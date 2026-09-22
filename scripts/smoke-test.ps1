# End-to-end smoke test without touching the mouse (see smoke-test.sh for the steps).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
& .\gradlew.bat runFenceIde -PdemoAutorun=normal -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# AI leg: if a local OpenAI-compatible model is running (Ollama by default) the analyst is enabled with
# auto-analysis of critical incidents and the checker verifies it ran inside the IDE. No key needed.
$aiEndpoint = if ($env:PLUGINFENCE_AI_ENDPOINT) { $env:PLUGINFENCE_AI_ENDPOINT } else { "http://127.0.0.1:11434/v1" }
$aiArgs = @()
$exitAfter = 15000
$modelUp = $false
if ($env:PLUGINFENCE_AI_ENDPOINT) { $modelUp = $true } else {
    try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 ($aiEndpoint -replace "/v1$", "/api/tags") | Out-Null; $modelUp = $true } catch {}
}
if ($modelUp) {
    $aiModel = if ($env:PLUGINFENCE_AI_MODEL) { $env:PLUGINFENCE_AI_MODEL } else { "llama3.2:3b" }
    $aiArgs = @("-PaiEndpoint=$aiEndpoint", "-PaiModel=$aiModel", "-PaiAutoAnalyse=true")
    if ($env:PLUGINFENCE_AI_KEY) { $aiArgs += "-PaiKey=$($env:PLUGINFENCE_AI_KEY)" }
    $exitAfter = 120000
    $env:PLUGINFENCE_SMOKE_AI = "1"
    Write-Host "AI leg enabled: $aiModel at $aiEndpoint"
} else {
    Write-Host "AI leg skipped: no model at $aiEndpoint (start Ollama, or set PLUGINFENCE_AI_ENDPOINT)"
}
& .\gradlew.bat runFenceIdeUpdated "-PdemoAutorun=normal,secret,exfil,process,attack" "-PdemoExitAfter=$exitAfter" -PdemoOpenToolWindows=true @aiArgs --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python scripts/check-sandbox-state.py
exit $LASTEXITCODE
