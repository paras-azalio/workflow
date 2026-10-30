#!/usr/bin/env bash
# Builds the module and runs its JUnit 5 tests without Maven Surefire.
# Use when the local Maven repository cannot resolve the Surefire provider (offline laptop).
# With a reachable repository, plain `mvn package` runs the same tests.
#
#   ./run-tests.sh                      # build (tests skipped by Maven), then run all tests
#   MVN=/path/to/mvn ./run-tests.sh
#
# Two JVMs, like two flow steps: all tests with the FileCR NEI on the classpath, then ClicrNeiIT with the
# CLICR NEI. They cannot share one: FileCR's ciq-processor jar bundles its own snakeyaml.
set -euo pipefail
cd "$(dirname "$0")"

MVN="${MVN:-mvn}"
REPO="${M2_REPO:-$HOME/.m2/repository}"
SEP=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac

# Windows java/javac need C:/... paths, not Git Bash /c/... paths
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi
}

"$MVN" -o -q -DskipTests package

JUNIT=""
for j in \
  org/junit/jupiter/junit-jupiter-api/5.10.5/junit-jupiter-api-5.10.5.jar \
  org/junit/jupiter/junit-jupiter-engine/5.10.5/junit-jupiter-engine-5.10.5.jar \
  org/junit/platform/junit-platform-launcher/1.10.5/junit-platform-launcher-1.10.5.jar \
  org/junit/platform/junit-platform-engine/1.10.5/junit-platform-engine-1.10.5.jar \
  org/junit/platform/junit-platform-commons/1.10.5/junit-platform-commons-1.10.5.jar \
  org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar \
  org/apiguardian/apiguardian-api/1.1.2/apiguardian-api-1.1.2.jar; do
  JUNIT="${JUNIT}${SEP}$(winpath "$REPO/$j")"
done
BASE="target/tools${SEP}target/classes${SEP}target/test-classes${JUNIT}"

# the NEI jars and their lib/ for the NEI integration tests (each skips itself when its jar is absent)
FILECR="$(winpath "${FILECR_TARGET:-../../NOKIA_FILE_CR_V1/target}")"
CLICR="$(winpath "${CLICR_TARGET:-../../JAVA_NOKIA_CLICR_AUTOMATION/target}")"

NEI_ROOT=$(winpath "$(cd ../.. && pwd)")
mkdir -p target/tools
javac -d target/tools -cp "$BASE" tools/RunTests.java

echo "== all tests (FileCR NEI on the classpath)"
java -Dnei.root="$NEI_ROOT" -cp "${BASE}${SEP}${FILECR}/macro_FILECR_ANY_V1.jar${SEP}${FILECR}/lib/*" RunTests
echo "== ClicrNeiIT (CLICR NEI on the classpath)"
java -Dnei.root="$NEI_ROOT" -cp "${BASE}${SEP}${CLICR}/macro_CLICR_ANY_V1.jar${SEP}${CLICR}/lib/*" RunTests \
  io.azalio.workflow.wrapper.nei.ClicrNeiIT
