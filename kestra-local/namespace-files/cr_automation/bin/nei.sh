#!/bin/sh
# Runs one NEI task through the NEI component wrapper - the step every flow uses to call an NEI.
#
#   sh bin/nei.sh <ne> <log-name> --param KEY=VALUE ...
#
#   <ne>        NEI package under nei/ (filecr, clicr, ...); ne_type is its upper-case name
#   <log-name>  name of this task's logs: $ORDER_DIR/logs/<log-name>.io.log and .mml
#
# Environment:
#   ORDER_DIR       the order folder (required)
#   WRAPPER_CONFIG  project config (default config/cr_automation.properties)
#   NEI_JAVA        Java 8 runtime (the Kestra image sets it; default java)
#   CP_SEP          classpath separator (default ':'; ';' for Git Bash on Windows)
#   STORE_REQ_ID    request id the step belongs to: when set, the step is recorded in the request store
#   STORE_STEP      step name (GENERATE_TEMPLATE, CIQ_VALIDATION, MOP_GENERATION, MOP_EXECUTION, ROLLBACK, ...)
#   STORE_NODE      node name of the step (node steps), EXECUTION_ID the Kestra execution id
#
# The wrapper runs with --exit-zero and --emit kestra: the step always ends OK and the flow decides from
# outputs.<task>.vars.status (0 SUCCESS, 1 WARNING, 9 FAILED). Afterwards the IO and MML logs are copied to
# out/logs/ (task output files) and the MML log is printed in the task log.
set -eu
ne="${1:?usage: nei.sh <ne> <log-name> --param KEY=VALUE ...}"
name="${2:?usage: nei.sh <ne> <log-name> --param KEY=VALUE ...}"
shift 2
: "${ORDER_DIR:?ORDER_DIR is not set}"
s="${CP_SEP:-:}"
NE_TYPE=$(echo "$ne" | tr '[:lower:]' '[:upper:]')

mkdir -p "$ORDER_DIR/logs" out/logs
"${NEI_JAVA:-java}" -cp "wrappers/*${s}nei/$ne/*${s}nei/$ne/lib/*" com.nokia.workflow.wrapper.WrapperMain \
  --component NEI \
  --config "${WRAPPER_CONFIG:-config/cr_automation.properties}" \
  --param "ne_type=$NE_TYPE" \
  --result "out/$name.result.json" \
  --io-log "$ORDER_DIR/logs/$name.io.log" \
  --mml-log "$ORDER_DIR/logs/$name.mml" \
  --emit kestra \
  --exit-zero \
  "$@"

cp "$ORDER_DIR/logs/$name.io.log" "$ORDER_DIR/logs/$name.mml" out/logs/ 2>/dev/null || true
if [ -n "${STORE_REQ_ID:-}" ]; then   # its own outputs line goes to /dev/null: the task's outputs stay the NEI's
  sh bin/store.sh record-step --req-id "$STORE_REQ_ID" --step "${STORE_STEP:?STORE_STEP is not set}" \
    --node "${STORE_NODE:-}" --execution "${EXECUTION_ID:-}" --result "out/$name.result.json" \
    --io-log "$ORDER_DIR/logs/$name.io.log" --mml-log "$ORDER_DIR/logs/$name.mml" > /dev/null \
    && echo "recorded in the request store: request $STORE_REQ_ID, step $STORE_STEP ${STORE_NODE:-}" \
    || echo "WARNING: the step was not recorded in the request store" >&2
fi
echo "=== MML $ORDER_DIR/logs/$name.mml"
cat "$ORDER_DIR/logs/$name.mml" 2>/dev/null || true
