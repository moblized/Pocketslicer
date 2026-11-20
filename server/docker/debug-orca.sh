#!/usr/bin/env bash
# CI diagnostics: run the OrcaSlicer CLI directly in several ways and report what happens.
set -uo pipefail
cd /tmp
mkdir -p dbg && cd dbg

PYTHONPATH=/app /opt/venv/bin/python - <<'EOF'
import json, os
os.environ.setdefault("API_TOKEN", "x")
from app.profiles import ProfileLibrary
import trimesh
lib = ProfileLibrary("/opt/orca/resources/profiles")
pr = "Bambu Lab X1 Carbon 0.4 nozzle"
o = lib.options(pr)
json.dump(lib.resolve("machine", pr), open("machine.json", "w"), indent=1)
json.dump(lib.resolve("process", o["default_process"]), open("process.json", "w"), indent=1)
json.dump(lib.resolve("filament", o["default_filament"]), open("filament.json", "w"), indent=1)
m = trimesh.creation.box(extents=(20, 20, 20)); m.apply_translation([128, 128, 10]); m.export("cube.stl")
print("presets written:", [os.path.getsize(f) for f in ("machine.json", "process.json", "filament.json")])
EOF

echo "=== AppRun ==="
cat /opt/orca/AppRun
ls /opt/orca /opt/orca/bin | head -40

D=/tmp/dbg
BASE=(--slice 0 --load-settings "$D/machine.json;$D/process.json" --load-filaments "$D/filament.json" --allow-newer-file --debug 4)

run() {
  local name="$1"; shift
  rm -rf "out_$name"; mkdir -p "out_$name"
  echo "=== variant: $name ==="
  echo "+ $*"
  ( cd "out_$name" && timeout 300 "$@" > log.txt 2>&1 ); local code=$?
  echo "exit code: $code"
  grep -v "^\s*$" "out_$name/log.txt" | tail -n 45
  ls -la "out_$name" | head -20
  echo "::notice title=orca variant $name::exit $code; files: $(ls out_$name | tr '\n' ' ')"
}

run plain        /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_plain" --export-3mf plate.gcode.3mf /tmp/dbg/cube.stl
run xvfb         xvfb-run -a /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_xvfb" --export-3mf plate.gcode.3mf /tmp/dbg/cube.stl
run nothumb      /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_nothumb" --export-3mf plate.gcode.3mf --no-check /tmp/dbg/cube.stl
run minsave      /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_minsave" --export-3mf plate.gcode.3mf --min-save /tmp/dbg/cube.stl
run gcodeonly    /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_gcodeonly" /tmp/dbg/cube.stl
run nobed        /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_nobed" --export-3mf plate.gcode.3mf /tmp/dbg/cube.stl
run bed          /opt/orca/AppRun "${BASE[@]}" --outputdir "$PWD/out_bed" --export-3mf plate.gcode.3mf --curr-bed-type "Textured PEI Plate" /tmp/dbg/cube.stl

if command -v gdb > /dev/null; then
  echo "=== gdb backtrace (plain) ==="
  mkdir -p out_gdb
  export LD_LIBRARY_PATH="/opt/orca/usr/lib:/opt/orca/usr/lib/x86_64-linux-gnu:/opt/orca/lib:${LD_LIBRARY_PATH:-}"
  ( cd out_gdb && timeout 300 gdb -q -batch -ex "set pagination off" -ex run -ex bt -ex "info sharedlibrary" \
      --args /opt/orca/bin/orca-slicer "${BASE[@]}" --outputdir "$PWD" --export-3mf plate.gcode.3mf /tmp/dbg/cube.stl \
      2>&1 | grep -v "^\[New Thread\|^\[Thread" | tail -n 60 )
fi
exit 0
