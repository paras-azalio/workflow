package com.nokia.workflow.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an activity configuration (node type + sub-activity): what each NEI step of an order of this activity
 * sends in addition to, or instead of, what the flow sends. Applied inside the step by the request store
 * (store.sh step-config) and the NEI wrapper.
 *
 * <pre>
 * {"description": "...",
 *  "steps": {"*":             {"params": {...}, "login": {...}, "grc": {...}},
 *            "MOP_EXECUTION": {"params": {"ACTIVITY_PYTHON_SCRIPT_PATH": "${WORKDIR}/nei/clicr/script/python"},
 *                              "nodes": {"NORTH1": {"login": {"M2MUSER1": "north_ops",
 *                                                             "M2MPASSWORD1": "${secret:CLICR_NORTH_PASSWORD}"}},
 *                                        "SOUTH*": {"grc": {"NIAM_IP": "10.0.0.2"}}}}}}
 * </pre>
 * Section {@code *} applies to every step; node patterns use {@code *} and {@code ?}; later entries win.
 * References: {@code ${order.X}}, {@code ${child.X}}, {@code ${node}}, {@code ${ciq.X}}, {@code ${secret:NAME}},
 * {@code ${WORKDIR}}.
 */
final class ActivityConfig {

    /** Keys that choose which NEI task runs, or identify the request: the process decides them, never an activity. */
    static final Set<String> RESERVED = Set.of("ne_type", "product_name", "product_version", "task_type", "REQ_TYPE", "ACTION",
            "REQ_ID", "PARENT_REQ_ID", "CHILD_REQ_ID", "ORDER_NO");
    private static final Pattern STEP = Pattern.compile("\\*|[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_*?.\\-]{1,128}");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|pswd|pwd|secret|token).*");
    private static final Pattern REF = Pattern.compile("\\$\\{([^}]*)\\}");
    private static final Pattern REF_OK = Pattern.compile("(order|child)\\.[A-Za-z_][A-Za-z0-9_]*|node|ciq\\.[A-Za-z_][A-Za-z0-9_]*"
            + "|secret:[A-Za-z0-9_]+|WORKDIR");
    private static final Set<String> SECTION_KEYS = Set.of("params", "login", "grc", "nodes");
    private static final Set<String> NODE_KEYS = Set.of("params", "login", "grc");

    private ActivityConfig() {
    }

    /** Every problem of the configuration; empty when it is valid. */
    @SuppressWarnings("unchecked")
    static List<String> problems(Object json) {
        List<String> p = new ArrayList<>();
        if (!(json instanceof Map<?, ?> root)) {
            return List.of("The configuration must be a JSON object.");
        }
        for (Object k : root.keySet()) {
            if (!"description".equals(k) && !"steps".equals(k)) {
                p.add("Unknown key '" + k + "' (use description, steps).");
            }
        }
        Object steps = root.get("steps");
        if (steps != null && !(steps instanceof Map)) {
            p.add("'steps' must be an object of step name -> settings.");
        } else if (steps != null) {
            for (Map.Entry<String, Object> s : ((Map<String, Object>) steps).entrySet()) {
                String where = "steps." + s.getKey();
                if (!STEP.matcher(s.getKey()).matches()) {
                    p.add(where + ": a step name is * or upper-case letters, digits and _ (e.g. MOP_EXECUTION).");
                }
                if (!(s.getValue() instanceof Map<?, ?> section)) {
                    p.add(where + " must be an object.");
                    continue;
                }
                section(where, (Map<String, Object>) section, SECTION_KEYS, p);
                Object nodes = section.get("nodes");
                if (nodes != null && !(nodes instanceof Map)) {
                    p.add(where + ".nodes must be an object of node pattern -> settings.");
                } else if (nodes != null) {
                    for (Map.Entry<String, Object> n : ((Map<String, Object>) nodes).entrySet()) {
                        String at = where + ".nodes." + n.getKey();
                        if (!NODE.matcher(n.getKey()).matches()) {
                            p.add(at + ": a node pattern is letters, digits, _ . - and the wildcards * ?.");
                        }
                        if (!(n.getValue() instanceof Map<?, ?> nodeSection)) {
                            p.add(at + " must be an object.");
                        } else {
                            section(at, (Map<String, Object>) nodeSection, NODE_KEYS, p);
                        }
                    }
                }
            }
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static void section(String where, Map<String, Object> section, Set<String> allowed, List<String> p) {
        for (String k : section.keySet()) {
            if (!allowed.contains(k)) {
                p.add(where + ": unknown key '" + k + "' (use " + String.join(", ", new java.util.TreeSet<>(allowed)) + ").");
            }
        }
        for (String part : List.of("params", "login", "grc")) {
            Object values = section.get(part);
            if (values == null) {
                continue;
            }
            if (!(values instanceof Map)) {
                p.add(where + "." + part + " must be an object of KEY -> value.");
                continue;
            }
            for (Map.Entry<String, Object> v : ((Map<String, Object>) values).entrySet()) {
                String at = where + "." + part + "." + v.getKey();
                if (!KEY.matcher(v.getKey()).matches()) {
                    p.add(at + ": not a valid parameter name.");
                }
                if ("params".equals(part) && RESERVED.contains(v.getKey())) {
                    p.add(at + ": " + v.getKey() + " is decided by the process, not by an activity.");
                }
                if (v.getValue() instanceof Map || v.getValue() instanceof Collection) {
                    p.add(at + ": the value must be text, a number or true / false.");
                    continue;
                }
                String value = v.getValue() == null ? "" : String.valueOf(v.getValue());
                if (SECRET_KEY.matcher(v.getKey()).matches() && !value.isEmpty() && !value.matches("\\$\\{secret:[A-Za-z0-9_]+\\}")) {
                    p.add(at + ": a secret is written as ${secret:NAME} (a Kestra secret), never as its value.");
                }
                Matcher m = REF.matcher(value);
                while (m.find()) {
                    if (!REF_OK.matcher(m.group(1).trim()).matches()) {
                        p.add(at + ": unknown reference ${" + m.group(1) + "} (use order.X, child.X, node, ciq.X, secret:NAME, WORKDIR).");
                    }
                }
            }
        }
    }
}
