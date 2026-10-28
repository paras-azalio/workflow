# Open-source / free replacements for Nokia BST – candidate comparison

Status: research note, written 2026-10-26. Facts were checked against official sites, docs, licence files and the GitHub API on that date. Star counts and release dates come from the GitHub API on 2026-10-26.

Inputs: the team requirements in `00_our_requirements.md` (IDs `R-xx`, `C-xx`) and the BST parity list in `01_bst_users_guide_capabilities.md` §6.

**How to read this file**
- "Native" = ships in the free edition, no code needed.
- "Plugin" = the tool has an extension point; we would write or install a plugin.
- "Build" = we would write it ourselves outside the tool's plugin model.
- **(verify)** = I could not confirm this from an official source. Check it in the proof of concept (PoC).
- Effort: **S** = under 1 week, **M** = 1–4 weeks, **L** = more than 4 weeks, for one developer who knows Java.

---

## 1. Comparison tables

The comparison is split into three tables so it stays readable: licence and platform (1a), workflow features (1b), and operations (1c).

### 1a. Licence, platform, maturity, operations footprint

| Tool | Licence of the free edition (link) | What is paywalled (that matters to us) | Engine language / runtime | Latest release (GitHub, as of 2026-10-26) | GitHub stars | Backing | Ops footprint |
|---|---|---|---|---|---|---|---|
| **Kestra** | Apache-2.0 ([pricing page](https://kestra.io/pricing), [repo](https://github.com/kestra-io/kestra)) | RBAC, SSO/LDAP/SCIM, multi-tenancy, audit logs, secrets manager (incl. namespace secrets), Apps/forms, worker groups, namespace-level variables and policies, "human-in-the-loop approvals" (Apps), backup/restore ([pricing](https://kestra.io/pricing)). OSS secrets are only base64 env vars, with "no encryption at rest, no audit trail" ([secrets doc](https://kestra.io/docs/concepts/secret)). OSS login is one basic-auth credential ([0.24 migration](https://kestra.io/docs/migration-guide/v0.24.0/basic-authentication)). | Java 21 ([requirements](https://kestra.io/docs/administrator-guide/requirements)); plugins in Java | v2.0.7, 2026-10-20 (2.0 GA 2026-09-08, [blog](https://kestra.io/blogs/release-2-0)); 1.3.x still patched | 30,000 | Kestra Technologies (VC-backed company) | Low. One JVM (standalone server) + PostgreSQL (queue and repository both on Postgres in 2.0, [blog](https://kestra.io/blogs/release-2-0)). Min 4 GiB RAM / 2 vCPU. No Docker needed if the Process task runner is used ([Process runner](https://kestra.io/docs/concepts/task-runners/types/process-task-runner)). |
| **Operaton** (Camunda 7 CE fork) | Apache-2.0 ([repo](https://github.com/operaton/operaton)) | Nothing. README says "no open-core model". | Java 17+ (tested 17/21/25) | v2.2.0, 2026-10-23 | 499 | Community; several service companies; no single vendor | Low. One JVM (Spring Boot / Quarkus / app server) + relational DB (PostgreSQL etc.). |
| **Conductor OSS** | Apache-2.0 ([repo](https://github.com/conductor-oss/conductor)) | RBAC, SSO, secrets, audit and other "enterprise grade security" are positioned as Orkes (paid) features ([Orkes comparison](https://orkes.io/platform/conductor-oss-vs-orkes)). Exact OSS auth scope (verify). | Java 21 server; workers in any language (Java SDK available) | v3.32.5, 2026-09-25 (3.33.0 in RC) | 32,385 | Orkes (primary maintainer; origin Netflix) | Medium. Server + one backend combination: Postgres-only, or Redis + Elasticsearch/OpenSearch, or MySQL + ES ([README](https://github.com/conductor-oss/conductor)). Plus our worker processes. |
| **Apache DolphinScheduler** | Apache-2.0 ([repo](https://github.com/apache/dolphinscheduler)) | Nothing (ASF project). | Java | 3.4.3, 2026-09-06 | 14,535 | Apache Software Foundation | Medium. API server + master + worker + DB + registry. Registry is ZooKeeper by default; a JDBC registry on the same DB is possible ([article](https://dev.to/chen_debra_3060b21d12b1b0/running-apache-dolphinscheduler-without-zookeeper-two-proven-registry-alternatives-3n79)). |
| **StackStorm (Orquesta)** | Apache-2.0 ([repo](https://github.com/StackStorm/st2)) | Nothing. RBAC, LDAP and the workflow designer were open-sourced into core in 3.4 ([RBAC doc](https://docs.stackstorm.com/rbac.html), [donation](https://stackstorm.com/2020/05/27/extreme-networks-donates-ewc-to-linux-foundation/)). SAML SSO listed as beta. | Python 3.8–3.11 | v3.9.0 (GitHub date 2026-01-12; previous 3.8.1 was 2023-12) ([release](https://github.com/StackStorm/st2/releases/tag/v3.9.0)) | 6,549 | Linux Foundation; small volunteer team | High. ~10 services + MongoDB + RabbitMQ + coordination backend + nginx. |
| **Flowable** | Apache-2.0 ([repo](https://github.com/flowable/flowable-engine)) | Engines are OSS. Since 7.0 the OSS Modeler, Task and Admin UI apps were removed; the new designer is cloud-only ([7.0 release](https://flowable.com/blog/releases/flowable-open-source-7-0-0-release)). | Java 17+ | flowable-8.0.0, 2026-02-27 | 9,620 | Flowable (company) | Low (library or Spring Boot app + DB), but no UI. |
| **Temporal** | MIT ([repo](https://github.com/temporalio/temporal)) | Self-hosted is complete. Auth/RBAC must be built via ClaimMapper/Authorizer plugins; default allows "every API request" ([security doc](https://docs.temporal.io/self-hosted-guide/security)). | Go server; Java SDK supports Java 1.8+ ([sdk-java](https://github.com/temporalio/sdk-java)) | v1.32.1, 2026-10-08 | 23,987 | Temporal Technologies | Medium–high. Several server services + Postgres/MySQL/Cassandra (+ optional Elasticsearch). |
| **Cadence** | Apache-2.0 per GitHub API ([repo](https://github.com/cadence-workflow/cadence)) | Nothing. | Go server; Java client | v1.4.2, 2026-10-02 | 9,519 | Uber | Medium–high (same model as Temporal). |
| **Camunda 7 CE** | Apache-2.0 | **End of life.** Last CE release 7.24 (Oct 2025); CE repo archived; no more CE releases or patches ([forum](https://forum.camunda.io/t/important-update-camunda-7-community-edition-end-of-life-announced/50921)). | Java | 7.24.0 (Oct 2025), repo archived | 4,265 | Camunda (EE only now) | — |
| **Camunda 8 / Zeebe** | Camunda License 1.0 (source-available) | **Production use needs a paid licence** for self-managed since 8.6 (Zeebe, Operate, Tasklist, Optimize "free for development only") ([8.6 announcement](https://docs.camunda.io/docs/reference/announcements-release-notes/860/860-announcements), [licensing blog](https://camunda.com/blog/2024/10/camunda-licensing-what-you-need-to-know/)). | Java | 8.10.6, 2026-10-22 | 4,339 | Camunda | High (cluster, Elasticsearch/OpenSearch, Identity/Keycloak). |
| **jBPM / Apache KIE** | Apache-2.0 | Nothing, but still in Apache incubation; old GWT editors removed in 10.2 ([KIE 10.2](https://kie.apache.org/blog/kie_10_2_0_release)). | Java 17/21 | KIE 10.2.0, 2026-04-29 | jBPM 1,741 | ASF (incubating), ex-Red Hat | Medium–high; tooling in transition. |
| **Activiti** | Apache-2.0 ([repo](https://github.com/Activiti/Activiti)) | Nothing. | Java | Only 7.21.0-rc tags in Oct 2026 | 10,549 | Hyland/Alfresco | Medium; cloud/K8s oriented. |
| **Apache Airflow** | Apache-2.0 ([repo](https://github.com/apache/airflow)) | Nothing. RBAC via the FAB auth manager. | Python 3 (modern only); Java SDK 1.0.0-beta1 (Jul 2026) | 3.3.2, 2026-09-17 | 47,279 | ASF; Astronomer | Medium–high (API server, scheduler, DAG processor, triggerer, DB). |
| **Prefect** | Apache-2.0 ([repo](https://github.com/PrefectHQ/prefect)) | RBAC, audit logs, SSO are Prefect Cloud only; self-hosted has a shared auth string only ([security settings](https://docs.prefect.io/v3/advanced/security-settings)). | Python 3 | 3.9.x line (dev builds Oct 2026) | 24,076 | Prefect Technologies | Medium. |
| **Dagster** | Apache-2.0 ([repo](https://github.com/dagster-io/dagster)) | RBAC/SSO/audit are Dagster+ only ([RBAC doc](https://docs.dagster.io/dagster-plus/features/authentication-and-access-control/rbac)); OSS webserver has no login. | Python 3 | 1.13.26, 2026-10-09 | 16,327 | Dagster Labs | Medium. |
| **Argo Workflows** | Apache-2.0 ([repo](https://github.com/argoproj/argo-workflows)) | Nothing. | Go; **Kubernetes-native** | v4.1.6, 2026-10-22 | 17,052 | CNCF (graduated) | High: needs a Kubernetes cluster. |
| **n8n** | Sustainable Use License (fair-code) + proprietary `.ee` files ([LICENSE.md](https://github.com/n8n-io/n8n/blob/master/LICENSE.md)) | Use only "for your own internal business purposes or for non-commercial or personal use"; `.ee` code needs an Enterprise licence. | Node.js | n8n@2.44.7, 2026-10-26 | 208,617 | n8n GmbH | Low. |
| **Windmill** | AGPLv3 + proprietary EE code; CE binary has extra terms ([LICENSE](https://github.com/windmill-labs/windmill/blob/main/LICENSE)) | CE: no reselling, no managed service, "modifying it, or wrapping it" not allowed without agreement; SSO capped (10 users per docs), 3 workspaces / 4 groups on the free self-hosted tier, audit logs EE ([pricing](https://www.windmill.dev/pricing), [instance settings](https://windmill.dev/docs/advanced/instance_settings)). | Rust + Postgres | v1.849.0, 2026-10-25 | 18,269 | Windmill Labs | Low–medium. |
| **Node-RED** | Apache-2.0 ([repo](https://github.com/node-red/node-red)) | Nothing. | Node.js | 5.0.9, 2026-10-21 | 23,783 | OpenJS Foundation | Low. |
| **Apache NiFi** | Apache-2.0 ([repo](https://github.com/apache/nifi)) | Nothing. | Java | 2.12.0, 2026-09-13 | 6,277 | ASF | Medium–high (heavy JVM, clustering). |
| **Rundeck** (added) | Apache-2.0 community ([repo](https://github.com/rundeck/rundeck)) | Failed-job resume, HA cluster, SSO and (per third parties) the visual workflow builder are in the paid PagerDuty Runbook Automation ([comparison](https://www.rundeck.com/rundeck-vs-commercial)) (verify table details). | Java | v6.3.0, 2026-10-22 | 6,348 | PagerDuty | Low–medium. |
| **Hatchet** (added) | MIT ([repo](https://github.com/hatchet-dev/hatchet)) | Self-hosted is complete (verify). | Go server; Python/TS/Go/Ruby SDKs (no Java SDK) | v0.110.8, 2026-10-13 | 8,182 | Hatchet (startup) | Medium (Postgres; optional RabbitMQ). |

### 1b. Workflow features (free edition)

| Tool | Definition format | if / switch / loops | Dynamic parallel fan-out + join | Sub-workflows with in/out | Wait / poll / human task | Retries / timeouts / compensation | Generic command / script / Java-jar step |
|---|---|---|---|---|---|---|---|
| **Kestra** | YAML; web code editor + no-code editor + topology view | Native: `If`, `Switch`, `Loop` (for-each), `LoopUntil` (while, with interval and limit) ([flowable tasks](https://kestra.io/docs/workflow-components/tasks/flowable-tasks)) | Native: `Loop` over a runtime list; `concurrencyLimit: 0` = all in parallel; declared `outputs` collected; `transmitFailed` controls the join rule ([Loop](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.loop)) | Native: `Subflow` (wait for result, inputs, outputs) | `Pause` with `onResume` input form and `pauseDuration` + timeout behaviour RESUME/WARN/FAIL/CANCEL ([Pause](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.pause)); `LoopUntil` for polling. EE adds approval Apps. | Native per-task retry and timeout; `errors` / `finally` handlers; `AllowFailure`. No first-class compensation (build as error handler). | Native: Shell `Commands` with the Process runner run any binary on the worker host (`java -jar`, `python2.7`). Stdout line `::{"outputs":{...}}::` becomes task outputs ([blueprint](https://kestra.io/blueprints/outputs-from-shell-commands)). Exit code 0 = SUCCESS, non-zero = FAILED ([0.23 note](https://kestra.io/docs/migration-guide/v0.23.0/script-warnings)). Custom tasks as Java plugins. Mail: `MailSend` task; inbound mail only via `MailReceivedTrigger` ([plugin-email](https://kestra.io/plugins/plugin-email)). HTTP task native. |
| **Operaton** | BPMN 2.0 XML + DMN; desktop Camunda Modeler (MIT) | Native: exclusive gateway (if/switch), loop-backs, sequential multi-instance (for-each), loop characteristics | Native: parallel multi-instance over a collection variable; implicit join; completion condition | Native: call activity with in/out variable mapping | Native: user task + Tasklist; message catch event + timer boundary event (wait for a correlated reply with timeout); timers | Native: job retries with retry time cycle; timer boundary for timeouts; BPMN compensation events | **Build:** no shell task. Write one Java delegate or external-task worker (S). HTTP via connect (verify in Operaton). |
| **Conductor OSS** | JSON; UI visualises executions (designer scope in OSS: verify) | Native: `SWITCH`, `DO_WHILE` | Native: `DYNAMIC_FORK` + `JOIN`; one task per fork, so use `SUB_WORKFLOW` for multi-step branches ([dynamic fork](https://docs.conductor-oss.org/documentation/configuration/workflowdef/operators/dynamic-fork-task.html)) | Native: `SUB_WORKFLOW` | Native: `WAIT` (duration/time), `HUMAN` (waits for an external signal; no form UI) ([choosing tasks](https://docs.conductor-oss.org/devguide/how-tos/Tasks/choosing-tasks.html)) | Native retry count/delay/timeouts per task definition; failure workflow for compensation | **Build:** no shell task; write a Java worker that runs commands (S–M). `HTTP` task native. |
| **DolphinScheduler** | Web drag-and-drop DAG designer; definitions stored in DB (Python SDK exists) | Native: Conditions, Switch. **No loop / for-each task** in the 3.4 task list ([task docs](https://github.com/apache/dolphinscheduler/tree/dev/docs/docs/en/guide/task)) (verify) | Not native in 3.4 task list (verify) | Native: Sub-workflow | No human task; no wait-for-message (verify) | Native retries and timeout alarms; no compensation | Native: Shell, Python, **Java** (fat jar / jar + main class, JDK via environment config) ([Java task](https://github.com/apache/dolphinscheduler/blob/dev/docs/docs/en/guide/task/java.md)), HTTP, SQL, Remote Shell. Outputs from stdout `${setValue(key=value)}` ([context params](https://github.com/apache/dolphinscheduler/blob/dev/docs/docs/en/guide/parameter/context.md)). |
| **StackStorm** | YAML (Orquesta) + web workflow designer | Native: conditional transitions; `with-items`; loops by transitions | Native: `with-items` with concurrency; join | Native: workflow calls workflow | Native: Inquiries (approval with response schema) | Native retry / timeout; no compensation construct | Native: `core.local` / `core.remote` shell and SSH actions; Python actions |
| **Flowable** | BPMN/CMMN XML; no OSS modeler since 7.0 | Native (BPMN) | Native: multi-instance | Native: call activity | Native: user task (no OSS task UI) | Native incl. compensation | Native **Shell task** (`command`, `outputVariable`, `errorCodeVariable`), HTTP task, Email task ([BPMN constructs](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs)) |
| **Temporal / Cadence** | Code only (Java, Go, …) | In code | In code | Child workflows | Signals, timers, durable sleep | Native retries/timeouts; saga in code | In code (activities) |
| **Airflow** | Python DAGs | Branch operators; no while loop | Dynamic task mapping | Task groups / trigger DAG | Sensors (deferrable); HITL operators since 3.1 ([HITL](https://airflow.apache.org/docs/apache-airflow/3.2.0/tutorial/hitl.html)) | Retries/timeouts | BashOperator |
| **Prefect / Dagster** | Python code | In code | In code / dynamic outputs | In code | Limited / in code | Retries | Shell via library |
| **Argo** | Kubernetes YAML (CRDs) | `when`, recursion | `withParam` fan-out | Templates | Suspend | Retries | Each step is a container |
| **n8n / Windmill / Node-RED** | Visual (+ JSON / scripts) | Yes | Partial | Yes | Wait nodes / approval steps | Partial | Script / exec nodes |
| **NiFi** | Visual dataflow | Routing processors | Not a workflow model | Process groups | No | Back-pressure, not retries per step | ExecuteProcess / ExecuteStreamCommand |

### 1c. Operations (free edition)

| Tool | Durable state | Resume / rerun from failed step; cancel | Versioning; in-flight runs keep their version | UI to start runs (form, file upload) + REST API | RBAC / multi-tenancy in the free edition | Lookup tables |
|---|---|---|---|---|---|---|
| **Kestra** | Yes (Postgres) | Restart = rerun failed tasks in the same execution; Replay = new execution from any chosen task, with original/latest/specific revision ([replay](https://kestra.io/docs/how-to-guides/replay)); kill | Flow revisions in OSS; API can run a given `?revision=`. Pinning of in-flight runs: (verify in PoC). | Yes: typed flow inputs incl. FILE render as an execution form; full REST API | **No** (EE only). One basic-auth login. Namespaces exist but without per-namespace secrets/variables/permissions in OSS. | Native KV store, namespace-scoped, editable in UI/API, TTL ([KV store](https://kestra.io/docs/concepts/kv-store)); no versioning |
| **Operaton** | Yes (DB) | Incident retry continues at the failed job; process-instance modification via REST to restart at any activity; cancel | Every deployment = new version; running instances stay on theirs; migration API | Tasklist start forms; REST API. File upload in forms (verify in Operaton). | **Yes**: Admin app with users, groups, authorizations; multi-tenancy | DMN decision tables (versioned, deployed) |
| **Conductor OSS** | Yes | `retry` (from last failed task), `rerun` from a task, `restart` with original or latest definition ([debugging](https://docs.conductor-oss.org/devguide/how-tos/Workflows/debugging-workflows)); terminate | Versioned definitions; restart with original definition supported | REST API; UI start with JSON input; no forms / file upload | **No** (Orkes feature) (verify) | None (use DB/HTTP) |
| **DolphinScheduler** | Yes | Rerun; recover from failed node | Workflow definition versions | Web UI with start parameters; resource center for files; REST API | **Yes**: tenants, users, projects, authorisation | None (resource files / SQL) |
| **StackStorm** | Yes (MongoDB) | Re-run from chosen task(s) of a completed workflow ([operations](https://docs.stackstorm.com/orquesta/operations.html)); cancel/pause | No definition versioning (packs in Git) | Web UI form from action parameter schema; REST API; no file upload | **Yes** (RBAC + LDAP in core) | Datastore key-value |
| **Flowable** | Yes | Job retry; process-instance migration | Versioned deployments | REST API only (no OSS UI) | IDM engine; no UI | DMN |
| **Temporal / Cadence** | Yes | Reset to an event; cancel | Worker versioning in code | Read-only UI; no forms | Build (ClaimMapper/Authorizer); namespaces | Build |
| **Airflow** | Yes | Clear/rerun tasks | DAG versioning in Airflow 3 (rerun may use latest bundle by default, [docs](https://astronomer.io/docs/airflow/airflow-dag-versioning)) | Trigger form with params | Yes (FAB auth manager) | Variables |

---

## 2. Disqualified or not recommended

| Tool | Verdict | Precise reason |
|---|---|---|
| **Camunda 8 / Zeebe** | Disqualified (licence) | Since 8.6, self-managed production use needs a paid Enterprise licence; components are "free for development only" under Camunda License 1.0 ([8.6 announcement](https://docs.camunda.io/docs/reference/announcements-release-notes/860/860-announcements)). Breaks C-01. |
| **Camunda 7 Community Edition** | Disqualified (end of life) | CE ended Oct 2025 with 7.24; repo archived; no security patches ([forum](https://forum.camunda.io/t/important-update-camunda-7-community-edition-end-of-life-announced/50921)). Use the Apache-2.0 fork **Operaton** instead (or CIB seven / FINOS Fluxnova, see [comparison](https://wz-it.com/en/blog/camunda-7-end-of-life-operaton-cibseven-fluxnova-compared/)). |
| **n8n** | Disqualified (licence) | Sustainable Use License: use "only for your own internal business purposes", distribution only free and non-commercial; `.ee` features need a paid licence ([LICENSE.md](https://github.com/n8n-io/n8n/blob/master/LICENSE.md)). Using it inside paid Nokia deliveries to an operator is at best ambiguous. Not open source. |
| **Windmill** | Disqualified (licence) | AGPLv3 core plus proprietary EE code. The CE binary forbids modifying, wrapping, reselling or offering as a service without an agreement; SSO and audit are limited ([LICENSE](https://github.com/windmill-labs/windmill/blob/main/LICENSE)). A clean AGPL build without EE code is possible, but AGPL obligations plus a fork make it a poor "cheapest" choice. |
| **Argo Workflows** | Disqualified (ops) | Requires Kubernetes; every step is a container. Too heavy for one restricted on-prem Linux server (C-02). |
| **Temporal / Cadence** | Not recommended | Workflows are code only. No declarative definition, no visual view, no start forms (R-22, R-26). Auth must be built. Good engine for developers, wrong tool for a BST-style configurable workflow catalogue. (Licences are fine: MIT / Apache-2.0.) |
| **Prefect, Dagster** | Not recommended | Data-pipeline tools defined in Python code. RBAC, SSO and audit are paid-cloud only ([Prefect](https://docs.prefect.io/v3/advanced/security-settings), [Dagster](https://docs.dagster.io/dagster-plus/features/authentication-and-access-control/rbac)). No human task / message wait model. |
| **Apache Airflow** | Not recommended | Licence and RBAC are fine. But DAGs are Python code, it is built for scheduled batch pipelines, has no while loop, and a re-run of a task may use the latest DAG version by default ([versioning](https://astronomer.io/docs/airflow/airflow-dag-versioning)). Ops footprint of 4+ services. Java SDK is still beta. |
| **Flowable** | Not recommended (missing UI) | Engine is strong and even has a native Shell task. But the OSS Modeler, Task and Admin apps were removed in 7.0 and the new designer is cloud-only ([7.0 release](https://flowable.com/blog/releases/flowable-open-source-7-0-0-release)). We would build all UI (L). Operaton gives the same BPMN model with UIs included. |
| **jBPM / Apache KIE** | Not recommended | Still incubating at the ASF; tooling in transition (classic editors removed in 10.2, [release](https://kie.apache.org/blog/kie_10_2_0_release)); Kogito focus is cloud-native/Quarkus. High learning and ops cost. |
| **Activiti** | Not recommended | Only release-candidate tags in Oct 2026; cloud/K8s orientation; smaller momentum than Flowable/Operaton. |
| **Node-RED** | Not recommended (missing features) | Event-flow tool, not a durable workflow engine: no per-execution history, no resume from a failed step, no versioned definitions (design fact; no single source page). |
| **Apache NiFi** | Not recommended (wrong model) | Dataflow / streaming tool. No human tasks, sub-workflow calls with outputs, or per-request execution trace in the BST sense. Heavy JVM. |
| **Rundeck Community** | Not recommended | Job runner with steps, good for commands. But conditional logic is weak in Community, and "Failed Job Resume" is a paid feature ([comparison](https://www.rundeck.com/rundeck-vs-commercial)). No dynamic fan-out or sub-workflow outputs. |
| **Hatchet** | Not recommended | Code-first like Temporal; no Java SDK. |
| **DolphinScheduler** | Viable, not top 3 | Strong on our side: Apache-2.0, Java, RBAC/projects free, visual designer, native Shell/Python/Java tasks, and `${setValue(k=v)}` output parsing. Missing MUSTs (verify): for-each/while loops, dynamic fan-out over a runtime list (R-10, R-11), human task and wait-for-message (R-13, R-16). It is a batch scheduler at heart. |
| **StackStorm** | Viable, not top 3 | Strong feature fit: YAML + designer, `with-items` fan-out, Inquiries, native shell/SSH, free RBAC/LDAP. Against it: heavy ops (MongoDB + RabbitMQ + ~10 services), Python stack, slow release cadence (3.8.1 Dec 2023 → 3.9.0 2025/26), small maintainer base, no definition versioning. |

---

## 3. Top 3 – fit against our requirements

### 3.1 Kestra OSS (Apache-2.0)

**Works out of the box (free edition)**
- R-01, R-02, R-04: Shell `Commands` with the Process task runner runs `java -jar …` with a Java 8 path and `python2.7 …` on the worker host. No Docker needed. The engine runs on Java 21; our Java 8 jars run as child processes.
- R-05 (partly): exit code 0 = SUCCESS, non-zero = FAILED.
- R-07 (partly): a stdout line `::{"outputs":{...}}::` becomes task outputs.
- R-08 to R-12: `If`, `Switch`, `Loop` (runtime list, parallel with `concurrencyLimit`, collected `outputs`, `transmitFailed` join rule), `LoopUntil` (while with interval and limit), `Subflow` with inputs/outputs.
- R-13: `LoopUntil` with a poll interval and limit; `Pause` with a timeout and a timeout behaviour.
- R-14: per-task `retry`, `timeout`, `errors`, `finally`, `AllowFailure`.
- R-16: `Pause` + `onResume` inputs gives a simple approval form in the UI.
- R-17: Pebble templates over inputs, outputs, `execution.id`, and the working directory.
- R-18: the KV store is editable in the UI and API, and readable from flows.
- R-21, R-22: flow revisions; YAML in Git; web editor with topology view and no-code editor.
- R-23, R-24: Postgres-backed state; Restart (same execution) and Replay from any task; kill.
- R-25: per-task logs, outputs, Gantt view, execution history.
- R-26: execution form from typed inputs (including FILE for the CIQ Excel), REST API, schedules, triggers.
- R-28: `MailSend` and `MailExecution` tasks.
- C-01: Apache-2.0 allows commercial use and modification. C-02: one JVM plus PostgreSQL. C-03: plugins are Java.

**Must be built**

| Gap | Solution | Effort |
|---|---|---|
| R-05/R-06/R-07 result contract: parse `KEY=VALUE` (and JSON), `STATUS=0` default, per-step/per-flow override of the success key and success/warning/failure values, rename outputs, split lists | Custom Java task plugin `RunComponent` (command, args, env, workdir, timeout, `successKey`, `successValues`, `warningValues`, `rename`, `split`). Sets SUCCESS/WARNING/FAILED itself. Quick start alternative: a wrapper script that converts `KEY=VALUE` into `::{"outputs":…}::` and maps STATUS to the exit code (S, but no WARNING state). | M |
| R-03 read/wait for a reply mail | Small `MailCheck` task (IMAP search by CR ID / subject) used inside `LoopUntil` with an interval and limit. Kestra only ships mail *triggers*, not a read task. | S |
| R-20 reusable step templates | Library subflows per namespace, plus the `RunComponent` plugin defaults. 2.0 removed `pluginDefaults` in OSS ([blog](https://kestra.io/blogs/release-2-0)), so defaults go into subflows or the plugin. | S |
| R-20 per-project secrets and lab/prod config | OSS secrets are global env vars. Use one Kestra instance per environment (lab, prod); keep project config in namespace files or KV; secrets via env vars or a small Vault-backed task. | S–M |
| R-27 RBAC per project, SSO, user-level audit | Not in OSS. Options: (a) one instance per project/environment behind a reverse proxy with corporate SSO (S–M ops, coarse); (b) an authorising proxy that maps users to namespaces by API path (M–L, fragile); (c) buy EE later; (d) fork and add RBAC (L, permanent maintenance). Prefer (a). | M |
| R-15 compensation | Pattern: each step records an undo action in outputs/KV; an `errors` branch runs the undo subflow in reverse. | M |
| BST-style rule-based logic selection (§6 item 2) | A dispatcher flow with `Switch` → `Subflow`. Dynamic `flowId` from an expression: (verify). | S |
| R-21 in-flight runs keep their revision | Confirm in PoC that an execution started on revision N keeps running N after a new save. | S (test) |

**Risks**
- Open-core boundary. Governance features sit in EE, and some items moved there (e.g. Git dashboard tasks in 2.0). Pin versions and keep our extensions as plugins.
- Fast change with breaking releases: 2.0 removed `ForEach`/`ForEachItem` (now `Loop`), `pluginDefaults`, the `Resume` task and more ([blog](https://kestra.io/blogs/release-2-0)). Budget upgrade work.
- Security. Recent advisories included a basic-auth bypass (fixed in 1.0.45 / 1.3.21) and unauthenticated management endpoints before 2.0.0-rc6 ([third-party advisory list](https://releasealert.dev/cve/CVE-2026-55069), [Kestra 2.0 hardening note](https://kestra.io/docs/migration-guide/v2.0.0/management-endpoint-hardening)). Keep it patched and do not expose port 8081.
- OSS secrets are base64 env vars without access control.

### 3.2 Operaton (Apache-2.0, Camunda 7 fork)

**Works out of the box**
- R-08 to R-12: gateways, multi-instance over a collection (dynamic parallel fan-out with join and completion condition), call activity with in/out mapping.
- R-13: message catch event plus a timer boundary event (wait for a correlated reply with a timeout).
- R-14: job retries with retry cycles; timer boundary events for timeouts; error events.
- R-15: native BPMN compensation.
- R-16: user tasks in Tasklist.
- R-18: DMN decision tables as versioned lookup tables.
- R-21: versioned deployments; running instances stay on their version; a migration API.
- R-23, R-24: DB state; incident retry; instance modification; cancel.
- R-25 (partly): Cockpit plus the history tables.
- R-26 (partly): Tasklist start forms and the REST API.
- **R-27: users, groups, authorizations and multi-tenancy are free.**
- C-01: Apache-2.0 with "no open-core model". C-02: one JVM plus a DB. C-03: Java-native.

**Must be built**

| Gap | Solution | Effort |
|---|---|---|
| R-01/R-02/R-04 generic command step | `RunCommand` JavaDelegate or external-task worker, with Modeler element templates so it appears as a configurable step | S |
| R-05 to R-07 result contract | Same parser/mapper as Kestra's, inside `RunCommand`; map WARNING to a variable or a BPMN error | S–M |
| R-03 mail read/wait | IMAP poller that correlates a BPMN message by CR ID; send mail via a delegate | S–M |
| R-20 secrets / environment config | External config + Vault/env lookup in delegates | S–M |
| R-22 readable definitions | BPMN XML is not human-friendly. Use the Camunda Desktop Modeler (MIT, still lists Camunda 7 support in 2026, [docs](https://docs.camunda.io/docs/components/modeler/desktop-modeler/)); optionally embed bpmn-js in a web page | S (adopt) / M (web) |
| R-25 full per-step stdout/history in the UI | CE-lineage Cockpit history views were limited in Camunda 7 CE. Check Operaton's current Cockpit; otherwise store stdout as variables/attachments and add a small history view (verify) | M |
| R-26 CIQ file upload form | Embedded or generated Tasklist form with a file variable (verify), or a small upload page calling the REST API | S–M |

**Risks**
- Small community: 499 stars and several competing forks (Operaton, CIB seven, Fluxnova). Long-term support depends on volunteers and service firms.
- BPMN learning curve for the delivery team. Diagrams are XML.
- The desktop Modeler depends on Camunda continuing to support Camunda 7 element templates.
- More glue code than Kestra for command steps, mail and output views.

### 3.3 Conductor OSS (Apache-2.0)

**Works out of the box**
- R-08 to R-12: `SWITCH`, `DO_WHILE`, `DYNAMIC_FORK` + `JOIN`, `SUB_WORKFLOW`.
- R-13, R-16: `WAIT` and `HUMAN` (completed by an API call).
- R-14: retries, delays and timeouts per task definition; failure workflow.
- `HTTP`, `INLINE` (JavaScript) and `JSON_JQ_TRANSFORM` tasks.
- R-21: versioned JSON definitions; restart with the original definition.
- R-23, R-24: retry from the last failed task, rerun from a task, terminate.
- UI shows executions with task inputs and outputs. REST API.
- Backed by Orkes; very active (32k stars, monthly releases).

**Must be built**

| Gap | Solution | Effort |
|---|---|---|
| R-01 to R-07 command step + result contract | Java worker service polling a `run_command` task type; runs the process; parses `KEY=VALUE`; sets COMPLETED / FAILED | M |
| R-03 mail | Mail send/read worker | S |
| R-11 multi-step branch per CR | `DYNAMIC_FORK` runs one task per fork, so wrap the per-CR steps in a sub-workflow | S |
| R-15 compensation | Failure workflow with recorded undo actions | M |
| R-18 lookup tables | Small table service or DB + HTTP task | M |
| R-26 start form + CIQ upload; R-16 approval UI | Small web front end calling the REST API | M |
| R-27 RBAC/SSO/audit | Not in OSS (Orkes feature). Build an auth proxy, or one instance per project | L |
| R-20 per-project secrets/config | Worker-side config and Vault | M |

**Risks**
- Most of the "usable product" features (RBAC, human-task forms, secrets) are where Orkes sells its paid product.
- JSON definitions are verbose.
- More moving parts: a Java 21 server, a backend combination, and our workers.

---

## 4. Recommendation

### Primary: Kestra OSS, used as-is and extended with our own Java plugins (no fork)

Why:
1. **It covers the largest share of our MUSTs with zero code.** Free features include a generic command step on the host (Java 8 jars and Python 2.7 through the Process runner), for-each with parallel concurrency over a runtime list, while/until loops, if/switch, subflows with outputs, pause with timeout and an approval form, retries and timeouts, restart and replay from any task, typed start forms with file upload, a KV store for lookup tables, and readable YAML with a visual view.
2. **It is the cheapest to run and to extend.** Apache-2.0 allows commercial use and modification. It needs one JVM plus PostgreSQL. Extensions are Java plugins, which suits the team.
3. **The main gap is governance, not orchestration.** RBAC, SSO, audit and the secrets manager are Enterprise-only. We handle this with one instance per environment (and per project if needed) behind corporate SSO at the proxy. If Nokia later needs fine-grained RBAC, buying EE is a contained upgrade path; the flows do not change.

**Use as-is, do not fork.** Kestra releases often and with breaking changes (2.0). A fork would cost permanent merge work. Keep everything Nokia-specific in plugins (`RunComponent`, `MailCheck`, compensation helpers) and in library subflows. Pin a Kestra version per delivery.

### Fallback: Operaton (Camunda 7 fork, Apache-2.0)

Choose Operaton instead if **per-project RBAC inside one shared instance**, **human-task queues**, or **true BPMN compensation** become hard requirements. Those are free in Operaton and Enterprise-only (or missing) in Kestra. Operaton costs more glue code (command step, mail, output views) and has a smaller community.

Why not Conductor as the fallback: it needs more building than Operaton (command workers, forms, lookups) and still lacks free RBAC.

### Minimal proof of concept (2–3 weeks, Kestra 2.0.x)

Build the 1051 flow from `00_our_requirements.md` §8 on one Linux VM with PostgreSQL, using only free features plus our plugins.

| # | Step | Shows | Pass criterion |
|---|---|---|---|
| 1 | Start from the UI with a FILE input (CIQ Excel) and from `curl` against the REST API | R-26 | Both start an execution with the file in its working folder |
| 2 | `ciq_validate`: `RunComponent` runs the ciq-processor jar with **Java 8** (`CiqProcessorMain`); success when `STATUS=PASSED` (per-step override); outputs include `CR_LIST=CR1,CR2,CR3` split into a list | R-02, R-04, R-05–R-07 | Wrong CIQ → FAILED with the report mailed (`MailSend`) and the flow stops |
| 3 | `Switch` on `STATUS`; a Python **2.7.5** script step in one branch | R-09, R-04 | Branch taken matches the status |
| 4 | `Pause` approval gate with an `onResume` field and `pauseDuration` | R-16 | Resume in the UI continues; timeout fails or cancels as configured |
| 5 | `Loop` over `CR_LIST` with `concurrencyLimit: 2`; each item calls a library `Subflow` that runs the CLICR CLI (`MOP_EXECUTION`) and returns `SUCCESS_IMSIS` / `FAILURE_IMSIS` | R-11, R-12, R-20 | Branches run in parallel; per-branch status and outputs collected; one failing CR handled by the join rule |
| 6 | `If` successes exist: send the MMTel mail, then `LoopUntil` + `MailCheck` waits for the reply (interval 1 min, limit 30 min) | R-03, R-10, R-13 | Reply found → continue; no reply → timeout path |
| 7 | `finally`: generate the report and mail it to `REPORT_EMAIL` | R-28, §8.6 | Mail sent on success and failure |
| 8 | Kill the Kestra JVM mid-loop, restart it | R-23 | Execution continues |
| 9 | Make step 5 fail for one CR, fix the input, **Restart** / **Replay from that task** | R-24 | Only the failed part re-runs |
| 10 | Save a new flow revision while an execution is paused at step 4 | R-21 | The paused run finishes on its original revision |
| 11 | KV lookup table edited in the UI changes a step's parameter without a flow change | R-18 | New value used on the next run |
| 12 | Two namespaces (`cr_automation`, `other_project`) on separate instances behind an SSO proxy | R-27 workaround | Users of project A cannot reach project B |

If steps 10 or 12 fail, or the team rejects the instance-per-project model, run the same PoC on Operaton before deciding.

---

## Sources (main)
- Kestra: [pricing/editions](https://kestra.io/pricing), [2.0 release](https://kestra.io/blogs/release-2-0), [flowable tasks](https://kestra.io/docs/workflow-components/tasks/flowable-tasks), [Loop](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.loop), [Pause](https://kestra.io/plugins/core/flow/io.kestra.plugin.core.flow.pause), [replay](https://kestra.io/docs/how-to-guides/replay), [secrets](https://kestra.io/docs/concepts/secret), [KV store](https://kestra.io/docs/concepts/kv-store), [email plugin](https://kestra.io/plugins/plugin-email), [requirements](https://kestra.io/docs/administrator-guide/requirements), [basic auth](https://kestra.io/docs/migration-guide/v0.24.0/basic-authentication)
- Camunda: [8.6 licence](https://docs.camunda.io/docs/reference/announcements-release-notes/860/860-announcements), [licensing blog](https://camunda.com/blog/2024/10/camunda-licensing-what-you-need-to-know/), [C7 CE EOL](https://forum.camunda.io/t/important-update-camunda-7-community-edition-end-of-life-announced/50921), [Desktop Modeler](https://docs.camunda.io/docs/components/modeler/desktop-modeler/)
- Operaton: [repo/README](https://github.com/operaton/operaton); fork comparison: [wz-it.com](https://wz-it.com/en/blog/camunda-7-end-of-life-operaton-cibseven-fluxnova-compared/) (third party)
- Conductor: [repo](https://github.com/conductor-oss/conductor), [releases](https://github.com/conductor-oss/conductor/releases), [choosing tasks](https://docs.conductor-oss.org/devguide/how-tos/Tasks/choosing-tasks.html), [debugging](https://docs.conductor-oss.org/devguide/how-tos/Workflows/debugging-workflows), [Orkes vs OSS](https://orkes.io/platform/conductor-oss-vs-orkes)
- Flowable: [7.0 release](https://flowable.com/blog/releases/flowable-open-source-7-0-0-release), [BPMN constructs](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs)
- Others: [Temporal security](https://docs.temporal.io/self-hosted-guide/security), [n8n licence](https://github.com/n8n-io/n8n/blob/master/LICENSE.md), [Windmill licence](https://github.com/windmill-labs/windmill/blob/main/LICENSE), [Airflow HITL](https://airflow.apache.org/docs/apache-airflow/3.2.0/tutorial/hitl.html), [Prefect security](https://docs.prefect.io/v3/advanced/security-settings), [Dagster RBAC](https://docs.dagster.io/dagster-plus/features/authentication-and-access-control/rbac), [StackStorm RBAC](https://docs.stackstorm.com/rbac.html), [StackStorm 3.9.0](https://github.com/StackStorm/st2/releases/tag/v3.9.0), [DolphinScheduler Java task](https://github.com/apache/dolphinscheduler/blob/dev/docs/docs/en/guide/task/java.md), [KIE 10.2](https://kie.apache.org/blog/kie_10_2_0_release), [Rundeck comparison](https://www.rundeck.com/rundeck-vs-commercial)
- Stars, licences (SPDX) and release dates: GitHub REST API `repos/{owner}/{repo}` and `/releases`, queried 2026-10-26.
