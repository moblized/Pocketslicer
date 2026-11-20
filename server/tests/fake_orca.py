#!/usr/bin/env python3
"""Stand-in for the OrcaSlicer CLI used by the tests.

Checks the arguments the server passes and writes a .gcode.3mf like the real CLI.
"""

import json
import os
import sys
import zipfile
from pathlib import Path


def arg(name, default=None):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return default


def main() -> int:
    log = os.environ.get("FAKE_ORCA_LOG")
    if log:
        Path(log).write_text(json.dumps({"argv": sys.argv[1:], "cwd": os.getcwd()}))
    if os.environ.get("FAKE_ORCA_FAIL"):
        out = Path(arg("--outputdir", "."))
        (out / "result.json").write_text(json.dumps({"return_code": -50, "error_string": "Fake failure"}))
        return 3
    settings = arg("--load-settings", "").split(";")
    filaments = arg("--load-filaments", "").split(";")
    for path in settings + filaments:
        data = json.loads(Path(path).read_text())
        assert "inherits" not in data, f"{path} still has inherits"
        assert data.get("from") == "system"
    machine = json.loads(Path(settings[0]).read_text())
    if os.environ.get("FAKE_ORCA_SEGV_ON_VARIANTS") and "printer_extruder_variant" in machine:
        import signal
        os.kill(os.getpid(), signal.SIGSEGV)
    process = json.loads(Path(settings[1]).read_text())
    assert machine["type"] == "machine" and process["type"] == "process"
    assert machine["name"] in process["compatible_printers"]

    pipe = arg("--pipe")
    if pipe:
        with open(pipe, "w") as fh:
            for pct in (10, 50, 90):
                fh.write(json.dumps({"plate_index": 1, "plate_count": 1, "total_percent": pct,
                                     "message": f"step {pct}"}) + "\n")
                fh.flush()

    models = [a for a in sys.argv[1:] if a.endswith(".stl")]
    out = Path(arg("--outputdir", "."))
    target = Path(arg("--export-3mf"))
    if not target.is_absolute():
        target = out / target
    info = f"""<?xml version="1.0" encoding="UTF-8"?>
<config>
  <header><header_item key="X-BBL-Client-Type" value="slicer"/></header>
  <plate>
    <metadata key="index" value="1"/>
    <metadata key="prediction" value="{1234 * len(models)}"/>
    <metadata key="weight" value="{5.5 * len(models)}"/>
    <filament id="1" type="PLA" color="#FFFFFF" used_m="{1.5 * len(models)}" used_g="{5.5 * len(models)}" />
    <warning msg="fake warning" level="1" error_code="1000C001"/>
  </plate>
</config>
"""
    gcode = "; HEADER_BLOCK_START\n; total layer number: 100\n; total estimated time: 20m 34s\nG28\n"
    with zipfile.ZipFile(target, "w") as zf:
        zf.writestr("Metadata/plate_1.gcode", gcode)
        zf.writestr("Metadata/slice_info.config", info)
    return 0


if __name__ == "__main__":
    sys.exit(main())
