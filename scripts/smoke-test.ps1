# End-to-end smoke test without touching the mouse (see smoke-test.sh for the steps).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
& .\gradlew.bat runFenceIde -PdemoAutorun=normal -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# AI leg: if a local OpenAI-compatible model is running (Ollama by default) the analyst is enabled with
# auto-analysis of critical incidents and the checker verifies it ran inside the IDE. No key needed.
$aiArgs = @()
$exitAfter = 15000
$aiEndpoint = ""; $aiModel = ""; $aiKey = $env:PLUGINFENCE_AI_KEY
if ($env:PLUGINFENCE_AI_ENDPOINT) {                       # explicit override wins
    $aiEndpoint = $env:PLUGINFENCE_AI_ENDPOINT
    $aiModel = if ($env:PLUGINFENCE_AI_MODEL) { $env:PLUGINFENCE_AI_MODEL } else { "openai/gpt-oss-120b" }
} elseif ($env:GROQ_API_KEY) {                            # a real hosted model, if we have a key
    $aiEndpoint = "https://api.groq.com/openai/v1"
    $aiModel = if ($env:PLUGINFENCE_AI_MODEL) { $env:PLUGINFENCE_AI_MODEL } elseif ($env:GROQ_MODEL) { $env:GROQ_MODEL } else { "openai/gpt-oss-120b" }
    $aiKey = $env:GROQ_API_KEY
} elseif ($env:OPENAI_API_KEY) {
    $aiEndpoint = "https://api.openai.com/v1"
    $aiModel = if ($env:PLUGINFENCE_AI_MODEL) { $env:PLUGINFENCE_AI_MODEL } else { "gpt-4.1-mini" }
    $aiKey = $env:OPENAI_API_KEY
} else {                                                   # local fallback
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "http://127.0.0.1:11434/api/tags" | Out-Null
        $aiEndpoint = "http://127.0.0.1:11434/v1"
        $aiModel = if ($env:PLUGINFENCE_AI_MODEL) { $env:PLUGINFENCE_AI_MODEL } else { "llama3.2:3b" }
    } catch {}
}
if ($aiEndpoint) {
    $aiArgs = @("-PaiEndpoint=$aiEndpoint", "-PaiModel=$aiModel", "-PaiAutoAnalyse=true")
    # The key reaches the sandbox IDE by environment inheritance, not as a Gradle property: -PaiKey
    # becomes a -D JVM option and IntelliJ writes its full JVM options into idea.log. A tool that
    # audits credential handling should not leave a key in a log file.
    if ($aiKey) {
        if ($aiEndpoint -like "*groq.com*") { $env:GROQ_API_KEY = $aiKey } else { $env:OPENAI_API_KEY = $aiKey }
    }
    $exitAfter = 120000
    $env:PLUGINFENCE_SMOKE_AI = "1"
    Write-Host "AI leg enabled: $aiModel at $aiEndpoint"
} else {
    Write-Host "AI leg skipped: set GROQ_API_KEY / OPENAI_API_KEY, or start Ollama on 127.0.0.1:11434"
}
& .\gradlew.bat runFenceIdeUpdated "-PdemoAutorun=normal,secret,exfil,process,attack" "-PdemoExitAfter=$exitAfter" -PdemoOpenToolWindows=true @aiArgs --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
python scripts/check-sandbox-state.py
exit $LASTEXITCODE
