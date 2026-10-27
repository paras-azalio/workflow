package com.nokia.workflow.wrapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Command-line entry: runs one component wrapper for one request and writes the result.
 *
 * <pre>
 * java -cp "component-wrappers.jar:lib/*" com.nokia.workflow.wrapper.WrapperMain \
 *      --component CIQ_PROCESSOR \
 *      [--request request.json|request.properties] [--param KEY=VALUE ...] \
 *      [--config project.properties] [--workdir DIR] \
 *      [--result result.json] [--emit keyvalue|json|kestra] [--exit-zero]
 * java ... WrapperMain --list
 * </pre>
 *
 * <p>Output contract (see 06_component_wrapper.md):
 * <ul>
 *   <li>{@code --result FILE}: the full result as JSON - component, status, statusText, message,
 *       request and results (password-like keys masked), log, timing.</li>
 *   <li>stdout: {@code STATUS=}, {@code STATUS_TEXT=}, {@code MESSAGE=} then every result as {@code KEY=VALUE}
 *       ({@code --emit keyvalue}, default); the JSON result ({@code json}); or the Kestra output marker
 *       {@code ::{"outputs":{...}}::} ({@code kestra}). Newlines in values are written as {@code \n}.</li>
 *   <li>exit code: 0 for SUCCESS and WARNING, 9 for FAILED; always 0 with {@code --exit-zero}, so the
 *       workflow can decide from the status itself (per-step / per-workflow override of the success rule).</li>
 * </ul>
 * Anything the wrapped component prints is moved to stderr, so stdout carries only the result.
 */
public final class WrapperMain {

    /** Request keys whose values are masked in the echoed request and in logs. */
    static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|pswd|pwd|secret|token).*");

    private WrapperMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs one call; returns the process exit code. Package-visible for tests. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        String component = null;
        String requestFile = null;
        String configFile = System.getenv("WRAPPER_CONFIG");
        String workDir = null;
        String resultFile = null;
        String emit = "keyvalue";
        String ioLog = null;
        String mmlLog = null;
        boolean exitZero = false;
        boolean list = false;
        Map<String, String> inlineParams = new LinkedHashMap<String, String>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String next = i + 1 < args.length ? args[i + 1] : null;
            if ("--component".equals(a) && next != null) {
                component = next; i++;
            } else if ("--request".equals(a) && next != null) {
                requestFile = next; i++;
            } else if ("--param".equals(a) && next != null) {
                int eq = next.indexOf('=');
                if (eq <= 0) {
                    return usage(err, "--param needs KEY=VALUE, got: " + next);
                }
                inlineParams.put(next.substring(0, eq).trim(), next.substring(eq + 1));
                i++;
            } else if ("--config".equals(a) && next != null) {
                configFile = next; i++;
            } else if ("--workdir".equals(a) && next != null) {
                workDir = next; i++;
            } else if ("--result".equals(a) && next != null) {
                resultFile = next; i++;
            } else if ("--emit".equals(a) && next != null) {
                emit = next.toLowerCase(); i++;
            } else if ("--io-log".equals(a) && next != null) {
                ioLog = next; i++;
            } else if ("--mml-log".equals(a) && next != null) {
                mmlLog = next; i++;
            } else if ("--exit-zero".equals(a)) {
                exitZero = true;
            } else if ("--list".equals(a)) {
                list = true;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                return usage(out, null);
            } else {
                return usage(err, "Unknown or incomplete argument: " + a);
            }
        }
        if (!"keyvalue".equals(emit) && !"json".equals(emit) && !"kestra".equals(emit)) {
            return usage(err, "--emit must be keyvalue, json or kestra");
        }

        WrapperRegistry registry = new WrapperRegistry();
        if (list) {
            for (ComponentWrapper w : registry.all()) {
                out.println(w.name() + "\t" + w.description());
            }
            for (String u : registry.unavailable()) {
                out.println("(unavailable)\t" + u);
            }
            return 0;
        }

        long start = System.currentTimeMillis();
        Map<String, String> requestParams = new LinkedHashMap<String, String>();
        WrapperResult result;
        WrapperContext context = null;
        try {
            if (requestFile != null) {
                requestParams.putAll(readRequest(new File(requestFile)));
            }
            requestParams.putAll(inlineParams);   // --param overrides the file
            Properties config = configFile == null ? new Properties() : readProperties(new File(configFile));
            Map<String, String> options = new LinkedHashMap<String, String>();
            if (mmlLog != null) {
                options.put("mml.log", mmlLog);
            }
            context = new WrapperContext(config, workDir == null ? new File(".") : new File(workDir), options);

            if (component == null) {
                result = WrapperResult.failed("Missing --component (use --list to see the available ones)");
            } else {
                ComponentWrapper wrapper = registry.find(component);
                if (wrapper == null) {
                    result = WrapperResult.failed("Unknown component '" + component + "'. Available: " + names(registry));
                } else {
                    // keyvalue / json: stdout carries only the result, so component output goes to stderr.
                    // kestra: only the ::{...}:: line is parsed; Kestra logs stderr lines as ERROR, so the
                    // component's output (stdout and stderr) goes to stdout and is logged as INFO.
                    boolean kestra = "kestra".equals(emit);
                    result = invoke(wrapper, new WrapperRequest(requestParams), context, kestra ? out : err, kestra);
                }
            }
        } catch (Exception e) {
            result = WrapperResult.failed("Wrapper error: " + e);
        }

        Map<String, Object> doc = document(component, requestParams, result, context, start);
        String io = ioLog(doc);
        try {
            if (resultFile != null) {
                writeFile(new File(resultFile), SimpleJson.write(doc) + "\n");
            }
            if (ioLog != null) {
                writeFile(new File(ioLog), io);
            }
        } catch (IOException e) {
            err.println("Could not write --result / --io-log file: " + e.getMessage());
            return 9;   // the contract was not delivered
        }
        if ("kestra".equals(emit)) {
            out.print(io);   // the IO log in the task log, like InstantLink's request IO view
        }
        emit(out, emit, doc, result);

        if (exitZero || result.status() != WrapperResult.Status.FAILED) {
            return 0;
        }
        return WrapperResult.Status.FAILED.code();
    }

    /**
     * Runs the wrapper with the component's output sent to {@code sink} (stderr normally, stdout for Kestra), and turns
     * exceptions into FAILED. With {@code alsoStderr} the component's stderr goes to {@code sink} too.
     */
    private static WrapperResult invoke(ComponentWrapper wrapper, WrapperRequest request,
                                        WrapperContext context, PrintStream sink, boolean alsoStderr) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(sink);
        if (alsoStderr) {
            System.setErr(sink);
        }
        try {
            WrapperResult r = wrapper.execute(request, context);
            return r == null ? WrapperResult.failed(wrapper.name() + " returned no result") : r;
        } catch (WrapperRequest.MissingParameterException e) {
            return WrapperResult.failed(e.getMessage());
        } catch (Throwable t) {
            context.log(stackTrace(t));
            return WrapperResult.failed(wrapper.name() + " failed: " + t);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    static Map<String, Object> document(String component, Map<String, String> request, WrapperResult result,
                                        WrapperContext context, long start) {
        Map<String, String> masked = mask(request);
        List<String> log = new ArrayList<String>();
        if (context != null) {
            log.addAll(context.logLines());
        }
        log.addAll(result.log());

        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("component", component);
        doc.put("status", result.status().code());
        doc.put("statusText", result.status().name());
        doc.put("message", result.message());
        doc.put("startedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").format(new Date(start)));
        doc.put("durationMs", System.currentTimeMillis() - start);
        doc.put("request", masked);
        doc.put("results", mask(result.results()));
        doc.put("log", log);
        return doc;
    }

    private static void emit(PrintStream out, String emit, Map<String, Object> doc, WrapperResult result) {
        if ("json".equals(emit)) {
            out.println(SimpleJson.write(doc));
            return;
        }
        if ("kestra".equals(emit)) {
            Map<String, Object> outputs = new LinkedHashMap<String, Object>(doc);
            outputs.remove("log");   // the log goes to the task log, not into the outputs
            Map<String, Object> wrap = new LinkedHashMap<String, Object>();
            wrap.put("outputs", outputs);
            for (Object line : (List<?>) doc.get("log")) {
                out.println(line);
            }
            out.println("::" + SimpleJson.writeCompact(wrap) + "::");
            return;
        }
        out.println("STATUS=" + result.status().code());
        out.println("STATUS_TEXT=" + result.status().name());
        out.println("MESSAGE=" + oneLine(result.message()));
        for (Map.Entry<String, String> e : mask(result.results()).entrySet()) {
            out.println(e.getKey() + "=" + oneLine(e.getValue()));
        }
    }

    /** Same map with the values of password-like keys replaced by {@code *****}. */
    static Map<String, String> mask(Map<String, String> values) {
        Map<String, String> masked = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            masked.put(e.getKey(), SECRET_KEY.matcher(e.getKey()).matches() ? "*****" : e.getValue());
        }
        return masked;
    }

    /**
     * The IO log of one call, like InstantLink's request IO view: the input (request) parameters that started the
     * task and the output parameters it returned, with the status. Password-like values are masked.
     */
    @SuppressWarnings("unchecked")
    static String ioLog(Map<String, Object> doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== IO ").append(doc.get("component")).append("  started ").append(doc.get("startedAt"))
          .append("  duration ").append(doc.get("durationMs")).append(" ms\n");
        sb.append("--- INPUT (request parameters)\n");
        for (Map.Entry<String, String> e : ((Map<String, String>) doc.get("request")).entrySet()) {
            sb.append("> ").append(e.getKey()).append('=').append(oneLine(e.getValue())).append('\n');
        }
        sb.append("--- OUTPUT (status ").append(doc.get("status")).append(' ').append(doc.get("statusText"))
          .append(": ").append(oneLine(String.valueOf(doc.get("message")))).append(")\n");
        for (Map.Entry<String, String> e : ((Map<String, String>) doc.get("results")).entrySet()) {
            sb.append("< ").append(e.getKey()).append('=').append(oneLine(e.getValue())).append('\n');
        }
        sb.append("=== END IO\n");
        return sb.toString();
    }

    static String oneLine(String s) {
        // only line breaks are escaped (as \n); backslashes stay as they are so Windows paths read normally
        return s == null ? "" : s.replace("\r", "").replace("\n", "\\n");
    }

    static Map<String, String> readRequest(File f) throws IOException {
        String text = readFile(f);
        String name = f.getName().toLowerCase();
        if (name.endsWith(".json") || text.trim().startsWith("{")) {
            return SimpleJson.parseFlatObject(text);
        }
        Properties p = new Properties();
        Reader r = new java.io.StringReader(text);
        p.load(r);
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k));
        }
        return m;
    }

    static Properties readProperties(File f) throws IOException {
        Properties p = new Properties();
        InputStream in = new FileInputStream(f);
        try {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
        return p;
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void writeFile(File f, String text) throws IOException {
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        Writer w = new OutputStreamWriter(new java.io.FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    private static String names(WrapperRegistry registry) {
        StringBuilder sb = new StringBuilder();
        for (ComponentWrapper w : registry.all()) {
            sb.append(sb.length() == 0 ? "" : ", ").append(w.name());
        }
        if (!registry.unavailable().isEmpty()) {
            sb.append(sb.length() == 0 ? "" : "; ").append("not loadable: ").append(registry.unavailable());
        }
        return sb.length() == 0 ? "(none on the classpath)" : sb.toString();
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static int usage(PrintStream to, String problem) {
        if (problem != null) {
            to.println("Error: " + problem);
        }
        to.println("Usage: java -cp \"component-wrappers.jar:lib/*\" com.nokia.workflow.wrapper.WrapperMain");
        to.println("         --component <NAME> [--request <file.json|file.properties>] [--param KEY=VALUE]...");
        to.println("         [--config <project.properties>] [--workdir <dir>] [--result <result.json>]");
        to.println("         [--emit keyvalue|json|kestra] [--exit-zero] [--io-log <file>] [--mml-log <file>]");
        to.println("       ... WrapperMain --list");
        return problem == null ? 0 : 2;
    }
}
