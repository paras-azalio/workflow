# 05 – Recommendation: which open-source tool replaces BST for us

Date: 2026-10-26. This file combines:
- `00_our_requirements.md`: what we asked for (`R-xx`);
- `01_bst_users_guide_capabilities.md`: everything BST can do (#1–71);
- `02_cr_automation_logics_usage.md`: what our real logics use;
- `03_bst_reference_manual.md`: the admin-level additions (#72–93);
- `04_open_source_candidates.md`: 22 tools compared.

The deciding facts were checked again directly on 2026-10-26 (see Sources).

## 1. Decision

**Primary: Kestra OSS (Apache-2.0), used as-is and extended with our own plugins. No fork.**
**Fallback: Operaton (Apache-2.0, the community fork of Camunda 7).**

The one open choice that can change this is **access control per project** (R-27); see §4.

## 2. Why Kestra

1. **It covers what our logics really do.** `02` shows the real CR logics use a small part of BST: If-loops, MapParameters, SendToNE, Dynamic Split and Join, and the Order Management activity wrapper. The table in §3 maps each one to a free Kestra feature.
2. **It covers what you asked for, with no code.** That includes loops (`Loop`, `LoopUntil`), `If`, `Switch`, subflows with outputs, parallel for-each over a runtime list with a concurrency limit, `Pause` with a timeout and resume inputs (approval / RETRY-ABORT), retries and timeouts per task, `errors` / `finally` branches, restart and replay from a failed task, a start form with file upload (the CIQ), a KV store (lookup tables), and mail send.
3. **It runs our components directly.** The shell task with the Process runner starts `java -jar …` on a Java 8 path and `python2.7 …` on the host. The engine runs on its own JVM (Java 21); our Java 8 jars run as child processes, so nothing in them changes.
4. **It is the cheapest option.**
   - **Licence:** Apache-2.0 allows commercial use and modification.
   - **Footprint:** one JVM plus PostgreSQL.
   - **Extensions:** written in Java, which suits the team.
   - **Definitions:** readable YAML in Git, with a topology view and a no-code editor in the UI.
5. **The alternatives are ruled out by licence or by build effort.**
   - **Licence:**
     - Camunda 8 needs a paid licence in production since 8.6.
     - Camunda 7 CE is end-of-life.
     - n8n allows only internal business use.
     - Windmill's free edition forbids modification.
   - **Build effort:**
     - Temporal and Cadence are code-only.
     - Argo needs Kubernetes.
     - Conductor needs workers, forms and lookup tables built, and has no free RBAC.
6. **Our own CLICR YAML engine was considered and rejected as the orchestrator.** It already has loops, conditions, `register` and validation. But it has no durable state, no parallel branches, no UI, no versioning and no RBAC, and building those would cost more than adopting Kestra. It stays what it is: the CLI executor called by a workflow step.

## 3. How our real BST logics map onto Kestra

| What `LL_CR` / `LL_CR_CUSTOM_LOGIC` do today (`02`) | In Kestra |
|---|---|
| **Dynamic Split and Join** per CR (one child request each, then OMDB polling) and per node (CLICR `MOP_EXECUTION`) | `Loop` over `CR_LIST` / the node list with `concurrencyLimit`, each item calling a `Subflow`. The subflow is waited for natively, so **the OMDB polling and the unbounded poll loop disappear**. |
| About 30 loops built from If back-links and `+1` counters, several unbounded | `Loop` / `LoopUntil` with an explicit limit and interval |
| SendToNE → FILECR `CR_CIQ_PROCESSOR`, then branch on `STATUS=="7"` | `RunComponent` (our plugin) runs the ciq-processor jar; success rule `STATUS=PASSED`; outputs `CR_LIST`, `CR_COUNT`, `REPORT_EMAIL`, `MMTEL_REFRESH_EMAIL`, `JSON_FILE_NAME` |
| SendToNE → CLICR `MOP_GENERATION` / `MOP_EXECUTION` / `_POSTCOMPLETION` | `RunComponent` runs the CLICR command-line launcher (to be added, C-04) |
| SendToNE → EMAIL / E2ESMTP (9 sends) | Kestra `MailSend` |
| SendToNE → READEMAIL (wait for a reply with attachments; MMTel reply classification with up to 3 tries) | `LoopUntil` + our `MailCheck` plugin (IMAP search by subject / CR ID; body and attachments as outputs) |
| SendToNE → OrderHub (fallout, ManualApproval CONTINUE/ABORT, RETRY) | `Pause` with `onResume` inputs (CONTINUE / ABORT / RETRY) shown in the Kestra UI, with a timeout. Decision needed: keep OrderHub as the operators' screen (then it is called over HTTP) or use the Kestra UI. |
| SendToNE → CLI (zip a report), GIT (get the CIQ) | Shell `Commands` / HTTP task |
| SendToNE → OMDB (child-request status) | Not needed (see the Split and Join row) |
| Timed task (`TASK_START_TIME`) used as a delay | `Pause` with a duration, or `LoopUntil` interval |
| OM activity wrapper: CreateSchedule APPEND + UpdateBaseline + ActivityStatus around nearly every NE call | Built in: each task run has a state, a duration and a **Gantt view** per execution. The wrapper steps go away. If an estimated schedule / baseline is still wanted, that becomes a later add-on. |
| MapParameters, string handling, `($X)` references | Pebble templating over inputs, outputs and variables |
| 5 lookup tables via `MT_Generic_Lookup` (timeouts, counters, email texts, library-logic choice, NE counts) | KV store (editable in the UI and API), or namespace files |
| Rollback flow drawn but not connected (22 dead methods) | `errors` branch running an undo subflow. Compensation is a pattern we write (R-15), not an engine feature. |
| Not used today: Switch, fixed S&J, ParallelSend2, SendOriginalTask, Manual (WFC), Notification, Alarm, Database, in-flight amend/cancel, catalog | Not needed for parity. Kestra still has `Switch` (R-09). |

Things `02` found that the new flows fix rather than copy:
- success paths with no final response;
- a failed node not failing the order;
- typos that change behaviour (`OEPR1`, `RERTY_FLAG`);
- three sub-activities and two NE IDs hard-coded in If steps;
- a clear-text SFTP password in a lookup table.

## 4. Gaps we must build or decide

| Gap | Our answer | Effort |
|---|---|---|
| **Result contract** (R-05–R-07): default exit 0 + `STATUS=0`, override per step / workflow, `KEY=VALUE` parsing, WARNING state, rename, split lists | `RunComponent` Java task plugin. A wrapper script that prints Kestra's `::{"outputs":…}::` line is a stop-gap for the proof of concept. | M |
| **Read / wait for a reply mail** (R-03) | `MailCheck` plugin used inside `LoopUntil` | S |
| **Command-line entry points for CLICR and FileCR** (C-04) | New launcher classes (arguments in; exit code + `KEY=VALUE` out; GRC values from a config file). The existing NEI classes stay. | S–M |
| **Access control per project** (R-27): RBAC, SSO, audit and the secrets manager are **Enterprise-only** in Kestra | Choose one: (a) one Kestra instance per project and environment behind the corporate SSO proxy (cheap, coarse); (b) buy Kestra EE later (flows unchanged); (c) switch to **Operaton**, where users, groups, authorizations and multi-tenancy are free. | **Decision** |
| Secrets (OSS: environment variables, no access control) | Per-instance env vars, or a small Vault-reading task | S–M |
| Reusable step templates (R-20) | Library subflows plus defaults inside `RunComponent` (2.0 removed `pluginDefaults` from OSS) | S |
| Compensation (R-15) | `errors` branch + undo subflow pattern | M |

**Risks:**
- Kestra releases often and with breaking changes. Version 2.0 (7 Sep 2026) replaced `ForEach` / `ForEachItem` with `Loop` and removed `pluginDefaults`. Mitigation: pin one version per delivery and keep all our logic in plugins and subflows.
- Recent authentication advisories. Mitigation: stay patched and don't expose the management port.

## 5. Proof of concept (2–3 weeks)

The 12-step plan in `04` §4 stands. It builds the 1051 flow on one Linux VM with PostgreSQL. The two tests that can flip the decision come first:
1. **A paused execution keeps its flow revision** after a new revision is saved (R-21).
2. **The access model is acceptable to the team:** an instance per project behind SSO, or EE, or Operaton (R-27).

If either fails, run the same proof of concept on Operaton.

## 6. Decisions needed from the team

1. **R-27:** an instance per project, Kestra EE later, or Operaton?
2. **Operator screen:** keep OrderHub for approvals and fallout, or move them to Kestra's pause / resume UI?
3. **Planning:** are the OM-style Gantt / baseline estimates still needed, or is Kestra's per-execution Gantt enough?
4. **Go-ahead** for the CLICR / FileCR command-line launchers (C-04) and the two plugins (`RunComponent`, `MailCheck`).

## 7. Shortlist: free, truly open source, extendable, and per-workflow jars / scripts

Each workflow brings its own jars and scripts, so the tool must also store, version and run those artifacts per project / workflow.

| Tool | Licence model | Loops, if, switch, dynamic parallel | How we add new functionality | Where each workflow's jars / scripts live and how they run | Verdict |
|---|---|---|---|---|---|
| **Kestra OSS** | Apache-2.0, **open-core** (RBAC, SSO, audit, secrets manager, worker groups are Enterprise) | All native (`Loop`, `LoopUntil`, `If`, `Switch`, parallel `Loop`) | Java plugins (jar dropped into the plugins folder) for new task types; subflows for reusable steps | **Namespace files** per project / namespace (in the UI or synced from Git), or a path on the host; run by shell `Commands` with the Process runner using the host's Java 8 / Python 2.7 | **Best fit** if the open-core limits are acceptable |
| **Operaton** | Apache-2.0, **fully free** (no paid edition; users, groups, authorizations, multi-tenancy included) | All native in BPMN (gateways, loops, multi-instance) | Java delegates, external-task workers (any language), Modeler element templates for our step types | No artifact store: jars and scripts sit on the server (or in a Git-synced folder) and are run by our `RunCommand` delegate | **Best fully-free option**; more to build (command step, mail, output views) |
| **Apache DolphinScheduler** | Apache-2.0, fully free (tenants, projects, users, authorization included) | if / switch native; **no loop or for-each task** in the 3.4 task list, and DAGs are acyclic | Task plugin SPI (Java) | **Resource Center** per tenant / project: upload jars and scripts; native Java, Shell and Python tasks; `${setValue(key=value)}` reads outputs from stdout | Strongest for jars and scripts, but **fails the loop requirement (R-10)** |
| **StackStorm** | Apache-2.0, fully free (RBAC, LDAP, designer included) | Conditional transitions, `with-items` with concurrency, loops by transitions | Packs (Git repos) with Python or shell actions | **Packs** per project hold the actions (scripts, jar calls) in Git | Good fit, but heavy operations (MongoDB, RabbitMQ, ~10 services), Python stack and slow releases |
| **Conductor OSS** | Apache-2.0, open-core (RBAC etc. in paid Orkes) | Native (`SWITCH`, `DO_WHILE`, `DYNAMIC_FORK`) | Workers in any language | On our worker hosts; the worker runs the command | More build work than Kestra and no free RBAC |

**Bottom line:**
- **Choose Kestra** if one instance per project (or buying Enterprise later) is acceptable for access control.
- **Choose Operaton** if everything, including roles and permissions, must be free.
- Both are Apache-2.0, so we may fork and extend them. With Kestra, keep our additions as plugins rather than a fork.

## Sources (checked 2026-10-26)
- Kestra licence (Apache-2.0): https://github.com/kestra-io/kestra
- Kestra OSS vs Enterprise (RBAC, SSO, audit, secrets manager, apps are Enterprise): https://kestra.io/docs/oss-vs-paid
- Kestra 2.0 breaking changes (`ForEach`/`ForEachItem` → `Loop`, `pluginDefaults` removed): https://kestra.io/docs/migration-guide/v2.0.0/foreach-loop , https://kestra.io/docs/migration-guide/v2.0.0/plugin-defaults-forced-removed , https://kestra.io/docs/changelog/v2.0.0
- Camunda 8.6 production licence: https://docs.camunda.io/docs/reference/announcements-release-notes/860/860-announcements , https://camunda.com/blog/2024/10/camunda-licensing-what-you-need-to-know/
- Operaton (Apache-2.0, no commercial edition): https://github.com/operaton/operaton
- All other sources: `04_open_source_candidates.md`
