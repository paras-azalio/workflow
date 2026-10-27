# BST Release 20 – What the Reference Manual Adds

Source: `extracted/Business_Service_Tool_Reference_Manual.txt` (Nokia "Business Service Tool Release 20 Reference Manual", Document Version 1.0, (c) 2019), read in full. It is short: 53 printed pages, aimed at BST administrators with UNIX and Oracle skills (p.1).

Background: `01_bst_users_guide_capabilities.md` (called "01" below). This file does not repeat 01. It fills the gaps 01 marked "not in this manual" and lists what else the Reference Manual (RM) adds.

**Page references:** `p.N` is the printed page number in the RM page header ("Business Service Tool N"). The RM's table of contents matches these headers.

**Extraction caveats:**
- The GRC table (p.3–7) and several option tables (p.28–43) are column-garbled in the text. The readings below were rebuilt by matching each description with its parameter. Where a reading is uncertain, it says so.
- Figures 1 and 2 (SAS3 task status mappings, p.8–9) and Figure 3 (p.50) are images and are missing from the text. Check the PDF if you need them.
- *Inferred* marks anything that is my reading, not a statement in the manual.

---

## 1. What the Reference Manual covers

| Ch. | Topic | Pages | In short |
|---|---|---|---|
| 1 | About this document | 1–2 | Audience, abbreviations, terminology (adds **Archive Database**), related documents |
| 2 | GRC parameters | 3–10 | The `bst` GRC section in detail; SAS3 status mapping; `old_logic_support` and its memory options; GRC import/export |
| 3 | Request parameters | 11 | Only `REQUEST_TRACE` |
| 4 | Directory structure | 12–13 | Server and workstation directories |
| 5 | System log files | 14–17 | Log file names, rotation, audit trail format, log levels |
| 6 | File cleaning | 18 | Trace data clean-up with crontab; workstation `_tmp` folders |
| 7 | Memory configuration | 19–25 | Service Module Engine heap, workstation (JNLP) heap and timeouts, UI server (Tomcat) heap |
| 8 | Provisioning logic transfer | 26–27 | Move a logic jar between environments on UNIX |
| 9 | Managing logics from the command line | 28–34 | `bst_admin.ksh`: list, get, insert, delete, activate, rules file, whole-set download/upload, activate set, trace export/import |
| 10 | Provisioning logic report | 35 | `create_logic_report.ksh` |
| 11 | Comparing logics | 36–37 | `compare_logics.ksh` |
| 12 | Number range import/export | 38–40 | `manage_number_ranges.ksh` and the text file format |
| 13 | Lookup tables from the command line | 41–43 | `store_lookup_files_to_db.ksh` |
| 14 | Upgrading old logics | 44–48 | `update_logic.ksh` options; Switch comma check |
| 15 | Regex syntax in EdifactParser / RegExp | 49 | Java regex, stricter matching since BST 3.1 |
| 16 | Troubleshooting | 50–53 | "All output parameters to pool", "Overwrite", logic load failures |

**What it does not cover:** step behaviour, request/response APIs, the task wire format, NEI routing, the database schema, crash recovery, threading and retries. Those belong to the User's Guide (01) or to InstantLink documents.

---

## 2. Gap fill for the gaps listed in 01

| Gap in 01 | What the Reference Manual says | Verdict |
|---|---|---|
| **Northbound request API** (format of inbound requests) | Chapter 3 covers only one request parameter, `REQUEST_TRACE`. For request parameters in general it says "see InstantLink customer documentation" (p.11). The examples show request parameters `NE_TYPE` and `IL_REQ_GROUP` used in logic rules (p.15, p.30). It also names `received_time`, which picks the logic version under `old_logic_support` (p.10). | **Not in the RM.** Only `REQUEST_TRACE`, `IL_REQ_GROUP` and `received_time` are new names. |
| **Task / NEI wire format** | Not described. The RM only names InstantLink's components: Request Processing Engine, Service Module Engine, Task Engine and Network Model Manager (p.19). BST runs in the **Service Module Engine** process (p.19). *Inferred:* tasks go to the NEs through the Task Engine. | **Not in the RM.** |
| **Full GRC list** | Table 1 (p.3–7) lists the same 11 `bst` parameters that 01 lists. No extra BST GRC parameters exist. The RM adds: <br>– `log_dir` in the **common** section (default `$(il_dir)/logdir/logs`) (p.7, p.14); <br>– `diag_level` in the **user_interface** section, for the BST pages in the InstantLink UI (p.16); <br>– JVM options `bst_old_logic_max_count_in_memory` (default 5) and `bst_old_logic_init_count_in_memory` (default 1) in `<installdir>/sas/.customer.environment-variables` (p.10). <br>Full detail is in §2.1 below. | **Filled** for BST. The InstantLink GRC sections are not listed. |
| **Log files** | Fully described: names, location, rotation, audit format and levels (p.14–17). See §6.3. | **Filled.** |
| **Command-line logic transfer** | Fully described: `bst_admin.ksh` (p.26–34) and the other scripts (p.35–48). See §4. | **Filled.** |
| **Engine-generated parameters** `task_type`, `ne_type`, `ne_id`, `CHILD_REQ_ID`, `PARENT_REQ_ID`, `IL_TASK_GROUP`, `ZZZPARALLEL_BRANCH_IDZZZ`, `ZZZRESERVED_ORDER_IDENTIFIER_PARAMETERZZZ`, `GENERATED` | None of these appear. The only engine-related parameters named are `received_time` (p.10) and `REQUEST_TRACE` (p.11). | **Not in the RM either.** They must come from InstantLink documents or from real traces and logic jars. |
| **Retries / per-task timeouts** (01 §4.3) | Not mentioned. The only timeouts in the RM are workstation UI connection timeouts (p.23–24). | **Not in the RM.** |
| **SAS3 task status mapping** (`use_sas3_task_status_values`) | When `YES`, statuses are mapped both when a task response arrives and when the request response is sent (p.8). The mapping tables are in Figures 1–2, which are images. | **Partly filled.** Read the PDF p.8–9 for the values. |
| **Crash recovery / request context in DB** (01 §1.3) | Not described. | **Not in the RM.** |

### 2.1 The `bst` GRC section in full (p.3–7)

| Technical name | Values (default **bold**) | Notes beyond 01 |
|---|---|---|
| `auto_save_interval` | 0 = off, 1–n minutes (**15**) | Raise it to 30 or more for large logics (p.3) |
| `bypass_expiration_mechanism` | YES / **NO** | With YES, "the request never expires in Business Service Tool"; the logic must detect expiry itself. "Should be avoided" (p.3) |
| `nesting_limit` | 2–99 (**15**) | "Maximum number of methods that can be invoked from each other (to prevent endless recursion)" (p.4) |
| `request_trace` | **NONE**, EXECUTION_PATH, EVERYTHING, MIXED | Do not use in production; it "slows down request processing considerably". MIXED is for large flows whose EVERYTHING trace would exceed 10 MB (p.4) |
| `request_expiration_trigger` | **0**–3 | Copies `@NEW@` / `@REQ@` parameters to the response only when the request expires (p.5) |
| `old_logic_support` | YES / **NO** | See §6.1 (p.5, p.9–10) |
| `use_sas3_task_status_values` | YES / **NO** | Backward compatibility with MDS/SAS 3 / BST 1.0 logics (p.5, p.8) |
| `diag_level` | levels in §6.3 (**WARNING**) | Engine log `BST_SME<n>_<date>_<number>.log` (p.6) |
| `diag_level_ui` | (**WARNING**) | `BST_UI_<date>_<seqno>.log` and Java Console, for Logic Editor, Lookup Table Editor and Number Range Editor (p.6) |
| `all_output_parameters_support` | YES / **NO** | Backward compatibility. NO is recommended for performance (p.6) |
| `overwrite_support` | YES / **NO** | Backward compatibility. With NO, new parameters always overwrite same-named pool parameters (p.7) |

GRC values are stored in the InstantLink database (p.3). They can be moved between test and production with the **InstantLink import/export tool** (p.10).

---

## 3. Logic file format and structure

What the RM says:

| Item | Statement | Page |
|---|---|---|
| Container | "The provisioning logic is stored in a JAR file", named `<name>_<date_time>.jar`, e.g. `TestLogic_20141014_130040.jar` | p.26 |
| Storage | The jars are kept in the database table **`BST_LOGIC_VERSIONS`** | p.26 |
| Active copies on disk | `<installdir>/sas/bst/logics/active` holds the active provisioning and library logics **and the logic rules defined for them**, in subdirectories (used when `old_logic_support=NO`). `<installdir>/sas/bst/logics/old_logics_dir` holds previously active logics (used when `old_logic_support=YES`). `logics/old` is obsolete. | p.12 |
| Other paths | A `<installdir>/sas/bst/logics/versions/` directory appears in an example (autosave jar) | p.47–48 |
| Inside the jar | Unit definitions are XML files under `xml/`. The Switch check output shows `/tmp/test_logic_20141014_145544.jar:xml/Switch_1.xml:DEFAULT__TEST=1,201` | p.48 |
| Unit IDs | Every unit has a **Tracer ID**. `update_logic.ksh -add_ids` adds them to pre-3.1 logics. `compare_logics.ksh` matches units **by element ID**, so a renamed unit is still recognised as the same unit. | p.37, p.44–45 |
| Element names | Only `a-z`, `0-9` and `_`. Names that differ only in case (`IfEqual` / `ifequal`) cause trouble in the Standalone editor; the script renames one to `ifequal_renamed_n`. | p.45 |
| Description | A logic has a description, shown as the tooltip on the Logic Versions page (`update_logic.ksh -d`) | p.45, p.47 |
| Old (BST 2.1.2) format | A **directory** such as `<installdir>/sas/bst/xml/config-version-123` or `/nokia/ilink/backup/config-version-499`. The upgrade turns it into a jar with "Tracer IDs, a graphical logic flow presentation and upgraded step definitions". | p.46–47 |
| Number ranges | Stored **inside the logic jar**. Importing a number range file creates "a new logic … with current time stamp" (p.38). | p.38 |
| Rules file | `rules.properties`, one for provisioning logics and one for library logics. Can be downloaded, edited and uploaded (p.31). The `--list-active-set` output shows the line form `<logic_version>;<rule expression>;`, e.g. `Provisioning_logic_example_20140929_155922;equals "$NE_TYPE" "BST";`. The catch-all rule is `default_logic_20141014_130407;;`. *Inferred:* `rules.properties` uses the same form. | p.30 |
| Rule expression syntax | `equals "$NE_TYPE" "ORDER" and substr "$IL_REQ_GROUP" "OM_TEST"`: prefix operators `equals` / `substr` with quoted operands, joined by `and` (`or` and parentheses per 01) | p.15, p.30 |
| Not described | The XML schema, `DESCRIPTION.properties`, `parameterlist.xml`, the graph/layout files and the jar manifest | — |

**Cross-check against the team's exported jars (observation, not from the RM):** `extracted/jars/LL_CR/` and `LL_CR_CUSTOM_LOGIC/` each contain `DESCRIPTION.properties`, `data/*.graph` (one per method, e.g. `main_method.graph`) and `xml/*.xml` (one per unit, named after the unit, e.g. `MapParameters_*.xml`, `IF_*.xml`). This agrees with the RM's `xml/Switch_1.xml` path. The schema of these files has to be learned from the jars themselves.

---

## 4. Command-line tools, deployment, transfer, import/export, configuration

### 4.1 `bst_admin.ksh` (in `<installdir>/sas/bin`, p.28)
It replaces the older `logic_management.ksh`, `activate_logic.ksh` and `export_trace.ksh`. It "covers most of the functionality available in the user interface". Each option has a long and a short form (p.28).

| Long option | Short | Argument(s) | Effect | Page |
|---|---|---|---|---|
| `--list-logic-versions` | `-lv` | – | List provisioning logic versions in the DB | p.28 |
| `--list-library-versions` | `-llv` | – | List library logic versions | p.28 |
| `--get-logic` | `-g` | version name, absolute dir | Download a logic jar from the DB | p.29 |
| `--get-library` | `-gl` | version name, dir | Download a library jar | p.29 |
| `--insert-logic` | `-i` | full path to jar | Insert a logic into the DB | p.29 |
| `--insert-library` | `-il` | full path to jar | Insert a library logic | p.29 |
| `--delete-logic-version` | `-dlv` | logic name | Delete a logic version | p.30 |
| `--delete-library-version` | `-dliv` | library name | Delete a library version | p.30 |
| `--list-active-set` | `-las` | – | List the active rules, logics and libraries | p.30 |
| `--list-current-set` | `-lc` | – | List the current **working set** | p.30 |
| `--activate-logic` | `-al` | full version name | Activate a logic. "Only possible if some version of the same logic is already active or there [is] only one active logic." | p.31 |
| `--activate-library` | `-all` | full version name | Activate a library; any other active version of it is deactivated | p.31 |
| `--deactivate-logic` / `--deactivate-library` | `-deal` / `-deall` | full version name | Deactivate | p.31 |
| `--get-rules-properties` / `--get-rules-properties-library` | `-grp` / `-grpl` | dir | Download `rules.properties` (logics / libraries). Upload is described in the text but its option name is not in the table. | p.31–32 |
| `--download-rules` | `-dr` | target dir | Download the active logics, libraries and rules as `LOGIC_RULES_<yyyyMMdd_hhmmss>.zip` | p.32 |
| `--upload-rules` | `-ur` | zip path | Load the zip into the **current working set**, where it can be changed before activation | p.32 |
| `--activate-current-set` | `-acs` | – | Replace the active set with the working set | p.32 |
| `--export-trace` | `-et` | request ID | Zip the trace, the logic and any executed library logics with their traces, into `<installdir>/sas/backup` | p.33 |
| `--import-trace` | `-it` | zip name | Import into `sas/bst/trace` with an `imported_` prefix. Logics are imported only if they do not already exist. | p.33–34 |

**Two-stage activation (p.30, p.32):**
- The **current working set** holds the current versions of rules, logics and libraries.
- The **active set** is what the BST Engine reads.
- `--activate-current-set` copies the working set into the active set. After that, `ctl_control reconfig` (or "Apply configuration changes" on the System Status page) makes the engine read the active set. Then "new requests start using the new configuration".

### 4.2 Logic transfer between environments (p.26–27)
1. `bst_admin.ksh --get-logic <jar> /tmp`
2. Copy the jar with FTP (binary mode) if the target is on another host.
3. `bst_admin.ksh --insert-logic /tmp/<jar>` on the target.
4. Activate the logic.

"The source and target release of Business Service Tool must be the same" (p.26). The logic can also be moved through the Logic Editor's upload/download or the InstantLink UI (p.26).

### 4.3 Other scripts

| Script | Location | Purpose | Page |
|---|---|---|---|
| `create_logic_report.ksh` | `sas/bst/bin` | Interactive: asks for the jar name and writes a zip report to `sas/reports` | p.35 |
| `compare_logics.ksh -source <jar> -target <jar> [-report SIMPLE\|HTML] [-report_base <dir>]` | `sas/bst/bin` | Works on downloaded jars. Output is `compare_report_<timestamp>.log` or `.html`, by default in `sas/bst`. Reports unique elements, renamed elements (same ID), shared elements, unique parameters and changed parameter values. | p.36–37 |
| `manage_number_ranges.ksh -l <jar>` / `-e` / `-i <jar> <file> <step>` / `-h` | `sas/bst/bin` | List NumberRange steps in a jar, then export or import one. Import writes a **new timestamped jar** and keeps the step's default value. | p.38–39 |
| `store_lookup_files_to_db.ksh -list` / `-delete t1,t2` / `-delete -all` / `-download <zip> <dir>` / `-upload\|-update\|-replace <file> <dir>` | `sas/bst/bin` | Lookup table maintenance. Runs as the InstantLink UNIX user. Each run logs to `lookup_table_converter_<timestamp>_<seq>.log`. A refresh is needed afterwards. | p.41–43 |
| `update_logic.ksh <dir\|jar> [options]` | `sas/bin` (p.48) | Upgrades logics from BST 2.1.2–3.2. See §4.5. | p.44–48 |
| `delete_file.ksh <days> -all <dir>` | `sas/bin` | Used from crontab to delete old trace files | p.18 |
| `ctl_control stop\|start\|reconfig` | – | Stop, start or reconfigure InstantLink | p.25, p.32 |

The manual is inconsistent about some names: `bst-admin.ksh` vs `bst_admin.ksh` (p.26), `update_logic.ksh` vs `update_logics.ksh` (p.44, p.48), `-edifact_update` vs `-update_edifact` (p.45–46), and `sas/bst/` vs `sas/bst/bin/` for the lookup script (p.42–43).

### 4.4 Data file formats
- **Number range file** (p.39–40): pipe-delimited, one range per line:
  `<min>|<max>|<NE_TYPE>|<param1>|<value1>[|<param2>|<value2>…]`
  e.g. `358501000000|358501009999|HLR|NE_ID|hlr1|BASIC1|T11`.
  Checks before import: start and end both present, start ≤ end, no overlapping ranges within one NE type. Comparison is numeric, or lexicographic for alphanumeric steps.
- **Lookup tables:** downloaded as a zip of lookup table files (p.42). The file format itself is **not described**. Upload modes (p.43):

  | Mode | Behaviour |
  |---|---|
  | `-upload` | Creates the table. Fails with Oracle `ORA-00001` on `UNI_BST_LOOKUP_TABLE_NAME` if the table exists. |
  | `-update` | Upsert by key. Creates the table if missing. |
  | `-replace` | Replaces the whole content. Creates the table if missing. |
  | no option | Every file in the directory is uploaded one by one. |

### 4.5 Upgrading old logics (`update_logic.ksh`, p.44–48)
- Needed for logics from BST 2.1.2, 3.0, 3.0.1, 3.1, 3.1.1 and 3.2. Logics from **4.0 or later need no upgrade** (p.44).
- Options:
  - `-report`: dry run; writes `logic_migration.log`.
  - `-add_ids`: adds Tracer IDs.
  - `-combine_log_steps`: turns the removed LogValue / Print / Debug steps into Log steps.
  - `-edifact_update`.
  - `-check_element_names`.
  - `-mandatory_updates`: all of the four options above.
  - `-n <name>`, `-d "<description>"`.
  - `-add_brackets`: `$X` → `[$X]` in MapParameters.
  - `-remove_needless_params`: drops `P=$P`, `P=($P)` and `P=[$P]` inputs.
  - `-percent_to_dollar`: the old `%PARAM` notation becomes `$PARAM`; the `%` wildcards in REMOVE_BEFORE/AFTER_LIST are left alone.
  - `-l <log_dir>`, `-all`, `-help`.
  - `-check_parameters_with_comma`: Switch steps accept commas in values since 3.2 PB4. Output format: `logic filename":"logic name":"parameter name"="parameter value`.
- The upgraded jar is written to the current directory. The original is left unchanged (p.47).
- It can also be used just to rename a logic (p.47–48).

### 4.6 Configuration files and settings named in the RM

| File / setting | Purpose | Page |
|---|---|---|
| `<installdir>/sas/.customer.environment-variables` (`JAVA_OPTS`) | Old-logic memory options | p.10 |
| `<installdir>/sas/bin/start_ServiceModuleEngine.ksh`, `start_TaskEngine.ksh`, `start_NetworkModel.ksh` (`MAX_HEAP`) | Heap sizes of the engines | p.19–21 |
| `<installdir>/sas/bin/tomcat_control` (`MAX_HEAP`, default 2 GB) | UI server heap | p.25 |
| `<installdir>/sas/ui/webapps/bst/*.jnlp` | Workstation heap (default 512 MB) and timeouts for each BST UI: `bst_online`, `bst_coverageanalysis`, `bst_lookup`, `bst_numberrange`, `bst_standalone`, `bst_tracer` | p.21–24 |
| `<installdir>/sas/ui/webapps/sas5/conf/plugins` | Logic Editor properties file | p.12 |
| `<installdir>/sas/bst/extensions` | Configuration for add-on step packs, e.g. **BST Interface for Subscriber Profile Repository** and BST Interface for Catalog | p.12 |
| `<installdir>/sas/lib`, `sas/lib/steps` | BST class files; "step files" | p.12 |
| `<installdir>/sas/sql` | SQL scripts that create the BST database tables | p.12 |
| `<installdir>/sas/data` | Example requests | p.12 |
| `C:\Documents and Settings\<user>\BST\BST.properties` | Standalone editor configuration, local logics, autosave and emergency saves | p.13 |

*Inferred:* the step implementations live in `sas/lib/steps`, and optional step packs are configured in `extensions`. In other words, step types are pluggable inside BST, even though no user-code step is documented (01 §3.3).

---

## 5. Expression, condition and reference language details beyond 01

| Item | RM statement | Page |
|---|---|---|
| **Regex engine** | EdifactParser and RegExp "follow the Java API specification". Since BST 3.1, `^XSU` no longer matches "a string starting with XSU"; the correct form is `^XSU.*`. *Inferred:* the expression must match the **whole** value (Java `matches()`), not just find a match. | p.49 |
| **Logic rule serialisation** | Prefix form with quoted operands: `equals "$NE_TYPE" "BST" and substr "$IL_REQ_GROUP" "LIB"`. The empty rule is the catch-all. | p.15, p.30 |
| **Obsolete notation** | `%PARAM` meant `$PARAM` before BST 3.1 and is no longer supported. `%` is still the "any sequence" wildcard in REMOVE_BEFORE_LIST / REMOVE_AFTER_LIST. | p.46 |
| **Bracket reference** | `[$PARAM]` was added for BST 2.1.2 compatibility in MapParameters. Mappings such as `P=$P`, `P=($P)` and `P=[$P]` are "needless". | p.45–46 |
| **Pool overwrite rule** | "All control parameters (parameters generated by the step itself) are entered into the parameter pool even if a parameter with the same name is already in the pool. The parameter with the same name is overwritten." Control parameters are returned to the pool automatically, so "All output parameters to pool" is unnecessary for steps. Additional mappings must be listed in "Add to parent pool". | p.7, p.50–51 |
| **Methods vs main_method** | The "All output parameters to pool" checkbox stays available on methods. `main_method` "has no output parameters at all". | p.51 |
| **Switch values** | Commas are allowed in Switch values since 3.2 PB4 | p.48 |
| **Trace control in-flow** | In MIXED mode the logic changes the `REQUEST_TRACE` pool parameter during execution. When it is `EVERYTHING`, pool contents are traced; otherwise only the path is traced. | p.4, p.11 |

---

## 6. Limits, performance, scaling, threading, persistence, recovery, logging

### 6.1 Limits and sizing

| Figure | Value | Page |
|---|---|---|
| A "large" logic | jar larger than 1 MB | p.19, p.21, p.23 |
| Service Module Engine heap example | `MAX_HEAP="1024m"` on a host with at least 4 GB, for large logics; `-Xms256m` | p.19–21 |
| UI server heap | default 2 GB; example 4 GB | p.25 |
| Workstation UI heap | default 512 MB; example `max-heap-size="1g"`; JNLP requires `j2se 1.8+` | p.21–22 |
| UI connection timeout | default 300 s; example 600 s (`-Djnlp.read_timeout`, `-Djnlp.connect_timeout`) | p.23–24 |
| Trace size | EVERYTHING on a large workflow can exceed 10 MB per trace, hence MIXED | p.4 |
| Old logic sets in memory | `bst_old_logic_max_count_in_memory` = 5; `bst_old_logic_init_count_in_memory` = 1, loaded at startup and refresh (this one applies even if it is larger than the max) | p.10 |
| Method nesting | 2–99, default 15 | p.4 |
| Performance warnings | `request_trace` ≠ NONE, `overwrite_support=YES` and `all_output_parameters_support=YES` each slow request processing "considerably". Detailed log levels cost disk space and performance. | p.4, p.8, p.16, p.50 |

### 6.2 Process and threading model
- "Business Service Tool runs in the same process as Service Module Engine" (p.19). It shares that JVM with other service modules. *Inferred:* one engine process per InstantLink node; the RM does not describe clustering.
- InstantLink components with their own heaps: Request Processing Engine, Service Module Engine, Task Engine, Network Model Manager (p.19).
- The sample start script sets `-Dtask_processor_count=7`, `-Dresp_forwarder_count=7`, `-Dcom.comptel.mds.sas5.requestengine.use_priority_upgrader=false`, `-Duse_statement_cache=false`, `-Duse_connection_verifier=true` and `-Dcollect_statistics=true`. It also exposes **JMX over SSL** on a per-module port (p.20–21). **The RM does not explain these options.** *Inferred:* fixed-size worker pools for task processing and response forwarding.
- The Java package `com.comptel.mds.sas5.servicemodules.bst` (p.51) shows BST's lineage: Comptel MDS / SAS5 service module.
- Nothing on per-request threading, parallel-branch scheduling, locking or throughput figures.

### 6.3 Persistence and recovery

| Data | Where | Page |
|---|---|---|
| Logic versions (jars) | DB table `BST_LOGIC_VERSIONS` | p.26 |
| Active set (logics and rules) | `sas/bst/logics/active` on the file system; read by the engine on reconfig | p.12, p.32 |
| Previously active logics | `sas/bst/logics/old_logics_dir` | p.12 |
| Lookup tables | DB (unique constraint `UNI_BST_LOOKUP_TABLE_NAME`); Oracle implied | p.42–43 |
| GRC | InstantLink DB | p.3 |
| Traces | Files `sas/bst/trace/trace_<reqid>` plus `trace_<reqid>_<index>` per executed library logic (index = execution order) | p.33 |
| Exports | `sas/backup` (trace zips), `sas/reports` (reports) | p.33, p.35 |
| Long-term request history | **Archive Database**, an InstantLink add-on "that allows storing request information over a longer period of time" | p.2 |

- **Crash recovery:** not described.
- **Old logic support:** each request runs with the logic set that was active when it was received, chosen by its `received_time`. This applies to unfinished, timed and long-running requests (p.9–10).

### 6.4 Logging (p.14–17)

| Log | File | UI equivalent |
|---|---|---|
| BST Engine | `BST_SME<n>_<date>_<number>.log` (e.g. `BST_SME1_20131015_7.log`) | Maintenance > System Status > Business Service Tool Log |
| BST pages in InstantLink UI | `UI_<date>_<seqno>.log` | Maintenance > System Status > User Interface Log |
| Configuration audit | `BST_MA_<date>_<seqno>.log` | BST > Audit Trail |
| Logic Editor and editors | `BST_UI_<date>_<seqno>.log` plus the Java Console | – |
| Lookup CLI | `lookup_table_converter_<timestamp>_<seq>.log` (p.41) | – |
| Upgrade | `logic_migration.log` (p.44) | – |

*Inferred:* the `<n>` in `BST_SME<n>` is the Service Module Engine instance number.

- **Location:** GRC `log_dir` in the common section; default `<installdir>/sas/logdir/logs` (p.14).
- **Rotation:** a new file at midnight, on every InstantLink start, on every configuration refresh, and when the maximum size is reached. The sequence number restarts at 0 each day (p.14).
- **Clean-up:** InstantLink cleans BST logs automatically. **Trace data and backup logics are not cleaned automatically**; use a crontab job such as `delete_file.ksh 7 -all …/sas/bst/trace` (p.14, p.18).
- **Disk full:** "if the disk in which the log files reside is full, Business Service Tool does not start" (p.16).
- **Levels:** each level includes all lower ones (p.16).

  | Level | What it records |
  |---|---|
  | ALL | Everything |
  | INFO | Top-level activities, e.g. refreshes and Log-step messages |
  | WARNING | Minor errors, e.g. an error while executing a logic for a request, or "main method ends without creating a request response" |
  | SEVERE | Fatal errors needing an operator: DB down, DB operation failed, invalid configuration, invalid logic |

  Use WARNING in production and ALL only temporarily. The Log step writes only at INFO or ALL (p.17).
- **Audit trail format** (p.15): `timestamp [user account] [action <additional data>]`, timestamp `YYYY-MM-DD HH:MI:SS`. Example: `2018-04-27 08:44:01 User=JohnD: Add logic rule: new rule=rule5, value=equals "$NE_TYPE" "ORDER" and substr "$IL_REQ_GROUP" "OM_TEST"`.
  - Covers activating and deleting logics, and adding, editing, deleting, duplicating and reordering logic rules (with old and new values).
  - "Configuration audit log cannot be turned off" and has no log levels (p.16).

---

## 7. Additional requirements for a replacement (continuing from 01's #71)

Only items not already covered by 01's #1–71.

72. **MUST** Keep a staged **working set** separate from the **active set**. Activate rules, workflows and libraries together as one atomic set, then reload live: new requests use the new set and in-flight requests are unaffected. *Why:* BST's deployment model is set-based (`--upload-rules`, `--activate-current-set`, `ctl_control reconfig`) (p.30, p.32).
73. **MUST** Bundle a whole environment's active configuration (workflows, libraries, routing rules) as **one portable archive**. Allow it to be loaded into staging, edited, then activated. *Why:* this is how BST moves configuration between environments (`LOGIC_RULES_<ts>.zip`) (p.32).
74. **SHOULD** Store routing rules as a plain, diffable text file (BST: `rules.properties`, `<version>;<expression>;`) that can be downloaded, edited and re-uploaded. *Why:* this lets rules be reviewed and promoted outside the UI (p.30–32).
75. **SHOULD** Provide a scriptable admin CLI or API that covers the whole lifecycle: list, get, insert, delete, activate and deactivate a workflow or library; get and put rules; export and import the set; activate the set; export and import traces. *Why:* BST's `bst_admin.ksh` "covers most of the functionality available in the user interface"; needed for CI/CD (p.28–34).
76. **MUST** Provide a migration path from the existing BST logic jars and `rules.properties`: parse `xml/<unit>.xml`, the flow files and the rules, at least to inventory and convert them. *Why:* logic is stored as jars in `BST_LOGIC_VERSIONS` with per-unit XML (p.26, p.48); the team already has exported jars. Requirement inferred.
77. **MUST** Match BST's regex semantics when converting workflows: Java regex syntax with **whole-value** matching (`^XSU` must be written `^XSU.*`), or rewrite patterns during conversion. *Why:* a silent change in match semantics would change routing and extraction (p.49). Whole-value reading inferred.
78. **MUST** Replicate BST's pool write semantics: a step's own result parameters always overwrite same-named pool parameters and are returned automatically; other mappings return only if they are listed. *Why:* converted workflows depend on this (p.7, p.50–51).
79. **SHOULD** Give every workflow unit a **stable ID** that is separate from its display name. Traces, coverage and version diffs should match units by ID, so renames are detected. *Why:* BST Tracer IDs and `compare_logics.ksh` work this way (p.37, p.44).
80. **SHOULD** Bundle a trace export with the **exact workflow and library versions** it ran. On import, never overwrite existing versions; prefix imported traces. *Why:* BST support hand-off works this way (p.33–34).
81. **SHOULD** Let a running workflow change its own trace detail (BST MIXED mode, toggled by setting a pool parameter). *Why:* full traces of large flows exceed 10 MB (p.4, p.11).
82. **SHOULD** Apply retention to traces and backups (BST needs a crontab job), rotate logs (daily, on start, on reload, on size), and keep running when the log disk is full. *Why:* in BST, traces are not cleaned automatically and a full log disk stops BST starting (p.14, p.16, p.18).
83. **SHOULD** Keep the configuration audit log always on (it cannot be disabled), with timestamp, user, action and old/new values for activation, deletion and every routing-rule change, including reordering. *Why:* this is BST's audit contract (p.15–16).
84. **SHOULD** Detect and log a run that ends **without sending a response** to the caller. *Why:* BST logs "main method ends without creating a request response" as a WARNING (p.16).
85. **SHOULD** Bulk-load lookup tables with distinct **create / upsert-by-key / replace / delete-list / delete-all / download** operations, a log file per run, and a refresh after loading. *Why:* this is BST's `store_lookup_files_to_db.ksh` behaviour (p.41–43).
86. **COULD** Bulk-import and export number ranges as pipe-delimited text (`min|max|NE_TYPE|name|value…`) with sanity checks: both ends present, start ≤ end, no overlap within an NE type, numeric or lexicographic comparison. *Why:* BST's `manage_number_ranges.ksh` (p.38–40).
87. **SHOULD** Promote runtime settings (BST's GRC) between environments with an import/export tool. *Why:* p.10.
88. **SHOULD** Reject or upgrade workflow definitions from another release on import, with a dry-run report mode. *Why:* BST requires the same release on both ends of a transfer and offers `update_logic.ksh -report` (p.26, p.41, p.44).
89. **SHOULD** Cap how many previous workflow versions are kept in memory, and how many are loaded at startup, when old-version execution is on. *Why:* BST defaults are 5 and 1 (p.10).
90. **SHOULD** Size and test the engine and designer for workflows at least as large as BST's "large" logics (jar over 1 MB), with configurable worker pools and heap. *Why:* BST's sizing guidance and worker counts (`task_processor_count=7`, `resp_forwarder_count=7`) (p.19–21); worker meaning inferred.
91. **COULD** Expose engine metrics and management over a secured management interface. *Why:* BST's engine runs with JMX over SSL and `collect_statistics=true` (p.20–21).
92. **COULD** Keep request history long-term outside the operational store. *Why:* InstantLink's Archive Database add-on serves this purpose (p.2).
93. **SHOULD** Support pluggable step packages that are installed and configured separately from the core. *Why:* BST keeps step files in `sas/lib/steps` and add-on step packs, such as the SPR and Catalog interfaces, in `sas/bst/extensions` (p.12). This strengthens 01's #71 from COULD: the team's plan to call Java jars and Python scripts directly needs it. Inferred.

---

## 8. Still open after both manuals
- Inbound request format and API (InstantLink / Order Management Web Services documents).
- Task wire format and NEI routing from `NE_TYPE` / `NE_ID` to the Java Macro Server NEI (InstantLink documents).
- Engine-generated parameters (`CHILD_REQ_ID`, `PARENT_REQ_ID`, `IL_TASK_GROUP`, `ZZZ…ZZZ`, `GENERATED`, lower-case `task_type` / `ne_type` / `ne_id`). Best source: real BST Tracer exports and the exported jars.
- Retries, per-task timeouts, crash recovery and threading semantics.
- The XML / `.graph` / `DESCRIPTION.properties` schema of logic jars. Learn it from `extracted/jars/`.
- SAS3 status mapping values: PDF p.8–9 figures.
