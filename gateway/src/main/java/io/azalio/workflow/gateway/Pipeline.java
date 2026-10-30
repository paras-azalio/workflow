package io.azalio.workflow.gateway;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pipeline of widgets a process step attaches to its flow: {@code node} runs for each node instead of the flow's
 * default node work, {@code after_nodes} once after all nodes, {@code after_step} once after the flow's own NEI step
 * (e.g. the MOP generation of a child order), with that step's result. A pipeline is a list of widgets; If / else and For
 * each hold their own lists, so a pipeline is always a tree (no loops, no joins) and compiles to plain Kestra tasks
 * ({@link PipelineCompiler}).
 *
 * <pre>
 * NEI       {"id", "widget": "NEI", "component": "clicr", "action": "MOP_EXECUTION", "step": "PRECHECK",
 *            "sub_activity": "${activity.sub_activity}_PRECHECK", "params": {"KEY": "value"}, "on_failure": "STOP"}
 * MAIL      {"id", "widget": "MAIL", "mailer": "default", "to", "cc", "subject", "body", "attachments", "on_failure"}
 * DECISION  {"id", "widget": "DECISION", "mode": "MANUAL" | "REPLY", "question", "options": ["APPROVED", "REJECTED"],
 *            "reply_to": "&lt;mail widget id&gt;", "reader": "default", "interval": "PT5M", "timeout": "P1D",
 *            "on_timeout": "REJECTED" | "FAIL"}
 * IF        {"id", "widget": "IF", "conditions": [{"ref": "activity.sub_activity", "op": "equals", "value": "33"}],
 *            "then": [...], "else": [...]}
 * FOR_EACH  {"id", "widget": "FOR_EACH", "items": "${step.main.SUCCESS_IMSIS}", "concurrency": 1, "body": [...]}
 * COLLECT   {"id", "widget": "COLLECT", "list": "MMTEL_SUCCESS", "value": "${item}", "separator": ","}   with another
 *            separator than "," the value is one item kept whole (e.g. a mail text) and the list reads joined by it
 * END       {"id", "widget": "END", "status": "SUCCESS" | "FAILED", "message"}
 * </pre>
 * References in texts: {@code ${order.X}}, {@code ${child.X}} (also the child's collected lists), {@code ${ciq.X}},
 * {@code ${activity.node_type}}, {@code ${activity.sub_activity}}, {@code ${node}} (node pipeline),
 * {@code ${run.nodes|failed_nodes|succeeded_nodes|failed_count}} (after_nodes), {@code ${run.step|status|statusText|message}}
 * and {@code ${run.RESULT}} (after_step: the flow's step and its NEI results), {@code ${item}} (inside For each),
 * {@code ${step.ID.FIELD}} (an earlier widget: NEI / MAIL results, status, statusText, message; DECISION decision,
 * comment, decided_by, from, received_at (yyyyMMddHHmmss), subject), and in NEI parameters and mail fields {@code ${secret:NAME}} and {@code ${WORKDIR}}.
 */
final class Pipeline {

    enum Hook {
        NODE("node"), AFTER_NODES("after_nodes"), AFTER_STEP("after_step");

        final String key;

        Hook(String key) {
            this.key = key;
        }

        static Hook of(String key) {
            for (Hook h : values()) {
                if (h.key.equals(key)) {
                    return h;
                }
            }
            return null;
        }
    }

    sealed interface Widget permits Nei, Mail, Decision, If, ForEach, Collect, End {
        String id();

        String label();
    }

    record Nei(String id, String label, String component, String action, String step, String subActivity,
               Map<String, String> params, boolean continueOnFailure) implements Widget {
    }

    record Mail(String id, String label, String mailer, String to, String cc, String subject, String body,
                String attachments, boolean continueOnFailure) implements Widget {
    }

    record Decision(String id, String label, boolean manual, String question, List<String> options, String replyTo,
                    String reader, String interval, String timeout, String onTimeout) implements Widget {
    }

    record If(String id, String label, List<Condition> conditions, List<Widget> then, List<Widget> otherwise) implements Widget {
    }

    record ForEach(String id, String label, String items, int concurrency, List<Widget> body) implements Widget {
    }

    record Collect(String id, String label, String list, String value, String separator) implements Widget {
    }

    record End(String id, String label, boolean failed, String message) implements Widget {
    }

    record Condition(String ref, String op, List<String> values) {
    }

    static final Pattern REF = Pattern.compile("\\$\\{([^}]*)\\}");
    static final Set<String> CONDITION_OPS = Set.of("equals", "not_equals", "in", "not_in", "empty", "non_empty");
    static final Set<String> DECISION_FIELDS = Set.of("decision", "comment", "decided_by", "from", "received_at", "subject");
    static final Set<String> RUN_FIELDS = Set.of("nodes", "failed_nodes", "succeeded_nodes", "failed_count");
    /** Parameters that identify the order; a pipeline cannot send others. */
    static final Set<String> IDENTITY_PARAMS = Set.of("REQ_ID", "CHILD_REQ_ID", "PARENT_REQ_ID", "ORDER_NO", "REQ_TYPE");

    private static final Pattern WIDGET_ID = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern UPPER = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern LIST_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern COMPONENT = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern PARAM_KEY = Pattern.compile("(login\\.|grc\\.)?[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern SECRET = Pattern.compile("secret:[A-Za-z0-9_]+");
    private static final int MAX_WIDGETS = 200;
    private static final int MAX_DEPTH = 6;

    final Hook hook;
    final List<Widget> widgets;

    private Pipeline(Hook hook, List<Widget> widgets) {
        this.hook = hook;
        this.widgets = widgets;
    }

    /** The pipeline, or null after adding every problem found to {@code problems}. */
    static Pipeline parse(String where, Hook hook, Object json, List<String> problems) {
        int before = problems.size();
        if (!(json instanceof List<?> list) || list.isEmpty()) {
            problems.add(where + ": a pipeline is a non-empty list of widgets.");
            return null;
        }
        Ctx ctx = new Ctx(where, hook, problems);
        List<Widget> widgets = ctx.list(list, new LinkedHashMap<>(), 0, 0);
        if (ctx.count > MAX_WIDGETS) {
            problems.add(where + ": at most " + MAX_WIDGETS + " widgets.");
        }
        return problems.size() > before ? null : new Pipeline(hook, widgets);
    }

    /** Every widget in document order, nested ones included. */
    static List<Widget> flatten(List<Widget> widgets) {
        List<Widget> out = new ArrayList<>();
        for (Widget w : widgets) {
            out.add(w);
            if (w instanceof If i) {
                out.addAll(flatten(i.then()));
                out.addAll(flatten(i.otherwise()));
            } else if (w instanceof ForEach f) {
                out.addAll(flatten(f.body()));
            }
        }
        return out;
    }

    /** The widgets whose results stay readable after these: nested If branches yes, For each bodies no. */
    private static List<Widget> exposed(List<Widget> widgets) {
        List<Widget> out = new ArrayList<>();
        for (Widget w : widgets) {
            out.add(w);
            if (w instanceof If i) {
                out.addAll(exposed(i.then()));
                out.addAll(exposed(i.otherwise()));
            }
        }
        return out;
    }

    private static final class Ctx {
        final String where;
        final Hook hook;
        final List<String> problems;
        final Set<String> ids = new LinkedHashSet<>();
        int count;

        Ctx(String where, Hook hook, List<String> problems) {
            this.where = where;
            this.hook = hook;
            this.problems = problems;
        }

        /** {@code visible}: widgets before this point whose results a reference may read (not those inside a For each). */
        List<Widget> list(List<?> list, Map<String, Widget> visible, int depth, int loops) {
            List<Widget> out = new ArrayList<>();
            if (depth > MAX_DEPTH) {
                problems.add(where + ": widgets are nested more than " + MAX_DEPTH + " levels deep.");
                return out;
            }
            for (Object o : list) {
                count++;
                Widget w = widget(o, visible, depth, loops);
                if (w != null) {
                    out.add(w);
                    visible.put(w.id(), w);
                    if (w instanceof If i) {
                        exposed(i.then()).forEach(x -> visible.putIfAbsent(x.id(), x));
                        exposed(i.otherwise()).forEach(x -> visible.putIfAbsent(x.id(), x));
                    }
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        Widget widget(Object o, Map<String, Widget> visible, int depth, int loops) {
            if (!(o instanceof Map<?, ?> raw)) {
                problems.add(where + ": every widget must be an object.");
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            String id = str(m.get("id"));
            String at = where + ", widget '" + (id == null ? "?" : id) + "'";
            int before = problems.size();
            if (id == null || !WIDGET_ID.matcher(id).matches() || id.contains("__") || "context".equals(id)) {
                problems.add(at + ": 'id' must be 1-24 lower-case letters, digits and _ (starting with a letter, no '__', not 'context').");
            } else if (!ids.add(id)) {
                problems.add(at + ": the id is used twice in this pipeline.");
            }
            String type = str(m.get("widget"));
            String label = str(m.get("label"));
            if (type == null) {
                problems.add(at + ": 'widget' is required (NEI, MAIL, DECISION, IF, FOR_EACH, COLLECT, END).");
                return null;
            }
            Widget w = switch (type.toUpperCase(Locale.ROOT)) {
                case "NEI" -> nei(at, id, label, m, visible, loops);
                case "MAIL" -> mail(at, id, label, m, visible, loops);
                case "DECISION" -> decision(at, id, label, m, visible, loops);
                case "IF" -> ifElse(at, id, label, m, visible, depth, loops);
                case "FOR_EACH" -> forEach(at, id, label, m, visible, depth, loops);
                case "COLLECT" -> collect(at, id, label, m, visible, loops);
                case "END" -> end(at, id, label, m, visible, loops);
                default -> {
                    problems.add(at + ": unknown widget '" + type + "' (NEI, MAIL, DECISION, IF, FOR_EACH, COLLECT, END).");
                    yield null;
                }
            };
            return problems.size() > before ? null : w;
        }

        Nei nei(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int loops) {
            String component = str(m.get("component"));
            if (component == null || !COMPONENT.matcher(component).matches()) {
                problems.add(at + ": 'component' must name the NEI package (e.g. clicr, filecr).");
            }
            String action = str(m.get("action"));
            if (action == null || !UPPER.matcher(action).matches()) {
                problems.add(at + ": 'action' must be the NEI action in upper case (e.g. MOP_EXECUTION).");
            }
            String step = str(m.get("step"));
            if (step == null) {
                step = action;
            } else if (!UPPER.matcher(step).matches()) {
                problems.add(at + ": 'step' (the name it is recorded under) must be upper case, at most 32 characters.");
            }
            String sub = str(m.get("sub_activity"));
            text(at, "sub_activity", sub, visible, loops, false);
            Map<String, String> params = new LinkedHashMap<>();
            Object p = m.get("params");
            if (p != null && !(p instanceof Map)) {
                problems.add(at + ": 'params' must be an object of KEY -> value.");
            } else if (p != null) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) p).entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
                    if (!PARAM_KEY.matcher(key).matches()) {
                        problems.add(at + ": parameter '" + key + "' is not a valid key.");
                    } else if (IDENTITY_PARAMS.contains(key)) {
                        problems.add(at + ": parameter '" + key + "' identifies the order and is set by the pipeline.");
                    }
                    text(at, "params." + key, value, visible, loops, true);
                    params.put(key, value);
                }
            }
            return new Nei(id, label, component, action, step, sub, Map.copyOf(params), continueOnFailure(at, m));
        }

        Mail mail(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int loops) {
            String mailer = name(at, "mailer", m);
            String to = str(m.get("to"));
            if (to == null || to.isBlank()) {
                problems.add(at + ": 'to' is required.");
            }
            String subject = str(m.get("subject"));
            if (subject == null || subject.isBlank()) {
                problems.add(at + ": 'subject' is required.");
            }
            String cc = str(m.get("cc"));
            String body = str(m.get("body"));
            String attachments = str(m.get("attachments"));
            for (String[] f : new String[][] {{"to", to}, {"cc", cc}, {"subject", subject}, {"body", body}, {"attachments", attachments}}) {
                text(at, f[0], f[1], visible, loops, true);
            }
            return new Mail(id, label, mailer, to, cc, subject, body, attachments, continueOnFailure(at, m));
        }

        Decision decision(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int loops) {
            String mode = String.valueOf(m.getOrDefault("mode", "MANUAL")).toUpperCase(Locale.ROOT);
            if (!mode.equals("MANUAL") && !mode.equals("REPLY")) {
                problems.add(at + ": 'mode' must be MANUAL (a person decides in the Order tab) or REPLY (from a mail reply).");
            }
            String question = str(m.get("question"));
            if (question == null || question.isBlank()) {
                problems.add(at + ": 'question' is required.");
            }
            text(at, "question", question, visible, loops, false);
            List<String> options = new ArrayList<>();
            Object o = m.get("options");
            if (o == null) {
                options.addAll(List.of("APPROVED", "REJECTED"));
            } else if (o instanceof List<?> l) {
                for (Object x : l) {
                    String s = String.valueOf(x);
                    if (!UPPER.matcher(s).matches() || "PENDING".equals(s) || options.contains(s)) {
                        problems.add(at + ": option '" + s + "' must be upper case, unique and not PENDING.");
                    }
                    options.add(s);
                }
            }
            if (options.size() < 2) {
                problems.add(at + ": a decision needs at least two options.");
            }
            String replyTo = str(m.get("reply_to"));
            if (mode.equals("REPLY") && (replyTo == null || !(visible.get(replyTo) instanceof Mail))) {
                problems.add(at + ": 'reply_to' must be the id of a MAIL widget before this decision.");
            }
            String reader = name(at, "reader", m);
            String interval = duration(at, "interval", m, "PT5M", Duration.ofSeconds(10));
            String timeout = duration(at, "timeout", m, "P1D", Duration.ofMinutes(1));
            String onTimeout = str(m.get("on_timeout"));
            if (onTimeout == null) {
                onTimeout = options.isEmpty() ? "FAIL" : options.get(options.size() - 1);
            } else if (!onTimeout.equals("FAIL") && !options.contains(onTimeout)) {
                problems.add(at + ": 'on_timeout' must be one of the options or FAIL.");
            }
            return new Decision(id, label, !mode.equals("REPLY"), question, List.copyOf(options), replyTo, reader, interval,
                    timeout, onTimeout);
        }

        If ifElse(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int depth, int loops) {
            List<Condition> conditions = new ArrayList<>();
            if (!(m.get("conditions") instanceof List<?> cl) || cl.isEmpty()) {
                problems.add(at + ": 'conditions' must be a non-empty list (all must hold).");
            } else {
                for (Object c : cl) {
                    if (!(c instanceof Map<?, ?> cm)) {
                        problems.add(at + ": every condition must be {ref, op, value}.");
                        continue;
                    }
                    String ref = str(cm.get("ref"));
                    String op = str(cm.get("op"));
                    if (ref == null) {
                        problems.add(at + ": a condition needs 'ref' (e.g. activity.sub_activity).");
                        continue;
                    }
                    String bad = ref(ref.trim(), visible, loops, false);
                    if (bad != null) {
                        problems.add(at + ": condition on '" + ref + "': " + bad);
                    }
                    if (op == null || !CONDITION_OPS.contains(op)) {
                        problems.add(at + ": condition on '" + ref + "': 'op' must be one of " + CONDITION_OPS + ".");
                        continue;
                    }
                    List<String> values = new ArrayList<>();
                    Object v = cm.get("value");
                    if (v instanceof List<?> vl) {
                        vl.forEach(x -> values.add(String.valueOf(x)));
                    } else if (v != null) {
                        values.add(String.valueOf(v));
                    }
                    if ((op.equals("equals") || op.equals("not_equals")) && values.size() != 1) {
                        problems.add(at + ": condition on '" + ref + "': '" + op + "' compares with exactly one value.");
                    } else if ((op.equals("in") || op.equals("not_in")) && values.isEmpty()) {
                        problems.add(at + ": condition on '" + ref + "': '" + op + "' needs a list of values.");
                    }
                    values.forEach(x -> noPebble(at, "condition value", x));
                    conditions.add(new Condition(ref.trim(), op, List.copyOf(values)));
                }
            }
            List<Widget> then = branch(at, "then", m.get("then"), visible, depth, loops);
            List<Widget> otherwise = branch(at, "else", m.get("else"), visible, depth, loops);
            if (then.isEmpty() && otherwise.isEmpty()) {
                problems.add(at + ": 'then' or 'else' must hold at least one widget.");
            }
            return new If(id, label, List.copyOf(conditions), then, otherwise);
        }

        List<Widget> branch(String at, String name, Object o, Map<String, Widget> visible, int depth, int loops) {
            if (o == null) {
                return List.of();
            }
            if (!(o instanceof List<?> l)) {
                problems.add(at + ": '" + name + "' must be a list of widgets.");
                return List.of();
            }
            return List.copyOf(list(l, new LinkedHashMap<>(visible), depth + 1, loops));
        }

        ForEach forEach(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int depth, int loops) {
            String items = str(m.get("items"));
            if (items == null || items.isBlank()) {
                problems.add(at + ": 'items' is required (comma-separated, e.g. ${step.main.SUCCESS_IMSIS}).");
            }
            text(at, "items", items, visible, loops, false);
            Long concurrency = ProcessDefinition.number(m.getOrDefault("concurrency", "1"));
            if (concurrency == null || concurrency < 0 || concurrency > 50) {
                problems.add(at + ": 'concurrency' must be 0 (all at once) to 50.");
            }
            List<Widget> body = List.of();
            if (!(m.get("body") instanceof List<?> l) || l.isEmpty()) {
                problems.add(at + ": 'body' must be a non-empty list of widgets.");
            } else {
                body = List.copyOf(list(l, new LinkedHashMap<>(visible), depth + 1, loops + 1));
            }
            return new ForEach(id, label, items, concurrency == null ? 1 : concurrency.intValue(), body);
        }

        Collect collect(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int loops) {
            String list = str(m.get("list"));
            if (list == null || !LIST_NAME.matcher(list).matches()) {
                problems.add(at + ": 'list' must be an upper-case name (read later as ${child.NAME}).");
            }
            String value = str(m.get("value"));
            if (value == null || value.isBlank()) {
                problems.add(at + ": 'value' is required.");
            }
            text(at, "value", value, visible, loops, false);
            String separator = String.valueOf(m.getOrDefault("separator", ","));
            if (separator.isEmpty() || separator.length() > 8 || separator.chars().anyMatch(c -> c < 0x20)) {
                problems.add(at + ": 'separator' must be 1-8 characters on one line (default ,).");
            }
            return new Collect(id, label, list, value, separator);
        }

        End end(String at, String id, String label, Map<String, Object> m, Map<String, Widget> visible, int loops) {
            String status = String.valueOf(m.getOrDefault("status", "FAILED")).toUpperCase(Locale.ROOT);
            if (!status.equals("SUCCESS") && !status.equals("FAILED")) {
                problems.add(at + ": 'status' must be SUCCESS or FAILED.");
            }
            String message = str(m.get("message"));
            text(at, "message", message, visible, loops, false);
            return new End(id, label, status.equals("FAILED"), message);
        }

        boolean continueOnFailure(String at, Map<String, Object> m) {
            String v = String.valueOf(m.getOrDefault("on_failure", "STOP")).toUpperCase(Locale.ROOT);
            if (!v.equals("STOP") && !v.equals("CONTINUE")) {
                problems.add(at + ": 'on_failure' must be STOP or CONTINUE.");
            }
            return v.equals("CONTINUE");
        }

        String name(String at, String field, Map<String, Object> m) {
            String v = String.valueOf(m.getOrDefault(field, "default"));
            if (!NAME.matcher(v).matches()) {
                problems.add(at + ": '" + field + "' must be a lower-case name from the project config (default: default).");
            }
            return v;
        }

        String duration(String at, String field, Map<String, Object> m, String fallback, Duration min) {
            String v = String.valueOf(m.getOrDefault(field, fallback));
            try {
                if (Duration.parse(v).compareTo(min) < 0) {
                    problems.add(at + ": '" + field + "' must be at least " + min + ".");
                }
            } catch (DateTimeParseException e) {
                problems.add(at + ": '" + field + "' must be an ISO-8601 duration such as PT5M or P1D.");
            }
            return v;
        }

        void text(String at, String field, String text, Map<String, Widget> visible, int loops, boolean secrets) {
            if (text == null) {
                return;
            }
            noPebble(at, field, text);
            Matcher mt = REF.matcher(text);
            while (mt.find()) {
                String bad = ref(mt.group(1).trim(), visible, loops, secrets);
                if (bad != null) {
                    problems.add(at + ": " + field + ": ${" + mt.group(1) + "}: " + bad);
                }
            }
        }

        void noPebble(String at, String field, String text) {
            if (text.contains("{{") || text.contains("{%") || text.contains("{#")) {
                problems.add(at + ": " + field + ": write references as ${...}; '{{', '{%' and '{#' are not allowed.");
            }
        }

        /** Null when the reference is allowed here, else why not. */
        String ref(String ref, Map<String, Widget> visible, int loops, boolean secrets) {
            String[] p = ref.split("\\.");
            switch (p[0]) {
                case "order", "child", "ciq" -> {
                    return p.length == 2 && IDENT.matcher(p[1]).matches() ? null : "use " + p[0] + ".NAME.";
                }
                case "activity" -> {
                    return p.length == 2 && (p[1].equals("node_type") || p[1].equals("sub_activity")) ? null
                            : "use activity.node_type or activity.sub_activity.";
                }
                case "run" -> {
                    if (hook == Hook.AFTER_STEP) {
                        return p.length == 2 && IDENT.matcher(p[1]).matches() ? null
                                : "use run.step, run.status, run.statusText, run.message or run.RESULT_NAME.";
                    }
                    if (hook != Hook.AFTER_NODES) {
                        return "run.X is known only in the after_nodes and after_step pipelines.";
                    }
                    return p.length == 2 && RUN_FIELDS.contains(p[1]) ? null : "use run." + RUN_FIELDS + ".";
                }
                case "node" -> {
                    return p.length == 1 && hook == Hook.NODE ? null : "${node} is known only in the node pipeline.";
                }
                case "item" -> {
                    return p.length == 1 && loops > 0 ? null : "${item} is known only inside a For each.";
                }
                case "step" -> {
                    if (p.length != 3) {
                        return "use step.WIDGET_ID.FIELD.";
                    }
                    Widget w = visible.get(p[1]);
                    if (w == null) {
                        return "no widget '" + p[1] + "' runs before this point (widgets inside a For each are not"
                                + " visible after it: collect their values with COLLECT).";
                    }
                    if (w instanceof Nei || w instanceof Mail) {
                        return IDENT.matcher(p[2]).matches() ? null : "'" + p[2] + "' is not a result name.";
                    }
                    if (w instanceof Decision) {
                        return DECISION_FIELDS.contains(p[2]) ? null : "a decision gives " + DECISION_FIELDS + ".";
                    }
                    return "widget '" + p[1] + "' gives no results.";
                }
                default -> {
                    if (secrets && (ref.equals("WORKDIR") || SECRET.matcher(ref).matches())) {
                        return null;
                    }
                    return ref.startsWith("secret:") || ref.equals("WORKDIR")
                            ? "secrets and ${WORKDIR} are allowed only in NEI parameters and mail fields."
                            : "unknown reference.";
                }
            }
        }
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
