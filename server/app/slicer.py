"""Slicing jobs: run the OrcaSlicer CLI and read its results."""

from __future__ import annotations

import json
import logging
import os
import re
import select
import shutil
import subprocess
import threading
import time
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Callable
from xml.etree import ElementTree as ET

from pydantic import BaseModel, Field, field_validator

from .meshes import ModelStore, load_mesh, place_on_bed
from .profiles import ProfileLibrary, parse_printable_area

log = logging.getLogger("pocketslicer")

OUTPUT_NAME = "plate.gcode.3mf"

BRIM_TYPES = {"auto": "auto_brim", "none": "no_brim", "outer": "outer_only"}
SUPPORT_TYPES = {"tree": "tree(auto)", "normal": "normal(auto)"}


class SliceRequest(BaseModel):
    model_id: str
    printer: str
    process: str
    filament: str
    rotate_x: float = 0.0
    rotate_y: float = 0.0
    rotate_z: float = 0.0
    scale: float = Field(1.0, gt=0.001, le=50.0)
    copies: int = Field(1, ge=1, le=50)
    auto_orient: bool = False
    bed_type: str = "Textured PEI Plate"
    supports: bool | None = None
    support_type: str | None = None
    infill: int | None = Field(None, ge=0, le=100)
    brim: str | None = None
    wall_loops: int | None = Field(None, ge=1, le=20)

    @field_validator("support_type")
    @classmethod
    def _support_type(cls, v: str | None) -> str | None:
        if v is not None and v not in SUPPORT_TYPES:
            raise ValueError(f"support_type must be one of {sorted(SUPPORT_TYPES)}")
        return v

    @field_validator("brim")
    @classmethod
    def _brim(cls, v: str | None) -> str | None:
        if v is not None and v not in BRIM_TYPES:
            raise ValueError(f"brim must be one of {sorted(BRIM_TYPES)}")
        return v


class SliceError(Exception):
    pass


def apply_overrides(process: dict[str, Any], req: SliceRequest) -> dict[str, Any]:
    if req.supports is not None:
        process["enable_support"] = "1" if req.supports else "0"
    if req.support_type is not None:
        process["support_type"] = SUPPORT_TYPES[req.support_type]
    if req.infill is not None:
        process["sparse_infill_density"] = f"{req.infill}%"
    if req.brim is not None:
        process["brim_type"] = BRIM_TYPES[req.brim]
    if req.wall_loops is not None:
        process["wall_loops"] = str(req.wall_loops)
    return process


def parse_slice_info(data: bytes) -> dict[str, Any]:
    root = ET.fromstring(data)
    total_time = 0.0
    total_weight = 0.0
    filaments: list[dict[str, Any]] = []
    warnings: list[str] = []
    plates = 0
    for plate in root.iter("plate"):
        plates += 1
        meta = {m.get("key"): m.get("value") for m in plate.findall("metadata")}
        total_time += _num(meta.get("prediction"))
        total_weight += _num(meta.get("weight"))
        for f in plate.findall("filament"):
            filaments.append(
                {
                    "type": f.get("type") or "",
                    "color": f.get("color") or "",
                    "used_m": _num(f.get("used_m")),
                    "used_g": _num(f.get("used_g")),
                }
            )
        for w in plate.findall("warning"):
            msg = w.get("msg")
            if msg:
                warnings.append(msg)
    if not total_weight and filaments:
        total_weight = sum(f["used_g"] for f in filaments)
    return {
        "print_time_s": int(round(total_time)),
        "weight_g": round(total_weight, 2),
        "length_m": round(sum(f["used_m"] for f in filaments), 2),
        "filaments": filaments,
        "warnings": warnings,
        "plates": plates,
    }


def _num(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def read_result_3mf(path: Path) -> dict[str, Any]:
    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())
        if "Metadata/plate_1.gcode" not in names:
            raise SliceError("The slicer finished but produced no G-code.")
        stats: dict[str, Any] = {"print_time_s": 0, "weight_g": 0.0, "length_m": 0.0,
                                 "filaments": [], "warnings": [], "plates": 1}
        if "Metadata/slice_info.config" in names:
            stats = parse_slice_info(zf.read("Metadata/slice_info.config"))
        if not stats["print_time_s"]:
            stats["print_time_s"] = _time_from_gcode(zf.read("Metadata/plate_1.gcode"))
        stats["layers"] = _layers_from_gcode(zf, "Metadata/plate_1.gcode")
    return stats


_TIME_RE = re.compile(rb"total estimated time:\s*([0-9dhms ]+)", re.I)
_LAYERS_RE = re.compile(rb"total layer number:\s*(\d+)", re.I)


def _time_from_gcode(gcode: bytes) -> int:
    m = _TIME_RE.search(gcode[:20000])
    if not m:
        return 0
    seconds = 0
    for value, unit in re.findall(rb"(\d+)\s*([dhms])", m.group(1)):
        seconds += int(value) * {b"d": 86400, b"h": 3600, b"m": 60, b"s": 1}[unit]
    return seconds


def _layers_from_gcode(zf: zipfile.ZipFile, name: str) -> int:
    with zf.open(name) as fh:
        head = fh.read(20000)
    m = _LAYERS_RE.search(head)
    return int(m.group(1)) if m else 0


def _progress_from(line: str) -> tuple[float | None, str | None]:
    try:
        data = json.loads(line)
    except ValueError:
        return None, None
    if not isinstance(data, dict):
        return None, None
    pct = None
    for key in ("total_percent", "total_progress", "plate_percent", "percent", "progress"):
        if key in data:
            try:
                pct = float(data[key])
                break
            except (TypeError, ValueError):
                pass
    msg = data.get("message") or data.get("msg")
    return pct, (str(msg) if msg else None)


def run_orca(
    *,
    orca_bin: str,
    wrapper: list[str],
    work: Path,
    machine: dict[str, Any],
    process: dict[str, Any],
    filament: dict[str, Any],
    model_files: list[Path],
    arrange: bool,
    orient: bool,
    bed_type: str | None,
    timeout: int,
    on_progress: Callable[[float | None, str | None], None] | None = None,
) -> Path:
    """Run one CLI slice in `work`. Returns the path of the sliced .gcode.3mf."""
    out = work / "out"
    out.mkdir(parents=True, exist_ok=True)
    m_path, p_path, f_path = work / "machine.json", work / "process.json", work / "filament.json"
    m_path.write_text(json.dumps(machine, indent=1))
    p_path.write_text(json.dumps(process, indent=1))
    f_path.write_text(json.dumps(filament, indent=1))

    pipe = work / "progress.pipe"
    try:
        os.mkfifo(pipe)
    except (OSError, AttributeError):
        pipe = None  # type: ignore[assignment]

    cmd = [*wrapper, orca_bin, "--slice", "0",
           "--load-settings", f"{m_path};{p_path}",
           "--load-filaments", str(f_path),
           "--arrange", "1" if arrange else "0",
           "--orient", "1" if orient else "0",
           "--allow-newer-file",
           "--outputdir", str(out),
           "--export-3mf", OUTPUT_NAME]
    if bed_type:
        cmd += ["--curr-bed-type", bed_type]
    if pipe is not None:
        cmd += ["--pipe", str(pipe)]
    cmd += [str(p) for p in model_files]

    env = dict(os.environ)
    env.setdefault("HOME", str(work))
    env.setdefault("LANG", "C.UTF-8")
    env.setdefault("LC_ALL", "C.UTF-8")
    log.info("Running: %s", " ".join(cmd))

    fd = None
    if pipe is not None:
        fd = os.open(pipe, os.O_RDONLY | os.O_NONBLOCK)
    log_path = work / "orca.log"
    with open(log_path, "wb") as log_file:
        log_file.write(("$ " + " ".join(cmd) + "\n").encode())
        log_file.flush()
        proc = subprocess.Popen(cmd, cwd=out, stdout=log_file, stderr=subprocess.STDOUT, env=env)
        started = time.monotonic()
        buf = b""
        try:
            while proc.poll() is None:
                if time.monotonic() - started > timeout:
                    proc.kill()
                    proc.wait()
                    raise SliceError(f"Slicing took longer than {timeout // 60} minutes and was stopped.")
                if fd is None:
                    time.sleep(0.3)
                    continue
                ready, _, _ = select.select([fd], [], [], 0.3)
                if not ready:
                    continue
                chunk = os.read(fd, 65536)
                if not chunk:
                    time.sleep(0.2)
                    continue
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    pct, msg = _progress_from(line.decode("utf-8", "replace"))
                    if on_progress and (pct is not None or msg):
                        on_progress(pct, msg)
        finally:
            if fd is not None:
                os.close(fd)
    code = proc.returncode
    result_file = out / OUTPUT_NAME
    if code != 0 or not result_file.exists():
        raise SliceError(_explain_failure(out, log_path, code))
    return result_file


def _explain_failure(out: Path, log_path: Path, code: int | None) -> str:
    result_json = out / "result.json"
    if result_json.exists():
        try:
            data = json.loads(result_json.read_text())
            msg = data.get("error_string") or data.get("error") or ""
            if msg:
                return f"OrcaSlicer: {msg}"
        except ValueError:
            pass
    tail = ""
    try:
        lines = log_path.read_text(errors="replace").strip().splitlines()
        interesting = [l for l in lines if re.search(r"error|fail|invalid|not compatible|exception", l, re.I)]
        tail = "\n".join((interesting or lines)[-6:])
    except OSError:
        pass
    return f"OrcaSlicer stopped (exit code {code}). {tail}".strip()


class JobManager:
    def __init__(self, settings: Any, profiles: ProfileLibrary, models: ModelStore) -> None:
        self.settings = settings
        self.profiles = profiles
        self.models = models
        self.root: Path = settings.jobs_dir
        self.jobs: dict[str, dict[str, Any]] = {}
        self.lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=settings.slice_workers, thread_name_prefix="slice")
        self._load()

    def _load(self) -> None:
        for path in self.root.glob("*/job.json"):
            try:
                job = json.loads(path.read_text())
            except (OSError, ValueError):
                continue
            if job.get("status") in ("queued", "slicing"):
                job["status"] = "failed"
                job["error"] = "The server restarted before this slice finished."
            self.jobs[job["id"]] = job

    def _save(self, job: dict[str, Any]) -> None:
        folder = self.root / job["id"]
        folder.mkdir(parents=True, exist_ok=True)
        tmp = folder / "job.json.tmp"
        tmp.write_text(json.dumps(job))
        tmp.replace(folder / "job.json")

    def _update(self, job_id: str, **changes: Any) -> dict[str, Any]:
        with self.lock:
            job = self.jobs[job_id]
            job.update(changes)
            job["updated_at"] = time.time()
            self._save(job)
            return dict(job)

    def get(self, job_id: str) -> dict[str, Any] | None:
        with self.lock:
            job = self.jobs.get(job_id)
            return dict(job) if job else None

    def recent(self, limit: int = 20) -> list[dict[str, Any]]:
        with self.lock:
            jobs = sorted(self.jobs.values(), key=lambda j: j.get("created_at", 0), reverse=True)
            return [dict(j) for j in jobs[:limit]]

    def output_path(self, job_id: str) -> Path | None:
        job = self.get(job_id)
        if not job or job.get("status") != "done":
            return None
        path = self.root / job_id / "work" / "out" / OUTPUT_NAME
        return path if path.exists() else None

    def submit(self, req: SliceRequest) -> dict[str, Any]:
        model = self.models.info(req.model_id)
        # Validate presets up front so mistakes show immediately.
        self.profiles.resolve("machine", req.printer)
        self.profiles.resolve("process", req.process)
        self.profiles.resolve("filament", req.filament)
        job_id = uuid.uuid4().hex
        now = time.time()
        job = {
            "id": job_id,
            "model_id": req.model_id,
            "name": model["name"],
            "status": "queued",
            "progress": 0.0,
            "message": "Waiting to slice",
            "error": None,
            "created_at": now,
            "updated_at": now,
            "request": req.model_dump(),
            "result": None,
            "printed_at": None,
        }
        with self.lock:
            self.jobs[job_id] = job
            self._save(job)
        self.pool.submit(self._run, job_id, req)
        return dict(job)

    def _run(self, job_id: str, req: SliceRequest) -> None:
        work = self.root / job_id / "work"
        try:
            self._update(job_id, status="slicing", message="Preparing model", progress=2.0)
            machine = self.profiles.resolve("machine", req.printer)
            process = apply_overrides(self.profiles.resolve("process", req.process), req)
            filament = self.profiles.resolve("filament", req.filament)
            bed = parse_printable_area(machine.get("printable_area"))

            work.mkdir(parents=True, exist_ok=True)
            mesh = load_mesh(self.models.mesh_path(req.model_id))
            placed = place_on_bed(mesh, req.rotate_x, req.rotate_y, req.rotate_z, req.scale, bed)
            model_file = work / "model.stl"
            placed.export(str(model_file), file_type="stl")

            self._update(job_id, message="Slicing", progress=5.0)

            def progress(pct: float | None, msg: str | None) -> None:
                changes: dict[str, Any] = {}
                if pct is not None:
                    changes["progress"] = max(5.0, min(99.0, pct))
                if msg:
                    changes["message"] = msg[:120]
                if changes:
                    self._update(job_id, **changes)

            result = run_orca(
                orca_bin=self.settings.orca_bin,
                wrapper=self.settings.orca_wrapper,
                work=work,
                machine=machine,
                process=process,
                filament=filament,
                model_files=[model_file] * req.copies,
                arrange=req.copies > 1 or req.auto_orient,
                orient=req.auto_orient,
                bed_type=req.bed_type if self.settings.orca_bed_type_flag else None,
                timeout=self.settings.slice_timeout,
                on_progress=progress,
            )
            stats = read_result_3mf(result)
            stats["output_bytes"] = result.stat().st_size
            self._update(job_id, status="done", progress=100.0, message="Ready to print", result=stats)
        except Exception as exc:  # report every failure to the app
            log.exception("Slice %s failed", job_id)
            self._update(job_id, status="failed", message="Slicing failed", error=str(exc)[:2000])

    def mark_printed(self, job_id: str) -> None:
        self._update(job_id, printed_at=time.time())

    def cleanup(self, max_age_s: float) -> int:
        removed = 0
        now = time.time()
        with self.lock:
            for job_id, job in list(self.jobs.items()):
                if job.get("status") in ("queued", "slicing"):
                    continue
                if now - job.get("created_at", now) > max_age_s:
                    shutil.rmtree(self.root / job_id, ignore_errors=True)
                    del self.jobs[job_id]
                    removed += 1
        return removed
