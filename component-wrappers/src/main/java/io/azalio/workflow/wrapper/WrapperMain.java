package io.azalio.workflow.wrapper;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Command-line entry: runs one component wrapper for one request and writes the result.
 *
 * <pre>
 * java -cp "component-wrappers.jar:lib/*" io.azalio.workflow.wrapper.WrapperMain \
 *      --component CIQ_PROCESSOR \
 *      [--request request.json|request.properties] [--param KEY=VALUE ...] [--override overrides.properties ...] \
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
 *
 * <p>Request values: {@code --request} first, then {@code --param}, then each {@code --override} file (e.g. the
 * activity configuration of the step). In any value, {@code ${WORKDIR}} is the working folder and
 * {@code ${secret:NAME}} the Kestra secret NAME (environment variable {@code SECRET_NAME}, base64, as Kestra OSS
 * keeps its secrets); a missing secret fails the call. Values that came from a secret are masked wherever the
 * request or the results are shown, whatever their key.
 */
public final class WrapperMain {

    /** Request keys whose values are masked in the echoed request and in logs. */
    static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|pswd|pwd|secret|token).*");
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_]+)\\}");

    private WrapperMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs one call; returns the process exit code. Package-visible for tests. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, System.getenv());
    }

    static int run(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
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
        List<String> overrideFiles = new ArrayList<String>();
        Set<String> secretValues = new HashSet<String>();

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
            } else if ("--override".equals(a) && next != null) {
                overrideFiles.add(next); i++;
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
            for (String f : overrideFiles) {
                requestParams.putAll(readOverrides(new File(f)));   // e.g. the activity configuration of the step
            }
            Properties config = configFile == null ? new Properties() : readProperties(new File(configFile));
            Map<String, String> options = new LinkedHashMap<String, String>();
            if (mmlLog != null) {
                options.put("mml.log", mmlLog);
            }
            context = new WrapperContext(config, workDir == null ? new File(".") : new File(workDir), options);
            String unresolved = resolveConfigSecrets(config, context.workDir(), env, secretValues);
            if (unresolved == null) {
                unresolved = resolveReferences(requestParams, context.workDir(), env, secretValues);
            }

            if (unresolved != null) {
                result = WrapperResult.failed(unresolved);
            } else if (component == null) {
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

        Map<String, Object> doc = document(component, requestParams, result, context, start, secretValues);
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
        emit(out, emit, doc, result, secretValues);

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
        return document(component, request, result, context, start, new HashSet<String>());
    }

    static Map<String, Object> document(String component, Map<String, String> request, WrapperResult result,
                                        WrapperContext context, long start, Set<String> secretValues) {
        Map<String, String> masked = mask(request, secretValues);
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
        doc.put("results", mask(result.results(), secretValues));
        doc.put("log", maskLines(log, secretValues));
        return doc;
    }

    private static void emit(PrintStream out, String emit, Map<String, Object> doc, WrapperResult result,
                             Set<String> secretValues) {
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
        for (Map.Entry<String, String> e : mask(result.results(), secretValues).entrySet()) {
            out.println(e.getKey() + "=" + oneLine(e.getValue()));
        }
    }

    /** Same map with the values of password-like keys replaced by {@code *****}. */
    static Map<String, String> mask(Map<String, String> values) {
        return mask(values, new HashSet<String>());
    }

    /** Also masks any value that came from a secret, whatever its key (e.g. an NEI echoing it back). */
    static Map<String, String> mask(Map<String, String> values, Set<String> secretValues) {
        Map<String, String> masked = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            boolean secret = SECRET_KEY.matcher(e.getKey()).matches() || containsSecret(e.getValue(), secretValues);
            masked.put(e.getKey(), secret ? "*****" : e.getValue());
        }
        return masked;
    }

    private static List<String> maskLines(List<String> lines, Set<String> secretValues) {
        List<String> out = new ArrayList<String>(lines.size());
        for (String line : lines) {
            String l = line;
            for (String s : secretValues) {
                l = l.replace(s, "*****");
            }
            out.add(l);
        }
        return out;
    }

    private static boolean containsSecret(String value, Set<String> secretValues) {
        if (value == null) {
            return false;
        }
        for (String s : secretValues) {
            if (value.contains(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves {@code ${secret:NAME}} in config values in place (e.g. {@code nei.clicr.login.M2MPASSWORD1}), so a
     * credential never has to be passed to a step by its flow. Same rules and masking as request values.
     */
    static String resolveConfigSecrets(Properties config, File workDir, Map<String, String> env, Set<String> secretValues) {
        Map<String, String> withSecrets = new LinkedHashMap<String, String>();
        for (String key : config.stringPropertyNames()) {
            if (config.getProperty(key).contains("${secret:")) {
                withSecrets.put(key, config.getProperty(key));
            }
        }
        String unresolved = resolveReferences(withSecrets, workDir, env, secretValues);
        if (unresolved == null) {
            for (Map.Entry<String, String> e : withSecrets.entrySet()) {
                config.setProperty(e.getKey(), e.getValue());
            }
        }
        return unresolved;
    }

    /**
     * Replaces {@code ${WORKDIR}} and {@code ${secret:NAME}} in every request value. Secrets come from the environment
     * variable {@code SECRET_NAME}, base64 as in Kestra OSS; their plain values are collected in {@code secretValues}
     * for masking. Returns null, or the message for a secret that is not available.
     */
    static String resolveReferences(Map<String, String> request, File workDir, Map<String, String> env,
                                    Set<String> secretValues) {
        String wd = workDir.getAbsolutePath().replace('\\', '/');
        for (Map.Entry<String, String> e : request.entrySet()) {
            String v = e.getValue();
            if (v == null || v.indexOf("${") < 0) {
                continue;
            }
            v = v.replace("${WORKDIR}", wd);
            java.util.regex.Matcher m = SECRET_REF.matcher(v);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String encoded = env.get("SECRET_" + m.group(1));
                if (encoded == null || encoded.trim().isEmpty()) {
                    return "Secret " + m.group(1) + " (for " + e.getKey() + ") is not available to this step: define the"
                            + " Kestra secret SECRET_" + m.group(1) + ".";
                }
                String plain;
                try {
                    plain = new String(java.util.Base64.getDecoder().decode(encoded.trim()), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException bad) {
                    return "Secret " + m.group(1) + " is not valid base64 (Kestra secrets are base64-encoded).";
                }
                if (!plain.isEmpty()) {
                    secretValues.add(plain);
                }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(plain));
            }
            m.appendTail(sb);
            e.setValue(sb.toString());
        }
        return null;
    }

    /** A {@code .properties} file of overrides; an absent file is no overrides (the step has no configuration). */
    static Map<String, String> readOverrides(File f) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!f.isFile()) {
            return out;
        }
        Properties p = readProperties(f);
        for (String k : new java.util.TreeSet<String>(p.stringPropertyNames())) {
            out.put(k, p.getProperty(k));
        }
        return out;
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
        to.println("Usage: java -cp \"component-wrappers.jar:lib/*\" io.azalio.workflow.wrapper.WrapperMain");
        to.println("         --component <NAME> [--request <file.json|file.properties>] [--param KEY=VALUE]...");
        to.println("         [--config <project.properties>] [--workdir <dir>] [--result <result.json>]");
        to.println("         [--emit keyvalue|json|kestra] [--exit-zero] [--io-log <file>] [--mml-log <file>]");
        to.println("       ... WrapperMain --list");
        return problem == null ? 0 : 2;
    }
}
