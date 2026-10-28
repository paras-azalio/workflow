package com.nokia.workflow.wrapper.store;

import com.nokia.workflow.wrapper.SimpleJson;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

/**
 * The request store: every order (parent) and child order with its request id, and every step of them (template,
 * CIQ validation, MOP generation, MOP execution per node, rollback, reports) with the NEI's IO - in tables of the
 * schema {@code cr_automation} of a PostgreSQL database (in kestra-local: Kestra's own database).
 *
 * <pre>
 * request       req_id (PK), parent_req_id, kind ORDER|CHILD, node_type, activity, node_version, cr_name,
 *               stage, cr_list, json_file, folder, kestra_execution_id, created_at, updated_at
 * request_step  step_id, req_id, parent_req_id, kestra_execution_id, step, node_name, status, status_text,
 *               message, request (jsonb: IO in), results (jsonb: IO out), io_log, mml_log, started_at,
 *               duration_ms, recorded_at
 * </pre>
 *
 * Commands (each prints one Kestra outputs line {@code ::{"outputs": {...}}::}):
 * <pre>
 *   new-request  --kind ORDER|CHILD [--wanted N] [--parent P] [--cr CR] [--node-type T] [--activity A]
 *                [--node-version V] [--execution E] --shared-dir DIR
 *                -> req_id (N if given and free, else the next number), taken (all ids); creates DIR/&lt;id&gt;/
 *   record-step  --req-id R --step S [--node N] [--execution E] --result result.json [--io-log F] [--mml-log F]
 *   set-stage    --req-id R --stage S [--cr-list CR1,CR2] [--json-file F]
 *   child-status --req-id R      -> nodes, failed_nodes, status (SUCCESS / FAILED) from the node executions
 *   registry     --order R       -> orders (the order list for the forms), order (one order with its steps,
 *                                   children, cr_options, rollback_options)
 * </pre>
 * Config ({@code --config} properties): {@code store.jdbc.url}, {@code store.jdbc.user}; the password comes from the
 * environment variable {@code STORE_JDBC_PASSWORD}. Rollback is offered for an activity only when
 * {@code rollback.<NODE_TYPE>_<ACTIVITY>=true}. The PostgreSQL JDBC driver must be on the classpath.
 */
public final class RequestStore {

    static final String SCHEMA = "cr_automation";
    private static final int MAX_ORDERS = 200;

    private final Connection db;
    private final Properties config;

    RequestStore(Connection db, Properties config) {
        this.db = db;
        this.config = config;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println("usage: RequestStore new-request|record-step|set-stage|child-status|registry --config FILE ...");
            return 2;
        }
        Map<String, String> opt = options(args);
        try {
            Properties config = new Properties();
            if (opt.containsKey("config")) {
                InputStream in = new FileInputStream(opt.get("config"));
                try {
                    config.load(in);
                } finally {
                    in.close();
                }
            }
            String url = opt.containsKey("jdbc-url") ? opt.get("jdbc-url") : config.getProperty("store.jdbc.url");
            String user = opt.containsKey("jdbc-user") ? opt.get("jdbc-user") : config.getProperty("store.jdbc.user");
            String password = System.getenv("STORE_JDBC_PASSWORD");
            if (url == null) {
                err.println("store.jdbc.url is not configured");
                return 2;
            }
            Connection db = DriverManager.getConnection(url, user, password);
            try {
                RequestStore store = new RequestStore(db, config);
                store.ensureSchema();
                Map<String, Object> outputs = store.command(args[0], opt);
                out.println("::" + SimpleJson.writeCompact(java.util.Collections.singletonMap("outputs", outputs)) + "::");
                return 0;
            } finally {
                db.close();
            }
        } catch (StoreException e) {
            err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            err.println("request store: " + e);
            return 1;
        }
    }

    Map<String, Object> command(String name, Map<String, String> opt) throws Exception {
        if ("new-request".equals(name)) {
            return newRequest(opt);
        } else if ("record-step".equals(name)) {
            return recordStep(opt);
        } else if ("set-stage".equals(name)) {
            return setStage(opt);
        } else if ("child-status".equals(name)) {
            return childStatus(Long.parseLong(require(opt, "req-id")));
        } else if ("registry".equals(name)) {
            return registry(Long.parseLong(require(opt, "order")));
        }
        throw new StoreException("unknown command '" + name + "'");
    }

    // ------------------------------------------------------------------ schema

    void ensureSchema() throws SQLException {
        Statement st = db.createStatement();
        try {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            st.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".request ("
                    + " req_id BIGINT PRIMARY KEY,"
                    + " parent_req_id BIGINT REFERENCES " + SCHEMA + ".request(req_id),"
                    + " kind VARCHAR(10) NOT NULL,"
                    + " node_type VARCHAR(128), activity VARCHAR(256), node_version VARCHAR(64), cr_name VARCHAR(64),"
                    + " stage VARCHAR(32), cr_list TEXT, json_file TEXT, folder TEXT,"
                    + " kestra_execution_id VARCHAR(64),"
                    + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            st.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".request_step ("
                    + " step_id BIGSERIAL PRIMARY KEY,"
                    + " req_id BIGINT NOT NULL REFERENCES " + SCHEMA + ".request(req_id),"
                    + " parent_req_id BIGINT,"
                    + " kestra_execution_id VARCHAR(64),"
                    + " step VARCHAR(32) NOT NULL, node_name VARCHAR(128),"
                    + " status INT, status_text VARCHAR(16), message TEXT,"
                    + " request JSONB, results JSONB, io_log TEXT, mml_log TEXT,"
                    + " started_at TIMESTAMPTZ, duration_ms BIGINT,"
                    + " recorded_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            st.execute("CREATE INDEX IF NOT EXISTS request_step_req ON " + SCHEMA + ".request_step (req_id)");
            st.execute("CREATE INDEX IF NOT EXISTS request_parent ON " + SCHEMA + ".request (parent_req_id)");
        } finally {
            st.close();
        }
    }

    // ------------------------------------------------------------------ new-request

    /**
     * Takes a request id under a table lock: the wanted number if it is free, otherwise one more than the highest
     * id in use (table or folders). "In use" includes the order folders, so ids given before the table existed
     * are never handed out again.
     */
    Map<String, Object> newRequest(Map<String, String> opt) throws Exception {
        String kind = require(opt, "kind");
        File shared = new File(require(opt, "shared-dir"));
        String wanted = opt.get("wanted");
        Long parent = opt.containsKey("parent") && !opt.get("parent").isEmpty() ? Long.valueOf(opt.get("parent")) : null;
        db.setAutoCommit(false);
        try {
            exec("LOCK TABLE " + SCHEMA + ".request IN EXCLUSIVE MODE");
            Set<Long> taken = takenIds(shared);
            long id;
            if (wanted != null && !wanted.trim().isEmpty()) {
                if (!wanted.trim().matches("[1-9][0-9]*")) {
                    throw new StoreException("order number must be a whole number from 1 up, got '" + wanted + "'");
                }
                id = Long.parseLong(wanted.trim());
                if (taken.contains(id)) {
                    throw new StoreException("request id " + id + " is already taken");
                }
            } else {
                id = 1;
                for (long t : taken) {
                    id = Math.max(id, t + 1);
                }
            }
            File folder = new File(shared, String.valueOf(id));
            if (!folder.mkdirs() && !folder.isDirectory()) {
                throw new StoreException("cannot create the order folder " + folder);
            }
            Map<String, Object> from = parent == null ? new LinkedHashMap<String, Object>() : request(parent);
            PreparedStatement ps = db.prepareStatement("INSERT INTO " + SCHEMA + ".request (req_id, parent_req_id,"
                    + " kind, node_type, activity, node_version, cr_name, stage, json_file, folder, kestra_execution_id)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
            try {
                ps.setLong(1, id);
                if (parent == null) {
                    ps.setNull(2, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(2, parent);
                }
                ps.setString(3, kind);
                ps.setString(4, first(opt.get("node-type"), (String) from.get("node_type")));
                ps.setString(5, first(opt.get("activity"), (String) from.get("activity")));
                ps.setString(6, first(opt.get("node-version"), (String) from.get("node_version")));
                ps.setString(7, opt.get("cr"));
                ps.setString(8, "CHILD".equals(kind) ? "RUNNING" : "NEW");
                ps.setString(9, (String) from.get("json_file"));
                ps.setString(10, folder.getPath().replace('\\', '/'));
                ps.setString(11, opt.get("execution"));
                ps.executeUpdate();
            } finally {
                ps.close();
            }
            db.commit();
            taken.add(id);
            List<String> ids = new ArrayList<String>();
            for (long t : taken) {
                ids.add(String.valueOf(t));
            }
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("req_id", id);
            out.put("taken", ids);
            return out;
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    private Set<Long> takenIds(File shared) throws SQLException {
        Set<Long> taken = new java.util.TreeSet<Long>();
        for (Map<String, Object> row : query("SELECT req_id FROM " + SCHEMA + ".request")) {
            taken.add(((Number) row.get("req_id")).longValue());
        }
        String[] names = shared.list();
        if (names != null) {
            for (String n : names) {
                if (n.matches("[1-9][0-9]{0,17}")) {
                    taken.add(Long.parseLong(n));
                }
            }
        }
        return taken;
    }

    // ------------------------------------------------------------------ record-step / set-stage

    @SuppressWarnings("unchecked")
    Map<String, Object> recordStep(Map<String, String> opt) throws Exception {
        long reqId = Long.parseLong(require(opt, "req-id"));
        Map<String, Object> doc = (Map<String, Object>) SimpleJson.parse(
                new String(Files.readAllBytes(new File(require(opt, "result")).toPath()), StandardCharsets.UTF_8));
        Map<String, Object> req = request(reqId);
        PreparedStatement ps = db.prepareStatement("INSERT INTO " + SCHEMA + ".request_step (req_id, parent_req_id,"
                + " kestra_execution_id, step, node_name, status, status_text, message, request, results, io_log,"
                + " mml_log, started_at, duration_ms) VALUES (?,?,?,?,?,?,?,?,CAST(? AS JSONB),CAST(? AS JSONB),?,?,?,?)"
                + " RETURNING step_id");
        try {
            ps.setLong(1, reqId);
            Object parent = req.get("parent_req_id");
            if (parent == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, ((Number) parent).longValue());
            }
            ps.setString(3, opt.get("execution"));
            ps.setString(4, require(opt, "step"));
            ps.setString(5, emptyToNull(opt.get("node")));
            ps.setInt(6, Integer.parseInt(String.valueOf(doc.get("status"))));
            ps.setString(7, (String) doc.get("statusText"));
            ps.setString(8, (String) doc.get("message"));
            ps.setString(9, SimpleJson.writeCompact(doc.get("request")));
            ps.setString(10, SimpleJson.writeCompact(doc.get("results")));
            ps.setString(11, opt.get("io-log"));
            ps.setString(12, opt.get("mml-log"));
            ps.setTimestamp(13, timestamp((String) doc.get("startedAt")));
            ps.setLong(14, Long.parseLong(String.valueOf(doc.get("durationMs"))));
            ResultSet rs = ps.executeQuery();
            rs.next();
            return single("step_id", rs.getLong(1));
        } finally {
            ps.close();
        }
    }

    Map<String, Object> setStage(Map<String, String> opt) throws SQLException {
        long reqId = Long.parseLong(require(opt, "req-id"));
        PreparedStatement ps = db.prepareStatement("UPDATE " + SCHEMA + ".request SET stage = ?,"
                + " cr_list = COALESCE(?, cr_list), json_file = COALESCE(?, json_file), updated_at = now()"
                + " WHERE req_id = ?");
        try {
            ps.setString(1, require(opt, "stage"));
            ps.setString(2, opt.containsKey("cr-list") ? opt.get("cr-list").replace(" ", "") : null);
            ps.setString(3, emptyToNull(opt.get("json-file")));
            ps.setLong(4, reqId);
            if (ps.executeUpdate() != 1) {
                throw new StoreException("request " + reqId + " does not exist");
            }
        } finally {
            ps.close();
        }
        return single("req_id", reqId);
    }

    // ------------------------------------------------------------------ child-status

    /** The latest MOP_EXECUTION per node of a child: which nodes ran, which failed. */
    Map<String, Object> childStatus(long reqId) throws SQLException {
        List<String> nodes = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (Map<String, Object> row : latestPerNode(reqId, "MOP_EXECUTION")) {
            nodes.add((String) row.get("node_name"));
            if (((Number) row.get("status")).intValue() != 0) {
                failed.add((String) row.get("node_name"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("nodes", nodes);
        out.put("failed_nodes", failed);
        out.put("failed_count", failed.size());
        out.put("status", failed.isEmpty() ? "SUCCESS" : "FAILED");
        return out;
    }

    private List<Map<String, Object>> latestPerNode(long reqId, String step) throws SQLException {
        return query("SELECT DISTINCT ON (node_name) node_name, status, results ->> 'ROLLBACK_ENABLED' AS rollback_enabled,"
                + " kestra_execution_id, recorded_at FROM " + SCHEMA + ".request_step"
                + " WHERE req_id = ? AND step = ? AND node_name IS NOT NULL ORDER BY node_name, step_id DESC", reqId, step);
    }

    // ------------------------------------------------------------------ registry (what the forms and the Order tab read)

    Map<String, Object> registry(long orderId) throws SQLException {
        List<Object> orders = new ArrayList<Object>();
        for (Map<String, Object> o : query("SELECT * FROM " + SCHEMA + ".request WHERE kind = 'ORDER'"
                + " ORDER BY req_id DESC LIMIT " + MAX_ORDERS)) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("value", String.valueOf(o.get("req_id")));
            s.put("label", label(o));
            s.put("stage", o.get("stage"));
            orders.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("orders", orders);
        out.put("order", order(orderId));
        return out;
    }

    Map<String, Object> order(long orderId) throws SQLException {
        Map<String, Object> o = request(orderId);
        Map<String, Object> order = new LinkedHashMap<String, Object>();
        order.put("value", String.valueOf(orderId));
        order.put("stage", o.get("stage"));
        order.put("node_type", o.get("node_type"));
        order.put("activity", o.get("activity"));
        order.put("node_version", o.get("node_version"));
        order.put("json_file", o.get("json_file"));
        order.put("order_dir", o.get("folder"));
        List<String> crList = new ArrayList<String>();
        if (o.get("cr_list") != null && "VALIDATED".equals(o.get("stage"))) {   // an invalid CIQ has no usable CRs
            for (String cr : ((String) o.get("cr_list")).split(",")) {
                if (!cr.trim().isEmpty()) {
                    crList.add(cr.trim());
                }
            }
        }
        order.put("cr_list", crList);

        List<Object> steps = new ArrayList<Object>();
        for (Map<String, Object> s : query("SELECT kestra_execution_id, step, status_text, recorded_at FROM " + SCHEMA
                + ".request_step WHERE req_id = ? ORDER BY step_id", orderId)) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("execution_id", s.get("kestra_execution_id"));
            m.put("stage", s.get("step") + " " + s.get("status_text"));
            m.put("at", at(s.get("recorded_at")));
            steps.add(m);
        }
        order.put("steps", steps);

        boolean rollbackSupported = Boolean.parseBoolean(config.getProperty(
                "rollback." + o.get("node_type") + "_" + o.get("activity"), "false"));
        order.put("rollback_supported", rollbackSupported);
        Map<String, Object> children = new LinkedHashMap<String, Object>();
        List<Object> rollbackOptions = new ArrayList<Object>();
        for (Map<String, Object> c : query("SELECT * FROM " + SCHEMA + ".request WHERE parent_req_id = ?"
                + " ORDER BY req_id", orderId)) {
            long childId = ((Number) c.get("req_id")).longValue();
            List<Object> nodes = new ArrayList<Object>();
            Map<String, Object> lastRollback = new LinkedHashMap<String, Object>();
            for (Map<String, Object> r : latestPerNode(childId, "ROLLBACK")) {
                lastRollback.put((String) r.get("node_name"), r.get("status"));
            }
            for (Map<String, Object> n : latestPerNode(childId, "MOP_EXECUTION")) {
                String node = (String) n.get("node_name");
                boolean enabled = "TRUE".equalsIgnoreCase(String.valueOf(n.get("rollback_enabled")));
                boolean rolledBack = lastRollback.containsKey(node) && ((Number) lastRollback.get(node)).intValue() == 0;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("node", node);
                m.put("status", ((Number) n.get("status")).intValue() == 0 ? "SUCCESS" : "FAILED");
                m.put("rollback_enabled", enabled);
                m.put("rolled_back", rolledBack);
                nodes.add(m);
                if (rollbackSupported && enabled && !rolledBack) {
                    Map<String, Object> opt = new LinkedHashMap<String, Object>();
                    opt.put("value", childId + ":" + node + ":" + c.get("cr_name"));   // child:node:CR
                    opt.put("label", "child " + childId + " (" + c.get("cr_name") + ") node " + node);
                    rollbackOptions.add(opt);
                }
            }
            Map<String, Object> run = new LinkedHashMap<String, Object>();
            run.put("child_id", String.valueOf(childId));
            run.put("execution_id", c.get("kestra_execution_id"));
            run.put("state", c.get("stage"));
            run.put("at", at(c.get("updated_at")));
            run.put("nodes", nodes);
            @SuppressWarnings("unchecked")
            List<Object> runs = (List<Object>) children.get(c.get("cr_name"));
            if (runs == null) {
                runs = new ArrayList<Object>();
                children.put((String) c.get("cr_name"), runs);
            }
            runs.add(run);
        }
        order.put("children", children);
        order.put("rollback_options", rollbackOptions);

        List<Object> crOptions = new ArrayList<Object>();
        for (String cr : crList) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> runs = (List<Map<String, Object>>) children.get(cr);
            Map<String, Object> opt = new LinkedHashMap<String, Object>();
            opt.put("value", cr);
            opt.put("label", runs == null ? cr : cr + " | last child " + runs.get(runs.size() - 1).get("child_id")
                    + " " + runs.get(runs.size() - 1).get("state"));
            crOptions.add(opt);
        }
        order.put("cr_options", crOptions);
        return order;
    }

    private static String label(Map<String, Object> o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.get("req_id")).append(" | ").append(o.get("node_type")).append('_').append(o.get("activity"))
          .append(" | ").append(o.get("stage"));
        if (o.get("cr_list") != null && "VALIDATED".equals(o.get("stage"))) {
            sb.append(" | ").append(o.get("cr_list"));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    Map<String, Object> request(long reqId) throws SQLException {
        List<Map<String, Object>> rows = query("SELECT * FROM " + SCHEMA + ".request WHERE req_id = ?", reqId);
        if (rows.isEmpty()) {
            throw new StoreException("request " + reqId + " does not exist");
        }
        return rows.get(0);
    }

    List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        PreparedStatement ps = db.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                for (int c = 1; c <= n; c++) {
                    row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                }
                rows.add(row);
            }
            return rows;
        } finally {
            ps.close();
        }
    }

    private void exec(String sql) throws SQLException {
        Statement st = db.createStatement();
        try {
            st.execute(sql);
        } finally {
            st.close();
        }
    }

    private static String at(Object ts) {
        if (!(ts instanceof Date)) {
            return ts == null ? null : String.valueOf(ts);
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format((Date) ts);
    }

    private static Timestamp timestamp(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return new Timestamp(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(iso).getTime());
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(key, value);
        return m;
    }

    private static String first(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    private static String emptyToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    private static String require(Map<String, String> opt, String name) {
        String v = opt.get(name);
        if (v == null || v.trim().isEmpty()) {
            throw new StoreException("--" + name + " is required");
        }
        return v.trim();
    }

    /** {@code --name value} pairs after the command. */
    static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String name = args[i].substring(2);
                m.put(name, i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "");
            }
        }
        return m;
    }

    static final class StoreException extends RuntimeException {
        StoreException(String message) {
            super(message);
        }
    }
}
