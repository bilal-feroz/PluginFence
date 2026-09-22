#!/usr/bin/env bash
# End-to-end smoke test without touching the mouse:
#   1. IDE + agent + Demo Helper 1.0.0, scripted "normal" behaviour, exit  -> baseline recorded
#   2. IDE + agent + Demo Helper 1.1.0, scripted attack actions, exit      -> blocks, incident, drift
#   3. assert on the persisted PluginFence state in the sandbox
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew runFenceIde        -PdemoAutorun=normal -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
# AI leg: if a local OpenAI-compatible model is running (Ollama by default), the analyst is enabled
# with auto-analysis of critical incidents, and the checker verifies it ran inside the IDE. No key,
# nothing leaves the machine. Override with PLUGINFENCE_AI_ENDPOINT / PLUGINFENCE_AI_MODEL / PLUGINFENCE_AI_KEY.
AI_ARGS=()
EXIT_AFTER=15000
AI_ENDPOINT="${PLUGINFENCE_AI_ENDPOINT:-http://127.0.0.1:11434/v1}"
if [[ -n "${PLUGINFENCE_AI_ENDPOINT:-}" ]] || curl -sf --max-time 3 "${AI_ENDPOINT%/v1}/api/tags" >/dev/null 2>&1; then
  AI_MODEL="${PLUGINFENCE_AI_MODEL:-llama3.2:3b}"
  AI_ARGS=("-PaiEndpoint=$AI_ENDPOINT" "-PaiModel=$AI_MODEL" "-PaiAutoAnalyse=true")
  [[ -n "${PLUGINFENCE_AI_KEY:-}" ]] && AI_ARGS+=("-PaiKey=$PLUGINFENCE_AI_KEY")
  EXIT_AFTER=120000   # give a small local model time to finish its investigations before the IDE exits
  export PLUGINFENCE_SMOKE_AI=1
  echo "AI leg enabled: $AI_MODEL at $AI_ENDPOINT"
else
  echo "AI leg skipped: no model at $AI_ENDPOINT (start Ollama, or set PLUGINFENCE_AI_ENDPOINT)"
fi
./gradlew runFenceIdeUpdated -PdemoAutorun=normal,secret,exfil,process,attack "-PdemoExitAfter=$EXIT_AFTER" -PdemoOpenToolWindows=true "${AI_ARGS[@]}" --console=plain
# Prefer python3, but on Windows that name may be a Store alias stub that does not run.
PY=python3
"$PY" --version >/dev/null 2>&1 || PY=python
"$PY" scripts/check-sandbox-state.py
