from __future__ import annotations

import os
from dataclasses import replace

from PySide6.QtCore import QObject, QElapsedTimer, QTimer, Signal

from .models import DeviceMode, DeviceStatus, EMPTY_STATUS

try:
    import hid  # type: ignore
except Exception:
    hid = None


HID_VID = 0x0483
HID_PIDS = (0x5741, 0x5740)

REPORT_SIZE = 33
REPORT_ID_CTRL_OUT = 0x02
REPORT_ID_STATUS_IN = 0x03

CMD_PING = 0x01
CMD_GET_STATUS = 0x02

MSG_PING_ACK = 0x81
MSG_STATUS = 0x82


class HidTransport(QObject):
    status_updated = Signal(object)
    connection_changed = Signal(bool)

    def __init__(self, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._status = replace(EMPTY_STATUS)
        self._status.passwords = [
            "dummy_btn1",
            "dummy_btn2",
            "dummy_btn3",
            "dummy_btn4",
            "dummy_btn5",
            "dummy_btn6",
        ]
        self._status.connected = False
        self._status.last_event = "Ready"
        self._hid_device = None
        self._use_mock = True
        self._seq = 0
        self._heartbeat = QTimer(self)
        self._heartbeat.setInterval(300)
        self._heartbeat.timeout.connect(self._emit_heartbeat)
        self._uptime = QElapsedTimer()
        self._pulse = 0

    def start(self) -> None:
        force_mock = os.getenv("PASSPRESS_FORCE_MOCK", "0") == "1"
        self._use_mock = force_mock

        if force_mock:
            self._status.connected = True
            self._status.last_event = "Connected (mock forced)"
        elif self._try_open_hid_device():
            self._status.connected = True
            self._status.last_event = "Connected (HID)"
        else:
            self._status.connected = False
            self._status.last_event = "Board not detected"

        self._uptime.start()
        self._status.uptime_ms = 0
        self._status.report_count = 0
        self._heartbeat.start()
        self.status_updated.emit(self._status)
        self.connection_changed.emit(self._status.connected)

    def stop(self) -> None:
        self._heartbeat.stop()
        if self._hid_device is not None:
            try:
                self._hid_device.close()
            except Exception:
                pass
            self._hid_device = None

    def verify_admin(self, password: str) -> bool:
        if not self._use_mock:
            self._status.last_event = "Admin verify not in phase 1/2"
            self.status_updated.emit(self._status)
            return False

        if not self._status.connected:
            return False
        if password == "admin123":
            self._status.mode = DeviceMode.ADMIN
            self._status.last_event = "Admin verified"
            self.status_updated.emit(self._status)
            return True
        self._status.last_event = "Admin verification failed"
        return False

    def set_mode(self, mode: DeviceMode) -> None:
        if not self._use_mock:
            self._status.last_event = "Set mode pending phase 3"
            self.status_updated.emit(self._status)
            return

        if not self._status.connected:
            return
        self._status.mode = mode
        if mode == DeviceMode.NON_TRUSTED:
            self._status.passwords = [f"dummy_btn{i + 1}" for i in range(6)]
        elif mode == DeviceMode.TRUSTED:
            self._status.passwords = [
                "*Moustafa_eJ@d2026#!",
                "StungPayingNeedyJavaValue",
                "qxz5fys",
                "741536",
                "test5pass55",
                "secret666xxx",
            ]
        self._status.last_event = f"Mode changed to {mode.name}"
        self.status_updated.emit(self._status)

    def save_button(self, index: int, password: str, append_enter: bool) -> bool:
        if not self._use_mock:
            self._status.last_event = "Save pending phase 4"
            self.status_updated.emit(self._status)
            return False

        if not self._status.connected or self._status.mode != DeviceMode.ADMIN:
            return False
        if index < 0 or index >= 6:
            return False
        self._status.passwords[index] = password
        self._status.append_enter[index] = append_enter
        self._status.last_event = f"Saved Button {index + 1}"
        self.status_updated.emit(self._status)
        return True

    def disconnect_device(self) -> None:
        if self._hid_device is not None:
            try:
                self._hid_device.close()
            except Exception:
                pass
            self._hid_device = None

        self._status = replace(EMPTY_STATUS)
        self._status.last_event = "Disconnected"
        self.status_updated.emit(self._status)
        self.connection_changed.emit(False)

    def _try_open_hid_device(self) -> bool:
        if hid is None:
            return False

        if hasattr(hid, "enumerate"):
            try:
                entries = hid.enumerate(HID_VID, 0)
            except Exception:
                entries = []

            preferred: list[dict] = []
            for pid in HID_PIDS:
                for entry in entries:
                    if int(entry.get("vendor_id", 0)) != HID_VID:
                        continue
                    if int(entry.get("product_id", 0)) != pid:
                        continue
                    preferred.append(entry)

            for entry in preferred:
                path = entry.get("path")
                if not path:
                    continue
                try:
                    dev = hid.device()
                    dev.open_path(path)
                except Exception:
                    continue

                if self._handshake_device(dev):
                    return True

            if preferred:
                return False

        for pid in HID_PIDS:
            try:
                dev = hid.device()
                dev.open(HID_VID, pid)
            except Exception:
                continue

            if self._handshake_device(dev):
                return True

        self._hid_device = None
        return False

    def _handshake_device(self, dev) -> bool:
        try:
            dev.set_nonblocking(1)
        except Exception:
            pass

        self._hid_device = dev
        self._status.connected = True

        decoded = self._request_status_packet(CMD_PING)
        if decoded is None:
            decoded = self._request_status_packet(CMD_GET_STATUS)

        if decoded is None:
            try:
                dev.close()
            except Exception:
                pass
            self._hid_device = None
            self._status.connected = False
            return False

        self._apply_decoded_status(decoded)
        return True

    def _send_ctrl_command(self, command: int, report_errors: bool = True) -> bool:
        if self._hid_device is None:
            return False

        self._seq = (self._seq + 1) & 0xFF
        packet = bytearray(REPORT_SIZE)
        packet[0] = REPORT_ID_CTRL_OUT
        packet[1] = command & 0xFF
        packet[2] = self._seq

        try:
            self._hid_device.send_feature_report(bytes(packet))
            return True
        except Exception:
            try:
                self._hid_device.close()
            except Exception:
                pass
            self._hid_device = None
            self._status.connected = False
            if report_errors:
                self._status.last_event = "HID send failed"
                self.connection_changed.emit(False)
                self.status_updated.emit(self._status)
            return False

    def _request_status_packet(self, command: int) -> dict | None:
        if self._hid_device is None:
            return None

        if not self._send_ctrl_command(command, report_errors=False):
            return None

        try:
            raw = self._hid_device.get_feature_report(REPORT_ID_STATUS_IN, REPORT_SIZE)
        except Exception:
            return None

        if not raw:
            return None

        return self._decode_status_packet(bytes(raw))

    def _apply_decoded_status(self, decoded: dict) -> None:
        msg_type = decoded["msg_type"]
        mode_raw = decoded["mode_raw"]
        busy = decoded["busy"]
        last_btn = decoded["last_btn"]
        any_pressed = decoded["any_pressed"]
        uptime_ms = decoded["uptime_ms"]
        report_count = decoded["report_count"]
        append_flags = decoded["append_flags"]
        pw_lengths = decoded["pw_lengths"]

        self._status.connected = self._hid_device is not None
        self._status.mode = DeviceMode(mode_raw) if mode_raw in (0, 1, 2) else DeviceMode.NON_TRUSTED
        self._status.uptime_ms = uptime_ms
        self._status.report_count = report_count
        self._status.last_active_button = (last_btn - 1) if 1 <= last_btn <= 6 else -1
        self._status.append_enter = append_flags

        masked_passwords: list[str] = []
        for length in pw_lengths:
            stars = max(3, min(length, 24))
            masked_passwords.append("*" * stars)
        self._status.passwords = masked_passwords

        if msg_type == MSG_PING_ACK:
            self._status.last_event = "Ping ACK"
        elif msg_type == MSG_STATUS:
            if busy:
                self._status.last_event = "Typing in progress"
            elif any_pressed:
                self._status.last_event = "Touch detected"
            else:
                self._status.last_event = "Status updated"
        else:
            self._status.last_event = "Status response"

    def _poll_status_once(self) -> None:
        if self._hid_device is None:
            return

        decoded = self._request_status_packet(CMD_GET_STATUS)
        if decoded is None:
            try:
                self._hid_device.close()
            except Exception:
                pass
            self._hid_device = None
            self._status.connected = False
            self._status.last_event = "HID status timeout"
            self.connection_changed.emit(False)
            self.status_updated.emit(self._status)
            return

        self._apply_decoded_status(decoded)

        self.status_updated.emit(self._status)

    def _decode_status_packet(self, data: bytes) -> dict | None:
        if len(data) == 0:
            return None

        def parse_candidate(packet: bytes) -> dict | None:
            if len(packet) < REPORT_SIZE:
                packet = packet + bytes(REPORT_SIZE - len(packet))

            msg_type = packet[1]
            mode_raw = packet[3]

            if msg_type not in (MSG_PING_ACK, MSG_STATUS, 0xFF):
                return None
            if mode_raw not in (0, 1, 2):
                return None

            return {
                "msg_type": msg_type,
                "mode_raw": mode_raw,
                "busy": packet[4],
                "last_btn": packet[6],
                "any_pressed": packet[7],
                "uptime_ms": int.from_bytes(packet[8:12], byteorder="little", signed=False),
                "report_count": int.from_bytes(packet[12:14], byteorder="little", signed=False),
                "append_flags": [bool(packet[14 + idx]) for idx in range(6)],
                "pw_lengths": [int(packet[20 + idx]) for idx in range(6)],
            }

        candidates: list[bytes] = []

        if len(data) >= REPORT_SIZE and data[0] == REPORT_ID_STATUS_IN:
            candidates.append(data[:REPORT_SIZE])

        if len(data) >= REPORT_SIZE and data[0] == 0 and data[1] == REPORT_ID_STATUS_IN:
            candidates.append(data[1 : 1 + REPORT_SIZE])

        if len(data) >= (REPORT_SIZE - 1):
            candidates.append(bytes([REPORT_ID_STATUS_IN]) + data[: REPORT_SIZE - 1])

        for start in range(0, min(4, len(data))):
            if len(data) >= start + REPORT_SIZE and data[start] == REPORT_ID_STATUS_IN:
                candidates.append(data[start : start + REPORT_SIZE])

        for packet in candidates:
            decoded = parse_candidate(packet)
            if decoded is not None:
                return decoded

        return None

    def _emit_heartbeat(self) -> None:
        if self._use_mock:
            if not self._status.connected:
                return
            self._pulse = (self._pulse + 1) % 6
            self._status.report_count += 1
            if self._uptime.isValid():
                self._status.uptime_ms = self._uptime.elapsed()
            self._status.last_active_button = self._pulse
            self._status.flashed_button = self._pulse
            self.status_updated.emit(self._status)
            self._status.flashed_button = -1
            return

        if self._hid_device is None:
            if self._try_open_hid_device():
                self._status.connected = True
                self._status.last_event = "Connected (HID)"
                self.connection_changed.emit(True)
                self.status_updated.emit(self._status)
            else:
                if self._status.connected:
                    self._status.connected = False
                    self._status.last_event = "Board not detected"
                    self.connection_changed.emit(False)
                    self.status_updated.emit(self._status)
            return

        self._poll_status_once()
