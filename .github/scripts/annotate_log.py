"""Turn the important lines of a failed build log into one GitHub error annotation."""
import re
import sys

path, title = sys.argv[1], sys.argv[2]
try:
    lines = open(path, errors="replace").read().splitlines()
except OSError:
    sys.exit(0)
pattern = re.compile(r"^(e: |ERROR|FAILURE|\* What went wrong|> |AAPT|FAILED|E +|.*\berror\b|.*Error:|.*Exception)")
keep = []
for i, line in enumerate(lines):
    if pattern.match(line):
        keep.append(re.sub(r"file:///home/runner/work/[^/]+/[^/]+/", "", line))
if not keep:
    keep = lines[-40:]
text = "\n".join(dict.fromkeys(keep))[:6000]
text = text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
print(f"::error title={title}::{text}")
