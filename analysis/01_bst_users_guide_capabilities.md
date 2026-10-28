# BST (Business Service Tool) Release 20 – Capability Catalogue from the User's Guide

Source: `extracted/Business_Service_Tool_Users_Guide.txt` (Nokia "Business Service Tool Release 20 User's Guide", Document Version 1.1, (c) 2020), read in full.

**Page references:** `p.N` is the printed page number in the page header ("User's Guide N"). PDF page = N + 2. The table of contents in the PDF is stale: from about the Database step onwards, its page numbers are 1–4 higher than the real headers. For example, the TOC lists SendToNE at 367 but it is actually on p.363. All references below use the real header numbers, checked by counting form feeds.

**Scope warning.** This is the *User's Guide*: it covers the Logic Editor, the step types, the UI and operations. The guide points elsewhere for several things: the northbound request API, the task/NEI wire format, the full GRC list, log files and command-line logic transfer. Those are in the *BST Reference Manual*, the *InstantLink* docs, *Order Management Web Services* and the *Provisioning and Activation Functional Description / Installation Guide*. Where the brief asks about something this manual does not cover, this file says **"not in this manual"**. Nothing has been made up to fill those gaps.

---

## 1. What BST is and how it is built

### 1.1 Purpose
- BST is an **add-on module to Nokia InstantLink**. It processes requests that InstantLink receives from OSS/BSS systems and builds the responses to them. It runs **"provisioning logics"**: graphical flowcharts made of *steps* and *methods* (p.5, p.16).
- A provisioning logic is "a set of rules that define how provisioning is performed". It can manipulate parameter data, execute provisioning tasks, read lookup tables and build a response (p.12, p.16).
- In practice BST is InstantLink's **request-orchestration / workflow engine**. One OSS/BSS request becomes one execution of one logic. That execution sends one or many **tasks** to network elements through InstantLink. After all tasks are complete, BST sends a **response** back through InstantLink to the OSS/BSS (p.13).

### 1.2 Components named in the manual
| Component | Role | Pages |
|---|---|---|
| **InstantLink** (Request Processing Engine / "Request Engine") | Receives OSS/BSS requests. Translates them into NE-specific commands and runs them. Routes responses back. Manages requests and tasks. BST runs inside it. | p.12, p.346 |
| **BST Engine** | Runtime interpreter of the active logic, one parameter pool per request. Logs go to `BST_SME<n>_<date>_<seqno>.log` / `BST_<date>_<seqno>.log`. | p.155, p.222, p.464 |
| **BST pages in InstantLink UI** (web) | Manage logic versions, active logics, logic library, logic rules, audit trail, coverage analysis, compare logics, reports, tracer, lookup tables, standalone installer. | p.18–40, p.134 |
| **BST Logic Editor** | Graphical designer for logics, a Java desktop client launched from the web UI. Uninstall goes through "Java Cache Viewer", so it is Java Web Start style. | p.41–88, p.91, p.96 |
| **Standalone Logic Editor** | Offline copy of the editor. Saves `.jar` logic files locally. Cannot activate logics or produce reports. | p.89–96 |
| **Number Range Editor**, **Lookup Table Editor** | Data editors for the NumberRange and LookUp steps. | p.443–454 |
| **BST Tracer** | Visual per-request execution trace and debugger. | p.455–462 |
| **Coverage Analysis** | Shows which units were used across many traced requests. | p.37–40 |
| **Order Management** (optional add-on) | Orders, activities, Gantt chart, mainline/baseline, point of no return, jeopardy, in-flight cancel/amend/reschedule, order events and order parameters. It has its own UI and "Order Management Web Services". | p.98–128, p.166, p.171, p.191, p.269–299, p.433–438 |
| **Catalog** + **BST Interface for Catalog** (optional) | Product/service catalogue. Supplies decomposition, delta and rollback (undo) decomposition, and execution order. | p.103–124, p.379–418 |
| **Workflow Client (WFC)** | Human work-item/ticket system. The Manual step sends tasks to it as an NE (`NE_TYPE=WFC`). | p.236–246 |
| **Alarm Dispatcher** | Forwards BST alarms to external monitoring. | p.168 |
| **InstantLink Edifact API** (optional) | Converts EDIFACT messages into `LINE<n>` request parameters for the EdifactParser step. | p.206 |
| External databases | The Database step (Oracle/PostgreSQL) and PL/SQL step (Oracle) connect through `<installdir>/sas/bst/.bst_db_conf.xml`. | p.194, p.310 |

Install paths such as `<installdir>/sas/bst`, `<installdir>/sas/.customer.environment-variables` (JAVA_OPTS) and `<installdir>/sas/logdir/logs` (p.108, p.194, p.452) show that BST runs inside InstantLink's **SAS** Java runtime. **Order Hub, the Java Macro Server and the NEI/macro concept are not mentioned anywhere in this manual.** The only interface to a network element it shows is: a step builds a *task* (NE_ID / NE_TYPE / TASK_TYPE plus parameters), hands it to InstantLink, and InstantLink executes it "in the network elements" (p.363). The manual uses "NEI" only for WFC, under "NEI parameters" on p.245. *Inference:* in your setup InstantLink would route a task with `NE_TYPE=CLICR` to the CLICR NEI macro. That routing layer belongs to InstantLink, not to BST.

### 1.3 Design time vs runtime
- **Design time:**
  - Logics are built in the Logic Editor and saved as **versioned `.jar` files** named `<name>_<YYYYMMDD>_<HHMMSS>.jar`, kept in a "logic versions" database table. Every Save creates a new timestamped version (p.25, p.94, p.130, p.150).
  - A logic must be **activated**, and then an **InstantLink system refresh** is required before it takes effect (p.18, p.25, p.133).
  - No changes are possible while InstantLink is in **production mode** (p.6, p.17, p.18, p.41).
- **Logic selection at runtime:**
  - **Logic rules** pick which active logic handles a request. Each rule is a boolean expression over request parameters, evaluated top-down, and the first match wins (p.29–36).
  - Optional **old-logic support** runs a request with the version that was active when the request was received, based on its `received_time`. This applies to timed or long-running requests (GRC `old_logic_support`, p.22, p.466).
- **Runtime:**
  - The BST Engine gets a per-request **parameter pool** that starts with all request parameters (p.51, p.156).
  - It executes the `main_method` flow and sends tasks and requests through InstantLink. It finishes with a SendResp step or with request failure.
  - Request context is held in the database, so execution survives restarts. LogicLibrary error code 900 mentions "Request crash recovery fails" and "Request context cannot be loaded from DB" (p.229).

---

## 2. Core concepts and data model (as the manual defines them)

| Concept | Definition in the manual | Pages |
|---|---|---|
| **Request** | A work order that InstantLink receives from an OSS/BSS. It contains a required operation for one subscriber on one or more NEs. Request status is the current state of that request. | p.12 |
| **Task** | A work order for a network element. A request can contain "original tasks" (see SendOriginalTask). Logic steps can also generate new tasks. | p.13, p.331 |
| **Task response / task status** | The NE's reply to a task, and the current status of the task. | p.13 |
| **Response** | A message with the status of the logic execution, sent to the OSS/BSS when the logic finishes. | p.13, p.352 |
| **Provisioning logic** | A flowchart of steps and methods. Starts at `main_method`. Stored as a versioned jar. | p.16, p.47 |
| **Library logic (Logic Library)** | A reusable logic called with the LogicLibrary step. Can nest up to depth 40 by default; deeper calls fail the request. Activated and versioned separately. | p.27, p.224 |
| **Logic rule** | `Param Equals/Substring Value` conditions joined with AND/OR and parentheses, using simple `$PARAM` references only. It selects the logic. One empty catch-all rule must remain, otherwise the request is FAILED. | p.29–36 |
| **Method** | A group of steps and other methods, used for structure and reuse. Has **input parameters** (added to a child pool) and **output parameters** (returned to the parent pool). Can be marked **Parallel**. Maximum nesting depth is set by GRC `nesting_limit` (default 15, range 2–99). | p.6, p.16, p.50, p.157, p.465 |
| **Step** | The functional building block. Configured through input and output parameters. Each step type has reserved parameter names. | p.5, p.159–163 |
| **Unit** | Either a step or a method. | p.13 |
| **Instance vs Common parameters** | Each parameter row has a "Common (C)" flag. Common rows are shared by all instances of the same step or method; instance rows apply to one placement only. A step or method can be reused in several places (Paste vs Paste As New). | p.50, p.157–159, p.140 |
| **Link** | A directed arrow. Every node has at most one outgoing link. The exception is the If step, which has TRUE and FALSE links. Branch uses per-condition target units instead of links. | p.143, p.174 |
| **Note** | A free-text annotation on the flow. | p.145 |
| **Parameter pool** | Per-request `NAME=value` string pairs: request parameters, generated parameters and task response parameters. Units can create, read, edit and delete parameters. Each pool is isolated per request. It is created when the request arrives and deleted when the response is sent. | p.156 |
| **Child / parent pool** | Each method or step call works on a child pool. Only declared outputs, entries in the "Add to parent pool" list (space-separated, `%` wildcard) or "All output parameters to pool" flow back to the parent. Some in-flight parameters (`AMEND_CUSTOMER_REQUIRED_DATE`, `CUSTOMER_REQUIRED_DATE`, `FAKED_TASK_RESPONSE_BY`, `AMENDED_%`, `INFLIGHT_%`) always propagate. | p.55, p.157–159, p.165, p.225 |
| **Parameter evaluation order inside a unit** | Instance inputs, then common inputs, then step-generated values such as `RESULT_<n>`, then instance outputs, then common outputs. | p.153–154 |
| **Parameter notation** | `($NAME)` is an optional reference: if it cannot be resolved, the parameter is not generated and a log entry is written. `$NAME` is a mandatory reference: if missing, logic execution stops with an error. References can be nested (innermost first) and concatenated, e.g. `($SERVICE)_($INDEX)` or `($SERVICE($INDEX))`. Do not mix the two forms in one string. Integer arithmetic is allowed in parentheses, e.g. `(($COUNTER)+1)`; results are truncated. `[$NAME]` (MapParameters only) keeps the target's old value when the source is missing. References are allowed in parameter *names* only in MapParameters. The `($LINE_1_X)` style in the brief is this same reference notation. | p.152–155, p.248–253 |
| **Response parameter prefixes** | NE responses carry `@REQ@<name>` (the value sent), `@NEW@<name>` (new value) and `@OLD@<name>` (previous value). | p.343 |
| **Lookup table** | A named key → value(s) table stored in the DB and cached on first use. Edited in the Lookup Table Editor. Changes need a refresh. | p.230, p.448–454 |
| **Number range** | Start/end range per NE type. Generates parameters when a value falls inside a range. Numeric or alphanumeric (lexical ASCII) comparison. | p.262–268, p.443 |
| **Activity / summary activity** (Order Management) | An activity is a Manual or SendToNE step with an activity name and an estimated duration. A method that contains activities and has a name is a *summary activity*. Both are shown in the Gantt chart. | p.98 |
| **Mainline / baseline** | The mainline is the expected route through the flow, highlighted in blue: by default the If TRUE branch and the first Branch condition. The baseline is the list of mainline activities with durations, i.e. the initial schedule estimate. It can be updated at runtime. | p.99–102, p.438 |
| **Point of No Return (PoNR)** | Once an activity flagged as PoNR has started, the order can no longer be cancelled. The flag can be overridden. | p.100, p.171, p.295 |
| **Jeopardy** | An order is in jeopardy when its estimated delivery date is later than the customer required date. | p.181, p.220 |
| **Order (OM)** | Exists only with Order Management. Supports in-flight amend, cancel and reschedule; has order events and order parameters (a persistent "global pool" visible outside BST). | p.125, p.269, p.281 |
| **Line items / decomposition / task group** | With Catalog, order line items (products/services) are decomposed into indexed `<prefix><group>_<pos>_…` parameters. "y = technical service group (task group)" and "z = technical service (task)". Groups are sequential and items inside a group may run in parallel (`GROUP_COUNT`, `GROUP_SIZE`). | p.103–106, p.385–389 |
| **Schedules** | Three separate meanings: (a) the OM mainline schedule (CreateSchedule, UpdateSchedule, OrderReschedule); (b) timed tasks via `TASK_START_TIME`; (c) a new request's start time in SendRequest. | p.191, p.300, p.346, p.433 |
| **Orders/sub-orders/order lines, "graphs", "fallout"** | **These terms are not used in this manual.** The nearest equivalents: request ≈ order; tasks ≈ order-line actions; SendRequest ≈ spawning child requests; method flow ≈ graph; FAILED/PARTLY_FAILED handling ≈ fallout. There is no fallout queue or management UI in the User's Guide. | — |

Request and task status values used throughout: **5 Cancelled, 6 Rejected, 7 Ready, 8 Partly failed, 9 Failed** (p.302, p.336, p.352). GRC `use_sas3_task_status_values` switches to legacy MDS/SAS 3 values (p.467).

---

## 3. Catalogue of built-in units

Every step shares these: Name (`a-zA-Z0-9_`, at most 200 characters, case-insensitive and unique), Description, Instance Description, an extra input-mapping tab and an output-mapping tab, OK/Apply/Cancel. The "Pool params" column gives the reserved pool parameter names (full list p.160–164).

### 3.1 Structural / control-flow units
| Unit | What it does | Key parameters | Pages |
|---|---|---|---|
| **Method** | A sub-flow with input and output parameter tables. "All output parameters to pool" is discouraged for performance reasons. Reusable through the Methods list. | Name, Input/Output params (C flag), Add to parent pool | p.47–51, p.72, p.137, p.157 |
| **Parallel method** (checkbox on a method) | Runs the method's *steps* at the same time, usually SendToNE steps. Must not contain If/Branch steps or sub-methods. `main_method` cannot be parallel. Must not contain activities. | Parallel flag | p.157, p.129 |
| **Split and Join method** (needs OM) | A fixed set of parallel branches; each branch is a method that must be a summary activity. Branches run independently and join when all are done; declared outputs are merged into the pool. A nested S&J needs a method between them. **No response-sending steps inside.** Duration is that of the longest branch. | Branch methods, outputs | p.52–58 |
| **Dynamic Split and Join** (needs OM) | Like S&J, but N parallel branches are created **at runtime** from one read-only branch method. Branch input comes from `<InPrefix><idx><sep>NAME` parameters. Non-prefixed parameters are copied to every branch. Outputs come back as `<OutPrefix><idx><sep>NAME`. `DSJ_CURRENT_BRANCH_IDENTIFIER` is set in each branch. | Default branch name, Branch summary activity name param (default `BRANCH_SUMMARY_ACTIVITY_NAME`), Number of branches (default 1), Start index (1), In prefix (`IN`), Out prefix (`OUT`), Separator (`_`) | p.59–71 |
| **If** | Evaluates a condition and follows the TRUE or FALSE link. If only TRUE is drawn and the result is FALSE, the method ends and control returns to the parent. **Loops must be built through the FALSE branch**; the mainline follows TRUE. | Conditions: Conjunction AND/OR/XOR, NOT, Operand1, Operator, Operand2; reserved `CONDITION` | p.216–221 |
| **Branch** | Multi-way branch. Conditions are evaluated in order and the first match jumps to its target unit; otherwise the Default target. If there is no target, the method is exited. | Condition description, Target unit, Default target, sub-grid conditions; reserved `CONDITION<n>`, `CONDITION_NAME<n>` | p.174–182 |
| **Condition operators** (If and Branch) | `=`, `<>`, `<`, `>`, `<=`, `>=` (numeric); starts with, ends with, substring/contains, `sequals`, `snequals`; `paramsexists` (one parameter or a list); `regexp` (value vs Java regex); `requals` (regex over parameter *names*, compared with a value); **NE status** (IDLE / WAITING / NOT_AVAILABLE of an NE ID); **Order in jeopardy**; **Order is cancelling**; **PoNR reached** (the last three query OM live). | | p.177–182, p.217–221 |
| **Switch** | Compares a switch parameter with case values and writes the matching case's parameter set (or the defaults) to the pool. Supports a regex over parameter names and `SWITCH_PARAMETER_NAME`. With `$X`, a missing parameter stops execution; with `($X)`, execution continues. | Switch param, cases (value → name/value/default), defaults, Sort | p.427–432 |
| **LogicLibrary** | Calls an active library logic, by name or by `$PARAM`. Passes either the whole pool or filtered parameters (mandatory/optional/prefix/cut-prefix). Can merge all response parameters back with a prefix. Has an "Ignore execution errors" option. Sets `LIBRARY_ERROR_CODE`: 100 = name missing, 101 = not found/inactive, 200 = execution failed, 900 = switch/crash-recovery/expired/context-load failure, 901 = crash. Can carry activity name and duration. Deleting a parameter inside the library does not delete it in the caller. Not usable in the standalone editor. | `ADD_ALL_PARAMS_TO_CHILD`, `PARAM_LIST`, `PARAO_LIST`, `STARTS_WITH_LIST`, `CUT_PREFIX` | p.27, p.224–229 |
| **Loops** | **There is no loop or for-each step.** Loops are built by linking back through the If FALSE branch and incrementing counters with MapParameters/Calc. The OrderEvent example loops 1..`ORDER_EVENT_PENDING_COUNT` this way. The engine guards recursion with `nesting_limit`. The Tracer can step through loop rounds. | | p.137, p.216, p.271, p.460, p.465 |
| **Wait / timers** | **There is no wait or delay step.** Time-based behaviour comes from `TASK_START_TIME` (timed task, `yyyyMMddHHmmss`) on SendToNE/SendOriginalTask/ParallelSend2, SendRequest start time, and human waits in the Manual step. Activity durations are estimates only. | | p.300, p.331, p.364, p.347 |

### 3.2 Parameter and data manipulation
| Step | What it does | Key parameters | Pages |
|---|---|---|---|
| **MapParameters** | Creates parameters (name and value can both use references and integer arithmetic). Default value when a source is missing (`DEF_VAL_NOT_FOUND_PARAMETER`). Bracket reference `[$X]`. Removes prefixes/suffixes, creating copies (`REMOVE_BEFORE_LIST`/`REMOVE_AFTER_LIST`, `%` wildcard). Deletes parameters (`DEL_PARAM_LIST`; deletion is slow). Adds `TIMESTAMP` (`yyyyMMddHHmmss`, `HH:mm:ss`, `SYSTEMMILLIS`). With OM, also `CUSTOMER_REQUIRED_DATE` / `ESTIMATED_DELIVERY_DATE`. Do not use the same parameter on both sides in one step. | see left | p.247–258 |
| **Calc** | One arithmetic or string operation into `RESULT`: `+ - * /`, `idiv`, `%`, `concat`, `charAt`, `length`, `numberOf`. | `LEFT`, `OPER`, `RIGHT`, `RESULT` | p.183–186 |
| **String** | A sequence of operations into `RESULT_1..n`; later operations can use earlier results; `ERROR` on failure. Operations: `addSeconds` (timestamp arithmetic and formats), `numberOf`, `binToDec`, `bitOperation` (AND/OR/XOR/NOT), `charAt`, `concat`, `decToBin`, `decToHex`, `getPrefix`, `getSuffix`, `hexToDec`, `indexOf`, `length`, `removePrefix`, `removeSuffix`, `replaceString`, `replaceSubString`, `substring`, `toLower`, `toUpper`. | `PARAM_1_<n>`, `PARAM_2_<n>`, `PARAM_3_<n>`, `OPER_<n>`, `RESULT_<n>` | p.419–426 |
| **Date** | Adds or subtracts up to 8 time amounts (years down to milliseconds) on a custom or current date. Converts formats with SimpleDateFormat. Compares two dates (BEFORE/AFTER/EQUAL) and returns the difference as `dd:HH:mm:ss` with a chosen maximum unit. | source/destination date and format | p.202–205 |
| **RegExp** | Matches Java regexes against source parameter values and creates parameters from capture groups `(\1)`. Can rename parameters (`*` source) or delete matching parameters. Options: first match only, use only common parameters, `KEEP_ORIGINALS`, `EVALUATE_REGEXP_<n>` (references inside the regex), defaults. | `REGEXP<n>`, `<n>_<param>`, `DEFAULT_<param>`, `DEL_PARAMS` | p.320–330 |
| **CheckParams** | Checks that the listed parameters exist and are non-empty. `CHECK_STATUS` = count missing; `SMESSAGE_RESULT` = "Missing parameter(s): …". Optional log output. | `PARAMETERS1`, `LOG_RESULTS` | p.187–190 |
| **NumberRange** | Range lookup by NE type and value. Writes the matching range's parameters or the defaults. Numeric or alphanumeric comparison. Optional ID (`NR`) for speed. | `NETYPE`, `PARAM_VALUE`, `RANGE<n>`, `<n>_<param>`, `DEFAULT_<param>`, `//AN//` | p.262–268 |
| **LookUp** | Looks up keys in named tables, or all tables (first hit wins). Optional multiple values per key, giving `KEY_1..n` and `KEY_COUNT`. `NOT_FOUND` for a missing key. `LOOKUP_ERROR` = `TABLE_NOT_FOUND` / `NO_KEYS_GIVEN`. | `LOOKUP_NAMES`, `KEY_LIST`, `MULTIPLE_VALUES` | p.230–235 |
| **XML_PARSER** | Flattens simple XML in a parameter into the pool. Two nesting levels with prefixes; for repeated tags only the last is kept. `XML_STEP_STATUS`. | `SERVICE`, `XMLprefix1` (`PROMO`), `XMLprefix2` (`SUBS1`) | p.439–442 |
| **EdifactParser** | Parses EDIFACT `LINE<n>` parameters with regexes plus extra rules (`<`, `>`, `!=`, `==`). Fields split on `+` become `($1)…($n)`. Supports `?` escapes. | `EDI_PARSE_OPTION`, `<n>_EDI_RULE<m>`, etc. | p.206–215 |
| **Database** | Runs SQL on a configured Oracle or PostgreSQL system. Rows become `<COL>_<n>`. Sets `DB_ROWS`, `DB_RESULT` (0/1), `DB_MESSAGE`, `DB_ERROR`. Errors are reported through these parameters and logic execution continues. NULLs must be wrapped in NVL. Do not touch the InstantLink DB. | `DB_SYSTEM`, `SQL_STATEMENT`, `SQL_MAX_ROWS`, `SQL_OUTPUT_PARAMS` | p.194–201 |
| **PL/SQL** | Calls an Oracle procedure or function with IN/OUT parameters, a return value, a REF CURSOR or DBMS_OUTPUT lines (prefix plus `<prefix>_ROWS`). | `PLSQL_PROCEDURE_NAME`, `PLSQL_PARAM_<n>`, `PLSQL_PARAM_TYPE_<n>`, `PLSQL_RETURN_PARAMETER`, `PLSQL_RETURN_CURSOR`, `PLSQL_DBMS_PREFIX` | p.310–319 |

### 3.3 Talking to NEs, other requests and the OSS/BSS
| Step | What it does | Key parameters | Pages |
|---|---|---|---|
| **SendToNE** | **Core step.** Builds one new task from selected pool parameters and sends it to InstantLink for execution on the NE. Waits for the response and maps it back. Can be an OM activity (name, duration, PoNR) and carry `BST_ACTIVITY_ID` to start/stop activities automatically. Supports timed tasks, best-effort tasks, jeopardy notifications and in-flight policies. | Task: `ADD_ALL_PARAMETERS`, `BEST_EFFORT_TASK`, `PARAM_LIST` (mandatory), `PARAO_LIST`, `STARTS_WITH_LIST`, `END_WITH_LIST`, `SUBSTRING`, `CUT_PREFIX` (`%` wildcard, references). Response: `GET_ALL_RESP_PARAMS`, `ADD_RESP_PREFIX`, `CUT_RESP_PREFIX`, `IGNORE_RESP_PREFIX`, `OUTPUT_PARAMETER_LIST`. Output: `STATUS_RESULT`, `SMESSAGE_RESULT` | p.363–378, p.125 |
| **SendOriginalTask** | Sends one of the *original tasks* that came with the incoming request (`TASK_ID`), or all of them (`SEND_ALL_ORIGINAL_TASKS`, which sets `OVERALL_STATUS` 7/8/9). Can add or update parameters, but only where InstantLink allows it (for example, the task's `NE_TYPE` cannot be changed). Can remove parameters (`REMOVE_FROM_ORIGINAL_TASK`). Response mapping as in SendToNE. | as SendToNE, plus `TASK_ID`, `ORIGINAL_SENT_TASK_COUNT` | p.331–345 |
| **ParallelSend2** | Builds N tasks from indexed pool parameters `<PREFIX><i><sep>NAME` (default `TASK_`), e.g. `TASK1_NE_ID`, `TASK1_NE_TYPE`, `TASK1_TASK_TYPE`, … and sends them **in parallel**. Collects responses as `<OUTPUT_PREFIX><i><sep>NAME` (default `RESP`). Sets `OVERALL_STATUS` 7/8/9. Per-task `TASK_START_TIME`. | `START_NUMBER`, `END_NUMBER`, `AMOUNT_OF_TASKS_TO_SEND`, `PREFIX`, `USER_DEFINED_PREFIX_SEPARATOR`, `GET_ALL_RESPONSE_PARAMETERS`, `OUTPUT_PARAMETER_LIST`, `RESPONSE_START_NUMBER`, `OUTPUT_PREFIX`, `USER_DEFINED_RESPONSE_PREFIX_SEPARATOR` | p.300–309 |
| **Manual** (manual task / approval) | Sends a **work item to Workflow Client**: form, work queue, description, optional `USER_NAME` and `EXPIRATION_PARAMETERS`. Parameter selection and response handling work as in SendToNE. Activity, PoNR, jeopardy notification and in-flight policy as SendToNE, but the **default in-flight policy is "interrupt"** (0). | `FORM_NAME`, `TICKET_GROUPS`, `TICKET_INFO`, plus the SendToNE lists | p.236–246, p.125 |
| **SendRequest** | Creates a **new InstantLink request**, i.e. a child or sub-request. Protocol: SAS3 Extended API (one task `TASK_1`) or SAS4 Request API (several `TASK_<N>`; deprecated). Request locking on `TASK_*` parameters (bypass adds `LOCK=NO`). Returns `SEND_RESULT` (`OK` / `ERROR:<why>`) and `SEND_REQUEST_ID`. The manual does **not** describe waiting for, or correlating, the child request's completion. Can create infinite request loops. | `USED_PROTOCOL`, `BYPASS_LOCKING`, `USED_PRIORITY`, `USED_REQ_USER` (<30 chars), `USED_ORDERNO`, `USED_RESPQID` (default `NONE`), `USED_START_TIME`, `USED_EXPIRATION_TIME` (default from Request Engine `default_request_time`), `USED_PRIM_SUB_ID`, `USED_SECO_SUB_ID`, `USED_RETRANSMISSION_FLAG`, `REQ_PAR_<NAME>`, `TASK_NEID_<N>`, `TASK_NETYPE_<N>`, `TASK_TYPE_<N>` (create/delete/display/edit), `TASK_<N>_<NAME>`, `NUM_OF_TASKS` | p.346–351 |
| **SendResp** | Builds the **final response** to the OSS/BSS and **ends logic execution**. Not recommended inside Split and Join, because a response from one branch stops all branches. | `_STATUS` (5/6/7/8/9), `_SMESSAGE`, `_SMESSAGE_ID`, `PARAM_LIST`, `PARAO_LIST`, `STARTS_WITH_LIST`, `STARTS_WITH_LIST_WITH_CUT_PREFIX`, `END_WITH_LIST`, `SUBSTRING`, `DELETE_FROM_LIST` | p.352–362 |
| **Notification** | Sends an intermediate progress message (up to 4000 characters, 8-character ID) through InstantLink to the BSS. Visible under Requests > Request Details > Notifications. | `NOTIFICATION`, `NOTIFICATION_ID`, `NOTIFICATION_LEVEL` | p.259–261 |
| **Alarm** | Raises an InstantLink alarm (NO_ALARM/WARNING/MINOR/MAJOR/CRITICAL) with text and the step path. Never stops execution. Can be forwarded through Alarm Dispatcher. | `SEVERITY`, `ADDITIONAL_ALARM_TEXT` | p.168–170 |
| **Log** | Writes a message and optionally the whole pool to the engine log at SEVERE/WARNING/INFO/FINE. Needs `diag_level` INFO/ALL. | `MESSAGE1`, `LOG_LEVEL`, `DEBUG_ON` | p.222–223 |
| **Send mail / e-mail** | **Not in this manual.** There is no e-mail step. The nearest features are Notification, Alarm and jeopardy notifications. | — | — |
| **Custom Java / script step** | **Not in this manual.** No user-code step is documented. Extension points are SQL/PL-SQL, library logics and the NEs themselves. | — | — |

### 3.4 Order Management / Catalog steps (optional modules)
| Step | What it does | Pages |
|---|---|---|
| **ActivityStatus** | Sets an activity (by ID) to STARTED/COMPLETED/FAILED. Needed for summary activities in catalog-driven mode. | p.166–167 |
| **AmendOrder** | Amends the *current* order from inside the logic, using parameters selected by prefix with optional cut-prefix. Can interrupt other parallel branches and pass data to them as `AMENDED_*`. Overrides: `AMEND_OVERRIDE_PONR`, `AMEND_OVERRIDE_PARALLEL_RESTRICTION`, `AMEND_OVERRIDE_CANCELLING_RESTRICTION`. Output `AMEND_STATUS` (`AMEND_OK`/`AMEND_FAILED`) and `AMEND_STATUS_MESSAGE`. | p.171–173 |
| **OrderReschedule** | Changes the order's customer required date (absolute value, offset, or both). Optional OM UI notification. `RESCHEDULE_OVERRIDE_PONR`. | p.295–299 |
| **OrderEvent** | Fetches pending events by type (`ORDER_EVENT_PENDING_COUNT`, `ORDER_EVENT_PENDING_<i>`), reads, modifies (accept/reject/close) or creates order events. Can store event parameters as order parameters. | p.269–280 |
| **OrderParameters** | Read, Write, Delete and Tag on **persistent order parameters**: a cross-branch and cross-order "global pool" stored outside BST. Filters by name/prefix/suffix/substring/tag; supports order-content and internal tags. | p.281–294 |
| **CreateSchedule** | Builds or modifies the OM mainline from decomposition parameters. Modes: CREATE, APPEND, UPDATE, **INJECT** (add activities at the current point), **REPLACE** (by `TARGET_ACTIVITY_ID`, or the next activity; replacing with nothing deletes). Optional baseline copy and `ESTIMATED_DELIVERY_DATE`. | p.191–193, p.106–124 |
| **UpdateSchedule** | Updates an activity's start date, duration, name or PoNR at runtime, matched by name or ID. | p.433–437 |
| **UpdateBaseline** | Snapshots the current mainline as the new baseline. | p.438 |
| **ServiceSpecification** | Fetches a product/service specification from Catalog. Returns missing mandatory/optional attributes, capabilities, dependencies and exclusions. | p.379–382 |
| **ServiceDecomposition** | Fetches the decomposition into technical services with NE-specific parameters such as `NE_TYPE`, `REQ_TYPE`, `REQ_OBJ`, `TASK_TYPE`, `ERROR_DATA`, `TS_DURATION`. Can return the full product hierarchy (groups, `ITEM_TYPE` LOCAL/REMOTE/SELECTION_GROUP, `BST_ACTIVITY_ID`, undo transaction). Supports cross-service parameter references. | p.383–395 |
| **DeltaSpecification / DeltaCalculation** | Checks prerequisites for, and computes the task groups of, a product upgrade or downgrade (migration). Error 903 = missing undo transaction. | p.396–406 |
| **ErrorDecomposition** | **Rollback / compensation:** returns the undo technical services in reverse order for a failed point (`ERROR_DATA`), or for the whole product. | p.407–414 |
| **ExecutionOrder** | Asks Catalog for the dependency-sorted execution order of items. Can report or ignore missing dependencies (error 904). | p.415–418 |

### 3.5 In-flight, rollback and error features that cut across steps
- **In-flight policies** (p.125–128):
  - `ORDER_CANCEL_TASK_RESPONSE_POLICY`, `ORDER_AMEND_TASK_RESPONSE_POLICY` and `ORDER_RESCHEDULE_TASK_RESPONSE_POLICY` are set per task. `0` means interrupt now with a fake *Cancelled (5)* response; `-1` means wait for the real NE response.
  - The default is `-1` for SendToNE and `0` for Manual.
  - The engine injects `INFLIGHT_OPERATION`/`DESCRIPTION`/`USER`, `FAKED_TASK_RESPONSE_BY` (`CANCELLATION_MANAGER` etc.), `CANCELLED_*`/`AMENDED_*` and `AMEND_CUSTOMER_REQUIRED_DATE`.
  - Priority when several operations arrive: reschedule < amend < cancel.
  - Fine-grained amend interrupt rules: `INTERRUPT_ACTIVITY_[IF|UNLESS]_AMENDED_PARAM_[IN_LIST|STARTS_WITH]` and `INTERRUPT_ACTIVITY_IF_AMENDED_PARAM_VALUES_MATCH_<n>`.
  - Interrupted WFC tickets are cancelled.
- **Rollback / compensation:** the engine does nothing automatically. The logic must call ErrorDecomposition, run the returned undo tasks, and optionally REPLACE/UPDATE the mainline (p.106–107).
- **"Ignore execution errors"** checkbox on many steps (LogicLibrary, ActivityStatus, CreateSchedule, OrderReschedule, Catalog steps). Its use is discouraged in catalog-driven flows (p.108).
- **Error results are reported as pool parameters** that the logic must test with If/Branch: `STATUS_RESULT`/`SMESSAGE_RESULT`, `OVERALL_STATUS`, `DB_RESULT`/`DB_ERROR`, `LOOKUP_ERROR`, `LIBRARY_ERROR_CODE`, `SC_ERROR_CODE`, `AMEND_STATUS`, `SEND_RESULT`, `CHECK_STATUS`.
- **Hard failure:** a missing mandatory parameter or a failed `$X` reference stops the logic. The BST engine then sets the request to **FAILED or PARTLY_FAILED**, unless errors are ignored (p.155, p.228).

---

## 4. How BST sends a task to an NE/NEI and handles the response

### 4.1 Building the task
- The task content is **only the parameters the step selects** from the pool: mandatory list, optional list, prefix/suffix/substring filters, cut-prefix (with `%` wildcard and `($X)` references), or "add all" (p.363–370). Routing comes from task parameters such as `NE_ID`, `NE_TYPE` and `TASK_TYPE`; ParallelSend2 examples use `TASK1_NE_ID=hlr1`, `TASK1_NE_TYPE=HLR`, `TASK1_TASK_TYPE=create` (p.303). Catalog decomposition supplies `NE_TYPE`, `REQ_TYPE`, `REQ_OBJ`, `TASK_TYPE` per technical service (p.386–387). *The manual does not state which of these InstantLink requires or how it resolves NE_ID from NE_TYPE.*
- **Parameters the manual says are added automatically:**
  - **SendToNE / Manual with Order Management installed:** `ORDER_CANCEL_TASK_RESPONSE_POLICY`, `ORDER_AMEND_TASK_RESPONSE_POLICY`, `ORDER_RESCHEDULE_TASK_RESPONSE_POLICY` (p.125).
  - **Manual step:** `FORM_NAME`, `MAX_TICKET_TIME` (GRC `max_ticket_time`), `NE_ID` (GRC `ne_id`, OM section), `NE_TYPE=WFC`, `REQ_TYPE=1`, `TICKET_GROUPS`, `TICKET_INFO`, `TICKET_TYPE=1`. The table on p.245 is column-garbled in the extraction, and this is the best reading.
  - `BST_ACTIVITY_ID`, when passed, makes the engine start the activity on send and mark it COMPLETED or FAILED from the response (p.106, p.108).
  - **SendRequest:** `PRIORITY`, `REQ_USER`, `RESP_QUEUE_ID` (default `NONE`), order number, start/expiration time, `LOCK=NO` when locking is bypassed (p.347–350).
- **Not found in this manual:** `task_type`/`ne_type`/`ne_id` as engine-generated parameters, `CHILD_REQ_ID`, `PARENT_REQ_ID`, `IL_TASK_GROUP`, `ZZZPARALLEL_BRANCH_IDZZZ`, `ZZZRESERVED_ORDER_IDENTIFIER_PARAMETERZZZ` and `GENERATED`. `REQ_TYPE` and `REQ_OBJ` appear only as example or catalog data, not as auto-generated. `ORDER_NO` appears only in an EDIFACT example and as SendRequest's `USED_ORDERNO`. These are presumably InstantLink/engine internals seen in traces. Look for them in the BST Reference Manual, InstantLink documentation or real BST Tracer exports.
- **Timed task:** `TASK_START_TIME=yyyyMMddHHmmss` defers sending (p.364).
- **Best-effort task:** `BEST_EFFORT_TASK` makes InstantLink reply success immediately, and the real NE response is never returned to BST. Lifecycle values are 1–4 (p.365).

### 4.2 Response processing
- The step waits (asynchronously, inside the engine) for the task response. It then writes **`STATUS_RESULT`** (from the response `STATUS`) and **`SMESSAGE_RESULT`** (from `SMESSAGE`) (p.371).
- Other response parameters arrive as `@REQ@x`, `@NEW@x` and `@OLD@x`. They are merged into the pool by an explicit `OUTPUT_PARAMETER_LIST`, or by "add all" with prefix handling (cut first, then add, then ignore-prefixes) (p.343, p.370–376).
- ParallelSend2 and SendOriginalTask(all) add `OVERALL_STATUS`: 7 if all succeeded, 8 if some failed, partly failed or were cancelled, 9 if all failed (p.302, p.336).
- **Branching on the result is the logic's job**, typically with If/Branch on `STATUS_RESULT`. The manual describes no automatic retry, timeout or fallout routing for a failed task.

### 4.3 Retries, timeouts, expiry
- **Retries:** not described anywhere in the User's Guide. They are presumably handled in InstantLink or the NE interface.
- **Timeouts / expiry:**
  - Requests have an engine **expiration mechanism**. GRC `bypass_expiration_mechanism` disables it, after which the logic must detect expiry itself. `request_expiration_trigger` (0–3) decides whether `@NEW@`/`@REQ@` parameters are copied into the response when a request expires (p.465–466).
  - SendRequest `USED_EXPIRATION_TIME` sets the expiry of a child request. WFC tickets use `MAX_TICKET_TIME` and `EXPIRATION_PARAMETERS`.
  - LogicLibrary error 900 includes "Request was expired" (p.229).

### 4.4 Failure, cancel, amend, reschedule
- **Failure:** see §3.5. The request becomes FAILED or PARTLY_FAILED. The final response status is chosen by the logic in SendResp (5/6/7/8/9).
- **Cancel / amend / reschedule:**
  - These arrive from OM Web Services or the OM UI, or from the logic itself (AmendOrder, OrderReschedule).
  - The running task is interrupted or allowed to complete according to its policy (§3.5). Interruption produces a fake response with `STATUS_RESULT` 5 and `INFLIGHT_*` parameters, so the logic can react.
  - PoNR blocks cancellation unless overridden.
  - Pending in-flight operations appear as **order events** for the OrderEvent step to accept or reject (p.125–128, p.269).

---

## 5. Operational features
| Area | Capability | Pages |
|---|---|---|
| Visual designer | Drag-and-drop palette; method tabs and navigation path; auto-layout, align, snap to grid, zoom, overview, full-screen navigation; tooltips showing configuration; notes; clipboard (4 items, persists in `~/BST/clipboard`, Paste vs Paste As New with type-mismatch checks); Methods/Steps lists with reference counts; search units (wildcards/regex by name, type, parameter name/value); Parameter Dictionary with CTRL+SPACE autocomplete (`parameterlist.xml` saved with the logic). | p.41–83, p.138–145 |
| Unit testing | Runs selected consecutive steps against a pasted or loaded request-parameter file and shows a before/after pool diff. Steps with external connections cannot be tested. Limits `ui_testing_engine_nesting_limit` 10 and `ui_testing_engine_max_unit_limit` 15000. | p.84–88 |
| Save-time validation | Activities require mainline; parallel methods may not contain activities; S&J may not contain response steps; S&J branches must be summary activities. Warnings: mainline not ending in a response, PoNR inside S&J, response step inside a library. | p.93, p.129 |
| Versioning | Each save creates a new `name_YYYYMMDD_HHMMSS.jar` version in the DB. Plus/minus expands version history. Autosave (`auto_save_interval`, default 15 min) as `AUTOSAVE_*.jar`; emergency save as `EMERGENCY_*.jar`. Save As renames and copies the change history. | p.22, p.94–95, p.129–131 |
| Deployment / activation | Activate on Logic Versions, through Logic Rules, or with "Save and Activate" in the editor (which triggers an InstantLink refresh). One logic can serve several rules; activating a new timestamp updates all of them. Only one timestamp of the same name may be used across rules. Library logics are activated separately. Deactivate. Copy/move between versions and library. Delete. | p.22–30, p.133 |
| Old-version execution | `old_logic_support`: in-flight and timed requests keep the logic version they started with; the Active Logics page lists current and previous versions. | p.22, p.466 |
| Export / import | Download/upload logic jars (server ↔ workstation) in the editor, the InstantLink UI or the UNIX command line (Reference Manual). Lookup tables as `LOOKUP_TABLES_<ts>.zip`, upload validated with logs in `sas/logdir/logs`. Trace export as `trace_export_<reqid>_<ts>.zip`. | p.150, p.452–453, p.456 |
| Documentation / diff | Auto-generated HTML report of a logic (methods, steps, parameters, flow images, baseline), downloadable as zip. **Compare Logics** report: unique elements, renamed elements, parameter differences. | p.20, p.134, p.146 |
| Change history and audit | Per-logic change history (units, links, parameter edits; per save, per user). Audit Trail page and `BST_MA_<date>_<seq>.log` for rule changes, deletions and activations. | p.23, p.148 |
| Monitoring / tracing | Request Details page in the InstantLink UI. **BST Tracer**: per-request execution path, pool before/after each unit, task and response parameters, loop rounds, subtraces for S&J/DSJ/libraries, comparison of two traces with filters, load of incomplete traces. Trace level via GRC `request_trace` (NONE/EXECUTION_PATH/EVERYTHING/MIXED) or a per-request `REQUEST_TRACE` parameter (recommended for production debugging). | p.455–462, p.466 |
| Coverage | Usage percentage of every unit across traced requests in a date range; HTML report. | p.37–40 |
| OM monitoring | Gantt chart, mainline, baseline, jeopardy, PoNR, order events and notifications in the OM UI. | p.98–124 |
| Alarms / notifications | Alarm step to the InstantLink alarm pane / Alarm Dispatcher. Notification step to the BSS and Request Details. Jeopardy notifications per activity (`SEND_ACTIVITY_JEOPARDY_NOTIFICATION`). **No e-mail.** | p.168, p.259, p.243, p.377 |
| Security / roles | InstantLink User Management roles: Administrator, View BST configuration, Edit BST configuration, Edit BST number ranges, Edit BST lookup tables, Edit BST logics. Everything is locked in production mode. Configuration mode is required to edit GRC. | p.9–10, p.464 |
| Logging | Engine log level (`diag_level`), UI log (`diag_level_ui`), Log step, CheckParams log option. | p.222, p.464 |
| GRC configuration (bst section) | `diag_level`, `diag_level_ui`, `auto_save_interval`, `bypass_expiration_mechanism`, `nesting_limit`, `old_logic_support`, `request_expiration_trigger`, `request_trace`, `use_sas3_task_status_values`, `all_output_parameters_support`, `overwrite_support`. Changes need a refresh. JVM properties `delete_unrun_activities` and `ignore_activity_not_found` apply to OM activities. | p.108, p.463–468 |
| Northbound APIs | **Not documented here.** Requests come from OSS/BSS through InstantLink. The manual mentions only the SAS3 "Extended API", SAS4 "Request API" (deprecated), InstantLink Edifact API and Order Management Web Services, for in-flight operations and rescheduling. No REST or SOAP specification. | p.106, p.125, p.206, p.347 |
| Scheduling | Timed tasks, request start time, OM schedule and baseline manipulation, CRD reschedule. No cron-style scheduler. | see §2 |
| Concurrency safety | Pools are isolated per request. SendRequest uses InstantLink request locking on `TASK_*` parameters. | p.156, p.346 |

---

## 6. Requirements checklist for a replacement

MUST = core orchestration needed to reproduce what BST does for your NEIs. SHOULD = important for operations or parity. COULD = only needed if you use the optional OM/Catalog/EDIFACT/DB features.

**Request intake, logic selection, data model**
1. **MUST** Receive an inbound request as a flat `NAME=value` parameter set and create an isolated per-request parameter pool from it (p.51, p.156).
2. **MUST** Choose the workflow from request parameters with ordered rules (Equals/Substring, AND/OR, parentheses), first match wins, plus a catch-all default; no match means FAILED (p.29–33).
3. **MUST** Keep workflow definitions versioned and immutable, with a timestamped version per save, and explicit activation and deactivation (p.22, p.94, p.130).
4. **SHOULD** Run in-flight, long-running and timed requests on the version that was active when they arrived (`old_logic_support` / `received_time`) (p.22).
5. **MUST** Support parameter references: optional `($X)` and mandatory `$X`, nested and concatenated, with integer arithmetic; references in parameter names when mapping (p.152–155, p.250–253).
6. **MUST** Support scoped child pools for sub-flows, with declared input and output parameters, an "add to parent" list with wildcards, and parameters that always propagate (p.157–165, p.55).
7. **MUST** Keep a defined evaluation order within a unit: inputs, then step results, then outputs (p.153).
8. **SHOULD** Let one step or sub-flow definition be reused in several places with shared ("common") and per-instance parameters (p.72, p.83, p.159).

**Control flow**
9. **MUST** Provide sequential flow with sub-flows (methods) nested up to a configurable depth limit (p.137, p.465).
10. **MUST** Provide an If step with TRUE/FALSE paths, where a missing path means return to the parent (p.216).
11. **MUST** Provide a multi-way Branch with ordered conditions, a default target and exit-method behaviour (p.174).
12. **MUST** Provide a condition language with numeric `= <> < > <= >=`; starts/ends/contains; string equals/not-equals; parameter-exists (single or list); regex on value; regex over parameter names; AND/OR/XOR/NOT (p.177–181, p.217–220).
13. **SHOULD** Allow conditions on live NE/interface status (IDLE/WAITING/NOT_AVAILABLE) (p.181).
14. **MUST** Support loops with counters, because BST builds them with If FALSE back-links; a real for-each/while construct is better, with a guard against infinite loops (p.216, p.84, p.271).
15. **MUST** Provide a Switch/case that emits a parameter set per case plus defaults, including regex on parameter names (p.427–432).
16. **MUST** Provide parallel fan-out of a fixed set of branches and join when all complete, with per-branch pools and merging of declared outputs (Split and Join) (p.52–58).
17. **MUST** Provide dynamic fan-out of N branches decided at runtime, with indexed input and output prefixes and a branch ID (Dynamic Split and Join) (p.59–71).
18. **MUST** Send several tasks in parallel from indexed parameters and aggregate their status as 7/8/9 (ParallelSend2, Parallel method) (p.157, p.300–309).
19. **MUST** Provide callable shared sub-workflows (library logics) with explicit parameter passing, nesting limit, error codes and an ignore-errors option (p.27, p.224–229).
20. **MUST** Provide an explicit end-with-response step that terminates the run; forbid or guard it inside parallel branches (p.352, p.55).

**Talking to NEIs and responses**
21. **MUST** Have a send-task-to-NE step that builds a task from selected pool parameters (mandatory list, optional list, prefix/suffix/substring, cut-prefix with wildcard, all) including routing keys (NE_ID / NE_TYPE / TASK_TYPE …), dispatches it to the NEI, and **waits durably** for the response (p.363–370).
22. **MUST** Merge the response into the pool: status to `STATUS_RESULT`, message to `SMESSAGE_RESULT`, selected or all response parameters with add/cut/ignore prefix rules, handling `@REQ@`/`@NEW@`/`@OLD@` (p.343, p.370–376).
23. **MUST** Use the status code model 5 Cancelled / 6 Rejected / 7 Ready / 8 Partly failed / 9 Failed for tasks, aggregates and the final response (p.302, p.352).
24. **MUST** Stop with FAILED when a mandatory task parameter is missing, and generate `SMESSAGE_RESULT` naming it (p.365).
25. **SHOULD** Support timed tasks that are not sent before a given timestamp (`TASK_START_TIME`) (p.364).
26. **SHOULD** Support best-effort (fire-and-forget) tasks (p.365).
27. **SHOULD** Forward the original tasks contained in the inbound request, one or all, with parameter add, update and remove (SendOriginalTask) (p.331–345).
28. **SHOULD** Spawn a new child request into the system with priority, user, order number, response queue, start time, expiry and locking, returning its request ID (SendRequest). Decide explicitly whether to wait for the child, because BST's manual does not say (p.346–351).
29. **MUST** Build the final response with status, message and message ID plus selected parameters, and parameter deletion before the response (p.352–362).
30. **SHOULD** Send intermediate progress notifications to the caller (p.259).
31. **MUST** Define request expiry/timeout handling, with an option to let the workflow handle expiry itself; per-task timeouts and **retries must be designed new**, because BST documents none (p.465–466).
32. **MUST** Leave error handling as data: steps set error or status parameters and the workflow branches on them; an "ignore errors" flag per step; a hard failure sets the request to FAILED/PARTLY_FAILED (p.155, p.228).
33. **MUST** Persist the request context so that execution survives a crash or restart and is recovered (p.229).

**Data steps**
34. **MUST** Map parameters: create, copy, default-if-missing, rename by prefix/suffix, delete, timestamps (p.247–258).
35. **MUST** Provide string and arithmetic operations (Calc and String operation sets, including timestamp add-seconds, hex/bin conversion, substring, replace) (p.183, p.419–426).
36. **SHOULD** Provide date arithmetic, format conversion, comparison and difference (p.202).
37. **MUST** Extract values and rename/delete parameters with regex and capture groups (RegExp) (p.320–325).
38. **MUST** Check that parameters are present (CheckParams) (p.187).
39. **MUST** Provide lookup tables with a managed editor, multi-value keys, NOT_FOUND handling, and versioned upload/download (p.230–235, p.448–453).
40. **SHOULD** Provide number-range routing per NE type, numeric or alphanumeric, with an editor and overlap validation (p.262–268, p.443–447).
41. **SHOULD** Provide SQL and stored-procedure calls on external databases, with row → indexed parameter mapping and error parameters (p.194–201, p.310–319).
42. **COULD** Provide an XML-to-parameters flattener (p.439).
43. **COULD** Provide an EDIFACT parser (p.206).

**Order-management / long-running features (only if you used OM)**
44. **SHOULD** Model activities with name, estimated duration and PoNR, and summary activities; show a Gantt or timeline per order (p.98–102).
45. **SHOULD** Provide mainline and baseline, and runtime schedule edits: inject, replace, update, append; update activity date, duration, name and PoNR; re-baseline (p.99–124, p.191, p.433, p.438).
46. **SHOULD** Provide in-flight cancel, amend and reschedule from outside and from inside the workflow, with a per-task interrupt-or-wait policy, fake-response injection, `INFLIGHT_*` parameters, priority rules, selective amend interrupt rules and PoNR override flags (p.125–128, p.171, p.295).
47. **SHOULD** Provide workflow conditions on jeopardy, cancelling and PoNR-reached state (p.181–182).
48. **SHOULD** Provide persistent order-level parameters shared between branches and orders (read/write/delete/tag) (p.281–294).
49. **COULD** Provide order events (fetch pending, read, accept or reject, create) (p.269–280).
50. **SHOULD** Provide manual / human tasks: create a work item with form, queue, assignee and expiry; wait for completion; map its response; cancel the ticket when interrupted (p.236–246).
51. **COULD** Provide jeopardy notifications per activity (p.243, p.377).
52. **COULD** Provide catalog-driven decomposition, delta (migration), rollback (undo-in-reverse) decomposition and execution ordering by dependencies (p.379–418).
53. **SHOULD** Provide a compensation pattern: record executed actions and run undo actions in reverse on failure (BST relies on ErrorDecomposition plus the logic) (p.106–107, p.407–414).

**Design-time tooling**
54. **SHOULD** Provide a visual or declarative workflow editor with step palette, sub-flow tabs, links, notes, search and parameter autocomplete (p.41–83).
55. **SHOULD** Validate at save time (structural rules for parallel blocks and response steps, mainline rules) (p.93, p.129).
56. **SHOULD** Allow unit testing of step sequences against a sample request, with a pool diff (p.84–88).
57. **SHOULD** Keep change history per workflow, plus an audit trail of activations and rule changes (p.23, p.148).
58. **SHOULD** Diff two workflow versions (p.134).
59. **COULD** Generate human-readable documentation and reports of a workflow (p.146).
60. **SHOULD** Export and import workflows and reference data between environments (file and CLI) (p.150, p.452).
61. **COULD** Provide autosave and crash-recovery of edits, and an offline editor (p.94–95, p.89).

**Runtime operations**
62. **MUST** Provide per-request execution tracing: path taken, pool before and after each step, task and response payloads, sub-traces for parallel branches and sub-workflows, switchable globally or per request; export/import of traces (p.455–462, p.466).
63. **SHOULD** Compare two request traces (p.460–461).
64. **COULD** Provide coverage analysis of workflow paths over many requests (p.37).
65. **SHOULD** Provide alarm raising to monitoring, with severity (p.168).
66. **MUST** Provide configurable engine log levels and a debug-dump step (Log) (p.222, p.464).
67. **MUST** Provide role-based access: view, edit workflows, activate, edit reference data, admin; and a production lock on changes (p.9–10, p.6).
68. **SHOULD** Make workflow and reference-data activation take effect without downtime (BST requires an InstantLink "refresh") (p.18, p.230).
69. **SHOULD** Keep runtime settings (BST's GRC) for trace level, nesting limit, expiry behaviour, legacy status values, etc., editable with a privilege (p.463–468).
70. **COULD** Send e-mail. Not provided by BST; add it only if the team wants it.
71. **COULD** Support custom code steps. Not in BST; BST covers this with SQL/PL-SQL, library logics and the NEIs themselves.
