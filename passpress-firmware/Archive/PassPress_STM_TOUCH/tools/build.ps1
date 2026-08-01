$ErrorActionPreference = 'Stop'

$cmakeCmd = Get-Command cmake -ErrorAction SilentlyContinue
if ($cmakeCmd) {
    $cmake = $cmakeCmd.Source
} else {
    $candidates = @(
        "$env:ProgramFiles\CMake\bin\cmake.exe",
        "$env:ProgramFiles(x86)\CMake\bin\cmake.exe"
    )

    $cmake = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $cmake) {
    throw 'CMake not found. Install CMake or add it to PATH.'
}

& $cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=cmake/arm-gcc-toolchain.cmake -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $cmake --build build -j
exit $LASTEXITCODE
