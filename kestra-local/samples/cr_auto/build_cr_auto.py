"""
Builds the FileCR-only sample project CR_AUTO (Kestra namespace cr_auto) through the APIs alone, runs one order through
it and writes every API call - method, URL, what was sent, what came back - into an HTML report.

  python build_cr_auto.py                    build, run an order, write the report
  python build_cr_auto.py --report out.html  another report file
  python build_cr_auto.py --remove           removes CR_AUTO again (Kestra flows, files, KV through the API; the
                                             project's rows in the store with SQL - the gateway only soft-deletes)

Nothing is copied from cr_automation's namespace: the project config is written here from scratch, the FileCR NEI is
the build zip NOKIA_FILE_CR_V1/target.zip uploaded as a component, the two step flows are written here. The platform
files (bin/store.sh, bin/component.sh, the wrapper jar, the JDBC driver) are uploaded from this repository.
Credentials come from kestra-local/.env and never reach the report (no Authorization header, secret-like keys masked).
"""
import argparse
import base64
import hashlib
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCAL = HERE.parents[1]                       # kestra-local
REPO = LOCAL.parent
PLATFORM = LOCAL / "namespace-files" / "cr_automation"
FILECR_ZIP = REPO.parent / "NOKIA_FILE_CR_V1" / "target.zip"
SAMPLE_CIQ = REPO / "samples" / "ciq" / "PGW_RDS_1051_CIQ_two_crs_pass.xlsx"
G = "http://localhost:8080"
PROJECT, NS, COMPONENT = "CR_AUTO", "cr_auto", "filecr"
SECRET_KEY = re.compile(r"pass|secret|token|pwd", re.I)

CONFIG = """# CR_AUTO project config - written from scratch by build_cr_auto.py, uploaded as config/cr_auto.properties.
# The request store (orders, steps, IO). Its password is not here: STORE_JDBC_PASSWORD comes from the Kestra secret CR_DB_PASSWORD.
store.jdbc.url=jdbc:postgresql://postgres:5432/kestra
store.jdbc.user=kestra
store.project=CR_AUTO
# The FileCR NEI's GRC and status codes are the component's own (Components tab, component filecr).
"""

TEMPLATES = "{dir}/templates"
COMPONENT_SPEC = {
    "kind": "WRAPPED",
    "description": "FileCR NEI from the build zip NOKIA_FILE_CR_V1/target.zip",
    "location": "UPLOADED",
    "wrapped": {
        "ne_type": "FILECR", "product": "ANY", "version": "V1", "config_dir": "config",
        "grc": {
            "moptemplates_path": TEMPLATES,
            "properties_path": TEMPLATES,
            "yaml_path": TEMPLATES + "/yaml",
            "jsonTemp_path": TEMPLATES + "/jsonTemplate",
            "REPORT_TEMP_PATH": TEMPLATES + "/html",
            "execution_report_template_path": TEMPLATES + "/html/mop_execution_report_template.html",
            "execution_summary_report_template_path": TEMPLATES + "/html/mop_execution_summary_report_template.html",
            "single_node_execution_summary_report_template_path": TEMPLATES + "/html/mop_execution_single_node_report_template.html",
        },
        "login": {},
    },
    "actions": [
        {"name": "GENERATE_CR_CIQ_TEMPLATE", "description": "the blank CIQ template of a node type + sub-activity",
         "params": [
             {"name": "REQ_TYPE", "default": "1"},
             {"name": "NODE_TYPE", "required": True, "default": "PGW_RDS"},
             {"name": "ACTIVITY_NAME", "required": True, "default": "1051_SUBSCRIBER_PROFILE_CONFIGURATION"},
             {"name": "SUB_ACTIVITY_NAME", "required": True, "default": "1051_SUBSCRIBER_PROFILE_CONFIGURATION"},
             {"name": "NODE_VERSION", "default": "ALL"},
             {"name": "CIQ_FILE_LOCATION", "required": True, "description": "folder the template is written to"},
             {"name": "ORDER_NO"}, {"name": "REQ_ID"}],
         "results": []},
        {"name": "CR_CIQ_PROCESSOR", "description": "validates a filled CIQ; its CRs when valid",
         "params": [
             {"name": "REQ_TYPE", "default": "1"},
             {"name": "CIQ_FILE_NAME", "required": True},
             {"name": "NODE_TYPE", "required": True}, {"name": "ACTIVITY_NAME", "required": True},
             {"name": "SUB_ACTIVITY_NAME", "required": True}, {"name": "NODE_VERSION", "default": "ALL"},
             {"name": "OUTPUT_FILE_LOCATION", "required": True}, {"name": "REPORT_TYPE", "default": "HTML"},
             {"name": "HEADER_ROW_NO", "default": "1"}, {"name": "ORDER_NO"}, {"name": "REQ_ID"}],
         "results": [{"name": "CR_LIST"}, {"name": "CR_COUNT"}, {"name": "TOTAL_ERRORS"}, {"name": "JSON_FILE_NAME"},
                     {"name": "REPORT_FILENAME_HTML"}]},
    ],
}

# The two step flows. The FileCR call names no version: KV COMPONENT_FILECR is the folder of the component's latest
# published version (kept by the gateway on publish / retire).
COMMON_ENV = """      SHARED_DATA_DIR: "{{ kv('SHARED_DATA_DIR') }}"
      STORE_JDBC_PASSWORD: "{{ secret('CR_DB_PASSWORD') }}"
"""
STORE_FILES = "[bin/**, wrappers/**, lib/**, config/**]"
REGISTER = """  - id: save_order
    type: io.kestra.plugin.core.kv.Set
    key: "ORDER_{{ render(vars.order_id) }}"
    kvType: JSON
    value: "{{ outputs.register.vars.order | toJson }}"

  - id: save_orders
    type: io.kestra.plugin.core.kv.Set
    key: ORDERS
    kvType: JSON
    value: "{{ outputs.register.vars.orders | toJson }}"
"""
LOGS = """finally:
  - id: collect_logs
    type: io.kestra.plugin.scripts.shell.Commands
    runIf: "{{ render(vars.order_id) is not empty }}"
    taskRunner:
      type: io.kestra.plugin.core.runner.Process
    outputFiles:
      - logs/**
    env:
      ORDER_DIR: "{{ render(vars.order_dir) }}"
    commands:
      - mkdir -p logs && cp "$ORDER_DIR"/logs/* logs/ 2>/dev/null || true
"""

FLOW_TEMPLATE = f"""id: filecr_template
namespace: {NS}
description: New order with the blank CIQ template (FileCR component, action GENERATE_CR_CIQ_TEMPLATE). Written by build_cr_auto.py.
labels:
  project: {PROJECT}
  process: "true"
  outline: "CIQ template (FileCR)"
inputs:
  - id: node_type
    type: STRING
    defaults: PGW_RDS
  - id: activity
    type: STRING
    defaults: 1051_SUBSCRIBER_PROFILE_CONFIGURATION
  - id: node_version
    type: STRING
    defaults: ALL
  - id: order_no
    type: STRING
    required: false
    validator: "^[1-9][0-9]*$"
variables:
  order_id: "{{{{ outputs.allocate.vars.req_id ?? '' }}}}"
  order_dir: "{{{{ kv('SHARED_DATA_DIR') }}}}/{{{{ outputs.allocate.vars.req_id ?? '' }}}}"
tasks:
  - id: allocate
    type: io.kestra.plugin.scripts.shell.Commands
    taskRunner:
      type: io.kestra.plugin.core.runner.Process
    namespaceFiles:
      enabled: true
      include: {STORE_FILES}
    env:
{COMMON_ENV}    commands:
      - >-
        sh bin/store.sh new-request --kind ORDER --wanted "{{{{ inputs.order_no ?? '' }}}}"
        --node-type "{{{{ inputs.node_type }}}}" --activity "{{{{ inputs.activity }}}}"
        --node-version "{{{{ inputs.node_version }}}}" --execution "{{{{ execution.id }}}}"

  - id: set_labels
    type: io.kestra.plugin.core.execution.Labels
    labels:
      req_id: "{{{{ render(vars.order_id) }}}}"
      order_id: "{{{{ render(vars.order_id) }}}}"

  - id: generate_template
    type: io.kestra.plugin.scripts.shell.Commands
    description: The FileCR component (its latest published version) - GENERATE_CR_CIQ_TEMPLATE
    taskRunner:
      type: io.kestra.plugin.core.runner.Process
    namespaceFiles:
      enabled: true
      include: [bin/**, wrappers/**, lib/**, config/**, "{{{{ kv('COMPONENT_FILECR') }}}}/**"]
    outputFiles:
      - out/**
    env:
{COMMON_ENV}      ORDER_DIR: "{{{{ render(vars.order_dir) }}}}"
      STORE_REQ_ID: "{{{{ render(vars.order_id) }}}}"
      STORE_STEP: GENERATE_TEMPLATE
      EXECUTION_ID: "{{{{ execution.id }}}}"
    commands:
      - >-
        sh bin/component.sh "{{{{ kv('COMPONENT_FILECR') }}}}" generate_template
        --param REQ_TYPE=1
        --param ACTION=GENERATE_CR_CIQ_TEMPLATE
        --param "NODE_TYPE={{{{ inputs.node_type }}}}"
        --param "ACTIVITY_NAME={{{{ inputs.activity }}}}"
        --param "SUB_ACTIVITY_NAME={{{{ inputs.activity }}}}"
        --param "NODE_VERSION={{{{ inputs.node_version }}}}"
        --param "CIQ_FILE_LOCATION=$ORDER_DIR/"
        --param "ORDER_NO={{{{ render(vars.order_id) }}}}"
        --param "REQ_ID={{{{ render(vars.order_id) }}}}"
      - cp "$ORDER_DIR"/*.xlsx out/ 2>/dev/null || true

  - id: register
    type: io.kestra.plugin.scripts.shell.Commands
    taskRunner:
      type: io.kestra.plugin.core.runner.Process
    namespaceFiles:
      enabled: true
      include: {STORE_FILES}
    env:
{COMMON_ENV}    commands:
      - >-
        sh bin/store.sh set-stage --req-id "{{{{ render(vars.order_id) }}}}"
        --stage "{{{{ outputs.generate_template.vars.status == 0 ? 'TEMPLATE' : 'TEMPLATE_FAILED' }}}}" > /dev/null
      - sh bin/store.sh registry --order "{{{{ render(vars.order_id) }}}}"

{REGISTER}
  - id: template_failed
    type: io.kestra.plugin.core.execution.Fail
    runIf: "{{{{ outputs.generate_template.vars.status != 0 }}}}"
    errorMessage: "CIQ template generation failed: {{{{ outputs.generate_template.vars.message }}}}"

{LOGS}"""

FLOW_VALIDATION = f"""id: filecr_validation
namespace: {NS}
description: Validates the filled CIQ of an order (FileCR component, action CR_CIQ_PROCESSOR). Written by build_cr_auto.py.
labels:
  project: {PROJECT}
  process: "true"
  outline: "CIQ validation (FileCR)"
inputs:
  - id: order_id
    type: STRING
  - id: ciq
    type: FILE
  - id: report_type
    type: STRING
    defaults: HTML
variables:
  order_id: "{{{{ inputs.order_id }}}}"
  node_type: "{{{{ kv('ORDER_' ~ inputs.order_id).node_type }}}}"
  activity: "{{{{ kv('ORDER_' ~ inputs.order_id).activity }}}}"
  node_version: "{{{{ kv('ORDER_' ~ inputs.order_id).node_version }}}}"
  order_dir: "{{{{ kv('SHARED_DATA_DIR') }}}}/{{{{ inputs.order_id }}}}"
  ciq_name: "{{{{ kv('ORDER_' ~ inputs.order_id).node_type }}}}_{{{{ kv('ORDER_' ~ inputs.order_id).activity }}}}_CIQ.xlsx"
tasks:
  - id: set_labels
    type: io.kestra.plugin.core.execution.Labels
    labels:
      req_id: "{{{{ inputs.order_id }}}}"
      order_id: "{{{{ inputs.order_id }}}}"

  - id: ciq_validation
    type: io.kestra.plugin.scripts.shell.Commands
    description: The FileCR component (its latest published version) - CR_CIQ_PROCESSOR
    taskRunner:
      type: io.kestra.plugin.core.runner.Process
    namespaceFiles:
      enabled: true
      include: [bin/**, wrappers/**, lib/**, config/**, "{{{{ kv('COMPONENT_FILECR') }}}}/**"]
    inputFiles:
      uploaded.xlsx: "{{{{ inputs.ciq }}}}"
    outputFiles:
      - out/**
    env:
{COMMON_ENV}      ORDER_DIR: "{{{{ render(vars.order_dir) }}}}"
      STORE_REQ_ID: "{{{{ inputs.order_id }}}}"
      STORE_STEP: CIQ_VALIDATION
      EXECUTION_ID: "{{{{ execution.id }}}}"
    commands:
      - mkdir -p "$ORDER_DIR" && cp uploaded.xlsx "$ORDER_DIR/{{{{ render(vars.ciq_name) }}}}"
      - >-
        sh bin/component.sh "{{{{ kv('COMPONENT_FILECR') }}}}" ciq_validation
        --param REQ_TYPE=1
        --param ACTION=CR_CIQ_PROCESSOR
        --param "CIQ_FILE_NAME=$ORDER_DIR/{{{{ render(vars.ciq_name) }}}}"
        --param "NODE_TYPE={{{{ render(vars.node_type) }}}}"
        --param "ACTIVITY_NAME={{{{ render(vars.activity) }}}}"
        --param "SUB_ACTIVITY_NAME={{{{ render(vars.activity) }}}}"
        --param "NODE_VERSION={{{{ render(vars.node_version) }}}}"
        --param "OUTPUT_FILE_LOCATION=$ORDER_DIR/"
        --param "REPORT_TYPE={{{{ inputs.report_type }}}}"
        --param HEADER_ROW_NO=1
        --param "ORDER_NO={{{{ inputs.order_id }}}}"
        --param "REQ_ID={{{{ inputs.order_id }}}}"
      - cp "$ORDER_DIR"/*.html "$ORDER_DIR"/*.json out/ 2>/dev/null || true

  - id: register
    type: io.kestra.plugin.scripts.shell.Commands
    taskRunner:
      type: io.kestra.plugin.core.runner.Process
    namespaceFiles:
      enabled: true
      include: {STORE_FILES}
    env:
{COMMON_ENV}    commands:
      - >-
        sh bin/store.sh set-stage --req-id "{{{{ inputs.order_id }}}}"
        --stage "{{{{ outputs.ciq_validation.vars.status == 0 ? 'VALIDATED' : 'INVALID' }}}}"
        --cr-list "{{{{ outputs.ciq_validation.vars.results.CR_LIST ?? '' }}}}"
        --json-file "{{{{ outputs.ciq_validation.vars.results.JSON_FILE_NAME is defined
           ? render(vars.order_dir) ~ '/' ~ outputs.ciq_validation.vars.results.JSON_FILE_NAME : '' }}}}" > /dev/null
      - sh bin/store.sh registry --order "{{{{ inputs.order_id }}}}"

{REGISTER}
  - id: ciq_invalid
    type: io.kestra.plugin.core.execution.Fail
    runIf: "{{{{ outputs.ciq_validation.vars.status != 0 }}}}"
    errorMessage: "CIQ validation failed: {{{{ outputs.ciq_validation.vars.message }}}}"

{LOGS}"""

STEP_INPUTS = {
    "node_type": {"from": "user", "type": "STRING", "label": "Node type", "required": True, "default": "PGW_RDS"},
    "activity": {"from": "user", "type": "STRING", "label": "Sub-activity", "required": True,
                 "default": "1051_SUBSCRIBER_PROFILE_CONFIGURATION"},
    "node_version": {"from": "user", "type": "STRING", "label": "Node version", "required": True, "default": "ALL"},
    "order_no": {"from": "user", "type": "STRING", "label": "Order number (empty: the next free number)"},
}
PROCESS = {
    "namespace": NS,
    "steps": [
        {"id": "new_order", "label": "New order: the blank CIQ template (FileCR)", "scope": "ORDER", "starts_order": True,
         "runs": {"flow": "filecr_template"}, "inputs": STEP_INPUTS},
        {"id": "validate", "follows": ["new_order"], "label": "Validate the filled CIQ (FileCR)", "scope": "ORDER",
         "available_when": {"order.stage": {"in": ["TEMPLATE", "INVALID", "VALIDATED"]}},
         "runs": {"flow": "filecr_validation"},
         "inputs": {"order_id": {"from": "order.value"},
                    "ciq": {"from": "user", "type": "FILE", "label": "Filled CIQ (.xlsx)", "required": True},
                    "report_type": {"value": "HTML"}}},
    ],
}


class Api:
    """Every HTTP hit goes through here and is logged for the report; the Authorization header is logged masked."""

    def __init__(self, user, password):
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
        self.hits = []
        self.phase = "config"

    def call(self, purpose, method, path, body=None, content_type=None, shown=None, expect=(200, 201, 204), record=True,
             accept="application/json", trim=None):
        url = G + path
        data = None
        if isinstance(body, (dict, list)):
            data, content_type = json.dumps(body).encode(), "application/json"
        elif isinstance(body, str):
            data = body.encode()
        elif isinstance(body, bytes):
            data = body
        headers = {"Authorization": self.auth, "Accept": accept}
        if content_type:
            headers["Content-Type"] = content_type
        at, started = datetime.now(), time.time()
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers), timeout=600) as r:
                status, raw, rh = r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            status, raw, rh = e.code, e.read(), e.headers
        text = raw.decode("utf-8", "replace")
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = text
        if record:
            self.hits.append({
                "phase": self.phase, "at": at.strftime("%H:%M:%S.%f")[:-3], "ms": int((time.time() - started) * 1000),
                "purpose": purpose, "method": method, "url": url,
                "req_headers": {**{k: v for k, v in headers.items() if k != "Authorization"}, "Authorization": "Basic *** (masked)"},
                "req_bytes": len(data or b""), "req_body": shown if shown is not None else mask(body),
                "status": status, "resp_bytes": len(raw),
                "resp_headers": {k: rh[k] for k in ("Content-Type", "Content-Length", "Location") if rh and rh.get(k)},
                "resp_body": mask(trim(parsed) if trim and isinstance(parsed, dict) else parsed)})
        if status not in expect:
            raise SystemExit(f"STOP at '{purpose}': {method} {path} answered {status}: {text[:400]}")
        return parsed

    def upload_file(self, purpose, ns, path, content):
        boundary = uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"fileContent\"; filename=\"{Path(path).name}\"\r\n"
                "Content-Type: application/octet-stream\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
        shown = f"--{boundary}\nContent-Disposition: form-data; name=\"fileContent\"; filename=\"{Path(path).name}\"\n\n" \
                + text_or_summary(path, content) + f"\n--{boundary}--"
        return self.call(purpose, "POST", f"/api/v1/main/namespaces/{ns}/files?path={urllib.parse.quote(path, safe='')}",
                         body, f"multipart/form-data; boundary={boundary}", shown)

    def wait(self, purpose, execution_id, timeout=600):
        started = time.time()
        while True:
            e = self.call(purpose, "GET", f"/api/v1/main/executions/{execution_id}", trim=execution_summary)
            state = e["state"]["current"]
            if state in ("SUCCESS", "FAILED", "KILLED", "WARNING"):
                return e
            if time.time() - started > timeout:
                raise SystemExit(f"STOP: execution {execution_id} still {state} after {timeout} s")
            time.sleep(3)


def execution_summary(e):
    return {"id": e.get("id"), "namespace": e.get("namespace"), "flowId": e.get("flowId"), "state": (e.get("state") or {}).get("current"),
            "labels": e.get("labels"), "taskRunList": [{"taskId": t["taskId"], "state": t["state"]["current"]} for t in e.get("taskRunList") or []],
            "…": "(the rest of the execution - inputs, outputs, task logs - left out of this log)"}


def mask(v):
    if isinstance(v, dict):
        return {k: ("*** (masked)" if isinstance(k, str) and SECRET_KEY.search(k) and not str(val).startswith("${secret:")
                    else f"<{len(val) if isinstance(val, list) else len(str(val).splitlines())} lines of the component's run log, left out of this API log>"
                    if k in ("log", "logs") and isinstance(val, (str, list))
                    else mask(val)) for k, val in v.items()}
    if isinstance(v, list):
        return [mask(x) for x in v]
    if isinstance(v, bytes):
        return f"<{len(v)} bytes, sha256 {hashlib.sha256(v).hexdigest()[:16]}…>"
    return v


def text_or_summary(path, content):
    if path.endswith((".properties", ".yaml")):
        return content.decode("utf-8", "replace")
    return f"<binary {Path(path).name}: {len(content)} bytes, sha256 {hashlib.sha256(content).hexdigest()[:16]}…>"


def read_env():
    env = {}
    for line in (LOCAL / ".env").read_text(encoding="utf-8").splitlines():
        m = re.match(r"^([A-Z_]+)=(.*)$", line)
        if m:
            env[m.group(1)] = m.group(2).strip().strip("'\"")
    return env


def build(api):
    started = datetime.now()
    c = f"/cr/api/projects/{PROJECT}/components"

    existing = api.call("The projects (with deleted ones)", "GET", "/cr/api/processes?hidden=true")
    if any(p["project"] == PROJECT for p in existing["processes"]):
        raise SystemExit(f"STOP: {PROJECT} exists already - remove it first.")

    for rel in ["bin/store.sh", "bin/component.sh", "wrappers/component-wrappers-0.1.0.jar", "lib/postgresql-42.7.4.jar"]:
        api.upload_file(f"Platform file {rel} (from this repository)", NS, "/" + rel, (PLATFORM / rel).read_bytes())
    api.upload_file("The project config, written from scratch", NS, f"/config/{NS}.properties", CONFIG.encode())
    api.call("KV SHARED_DATA_DIR: where orders keep their folders", "PUT", f"/api/v1/main/namespaces/{NS}/kv/SHARED_DATA_DIR",
             json.dumps("/mnt/shared_data"), "text/plain")
    api.call("What the namespace now holds", "GET", f"/api/v1/main/namespaces/{NS}/files/directory?path=%2F")

    api.call("Create the project: an empty draft v1", "POST", "/cr/api/processes",
             {"project": PROJECT, "name": "FileCR sample built through the APIs", "namespace": NS})

    api.call("Declare the component", "POST", c, {"name": COMPONENT, "kind": "WRAPPED", "description": COMPONENT_SPEC["description"]})
    zip_bytes = FILECR_ZIP.read_bytes()
    api.call("Upload target.zip, unpacked (its top folder target/ is left out)", "PUT",
             f"{c}/{COMPONENT}/versions/1/files?path=&unzip=true", zip_bytes, "application/octet-stream",
             f"<{FILECR_ZIP.name}: {len(zip_bytes)} bytes, sha256 {hashlib.sha256(zip_bytes).hexdigest()[:16]}…>")
    v = api.call("The draft (its revision)", "GET", f"{c}/{COMPONENT}/versions/1")
    saved = api.call("Save the definition: NEI settings, GRC ({dir} = this version's folder), the two actions", "PUT",
                     f"{c}/{COMPONENT}/versions/1", {"spec": COMPONENT_SPEC, "revision": v["revision"]})
    if saved.get("problems"):
        raise SystemExit(f"STOP: component problems {saved['problems']}")
    t = api.call("Try it: GENERATE_CR_CIQ_TEMPLATE once", "POST", f"{c}/{COMPONENT}/versions/1/try",
                 {"action": "GENERATE_CR_CIQ_TEMPLATE",
                  "params": {"REQ_TYPE": "1", "NODE_TYPE": "PGW_RDS", "ACTIVITY_NAME": "1051_SUBSCRIBER_PROFILE_CONFIGURATION",
                             "SUB_ACTIVITY_NAME": "1051_SUBSCRIBER_PROFILE_CONFIGURATION", "NODE_VERSION": "ALL",
                             "CIQ_FILE_LOCATION": "${WORKDIR}/try/", "REQ_ID": "1"}})
    if (t.get("result") or {}).get("statusText") != "SUCCESS":
        raise SystemExit(f"STOP: Try it did not succeed: {json.dumps(t)[:600]}")
    api.call("Publish v1", "POST", f"{c}/{COMPONENT}/versions/1/publish")
    api.call("KV COMPONENT_FILECR, kept by the gateway: the published version's folder", "GET",
             f"/api/v1/main/namespaces/{NS}/kv/COMPONENT_FILECR")
    api.call("The component.properties the gateway generated", "GET",
             f"/api/v1/main/namespaces/{NS}/files?path=%2Fcomponents%2F{COMPONENT}%2Fv1%2Fcomponent.properties",
             accept="application/octet-stream")

    for flow in (FLOW_TEMPLATE, FLOW_VALIDATION):
        api.call(f"Create flow {flow.split()[1]}", "POST", "/api/v1/main/flows", flow, "application/x-yaml", flow)

    d = api.call("The draft v1 (its revision)", "GET", f"/cr/api/processes/{PROJECT}/versions/1")
    saved = api.call("Save the definition: two steps running the two flows", "PUT", f"/cr/api/processes/{PROJECT}/versions/1",
                     {"definition": PROCESS, "revision": d["revision"]})
    if saved.get("problems"):
        raise SystemExit(f"STOP: process problems {saved['problems']}")
    api.call("Publish v1", "POST", f"/cr/api/processes/{PROJECT}/versions/1/publish")
    api.call("What a new order can start with", "GET", f"/cr/api/processes/{PROJECT}/start")

    api.phase = "run"
    s = api.call("Start a new order: the blank CIQ template", "POST", f"/cr/api/processes/{PROJECT}/start/new_order",
                 {"inputs": {"node_type": "PGW_RDS", "activity": "1051_SUBSCRIBER_PROFILE_CONFIGURATION", "node_version": "ALL"}})
    e = api.wait("The template execution", s["execution_id"])
    if e["state"]["current"] != "SUCCESS":
        raise SystemExit(f"STOP: the template execution ended {e['state']['current']}")
    order = {l["key"]: l["value"] for l in e["labels"]}["order_id"]
    api.call("What the order offers next", "GET", f"/cr/api/orders/{order}/next")
    ciq = SAMPLE_CIQ.read_bytes()
    s = api.call("Validate a filled CIQ for the order", "POST", f"/cr/api/orders/{order}/steps/validate",
                 {"inputs": {}, "files": {"ciq": {"filename": SAMPLE_CIQ.name, "base64": base64.b64encode(ciq).decode()}}},
                 shown={"inputs": {}, "files": {"ciq": {"filename": SAMPLE_CIQ.name,
                                                       "base64": f"<{len(ciq)} bytes of {SAMPLE_CIQ.name}, base64>"}}})
    e = api.wait("The validation execution", s["execution_id"])
    api.call("The order afterwards", "GET", f"/cr/api/orders?id={order}")
    kv = api.call(f"KV ORDER_{order}: what the forms and the Order tab read", "GET", f"/api/v1/main/namespaces/{NS}/kv/ORDER_{order}")
    return {"order": order, "validation": e["state"]["current"], "started": started, "order_kv": kv.get("value", kv)}


def report(api, outcome, path):
    def pretty(v):
        if v is None:
            return ""
        text = v if isinstance(v, str) else json.dumps(v, indent=2, ensure_ascii=False)
        return text if len(text) <= 20000 else text[:20000] + f"\n… ({len(text) - 20000} more characters)"

    def size(n):
        return f"{n} B" if n < 1024 else f"{n / 1024:.1f} KB" if n < 1024 * 1024 else f"{n / 1048576:.1f} MB"

    # consecutive identical polls are one row (×N), showing the last answer
    rows = []
    for h in api.hits:
        prev = rows[-1] if rows else None
        if prev and prev["method"] == h["method"] == "GET" and prev["url"] == h["url"] and prev["purpose"] == h["purpose"]:
            prev.update({k: h[k] for k in ("status", "resp_bytes", "resp_headers", "resp_body")})
            prev["times"] += 1
            prev["until"] = h["at"]
        else:
            rows.append({**h, "times": 1, "until": h["at"]})

    def row(i, h):
        path = h["url"].replace(G, "")
        req = f"{h['method']} {path} HTTP/1.1\nHost: {G.split('//')[1]}\n" + "".join(f"{k}: {v}\n" for k, v in h["req_headers"].items())
        if h["req_body"] is not None:
            req += "\n" + pretty(h["req_body"])
        resp = f"HTTP/1.1 {h['status']}\n" + "".join(f"{k}: {v}\n" for k, v in h["resp_headers"].items())
        if h["resp_body"] is not None:
            resp += "\n" + pretty(h["resp_body"])
        times = f" ×{h['times']} (until {h['until']}, last answer shown)" if h["times"] > 1 else ""
        return f"""<details class="hit"><summary>
<span class="n">{i}</span><span class="t">{h['at']}</span><span class="m m-{h['method']}">{h['method']}</span>
<code class="u">{html.escape(path)}</code><span class="s {'ok' if 200 <= h['status'] < 300 else 'bad'}">{h['status']}</span>
<span class="d">{h['ms']} ms · ↑ {size(h['req_bytes'])} · ↓ {size(h['resp_bytes'])}{times}</span>
<span class="p">{html.escape(h['purpose'])}</span></summary>
<div class="io"><div><h3>Request</h3><pre>{html.escape(req)}</pre></div><div><h3>Response</h3><pre>{html.escape(resp)}</pre></div></div></details>"""

    config = [r for r in rows if r["phase"] == "config"]
    run = [r for r in rows if r["phase"] == "run"]
    body = "<h2>Configuration</h2>" + "".join(row(i, r) for i, r in enumerate(config, 1))
    if run:
        body += ("<h2>Test run</h2><p class='lead'>The hits that ran one order through the configured process, to prove it works.</p>"
                 + "".join(row(i, r) for i, r in enumerate(run, len(config) + 1)))
    first, last = (api.hits[0]["at"], api.hits[-1]["at"]) if api.hits else ("", "")
    path.write_text(f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CR_AUTO API hits</title><style>
:root {{ --bg:#f7f7f9; --card:#fff; --text:#1d1d24; --dim:#5f6070; --line:#dddde6; --code:#f0f0f5; --ok:#1a7f4b; --bad:#b42318;
  --get:#2459c4; --post:#1a7f4b; --put:#9a5b00; --delete:#b42318; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#14141a; --card:#1d1d25; --text:#e8e8ef; --dim:#a0a0b2; --line:#33333f;
  --code:#262630; --ok:#4cc38a; --bad:#ff7a6b; --get:#7aa7ff; --post:#4cc38a; --put:#f0b45a; --delete:#ff7a6b; }} }}
body {{ margin:0; background:var(--bg); color:var(--text); font:14px/1.5 system-ui,sans-serif; }}
main {{ max-width:1280px; margin:0 auto; padding:24px 16px 64px; }}
h1 {{ font-size:1.5rem; margin:0 0 4px; }} h2 {{ font-size:1.05rem; margin:28px 0 8px; }}
h3 {{ font-size:.75rem; margin:0 0 4px; color:var(--dim); text-transform:uppercase; letter-spacing:.05em; }}
.lead {{ color:var(--dim); margin:0 0 10px; }}
.hit {{ background:var(--card); border:1px solid var(--line); border-radius:6px; margin:4px 0; }}
summary {{ cursor:pointer; padding:6px 10px; display:flex; gap:10px; align-items:baseline; flex-wrap:wrap; font-family:ui-monospace,Consolas,monospace; font-size:.82rem; }}
.n {{ color:var(--dim); min-width:2em; text-align:right; }} .t {{ color:var(--dim); }} .m {{ font-weight:700; min-width:3.6em; }}
.m-GET {{ color:var(--get); }} .m-POST {{ color:var(--post); }} .m-PUT {{ color:var(--put); }} .m-DELETE {{ color:var(--delete); }}
.u {{ word-break:break-all; }} .s {{ font-weight:700; }} .ok {{ color:var(--ok); }} .bad {{ color:var(--bad); }} .d {{ color:var(--dim); }}
.p {{ color:var(--dim); flex-basis:100%; padding-left:3em; font-family:system-ui,sans-serif; }}
.io {{ display:grid; grid-template-columns:1fr 1fr; gap:10px; padding:10px; border-top:1px solid var(--line); }}
@media (max-width:800px) {{ .io {{ grid-template-columns:1fr; }} }}
pre {{ background:var(--code); padding:10px; border-radius:6px; overflow:auto; max-height:480px; font-size:.78rem; margin:0; white-space:pre-wrap; word-break:break-word; }}
</style></head><body><main>
<h1>CR_AUTO – API hit log</h1>
<p class="lead">{len(api.hits)} HTTP hits to {G} from {first} to {last} ({datetime.now():%Y-%m-%d}), in order. Gateway API: /cr/api/…; Kestra API: /api/v1/main/….
Click a hit for its request (line, headers, payload) and response (status, headers, body). Authorization is masked; repository jars and scripts are shown by size and hash; the config file and flows in full.</p>
{body}</main></body></html>""", encoding="utf-8")


def remove(api):
    for f in api.call("flows", "GET", f"/api/v1/main/flows/{NS}", record=False) or []:
        api.call("delete flow", "DELETE", f"/api/v1/main/flows/{NS}/{f['id']}", record=False, expect=(200, 204, 404))
    api.call("delete files", "DELETE", f"/api/v1/main/namespaces/{NS}/files?path=%2F", record=False, expect=(200, 204, 404))
    for k in ("SHARED_DATA_DIR", "COMPONENT_FILECR", "ORDERS", "REQ_IDS_TAKEN"):
        api.call("delete KV", "DELETE", f"/api/v1/main/namespaces/{NS}/kv/{k}", record=False, expect=(200, 204, 404))
    import subprocess
    sql = f"""
      delete from cr_automation.component_version where component_id in (select component_id from cr_automation.component where project='{PROJECT}');
      delete from cr_automation.component where project='{PROJECT}';
      delete from cr_automation.request_value where req_id in (select req_id from cr_automation.request where process_id in (select process_id from cr_automation.process where project='{PROJECT}'));
      delete from cr_automation.request_step where req_id in (select req_id from cr_automation.request where process_id in (select process_id from cr_automation.process where project='{PROJECT}'));
      delete from cr_automation.request where process_id in (select process_id from cr_automation.process where project='{PROJECT}');
      delete from cr_automation.process_version where process_id in (select process_id from cr_automation.process where project='{PROJECT}');
      delete from cr_automation.process where project='{PROJECT}';"""
    subprocess.run(["docker", "exec", "kestra-local-postgres-1", "psql", "-U", "kestra", "-d", "kestra", "-q", "-c", sql], check=True)
    print(f"{PROJECT} removed")


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--report", default=str(REPO / "analysis" / "cr_auto_api_calls.html"))
    a.add_argument("--remove", action="store_true")
    args = a.parse_args()
    env = read_env()
    api = Api(env["KESTRA_USER"], env["KESTRA_PASSWORD"])
    if args.remove:
        remove(api)
        return
    outcome, error = None, None
    try:
        outcome = build(api)
    except SystemExit as e:
        error = str(e)
    report(api, outcome, Path(args.report))
    print(f"{len(api.hits)} hits logged in {args.report}")
    if error:
        print(error)
        sys.exit(1)
    print(f"order {outcome['order']}: validation {outcome['validation']}")


if __name__ == "__main__":
    main()
