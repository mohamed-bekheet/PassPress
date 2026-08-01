Tiny bootloader scaffold

Purpose
- Provide a minimal bootloader that checks a RAM reset-request magic and, when present, stays in bootloader mode to accept a chunked HID update from GUI.

Notes
- This repository now contains a protocol/state-machine scaffold in `src/bootloader_stub.c`.
- You still need to wire `Boot_ReceivePacket()` and `Boot_SendAck()` to your USB HID transport.
- To use it you must:
  1. Build and flash the bootloader to flash base (0x08000000).
  2. Re-link the application to run at a higher address (example: 0x08002000).

Minimal recommended flow
- Bootloader at 0x08000000 (small size, e.g. 8KB)
- Application linked at 0x08002000
- App uses `TouchPassword_EnterBootloader()` to set a RAM magic at 0x200017FC and reset.
- Bootloader consumes that RAM magic and enters update mode.

Protocol expected by GUI
- Packet size: 33 bytes.
- GUI sends `CMD_BOOT_TRANSFER (0x10)` packets:
  1. `BOOT_FLAG_APP_ADDR (0x04)`: payload len=4, little-endian app base address.
  2. Data chunks: `BOOT_FLAG_MORE (0x00)` or `BOOT_FLAG_LAST (0x01)`, payload <= 27 bytes.
  3. Final CRC: `BOOT_FLAG_CRC (0x02)`, payload len=4, CRC32 over full image.
- Bootloader responds with ACK (`MSG_BOOT_ACK = 0x90`) containing seq and status code.

Safety
- Bootloader validates CRC before jumping to app.
- Keep bootloader region immutable and never allow writes into bootloader pages.

Building
- Create a separate CMake target that uses a linker script placing vectors at 0x08000000 for the bootloader.
- Reconfigure the main app linker script to app start address (example: 0x08002000).

Flashing
- First flash bootloader once (ST-Link/CubeProgrammer) at 0x08000000.
- Then for normal updates use GUI Firmware Update with:
  - file: `.bin`, `.elf`, or `.hex`
  - app address: same address used by app linker (example: 0x08002000)
- GUI converts ELF/HEX to BIN using `objcopy` automatically (if available on PATH).

Next required integration
- Implement HID RX/TX in `Boot_ReceivePacket()` and `Boot_SendAck()`.
- Add bootloader CMake target and linker scripts for dual-image build.
