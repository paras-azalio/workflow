#!/bin/sh
# Runs one action of a registered component (Process designer > Components) through the component wrapper.
#
#   sh bin/component.sh <component dir> <log-name> --param KEY=VALUE ...
#
#   <component dir>  components/<name>/v<version>: its component.properties is written by the gateway when the
#                    version is saved. component.kind WRAPPED = an NEI jar (component.ne_type, .product, .version;
#                    jar and lib/ under component.home, default the component dir) run by the NEI component;
#                    STANDALONE = a program (jar, Python, shell) run by the COMMAND component (command.<name>.*).
#   <log-name>       name of this call's logs: $ORDER_DIR/logs/<log-name>.io.log (and .mml for WRAPPED)
#
# The project's config file comes first, the component's settings win. Environment, logs and the request-store
# recording are the ones of bin/nei.sh (ORDER_DIR, STORE_REQ_ID, STORE_STEP, STORE_NODE, EXECUTION_ID, NEI_JAVA).
set -eu
dir="${1:?usage: component.sh <component dir> <log-name> --param KEY=VALUE ...}"
name="${2:?usage: component.sh <component dir> <log-name> --param KEY=VALUE ...}"
shift 2
: "${ORDER_DIR:?ORDER_DIR is not set}"
s="${CP_SEP:-:}"
props="$dir/component.properties"
[ -f "$props" ] || { echo "No $props: save or publish the component version first." >&2; exit 1; }
prop() { sed -n "s/^$1=//p" "$props" | head -1; }
kind=$(prop component.kind)
home=$(prop component.home)
home="${home:-$dir}"

mkdir -p "$ORDER_DIR/logs" out/logs
config="out/$name.config.properties"
project=$(ls config/*.properties 2>/dev/null | head -1 || true)
{ [ -n "$project" ] && cat "$project"; echo; cat "$props"; } > "$config"
if [ -n "${STORE_REQ_ID:-}" ]; then
  sh bin/store.sh step-config --req-id "$STORE_REQ_ID" --step "${STORE_STEP:?STORE_STEP is not set}" \
    --node "${STORE_NODE:-}" --out "out/$name.activity.properties" > /dev/null \
    || { echo "The activity configuration of step $STORE_STEP could not be applied (see above)." >&2; exit 1; }
  set -- "$@" --override "out/$name.activity.properties"
fi

if [ "$kind" = WRAPPED ]; then
  "${NEI_JAVA:-java}" -cp "wrappers/*${s}$home/*${s}$home/lib/*" com.nokia.workflow.wrapper.WrapperMain \
    --component NEI --config "$config" \
    --param "ne_type=$(prop component.ne_type)" --param "product_name=$(prop component.product)" \
    --param "product_version=$(prop component.version)" \
    --result "out/$name.result.json" --io-log "$ORDER_DIR/logs/$name.io.log" --mml-log "$ORDER_DIR/logs/$name.mml" \
    --emit kestra --exit-zero "$@"
elif [ "$kind" = STANDALONE ]; then
  "${NEI_JAVA:-java}" -cp "wrappers/*" com.nokia.workflow.wrapper.WrapperMain \
    --component COMMAND --config "$config" --param "COMMAND=$(prop component.name)" \
    --result "out/$name.result.json" --io-log "$ORDER_DIR/logs/$name.io.log" \
    --emit kestra --exit-zero "$@"
else
  echo "$props: component.kind must be WRAPPED or STANDALONE, not '$kind'." >&2
  exit 1
fi

cp "$ORDER_DIR/logs/$name.io.log" "$ORDER_DIR/logs/$name.mml" out/logs/ 2>/dev/null || true
if [ -n "${STORE_REQ_ID:-}" ]; then
  sh bin/store.sh record-step --req-id "$STORE_REQ_ID" --step "$STORE_STEP" --node "${STORE_NODE:-}" \
    --execution "${EXECUTION_ID:-}" --result "out/$name.result.json" --io-log "$ORDER_DIR/logs/$name.io.log" \
    --mml-log "$ORDER_DIR/logs/$name.mml" > /dev/null \
    && echo "recorded in the request store: request $STORE_REQ_ID, step $STORE_STEP ${STORE_NODE:-}" \
    || echo "WARNING: the step was not recorded in the request store" >&2
fi
if [ -f "$ORDER_DIR/logs/$name.mml" ]; then
  echo "=== MML $ORDER_DIR/logs/$name.mml"
  cat "$ORDER_DIR/logs/$name.mml"
fi
