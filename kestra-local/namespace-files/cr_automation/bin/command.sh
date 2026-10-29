#!/bin/sh
# Runs a configured program (mail sender, reply reader, any jar or script) through the wrapper's COMMAND component.
#
#   sh bin/command.sh <command> <log-name> --param KEY=VALUE ...
#
#   <command>   config command.<command>, e.g. mail.default (see the namespace's config/*.properties)
#   <log-name>  name of this task's logs: $ORDER_DIR/logs/<log-name>.io.log
#
# Environment: ORDER_DIR (required), WRAPPER_CONFIG, NEI_JAVA, CP_SEP, and STORE_REQ_ID / STORE_STEP / STORE_NODE /
# EXECUTION_ID to record the call in the request store, as for bin/nei.sh - also the activity configuration of
# the step (store.sh step-config) is applied the same way. Outputs: outputs.<task>.vars.status (0 / 1 / 9),
# statusText, message and results.<KEY> (the program's response).
set -eu
cmd="${1:?usage: command.sh <command> <log-name> --param KEY=VALUE ...}"
name="${2:?usage: command.sh <command> <log-name> --param KEY=VALUE ...}"
shift 2
: "${ORDER_DIR:?ORDER_DIR is not set}"

mkdir -p "$ORDER_DIR/logs" out/logs
if [ -n "${STORE_REQ_ID:-}" ]; then
  sh bin/store.sh step-config --req-id "$STORE_REQ_ID" --step "${STORE_STEP:?STORE_STEP is not set}" \
    --node "${STORE_NODE:-}" --out "out/$name.activity.properties" > /dev/null \
    || { echo "The activity configuration of step $STORE_STEP could not be applied (see above)." >&2; exit 1; }
  set -- "$@" --override "out/$name.activity.properties"
fi
"${NEI_JAVA:-java}" -cp "wrappers/*" com.nokia.workflow.wrapper.WrapperMain \
  --component COMMAND \
  --config "${WRAPPER_CONFIG:-$(ls config/*.properties 2>/dev/null | head -1)}" \
  --param "COMMAND=$cmd" \
  --result "out/$name.result.json" \
  --io-log "$ORDER_DIR/logs/$name.io.log" \
  --emit kestra \
  --exit-zero \
  "$@"

cp "$ORDER_DIR/logs/$name.io.log" out/logs/ 2>/dev/null || true
if [ -n "${STORE_REQ_ID:-}" ]; then
  sh bin/store.sh record-step --req-id "$STORE_REQ_ID" --step "$STORE_STEP" --node "${STORE_NODE:-}" \
    --execution "${EXECUTION_ID:-}" --result "out/$name.result.json" --io-log "$ORDER_DIR/logs/$name.io.log" > /dev/null \
    && echo "recorded in the request store: request $STORE_REQ_ID, step $STORE_STEP ${STORE_NODE:-}" \
    || echo "WARNING: the step was not recorded in the request store" >&2
fi
