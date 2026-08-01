from dataclasses import dataclass
from enum import IntEnum


class DeviceMode(IntEnum):
    NON_TRUSTED = 0
    TRUSTED = 1
    ADMIN = 2


@dataclass(slots=True)
class DeviceStatus:
    connected: bool
    mode: DeviceMode
    passwords: list[str]
    append_enter: list[bool]
    flashed_button: int = -1
    uptime_ms: int = 0
    report_count: int = 0
    last_active_button: int = -1
    last_event: str = "Idle"


EMPTY_STATUS = DeviceStatus(
    connected=False,
    mode=DeviceMode.NON_TRUSTED,
    passwords=["" for _ in range(6)],
    append_enter=[True for _ in range(6)],
    flashed_button=-1,
    uptime_ms=0,
    report_count=0,
    last_active_button=-1,
    last_event="Disconnected",
)
