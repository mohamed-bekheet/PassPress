# PassPress Embedded Firmware (`passpress-firmware`)

Welcome to the **PassPress Embedded Firmware** repository. This directory contains C/C++ firmware code, bootloader, driver libraries, and build scripts for the **PassPress** hardware security keyboard device.

---

## Directory Structure

```
passpress-firmware/
├── STM_WS/                    # Active STM32 C/C++ Firmware Workspace
│   ├── Core/                  # Main application entry point, system init, and interrupts
│   ├── Bootloader/            # Custom bootloader for firmware updates
│   ├── USB_DEVICE/            # USB HID Keyboard Device Class driver implementation
│   ├── TOUCHSENSING/          # Capacitive touch sensing library configuration
│   ├── GUI/                   # OLED/LCD user interface display driver
│   ├── Drivers/               # STM32F0xx HAL and CMSIS driver sources
│   ├── Middlewares/           # ST USB Device library & ST TouchSensing middleware
│   ├── CMakeLists.txt         # CMake cross-compilation configuration for ARM GCC
│   ├── CMakePresets.json      # Preconfigured CMake build profiles
│   ├── STM32F042XX_FLASH.ld   # Linker script for flash memory layout
│   └── flash cmd.txt          # Command line scripts for flashing via OpenOCD / ST-Link
├── Archive/                   # Historical firmware builds & archived workspace iterations
└── README.md                  # This documentation file
```

---

## Technical Stack & Microcontroller Architecture

- **Microcontroller**: STM32F042XX (ARM Cortex-M0 core running up to 48 MHz).
- **Toolchain**: ARM GNU Toolchain (`arm-none-eabi-gcc`), CMake, Ninja / Make.
- **USB Class**: Human Interface Device (HID) Keyboard class for driverless plug-and-play operation on Windows, macOS, Linux, and Android.
- **Peripherals**:
  - **USB**: Full-speed USB peripheral for HID keypress injection.
  - **Touch Sensing (TSC)**: Capacitive touch button detection for PIN entry / button input.
  - **SPI/I2C**: Display interface for rendering GUI menus and authentication prompts.

---

## Flashing & Debugging

### Flashing via OpenOCD / ST-Link
Refer to [flash cmd.txt](file:///e:/Personal/Pass_Press/passpress-firmware/STM_WS/flash%20cmd.txt) for OpenOCD flashing commands:

```bash
openocd -f interface/stlink.cfg -f target/stm32f0x.cfg -c "program build/STM_WS.elf verify reset exit"
```

---

## Archival Builds

Older firmware iterations and touch calibration binaries are safely stored in [Archive/](file:///e:/Personal/Pass_Press/passpress-firmware/Archive/).
