#!/usr/bin/env bash
# Runs a cr_automation flow's task commands exactly as Kestra's Process runner would - namespace files copied
# into a fresh working folder, inputs and Pebble expressions filled in - without a running Kestra. Then checks
# the ::{"outputs":...}:: line against Kestra 2.0's own pattern (TaskLogLineMatcher: ^::(\{.*})::$) and
# evaluates the flow's If condition.
#
#   ./simulate.sh template [NODE_TYPE] [ACTIVITY]               -> generate_ciq_template
#   ./simulate.sh validate <ciq.xlsx> [NODE_TYPE] [ACTIVITY]    -> ciq_validation
#
# SHARED_DATA_DIR (the KV entry) defaults to a temporary folder; the execution id is faked.
set -euo pipefail
cd "$(dirname "$0")"
HERE="$(pwd)"

FLOW="${1:?usage: simulate.sh template|validate [...]}"; shift
if [ "$FLOW" = "validate" ]; then CIQ="$(cd "$(dirname "${1:?ciq.xlsx}")" && pwd)/$(basename "$1")"; shift; fi
NODE_TYPE="${1:-PGW_RDS}"
ACTIVITY="${2:-1051_SUBSCRIBER_PROFILE_CONFIGURATION}"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac   # the flows use ':' (Linux)
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }

SHARED="${SHARED_DATA_DIR:-$(mktemp -d)}"
EXEC_ID="sim$(date +%H%M%S)$RANDOM"                # Kestra: {{ execution.id }}
ORDER_DIR="$(win "$SHARED")/$EXEC_ID"              # vars.order_dir
WD="$(mktemp -d)"
cp -r namespace-files/cr_automation/. "$WD/"
cd "$WD"
export ORDER_DIR CP_SEP="$SEP" NEI_JAVA="${NEI_JAVA:-java}"   # what the flows put in the step's env
COMMON=(--param REQ_TYPE=1 --param "NODE_TYPE=$NODE_TYPE" --param "ACTIVITY_NAME=$ACTIVITY"
        --param "SUB_ACTIVITY_NAME=$ACTIVITY" --param NODE_VERSION=ALL --param ORDER_NO=SIM-ORDER --param "REQ_ID=$EXEC_ID")

set +e
if [ "$FLOW" = "template" ]; then
  TASK=generate_template
  sh bin/nei.sh filecr $TASK "${COMMON[@]}" \
    --param ACTION=GENERATE_CR_CIQ_TEMPLATE --param "CIQ_FILE_LOCATION=$ORDER_DIR/" > stdout.txt 2> stderr.txt
  CODE=$?
  cp "$ORDER_DIR"/*.xlsx out/ 2>/dev/null
else
  TASK=ciq_validation
  CIQ_NAME="${NODE_TYPE}_${ACTIVITY}_CIQ.xlsx"
  mkdir -p "$ORDER_DIR" && cp "$CIQ" ciq.xlsx && cp ciq.xlsx "$ORDER_DIR/$CIQ_NAME"
  sh bin/nei.sh filecr $TASK "${COMMON[@]}" \
    --param ACTION=CR_CIQ_PROCESSOR --param "CIQ_FILE_NAME=$ORDER_DIR/$CIQ_NAME" \
    --param "OUTPUT_FILE_LOCATION=$ORDER_DIR/" --param REPORT_TYPE=HTML --param HEADER_ROW_NO=1 > stdout.txt 2> stderr.txt
  CODE=$?
  cp "$ORDER_DIR"/*.html "$ORDER_DIR"/*.json out/ 2>/dev/null
fi
set -e

echo "flow task        : cr_automation.$TASK"
echo "execution id     : $EXEC_ID (fake)"
echo "order folder     : $ORDER_DIR  ->  $(ls "$ORDER_DIR" | tr '\n' ' ')"
echo "task output files: $(ls out | tr '\n' ' ')"
echo "exit code        : $CODE (non-zero would mark the Kestra task FAILED)"
echo "output markers   : $(grep -cE '^::\{.*\}::$' stdout.txt || true) (must be 1)"
python - stdout.txt "$TASK" <<'EOF'
import json, re, sys
task = sys.argv[2]
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):   # FileCR logs in the JVM default charset
    m = re.match(r"^::(\{.*\})::$", line.rstrip("\r\n"))
    if not m:
        continue
    o = json.loads(m.group(1))["outputs"]
    r = o.get("results", {})
    print("outputs.status   : %s %s - %s" % (o["status"], o["statusText"], o["message"]))
    print("If (status == 0) : %s -> %s branch" % (o["status"] == 0, "then" if o["status"] == 0 else "else (Fail)"))
    for k in ("CR_LIST", "CR_COUNT", "REPORT_EMAIL", "MMTEL_REFRESH_EMAIL", "JSON_FILE_NAME",
              "REPORT_FILENAME_HTML", "NEI_MACRO", "NEI_CONN_STATUS", "NEI_TASK_STATUS", "STATUS", "STATUS_MESSAGE"):
        if k in r:
            print("  results.%-21s= %s" % (k, r[k]))
    print("  (%d results in total; request echoed: %s)" % (len(r), ", ".join(sorted(o.get("request", {})))))
EOF
echo "io log           : $ORDER_DIR/logs/$TASK.io.log ($(grep -c "^> " "$ORDER_DIR/logs/$TASK.io.log") in, $(grep -c "^< " "$ORDER_DIR/logs/$TASK.io.log") out)"
echo "mml log          : $ORDER_DIR/logs/$TASK.mml ($(wc -l < "$ORDER_DIR/logs/$TASK.mml") lines, last: $(tail -1 "$ORDER_DIR/logs/$TASK.mml"))"
echo "working folder   : $WD"
