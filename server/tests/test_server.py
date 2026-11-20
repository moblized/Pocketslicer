import importlib
import json
import os
import stat
import sys
import time
from pathlib import Path

import numpy as np
import pytest
import trimesh

HERE = Path(__file__).parent
FIXTURES = HERE / "fixtures" / "profiles"
X1C = "Bambu Lab X1 Carbon 0.4 nozzle"


# ---------- profiles ----------


def test_flatten_merges_chain_and_strips_nil():
    from app.profiles import ProfileLibrary

    lib = ProfileLibrary(FIXTURES)
    m = lib.resolve("machine", X1C)
    assert m["printable_area"] == ["0x0", "256x0", "256x256", "0x256"]  # child overrides parent
    assert m["retraction_length"] == ["0.8"]  # inherited from grandparent
    assert m["setting_id"] == "GM001"
    assert "inherits" not in m and m["from"] == "system" and m["instantiation"] == "true"

    f = lib.resolve("filament", "Bambu PLA Basic @BBL X1C")
    assert f["nozzle_temperature"] == ["220"]
    assert "filament_retraction_length" not in f  # "nil" removed
    assert "filament_id" not in f or f["filament_id"] != "GFA00"  # leaf-only key not inherited


def test_fallback_library_resolves_parent_in_its_own_vendor():
    from app.profiles import ProfileLibrary

    lib = ProfileLibrary(FIXTURES)
    petg = lib.resolve("filament", "Generic PETG @System")
    assert petg.get("orca_lib_marker") == "1"
    assert petg["filament_type"] == ["PETG"]


def test_options_for_x1c():
    from app.profiles import ProfileLibrary

    lib = ProfileLibrary(FIXTURES)
    opts = lib.options(X1C)
    assert [p["name"] for p in opts["processes"]] == ["0.12mm Fine @BBL X1C", "0.20mm Standard @BBL X1C"]
    assert opts["processes"][1] == {"name": "0.20mm Standard @BBL X1C", "layer_height": 0.2, "infill": 15,
                                    "supports": False, "walls": 2, "brim": "auto"}
    names = [f["name"] for f in opts["filaments"]]
    assert names[0] == "Bambu PLA Basic @BBL X1C"
    assert "Generic ABS @BBL" in names  # condition matched
    assert "Generic PETG @System" in names  # compatible with everything
    assert opts["default_process"] == "0.20mm Standard @BBL X1C"
    assert opts["default_filament"] == "Bambu PLA Basic @BBL X1C"
    assert opts["printer"]["bed"]["max_x"] == 256

    p1s = lib.options("Bambu Lab P1S 0.4 nozzle")
    assert "Generic ABS @BBL" not in [f["name"] for f in p1s["filaments"]]


def test_condition_matching():
    from app.profiles import condition_matches

    machine = {"printer_model": "Bambu Lab X1 Carbon", "nozzle_diameter": ["0.4"]}
    assert condition_matches("printer_model=~/.*X1.*/", machine) is True
    assert condition_matches("printer_model!~/.*X1.*/", machine) is False
    assert condition_matches("nozzle_diameter[0]==0.4", machine) is True
    assert condition_matches("nozzle_diameter[0]!=0.4", machine) is False
    assert condition_matches("a or b", machine) is None
    assert condition_matches("", machine) is True


# ---------- meshes ----------


def test_place_on_bed_rotation_and_centering():
    from app.meshes import place_on_bed

    box = trimesh.creation.box(extents=(10, 20, 30))
    placed = place_on_bed(box, 90, 0, 0, 2.0, (0, 0, 256, 256))
    lo, hi = placed.bounds
    ext = hi - lo
    assert np.allclose(ext, [20, 60, 40])  # X-rotation swaps Y and Z, then scale 2
    assert np.isclose(lo[2], 0)
    assert np.allclose((lo[:2] + hi[:2]) / 2, [128, 128])


def test_safe_stem():
    from app.meshes import safe_stem

    assert safe_stem("Water holder (v2).stl") == "Water_holder_v2"
    assert safe_stem("x.gcode.3mf") == "x"
    assert safe_stem("???.stl") == "model"


# ---------- slice results ----------


def test_parse_slice_info():
    from app.slicer import parse_slice_info

    xml = b"""<config><plate><metadata key="prediction" value="3600"/><metadata key="weight" value="12.5"/>
    <filament id="1" type="PLA" color="#000000" used_m="4.2" used_g="12.5"/>
    <warning msg="Floating regions" level="1"/></plate></config>"""
    stats = parse_slice_info(xml)
    assert stats["print_time_s"] == 3600
    assert stats["weight_g"] == 12.5
    assert stats["length_m"] == 4.2
    assert stats["warnings"] == ["Floating regions"]


def test_apply_overrides():
    from app.slicer import SliceRequest, apply_overrides

    req = SliceRequest(model_id="a" * 32, printer=X1C, process="p", filament="f",
                       supports=True, support_type="tree", infill=30, brim="none", wall_loops=3)
    out = apply_overrides({}, req)
    assert out == {"enable_support": "1", "support_type": "tree(auto)", "sparse_infill_density": "30%",
                   "brim_type": "no_brim", "wall_loops": "3"}
    with pytest.raises(Exception):
        SliceRequest(model_id="x", printer="a", process="b", filament="c", brim="huge")


# ---------- printer protocol ----------


def test_print_command():
    from app.printer import build_print_command

    cmd = build_print_command(sequence_id="7", filename="part.gcode.3mf", task_name="part", md5="abc",
                              url_style="ftp", ams_slot=5, timelapse=False, bed_leveling=True,
                              flow_cali=False, vibration_cali=False)["print"]
    assert cmd["command"] == "project_file"
    assert cmd["url"] == "ftp://part.gcode.3mf"
    assert cmd["param"] == "Metadata/plate_1.gcode"
    assert cmd["use_ams"] is True and cmd["ams_mapping"] == [5]

    ext = build_print_command(sequence_id="8", filename="a.gcode.3mf", task_name="a", md5="", url_style="sdcard",
                              ams_slot=None, timelapse=True, bed_leveling=False, flow_cali=True,
                              vibration_cali=False)["print"]
    assert ext["url"] == "file:///sdcard/a.gcode.3mf"
    assert ext["use_ams"] is False and ext["ams_mapping"] == []


def test_normalize_status():
    from app.printer import deep_merge, normalize_status

    report = {"print": {"gcode_state": "RUNNING", "mc_percent": 42, "mc_remaining_time": 17,
                        "ams": {"tray_now": "1", "ams": [{"id": "0", "humidity": "4", "tray": [
                            {"id": "0", "tray_type": "PLA", "tray_color": "FF0000FF", "tray_sub_brands": "PLA Basic", "remain": 80},
                            {"id": "1"}]}]},
                        "vt_tray": {"tray_type": "PETG", "tray_color": "00FF00FF"}}}
    deep_merge(report, {"print": {"mc_percent": 43}})
    s = normalize_status(report)
    assert s["state"] == "RUNNING" and s["progress"] == 43
    assert s["ams"][0]["humidity"] == "4"
    trays = s["ams"][0]["trays"]
    assert trays[0] == {"slot": 0, "index": 0, "loaded": True, "type": "PLA", "name": "PLA Basic",
                        "color": "#FF0000", "remain": 80}
    assert trays[1]["loaded"] is False and trays[1]["slot"] == 1
    assert s["external"]["color"] == "#00FF00"
    assert s["tray_now"] == 1


# ---------- full API with a fake slicer ----------


@pytest.fixture()
def client(tmp_path, monkeypatch):
    fake = tmp_path / "orca"
    fake.write_text(f"#!/bin/sh\nexec {sys.executable} {HERE / 'fake_orca.py'} \"$@\"\n")
    fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
    monkeypatch.setenv("DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("ORCA_BIN", str(fake))
    monkeypatch.setenv("ORCA_PROFILES", str(FIXTURES))
    monkeypatch.setenv("API_TOKEN", "secret")
    monkeypatch.setenv("FAKE_ORCA_LOG", str(tmp_path / "orca_args.json"))
    for key in ("PRINTER_IP", "PRINTER_SERIAL", "PRINTER_ACCESS_CODE"):
        monkeypatch.delenv(key, raising=False)
    import app.main

    importlib.reload(app.main)
    from fastapi.testclient import TestClient

    with TestClient(app.main.app) as c:
        c.headers["Authorization"] = "Bearer secret"
        c.tmp = tmp_path  # type: ignore[attr-defined]
        yield c


def _upload_cube(client, name="cube.stl"):
    path = client.tmp / name
    trimesh.creation.box(extents=(20, 20, 20)).export(str(path))
    with open(path, "rb") as fh:
        r = client.post("/api/models", files={"file": (name, fh, "application/octet-stream")})
    assert r.status_code == 200, r.text
    return r.json()


def _wait(client, job_id, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        job = client.get(f"/api/jobs/{job_id}").json()
        if job["status"] in ("done", "failed"):
            return job
        time.sleep(0.1)
    raise AssertionError("job did not finish")


def test_auth_required(client):
    assert client.get("/api/health").status_code == 200
    r = client.get("/api/printers", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401


def test_full_slice_flow(client):
    printers = client.get("/api/printers").json()
    assert printers["default"] == X1C
    opts = client.get(f"/api/printers/{X1C}/options").json()
    model = _upload_cube(client)
    assert model["size"] == {"x": 20.0, "y": 20.0, "z": 20.0}
    preview = client.get(f"/api/models/{model['id']}/preview.stl")
    assert preview.status_code == 200 and len(preview.content) == 84 + 50 * 12

    body = {"model_id": model["id"], "printer": X1C, "process": opts["default_process"],
            "filament": opts["default_filament"], "copies": 3, "rotate_x": 90, "supports": True,
            "infill": 25, "bed_type": "Cool Plate"}
    job = client.post("/api/jobs", json=body).json()
    done = _wait(client, job["id"])
    assert done["status"] == "done", done
    assert done["result"]["print_time_s"] == 1234 * 3
    assert done["result"]["weight_g"] == 16.5
    assert done["result"]["layers"] == 100
    assert done["result"]["warnings"] == ["fake warning"]

    args = json.loads((client.tmp / "orca_args.json").read_text())["argv"]
    assert args[args.index("--arrange") + 1] == "1"
    assert args[args.index("--curr-bed-type") + 1] == "Cool Plate"
    assert args[args.index("--export-3mf") + 1] == "plate.gcode.3mf"
    assert sum(1 for a in args if a.endswith("model.stl")) == 3
    work = Path(args[args.index("--outputdir") + 1]).parent
    process = json.loads((work / "process.json").read_text())
    assert process["enable_support"] == "1" and process["sparse_infill_density"] == "25%"

    f = client.get(f"/api/jobs/{job['id']}/file")
    assert f.status_code == 200 and f.content[:2] == b"PK"
    assert client.get("/api/jobs").json()["jobs"][0]["id"] == job["id"]


def test_failed_slice_reports_reason(client, monkeypatch):
    monkeypatch.setenv("FAKE_ORCA_FAIL", "1")
    opts = client.get(f"/api/printers/{X1C}/options").json()
    model = _upload_cube(client)
    job = client.post("/api/jobs", json={"model_id": model["id"], "printer": X1C,
                                         "process": opts["default_process"],
                                         "filament": opts["default_filament"]}).json()
    done = _wait(client, job["id"])
    assert done["status"] == "failed"
    assert "Fake failure" in done["error"]


def test_bad_inputs(client):
    r = client.post("/api/models", files={"file": ("notes.txt", b"hello", "text/plain")})
    assert r.status_code == 400
    r = client.post("/api/models", files={"file": ("broken.stl", b"solid x\nendsolid", "model/stl")})
    assert r.status_code == 400
    model = _upload_cube(client)
    r = client.post("/api/jobs", json={"model_id": model["id"], "printer": X1C, "process": "nope",
                                       "filament": "Bambu PLA Basic @BBL X1C"})
    assert r.status_code == 400
    assert client.get("/api/models/../../etc/passwd/preview.stl").status_code == 404


def test_printer_not_configured(client):
    s = client.get("/api/printer/status").json()
    assert s["configured"] is False
    r = client.post("/api/jobs/abc/print", json={})
    assert r.status_code == 409
