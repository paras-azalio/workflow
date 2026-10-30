package io.azalio.workflow.wrapper.nei;

import io.azalio.workflow.wrapper.WrapperContext;
import io.azalio.workflow.wrapper.WrapperRequest;
import io.azalio.workflow.wrapper.WrapperResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real FileCR NEI jar (macro_FILECR_ANY_V1.jar, unchanged) through the NEI wrapper: template generation
 * and CIQ validation on the 1051 samples. Skips itself when the jar is not on the test classpath (run-tests.sh adds
 * NOKIA_FILE_CR_V1/target) or the Nokia folders are not next to this module.
 */
public class FileCrNeiIT {

    private static final String ACTIVITY = "1051_SUBSCRIBER_PROFILE_CONFIGURATION";

    @TempDir
    File work;

    private File root;
    private WrapperContext ctx;

    @BeforeEach
    void setUp() {
        boolean jar;
        try {
            Class.forName("macro_FILECR_ANY_V1");
            jar = true;
        } catch (ClassNotFoundException e) {
            jar = false;
        }
        assumeTrue(jar, "macro_FILECR_ANY_V1 not on the test classpath");
        root = new File(System.getProperty("nei.root", "../.."));
        File rules = new File(root, "version/1051/ggnyaml");
        File res = new File(root, "NOKIA_FILE_CR_V1/src/main/resources/templates");
        assumeTrue(new File(rules, "PGW_RDS_" + ACTIVITY + "_validation-rules.yaml").isFile(), "1051 rules missing");

        Properties cfg = new Properties();   // the FileCR GRC section, as the project config carries it
        cfg.setProperty("nei.filecr.grc.yaml_path", path(rules));
        cfg.setProperty("nei.filecr.grc.jsonTemp_path", path(rules));
        cfg.setProperty("nei.filecr.grc.REPORT_TEMP_PATH", path(new File(res, "html")));
        cfg.setProperty("nei.filecr.grc.properties_path", path(res));
        cfg.setProperty("nei.filecr.configdir", path(new File(root, "NOKIA_FILE_CR_V1/target/config")));
        ctx = new WrapperContext(cfg, work);
    }

    private static String path(File f) {
        return f.getAbsolutePath().replace('\\', '/');
    }

    private WrapperRequest request(String action, String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("ne_type", "FILECR");
        m.put("REQ_TYPE", "1");
        m.put("ACTION", action);
        m.put("NODE_TYPE", "PGW_RDS");
        m.put("ACTIVITY_NAME", ACTIVITY);
        m.put("SUB_ACTIVITY_NAME", ACTIVITY);
        m.put("NODE_VERSION", "ALL");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new WrapperRequest(m);
    }

    @Test
    void generateTemplate() throws Exception {
        File out = new File(work, "template");
        WrapperResult r = new NeiWrapper().execute(
                request("GENERATE_CR_CIQ_TEMPLATE", "CIQ_FILE_LOCATION", path(out)), ctx);
        assertEquals(WrapperResult.Status.SUCCESS, r.status(), r.message() + " " + r.results() + " " + r.log());
        assertEquals("macro_FILECR_ANY_V1", r.results().get("NEI_MACRO"));
        File[] files = out.listFiles();
        assertTrue(files != null && files.length > 0, "template written: " + r.results());
    }

    @Test
    void validCiqPasses() throws Exception {
        File out = new File(work, "valid");
        WrapperResult r = new NeiWrapper().execute(request("CR_CIQ_PROCESSOR",
                "CIQ_FILE_NAME", path(new File(root, "BST/samples/ciq/PGW_RDS_1051_CIQ_current_rules_pass.xlsx")),
                "OUTPUT_FILE_LOCATION", path(out) + "/", "REPORT_TYPE", "HTML", "HEADER_ROW_NO", "1"), ctx);
        assertEquals(WrapperResult.Status.SUCCESS, r.status(), r.message() + " " + r.results() + " " + r.log());
        assertEquals("CR1", r.results().get("CR_LIST"));
        assertEquals("1", r.results().get("CR_COUNT"));
        assertNotNull(r.results().get("JSON_FILE_NAME"));
        assertTrue(new File(out, r.results().get("JSON_FILE_NAME")).isFile(), "JSON written");
    }

    @Test
    void mmlLogIsWrittenLikeTheMacroServerLogfile() throws Exception {
        File mml = new File(work, "logs/ciq_validation.mml");
        Map<String, String> opts = new LinkedHashMap<String, String>();
        opts.put("mml.log", mml.getPath());
        WrapperContext withMml = new WrapperContext(ctx.config(), work, opts);
        File out = new File(work, "mml");
        WrapperResult r = new NeiWrapper().execute(request("CR_CIQ_PROCESSOR",
                "CIQ_FILE_NAME", path(new File(root, "BST/samples/ciq/PGW_RDS_1051_CIQ_current_rules_pass.xlsx")),
                "OUTPUT_FILE_LOCATION", path(out) + "/", "REPORT_TYPE", "HTML", "HEADER_ROW_NO", "1",
                "REQ_ID", "REQ123"), withMml);
        assertEquals(WrapperResult.Status.SUCCESS, r.status(), r.message());
        String text = new String(java.nio.file.Files.readAllBytes(mml.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("Report Status : PASSED"), "FileCR printMml lines in the MML log: " + text);
        assertTrue(text.trim().endsWith("$END$ REQ123"), text);
    }

    @Test
    void invalidCiqFails() throws Exception {
        File out = new File(work, "invalid");
        WrapperResult r = new NeiWrapper().execute(request("CR_CIQ_PROCESSOR",
                "CIQ_FILE_NAME", path(new File(root, "BST/samples/ciq/PGW_RDS_1051_CIQ_fail_two_rows_old_words.xlsx")),
                "OUTPUT_FILE_LOCATION", path(out) + "/", "REPORT_TYPE", "HTML", "HEADER_ROW_NO", "1"), ctx);
        assertEquals(WrapperResult.Status.FAILED, r.status(), r.message() + " " + r.results());
        assertEquals("2", r.results().get("NEI_TASK_STATUS"));
    }

    @Test
    void missingMandatoryParameterFails() throws Exception {
        WrapperResult r = new NeiWrapper().execute(request("GENERATE_CR_CIQ_TEMPLATE"), ctx);   // no CIQ_FILE_LOCATION
        assertEquals(WrapperResult.Status.FAILED, r.status(), r.message() + " " + r.results());
        assertTrue(String.valueOf(r.results()).contains("CIQ_FILE_LOCATION"), "NEI names the missing parameter: " + r.results());
    }

    @Test
    void unknownNeTypeAndMissingConfigFail() throws Exception {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("ne_type", "NOSUCH");
        WrapperResult r = new NeiWrapper().execute(new WrapperRequest(m), ctx);
        assertEquals(WrapperResult.Status.FAILED, r.status());
        assertTrue(r.message().contains("macro_NOSUCH_ANY_V1 not on the classpath"), r.message());

        r = new NeiWrapper().execute(request("GENERATE_CR_CIQ_TEMPLATE", "CIQ_FILE_LOCATION", "x"),
                new WrapperContext(new Properties(), work));
        assertEquals(WrapperResult.Status.FAILED, r.status());
        assertTrue(r.message().startsWith("No GRC config for FILECR"), r.message());
    }
}
