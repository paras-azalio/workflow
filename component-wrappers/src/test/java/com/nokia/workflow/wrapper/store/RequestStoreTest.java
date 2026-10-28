package com.nokia.workflow.wrapper.store;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    public void csvTrimsAndDropsEmptyAndDuplicateNodes() {
        assertEquals(Arrays.asList("NORTH1", "SOUTH1"), RequestStore.csv(" NORTH1, ,SOUTH1,NORTH1 "));
        assertTrue(RequestStore.csv(null).isEmpty());
    }
}
