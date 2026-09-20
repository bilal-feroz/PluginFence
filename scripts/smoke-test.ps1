# End-to-end smoke test without touching the mouse (see smoke-test.sh for the steps).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
& .\gradlew.bat runFenceIde -PdemoAutorun=normal -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& .\gradlew.bat runFenceIdeUpdated "-PdemoAutorun=normal,secret,exfil,process,attack" -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python scripts/check-sandbox-state.py
exit $LASTEXITCODE
