package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ActivityConfigTest {

    private static List<String> problems(String json) {
        return ActivityConfig.problems(SimpleJson.parse(json));
    }

    @Test
    public void aTypicalConfigurationIsValid() {
        assertEquals(List.of(), problems("""
                {"description": "1051 on the lab",
                 "steps": {"*": {"params": {"LAB": "true"}},
                           "MOP_EXECUTION": {"params": {"ACTIVITY_PYTHON_SCRIPT_PATH": "${WORKDIR}/nei/clicr/script/python",
                                                        "LICENSE_FILE_PATH": "${order.folder}"},
                                             "nodes": {"NORTH1": {"login": {"M2MUSER1": "north_ops",
                                                                            "M2MPASSWORD1": "${secret:CLICR_NORTH_PASSWORD}"}},
                                                       "SOUTH*": {"grc": {"NIAM_IP": "10.0.0.2"}}}}}}"""));
    }

    @Test
    public void secretsAreNeverWrittenAsValues() {
        String all = String.join("\n", problems("""
                {"steps": {"MOP_EXECUTION": {"login": {"M2MPASSWORD1": "plain-text"},
                                             "grc": {"REPO_PASSWORD": "${secret:REPO_PW} and more"}}}}"""));
        assertTrue(all.contains("steps.MOP_EXECUTION.login.M2MPASSWORD1: a secret is written as ${secret:NAME}"), all);
        assertTrue(all.contains("steps.MOP_EXECUTION.grc.REPO_PASSWORD: a secret is written as ${secret:NAME}"), all);
    }

    @Test
    public void theProcessKeysAndUnknownThingsAreRefused() {
        String all = String.join("\n", problems("""
                {"steps": {"MOP_EXECUTION": {"params": {"ACTION": "MOP_GENERATION", "CHILD_REQ_ID": "1", "X": "${env.HOME}",
                                                        "Y": {"nested": 1}},
                                             "nodez": {}},
                           "mop_execution": {},
                           "MOP_GENERATION": {"nodes": {"bad pattern!": {"params": {}}}}},
                 "stepz": {}}"""));
        assertTrue(all.contains("ACTION is decided by the process"), all);
        assertTrue(all.contains("CHILD_REQ_ID is decided by the process"), all);
        assertTrue(all.contains("unknown reference ${env.HOME}"), all);
        assertTrue(all.contains("the value must be text"), all);
        assertTrue(all.contains("unknown key 'nodez'"), all);
        assertTrue(all.contains("steps.mop_execution: a step name is * or upper-case"), all);
        assertTrue(all.contains("a node pattern is letters"), all);
        assertTrue(all.contains("Unknown key 'stepz'"), all);
    }
}
