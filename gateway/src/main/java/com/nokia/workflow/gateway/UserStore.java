package com.nokia.workflow.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Users, sessions and the audit log of the gateway, in tables of one PostgreSQL schema.
 *
 * <p>Security rules kept here: one answer for unknown user / wrong password / locked / disabled; a lock after
 * repeated failures; sessions stored only as a hash of their token, ended by idle time, absolute age, logout, and
 * by any change to the user's role, password or enabled state; the last enabled admin can never be removed.
 */
final class UserStore {

    /** Kestra's login page only accepts an e-mail address as user name. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    record User(long id, String username, String displayName, Role role, boolean enabled) {
    }

    record Settings(Duration sessionIdle, Duration sessionMax, int maxFailedLogins, Duration lockDuration) {
    }

    /** A change that is not allowed (bad input, conflict); {@code status} is the HTTP status to answer. */
    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final Db db;
    private final String schema;
    private final Settings settings;

    UserStore(Db db, String schema, Settings settings) {
        if (!schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid schema name '%s'.".formatted(schema));
        }
        this.db = db;
        this.schema = schema;
        this.settings = settings;
    }

    void ensureSchema() throws SQLException {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                st.execute("CREATE TABLE IF NOT EXISTS " + t("app_user") + " ("
                        + " user_id BIGSERIAL PRIMARY KEY,"
                        + " username VARCHAR(256) NOT NULL UNIQUE,"
                        + " display_name VARCHAR(256),"
                        + " role VARCHAR(16) NOT NULL CHECK (role IN ('VIEWER', 'EXECUTOR', 'ADMIN')),"
                        + " password_hash TEXT NOT NULL,"
                        + " enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                        + " failed_logins INT NOT NULL DEFAULT 0,"
                        + " locked_until TIMESTAMPTZ,"
                        + " password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                st.execute("CREATE TABLE IF NOT EXISTS " + t("app_session") + " ("
                        + " token_hash CHAR(64) PRIMARY KEY,"
                        + " user_id BIGINT NOT NULL REFERENCES " + t("app_user") + "(user_id) ON DELETE CASCADE,"
                        + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " expires_at TIMESTAMPTZ NOT NULL,"
                        + " ip VARCHAR(64), user_agent VARCHAR(512))");
                st.execute("CREATE INDEX IF NOT EXISTS app_session_user ON " + t("app_session") + " (user_id)");
                st.execute("CREATE TABLE IF NOT EXISTS " + t("audit_log") + " ("
                        + " audit_id BIGSERIAL PRIMARY KEY,"
                        + " at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " username VARCHAR(256), role VARCHAR(16), action VARCHAR(32) NOT NULL,"
                        + " method VARCHAR(8), path TEXT, status INT, ip VARCHAR(64), detail TEXT)");
            }
            return null;
        });
    }

    /** Creates the first admin from the configuration, only while there is no user at all. */
    boolean bootstrapAdmin(String username, String password) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return false;
        }
        return db.transaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("LOCK TABLE " + t("app_user") + " IN EXCLUSIVE MODE");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + t("app_user"))) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return false;
                    }
                }
            }
            insertUser(c, username, "Administrator", Role.ADMIN, password);
            audit(c, username, Role.ADMIN, "BOOTSTRAP_ADMIN", null, null, null, null, "first admin from the configuration");
            return true;
        });
    }

    // ------------------------------------------------------------------ login

    /**
     * The user when the credentials are right, the user is enabled and not locked; empty otherwise, with no hint
     * which of these failed. A wrong password counts towards the lock.
     */
    Optional<User> authenticate(String username, String password, String ip) throws SQLException {
        String name = normalizeUsername(username);
        char[] pw = password == null ? new char[0] : password.toCharArray();
        return db.transaction(c -> {
            Map<String, Object> row = name == null ? null : one(c, "SELECT * FROM " + t("app_user")
                    + " WHERE username = ? FOR UPDATE", name);
            if (row == null) {
                Passwords.verifyDummy(pw);
                audit(c, name, null, "LOGIN_FAILED", null, null, 401, ip, "unknown user");
                return Optional.<User>empty();
            }
            User user = user(row);
            Timestamp lockedUntil = (Timestamp) row.get("locked_until");
            if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
                Passwords.verifyDummy(pw);
                audit(c, name, user.role(), "LOGIN_FAILED", null, null, 401, ip, "locked");
                return Optional.<User>empty();
            }
            if (!user.enabled()) {
                Passwords.verifyDummy(pw);
                audit(c, name, user.role(), "LOGIN_FAILED", null, null, 401, ip, "disabled");
                return Optional.<User>empty();
            }
            if (!Passwords.verify(pw, (String) row.get("password_hash"))) {
                int failed = ((Number) row.get("failed_logins")).intValue() + 1;
                boolean lock = failed >= settings.maxFailedLogins();
                update(c, "UPDATE " + t("app_user") + " SET failed_logins = ?, locked_until = ? WHERE user_id = ?",
                        lock ? 0 : failed, lock ? Timestamp.from(Instant.now().plus(settings.lockDuration())) : null,
                        user.id());
                audit(c, name, user.role(), "LOGIN_FAILED", null, null, 401, ip,
                        lock ? "wrong password - locked for " + settings.lockDuration().toMinutes() + " min" : "wrong password");
                return Optional.<User>empty();
            }
            update(c, "UPDATE " + t("app_user") + " SET failed_logins = 0, locked_until = NULL WHERE user_id = ?", user.id());
            return Optional.of(user);
        });
    }

    /** A new session for the user; returns the cookie token (only its hash is stored). */
    String createSession(User user, String ip, String userAgent) throws SQLException {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.with(c -> update(c, "INSERT INTO " + t("app_session") + " (token_hash, user_id, expires_at, ip, user_agent)"
                + " VALUES (?,?,?,?,?)", sha256(token), user.id(),
                Timestamp.from(Instant.now().plus(settings.sessionMax())), trim(ip, 64), trim(userAgent, 512)));
        db.with(c -> {
            audit(c, user.username(), user.role(), "LOGIN", null, null, 204, ip, null);
            return null;
        });
        return token;
    }

    /** The session's user, while the session is neither idle too long nor too old and the user is enabled. */
    Optional<User> session(String token) throws SQLException {
        if (token == null || token.isEmpty() || token.length() > 128) {
            return Optional.empty();
        }
        String hash = sha256(token);
        return db.with(c -> {
            Map<String, Object> row = one(c, "SELECT s.last_seen_at, s.expires_at, u.* FROM " + t("app_session") + " s JOIN "
                    + t("app_user") + " u ON u.user_id = s.user_id WHERE s.token_hash = ?", hash);
            if (row == null) {
                return Optional.<User>empty();
            }
            Instant now = Instant.now();
            Instant lastSeen = ((Timestamp) row.get("last_seen_at")).toInstant();
            boolean expired = now.isAfter(((Timestamp) row.get("expires_at")).toInstant())
                    || now.isAfter(lastSeen.plus(settings.sessionIdle()));
            User user = user(row);
            if (expired || !user.enabled()) {
                update(c, "DELETE FROM " + t("app_session") + " WHERE token_hash = ?", hash);
                return Optional.<User>empty();
            }
            if (now.isAfter(lastSeen.plus(TOUCH_EVERY))) {
                update(c, "UPDATE " + t("app_session") + " SET last_seen_at = now() WHERE token_hash = ?", hash);
            }
            return Optional.of(user);
        });
    }

    void endSession(String token) throws SQLException {
        if (token != null && !token.isEmpty()) {
            db.with(c -> update(c, "DELETE FROM " + t("app_session") + " WHERE token_hash = ?", sha256(token)));
        }
    }

    // ------------------------------------------------------------------ users (admin)

    List<Map<String, Object>> listUsers() throws SQLException {
        return db.with(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : all(c, "SELECT u.*, (SELECT count(*) FROM " + t("app_session")
                    + " s WHERE s.user_id = u.user_id AND s.expires_at > now()) AS sessions FROM " + t("app_user")
                    + " u ORDER BY u.username")) {
                out.add(publicView(row));
            }
            return out;
        });
    }

    Map<String, Object> createUser(String actor, Role actorRole, String username, String displayName, String role,
                                   String password) throws SQLException {
        String name = normalizeUsername(username);
        if (name == null) {
            throw new Rejected(400, "The user name must be an e-mail address (Kestra's login page only accepts those).");
        }
        Role r = parseRole(role);
        List<String> problems = Passwords.problems(name, password);
        if (!problems.isEmpty()) {
            throw new Rejected(400, String.join(" ", problems));
        }
        return db.transaction(c -> {
            if (one(c, "SELECT user_id FROM " + t("app_user") + " WHERE username = ?", name) != null) {
                throw new Rejected(409, "The user %s already exists.".formatted(name));
            }
            insertUser(c, name, displayName, r, password);
            audit(c, actor, actorRole, "USER_CREATE", null, null, null, null, name + " as " + r);
            return publicView(one(c, "SELECT u.*, 0 AS sessions FROM " + t("app_user") + " u WHERE username = ?", name));
        });
    }

    /**
     * Changes role, enabled, display name and / or password. A change of role, enabled or password ends all the
     * user's sessions. Refused when it would leave no enabled admin.
     */
    Map<String, Object> updateUser(String actor, Role actorRole, String username, String role, Boolean enabled,
                                   String displayName, String password) throws SQLException {
        String name = normalizeUsername(username);
        Role newRole = role == null ? null : parseRole(role);
        if (password != null) {
            List<String> problems = Passwords.problems(name, password);
            if (!problems.isEmpty()) {
                throw new Rejected(400, String.join(" ", problems));
            }
        }
        return db.transaction(c -> {
            lockAdmins(c);
            Map<String, Object> row = name == null ? null
                    : one(c, "SELECT * FROM " + t("app_user") + " WHERE username = ? FOR UPDATE", name);
            if (row == null) {
                throw new Rejected(404, "There is no user %s.".formatted(username));
            }
            User before = user(row);
            boolean staysAdmin = (newRole == null ? before.role() : newRole) == Role.ADMIN
                    && (enabled == null ? before.enabled() : enabled);
            if (before.role() == Role.ADMIN && before.enabled() && !staysAdmin && enabledAdmins(c) <= 1) {
                throw new Rejected(409, "%s is the last enabled admin: make another user admin first.".formatted(name));
            }
            List<String> changes = new ArrayList<>();
            if (newRole != null && newRole != before.role()) {
                update(c, "UPDATE " + t("app_user") + " SET role = ?, updated_at = now() WHERE user_id = ?", newRole.name(), before.id());
                changes.add("role " + before.role() + " -> " + newRole);
            }
            if (enabled != null && enabled != before.enabled()) {
                update(c, "UPDATE " + t("app_user") + " SET enabled = ?, updated_at = now() WHERE user_id = ?", enabled, before.id());
                changes.add(enabled ? "enabled" : "disabled");
            }
            if (displayName != null) {
                update(c, "UPDATE " + t("app_user") + " SET display_name = ?, updated_at = now() WHERE user_id = ?",
                        trim(displayName, 256), before.id());
                changes.add("display name");
            }
            if (password != null) {
                update(c, "UPDATE " + t("app_user") + " SET password_hash = ?, password_changed_at = now(), failed_logins = 0,"
                        + " locked_until = NULL, updated_at = now() WHERE user_id = ?", Passwords.hash(password.toCharArray()), before.id());
                changes.add("password");
            }
            boolean endSessions = changes.stream().anyMatch(ch -> !"display name".equals(ch));
            if (endSessions) {
                update(c, "DELETE FROM " + t("app_session") + " WHERE user_id = ?", before.id());
            }
            audit(c, actor, actorRole, "USER_UPDATE", null, null, null, null, name + ": "
                    + (changes.isEmpty() ? "nothing changed" : String.join(", ", changes)) + (endSessions ? " (sessions ended)" : ""));
            return publicView(one(c, "SELECT u.*, (SELECT count(*) FROM " + t("app_session") + " s WHERE s.user_id = u.user_id)"
                    + " AS sessions FROM " + t("app_user") + " u WHERE user_id = ?", before.id()));
        });
    }

    void deleteUser(String actor, Role actorRole, String username) throws SQLException {
        String name = normalizeUsername(username);
        db.transaction(c -> {
            lockAdmins(c);
            Map<String, Object> row = name == null ? null
                    : one(c, "SELECT * FROM " + t("app_user") + " WHERE username = ? FOR UPDATE", name);
            if (row == null) {
                throw new Rejected(404, "There is no user %s.".formatted(username));
            }
            User u = user(row);
            if (u.role() == Role.ADMIN && u.enabled() && enabledAdmins(c) <= 1) {
                throw new Rejected(409, "%s is the last enabled admin and cannot be deleted.".formatted(name));
            }
            update(c, "DELETE FROM " + t("app_user") + " WHERE user_id = ?", u.id());
            audit(c, actor, actorRole, "USER_DELETE", null, null, null, null, name);
            return null;
        });
    }

    /** The user's own password change: needs the current password; ends all the user's sessions. */
    void changeOwnPassword(User user, String current, String password, String ip) throws SQLException {
        if (authenticate(user.username(), current, ip).isEmpty()) {
            throw new Rejected(403, "The current password is wrong.");
        }
        updateUser(user.username(), user.role(), user.username(), null, null, null, password);
    }

    // ------------------------------------------------------------------ audit

    void audit(String username, Role role, String action, String method, String path, Integer status, String ip,
               String detail) {
        try {
            db.with(c -> {
                audit(c, username, role, action, method, path, status, ip, detail);
                return null;
            });
        } catch (SQLException e) {
            System.err.println("gateway: audit entry not written: " + e.getMessage());
        }
    }

    List<Map<String, Object>> auditLog(int limit) throws SQLException {
        int n = Math.max(1, Math.min(limit, 1000));
        return db.with(c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : all(c, "SELECT * FROM " + t("audit_log") + " ORDER BY audit_id DESC LIMIT " + n)) {
                Map<String, Object> m = new LinkedHashMap<>(row);
                m.put("at", String.valueOf(row.get("at")));
                out.add(m);
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ helpers

    static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String u = username.trim().toLowerCase(Locale.ROOT);
        return u.length() <= 256 && EMAIL.matcher(u).matches() ? u : null;
    }

    private static Role parseRole(String role) {
        try {
            return Role.parse(role == null ? "" : role);
        } catch (IllegalArgumentException e) {
            throw new Rejected(400, e.getMessage());
        }
    }

    private void insertUser(Connection c, String username, String displayName, Role role, String password) throws SQLException {
        update(c, "INSERT INTO " + t("app_user") + " (username, display_name, role, password_hash) VALUES (?,?,?,?)",
                username, trim(displayName, 256), role.name(), Passwords.hash(password.toCharArray()));
    }

    private void lockAdmins(Connection c) throws SQLException {
        all(c, "SELECT user_id FROM " + t("app_user") + " WHERE role = 'ADMIN' AND enabled FOR UPDATE");
    }

    private long enabledAdmins(Connection c) throws SQLException {
        return ((Number) one(c, "SELECT count(*) AS n FROM " + t("app_user") + " WHERE role = 'ADMIN' AND enabled").get("n")).longValue();
    }

    private void audit(Connection c, String username, Role role, String action, String method, String path, Integer status,
                       String ip, String detail) throws SQLException {
        update(c, "INSERT INTO " + t("audit_log") + " (username, role, action, method, path, status, ip, detail)"
                + " VALUES (?,?,?,?,?,?,?,?)", trim(username, 256), role == null ? null : role.name(), action, method,
                path, status, trim(ip, 64), detail);
    }

    private static User user(Map<String, Object> row) {
        return new User(((Number) row.get("user_id")).longValue(), (String) row.get("username"),
                (String) row.get("display_name"), Role.valueOf((String) row.get("role")), (Boolean) row.get("enabled"));
    }

    private static Map<String, Object> publicView(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", row.get("username"));
        m.put("display_name", row.get("display_name"));
        m.put("role", row.get("role"));
        m.put("enabled", row.get("enabled"));
        Timestamp lockedUntil = (Timestamp) row.get("locked_until");
        m.put("locked", lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now()));
        m.put("sessions", row.get("sessions"));
        m.put("password_changed_at", String.valueOf(row.get("password_changed_at")));
        m.put("created_at", String.valueOf(row.get("created_at")));
        return m;
    }

    private String t(String table) {
        return schema + "." + table;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params)) {
            return ps.executeUpdate();
        }
    }

    private static Map<String, Object> one(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = all(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<Map<String, Object>> all(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, params); ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
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
