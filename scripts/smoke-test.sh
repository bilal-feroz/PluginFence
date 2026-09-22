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
AI_ENDPOINT=""
AI_MODEL=""
AI_KEY="${PLUGINFENCE_AI_KEY:-}"
if [[ -n "${PLUGINFENCE_AI_ENDPOINT:-}" ]]; then                       # explicit override wins
  AI_ENDPOINT="$PLUGINFENCE_AI_ENDPOINT"
  AI_MODEL="${PLUGINFENCE_AI_MODEL:-openai/gpt-oss-120b}"
elif [[ -n "${GROQ_API_KEY:-}" ]]; then                                # a real hosted model, if we have a key
  AI_ENDPOINT="https://api.groq.com/openai/v1"
  AI_MODEL="${PLUGINFENCE_AI_MODEL:-${GROQ_MODEL:-openai/gpt-oss-120b}}"
  AI_KEY="$GROQ_API_KEY"
elif [[ -n "${OPENAI_API_KEY:-}" ]]; then
  AI_ENDPOINT="https://api.openai.com/v1"
  AI_MODEL="${PLUGINFENCE_AI_MODEL:-gpt-4.1-mini}"
  AI_KEY="$OPENAI_API_KEY"
elif curl -sf --max-time 3 "http://127.0.0.1:11434/api/tags" >/dev/null 2>&1; then   # local fallback
  AI_ENDPOINT="http://127.0.0.1:11434/v1"
  AI_MODEL="${PLUGINFENCE_AI_MODEL:-llama3.2:3b}"
fi
if [[ -n "$AI_ENDPOINT" ]]; then
  AI_ARGS=("-PaiEndpoint=$AI_ENDPOINT" "-PaiModel=$AI_MODEL" "-PaiAutoAnalyse=true")
  # The key is passed by *inheriting* GROQ_API_KEY / OPENAI_API_KEY into the sandbox IDE, not as a
  # Gradle property: -PaiKey becomes a -D JVM option, and IntelliJ writes its full JVM options into
  # idea.log. A tool that audits credential handling should not leave a key in a log file.
  export GROQ_API_KEY OPENAI_API_KEY
  if [[ -n "${PLUGINFENCE_AI_KEY:-}" ]]; then
    case "$AI_ENDPOINT" in
      *groq.com*) export GROQ_API_KEY="$PLUGINFENCE_AI_KEY" ;;
      *) export OPENAI_API_KEY="$PLUGINFENCE_AI_KEY" ;;
    esac
  fi
  EXIT_AFTER=120000   # let the analyst finish its investigations before the IDE exits
  export PLUGINFENCE_SMOKE_AI=1
  echo "AI leg enabled: $AI_MODEL at $AI_ENDPOINT"
else
  echo "AI leg skipped: set GROQ_API_KEY / OPENAI_API_KEY, or start Ollama on 127.0.0.1:11434"
fi
./gradlew runFenceIdeUpdated -PdemoAutorun=normal,secret,exfil,process,attack "-PdemoExitAfter=$EXIT_AFTER" -PdemoOpenToolWindows=true "${AI_ARGS[@]}" --console=plain
# Prefer python3, but on Windows that name may be a Store alias stub that does not run.
PY=python3
"$PY" --version >/dev/null 2>&1 || PY=python
"$PY" scripts/check-sandbox-state.py
