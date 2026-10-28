package com.nokia.workflow.wrapper;

import com.nokia.workflow.wrapper.command.CommandProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The COMMAND component: a configured program, its response as results, its failures, timeout and secrets. */
public class CommandWrapperTest {

    @TempDir
    File tmpDir;

    private String out;

    private int run(Map<String, String> env, String config, String... params) throws IOException {
        File cfg = new File(tmpDir, "project.properties");
        FileOutputStream s = new FileOutputStream(cfg);
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath().replace('\\', '/');
        String cp;
        try {
            cp = new File(CommandProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getAbsolutePath().replace('\\', '/');
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        s.write(("command.probe=" + java + " -cp " + cp + " " + CommandProbe.class.getName() + "\n" + config)
                .replace("\\", "\\\\").getBytes(StandardCharsets.UTF_8));
        s.close();
        String[] args = new String[6 + 2 * params.length];
        args[0] = "--component";
        args[1] = "COMMAND";
        args[2] = "--config";
        args[3] = cfg.getPath();
        args[4] = "--workdir";
        args[5] = tmpDir.getPath();
        for (int i = 0; i < params.length; i++) {
            args[6 + 2 * i] = "--param";
            args[7 + 2 * i] = params[i];
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int code = WrapperMain.run(args, new PrintStream(o, true), new PrintStream(new ByteArrayOutputStream(), true), env);
        out = new String(o.toByteArray(), StandardCharsets.UTF_8);
        return code;
    }

    @Test
    public void theProgramsResponseBecomesTheResult() throws IOException {
        int code = run(new HashMap<String, String>(), "", "COMMAND=probe", "TO=owner@example.com");
        assertEquals(0, code, out);
        assertTrue(out.contains("STATUS=0"), out);
        assertTrue(out.contains("MESSAGE_ID=m-owner@example.com"), out);
        assertTrue(out.contains("EXIT_CODE=0"), out);
    }

    @Test
    public void aReportedStatusWinsAndANonZeroExitWithoutOneFails() throws IOException {
        run(new HashMap<String, String>(), "", "COMMAND=probe", "STATUS=WARNING");
        assertTrue(out.contains("STATUS=1") && out.contains("MESSAGE=probe says WARNING"), out);
        int code = run(new HashMap<String, String>(), "", "COMMAND=probe", "EXIT=3");
        assertEquals(9, code, out);
        assertTrue(out.contains("ended with exit code 3"), out);
    }

    @Test
    public void anUnconfiguredCommandFails() throws IOException {
        int code = run(new HashMap<String, String>(), "", "COMMAND=nothing_here");
        assertEquals(9, code);
        assertTrue(out.contains("No program is configured for command 'nothing_here'"), out);
    }

    @Test
    public void aProgramThatHangsIsStoppedAtItsTimeout() throws IOException {
        long start = System.currentTimeMillis();
        int code = run(new HashMap<String, String>(), "command.probe.timeout_sec=1\n", "COMMAND=probe", "SLEEP_MS=20000");
        assertEquals(9, code);
        assertTrue(out.contains("did not finish within 1 s"), out);
        assertTrue(System.currentTimeMillis() - start < 15000, "stopped at the timeout");
    }

    @Test
    public void aSecretInTheConfigIsResolvedForTheCallAndMasked() throws IOException {
        Map<String, String> env = new HashMap<String, String>();
        env.put("SECRET_SMTP_PW", Base64.getEncoder().encodeToString("smtp-s3cret".getBytes(StandardCharsets.UTF_8)));
        run(env, "mail.password=${secret:SMTP_PW}\n", "COMMAND=probe", "SMTP_PASSWORD=${secret:SMTP_PW}");
        assertTrue(out.contains("SEEN_PASSWORD=*****"), "the program got it, the result masks it: " + out);
        assertFalse(out.contains("smtp-s3cret"), out);
        int code = run(new HashMap<String, String>(), "nei.x.login.PW=${secret:GONE}\n", "COMMAND=probe");
        assertEquals(9, code);
        assertTrue(out.contains("Secret GONE (for nei.x.login.PW) is not available"), out);
    }
}
