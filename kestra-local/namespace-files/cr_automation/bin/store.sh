#!/bin/sh
# Runs one request-store command (component-wrappers RequestStore): the request / step tables of the schema
# cr_automation in PostgreSQL - request ids, every step with its IO, the order registry the forms read.
#
#   sh bin/store.sh <command> --opt value ...                  (commands: see RequestStore)
#   sh bin/store.sh locked --key K -- <command> <args> ...     runs the command holding a database lock on K
#
# Environment: STORE_JDBC_PASSWORD (from the Kestra secret CR_DB_PASSWORD), SHARED_DATA_DIR,
# WRAPPER_CONFIG (default config/cr_automation.properties: store.jdbc.url, store.jdbc.user, rollback.*),
# NEI_JAVA, CP_SEP. Prints the command's Kestra outputs line.
set -eu
s="${CP_SEP:-:}"
cmd="${1:?usage: store.sh <command> --opt value ...}"
shift
# the store's own options go before the caller's: after "--" everything belongs to the locked command
exec "${NEI_JAVA:-java}" -cp "wrappers/*${s}lib/*" io.azalio.workflow.wrapper.store.RequestStore "$cmd" \
  --config "${WRAPPER_CONFIG:-$(ls config/*.properties 2>/dev/null | head -1)}" --shared-dir "${SHARED_DATA_DIR:-/mnt/shared_data}" "$@"
