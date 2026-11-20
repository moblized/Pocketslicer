"""Uploaded models: loading, preview meshes and print-ready transforms."""

from __future__ import annotations

import json
import logging
import math
import re
import shutil
import time
import uuid
from pathlib import Path
from typing import Any

import numpy as np
import trimesh

log = logging.getLogger("pocketslicer")

SUPPORTED = (".stl", ".3mf", ".obj")


class ModelError(Exception):
    pass


def safe_stem(filename: str, fallback: str = "model") -> str:
    stem = Path(filename or "").stem
    if stem.lower().endswith(".gcode"):
        stem = stem[:-6]
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("._-")
    return (stem or fallback)[:60]


def load_mesh(path: Path) -> trimesh.Trimesh:
    try:
        loaded = trimesh.load(str(path), force="mesh", process=False)
    except Exception as exc:  # trimesh raises many exception types
        raise ModelError(f"Couldn't read this file: {exc}") from exc
    if isinstance(loaded, trimesh.Scene):
        geoms = [g for g in loaded.dump() if isinstance(g, trimesh.Trimesh)]
        if not geoms:
            raise ModelError("The file has no 3D geometry.")
        loaded = trimesh.util.concatenate(geoms)
    if not isinstance(loaded, trimesh.Trimesh) or len(loaded.faces) == 0:
        raise ModelError("The file has no 3D geometry.")
    loaded.remove_unreferenced_vertices()
    return loaded


def make_preview(mesh: trimesh.Trimesh, max_faces: int) -> trimesh.Trimesh:
    if len(mesh.faces) <= max_faces:
        return mesh
    try:
        return mesh.simplify_quadric_decimation(face_count=max_faces)
    except Exception as exc:  # optional dependency or decimation failure
        log.warning("Preview decimation failed (%s); sending full mesh", exc)
        return mesh


def rotation_matrix(rx: float, ry: float, rz: float) -> np.ndarray:
    """4x4 rotation applying X, then Y, then Z (degrees). Must match the app."""
    return trimesh.transformations.euler_matrix(
        math.radians(rx), math.radians(ry), math.radians(rz), "sxyz"
    )


def place_on_bed(
    mesh: trimesh.Trimesh,
    rx: float,
    ry: float,
    rz: float,
    scale: float,
    bed: tuple[float, float, float, float],
) -> trimesh.Trimesh:
    """Rotate, scale, then centre on the bed with the lowest point at Z=0."""
    out = mesh.copy()
    out.apply_transform(rotation_matrix(rx, ry, rz))
    if scale != 1.0:
        out.apply_scale(scale)
    lo, hi = out.bounds
    cx = (bed[0] + bed[2]) / 2
    cy = (bed[1] + bed[3]) / 2
    out.apply_translation([cx - (lo[0] + hi[0]) / 2, cy - (lo[1] + hi[1]) / 2, -lo[2]])
    return out


class ModelStore:
    def __init__(self, root: Path, preview_max_faces: int) -> None:
        self.root = root
        self.preview_max_faces = preview_max_faces

    def dir(self, model_id: str) -> Path:
        if not re.fullmatch(r"[a-f0-9]{32}", model_id or ""):
            raise ModelError("Unknown model")
        return self.root / model_id

    def create(self, upload_path: Path, original_name: str) -> dict[str, Any]:
        ext = Path(original_name).suffix.lower()
        if ext not in SUPPORTED:
            raise ModelError("Use an STL, 3MF or OBJ file.")
        model_id = uuid.uuid4().hex
        folder = self.root / model_id
        folder.mkdir(parents=True)
        source = folder / f"source{ext}"
        shutil.move(str(upload_path), source)
        try:
            mesh = load_mesh(source)
            mesh.export(str(folder / "mesh.stl"), file_type="stl")
            preview = make_preview(mesh, self.preview_max_faces)
            preview.export(str(folder / "preview.stl"), file_type="stl")
        except ModelError:
            _rmtree(folder)
            raise
        except Exception as exc:
            _rmtree(folder)
            raise ModelError(f"Couldn't prepare this model: {exc}") from exc
        extents = [float(round(v, 3)) for v in mesh.extents]
        info = {
            "id": model_id,
            "name": safe_stem(original_name),
            "file_name": Path(original_name).name,
            "created_at": time.time(),
            "size": {"x": extents[0], "y": extents[1], "z": extents[2]},
            "faces": int(len(mesh.faces)),
            "preview_faces": int(len(preview.faces)),
            "watertight": bool(mesh.is_watertight),
        }
        (folder / "model.json").write_text(json.dumps(info))
        return info

    def info(self, model_id: str) -> dict[str, Any]:
        path = self.dir(model_id) / "model.json"
        if not path.exists():
            raise ModelError("Unknown model")
        return json.loads(path.read_text())

    def mesh_path(self, model_id: str) -> Path:
        path = self.dir(model_id) / "mesh.stl"
        if not path.exists():
            raise ModelError("Unknown model")
        return path

    def preview_path(self, model_id: str) -> Path:
        path = self.dir(model_id) / "preview.stl"
        if not path.exists():
            raise ModelError("Unknown model")
        return path

    def cleanup(self, max_age_s: float) -> int:
        removed = 0
        now = time.time()
        for folder in self.root.iterdir():
            if folder.is_dir() and now - folder.stat().st_mtime > max_age_s:
                _rmtree(folder)
                removed += 1
        return removed


def _rmtree(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)
