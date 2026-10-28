package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Process definitions with versions, one process per project (table {@code process}); the version lifecycle is
 * {@link VersionedStore}'s (table {@code process_version}, content column {@code definition}).
 */
final class ProcessStore {

    record Version(long processId, String project, int version, String state, int revision, Map<String, Object> definition) {
    }

    /** Checks a definition against the outside world at publish time (e.g. the step flows exist in Kestra). */
    @FunctionalInterface
    interface PublishCheck {
        List<String> problems(ProcessDefinition definition) throws Exception;
    }

    private final Db db;
    private final String schema;
    private final VersionedStore versions;

    ProcessStore(Db db, String schema, UserStore audit) {
        this.db = db;
        this.schema = schema;
        this.versions = new VersionedStore(db, schema, new VersionedStore.Spec("PROCESS", "process", "process_id",
                "process_version", "definition"), audit, ProcessStore::problems);
    }

    void ensureSchema() throws SQLException {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                st.execute("CREATE TABLE IF NOT EXISTS " + t("process") + " ("
                        + " process_id BIGSERIAL PRIMARY KEY,"
                        + " project VARCHAR(64) NOT NULL UNIQUE,"
                        + " name VARCHAR(256) NOT NULL,"
                        + " description TEXT,"
                        + " created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                versions.ensureVersionTable(st);
                st.execute("ALTER TABLE " + t("process_version") + " ADD COLUMN IF NOT EXISTS compiled JSONB");
            }
            return null;
        });
    }

    /**
     * Creates the project's process with {@code definition} as its published version 1, once: nothing happens
     * when the project already has a process. Orders created before (no pinned version) are pinned to it.
     */
    boolean seed(String project, String name, String definitionJson) throws SQLException {
        ProcessDefinition.parse(SimpleJson.parse(definitionJson));
        return db.transaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("LOCK TABLE " + t("process") + " IN EXCLUSIVE MODE");
            }
            if (VersionedStore.one(c, "SELECT process_id FROM " + t("process") + " WHERE project = ?", project) != null) {
                return false;
            }
            long id = ((Number) VersionedStore.one(c, "INSERT INTO " + t("process") + " (project, name) VALUES (?,?) RETURNING process_id",
                    project, name).get("process_id")).longValue();
            versions.insertFirst(c, id, "PUBLISHED", definitionJson, "seed");
            if (Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".request') IS NOT NULL AS ok").get("ok"))) {
                VersionedStore.update(c, "ALTER TABLE " + t("request") + " ADD COLUMN IF NOT EXISTS process_id BIGINT");
                VersionedStore.update(c, "ALTER TABLE " + t("request") + " ADD COLUMN IF NOT EXISTS process_version INT");
                VersionedStore.update(c, "UPDATE " + t("request") + " SET process_id = ?, process_version = 1 WHERE process_id IS NULL", id);
            }
            return true;
        });
    }

    /** The process version a request (order) is pinned to, from the request store's table; null when not pinned. */
    Version pinnedVersion(long reqId) throws SQLException {
        Map<String, Object> r = db.with(c -> {
            if (!Boolean.TRUE.equals(VersionedStore.one(c, "SELECT to_regclass('" + schema + ".request') IS NOT NULL AS ok").get("ok"))) {
                return null;
            }
            return VersionedStore.one(c, "SELECT process_id, process_version FROM " + t("request") + " WHERE req_id = ?", reqId);
        });
        if (r == null || r.get("process_id") == null || r.get("process_version") == null) {
            return null;
        }
        return version(((Number) r.get("process_id")).longValue(), ((Number) r.get("process_version")).intValue());
    }

    Version latestPublished(String project) throws SQLException {
        Long id = id(project);
        return id == null ? null : wrap(project, versions.latestPublished(id));
    }

    Version version(long processId, int version) throws SQLException {
        String project = db.with(c -> {
            Map<String, Object> p = VersionedStore.one(c, "SELECT project FROM " + t("process") + " WHERE process_id = ?", processId);
            return p == null ? null : (String) p.get("project");
        });
        return project == null ? null : wrap(project, versions.version(processId, version));
    }

    Version version(String project, int version) throws SQLException {
        Long id = id(project);
        return id == null ? null : wrap(project, versions.version(id, version));
    }

    List<Map<String, Object>> processes() throws SQLException {
        return db.with(c -> VersionedStore.all(c, "SELECT p.project, p.name, p.description,"
                + " (SELECT max(version) FROM " + t("process_version") + " v WHERE v.process_id = p.process_id AND v.state = 'PUBLISHED') AS published_version,"
                + " (SELECT max(version) FROM " + t("process_version") + " v WHERE v.process_id = p.process_id AND v.state = 'DRAFT') AS draft_version"
                + " FROM " + t("process") + " p ORDER BY p.project"));
    }

    List<Map<String, Object>> versions(String project) throws SQLException {
        return versions.versions(requireId(project));
    }

    Version createDraft(String project, Integer fromVersion, String actor) throws SQLException {
        return wrap(project, versions.createDraft(requireId(project), label(project), fromVersion, actor));
    }

    List<String> updateDraft(String project, int version, String definitionJson, int expectedRevision, String actor)
            throws SQLException {
        return versions.updateDraft(requireId(project), label(project), version, definitionJson, expectedRevision, actor);
    }

    void publish(String project, int version, String actor, PublishCheck check) throws Exception {
        versions.publish(requireId(project), label(project), version, actor,
                content -> check.problems(ProcessDefinition.parse(content)));
    }

    /**
     * The pipeline flows deployed for a published version, {@code {"<step flow>": {"<hook>": "<flow id>"}}}; the
     * request store reads them ({@code store.sh pipelines}) to run the version's pipelines.
     */
    void setCompiled(String project, int version, Map<String, Map<String, String>> compiled) throws SQLException {
        long id = requireId(project);
        db.with(c -> VersionedStore.update(c, "UPDATE " + t("process_version") + " SET compiled = CAST(? AS JSONB)"
                + " WHERE process_id = ? AND version = ?", SimpleJson.writeCompact(compiled), id, version));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> compiled(String project, int version) throws SQLException {
        long id = requireId(project);
        Map<String, Object> r = db.with(c -> VersionedStore.one(c, "SELECT compiled::text AS compiled FROM " + t("process_version")
                + " WHERE process_id = ? AND version = ?", id, version));
        return r == null || r.get("compiled") == null ? Map.of() : (Map<String, Object>) SimpleJson.parse(String.valueOf(r.get("compiled")));
    }

    void discardDraft(String project, int version, String actor) throws SQLException {
        versions.discardDraft(requireId(project), label(project), version, actor);
    }

    void retire(String project, int version, String actor) throws SQLException {
        versions.retire(requireId(project), label(project), version, actor);
    }

    static List<String> problems(Object parsedDefinition) {
        try {
            ProcessDefinition.parse(parsedDefinition);
            return List.of();
        } catch (ProcessDefinition.Invalid e) {
            return e.problems;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Long id(String project) throws SQLException {
        return db.with(c -> {
            Map<String, Object> p = VersionedStore.one(c, "SELECT process_id FROM " + t("process") + " WHERE project = ?", project);
            return p == null ? null : ((Number) p.get("process_id")).longValue();
        });
    }

    private long requireId(String project) throws SQLException {
        Long id = id(project);
        if (id == null) {
            throw new UserStore.Rejected(404, "There is no process for project %s.".formatted(project));
        }
        return id;
    }

    private static String label(String project) {
        return "process " + project;
    }

    private static Version wrap(String project, VersionedStore.Version v) {
        return v == null ? null : new Version(v.headId(), project, v.version(), v.state(), v.revision(), v.content());
    }

    private String t(String table) {
        return schema + "." + table;
    }
}
