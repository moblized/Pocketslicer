"""Upload a test part to the PocketSlicer server and slice it once, so the app has history."""
import json
import struct
import sys
import time
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8790"
TOKEN = sys.argv[2] if len(sys.argv) > 2 else "e2e"
H = {"Authorization": f"Bearer {TOKEN}"}


def box_stl(sx, sy, sz, ox=0.0, oy=0.0, oz=0.0):
    v = [(ox + x * sx, oy + y * sy, oz + z * sz) for x in (0, 1) for y in (0, 1) for z in (0, 1)]
    faces = [(0, 1, 3), (0, 3, 2), (4, 6, 7), (4, 7, 5), (0, 4, 5), (0, 5, 1),
             (2, 3, 7), (2, 7, 6), (0, 2, 6), (0, 6, 4), (1, 5, 7), (1, 7, 3)]
    return [(v[a], v[b], v[c]) for a, b, c in faces]


tris = box_stl(30, 18, 12) + box_stl(10, 10, 25, 10, 4, 12)  # a little "chair" shape
stl = b"PocketSlicer e2e".ljust(80, b" ") + struct.pack("<I", len(tris))
for t in tris:
    stl += struct.pack("<3f", 0, 0, 0) + b"".join(struct.pack("<3f", *p) for p in t) + b"\0\0"


def call(method, path, body=None, headers=None):
    req = urllib.request.Request(BASE + path, data=body, method=method, headers={**H, **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read())


for _ in range(60):
    try:
        if call("GET", "/api/health")["ok"]:
            break
    except Exception:
        pass
    time.sleep(2)

boundary = uuid.uuid4().hex
body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"desk_bracket.stl\"\r\n"
        f"Content-Type: application/octet-stream\r\n\r\n").encode() + stl + f"\r\n--{boundary}--\r\n".encode()
model = call("POST", "/api/models", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})
opts = call("GET", "/api/printers/Bambu%20Lab%20X1%20Carbon%200.4%20nozzle/options")
job = call("POST", "/api/jobs", json.dumps({
    "model_id": model["id"], "printer": "Bambu Lab X1 Carbon 0.4 nozzle",
    "process": opts["default_process"], "filament": opts["default_filament"],
}).encode(), {"Content-Type": "application/json"})
for _ in range(120):
    job = call("GET", f"/api/jobs/{job['id']}")
    if job["status"] in ("done", "failed"):
        break
    time.sleep(1)
print("seeded:", job["status"], job.get("result") or job.get("error"))
sys.exit(0 if job["status"] == "done" else 1)
