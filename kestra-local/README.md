# kestra-local – CR_AUTOMATION on Kestra 2.0.7

CR_AUTOMATION orders on Kestra, calling the NEI jars unchanged (FileCR and CLICR). Like FlowOne, nothing is triggered automatically: a flow runs only when someone starts it from the UI or the API.

An **order** is started on demand and then **continued** step by step by the user, as in FlowOne:

| Flow | What it does |
|---|---|
| `cr_automation.generate_ciq_template` | **Starts an order** with the blank CIQ (FileCR `GENERATE_CR_CIQ_TEMPLATE`). Order stage `TEMPLATE`. |
| `cr_automation.ciq_validation` | Validates a CIQ (FileCR `CR_CIQ_PROCESSOR`). `order_type` **Existing order**: pick the order in `order_id`; the CIQ upload is optional - an uploaded CIQ first replaces the order's CIQ in its folder, without one the `.xlsx` placed in the order folder last is validated. `order_type` **New order**: no old order is asked for; it creates the order like the template does (order number or next number) and needs the uploaded CIQ. Stage becomes `VALIDATED` with its `CR_LIST`, or `INVALID` (validate the same order again). |
| `cr_automation.cr_child_order` | **Continues a validated order with one CR.** Pick the order (dropdown: validated orders) and the CR (dropdown: that order's CRs, each with its last child order and state). CLICR `MOP_GENERATION` for the CR (child stage `MOP_GENERATED`). Only when `run_execution` is ticked (off by default) it runs `cr_mop_execution` for all nodes. Run it again for the next CR. |
| `cr_automation.cr_mop_execution` | **Executes a child's MOP on some or all of its nodes, in parallel.** Pick the order, the child and the nodes (`All nodes` or any; from the child's MOP generation). The nodes are claimed in the request store first: a node another execution is running is refused. Each node: CLICR `MOP_EXECUTION`, a **failure report** when it failed; then the CR **summary report** (one writer per child, database lock). Fails when one of its nodes failed. Also from the child's **Order** tab. |
| `cr_automation.cr_node_rollback` | **Rolls back one node** of a child order on demand: CLICR `MOP_EXECUTION` with `ROLLBACK_ONLY=true` for that node (only the workflow's rollback phase runs), then the rollback report (`GENERATE_REPORT`, `ROLLBACK_REPORT=true`). Offered only for nodes whose execution ended with `ROLLBACK_ENABLED=TRUE` and not rolled back yet, and only when the activity allows it (`rollback.<NODE_TYPE>_<ACTIVITY>=true` in the config - PGW 1051: `false`). Runs under the child's request id, using what the execution left in `/mnt/shared_data/<child id>/<node>/`; its own engine log and report go to `LOGS_ROLLBACK/` and `ROLLBACK_REPORT/`. |
| `cr_automation.cr_report_generation` | (added separately) Re-creates the summary report of a CR's last child order on demand. |

## Orders and child orders

```
order 3AMq…   (id = the execution that started it; folder /mnt/shared_data/<order id>/)
  generate_ciq_template 3AMq…        TEMPLATE      blank CIQ
  ciq_validation        2p5c…        INVALID       4 errors - corrected and validated again:
  ciq_validation        1xdv…        VALIDATED     CR_LIST=CR1,CR2, activity JSON
  cr_child_order        27Ml…  CR2   SUCCESS       (child id = CHILD_REQ_ID, folder /mnt/shared_data/<child id>/)
  cr_child_order        3zrH…  CR1   FAILED        MOP generated; node NORTH1 failed (no lab)
```

- **Request store (database).** Every order and child order and every step of them is a row in PostgreSQL - schema `cr_automation` in Kestra's own database (`bin/store.sh`, Java `RequestStore` in `../component-wrappers`):

  | Table | Rows |
  |---|---|
  | `request` | one per order / child order: `req_id` (PK), `parent_req_id`, `kind` ORDER/CHILD, node type, activity, `cr_name`, `stage` (order: TEMPLATE / INVALID / VALIDATED; child: RUNNING / SUCCESS / FAILED), CR list, activity JSON, folder, the Kestra execution that created it |
  | `request_step` | one per NEI step: `req_id`, `parent_req_id`, Kestra execution id, `step` (GENERATE_TEMPLATE, CIQ_VALIDATION, MOP_GENERATION, MOP_EXECUTION, FAILURE_REPORT, SUMMARY_REPORT, ROLLBACK, ROLLBACK_REPORT), `node_name`, status, message, **`request` and `results` as JSONB** (the IO log: e.g. `results->>'ROLLBACK_ENABLED'`, `CR_LIST`), IO / MML log paths, start, duration |

  `bin/nei.sh` records every NEI step by itself (env `STORE_REQ_ID`, `STORE_STEP`, `STORE_NODE`). The flows read the store for decisions (which nodes failed; which nodes can be rolled back) and publish what the forms need to KV - `ORDERS` (the order list), `ORDER_<id>` (stage, CRs, steps, children with their nodes, `cr_options`, `rollback_options`) and `REQ_IDS_TAKEN` - because a Kestra form can read KV but cannot run SQL. Look at it with `docker exec kestra-local-postgres-1 psql -U kestra -d kestra -c "select * from cr_automation.request_step"`. The password is the Kestra secret `CR_DB_PASSWORD` (`SECRET_CR_DB_PASSWORD` in `.env`, base64 of `POSTGRES_PASSWORD`).
- **Request ids are whole numbers** shared by orders and child orders, like InstantLink's, given out by the store under a table lock: a new order takes its **order number** when one is given (it must be free; `order_no` input), otherwise one more than the highest id in use (table or `/mnt/shared_data/<n>` folders, so ids from before the table are respected); a child takes the next number (order 21 → child 22, or 23 if 22 is taken). A taken order number is refused **in the form, before the order is created**: the input `order_no_check` looks the number up in KV `REQ_IDS_TAKEN` and shows "order number N is free" - or the error "The key 'ORDER_NUMBER_N_IS_ALREADY_TAKEN' does not exist", which blocks Execute (the API answers 422). The id is REQ_ID / PARENT_REQ_ID / CHILD_REQ_ID and ORDER_NO in the NEI requests, and the labels `req_id` and `order_id` (children also `cr_name`). The UI shows it as the execution's **Id** (Executions list, page title); Kestra's own alphanumeric execution id is still the key in URLs and the API (hover the number to see it).
- **Folders follow BST:** order folder - CIQ, validation reports, activity JSON; child folder - the CR's MOP html, CLICR's `LOGS/<node>_execution.log` and `REPORT/<..>_EXECUTION_REPORT_<node>.json`. The child reads the JSON from the order folder, as in today's requests.

## Order tab (execution screen) - continue an order

Every execution of an order has an **Order** tab: the order (request id, stage, activity, folder, CRs), every step and child order with its result and a link, and **Continue this order**:
- **Validate a CIQ for this order** - optionally choose the filled (or corrected) CIQ, run `ciq_validation` on this order;
- **Start a child order** (once VALIDATED) - pick the CR, tick or untick MOP execution, run `cr_child_order`;
- **Roll back a node** (only when a node qualifies, see `cr_node_rollback`) - pick it, run the rollback.

Each child order shows its nodes with their result (and "rolled back").

From the Executions list: tick one execution of an order and click **Continue order** in the selection bar - it opens that order's Order tab. The tab starts a normal execution of that flow and opens it. The same steps can be started from Flows → cr_automation → the flow → Execute.

## IO / MML tab (execution screen)

Every execution screen has an **IO / MML** tab next to Logs - the FlowOne view of the NEI logs. Left: the order's (or child's) steps - `generate_template`, `ciq_validation_<execution id>`, `mop_generation`, `mop_execution_<node>` - each with **IO** (request parameters in, status and results out), **MML** (what the NEI printed with `printMml`, ending with `$END$ <request id>`) and, for a node, **EXEC** (CLICR's execution log). Right: the file, a line filter and Download.

- **Data source:** the saved log files. Each flow's `finally` step `collect_logs` copies `<folder>/logs/` (and a child's `LOGS/`, `REPORT/`) into the execution's output files (Kestra's internal storage, behind its database), so the tab reads them through Kestra's own API - also the node runs, which happen in Loop sub-executions. An order's execution shows the whole order so far (template and all validations).
- **How:** both tabs are a change to Kestra's own open-source UI (`../kestra/ui`, v2.0.7): `src/components/executions/OrderPanel.vue` and `IoMmlLogs.vue` (new), two routes in `src/components/executions/executionTabs.ts`, the **Continue order** button (`components/executions/Executions.vue`), the request id as Id (`src/utils/requestId.ts` (new), `components/executions/Executions.vue`, `components/executions/composables/useExecutionRoot.ts`), and the `cr_order` / `io_mml_logs` texts in `src/translations/en.json`. `./build-ui.sh` builds it into `ui-overlay/`; the image serves it in front of the unchanged Kestra jar (`kestra-launch.sh` puts `/app/ui-overlay` first on the classpath). On a Kestra upgrade, re-apply these changes to the new UI source and rebuild.

## How a step calls an NEI

- **Every NEI step runs `bin/nei.sh <ne> <log-name> --param KEY=VALUE ...`.** This is the NEI component wrapper (`../component-wrappers`, contract in `../analysis/06_component_wrapper.md`). It runs the NEI jar unchanged with its `lib/`, and does what the Macro Server does: `init`, `login`, task method. It returns the NEI's status and its `7200 NEW` values as Kestra outputs. The same script serves FileCR and CLICR, and any other NEI package under `nei/`.
- **The flow decides success itself.** The wrapper runs with `--exit-zero`; the flow checks `outputs.<task>.vars.status` (0 success, 9 failed) and ends with `Fail` and a clear message (error count and report, node and `LAST_ACTIVITY_EXECUTED`).
- **IO and MML logs, as in the Macro Server's `logs/` folder.** Each NEI step writes two files to `<order folder>/logs/` and also keeps them as task output files:
  - `<log-name>.io.log` (IO): the request parameters that triggered the order (`> KEY=VALUE`, secrets masked), then the status and every result the NEI returned (`< KEY=VALUE`). The same block is printed in the task log.
  - `<log-name>.mml` (MML): what the NEI writes with `printMml`, like `Set logfile logs/test.mml` in today's login command. It ends with `$END$ <request id>`. The task log also prints it under `=== MML`.
- **Config per environment, secrets apart:**
  - `config/cr_automation.properties` replaces the NEIs' `mds.rc` GRC sections: paths, NIAM, repository, M2M user.
  - Passwords come from Kestra secrets as environment variables: `CLICR_REPO_PASSWORD` → `NEI_GRC_REPO_PASSWORD`, and `CLICR_M2MPASSWORD1` → `NEI_LOGIN_M2MPASSWORD1`.
  - In Kestra OSS a secret is a `SECRET_<NAME>` variable (base64) on the Kestra container (see `.env.example`).
  - CLICR needs a GRC `REPO_PASSWORD`: without it, MOP generation fails with a NullPointerException.
- **`SHARED_DATA_DIR` is `/mnt/shared_data`** - inside the Docker volume `kestra-local_shared-data`; on Windows open `\\wsl.localhost\docker-desktop\mnt\docker-desktop-disk\data\docker\volumes\kestra-local_shared-data\_data\` in Explorer, or `docker exec kestra-local-kestra-1 ls /mnt/shared_data`. It is a key-value entry of the namespace and a Docker volume. It is BST's path, and the 1051 workflow YAML uses it literally (`/mnt/shared_data/${CHILD_REQ_ID}/...`).

## Layout

| Path | What |
|---|---|
| `flows/cr_automation/*.yaml` | The three flows |
| `namespace-files/cr_automation/bin/nei.sh` | The step launcher: runs one NEI task through the wrapper, keeps the IO / MML logs |
| `namespace-files/cr_automation/bin/store.sh` | The request store: request ids, step records, the order data the forms read (`RequestStore`) |
| `namespace-files/cr_automation/lib/` | PostgreSQL JDBC driver for the store (copied by `assemble-namespace-files.sh`, not kept in Git) |
| `namespace-files/cr_automation/config/cr_automation.properties` | Project config: the FileCR and CLICR GRC sections, CLICR login user. `${WORKDIR}` is the step's working folder. |
| `namespace-files/cr_automation/{wrappers,nei,templates}/` | Built by `assemble-namespace-files.sh`, not kept in Git: the wrapper jar; the FileCR and CLICR packages (jar, `lib/`, status-code files); validation rules, JSON-output and report templates; the CLICR workflow YAMLs and MOP report templates (`templates/clicr/`) |
| `assemble-namespace-files.sh` | Collects the above from the build outputs and the template folders (`ACTIVITIES=` picks the CLICR workflows) |
| `simulate.sh` | Runs the FileCR steps exactly as Kestra would, without Kestra, and checks the output line and the decision |
| `Dockerfile`, `docker-compose.yml`, `.env.example` | Kestra 2.0.7 with a Java 8 runtime for the NEIs (`NEI_JAVA`) and the rebuilt UI, PostgreSQL, basic auth, the `/mnt/shared_data` volume, the CLICR secrets |
| `build-ui.sh`, `kestra-launch.sh`, `ui-overlay/` | Builds Kestra's UI with the IO / MML tab; starts Kestra with it (`ui-overlay/` is built, not kept in Git) |
| `deploy.sh` | Sets the `SHARED_DATA_DIR` key-value entry, uploads the namespace files and creates or updates the flows (REST API) |
| `run.sh` | Starts a flow: `template`, `validate <ciq.xlsx> [order id]`, `child <order id> <CR> [false\|true]` (default false = MOP only), `exec <order id> <child id> [NODE,NODE\|*]` (parallel, default all nodes); `orders` lists the registry |
| `status.sh` | Waits for an execution, then shows its state and real error (it follows Loop sub-executions) and its order with every execution |

## Run it

```bash
(cd ../component-wrappers && mvn package)          # offline laptop: MVN=<path to mvn> ./run-tests.sh
./assemble-namespace-files.sh                      # needs NOKIA_FILE_CR_V1/target and JAVA_NOKIA_CLICR_AUTOMATION/target
./build-ui.sh                                      # Kestra UI with the IO / MML tab (Node.js 24, first run ~10 min)

cp .env.example .env                               # set real credentials and secrets
docker compose up -d --build                       # UI: http://localhost:8080
./deploy.sh

# an order, step by step (the same from the UI: Flows -> cr_automation -> Execute)
./run.sh template                                  # -> order id
./run.sh validate ../samples/ciq/PGW_RDS_1051_CIQ_two_crs_pass.xlsx <order id>   # '-' instead of the file: validate the CIQ in the order folder;
                                                   # no order id: a new order
./run.sh child <order id> CR2 false                # MOP only
./run.sh child <order id> CR1                      # MOP + execution
./status.sh <execution id>
```

## Status (2026-10-26)

**Wrapper tests: 25 pass**, including integration tests that run the real NEI jars:
- **FileCR (5):** template, valid CIQ, invalid CIQ, a missing mandatory parameter, the MML log.
- **CLICR (3), in its own JVM:**
  - MOP generation for CR2 of a two-CR JSON → only `-CR2.html`, `ACTIVITY_CR_NODE_NAMES=SOUTH1`;
  - an unknown CR → FAILED, "CR group not found";
  - MOP execution with no reachable node → FAILED, `ACTIVITY_EXECUTION_STATUS=FAIL`, plus the execution log and report.

**Real Kestra (Docker), one order continued step by step** (`../samples/ciq/PGW_RDS_1051_CIQ_two_crs_pass.xlsx`: zones NORTH1 → CR1, SOUTH1 → CR2):

| Step | Execution | Order stage / result |
|---|---|---|
| `generate_ciq_template` | SUCCESS | new order, `TEMPLATE` |
| `ciq_validation` on that order, invalid CIQ | FAILED, "4 error(s) ... validate order <id> again" | `INVALID` |
| `ciq_validation` on that order, valid CIQ | SUCCESS | `VALIDATED`, CRs CR1,CR2 |
| `cr_child_order` CR2, `run_execution=false` | SUCCESS | MOP `-CR2.html`; CR option shows "CR2 \| last child … SUCCESS" |
| `cr_child_order` CR1 | FAILED: "MOP execution failed on node NORTH1 (CR1) … `PRE_NODE_HEALTH_CHECK`" (no lab reachable - expected) | MOP generated, CLICR `LOGS/` and `REPORT/` written; "CR1 \| last child … FAILED" |
| `ciq_validation` without `order_id` | SUCCESS | a second order, `VALIDATED` |

- The Kestra form: `order_id` lists the validated orders; after picking one, `cr_name` lists its CRs with their last child order (Kestra's dependent inputs).
- **IO / MML tab:** a child shows `mop_generation` (IO, MML) and `mop_execution_NORTH1` (IO, MML, EXEC); the order's validation shows `generate_template` and both validations.
- No secret value in any order folder or log. The IO logs show secrets as `*****`; CLICR's own MML shows the M2M password as `********`.

**Request store, reports, rollback (2026-10-27, real Kestra):**
- order 73 (template → valid CIQ) with children 74 (CR1, with execution) and 75 (CR2, MOP only): table `request` has 73 (ORDER, VALIDATED), 74 / 75 (CHILD, `parent_req_id` 73, FAILED / SUCCESS); `request_step` has every step with its IO (`CR_LIST` on the validation, `ROLLBACK_ENABLED` on the node).
- order 77, one CR on two nodes (`../samples/ciq/PGW_RDS_1051_CIQ_one_cr_two_nodes_pass.xlsx`): NORTH1 failed and **SOUTH1 still ran**; both got a failure report, the CR summary covers both, the child failed with "MOP execution failed on 2 of 2 node(s) (CR1): NORTH1, SOUTH1".
- FileCR reads the activity of `GENERATE_REPORT` from `SUB_ACTIVITY_NAME` (its constant `ACTIVITY_NAME` is that key) - without it: "Required field missing: --activity".
- rollback flow: tested with one staged row (`ROLLBACK_ENABLED=TRUE`, removed afterwards), since no node can succeed without the lab and 1051 allows no rollback: `ROLLBACK_ONLY=true` reached CLICR under the child's id, logs and report went to `LOGS_ROLLBACK/` / `ROLLBACK_REPORT/`, the rollback report was created, both steps were recorded; the rollback itself failed at SSH (no lab).

**Learned on Kestra 2.0.7:**
- A Loop iteration is its own sub-execution (kind `LOOP`), with its own logs. `status.sh` follows those links.
- `runIf` on a Loop does not stop its iterations: guard a Loop with an `If`.
- Input type names: `BOOL`, not `BOOLEAN`.
- A `SELECT` input can take its options from an expression (`kv(...)`, with `jq`) and depend on another input (`dependsOn`); options can be `{label, value}`.
- Basic-auth credentials from the config replace the stored ones at start-up only if they pass Kestra's rule (e-mail user; password 8+ characters with upper, lower and a digit); otherwise the old ones stay. The database password is fixed when the PostgreSQL volume is created.

**Open points:**
- **MOP execution against real nodes** needs the lab:
  - `NIAM_IP` / `M2MPORT` / `REPO_*` in the config, and the secrets;
  - the 1051 YAML's `ACTIVITY_PYTHON_SCRIPT_PATH` (an InstantLink server path, hardcoded in the YAML), which must exist on the Kestra worker.
- **CLICR jar:** `JAVA_NOKIA_CLICR_AUTOMATION/target/macro_CLICR_ANY_V1.jar` (2026-10-09 17:49) is older than the last changes to `MopGenerator.java` and `MopExecutionUtil.java`. Rebuild it before a lab run.
- **CLICR file handle leak:** `YamlWorkflowLoader.load` never closes the workflow YAML. One file handle leaks per task in a long-running Macro Server. Here every task is a new JVM, so it does not matter.
- **Size:** the namespace files are about 27 MB (FileCR's `lib/` is most of it), copied into every step's working folder. For production, install the NEI packages once on the worker and point the classpath there.
- **No approval step between MOP generation and execution:** `run_execution=false` is the manual stop. A Pause (approve, then execute) can be added to the child if the process needs one.

## More projects

**Add a project** (e.g. CR_AUTOMATIONS in namespace cr_automations):
1. `kestra-local/flows/cr_automations/` = a copy of `flows/cr_automation/`; in each of its 7 files set the first line to `namespace: cr_automations` (the flows refer to their own namespace as `{{ flow.namespace }}` everywhere else).
2. `kestra-local/namespace-files/cr_automations/` = a copy of `namespace-files/cr_automation/` (or `./assemble-namespace-files.sh cr_automations` after copying its `bin/` and `config/`); in its one `config/*.properties` set `store.project=CR_AUTOMATIONS` (the scripts use whichever single `.properties` file is in `config/`).
3. `./deploy.sh cr_automations` (files, flows, KV `SHARED_DATA_DIR`; refuses flows that name another namespace).
4. Process designer → **New project**: id `CR_AUTOMATIONS`, namespace `cr_automations`, start from a copy of CR_AUTOMATION (or empty) → edit the draft → Publish (checks the flows exist in that namespace and deploys its pipelines there).
5. Activities tab, project CR_AUTOMATIONS: declare its activities and levels (they are per project).
New orders of that namespace's flows are pinned to that project; its Orders, Order tab, automatic starts and stops work like CR_AUTOMATION's.

## Components (jars and scripts from the UI)

Process designer → **Components** → pick the project → **New component** (standalone or wrapped):
1. **Files:** upload the jar / script (or a zip, unpacked), or point it to a folder of the namespace (e.g. `nei/filecr`).
2. **Settings:** wrapped = NE type / product / version, config dir, GRC and login values; standalone = runtime, command (`{runtime} {file:check.py}`), how values go in (request file, arguments `--imsi {IMSI}`, env `P_IMSI`) and how results come out (response file, `KEY=value` lines, last JSON line, exit code), success rule, timeout.
3. **Actions:** name, parameters (required, default, secret `${secret:NAME}`), results.
4. **Save → Try it** (runs one action in the flow `cr_component_try`) **→ Publish**.

Use it in a process: Processes → a step's pipeline → a NEI box → Component = the registered component, Action = one of its actions (its other parameters are offered). Save / Publish checks the action and the required parameters; the process version runs the component version published last when it was published.
