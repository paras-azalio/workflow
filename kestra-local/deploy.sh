#!/usr/bin/env bash
# Deploys one project's namespace (default cr_automation) to a running Kestra through its REST API (Kestra 2.0 OSS,
# tenant "main"): namespace-files/<ns>/ and flows/<ns>/*.yaml, whose flows must say "namespace: <ns>".
#   ./deploy.sh [namespace]
#
#   PUT  /api/v1/main/namespaces/{ns}/kv/{key}           text body (JSON/Ion value) - SHARED_DATA_DIR
#   POST /api/v1/main/namespaces/{ns}/files?path=/...    multipart field "fileContent" - namespace files
#   POST /api/v1/main/flows                              application/x-yaml - create a flow
#   PUT  /api/v1/main/flows/{ns}/{id}                    application/x-yaml - update (new revision)
# Credentials, URL and SHARED_DATA_DIR come from .env (see .env.example).
set -euo pipefail
cd "$(dirname "$0")"
# Git Bash (MSYS) rewrites arguments that look like Unix paths (/app/shared_data -> C:/.../Git/app/shared_data)
# before curl sees them; the values here are paths inside the Kestra container, so switch that off.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
ENV_FILE="${ENV_FILE:-.env}"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a
URL="${KESTRA_URL:-http://localhost:8080}"
AUTH=(-u "${KESTRA_USER:?set KESTRA_USER in .env}:${KESTRA_PASSWORD:?set KESTRA_PASSWORD in .env}")
NS="${1:-cr_automation}"
[ -d "namespace-files/$NS" ] && [ -d "flows/$NS" ] || { echo "namespace-files/$NS and flows/$NS are both needed" >&2; exit 1; }
if grep -L "^namespace: $NS[[:space:]]*$" flows/$NS/*.yaml | grep -q .; then
  echo "these flows do not say 'namespace: $NS':" >&2; grep -L "^namespace: $NS[[:space:]]*$" flows/$NS/*.yaml >&2; exit 1
fi

echo "== KV $NS.SHARED_DATA_DIR = ${SHARED_DATA_DIR:=/mnt/shared_data}"
curl -sS -f "${AUTH[@]}" -X PUT "$URL/api/v1/main/namespaces/$NS/kv/SHARED_DATA_DIR" \
     -H 'Content-Type: text/plain' --data "\"$SHARED_DATA_DIR\"" > /dev/null

echo "== namespace files -> $NS"
(cd "namespace-files/$NS" && find . -type f | sed 's#^\./##' | sort) | while read -r rel; do
  # activity file names may hold '&' (e.g. SBC_146_..._&_...), which would end the query parameter
  enc=$(python -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "/$rel")
  curl -sS -f "${AUTH[@]}" -X POST "$URL/api/v1/main/namespaces/$NS/files?path=$enc" \
       -F "fileContent=@namespace-files/$NS/$rel" > /dev/null
  echo "   /$rel"
done

echo "== flows"
for f in flows/$NS/*.yaml; do
  id=$(sed -n 's/^id: *//p' "$f" | head -1)
  code=$(curl -sS "${AUTH[@]}" -o .curl.out -w '%{http_code}' "$URL/api/v1/main/flows/$NS/$id"); rm -f .curl.out
  if [ "$code" = "200" ]; then
    curl -sS -f "${AUTH[@]}" -X PUT "$URL/api/v1/main/flows/$NS/$id" -H 'Content-Type: application/x-yaml' \
         --data-binary "@$f" > /dev/null && echo "   updated $NS.$id"
  else
    curl -sS -f "${AUTH[@]}" -X POST "$URL/api/v1/main/flows" -H 'Content-Type: application/x-yaml' \
         --data-binary "@$f" > /dev/null && echo "   created $NS.$id"
  fi
done
