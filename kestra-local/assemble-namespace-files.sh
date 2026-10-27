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
RULES_DIR="${RULES_DIR:-$NOKIA/version/1051/ggnyaml}"                       # *_validation-rules.yaml
JSON_DIR="${JSON_DIR:-$NOKIA/version/1051/ggnyaml}"                         # *_json-output.yaml
FILECR_RES="${FILECR_RES:-$NOKIA/NOKIA_FILE_CR_V1/src/main/resources/templates}"   # html/, application.properties
CLICR_PKG="${CLICR_PKG:-$NOKIA/JAVA_NOKIA_CLICR_AUTOMATION/target}"         # macro_CLICR_ANY_V1.jar, lib/, config/
WORKFLOW_DIR="${WORKFLOW_DIR:-$NOKIA/version/1051/ggnyaml}"                 # CLICR workflow YAMLs <NODE_TYPE>_<ACTIVITY>.yaml
CLICR_HTML="${CLICR_HTML:-$NOKIA/version/1051/ggnyaml/templates/html}"     # mop_summary_report_template*.html
# Activities whose CLICR workflow is packaged (copied by name: the workflow folder also holds work copies)
PG_JAR="${PG_JAR:-$HOME/.m2/repository/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar}"   # JDBC driver (Java 8+)
ACTIVITIES="${ACTIVITIES:-PGW_RDS_1051_SUBSCRIBER_PROFILE_CONFIGURATION PGW_RDS_1051_SUBSCRIBER_PROFILE_CONFIGURATION_POSTCOMPLETION}"

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
cp "$RULES_DIR"/*_validation-rules.yaml "$NS/templates/rules/"
cp "$JSON_DIR"/*_json-output.yaml "$NS/templates/json/"
cp "$FILECR_RES"/html/validation-report-template*.html "$NS/templates/html/"
cp "$FILECR_RES"/html/mop_execution_*_template.html "$NS/templates/html/"
cp "$FILECR_RES"/application.properties "$NS/templates/"
cp "$CLICR_PKG/macro_CLICR_ANY_V1.jar" "$NS/nei/clicr/"
cp "$CLICR_PKG"/lib/*.jar "$NS/nei/clicr/lib/"
cp "$CLICR_PKG"/config/*_codes.txt "$NS/nei/clicr/config/"
for a in $ACTIVITIES; do
  cp "$WORKFLOW_DIR/$a.yaml" "$NS/templates/clicr/yaml/"
  cp "$JSON_DIR/${a}_json-output.yaml" "$NS/templates/clicr/jsonTemplate/"
done
cp "$CLICR_HTML"/mop_summary_report_template.html "$CLICR_HTML"/mop_summary_report_template_v2.html \
   "$NS/templates/clicr/html/"

echo "namespace files for cr_automation:"
(cd "$NS" && find . -type f | sort | sed 's#^\./#  #')
du -sh "$NS" | sed 's/^/total: /'
