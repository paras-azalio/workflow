package com.nokia.workflow.wrapper.nei;

import com.nokia.workflow.wrapper.WrapperContext;
import com.nokia.workflow.wrapper.WrapperRequest;
import com.nokia.workflow.wrapper.WrapperResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real CLICR NEI jar (macro_CLICR_ANY_V1.jar, unchanged) through the NEI wrapper: what a child order does -
 * MOP_GENERATION for one CR of a two-CR 1051 JSON (BST/samples/json), then MOP_EXECUTION for its node with no
 * reachable NIAM. Skips itself when the jar is not on the test classpath (run-tests.sh runs this class in its own
 * JVM with JAVA_NOKIA_CLICR_AUTOMATION/target) or the Nokia folders are not next to this module.
 *
 * <p>One working folder for the whole class: the Macro Server reads the GRC once per JVM, so every test must see
 * the same template paths (a flow step is one JVM per task and is not affected). The folder is not deleted
 * afterwards: CLICR's YamlWorkflowLoader leaves the workflow YAML open, and Windows cannot delete an open file.
 */
public class ClicrNeiIT {

    private static final String ACTIVITY = "1051_SUBSCRIBER_PROFILE_CONFIGURATION";
    private static final String BASE = "PGW_RDS_" + ACTIVITY;

    @TempDir(cleanup = CleanupMode.NEVER)
    static File work;

    private File json;
    private Properties cfg;

    @BeforeEach
    void setUp() throws Exception {
        boolean jar;
        try {
            Class.forName("macro_CLICR_ANY_V1");
            jar = true;
        } catch (ClassNotFoundException e) {
            jar = false;
        }
        assumeTrue(jar, "macro_CLICR_ANY_V1 not on the test classpath");
        File root = new File(System.getProperty("nokia.root", "../.."));
        File ggn = new File(root, "version/1051/ggnyaml");
        json = new File(root, "BST/samples/json/PGW_RDS_1051_two_crs.json");
        assumeTrue(new File(ggn, BASE + ".yaml").isFile() && json.isFile(), "1051 workflow or sample JSON missing");

        // moptemplates_path layout: yaml/<NODE_TYPE>_<ACTIVITY>.yaml and jsonTemplate/<..>_json-output.yaml
        File templates = new File(work, "templates");
        if (!templates.isDirectory()) {   // once per class: the first task's GRC paths stay in force (see above)
            copy(new File(ggn, BASE + ".yaml"), new File(templates, "yaml/" + BASE + ".yaml"));
            copy(new File(ggn, BASE + "_json-output.yaml"),
                    new File(templates, "jsonTemplate/" + BASE + "_json-output.yaml"));
        }

        cfg = new Properties();   // the CLICR GRC section, as the project config carries it
        cfg.setProperty("nei.clicr.grc.moptemplates_path", path(templates));
        cfg.setProperty("nei.clicr.grc.mop_summary_report_template_path",
                path(new File(ggn, "templates/html/mop_summary_report_template_v2.html")));
        cfg.setProperty("nei.clicr.grc.mop_summary_report_template_path_v1",
                path(new File(ggn, "templates/html/mop_summary_report_template.html")));
        cfg.setProperty("nei.clicr.grc.NIAM_IP", "127.0.0.1");
        cfg.setProperty("nei.clicr.grc.M2MPORT", "1");   // nothing listens there: no node is reachable
        cfg.setProperty("nei.clicr.grc.REPO_IP", "127.0.0.1");
        cfg.setProperty("nei.clicr.grc.REPO_USER", "test");
        cfg.setProperty("nei.clicr.grc.REPO_PASSWORD", "test-only");
        cfg.setProperty("nei.clicr.login.M2MUSER1", "test");
        cfg.setProperty("nei.clicr.login.M2MPASSWORD1", "test-only");
        cfg.setProperty("nei.clicr.configdir", path(new File(root, "JAVA_NOKIA_CLICR_AUTOMATION/target/config")));
    }

    private static void copy(File from, File to) throws Exception {
        to.getParentFile().mkdirs();
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String path(File f) {
        return f.getAbsolutePath().replace('\\', '/');
    }

    private WrapperContext ctx(File mml) {
        Map<String, String> opts = new LinkedHashMap<String, String>();
        opts.put("mml.log", mml.getPath());
        return new WrapperContext(cfg, work, opts);
    }

    private WrapperRequest request(String action, String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("ne_type", "CLICR");
        m.put("REQ_TYPE", "1");
        m.put("ACTION", action);
        m.put("NODE_TYPE", "PGW_RDS");
        m.put("SUB_ACTIVITY_NAME", ACTIVITY);
        m.put("NODE_VERSION", "ALL");
        m.put("PARENT_REQ_ID", "P1");
        m.put("CHILD_REQ_ID", "C2");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new WrapperRequest(m);
    }

    @Test
    void mopGenerationForOneCr() throws Exception {
        File child = new File(work, "C2");
        File mml = new File(child, "logs/mop_generation.mml");
        WrapperResult r = new NeiWrapper().execute(request("MOP_GENERATION", "JSON_FILE_NAME", path(json),
                "OUTPUT_FILE_LOCATION", path(child) + "/", "CR_NAME", "CR2", "REQ_ID", "C2"), ctx(mml));
        assertEquals(WrapperResult.Status.SUCCESS, r.status(), r.message());
        assertEquals("CR2", r.results().get("ACTIVITY_CR_NAME"));
        assertEquals("SOUTH1", r.results().get("ACTIVITY_CR_NODE_NAMES"), "CR2 runs on zone SOUTH1 only");
        assertEquals(BASE + "-CR2.html", r.results().get("ACTIVITY_CR_MOP"));
        assertTrue(new File(child, BASE + "-CR2.html").isFile(), "MOP html for CR2 only");
        assertTrue(!new File(child, BASE + "-CR1.html").exists(), "no MOP for the other CR");
        String text = new String(Files.readAllBytes(mml.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("=== MOP Generation Complete ==="), text);
        assertTrue(text.trim().endsWith("$END$ C2"), text);
    }

    @Test
    void unknownCrFails() throws Exception {
        File child = new File(work, "C9");
        WrapperResult r = new NeiWrapper().execute(request("MOP_GENERATION", "JSON_FILE_NAME", path(json),
                "OUTPUT_FILE_LOCATION", path(child) + "/", "CR_NAME", "CR9"), ctx(new File(child, "logs/x.mml")));
        assertEquals(WrapperResult.Status.FAILED, r.status(), r.message());
        assertEquals("2", r.results().get("NEI_TASK_STATUS"));
        String text = new String(Files.readAllBytes(new File(child, "logs/x.mml").toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("CR group not found for MOP generation: CR9"), text);
    }

    @Test
    void mopExecutionWithNoReachableNodeFails() throws Exception {
        File child = new File(work, "C2x");
        File mml = new File(child, "logs/mop_execution_SOUTH1.mml");
        WrapperResult r = new NeiWrapper().execute(request("MOP_EXECUTION", "INPUT_JSON_FILE_NAME", path(json),
                "OUTPUT_LOGS_FILE_LOCATION", path(child) + "/LOGS", "OUTPUT_JSON_REPORT_LOCATION", path(child) + "/REPORT",
                "CR_NAME", "CR2", "NODE_NAME", "SOUTH1", "ROLLBACK_ONLY", "false", "REQ_ID", "C2"), ctx(mml));
        assertEquals(WrapperResult.Status.FAILED, r.status(), r.message());
        assertEquals("FAIL", r.results().get("ACTIVITY_EXECUTION_STATUS"));
        assertEquals("SOUTH1", r.results().get("EXECUTION_NODE"));
        assertTrue(new File(child, "LOGS/SOUTH1_execution.log").isFile(), "the engine's execution log");
        assertTrue(new File(child, "REPORT/" + BASE + "_EXECUTION_REPORT_SOUTH1.json").isFile(), "execution report");
        String text = new String(Files.readAllBytes(mml.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("Connection refused"), "SSH to the NIAM fails: " + text);
        assertTrue(text.trim().endsWith("$END$ C2"), text);
    }
}
