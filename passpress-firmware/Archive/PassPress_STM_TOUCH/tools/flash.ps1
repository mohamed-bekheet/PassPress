param(
    [string]$ElfPath = 'build/PassPress_STM_TOUCH.elf'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $ElfPath)) {
    throw "ELF file not found: $ElfPath. Run build first."
}

$cliCmd = Get-Command STM32_Programmer_CLI -ErrorAction SilentlyContinue
if ($cliCmd) {
    $cli = $cliCmd.Source
} else {
    $candidates = @(
        "$env:ProgramFiles\STMicroelectronics\STM32Cube\STM32CubeProgrammer\bin\STM32_Programmer_CLI.exe",
        "$env:ProgramFiles(x86)\STMicroelectronics\STM32Cube\STM32CubeProgrammer\bin\STM32_Programmer_CLI.exe"
    )

    $cli = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $cli) {
    throw 'STM32_Programmer_CLI not found. Install STM32CubeProgrammer or add it to PATH.'
}

& $cli -c port=SWD -w $ElfPath -v -rst
exit $LASTEXITCODE
