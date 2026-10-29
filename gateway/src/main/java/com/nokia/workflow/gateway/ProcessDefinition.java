package com.nokia.workflow.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A process version's definition: the steps an order and its children go through, when each step is offered,
 * which flow runs it and where each of the flow's inputs comes from. Parsed and validated as a whole; every
 * problem is reported, not only the first. Nothing project- or activity-specific is known here.
 *
 * <pre>
 * {"namespace": "cr_automation",
 *  "layout": {"child_order": {"x": 320, "y": 0}},              where the designer draws each step (optional)
 *  "steps": [{"id": "child_order", "label": "...", "description": "...", "scope": "ORDER" | "CHILD",
 *             "starts_order": false, "follows": ["validate"],  the arrows of the diagram (optional)
 *             "otherwise": "validate",          where the diagram's FALSE arrow of available_when leads (optional)
 *             "available_when": {"order.stage": {"in": ["VALIDATED"]}, "child.mop_nodes": {"non_empty": true}},
 *             "runs": {"flow": "cr_child_order"},
 *             "inputs": {"order_id": {"from": "order.value"},
 *                        "order_type": {"value": "EXISTING"},
 *                        "cr_name": {"from": "user", "type": "SELECT", "label": "...", "required": true,
 *                                    "options_from": "order.cr_options"},
 *                        "nodes": {"from": "user", "type": "MULTISELECT", "options_from": "child.node_options",
 *                                  "all_value": "*"}},
 *             "pipelines": {"node": [...widgets], "after_nodes": [...widgets]},   see {@link Pipeline}; the flow's
 *                                                label pipeline_hooks lists the hooks it runs (node / after_nodes: CHILD)
 *             "auto_start": {"for_each": "cr_name", "max_running": 2}}]}   started by the gateway as soon as it is
 *                                                offered, once per value of that SELECT input (see {@link AutoStarter})
 * </pre>
 */
final class ProcessDefinition {

    enum Scope { ORDER, CHILD }

    enum InputType { STRING, BOOL, SELECT, MULTISELECT, FILE }

    enum Source { VALUE, CONTEXT, USER }

    record Condition(String path, String op, Object operand) {
    }

    record Input(String name, Source source, String value, String path, InputType type, String label, boolean required,
                 Object defaultValue, String optionsFrom, List<Map<String, Object>> options, String allValue) {
    }

    /**
     * {@code follows}: the steps this one comes after - the arrows of the process diagram; {@code otherwise}: the step
     * the diagram's FALSE arrow leads to while this one is not offered (drawing only, may be null).
     */
    record Step(String id, String label, String description, Scope scope, boolean startsOrder, String flow,
                List<Condition> availableWhen, List<Input> inputs, List<String> follows,
                Map<Pipeline.Hook, Pipeline> pipelines, AutoStart autoStart, String otherwise) {
    }

    /**
     * Start the step without a person as soon as it is offered: once, or once per value of the SELECT input
     * {@code forEach}; {@code maxRunning} &gt; 0 limits how many of these starts run at the same time.
     */
    record AutoStart(String forEach, int maxRunning) {
    }

    /** The definition is not valid; every problem found is listed. */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final List<String> problems;

        Invalid(List<String> problems) {
            super("The process definition is not valid: " + String.join("; ", problems));
            this.problems = problems;
        }
    }

    private static final Pattern STEP_ID = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern FLOW_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
    private static final Pattern CONTEXT_PATH = Pattern.compile("(order|child)(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    private static final Set<String> OPERATORS = Set.of("in", "not_in", "equals", "non_empty", "empty");

    final String namespace;
    final List<Step> steps;

    private ProcessDefinition(String namespace, List<Step> steps) {
        this.namespace = namespace;
        this.steps = steps;
    }

    Step step(String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** @throws Invalid with all problems found */
    @SuppressWarnings("unchecked")
    static ProcessDefinition parse(Object json) {
        List<String> problems = new ArrayList<>();
        if (!(json instanceof Map<?, ?> root)) {
            throw new Invalid(List.of("The definition must be a JSON object."));
        }
        String namespace = str(root.get("namespace"));
        if (namespace == null || !FLOW_ID.matcher(namespace).matches()) {
            problems.add("'namespace' is required (the Kestra namespace of the step flows).");
        }
        List<Step> steps = new ArrayList<>();
        if (!(root.get("steps") instanceof List<?> list) || list.isEmpty()) {
            problems.add("'steps' must be a non-empty list.");
        } else {
            Set<String> ids = new LinkedHashSet<>();
            int i = 0;
            for (Object o : list) {
                i++;
                if (!(o instanceof Map<?, ?> m)) {
                    problems.add("Step " + i + " must be an object.");
                    continue;
                }
                String rawId = str(m.get("id"));
                if (rawId != null && !ids.add(rawId)) {
                    problems.add("Step id '" + rawId + "' is used twice.");
                }
                Step s = step((Map<String, Object>) m, i, problems);
                if (s != null) {
                    steps.add(s);
                }
            }
            if (problems.isEmpty()) {
                diagram(root, steps, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new ProcessDefinition(namespace, List.copyOf(steps));
    }

    @SuppressWarnings("unchecked")
    private static Step step(Map<String, Object> m, int index, List<String> problems) {
        int before = problems.size();
        String id = str(m.get("id"));
        String where = "Step " + (id == null ? "#" + index : "'" + id + "'");
        if (id == null || !STEP_ID.matcher(id).matches()) {
            problems.add(where + ": 'id' must be lower-case letters, digits and _ (starting with a letter).");
        }
        String label = str(m.get("label"));
        if (label == null || label.isBlank()) {
            problems.add(where + ": 'label' is required.");
        }
        Scope scope = null;
        try {
            scope = Scope.valueOf(String.valueOf(m.get("scope")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add(where + ": 'scope' must be ORDER or CHILD.");
        }
        boolean startsOrder = bool(m.get("starts_order"));
        if (startsOrder && scope == Scope.CHILD) {
            problems.add(where + ": only an ORDER step can start an order.");
        }
        String flow = m.get("runs") instanceof Map<?, ?> runs ? str(runs.get("flow")) : null;
        if (flow == null || !FLOW_ID.matcher(flow).matches()) {
            problems.add(where + ": 'runs.flow' must name the Kestra flow that runs the step.");
        }

        List<Condition> conditions = new ArrayList<>();
        Object aw = m.get("available_when");
        if (aw != null && !(aw instanceof Map)) {
            problems.add(where + ": 'available_when' must be an object of path -> {operator: operand}.");
        } else if (aw != null) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) aw).entrySet()) {
                String path = e.getKey();
                checkPath(where, "available_when", path, scope, startsOrder, problems);
                if (!(e.getValue() instanceof Map<?, ?> ops) || ops.size() != 1) {
                    problems.add(where + ": available_when." + path + " must be exactly one {operator: operand}.");
                    continue;
                }
                Map.Entry<?, ?> op = ops.entrySet().iterator().next();
                String operator = String.valueOf(op.getKey());
                if (!OPERATORS.contains(operator)) {
                    problems.add(where + ": available_when." + path + ": unknown operator '" + operator + "' (use " + OPERATORS + ").");
                } else if ((operator.equals("in") || operator.equals("not_in")) && !(op.getValue() instanceof List)) {
                    problems.add(where + ": available_when." + path + "." + operator + " needs a list.");
                }
                conditions.add(new Condition(path, operator, op.getValue()));
            }
        }

        List<Input> inputs = new ArrayList<>();
        Object in = m.get("inputs");
        if (in != null && !(in instanceof Map)) {
            problems.add(where + ": 'inputs' must be an object of flow input -> source.");
        } else if (in != null) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) in).entrySet()) {
                Input input = input(where, e.getKey(), e.getValue(), scope, startsOrder, problems);
                if (input != null) {
                    inputs.add(input);
                }
            }
        }
        Map<Pipeline.Hook, Pipeline> pipelines = new LinkedHashMap<>();
        Object pl = m.get("pipelines");
        if (pl != null && !(pl instanceof Map)) {
            problems.add(where + ": 'pipelines' must be an object of hook (node, after_nodes, after_step) -> list of widgets.");
        } else if (pl != null) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pl).entrySet()) {
                Pipeline.Hook hook = Pipeline.Hook.of(e.getKey());
                if (hook == null) {
                    problems.add(where + ": unknown pipeline '" + e.getKey() + "' (use node, after_nodes or after_step).");
                } else if (hook != Pipeline.Hook.AFTER_STEP && scope != Scope.CHILD) {
                    problems.add(where + ": only a CHILD step can have a " + hook.key + " pipeline.");
                } else {
                    Pipeline p = Pipeline.parse(where + ", pipeline " + hook.key, hook, e.getValue(), problems);
                    if (p != null) {
                        pipelines.put(hook, p);
                    }
                }
            }
        }
        AutoStart autoStart = autoStart(where, m.get("auto_start"), startsOrder, inputs, problems);
        if (problems.size() > before) {
            return null;
        }
        String description = str(m.get("description"));
        return new Step(id, label, description, scope, startsOrder, flow, List.copyOf(conditions), List.copyOf(inputs),
                follows(m.get("follows")), Map.copyOf(pipelines), autoStart, str(m.get("otherwise")));
    }

    /** Null when the step is started by a person only. */
    private static AutoStart autoStart(String where, Object o, boolean startsOrder, List<Input> inputs, List<String> problems) {
        if (o == null) {
            return null;
        }
        String at = where + ": auto_start";
        if (!(o instanceof Map<?, ?> m)) {
            problems.add(at + " must be an object {for_each, max_running}.");
            return null;
        }
        if (startsOrder) {
            problems.add(at + ": a step that starts an order cannot start by itself (there is no order yet).");
        }
        for (Object k : m.keySet()) {
            if (!"for_each".equals(k) && !"max_running".equals(k)) {
                problems.add(at + ": unknown key '" + k + "' (use for_each, max_running).");
            }
        }
        String forEach = str(m.get("for_each"));
        if (forEach != null && inputs.stream().noneMatch(i -> i.name().equals(forEach) && i.source() == Source.USER
                && i.type() == InputType.SELECT)) {
            problems.add(at + ": for_each must name a SELECT input asked from the user ('" + forEach + "' is not one).");
        }
        for (Input i : inputs) {
            if (i.source() != Source.USER || i.name().equals(forEach)) {
                continue;
            }
            if (i.type() == InputType.FILE && i.required()) {
                problems.add(at + ": input '" + i.name() + "' is a required file, which nobody gives when the step starts by itself.");
            } else if (i.required() && i.type() != InputType.BOOL && i.defaultValue() == null) {
                problems.add(at + ": input '" + i.name() + "' is required: give it a default, used when the step starts by itself.");
            }
        }
        Long max = number(m.containsKey("max_running") ? m.get("max_running") : "0");
        if (max == null || max < 0 || max > 50) {
            problems.add(at + ": max_running must be 0 (no limit) to 50.");
            max = 0L;
        }
        return new AutoStart(forEach, max.intValue());
    }

    private static List<String> follows(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                out.add(String.valueOf(x));
            }
        }
        return List.copyOf(out);
    }

    /** The diagram parts: every arrow joins two steps of the definition; the layout only places existing steps. */
    @SuppressWarnings("unchecked")
    private static void diagram(Map<?, ?> root, List<Step> steps, List<String> problems) {
        Map<String, String> pipelineFlows = new LinkedHashMap<>();
        for (Step s : steps) {
            if (!s.pipelines().isEmpty() && pipelineFlows.putIfAbsent(s.flow(), s.id()) != null) {
                problems.add("Steps '" + pipelineFlows.get(s.flow()) + "' and '" + s.id() + "' both attach pipelines to flow "
                        + s.flow() + ": only one step per flow can.");
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        steps.forEach(s -> ids.add(s.id()));
        for (Step s : steps) {
            if (s.otherwise() != null && (s.otherwise().equals(s.id()) || !ids.contains(s.otherwise()))) {
                problems.add("Step '" + s.id() + "': otherwise '" + s.otherwise() + "' must be another step of this process.");
            }
            for (String f : s.follows()) {
                if (f.equals(s.id())) {
                    problems.add("Step '" + s.id() + "' cannot follow itself.");
                } else if (!ids.contains(f)) {
                    problems.add("Step '" + s.id() + "' follows '" + f + "', which is not a step of this process.");
                }
            }
        }
        Object layout = root.get("layout");
        if (layout != null && !(layout instanceof Map)) {
            problems.add("'layout' must be an object of step id -> {x, y}.");
        } else if (layout != null) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) layout).entrySet()) {
                if (!ids.contains(e.getKey())) {
                    problems.add("layout." + e.getKey() + ": there is no such step.");
                } else if (!(e.getValue() instanceof Map<?, ?> xy) || !isDecimal(xy.get("x")) || !isDecimal(xy.get("y"))) {
                    problems.add("layout." + e.getKey() + " must be {x: number, y: number}.");
                }
            }
        }
    }

    private static boolean isDecimal(Object o) {
        try {
            return o != null && !Double.isNaN(Double.parseDouble(String.valueOf(o)));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Input input(String where, String name, Object spec, Scope scope, boolean startsOrder, List<String> problems) {
        String at = where + ": input '" + name + "'";
        if (!INPUT_NAME.matcher(name).matches()) {
            problems.add(at + ": not a valid flow input name.");
            return null;
        }
        if (!(spec instanceof Map<?, ?> s0)) {
            problems.add(at + " must be an object.");
            return null;
        }
        Map<String, Object> s = (Map<String, Object>) s0;
        boolean hasValue = s.containsKey("value");
        String from = str(s.get("from"));
        if (hasValue == (from != null)) {
            problems.add(at + ": give exactly one of 'value' (a constant) or 'from' (user, or an order. / child. path).");
            return null;
        }
        if (hasValue) {
            return new Input(name, Source.VALUE, str(s.get("value")), null, null, null, false, null, null, null, null);
        }
        if (!"user".equals(from)) {
            checkPath(where, "input " + name, from, scope, startsOrder, problems);
            return new Input(name, Source.CONTEXT, null, from, null, null, false, null, null, null, null);
        }
        InputType type;
        try {
            type = InputType.valueOf(String.valueOf(s.get("type")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add(at + ": 'type' must be one of STRING, BOOL, SELECT, MULTISELECT, FILE.");
            return null;
        }
        String optionsFrom = str(s.get("options_from"));
        List<Map<String, Object>> options = null;
        if (s.get("options") instanceof List<?> list) {
            options = new ArrayList<>();
            for (Object o : list) {
                options.add(o instanceof Map<?, ?> om ? new LinkedHashMap<>((Map<String, Object>) om)
                        : Map.of("value", String.valueOf(o), "label", String.valueOf(o)));
            }
        }
        if (type == InputType.SELECT || type == InputType.MULTISELECT) {
            if ((optionsFrom == null) == (options == null)) {
                problems.add(at + ": a " + type + " needs exactly one of 'options' (a list) or 'options_from' (a path).");
            } else if (optionsFrom != null) {
                checkPath(where, "input " + name, optionsFrom, scope, startsOrder, problems);
            }
        } else if (optionsFrom != null || options != null) {
            problems.add(at + ": options only apply to SELECT and MULTISELECT.");
        }
        String allValue = str(s.get("all_value"));
        if (allValue != null && type != InputType.MULTISELECT) {
            problems.add(at + ": 'all_value' only applies to MULTISELECT.");
        }
        String label = str(s.get("label"));
        return new Input(name, Source.USER, null, null, type, label == null ? name : label,
                bool(s.get("required")), s.get("default"), optionsFrom, options, allValue);
    }

    private static void checkPath(String where, String what, String path, Scope scope, boolean startsOrder, List<String> problems) {
        if (path == null || !CONTEXT_PATH.matcher(path).matches()) {
            problems.add(where + ": " + what + ": '" + path + "' is not a path like order.stage or child.mop_nodes.");
        } else if (startsOrder) {
            problems.add(where + ": " + what + ": a step that starts an order has no order yet ('" + path + "').");
        } else if (path.startsWith("child.") && scope != Scope.CHILD) {
            problems.add(where + ": " + what + ": '" + path + "' needs a CHILD step.");
        }
    }

    // ------------------------------------------------------------------ evaluation against an order / child

    /** The value at {@code order.x.y} / {@code child.x}, or null. */
    static Object resolve(String path, Map<String, Object> order, Map<String, Object> child) {
        String[] parts = path.split("\\.");
        Object cur = "order".equals(parts[0]) ? order : child;
        for (int i = 1; i < parts.length && cur != null; i++) {
            cur = cur instanceof Map<?, ?> m ? m.get(parts[i]) : null;
        }
        return cur;
    }

    static boolean available(Step step, Map<String, Object> order, Map<String, Object> child) {
        for (Condition c : step.availableWhen()) {
            if (!holds(c, resolve(c.path(), order, child))) {
                return false;
            }
        }
        return true;
    }

    static boolean holds(Condition c, Object value) {
        return switch (c.op()) {
            case "in" -> value != null && strings(c.operand()).contains(String.valueOf(value));
            case "not_in" -> value == null || !strings(c.operand()).contains(String.valueOf(value));
            case "equals" -> value != null && String.valueOf(value).equals(String.valueOf(c.operand()));
            case "non_empty" -> isFalse(c.operand()) ? isEmpty(value) : !isEmpty(value);
            case "empty" -> isFalse(c.operand()) ? !isEmpty(value) : isEmpty(value);
            default -> false;
        };
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof Collection<?> col && col.isEmpty()) || (v instanceof Map<?, ?> m && m.isEmpty())
                || String.valueOf(v).isEmpty();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** JSON true - as a Boolean or, from SimpleJson, as the text "true". */
    static boolean bool(Object o) {
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static boolean isFalse(Object o) {
        return Boolean.FALSE.equals(o) || "false".equalsIgnoreCase(String.valueOf(o));
    }

    /** A JSON number - as a Number or, from SimpleJson, as its text; null when absent or not a number. */
    static Long number(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? null : Long.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
