cmake_minimum_required(VERSION 3.20)

project(PassPress_STM_TOUCH C ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)

set(MCU_FLAGS
    -mcpu=cortex-m0
    -mthumb
)

set(COMMON_FLAGS
    ${MCU_FLAGS}
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -Wpedantic
)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${COMMON_FLAGS}")
set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} ${COMMON_FLAGS} -x assembler-with-cpp")

add_executable(${PROJECT_NAME}.elf
    src/main.c
    startup/startup_stm32f042x6.s
)

target_link_options(${PROJECT_NAME}.elf PRIVATE
    ${MCU_FLAGS}
    -T${CMAKE_SOURCE_DIR}/linker/STM32F042G6UX_FLASH.ld
    -Wl,--gc-sections
    -Wl,-Map=${PROJECT_NAME}.map
    -specs=nano.specs
    -specs=nosys.specs
)

add_custom_command(TARGET ${PROJECT_NAME}.elf POST_BUILD
    COMMAND arm-none-eabi-size ${PROJECT_NAME}.elf
    COMMAND arm-none-eabi-objcopy -O ihex ${PROJECT_NAME}.elf ${PROJECT_NAME}.hex
    COMMAND arm-none-eabi-objcopy -O binary ${PROJECT_NAME}.elf ${PROJECT_NAME}.bin
)
