# PocketSlicer

A phone-first slicer for Bambu Lab printers. The Android app gives you touch-friendly controls and a 3D plate preview. A small server on your TrueNAS slices with **OrcaSlicer's engine** and sends the print to your printer over the LAN.

```
Phone (PocketSlicer app)  ──HTTPS──▶  PocketSlicer server (TrueNAS)  ──LAN──▶  X1 Carbon
  open / place / settings               OrcaSlicer CLI + Bambu presets          FTPS upload + MQTT start
```

## What it does

- Open an STL, 3MF or OBJ from Files, or share one into the app from another app.
- See it on the X1C plate in 3D (drag to orbit, pinch to zoom, double-tap to reset).
- Rotate in 90° steps, scale, make copies, or let OrcaSlicer auto-orient it.
- Pick printer, quality, filament and build plate from OrcaSlicer's own Bambu Lab presets.
- Quick overrides: supports (tree or normal), infill, walls, brim.
- Slice on the server and get print time, filament grams and metres, layer count and slicer warnings.
- Print: choose the AMS slot (live colours from the printer) or the external spool, plus bed leveling, flow calibration and timelapse.
- Save the sliced `.gcode.3mf` to your phone.
- Home screen shows the printer's live state, progress and AMS colours.
- Light, dark or system theme. The launcher icon switches to a dark version in dark mode.

## 1. Run the server on TrueNAS

The image is published to `ghcr.io/moblized/pocketslicer-server:latest` by GitHub Actions. It includes OrcaSlicer and its presets.

1. Create a dataset for its data, for example `apps/pocketslicer`, and give the `apps` user (568) access.
2. **Apps → Discover Apps → ⋮ → Install via YAML**, name it `pocketslicer`, and paste:

```yaml
services:
  pocketslicer:
    image: ghcr.io/moblized/pocketslicer-server:latest
    container_name: pocketslicer
    user: "568:568"
    environment:
      - TZ=America/New_York
      - API_TOKEN=make-up-a-long-random-string
      # Your printer (LAN-only mode + Developer Mode on):
      - PRINTER_IP=192.168.1.50
      - PRINTER_SERIAL=00M00A000000000
      - PRINTER_ACCESS_CODE=12345678
      - PRINTER_NAME=X1 Carbon
    volumes:
      - /mnt/POOL/apps/pocketslicer:/data
    ports:
      - 8790:8790
    restart: unless-stopped
```

3. Point your reverse proxy (for example `pocketslicer.moblized.org`) at port **8790**, or use `http://NAS-IP:8790` on your home network.

If TrueNAS can't pull the image ("denied"), open the package on GitHub (**Packages → pocketslicer-server → Package settings**) and set its visibility to Public.

### Printer connection

The server talks to the printer the same way Bambu Studio does in LAN mode, so the printer needs **LAN-only mode with Developer Mode** turned on (Settings → WLAN/Network on the printer). Use the IP address and the 8-character access code shown there, and the serial number from the device info screen.

To keep the printer in cloud mode, point `PRINTER_IP`, `PRINTER_SERIAL` and `PRINTER_ACCESS_CODE` at a **Bambuddy virtual printer** instead, and Bambuddy passes the print on to the printer.

Without the printer variables the app still slices; you save the `.gcode.3mf` and print it another way.

### All settings

| Variable | Default | What it does |
|---|---|---|
| `API_TOKEN` | generated | Password the app must send. If unset, one is generated and written to `/data/api_token.txt` and the logs. |
| `PRINTER_IP`, `PRINTER_SERIAL`, `PRINTER_ACCESS_CODE` | — | The printer (or Bambuddy virtual printer) to print on. |
| `PRINTER_NAME` | `X1 Carbon` | Name shown in the app. |
| `DEFAULT_PRINTER_PRESET` | `Bambu Lab X1 Carbon 0.4 nozzle` | Preset the app starts with. |
| `PRINT_URL_STYLE` | `ftp` | Use `sdcard` if your printer won't start prints sent with `ftp://` URLs. |
| `SLICE_TIMEOUT` | `1800` | Seconds before a slice is stopped. |
| `RETENTION_DAYS` | `14` | Old models and slices are deleted after this. |
| `MAX_UPLOAD_MB` | `300` | Largest model you can upload. |
| `PREVIEW_MAX_FACES` | `300000` | Big meshes are simplified for the phone preview only. Slicing always uses the full mesh. |
| `ORCA_WRAPPER` | — | Set to `xvfb-run -a` if OrcaSlicer ever needs a virtual display. |

## 2. Install the app

Every push to `main` builds a signed APK and attaches it to the newest [GitHub release](https://github.com/moblized/pocketslicer/releases). Download `PocketSlicer-0.1.N.apk` on your phone and install it. Later builds install over earlier ones.

On first launch, enter your server address and the `API_TOKEN`.

## Development

```
server/   FastAPI service (Python 3.12) + Dockerfile + tests
android/  Kotlin + Jetpack Compose app
```

- Server tests: `cd server && pip install -r requirements-dev.txt && pytest`
- CI also builds the Docker image and slices test parts with the real OrcaSlicer before publishing it.
- APK signing: CI uses `android/app/pocketslicer-ci.keystore` unless the `SIGNING_STORE_B64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` secrets are set. The repo is public, so for a private key add those secrets (switching keys means uninstalling the app once).

### Limits of this first version

- A 3MF is treated as one model: its parts are merged and its own plate settings are ignored.
- Single-filament prints only (one AMS slot per print).
- Rotation is in 90° steps.
- No G-code layer preview yet; you get the stats and warnings.

## Licence

OrcaSlicer is AGPL-3.0; the server image runs the unmodified OrcaSlicer release.
