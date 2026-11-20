"""Settings, read once from environment variables."""

from __future__ import annotations

import logging
import os
import secrets
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("pocketslicer")


def _bool(name: str, default: bool) -> bool:
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "on")


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass
class Settings:
    data_dir: Path
    orca_bin: str
    orca_profiles: Path
    orca_wrapper: list[str]
    orca_bed_type_flag: bool
    orca_version: str
    api_token: str
    printer_ip: str
    printer_serial: str
    printer_access_code: str
    printer_name: str
    default_printer_preset: str
    print_url_style: str
    slice_timeout: int
    slice_workers: int
    preview_max_faces: int
    max_upload_mb: int
    retention_days: int

    @property
    def printer_configured(self) -> bool:
        return bool(self.printer_ip and self.printer_serial and self.printer_access_code)

    @property
    def models_dir(self) -> Path:
        return self.data_dir / "models"

    @property
    def jobs_dir(self) -> Path:
        return self.data_dir / "jobs"


def _load_token(data_dir: Path) -> str:
    token = os.environ.get("API_TOKEN", "").strip()
    if token:
        return token
    token_file = data_dir / "api_token.txt"
    if token_file.exists():
        saved = token_file.read_text().strip()
        if saved:
            return saved
    token = secrets.token_urlsafe(24)
    try:
        token_file.write_text(token + "\n")
    except OSError:
        pass
    log.warning("API_TOKEN not set. Generated one and saved it to %s: %s", token_file, token)
    return token


def load_settings() -> Settings:
    data_dir = Path(os.environ.get("DATA_DIR", "/data"))
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "models").mkdir(exist_ok=True)
    (data_dir / "jobs").mkdir(exist_ok=True)
    wrapper = os.environ.get("ORCA_WRAPPER", "").split()
    return Settings(
        data_dir=data_dir,
        orca_bin=os.environ.get("ORCA_BIN", "/opt/orca/AppRun"),
        orca_profiles=Path(os.environ.get("ORCA_PROFILES", "/opt/orca/resources/profiles")),
        orca_wrapper=wrapper,
        orca_bed_type_flag=_bool("ORCA_BED_TYPE_FLAG", True),
        orca_version=os.environ.get("ORCA_VERSION", "unknown"),
        api_token=_load_token(data_dir),
        printer_ip=os.environ.get("PRINTER_IP", "").strip(),
        printer_serial=os.environ.get("PRINTER_SERIAL", "").strip(),
        printer_access_code=os.environ.get("PRINTER_ACCESS_CODE", "").strip(),
        printer_name=os.environ.get("PRINTER_NAME", "X1 Carbon").strip(),
        default_printer_preset=os.environ.get(
            "DEFAULT_PRINTER_PRESET", "Bambu Lab X1 Carbon 0.4 nozzle"
        ).strip(),
        print_url_style=os.environ.get("PRINT_URL_STYLE", "ftp").strip().lower(),
        slice_timeout=_int("SLICE_TIMEOUT", 1800),
        slice_workers=max(1, _int("SLICE_WORKERS", 1)),
        preview_max_faces=_int("PREVIEW_MAX_FACES", 300_000),
        max_upload_mb=_int("MAX_UPLOAD_MB", 300),
        retention_days=_int("RETENTION_DAYS", 14),
    )
