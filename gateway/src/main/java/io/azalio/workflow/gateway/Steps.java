package io.azalio.workflow.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an order or a child can do next, from the process version the order is pinned to, and starting such a
 * step: the step must be available NOW (checked again on the server), every user value is checked against the
 * step's definition, then the step's flow is started in Kestra with the mapped inputs, labelled with who did it.
 *
 * <p>The order's data comes from the order registry its project's flows publish (KV ORDER_&lt;id&gt; / ORDERS of the
 * project's namespace; the project is the one of the process version the order is pinned to).
 */
final class Steps {

    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final Duration DEDUP = Duration.ofMinutes(2);

    private record Started(Map<String, Object> result, Instant until) {
    }

    /** The order (and child) a step is evaluated against. */
    record Context(Map<String, Object> order, Map<String, Object> child, ProcessStore.Version version,
                   ProcessDefinition definition, boolean continuable, String reason) {
    }

    private final Kestra kestra;
    private final ProcessStore processes;
    private final ActivityStore activities;
    private final UserStore users;
    private final String defaultNamespace;
    private final AutoStarter autoStarter;
    private final Map<String, Started> recent = new ConcurrentHashMap<>();
    private final Map<String, Object> inFlight = new ConcurrentHashMap<>();

    /** @param defaultNamespace where the registry of orders that are not pinned to a process version is */
    Steps(Kestra kestra, ProcessStore processes, ActivityStore activities, UserStore users, String defaultNamespace,
          AutoStarter autoStarter) {
        this.kestra = kestra;
        this.autoStarter = autoStarter;
        this.processes = processes;
        this.activities = activities;
        this.users = users;
        this.defaultNamespace = defaultNamespace;
    }

    // ------------------------------------------------------------------ next steps

    @SuppressWarnings("unchecked")
    Context context(String orderId, String childId) throws Exception {
        if (orderId == null || !orderId.matches("[1-9][0-9]{0,17}")) {
            throw new UserStore.Rejected(400, "The order id must be a whole number.");
        }
        ProcessStore.Version v = processes.pinnedVersion(Long.parseLong(orderId));
        String ns = v == null ? null : processes.namespace(v.project());
        if (ns == null) {
            ns = defaultNamespace;
        }
        Object o = kestra.kv(ns, "ORDER_" + orderId);
        if (!(o instanceof Map<?, ?> om)) {
            throw new UserStore.Rejected(404, "Order %s is not in the order registry.".formatted(orderId));
        }
        Map<String, Object> order = (Map<String, Object>) om;
        Map<String, Object> child = null;
        if (childId != null) {
            Object c = order.get("children_by_id") instanceof Map<?, ?> byId ? byId.get(childId) : null;
            if (!(c instanceof Map<?, ?> cm)) {
                throw new UserStore.Rejected(404, "Order %s has no child order %s.".formatted(orderId, childId));
            }
            child = (Map<String, Object>) cm;
        }
        boolean listed = false;
        if (kestra.kv(ns, "ORDERS") instanceof List<?> orders) {
            listed = orders.stream().anyMatch(x -> x instanceof Map<?, ?> m && orderId.equals(String.valueOf(m.get("value"))));
        }
        if (!listed) {
            return new Context(order, child, null, null, false,
                    "Order %s is not in the request store (created before it), so it cannot be continued.".formatted(orderId));
        }
        if (v == null) {
            return new Context(order, child, null, null, false,
                    "Order %s is not pinned to a process version.".formatted(orderId));
        }
        return new Context(order, child, v, ProcessDefinition.parse(v.definition()), true, null);
    }

    /** The steps offered now for the order (childId null) or one of its children, each with its user inputs. */
    Map<String, Object> next(String orderId, String childId, Role role) throws Exception {
        Context ctx = context(orderId, childId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_id", orderId);
        out.put("child_id", childId);
        out.put("continuable", ctx.continuable());
        out.put("reason", ctx.reason());
        out.put("can_execute", role.atLeast(Role.EXECUTOR));
        List<Object> steps = new ArrayList<>();
        if (ctx.continuable()) {
            out.put("process", Map.of("project", ctx.version().project(), "version", ctx.version().version(),
                    "state", ctx.version().state()));
            out.put("activity", activities.pinned(Long.parseLong(orderId)));
            for (ProcessDefinition.Step s : available(ctx)) {
                steps.add(view(s, ctx.order(), ctx.child()));
            }
            if (autoStarter != null) {
                out.put("auto_starts", autoStarter.list(Long.parseLong(orderId), childId == null ? null : Long.valueOf(childId)));
            }
        }
        out.put("steps", steps);
        return out;
    }

    /** The steps that start a new order, from the project's latest published process version. */
    Map<String, Object> startSteps(String project, Role role) throws Exception {
        ProcessStore.Version v = processes.latestPublished(project);
        if (v == null) {
            throw new UserStore.Rejected(404, "Project %s has no published process.".formatted(project));
        }
        List<Object> steps = new ArrayList<>();
        for (ProcessDefinition.Step s : ProcessDefinition.parse(v.definition()).steps) {
            if (s.startsOrder()) {
                steps.add(view(s, null, null));
            }
        }
        return Map.of("process", Map.of("project", v.project(), "version", v.version()), "can_execute",
                role.atLeast(Role.EXECUTOR), "steps", steps);
    }

    static List<ProcessDefinition.Step> available(Context ctx) {
        List<ProcessDefinition.Step> out = new ArrayList<>();
        ProcessDefinition.Scope scope = ctx.child() == null ? ProcessDefinition.Scope.ORDER : ProcessDefinition.Scope.CHILD;
        for (ProcessDefinition.Step s : ctx.definition().steps) {
            if (!s.startsOrder() && s.scope() == scope && ProcessDefinition.available(s, ctx.order(), ctx.child())) {
                out.add(s);
            }
        }
        return out;
    }

    private static Map<String, Object> view(ProcessDefinition.Step s, Map<String, Object> order, Map<String, Object> child) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("label", s.label());
        m.put("description", s.description());
        List<Object> inputs = new ArrayList<>();
        for (ProcessDefinition.Input in : s.inputs()) {
            if (in.source() != ProcessDefinition.Source.USER) {
                continue;
            }
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("name", in.name());
            i.put("type", in.type().name());
            i.put("label", in.label());
            i.put("required", in.required());
            i.put("default", in.defaultValue());
            if (in.type() == ProcessDefinition.InputType.SELECT || in.type() == ProcessDefinition.InputType.MULTISELECT) {
                i.put("options", options(in, order, child));
                i.put("all_value", in.allValue());
            }
            inputs.add(i);
        }
        m.put("inputs", inputs);
        return m;
    }

    /** The options of a SELECT / MULTISELECT as {value, label}; a MULTISELECT's "all" value is not one of them. */
    static List<Map<String, Object>> options(ProcessDefinition.Input in, Map<String, Object> order, Map<String, Object> child) {
        Object raw = in.options() != null ? in.options() : ProcessDefinition.resolve(in.optionsFrom(), order, child);
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String value = o instanceof Map<?, ?> m ? String.valueOf(m.get("value")) : String.valueOf(o);
                String label = o instanceof Map<?, ?> m && m.get("label") != null ? String.valueOf(m.get("label")) : value;
                if (in.allValue() != null && in.allValue().equals(value)) {
                    continue;
                }
                Map<String, Object> opt = new LinkedHashMap<>();
                opt.put("value", value);
                opt.put("label", label);
                out.add(opt);
            }
        }
        return out;
    }

    /**
     * The field names an order and a child have in the order registry, read from the newest order that has a child
     * (else the newest order): what a step's {@code order.} / {@code child.} paths can name - for the designer.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> contextFields(String project) throws Exception {
        String namespace = processes.namespace(project);
        if (namespace == null) {
            throw new UserStore.Rejected(404, "There is no project %s.".formatted(project));
        }
        java.util.TreeSet<String> orderFields = new java.util.TreeSet<>();
        java.util.TreeSet<String> childFields = new java.util.TreeSet<>();
        String sample = null;
        if (kestra.kv(namespace, "ORDERS") instanceof List<?> orders) {
            for (Object o : orders) {
                if (!(o instanceof Map<?, ?> summary) || !(kestra.kv(namespace, "ORDER_" + summary.get("value")) instanceof Map<?, ?> order)) {
                    continue;
                }
                if (sample == null) {
                    sample = String.valueOf(summary.get("value"));
                    order.keySet().forEach(k -> orderFields.add(String.valueOf(k)));
                }
                if (order.get("children_by_id") instanceof Map<?, ?> byId && !byId.isEmpty()
                        && byId.values().iterator().next() instanceof Map<?, ?> c) {
                    c.keySet().forEach(k -> childFields.add(String.valueOf(k)));
                    order.keySet().forEach(k -> orderFields.add(String.valueOf(k)));
                    break;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", new ArrayList<>(orderFields));
        out.put("child", new ArrayList<>(childFields));
        out.put("sample_order", sample);
        return out;
    }

    // ------------------------------------------------------------------ start a step

    @FunctionalInterface
    private interface StepRun {
        Map<String, Object> run() throws Exception;
    }

    /** Runs a start once per request key: same-key submits wait for the first and get its answer. */
    private Map<String, Object> once(String key, StepRun run) throws Exception {
        if (key == null) {
            return run.run();
        }
        synchronized (inFlight.computeIfAbsent(key, k -> new Object())) {
            try {
                Map<String, Object> again = repeated(key);
                if (again != null) {
                    return again;
                }
                Map<String, Object> result = run.run();
                remember(key, result);
                return result;
            } finally {
                inFlight.remove(key);
            }
        }
    }

    Map<String, Object> start(String orderId, String childId, String stepId, Map<String, Object> body, UserStore.User user,
                              String ip) throws Exception {
        requireExecutor(user);
        return once(dedupKey(user, body, orderId + "/" + childId + "/" + stepId), () -> startNow(orderId, childId, stepId, body, user, ip));
    }

    private Map<String, Object> startNow(String orderId, String childId, String stepId, Map<String, Object> body,
                                         UserStore.User user, String ip) throws Exception {
        Context ctx = context(orderId, childId);
        if (!ctx.continuable()) {
            throw new UserStore.Rejected(409, ctx.reason());
        }
        requireNotDeleted(ctx.version());
        ProcessDefinition.Step step = ctx.definition().step(stepId);
        if (step == null) {
            throw new UserStore.Rejected(404, "Process version %d has no step %s.".formatted(ctx.version().version(), stepId));
        }
        if (!available(ctx).contains(step)) {
            throw new UserStore.Rejected(409, "The step '%s' is not available for %s now (the order may have moved on): reload."
                    .formatted(step.label(), childId == null ? "order " + orderId : "child order " + childId));
        }
        Map<String, Object> out = run(step, ctx.definition().namespace, ctx.order(), ctx.child(), body, user, ip,
                "order " + orderId + (childId == null ? "" : " child " + childId), ctx.version());
        if (step.autoStart() != null && autoStarter != null) {
            Object item = step.autoStart().forEach() == null ? "" : body.get("inputs") instanceof Map<?, ?> in ? in.get(step.autoStart().forEach()) : null;
            autoStarter.startedByPerson(Long.parseLong(orderId), childId == null ? null : Long.valueOf(childId), step,
                    item == null ? "" : String.valueOf(item), ctx.version().version(), user.username(), out.get("execution_id"));
        }
        return out;
    }

    /** Starts an automatic step the gateway found offered in {@code ctx} (just evaluated), as {@code user}. */
    Map<String, Object> startAutomatically(Context ctx, ProcessDefinition.Step step, Map<String, Object> body, UserStore.User user)
            throws Exception {
        requireNotDeleted(ctx.version());
        return run(step, ctx.definition().namespace, ctx.order(), ctx.child(), body, user, null,
                "automatic start, order " + ctx.order().get("value"), ctx.version());
    }

    Map<String, Object> startOrder(String project, String stepId, Map<String, Object> body, UserStore.User user, String ip) throws Exception {
        requireExecutor(user);
        return once(dedupKey(user, body, "new/" + project + "/" + stepId), () -> startOrderNow(project, stepId, body, user, ip));
    }

    private Map<String, Object> startOrderNow(String project, String stepId, Map<String, Object> body, UserStore.User user, String ip) throws Exception {
        ProcessStore.Version v = processes.latestPublished(project);
        if (v == null) {
            throw new UserStore.Rejected(404, "Project %s has no published process.".formatted(project));
        }
        ProcessDefinition def = ProcessDefinition.parse(v.definition());
        ProcessDefinition.Step step = def.step(stepId);
        if (step == null || !step.startsOrder()) {
            throw new UserStore.Rejected(404, "Process version %d has no step %s that starts an order.".formatted(v.version(), stepId));
        }
        return run(step, def.namespace, null, null, body, user, ip, "new order", v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(ProcessDefinition.Step step, String flowNamespace, Map<String, Object> order,
                                    Map<String, Object> child, Map<String, Object> body, UserStore.User user, String ip,
                                    String what, ProcessStore.Version version) throws Exception {
        Map<String, Object> values = body.get("inputs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> files = body.get("files") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Kestra.FilePart> parts = new LinkedHashMap<>();
        for (ProcessDefinition.Input in : step.inputs()) {
            switch (in.source()) {
                case VALUE -> {
                    if (in.value() != null) {
                        fields.put(in.name(), in.value());
                    }
                }
                case CONTEXT -> {
                    Object v = ProcessDefinition.resolve(in.path(), order, child);
                    if (v == null) {
                        throw new UserStore.Rejected(409, "The step needs %s, which this order does not have.".formatted(in.path()));
                    }
                    fields.put(in.name(), String.valueOf(v));
                }
                case USER -> userValue(in, values.get(in.name()), files.get(in.name()), order, child, fields, parts);
            }
        }
        List<String> labels = List.of("started_by:" + user.username(), "process_step:" + step.id(),
                "process_version:" + version.project() + "." + version.version());
        Map<String, Object> e = kestra.startExecution(flowNamespace, step.flow(), labels, fields, parts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execution_id", e.get("id"));
        out.put("namespace", flowNamespace);
        out.put("flow_id", step.flow());
        out.put("step", step.id());
        users.audit(user.username(), user.role(), "START_STEP", "POST", null, 200, ip,
                what + ": " + step.id() + " -> " + step.flow() + " " + e.get("id"));
        return out;
    }

    @SuppressWarnings("unchecked")
    static void userValue(ProcessDefinition.Input in, Object given, Object file, Map<String, Object> order,
                          Map<String, Object> child, Map<String, String> fields, Map<String, Kestra.FilePart> parts) {
        String at = "'" + in.label() + "'";
        switch (in.type()) {
            case STRING -> {
                String v = given == null ? (in.defaultValue() == null ? null : String.valueOf(in.defaultValue())) : String.valueOf(given);
                if (v == null || v.isBlank()) {
                    if (in.required()) {
                        throw new UserStore.Rejected(400, at + " is required.");
                    }
                } else {
                    fields.put(in.name(), v.trim());
                }
            }
            case BOOL -> fields.put(in.name(), String.valueOf(ProcessDefinition.bool(given == null ? in.defaultValue() : given)));
            case SELECT -> {
                String v = given == null ? (in.defaultValue() == null ? null : String.valueOf(in.defaultValue())) : String.valueOf(given);
                if (v == null || v.isBlank()) {
                    if (in.required()) {
                        throw new UserStore.Rejected(400, at + " is required.");
                    }
                    return;
                }
                if (Steps.options(in, order, child).stream().noneMatch(o -> v.equals(o.get("value")))) {
                    throw new UserStore.Rejected(400, "%s: '%s' is not one of the offered values.".formatted(at, v));
                }
                fields.put(in.name(), v);
            }
            case MULTISELECT -> {
                Set<String> chosen = new LinkedHashSet<>();
                if (given instanceof List<?> list) {
                    list.forEach(x -> chosen.add(String.valueOf(x)));
                } else if (given != null) {
                    chosen.add(String.valueOf(given));
                }
                if (in.allValue() != null && chosen.contains(in.allValue())) {
                    chosen.clear();
                    chosen.add(in.allValue());
                } else {
                    Set<String> offered = new LinkedHashSet<>();
                    Steps.options(in, order, child).forEach(o -> offered.add(String.valueOf(o.get("value"))));
                    for (String c : chosen) {
                        if (!offered.contains(c)) {
                            throw new UserStore.Rejected(400, "%s: '%s' is not one of the offered values.".formatted(at, c));
                        }
                    }
                }
                if (chosen.isEmpty()) {
                    if (in.required()) {
                        throw new UserStore.Rejected(400, at + ": choose at least one.");
                    }
                    return;
                }
                fields.put(in.name(), io.azalio.workflow.wrapper.SimpleJson.writeCompact(new ArrayList<>(chosen)));
            }
            case FILE -> {
                if (!(file instanceof Map<?, ?> fm) || fm.get("base64") == null) {
                    if (in.required()) {
                        throw new UserStore.Rejected(400, at + ": a file is required.");
                    }
                    return;
                }
                byte[] content;
                try {
                    content = Base64.getDecoder().decode(String.valueOf(fm.get("base64")));
                } catch (IllegalArgumentException e) {
                    throw new UserStore.Rejected(400, at + ": the file content is not valid base64.");
                }
                if (content.length == 0) {
                    throw new UserStore.Rejected(400, at + ": the file is empty.");
                }
                if (content.length > MAX_FILE_BYTES) {
                    throw new UserStore.Rejected(413, at + ": the file is larger than " + (MAX_FILE_BYTES >> 20) + " MB.");
                }
                String name = fm.get("filename") == null ? in.name() : String.valueOf(fm.get("filename"));
                parts.put(in.name(), new Kestra.FilePart(name.replaceAll("[\\\\/]", "_"), content));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A deleted (hidden) project's orders can be looked at, not continued. */
    private void requireNotDeleted(ProcessStore.Version v) throws java.sql.SQLException {
        if (processes.hidden(v.project())) {
            throw new UserStore.Rejected(409, "Project %s is deleted: restore it to continue its orders.".formatted(v.project()));
        }
    }

    private static void requireExecutor(UserStore.User user) {
        if (!user.role().atLeast(Role.EXECUTOR)) {
            throw new UserStore.Rejected(403, "Your role %s may not start steps (needs EXECUTOR).".formatted(user.role()));
        }
    }

    /** A repeated submit (same user, same client request key, within 2 minutes) gets the first answer again. */
    private static String dedupKey(UserStore.User user, Map<String, Object> body, String what) {
        Object key = body.get("request_key");
        return key == null ? null : user.username() + "|" + what + "|" + key;
    }

    private Map<String, Object> repeated(String key) {
        if (key == null) {
            return null;
        }
        Started s = recent.get(key);
        if (s == null || Instant.now().isAfter(s.until())) {
            recent.remove(key);
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(s.result());
        out.put("repeated", true);
        return out;
    }

    private void remember(String key, Map<String, Object> result) {
        if (key != null) {
            recent.put(key, new Started(result, Instant.now().plus(DEDUP)));
            recent.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue().until()));
        }
    }
}
