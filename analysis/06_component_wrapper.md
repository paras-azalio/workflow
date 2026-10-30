# 06 – Component wrappers: one contract for every jar and script a workflow calls

Date: 2026-10-26. Status: **contract agreed; generic `NEI` wrapper built and tested with the FileCR jar (template + CIQ validation).**

> **Update (same day):** decided to use the NEI jars directly. One generic `NEI` wrapper runs any NEI jar unchanged (FileCR now, CLICR next), so no per-component wrapper is needed; the earlier `CIQ_PROCESSOR` wrapper was removed. §7 describes it; §4–§5 below are kept as history of the first prototype.
Code: `BST/component-wrappers` (Java 8, no third-party dependencies in the core).
First Kestra use: `BST/kestra-local`.

## 1. Why

Seniors' decision (2026-10-26): workflow steps do not call jars and scripts directly. They call a **wrapper class that acts like an NEI**:
- it takes the **request parameters**;
- it runs the component;
- it returns the **status** (success or failure) **embedded with the results and the request parameters**.

The wrappers are written **once per component** and reused by every workflow and project.

What this gives:
- **The engine stays generic.** It needs only one way to call a wrapper. Every component (ciq-processor, CLICR, FileCR reports, Python and shell scripts, mail) looks the same to it.
- **The NEI knowledge is kept** (request keys, actions, naming conventions), without the Macro Server.
- **The engine can be swapped.** The same wrappers work under Kestra, Operaton or a plain shell script.

## 2. The contract

### 2.1 Call

```
java -cp "component-wrappers.jar:lib/*" io.azalio.workflow.wrapper.WrapperMain \
     --component <NAME>                         # e.g. CIQ_PROCESSOR (case-insensitive)
     [--request request.json|request.properties]  # request parameters, flat KEY=VALUE
     [--param KEY=VALUE ...]                    # overrides / additions to the request
     [--config project.properties]              # project + environment config (or env WRAPPER_CONFIG)
     [--workdir DIR]                            # relative paths resolve here (default: current folder)
     [--result result.json]                     # full result document
     [--emit keyvalue|json|kestra]              # what goes to stdout (default keyvalue)
     [--exit-zero]                              # always exit 0; the workflow decides from the status
     [--io-log FILE]                            # IO log: request in, status + results out (secrets masked)
     [--mml-log FILE]                           # MML log for wrappers that write one (NEI: printMml)
java ... WrapperMain --list                     # wrappers on the classpath
```

### 2.2 Request

- A flat, ordered `KEY=VALUE` map: the same shape and the same keys as the NEI arglist today.
- For `CIQ_PROCESSOR` the keys are `ACTION`, `CIQ_FILE_NAME`, `NODE_TYPE`, `ACTIVITY_NAME`, `NODE_VERSION`, `OUTPUT_FILE_LOCATION`, `REPORT_TYPE`.
- A JSON request file may nest objects (flattened as `a.b`) and use arrays (joined with `,`).

### 2.3 Result

```json
{
  "component": "CIQ_PROCESSOR",
  "status": 0,                      // 0 SUCCESS, 1 WARNING, 9 FAILED
  "statusText": "SUCCESS",
  "message": "CIQ validation PASSED",
  "startedAt": "2026-10-26T17:40:01.123+0530",
  "durationMs": 1830,
  "request": { "CIQ_FILE_NAME": "ciq.xlsx", "NODE_TYPE": "PGW_RDS", "...": "..." },
  "results": { "VALIDATION_STATUS": "PASSED", "CR_LIST": "CR1", "JSON_FILE_PATH": "...", "...": "..." },
  "log": [ "..." ]
}
```

| Item | Rule |
|---|---|
| **Status** | `0` SUCCESS, `1` WARNING (finished but needs a look; does not stop a flow by default), `9` FAILED (the same code an NEI uses). |
| **Exit code** | 0 for SUCCESS and WARNING, 9 for FAILED, 2 for a bad command line. With `--exit-zero`, always 0. |
| **Success rule** | Default (R-05): a step fails when the wrapper's status is FAILED. Override (R-06): call with `--exit-zero` and let the workflow test `status` or any result (e.g. `VALIDATION_STATUS`) with its own if / switch. Override per step or for the whole workflow. |
| **stdout** | Only the result. `keyvalue`: `STATUS=`, `STATUS_TEXT=`, `MESSAGE=`, then every result as `KEY=VALUE` (line breaks in values become `\n`). `json`: the document above. `kestra`: the log lines, then one `::{"outputs":{…}}::` line that Kestra turns into task outputs. Anything the component prints goes to stderr. |
| **Secrets** | Values of keys matching `password` / `passwd` / `pswd` / `pwd` / `secret` / `token` are shown as `*****` in the echoed request **and** in the results. |
| **Errors** | A missing parameter, missing file, unknown component, unknown ACTION or an exception all give status 9 with a clear message. The wrapper never crashes without a result. |

### 2.4 Project config (replaces the NEI GRC section)

- **One properties file per project and environment,** e.g. `cr_automation-lab.properties` or `cr_automation-prod.properties`.
- **It holds deployment details:** directories, file-name suffixes, runtimes, mail servers.
- **Relative paths** resolve against the step's working folder. That is how Kestra namespace files work: they are copied into that folder.

## 3. Adding a wrapper

1. Implement `io.azalio.workflow.wrapper.ComponentWrapper`: `name()`, `description()`, `execute(request, context)`.
2. Return `WrapperResult.success / warning / failed(message)` with `.put(key, value)` for results and `.addLog(lines)`.
3. List the class in `META-INF/services/io.azalio.workflow.wrapper.ComponentWrapper` of its jar.
4. Put the jar (and the component's own jar) on the classpath. Nothing else changes: not the core, not the engine, not other workflows.

Rules for wrappers:
- **Don't print to stdout;** use `context.log(...)`.
- **Read paths from `context.config(...)`,** never hard-code them.
- **Keep the NEI's request keys and actions,** so existing request data carries over.

## 4. Wrappers

| Component | ACTIONs | Status |
|---|---|---|
| **CIQ_PROCESSOR** (ciq-processor 1.0.3) | `CR_CIQ_PROCESSOR` (validate + JSON), `GENERATE_CR_CIQ_TEMPLATE` | **Built and tested** |
| CLICR | `MOP_GENERATION`, `MOP_EXECUTION` | Next. Needs `MopExecutionUtil` / `MopGeneratorUtil` freed from `MacroSet` and GRC, the change the team planned. |
| FILECR_REPORT | `GENERATE_REPORT` | Next (same `MacroSet` decoupling) |
| SCRIPT | run a Python / shell script and parse its `KEY=VALUE` output | Planned (generic) |
| MAIL_SEND / MAIL_READ | send mail with attachments; read or wait for a reply | Planned |

### CIQ_PROCESSOR details
- **Same behaviour as the FileCR NEI action:**
  - rules file: `<ciq.rules.dir>/<NODE_TYPE>[_<NODE_VERSION>]_<ACTIVITY_NAME>_validation-rules.yaml`;
  - JSON template: `<ciq.jsonTemplate.dir>/<NODE_TYPE>_<ACTIVITY_NAME>_json-output.yaml`;
  - HTML report template from `ciq.reportTemplate.dir` / `.name`.
- **ciq-processor runs in the same process,** not as a separate `java -jar`, so the wrapper can read the full report (sheets, errors, logs). ciq-processor's code is not changed.
- **Results:**
  - `VALIDATION_STATUS`, `ERRORS`;
  - every ciq-processor output (`CR_LIST`, `CR_COUNT`, `REPORT_EMAIL`, `MMTEL_REFRESH_EMAIL`, `JSON_FILE_NAME`, `REPORT_FILENAME_*`);
  - `JSON_FILE_PATH`, `REPORT_PATH_<FMT>`, `OUTPUT_DIR`, `RULES_FILE`, `JSON_OUTPUT_CONFIG_FILE`;
  - the first 20 errors as `ERROR_1..ERROR_n`, plus `ERRORS_LISTED`, `SHEETS_FAILED`, `GLOBAL_ERRORS`.

## 5. Verification (2026-10-26)

| What | Result |
|---|---|
| Unit tests: contract, JSON, masking, exit codes, stdout cleanliness, Kestra marker, request files, `--param` override, config | 14 / 14 pass |
| Integration tests against the real ciq-processor with the `ggnyaml` 1051 rules: pass, fail, template generation, missing rules / unknown ACTION / missing CIQ | 4 / 4 pass |
| `kestra-local/simulate-task.sh`: the Kestra task's exact command in a fresh working folder with namespace files | Valid CIQ: status 0, `CR_LIST=CR1`, JSON written. Invalid CIQ: status 9 with `ERROR_1`. Exactly one marker line matching Kestra's `TaskLogLineMatcher` pattern. |
| A real Kestra run | **Not yet.** Docker Desktop was not running, and Kestra 2.0 needs Java 21. |

What the tests found and fixed on the way:
- **Secrets were masked in the echoed request but not in the results.** Results are now masked too.
- **Windows paths came out with doubled backslashes on stdout.** Only line breaks are escaped now.

### Data found while testing

The current `ggnyaml` 1051 rules (commit `13e87b4`, 15 Oct) accept only `UNCHANGED` / `ENABLED` / `DISABLED` for **all three** actions (CB, ISD, CF), and FT Number is required only with `ENABLED`.
- **CIQs:** order 1769's CIQ (`ENABLE` / `REMOVE` for ISD and CF) now fails validation with 7 errors. Lab CIQs must use the new words.
- **Execution side is fine with the new words:**
  - `hlr_profile.py` maps `ENABLED` → `ENABLE` and `DISABLED` → `REMOVE` through `_ACTION_ALIASES`. Checked: ISD Normal and CF Busy with both words give the right decisions.
  - The 1051 YAML only compares actions with `UNCHANGED`.

## 6. Build notes

- `mvn package` builds and tests where the Maven repository is reachable.
- **On this laptop (offline, Nokia repository not reachable):** use `./run-tests.sh` (`MVN=<path to mvn>`). It builds with tests skipped, then runs the same JUnit 5 tests with the cached JUnit launcher, because Surefire's JUnit provider is not in the local cache.
- **Plugin versions are pinned** to ones cached locally: compiler 3.11.0, resources 3.3.1, surefire 3.2.2, jar 3.4.1.
- **ciq-processor is a compile-time dependency only** (`systemPath` to its CLI jar). At runtime it is supplied separately (`wrappers/lib/`), so it can be upgraded without rebuilding the wrappers.


## 7. The generic NEI wrapper (current)

**Why.** The NEI jars already produce a status and result parameters, but only by talking to the Macro Server. Verified on 2026-10-26 with `macro_CLICR_ANY_V1.jar` and `macro_FILECR_ANY_V1.jar`:
- **No `main`.** They have no manifest. The only `main` in the CLICR jar is `cliautomation.ExampleCaller`, a hardcoded MOP-generation demo.
- **Status as an object.** The task method returns a `Status` object: `0 0` = connection OK and task OK; a failed task is `0 2` (FileCR / CLICR `STATUS=9`).
- **Results on stdout.** They are printed as `7200 NEW KEY VALUE` lines by `MacroSet.sendData`.
- **Config.** They need their GRC section (`mds.rc`, passed through the `RCFILE` system property).
- **Status-code tables.** For their own error reporting (`sendErrorMsg`), they need the Macro Server's tables from `error_codes.txt`, `phase_codes.txt` and `supp_codes.txt`.

With only `-DRCFILE` and a call sequence of `init` → `login` → `create`, both jars ran without a Macro Server process. The wrapper is exactly that call sequence plus the result contract.

**Call:**
```
java -cp "component-wrappers.jar:<nei>/macro_<NE>_ANY_V1.jar:<nei>/lib/*" io.azalio.workflow.wrapper.WrapperMain      --component NEI --config project.properties      --param ne_type=FILECR --param REQ_TYPE=1 --param ACTION=CR_CIQ_PROCESSOR --param ...      [--result r.json] [--emit kestra] [--exit-zero] [--io-log logs/<task>.io.log] [--mml-log logs/<task>.mml]
```

**Request:** the NEI task arglist exactly as InstantLink sends it:
- `ne_type` (required);
- `product_name` (default `ANY`), `product_version` (default `V1`), `task_type` (default `create`);
- then `REQ_TYPE`, `ACTION` and the action's own parameters.

The macro class is `macro_<ne_type>_<product_name>_<product_version>`.

**Config:**
- `nei.<ne>.grc.<KEY>`: the GRC section; the wrapper writes the `mds.rc`. Or `nei.<ne>.rcfile` to use an existing one. Secret GRC values go in environment variables `NEI_GRC_<KEY>` (e.g. `NEI_GRC_REPO_PASSWORD`), which are added to the section.
- `nei.<ne>.configdir`: the NEI package's `config/` folder with the status-code files.
- `nei.<ne>.login.<KEY>`: the login arglist. Secrets go in environment variables `NEI_LOGIN_<KEY>`.
- `${WORKDIR}` in any value means the step's working folder.

**Result:**

| Item | Rule |
|---|---|
| Status | SUCCESS for task status 0, WARNING for 1 (partly failed), FAILED otherwise or when the connection status ≠ 0 |
| Results | Every `7200 NEW` key, later values winning (e.g. `STATUS=7`, `STATUS_MESSAGE`, `CR_LIST`, `TOTAL_ERRORS`), plus `NEI_MACRO`, `NEI_CONN_STATUS`, `NEI_TASK_STATUS` |
| Log | Everything else the NEI prints |
| IO log (`--io-log`) | `=== IO <component>`, then `--- INPUT` with `> KEY=VALUE` for each request parameter (secrets masked), then `--- OUTPUT (status ...)` with `< KEY=VALUE` for each result, then `=== END IO`. With `--emit kestra` the same block is also printed before the outputs line. |
| MML log (`--mml-log`) | The Macro Server's MML log file (`Set logfile logs/test.mml`). The wrapper sets the Macro Server's `mmlLogFile` / `file` fields, so `MacroSet.printMml` appends there. After the task it closes the NEI's writer (`closeMmlWriter`) and writes `$END$ <REQ_ID>`. Without `--mml-log`, MML lines are dropped, as in the Macro Server with no logfile. |

**Runtime:** Java 8. CLICR needs Nashorn, and the status-code setup uses a Java 8-only reflection step. The Kestra image adds a Java 8 runtime (`NEI_JAVA`); the wrapper module has no compile-time dependency on any Nokia jar.

**Verified (2026-10-26):** 25 / 25 tests pass (22 + 3 CLICR), including the IO log file and the MML log (FileCR `printMml` lines plus `$END$`). The FileCR integration test covers:
- template generation;
- a valid CIQ (`CR_LIST=CR1`, JSON written);
- an invalid CIQ (task status 2);
- a missing `CIQ_FILE_LOCATION` (FileCR's own error message, after the status-code fix);
- an unknown NE and missing config.

`kestra-local/simulate.sh` runs both FileCR flows' exact commands; see `kestra-local/README.md`.

**CLICR (2026-10-26):** the same wrapper runs `macro_CLICR_ANY_V1.jar` for `MOP_GENERATION` (with `CR_NAME`: one CR's MOP, results `ACTIVITY_CR_NAME`, `ACTIVITY_CR_NODE_NAMES`, `ACTIVITY_CR_MOP`, `ACTIVITY_CR_EMAIL`) and `MOP_EXECUTION` (per `NODE_NAME`; results include `ACTIVITY_EXECUTION_STATUS`, `LAST_ACTIVITY_EXECUTED` and the engine's variables). `ClicrNeiIT` covers both in its own JVM. FileCR's ciq-processor jar bundles its own snakeyaml, so the two NEIs never share a classpath; in a flow each task is its own JVM anyway.

Behaviour of the NEI jars found on the way:
- CLICR puts every `REPO_*` GRC value into its arglist; a missing `REPO_PASSWORD` makes MOP generation fail with a NullPointerException.
- The Macro Server reads the GRC once per JVM.
- `MacroSet.printMml` keeps the MML file open in a static writer. The wrapper calls `closeMmlWriter()` after the task.
- CLICR's `YamlWorkflowLoader` never closes the workflow YAML.

**Flow steps:** `kestra-local/namespace-files/cr_automation/bin/nei.sh <ne> <log-name> --param ...` is the one launcher every flow step uses. It builds the classpath for `nei/<ne>/`, adds `--config`, `--io-log` / `--mml-log` under `$ORDER_DIR/logs/`, `--emit kestra --exit-zero`, and keeps the logs as output files.

## 8. The request store (2026-10-27)

`io.azalio.workflow.wrapper.store.RequestStore` (same module, Java 8, JDBC driver on the classpath at run time) keeps
every order / child order (`request`) and every NEI step with its IO (`request_step`, request and results as JSONB) in
the schema `cr_automation` of a PostgreSQL database. Commands: `new-request` (request id: the wanted order number, or
one more than the highest in use - table and order folders - under a table lock; creates the order folder),
`record-step` (from a wrapper `--result` document), `set-stage`, `child-status` (latest MOP_EXECUTION per node: which
failed), `registry` (the order list and one order with steps, children, nodes, `cr_options`, `rollback_options` - what
the Kestra forms read through KV). Rollback is offered for a node when its latest MOP_EXECUTION has
`ROLLBACK_ENABLED=TRUE` (set by CLICR's engine when the configuration phase - and postcheck, if it ran - succeeded),
it is not rolled back yet, and `rollback.<NODE_TYPE>_<ACTIVITY>=true` in the config. Tested against Kestra's
PostgreSQL in kestra-local (see its README).
