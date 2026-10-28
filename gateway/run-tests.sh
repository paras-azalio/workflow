#!/usr/bin/env bash
# Compiles the gateway and runs its tests in a Java 21 container (the laptop's JDK is 8) on the kestra-local
# network, so UserStoreIT reaches PostgreSQL - in its own throw-away schema cr_gateway_test.
#
#   ./run-tests.sh            needs ../kestra-local/.env (POSTGRES_PASSWORD) and the running kestra-local stack
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1
REPO="${M2_REPO:-$HOME/.m2/repository}"
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
set -a; . ../kestra-local/.env; set +a

JUNIT=""
for j in \
  org/junit/jupiter/junit-jupiter-api/5.10.5/junit-jupiter-api-5.10.5.jar \
  org/junit/jupiter/junit-jupiter-engine/5.10.5/junit-jupiter-engine-5.10.5.jar \
  org/junit/platform/junit-platform-launcher/1.10.5/junit-platform-launcher-1.10.5.jar \
  org/junit/platform/junit-platform-engine/1.10.5/junit-platform-engine-1.10.5.jar \
  org/junit/platform/junit-platform-commons/1.10.5/junit-platform-commons-1.10.5.jar \
  org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar \
  org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar; do
  JUNIT="$JUNIT:/m2/$j"
done
LIBS="/libs/component-wrappers-0.1.0.jar:/libs/postgresql-42.7.4.jar"

docker run --rm --network kestra-local_default \
  -v "$(win "$PWD"):/src:ro" -v "$(win "$REPO"):/m2:ro" \
  -v "$(win "$PWD/../component-wrappers/target/component-wrappers-0.1.0.jar"):/libs/component-wrappers-0.1.0.jar:ro" \
  -v "$(win "$PWD/../kestra-local/namespace-files/cr_automation/lib/postgresql-42.7.4.jar"):/libs/postgresql-42.7.4.jar:ro" \
  -e GATEWAY_TEST_JDBC_URL=jdbc:postgresql://postgres:5432/kestra -e GATEWAY_TEST_JDBC_USER=kestra \
  -e GATEWAY_TEST_JDBC_PASSWORD="$POSTGRES_PASSWORD" \
  eclipse-temurin:21-jdk sh -c "
    set -e
    mkdir -p /out/main /out/test /out/tools
    javac -Xlint:all -d /out/main -cp '$LIBS' \$(find /src/src/main/java -name '*.java')
    javac -d /out/test -cp '/out/main:$LIBS$JUNIT' \$(find /src/src/test/java -name '*.java')
    javac -d /out/tools -cp '$JUNIT' /src/tools/RunTests.java
    java -cp '/out/tools:/out/test:/out/main:/src/src/main/resources:$LIBS$JUNIT' RunTests"
