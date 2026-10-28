package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PipelineTest {

    /** 33: precheck, mail to the owner, the owner's reply decides whether the main MOP runs on the node. */
    static final String PRECHECK = """
            [{"id": "route", "widget": "IF", "conditions": [{"ref": "activity.sub_activity", "op": "equals", "value": "33_LIC_LOADING_IN_SBC"}],
              "then": [
                {"id": "precheck", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION", "step": "PRECHECK",
                 "sub_activity": "${activity.sub_activity}_PRECHECK"},
                {"id": "owner_mail", "widget": "MAIL", "to": "${ciq.OWNER_EMAIL}", "subject": "Precheck of ${node} for order ${order.id}",
                 "body": "Reply APPROVED or REJECTED. Result: ${step.precheck.statusText}"},
                {"id": "owner_reply", "widget": "DECISION", "mode": "REPLY", "question": "Go ahead on ${node}?", "reply_to": "owner_mail",
                 "timeout": "P2D", "on_timeout": "REJECTED"},
                {"id": "approved", "widget": "IF", "conditions": [{"ref": "step.owner_reply.decision", "op": "equals", "value": "APPROVED"}],
                 "then": [{"id": "main", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION"}],
                 "else": [{"id": "abort", "widget": "END", "status": "FAILED", "message": "The owner answered ${step.owner_reply.decision}"}]}],
              "else": [{"id": "main_default", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION"}]}]""";

    /** 1051: per IMSI that changed, a mail and its reply; the IMSIs are collected into two lists of the child. */
    static final String MMTEL = """
            [{"id": "main", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION"},
             {"id": "failures", "widget": "COLLECT", "list": "FAILURE_IMSIS", "value": "${step.main.FAILURE_IMSIS}"},
             {"id": "per_imsi", "widget": "FOR_EACH", "items": "${step.main.SUCCESS_IMSIS}", "concurrency": 5, "body": [
                {"id": "mmtel_mail", "widget": "MAIL", "to": "mmtel@example.com", "subject": "MMTel refresh ${item}"},
                {"id": "mmtel_reply", "widget": "DECISION", "mode": "REPLY", "question": "MMTel ${item}", "reply_to": "mmtel_mail",
                 "options": ["SUCCESS", "FAILURE"], "interval": "PT1M", "timeout": "PT30M", "on_timeout": "FAILURE"},
                {"id": "ok", "widget": "IF", "conditions": [{"ref": "step.mmtel_reply.decision", "op": "equals", "value": "SUCCESS"}],
                 "then": [{"id": "keep_ok", "widget": "COLLECT", "list": "MMTEL_SUCCESS", "value": "${item}"}],
                 "else": [{"id": "keep_failed", "widget": "COLLECT", "list": "MMTEL_FAILURE", "value": "${item}"}]}]}]""";

    private static Pipeline parse(Pipeline.Hook hook, String json, List<String> problems) {
        return Pipeline.parse("test", hook, SimpleJson.parse(json), problems);
    }

    private static List<String> problems(Pipeline.Hook hook, String json) {
        List<String> p = new ArrayList<>();
        assertNull(parse(hook, json, p), "expected problems");
        return p;
    }

    private static boolean has(List<String> problems, String part) {
        return problems.stream().anyMatch(p -> p.contains(part));
    }

    @Test
    public void the33And1051PipelinesAreValid() {
        List<String> p = new ArrayList<>();
        Pipeline precheck = parse(Pipeline.Hook.NODE, PRECHECK, p);
        Pipeline mmtel = parse(Pipeline.Hook.NODE, MMTEL, p);
        assertEquals(List.of(), p);
        assertEquals(List.of("route", "precheck", "owner_mail", "owner_reply", "approved", "main", "abort", "main_default"),
                Pipeline.flatten(precheck.widgets).stream().map(Pipeline.Widget::id).toList());
        assertEquals(8, Pipeline.flatten(mmtel.widgets).size());
    }

    @Test
    public void referencesMustBeKnownWhereTheyAreUsed() {
        List<String> p = problems(Pipeline.Hook.AFTER_NODES, """
                [{"id": "a", "widget": "COLLECT", "list": "X", "value": "${node}"},
                 {"id": "b", "widget": "COLLECT", "list": "X", "value": "${item}"},
                 {"id": "c", "widget": "COLLECT", "list": "X", "value": "${step.later.message}"},
                 {"id": "loop", "widget": "FOR_EACH", "items": "${child.LIST}", "body": [
                    {"id": "inner", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION"}]},
                 {"id": "d", "widget": "COLLECT", "list": "X", "value": "${step.inner.message}"},
                 {"id": "later", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION"},
                 {"id": "e", "widget": "COLLECT", "list": "X", "value": "${secret:PW} ${nonsense.x} ${run.nodes}"}]""");
        assertTrue(has(p, "widget 'a': value: ${node}: ${node} is known only in the node pipeline."), p.toString());
        assertTrue(has(p, "widget 'b': value: ${item}: ${item} is known only inside a For each."), p.toString());
        assertTrue(has(p, "widget 'c': value: ${step.later.message}: no widget 'later' runs before this point"), p.toString());
        assertTrue(has(p, "widget 'd': value: ${step.inner.message}: no widget 'inner' runs before this point"), p.toString());
        assertTrue(has(p, "${secret:PW}: secrets and ${WORKDIR} are allowed only in NEI parameters and mail fields."), p.toString());
        assertTrue(has(p, "${nonsense.x}: unknown reference."), p.toString());
        assertFalse(has(p, "${run.nodes}"), "run.X is known in the after_nodes pipeline: " + p);
    }

    @Test
    public void widgetsAreCheckedOneByOne() {
        List<String> p = problems(Pipeline.Hook.NODE, """
                [{"id": "x", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION", "params": {"REQ_ID": "9", "A": "{{ evil }}"}},
                 {"id": "x", "widget": "END"},
                 {"id": "ask", "widget": "DECISION", "mode": "REPLY", "question": "?", "reply_to": "x", "options": ["OK"]},
                 {"id": "branch", "widget": "IF", "conditions": [{"ref": "node", "op": "bigger"}]},
                 {"id": "y", "widget": "SLEEP"},
                 {"id": "Bad-Id", "widget": "END"}]""");
        assertTrue(has(p, "parameter 'REQ_ID' identifies the order"), p.toString());
        assertTrue(has(p, "params.A: write references as ${...}"), p.toString());
        assertTrue(has(p, "widget 'x': the id is used twice"), p.toString());
        assertTrue(has(p, "'reply_to' must be the id of a MAIL widget before this decision."), p.toString());
        assertTrue(has(p, "a decision needs at least two options."), p.toString());
        assertTrue(has(p, "condition on 'node': 'op' must be one of"), p.toString());
        assertTrue(has(p, "'then' or 'else' must hold at least one widget."), p.toString());
        assertTrue(has(p, "unknown widget 'SLEEP'"), p.toString());
        assertTrue(has(p, "widget 'Bad-Id': 'id' must be"), p.toString());
    }

    @Test
    public void theCompiledFlowRunsEachWidgetAsKestraTasks() {
        List<String> p = new ArrayList<>();
        Pipeline pipeline = parse(Pipeline.Hook.NODE, PRECHECK, p);
        PipelineCompiler.Compiled c = PipelineCompiler.compile("cr_automation", "CR_AUTOMATION", 2, "mop_execution", pipeline);
        assertEquals("pl_cr_automation_mop_execution_node_v2", c.flowId());
        String y = c.yaml();
        assertTrue(y.contains("id: \"pl_cr_automation_mop_execution_node_v2\""), y);
        assertTrue(y.contains("type: \"io.kestra.plugin.core.flow.If\""), y);
        assertTrue(y.contains("condition: \"{{ (('' ~ (outputs.context.vars.activity['sub_activity'] ?? ''))) == '33_LIC_LOADING_IN_SBC' }}\""), y);
        assertTrue(y.contains("type: \"io.kestra.plugin.core.flow.LoopUntil\""), y);
        assertTrue(y.contains("maxDuration: \"P2D\""), y);
        assertTrue(y.contains("sh bin/nei.sh clicr \\\"$LOG_NAME\\\""), y);
        assertTrue(y.contains("STORE_STEP: \"PRECHECK\""), y);
        assertTrue(y.contains("{{ ('' ~ (outputs.context.vars.activity['sub_activity'] ?? '')) }}_PRECHECK"), y);
        assertTrue(y.contains("sh bin/command.sh mail.default"), y);
        assertTrue(y.contains("step NODE_PIPELINE"), "the node's outcome is recorded: " + y);
        assertTrue(y.contains("afterExecution:"), y);
    }

    @Test
    public void aValueNeverBecomesPartOfAShellCommand() {
        List<String> p = new ArrayList<>();
        Pipeline pipeline = parse(Pipeline.Hook.NODE, """
                [{"id": "m", "widget": "MAIL", "to": "a@b.c", "subject": "x\\"; rm -rf / #'$(id)"}]""", p);
        assertEquals(List.of(), p);
        String y = PipelineCompiler.compile("cr_automation", "P", 1, "s", pipeline).yaml();
        for (String line : y.split("\n")) {
            if (line.trim().startsWith("- \"sh ")) {
                assertFalse(line.contains("rm -rf"), "the subject is in the environment, not the command: " + line);
            }
        }
        assertTrue(y.contains("rm -rf / #'$(id)"), y);
    }

    @Test
    public void aForEachRunsOnlyWhenItHasItemsAndReadsTheItem() {
        List<String> p = new ArrayList<>();
        String y = PipelineCompiler.compile("cr_automation", "P", 1, "s", parse(Pipeline.Hook.NODE, MMTEL, p)).yaml();
        assertEquals(List.of(), p);
        assertTrue(y.contains("type: \"io.kestra.plugin.core.flow.Loop\""), y);
        assertTrue(y.contains("concurrencyLimit: 5"), y);
        assertTrue(y.contains("| length) > 0 }}"), y);
        assertTrue(y.contains("--name MMTEL_SUCCESS"), y);
        assertTrue(y.contains("(item.value ?? '')"), y);
        assertTrue(y.contains("_{{ item.index }}"), "each item has its own log: " + y);
    }

    @Test
    public void aManualDecisionPausesWithItsOptions() {
        List<String> p = new ArrayList<>();
        String y = PipelineCompiler.compile("cr_automation", "P", 1, "s", parse(Pipeline.Hook.AFTER_NODES, """
                [{"id": "go", "widget": "DECISION", "question": "Run the post-completion for ${run.succeeded_nodes}?",
                  "options": ["YES", "NO"], "timeout": "PT4H", "on_timeout": "FAIL"},
                 {"id": "done", "widget": "END", "status": "SUCCESS"}]""", p)).yaml();
        assertEquals(List.of(), p);
        assertTrue(y.contains("type: \"io.kestra.plugin.core.flow.Pause\""), y);
        assertTrue(y.contains("behavior: \"FAIL\""), y);
        assertTrue(y.contains("- \"YES\""), y);
        assertTrue(y.contains("type: \"io.kestra.plugin.core.execution.Exit\""), y);
        assertFalse(y.contains("afterExecution"), "only a node pipeline records a node outcome: " + y);
        assertTrue(y.contains("--nodes \\\"{{ inputs.nodes }}\\\""), y);
    }

    @Test
    public void aProcessStepMayCarryPipelinesOnlyAsAChildStepAndOnePerFlow() throws Exception {
        String step = """
                {"id": "%s", "label": "x", "scope": "%s", "runs": {"flow": "cr_mop_execution"},
                 "pipelines": {"node": [{"id": "e", "widget": "END", "status": "SUCCESS"}]}}""";
        List<String> p = org.junit.jupiter.api.Assertions.assertThrows(ProcessDefinition.Invalid.class, () ->
                ProcessDefinition.parse(SimpleJson.parse("{\"namespace\": \"cr_automation\", \"steps\": [" + step.formatted("a", "ORDER")
                        + "]}"))).problems;
        assertTrue(has(p, "only a CHILD step can have pipelines"), p.toString());
        p = org.junit.jupiter.api.Assertions.assertThrows(ProcessDefinition.Invalid.class, () ->
                ProcessDefinition.parse(SimpleJson.parse("{\"namespace\": \"cr_automation\", \"steps\": [" + step.formatted("a", "CHILD")
                        + ", " + step.formatted("b", "CHILD") + "]}"))).problems;
        assertTrue(has(p, "both attach pipelines to flow cr_mop_execution"), p.toString());
        ProcessDefinition ok = ProcessDefinition.parse(SimpleJson.parse("{\"namespace\": \"cr_automation\", \"steps\": ["
                + step.formatted("a", "CHILD") + "]}"));
        assertNotNull(ok.step("a").pipelines().get(Pipeline.Hook.NODE));
    }

    @Test
    public void aReplysSenderTimeAndSubjectCanBeCollectedWholeWithTheirOwnSeparator() {
        String json = """
                [{"id": "each", "widget": "FOR_EACH", "items": "A,B", "body": [
                   {"id": "m", "widget": "MAIL", "to": "x@y.z", "subject": "Refresh ${item}"},
                   {"id": "r", "widget": "DECISION", "mode": "REPLY", "question": "?", "reply_to": "m", "options": ["SUCCESS", "FAILURE"]},
                   {"id": "body", "widget": "COLLECT", "list": "EMAIL_BODY_IMSI", "separator": ";",
                    "value": "(INTERNAL_IMSI_BODY)${item}~|~${step.r.from}~|~${step.r.received_at}~|~${step.r.subject}~|~${step.r.comment}"}]}]""";
        List<String> p = new ArrayList<>();
        String y = PipelineCompiler.compile("cr_automation", "P", 1, "s", parse(Pipeline.Hook.NODE, json, p)).yaml();
        assertEquals(List.of(), p);
        assertTrue(y.contains("SEPARATOR: \";\""), y);
        assertTrue(y.contains("--separator \\\"$SEPARATOR\\\""), y);
        assertTrue(y.contains("results['RECEIVED_AT']"), y);
        assertTrue(y.contains("MAIL_SUBJECT=$P_"), "the reply reader gets the mail's subject: " + y);
        assertTrue(has(problems(Pipeline.Hook.NODE, """
                [{"id": "c", "widget": "COLLECT", "list": "X", "value": "v", "separator": ""}]"""), "'separator' must be 1-8 characters"));
    }

    @Test
    public void yamlScalarsAreAlwaysQuoted() {
        assertEquals("\"a: \\\"b\\\"\\n#c\"", PipelineCompiler.Yaml.scalar("a: \"b\"\n#c"));
        assertEquals("5", PipelineCompiler.Yaml.scalar(5));
        assertEquals("true", PipelineCompiler.Yaml.scalar(true));
    }
}
