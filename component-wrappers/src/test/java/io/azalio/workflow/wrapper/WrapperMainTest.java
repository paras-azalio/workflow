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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WrapperMainTest {

    @TempDir
    File tmpDir;

    private String out;
    private String err;

    private int run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = WrapperMain.run(args, new PrintStream(o, true), new PrintStream(e, true));
        out = new String(o.toByteArray(), StandardCharsets.UTF_8);
        err = new String(e.toByteArray(), StandardCharsets.UTF_8);
        return code;
    }

    private File write(String name, String text) throws IOException {
        File f = new File(tmpDir, name);
        FileOutputStream s = new FileOutputStream(f);
        s.write(text.getBytes(StandardCharsets.UTF_8));
        s.close();
        return f;
    }

    @Test
    public void successPrintsKeyValuesAndExitsZero() {
        int code = run("--component", "test_echo", "--param", "A=1", "--param", "MULTI=line1\nline2");
        assertEquals(0, code);
        assertTrue(out.startsWith("STATUS=0\nSTATUS_TEXT=SUCCESS\nMESSAGE=ok\n".replace("\n", System.lineSeparator())), out);
        assertTrue(out.contains("A=1"), out);
        assertTrue(out.contains("MULTI=line1\\nline2"), "newlines escaped: " + out);
    }

    @Test
    public void componentStdoutGoesToStderrNotTheResult() {
        run("--component", "TEST_ECHO");
        assertFalse(out.contains("noise from the component"), out);
        assertTrue(err.contains("noise from the component"), err);
    }

    @Test
    public void kestraModeSendsComponentOutputToStdoutNotStderr() {
        run("--component", "TEST_ECHO", "--emit", "kestra");
        assertTrue(out.contains("noise from the component"), out);   // Kestra logs stdout as INFO
        assertFalse(err.contains("noise from the component"), err);  // stderr would be logged as ERROR
    }

    @Test
    public void ioLogHasInputAndOutputWithSecretsMasked() throws IOException {
        File io = new File(tmpDir, "logs/task.io.log");
        run("--component", "TEST_ECHO", "--param", "ACTION=X", "--param", "M2MPASSWORD1=s3cr3t", "--io-log", io.getPath());
        String text = new String(Files.readAllBytes(io.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("=== IO TEST_ECHO"), text);
        assertTrue(text.contains("--- INPUT (request parameters)\n> ACTION=X\n> M2MPASSWORD1=*****\n"), text);
        assertTrue(text.contains("--- OUTPUT (status 0 SUCCESS: ok)\n"), text);
        assertTrue(text.contains("< ACTION=X\n"), text);
        assertTrue(text.endsWith("=== END IO\n"), text);
        assertFalse(text.contains("s3cr3t"), text);

        run("--component", "TEST_ECHO", "--param", "ACTION=X", "--emit", "kestra");   // also in the Kestra task log
        assertTrue(out.contains("--- INPUT (request parameters)"), out);
        assertTrue(out.indexOf("=== END IO") < out.indexOf("::{"), "IO block before the outputs marker: " + out);
    }

    @Test
    public void failedExitsNineUnlessExitZero() {
        assertEquals(9, run("--component", "TEST_ECHO", "--param", "WANT_STATUS=FAILED"));
        assertTrue(out.contains("STATUS=9"), out);
        assertEquals(0, run("--component", "TEST_ECHO", "--param", "WANT_STATUS=FAILED", "--exit-zero"));
        assertTrue(out.contains("STATUS=9"), out);
    }

    @Test
    public void warningExitsZeroWithStatusOne() {
        assertEquals(0, run("--component", "TEST_ECHO", "--param", "WANT_STATUS=WARNING"));
        assertTrue(out.contains("STATUS=1"), out);
        assertTrue(out.contains("STATUS_TEXT=WARNING"), out);
    }

    @Test
    public void unknownComponentAndExceptionsAreFailed() {
        assertEquals(9, run("--component", "NOPE"));
        assertTrue(out.contains("Unknown component 'NOPE'"), out);

        assertEquals(9, run("--component", "TEST_BOOM"));
        assertTrue(out.contains("MESSAGE=Missing parameter: NOT_THERE_UNLESS_GIVEN"), out);

        assertEquals(9, run("--component", "TEST_BOOM", "--param", "NOT_THERE_UNLESS_GIVEN=x"));
        assertTrue(out.contains("TEST_BOOM failed: java.lang.IllegalStateException: kaboom"), out);
    }

    @Test
    public void resultFileEchoesTheRequestWithSecretsMasked() throws IOException {
        File req = write("req.json", "{\"ACTION\":\"X\",\"M2MPASSWORD1\":\"s3cr3t\",\"user_pswd1\":\"p\"}");
        File result = new File(tmpDir, "sub/result.json");
        assertEquals(0, run("--component", "TEST_ECHO", "--request", req.getPath(), "--result", result.getPath()));
        String json = new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8);
        Map<String, String> doc = SimpleJson.parseFlatObject(json);
        assertEquals("TEST_ECHO", doc.get("component"));
        assertEquals("0", doc.get("status"));
        assertEquals("X", doc.get("request.ACTION"));
        assertEquals("*****", doc.get("request.M2MPASSWORD1"));
        assertEquals("*****", doc.get("request.user_pswd1"));
        assertEquals("echo called", doc.get("log"));
        assertFalse(json.contains("s3cr3t"), json);
    }

    @Test
    public void propertiesRequestParamOverrideAndConfig() throws IOException {
        File req = write("req.properties", "A=from-file\nB=keep\n");
        File cfg = write("p.properties", "test.dir=/opt/x\n");
        run("--component", "TEST_ECHO", "--request", req.getPath(), "--param", "A=from-param",
                "--config", cfg.getPath());
        assertTrue(out.contains("A=from-param"), out);
        assertTrue(out.contains("B=keep"), out);
        assertTrue(out.contains("CONFIG_DIR=/opt/x"), out);
    }

    @Test
    public void kestraEmitIsOneMarkerLineWithNestedOutputs() {
        run("--component", "TEST_ECHO", "--param", "A=1", "--emit", "kestra");
        String marker = null;
        for (String line : out.split("\\r?\\n")) {
            if (line.startsWith("::{")) {
                marker = line;
            }
        }
        assertTrue(marker != null && marker.endsWith("}::"), out);
        Map<String, String> m = SimpleJson.parseFlatObject(marker.substring(2, marker.length() - 2));
        assertEquals("0", m.get("outputs.status"));
        assertEquals("1", m.get("outputs.results.A"));
        assertEquals("1", m.get("outputs.request.A"));
        assertTrue(out.contains("echo called"), out);   // log lines go to the task log
    }

    @Test
    public void badArgumentsGiveUsageAndExitTwo() {
        assertEquals(2, run("--param", "noequals"));
        assertEquals(2, run("--emit", "xml", "--component", "TEST_ECHO"));
        assertEquals(2, run("--whatever"));
    }

    @Test
    public void listShowsRegisteredWrappers() {
        assertEquals(0, run("--list"));
        assertTrue(out.contains("NEI"), out);
        assertTrue(out.contains("TEST_ECHO"), out);
    }
}
