"""Talk to a Bambu Lab printer over LAN: MQTT status/commands and FTPS uploads.

Needs the printer in LAN-only mode with Developer Mode on. A Bambuddy virtual
printer speaks the same protocol, so its IP/serial/access code work here too.
"""

from __future__ import annotations

import ftplib
import hashlib
import json
import logging
import socket
import ssl
import threading
import time
from pathlib import Path
from typing import Any

import paho.mqtt.client as mqtt

log = logging.getLogger("pocketslicer")


class PrinterError(Exception):
    pass


def _insecure_context() -> ssl.SSLContext:
    # Bambu printers use self-signed certificates on the LAN.
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return ctx


class ImplicitFTPS(ftplib.FTP_TLS):
    """FTPS on port 990 with TLS from the first byte, reusing the TLS session for data."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._sock: socket.socket | None = None

    @property
    def sock(self) -> socket.socket | None:  # type: ignore[override]
        return self._sock

    @sock.setter
    def sock(self, value: socket.socket | None) -> None:
        if value is not None and not isinstance(value, ssl.SSLSocket):
            value = self.context.wrap_socket(value, server_hostname=self.host)
        self._sock = value

    def ntransfercmd(self, cmd: str, rest: Any = None) -> tuple[socket.socket, int | None]:
        conn, size = ftplib.FTP.ntransfercmd(self, cmd, rest)
        if self._prot_p:  # type: ignore[attr-defined]
            session = self.sock.session if isinstance(self.sock, ssl.SSLSocket) else None
            conn = self.context.wrap_socket(conn, server_hostname=self.host, session=session)
        return conn, size

    def storbinary(self, cmd: str, fp: Any, blocksize: int = 65536, callback: Any = None, rest: Any = None) -> str:  # type: ignore[override]
        # Like ftplib's, but the TLS shutdown is time-limited: some Bambu firmware
        # never answers it, while other FTPS servers reject the upload without it.
        self.voidcmd("TYPE I")
        with self.transfercmd(cmd, rest) as conn:
            while True:
                buf = fp.read(blocksize)
                if not buf:
                    break
                conn.sendall(buf)
                if callback:
                    callback(buf)
            if isinstance(conn, ssl.SSLSocket):
                conn.settimeout(5)
                try:
                    conn.unwrap()
                except (OSError, ValueError):
                    pass
        return self.voidresp()


def deep_merge(base: dict[str, Any], update: dict[str, Any]) -> dict[str, Any]:
    for key, value in update.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            deep_merge(base[key], value)
        else:
            base[key] = value
    return base


def _int(value: Any) -> int | None:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _flt(value: Any) -> float | None:
    try:
        return round(float(value), 1)
    except (TypeError, ValueError):
        return None


def _color(raw: Any) -> str:
    text = str(raw or "")
    if len(text) >= 6:
        return "#" + text[:6].upper()
    return ""


def normalize_status(report: dict[str, Any]) -> dict[str, Any]:
    p = report.get("print", {}) if isinstance(report, dict) else {}
    ams_units = []
    ams = p.get("ams") or {}
    for unit in ams.get("ams") or []:
        try:
            unit_id = int(unit.get("id", 0))
        except (TypeError, ValueError):
            unit_id = 0
        trays = []
        for tray in unit.get("tray") or []:
            try:
                tray_id = int(tray.get("id", 0))
            except (TypeError, ValueError):
                tray_id = 0
            loaded = bool(tray.get("tray_type"))
            trays.append(
                {
                    "slot": unit_id * 4 + tray_id,
                    "index": tray_id,
                    "loaded": loaded,
                    "type": tray.get("tray_type") or "",
                    "name": tray.get("tray_sub_brands") or tray.get("tray_type") or "",
                    "color": _color(tray.get("tray_color")) if loaded else "",
                    "remain": _int(tray.get("remain")) if _int(tray.get("remain")) is not None else -1,
                }
            )
        humidity = unit.get("humidity")
        ams_units.append({"id": unit_id, "humidity": str(humidity) if humidity is not None else None, "trays": trays})
    vt = p.get("vt_tray") or {}
    external = {
        "loaded": bool(vt.get("tray_type")),
        "type": vt.get("tray_type") or "",
        "color": _color(vt.get("tray_color")) if vt.get("tray_type") else "",
    }
    try:
        tray_now = int(ams.get("tray_now", -1))
    except (TypeError, ValueError):
        tray_now = -1
    return {
        "state": str(p.get("gcode_state") or "UNKNOWN"),
        "progress": _int(p.get("mc_percent")),
        "remaining_min": _int(p.get("mc_remaining_time")),
        "layer": _int(p.get("layer_num")),
        "total_layers": _int(p.get("total_layer_num")),
        "task": str(p.get("subtask_name") or ""),
        "nozzle_temp": _flt(p.get("nozzle_temper")),
        "bed_temp": _flt(p.get("bed_temper")),
        "ams": ams_units,
        "tray_now": tray_now,
        "external": external,
    }


def build_print_command(
    *,
    sequence_id: str,
    filename: str,
    task_name: str,
    md5: str,
    url_style: str,
    ams_slot: int | None,
    timelapse: bool,
    bed_leveling: bool,
    flow_cali: bool,
    vibration_cali: bool,
) -> dict[str, Any]:
    url = f"file:///sdcard/{filename}" if url_style == "sdcard" else f"ftp://{filename}"
    use_ams = ams_slot is not None and ams_slot >= 0
    return {
        "print": {
            "sequence_id": sequence_id,
            "command": "project_file",
            "param": "Metadata/plate_1.gcode",
            "project_id": "0",
            "profile_id": "0",
            "task_id": "0",
            "subtask_id": "0",
            "subtask_name": task_name,
            "file": filename,
            "url": url,
            "md5": md5,
            "bed_type": "auto",
            "timelapse": timelapse,
            "bed_leveling": bed_leveling,
            "bed_levelling": bed_leveling,
            "flow_cali": flow_cali,
            "vibration_cali": vibration_cali,
            "layer_inspect": False,
            "use_ams": use_ams,
            "ams_mapping": [int(ams_slot)] if use_ams else [],
        }
    }


class PrinterLink:
    def __init__(self, ip: str, serial: str, access_code: str, url_style: str = "ftp") -> None:
        self.ip = ip
        self.serial = serial
        self.access_code = access_code
        self.url_style = url_style
        self._client: mqtt.Client | None = None
        self._connected = threading.Event()
        self._report: dict[str, Any] = {}
        self._report_at = 0.0
        self._pushall_at = 0.0
        self._acks: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._report_event = threading.Condition(self._lock)
        self._seq = int(time.time()) % 100000
        self._last_error = ""

    # ---------- MQTT ----------

    @property
    def _topic_report(self) -> str:
        return f"device/{self.serial}/report"

    @property
    def _topic_request(self) -> str:
        return f"device/{self.serial}/request"

    def _ensure_client(self) -> None:
        if self._client is not None:
            return
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"pocketslicer-{int(time.time())}")
        client.username_pw_set("bblp", self.access_code)
        client.tls_set_context(_insecure_context())
        client.tls_insecure_set(True)
        client.on_connect = self._on_connect
        client.on_disconnect = self._on_disconnect
        client.on_message = self._on_message
        client.reconnect_delay_set(min_delay=2, max_delay=30)
        try:
            client.connect_async(self.ip, 8883, keepalive=30)
        except Exception as exc:  # DNS errors etc.
            self._last_error = str(exc)
            raise PrinterError(f"Can't reach the printer at {self.ip}: {exc}") from exc
        client.loop_start()
        self._client = client

    def _on_connect(self, client: mqtt.Client, userdata: Any, flags: Any, reason: Any, props: Any = None) -> None:
        if getattr(reason, "is_failure", False):
            self._last_error = f"Printer refused the connection ({reason}). Check the access code."
            log.warning(self._last_error)
            return
        self._last_error = ""
        client.subscribe(self._topic_report)
        self._connected.set()
        self._publish({"pushing": {"sequence_id": self._next_seq(), "command": "pushall"}})
        self._pushall_at = time.time()

    def _on_disconnect(self, client: mqtt.Client, userdata: Any, flags: Any, reason: Any, props: Any = None) -> None:
        self._connected.clear()

    def _on_message(self, client: mqtt.Client, userdata: Any, msg: mqtt.MQTTMessage) -> None:
        try:
            data = json.loads(msg.payload.decode("utf-8", "replace"))
        except ValueError:
            return
        if not isinstance(data, dict):
            return
        with self._report_event:
            p = data.get("print")
            if isinstance(p, dict) and p.get("command") == "project_file":
                self._acks[str(p.get("sequence_id"))] = p
            deep_merge(self._report, data)
            self._report_at = time.time()
            self._report_event.notify_all()

    def _next_seq(self) -> str:
        self._seq += 1
        return str(self._seq)

    def _publish(self, payload: dict[str, Any]) -> None:
        if self._client is None:
            raise PrinterError("Not connected to the printer")
        self._client.publish(self._topic_request, json.dumps(payload), qos=1)

    def _wait_connected(self, timeout: float) -> None:
        self._ensure_client()
        if not self._connected.wait(timeout):
            raise PrinterError(self._last_error or f"Can't connect to the printer at {self.ip}.")

    def status(self, wait: float = 4.0) -> dict[str, Any]:
        self._wait_connected(wait)
        now = time.time()
        if now - self._pushall_at > 60:
            self._pushall_at = now
            self._publish({"pushing": {"sequence_id": self._next_seq(), "command": "pushall"}})
        with self._report_event:
            if not self._report:
                self._report_event.wait_for(lambda: bool(self._report), timeout=wait)
            report = json.loads(json.dumps(self._report))
            updated = self._report_at
        if not report:
            raise PrinterError("The printer didn't send its status.")
        result = normalize_status(report)
        result["updated_at"] = updated
        return result

    # ---------- printing ----------

    def upload(self, local: Path, remote_name: str) -> None:
        ftp = ImplicitFTPS(context=_insecure_context(), timeout=60)
        try:
            ftp.connect(self.ip, 990)
            ftp.login("bblp", self.access_code)
            ftp.prot_p()
            with open(local, "rb") as fh:
                ftp.storbinary(f"STOR {remote_name}", fh)
        except ftplib.error_perm as exc:
            raise PrinterError(f"The printer refused the upload: {exc}") from exc
        except (OSError, ftplib.Error) as exc:
            raise PrinterError(f"Upload to the printer failed: {exc}") from exc
        finally:
            try:
                ftp.close()
            except Exception:
                pass

    def start_print(
        self,
        local: Path,
        task_name: str,
        *,
        ams_slot: int | None,
        timelapse: bool,
        bed_leveling: bool,
        flow_cali: bool,
        vibration_cali: bool = False,
    ) -> dict[str, Any]:
        self._wait_connected(8.0)
        filename = f"{task_name}.gcode.3mf"
        md5 = hashlib.md5(local.read_bytes()).hexdigest()
        self.upload(local, filename)
        seq = self._next_seq()
        cmd = build_print_command(
            sequence_id=seq,
            filename=filename,
            task_name=task_name,
            md5=md5,
            url_style=self.url_style,
            ams_slot=ams_slot,
            timelapse=timelapse,
            bed_leveling=bed_leveling,
            flow_cali=flow_cali,
            vibration_cali=vibration_cali,
        )
        self._publish(cmd)
        with self._report_event:
            self._report_event.wait_for(lambda: seq in self._acks, timeout=8)
            ack = self._acks.pop(seq, None)
        if ack and str(ack.get("result", "")).lower() not in ("", "success"):
            raise PrinterError(f"The printer rejected the print: {ack.get('reason') or ack.get('result')}")
        return {"file": filename, "acknowledged": ack is not None}

    def close(self) -> None:
        if self._client is not None:
            self._client.loop_stop()
            self._client.disconnect()
            self._client = None
