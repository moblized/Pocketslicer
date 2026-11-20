#!/usr/bin/env bash
# Download an OrcaSlicer Linux AppImage release and unpack it to $2 (default /opt/orca).
set -euo pipefail

VERSION="${1:?usage: fetch-orca.sh VERSION [DEST]}"
DEST="${2:-/opt/orca}"
BASE="https://github.com/SoftFever/OrcaSlicer/releases/download/v${VERSION}"

cd /tmp
AUTH=()
if [ -s /run/secrets/github_token ]; then
  AUTH=(-H "Authorization: Bearer $(cat /run/secrets/github_token)")
fi
api() { curl -fsSL "${AUTH[@]}" "https://api.github.com/repos/SoftFever/OrcaSlicer/$1"; }
pick_asset='
import json, sys
assets = [a["browser_download_url"] for a in json.load(sys.stdin).get("assets", [])
          if a["name"].endswith(".AppImage") and "linux" in a["name"].lower()]
assets.sort(key=lambda u: (0 if "2404" in u else 1, u))
print(assets[0] if assets else "")'
url=""
for name in \
  "OrcaSlicer_Linux_AppImage_Ubuntu2404_V${VERSION}.AppImage" \
  "OrcaSlicer_Linux_AppImage_Ubuntu2404_v${VERSION}.AppImage" \
  "OrcaSlicer_Linux_AppImage_V${VERSION}.AppImage"; do
  if curl -fsIL --retry 2 "${BASE}/${name}" > /dev/null 2>&1; then
    url="${BASE}/${name}"
    break
  fi
done

if [ -z "$url" ]; then
  echo "Known asset names not found for v${VERSION}; asking the GitHub API" >&2
  url=$(api "releases/tags/v${VERSION}" | python3 -c "$pick_asset" || true)
fi

if [ -z "$url" ]; then
  echo "No AppImage for v${VERSION}; using the latest OrcaSlicer release" >&2
  url=$(api "releases/latest" | python3 -c "$pick_asset" || true)
fi

if [ -z "$url" ]; then
  echo "No Linux AppImage found for OrcaSlicer v${VERSION}" >&2
  exit 1
fi

echo "Downloading ${url}"
curl -fL --retry 3 -o orca.AppImage "$url"
chmod +x orca.AppImage
./orca.AppImage --appimage-extract > /dev/null
rm -rf "$DEST"
mv squashfs-root "$DEST"
rm -f orca.AppImage
test -x "$DEST/AppRun"
test -d "$DEST/resources/profiles/BBL"
echo "OrcaSlicer ${VERSION} unpacked to ${DEST}"
