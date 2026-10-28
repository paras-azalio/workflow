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
 * orders of the activity are pinned to its latest published version (request store, new-request).
 */
final class ActivityStore {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.&-]{0,127}");

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
                throw new UserStore.Rejected(400, "'%s' must be letters, digits and _ . & - (as in the activity's YAML names).".formatted(f[0]));
            }
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

    /** The activity configuration an order is pinned to, or null: {node_type, sub_activity, version, state}. */
    Map<String, Object> pinned(long orderId) throws SQLException {
        return db.with(c -> {
            if (!Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".request') IS NOT NULL AS ok").get("ok"))) {
                return null;
            }
            return VersionedStore.one(c, "SELECT a.node_type, a.sub_activity, v.version, v.state FROM " + t("request") + " r JOIN "
                    + t("activity_version") + " v ON v.activity_id = r.activity_id AND v.version = r.activity_version JOIN "
                    + t("activity") + " a ON a.activity_id = v.activity_id WHERE r.req_id = ?", orderId);
        });
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
