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
 * (default 300). How values go in, {@code command.<name>.input}:
 * <ul>
 *   <li>{@code REQUEST_FILE} (default): called as {@code <command line> <request file> <response file>}; the request
 *       file holds every request parameter except {@code COMMAND} as a UTF-8 properties file;</li>
 *   <li>{@code ARGS}: {@code command.<name>.args} is appended, each {@code {KEY}} in it replaced by that parameter's
 *       value as one argument (no shell, so a value never becomes part of a command);</li>
 *   <li>{@code ENV}: every parameter as the environment variable {@code P_<KEY>}.</li>
 * </ul>
 * How results come back, {@code command.<name>.output}: {@code RESPONSE_FILE} (default, only with REQUEST_FILE: a
 * properties file written by the program), {@code STDOUT_KEYVALUE} ({@code KEY=VALUE} lines of its output),
 * {@code STDOUT_JSON} (the last output line that is a JSON object) or {@code EXIT_CODE} (nothing but the exit code).
 * {@code STATUS} SUCCESS / WARNING / FAILED and {@code MESSAGE} among the results set the status and message, other
 * keys are results. Success: {@code command.<name>.success_key} / {@code .success_value} (e.g. RESULT / OK) when
 * set, else STATUS when returned, else exit code 0. The program's output goes to the step log; results always
 * include {@code EXIT_CODE}.
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
        String input = context.config("command." + name + ".input", "REQUEST_FILE").trim().toUpperCase(java.util.Locale.ROOT);
        String outputMode = context.config("command." + name + ".output", "REQUEST_FILE".equals(input) ? "RESPONSE_FILE" : "STDOUT_KEYVALUE")
                .trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Arrays.asList("REQUEST_FILE", "ARGS", "ENV").contains(input)) {
            return WrapperResult.failed("command." + name + ".input must be REQUEST_FILE, ARGS or ENV, not " + input + ".");
        }
        if (!java.util.Arrays.asList("RESPONSE_FILE", "STDOUT_KEYVALUE", "STDOUT_JSON", "EXIT_CODE").contains(outputMode)
                || ("RESPONSE_FILE".equals(outputMode) && !"REQUEST_FILE".equals(input))) {
            return WrapperResult.failed("command." + name + ".output must be RESPONSE_FILE (with input REQUEST_FILE), STDOUT_KEYVALUE,"
                    + " STDOUT_JSON or EXIT_CODE, not " + outputMode + ".");
        }

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
        if ("REQUEST_FILE".equals(input)) {
            command.add(requestFile.getAbsolutePath());
            command.add(responseFile.getAbsolutePath());
        } else if ("ARGS".equals(input)) {
            String args = context.config("command." + name + ".args", "");
            for (String part : args.trim().isEmpty() ? new String[0] : args.trim().split("\\s+")) {
                command.add(fill(part, params));
            }
        }
        context.log("command " + name + ": " + line + ("ARGS".equals(input) ? " " + context.config("command." + name + ".args", "") : ""));

        ProcessBuilder pb = new ProcessBuilder(command).directory(context.workDir()).redirectErrorStream(true);
        if ("ENV".equals(input)) {
            for (String k : params.stringPropertyNames()) {
                pb.environment().put("P_" + k, params.getProperty(k));
            }
        }
        final Process p = pb.start();
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

        Map<String, String> response;
        if ("RESPONSE_FILE".equals(outputMode)) {
            response = responseFile.isFile() ? read(responseFile) : new LinkedHashMap<String, String>();
        } else if ("STDOUT_KEYVALUE".equals(outputMode)) {
            response = keyValues(output);
        } else if ("STDOUT_JSON".equals(outputMode)) {
            response = lastJson(output);
        } else {
            response = new LinkedHashMap<String, String>();
        }
        String successKey = context.config("command." + name + ".success_key");
        String status = response.remove("STATUS");
        String message = response.remove("MESSAGE");
        if (successKey != null && !successKey.trim().isEmpty()) {
            String want = context.config("command." + name + ".success_value", "SUCCESS");
            String got = successKey.trim().equals("STATUS") ? status : response.get(successKey.trim());
            status = got != null && got.trim().equalsIgnoreCase(want.trim()) ? "SUCCESS" : "FAILED";
            if (got == null && message == null) {
                message = "Command '" + name + "' returned no " + successKey.trim() + ".";
            }
        }
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

    /** {KEY} in an argument becomes the parameter's value (missing: empty). */
    static String fill(String part, Properties params) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.]*)\\}").matcher(part);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(params.getProperty(m.group(1), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** KEY=VALUE lines of the output (the last value of a key wins); other lines are only log. */
    static Map<String, String> keyValues(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String l : lines) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z_][A-Za-z0-9_.]*)=(.*)$").matcher(l.trim());
            if (m.matches()) {
                out.put(m.group(1), m.group(2));
            }
        }
        return out;
    }

    /** The last output line that is a JSON object, its values as text. */
    @SuppressWarnings("unchecked")
    static Map<String, String> lastJson(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i).trim();
            if (!l.startsWith("{")) {
                continue;
            }
            try {
                Object parsed = com.nokia.workflow.wrapper.SimpleJson.parse(l);
                if (parsed instanceof Map) {
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) parsed).entrySet()) {
                        Object v = e.getValue();
                        out.put(e.getKey(), v instanceof String || v == null ? String.valueOf(v == null ? "" : v)
                                : com.nokia.workflow.wrapper.SimpleJson.writeCompact(v));
                    }
                    return out;
                }
            } catch (RuntimeException ignored) {
                // not JSON after all: look further up
            }
        }
        return out;
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
