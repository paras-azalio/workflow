package com.nokia.workflow.wrapper.nei;

import com.nokia.workflow.wrapper.ComponentWrapper;
import com.nokia.workflow.wrapper.WrapperContext;
import com.nokia.workflow.wrapper.WrapperRequest;
import com.nokia.workflow.wrapper.WrapperResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code NEI}: runs an InstantLink NEI jar (e.g. {@code macro_FILECR_ANY_V1.jar}, {@code macro_CLICR_ANY_V1.jar})
 * <b>unchanged</b>, without a Macro Server process.
 *
 * <p>It does what the Macro Server does for one task: loads {@code macro_<ne_type>_<product_name>_<product_version>},
 * calls {@code init}, {@code login} (login parameters), the task method named by {@code task_type}
 * ({@code create} by default) with the request as the task arglist, and {@code logout}. The NEI reads its GRC
 * section from an {@code mds.rc} passed through the {@code RCFILE} system property - nothing else is needed
 * (verified 2026-10-26 with the CLICR and FileCR jars).
 *
 * <p>Status: the {@code Status} the task method returns - {@code 0 0} (connection OK, task OK) is SUCCESS,
 * task status 1 (partly failed) is WARNING, anything else FAILED. Results: every {@code 7200 NEW KEY VALUE} line
 * the NEI prints through {@code MacroSet.sendData} (later values of the same key win), plus
 * {@code NEI_CONN_STATUS}, {@code NEI_TASK_STATUS}, {@code NEI_MACRO}. Other output of the NEI goes to the log.
 *
 * <h2>Request</h2>
 * The NEI task arglist exactly as InstantLink would send it: {@code ne_type} (required), {@code product_name}
 * (default ANY), {@code product_version} (default V1), {@code task_type} (default create), {@code REQ_TYPE},
 * {@code ACTION} and the action's own parameters.
 *
 * <h2>Config (per NE type, lower-case or as written in ne_type)</h2>
 * <ul>
 *   <li>{@code nei.<ne_type>.rcfile} - an existing mds.rc, or</li>
 *   <li>{@code nei.<ne_type>.grc.<KEY>=value} - the GRC section; the wrapper writes the mds.rc itself
 *       (program {@code macro_<ne_type>_<product>_<version>}). Use {@code ${WORKDIR}} for paths inside the
 *       step's working folder (Kestra namespace files). Secret GRC values: environment variables
 *       {@code NEI_GRC_<KEY>} (e.g. {@code NEI_GRC_REPO_PASSWORD}) are added to the section.</li>
 *   <li>{@code nei.<ne_type>.login.<KEY>=value} - login arglist (e.g. {@code M2MUSER1}). Secrets: environment
 *       variables {@code NEI_LOGIN_<KEY>} (e.g. {@code NEI_LOGIN_M2MPASSWORD1}) override and keep them out of
 *       files and command lines.</li>
 * </ul>
 */
public final class NeiWrapper implements ComponentWrapper {

    static final Pattern NEW_LINE = Pattern.compile("^\\d+ NEW (\\S+)(?: (.*))?$");
    private static final String MS = "com.comptel.mds.sas.java_macroserver.";

    @Override
    public String name() {
        return "NEI";
    }

    @Override
    public String description() {
        return "Run an InstantLink NEI jar (macro_<ne_type>_<product>_<version>) for one task, without a Macro Server";
    }

    @Override
    public WrapperResult execute(WrapperRequest req, WrapperContext ctx) throws Exception {
        String neType = req.require("ne_type");
        String product = req.get("product_name", "ANY");
        String version = req.get("product_version", "V1");
        String taskType = req.get("task_type", "create").toLowerCase(Locale.ROOT);
        String macroName = "macro_" + neType + "_" + product + "_" + version;
        if (!taskType.matches("create|modify|delete|display")) {
            return WrapperResult.failed("Unsupported task_type '" + taskType + "' - expected create, modify, delete or display");
        }

        Class<?> macroClass;
        try {
            macroClass = Class.forName(macroName);
        } catch (ClassNotFoundException e) {
            return WrapperResult.failed("NEI class " + macroName + " not on the classpath (add its jar and lib/ to the step)");
        }

        File rc = rcFile(ctx, neType, macroName);
        if (rc == null) {
            return WrapperResult.failed("No GRC config for " + neType + ": set nei." + neType + ".rcfile or nei."
                    + neType + ".grc.* in the project config");
        }
        System.setProperty("RCFILE", rc.getAbsolutePath());
        ctx.log("NEI " + macroName + " task_type=" + taskType + " RCFILE=" + rc.getAbsolutePath());
        String configDir = cfg(ctx, neType, "configdir");
        prepareMacroServer(configDir == null ? null : ctx.resolve(configDir), ctx);
        File mml = ctx.option("mml.log") == null ? null : ctx.resolve(ctx.option("mml.log"));
        if (mml != null) {
            setMmlLog(mml, ctx);
        }

        // Capture what the NEI prints (sendData -> System.out) and copy it to the step log (stderr).
        PrintStream before = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new Tee(captured, before), true, "UTF-8"));
        Object status;
        Object macro = null;
        try {
            macro = macroClass.newInstance();
            invoke(macroClass, macro, "init", newArglist(new LinkedHashMap<String, String>()));
            Object login = invoke(macroClass, macro, "login", newArglist(loginParams(ctx, neType)));
            if (failed(login)) {
                status = login;
            } else {
                Map<String, String> task = new LinkedHashMap<String, String>(req.asMap());
                putIfAbsent(task, "task_type", taskType);
                putIfAbsent(task, "product_name", product);
                putIfAbsent(task, "product_version", version);
                status = invoke(macroClass, macro, taskType, newArglist(task));
                try {
                    invoke(macroClass, macro, "logout", newArglist(new LinkedHashMap<String, String>()));
                } catch (Exception ignored) {
                    // logout result does not change the task result
                }
            }
        } finally {
            System.out.flush();
            System.setOut(before);
            closeMmlWriter(macro);
        }
        if (mml != null) {   // the Macro Server closes each task in the MML log with "$END$ <task id>"
            appendLine(mml, "$END$ " + req.get("REQ_ID", req.get("CHILD_REQ_ID", macroName + "_" + taskType)));
        }

        Map<String, String> results = new LinkedHashMap<String, String>();
        List<String> log = new ArrayList<String>();
        for (String line : new String(captured.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n")) {
            Matcher m = NEW_LINE.matcher(line);
            if (m.matches()) {
                results.put(m.group(1), m.group(2) == null ? "" : m.group(2));
            } else if (!line.trim().isEmpty()) {
                log.add(line);
            }
        }

        int conn = intCall(status, "getConnStatus");
        int task = intCall(status, "getTaskStatus");
        String neiMessage = results.get("STATUS_MESSAGE");
        WrapperResult r;
        if (conn != 0) {
            r = WrapperResult.failed(macroName + " connection status " + conn + msg(neiMessage));
        } else if (task == 0) {
            r = WrapperResult.success(macroName + " task OK" + msg(neiMessage));
        } else if (task == 1) {
            r = WrapperResult.warning(macroName + " task partly failed" + msg(neiMessage));
        } else {
            r = WrapperResult.failed(macroName + " task failed (task status " + task + ")" + msg(neiMessage));
        }
        return r.putAll(results)
                .put("NEI_MACRO", macroName)
                .put("NEI_CONN_STATUS", conn)
                .put("NEI_TASK_STATUS", task)
                .addLog(log);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Points the Macro Server's MML log at {@code file} - what {@code Set logfile logs/test.mml} does in a Macro
     * Server script. {@code MacroSet.printMml} appends every MML line there (and flushes); with no MML log set the
     * NEI's MML lines are dropped.
     */
    static void setMmlLog(File file, WrapperContext ctx) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            Class<?> ms = Class.forName(MS + "MacroServer");
            for (String[] f : new String[][] {{"mmlLogFile", "string"}, {"file", "file"}}) {
                java.lang.reflect.Field field = ms.getDeclaredField(f[0]);
                field.setAccessible(true);
                field.set(null, "file".equals(f[1]) ? (Object) file.getAbsoluteFile() : file.getAbsolutePath());
            }
            ctx.log("NEI MML log: " + file.getAbsolutePath());
        } catch (Exception e) {
            ctx.log("NEI MML log not set (" + e + ")");
        }
    }

    /**
     * {@code MacroSet.printMml} keeps the MML log open in a static writer; the Macro Server closes it after each
     * task with {@code MacroSet.closeMmlWriter()}. Without that the file stays locked (Windows) and the next task
     * in the same JVM keeps writing to the old file.
     */
    private static void closeMmlWriter(Object macro) {
        if (macro == null) {
            return;
        }
        for (Class<?> c = macro.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("closeMmlWriter");
                m.setAccessible(true);
                m.invoke(macro);
                return;
            } catch (NoSuchMethodException e) {
                // look in the superclass (MacroSet)
            } catch (Exception e) {
                return;   // a diagnostic file; closing it never changes the task result
            }
        }
    }

    private static void appendLine(File file, String line) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
            try {
                w.write(line + System.lineSeparator());
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
            // the MML log is a diagnostic; failing to close it never changes the task result
        }
    }

    /**
     * The one piece of Macro Server state an NEI needs besides its GRC: the status-code tables
     * ({@code error_codes.txt}, {@code phase_codes.txt}, {@code supp_codes.txt} from the NEI package's config
     * folder) that {@code MacroSet.sendErrorMsg} looks up. Without them an NEI's own error reporting (e.g. a
     * missing mandatory parameter) throws a NullPointerException. Done like exec_sim's SimActivityRunner:
     * create the Macro Server singleton, then load the files with the Macro Server's own reader. With no config
     * folder the tables are left empty, so error reporting still works (without code texts).
     */
    static void prepareMacroServer(File configDir, WrapperContext ctx) {
        try {
            Class<?> ms = Class.forName(MS + "MacroServer");
            java.lang.reflect.Field inst = ms.getDeclaredField("macroServer");
            inst.setAccessible(true);
            if (inst.get(null) == null) {
                java.lang.reflect.Field mods = java.lang.reflect.Field.class.getDeclaredField("modifiers");
                mods.setAccessible(true);   // Java 8: the field is private static final
                mods.setInt(inst, inst.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                inst.set(null, ms.newInstance());
            }
            for (String table : new String[] {"errorCodes", "phaseCodes", "suppCodes"}) {
                java.lang.reflect.Field f = ms.getDeclaredField(table);
                f.setAccessible(true);
                if (f.get(null) == null) {
                    f.set(null, new java.util.Hashtable<String, String>());
                }
            }
            if (configDir != null && configDir.isDirectory()) {
                Method read = ms.getDeclaredMethod("readFile", String.class, String.class);
                read.setAccessible(true);
                String dir = configDir.getAbsolutePath().replace('\\', '/') + "/";
                for (String name : new String[] {"error_codes.txt", "phase_codes.txt", "supp_codes.txt"}) {
                    if (new File(configDir, name).isFile()) {
                        read.invoke(null, dir, name);
                    }
                }
                ctx.log("NEI status codes loaded from " + dir);
            }
        } catch (Exception e) {
            ctx.log("NEI status codes not prepared (" + e + "); NEI error reporting may fail");
        }
    }

    /** The configured mds.rc, or one written from {@code nei.<ne>.grc.*}; null when neither is configured. */
    static File rcFile(WrapperContext ctx, String neType, String macroName) throws Exception {
        String existing = cfg(ctx, neType, "rcfile");
        if (existing != null) {
            return ctx.resolve(existing);
        }
        Map<String, String> grc = section(ctx, neType, "grc");
        if (grc.isEmpty()) {
            return null;
        }
        grc.putAll(env("NEI_GRC_"));   // secrets (e.g. REPO_PASSWORD) stay out of the config file
        StringBuilder sb = new StringBuilder("# written by the NEI component wrapper from the project config\n");
        sb.append("program: ").append(macroName).append('\n');
        for (Map.Entry<String, String> e : grc.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        File f = File.createTempFile("nei-" + neType + "-", ".rc");
        f.deleteOnExit();
        Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8);
        try {
            w.write(sb.toString());
        } finally {
            w.close();
        }
        return f;
    }

    /** Login arglist: config {@code nei.<ne>.login.*}, overridden by environment variables {@code NEI_LOGIN_<KEY>}. */
    static Map<String, String> loginParams(WrapperContext ctx, String neType) {
        Map<String, String> login = section(ctx, neType, "login");
        login.putAll(env("NEI_LOGIN_"));
        return login;
    }

    /** Environment variables {@code <prefix><KEY>} as KEY -> value. */
    private static Map<String, String> env(String prefix) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getKey().length() > prefix.length()) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    /** All {@code nei.<ne>.<part>.KEY} entries (ne type as written or lower-case), KEY case kept. */
    private static Map<String, String> section(WrapperContext ctx, String neType, String part) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Properties p = ctx.config();
        for (String ne : new String[] {neType.toLowerCase(Locale.ROOT), neType}) {
            String prefix = "nei." + ne + "." + part + ".";
            for (String k : p.stringPropertyNames()) {
                if (k.startsWith(prefix)) {
                    out.put(k.substring(prefix.length()), ctx.config(k));
                }
            }
        }
        return out;
    }

    private static String cfg(WrapperContext ctx, String neType, String key) {
        String v = ctx.config("nei." + neType.toLowerCase(Locale.ROOT) + "." + key);
        return v != null ? v : ctx.config("nei." + neType + "." + key);
    }

    private static Object newArglist(Map<String, String> values) throws Exception {
        Class<?> c = Class.forName(MS + "Arglist");
        Object a = c.newInstance();
        Method set = c.getMethod("setArg", String.class, String.class);
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() != null) {
                set.invoke(a, e.getKey(), e.getValue());
            }
        }
        return a;
    }

    private static Object invoke(Class<?> macroClass, Object macro, String method, Object arglist) throws Exception {
        Method m = macroClass.getMethod(method, Class.forName(MS + "Arglist"));
        try {
            return m.invoke(macro, arglist);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        }
    }

    private static boolean failed(Object status) throws Exception {
        return intCall(status, "getConnStatus") != 0 || intCall(status, "getTaskStatus") != 0;
    }

    private static int intCall(Object status, String getter) throws Exception {
        if (status == null) {
            return -1;
        }
        return ((Integer) status.getClass().getMethod(getter).invoke(status)).intValue();
    }

    private static void putIfAbsent(Map<String, String> m, String k, String v) {
        if (!m.containsKey(k)) {
            m.put(k, v);
        }
    }

    private static String msg(String neiMessage) {
        return neiMessage == null || neiMessage.isEmpty() ? "" : ": " + neiMessage;
    }

    /** Writes to two streams. */
    private static final class Tee extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        Tee(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int c) throws java.io.IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            a.flush();
            b.flush();
        }
    }
}
