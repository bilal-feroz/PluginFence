#!/usr/bin/env bash
# End-to-end smoke test without touching the mouse:
#   1. IDE + agent + Demo Helper 1.0.0, scripted "normal" behaviour, exit  -> baseline recorded
#   2. IDE + agent + Demo Helper 1.1.0, scripted attack actions, exit      -> blocks, incident, drift
#   3. assert on the persisted PluginFence state in the sandbox
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew runFenceIde        -PdemoAutorun=normal -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
./gradlew runFenceIdeUpdated -PdemoAutorun=normal,secret,exfil,process,attack -PdemoExitAfter=15000 -PdemoOpenToolWindows=true --console=plain
python3 scripts/check-sandbox-state.py
