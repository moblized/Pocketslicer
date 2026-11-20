"""OrcaSlicer system presets: indexing, inheritance flattening and compatibility.

The OrcaSlicer CLI does not reliably follow `inherits` chains, so every preset is
flattened here (parent first, child overrides) before it is handed to the CLI.
"""

from __future__ import annotations

import copy
import json
import re
import threading
from pathlib import Path
from typing import Any

TYPES = ("machine", "process", "filament")

# Keys that describe the preset itself rather than settings. Taken from the leaf only.
_LEAF_ONLY = ("name", "setting_id", "filament_id", "instantiation", "description")

BED_TYPES = [
    {"key": "Textured PEI Plate", "label": "Textured PEI Plate"},
    {"key": "Cool Plate", "label": "Smooth Cool Plate"},
    {"key": "Supertack Plate", "label": "Cool Plate (SuperTack)"},
    {"key": "Engineering Plate", "label": "Engineering Plate"},
    {"key": "High Temp Plate", "label": "Smooth High Temp Plate"},
    {"key": "Textured Cool Plate", "label": "Textured Cool Plate"},
]


class ProfileError(Exception):
    pass


def _is_nil(value: Any) -> bool:
    if value == "nil":
        return True
    if isinstance(value, list) and value and all(v == "nil" for v in value):
        return True
    return False


def _first(value: Any, default: Any = None) -> Any:
    if isinstance(value, list):
        return value[0] if value else default
    return value if value is not None else default


def _float(value: Any, default: float = 0.0) -> float:
    try:
        return float(str(_first(value, default)).rstrip("%"))
    except (TypeError, ValueError):
        return default


def parse_printable_area(value: Any) -> tuple[float, float, float, float]:
    """Return (min_x, min_y, max_x, max_y) from Orca's ["0x0", "256x0", ...] list."""
    points = []
    for item in value or []:
        try:
            x, y = str(item).lower().split("x")
            points.append((float(x), float(y)))
        except ValueError:
            continue
    if not points:
        return (0.0, 0.0, 256.0, 256.0)
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return (min(xs), min(ys), max(xs), max(ys))


_ATOM = re.compile(r"^\s*([a-z_]+)(?:\[(\d+)\])?\s*(=~|!~|==|!=)\s*(.+?)\s*$")


def condition_matches(condition: str, machine: dict[str, Any]) -> bool | None:
    """Evaluate a simple compatible_printers_condition. None means it can't be parsed."""
    condition = (condition or "").strip()
    if not condition:
        return True
    if " or " in condition or "(" in condition.replace("[", "").replace("]", ""):
        return None
    for atom in re.split(r"\s+and\s+", condition):
        m = _ATOM.match(atom)
        if not m:
            return None
        key, index, op, rhs = m.groups()
        raw = machine.get(key)
        if raw is None:
            return False
        if isinstance(raw, list):
            raw = raw[int(index or 0)] if raw else ""
        value = str(raw)
        if op in ("=~", "!~"):
            rm = re.match(r"^/(.*)/[a-z]*$", rhs)
            if not rm:
                return None
            found = re.search(rm.group(1), value) is not None
            ok = found if op == "=~" else not found
        else:
            rhs_clean = rhs.strip("\"'")
            try:
                equal = float(value) == float(rhs_clean)
            except ValueError:
                equal = value == rhs_clean
            ok = equal if op == "==" else not equal
        if not ok:
            return False
    return True


class ProfileLibrary:
    """Index of one vendor's system presets plus shared fallback libraries."""

    def __init__(
        self,
        root: Path,
        vendor: str = "BBL",
        fallback_vendors: tuple[str, ...] = ("OrcaFilamentLibrary",),
    ) -> None:
        self.root = Path(root)
        self.vendor = vendor
        self.fallback_vendors = fallback_vendors
        self._index: dict[tuple[str, str], dict[str, Path]] = {}
        self._raw: dict[Path, dict[str, Any]] = {}
        self._resolved: dict[tuple[str, str], dict[str, Any]] = {}
        self._options: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()
        self._scan()

    # ---------- indexing ----------

    def _scan(self) -> None:
        if not self.root.is_dir():
            raise ProfileError(f"Profiles folder not found: {self.root}")
        for vendor in (self.vendor, *self.fallback_vendors):
            vdir = self.root / vendor
            if not vdir.is_dir():
                continue
            for type_ in TYPES:
                tdir = vdir / type_
                if not tdir.is_dir():
                    continue
                names: dict[str, Path] = {}
                for path in sorted(tdir.rglob("*.json")):
                    data = self._read(path)
                    if data is None:
                        continue
                    name = data.get("name")
                    if isinstance(name, str) and name:
                        names.setdefault(name, path)
                self._index[(vendor, type_)] = names
        if not self._index.get((self.vendor, "machine")):
            raise ProfileError(f"No {self.vendor} printer presets found in {self.root}")

    def _read(self, path: Path) -> dict[str, Any] | None:
        cached = self._raw.get(path)
        if cached is not None:
            return cached
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if not isinstance(data, dict):
            return None
        self._raw[path] = data
        return data

    def _find(self, type_: str, name: str, prefer: str) -> tuple[str, Path] | tuple[None, None]:
        order = [prefer, self.vendor, *self.fallback_vendors]
        seen = set()
        for vendor in order:
            if vendor in seen:
                continue
            seen.add(vendor)
            path = self._index.get((vendor, type_), {}).get(name)
            if path is not None:
                return vendor, path
        return None, None

    def names(self, type_: str, vendor: str | None = None) -> list[str]:
        return list(self._index.get((vendor or self.vendor, type_), {}).keys())

    # ---------- flattening ----------

    def resolve(self, type_: str, name: str) -> dict[str, Any]:
        """Return a fully flattened copy of a preset, ready for the CLI."""
        key = (type_, name)
        with self._lock:
            if key not in self._resolved:
                self._resolved[key] = self._flatten(type_, name)
            return copy.deepcopy(self._resolved[key])

    def _flatten(self, type_: str, name: str) -> dict[str, Any]:
        vendor, path = self._find(type_, name, self.vendor)
        if path is None:
            raise ProfileError(f"{type_} preset not found: {name}")
        leaf = self._read(path) or {}
        chain = [leaf]
        seen = {name}
        parent = leaf.get("inherits")
        current_vendor = vendor
        while parent:
            if parent in seen:
                raise ProfileError(f"Inheritance loop at {parent} while resolving {name}")
            seen.add(parent)
            parent_vendor, parent_path = self._find(type_, parent, current_vendor)
            if parent_path is None:
                raise ProfileError(f"Parent preset '{parent}' of '{name}' not found")
            data = self._read(parent_path) or {}
            chain.append(data)
            current_vendor = parent_vendor
            parent = data.get("inherits")

        merged: dict[str, Any] = {}
        for data in reversed(chain):
            for k, v in data.items():
                if k in _LEAF_ONLY:
                    continue
                merged[k] = copy.deepcopy(v)
        for k in _LEAF_ONLY:
            if k in leaf:
                merged[k] = copy.deepcopy(leaf[k])
        merged["name"] = name
        merged["type"] = type_
        merged["from"] = "system"
        merged["instantiation"] = "true"
        merged.pop("inherits", None)
        merged.pop("inherits_group", None)
        return {k: v for k, v in merged.items() if not _is_nil(v)}

    def _instantiable(self, type_: str, vendor: str) -> list[str]:
        out = []
        for name, path in self._index.get((vendor, type_), {}).items():
            raw = self._read(path) or {}
            if str(raw.get("instantiation", "true")).lower() == "true":
                out.append(name)
        return out

    # ---------- listings ----------

    def printers(self) -> list[dict[str, Any]]:
        result = []
        for name in sorted(self._instantiable("machine", self.vendor)):
            try:
                m = self.resolve("machine", name)
            except ProfileError:
                continue
            result.append(self.printer_info(m))
        return result

    @staticmethod
    def printer_info(machine: dict[str, Any]) -> dict[str, Any]:
        min_x, min_y, max_x, max_y = parse_printable_area(machine.get("printable_area"))
        return {
            "name": machine["name"],
            "model": str(_first(machine.get("printer_model"), "")),
            "nozzle": _float(machine.get("nozzle_diameter"), 0.4),
            "bed": {
                "min_x": min_x,
                "min_y": min_y,
                "max_x": max_x,
                "max_y": max_y,
                "height": _float(machine.get("printable_height"), 250.0),
            },
            "default_process": str(_first(machine.get("default_print_profile"), "")),
            "default_filament": str(_first(machine.get("default_filament_profile"), "")),
        }

    def _compatible(self, preset: dict[str, Any], machine: dict[str, Any]) -> bool:
        printers = preset.get("compatible_printers") or []
        if isinstance(printers, str):
            printers = [printers]
        if printers:
            return machine["name"] in printers
        result = condition_matches(str(preset.get("compatible_printers_condition", "")), machine)
        return bool(result)

    def options(self, printer: str) -> dict[str, Any]:
        """Processes and filaments compatible with one printer preset."""
        with self._lock:
            if printer in self._options:
                return copy.deepcopy(self._options[printer])
        machine = self.resolve("machine", printer)
        info = self.printer_info(machine)

        processes = []
        for name in self._instantiable("process", self.vendor):
            try:
                p = self.resolve("process", name)
            except ProfileError:
                continue
            if self._compatible(p, machine):
                processes.append(
                    {
                        "name": name,
                        "layer_height": _float(p.get("layer_height"), 0.2),
                        "infill": int(round(_float(p.get("sparse_infill_density"), 15))),
                        "supports": str(_first(p.get("enable_support"), "0")) == "1",
                        "walls": int(_float(p.get("wall_loops"), 2)),
                        "brim": {"no_brim": "none", "outer_only": "outer"}.get(
                            str(_first(p.get("brim_type"), "auto_brim")), "auto"
                        ),
                    }
                )
        processes.sort(key=lambda p: (p["layer_height"], p["name"]))

        filaments = []
        for vendor in (self.vendor, *self.fallback_vendors):
            for name in self._instantiable("filament", vendor):
                found_vendor, _ = self._find("filament", name, vendor)
                if found_vendor != vendor:
                    continue
                try:
                    f = self.resolve("filament", name)
                except ProfileError:
                    continue
                if not self._compatible(f, machine):
                    continue
                filaments.append(
                    {
                        "name": name,
                        "type": str(_first(f.get("filament_type"), "")),
                        "vendor": str(_first(f.get("filament_vendor"), "")),
                        "color": str(_first(f.get("default_filament_colour"), "") or ""),
                    }
                )
        seen: set[str] = set()
        unique = []
        for f in filaments:
            if f["name"] not in seen:
                seen.add(f["name"])
                unique.append(f)

        def filament_order(f: dict[str, Any]) -> tuple[int, str]:
            vendor = f["vendor"].lower()
            rank = 0 if vendor.startswith("bambu") else 1 if vendor == "generic" else 2
            return (rank, f["name"].lower())

        unique.sort(key=filament_order)

        names = {p["name"] for p in processes}
        default_process = info["default_process"] if info["default_process"] in names else (
            next((p["name"] for p in processes if "Standard" in p["name"]), processes[0]["name"] if processes else "")
        )
        fil_names = {f["name"] for f in unique}
        default_filament = info["default_filament"] if info["default_filament"] in fil_names else (
            unique[0]["name"] if unique else ""
        )
        result = {
            "printer": info,
            "processes": processes,
            "filaments": unique,
            "bed_types": BED_TYPES,
            "default_process": default_process,
            "default_filament": default_filament,
            "default_bed_type": "Textured PEI Plate",
        }
        with self._lock:
            self._options[printer] = result
        return copy.deepcopy(result)
