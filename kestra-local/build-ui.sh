#!/usr/bin/env bash
# Builds Kestra's UI from the v2.0.7 source in ../kestra/ui (with our "IO / MML" execution tab,
# src/components/executions/IoMmlLogs.vue) and copies it to ui-overlay/, which the Dockerfile puts in front of
# the Kestra jar (kestra-launch.sh). Needs Node.js (24) and npm; the first run installs about 1,400 packages.
#
#   ./build-ui.sh && docker compose up -d --build
set -euo pipefail
cd "$(dirname "$0")"
UI=../kestra/ui
OUT=../kestra/webserver/src/main/resources/ui      # vite's outDir (vite.config)

[ -d "$UI/node_modules" ] || (cd "$UI" && npm ci --no-audit --no-fund)
# the workspace SDK the UI imports (what scripts/ensure-sdk.mjs does)
(cd "$UI" && npm run build --workspace=@kestra-io/hey-api-plugin && npm run build --workspace=@kestra-io/kestra-sdk)
(cd "$UI" && npx vite build --emptyOutDir)

rm -rf ui-overlay
mkdir -p ui-overlay
cp -r "$OUT" ui-overlay/ui
echo "UI copied to ui-overlay/ui ($(du -sh ui-overlay | cut -f1))"
