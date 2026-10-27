# 02 – What the real CR-automation logics actually use in BST

Scope: the two exported BST logics **LL_CR** (export timestamp `20261015_051722`) and **LL_CR_CUSTOM_LOGIC** (`20261015_082415`), plus the lookup tables they reference. The goal is to ground the replacement's requirements in real usage. Read this next to `01_bst_users_guide_capabilities.md`; requirement numbers (#n) refer to its section 6.

Conventions in this document:
- Names of methods, steps, parameters and values are quoted exactly as they appear in the files.
- "Inferred" marks anything that is not written in the files but deduced from them.
- Credentials are shown as `<redacted>`.
- "Live" means reachable from `main_method` through connected links. Both logics contain a lot of dead material (units drawn but not linked, or methods only reachable through unlinked units). Counts are given for live units unless stated otherwise.

---

## 0. Inputs and export format

### 0.1 Files

| Logic | Files | `xml/` | `data/` | Method definitions | Step definitions | Step definitions never placed in any flow |
|---|---|---|---|---|---|---|
| LL_CR | `DESCRIPTION.properties` + 291 xml + 66 graph | 450 KB | 431 KB | 66 (62 `Method` + 4 `MethodDynamicSplitAndJoin`) | 225 | 88 |
| LL_CR_CUSTOM_LOGIC | `DESCRIPTION.properties` + 218 xml + 32 graph | 295 KB | 271 KB | 32 | 186 | 14 |

`DESCRIPTION.properties` holds only `timeStamp`, `description` (`myLogicDescription` for LL_CR, empty for the custom logic) and `logicName`.

The lookup export folder has **152 entries**: 151 tables plus `LOOKUP_TABLES_20260518_173715`, which looks like an older export of email-message rows (inferred). The two logics reference only **5** of these tables (section 8).

### 0.2 How a logic is stored

The jar is a flat set of files. There is one file per unit name.

- **`xml/<UnitName>.xml` with root `<step>`**: a step definition.
  - `<class>` gives the step type, e.g. `IFBSTStep`, `SendToNeBSTStep`.
  - `<input>/<parameter name=… overwrite="yes">` holds the **common** parameters, shared by every placement of the step.
  - Also present: `<activity-name>`, `<activity-duration>`, and `<output all="yes|no">`.
- **`xml/<MethodName>.xml` with root `<method parallel="yes|no">`**: a method definition.
  - `<class>` is `Method` or `MethodDynamicSplitAndJoin`.
  - It has `<mainline-enabled>`, `<catalog-driven>`, `<input>` and `<output all=…>`.
  - The flow is in `<body>`: a list of `<exec id="STEP_…|METHOD_…" name="<unit name>" jump0=".." jump1="..">`.
  - Each `exec` holds the **instance** parameters (`<parameter>`) and step-level output mappings (`<out_parameter>`). The most common output mapping is `ADD_TO_UPPER_LEVEL`, the "add to parent pool" list, e.g. `LIBOUT(%) SFTP(%) SMESSAGE`.
  - `jump1` and `jump0` are **relative offsets** to the next unit:
    - `jump1` is the normal or TRUE successor.
    - `jump0` is the FALSE successor of an If.
    - `0` means no link.
    - Negative values are back-links (loops). Example from `Method_2`: `jump1="-4"`.
- **`data/<MethodName>.graph`**: the diagram, with `<node>` entries (`type`, `label`, `stepID`, x/y position) and `<edge>` entries (`startnodeid`, `endnodeid`). Edge labels give `TRUE`/`FALSE` for If and `<CONDITION_NAME> [n]`/`Default condition` for Branch. This file carries the same links as `jump0`/`jump1` plus the layout.
- Unit names are case-insensitive: the flow label `IfExists` resolves to `IFExists.xml`.
- A step used in many places has one definition file. Common parameters (e.g. `CONDITION = sequals "($OPER1)" "($OPER2)"`) sit in the definition, and per-place values (`OPER1`, `OPER2`) sit in the method's `exec`. The logics rely on this heavily (#8).

The analysis below was produced by parsing all xml and graph files, following links from `main_method`, and merging common with instance parameters.

---

## 1. LL_CR (main logic)

### 1.1 Purpose

LL_CR is the end-to-end "CR automation" order flow. It handles two kinds of request in one logic:

- **Parent order** (`PARENT_ORDER`):
  1. Obtains the CIQ Excel, from GitLab or else by email from the user.
  2. Validates it with **FILECR** `CR_CIQ_PROCESSOR`. This produces the JSON and `CR_LIST`/`CR_COUNT`.
  3. Commits the CIQ to Git.
  4. Fans out **one child request per CR** (SendRequest) and waits for each child.
- **Sub-order** (`SUB_ORDER`, one per CR):
  1. Generates the MOP with **CLICR** `MOP_GENERATION`.
  2. Emails it for approval and waits for a human decision in OrderHub.
  3. Optionally runs a pre-check library (`LL_CR_CUSTOM_LOGIC`).
  4. Checks a business-hours window.
  5. Runs **CLICR** `MOP_EXECUTION` on every node in parallel.
  6. Optionally runs a post-completion library (`LL_CR_CUSTOM_LOGIC`, used for MMTEL refresh).
  7. Generates a report (**FILECR** `GENERATE_REPORT`), zips the logs, and emails the result.

Which branch runs is chosen inside the logic by `Branch_1` on `($INVOKE_METHOD)` (`PARENT_ORDER` / `SUB_ORDER`). Nothing in either logic or any lookup table sets `INVOKE_METHOD`. It must come with the inbound request (inferred).

### 1.2 End-to-end flow (live path, in order)

```
main_method
  MapParameters_OrderLevel   ACTIVITY_NAME=($LINE_1_ACTIVITY_NAME), SUB_ACTIVITY_NAME=($LINE_1_SUB_ACTIVITY_NAME), NODE_TYPE=($LINE_1_NODE_NAME)
  MapParameters              COUNTER_1..3=1, EMAIL_COUNTER=0, CIQ_MISS_EMAIL_COUNTER=0, CIQ_IN_COUNTER=0, IN_COUNTER=0
  Fetch_Lookup_Values        (6 x MT_Generic_Lookup) -> TIMEOUT, MAX_COUNTER, E2E_READ_TO_EMAIL, BASEDIR, GIT_MAX_COUNTER, READEMAIL_WAIT_TIME
  Branch_1 on ($INVOKE_METHOD):  "ParentOrder [1]" -> PARENT_ORDER | "SubOrder [2]" -> SUB_ORDER | Default -> (exit method)

PARENT_ORDER
  LookUp LKT_MANO_GENERIC/E2E_GIT_BRANCH -> GITBRANCH          (NOT_FOUND -> SendResponse _STATUS=9)
  LookUp LKT_MANO_GENERIC/E2E_PREPROCESSING_CIQ_EXT            (NOT_FOUND -> SendResponse 9)
  MT_Get_CIQ   (4 x SendToNe to GIT: GET_NAMESPACE, CREATE_PROJECT, CREATE_DIRECTORY_FILE, DOWNLOAD_FILE)
  if LIBOUT_STATUS==7: loop IDX over SFTP_RESP_FILENAME_<IDX> looking for "<NODE_TYPE>_<SUB_ACTIVITY_NAME>_CIQ.xlsx"
       found -> Method_ReadCIQFiles
       not found / Git failed -> GITCIQ_FILE_FOUND=FALSE -> MT_Create_CIQ_Template (FILECR GENERATE_CR_CIQ_TEMPLATE)
            -> Method_GET_CR_CIQ_Email (EMAIL: "please provide the CIQ")
            -> Method_GET_CR_CIQ_ReadEmail (READEMAIL: wait for reply with attachment)
            -> loop INDEX over LIST_OF_ATTACH_NAMES_<INDEX>, substr match on the CIQ file name
                 match    -> Method_ReadCIQFiles
                 no match -> CIQ_IN_COUNTER+1; if MAX_COUNTER >= CIQ_IN_COUNTER -> email again
                             else Method_EMAIL ("Order Closure Due to Email Attachment Issue")
                                  -> E2E_OH_Fallout (OrderHub) -> RETRY: email again | else SendResponse 9 "CIQ input is missing"
  Method_ReadCIQFiles (FILECR CR_CIQ_PROCESSOR) -> CR_LIST, CR_COUNT, REPORT_EMAIL, MMTEL_REFRESH_EMAIL, JSON_FILE_ABSOLUTE_PATH, REPORT_FILENAME_HTML
  IFRetriggerMail (LIBOUT_RETRIGGER_EMAIL=="TRUE", set when FILECR returns ERROR_TYPE FUNCTIONAL)
       TRUE  -> MT_Create_CIQ_Template -> email the validation report + template again (back into the email loop)
       FALSE -> Method_GetCRID (split CR_LIST on "," into CR_1..CR_n)
              -> RegExp_ChangePrefix (LIST_OF_ATTACH_NAMES_* -> SFTP_RESP_FILENAME_*)
              -> IF_5 GITCIQ_FILE_FOUND=="FALSE": Method_GIT_Commit (GIT PROJECT_CREATE_FILE_PUSH), retried up to GIT_MAX_COUNTER
              -> DynamicSplitAndJoin_1  (DSJ_BRANCH_COUNT=($CR_COUNT))
                   DynamicSplitAndJoinBranch_1 (one per CR):
                     Method_SS_SendReq   SendRequestSubOrder (new request "CFS_SINGLE_CR_AUTOMATION" for CR ($CR_($DSJ_CURRENT_BRANCH_IDENTIFIER)))
                     SEND_RESULT=="OK" ? -> CreateSchedule/UpdateBaseline/ActivityStatus "Sub order (<id>) created …"
                                          -> Method_CheckRequestStatus (OMDB, repeated until STATUS 7, then SUB_REQ_STATUS=="READY")
                                        : -> E2E_OH_SS_Fallout (OrderHub task)
  (end of PARENT_ORDER - no SendResp on the success path)

SUB_ORDER
  MapParameters_38 (LIBOUT_OUT_UPPER_LEVEL_* copies of LINE_1_PS_PARAM_*)
  LKP_VALUES     UserCount = CR_AUTOMATION[<VENDOR>_<NODE_TYPE>]; 9 email texts from LKT_E2E_EMAIL_MESSAGES
  MOPCreation    CLICR MOP_GENERATION -> ACTIVITY_CR_NODE_NAMES, ACTIVITY_CR_EMAIL (fallout+RETRY on failure)
  LIBOUT_STATUS!=7 -> SendResponse 9 ; LIBOUT_MOP_CREATION_FAILED=="TRUE" -> MtActivityCompletionEmail
  ActivityExecutionApproval
     SendMail       EMAIL with the MOP attached (CR_ACTIVITY_APPROVAL text)
     ManualApproval OrderHub task in "($VENDOR)_CR_AUTOMATION_VALIDATION_AND_EXECUTION"; RESOLUTION CONTINUE | ABORT
  LIBOUT_ABORT=="TRUE" -> MtActivityCompletionEmail (abort mail)
  Pre-requisite routing (hard-coded on ($LINE_1_PS_PARAM_SUB_ACTIVITY_NAME)):
     "33_LIC_LOADING_IN_SBC"                    -> LookUp CR_AUTOMATION            -> CUSTOM_LOGIC
     "10005_SAPC_CCPC_PREFERENCE_CHANGE_IN_DSR" -> LookUp CR_AUTOMATION_PREREQUISITE -> CUSTOM_LOGIC
     if CUSTOM_LOGIC=="LL_CR_CUSTOM_LOGIC" -> LogicLibrary (LIBRARY_LOGIC=($LOGIC))
          FLOW_CONTINUE=="TRUE" -> continue ; else -> "Task aborted" -> MtActivityCompletionEmail
     otherwise -> continue
  MT_Get_Node_Count   split ACTIVITY_CR_NODE_NAMES on "," -> NODE_NAME_1..n, NODE_COUNT
  Method_1            Calc idiv/irem: spread NODE_COUNT over UserCount CLI NE instances (Method_2, Method_3 loops)
  CHECK_REQUEST_VALIDITY  LookUp CR_AUTOMATION/TIMEFRAME + 2 Date comparisons -> LIBOUT_REQUEST_VALID
     FALSE -> Method_6 (cut-off email) -> SendResponse _STATUS=7 "INVALID REQUEST AS DEFINED TIME LIMIT REACHED"
     TRUE  -> DynamicSplitAndJoin_3 (DSJ_BRANCH_COUNT=($NODE_COUNT)), branch method ACT_EXEC:
                MT_assign_NEID1     CLI_ANY_NE_ID = CLI_($LINE_1_PS_PARAM_NODE_TYPE)_($UserID)
                GenPeerNode         NODE_n -> PEER_NODE_NAME_n / NODE_NAME_n
                ACTIVITY_EXECUTION  CLICR MOP_EXECUTION for NODE_NAME_($DSJ_CURRENT_BRANCH_IDENTIFIER)
                    failure -> GenReport_Fail (FILECR GENERATE_REPORT, NODE_FAILURE_REPORT=TRUE)
                               -> Method_4 (CLI: cp + zip node log) -> Method_5 (E2ESMTP failure mail)
                MapParameters_Failed, RegExp_Create_OutUpper_Level_parameters (RBMANDATORY_* -> LIBOUT_OUT_UPPER_LEVEL_<branch>_*)
           -> "1051_SUBSCRIBER_PROFILE_CONFIGURATION"? -> LookUp CR_AUTOMATION_PREREQUISITE -> LogicLibrary LL_CR_CUSTOM_LOGIC (MMTEL post-completion)
           -> CUT_DSJ_PREFIX_STEP (RegExp: OUT<n>_X -> X)
           -> GenReport (FILECR GENERATE_REPORT summary) -> MtCliZipFile (CLI zip LOGS) -> MtActivityCompletionEmail
  (end of SUB_ORDER - no SendResp on the success path)
```

Maximum live method nesting is 7: `main_method > SUB_ORDER > DynamicSplitAndJoin_3 > ACT_EXEC > ACTIVITY_EXECUTION > GenReport_Fail > E2E_OH_Fallout`. On top of that comes one library level, where `LL_CR_CUSTOM_LOGIC` itself nests 4 deep.

**Dead in LL_CR.** These methods are reachable only through unlinked nodes, or not at all:
- `Method_Manual_Fallout_Rollback`, `DynamicSplitAndJoin_4`, `ACT_EXEC_ROLLBACK`, `ACTIVITY_EXECUTION_ROLLBACK`, `MULTINODE_MANUAL_FALLOUT`, `SEND_EXECUTION_STATUS`, `READ_EXECUTION_STATUS_MAIL`, `Prepare_SUCCESS_ACTIVITY_EXECUTION`
- `Method_Fallout`, `Method_OHFailure`, `Method_OHFailure1`, `Method_OHFailure_copy1`, `Method_CMDB_OHFailure`
- `Method_SFTP_PUT`, `CLI_CREATE_FOLDER`, `CREATE_ACTIVITY_LOG_ZIP`
- `GENERATE_LIST_PARAMETERS`, `Method_FORMATTIME`, `GenValues`, `Method_GetCREMAIL_ID`, `DynamicSplitAndJoin_2`, `DynamicSplitAndJoinBranch_2`

So **the whole rollback design is drawn but not wired in.** Section 9 covers this.

### 1.3 Step inventory (LL_CR)

"Live placed" counts each node in a live method once.

| Step type (class) | Live placed | Placed incl. unlinked/dead | Notes |
|---|---|---|---|
| MapParameters (`MapParametersBSTStep`) | 236 | 327 | Staging of task parameters, counters, flags |
| If (`IFBSTStep`) | 139 | 190 | All branching and all loops |
| Method call (`Method`) | 76 | 102 | |
| ActivityStatus | 64 | 79 | STARTED/COMPLETED/FAILED around almost every NE call |
| SendToNE (`SendToNeBSTStep`) | 25 | 36 | Step labels used: `SendToNe`, `SendToNeFile`, `SendToNetact`, `Failure_OH*`, `failureOrderhub` |
| CreateSchedule | 21 | 29 | Always `SCHEDULE_MODE=APPEND`, prefix `LP_` or `CMG_` |
| UpdateBaseline | 18 | 19 | Labelled `Notification1` / `Notification_copy1` / `Notification`; **they are not Notification steps** |
| String | 12 | 19 | |
| RegExp | 8 | 9 | |
| LookUp | 7 | 9 | |
| SendResp | 6 | 7 | |
| Dynamic Split and Join (`MethodDynamicSplitAndJoin`) | 2 | 3 (+1 unplaced def) | |
| LogicLibrary | 2 | 4 | Always calls `LL_CR_CUSTOM_LOGIC` |
| Calc | 2 | 2 | `idiv`, `irem` |
| Date | 2 | 2 | Comparison only |
| Branch | 1 | 1 (+1 unplaced `Branch_BNG`) | Only in `main_method` |
| SendRequest | 1 | 1 | |
| **Total live** | **622** | | |

**Not used in LL_CR:**
- **Defined but never placed:** Switch (`Switch_1`, `Switch_2`) and Notification (`Notification_3`, `Notification_25`…`_28`).
- **Never used at all:**
  - Control flow: Split and Join (fixed branches), ParallelSend2, parallel-method flag (other than on DSJ).
  - Task and request steps: SendOriginalTask, Manual (WFC work item).
  - Data steps: CheckParams, NumberRange, Database, PL/SQL, XML_PARSER, EdifactParser.
  - Operations steps: Alarm, Log.
  - Order Management: OrderParameters, OrderEvent, AmendOrder, OrderReschedule, UpdateSchedule.
  - Catalog steps: ServiceSpecification, ServiceDecomposition, Delta*, ErrorDecomposition, ExecutionOrder.

---

## 2. LL_CR_CUSTOM_LOGIC (library logic)

### 2.1 Purpose

This is a library logic called by LL_CR through `LogicLibrary`. It always receives the whole pool (`ADD_ALL_PARAMS_TO_CHILD=TRUE`) and returns `LIBOUT(%)` parameters. It holds the per-CR "extras" that the generic flow cannot do. It has **no SendResp**. It contains three hard-coded variants, selected by `($LINE_1_PS_PARAM_SUB_ACTIVITY_NAME)`:

| Variant | Called from LL_CR | What it does |
|---|---|---|
| `1051_SUBSCRIBER_PROFILE_CONFIGURATION` | After `DynamicSplitAndJoin_3` (post-completion) | Splits `($OUT1_LIBOUT_SUCCESS_IMSIS)` into `IMSI1..n`. For each IMSI: emails "Run MMTEL `<IMSI>`" to `($LINE_1_PS_PARAM_MMTEL_REFRESH_EMAIL)`, then reads the reply (READEMAIL) and classifies it (`SUCCESSFUL` / `FAIL` / invalid, up to 3 tries). It builds `MMTEL_SUCCESS`, `MMTEL_FAILURE` and `INTERNAL_IMSI_BODY`. Finally it sends CLICR `MOP_EXECUTION` with `SUB_ACTIVITY_NAME=<sub>_POSTCOMPLETION` to `NE_ID=($OUT1_CLI_ANY_NE_ID)`. |
| `10005_SAPC_CCPC_PREFERENCE_CHANGE_IN_DSR` | Before execution (pre-requisite) | Two stages × N nodes. For each: CLICR `MOP_EXECUTION` `<sub>_PRECHECK_STAGE<ACTIVITY_IDX>` on `NE_ID=CLI_DSR_1`, then CLI zip of the per-node HTML report, then an email to the user, then reading the reply `CONTINUE`/`ABORT`. After 3 invalid or missing replies it escalates to an OrderHub task. |
| anything else (in practice `33_LIC_LOADING_IN_SBC`) | Before execution | Loops over the nodes. For each: CLICR `MOP_EXECUTION` `<sub>_PRECHECK` on `NE_ID=CLI_SBC_1` with `LICENSE_FILE_PATH=/mnt/shared_data/($MOP1_PARENT_REQ_ID)`. Then it zips `PREREQUISITE`, sends one email with the report, reads the reply, and escalates as above. |

### 2.2 Flow outline

```
main_method
  Method_1_copy1   split ACTIVITY_CR_NODE_NAMES -> NODE_NAME1..n, NODE_COUNT; FLOW_COUNT = (($NODE_COUNT)+($NODE_COUNT)) or 2
  MapParameters    ACTIVITY_IDX=1, NODE_IDX=1, INDEX=1, NODE_NAME_COUNT=1
  IF sub == 1051…  -> Method_2 (IMSI loop: Method_1 split, Method_GET_CR_CIQ_Email_copy2, Method_GET_CR_CIQ_ReadEmail_copy1)
                      -> IFExists IMSI1 -> CreateSchedule/UpdateBaseline/ActivityStatus "ACTIVITY_EXECUTION"
                      -> SendToNetact (CLICR MOP_EXECUTION _POSTCOMPLETION)
  IF sub == 10005… -> MapParameters_1 (CR1_* for DSR stage) -> SendToNe_1 -> per-node email/read loop (below)
  else             -> MapParameters_1 (CR1_* for SBC) -> SendToNe_1 -> IF_1 ($INDEX) < ($NODE_COUNT) loop over nodes
  MtCliZipFile (CLI zip) -> Method_GET_CR_CIQ_Email (EMAIL, delayed by TASK_START_TIME) -> Method_GET_CR_CIQ_ReadEmail (READEMAIL)
  reply handling: RETRY_FLAG=="YES" -> email again
                  EMAIL_BODY_1 contains "CONTINUE" -> FLOW_CONTINUE=TRUE
                  contains "ABORT" -> ABORT=TRUE, FLOW_CONTINUE=FALSE
                  invalid -> INVALID_RESPONSE_COUNT+1 (until 3) -> email again
                  no reply -> INDEX+1 (until 3) -> email again
                  limit reached -> Method_GET_CR_CIQ_Email_copy1 (escalation mail) -> ManualTask (OrderHub CR_AUTOMATION_PRECHECK)
  DSR only: IF_9 / IF_7 / IF_Equals_copy3 / IF_10 advance NODE_IDX, ACTIVITY_IDX (<=2), NODE_NAME_COUNT (<= FLOW_COUNT) and go back to MapParameters_1
```

Dead in the custom logic: `ManualApproval`, `Method_OHFailure_copy1`, `E2E_OH_Fallout_copy3`, `GenReport`, `MT_Generic_Lookup_copy3/4/5/7`, and 3 unlinked `LogicLibrary` nodes.

### 2.3 Step inventory (LL_CR_CUSTOM_LOGIC)

| Step type | Live placed | Placed incl. dead |
|---|---|---|
| MapParameters | 182 | 211 |
| If | 111 | 126 |
| Method call | 30 | 38 |
| ActivityStatus | 22 | 24 |
| SendToNE | 20 | 23 |
| String | 20 | 24 |
| CreateSchedule | 8 | 9 |
| UpdateBaseline | 8 | 9 |
| LookUp | 7 | 7 |
| RegExp | 3 | 3 |
| LogicLibrary | 0 | 3 (unlinked) |
| **Total live** | **411** | |

**Not used in the custom logic:** Branch, Switch, Calc, Date, SendResp, SendRequest, DSJ, S&J, ParallelSend2, plus everything listed as unused for LL_CR.

---

## 3. Every task sent to an NE

### 3.1 How tasks are built and read back (common pattern)

All 45 live SendToNE steps across the two logics use the **same pattern**:

1. **Staging.** A MapParameters step creates the task's parameters under a private prefix, e.g. `MOP1_NE_TYPE=CLICR`, `MOP1_ACTION=MOP_GENERATION`.
2. **Selection.** The SendToNE step uses `ADD_ALL_PARAMETERS=FALSE` with `CUT_PREFIX=<prefix>`. Only parameters with that prefix go into the task, with the prefix removed.
   - `CUT_PREFIX` sometimes lists several prefixes, e.g. `TASK_ LP_`, `OMDB_ LP_`, `NCOM_ CLI_ANY_ CLI_ANY_CMM_`, `READEMAIL_ CMG_`.
   - `PARAM_LIST`, `PARAO_LIST` and `STARTS_WITH_LIST` appear **only in dead steps** (`Failure_OH*`, `failureOrderhub`).
3. **Response.** Every step sets `GET_ALL_RESP_PARAMS=TRUE`, `CUT_RESP_PREFIX=@NEW@` and `IGNORE_RESP_PREFIX=@REQ@`.
   - Some also set `ADD_RESP_PREFIX`: `SFTP_RESP_`, `OHR_`, `LIBOUT_`, `FTP_RESP_` or `PEER_`.
   - `OUTPUT_PARAMETER_LIST` is never used.
4. **Branching.** The logics **never read `STATUS_RESULT` or `SMESSAGE_RESULT`**. They branch on the NEI-returned `STATUS` parameter, or on the prefixed copy: `($LIBOUT_STATUS)`, `($SFTP_RESP_STATUS)`, `($FTP_RESP_STATUS)`.
   - The test is always `sequals … "7"`, or `sequals … "($CONF_STATUS_READY)"`. `CONF_STATUS_READY` is `7` in lookup `CONF_PARAMS`. Neither logic reads `CONF_PARAMS`, so the value must already be in the pool (inferred).
   - Only the values 7 and 9 are used. 5, 6 and 8 never appear.
5. **Disabling parameters.** A `#` prefix is used to switch a staged parameter off, e.g. `#READ_JSON_FILE_LOCATION`, `#SMTP_DOMAIN`, `#READEMAIL_TASK_START_TIME`, `TASK_1_#REQUEST_TRACE`. The renamed parameter no longer matches `CUT_PREFIX`, so it is not sent (inferred).
6. **Routing.** Routing keys are plain task parameters: `NE_TYPE`, sometimes `NE_ID`, `REQ_TYPE`, `REQ_OBJ`, `IL_TASK_GROUP`. CLICR and FILECR tasks set both `NE_ID` and lower-case `ne_id` (e.g. `MOP1_NE_ID=CLICR` and `MOP1_ne_id=CLICR`).
   - `task_type` is never set.
   - Several tasks set **no NE_ID**: GIT, EMAIL, E2ESMTP, READEMAIL and OMDB, plus some ORDERHUB and CLI tasks. Routing then presumably relies on `NE_TYPE` (inferred).
7. **OM activity wrapper.** Almost every NE call is preceded by MapParameters (`LP_*` or `CMG_*`: `ACTIVITY_NAME`, `NAME`, `ITEM_TYPE=REMOTE`, `TRANSACTION_NAME=Execute`, `TRANSACTION_DURATION=28800|36000`), then `CreateSchedule` (APPEND), `UpdateBaseline` and `ActivityStatus STARTED`. It is followed by `ActivityStatus COMPLETED` or `FAILED`. In effect, each NE call is appended to the OM Gantt/mainline at runtime.

### 3.2 LL_CR – live NE tasks

| # | Method / step | NE_TYPE / NE_ID | ACTION | Notable task parameters (after CUT_PREFIX) | Response handling and next branch |
|---|---|---|---|---|---|
| 1 | `MT_Get_CIQ` / `SendToNe` (`GIT1_`) | GIT / – | `ACTION_NAME=GET_NAMESPACE` | `REQ_TYPE=1`, `REQ_OBJ=1`, `IL_TASK_GROUP=GET_NAMESPACE` | `($STATUS)=="7"`? Else `LIBOUT_STATUS=9`, "Failed to fetch Namespace details for the GIT", ActivityStatus FAILED; the caller falls back to the CIQ email path |
| 2 | `MT_Get_CIQ` / `SendToNe` (`GIT1_`) | GIT / – | `ACTION_NAME=CREATE_PROJECT` | `PROJECT_PATH=($ORDER_NO)`, `NAMESPACE_ID=($NAMESPACE_ID)`, `PROJECT_NAME=CR_AUTOMATION`, `PROJ_DESC=CR_AUTOMATION_($ORDER_NO)` | STATUS 7 → next activity; else fail |
| 3 | `MT_Get_CIQ` / `SendToNe` (`GIT1_FILE_`) | GIT / – | `FILE_ACTION=CREATE_DIRECTORY_FILE` | `FILE_DIRECTORY`, `FILE_COMMIT_MESSAGE=Create folder ($ORDER_NO)`, `FILE_CONTENT`, `PROJECT_ID` | STATUS 7 → `LINE_1_PS_PARAM_PROJECT_ID=($PROJECT_ID)`, then `($PROJECT_EXISTS)=="TRUE"` |
| 4 | `MT_Get_CIQ` / `SendToNe` (`GIT_CIQ_`) | GIT / – | `ACTION_NAME=DOWNLOAD_FILE` | `FILENAME=*`, `BRANCH=($GITBRANCH)`, `OUTPUT_FILE_PATH=/mnt/shared_data/($REQ_ID)/` | `ADD_RESP_PREFIX=SFTP_RESP_`; branch on `($SFTP_RESP_STATUS)=="7"`; files arrive as `SFTP_RESP_FILENAME_<n>` |
| 5 | `Method_ReadCIQFiles` / `SendToNe` (`READ_`) | **FILECR / FILECR** | **`CR_CIQ_PROCESSOR`** | `CIQ_FILE_NAME=/mnt/shared_data/($REQ_ID)/…_CIQ.xlsx`, `HEADER_ROW_NO=1`, `REPORT_TYPE=HTML`, `OUTPUT_FILE_LOCATION`, `NODE_TYPE`, `NODE_VERSION`, `ACTIVITY_NAME`, `SUB_ACTIVITY_NAME`, `IL_TASK_GROUP=ReadCIQ` | STATUS 7 → `JSON_FILE_ABSOLUTE_PATH=/mnt/shared_data/($REQ_ID)/($JSON_FILE_NAME)`; returned to the parent: `CR_COUNT CR_LIST MMTEL_REFRESH_EMAIL REPORT_EMAIL REPORT_FILENAME_HTML ORDER(%) LINE_1_PS_PARAM(%) LIBOUT(%)`. Failure → `ERROR_TYPE=="FUNCTIONAL"` plus `REPORT_FILENAME_HTML` exists → re-mail the template and report; otherwise OrderHub fallout |
| 6 | `MT_Create_CIQ_Template` / `SendToNe` (`GEN_`) | FILECR / FILECR | **`GENERATE_CR_CIQ_TEMPLATE`** | `CIQ_FILE_LOCATION=/mnt/shared_data/($REQ_ID)/CIQTEMPLATE`, `FILENAME=($NODE_TYPE)_($SUB_ACTIVITY_NAME)_CIQ.xlsx` | STATUS 7 → flags; else fallout (RETRY loops back) |
| 7 | `Method_GIT_Commit` / `SendToNe` (`GIT_CMT_`) | GIT / – | `ACTION_NAME=PROJECT_CREATE_FILE_PUSH` | `LOCAL_PROJECT_PATH=/mnt/shared_data/($REQ_ID)`, `BRANCH`, `COMMIT_MESSAGE=… on ($TIMESTAMP)` | STATUS 7 → `LIBOUT_STATUS=7`; else 9, and the caller retries while `GIT_MAX_COUNTER >= GIT_RETRY_COUNTER` |
| 8 | `Method_GET_CR_CIQ_Email` / `SendToNeFile` (`SMTP_`) | EMAIL / – | – | `MESSAGE_FNAME=CR_AUTOMATION_EMAIL_CIQ`, `SUBJECT`, `TO_EMAIL`, `MESSAGE`, `USER_ACTION`, `FILENAME`, `DOWNLOAD_PATH`, `CIQ_MAIL_SUBJECT` | `ADD_RESP_PREFIX=LIBOUT_`; `($LIBOUT_STATUS)=="7"`, else fallout with RETRY |
| 9 | `Method_GET_CR_CIQ_ReadEmail` / `SendToNe` (`READEMAIL_`) | READEMAIL / – | – (`REQ_TYPE=4`) | `SUBJECT=Re: MANO E2E - …`, `FROM_EMAIL`, `TO_EMAIL`, `FOLDER_PATH`, `ACTIVITY_TIMEOUT=($TIMEOUT)`, `READEMAIL WAIT_TIME` (name contains a space), `DIR_CREATE=TRUE`, `GRC_OVERRIDE_REQUIRED=TRUE` | STATUS 7 → `SUBJECT_COUNT+1`, `EMAIL_BODY_1` upper-cased, attachments as `LIST_OF_ATTACH_NAMES_<n>`; else fallout (RETRY re-reads) |
| 10 | `Method_EMAIL` / `SendToNe` (`EMAIL_`) | EMAIL / – | – | Closure email: "Order Closure Due to Email Attachment Issue" | STATUS 7 → `LIBOUT_STATUS=($STATUS)` (default 7); else fallout |
| 11 | `E2E_OH_Fallout` / `SendToNe` (`TASK_`) | **ORDERHUB / orderhub1** | `CREATE_TASK` | `OH_TASK_TYPE` = `OH_WORK_QUEUE` = `CR_AUTOMATION_FALLOUT_MANAGEMENT`, `ORDER_NO`, `CR_NAME`, `CR_NUMBER`, `INFO_MESSAGE=($ERROR_MESSAGE)`, `NODE_TYPE`, `ACTIVITY_NAME`, `SUB_ACTIVITY_NAME`, `IL_TASK_GROUP=Manual E2E Fallout - …` | `ADD_RESP_PREFIX=OHR_`; `OHR_RESOLUTION` (or the caller's `RESOLUTION`) ∈ `IGNORE`/`SKIP`/`CONTINUE` → OK; `ABORT` → `LIBOUT_ABORT=TRUE`; `RETRY` → `RETRY_FLAG=YES`; anything else → a new task is created |
| 12 | `MT_Generic_Lookup` / `SendToNe` (`TASK_`) | ORDERHUB / – | `CREATE_TASK` | `OH_TASK_TYPE`=`OH_WORK_QUEUE`=`($FALLOUT_QUEUE)`, `ERROR_MESSAGE` "Lookup key … not found" | Only if a lookup key is missing. `STATUS 7` and `RESOLUTION=="RETRY"` → re-run the LookUp (after an admin fixes the table) |
| 13 | `E2E_OH_SS_Fallout` / `SendToNe` (`TASK_`) | ORDERHUB / orderhub1 | `CREATE_TASK` | as #11 (inside the DSJ_1 branch) | Response not evaluated |
| 14 | `Method_CheckRequestStatus` / `SendToNe` (`OMDB_ LP_`) | **OMDB** / – | – | `SUB_ORDER_REQ_ID=($REQ_ID_1)` (the child request ID from SendRequest) | STATUS 7 → `($SUB_REQ_STATUS)=="READY"` ? `LIBOUT_STATUS=7` : 9. **STATUS not 7 → loops straight back and resends, with no counter** |
| 15 | `MOPCreation` / `SendToNeFile` (`MOP1_`) | **CLICR / CLICR** | **`MOP_GENERATION`** | `JSON_FILE_NAME=($LINE_1_JSON_FILE_PATH)`, `OUTPUT_FILE_LOCATION=/mnt/shared_data/($REQ_ID)/MOP`, `OUTPUT_JSON_REPORT_LOCATION=…/REPORT`, `NODE_TYPE`, `NODE_VERSION`, `ACTIVITY_NAME`, `SUB_ACTIVITY_NAME`, `CR_NAME`, `PARENT_REQ_ID`, `CHILD_REQ_ID=($REQ_ID)`, `PROJECT_ID` | STATUS 7 → copies `ACTIVITY_CR_NODE_NAMES` and `ACTIVITY_CR_EMAIL` (NEI response, inferred) to `LIBOUT_OUT_UPPER_LEVEL_*` and `RBMANDATORY_*`; failure → fallout; RETRY re-runs, else `LIBOUT_MOP_CREATION_FAILED=TRUE` |
| 16 | `SendMail` / `SendToNeFile` (`SMTP_`) | EMAIL / – | – | Approval email: `TO_EMAIL=($ACTIVITY_CR_EMAIL)`, `CC_EMAIL`, `FILENAME=($ACTIVITY_CR_MOP)`, `DOWNLOAD_PATH=($MOP_FILE_PATH)`, `MESSAGE` from `CR_ACTIVITY_APPROVAL` | `($LIBOUT_STATUS)==($CONF_STATUS_READY)`; else fallout; RETRY → resend; ABORT → skip flag |
| 17 | `ManualApproval` / `SendToNe` (`TASK_ LP_`) | ORDERHUB / – | `CREATE_TASK` | `OH_TASK_TYPE=CR_AUTOMATION_VALIDATION_AND_EXECUTION`, `OH_WORK_QUEUE=($VENDOR)_CR_AUTOMATION_VALIDATION_AND_EXECUTION`, `NODE_NAMES=($ACTIVITY_CR_NODE_NAMES)` | `($RESOLUTION)`: `CONTINUE` → `LIBOUT_STATUS=7`; `ABORT` → `LIBOUT_SKIP_TO_MT_ORDER_COMPLETION_FLAG=TRUE`. STATUS not checked |
| 18 | `ACTIVITY_EXECUTION` / `SendToNetact` (`NCOM_ CLI_ANY_ CLI_ANY_CMM_`) | **CLICR** / `CLI_<NODE_TYPE>_<UserID>` (set by `MT_assign_NEID1`) | **`MOP_EXECUTION`** | `NODE_NAME=($NODE_NAME_($DSJ_CURRENT_BRANCH_IDENTIFIER))`, `INPUT_JSON_FILE_NAME`, `OUTPUT_LOGS_FILE_LOCATION=…/LOGS`, `OUTPUT_JSON_REPORT_LOCATION=…/REPORT`, `EMAIL_BODY_IMSI`, `CR_NAME`, `PARENT_REQ_ID`, `CHILD_REQ_ID`, `PROJECT_ID`, `NODE_VERSION`, `IL_TASK_GROUP=ACTIVITY_EXECUTION_<node>` | `ADD_RESP_PREFIX=LIBOUT_`. About 55 per-node copies of the rollback data (`CREATED_IPS_`, `DELETED_REALMS_`, `ROLLBACK_REQUIRED_`, `LAST_ACTIVITY_EXECUTED_`, … each also as `RBMANDATORY_*`). `($LIBOUT_STATUS)=="7"` → COMPLETED; else FAILED → GenReport_Fail → Method_4 → Method_5. **Both paths set `LIBOUT_STATUS=7`**, so a node failure never fails the order |
| 19 | `GenReport_Fail` / `SendToNeFile` (`GEN_`) | FILECR / FILECR | **`GENERATE_REPORT`** | `NODE_FAILURE_REPORT=TRUE`, `NODE_NAME`, `OUTPUT_REPORT_NAME=…_EXECUTON_FAILURE_REPORT.html`, `FAILURE_REPORT_LOCATION`, `OUTPUT_REPORT_LOCATION`, `REQ_ID`, `CR_NAME` | STATUS 7, else fallout; `DEL_PARAM_LIST=NODE_FAILURE_REPORT GEN_NODE_FAILURE_REPORT` afterwards |
| 20 | `Method_4` / `SendToNeFile` (`CLI_`) | CLI / cliUnix | – (`TEMPLATE=CFX/GENERIC`, `REQ_TYPE=2`, `REQ_OBJ=2`) | `COMMAND=cp -r ($SOURCE_FILE_LOCATION) ($DESTINATION_FILE_LOCATION) && cd … && zip -r ($ZIP_FILE_NAME) ($ZIP_FILE_PATH)` | `LIBOUT_STATUS==CONF_STATUS_READY`; else fallout |
| 21 | `Method_5` / `SendToNeFile` (`SMTP_`) | E2ESMTP / – | – | Per-node failure email with the zip attached | STATUS 7, else fallout |
| 22 | `GenReport` / `SendToNeFile` (`GEN_`) | FILECR / FILECR | `GENERATE_REPORT` | Summary report `…_EXECUTON_REPORT.html` | STATUS 7, else fallout |
| 23 | `MtCliZipFile` / `SendToNeFile` (`CLI_`) | CLI / cliUnix | – | `COMMAND=cp -r … && zip -r LOGS.zip LOGS` | As #20 |
| 24 | `MtActivityCompletionEmail` / `SendToNeFile` (`SMTP_`) | EMAIL (abort / MOP-fail text) or E2ESMTP (completion) | – | `FILENAME=LOGS.zip,…_SUMMARY_REPORT.html`; message chosen from `ABORT_MESSAGE`, `MOP_FAIL_MESSAGE`, `EMAIL_COMPLETION_MESSAGE`, `CR_CUSTOM_ABORT`, `CR_EMAIL_EXCEEDED` | STATUS 7, else fallout |
| 25 | `Method_6` / `SendToNeFile` (`SMTP_`) | E2ESMTP / – | – | Cut-off-time email (`EMAIL_CUTOFF_TIME_MESSAGE`) | STATUS 7, else fallout |

Also **SendRequest** in `Method_SS_SendReq` (`SendRequestSubOrder`, inside the DSJ_1 branch):
- `USED_PROTOCOL=SAS3`, `USED_PRIORITY=1`, `USED_REQ_USER=user1`, `REQ_PAR_IL_REQ_USER=user1`, `USED_ORDERNO=($ORDER_NO)`, `BYPASS_LOCKING=FALSE`, `NUM_OF_TASKS=1`.
- `TASK_NETYPE_1=ORDER`, `TASK_NEID_1=` (empty), `TASK_TYPE_1=create`.
- Task parameters `TASK_1_*`, including `LINE_1_PS_NAME=CFS_SINGLE_CR_AUTOMATION`, `LINE_1_PS_PARAM_CR_NUMBER=($CR_($DSJ_CURRENT_BRANCH_IDENTIFIER))`, `LINE_1_JSON_FILE_PATH=($JSON_FILE_ABSOLUTE_PATH)`, `MAIN_ORDER_NO=($REQ_ID)`, `ORDER_CR_REF_ID=($REQ_ID)_CR($DSJ_CURRENT_BRANCH_IDENTIFIER)`, `IL_REQ_GROUP=CR_CONFIGURATION`, `REPORT_EMAIL`, `MMTEL_REFRESH_EMAIL`, `PROJECT_ID`, `VENDOR`, `VERSION`.
- The step branches on `($SEND_RESULT)=="OK"` and keeps `REQ_ID_1=($SEND_REQUEST_ID)`.

### 3.3 LL_CR_CUSTOM_LOGIC – live NE tasks

| Method / step | NE_TYPE / NE_ID | ACTION | Notable parameters | Response handling |
|---|---|---|---|---|
| `main_method` / `SendToNe_1` (`CR1_`), SBC variant | CLICR / `CLI_SBC_1` | `MOP_EXECUTION` | `SUB_ACTIVITY_NAME=<sub>_PRECHECK`, `ACTIVITY_NAME=<act>_PRECHECK`, `NODE_NAME=($NODE_NAME($INDEX))`, `LICENSE_FILE_PATH=/mnt/shared_data/($MOP1_PARENT_REQ_ID)`, `INPUT_JSON_FILE_NAME`, `IL_TASK_GROUP=CR_AUTOMATION_PREREQUISITE`, `TYPE=($TRANSACTION_NAME)` | No immediate status check; it loops `($INDEX) < ($NODE_COUNT)`. Later `($STATUS)=="9"` picks the "precheck failed" email text |
| `main_method` / `SendToNe_1` (`CR1_`), DSR variant | CLICR / `CLI_DSR_1` | `MOP_EXECUTION` | `SUB_ACTIVITY_NAME=<sub>_PRECHECK_STAGE($ACTIVITY_IDX)`, `NODE_NAME=($NODE_NAME($NODE_IDX))` | As above, once per node and stage |
| `main_method` / `SendToNetact` (`NCOM_ CLI_ANY_ CLI_ANY_CMM_`) | CLICR / `($OUT1_CLI_ANY_NE_ID)` | `MOP_EXECUTION` | `SUB_ACTIVITY_NAME=<sub>_POSTCOMPLETION`, **`MMTEL_SUCCESS`, `MMTEL_FAILURE`, `FAILURE_IMSIS`** (set to `null` when `OUT1_LIBOUT_FAILURE_IMSIS` is absent), **`EMAIL_BODY_IMSI=($INTERNAL_IMSI_BODY)`**, `OUTPUT_LOGS_FILE_LOCATION=…/LOGS/MMTEL_Refresh` | Response not evaluated (last step) |
| `MtCliZipFile` / `SendToNeFile` (`CLI_`) | CLI / cliUnix | – | `COMMAND=cd /mnt/shared_data/($REQ_ID) && zip -r PREREQUISITE.zip PREREQUISITE` (DSR: a per-node, per-stage zip) | `LIBOUT_STATUS==CONF_STATUS_READY`, else fallout |
| `Method_GET_CR_CIQ_Email` (and `_copy1`, `_copy2`) / `SendToNeFile*` (`SMTP_`) | EMAIL / – | – | Precheck mail: `FILENAME=PREREQUISITE.zip` or `PREREQUISITE_STAGE<n>.zip`. Escalation mail: `CR_AUTO_CUSTOM_ORDERHUB` text. MMTEL mail: `SUBJECT=Run MMTEL ($IMSI_NUMBER)` to `($LINE_1_PS_PARAM_MMTEL_REFRESH_EMAIL)`. **`TASK_START_TIME`** = now + `TASK_WAIT_TIME` (120) on resends | `LIBOUT_STATUS=="7"`, else fallout and RETRY |
| `Method_GET_CR_CIQ_ReadEmail` (and `_copy1`) / `SendToNe*` (`READEMAIL_`) | READEMAIL / – | – (`REQ_TYPE=4`) | `SUBJECT=Re: …` or `Re: Run MMTEL ($IMSI_NUMBER)`, `FROM_EMAIL`, `ACTIVITY_TIMEOUT=($TIMEOUT)`, `READEMAIL_WAIT_TIME` | STATUS 7 → `EMAIL_BODY_1`. On failure, `MAX_COUNTER > EMAIL_FAIL_COUNTER` → `READ_EMAIL_FAIL=TRUE`; else fallout |
| `ManualTask` / `SendToNe_copy1` (`TASK_ LP_`) | ORDERHUB / – | `CREATE_TASK` | `OH_TASK_TYPE`=`OH_WORK_QUEUE`=`CR_AUTOMATION_PRECHECK`, `MESSAGE=INVALID_RESPONSE`, `NODE_NAMES` | `RESOLUTION` `CONTINUE` → `FLOW_CONTINUE=TRUE`; `ABORT` → `ABORT=TRUE`, `FLOW_CONTINUE=FALSE` |
| `E2E_OH_Fallout*` / `SendToNe*` (`TASK_`) | ORDERHUB / orderhub1 | `CREATE_TASK` | as LL_CR #11 | as LL_CR #11, plus `FLOW_CONTINUE` |
| `MT_Generic_Lookup*` / `SendToNe*` (`TASK_`) | ORDERHUB / – | `CREATE_TASK` | as LL_CR #12 | as LL_CR #12 |

### 3.4 NE types actually called

| NE_TYPE | Purpose in these logics | Live calls (LL_CR / custom) |
|---|---|---|
| **CLICR** | `MOP_GENERATION`, `MOP_EXECUTION` (execution, `_PRECHECK`, `_PRECHECK_STAGE<n>`, `_POSTCOMPLETION`) | 2 / 3 |
| **FILECR** | `CR_CIQ_PROCESSOR`, `GENERATE_CR_CIQ_TEMPLATE`, `GENERATE_REPORT` (summary and per-node failure) | 4 / 0 |
| **ORDERHUB** | Human tasks: fallout, approval and pre-check escalation (`CREATE_TASK`, decision returned as `RESOLUTION`) | 4 / 5 |
| **EMAIL**, **E2ESMTP** | Outgoing email with attachments | 7 / 3 |
| **READEMAIL** | Wait for and read the user's reply email (body and attachments) | 1 / 2 |
| **GIT** | GitLab namespace, project, folder, download, commit | 5 / 0 |
| **OMDB** | Poll the status of a child request | 1 / 0 |
| **CLI** (`cliUnix`, `CFX/GENERIC`) | Run shell commands (`cp`, `zip`, `mkdir` in dead code) on the shared filesystem | 2 / 1 |

The sample parameters in the brief all appear except `ZZZPARALLEL_BRANCH_IDZZZ` and `CIQ_FILE_NAME` as a top-level pool name: `CIQ_FILE_NAME` is only ever staged as `READ_CIQ_FILE_NAME`. `ZZZPARALLEL_BRANCH_IDZZZ` is not in either logic. It is presumably engine-generated inside DSJ branches (inferred).

---

## 4. Loops

There is no loop step. Every loop is an If with a back-link plus a MapParameters counter `(($X)+1)`. About 30 live loops exist across the two logics.

| What is iterated | Where | Construction |
|---|---|---|
| Files downloaded from Git (`SFTP_RESP_FILENAME_<IDX>`) | `PARENT_ORDER` | `IDX=1`; `paramsexist "SFTP_RESP_FILENAME_($IDX)"`; `IDX=(($IDX)+1)` back to the If |
| Email attachments (`LIST_OF_ATTACH_NAMES_<INDEX>`, `<INDX>`) | `PARENT_ORDER`, `Method_ReadCIQFiles` | Same pattern; `substr` match on the CIQ file name |
| CR list (`CR_LIST` "a,b,c" → `CR_1..n`) | `Method_GetCRID` | `substr "($CR_LIST)" ","`, then String `indexOf`/`substring`/`length`, `INDEX+1`, back-link |
| Node list (`ACTIVITY_CR_NODE_NAMES` → `NODE_NAME_1..n`, `NODE_COUNT`) | `MT_Get_Node_Count`; custom `Method_1_copy1` | Same comma-split loop |
| IMSI list (`OUT1_LIBOUT_SUCCESS_IMSIS` → `IMSI1..n`) | custom `Method_1` | Same comma-split loop |
| Per-IMSI email / reply / classify | custom `Method_2` | `paramsexist "IMSI($IMSI_IDX)"`, `IMSI_IDX+1`, back to the If |
| Per-node pre-check send | custom `main_method` | `less "($INDEX)" "($NODE_COUNT)"` → `INDEX+1` → back to `MapParameters_1` |
| Stage × node (DSR) | custom `main_method` | `NODE_IDX`, `ACTIVITY_IDX` (`lesseq … 2`), `NODE_NAME_COUNT` (`lesseq … ($FLOW_COUNT)`) |
| Distribute nodes over NE instances | `Method_2`, `Method_3`, `MT_assign_NEID1` (LL_CR) | `lesseq "($i)" "($UserCount)"`; `User($i)AssignedNodeCount`; `User($j)MaxNodeCount`; `UserID+1` until `DSJ_CURRENT_BRANCH_IDENTIFIER <= User(UserID)MaxNodeCount` |
| Peer nodes (`NODE_<INDEX>`) | `GenPeerNode` | `paramsexist "NODE_($INDEX)"` |
| Retry: Git commit | `PARENT_ORDER` | `GIT_RETRY_COUNTER+1`; `greatereq ($GIT_MAX_COUNTER) ($GIT_RETRY_COUNTER)` (`GIT_COMMIT_RETRY=3`) |
| Retry: CIQ email reminders | `PARENT_ORDER` | `CIQ_IN_COUNTER+1`; `greatereq ($MAX_COUNTER) ($CIQ_IN_COUNTER)` (`MAX_COUNTER=2` from `MANO_ACTIVITY_CONFIGURATION`) |
| Retry: invalid or missing email reply | custom `main_method`, `Method_2` | `INVALID_RESPONSE_COUNT` / `INDEX` `equals … 3` |
| Retry: human `RETRY` decision | ~15 methods | After `E2E_OH_Fallout`, `RETRY_FLAG=="YES"` or `RESOLUTION=="RETRY"` → back-link to the start of the activity. **No counter: an operator can retry forever** |
| Re-create the fallout task until the answer is recognised | `E2E_OH_Fallout` | Last If FALSE → back to the first MapParameters. **Unbounded** |
| Poll a child request | `Method_CheckRequestStatus` | STATUS not 7 → back to the start. **Unbounded, no delay in the logic** (the wait is presumably inside the OMDB NEI, inferred) |

---

## 5. Parallelism

- **Dynamic Split and Join is the only parallel construct used.** It is live twice:
  - `DynamicSplitAndJoin_1` (in `PARENT_ORDER`): `DSJ_BRANCH_COUNT=($CR_COUNT)`, branch `DynamicSplitAndJoinBranch_1`. It fans out **one child request per CR** (SendRequest), then waits for each child by polling OMDB.
  - `DynamicSplitAndJoin_3` (in `SUB_ORDER`): `DSJ_BRANCH_COUNT=($NODE_COUNT)`, branch `ACT_EXEC`. It fans out **one CLICR `MOP_EXECUTION` per network node**.
  - Both use `DSJ_START_INDEX=1`, `DSJ_IN_PREFIX=IN`, `DSJ_OUT_PREFIX=OUT`, `DSJ_PREFIX_SEPARATOR=_` and `DSJ_BRANCH_SUMMARY_ACTIVITY_PARAM=BRANCH_SUMMARY_ACTIVITY_NAME`. The DSJ methods have `parallel="yes"` and `output all="yes"`.
  - A third, `DynamicSplitAndJoin_4` (rollback per node, branch `ACT_EXEC_ROLLBACK`), exists but is unlinked. `DynamicSplitAndJoin_2` is an empty stub.
- **Branch input.** The `IN<n>_` prefix mechanism is **not** used. Branches read shared, non-prefixed parameters indexed by the branch ID, e.g. `($CR_($DSJ_CURRENT_BRANCH_IDENTIFIER))` and `($NODE_NAME_($DSJ_CURRENT_BRANCH_IDENTIFIER))`. They also build their outputs with the branch ID or node name in the parameter *name*, e.g. `PEER_NODE_STATUS_($DSJ_CURRENT_BRANCH_IDENTIFIER)` and `CREATED_IPS_($NODE_NAME_(…))`.
- **Join.** Outputs return as `OUT<n>_<name>`.
  - After DSJ_3, `CUT_DSJ_PREFIX_STEP` (RegExp `REGEXP1 = *,(OUT[0-9]*_)(.*)` → `(\2)`) strips the branch prefix. Collisions resolve to whichever branch is written last (inferred); the node-name suffixes avoid collisions for the important data.
  - The custom logic reads branch 1 directly: `($OUT1_CLI_ANY_NE_ID)`, `($OUT1_LIBOUT_SUCCESS_IMSIS)`. The 1051 MMTEL step therefore assumes one node (inferred).
  - DSJ_1 outputs are not used after the join.
- **Load balancing across NE instances.** `CR_AUTOMATION[<Vendor>_<NodeType>]` gives `UserCount`, the number of CLICR NE instances, e.g. `Nokia_SBC=1`. `Method_1/2/3` and `MT_assign_NEID1` spread the DSJ branches over `CLI_<NODE_TYPE>_1..UserCount`. This is a hand-built concurrency or affinity limit (inferred purpose).
- Not used: fixed Split and Join, ParallelSend2, and parallel methods other than DSJ.
- No SendResp inside any DSJ branch.

---

## 6. Conditions actually used

All conditions are written as the `CONDITION` string of the If step definition, with operands passed per placement in `OPER1`…`OPER4`. Counts are live If and Branch conditions.

| Style | LL_CR | Custom | Example (exact) |
|---|---|---|---|
| `sequals` (string equals) | 104 | 77 | `sequals "($OPER1)" "($OPER2)"` with `OPER1=($LIBOUT_STATUS)`, `OPER2=7` |
| `paramsexist` (one parameter) | 16 | 14 | `paramsexist "($OPER1)"` with `OPER1=SFTP_RESP_FILENAME_($IDX)`; `paramsexist "NODE_($INDEX)"` |
| `paramsexist … sequals … and` | 5 | – | `paramsexist "LIBOUT_RETRIGGER_EMAIL" sequals "($LIBOUT_RETRIGGER_EMAIL)" "TRUE" and`; `paramsexist "($OPER1)" sequals "($($OPER1))" "TRUE" and` |
| `sequals … sequals … or` | 5 | – | `sequals "($OPER1)" "($OPER3)" sequals "($OPER2)" "($OPER3)" or` (RESOLUTION or OHR_RESOLUTION equals IGNORE/SKIP/…) |
| `sequals … sequals … and` | 1 | – | `CHECK_REQUEST_VALIDITY`: `BEFORE_TIMEFRAME=="BEFORE"` and `AFTER_TIMEFRAME=="AFTER"` |
| `substr` (contains) | 5 | 6 | `substr "($OPER1)" ","`; `EMAIL_BODY_1` contains `CONTINUE` / `ABORT` / `SUCCESSFUL` / `FAIL` |
| `equals` (numeric) | – | 6 | `equals "($OPER1)" "($OPER2)"` with `($INDEX)`, `3` |
| `greater`, `greatereq` | 2 | 5 | `greatereq "($OPER1)" "($OPER2)"` with `($MAX_COUNTER)`, `($CIQ_IN_COUNTER)` |
| `less`, `lesseq` | 3 | 3 | `lesseq "($i)" "($OPER1)"`; `less "($OPER1)" "($OPER2)"` |
| Branch conditions | 1 step | – | `CONDITION1 = sequals "($OPER1)" "PARENT_ORDER"`, `CONDITION_NAME1 = ParentOrder`, default target "exit method" |

**Not used:**
- Numeric `<>`, `snequals`, starts-with and ends-with.
- `regexp` and `requals` in conditions.
- XOR and NOT.
- `paramsexist` with a list.
- NE status (IDLE/WAITING/NOT_AVAILABLE).
- Jeopardy, cancelling and PoNR conditions.

Status is always compared as a **string** (`sequals … "7"`), never numerically.

---

## 7. Parameter-reference syntax actually used

| Form | Used? | Real examples |
|---|---|---|
| Optional `($X)` | **Yes. It is the only form used.** | `($ORDER_NO)`, `($REQ_ID)`, `($LINE_1_PS_PARAM_SUB_ACTIVITY_NAME)` |
| Mandatory `$X` (bare) | **No.** None found in either logic. | – |
| Nested references | Yes, heavily | `($($LKP_KEY)_1)` (value of the key named by `LKP_KEY`); `($SFTP_RESP_FILENAME_($IDX))`; `($NODE_NAME_($DSJ_CURRENT_BRANCH_IDENTIFIER))`; `($LINE_($PARAM_INDEX)_PS_PARAM_SITE)`; `($User($j)AssignedNodeCount)`; `($LIBOUT_ACTIVITY_EXECUTION_ACTION_($PEER_NODE_NAME_($DSJ_CURRENT_BRANCH_IDENTIFIER))_($PEER_NODE_IDX))` (dead) |
| Concatenation in values | Yes | `/mnt/shared_data/($REQ_ID)/($NODE_TYPE)_($SUB_ACTIVITY_NAME)_CIQ.xlsx`; `MANO E2E - ($ORDER_NO) \|\| ($NODE_TYPE) : ($ACTIVITY_NAME) : ($SUB_ACTIVITY_NAME)_($REQ_ID)_($SUBJECT_COUNT)` |
| Integer arithmetic | Yes, mostly +1 counters | `(($INDEX)+1)` (10×), `(($SUBJECT_COUNT)+1)` (8×), `(($INDEX)-1)`, `(($NODE_COUNT)+($NODE_COUNT))`, `(($User($j)AssignedNodeCount)+($BkpNodeCount))`, `(($RESULT_1)+1)` inside a String step |
| References in parameter **names** (MapParameters) | Yes | `SFTP_RESP_FILENAME_($INDX)`, `CR_($INDEX)`, `NODE_NAME_($INDEX)`, `User($i)AssignedNodeCount`, `($LKP_KEY)_3`, `PEER_NODE_ROLLBACK_REQUIRED_($DSJ_CURRENT_BRANCH_IDENTIFIER)`, `RBMANDATORY_($NODE_NAME_(…))_ROLLBACK_ENABLED` |
| References inside a regex | Yes (`EVALUATE_REGEXP_1=TRUE`) | `REGEXP1 = *,($SRC)` with `SRC=LIST_OF_ATTACH_NAMES_(.*)`; replacement `1_* = ($DST)(\1),` |
| Bracket `[$X]` (keep old value) | **No** | – |
| Wildcard `%` in parameter lists | Yes, in "add to parent" lists | `ADD_TO_UPPER_LEVEL = LIBOUT(%) SFTP(%) SMESSAGE LINE(%) PROJECT_ID GITCIQ_FILE_FOUND`; LogicLibrary `OUTPUT_PARAMETER_LIST = LIBOUT(%)` |
| Step result chaining | Yes | String `RESULT_1..5` used by later operations (`PARAM_1_4 = (($RESULT_1)+1)`), then mapped out as `FORMATTED=($RESULT_1)` and `KEY_LENGTH=($RESULT_5)`; Calc `($RESULT)` |

Consequence: because only optional references are used, a missing parameter never stops a run. It silently becomes "not generated" or empty. The logic compensates with explicit `paramsexist` checks.

---

## 8. Lookup tables

### 8.1 Tables referenced

Only 5 of the 151 exported tables are referenced. All are read with `LookUp` and `MULTIPLE_VALUES=TRUE`, which yields `<KEY>_1`, `<KEY>_2`, …. Most reads go through the shared method `MT_Generic_Lookup` (inputs `LOOKUP_TABLE`, `LKP_KEY`; outputs `LKP_FOUND=TRUE` or OrderHub fallout plus RETRY on `NOT_FOUND`).

The export format is one line per key: `key="<k>" values="v1",,"v2",,"v3"…`.

| Table | Rows | Keys used | What it configures |
|---|---|---|---|
| `CR_AUTOMATION` | 13 | `<VENDOR>_<NODE_TYPE>` (e.g. `Nokia_SBC`), `33_LIC_LOADING_IN_SBC`, `TIMEFRAME`, `TASK_WAIT_TIME` | Number of CLICR NE instances per vendor/node type; pre-check library for one sub-activity; business-hours window; email resend delay |
| `CR_AUTOMATION_PREREQUISITE` | 2 | `($LINE_1_PS_PARAM_SUB_ACTIVITY_NAME)` | Which sub-activities call the library logic (used for **both** the pre-check of 10005 and the post-completion of 1051) |
| `CR_AUTOMATION_POSTCOMPLETION` | 1 | **not referenced by either logic** | Intended for post-completion routing (inferred); the logic uses `CR_AUTOMATION_PREREQUISITE` instead |
| `MANO_ACTIVITY_CONFIGURATION` | 196 | `CR_AUTOMATION` (`480`,,`2` → `TIMEOUT`, `MAX_COUNTER`), `CR_AUTOMATION_EMAIL` (`2880` → `READEMAIL_WAIT_TIME`) | Timeouts and counters, shared with other MANO activities |
| `LKT_MANO_GENERIC` | 34 | `E2E_GIT_BRANCH` (`main`), `E2E_PREPROCESSING_CIQ_EXT` (`xlsx`), `E2E_READ_EMAIL` (a shared mailbox address), `BASE_DIR`, `GIT_COMMIT_RETRY` (`3`); dead code also uses `USER_NAME`, `PASSWORD`, `SFTP_ADDRESS`, `DESTINATION_DIR`, `EMAIL_DOWNLOADED_ATTACHMENT` | Generic environment settings. **The table stores an SFTP user name and password in clear text (`PASSWORD` = `<redacted>`).** |
| `LKT_E2E_EMAIL_MESSAGES` | 608 | 19 keys, e.g. `CR_AUTO_EMAIL_FRESH`, `CR_AUTO_CIQ_CORRUPT`, `CR_AUTO_EMAIL_COMPLETION`, `CR_AUTO_EMAIL_ABORT`, `CR_AUTO_MOP_FAIL`, `CR_AUTO_ACTIVITY_FAIL`, `CR_AUTO_ACTIVITY_CUTOFF_TIME`, `CR_AUTO_ACTIVITY_EXECUTION_FAILED`, `CR_AUTO_PRECHECK_VALIDATION_ABORT`, `CR_AUTO_EMAIL_EXCEEDED`, `CR_ACTIVITY_APPROVAL`, `CR_AUTO_CUSTOM_EMAIL`, `CR_AUTO_CUSTOM_EMAIL_FAIL`, `CR_AUTO_CUSTOM_ORDERHUB`, `CR_AUTO_IMSI_EMAIL`, `CR_AUTO_INVALID_IMSI_RESPONSE`, `CR_AUTO_INVALID_RESPONSE`; dead code: `MULTI_MOP_SS_ROLLBACK`; `EMAIL_COUNTER_EXHAUSTED` (dead) **is not in the table** | Email texts. Value 1 = user action (`REPLY OVER MAIL` / `NO ACTION` / `MANO UI ACTION`), value 2 = activity details, value 3 = message body with placeholders `##NODETYPE##`, `##ACTIVITY_NAME##`, `##FILENAME##`, `##VENDOR##`, `##WORKQUEUE##`, which String `replaceSubString` fills in |

### 8.2 The CR_AUTOMATION* tables in full

`CR_AUTOMATION`:
```
key="33_LIC_LOADING_IN_SBC" values="LL_CR_CUSTOM_LOGIC",,""
key="CR_AUTOMATION_ROLLBACK" values="7",,""          (not referenced)
key="Mavenir_DPA"   values="1",,""
key="Nokia"         values="3",,""
key="Nokia_CFX"     values="1",,""
key="Nokia_DSR"     values="1",,""
key="Nokia_EIR"     values="1",,""
key="Nokia_MRF"     values="1",,""
key="Nokia_PGW_RDS" values="1",,""
key="Nokia_SBC"     values="1",,""
key="Nokia_WBX_SAS" values="1",,""
key="TASK_WAIT_TIME" values="120",,""
key="TIMEFRAME"     values="00:00:00",,"23:59:00"
```
`CR_AUTOMATION_PREREQUISITE`:
```
key="10005_SAPC_CCPC_PREFERENCE_CHANGE_IN_DSR" values="LL_CR_CUSTOM_LOGIC"
key="1051_SUBSCRIBER_PROFILE_CONFIGURATION"    values="LL_CR_CUSTOM_LOGIC"
```
`CR_AUTOMATION_POSTCOMPLETION`:
```
key="1051_SUBSCRIBER_PROFILE_CONFIGURATION" values="LL_CR_CUSTOM_LOGIC"
```

### 8.3 How generic the design really is

- **There is no activity → NE / ACTION / parameter mapping in any lookup.** Every NE type, ACTION, task parameter, file path and work-queue name is hard-coded in MapParameters steps.
- The design is generic across CR types for a different reason. CR-specific behaviour lives in the **NEIs**: CLICR runs the YAML/JSON for `SUB_ACTIVITY_NAME`, and FILECR validates the CIQ per `NODE_TYPE` and `SUB_ACTIVITY_NAME`. The BST flow is the same for every CR.
- The lookups only tune the flow: library name per sub-activity, NE instance count per vendor/node type, time window, counters and timeouts, wait time, and email texts.
- Where a CR needs different orchestration, the logics **hard-code sub-activity names in If steps**:
  - `33_LIC_LOADING_IN_SBC`, `10005_SAPC_CCPC_PREFERENCE_CHANGE_IN_DSR` and `1051_SUBSCRIBER_PROFILE_CONFIGURATION` in both logics.
  - `MRF` in unlinked nodes.
  - NE IDs `CLI_SBC_1` and `CLI_DSR_1`.
  - The lookup only confirms the library name.

---

## 9. Error handling, retries, rollback, timeouts, emails – as implemented

- **Error results are data.**
  - After each NE call the logic tests `STATUS`/`LIBOUT_STATUS` and sets `LIBOUT_STATUS` (7/9), `SMESSAGE`, `SMESSAGE_ID` (`0`, `9`, `BST009`, `BST999`) and flags.
  - Flag examples: `LIBOUT_ABORT`, `LIBOUT_MOP_CREATION_FAILED`, `LIBOUT_SKIP_TO_MT_ORDER_COMPLETION_FLAG`, `LIBOUT_RETRIGGER_EMAIL`, `LIBOUT_INCOMPLETE_DATA`, `LIBOUT_MULTICR_INCOMPLETE_DATA`, `FLOW_CONTINUE`, `RETRY_FLAG`.
  - Methods return these through `ADD_TO_UPPER_LEVEL` lists.
- **Main failure pattern: a human decision via OrderHub.**
  - On almost any failed NE call, `E2E_OH_Fallout` creates an OrderHub task in work queue `CR_AUTOMATION_FALLOUT_MANAGEMENT` and **waits for the operator's `RESOLUTION`**:
    - `IGNORE`, `SKIP`, `CONTINUE`: carry on.
    - `ABORT`: finish with an abort email.
    - `RETRY`: jump back and repeat the failed activity.
  - The same pattern guards missing lookup keys (`MT_Generic_Lookup`, queue `($FALLOUT_QUEUE)`).
  - Approvals use OrderHub too: `ManualApproval` (`<VENDOR>_CR_AUTOMATION_VALIDATION_AND_EXECUTION`) and custom `ManualTask` (`CR_AUTOMATION_PRECHECK`).
  - The BST **Manual** step (WFC) is not used.
- **Automatic retries with counters.** Only three:
  - Git commit, up to `GIT_COMMIT_RETRY=3`.
  - CIQ email reminders, up to `MAX_COUNTER=2`.
  - Invalid or missing email replies, up to 3 (custom).
  - Human RETRY loops, fallout re-creation and OMDB polling have no limit.
- **Timeouts.**
  - No BST engine expiry handling is visible.
  - Time limits are passed to NEIs as task parameters: `READEMAIL_ACTIVITY_TIMEOUT=($TIMEOUT)` (480) and `READEMAIL_WAIT_TIME` (2880). The units are not stated (inferred minutes).
  - OM activity durations `28800`/`36000` are schedule estimates only.
- **Business-hours window.** `CHECK_REQUEST_VALIDITY` compares now (`GET_TIMESTAMP=HH:mm:ss`) with `TIMEFRAME` using two Date `COMPARISON` steps. Outside the window it sends a cut-off email (`Method_6`) and `SendResp _STATUS=7` "INVALID REQUEST AS DEFINED TIME LIMIT REACHED".
- **Final responses.**
  - The 6 live `SendResp` steps all use `_STATUS=9` with `_SMESSAGE=($SMESSAGE)`, except the time-window one (`_STATUS=7`).
  - `PARAM_LIST`, `PARAO_LIST`, `STARTS_WITH_LIST` and `DELETE_FROM_LIST` are always empty.
  - The **success paths of both `PARENT_ORDER` and `SUB_ORDER` end without SendResp**. The final response then comes from the engine's default end-of-logic behaviour (inferred).
- **Per-node failure does not fail the order.** In `ACTIVITY_EXECUTION` both success and failure set `LIBOUT_STATUS=7`. A failure produces a FILECR failure report, a zip and an email, and the order continues to the summary report and completion email.
- **Rollback.**
  - Execution data needed for rollback is recorded live (`RenameCLIPArams` copies `CLI_ANY_*` to `RBMANDATORY_*`; about 55 `RBMANDATORY_*`/`LIBOUT_*` per-node parameters).
  - The rollback flow is fully drawn but **not linked**: `Method_Manual_Fallout_Rollback` → `SEND_EXECUTION_STATUS` (email "reply CONTINUE or ROLLBACK") → `READ_EXECUTION_STATUS_MAIL` → `MULTINODE_MANUAL_FALLOUT` (OrderHub `ROLLBACK_CR_AUTOMATION`; `COMPLETE & SUCCESS` / `ROLLBACK & COMPLETE` / `ROLLBACK & ABORT` / `COMPLETE & FAILED`) → `DynamicSplitAndJoin_4` → `ACTIVITY_EXECUTION_ROLLBACK` (CLICR `MOP_EXECUTION` with `ROLLBACK_ONLY=true`).
  - No catalog ErrorDecomposition is used.
- **In-flight policies.** `ORDER_CANCEL/AMEND/RESCHEDULE_TASK_RESPONSE_POLICY=0` appear only on dead `Failure_OH*` steps.
- **Emails.** These are **central to the process**: 9 live email-send steps (6 in LL_CR, 3 in the custom logic) and 3 live email-read steps across the two logics, used to:
  - ask for the CIQ, re-send it with the validation report;
  - send the MOP for approval;
  - send pre-check reports and collect CONTINUE/ABORT;
  - send MMTEL refresh requests per IMSI and collect SUCCESSFUL/FAIL;
  - send node failure reports, the completion/abort/cut-off email and the closure email.

  Subjects carry a counter (`_($SUBJECT_COUNT)`) so each reply can be matched (inferred).
- **Notifications, alarms, logging.** Notification, Alarm and Log steps are not used. The steps labelled `Notification*` are `UpdateBaseline`. Tracing is requested only once, and that use is disabled (`TASK_1_#REQUEST_TRACE = EVERYTHING`).

---

## 10. Workarounds for BST limitations

| Limitation in BST (per document 01) | What these logics do instead |
|---|---|
| No e-mail step | Separate NEIs: `EMAIL` / `E2ESMTP` to send, `READEMAIL` to wait for and parse replies, including attachments |
| No wait / delay step | (a) A timed task: `SMTP_TASK_START_TIME` = now + `TASK_WAIT_TIME` via String `addSeconds` delays the resend email. (b) Waits are pushed into NEIs: READEMAIL with `ACTIVITY_TIMEOUT`/`WAIT_TIME`; OMDB presumably blocks until the child request finishes (inferred). (c) A long-standing OrderHub task acts as a human wait |
| SendRequest cannot wait for the child request | Poll the **OMDB NE** with `SUB_ORDER_REQ_ID` until `SUB_REQ_STATUS=="READY"` |
| Manual step limited to WFC | Human tasks are OrderHub NE tasks (`CREATE_TASK`, decision returned as `RESOLUTION`) or email replies parsed with `substr` |
| No custom code step | Logic goes into NEIs (CLICR, FILECR); shell commands run via the `CLI` NE on `cliUnix` (`cp`, `zip`, `mkdir`); String step chains do comma-splitting and date formatting (`FORMATTED_TIME` builds an ISO-like timestamp from `getPrefix`/`getSuffix`) |
| No for-each loop | If back-links with counters; comma-separated lists split by String `indexOf`/`substring` |
| No logic-rule visibility of parent vs child | A `Branch` on `INVOKE_METHOD` inside one logic serves both the parent and the child request types |
| DSJ output prefixes | RegExp `CUT_DSJ_PREFIX_STEP` strips `OUT<n>_`; per-node names are embedded in parameter names |
| Parameter "comment-out" | Prefix `#` on staged parameter names so `CUT_PREFIX` skips them |
| Configuration and secrets | Kept in lookup tables, including a clear-text SFTP password (`<redacted>`) |
| Load or affinity control across NEI instances | Calc `idiv`/`irem` plus loops assign each node to `CLI_<NODE_TYPE>_<n>` |

### 10.1 Quality observations (relevant to validation and tooling in the replacement)

- Large amounts of dead material:
  - LL_CR: 22 dead methods, 88 never-placed step definitions, many unlinked nodes.
  - Custom: 8 dead methods, 14 never-placed definitions.
  - Many `_copyN` duplicates of the same method, e.g. `MT_Generic_Lookup_copy1…10` and `E2E_OH_Fallout_copy1…3`.
- Typos the engine cannot catch, all of which silently change behaviour because only optional references are used:
  - `OEPR1` instead of `OPER1` in custom `IF_4`, `IF_4_copy1`, `IF_4_copy2`;
  - `RERTY_FLAG` in custom `E2E_OH_Fallout`;
  - parameter name `READEMAIL_READEMAIL WAIT_TIME` with a space;
  - `CR_AUTOMATION_FALLOUT_MANAGEMNT` in dead code.
- The If description "This flag will be generated for Multi Mop usecases…" is copied onto unrelated `IF_Equals` placements.

---

## 11. Requirements actually exercised (mapped to section 6 of document 01)

Legend: **USED** = exercised by live paths. **PARTLY** = only part of the feature is used, or it is used only in dead code. **NOT USED**. **N/D** = platform-level, so it cannot be determined from a logic export.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | Flat NAME=value request, per-request pool | USED (implicit) | Request supplies `LINE_1_*`, `ORDER_NO`, `REQ_ID`, `INVOKE_METHOD`, `LINE_1_PS_PARAM_*` |
| 2 | Logic selection rules | N/D | Not in the export; the logic itself branches on `INVOKE_METHOD` (`Branch_1`) |
| 3 | Versioned, immutable definitions | N/D | Export names are timestamped (`LL_CR_20261015_051722`) |
| 4 | In-flight on old version | N/D | Matters because runs wait days (2880 wait time, OrderHub tasks) (inferred) |
| 5 | Parameter references | USED | Optional `($X)` only; nested, concatenated, integer arithmetic, references in names. Mandatory `$X` and `[$X]` not used |
| 6 | Scoped child pools, "add to parent" with wildcards | USED | `ADD_TO_UPPER_LEVEL = LIBOUT(%) …` on most method calls; `output all="yes"` on many methods; LogicLibrary `OUTPUT_PARAMETER_LIST = LIBOUT(%)` |
| 7 | Evaluation order inside a unit | USED | String `RESULT_n` chaining; step output mapping `FORMATTED=($RESULT_1)` |
| 8 | Reuse with common and instance parameters | USED | One `IF_Equals`/`IFEquals` definition with per-instance `OPER1/OPER2`; `CreateSchedule_1` and `ActivityStatus_*` reused dozens of times; shared `MT_Generic_Lookup`, `E2E_OH_Fallout` |
| 9 | Sequential flow, nested methods with a depth limit | USED | Depth 7 live in LL_CR, plus a library level |
| 10 | If with TRUE/FALSE, missing path = return | USED | 139 + 111 live Ifs; many Ifs have only one link and rely on "return to parent" |
| 11 | Multi-way Branch with default / exit | USED (once) | `main_method` `Branch_1`, default "exit method" |
| 12 | Condition language | PARTLY | Used: `sequals`, `paramsexist`, `substr`, `equals`, `greater(eq)`, `less(eq)`, AND, OR. Not used: `<>`, `snequals`, starts/ends, `regexp`, `requals`, XOR, NOT, list-exists |
| 13 | NE status conditions | NOT USED | |
| 14 | Loops with counters and a guard | USED | ~30 loops (section 4); several unbounded loops show that a guard is needed |
| 15 | Switch | NOT USED | `Switch_1`/`Switch_2` defined but never placed |
| 16 | Split and Join (fixed) | NOT USED | |
| 17 | Dynamic Split and Join | USED | `DynamicSplitAndJoin_1` per CR (`($CR_COUNT)`), `DynamicSplitAndJoin_3` per node (`($NODE_COUNT)`); branch ID used, IN-prefix not used, OUT-prefix stripped by RegExp |
| 18 | ParallelSend2 / parallel method | NOT USED | |
| 19 | Library logics | USED | 2 live `LogicLibrary` calls to `LL_CR_CUSTOM_LOGIC`; name from lookup (`LIBRARY_LOGIC=($LOGIC)`); whole pool passed; `IGNORE_LIBRARY_ERRORS=FALSE`; `LIBRARY_ERROR_CODE` never tested |
| 20 | End-with-response step | USED | 6 live `SendResp` (status 9; one status 7); none inside DSJ; success paths end without SendResp |
| 21 | Send task to NE, prefix selection, durable wait | USED | 45 live SendToNE; selection always `ADD_ALL_PARAMETERS=FALSE` + `CUT_PREFIX` (several prefixes allowed); `PARAM_LIST`/`PARAO_LIST`/`STARTS_WITH_LIST` only in dead code; waits last hours to days |
| 22 | Merge response with prefix rules | USED | `GET_ALL_RESP_PARAMS=TRUE`, `CUT_RESP_PREFIX=@NEW@`, `IGNORE_RESP_PREFIX=@REQ@`, `ADD_RESP_PREFIX` (5 prefixes); `STATUS_RESULT`/`SMESSAGE_RESULT` never read |
| 23 | Status model 5–9 | PARTLY | Only 7 and 9, compared as strings |
| 24 | Fail on missing mandatory task parameter | NOT USED | No `PARAM_LIST`, no `$X` |
| 25 | Timed tasks (`TASK_START_TIME`) | USED (custom) | `SMTP_TASK_START_TIME` = now + 120 s as a delay; LL_CR's `#READEMAIL_TASK_START_TIME` is disabled |
| 26 | Best-effort tasks | NOT USED | |
| 27 | SendOriginalTask | NOT USED | |
| 28 | SendRequest (child requests) | USED | One child request per CR (SAS3, priority 1, order no, `BYPASS_LOCKING=FALSE`); completion awaited by **OMDB polling**. The replacement needs a native "spawn child and await" |
| 29 | Final response with status, message, ID | USED | `_STATUS`, `_SMESSAGE`, `_SMESSAGE_ID` only |
| 30 | Intermediate notifications | NOT USED | Notification steps defined, never placed; progress goes through OM activities and emails instead |
| 31 | Expiry, timeouts, retries | PARTLY | Retries and timeouts are hand-built (counters, NE timeout parameters, time window); no engine expiry handling visible |
| 32 | Error handling as data, ignore-errors flag | USED | Status and flags tested by If; `ACTIVITY_STATUS_IGNORE_ERRORS=TRUE` on most ActivityStatus steps; `SCHEDULE_IGNORE_ERRORS=FALSE` |
| 33 | Persist context, survive restart | N/D | Essential in practice: runs wait on email and OrderHub for days (inferred) |
| 34 | MapParameters (create, copy, default, delete, timestamp) | USED | 236 + 182 live; `GET_TIMESTAMP` (`yyyyMMddHHmmss`, `HH:mm:ss`), `DEL_PARAM_LIST`, `DEF_VAL_NOT_FOUND_PARAMETER`, `MAP_REMOVE_DEF_VAL_NOT_FOUND_PARAMETER`; `REMOVE_BEFORE/AFTER_LIST` always empty |
| 35 | String and Calc operations | USED | String: `replaceSubString`, `getPrefix`, `getSuffix`, `removePrefix`, `removeSuffix`, `substring`, `indexOf`, `length`, `concat`, `toUpper`, `addSeconds`. Calc: `idiv`, `irem` |
| 36 | Date arithmetic and comparison | PARTLY | Two `COMPARISON` steps (time-of-day window) only |
| 37 | RegExp extract, rename, delete | USED | Prefix renames (`*,(OUT[0-9]*_)(.*)` → `(\2)`, `CLI_ANY_(.*)` → `RBMANDATORY_(\1)`), capture (`(.*)T(.*)` → `(\1) (\2)`), `EVALUATE_REGEXP_1`, `KEEP_ORIGINALS` |
| 38 | CheckParams | NOT USED | `paramsexist` Ifs used instead |
| 39 | Lookup tables (multi-value, NOT_FOUND) | USED | 5 tables, `MULTIPLE_VALUES=TRUE`, `NOT_FOUND` tested and routed to OrderHub with RETRY |
| 40 | Number ranges | NOT USED | |
| 41 | Database / PL-SQL | NOT USED | |
| 42 | XML parser | NOT USED | |
| 43 | EDIFACT | NOT USED | |
| 44 | Activities, summary activities, Gantt | USED | `CreateSchedule` APPEND + `UpdateBaseline` + `ActivityStatus` around almost every NE call (LP_/CMG_ prefixes, durations 28800/36000); DSJ summary-activity parameter |
| 45 | Mainline/baseline runtime edits | PARTLY | APPEND, `SCHEDULE_UPDATE_BASELINE=TRUE`, `SCHEDULE_GENERATE_EDD=TRUE`, UpdateBaseline. Not used: INJECT, REPLACE, UpdateSchedule |
| 46 | In-flight cancel, amend, reschedule | NOT USED (dead code only) | `ORDER_*_TASK_RESPONSE_POLICY=0` only on unlinked `Failure_OH*` |
| 47 | Jeopardy / cancelling / PoNR conditions | NOT USED | |
| 48 | Order parameters | NOT USED | |
| 49 | Order events | NOT USED | |
| 50 | Manual / human tasks | USED, via NEIs | BST Manual step not used; human decisions come from the OrderHub NE (`RESOLUTION`) and READEMAIL replies (`CONTINUE`/`ABORT`/`SUCCESSFUL`/`FAIL`) |
| 51 | Jeopardy notifications | NOT USED | |
| 52 | Catalog decomposition / delta / rollback decomposition | NOT USED | |
| 53 | Compensation (record and undo) | PARTLY | Undo data (`RBMANDATORY_*`) recorded live; rollback flow (`DynamicSplitAndJoin_4`, `ROLLBACK_ONLY=true`) drawn but not linked |
| 54 | Visual / declarative editor | N/D | |
| 55 | Save-time validation | N/D | The export shows the need: dead units and typos such as `OEPR1` and `RERTY_FLAG` |
| 56 | Unit testing | N/D | |
| 57 | Change history / audit | N/D | |
| 58 | Diff versions | N/D | |
| 59 | Generated documentation | N/D | |
| 60 | Export / import | N/D (export exists) | Logic jars and lookup exports are the inputs of this analysis |
| 61 | Autosave / offline editor | N/D | |
| 62 | Execution tracing | N/D | One attempt to enable tracing for child requests is disabled (`TASK_1_#REQUEST_TRACE = EVERYTHING`) |
| 63 | Compare traces | N/D | |
| 64 | Coverage | N/D | Would help remove the large amount of dead code |
| 65 | Alarms | NOT USED | |
| 66 | Log step / log levels | NOT USED (Log step) | |
| 67 | Role-based access | N/D | |
| 68 | Activation without downtime | N/D | Lookups are edited for operator RETRY ("Please select 'RETRY' after lookup table … update"), so lookup changes must apply live |
| 69 | Runtime settings | N/D | |
| 70 | Send e-mail | **USED – effectively MUST** | 9 send steps and 3 reply-read steps (EMAIL, E2ESMTP, READEMAIL NEIs), including attachments and reply parsing |
| 71 | Custom code steps | USED via NEIs | CLICR, FILECR, GIT, OMDB, ORDERHUB NEIs and shell commands through the `CLI` NE |

**Exercised (USED or PARTLY):** #1, 5, 6, 7, 8, 9, 10, 11, 12 (partly), 14, 17, 19, 20, 21, 22, 23 (partly), 25, 28, 29, 31 (partly), 32, 34, 35, 36 (partly), 37, 39, 44, 45 (partly), 50 (via NEIs), 53 (partly), 70, 71.

**NOT USED:** #13, 15, 16, 18, 24, 26, 27, 30, 38, 40, 41, 42, 43, 46 (dead code only), 47, 48, 49, 51, 52, 65, 66.

**Not determinable from the export (platform-level):** #2, 3, 4, 33, 54–64, 67–69. Of these, #33 (durable long waits) and #68 (live reference-data changes) are clearly needed by the observed behaviour (inferred).
