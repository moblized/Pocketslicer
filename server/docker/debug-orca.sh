#!/usr/bin/env bash
# CI diagnostics: try OrcaSlicer CLI workarounds for the multi-extruder segfault.
set -uo pipefail
mkdir -p /tmp/dbg && cd /tmp/dbg

presets() { # $1 = orca dir, $2 = mode (plain|addkeys|strip), $3 = out dir
  mkdir -p "$3"
  PYTHONPATH=/app /opt/venv/bin/python - "$1" "$2" "$3" <<'EOF'
import json, sys
from pathlib import Path
orca, mode, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
from app.profiles import ProfileLibrary
import trimesh
lib = ProfileLibrary(Path(orca) / "resources/profiles")
pr = "Bambu Lab X1 Carbon 0.4 nozzle"
o = lib.options(pr)
m = lib.resolve("machine", pr)
p = lib.resolve("process", o["default_process"])
f = lib.resolve("filament", o["default_filament"])
def show(name, d):
    keys = {k: v for k, v in d.items() if any(s in k for s in ("variant", "extruder_id", "extruder_type", "nozzle_volume", "filament_map", "self_index"))}
    print(f"  {name}: {json.dumps(keys)[:600]}")
if mode == "plain":
    show("machine", m); show("process", p); show("filament", f)
if mode == "addkeys":
    m.setdefault("extruder_type", ["Direct Drive"])
    m.setdefault("nozzle_volume_type", ["Standard"])
    p.setdefault("filament_map", ["1"])
    f.setdefault("filament_map", ["1"])
if mode == "strip":
    for d in (m, p, f):
        for k in list(d):
            if "variant" in k or k in ("printer_extruder_id", "print_extruder_id", "filament_self_index"):
                del d[k]
json.dump(m, open(out / "machine.json", "w"), indent=1)
json.dump(p, open(out / "process.json", "w"), indent=1)
json.dump(f, open(out / "filament.json", "w"), indent=1)
c = trimesh.creation.box(extents=(20, 20, 20)); c.apply_translation([128, 128, 10]); c.export(out / "cube.stl")
EOF
}

attempt() { # $1 name, $2 orca dir, $3 preset mode, extra args...
  local name="$1" orca="$2" mode="$3"; shift 3
  local d="/tmp/dbg/$name"
  rm -rf "$d"; presets "$orca" "$mode" "$d"
  echo "=== $name ($orca, presets: $mode) ==="
  ( cd "$d" && timeout 300 "$orca/AppRun" --slice 0 --load-settings "$d/machine.json;$d/process.json" \
      --load-filaments "$d/filament.json" --allow-newer-file --debug 3 --outputdir "$d" \
      --export-3mf plate.gcode.3mf "$@" "$d/cube.stl" > "$d/log.txt" 2>&1 )
  local code=$?
  grep -v "^\s*$" "$d/log.txt" | grep -iv "update_slice_result_valid_state\|set_pos_and_size\|arrange progress" | tail -n 12
  local ok="no"; [ -s "$d/plate.gcode.3mf" ] && ok="yes"
  echo "RESULT $name: exit $code, 3mf=$ok"
  echo "::notice title=orca $name::exit $code, gcode.3mf produced: $ok"
}

attempt v232_plain   /opt/orca plain
attempt v232_addkeys /opt/orca addkeys
attempt v232_strip   /opt/orca strip

for v in 2.3.1 latest; do
  if bash /dbg/fetch-orca.sh "$v" "/opt/orca-$v" > /tmp/dbg/fetch-$v.log 2>&1; then
    attempt "v${v//./}_plain" "/opt/orca-$v" plain
    attempt "v${v//./}_strip" "/opt/orca-$v" strip
  else
    echo "::notice title=orca $v::download failed: $(tail -n 3 /tmp/dbg/fetch-$v.log | tr '\n' ' ')"
  fi
done
grep -h "Downloading" /tmp/dbg/fetch-*.log
exit 0
