from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import zlib
from dataclasses import replace
from pathlib import Path

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
CMD_SET_MODE = 0x03
CMD_GET_BUTTON_CONFIG = 0x04
CMD_SET_BUTTON_CONFIG = 0x05
CMD_ENTER_BOOTLOADER = 0x06
CMD_BOOT_TRANSFER = 0x10

MSG_PING_ACK = 0x81
MSG_STATUS = 0x82
MSG_BUTTON_CONFIG = 0x83
MSG_BOOT_ACK = 0x90

BOOT_FLAG_MORE = 0x00
BOOT_FLAG_LAST = 0x01
BOOT_FLAG_CRC = 0x02
BOOT_FLAG_APP_ADDR = 0x04

PASSWORD_MAX_LEN = 27


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
        self._button_cache: list[str | None] = [None for _ in range(6)]
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
        self._button_cache = [None for _ in range(6)]
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
        if password != "admin123":
            self._status.last_event = "Admin verification failed"
            self.status_updated.emit(self._status)
            return False

        if not self._use_mock:
            self.set_mode(DeviceMode.ADMIN)
            if self._status.mode == DeviceMode.ADMIN:
                self._status.last_event = "Admin verified"
                self.status_updated.emit(self._status)
                return True
            return False

        if not self._status.connected:
            return False
        self._status.mode = DeviceMode.ADMIN
        self._status.last_event = "Admin verified"
        self.status_updated.emit(self._status)
        return True

    def get_last_event(self) -> str:
        return self._status.last_event

    def set_mode(self, mode: DeviceMode) -> None:
        if not self._use_mock:
            if (not self._status.connected) or (self._hid_device is None):
                self._status.last_event = "Board not connected"
                self.status_updated.emit(self._status)
                return

            if not self._send_ctrl_command(CMD_SET_MODE, payload=bytes([int(mode)])):
                return

            import time
            expected_seq = self._seq
            for _ in range(25):
                time.sleep(0.01)
                try:
                    raw = self._hid_device.get_feature_report(REPORT_ID_STATUS_IN, REPORT_SIZE)
                    if raw and len(raw) > 2:
                        start = 1 if raw[0] == 0 else 0
                        if raw[start] == REPORT_ID_STATUS_IN and raw[start + 2] == expected_seq:
                            break
                except Exception:
                    pass

            self._poll_status_once()
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
        if not self._status.connected or self._status.mode != DeviceMode.ADMIN:
            return False
        if index < 0 or index >= 6:
            return False

        if self._use_mock:
            self._status.passwords[index] = password
            self._status.append_enter[index] = append_enter
            self._button_cache[index] = password
            self._status.last_event = f"Saved Button {index + 1}"
            self.status_updated.emit(self._status)
            return True

        encoded_password = self._encode_password(password)
        if encoded_password is None:
            self._status.last_event = "Password must be ASCII"
            self.status_updated.emit(self._status)
            return False

        payload = bytes([index, 1 if append_enter else 0, len(encoded_password)]) + encoded_password
        if not self._send_ctrl_command(CMD_SET_BUTTON_CONFIG, payload=payload):
            return False

        import time
        expected_seq = self._seq
        dev = self._hid_device
        for _ in range(25):
            if dev is None:
                break
            time.sleep(0.01)
            try:
                raw = dev.get_feature_report(REPORT_ID_STATUS_IN, REPORT_SIZE)
                if raw and len(raw) > 2:
                    start = 1 if raw[0] == 0 else 0
                    if raw[start] == REPORT_ID_STATUS_IN and raw[start + 2] == expected_seq:
                        break
            except Exception:
                pass

        decoded = self._request_button_config(index)
        if decoded is not None:
            self._button_cache[index] = decoded["password"]
            self._status.passwords[index] = decoded["password"]
            self._status.append_enter[index] = decoded["append_enter"]
        else:
            self._button_cache[index] = password
            self._status.passwords[index] = password
            self._status.append_enter[index] = append_enter
        self._status.last_event = f"Saved Button {index + 1}"
        self.status_updated.emit(self._status)
        return True

    def get_button_config(self, index: int) -> bool:
        if index < 0 or index >= 6:
            return False

        if self._use_mock:
            self.status_updated.emit(self._status)
            return True

        if not self._status.connected or self._hid_device is None or self._status.mode != DeviceMode.ADMIN:
            return False

        decoded = self._request_button_config(index)
        if decoded is None:
            return False

        self._button_cache[index] = decoded["password"]
        self._status.passwords[index] = decoded["password"]
        self._status.append_enter[index] = decoded["append_enter"]
        self.status_updated.emit(self._status)
        return True

    def enter_bootloader(self) -> bool:
        if self._use_mock:
            self._status.last_event = "Entered bootloader (mock)"
            self.status_updated.emit(self._status)
            return True

        if not self._status.connected or self._hid_device is None:
            self._status.last_event = "Board not connected"
            self.status_updated.emit(self._status)
            return False

        if not self._send_ctrl_command(CMD_ENTER_BOOTLOADER):
            return False

        import time
        # Wait briefly for device to reset and disconnect
        dev = self._hid_device
        for _ in range(100):
            if dev is None:
                break
            time.sleep(0.02)
            try:
                # Try to read status; if device gone this will fail
                _ = dev.get_feature_report(REPORT_ID_STATUS_IN, REPORT_SIZE)
            except Exception:
                # Device likely reset/entered bootloader
                self._status.last_event = "Bootloader triggered"
                self._status.connected = False
                self.connection_changed.emit(False)
                self.status_updated.emit(self._status)
                self._hid_device = None
                return True

        # If still present, report that bootloader trigger may have failed
        self._status.last_event = "Bootloader trigger timed out"
        self.status_updated.emit(self._status)
        return False

    def _find_objcopy(self) -> str | None:
        candidates = [
            "arm-none-eabi-objcopy",
            "arm-none-eabi-objcopy.exe",
            "llvm-objcopy",
            "objcopy",
        ]
        for name in candidates:
            path = shutil.which(name)
            if path:
                return path
        return None

    def _load_firmware_payload(self, file_path: str) -> bytes | None:
        suffix = Path(file_path).suffix.lower()
        if suffix == ".bin":
            try:
                with open(file_path, "rb") as f:
                    return f.read()
            except Exception:
                return None

        if suffix not in (".elf", ".hex"):
            return None

        objcopy = self._find_objcopy()
        if objcopy is None:
            self._status.last_event = "objcopy not found (needed for ELF/HEX)"
            self.status_updated.emit(self._status)
            return None

        tmp_path: str | None = None
        try:
            with tempfile.NamedTemporaryFile(prefix="passpress_fw_", suffix=".bin", delete=False) as tmp:
                tmp_path = tmp.name

            result = subprocess.run([objcopy, "-O", "binary", file_path, tmp_path], capture_output=True, text=True)
            if result.returncode != 0:
                self._status.last_event = "objcopy conversion failed"
                self.status_updated.emit(self._status)
                return None

            with open(tmp_path, "rb") as f:
                return f.read()
        except Exception:
            return None
        finally:
            if tmp_path and os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except Exception:
                    pass

    def _encode_password(self, password: str) -> bytes | None:
        try:
            encoded = password.encode("ascii")
        except UnicodeEncodeError:
            return None

        if len(encoded) > PASSWORD_MAX_LEN:
            return None
        return encoded

    def disconnect_device(self) -> None:
        if self._hid_device is not None:
            try:
                self._hid_device.close()
            except Exception:
                pass
            self._hid_device = None

        self._status = replace(EMPTY_STATUS)
        self._status.last_event = "Disconnected"
        self._button_cache = [None for _ in range(6)]
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

    def _send_ctrl_command(self, command: int, report_errors: bool = True, payload: bytes | None = None) -> bool:
        if self._hid_device is None:
            return False

        self._seq = (self._seq + 1) & 0xFF
        packet = bytearray(REPORT_SIZE)
        packet[0] = REPORT_ID_CTRL_OUT
        packet[1] = command & 0xFF
        packet[2] = self._seq
        if payload:
            copy_len = min(len(payload), REPORT_SIZE - 3)
            packet[3 : 3 + copy_len] = payload[:copy_len]

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
        for idx, length in enumerate(pw_lengths):
            stars = max(3, min(length, 24))
            cached = self._button_cache[idx]
            if self._status.mode == DeviceMode.ADMIN and cached is not None:
                masked_passwords.append(cached)
            else:
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

    def _request_button_config(self, index: int) -> dict | None:
        if self._hid_device is None:
            return None

        if not self._send_ctrl_command(CMD_GET_BUTTON_CONFIG, payload=bytes([index])):
            return None

        import time
        expected_seq = self._seq
        for _ in range(25):
            time.sleep(0.01)
            try:
                raw = self._hid_device.get_feature_report(REPORT_ID_STATUS_IN, REPORT_SIZE)
                if raw and len(raw) > 2:
                    start = 1 if raw[0] == 0 else 0
                    if raw[start] == REPORT_ID_STATUS_IN and raw[start + 2] == expected_seq:
                        return self._decode_button_config_packet(bytes(raw))
            except Exception:
                pass

        return None

    def _decode_button_config_packet(self, data: bytes) -> dict | None:
        if len(data) == 0:
            return None

        candidates: list[bytes] = []
        if len(data) >= REPORT_SIZE and data[0] == REPORT_ID_STATUS_IN:
            candidates.append(data[:REPORT_SIZE])
        if len(data) >= REPORT_SIZE and data[0] == 0 and data[1] == REPORT_ID_STATUS_IN:
            candidates.append(data[1 : 1 + REPORT_SIZE])
        if len(data) >= (REPORT_SIZE - 1):
            candidates.append(bytes([REPORT_ID_STATUS_IN]) + data[: REPORT_SIZE - 1])

        for packet in candidates:
            if len(packet) < 6 or packet[0] != REPORT_ID_STATUS_IN or packet[1] != MSG_BUTTON_CONFIG:
                continue

            button_idx = int(packet[3])
            append_enter = bool(packet[4])
            password_len = int(packet[5])
            if button_idx < 0 or button_idx >= 6 or password_len > PASSWORD_MAX_LEN:
                continue

            end = 6 + password_len
            if end > len(packet):
                continue

            try:
                password = packet[6:end].decode("ascii")
            except UnicodeDecodeError:
                continue

            return {
                "button_idx": button_idx,
                "append_enter": append_enter,
                "password": password,
            }

        return None

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

    def _open_device_direct(self) -> bool:
        """Try to open any matching HID device without performing the normal app handshake."""
        if hid is None:
            return False

        # Try enumerate first
        try:
            entries = hid.enumerate(HID_VID, 0)
        except Exception:
            entries = []

        for pid in HID_PIDS:
            for entry in entries:
                try:
                    if int(entry.get("vendor_id", 0)) != HID_VID:
                        continue
                    if int(entry.get("product_id", 0)) != pid:
                        continue
                    path = entry.get("path")
                    if not path:
                        continue
                    dev = hid.device()
                    dev.open_path(path)
                    try:
                        dev.set_nonblocking(1)
                    except Exception:
                        pass
                    self._hid_device = dev
                    return True
                except Exception:
                    continue

        # Last resort: open by VID/PID
        for pid in HID_PIDS:
            try:
                dev = hid.device()
                dev.open(HID_VID, pid)
                try:
                    dev.set_nonblocking(1)
                except Exception:
                    pass
                self._hid_device = dev
                return True
            except Exception:
                continue

        return False

    def upload_firmware(
        self,
        file_path: str,
        chunk_size: int = 27,
        app_start_addr: int = 0x08002000,
        progress_cb=None,
    ) -> bool:
        """Upload BIN/ELF/HEX firmware using bootloader chunked HID packets."""
        if not os.path.isfile(file_path):
            self._status.last_event = f"File not found: {file_path}"
            self.status_updated.emit(self._status)
            return False

        data = self._load_firmware_payload(file_path)
        if data is None:
            self._status.last_event = "Failed to read/convert firmware file"
            self.status_updated.emit(self._status)
            return False

        total_len = len(data)
        if total_len == 0:
            self._status.last_event = "Firmware file is empty"
            self.status_updated.emit(self._status)
            return False

        # Trigger bootloader
        if not self.enter_bootloader():
            return False

        import time

        # Wait for device to disappear and reappear as bootloader.
        start = time.time()
        opened = False
        while time.time() - start < 10.0:
            time.sleep(0.2)
            if self._open_device_direct():
                opened = True
                break

        if not opened or self._hid_device is None:
            self._status.last_event = "Bootloader not detected"
            self.status_updated.emit(self._status)
            return False

        def _wait_ack(expected_seq: int, timeout_s: float) -> bool:
            dev = self._hid_device
            if dev is None:
                return False
            ack_start = time.time()
            while time.time() - ack_start < timeout_s:
                try:
                    raw = dev.get_feature_report(REPORT_ID_STATUS_IN, REPORT_SIZE)
                    if raw and len(raw) > 2:
                        start_idx = 1 if raw[0] == 0 else 0
                        if raw[start_idx] == REPORT_ID_STATUS_IN and raw[start_idx + 1] == MSG_BOOT_ACK and raw[start_idx + 2] == expected_seq:
                            return (raw[start_idx + 3] == 0)
                except Exception:
                    pass
                time.sleep(0.05)
            return False

        # Packet 1: application start address so bootloader writes to correct region.
        seq = 1
        addr_packet = bytearray(REPORT_SIZE)
        addr_packet[0] = REPORT_ID_CTRL_OUT
        addr_packet[1] = CMD_BOOT_TRANSFER & 0xFF
        addr_packet[2] = seq
        addr_packet[3] = BOOT_FLAG_APP_ADDR
        addr_packet[4] = 4
        addr_packet[5] = 0
        addr_packet[6:10] = int(app_start_addr).to_bytes(4, byteorder="little", signed=False)
        try:
            self._hid_device.send_feature_report(bytes(addr_packet))
        except Exception:
            self._status.last_event = "HID send failed during address setup"
            self.status_updated.emit(self._status)
            return False

        if not _wait_ack(seq, 3.0):
            self._status.last_event = "Bootloader address ACK timeout"
            self.status_updated.emit(self._status)
            return False

        # Send image chunks.
        offset = 0
        while offset < total_len:
            chunk = data[offset : offset + chunk_size]
            seq = (seq + 1) & 0xFF
            flags = BOOT_FLAG_MORE
            if (offset + len(chunk)) >= total_len:
                flags = BOOT_FLAG_LAST

            packet = bytearray(REPORT_SIZE)
            packet[0] = REPORT_ID_CTRL_OUT
            packet[1] = CMD_BOOT_TRANSFER & 0xFF
            packet[2] = seq
            packet[3] = flags
            packet[4] = len(chunk) & 0xFF
            packet[5] = (len(chunk) >> 8) & 0xFF
            packet[6 : 6 + len(chunk)] = chunk

            try:
                self._hid_device.send_feature_report(bytes(packet))
            except Exception:
                self._status.last_event = "HID send failed during upload"
                self.status_updated.emit(self._status)
                return False

            if not _wait_ack(seq, 3.0):
                self._status.last_event = f"Bootloader ACK timeout seq={seq}"
                self.status_updated.emit(self._status)
                return False

            offset += len(chunk)
            if progress_cb:
                try:
                    progress_cb(offset, total_len)
                except Exception:
                    pass

        # Final packet: CRC32
        crc = zlib.crc32(data) & 0xFFFFFFFF
        seq = (seq + 1) & 0xFF
        crc_packet = bytearray(REPORT_SIZE)
        crc_packet[0] = REPORT_ID_CTRL_OUT
        crc_packet[1] = CMD_BOOT_TRANSFER & 0xFF
        crc_packet[2] = seq
        crc_packet[3] = BOOT_FLAG_CRC
        crc_packet[4] = 4
        crc_packet[5] = 0
        crc_packet[6:10] = crc.to_bytes(4, byteorder="little")

        try:
            self._hid_device.send_feature_report(bytes(crc_packet))
        except Exception:
            self._status.last_event = "HID send failed during CRC"
            self.status_updated.emit(self._status)
            return False

        if not _wait_ack(seq, 10.0):
            self._status.last_event = "Bootloader final ACK timeout"
            self.status_updated.emit(self._status)
            return False

        self._status.last_event = f"Firmware upload complete ({total_len} bytes)"
        self.status_updated.emit(self._status)
        return True
