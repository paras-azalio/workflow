# BST replacement – full context

Where we are with replacing Nokia BST / InstantLink (FlowOne) orchestration for CR_AUTOMATION by an open-source workflow tool. Last updated 2026-10-27 (afternoon). Start here; the details are in the files this page points to.

---

## 1. Goal and ground rules (from the user)

- **Replace BST/FlowOne orchestration** with a **free, open-source, extendable** workflow tool (cheapest option, used as-is or extended).
- **Generic and multi-project.** CR_AUTOMATION is one project; others will follow. Nothing project-specific hardcoded in the tool.
- **Every workflow integrates its own jars and scripts:** the NEI jars (FileCR, CLICR), Python scripts, mail jars, and so on.
- **Workflow features:**
  - loops, if/else and switch;
  - default success = exit 0 / NEI status 0, overridable per step or per workflow with other parameter names.
- **Seniors' decision:** steps call a **wrapper class that acts like an NEI**. Request parameters go in; status, results and the echoed request come out.
- **User's decision:** use the **NEI jars directly, unchanged**:
  - `JAVA_NOKIA_CLICR_AUTOMATION/target/macro_CLICR_ANY_V1.jar`
  - `NOKIA_FILE_CR_V1/target/macro_FILECR_ANY_V1.jar`
- **On demand, like FlowOne:** nothing is auto-triggered. An order is **started** (CIQ template, or CIQ validation as a new order), then **continued step by step** by a person.
- **Request ids:** whole numbers (1, 2, 3, ...) shared by orders and child orders, like InstantLink. A given **order number becomes the order's id**, and is checked at order creation.
- **FlowOne-like screens:** IO and MML logs on the execution screen; continue an order from the screen.
- **Process (user's diagram, 2026-10-27):** select order workflow → template → (or CIQ already present) → CIQ validation → MOP generation per CR, **each CR its own child order** → node 1..n execution → success feeds the **summary report per CR**; a failed node gets a **failure report** (also in the summary) → rollback (`MOP_EXECUTION`, `ROLLBACK_ONLY=true`) for nodes capable of it, **depends on the activity (PGW 1051: no rollback)**.
- **User's answers (2026-10-27):** a node failure does **not** stop the other nodes; rollback is **on demand per node**; parent/child ids and every step go into **new tables** (not a new database).
- **Other constraints:**
  - No ciq-processor code changes without a go-ahead; also no CLICR / FileCR source changes without one.
  - Never write real credentials into files or chat. Secrets come from environment variables or Kestra secrets.
  - Don't guess; verify.

## 2. Decisions so far (in order)

1. **Analysis** (`analysis/`): `00` requirements (R-xx); `01` / `03` BST User's Guide and Reference Manual capabilities; `02` what the real CR logics use; `04` 22 open-source tools compared; `05` recommendation: **Kestra OSS (Apache-2.0)**, fallback Operaton. Real BST logics use only If-loops, MapParameters, SendToNE, Dynamic Split/Join and activity wrappers; email, approvals and child-request polling are NEIs.
2. **Kestra 2.0.7 chosen.** OSS has no RBAC / SSO / audit / secrets manager (Enterprise only) – still open (§12). Kestra 2.0 replaced ForEach with Loop and removed pluginDefaults.
3. **Component wrapper contract** (`analysis/06_component_wrapper.md`): a first CIQ_PROCESSOR wrapper was **replaced by one generic NEI wrapper** once the user chose to run the NEI jars directly.
4. **Proven:** the NEI jars run **without a Macro Server process** with `-DRCFILE=<mds.rc>`, the call sequence `init` → `login` → task method, and the Macro Server status-code tables.
5. **FileCR flows first** (template, validation), then **IO/MML logs**.
6. **Parent → child:** an automatic fan-out flow (`cr_order`) was built, **rejected and removed**; replaced by **manually continued orders**.
7. **Numeric request ids**, order number = order id, taken numbers refused in the form.
8. **Kestra UI extended** (our fork of the open-source UI): **Order** and **IO / MML** tabs, request id as the visible Id, **Continue order** button.
9. **CIQ validation form:** `order_type` NEW / EXISTING; the CIQ upload is optional for an existing order (an uploaded CIQ replaces the order folder's CIQ; otherwise the CIQ placed in the folder is used).
10. **Request store in PostgreSQL** (tables `request`, `request_step`), **reports** (failure per node, summary per CR), **nodes continue after a failure**, **on-demand rollback per node** (`cr_node_rollback`).

---

## 3. Architecture

```
 Kestra 2.0.7 (Docker: kestra + postgres)                         /mnt/shared_data (Docker volume)
 ┌──────────────────────────────────────────────────────────┐     ┌────────────────────────────────┐
 │ UI  (Kestra UI + our tabs: Order, IO / MML)              │     │ <order id>/  CIQ, validation   │
 │ flows: generate_ciq_template, ciq_validation,            │────▶│   reports, activity JSON, logs/│
 │        cr_child_order, cr_node_rollback,                 │     │ <child id>/  MOP html, logs/,  │
 │        cr_report_generation (added by someone else)      │     │   LOGS/, REPORT/,              │
 │ KV: SHARED_DATA_DIR, ORDERS, ORDER_<id>, REQ_IDS_TAKEN   │     │   LOGS_ROLLBACK/,              │
 │ secrets: SECRET_CLICR_*, SECRET_CR_DB_PASSWORD           │     │   ROLLBACK_REPORT/             │
 └───────┬──────────────────────────────────────┬───────────┘     └────────────────────────────────┘
         │ each NEI step (shell task, Java 8)    │ ids, steps, order data
         ▼                                       ▼
   sh bin/nei.sh <ne> <log> --param ...     sh bin/store.sh <command>  ── PostgreSQL (Kestra's DB)
         │  (records the step via store.sh)        RequestStore           schema cr_automation:
         ▼                                                                 request, request_step
   WrapperMain --component NEI   (component-wrappers jar, reflection)
         ▼
   macro_FILECR_ANY_V1 / macro_CLICR_ANY_V1  (unchanged NEI jars + their lib/)
```

---

## 4. Component wrappers – `component-wrappers/`

Java 8, no compile dependencies. Contract: `analysis/06_component_wrapper.md` (§7 NEI wrapper, §8 request store).

**`WrapperMain` options:** `--component`, `--request`, `--param KEY=VALUE`, `--config`, `--workdir`, `--result`, `--emit keyvalue|json|kestra`, `--exit-zero`, `--list`, `--io-log <file>`, `--mml-log <file>`.

**Behaviour:**
- **Result:** status 0 SUCCESS / 1 WARNING / 9 FAILED. Exit code 0, or 9 for FAILED; always 0 with `--exit-zero`; 2 for usage errors.
- **Secrets:** keys matching `password|passwd|pswd|pwd|secret|token` are masked (`*****`) in the echoed request and the results.
- **`--emit kestra`:** one `::{"outputs":...}::` line → `outputs.<task>.vars`. Component stdout/stderr go to stdout (Kestra logs them as INFO).

**`nei/NeiWrapper` (component `NEI`):**
- **Request:** `ne_type` (required), `product_name` (ANY), `product_version` (V1), `task_type` (create) → class `macro_<ne>_<prod>_<ver>`.
- **GRC:** `nei.<ne>.grc.KEY` in the project config (the wrapper writes the mds.rc, sets `RCFILE`), or `nei.<ne>.rcfile`. Secret GRC values from env `NEI_GRC_<KEY>` (e.g. `NEI_GRC_REPO_PASSWORD`).
- **Login:** `nei.<ne>.login.KEY`; secrets from env `NEI_LOGIN_<KEY>` (e.g. `NEI_LOGIN_M2MPASSWORD1`).
- **Status-code tables:** `nei.<ne>.configdir` → `config/*_codes.txt`, loaded with the Macro Server's reader (Java 8-only reflection step creates the MacroServer singleton).
- **Results:** every `7200 NEW KEY VALUE` line, plus `NEI_MACRO`, `NEI_CONN_STATUS`, `NEI_TASK_STATUS`. Task 0 → SUCCESS, 1 → WARNING, else (or connection ≠ 0) → FAILED.
- **MML log:** sets the Macro Server's static `mmlLogFile` / `file` to `--mml-log`; after the task `MacroSet.closeMmlWriter()` and `$END$ <REQ_ID>`.

**`store/RequestStore` (the request store, §6):** commands `new-request`, `record-step`, `set-stage`, `child-status`, `registry`; JDBC (PostgreSQL driver on the classpath at run time), config `store.jdbc.url` / `store.jdbc.user`, password env `STORE_JDBC_PASSWORD`; creates its schema and tables itself (`CREATE ... IF NOT EXISTS`).

**Tests:** `MVN=<mvn> bash run-tests.sh` (JUnit launcher, no Surefire – the laptop's Maven cache lacks it). **25 pass:** 22 in the FileCR JVM (incl. `FileCrNeiIT`), 3 in `ClicrNeiIT` in its own JVM (FileCR's ciq-processor bundles its own snakeyaml). `RequestStore` was tested against Kestra's PostgreSQL in the container (no unit test).

**Facts about the NEIs, found while building this:**
- **Status object:** `Status(conn, task)`; `0 0` = OK; a failed task `0 2` (NEI `STATUS=9`); `STATUS=7` = success.
- **Results:** `7200 NEW` lines from `sendData` on stdout. MML via `printMml` to the Macro Server MML file (dropped if none is set).
- **GRC is cached** once per JVM (fine in Kestra: one JVM per task).
- **CLICR needs Java 8** (Nashorn) and **GRC `REPO_PASSWORD`** (else NullPointerException in MOP generation).
- **CLICR routing:** `create` → `@Keys` `REQ_TYPE=1` and `ACTION=MOP_GENERATION | MOP_EXECUTION`; an unknown ACTION returns OK silently.
- **MOP generation** with `CR_NAME`: that CR only; returns `ACTIVITY_CR_NAME`, `ACTIVITY_CR_NODE_NAMES` (zones, e.g. NORTH1), `ACTIVITY_CR_MOP`, `ACTIVITY_CR_EMAIL`.
- **MOP execution request:** `INPUT_JSON_FILE_NAME`, `OUTPUT_LOGS_FILE_LOCATION`, `OUTPUT_JSON_REPORT_LOCATION`, `CR_NAME`, `NODE_NAME`, `NODE_TYPE`, `SUB_ACTIVITY_NAME`, `NODE_VERSION`, `ROLLBACK_ONLY`. Writes `LOGS/<node>_execution.log` and `REPORT/<NT>_<ACT>_EXECUTION_REPORT_<node>.json`. Masks the M2M password in its MML (`********`).
- **Rollback flags (two different ones):**
  - `ROLLBACK_ENABLED=TRUE/FALSE` (capitals) – set by CLICR's engine (`ExecutionOrchestrator.java:2085`): TRUE when the activity configuration phase (and postcheck, if it ran) **succeeded** on the node → the change is applied and can be undone later. **This is the "node capable of rollback" flag.**
  - `ROLLBACK_REQUIRED` – the workflow YAML's own variable (default `"false"`, failure branches set `"true"`); gates the YAML's `ROLLBACK_CONFIGURATION` phase **inside the same run**.
  - `ROLLBACK_ONLY=true` – CLICR skips the forward phases (`when: ROLLBACK_ONLY != "true"`) and forces `ROLLBACK_REQUIRED=true`: only the rollback phase runs. The 1051 YAML has `ROLLBACK_REQUIRED: "false"` and no rollback phase.
- **FileCR `GENERATE_REPORT`:** summary per CR (`OUTPUT_REPORT_LOCATION` = folder of the node JSONs, `CR_NAME` → `<NT>_<ACT>_<CR>_SUMMARY_REPORT.html`); failure report per node (`NODE_FAILURE_REPORT=TRUE`, `NODE_NAME`, `FAILURE_REPORT_LOCATION` → `<NT>_<ACT>_<NODE>_EXECUTON_FAILURE_REPORT.html`); `ROLLBACK_REPORT=true` for the rollback summary. **It reads the activity from `SUB_ACTIVITY_NAME`** (its constant `ACTIVITY_NAME` is that key); without it: "Required field missing: --activity". GRC: `execution_report_template_path`, `single_node_execution_summary_report_template_path`, `execution_summary_report_template_path`.
- **File handle leak:** CLICR `YamlWorkflowLoader.load` never closes the YAML stream (irrelevant here; leaks in a long-running Macro Server).

---

## 5. Kestra setup – `kestra-local/` (see its README)

| Path | What |
|---|---|
| `flows/cr_automation/` | `generate_ciq_template`, `ciq_validation`, `cr_child_order`, `cr_node_rollback`; `cr_report_generation` (added by someone else on 2026-10-27 11:30 – passes only `ACTIVITY_NAME`, so its report will fail until it also passes `SUB_ACTIVITY_NAME`) |
| `namespace-files/cr_automation/bin/nei.sh` | **Step launcher:** one NEI task through the wrapper (`--emit kestra --exit-zero`, `--io-log` / `--mml-log` in `$ORDER_DIR/logs/`), copies logs to `out/logs/`, prints the MML; **records the step in the store** when `STORE_REQ_ID` is set (`STORE_STEP`, `STORE_NODE`, `EXECUTION_ID`) |
| `…/bin/store.sh` | **Request store** launcher (`RequestStore`, classpath `wrappers/*:lib/*`) |
| `…/config/cr_automation.properties` | FileCR and CLICR GRC sections (paths with `${WORKDIR}`), FileCR report templates, CLICR NIAM / repo / M2M user (local placeholders), configdirs, `store.jdbc.url` / `store.jdbc.user`, `rollback.<NODE_TYPE>_<ACTIVITY>` (1051: `false`) |
| `…/{wrappers,nei,templates,lib}/` | Built by `assemble-namespace-files.sh`, not in Git (§7); `lib/` = `postgresql-42.7.4.jar` from `~/.m2` |
| `assemble-namespace-files.sh`, `deploy.sh` | Collect jars/templates; REST deploy (KV `SHARED_DATA_DIR`, namespace files, flows) |
| `run.sh` | `template`, `validate <ciq.xlsx\|-> [order]` (`-` = no upload; no order = new order), `child <order> <CR> [true\|false]`, `orders` |
| `status.sh` | Waits for an execution, shows its real error (follows Loop sub-executions) and its order with every execution |
| `simulate.sh` | Runs the FileCR steps like Kestra would, without Kestra |
| `Dockerfile`, `docker-compose.yml`, `.env.example` | See below |
| `build-ui.sh`, `kestra-launch.sh`, `ui-overlay/` | Our UI build, served in front of the Kestra jar (§8) |

All scripts read login/URL from `.env`; `ENV_FILE=<file>` uses another env file (used for testing while the user's `.env` login is invalid, §11).

**Docker (`Dockerfile`, `docker-compose.yml`):** `kestra/kestra:v2.0.7` + Java 8 at `/opt/java8` (`NEI_JAVA`) + our UI overlay; PostgreSQL 18 (also holds our schema `cr_automation`); basic auth from `.env`; volume `shared-data` at `/mnt/shared_data` (BST's path; the 1051 YAML uses it literally); secrets (base64, from `.env`): `SECRET_CLICR_REPO_PASSWORD`, `SECRET_CLICR_M2MPASSWORD1`, `SECRET_CR_DB_PASSWORD` (= `POSTGRES_PASSWORD`).

**Shared data on Windows:** `\\wsl.localhost\docker-desktop\mnt\docker-desktop-disk\data\docker\volumes\kestra-local_shared-data\_data\`

---

## 6. How an order works now

| Flow | Does |
|---|---|
| `generate_ciq_template` | **Starts an order:** request id (order number or next), FileCR `GENERATE_CR_CIQ_TEMPLATE`. Stage `TEMPLATE`. |
| `ciq_validation` | `order_type` **EXISTING** (pick `order_id`; CIQ upload optional – an uploaded CIQ replaces `<order>/<NT>_<ACT>_CIQ.xlsx`, otherwise the `.xlsx` placed in the order folder last, by ctime, is used) or **NEW** (creates the order like the template; CIQ upload required – checked before an id is taken). FileCR `CR_CIQ_PROCESSOR`. Stage `VALIDATED` with `CR_LIST`, or `INVALID` (validate the same order again). Attempts logged as `ciq_validation_1`, `_2`, … |
| `cr_child_order` | **One CR of a validated order:** `order_id` dropdown (validated orders) → dependent `cr_name` dropdown (CRs with their last child and state). Child request id = next number. CLICR `MOP_GENERATION` (CR_NAME), then (`run_execution=true`) `MOP_EXECUTION` per node (Loop, concurrency 1) – **every node runs, also after a failure**; a failed node → FileCR **failure report**; then the CR **summary report**; the store's `child-status` decides: the child FAILS when any node failed ("MOP execution failed on 2 of 2 node(s) (CR1): NORTH1, SOUTH1"). |
| `cr_node_rollback` | **On demand, one node:** `order_id` → dependent `node` dropdown (`rollback_options`: nodes whose latest MOP_EXECUTION has `ROLLBACK_ENABLED=TRUE`, not rolled back yet, activity allowed by `rollback.<NT>_<ACT>=true`). CLICR `MOP_EXECUTION` `ROLLBACK_ONLY=true` under the **child's** request id (needs the child folder's backup/precheck), logs to `LOGS_ROLLBACK/`, report to `ROLLBACK_REPORT/`, then `GENERATE_REPORT` `ROLLBACK_REPORT=true`. |

**Request store (PostgreSQL, Kestra's database, schema `cr_automation`):**

| Table | Rows |
|---|---|
| `request` | one per order / child: `req_id` (PK), `parent_req_id`, `kind` ORDER/CHILD, node type, activity, node version, `cr_name`, `stage` (order: NEW/TEMPLATE/TEMPLATE_FAILED/INVALID/VALIDATED; child: RUNNING/SUCCESS/FAILED/…), `cr_list`, `json_file`, `folder`, creating Kestra execution, timestamps |
| `request_step` | one per NEI step: `req_id`, `parent_req_id`, Kestra execution id, `step` (GENERATE_TEMPLATE, CIQ_VALIDATION, MOP_GENERATION, MOP_EXECUTION, FAILURE_REPORT, SUMMARY_REPORT, ROLLBACK, ROLLBACK_REPORT), `node_name`, status, status text, message, **`request` / `results` JSONB** (the IO), IO/MML log paths, start, duration |

- **Request ids** (`store.sh new-request`, table lock): new order = the given **order number** (must be free) or **one more than the highest id in use** (table **or** numeric folders, so pre-table ids 12…72 are respected; gaps not reused); child = next number. Creates the folder. Used as `REQ_ID` / `PARENT_REQ_ID` / `CHILD_REQ_ID` / `ORDER_NO`, labels `req_id`, `order_id` (children `cr_name`), the visible Id in our UI. Kestra's own alphanumeric execution id stays the key in URLs/API.
- **Order number check in the form:** input `order_no_check` reads KV `REQ_IDS_TAKEN` (published from `new-request`); a taken number fails the form with "The key 'ORDER_NUMBER_<n>_IS_ALREADY_TAKEN' does not exist" (deliberate failed lookup – Kestra expressions have no fail function); API 422, no execution.
- **KV = projection for the forms** (Kestra forms read KV, cannot run SQL): `store.sh registry --order <id>` → `ORDERS` (list) and `ORDER_<id>` (stage, CRs, `json_file`, `order_dir`, steps, children per CR with their nodes, `cr_options`, `rollback_options`, `rollback_supported`), saved by KV Set tasks after each change (`afterExecution` for the child's final state).
- **Query example:** `docker exec kestra-local-postgres-1 psql -U kestra -d kestra -c "select req_id, step, node_name, status_text, results->>'ROLLBACK_ENABLED' from cr_automation.request_step order by step_id"`.

**Folders:** order – CIQ, validation reports, activity JSON, `logs/`; child – MOP html, `logs/`, `LOGS/`, `REPORT/` (node JSONs, failure reports, summary), `LOGS_ROLLBACK/`, `ROLLBACK_REPORT/`. The child reads the JSON from the order folder.

**IO / MML logs:** `<folder>/logs/<step>.io.log` (`>` request in, `<` results out, secrets masked) and `<step>.mml` (`printMml` + `$END$ <request id>`). Each flow's `finally` `collect_logs` copies the folder's logs (and `LOGS/`, `REPORT/`, `LOGS_ROLLBACK/`, `ROLLBACK_REPORT/`) into the execution's output files for the UI tab – including node runs from Loop sub-executions.

---

## 7. Where the YAMLs and templates come from

`assemble-namespace-files.sh` copies them. After editing a source: `./assemble-namespace-files.sh && ./deploy.sh`.

| What | Source | In namespace files | Read by |
|---|---|---|---|
| `*_validation-rules.yaml` | `version/1051/ggnyaml/` | `templates/rules/` | FileCR (`yaml_path`) |
| `*_json-output.yaml` | `version/1051/ggnyaml/` | `templates/json/`, `templates/clicr/jsonTemplate/` | FileCR (`jsonTemp_path`); CLICR schema |
| CLICR workflow YAMLs (names in `ACTIVITIES`) | `version/1051/ggnyaml/` | `templates/clicr/yaml/` | CLICR (`moptemplates_path`) |
| Validation report HTML, `mop_execution_*_template.html`, `application.properties` | `NOKIA_FILE_CR_V1/src/main/resources/templates/` | `templates/html/`, `templates/` | FileCR |
| MOP report HTML (`mop_summary_report_template*.html`) | `version/1051/ggnyaml/templates/html/` | `templates/clicr/html/` | CLICR |
| NEI jars, `lib/`, `config/*_codes.txt` | `NOKIA_FILE_CR_V1/target/`, `JAVA_NOKIA_CLICR_AUTOMATION/target/` | `nei/filecr/`, `nei/clicr/` | the wrapper |
| PostgreSQL JDBC driver | `~/.m2/.../postgresql-42.7.4.jar` (`PG_JAR`) | `lib/` | `store.sh` |

---

## 8. Kestra UI changes (our fork of the open-source UI)

**Source:** `BST/kestra/` = shallow clone of Kestra v2.0.7 (commit `49b62a2`), changes uncommitted there. **All UI changes are saved as `kestra-local/kestra-ui-cr-automation.patch`** (7 files) – after a fresh clone: `cd BST/kestra && git apply ../kestra-local/kestra-ui-cr-automation.patch`, then `kestra-local/build-ui.sh`. Changed files under `ui/src/`:
- `components/executions/OrderPanel.vue` (new) – **Order** tab: the order (id, stage, activity, folder, CRs, steps, children with their nodes and links) and **Continue this order**: validate a CIQ (file optional), start a child order (CR, execution yes/no), **roll back a node** (when `rollback_options` exist).
- `components/executions/IoMmlLogs.vue` (new) – **IO / MML** tab: every step with IO / MML / EXEC buttons (EXEC = CLICR node log; `LOGS_ROLLBACK/` → `mop_rollback_<node>`), viewer, line filter, Download. Data: the `collect_logs` output files via Kestra's outputs/file API.
- `components/executions/executionTabs.ts` – the two tab routes.
- `utils/requestId.ts` (new), `components/executions/Executions.vue`, `components/executions/composables/useExecutionRoot.ts` – `req_id` as the Id; **Continue order** in the Executions list selection bar.
- `translations/en.json` – `cr_order.*`, `io_mml_logs.*`.

**Build and serve:** `kestra-local/build-ui.sh` (npm ci first time, builds the workspace SDK – the `prebuild` script does nothing on Windows – then `vite build`, copies to `ui-overlay/`). The Dockerfile copies it to `/app/ui-overlay/ui`; `kestra-launch.sh` runs `java -cp /app/ui-overlay:/app/kestra io.kestra.cli.Kestra server standalone`. **On a Kestra upgrade:** re-apply these changes to the new UI source and rebuild.

---

## 9. Run it

```bash
cd BST/component-wrappers && MVN=<path to mvn> bash run-tests.sh   # or mvn package with a reachable repo
cd ../kestra-local
./assemble-namespace-files.sh
./build-ui.sh                       # Node.js 24; first run ~10 min
cp .env.example .env                # set credentials and base64 secrets (once)
docker compose up -d --build        # UI http://localhost:8080
./deploy.sh
```

**Use:** Flows → cr_automation → `generate_ciq_template` (optional order number) or `ciq_validation` (NEW) → Executions → tick the order → **Continue order** → Order tab: validate the CIQ → start a child order per CR → (if offered) roll back a node. Watch each execution's **IO / MML** tab. CLI: `./run.sh template` / `validate <ciq|-> <order>` / `child <order> <CR> [false]`, `./status.sh <execution id>`.

**Samples** (`samples/ciq/`): `..._current_rules_pass.xlsx` (1 CR); `..._two_crs_pass.xlsx` (NORTH1 → CR1, SOUTH1 → CR2); `..._one_cr_two_nodes_pass.xlsx` (CR1 on NORTH1 and SOUTH1); `..._fail_two_rows_old_words.xlsx` (invalid, 4 errors). `samples/json/PGW_RDS_1051_two_crs.json` = `ClicrNeiIT` fixture.

---

## 10. Verified (real Kestra in Docker)

- **2026-10-26/27, order process:** template → orders 21 (order number) / 22 (next); 21 again refused in the form (422); invalid then valid CIQ on one order; CR2 MOP only → child 23 (`PARENT_REQ_ID=21`, `CHILD_REQ_ID=23`); CR1 with execution → FAILED at NORTH1 `PRE_NODE_HEALTH_CHECK` (SSH refused: **no lab reachable**, expected).
- **Validation form:** existing order without upload (blank template → INVALID; CIQ placed as `myciq.xlsx` → VALIDATED), upload replaces the order CIQ (checksum equal), NEW without CIQ stops before taking an id, NEW with order number 40.
- **2026-10-27, request store / reports / rollback:** order 73 → children 74 (CR1, FAILED) / 75 (CR2, SUCCESS) with all steps and IO in the tables; order 77 (one CR on two nodes) → NORTH1 failed, **SOUTH1 still ran**, two failure reports + one summary, child 78 FAILED naming both nodes; rollback flow tested with one **staged** `ROLLBACK_ENABLED=TRUE` row (removed afterwards, with the two rollback steps): `ROLLBACK_ONLY=true` reached CLICR under child 78, logs/report in `LOGS_ROLLBACK/` / `ROLLBACK_REPORT/`, rollback report created, steps recorded; the rollback itself failed at SSH.
- **UI:** dropdowns (dependent CR), Order tab (nodes, buttons, no-file validation), Continue order, IO / MML tab, numeric Id in list and title.
- **Secrets:** none in any folder, log or Kestra log. **Wrapper tests:** 25/25.

---

## 11. Learned the hard way

**Kestra 2.0.7:**
- A Loop iteration is its own sub-execution (kind `LOOP`); inside it `outputs.<sibling>` is that iteration's.
- `runIf` on a Loop does not stop its iterations: wrap the Loop in an `If`.
- Input type `BOOL` (not `BOOLEAN`). `SELECT` options from an expression (`kv`, `jq`, `{label, value}`) and `dependsOn` (inputs + condition: hidden inputs are not required). `validator` regex is static (not rendered).
- No fail function in expressions → a deliberate missing `kv()` key makes the form fail with a readable message.
- `split` takes a regex (avoid `|`). Inline `# comments` inside a folded (`>-`) command become shell comments and cut the command.
- The execution GET has no task outputs (use the outputs API). Stderr lines are logged as ERROR.
- Secrets: `SECRET_<NAME>` env vars (base64) → `secret('NAME')`. KV PUT takes `text/plain` (JSON text).
- **Basic auth:** config credentials replace the stored ones at start-up only if valid (e-mail user; password 8+ chars with upper, lower, digit). The user's `.env` `KESTRA_PASSWORD` fails this, so the `.env.example` default login (`admin@example.com` / `ChangeMe123`) still works; tests used `ENV_FILE` with that login.
- The PostgreSQL password is fixed when its volume is created; a later `.env` change needs `ALTER USER kestra PASSWORD ...` (done once, 2026-10-26).

**Files:** a copied file keeps its mtime (Explorer, `docker cp`) → pick "placed last" by **ctime** (`ls -tc`).

**Windows / Git Bash:** `MSYS_NO_PATHCONV=1` (else `/app/...`, `/mnt/...`, `/tmp/...` get rewritten); then `-o /dev/null` breaks curl (exit 23, harmless). Python heredocs in the shell mangle backslashes – use the Edit tool or a script file. Java on Windows prints FileCR's log in cp1252.

**Claude Code safety:** `rm -rf` inside `docker exec sh -c` scripts is blocked – the user must remove `/tmp/store` in the Kestra container: `docker exec -u root kestra-local-kestra-1 rm -rf /tmp/store`.

**Maven offline:** pinned plugin versions; tests via `run-tests.sh` (JUnit Launcher).

---

## 12. Open points / next steps

- **MOP execution on real nodes** needs the lab: real `NIAM_IP` / `M2MPORT` / `REPO_*` in `cr_automation.properties`, the secrets in `.env`, and the 1051 YAML's hardcoded `ACTIVITY_PYTHON_SCRIPT_PATH` (an InstantLink server path) on the Kestra worker. Then a real rollback test on an activity with a rollback phase (and `rollback.<NT>_<ACT>=true`).
- **CLICR jar is stale:** `macro_CLICR_ANY_V1.jar` (2026-10-09 17:49) is older than later changes to `MopGenerator.java` / `MopExecutionUtil.java`. Rebuild before a lab run.
- **CLICR handle leak:** close the YAML stream in `YamlWorkflowLoader` (needs the user's go-ahead).
- **`cr_report_generation`** (not ours): add `SUB_ACTIVITY_NAME`.
- **User's `.env`:** fix `KESTRA_PASSWORD` (rule above). Remove `/tmp/store` in the container (above).
- **Not decided:** an approval step between MOP generation and execution (today `run_execution=false` is the manual stop); RBAC / SSO / audit (Kestra OSS vs Enterprise vs Operaton); production layout (install NEI packages on the worker instead of ~28 MB namespace files per step); whether the request store should get its own database user / database later.
- **Not built yet:** email steps, MMTel refresh, POSTCOMPLETION activity flows, other projects; import of pre-store orders (12–40, 69–72) into the tables (their ids are respected, not listed).
- **Old test data:** earlier test orders (alphanumeric ids, and 21–40 from before the store) remain in Kestra history and `/mnt/shared_data`; they are no longer in the order dropdowns once a flow republishes `ORDERS` from the store.

---

## 13. Related work (outside BST)

- **1051 activity (UC 1051 phase 2, CIQ v3):** `version/1051/ggnyaml` (validation rules, JSON-output, workflow YAML, Python `hlr_profile*.py`); lab runs in `version/1051/*`.
- **Simulator tooling:** only in `exec_sim/clicr_1051`. Open 1051 items: RSI `hssPriorityOrder` (with the SME); TESTIMSI whitelist steps commented out in the YAML; the engine prompt-offset fix; hardcoded LDAP / root passwords in the YAML; MMTel.
- **NEI source:** `JAVA_NOKIA_CLICR_AUTOMATION` (CLICR) and `NOKIA_FILE_CR_V1` (FileCR); `ciq-processor` (no code changes without a go-ahead).
