package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProcessDefinitionTest {

    static String shipped() throws Exception {
        try (InputStream in = ProcessDefinitionTest.class.getResourceAsStream("/process/CR_AUTOMATION.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> problems(String json) {
        return assertThrows(ProcessDefinition.Invalid.class, () -> ProcessDefinition.parse(SimpleJson.parse(json))).problems;
    }

    @Test
    public void theShippedProcessIsValidAndReproducesTodaysSteps() throws Exception {
        ProcessDefinition def = ProcessDefinition.parse(SimpleJson.parse(shipped()));
        assertEquals("cr_automation", def.namespace);
        assertEquals(List.of("new_order_template", "new_order_validation", "validate", "child_order", "rollback_order_node",
                "mop_execution", "rollback_child_node"), def.steps.stream().map(ProcessDefinition.Step::id).toList());
        assertEquals("cr_mop_execution", def.step("mop_execution").flow());
        assertEquals(ProcessDefinition.Scope.CHILD, def.step("mop_execution").scope());
    }

    @Test
    public void everyProblemIsReportedNotOnlyTheFirst() {
        List<String> p = problems("""
                {"namespace": "cr_automation", "steps": [
                  {"id": "a", "label": "A", "scope": "ORDER", "runs": {"flow": "f"},
                   "inputs": {"x": {"from": "child.child_id"}}},
                  {"id": "a", "label": "A2", "scope": "ORDER", "runs": {"flow": "f"}},
                  {"id": "Bad Id", "label": "", "scope": "NODE", "runs": {}},
                  {"id": "c", "label": "C", "scope": "ORDER", "starts_order": true, "runs": {"flow": "f"},
                   "inputs": {"o": {"from": "order.value"}}},
                  {"id": "d", "label": "D", "scope": "CHILD", "runs": {"flow": "f"},
                   "available_when": {"child.stage": {"like": "x"}},
                   "inputs": {"s": {"from": "user", "type": "SELECT"}, "v": {"value": "1", "from": "user"}}}
                ]}""");
        String all = String.join("\n", p);
        assertTrue(all.contains("'child.child_id' needs a CHILD step"), all);
        assertTrue(all.contains("Step id 'a' is used twice"), all);
        assertTrue(all.contains("'id' must be lower-case"), all);
        assertTrue(all.contains("'label' is required"), all);
        assertTrue(all.contains("'scope' must be ORDER or CHILD"), all);
        assertTrue(all.contains("'runs.flow' must name"), all);
        assertTrue(all.contains("a step that starts an order has no order yet"), all);
        assertTrue(all.contains("unknown operator 'like'"), all);
        assertTrue(all.contains("needs exactly one of 'options'"), all);
        assertTrue(all.contains("give exactly one of 'value'"), all);
    }

    @Test
    public void theDiagramMayOnlyJoinAndPlaceExistingSteps() {
        String all = String.join("\n", problems("""
                {"namespace": "cr_automation",
                 "layout": {"a": {"x": 10, "y": 20.5}, "ghost": {"x": 0, "y": 0}, "b": {"x": "left"}},
                 "steps": [
                  {"id": "a", "label": "A", "scope": "ORDER", "runs": {"flow": "f"}, "follows": ["a"]},
                  {"id": "b", "label": "B", "scope": "ORDER", "runs": {"flow": "f"}, "follows": ["a", "nope"]}
                ]}"""));
        assertTrue(all.contains("Step 'a' cannot follow itself"), all);
        assertTrue(all.contains("Step 'b' follows 'nope', which is not a step"), all);
        assertTrue(all.contains("layout.ghost: there is no such step"), all);
        assertTrue(all.contains("layout.b must be {x: number, y: number}"), all);
        assertFalse(all.contains("layout.a"), all);
        ProcessDefinition ok = ProcessDefinition.parse(SimpleJson.parse("""
                {"namespace": "cr_automation", "layout": {"b": {"x": 300, "y": 0}}, "steps": [
                  {"id": "a", "label": "A", "scope": "ORDER", "runs": {"flow": "f"}},
                  {"id": "b", "label": "B", "scope": "ORDER", "runs": {"flow": "f"}, "follows": ["a"]}]}"""));
        assertEquals(List.of("a"), ok.step("b").follows());
    }

    @Test
    public void conditionsDecideWhenAStepIsOffered() throws Exception {
        ProcessDefinition def = ProcessDefinition.parse(SimpleJson.parse(shipped()));
        Map<String, Object> validated = Map.of("value", "77", "stage", "VALIDATED", "rollback_options", List.of());
        Map<String, Object> invalid = Map.of("value", "78", "stage", "INVALID");
        assertTrue(ProcessDefinition.available(def.step("child_order"), validated, null));
        assertFalse(ProcessDefinition.available(def.step("child_order"), invalid, null));
        assertTrue(ProcessDefinition.available(def.step("validate"), invalid, null), "no condition: always offered");
        assertFalse(ProcessDefinition.available(def.step("rollback_order_node"), validated, null), "empty list");
        Map<String, Object> child = Map.of("child_id", "82", "mop_nodes", List.of("NORTH1"));
        assertTrue(ProcessDefinition.available(def.step("mop_execution"), validated, child));
        assertFalse(ProcessDefinition.available(def.step("mop_execution"), validated, Map.of("child_id", "84", "mop_nodes", List.of())));
        assertEquals("82", ProcessDefinition.resolve("child.child_id", validated, child));
        assertEquals(null, ProcessDefinition.resolve("order.nothing.deeper", validated, child));
    }
}
