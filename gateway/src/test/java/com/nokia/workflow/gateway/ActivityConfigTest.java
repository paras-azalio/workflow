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
    public void executionSettingsNameAModeAndNodePatterns() {
        assertEquals(List.of(), problems("""
                {"execution": {"mode": "QUEUED", "nodes": {"SOUTH*": "PARALLEL"}}}"""));
        String all = String.join("\n", problems("""
                {"execution": {"mode": "SERIAL", "nodes": {"a b": "QUEUED", "X": "LATER"}, "order": 1}}"""));
        assertTrue(all.contains("execution.mode must be PARALLEL or QUEUED"), all);
        assertTrue(all.contains("execution.nodes.a b: a node pattern is"), all);
        assertTrue(all.contains("execution.nodes.X must be PARALLEL or QUEUED"), all);
        assertTrue(all.contains("execution: unknown key 'order'"), all);
    }

    @Test
    public void theProjectsLoginAndGrcValuesAreReadPerComponent() throws Exception {
        java.util.Map<String, Object> d = ActivityStore.projectDefaults("""
                nei.clicr.grc.NIAM_IP=127.0.0.1
                nei.clicr.login.M2MUSER1=m2madmin
                nei.clicr.login.M2MPASSWORD1=${secret:CLICR_M2MPASSWORD1}
                nei.clicr.configdir=nei/clicr/config
                nei.filecr.grc.yaml_path=${WORKDIR}/templates/rules
                store.jdbc.user=kestra
                """);
        assertEquals(java.util.Map.of("login", java.util.Map.of("M2MUSER1", "m2madmin", "M2MPASSWORD1", "${secret:CLICR_M2MPASSWORD1}"),
                "grc", java.util.Map.of("NIAM_IP", "127.0.0.1")), d.get("clicr"));
        assertEquals(java.util.Map.of("login", java.util.Map.of(), "grc", java.util.Map.of("yaml_path", "${WORKDIR}/templates/rules")), d.get("filecr"));
        assertEquals(2, d.size(), "only nei.<component>.login / grc lines count");
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
