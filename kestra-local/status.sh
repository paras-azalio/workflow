#!/usr/bin/env bash
# Waits for a cr_automation execution to finish (or pause), then shows it and its order.
#
#   ./status.sh <execution id> [timeout seconds, default 600]
#
# Prints the execution's state and real error (following Loop sub-executions), then the order it belongs to
# (label order_id): stage, CRs, and every execution of that order (template, validations, child orders).
# Login and URL come from .env (ENV_FILE=<file> to use another one).
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a
export URL="${KESTRA_URL:-http://localhost:8080}" KAUTH="${KESTRA_USER:?}:${KESTRA_PASSWORD:?}"
export EXEC_ID="${1:?usage: status.sh <execution id> [timeout]}" TIMEOUT="${2:-600}"

python - <<'EOF'
import base64, json, os, re, time, urllib.error, urllib.request

URL, EXEC_ID = os.environ["URL"], os.environ["EXEC_ID"]
AUTH = "Basic " + base64.b64encode(os.environ["KAUTH"].encode()).decode()
DONE = {"SUCCESS", "FAILED", "WARNING", "KILLED", "CANCELLED", "PAUSED"}
LINK = re.compile(r'\[\[link execution="([^"]+)"')

def get(path):
    req = urllib.request.Request(URL + path, headers={"Authorization": AUTH})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

def problems(eid, depth=0):
    """ERROR messages; a Loop iteration's error is replaced by the errors of its sub-execution."""
    out = []
    for l in get("/api/v1/main/logs/" + eid):
        if l["level"] != "ERROR":
            continue
        m = LINK.search(l["message"])
        out += problems(m.group(1), depth + 1) if m and depth < 3 else [l["message"]]
    return out

def labels(e):
    return {l["key"]: l["value"] for l in e.get("labels") or []}

deadline = time.time() + int(os.environ["TIMEOUT"])
while True:
    e = get("/api/v1/main/executions/" + EXEC_ID)
    if e["state"]["current"] in DONE or time.time() > deadline:
        break
    time.sleep(3)

print("%-22s %-24s %s" % (e["id"], e["flowId"], e["state"]["current"]))
for m in problems(e["id"])[:3]:
    print("    ERROR " + " ".join(m.split())[:400])

order_id = labels(e).get("order_id")
if not order_id:
    raise SystemExit(0)
try:
    order = get("/api/v1/main/namespaces/cr_automation/kv/ORDER_" + order_id)
    order = order.get("value", order)
except urllib.error.HTTPError:
    raise SystemExit("order %s: not in the registry" % order_id)
print("\norder %s  stage %s  %s_%s  CRs %s" % (order_id, order.get("stage"), order.get("node_type"),
      order.get("activity"), ",".join(order.get("cr_list") or []) or "-"))
q = "/api/v1/main/executions/search?size=200&filters%5Bnamespace%5D%5BEQUALS%5D=cr_automation"
runs = [x for x in get(q).get("results", []) if labels(x).get("order_id") == order_id]
for x in sorted(runs, key=lambda x: x["state"]["startDate"]):
    print("  %-22s %-24s %-8s %s" % (x["id"], x["flowId"], x["state"]["current"], labels(x).get("cr_name", "")))
EOF
