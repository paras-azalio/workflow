package com.nokia.workflow.gateway;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Activity declarations (project + node type + sub-activity) with versioned configurations (table {@code activity},
 * versions in {@code activity_version}, content column {@code config}). A declaration starts as an empty draft; new
 * orders are pinned to the latest published version of each level that applies to them (request store,
 * new-request): {@code * / *} (the whole project), {@code <node type> / *}, {@code <node type> / <sub-activity>}.
 */
final class ActivityStore {

    private static final Pattern NAME = Pattern.compile("\\*|[A-Za-z0-9][A-Za-z0-9_.&-]{0,127}");

    private final Db db;
    private final String schema;
    private final String project;
    private final VersionedStore versions;
    private final Set<String> knownSteps;

    /** @param knownSteps step names a configuration may name besides those the request store has recorded */
    ActivityStore(Db db, String schema, String project, UserStore audit, Set<String> knownSteps) {
        this.db = db;
        this.schema = schema;
        this.project = project;
        this.knownSteps = knownSteps;
        this.versions = new VersionedStore(db, schema, new VersionedStore.Spec("ACTIVITY", "activity", "activity_id",
                "activity_version", "config"), audit, ActivityConfig::problems);
    }

    void ensureSchema() throws SQLException {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                st.execute("CREATE TABLE IF NOT EXISTS " + t("activity") + " ("
                        + " activity_id BIGSERIAL PRIMARY KEY,"
                        + " project VARCHAR(64) NOT NULL,"
                        + " node_type VARCHAR(128) NOT NULL,"
                        + " sub_activity VARCHAR(256) NOT NULL,"
                        + " description TEXT,"
                        + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " UNIQUE (project, node_type, sub_activity))");
                versions.ensureVersionTable(st);
            }
            return null;
        });
    }

    /** Declares an activity; its configuration starts as an empty draft (version 1) to fill and publish. */
    Map<String, Object> declare(String nodeType, String subActivity, String description, String actor, UserStore audit)
            throws SQLException {
        for (String[] f : new String[][] {{"node_type", nodeType}, {"sub_activity", subActivity}}) {
            if (f[1] == null || !NAME.matcher(f[1]).matches()) {
                throw new UserStore.Rejected(400, "'%s' must be letters, digits and _ . & - (as in the activity's YAML names), or * for all.".formatted(f[0]));
            }
        }
        if ("*".equals(nodeType) && !"*".equals(subActivity)) {
            throw new UserStore.Rejected(400, "With node type * (the whole project) the sub-activity is * too.");
        }
        long id = db.transaction(c -> {
            if (VersionedStore.one(c, "SELECT activity_id FROM " + t("activity") + " WHERE project = ? AND node_type = ? AND sub_activity = ?",
                    project, nodeType, subActivity) != null) {
                throw new UserStore.Rejected(409, "Activity %s/%s is already declared.".formatted(nodeType, subActivity));
            }
            long newId = ((Number) VersionedStore.one(c, "INSERT INTO " + t("activity") + " (project, node_type, sub_activity, description)"
                    + " VALUES (?,?,?,?) RETURNING activity_id", project, nodeType, subActivity, description).get("activity_id")).longValue();
            versions.insertFirst(c, newId, "DRAFT", "{\"steps\":{}}", actor);
            return newId;
        });
        audit.audit(actor, Role.ADMIN, "ACTIVITY_DECLARE", null, null, null, null, label(nodeType, subActivity));
        return Map.of("node_type", nodeType, "sub_activity", subActivity, "version", 1, "state", "DRAFT", "activity_id", id);
    }

    List<Map<String, Object>> list() throws SQLException {
        return db.with(c -> VersionedStore.all(c, "SELECT a.node_type, a.sub_activity, a.description,"
                + " (SELECT max(version) FROM " + t("activity_version") + " v WHERE v.activity_id = a.activity_id AND v.state = 'PUBLISHED') AS published_version,"
                + " (SELECT max(version) FROM " + t("activity_version") + " v WHERE v.activity_id = a.activity_id AND v.state = 'DRAFT') AS draft_version"
                + " FROM " + t("activity") + " a WHERE a.project = ? ORDER BY a.node_type, a.sub_activity", project));
    }

    List<Map<String, Object>> versions(String nodeType, String subActivity) throws SQLException {
        return versions.versions(requireId(nodeType, subActivity));
    }

    VersionedStore.Version version(String nodeType, String subActivity, int version) throws SQLException {
        return versions.version(requireId(nodeType, subActivity), version);
    }

    VersionedStore.Version createDraft(String nodeType, String subActivity, Integer from, String actor) throws SQLException {
        return versions.createDraft(requireId(nodeType, subActivity), label(nodeType, subActivity), from, actor);
    }

    List<String> updateDraft(String nodeType, String subActivity, int version, String json, int revision, String actor)
            throws SQLException {
        return versions.updateDraft(requireId(nodeType, subActivity), label(nodeType, subActivity), version, json, revision, actor);
    }

    /** Publishing also refuses step names no flow records (a typo would silently configure nothing). */
    void publish(String nodeType, String subActivity, int version, String actor) throws Exception {
        versions.publish(requireId(nodeType, subActivity), label(nodeType, subActivity), version, actor, this::unknownSteps);
    }

    void discardDraft(String nodeType, String subActivity, int version, String actor) throws SQLException {
        versions.discardDraft(requireId(nodeType, subActivity), label(nodeType, subActivity), version, actor);
    }

    void retire(String nodeType, String subActivity, int version, String actor) throws SQLException {
        versions.retire(requireId(nodeType, subActivity), label(nodeType, subActivity), version, actor);
    }

    /**
     * The activity configuration an order is pinned to, or null: {node_type, sub_activity, version, state}, with
     * {@code layers}: every configuration level it is pinned to, general to specific, as "node type / sub-activity vN".
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> pinned(long orderId) throws SQLException {
        return db.with(c -> {
            if (!Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".request') IS NOT NULL AS ok").get("ok"))) {
                return null;
            }
            Map<String, Object> out = VersionedStore.one(c, "SELECT a.node_type, a.sub_activity, v.version, v.state FROM " + t("request") + " r JOIN "
                    + t("activity_version") + " v ON v.activity_id = r.activity_id AND v.version = r.activity_version JOIN "
                    + t("activity") + " a ON a.activity_id = v.activity_id WHERE r.req_id = ?", orderId);
            boolean hasLayers = VersionedStore.one(c, "SELECT 1 FROM information_schema.columns WHERE table_schema = ?"
                    + " AND table_name = 'request' AND column_name = 'config_layers'", schema) != null;
            Map<String, Object> row = hasLayers ? VersionedStore.one(c, "SELECT config_layers FROM " + t("request") + " WHERE req_id = ?", orderId) : null;
            List<String> layers = new ArrayList<>();
            if (row != null && row.get("config_layers") != null
                    && com.nokia.workflow.wrapper.SimpleJson.parse(String.valueOf(row.get("config_layers"))) instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> l = (Map<String, Object>) o;
                    layers.add(l.get("node_type") + " / " + l.get("sub_activity") + " v" + l.get("version"));
                }
            }
            if (out == null && layers.isEmpty()) {
                return null;
            }
            Map<String, Object> m = out == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(out);
            m.put("layers", layers);
            return m;
        });
    }

    /**
     * What each recorded step sends: per step its component (the request's ne_type, lower case) and the request
     * parameters of its latest run (without the process's own keys and the login. / grc. overrides; secrets were
     * masked when recorded).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> stepParameters() throws SQLException {
        Map<String, Object> out = new java.util.TreeMap<>();
        db.with(c -> {
            if (!Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".request_step') IS NOT NULL AS ok").get("ok"))) {
                return null;
            }
            for (Map<String, Object> r : VersionedStore.all(c, "SELECT DISTINCT ON (step) step, request::text AS request FROM "
                    + t("request_step") + " WHERE request IS NOT NULL ORDER BY step, step_id DESC")) {
                if (!(com.nokia.workflow.wrapper.SimpleJson.parse(String.valueOf(r.get("request"))) instanceof Map<?, ?> req)) {
                    continue;
                }
                Map<String, Object> params = new java.util.TreeMap<>();
                for (Map.Entry<?, ?> e : req.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (!ActivityConfig.RESERVED.contains(k) && !k.startsWith("login.") && !k.startsWith("grc.")) {
                        params.put(k, e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                }
                Object ne = req.get("ne_type");
                out.put(String.valueOf(r.get("step")), Map.of("component", ne == null ? "" : String.valueOf(ne).toLowerCase(java.util.Locale.ROOT),
                        "params", params));
            }
            return null;
        });
        return out;
    }

    /**
     * The project's login and GRC values per component from its config file ({@code nei.<component>.login.KEY},
     * {@code nei.<component>.grc.KEY}): what a configuration level changes. Secrets are only references there.
     */
    static Map<String, Object> projectDefaults(String properties) throws java.io.IOException {
        java.util.Properties p = new java.util.Properties();
        if (properties != null) {
            p.load(new java.io.StringReader(properties));
        }
        Map<String, Object> out = new java.util.TreeMap<>();
        for (String name : new java.util.TreeSet<>(p.stringPropertyNames())) {
            String[] part = name.split("\\.", 4);
            if (part.length == 4 && "nei".equals(part[0]) && ("login".equals(part[2]) || "grc".equals(part[2]))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> component = (Map<String, Object>) out.computeIfAbsent(part[1], k -> new java.util.TreeMap<>(
                        Map.of("login", new java.util.TreeMap<String, String>(), "grc", new java.util.TreeMap<String, String>())));
                @SuppressWarnings("unchecked")
                Map<String, String> group = (Map<String, String>) component.get(part[2]);
                group.put(part[3], p.getProperty(name));
            }
        }
        return out;
    }

    /**
     * The step names a configuration may name: the configured ones, every step the request store has recorded, and
     * the steps the project's process pipelines record (NEI widgets by their step, mails as MAIL_&lt;ID&gt;).
     */
    Set<String> knownSteps() throws SQLException {
        Set<String> known = new java.util.TreeSet<>(knownSteps);
        db.with(c -> {
            if (Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".request_step') IS NOT NULL AS ok").get("ok"))) {
                for (Map<String, Object> r : VersionedStore.all(c, "SELECT DISTINCT step FROM " + t("request_step"))) {
                    known.add(String.valueOf(r.get("step")));
                }
            }
            if (Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".process_version') IS NOT NULL AS ok").get("ok"))) {
                for (Map<String, Object> r : VersionedStore.all(c, "SELECT v.definition::text AS d FROM " + t("process_version")
                        + " v JOIN " + t("process") + " p ON p.process_id = v.process_id WHERE p.project = ? AND v.state <> 'RETIRED'", project)) {
                    known.addAll(pipelineSteps(String.valueOf(r.get("d"))));
                }
            }
            return null;
        });
        return known;
    }

    private static Set<String> pipelineSteps(String definition) {
        Set<String> out = new java.util.LinkedHashSet<>();
        try {
            for (ProcessDefinition.Step s : ProcessDefinition.parse(com.nokia.workflow.wrapper.SimpleJson.parse(definition)).steps) {
                for (Pipeline p : s.pipelines().values()) {
                    for (Pipeline.Widget w : Pipeline.flatten(p.widgets)) {
                        if (w instanceof Pipeline.Nei n) {
                            out.add(n.step());
                        } else if (w instanceof Pipeline.Mail m) {
                            out.add("MAIL_" + m.id().toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
        } catch (ProcessDefinition.Invalid e) {
            // a draft that does not parse names no steps yet
        }
        return out;
    }

    private List<String> unknownSteps(Map<String, Object> config) throws SQLException {
        Set<String> known = knownSteps();
        List<String> problems = new ArrayList<>();
        if (config.get("steps") instanceof Map<?, ?> steps) {
            for (Object s : steps.keySet()) {
                if (!"*".equals(s) && !known.contains(String.valueOf(s))) {
                    problems.add("steps." + s + ": no flow records a step of that name (known: " + String.join(", ", known) + ") - check the spelling.");
                }
            }
        }
        return problems;
    }

    private long requireId(String nodeType, String subActivity) throws SQLException {
        Map<String, Object> a = db.with(c -> VersionedStore.one(c, "SELECT activity_id FROM " + t("activity")
                + " WHERE project = ? AND node_type = ? AND sub_activity = ?", project, nodeType, subActivity));
        if (a == null) {
            throw new UserStore.Rejected(404, "Activity %s/%s is not declared.".formatted(nodeType, subActivity));
        }
        return ((Number) a.get("activity_id")).longValue();
    }

    private static String label(String nodeType, String subActivity) {
        return "activity " + nodeType + "/" + subActivity;
    }

    private String t(String table) {
        return schema + "." + table;
    }
}
