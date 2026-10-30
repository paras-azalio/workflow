package io.azalio.workflow.wrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Activity overrides (--override), ${WORKDIR} / ${secret:NAME} in request values, and masking of secret values. */
public class OverridesAndSecretsTest {

    @TempDir
    File tmpDir;

    private String out;

    private int run(Map<String, String> env, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int code = WrapperMain.run(args, new PrintStream(o, true), new PrintStream(new ByteArrayOutputStream(), true), env);
        out = new String(o.toByteArray(), StandardCharsets.UTF_8);
        return code;
    }

    private File write(String name, String text) throws IOException {
        File f = new File(tmpDir, name);
        FileOutputStream s = new FileOutputStream(f);
        s.write(text.getBytes(StandardCharsets.UTF_8));
        s.close();
        return f;
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void anOverrideFileWinsOverTheFlowsParametersAndAMissingFileIsNoOverride() throws IOException {
        File ov = write("step.activity.properties", "A=from-activity\nNEW=added\n");
        run(new HashMap<String, String>(), "--component", "TEST_ECHO", "--param", "A=from-flow", "--param", "B=kept",
                "--override", ov.getPath(), "--override", new File(tmpDir, "absent.properties").getPath());
        assertTrue(out.contains("A=from-activity"), out);
        assertTrue(out.contains("NEW=added"), out);
        assertTrue(out.contains("B=kept"), out);
    }

    @Test
    public void workdirAndSecretsAreResolvedAndSecretValuesAreMaskedWhateverTheKey() throws IOException {
        Map<String, String> env = new HashMap<String, String>();
        env.put("SECRET_NORTH_PW", b64("n0rth-pa55"));
        File io = new File(tmpDir, "x.io.log");
        File result = new File(tmpDir, "x.result.json");
        run(env, "--component", "TEST_ECHO", "--workdir", tmpDir.getPath(),
                "--param", "PATH_X=${WORKDIR}/nei/clicr/script", "--param", "CODE=${secret:NORTH_PW}",
                "--io-log", io.getPath(), "--result", result.getPath());
        String wd = tmpDir.getAbsolutePath().replace('\\', '/');
        assertTrue(out.contains("PATH_X=" + wd + "/nei/clicr/script"), out);
        assertTrue(out.contains("CODE=*****"), "a secret's value is masked under any key: " + out);
        assertFalse(out.contains("n0rth-pa55"), out);
        assertFalse(new String(Files.readAllBytes(io.toPath()), StandardCharsets.UTF_8).contains("n0rth-pa55"));
        assertFalse(new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8).contains("n0rth-pa55"));
    }

    @Test
    public void aMissingSecretFailsTheCallWithoutRunningIt() {
        int code = run(new HashMap<String, String>(), "--component", "TEST_ECHO", "--param", "login.M2MPASSWORD1=${secret:GONE}");
        assertEquals(9, code);
        assertTrue(out.contains("Secret GONE (for login.M2MPASSWORD1) is not available"), out);
        assertFalse(out.contains("noise from the component"), "the component was not called: " + out);
    }
}
