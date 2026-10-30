package io.azalio.workflow.wrapper.store;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestStoreTest {

    @Test
    public void optionsStopAtDoubleDashSoTheLockedCommandKeepsItsOwnArguments() {
        String[] args = {"locked", "--key", "summary:81", "--config", "c.properties", "--",
                "sh", "bin/nei.sh", "filecr", "summary_report", "--param", "ACTION=GENERATE_REPORT"};
        Map<String, String> opt = RequestStore.options(args);
        assertEquals("summary:81", opt.get("key"));
        assertEquals("c.properties", opt.get("config"));
        assertFalse(opt.containsKey("param"));
        assertEquals(Arrays.asList("sh", "bin/nei.sh", "filecr", "summary_report", "--param", "ACTION=GENERATE_REPORT"),
                RequestStore.commandAfterDashes(args));
    }

    @Test
    public void nodePatternsUseStarAndQuestionMarkOnly() {
        assertTrue(RequestStore.glob("NORTH1", "NORTH1"));
        assertTrue(RequestStore.glob("SOUTH*", "SOUTH12"));
        assertTrue(RequestStore.glob("N?RTH1", "NORTH1"));
        assertFalse(RequestStore.glob("NORTH1", "NORTH10"));
        assertFalse(RequestStore.glob("N.RTH1", "NORTH1"), "a dot is a dot, not a regex wildcard");
    }

    @Test
    public void referencesAreResolvedAndSecretsAndWorkdirAreLeftForTheWrapper() {
        Map<String, Object> order = new java.util.LinkedHashMap<String, Object>();
        order.put("req_id", 77L);
        order.put("folder", "/mnt/shared_data/77");
        Map<String, Object> child = new java.util.LinkedHashMap<String, Object>();
        child.put("req_id", 85L);
        child.put("cr_name", "CR1");
        Map<String, Object> ciq = new java.util.LinkedHashMap<String, Object>();
        ciq.put("REPORT_EMAIL", "ops@example.com");
        assertEquals("/mnt/shared_data/77/lic", RequestStore.resolve("K", "${order.folder}/lic", order, child, "NORTH1", ciq));
        assertEquals("85-CR1-NORTH1", RequestStore.resolve("K", "${child.id}-${child.cr_name}-${node}", order, child, "NORTH1", ciq));
        assertEquals("ops@example.com", RequestStore.resolve("K", "${ciq.REPORT_EMAIL}", order, child, null, ciq));
        assertEquals("${secret:PW} ${WORKDIR}/x", RequestStore.resolve("K", "${secret:PW} ${WORKDIR}/x", order, child, null, ciq));
    }

    @Test
    public void unknownOrMissingReferencesFailTheStepInsteadOfSendingGarbage() {
        Map<String, Object> order = new java.util.LinkedHashMap<String, Object>();
        order.put("req_id", 77L);
        assertThrows(RuntimeException.class, () -> RequestStore.resolve("K", "${order.nope}", order, null, null, null));
        assertThrows(RuntimeException.class, () -> RequestStore.resolve("K", "${child.cr_name}", order, null, null, null),
                "an order step has no child");
        assertThrows(RuntimeException.class, () -> RequestStore.resolve("K", "${node}", order, null, null, null),
                "a step without a node");
        assertThrows(RuntimeException.class, () -> RequestStore.resolve("K", "${env.HOME}", order, null, null, null));
    }

    @Test
    public void theMostSpecificLevelDecidesHowANodeRuns() {
        java.util.List<Map<String, Object>> layers = Arrays.asList(
                layer("{\"execution\": {\"mode\": \"QUEUED\", \"nodes\": {\"EAST*\": \"QUEUED\"}}}"),
                layer("{\"execution\": {\"nodes\": {\"SOUTH*\": \"QUEUED\"}}}"),
                layer("{\"execution\": {\"mode\": \"PARALLEL\", \"nodes\": {\"SOUTH9\": \"PARALLEL\"}}}"));
        assertEquals("PARALLEL", RequestStore.configuredMode(layers, "NORTH1"), "the sub-activity's mode beats the project's");
        assertEquals("QUEUED", RequestStore.configuredMode(layers, "SOUTH1"), "a node pattern beats every mode");
        assertEquals("PARALLEL", RequestStore.configuredMode(layers, "SOUTH9"), "the most specific node pattern wins");
        assertEquals("QUEUED", RequestStore.configuredMode(layers, "EAST1"), "a project node pattern beats a sub-activity mode");
        assertEquals("PARALLEL", RequestStore.configuredMode(Arrays.asList(layer("{}")), "NORTH1"), "nothing set: parallel");
        assertEquals("PARALLEL", RequestStore.configuredMode(new java.util.ArrayList<Map<String, Object>>(), "NORTH1"));
    }

    private static Map<String, Object> layer(String config) {
        Map<String, Object> l = new java.util.HashMap<String, Object>();
        l.put("config", io.azalio.workflow.wrapper.SimpleJson.parse(config));
        return l;
    }

    @Test
    public void csvTrimsAndDropsEmptyAndDuplicateNodes() {
        assertEquals(Arrays.asList("NORTH1", "SOUTH1"), RequestStore.csv(" NORTH1, ,SOUTH1,NORTH1 "));
        assertTrue(RequestStore.csv(null).isEmpty());
    }
}
