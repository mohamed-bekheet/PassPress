from __future__ import annotations

from dataclasses import replace

from PySide6.QtCore import QObject, QElapsedTimer, QTimer, Signal

from .models import DeviceMode, DeviceStatus, EMPTY_STATUS


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
        self._status.connected = True
        self._status.last_event = "Ready"
        self._heartbeat = QTimer(self)
        self._heartbeat.setInterval(300)
        self._heartbeat.timeout.connect(self._emit_heartbeat)
        self._uptime = QElapsedTimer()
        self._pulse = 0

    def start(self) -> None:
        self._uptime.start()
        self._status.uptime_ms = 0
        self._status.report_count = 0
        self._status.last_event = "Connected"
        self._heartbeat.start()
        self.status_updated.emit(self._status)
        self.connection_changed.emit(self._status.connected)

    def stop(self) -> None:
        self._heartbeat.stop()

    def verify_admin(self, password: str) -> bool:
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
        self._status.passwords[index] = password
        self._status.append_enter[index] = append_enter
        self._status.last_event = f"Saved Button {index + 1}"
        self.status_updated.emit(self._status)
        return True

    def disconnect(self) -> None:
        self._status = replace(EMPTY_STATUS)
        self._status.last_event = "Disconnected"
        self.status_updated.emit(self._status)
        self.connection_changed.emit(False)

    def _emit_heartbeat(self) -> None:
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
