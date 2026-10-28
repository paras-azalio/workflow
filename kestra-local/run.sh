#!/usr/bin/env bash
# Starts a cr_automation flow on demand through the REST API (multipart, one part per input):
#   POST /api/v1/main/executions/{ns}/{id}
#
#   ./run.sh template [NODE_TYPE] [ACTIVITY]          generate_ciq_template - starts an order
#   ./run.sh validate <ciq.xlsx|-> [ORDER_ID]         ciq_validation - continues ORDER_ID ('-' = no upload: validate
#                                                     the CIQ in its order folder), or starts a new order
#   ./run.sh child <ORDER_ID> <CR> [false|true]       cr_child_order - one CR of a validated order
#                                                     (default false = generate the MOP only; true = also execute it)
#   ./run.sh exec <ORDER_ID> <CHILD_ID> [NODE,NODE|*] cr_mop_execution - the child's MOP on these nodes, in
#                                                     parallel (default * = all nodes of its MOP)
#   ./run.sh orders                                   the order registry (KV ORDERS)
#
# Login and URL come from .env (ENV_FILE=<file> to use another one).
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash: do not rewrite /... arguments
ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a
URL="${KESTRA_URL:-http://localhost:8080}"
AUTH=(-u "${KESTRA_USER:?}:${KESTRA_PASSWORD:?}")
USAGE="usage: run.sh template|validate|child|exec|orders ..."

case "${1:?$USAGE}" in
  template) FLOW=generate_ciq_template; shift
            PARTS=(-F "node_type=${1:-PGW_RDS}" -F "activity=${2:-1051_SUBSCRIBER_PROFILE_CONFIGURATION}") ;;
  validate) FLOW=ciq_validation; shift; CIQ="${1:?ciq.xlsx or -}"; PARTS=()
            [ "$CIQ" != "-" ] && PARTS+=(-F "ciq=@$CIQ")
            if [ -n "${2:-}" ]; then PARTS+=(-F order_type=EXISTING -F "order_id=$2"); else PARTS+=(-F order_type=NEW); fi ;;
  child)    FLOW=cr_child_order; shift
            PARTS=(-F "order_id=${1:?order id}" -F "cr_name=${2:?CR, e.g. CR1}" -F "run_execution=${3:-false}") ;;
  exec)     FLOW=cr_mop_execution; shift
            NODES=$(python -c 'import json,sys; print(json.dumps([n.strip() for n in sys.argv[1].split(",") if n.strip()]))' "${3:-*}")
            PARTS=(-F "order_id=${1:?order id}" -F "child_id=${2:?child id}" -F "nodes=$NODES") ;;
  orders)   curl -sS -f "${AUTH[@]}" "$URL/api/v1/main/namespaces/cr_automation/kv/ORDERS" \
              | python -c 'import json,sys; d=json.load(sys.stdin); [print(o["label"]) for o in d.get("value", d)]'
            exit 0 ;;
  *) echo "$USAGE" >&2; exit 2 ;;
esac
resp=$(curl -sS -f "${AUTH[@]}" -X POST "$URL/api/v1/main/executions/cr_automation/$FLOW" "${PARTS[@]}")
id=$(echo "$resp" | python -c 'import json,sys; print(json.load(sys.stdin)["id"])')   # top-level execution id
echo "execution: $id"
echo "UI       : $URL/ui/main/executions/cr_automation/$FLOW/$id"
