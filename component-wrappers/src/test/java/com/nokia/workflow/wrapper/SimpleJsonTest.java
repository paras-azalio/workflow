package com.nokia.workflow.wrapper;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class SimpleJsonTest {

    @Test
    public void writesAndReadsBackAFlatObject() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("A", "x\"y\\z\nnext\ttab");
        m.put("N", 9);
        m.put("B", true);
        m.put("NULL", null);
        Map<String, String> back = SimpleJson.parseFlatObject(SimpleJson.write(m));
        assertEquals("x\"y\\z\nnext\ttab", back.get("A"));
        assertEquals("9", back.get("N"));
        assertEquals("true", back.get("B"));
        assertEquals("", back.get("NULL"));
        assertEquals(Arrays.asList("A", "N", "B", "NULL"), Arrays.asList(back.keySet().toArray()));
    }

    @Test
    public void flattensNestedObjectsAndJoinsArrays() {
        Map<String, String> m = SimpleJson.parseFlatObject(
                "{ \"NODE_TYPE\": \"PGW_RDS\", \"cfg\": { \"dir\": \"/x\", \"n\": 2 }, \"list\": [\"CR1\", \"CR2\"],"
                        + " \"u\": \"\\u00e9\" }");
        assertEquals("PGW_RDS", m.get("NODE_TYPE"));
        assertEquals("/x", m.get("cfg.dir"));
        assertEquals("2", m.get("cfg.n"));
        assertEquals("CR1,CR2", m.get("list"));
        assertEquals("\u00e9", m.get("u"));
    }

    @Test
    public void compactIsOneLine() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("a", Arrays.asList("x", "y"));
        String c = SimpleJson.writeCompact(m);
        assertTrue(!c.contains("\n"), c);
        assertEquals("{\"a\": [\"x\",\"y\"]}", c);
    }

    @Test
    public void rejectsBadInput() {
        for (String bad : new String[] {"[1,2]", "{\"a\":}", "{\"a\":\"x\"} trailing", "{\"a\" \"x\"}", ""}) {
            try {
                SimpleJson.parseFlatObject(bad);
                fail("accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
