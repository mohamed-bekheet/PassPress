# PassPress STM32F042 Blink

Minimal GCC + CMake firmware for `STM32F042G6U6TR` that blinks `PB0` and `PB1`.

## Prerequisites

- `arm-none-eabi-gcc` toolchain in PATH
- `cmake` in PATH
- `STM32_Programmer_CLI` in PATH (from STM32CubeProgrammer)
- ST-Link connected to target via SWD

## Build

```powershell
cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=cmake/arm-gcc-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

Output files:

- `build/PassPress_STM_TOUCH.elf`
- `build/PassPress_STM_TOUCH.hex`
- `build/PassPress_STM_TOUCH.bin`

## Flash

```powershell
STM32_Programmer_CLI -c port=SWD -w build/PassPress_STM_TOUCH.elf -v -rst
```

## VS Code

Tasks are provided in `.vscode/tasks.json`:

- `cmake-configure`
- `cmake-build`
- `flash-stm32f0`

Optional debug launch config (OpenOCD + Cortex-Debug) is in `.vscode/launch.json`.
