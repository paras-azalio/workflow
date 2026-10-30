package io.azalio.workflow.gateway;

import io.azalio.workflow.wrapper.SimpleJson;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComponentSpecTest {

    private static final Map<String, String> RUNTIMES = Map.of("python3", "python3", "java8", "/opt/java8/bin/java", "shell", "sh");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec(String json) {
        return (Map<String, Object>) SimpleJson.parse(json);
    }

    private static Properties props(String json, String name) throws Exception {
        Properties p = new Properties();
        p.load(new StringReader(ComponentSpec.properties(spec(json), name, ComponentStore.dir(name, 2), RUNTIMES)));
        return p;
    }

    @Test
    public void aStandaloneScriptBecomesACommandOfTheWrapper() throws Exception {
        String json = """
                {"kind": "STANDALONE", "standalone": {"runtime": "python3", "command": "{runtime} {file:check.py} --dir {dir}",
                 "input": "ARGS", "args": "--imsi {IMSI}", "output": "STDOUT_JSON", "success_key": "RESULT", "success_value": "OK"},
                 "actions": [{"name": "CHECK", "params": [{"name": "IMSI", "required": true}], "results": [{"name": "RESULT"}]}]}""";
        assertEquals(List.of(), ComponentSpec.problems(SimpleJson.parse(json)));
        Properties p = props(json, "hlr_check");
        assertEquals("STANDALONE", p.getProperty("component.kind"));
        assertEquals("components/hlr_check/v2", p.getProperty("component.home"));
        assertEquals("python3 ${WORKDIR}/components/hlr_check/v2/check.py --dir ${WORKDIR}/components/hlr_check/v2",
                p.getProperty("command.hlr_check"));
        assertEquals("ARGS", p.getProperty("command.hlr_check.input"));
        assertEquals("--imsi {IMSI}", p.getProperty("command.hlr_check.args"));
        assertEquals("STDOUT_JSON", p.getProperty("command.hlr_check.output"));
        assertEquals("RESULT", p.getProperty("command.hlr_check.success_key"));
        assertEquals("OK", p.getProperty("command.hlr_check.success_value"));
    }

    @Test
    public void aWrappedJarGetsItsNeiSettingsAndAFolderComponentUsesThatFolder() throws Exception {
        String json = """
                {"kind": "WRAPPED", "location": "FOLDER", "folder": "nei/clicr",
                 "wrapped": {"ne_type": "CLICR", "grc": {"NIAM_IP": "10.0.0.1", "REPO_PASSWORD": "${secret:CLICR_REPO_PASSWORD}"},
                             "login": {"M2MUSER1": "m2m", "KEY_FILE": "{dir}/keys/id_rsa"}}}""";
        assertEquals(List.of(), ComponentSpec.problems(SimpleJson.parse(json)));
        Properties p = props(json, "clicr");
        assertEquals("nei/clicr", p.getProperty("component.home"));
        assertEquals("CLICR", p.getProperty("component.ne_type"));
        assertEquals("ANY", p.getProperty("component.product"));
        assertEquals("V1", p.getProperty("component.version"));
        assertEquals("nei/clicr/config", p.getProperty("nei.clicr.configdir"));
        assertEquals("10.0.0.1", p.getProperty("nei.clicr.grc.NIAM_IP"));
        assertEquals("${secret:CLICR_REPO_PASSWORD}", p.getProperty("nei.clicr.grc.REPO_PASSWORD"));
        assertEquals("m2m", p.getProperty("nei.clicr.login.M2MUSER1"));
        assertEquals("${WORKDIR}/nei/clicr/keys/id_rsa", p.getProperty("nei.clicr.login.KEY_FILE"));
    }

    @Test
    public void everyProblemIsReported() {
        String all = String.join("\n", ComponentSpec.problems(SimpleJson.parse("""
                {"kind": "STANDALONE", "extra": 1, "location": "FOLDER", "folder": "../etc",
                 "standalone": {"runtime": "perl", "command": "{{ evil }}", "input": "ARGS", "output": "RESPONSE_FILE", "timeout_sec": 0},
                 "actions": [{"name": "do it"}, {"name": "RUN", "params": [{"name": "PASSWORD", "default": "plain"}, {"name": "X"}, {"name": "X"}]},
                             {"name": "RUN"}]}""")));
        assertTrue(all.contains("Unknown key 'extra'"), all);
        assertTrue(all.contains("'folder' must be a folder of the namespace's files"), all);
        assertTrue(all.contains("standalone.runtime must be one of"), all);
        assertTrue(all.contains("'{{' and '{%' are not allowed"), all);
        assertTrue(all.contains("RESPONSE_FILE needs input REQUEST_FILE"), all);
        assertTrue(all.contains("timeout_sec must be 1 to 86400"), all);
        assertTrue(all.contains("action 'do it': the name must be upper case"), all);
        assertTrue(all.contains("parameter PASSWORD: a secret's default is written as ${secret:NAME}"), all);
        assertTrue(all.contains("params names 'X' twice"), all);
        assertTrue(all.contains("action 'RUN': the name is used twice"), all);
        String wrapped = String.join("\n", ComponentSpec.problems(SimpleJson.parse("""
                {"kind": "WRAPPED", "wrapped": {"ne_type": "clicr", "login": {"M2MPASSWORD1": "secret-in-clear"}}}""")));
        assertTrue(wrapped.contains("wrapped.ne_type must be the NE type in upper case"), wrapped);
        assertTrue(wrapped.contains("wrapped.login.M2MPASSWORD1: a secret is written as ${secret:NAME}"), wrapped);
        assertTrue(ComponentSpec.problems(SimpleJson.parse(ComponentSpec.blank("STANDALONE"))).isEmpty(), "a new component starts valid");
    }
}
