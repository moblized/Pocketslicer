"""HTTP API for the PocketSlicer app."""

from __future__ import annotations

import hmac
import logging
import tempfile
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from . import __version__
from .config import load_settings
from .meshes import ModelError, ModelStore, safe_stem
from .printer import PrinterError, PrinterLink
from .profiles import ProfileError, ProfileLibrary
from .slicer import JobManager, SliceRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("pocketslicer")

settings = load_settings()

_state: dict[str, Any] = {"profiles": None, "profiles_error": None}
_state_lock = threading.Lock()
models = ModelStore(settings.models_dir, settings.preview_max_faces)
printer = (
    PrinterLink(settings.printer_ip, settings.printer_serial, settings.printer_access_code, settings.print_url_style)
    if settings.printer_configured
    else None
)


def profiles() -> ProfileLibrary:
    with _state_lock:
        if _state["profiles"] is None:
            try:
                _state["profiles"] = ProfileLibrary(settings.orca_profiles)
                _state["profiles_error"] = None
            except ProfileError as exc:
                _state["profiles_error"] = str(exc)
                raise HTTPException(503, f"OrcaSlicer profiles unavailable: {exc}") from exc
        return _state["profiles"]


_jobs: JobManager | None = None


def jobs() -> JobManager:
    global _jobs
    if _jobs is None:
        _jobs = JobManager(settings, profiles(), models)
    return _jobs


def require_token(request: Request) -> None:
    header = request.headers.get("authorization", "")
    token = header[7:].strip() if header.lower().startswith("bearer ") else ""
    if not token:
        token = request.query_params.get("token", "")
    if not token or not hmac.compare_digest(token, settings.api_token):
        raise HTTPException(401, "Missing or wrong API token")


auth = [Depends(require_token)]


def _cleanup_loop() -> None:
    while True:
        max_age = settings.retention_days * 86400
        try:
            removed = models.cleanup(max_age)
            if _jobs is not None:
                removed += _jobs.cleanup(max_age)
            if removed:
                log.info("Cleaned up %d old models/jobs", removed)
        except Exception:
            log.exception("Cleanup failed")
        time.sleep(6 * 3600)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    threading.Thread(target=_cleanup_loop, daemon=True, name="cleanup").start()
    try:
        profiles()
        jobs()
        log.info("Loaded OrcaSlicer presets from %s", settings.orca_profiles)
    except HTTPException as exc:
        log.error("%s", exc.detail)
    yield
    if printer is not None:
        printer.close()


app = FastAPI(title="PocketSlicer server", version=__version__, lifespan=lifespan)


# ---------- health & presets ----------


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {
        "ok": _state["profiles_error"] is None,
        "version": __version__,
        "orca_version": settings.orca_version,
        "printer_configured": settings.printer_configured,
        "printer_name": settings.printer_name,
        "default_printer": settings.default_printer_preset,
        "error": _state["profiles_error"],
    }


@app.get("/api/printers", dependencies=auth)
def list_printers() -> dict[str, Any]:
    lib = profiles()
    printers = lib.printers()
    names = {p["name"] for p in printers}
    default = settings.default_printer_preset if settings.default_printer_preset in names else (
        printers[0]["name"] if printers else ""
    )
    return {"printers": printers, "default": default}


@app.get("/api/printers/{name}/options", dependencies=auth)
def printer_options(name: str) -> dict[str, Any]:
    try:
        return profiles().options(name)
    except ProfileError as exc:
        raise HTTPException(404, str(exc)) from exc


# ---------- models ----------


@app.post("/api/models", dependencies=auth)
async def upload_model(file: UploadFile = File(...)) -> dict[str, Any]:
    limit = settings.max_upload_mb * 1024 * 1024
    tmp = Path(tempfile.mkstemp(dir=settings.models_dir, suffix=".upload")[1])
    size = 0
    try:
        with open(tmp, "wb") as out:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > limit:
                    raise HTTPException(413, f"File is bigger than {settings.max_upload_mb} MB")
                out.write(chunk)
        try:
            return models.create(tmp, file.filename or "model.stl")
        except ModelError as exc:
            raise HTTPException(400, str(exc)) from exc
    finally:
        tmp.unlink(missing_ok=True)


@app.get("/api/models/{model_id}", dependencies=auth)
def model_info(model_id: str) -> dict[str, Any]:
    try:
        return models.info(model_id)
    except ModelError as exc:
        raise HTTPException(404, str(exc)) from exc


@app.get("/api/models/{model_id}/preview.stl", dependencies=auth)
def model_preview(model_id: str) -> FileResponse:
    try:
        return FileResponse(models.preview_path(model_id), media_type="model/stl", filename="preview.stl")
    except ModelError as exc:
        raise HTTPException(404, str(exc)) from exc


# ---------- slicing ----------


@app.post("/api/jobs", dependencies=auth)
def create_job(req: SliceRequest) -> dict[str, Any]:
    try:
        return jobs().submit(req)
    except (ModelError, ProfileError) as exc:
        raise HTTPException(400, str(exc)) from exc


@app.get("/api/jobs", dependencies=auth)
def list_jobs(limit: int = 20) -> dict[str, Any]:
    return {"jobs": jobs().recent(max(1, min(limit, 100)))}


@app.get("/api/jobs/{job_id}", dependencies=auth)
def get_job(job_id: str) -> dict[str, Any]:
    job = jobs().get(job_id)
    if not job:
        raise HTTPException(404, "Unknown job")
    return job


@app.get("/api/jobs/{job_id}/file", dependencies=auth)
def job_file(job_id: str) -> FileResponse:
    path = jobs().output_path(job_id)
    job = jobs().get(job_id)
    if path is None or job is None:
        raise HTTPException(404, "This slice has no file yet")
    return FileResponse(path, media_type="application/octet-stream", filename=f"{job['name']}.gcode.3mf")


# ---------- printer ----------


class PrintRequest(BaseModel):
    ams_slot: int | None = Field(None, ge=0, le=255)
    timelapse: bool = False
    bed_leveling: bool = True
    flow_cali: bool = False


def _printer() -> PrinterLink:
    if printer is None:
        raise HTTPException(
            409, "No printer set up on the server. Add PRINTER_IP, PRINTER_SERIAL and PRINTER_ACCESS_CODE."
        )
    return printer


@app.get("/api/printer/status", dependencies=auth)
def printer_status() -> dict[str, Any]:
    if printer is None:
        return {"configured": False, "connected": False, "name": settings.printer_name}
    try:
        status = printer.status()
        return {"configured": True, "connected": True, "name": settings.printer_name, **status}
    except PrinterError as exc:
        return {"configured": True, "connected": False, "name": settings.printer_name, "error": str(exc)}


@app.post("/api/jobs/{job_id}/print", dependencies=auth)
def print_job(job_id: str, req: PrintRequest) -> dict[str, Any]:
    link = _printer()
    manager = jobs()
    job = manager.get(job_id)
    path = manager.output_path(job_id)
    if job is None or path is None:
        raise HTTPException(404, "Slice this model first")
    task = safe_stem(job["name"], "pocketslicer")
    try:
        result = link.start_print(
            path,
            task,
            ams_slot=req.ams_slot,
            timelapse=req.timelapse,
            bed_leveling=req.bed_leveling,
            flow_cali=req.flow_cali,
        )
    except PrinterError as exc:
        raise HTTPException(502, str(exc)) from exc
    manager.mark_printed(job_id)
    return {"ok": True, **result}

