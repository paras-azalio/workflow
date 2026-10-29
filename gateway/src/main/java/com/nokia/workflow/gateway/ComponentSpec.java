package com.nokia.workflow.gateway;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A component's definition (one version): a jar or script the steps can call, run by the component wrapper through
 * {@code bin/component.sh}. Checked as a whole; written for the wrapper as {@code component.properties} next to its
 * files ({@code components/<name>/v<version>/} in the project's namespace, or an existing folder such as nei/clicr).
 *
 * <pre>
 * {"description": "...", "kind": "WRAPPED" | "STANDALONE", "location": "UPLOADED" | "FOLDER", "folder": "nei/clicr",
 *  "wrapped":    {"ne_type": "CLICR", "product": "ANY", "version": "V1", "config_dir": "config",
 *                 "grc": {"KEY": "value"}, "login": {"KEY": "${secret:NAME}"}},
 *  "standalone": {"runtime": "python3" | "java" | "java8" | "shell", "command": "{runtime} {file:check.py} --x",
 *                 "input": "REQUEST_FILE" | "ARGS" | "ENV", "args": "--imsi {IMSI}",
 *                 "output": "RESPONSE_FILE" | "STDOUT_KEYVALUE" | "STDOUT_JSON" | "EXIT_CODE",
 *                 "success_key": "RESULT", "success_value": "OK", "timeout_sec": 300},
 *  "actions": [{"name": "CR_CIQ_PROCESSOR", "description": "...",
 *               "params": [{"name": "CIQ_FILE", "required": true, "default": "", "description": "...", "secret": false}],
 *               "results": [{"name": "CR_LIST", "description": "..."}]}]}
 * </pre>
 * WRAPPED is an NEI jar with the Macro Server contract (class macro_&lt;NE&gt;_&lt;PRODUCT&gt;_&lt;VERSION&gt;); STANDALONE
 * any program, its values going in and its results coming back as chosen.
 */
final class ComponentSpec {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    static final Set<String> RUNTIMES = Set.of("python3", "java", "java8", "shell");
    private static final Pattern UPPER = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,127}");
    private static final Pattern FOLDER = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}");
    private static final Pattern FILE_REF = Pattern.compile("\\{file:([^}]+)}");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|pswd|pwd|secret|token).*");
    private static final Set<String> INPUTS = Set.of("REQUEST_FILE", "ARGS", "ENV");
    private static final Set<String> OUTPUTS = Set.of("RESPONSE_FILE", "STDOUT_KEYVALUE", "STDOUT_JSON", "EXIT_CODE");
    private static final Set<String> ROOT_KEYS = Set.of("description", "kind", "location", "folder", "wrapped", "standalone", "actions");

    private ComponentSpec() {
    }

    /** The starting definition of a new component of this kind. */
    static String blank(String kind) {
        return "WRAPPED".equals(kind)
                ? "{\"kind\":\"WRAPPED\",\"location\":\"UPLOADED\",\"wrapped\":{\"ne_type\":\"\",\"product\":\"ANY\",\"version\":\"V1\","
                        + "\"config_dir\":\"config\",\"grc\":{},\"login\":{}},\"actions\":[]}"
                : "{\"kind\":\"STANDALONE\",\"location\":\"UPLOADED\",\"standalone\":{\"runtime\":\"python3\",\"command\":\"{runtime} {file:main.py}\","
                        + "\"input\":\"ARGS\",\"args\":\"\",\"output\":\"STDOUT_KEYVALUE\",\"timeout_sec\":300},\"actions\":[]}";
    }

    @SuppressWarnings("unchecked")
    static List<String> problems(Object json) {
        List<String> p = new ArrayList<>();
        if (!(json instanceof Map<?, ?> root)) {
            return List.of("The component must be a JSON object.");
        }
        for (Object k : root.keySet()) {
            if (!ROOT_KEYS.contains(String.valueOf(k))) {
                p.add("Unknown key '" + k + "' (use " + String.join(", ", new java.util.TreeSet<>(ROOT_KEYS)) + ").");
            }
        }
        String kind = str(root.get("kind"));
        if (!"WRAPPED".equals(kind) && !"STANDALONE".equals(kind)) {
            p.add("'kind' must be WRAPPED (an NEI jar) or STANDALONE (any program).");
        }
        String location = str(root.get("location"), "UPLOADED");
        if (!"UPLOADED".equals(location) && !"FOLDER".equals(location)) {
            p.add("'location' must be UPLOADED (files uploaded here) or FOLDER (a folder already in the namespace, e.g. nei/clicr).");
        } else if ("FOLDER".equals(location)) {
            String folder = str(root.get("folder"));
            if (folder == null || !FOLDER.matcher(folder).matches() || folder.contains("..")) {
                p.add("'folder' must be a folder of the namespace's files, e.g. nei/clicr.");
            }
        }
        if ("WRAPPED".equals(kind)) {
            Map<String, Object> w = root.get("wrapped") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            if (!(str(w.get("ne_type")) != null && UPPER.matcher(str(w.get("ne_type"))).matches())) {
                p.add("wrapped.ne_type must be the NE type in upper case, e.g. CLICR (class macro_CLICR_ANY_V1).");
            }
            for (String f : List.of("product", "version")) {
                String v = str(w.get(f), "product".equals(f) ? "ANY" : "V1");
                if (!UPPER.matcher(v).matches()) {
                    p.add("wrapped." + f + " must be upper case, e.g. " + ("product".equals(f) ? "ANY" : "V1") + ".");
                }
            }
            String configDir = str(w.get("config_dir"), "config");
            if (!FOLDER.matcher(configDir).matches() || configDir.contains("..")) {
                p.add("wrapped.config_dir must be a folder inside the component (its status-code tables), e.g. config.");
            }
            for (String group : List.of("grc", "login")) {
                values(p, "wrapped." + group, w.get(group));
            }
        } else if ("STANDALONE".equals(kind)) {
            Map<String, Object> s = root.get("standalone") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            if (!RUNTIMES.contains(str(s.get("runtime"), ""))) {
                p.add("standalone.runtime must be one of " + new java.util.TreeSet<>(RUNTIMES) + ".");
            }
            String command = str(s.get("command"));
            if (command == null || command.isBlank()) {
                p.add("standalone.command is required, e.g. {runtime} {file:check.py}.");
            } else if (command.contains("{{") || command.contains("{%")) {
                p.add("standalone.command: use {runtime}, {file:NAME} and {dir}; '{{' and '{%' are not allowed.");
            }
            String input = str(s.get("input"), "REQUEST_FILE");
            String output = str(s.get("output"), "REQUEST_FILE".equals(input) ? "RESPONSE_FILE" : "STDOUT_KEYVALUE");
            if (!INPUTS.contains(input)) {
                p.add("standalone.input must be one of " + new java.util.TreeSet<>(INPUTS) + ".");
            }
            if (!OUTPUTS.contains(output)) {
                p.add("standalone.output must be one of " + new java.util.TreeSet<>(OUTPUTS) + ".");
            } else if ("RESPONSE_FILE".equals(output) && !"REQUEST_FILE".equals(input)) {
                p.add("standalone.output RESPONSE_FILE needs input REQUEST_FILE (the response file is the second argument).");
            }
            if (str(s.get("args")) != null && !str(s.get("args")).isBlank() && !"ARGS".equals(input)) {
                p.add("standalone.args is used only with input ARGS.");
            }
            Long timeout = ProcessDefinition.number(s.getOrDefault("timeout_sec", "300"));
            if (timeout == null || timeout < 1 || timeout > 86400) {
                p.add("standalone.timeout_sec must be 1 to 86400.");
            }
        }
        actions(p, root.get("actions"));
        return p;
    }

    @SuppressWarnings("unchecked")
    private static void actions(List<String> p, Object o) {
        if (o == null) {
            return;
        }
        if (!(o instanceof List<?> list)) {
            p.add("'actions' must be a list.");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object a : list) {
            if (!(a instanceof Map<?, ?> am)) {
                p.add("Every action must be an object {name, params, results}.");
                continue;
            }
            String name = str(am.get("name"));
            String at = "action '" + (name == null ? "?" : name) + "'";
            if (name == null || !UPPER.matcher(name).matches()) {
                p.add(at + ": the name must be upper case, e.g. CR_CIQ_PROCESSOR.");
            } else if (!names.add(name)) {
                p.add(at + ": the name is used twice.");
            }
            for (String part : List.of("params", "results")) {
                Object items = am.get(part);
                if (items == null) {
                    continue;
                }
                if (!(items instanceof List<?> il)) {
                    p.add(at + ": " + part + " must be a list.");
                    continue;
                }
                Set<String> seen = new LinkedHashSet<>();
                for (Object i : il) {
                    String n = i instanceof Map<?, ?> im ? str(im.get("name")) : null;
                    if (n == null || !KEY.matcher(n).matches()) {
                        p.add(at + ": every entry of " + part + " needs a valid name.");
                    } else if (!seen.add(n)) {
                        p.add(at + ": " + part + " names '" + n + "' twice.");
                    } else if ("params".equals(part) && SECRET_KEY.matcher(n).matches()
                            && str(((Map<String, Object>) i).get("default")) != null
                            && !str(((Map<String, Object>) i).get("default")).isBlank()
                            && !str(((Map<String, Object>) i).get("default")).matches("\\$\\{secret:[A-Za-z0-9_]+}")) {
                        p.add(at + ": parameter " + n + ": a secret's default is written as ${secret:NAME}, never as its value.");
                    }
                }
            }
        }
    }

    private static void values(List<String> p, String where, Object o) {
        if (o == null) {
            return;
        }
        if (!(o instanceof Map<?, ?> m)) {
            p.add(where + " must be an object of KEY -> value.");
            return;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (!KEY.matcher(k).matches()) {
                p.add(where + "." + k + ": not a valid key.");
            }
            if (SECRET_KEY.matcher(k).matches() && !v.isEmpty() && !v.matches("\\$\\{secret:[A-Za-z0-9_]+}")) {
                p.add(where + "." + k + ": a secret is written as ${secret:NAME} (a Kestra secret), never as its value.");
            }
        }
    }

    /** Where the component's own files are: its version folder, or the existing folder it names. */
    static String home(Map<String, Object> spec, String dir) {
        return "FOLDER".equals(str(spec.get("location"), "UPLOADED")) ? str(spec.get("folder")) : dir;
    }

    /**
     * The wrapper's settings of this version, read by bin/component.sh with the project's config first.
     *
     * @param runtimes runtime name -> program, e.g. python3 -> python3, java8 -> /opt/java8/bin/java
     */
    @SuppressWarnings("unchecked")
    static String properties(Map<String, Object> spec, String name, String dir, Map<String, String> runtimes) throws IOException {
        Properties out = new Properties();
        String kind = str(spec.get("kind"));
        String home = home(spec, dir);
        out.setProperty("component.kind", kind);
        out.setProperty("component.name", name);
        out.setProperty("component.home", home);
        if ("WRAPPED".equals(kind)) {
            Map<String, Object> w = (Map<String, Object>) spec.getOrDefault("wrapped", Map.of());
            String ne = str(w.get("ne_type"), "");
            String prefix = "nei." + ne.toLowerCase(java.util.Locale.ROOT) + ".";
            out.setProperty("component.ne_type", ne);
            out.setProperty("component.product", str(w.get("product"), "ANY"));
            out.setProperty("component.version", str(w.get("version"), "V1"));
            out.setProperty(prefix + "configdir", home + "/" + str(w.get("config_dir"), "config"));
            for (String group : List.of("grc", "login")) {
                if (w.get(group) instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> out.setProperty(prefix + group + "." + k,
                            v == null ? "" : String.valueOf(v).replace("{dir}", "${WORKDIR}/" + home)));
                }
            }
        } else {
            Map<String, Object> s = (Map<String, Object>) spec.getOrDefault("standalone", Map.of());
            String c = "command." + name;
            out.setProperty(c, command(str(s.get("command"), ""), str(s.get("runtime"), "shell"), home, runtimes));
            out.setProperty(c + ".timeout_sec", str(s.get("timeout_sec"), "300"));
            String input = str(s.get("input"), "REQUEST_FILE");
            out.setProperty(c + ".input", input);
            out.setProperty(c + ".output", str(s.get("output"), "REQUEST_FILE".equals(input) ? "RESPONSE_FILE" : "STDOUT_KEYVALUE"));
            if (str(s.get("args")) != null) {
                out.setProperty(c + ".args", str(s.get("args")));
            }
            if (str(s.get("success_key")) != null && !str(s.get("success_key")).isBlank()) {
                out.setProperty(c + ".success_key", str(s.get("success_key")));
                out.setProperty(c + ".success_value", str(s.get("success_value"), "SUCCESS"));
            }
        }
        StringWriter w = new StringWriter();
        out.store(w, "component " + name + " - written by the gateway from the Process designer; edit it there");
        return w.toString();
    }

    /** {runtime} -> the runtime's program, {file:x} -> the file in the component's folder, {dir} -> that folder. */
    static String command(String template, String runtime, String home, Map<String, String> runtimes) {
        String base = "${WORKDIR}/" + home;
        String c = template.replace("{runtime}", runtimes.getOrDefault(runtime, runtime)).replace("{dir}", base);
        Matcher m = FILE_REF.matcher(c);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(base + "/" + m.group(1).trim()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The actions as {name -> action}, for checking calls. */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> actions(Map<String, Object> spec) {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        if (spec.get("actions") instanceof List<?> l) {
            for (Object a : l) {
                if (a instanceof Map<?, ?> m && m.get("name") != null) {
                    out.put(String.valueOf(m.get("name")), (Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static String str(Object o, String fallback) {
        return o == null || String.valueOf(o).isBlank() ? fallback : String.valueOf(o);
    }
}
