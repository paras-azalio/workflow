package com.nokia.workflow.wrapper.command;

import com.nokia.workflow.wrapper.ComponentWrapper;
import com.nokia.workflow.wrapper.WrapperContext;
import com.nokia.workflow.wrapper.WrapperRequest;
import com.nokia.workflow.wrapper.WrapperResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Runs any configured program - a mail sender, a reply reader, a jar or a script - behind the wrapper contract.
 *
 * <p>Request {@code COMMAND=<name>} picks the program: config {@code command.<name>=<command line>} (split on
 * spaces; {@code ${WORKDIR}} and {@code ${secret:NAME}} allowed), optional {@code command.<name>.timeout_sec}
 * (default 300). The program is called as {@code <command line> <request file> <response file>}:
 * <ul>
 *   <li>request file: every request parameter except {@code COMMAND}, as a UTF-8 properties file;</li>
 *   <li>response file: written by the program, a properties file. {@code STATUS} SUCCESS / WARNING / FAILED
 *       (default: SUCCESS for exit code 0, FAILED otherwise), {@code MESSAGE}, and any other keys as results.</li>
 * </ul>
 * The program's output goes to the step log; results always include {@code EXIT_CODE}.
 */
public final class CommandWrapper implements ComponentWrapper {

    @Override
    public String name() {
        return "COMMAND";
    }

    @Override
    public String description() {
        return "Runs a configured program (command.<name>) with a request and a response properties file";
    }

    @Override
    public WrapperResult execute(WrapperRequest request, WrapperContext context) throws Exception {
        String name = request.require("COMMAND");
        String line = context.config("command." + name);
        if (line == null) {
            return WrapperResult.failed("No program is configured for command '" + name + "': set command." + name
                    + " in the project config.");
        }
        long timeout = Long.parseLong(context.config("command." + name + ".timeout_sec", "300"));

        File requestFile = context.resolve("command-" + name + ".request.properties");
        File responseFile = context.resolve("command-" + name + ".response.properties");
        Properties params = new Properties();
        for (Map.Entry<String, String> e : request.asMap().entrySet()) {
            if (!"COMMAND".equals(e.getKey())) {
                params.setProperty(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        write(params, requestFile);
        responseFile.delete();

        List<String> command = new ArrayList<String>();
        for (String part : line.trim().split("\\s+")) {
            command.add(part);
        }
        command.add(requestFile.getAbsolutePath());
        command.add(responseFile.getAbsolutePath());
        context.log("command " + name + ": " + line);

        final Process p = new ProcessBuilder(command).directory(context.workDir()).redirectErrorStream(true).start();
        final List<String> output = java.util.Collections.synchronizedList(new ArrayList<String>());
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String l;
                    while ((l = r.readLine()) != null) {
                        output.add(l);
                    }
                    r.close();
                } catch (Exception ignored) {
                    // the stream closes when the program is killed after its timeout
                }
            }
        });
        reader.start();
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            reader.join(2000);
            return WrapperResult.failed("Command '" + name + "' did not finish within " + timeout + " s.")
                    .addLog(new ArrayList<String>(output));
        }
        reader.join(5000);
        int exit = p.exitValue();

        Map<String, String> response = responseFile.isFile() ? read(responseFile) : new LinkedHashMap<String, String>();
        String status = response.remove("STATUS");
        String message = response.remove("MESSAGE");
        WrapperResult result;
        if (status == null) {
            result = exit == 0 ? WrapperResult.success(message == null ? "Command '" + name + "' done." : message)
                    : WrapperResult.failed(message == null ? "Command '" + name + "' ended with exit code " + exit + "." : message);
        } else if ("SUCCESS".equalsIgnoreCase(status.trim())) {
            result = WrapperResult.success(message);
        } else if ("WARNING".equalsIgnoreCase(status.trim())) {
            result = WrapperResult.warning(message);
        } else {
            result = WrapperResult.failed(message == null ? "Command '" + name + "' reported " + status + "." : message);
        }
        return result.putAll(response).put("EXIT_CODE", exit).addLog(new ArrayList<String>(output));
    }

    private static void write(Properties p, File f) throws Exception {
        OutputStream os = new FileOutputStream(f);
        try {
            p.store(new OutputStreamWriter(os, StandardCharsets.UTF_8), "request");
        } finally {
            os.close();
        }
    }

    private static Map<String, String> read(File f) throws Exception {
        Properties p = new Properties();
        InputStream in = new FileInputStream(f);
        try {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String k : p.stringPropertyNames()) {
            out.put(k, p.getProperty(k));
        }
        return out;
    }
}
