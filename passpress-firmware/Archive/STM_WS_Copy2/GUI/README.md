# PASSPRESS HID Control Center (Initial Implementation)

This is the first implementation pass for the desktop utility under the `GUI` folder.

## Current Scope

- Portable-friendly project scaffold (PySide6 + onefile build script).
- Main dark UI window with:
  - Digital twin board panel.
  - 6 relative-coordinate interactive hotspots.
  - Hover glow and live flash feedback.
  - Mode-driven drawer behavior:
    - Non-Trusted: masked password view.
    - Trusted: plain text read-only.
    - Admin: editable + save button.
  - Status bar with connection and security tier color.
- Transport abstraction (`HidTransport`) with mock heartbeat and mode transitions:
  - `GET_STATUS` behavior (simulated via periodic status updates).
  - `VERIFY_ADMIN` behavior (mock credential check).
  - `WRITE_DATA` behavior (mock save to in-memory device state).

## Board Picture Location

Place the board image file in:

- `GUI/assets/board.png` (preferred)
- `GUI/assets/board.jpg`
- `GUI/assets/board.jpeg`

The app auto-loads the first one found.

## Zero-Cache Note

- No local files, settings, registry, or cache are used by the app.
- Runtime state exists only in memory and is wiped on disconnect event.

## Run (Development)

```powershell
cd GUI
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python main.py
```

## Build Portable EXE

```powershell
cd GUI
.\build_portable.bat
```

Output:
- `GUI\dist\PASSPRESS-HID-Control-Center.exe`

## Next Iteration

- Replace mock `HidTransport` internals with real HID Feature Reports.
- Add real board image asset and fine-tuned hotspot coordinates.
- Add Security tab flows (change trusted PIN sequence, change admin password).
- Add inactivity auto-lock behavior synced with hardware.
