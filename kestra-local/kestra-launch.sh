#!/usr/bin/env sh
# Starts Kestra like the image's own launcher (/app/kestra is a self-running jar), with one difference: the
# rebuilt UI in /app/ui-overlay/ui comes first on the classpath, so it replaces the UI inside the jar (the
# server reads ui/index.html and ui/assets/** from the classpath). The jar itself is unchanged.
# The UI is Kestra's own (BST/kestra/ui, v2.0.7) plus the "IO / MML" execution tab; see build-ui.sh.
set -e

KESTRA_PLUGINS_PATH=${KESTRA_PLUGINS_PATH:-"/app/plugins"}
export KESTRA_PLUGINS_PATH

KESTRA_CONFIGURATION_PATH=${KESTRA_CONFIGURATION_PATH:-"/app/confs"}
if [ "${KESTRA_CONFIGURATION}" ]; then
    echo "${KESTRA_CONFIGURATION}" > "${KESTRA_CONFIGURATION_PATH}/application.yml"
    export MICRONAUT_CONFIG_FILES="${KESTRA_CONFIGURATION_PATH}/application.yml"
fi

# the same options as the jar's launcher
JAVA_ADD_OPENS="--add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED"
KESTRA_JAVA_OPTS="-XX:MaxRAMPercentage=50.0"

exec java ${KESTRA_JAVA_OPTS} ${JAVA_OPTS} ${JAVA_ADD_OPENS} --sun-misc-unsafe-memory-access=allow \
    --enable-native-access=ALL-UNNAMED -cp "/app/ui-overlay:/app/kestra" io.kestra.cli.Kestra "$@"
