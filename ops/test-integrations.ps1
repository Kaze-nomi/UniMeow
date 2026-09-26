param([string]$Python = "python", [switch]$KeepRunning)
$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "test_integrations.py"
if ($KeepRunning) { & $Python $runner --keep-running } else { & $Python $runner }
exit $LASTEXITCODE
