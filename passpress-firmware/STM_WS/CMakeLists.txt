cmake_minimum_required(VERSION 3.22)

#
# This file is generated only once,
# and is not re-generated if converter is called multiple times.
#
# User is free to modify the file as much as necessary
#

# Setup compiler settings
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)


# Define the build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE "Debug")
endif()

# Set the project name
set(CMAKE_PROJECT_NAME STM_WS)

# Enable compile command to ease indexing with e.g. clangd
set(CMAKE_EXPORT_COMPILE_COMMANDS TRUE)

# Core project settings
project(${CMAKE_PROJECT_NAME})
message("Build type: " ${CMAKE_BUILD_TYPE})

# Enable CMake support for ASM and C languages
enable_language(C ASM)

# Create an executable object type
add_executable(${CMAKE_PROJECT_NAME})

set(APP_FLASH_ORIGIN "" CACHE STRING "Flash origin for a relocated application image")

if(APP_FLASH_ORIGIN)
    set(_passpress_linker_template "${CMAKE_SOURCE_DIR}/STM32F042XX_FLASH.ld")
    set(_passpress_linker_output "${CMAKE_BINARY_DIR}/STM32F042XX_RELOCATED.ld")
    file(READ "${_passpress_linker_template}" _passpress_linker_contents)
    string(REPLACE "ORIGIN = 0x8000000" "ORIGIN = ${APP_FLASH_ORIGIN}" _passpress_linker_contents "${_passpress_linker_contents}")
    string(REPLACE "ORIGIN = 0x08000000" "ORIGIN = ${APP_FLASH_ORIGIN}" _passpress_linker_contents "${_passpress_linker_contents}")
    file(WRITE "${_passpress_linker_output}" "${_passpress_linker_contents}")
    set(LINKER_SCRIPT "${_passpress_linker_output}")
    set(LINKER_SCRIPT "${_passpress_linker_output}" CACHE FILEPATH "Linker script for the active application image" FORCE)
endif()

# Add STM32CubeMX generated sources
add_subdirectory(cmake/stm32cubemx)

include(cmake/passpress_relocated_app.cmake)

# Link directories setup
target_link_directories(${CMAKE_PROJECT_NAME} PRIVATE
    # Add user defined library search paths
)

# Add sources to executable
target_sources(${CMAKE_PROJECT_NAME} PRIVATE
    # Add user sources here
    Core/Src/touch_password.c
)

# Add include paths
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    # Add user defined include paths
)

# Add project symbols (macros)
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    # Add user defined symbols
)

# Remove wrong libob.a library dependency when using cpp files
list(REMOVE_ITEM CMAKE_C_IMPLICIT_LINK_LIBRARIES ob)

# Add linked libraries
target_link_libraries(${CMAKE_PROJECT_NAME}
    stm32cubemx

    # Add user defined libraries
)
