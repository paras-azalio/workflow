package io.azalio.workflow.gateway;

import io.azalio.workflow.wrapper.SimpleJson;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The version lifecycle shared by everything the gateway keeps with versions (process definitions, activity
 * configurations): one head row, versions DRAFT (editable, at most one), PUBLISHED (never changed; the latest one
 * is used for new orders) and RETIRED (kept for the orders pinned to it). Drafts are saved with optimistic locking
 * ({@code revision}); version numbers are never reused (head column {@code last_version}).
 */
final class VersionedStore {

    /** Where one kind is kept: head table + its id column, version table + its JSONB content column. */
    record Spec(String what, String head, String idColumn, String versions, String content) {
    }

    record Version(long headId, int version, String state, int revision, Map<String, Object> content) {
    }

    /** Checks a version against the outside world at publish time (e.g. the step flows exist in Kestra). */
    @FunctionalInterface
    interface PublishCheck {
        List<String> problems(Map<String, Object> content) throws Exception;
    }

    private final Db db;
    private final String schema;
    private final Spec spec;
    private final UserStore audit;
    private final Function<Object, List<String>> validator;

    VersionedStore(Db db, String schema, Spec spec, UserStore audit, Function<Object, List<String>> validator) {
        this.db = db;
        this.schema = schema;
        this.spec = spec;
        this.audit = audit;
        this.validator = validator;
    }

    /** The version table; the head table (with {@code last_version}) is created by the owner. */
    void ensureVersionTable(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS " + t(spec.versions()) + " ("
                + " " + spec.idColumn() + " BIGINT NOT NULL REFERENCES " + t(spec.head()) + "(" + spec.idColumn() + "),"
                + " version INT NOT NULL,"
                + " state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),"
                + " " + spec.content() + " JSONB NOT NULL,"
                + " revision INT NOT NULL DEFAULT 1,"
                + " created_by VARCHAR(256), created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                + " updated_by VARCHAR(256), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                + " published_by VARCHAR(256), published_at TIMESTAMPTZ,"
                + " PRIMARY KEY (" + spec.idColumn() + ", version))");
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS " + spec.versions() + "_one_draft ON " + t(spec.versions())
                + " (" + spec.idColumn() + ") WHERE state = 'DRAFT'");
        st.execute("ALTER TABLE " + t(spec.head()) + " ADD COLUMN IF NOT EXISTS last_version INT NOT NULL DEFAULT 0");
    }

    List<String> problems(Object parsedContent) {
        return validator.apply(parsedContent);
    }

    Version version(long id, int version) throws SQLException {
        return db.with(c -> version(one(c, "SELECT * FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? AND version = ?",
                id, version)));
    }

    Version latestPublished(long id) throws SQLException {
        return db.with(c -> version(one(c, "SELECT * FROM " + t(spec.versions()) + " WHERE " + spec.idColumn()
                + " = ? AND state = 'PUBLISHED' ORDER BY version DESC LIMIT 1", id)));
    }

    List<Map<String, Object>> versions(long id) throws SQLException {
        return db.with(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : all(c, "SELECT version, state, revision, created_by, created_at, updated_by, updated_at,"
                    + " published_by, published_at FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? ORDER BY version DESC", id)) {
                Map<String, Object> m = new LinkedHashMap<>();
                r.forEach((k, v) -> m.put(k, v instanceof java.util.Date ? String.valueOf(v) : v));
                out.add(m);
            }
            return out;
        });
    }

    /** The first version of a new head, in the given state (seeded content is published, a declaration starts as a draft). */
    void insertFirst(Connection c, long id, String state, String contentJson, String actor) throws SQLException {
        update(c, "INSERT INTO " + t(spec.versions()) + " (" + spec.idColumn() + ", version, state, " + spec.content()
                + ", created_by, updated_by, published_by, published_at) VALUES (?, 1, ?, CAST(? AS JSONB), ?, ?, ?, "
                + ("PUBLISHED".equals(state) ? "now()" : "NULL") + ")", id, state, contentJson, actor, actor,
                "PUBLISHED".equals(state) ? actor : null);
        update(c, "UPDATE " + t(spec.head()) + " SET last_version = 1 WHERE " + spec.idColumn() + " = ?", id);
    }

    /** A new draft copied from {@code fromVersion} (default: the latest published one). One draft at a time. */
    Version createDraft(long id, String label, Integer fromVersion, String actor) throws SQLException {
        Version created = db.transaction(c -> {
            Map<String, Object> head = lockHead(c, id, label);
            if (one(c, "SELECT version FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? AND state = 'DRAFT'", id) != null) {
                throw new UserStore.Rejected(409, "%s already has a draft: edit or publish it first.".formatted(cap(label)));
            }
            Map<String, Object> from = fromVersion == null
                    ? one(c, "SELECT * FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? AND state = 'PUBLISHED' ORDER BY version DESC LIMIT 1", id)
                    : one(c, "SELECT * FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? AND version = ?", id, fromVersion);
            if (from == null) {
                throw new UserStore.Rejected(404, "%s has no version %s to copy.".formatted(cap(label), fromVersion == null ? "published" : fromVersion));
            }
            Number highest = (Number) one(c, "SELECT max(version) AS v FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ?", id).get("v");
            int next = Math.max(highest == null ? 0 : highest.intValue(), ((Number) head.get("last_version")).intValue()) + 1;
            update(c, "UPDATE " + t(spec.head()) + " SET last_version = ? WHERE " + spec.idColumn() + " = ?", next, id);
            update(c, "INSERT INTO " + t(spec.versions()) + " (" + spec.idColumn() + ", version, state, " + spec.content()
                    + ", created_by, updated_by) VALUES (?,?, 'DRAFT', CAST(? AS JSONB), ?, ?)", id, next,
                    String.valueOf(from.get(spec.content())), actor, actor);
            return version(one(c, "SELECT * FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? AND version = ?", id, next));
        });
        audit.audit(actor, Role.ADMIN, spec.what() + "_DRAFT", null, null, null, null, label + " v" + created.version());
        return created;
    }

    /**
     * Saves a draft when {@code expectedRevision} is still current. Invalid content is saved too (work in
     * progress); its problems are returned. Published and retired versions are never changed.
     */
    List<String> updateDraft(long id, String label, int version, String json, int expectedRevision, String actor)
            throws SQLException {
        Object parsed;
        try {
            parsed = SimpleJson.parse(json);
        } catch (RuntimeException e) {
            throw new UserStore.Rejected(400, "The content is not valid JSON: " + e.getMessage());
        }
        List<String> problems = problems(parsed);
        db.transaction(c -> {
            lockHead(c, id, label);
            Map<String, Object> v = one(c, "SELECT state, revision FROM " + t(spec.versions()) + " WHERE " + spec.idColumn()
                    + " = ? AND version = ? FOR UPDATE", id, version);
            if (v == null) {
                throw new UserStore.Rejected(404, "%s has no version %d.".formatted(cap(label), version));
            }
            if (!"DRAFT".equals(v.get("state"))) {
                throw new UserStore.Rejected(409, "Version %d is %s and can no longer be changed: create a draft.".formatted(version, v.get("state")));
            }
            if (((Number) v.get("revision")).intValue() != expectedRevision) {
                throw new UserStore.Rejected(409, "Version %d was changed by someone else (revision %s, you had %d): reload it."
                        .formatted(version, v.get("revision"), expectedRevision));
            }
            update(c, "UPDATE " + t(spec.versions()) + " SET " + spec.content() + " = CAST(? AS JSONB), revision = revision + 1,"
                    + " updated_by = ?, updated_at = now() WHERE " + spec.idColumn() + " = ? AND version = ?",
                    SimpleJson.writeCompact(parsed), actor, id, version);
            return null;
        });
        audit.audit(actor, Role.ADMIN, spec.what() + "_SAVE", null, null, null, null, label + " v" + version
                + (problems.isEmpty() ? "" : " (" + problems.size() + " problem(s))"));
        return problems;
    }

    /** Publishes a valid draft; the version published before it is retired. */
    void publish(long id, String label, int version, String actor, PublishCheck check) throws Exception {
        Version draft = version(id, version);
        if (draft == null) {
            throw new UserStore.Rejected(404, "%s has no version %d.".formatted(cap(label), version));
        }
        if (!"DRAFT".equals(draft.state())) {
            throw new UserStore.Rejected(409, "Version %d is %s, not a draft.".formatted(version, draft.state()));
        }
        List<String> problems = problems(draft.content());
        if (!problems.isEmpty()) {
            throw new UserStore.Rejected(400, "The draft is not valid: " + String.join("; ", problems));
        }
        List<String> outside = check.problems(draft.content());
        if (!outside.isEmpty()) {
            throw new UserStore.Rejected(400, "The draft cannot be published: " + String.join("; ", outside));
        }
        db.transaction(c -> {
            lockHead(c, id, label);
            Map<String, Object> v = one(c, "SELECT state, revision FROM " + t(spec.versions()) + " WHERE " + spec.idColumn()
                    + " = ? AND version = ? FOR UPDATE", id, version);
            if (v == null || !"DRAFT".equals(v.get("state")) || ((Number) v.get("revision")).intValue() != draft.revision()) {
                throw new UserStore.Rejected(409, "Version %d changed while it was being published: reload it.".formatted(version));
            }
            update(c, "UPDATE " + t(spec.versions()) + " SET state = 'RETIRED' WHERE " + spec.idColumn() + " = ? AND state = 'PUBLISHED'", id);
            update(c, "UPDATE " + t(spec.versions()) + " SET state = 'PUBLISHED', published_by = ?, published_at = now()"
                    + " WHERE " + spec.idColumn() + " = ? AND version = ?", actor, id, version);
            return null;
        });
        audit.audit(actor, Role.ADMIN, spec.what() + "_PUBLISH", null, null, null, null, label + " v" + version);
    }

    /** Throws a draft away; published and retired versions are kept for good (orders may be pinned to them). */
    void discardDraft(long id, String label, int version, String actor) throws SQLException {
        db.transaction(c -> {
            lockHead(c, id, label);
            Map<String, Object> v = one(c, "SELECT state FROM " + t(spec.versions()) + " WHERE " + spec.idColumn()
                    + " = ? AND version = ? FOR UPDATE", id, version);
            if (v == null) {
                throw new UserStore.Rejected(404, "%s has no version %d.".formatted(cap(label), version));
            }
            if (!"DRAFT".equals(v.get("state"))) {
                throw new UserStore.Rejected(409, "Version %d is %s; only a draft can be discarded.".formatted(version, v.get("state")));
            }
            update(c, "DELETE FROM " + t(spec.versions()) + " WHERE " + spec.idColumn() + " = ? AND version = ?", id, version);
            return null;
        });
        audit.audit(actor, Role.ADMIN, spec.what() + "_DISCARD", null, null, null, null, label + " v" + version);
    }

    /** Retires a published version; refused for the only published one. */
    void retire(long id, String label, int version, String actor) throws SQLException {
        db.transaction(c -> {
            lockHead(c, id, label);
            Map<String, Object> v = one(c, "SELECT state FROM " + t(spec.versions()) + " WHERE " + spec.idColumn()
                    + " = ? AND version = ? FOR UPDATE", id, version);
            if (v == null) {
                throw new UserStore.Rejected(404, "%s has no version %d.".formatted(cap(label), version));
            }
            if (!"PUBLISHED".equals(v.get("state"))) {
                throw new UserStore.Rejected(409, "Version %d is %s; only a published version can be retired.".formatted(version, v.get("state")));
            }
            long published = ((Number) one(c, "SELECT count(*) AS n FROM " + t(spec.versions()) + " WHERE " + spec.idColumn()
                    + " = ? AND state = 'PUBLISHED'", id).get("n")).longValue();
            if (published <= 1) {
                throw new UserStore.Rejected(409, "Version %d is the only published version: publish another one first.".formatted(version));
            }
            update(c, "UPDATE " + t(spec.versions()) + " SET state = 'RETIRED' WHERE " + spec.idColumn() + " = ? AND version = ?", id, version);
            return null;
        });
        audit.audit(actor, Role.ADMIN, spec.what() + "_RETIRE", null, null, null, null, label + " v" + version);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> lockHead(Connection c, long id, String label) throws SQLException {
        Map<String, Object> head = one(c, "SELECT * FROM " + t(spec.head()) + " WHERE " + spec.idColumn() + " = ? FOR UPDATE", id);
        if (head == null) {
            throw new UserStore.Rejected(404, "There is no %s.".formatted(label));
        }
        return head;
    }

    @SuppressWarnings("unchecked")
    private Version version(Map<String, Object> r) {
        if (r == null) {
            return null;
        }
        return new Version(((Number) r.get(spec.idColumn())).longValue(), ((Number) r.get("version")).intValue(),
                (String) r.get("state"), ((Number) r.get("revision")).intValue(),
                (Map<String, Object>) SimpleJson.parse(String.valueOf(r.get(spec.content()))));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String t(String table) {
        return schema + "." + table;
    }

    static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    static Map<String, Object> one(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = all(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static List<Map<String, Object>> all(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            boolean hasRows = ps.execute();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (!hasRows) {
                return rows;
            }
            try (ResultSet rs = ps.getResultSet()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... params) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
        return ps;
    }
}
