"""Smoke test run in CI inside the Docker image: real OrcaSlicer, real presets.

Usage: python -m app.smoke
Failures are printed as GitHub Actions ::error:: annotations.
"""

from __future__ import annotations

import os
import sys
import tempfile
import time
import traceback
from pathlib import Path

import trimesh


def annotate(title: str, message: str) -> None:
    flat = message.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::error title={title}::{flat[:3500]}", flush=True)


def main() -> int:
    tmp = Path(tempfile.mkdtemp(prefix="pocketslicer-smoke-"))
    os.environ.setdefault("DATA_DIR", str(tmp / "data"))
    os.environ.setdefault("API_TOKEN", "smoke")

    from .config import load_settings
    from .meshes import ModelStore
    from .profiles import ProfileLibrary
    from .slicer import JobManager, SliceRequest

    settings = load_settings()
    failures = 0
    binary = Path(settings.orca_bin).parent / "bin" / "orca-slicer"
    if binary.exists():
        import subprocess

        ldd = subprocess.run(["ldd", str(binary)], capture_output=True, text=True).stdout
        missing = [l.strip() for l in ldd.splitlines() if "not found" in l]
        if missing:
            print("::warning title=missing libraries::" + "%0A".join(missing), flush=True)
    try:
        lib = ProfileLibrary(settings.orca_profiles)
        printers = lib.printers()
        print(f"{len(printers)} Bambu printer presets", flush=True)
        names = [p["name"] for p in printers]
        if settings.default_printer_preset not in names:
            annotate("presets", f"{settings.default_printer_preset} missing. Found: {names[:40]}")
            return 1
        opts = lib.options(settings.default_printer_preset)
        print(f"X1C: {len(opts['processes'])} processes, {len(opts['filaments'])} filaments; "
              f"defaults {opts['default_process']} / {opts['default_filament']}", flush=True)
        if not opts["processes"] or not opts["filaments"]:
            annotate("presets", "No compatible processes or filaments for the X1C")
            return 1
    except Exception:
        annotate("presets", traceback.format_exc())
        return 1

    models = ModelStore(settings.models_dir, settings.preview_max_faces)
    cube = tmp / "cube.stl"
    # A "T": a 20 mm cube with a 40 mm bar on top, so supports have an overhang to hold.
    base = trimesh.creation.box(extents=(20, 20, 20))
    bar = trimesh.creation.box(extents=(40, 8, 4))
    bar.apply_translation([0, 0, 12])
    trimesh.util.concatenate([base, bar]).export(str(cube))
    model = models.create(cube, "smoke cube.stl")
    manager = JobManager(settings, lib, models)

    petg = next((f["name"] for f in opts["filaments"] if f["type"] == "PETG"), opts["default_filament"])
    cases = [
        ("defaults", {}),
        ("supports+copies+cool plate", {"supports": True, "support_type": "tree", "copies": 3,
                                        "bed_type": "Cool Plate", "infill": 30, "brim": "none"}),
        ("rotate+scale+orient+petg", {"rotate_x": 90, "scale": 1.5, "auto_orient": True,
                                      "filament": petg, "wall_loops": 3}),
    ]
    for label, extra in cases:
        body = {
            "model_id": model["id"],
            "printer": settings.default_printer_preset,
            "process": opts["default_process"],
            "filament": opts["default_filament"],
            **extra,
        }
        started = time.time()
        job = manager.submit(SliceRequest(**body))
        while True:
            current = manager.get(job["id"])
            if current["status"] in ("done", "failed"):
                break
            time.sleep(0.5)
        took = time.time() - started
        if current["status"] != "done":
            failures += 1
            log = ""
            orca_log = settings.jobs_dir / job["id"] / "work" / "orca.log"
            if orca_log.exists():
                log = "\n".join(orca_log.read_text(errors="replace").splitlines()[-40:])
            annotate(f"slice {label}", f"{current['error']}\n--- orca.log tail ---\n{log}")
            print(f"FAIL {label} after {took:.1f}s: {current['error']}", flush=True)
            continue
        r = current["result"]
        print(f"OK   {label}: {r['print_time_s']}s, {r['weight_g']} g, {r['layers']} layers, "
              f"{r['output_bytes']} bytes, warnings={r['warnings']} ({took:.1f}s)", flush=True)
        if r["print_time_s"] <= 0 or r["weight_g"] <= 0:
            failures += 1
            annotate(f"slice {label}", f"Sliced but stats look empty: {r}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
