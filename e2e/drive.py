"""Drive the PocketSlicer app on an emulator with adb + uiautomator and take screenshots."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "org.moblized.pocketslicer"
OUT = os.environ.get("SHOTS", "e2e-shots")
os.makedirs(OUT, exist_ok=True)
failures = []


def adb(*args, check=True, capture=True):
    r = subprocess.run(["adb", *args], capture_output=capture, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {r.stderr}")
    return r.stdout


def shot(name):
    with open(os.path.join(OUT, f"{name}.png"), "wb") as fh:
        fh.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    print(f"[shot] {name}", flush=True)


def nodes():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        try:
            root = ET.fromstring(xml[xml.find("<?xml"):] if "<?xml" in xml else xml)
            return list(root.iter("node"))
        except ET.ParseError:
            time.sleep(1)
    return []


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text=None, desc=None, cls=None, exact=False):
    for n in nodes():
        t, d = n.get("text", ""), n.get("content-desc", "")
        if text is not None and not ((t == text) if exact else (text in t)):
            continue
        if desc is not None and desc not in d:
            continue
        if cls is not None and n.get("class") != cls:
            continue
        return n
    return None


def wait_for(timeout=40, **kw):
    end = time.time() + timeout
    while time.time() < end:
        crash_check()
        n = find(**kw)
        if n is not None:
            return n
        time.sleep(1)
    raise TimeoutError(f"not found: {kw}")


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def crash_check():
    log = adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)
    if "FATAL EXCEPTION" in log and PKG in log:
        print(log[-6000:])
        print("::error title=App crashed::" + log[-3000:].replace("\n", "%0A"))
        raise SystemExit(1)


def step(name, fn):
    try:
        fn()
    except SystemExit:
        raise
    except Exception as exc:
        failures.append(f"{name}: {exc}")
        print(f"::error title=e2e {name}::{exc}")
        shot(f"fail-{name}")


adb("logcat", "-c")
adb("install", "-r", sys.argv[1])
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
time.sleep(4)


def setup():
    wait_for(text="Connect", exact=True)
    shot("01-setup")
    fields = [n for n in nodes() if n.get("class") == "android.widget.EditText"]
    tap(fields[0]); adb("shell", "input", "text", "http://10.0.2.2:8790")
    tap(fields[1]); adb("shell", "input", "text", "e2e")
    adb("shell", "input", "keyevent", "4")  # hide keyboard
    time.sleep(1)
    tap(wait_for(text="Connect", exact=True))
    wait_for(text="Open a model")
    time.sleep(3)
    shot("02-home")


def open_result():
    tap(wait_for(text="desk_bracket"))
    wait_for(text="Ready to print")
    time.sleep(1)
    shot("03-result")


def plate():
    tap(wait_for(text="Change", exact=True))
    wait_for(text="Print settings", timeout=60)
    time.sleep(4)
    shot("04-plate")
    tap(wait_for(text="X +90°"))
    time.sleep(2)
    shot("05-rotated")
    adb("shell", "input", "swipe", "500", "1800", "500", "700", "400")
    time.sleep(1)
    shot("06-plate-settings")


def reslice():
    tap(wait_for(text="Slice", exact=True))
    wait_for(text="Ready to print", timeout=120)
    time.sleep(1)
    shot("07-new-result")


def filament_picker():
    adb("shell", "input", "keyevent", "4")
    wait_for(text="Print settings", timeout=20)
    tap(wait_for(text="Filament", exact=True))
    wait_for(text="Search")
    time.sleep(1)
    shot("08-filament-picker")
    adb("shell", "input", "keyevent", "4")


def dark_mode():
    for _ in range(4):
        if find(text="Open a model"):
            break
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    tap(wait_for(desc="Settings"))
    tap(wait_for(text="Dark", exact=True))
    time.sleep(2)
    shot("09-settings-dark")
    adb("shell", "input", "keyevent", "4")
    time.sleep(2)
    shot("10-home-dark")


for name, fn in [("setup", setup), ("result", open_result), ("plate", plate), ("reslice", reslice),
                 ("filament", filament_picker), ("dark", dark_mode)]:
    step(name, fn)

crash_check()
print("failures:", failures)
sys.exit(1 if failures else 0)
