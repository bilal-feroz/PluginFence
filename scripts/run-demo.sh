#!/usr/bin/env bash
# Starts a sandbox IntelliJ IDEA with the PluginFence agent attached, the PluginFence plugin and
# Demo Helper 1.0.0 (benign). Pass "update" to start with Demo Helper 1.1.0 (suspicious) instead.
#
#   ./scripts/run-demo.sh            # baseline run  (Demo Helper 1.0.0)
#   ./scripts/run-demo.sh update     # update run    (Demo Helper 1.1.0, same sandbox => drift)
#
# Extra Gradle properties are passed through, e.g. -PdemoAutorun=normal -PdemoExitAfter=20000
set -euo pipefail
cd "$(dirname "$0")/.."
task="runFenceIde"
if [[ "${1:-}" == "update" ]]; then task="runFenceIdeUpdated"; shift; fi
exec ./gradlew "$task" "$@"
