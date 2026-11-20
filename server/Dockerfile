# syntax=docker/dockerfile:1
FROM ubuntu:24.04

# 2.3.2's CLI segfaults on Bambu presets with nozzle variants (OrcaSlicer #13193).
ARG ORCA_VERSION=2.3.1
ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    HOME=/tmp \
    ORCA_VERSION=${ORCA_VERSION} \
    ORCA_BIN=/opt/orca/AppRun \
    ORCA_PROFILES=/opt/orca/resources/profiles \
    DATA_DIR=/data \
    PORT=8790 \
    PYTHONUNBUFFERED=1

# OrcaSlicer's shared-library needs (GTK/WebKit are linked even in CLI mode), plus Python.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl python3 python3-venv \
      libgtk-3-0t64 libwebkit2gtk-4.1-0 libgl1 libgl1-mesa-dri libegl1 libglu1-mesa libopengl0 libosmesa6 \
      libgstreamer1.0-0 libgstreamer-plugins-base1.0-0 libsecret-1-0 libnotify4 libsoup-3.0-0 \
      libdbus-1-3 libudev1 libfontconfig1 libmspack0t64 fonts-dejavu-core xvfb xauth \
    && rm -rf /var/lib/apt/lists/*

COPY docker/fetch-orca.sh /usr/local/bin/fetch-orca.sh
RUN --mount=type=secret,id=github_token bash /usr/local/bin/fetch-orca.sh "${ORCA_VERSION}" /opt/orca \
    && (ldd /opt/orca/bin/orca-slicer 2>/dev/null | grep "not found" || true)

WORKDIR /app
COPY requirements.txt .
RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN mkdir -p /data && chmod 777 /data
VOLUME /data
EXPOSE 8790

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD /opt/venv/bin/python -c "import os,urllib.request; urllib.request.urlopen(f'http://127.0.0.1:{os.environ.get(\"PORT\",\"8790\")}/api/health', timeout=4)" || exit 1

CMD ["sh", "-c", "exec /opt/venv/bin/uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --proxy-headers --forwarded-allow-ips='*'"]
