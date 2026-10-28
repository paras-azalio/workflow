package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StepsTest {

    private final ProcessDefinition def = ProcessDefinition.parse(SimpleJson.parse(ProcessDefinitionTest.shipped()));
    private final Map<String, Object> order = Map.of("value", "77", "stage", "VALIDATED",
            "cr_options", List.of(Map.of("value", "CR1", "label", "CR1 | last child 82 FAILED")));
    private final Map<String, Object> child = Map.of("child_id", "82", "mop_nodes", List.of("NORTH1", "SOUTH1"),
            "node_options", List.of(Map.of("value", "*", "label", "All nodes (2)"), Map.of("value", "NORTH1", "label", "NORTH1 | FAILED"),
                    Map.of("value", "SOUTH1", "label", "SOUTH1 | NOT_EXECUTED")));

    public StepsTest() throws Exception {
    }

    private ProcessDefinition.Input input(String step, String name) {
        return def.step(step).inputs().stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private Map<String, String> fields(String step, String name, Object given) {
        Map<String, String> fields = new LinkedHashMap<>();
        Steps.userValue(input(step, name), given, null, order, child, fields, new LinkedHashMap<>());
        return fields;
    }

    @Test
    public void aSelectAcceptsOnlyTheOfferedValues() {
        assertEquals("CR1", fields("child_order", "cr_name", "CR1").get("cr_name"));
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> fields("child_order", "cr_name", "CR9")).status);
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> fields("child_order", "cr_name", null)).status);
    }

    @Test
    public void allNodesWinsOverSingleNodesAndUnknownNodesAreRefused() {
        assertEquals("[\"*\"]", fields("mop_execution", "nodes", List.of("NORTH1", "*")).get("nodes"));
        assertEquals("[\"NORTH1\",\"SOUTH1\"]", fields("mop_execution", "nodes", List.of("NORTH1", "SOUTH1")).get("nodes"));
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> fields("mop_execution", "nodes", List.of("EAST9"))).status);
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> fields("mop_execution", "nodes", List.of())).status);
    }

    @Test
    public void theAllValueIsNotOfferedAsANode() {
        List<Map<String, Object>> options = Steps.options(input("mop_execution", "nodes"), order, child);
        assertEquals(List.of("NORTH1", "SOUTH1"), options.stream().map(o -> o.get("value")).toList());
    }

    @Test
    public void defaultsAndBooleans() {
        assertEquals("false", fields("child_order", "run_execution", null).get("run_execution"));
        assertEquals("true", fields("child_order", "run_execution", true).get("run_execution"));
        Map<String, String> f = new LinkedHashMap<>();
        Steps.userValue(input("new_order_template", "node_type"), null, null, null, null, f, new LinkedHashMap<>());
        assertEquals("PGW_RDS", f.get("node_type"));
        Map<String, String> none = new LinkedHashMap<>();
        Steps.userValue(input("new_order_template", "order_no"), "  ", null, null, null, none, new LinkedHashMap<>());
        assertFalse(none.containsKey("order_no"), "optional and blank: not sent");
    }

    @Test
    public void filesAreCheckedAndPassedAsFileParts() {
        Map<String, Kestra.FilePart> parts = new LinkedHashMap<>();
        Steps.userValue(input("new_order_validation", "ciq"), null,
                Map.of("filename", "..\\x/ciq.xlsx", "base64", Base64.getEncoder().encodeToString("PK".getBytes(StandardCharsets.UTF_8))),
                null, null, new LinkedHashMap<>(), parts);
        assertEquals(".._x_ciq.xlsx", parts.get("ciq").filename(), "no path separators survive");
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> Steps.userValue(input("new_order_validation", "ciq"), null,
                null, null, null, new LinkedHashMap<>(), new LinkedHashMap<>())).status);
        assertEquals(400, assertThrows(UserStore.Rejected.class, () -> Steps.userValue(input("new_order_validation", "ciq"), null,
                Map.of("base64", "%%%"), null, null, new LinkedHashMap<>(), new LinkedHashMap<>())).status);
        Map<String, Kestra.FilePart> optional = new LinkedHashMap<>();
        Steps.userValue(input("validate", "ciq"), null, null, order, null, new LinkedHashMap<>(), optional);
        assertTrue(optional.isEmpty(), "optional file not given: nothing sent");
    }

    @Test
    public void theMultipartBodyHasOnePartPerInputAndTheFile() {
        String body = new String(Kestra.multipart("B", Map.of("order_id", "77"),
                Map.of("ciq", new Kestra.FilePart("a\"b.xlsx", "XLSX".getBytes(StandardCharsets.UTF_8)))), StandardCharsets.UTF_8);
        assertTrue(body.contains("--B\r\nContent-Disposition: form-data; name=\"order_id\"\r\n"), body);
        assertTrue(body.contains("\r\n\r\n77\r\n"), body);
        assertTrue(body.contains("name=\"ciq\"; filename=\"a%22b.xlsx\""), body);
        assertTrue(body.endsWith("--B--\r\n"), body);
    }
}
