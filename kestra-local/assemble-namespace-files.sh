#!/usr/bin/env bash
# Collects everything the cr_automation flows need into namespace-files/cr_automation/:
#   wrappers/                 component-wrappers jar (build: ../component-wrappers)
#   nei/filecr/               the FileCR NEI package, unchanged: macro_FILECR_ANY_V1.jar, lib/, config/ (status codes)
#   nei/clicr/                the CLICR NEI package, unchanged: macro_CLICR_ANY_V1.jar, lib/, config/ (status codes)
#   templates/rules|json|html CIQ validation rules, JSON-output templates, report templates (FileCR GRC paths)
#   templates/application.properties   FileCR properties (GRC properties_path)
#   templates/clicr/yaml|jsonTemplate|html   CLICR workflow YAMLs, JSON-output templates, MOP report templates
#                                            (CLICR GRC moptemplates_path, mop_summary_report_template_path*)
#   lib/                      PostgreSQL JDBC driver for the request store (bin/store.sh)
#   config/, bin/             project config and the step launcher (kept in Git, not copied)
# Sources can be overridden with the environment variables below.
set -euo pipefail
cd "$(dirname "$0")"

NOKIA="${NOKIA:-$(cd ../.. && pwd)}"
WRAPPERS_JAR="${WRAPPERS_JAR:-$NOKIA/BST/component-wrappers/target/component-wrappers-0.1.0.jar}"
FILECR_PKG="${FILECR_PKG:-$NOKIA/NOKIA_FILE_CR_V1/target}"                  # macro_FILECR_ANY_V1.jar, lib/, config/
CLICR_PKG="${CLICR_PKG:-$NOKIA/JAVA_NOKIA_CLICR_AUTOMATION/target}"         # macro_CLICR_ANY_V1.jar, lib/, config/
# Templates come only from the two NEI repositories (every activity), never from version/<uc>/ work folders.
FILECR_RES="${FILECR_RES:-$NOKIA/NOKIA_FILE_CR_V1/src/main/resources/templates}"   # yaml/ (rules), jsonTemplate/, html/, application.properties
CLICR_RES="${CLICR_RES:-$NOKIA/JAVA_NOKIA_CLICR_AUTOMATION/src/main/resources/templates}"   # yaml/ (workflows), jsonTemplate/, html/
# scripts the CLICR workflow YAMLs run on the worker (an activity points ACTIVITY_PYTHON_SCRIPT_PATH at
# ${WORKDIR}/nei/clicr/script/python through its configuration)
CLICR_SCRIPTS="${CLICR_SCRIPTS:-$NOKIA/JAVA_NOKIA_CLICR_AUTOMATION/src/main/resources/script}"
PG_JAR="${PG_JAR:-$HOME/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar}"   # JDBC driver (Java 8+)

NS=namespace-files/cr_automation
for f in "$WRAPPERS_JAR" "$FILECR_PKG/macro_FILECR_ANY_V1.jar" "$CLICR_PKG/macro_CLICR_ANY_V1.jar" "$PG_JAR"; do
  [ -f "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$NS/wrappers" "$NS/nei" "$NS/templates" "$NS/lib"
mkdir -p "$NS/lib"
cp "$PG_JAR" "$NS/lib/"
mkdir -p "$NS/wrappers" "$NS/nei/filecr/lib" "$NS/nei/filecr/config" "$NS/nei/clicr/lib" "$NS/nei/clicr/config" \
         "$NS/templates/rules" "$NS/templates/json" "$NS/templates/html" \
         "$NS/templates/clicr/yaml" "$NS/templates/clicr/jsonTemplate" "$NS/templates/clicr/html"
cp "$WRAPPERS_JAR" "$NS/wrappers/"
cp "$FILECR_PKG/macro_FILECR_ANY_V1.jar" "$NS/nei/filecr/"
cp "$FILECR_PKG"/lib/*.jar "$NS/nei/filecr/lib/"
cp "$FILECR_PKG"/config/*_codes.txt "$NS/nei/filecr/config/"
cp "$FILECR_RES"/yaml/*_validation-rules.yaml "$NS/templates/rules/"
cp "$FILECR_RES"/jsonTemplate/*_json-output.yaml "$NS/templates/json/"
cp "$FILECR_RES"/html/validation-report-template*.html "$NS/templates/html/"
cp "$FILECR_RES"/html/mop_execution_*_template.html "$NS/templates/html/"
cp "$FILECR_RES"/application.properties "$NS/templates/"
cp "$CLICR_PKG/macro_CLICR_ANY_V1.jar" "$NS/nei/clicr/"
cp "$CLICR_PKG"/lib/*.jar "$NS/nei/clicr/lib/"
cp "$CLICR_PKG"/config/*_codes.txt "$NS/nei/clicr/config/"
cp "$CLICR_RES"/yaml/*.yaml "$NS/templates/clicr/yaml/"
cp "$CLICR_RES"/jsonTemplate/*_json-output.yaml "$NS/templates/clicr/jsonTemplate/"
cp "$CLICR_RES"/html/mop_summary_report_template*.html "$NS/templates/clicr/html/"
mkdir -p "$NS/nei/clicr/script"
(cd "$CLICR_SCRIPTS" && find . -type f -not -path '*/__pycache__/*' -not -name '*.pyc' | while read -r f; do
  mkdir -p "$OLDPWD/$NS/nei/clicr/script/$(dirname "$f")" && cp "$f" "$OLDPWD/$NS/nei/clicr/script/$f"
done)

echo "namespace files for cr_automation:"
(cd "$NS" && find . -type f | sort | sed 's#^\./#  #')
du -sh "$NS" | sed 's/^/total: /'
