# Our requirements for the replacement workflow tool

This file records what the team asked for in discussion. It is separate from BST parity (`01_bst_users_guide_capabilities.md` §6): some needs go beyond BST, and some BST features may not be needed (see `02_cr_automation_logics_usage.md`).

Status: draft, written 2026-10-26 from the design discussion. IDs are `R-xx` so other files can refer to them.

## 1. How steps run

| ID | Requirement | Priority |
|---|---|---|
| R-01 | **No NEI / Macro Server layer.** Steps call components directly: Java jars and main classes, Python scripts, shell commands, HTTP calls. | MUST |
| R-02 | **One generic "run a command" step** (command, arguments, environment, working folder, timeout). New step types can be added as plugins without changing the engine. | MUST |
| R-03 | **Email:** send mail (with attachments), and read or wait for a reply mail (e.g. the MMTel refresh reply), through a built-in step or our own mail jar. | MUST |
| R-04 | Must call Java 8 components and Python 2.7.5 scripts on the target server. The engine itself may use a newer runtime. | MUST |

## 2. Result contract

| ID | Requirement | Priority |
|---|---|---|
| R-05 | **Default success rule:** exit code 0, plus `STATUS=0` when the step prints a STATUS. Anything else is a failure. | MUST |
| R-06 | **Override per step or per whole workflow:** which output decides success (e.g. `STATUS=PASSED` for ciq-processor), which values mean success / warning / failure, and other parameter names. | MUST |
| R-07 | **Outputs:** parsed from stdout `KEY=VALUE` lines (optionally a JSON result file) into step variables. Outputs can be renamed, and list values split on a separator (e.g. `CR_LIST=CR1,CR2`). | MUST |

## 3. Control flow

| ID | Requirement | Priority |
|---|---|---|
| R-08 | **if / else.** | MUST |
| R-09 | **switch / case** with a default. | MUST |
| R-10 | **Loops:** for-each over a list, and while / until with a guard against endless loops. | MUST |
| R-11 | **Parallel fan-out over a list known only at runtime** (one branch per `CR_LIST` entry), with a parallelism limit, a join rule (all must pass / continue on partial) and a status per branch. | MUST |
| R-12 | **Sub-workflows** (reusable library flows) with declared inputs and outputs. | MUST |
| R-13 | **Wait / poll** until a condition or timeout (e.g. a reply mail, a file appearing). | MUST |
| R-14 | **Error handling per step:** `on_failure` = stop / continue / go to step; retries with delay; timeout. | MUST |
| R-15 | **Rollback / compensation steps** that run when an order fails. | SHOULD |
| R-16 | **Manual approval gate** before a production execution. | SHOULD |

## 4. Data

| ID | Requirement | Priority |
|---|---|---|
| R-17 | **Variables per execution.** Step parameters are templates filled from order inputs, earlier step outputs, system values (execution ID, step / branch ID, a work folder per execution) and project config. No hand-built paths or request IDs. | MUST |
| R-18 | **Lookup / reference tables** (key → values) that users can edit without changing a workflow, like BST lookup tables. | SHOULD |

## 5. Generic and multi-project

| ID | Requirement | Priority |
|---|---|---|
| R-19 | **Nothing project-specific in the engine.** CR_AUTOMATION is one project; others will have different workflows. | MUST |
| R-20 | **Per project:** workflows, reusable step templates (e.g. "CIQ validate" defined once and used by 1051 / 1008 / 1058), secrets, environment config (lab / prod paths and runtimes). | MUST |
| R-21 | **Versioned workflow definitions.** A running execution stays on the version it started with. | MUST |
| R-22 | **Readable declarative definitions** (YAML / JSON in Git) plus a visual view. A visual editor is a plus. | SHOULD |

## 6. Operations

| ID | Requirement | Priority |
|---|---|---|
| R-23 | **Durable state:** executions survive an engine restart. | MUST |
| R-24 | **Rerun / resume** from the failed step or branch; cancel a running execution. | MUST |
| R-25 | **Per-step record:** command, arguments (secrets masked), stdout / stderr, outputs, duration, produced files; execution history and audit trail. | MUST |
| R-26 | **Start an execution** from a UI (input form, CIQ file upload), a REST API, a schedule or a file drop. | MUST |
| R-27 | **Role-based access per project:** who may edit, who may start, who may approve. | MUST |
| R-28 | Notifications: mail on completion or failure. | SHOULD |

## 7. Constraints

| ID | Constraint |
|---|---|
| C-01 | **Cheapest option:** open-source or free, used as-is or forked / extended. The licence must allow commercial use and modification in Nokia deliveries without per-seat fees. |
| C-02 | **On-prem Linux server,** possibly with restricted network access. Low operations overhead. |
| C-03 | **Java-centric team.** The existing components (ciq-processor, CLICR engine, FileCR) are Java 8. |
| C-04 | **CLICR and FileCR get a command-line entry point once the tool is chosen.** Today `MopExecutionUtil` / `MopGeneratorUtil` depend on `MacroSet`, GRC config (`mds.rc`) and the MacroServer bootstrap. The plan: arguments in; exit code + `KEY=VALUE` out; GRC values from a project config file. ciq-processor already behaves this way (`CiqProcessorMain`). |

## 8. Example workflow the tool must express (1051)

1. **`ciq_validate`:** ciq-processor jar; success when `STATUS=PASSED`; on failure, mail the validation report and stop.
2. **`mop_generate`:** CLICR CLI, `MOP_GENERATION`.
3. **Optional approval gate.**
4. **For each `CR_LIST` item, in parallel:** CLICR CLI `MOP_EXECUTION`; outputs `SUCCESS_IMSIS` / `FAILURE_IMSIS`.
5. **If there are successes:** POSTCOMPLETION (MMTel), then send the mail and wait for the reply with a timeout.
6. **Always:** generate the report and mail it to `REPORT_EMAIL`.
