# 07 – Process designer, activity configuration and roles

Date: 2026-10-27. Status: **design agreed with the user; phase A (gateway, roles) and phase B (process versions, next steps, steps started through the gateway, Order tab from the definition) built and verified (43 gateway tests, 41 + 38 end-to-end checks); phase C (activity declarations: step / node / login / GRC parameters, secrets by reference) built and verified on a real order (see CONTEXT.md §10); phase D (Process designer screen: diagram, step editor, activity form, versions) and phase E (pipelines of widgets compiled to Kestra flows; 33 and 1051 configured in the designer) built and verified (CONTEXT.md §2 decision 16, §10).**

Phase E as built (differences from §3–4): no generic step runner and no transitions table – a step's flow offers hooks (`pipeline_hooks`), a pipeline is a tree of widgets (If / For each hold their own lists, so no joins), and publishing compiles each pipeline into a Kestra flow that the step's flow runs as a subflow. Gate = If + End; Report = an NEI widget (FileCR / CLICR).

Phase B as built (differences from §4): the "step runner" is the gateway – it starts the step's Kestra flow with the mapped inputs; today's five flows stay the step implementations. A generic NEI step flow comes with the widgets (phase C / E), when a step's parameters must be configurable without a flow of its own.

User's decisions (2026-10-27):
- The order process (template → validation → child → MOP execution → rollback, plus activity-specific extra steps) is a **flow diagram defined in our own Process designer screen**, built from **widgets**.
- Definitions are stored **in the database, with versions**.
- **Activity declaration** (node type + sub-activity) carries its parameters: extra / changed request parameters per step, per node, **login changes** for some nodes.
- **Three roles:** order executor, admin, viewer. Check the edge cases.
- Never hardcode anything activity-, node- or project-specific.

Related: `05_recommendation.md` (Kestra OSS has no RBAC), `06_component_wrapper.md` (wrapper contract).

---

## 1. Why a gateway (verified in the Kestra 2.0.7 source)

- Kestra OSS has **one** login: `BasicAuthService.validateCredentials` compares against the single configured user. No users, no roles.
- The UI logs in with `POST /api/v1/login`; Kestra answers with an HttpOnly cookie `BASIC_AUTH` (= base64 user:password) and a flag cookie the UI reads. Every API call is checked against that cookie (`AuthenticationFilter`).
- Kestra's CSRF check (`CsrfTokenFilter`) applies **only to cookie-authenticated** requests; calls with an `Authorization` header skip it.
- The UI uses **SSE** (`EventSource`: logs, execution follow, topology), no WebSockets.

So roles can only be enforced **in front of Kestra**: a small **gateway** that users talk to instead of Kestra.

```
browser ──▶ gateway :8080  (our users, roles, sessions, audit; process / activity API)
              │  allowed requests only, with Kestra's single technical login (Authorization header)
              ▼
            kestra:8080  (not published any more; only the gateway and the flows reach it)
```

**Gateway** (Java 21, JDK only + PostgreSQL JDBC driver, no framework):
- `POST /api/v1/login`, `POST /api/v1/logout`: answered by the gateway, against **our** user table. Sets its own session cookie (random 256-bit token, HttpOnly, SameSite=Strict) and Kestra's flag cookie, so Kestra's own login page keeps working unchanged.
- Every other request: session → user → role → **rule check** → forward to Kestra with `Authorization: Basic <technical login>`; the browser's `BASIC_AUTH` cookie is never forwarded.
- Streams bodies both ways (uploads, downloads, SSE), virtual threads per connection.
- `/cr/api/**`: our own API (who am I, users, processes, activities, next steps).

## 2. Roles

| | viewer | order executor | admin |
|---|---|---|---|
| See everything (flows, executions, logs, IO / MML, Order tab, outputs, KV, namespace files) | ✔ | ✔ | ✔ |
| Read-only POSTs (input validation of a form, flow graph / validate, chart preview / export, autocomplete, latest executions) | ✔ | ✔ | ✔ |
| Start an execution of a **process flow** of `cr_automation` (template, validation, child order, MOP execution, rollback, …) | – | ✔ | ✔ |
| Resume a paused execution (manual decisions), kill, restart, replay **one** execution of `cr_automation` | – | ✔ | ✔ |
| Bulk actions, change status, delete executions or logs, labels | – | – | ✔ |
| Edit flows, namespace files, KV, triggers, concurrency, import / export, plugins, MCP / AI | – | – | ✔ |
| **Evaluate expressions** (`expressions/render`, `actions/eval`, `flows/expressions`) – they can read secrets and KV | – | – | ✔ |
| Process designer / activity declarations: **edit, publish** | read | read | ✔ |
| Users and roles | – | – | ✔ |

Rules are an **allow list** (method + normalized path pattern); anything not listed is admin-only. The list is in one class of the gateway and covered by tests, one per rule.

## 3. Data model (schema `cr_automation`, same database as the request store)

**Users and security**

| Table | Columns |
|---|---|
| `app_user` | `user_id`, `username` (unique, lower-case), `display_name`, `role` VIEWER / EXECUTOR / ADMIN, `password_hash` (PBKDF2-HMAC-SHA256, 210 000 iterations, 16-byte salt), `enabled`, `failed_logins`, `locked_until`, `password_changed_at`, `created_at`, `updated_at` |
| `app_session` | `token_hash` (SHA-256 of the cookie value; the token itself is never stored), `user_id`, `created_at`, `last_seen_at`, `expires_at`, `ip`, `user_agent` |
| `audit_log` | `at`, `username`, `role`, `action` (LOGIN, LOGIN_FAILED, LOGOUT, DENIED, START_EXECUTION, USER_CHANGE, PROCESS_PUBLISH, …), `method`, `path`, `status`, `detail` |

**Processes**

| Table | Columns |
|---|---|
| `process` | `process_id`, `project` (e.g. CR_AUTOMATION), `name`, `description` |
| `process_version` | `process_id`, `version` (1, 2, …), `state` DRAFT / PUBLISHED / RETIRED, `definition` JSONB (steps + transitions), `created_by`, `created_at`, `published_by`, `published_at` – a published version is **immutable** |
| `activity` | `activity_id`, `project`, `node_type`, `sub_activity`, `process_id` (which process it uses), `enabled` |
| `activity_version` | `activity_id`, `version`, `state`, `config` JSONB: step parameters, node overrides, login / GRC overrides, execution settings; immutable once published |
| `request` (existing) | + `process_id`, `process_version`, `activity_version`: **an order is pinned to the versions it started with**; its children inherit them |

**Definition (JSONB)** – steps and transitions:
```json
{
  "steps": [
    {"id": "validate", "widget": "NEI", "scope": "ORDER", "start": "MANUAL",
     "nei": "FILECR", "action": "CR_CIQ_PROCESSOR", "params": {"REPORT_TYPE": "HTML"},
     "produces_stage": {"ok": "VALIDATED", "fail": "INVALID"}},
    {"id": "precheck", "widget": "NEI", "scope": "NODE", "start": "AUTO", "nei": "CLICR",
     "action": "MOP_EXECUTION", "params": {"SUB_ACTIVITY_NAME": "${activity.sub_activity}_PRECHECK"}}
  ],
  "transitions": [
    {"from": "mop_generation", "to": "precheck", "when": "activity.sub_activity == '33_LIC_LOADING_IN_SBC'"},
    {"from": "precheck", "to": "mop_execution", "when": "step.owner_mail.outcome == 'SUCCESS'"}
  ]
}
```
Widgets: NEI call, Mail, Decision (manual / from a reply), For each, Gate, Collect, Report. Scope: ORDER, CHILD (per CR), NODE. Start: MANUAL (offered as a "continue" action) or AUTO (right after its predecessor).

**Parameter resolution** (one resolver, in the store): step default ← activity version ← node override ← run-time input. References: `${order.X}`, `${child.X}`, `${ciq.X}` (validation results), `${step.<id>.X}` (results of an earlier step), `${node}`, `${activity.X}`, `${secret:NAME}` (resolved only inside the step runner, never stored or logged). Login / GRC overrides become request keys `login.KEY` / `grc.KEY` that the NEI wrapper moves into the login arglist / GRC section (generic wrapper change).

## 4. Runtime

- One generic **step runner** flow in Kestra runs one step (or a node / for-each group of it): reads the step from the pinned version, resolves the parameters, calls the wrapper, records the step (as today), writes the produced stage, then starts the AUTO successors.
- **Next steps** (`/cr/api/orders/{id}/next`): computed from the pinned definition, the order's / child's stages and the user's role. The Order tab shows exactly these – no flow names in the UI any more.
- Node claims, the per-child summary lock, IO / MML logs and the request store work as now.

## 5. Edge cases (to be covered by tests)

**Login and sessions**
- Unknown user and wrong password give the same answer and take the same time (dummy hash check); no user enumeration.
- 5 failed logins → locked 15 minutes; a lock is reported as "invalid credentials" too; success resets the counter.
- Sessions: idle timeout 8 h, absolute 24 h; logout deletes the session; **changing a user's role, password or disabling the user ends all their sessions immediately**.
- A disabled user cannot log in, and their open sessions stop working on the next request.
- The first admin is created from `GATEWAY_ADMIN_USER` / `GATEWAY_ADMIN_PASSWORD` (Kestra secret style env) **only when no user exists**; afterwards those variables are ignored.
- **The last enabled admin cannot be demoted, disabled or deleted** (including by themselves).
- Password rules: at least 12 characters, not equal to the username.
- Cookies: HttpOnly, SameSite=Strict, `Secure` when served over HTTPS; the token is stored only as a hash.

**Requests**
- CSRF: Kestra skips its own check for header-authenticated calls, so the gateway enforces it: every non-GET/HEAD/OPTIONS request needs an `Origin` (or `Referer`) whose host equals the request's `Host`.
- Paths are decoded and normalized (`//`, `/./`, `%2F`, `..`) **before** the rule check, and the normalized path is what is forwarded – the check and Kestra can never see different paths.
- Not logged in: API → 401 (the UI shows its login page); static UI files and Kestra's anonymous endpoints (`/api/v1/configs`, `/api/v1/configs/login`) pass.
- Denied: 403 with a readable message, written to `audit_log`.
- The executor's "start execution" is limited to namespace `cr_automation` and to flows marked as process flows (label `process: "true"`); any other flow → 403.
- Kestra unreachable → 502 with a clear message; Kestra's own 401 (technical login wrong) → 502 "gateway misconfigured", never shown as a user login failure.
- SSE and large downloads are streamed, not buffered; client disconnect closes the upstream request.
- Kestra's port is no longer published; the management port stays closed.

**Processes**
- A published version cannot be edited; editing creates a new draft. Publishing validates the definition: unique step ids, every transition's steps exist, no AUTO cycle, each widget's required fields, scope rules (a NODE step only under a CHILD), references resolvable.
- Running orders keep their pinned versions; a new version applies to new orders only.
- Two admins editing the same draft: optimistic locking (version + `updated_at`); the second save gets 409.
- An activity without its own configuration uses the process defaults; an activity whose process version is retired cannot start new orders.

## 6. Build order

| Phase | What | Done when |
|---|---|---|
| **A** | Gateway: users, roles, sessions, audit, rule list, login / logout, proxy (incl. SSE), first admin, UI shows the role and hides actions the role cannot use | Viewer / executor / admin verified against the real Kestra, every edge case of §5 "Login / Requests" tested |
| B | Process + activity tables with versions; today's process as version 1; step runner; next-steps API; Order tab driven by it | Behaviour equal to today, flows started through the runner |
| C | Activity declarations: parameters, node overrides, login / GRC overrides (wrapper change) | 1051 / 1058 configured without code |
| D | Process designer screen (vue-flow canvas, widget palette, step panel, YAML view, publish) | Today's process editable and publishable in the UI |
| E | Widgets Mail, Decision, For each, Collect, Gate; then 33 and 1051 configured purely in the designer | 33 precheck → owner mail → node gate; 1051 MMTel → POSTCOMPLETION |
