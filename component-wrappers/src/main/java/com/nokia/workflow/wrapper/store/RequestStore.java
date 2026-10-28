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
 * node_lock     req_id, node_name (PK), kestra_execution_id, claimed_at - a node being executed right now
 * </pre>
 *
 * Commands (each prints one Kestra outputs line {@code ::{"outputs": {...}}::}, except {@code locked}):
 * <pre>
 *   new-request   --kind ORDER|CHILD [--wanted N] [--parent P] [--cr CR] [--node-type T] [--activity A]
 *                 [--node-version V] [--execution E] --shared-dir DIR
 *                 -> req_id (N if given and free, else the next number), taken (all ids); creates DIR/&lt;id&gt;/
 *   record-step   --req-id R --step S [--node N] [--execution E] --result result.json [--io-log F] [--mml-log F]
 *   set-stage     --req-id R --stage S [--if-stage S1,S2] [--cr-list CR1,CR2] [--json-file F]
 *   step-config   --req-id R --step S [--node N] --out FILE
 *                 -> the step's request overrides from the order's activity configuration, as a properties file
 *   claim-nodes   --req-id R --nodes N1,N2|* --execution E
 *                 -> the child's data and the claimed nodes; all or none: fails when a node is not in the child's
 *                    MOP or another execution is running it
 *   release-nodes --req-id R (--execution E | --all)
 *   child-status  --req-id R [--nodes N1,N2] [--update-stage]
 *                 -> nodes, failed_nodes, status of these nodes (default all), child_stage of the whole child
 *   registry      --order R       -> orders (the order list for the forms), order (one order with its steps,
 *                                    children, cr_options, child_options, node options, rollback_options)
 *   locked        --key K -- command args...   runs the command while holding a database lock on K (one at a
 *                 time across all executions); exits with the command's exit code
 *   pipelines     --req-id R --flow F   -> node, after_nodes: the compiled pipeline flows the order's process
 *                                          version attaches to flow F ("" when none)
 *   pipeline-context --req-id R [--nodes N1,N2]
 *                 -> order, child (with its collected lists), ciq, activity, run (with --nodes: their results)
 *   collect       --req-id R --name LIST --value V1,V2 [--node N] [--execution E]   adds values to a child's list
 *   record        --req-id R --step S --status-text T [--status N] [--node N] [--execution E] [--message M]
 *                 [--request-json J] [--results-json J] [--unless-since ISO]   records a step without an NEI result;
 *                 with --unless-since, only when the request has no row of that step / node since then
 * </pre>
 * A node's result is its latest MOP_EXECUTION or NODE_PIPELINE row (a pipeline records NODE_PIPELINE when it ends).
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
                if ("locked".equals(args[0])) {
                    return store.locked(require(opt, "key"), commandAfterDashes(args));
                }
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
        } else if ("step-config".equals(name)) {
            return stepConfig(Long.parseLong(require(opt, "req-id")), require(opt, "step"), opt.get("node"),
                    new File(require(opt, "out")));
        } else if ("claim-nodes".equals(name)) {
            return claimNodes(Long.parseLong(require(opt, "req-id")), require(opt, "nodes"), require(opt, "execution"));
        } else if ("release-nodes".equals(name)) {
            return releaseNodes(Long.parseLong(require(opt, "req-id")), opt.get("execution"), opt.containsKey("all"));
        } else if ("child-status".equals(name)) {
            return childStatus(Long.parseLong(require(opt, "req-id")), opt.get("nodes"), opt.containsKey("update-stage"));
        } else if ("registry".equals(name)) {
            return registry(Long.parseLong(require(opt, "order")));
        } else if ("pipelines".equals(name)) {
            return pipelines(Long.parseLong(require(opt, "req-id")), require(opt, "flow"));
        } else if ("pipeline-context".equals(name)) {
            return pipelineContext(Long.parseLong(require(opt, "req-id")), opt.get("nodes"));
        } else if ("collect".equals(name)) {
            return collect(Long.parseLong(require(opt, "req-id")), require(opt, "name"), opt.get("value"), opt.get("node"),
                    opt.get("execution"));
        } else if ("record".equals(name)) {
            return record(opt);
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
            st.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".node_lock ("
                    + " req_id BIGINT NOT NULL REFERENCES " + SCHEMA + ".request(req_id),"
                    + " node_name VARCHAR(128) NOT NULL,"
                    + " kestra_execution_id VARCHAR(64) NOT NULL,"
                    + " claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                    + " PRIMARY KEY (req_id, node_name))");
            st.execute("ALTER TABLE " + SCHEMA + ".request ADD COLUMN IF NOT EXISTS process_id BIGINT");
            st.execute("ALTER TABLE " + SCHEMA + ".request ADD COLUMN IF NOT EXISTS process_version INT");
            st.execute("ALTER TABLE " + SCHEMA + ".request ADD COLUMN IF NOT EXISTS activity_id BIGINT");
            st.execute("ALTER TABLE " + SCHEMA + ".request ADD COLUMN IF NOT EXISTS activity_version INT");
            st.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".request_value ("
                    + " value_id BIGSERIAL PRIMARY KEY,"
                    + " req_id BIGINT NOT NULL REFERENCES " + SCHEMA + ".request(req_id),"
                    + " name VARCHAR(64) NOT NULL, value TEXT NOT NULL, node_name VARCHAR(128),"
                    + " kestra_execution_id VARCHAR(64), added_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            st.execute("CREATE INDEX IF NOT EXISTS request_value_req ON " + SCHEMA + ".request_value (req_id, name)");
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
            Object[] process = parent == null ? publishedProcess() : new Object[] {from.get("process_id"), from.get("process_version")};
            Object[] activity = parent == null
                    ? publishedActivity(first(opt.get("node-type"), null), first(opt.get("activity"), null))
                    : new Object[] {from.get("activity_id"), from.get("activity_version")};
            PreparedStatement ps = db.prepareStatement("INSERT INTO " + SCHEMA + ".request (req_id, parent_req_id,"
                    + " kind, node_type, activity, node_version, cr_name, stage, json_file, folder, kestra_execution_id,"
                    + " process_id, process_version, activity_id, activity_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
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
                ps.setObject(12, process[0]);
                ps.setObject(13, process[1]);
                ps.setObject(14, activity[0]);
                ps.setObject(15, activity[1]);
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

    /**
     * The process version a new order is pinned to: the latest PUBLISHED version of the project's process
     * (config {@code store.project}, default CR_AUTOMATION). {null, null} while the gateway has not created the
     * process tables; a child always takes its order's version.
     */
    private Object[] publishedProcess() throws SQLException {
        List<Map<String, Object>> exists = query("SELECT to_regclass('" + SCHEMA + ".process_version') IS NOT NULL AS ok");
        if (exists.isEmpty() || !Boolean.TRUE.equals(exists.get(0).get("ok"))) {
            return new Object[] {null, null};
        }
        List<Map<String, Object>> rows = query("SELECT p.process_id, v.version FROM " + SCHEMA + ".process p JOIN " + SCHEMA
                + ".process_version v ON v.process_id = p.process_id WHERE p.project = ? AND v.state = 'PUBLISHED'"
                + " ORDER BY v.version DESC LIMIT 1", config.getProperty("store.project", "CR_AUTOMATION"));
        return rows.isEmpty() ? new Object[] {null, null}
                : new Object[] {rows.get(0).get("process_id"), rows.get(0).get("version")};
    }

    /**
     * The activity configuration version a new order is pinned to: the latest PUBLISHED version declared for its
     * node type + sub-activity. {null, null} when none is declared (the steps then run with the flows' own values).
     */
    private Object[] publishedActivity(String nodeType, String subActivity) throws SQLException {
        List<Map<String, Object>> exists = query("SELECT to_regclass('" + SCHEMA + ".activity_version') IS NOT NULL AS ok");
        if (nodeType == null || subActivity == null || exists.isEmpty() || !Boolean.TRUE.equals(exists.get(0).get("ok"))) {
            return new Object[] {null, null};
        }
        List<Map<String, Object>> rows = query("SELECT a.activity_id, v.version FROM " + SCHEMA + ".activity a JOIN " + SCHEMA
                + ".activity_version v ON v.activity_id = a.activity_id WHERE a.project = ? AND a.node_type = ?"
                + " AND a.sub_activity = ? AND v.state = 'PUBLISHED' ORDER BY v.version DESC LIMIT 1",
                config.getProperty("store.project", "CR_AUTOMATION"), nodeType, subActivity);
        return rows.isEmpty() ? new Object[] {null, null}
                : new Object[] {rows.get(0).get("activity_id"), rows.get(0).get("version")};
    }

    // ------------------------------------------------------------------ step-config (activity configuration of one step)

    private static final java.util.regex.Pattern REF = java.util.regex.Pattern.compile("\\$\\{([^}]*)\\}");

    /**
     * Writes the request overrides of one step, from the activity configuration version the order is pinned to:
     * section {@code *} then the step's section, each with {@code params} (as KEY), {@code login} (as login.KEY),
     * {@code grc} (as grc.KEY), then its {@code nodes} entries whose pattern matches the node, in declaration order
     * (later wins). References {@code ${order.X}}, {@code ${child.X}}, {@code ${node}}, {@code ${ciq.X}} are
     * resolved here; {@code ${secret:NAME}} and {@code ${WORKDIR}} are left for the wrapper. An empty file when the
     * order has no activity configuration.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> stepConfig(long reqId, String step, String node, File out) throws Exception {
        Map<String, Object> req = request(reqId);
        Map<String, Object> order = req.get("parent_req_id") == null ? req
                : request(((Number) req.get("parent_req_id")).longValue());
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        String source = null;
        if (order.get("activity_id") != null && order.get("activity_version") != null) {
            List<Map<String, Object>> rows = query("SELECT a.node_type, a.sub_activity, v.config FROM " + SCHEMA + ".activity a JOIN "
                    + SCHEMA + ".activity_version v ON v.activity_id = a.activity_id WHERE a.activity_id = ? AND v.version = ?",
                    order.get("activity_id"), order.get("activity_version"));
            if (rows.isEmpty()) {
                throw new StoreException("order " + order.get("req_id") + " is pinned to activity configuration "
                        + order.get("activity_id") + " v" + order.get("activity_version") + ", which does not exist");
            }
            Map<String, Object> row = rows.get(0);
            source = row.get("node_type") + "/" + row.get("sub_activity") + " v" + order.get("activity_version");
            Object cfg = SimpleJson.parse(String.valueOf(row.get("config")));
            Object steps = cfg instanceof Map ? ((Map<String, Object>) cfg).get("steps") : null;
            if (steps instanceof Map) {
                for (String section : new String[] {"*", step}) {
                    Object s = ((Map<String, Object>) steps).get(section);
                    if (s instanceof Map) {
                        apply((Map<String, Object>) s, overrides);
                        Object nodes = ((Map<String, Object>) s).get("nodes");
                        if (nodes instanceof Map && node != null && !node.isEmpty()) {
                            for (Map.Entry<String, Object> n : ((Map<String, Object>) nodes).entrySet()) {
                                if (glob(n.getKey(), node) && n.getValue() instanceof Map) {
                                    apply((Map<String, Object>) n.getValue(), overrides);
                                }
                            }
                        }
                    }
                }
            }
            Map<String, Object> ciq = null;
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                if (e.getValue().contains("${ciq.") && ciq == null) {
                    ciq = ciqResults(((Number) order.get("req_id")).longValue());
                }
                e.setValue(resolve(e.getKey(), e.getValue(), order, req.get("parent_req_id") == null ? null : withValues(req), node, ciq));
            }
        }
        Properties p = new Properties();
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            p.setProperty(e.getKey(), e.getValue());
        }
        java.io.OutputStream os = new java.io.FileOutputStream(out);
        try {
            p.store(new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8),
                    source == null ? "no activity configuration" : "activity configuration " + source + ", step " + step);
        } finally {
            os.close();
        }
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("activity_config", source);
        o.put("keys", new ArrayList<String>(overrides.keySet()));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static void apply(Map<String, Object> section, Map<String, String> overrides) {
        for (String[] part : new String[][] {{"params", ""}, {"login", "login."}, {"grc", "grc."}}) {
            Object values = section.get(part[0]);
            if (values instanceof Map) {
                for (Map.Entry<String, Object> v : ((Map<String, Object>) values).entrySet()) {
                    overrides.put(part[1] + v.getKey(), v.getValue() == null ? "" : String.valueOf(v.getValue()));
                }
            }
        }
    }

    /** Node patterns: {@code *} any characters, {@code ?} one character, everything else literally. */
    static boolean glob(String pattern, String value) {
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            re.append(c == '*' ? ".*" : c == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        return value.matches(re.toString());
    }

    static String resolve(String key, String value, Map<String, Object> order, Map<String, Object> child, String node,
                          Map<String, Object> ciq) {
        java.util.regex.Matcher m = REF.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String ref = m.group(1).trim();
            String v;
            if (ref.startsWith("secret:") || "WORKDIR".equals(ref)) {
                v = m.group(0);   // resolved by the wrapper inside the step
            } else if ("node".equals(ref)) {
                v = node;
            } else if (ref.startsWith("order.")) {
                v = field(order, ref.substring(6));
            } else if (ref.startsWith("child.")) {
                v = child == null ? null : field(child, ref.substring(6));
            } else if (ref.startsWith("ciq.")) {
                Object c = ciq == null ? null : ciq.get(ref.substring(4));
                v = c == null ? null : String.valueOf(c);
            } else {
                throw new StoreException(key + ": unknown reference ${" + ref + "} (use order.X, child.X, node, ciq.X,"
                        + " secret:NAME or WORKDIR)");
            }
            if (v == null) {
                throw new StoreException(key + ": ${" + ref + "} has no value for this step");
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** order.X / child.X: id, folder and the request's columns (node_type, activity, cr_name, json_file, ...). */
    private static String field(Map<String, Object> request, String name) {
        Object v = "id".equals(name) || "order_no".equals(name) ? request.get("req_id") : request.get(name);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ciqResults(long orderId) throws SQLException {
        List<Map<String, Object>> rows = query("SELECT results FROM " + SCHEMA + ".request_step WHERE req_id = ?"
                + " AND step = 'CIQ_VALIDATION' AND status = 0 ORDER BY step_id DESC LIMIT 1", orderId);
        return rows.isEmpty() ? new LinkedHashMap<String, Object>()
                : (Map<String, Object>) SimpleJson.parse(String.valueOf(rows.get(0).get("results")));
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

    /** With {@code --if-stage}, the stage changes only while the request is in one of those stages (no error otherwise). */
    Map<String, Object> setStage(Map<String, String> opt) throws SQLException {
        long reqId = Long.parseLong(require(opt, "req-id"));
        List<String> ifStages = csv(opt.get("if-stage"));
        StringBuilder sql = new StringBuilder("UPDATE " + SCHEMA + ".request SET stage = ?,"
                + " cr_list = COALESCE(?, cr_list), json_file = COALESCE(?, json_file), updated_at = now()"
                + " WHERE req_id = ?");
        for (int i = 0; i < ifStages.size(); i++) {
            sql.append(i == 0 ? " AND stage IN (?" : ",?");
        }
        if (!ifStages.isEmpty()) {
            sql.append(')');
        }
        PreparedStatement ps = db.prepareStatement(sql.toString());
        try {
            ps.setString(1, require(opt, "stage"));
            ps.setString(2, opt.containsKey("cr-list") ? opt.get("cr-list").replace(" ", "") : null);
            ps.setString(3, emptyToNull(opt.get("json-file")));
            ps.setLong(4, reqId);
            for (int i = 0; i < ifStages.size(); i++) {
                ps.setString(5 + i, ifStages.get(i));
            }
            int updated = ps.executeUpdate();
            if (updated != 1 && ifStages.isEmpty()) {
                throw new StoreException("request " + reqId + " does not exist");
            }
            Map<String, Object> out = single("req_id", reqId);
            out.put("updated", updated == 1);
            return out;
        } finally {
            ps.close();
        }
    }

    // ------------------------------------------------------------------ claim-nodes / release-nodes

    /**
     * Claims nodes of a child for one execution, all or none, in one transaction: a node already claimed by another
     * execution makes the whole claim fail, so two executions never run the same node at the same time. A claim by
     * the same execution again (a restarted task) succeeds. {@code *} means every node of the child's MOP.
     */
    Map<String, Object> claimNodes(long reqId, String nodes, String execution) throws Exception {
        Map<String, Object> child = request(reqId);
        if (!"CHILD".equals(child.get("kind"))) {
            throw new StoreException("request " + reqId + " is not a child order");
        }
        List<String> mopNodes = mopNodes(reqId);
        if (mopNodes.isEmpty()) {
            throw new StoreException("child " + reqId + " has no generated MOP - run its MOP generation first");
        }
        List<String> wanted = "*".equals(nodes.trim()) ? mopNodes : csv(nodes);
        if (wanted.contains("*")) {
            wanted = mopNodes;
        }
        for (String node : wanted) {
            if (!mopNodes.contains(node)) {
                throw new StoreException("node " + node + " is not in the MOP of child " + reqId + " (its nodes: "
                        + join(mopNodes) + ")");
            }
        }
        if (wanted.isEmpty()) {
            throw new StoreException("no node chosen for child " + reqId);
        }
        db.setAutoCommit(false);
        try {
            List<String> busy = new ArrayList<String>();
            PreparedStatement ps = db.prepareStatement("INSERT INTO " + SCHEMA + ".node_lock (req_id, node_name,"
                    + " kestra_execution_id) VALUES (?,?,?) ON CONFLICT (req_id, node_name) DO UPDATE"
                    + " SET claimed_at = now() WHERE " + SCHEMA + ".node_lock.kestra_execution_id = EXCLUDED.kestra_execution_id");
            try {
                for (String node : wanted) {
                    ps.setLong(1, reqId);
                    ps.setString(2, node);
                    ps.setString(3, execution);
                    if (ps.executeUpdate() == 0) {
                        busy.add(node);
                    }
                }
            } finally {
                ps.close();
            }
            if (!busy.isEmpty()) {
                db.rollback();
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> l : locks(reqId)) {
                    if (busy.contains(l.get("node_name"))) {
                        sb.append(sb.length() == 0 ? "" : ", ").append(l.get("node_name")).append(" (execution ")
                          .append(l.get("kestra_execution_id")).append(')');
                    }
                }
                throw new StoreException("child " + reqId + ": node(s) already being executed by another execution: " + sb
                        + " - wait until it ends, or choose other nodes");
            }
            db.commit();
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("nodes", wanted);
        out.put("node_count", wanted.size());
        out.put("child_id", String.valueOf(reqId));
        out.put("order_id", String.valueOf(child.get("parent_req_id")));
        for (String k : new String[] {"cr_name", "node_type", "activity", "node_version", "json_file", "folder"}) {
            out.put(k, child.get(k));
        }
        return out;
    }

    Map<String, Object> releaseNodes(long reqId, String execution, boolean all) throws SQLException {
        if (!all && emptyToNull(execution) == null) {
            throw new StoreException("--execution (or --all) is required");
        }
        PreparedStatement ps = db.prepareStatement("DELETE FROM " + SCHEMA + ".node_lock WHERE req_id = ?"
                + (all ? "" : " AND kestra_execution_id = ?"));
        try {
            ps.setLong(1, reqId);
            if (!all) {
                ps.setString(2, execution);
            }
            return single("released", ps.executeUpdate());
        } finally {
            ps.close();
        }
    }

    private List<Map<String, Object>> locks(long reqId) throws SQLException {
        return query("SELECT node_name, kestra_execution_id, claimed_at FROM " + SCHEMA + ".node_lock WHERE req_id = ?"
                + " ORDER BY node_name", reqId);
    }

    /** The nodes (zones) of the child's MOP: ACTIVITY_CR_NODE_NAMES of its latest successful MOP_GENERATION. */
    List<String> mopNodes(long reqId) throws SQLException {
        List<Map<String, Object>> rows = query("SELECT results ->> 'ACTIVITY_CR_NODE_NAMES' AS nodes FROM " + SCHEMA
                + ".request_step WHERE req_id = ? AND step = 'MOP_GENERATION' AND status = 0 ORDER BY step_id DESC LIMIT 1",
                reqId);
        return rows.isEmpty() ? new ArrayList<String>() : csv((String) rows.get(0).get("nodes"));
    }

    // ------------------------------------------------------------------ locked

    /**
     * Runs a command while holding the PostgreSQL advisory lock on {@code key}: across all executions only one
     * command per key runs at a time (e.g. the summary report of one child). The lock goes with the connection, so
     * it is released even when this JVM dies.
     */
    int locked(String key, List<String> command) throws Exception {
        if (command.isEmpty()) {
            throw new StoreException("locked: no command after --");
        }
        PreparedStatement ps = db.prepareStatement("SELECT pg_advisory_lock(hashtext(?))");
        try {
            ps.setString(1, key);
            ps.execute();
        } finally {
            ps.close();
        }
        try {
            return new ProcessBuilder(command).inheritIO().start().waitFor();
        } finally {
            PreparedStatement un = db.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))");
            try {
                un.setString(1, key);
                un.execute();
            } finally {
                un.close();
            }
        }
    }

    // ------------------------------------------------------------------ child-status

    /**
     * The latest MOP_EXECUTION per node of a child. {@code nodes}, {@code failed_nodes}, {@code status}: the given
     * nodes (default: every executed node). {@code child_stage}: the whole child - EXECUTING while a node is claimed,
     * else FAILED when a node's latest execution failed, SUCCESS when every MOP node succeeded, PARTIAL when some MOP
     * nodes were not executed yet, MOP_GENERATED when none was. With {@code --update-stage} it is stored, computed and
     * written in one transaction under a row lock, so executions ending together cannot overwrite each other.
     */
    Map<String, Object> childStatus(long reqId, String onlyNodes, boolean updateStage) throws SQLException {
        db.setAutoCommit(false);
        try {
            query("SELECT req_id FROM " + SCHEMA + ".request WHERE req_id = ? FOR UPDATE", reqId);
            List<String> filter = csv(onlyNodes);
            List<String> nodes = new ArrayList<String>();
            List<String> failed = new ArrayList<String>();
            Map<String, Boolean> latestOk = new LinkedHashMap<String, Boolean>();
            for (Map<String, Object> row : latestPerNode(reqId, "MOP_EXECUTION", "NODE_PIPELINE")) {
                String node = (String) row.get("node_name");
                boolean ok = ((Number) row.get("status")).intValue() == 0;
                latestOk.put(node, ok);
                if (filter.isEmpty() || filter.contains(node)) {
                    nodes.add(node);
                    if (!ok) {
                        failed.add(node);
                    }
                }
            }
            List<String> mopNodes = mopNodes(reqId);
            String stage;
            if (!locks(reqId).isEmpty()) {
                stage = "EXECUTING";
            } else if (latestOk.containsValue(Boolean.FALSE)) {
                stage = "FAILED";
            } else if (latestOk.isEmpty()) {
                stage = "MOP_GENERATED";
            } else if (latestOk.keySet().containsAll(mopNodes)) {
                stage = "SUCCESS";
            } else {
                stage = "PARTIAL";
            }
            if (updateStage && !mopNodes.isEmpty()) {
                PreparedStatement ps = db.prepareStatement("UPDATE " + SCHEMA + ".request SET stage = ?, updated_at = now()"
                        + " WHERE req_id = ?");
                try {
                    ps.setString(1, stage);
                    ps.setLong(2, reqId);
                    ps.executeUpdate();
                } finally {
                    ps.close();
                }
            }
            db.commit();
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("nodes", nodes);
            out.put("failed_nodes", failed);
            out.put("failed_count", failed.size());
            out.put("status", failed.isEmpty() ? "SUCCESS" : "FAILED");
            out.put("child_stage", stage);
            return out;
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** The latest row per node among the given steps. */
    private List<Map<String, Object>> latestPerNode(long reqId, String... steps) throws SQLException {
        StringBuilder in = new StringBuilder();
        Object[] params = new Object[steps.length + 1];
        params[0] = reqId;
        for (int i = 0; i < steps.length; i++) {
            in.append(i == 0 ? "?" : ",?");
            params[i + 1] = steps[i];
        }
        return query("SELECT DISTINCT ON (node_name) node_name, step, status, results ->> 'ROLLBACK_ENABLED' AS rollback_enabled,"
                + " kestra_execution_id, recorded_at FROM " + SCHEMA + ".request_step"
                + " WHERE req_id = ? AND step IN (" + in + ") AND node_name IS NOT NULL ORDER BY node_name, step_id DESC", params);
    }

    // ------------------------------------------------------------------ pipelines (compiled by the gateway at publish)

    /**
     * The pipeline flows the order's pinned process version attaches to {@code flow}: column {@code compiled} of
     * {@code process_version}, {@code {"<flow>": {"node": "<flow id>", "after_nodes": "<flow id>"}}}. Empty values
     * when the version has none (the flow then runs its own default steps).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> pipelines(long reqId, String flow) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("node", "");
        out.put("after_nodes", "");
        Map<String, Object> req = request(reqId);
        Map<String, Object> order = req.get("parent_req_id") == null ? req : request(((Number) req.get("parent_req_id")).longValue());
        List<Map<String, Object>> col = query("SELECT 1 FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'process_version' AND column_name = 'compiled'", SCHEMA);
        if (col.isEmpty() || order.get("process_id") == null || order.get("process_version") == null) {
            return out;
        }
        List<Map<String, Object>> rows = query("SELECT compiled::text AS compiled FROM " + SCHEMA + ".process_version"
                + " WHERE process_id = ? AND version = ?", order.get("process_id"), order.get("process_version"));
        if (rows.isEmpty() || rows.get(0).get("compiled") == null) {
            return out;
        }
        Object compiled = SimpleJson.parse(String.valueOf(rows.get(0).get("compiled")));
        Object hooks = compiled instanceof Map ? ((Map<String, Object>) compiled).get(flow) : null;
        if (hooks instanceof Map) {
            for (String hook : new String[] {"node", "after_nodes"}) {
                Object id = ((Map<String, Object>) hooks).get(hook);
                if (id != null) {
                    out.put(hook, String.valueOf(id));
                }
            }
        }
        return out;
    }

    /**
     * What a pipeline's references read: {@code order} and {@code child} (request columns as text, the child with
     * its collected lists as comma-separated text), {@code ciq} (the order's validation results), {@code activity}
     * ({@code node_type}, {@code sub_activity}) and, with {@code nodes}, {@code run}: nodes, failed_nodes,
     * succeeded_nodes (comma-separated) and failed_count of those nodes.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> pipelineContext(long reqId, String nodes) throws SQLException {
        Map<String, Object> req = request(reqId);
        boolean isChild = req.get("parent_req_id") != null;
        Map<String, Object> order = isChild ? request(((Number) req.get("parent_req_id")).longValue()) : req;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("order", texts(order));
        out.put("child", isChild ? texts(withValues(req)) : new LinkedHashMap<String, Object>());
        out.put("ciq", ciqResults(((Number) order.get("req_id")).longValue()));
        Map<String, Object> activity = new LinkedHashMap<String, Object>();
        activity.put("node_type", text(order.get("node_type")));
        activity.put("sub_activity", text(order.get("activity")));
        out.put("activity", activity);
        Map<String, Object> run = new LinkedHashMap<String, Object>();
        if (nodes != null && isChild) {
            Map<String, Object> status = childStatus(reqId, nodes, false);
            List<String> ran = (List<String>) status.get("nodes");
            List<String> failed = (List<String>) status.get("failed_nodes");
            List<String> ok = new ArrayList<String>(ran);
            ok.removeAll(failed);
            run.put("nodes", join(csv(nodes)));
            run.put("failed_nodes", join(failed));
            run.put("succeeded_nodes", join(ok));
            run.put("failed_count", String.valueOf(failed.size()));
        }
        out.put("run", run);
        return out;
    }

    /** The request with each of its collected lists (request_value) as one comma-separated field. */
    Map<String, Object> withValues(Map<String, Object> req) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<String, Object>(req);
        Map<String, List<String>> lists = new LinkedHashMap<String, List<String>>();
        for (Map<String, Object> v : query("SELECT name, value FROM " + SCHEMA + ".request_value WHERE req_id = ?"
                + " ORDER BY value_id", req.get("req_id"))) {
            List<String> l = lists.get((String) v.get("name"));
            if (l == null) {
                l = new ArrayList<String>();
                lists.put((String) v.get("name"), l);
            }
            if (!l.contains((String) v.get("value"))) {
                l.add((String) v.get("value"));
            }
        }
        for (Map.Entry<String, List<String>> e : lists.entrySet()) {
            if (!out.containsKey(e.getKey())) {
                out.put(e.getKey(), join(e.getValue()));
            }
        }
        return out;
    }

    /** Adds values (comma-separated) to a child's list; concurrent pipelines each insert their own rows. */
    Map<String, Object> collect(long reqId, String name, String values, String node, String execution) throws SQLException {
        if (!name.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new StoreException("list name must be upper-case letters, digits and _ (got '" + name + "')");
        }
        request(reqId);
        List<String> added = csv(values);
        PreparedStatement ps = db.prepareStatement("INSERT INTO " + SCHEMA + ".request_value (req_id, name, value,"
                + " node_name, kestra_execution_id) VALUES (?,?,?,?,?)");
        try {
            for (String v : added) {
                ps.setLong(1, reqId);
                ps.setString(2, name);
                ps.setString(3, v);
                ps.setString(4, emptyToNull(node));
                ps.setString(5, emptyToNull(execution));
                ps.executeUpdate();
            }
        } finally {
            ps.close();
        }
        Map<String, Object> out = single("added", added.size());
        out.put("name", name);
        return out;
    }

    /** A step row without an NEI result (pipeline outcome, decision, ...). */
    Map<String, Object> record(Map<String, String> opt) throws Exception {
        long reqId = Long.parseLong(require(opt, "req-id"));
        String step = require(opt, "step");
        String node = emptyToNull(opt.get("node"));
        String text = require(opt, "status-text").toUpperCase();
        int status = emptyToNull(opt.get("status")) != null ? Integer.parseInt(opt.get("status").trim())
                : "SUCCESS".equals(text) || "DECIDED".equals(text) ? 0 : "FAILED".equals(text) || "KILLED".equals(text) ? 9 : 1;
        Map<String, Object> req = request(reqId);
        if (emptyToNull(opt.get("unless-since")) != null) {
            Timestamp since = new Timestamp(java.time.OffsetDateTime.parse(opt.get("unless-since").trim()).toInstant().toEpochMilli());
            List<Map<String, Object>> rows = query("SELECT step_id FROM " + SCHEMA + ".request_step WHERE req_id = ? AND step = ?"
                    + " AND node_name IS NOT DISTINCT FROM ? AND recorded_at >= ?", reqId, step, node, since);
            if (!rows.isEmpty()) {
                Map<String, Object> out = single("step_id", rows.get(0).get("step_id"));
                out.put("recorded", false);
                return out;
            }
        }
        PreparedStatement ps = db.prepareStatement("INSERT INTO " + SCHEMA + ".request_step (req_id, parent_req_id,"
                + " kestra_execution_id, step, node_name, status, status_text, message, request, results, started_at,"
                + " duration_ms) VALUES (?,?,?,?,?,?,?,?,CAST(? AS JSONB),CAST(? AS JSONB),now(),0) RETURNING step_id");
        try {
            ps.setLong(1, reqId);
            ps.setObject(2, req.get("parent_req_id"));
            ps.setString(3, emptyToNull(opt.get("execution")));
            ps.setString(4, step);
            ps.setString(5, node);
            ps.setInt(6, status);
            ps.setString(7, text);
            ps.setString(8, opt.get("message"));
            ps.setString(9, json(opt.get("request-json")));
            ps.setString(10, json(opt.get("results-json")));
            ResultSet rs = ps.executeQuery();
            rs.next();
            Map<String, Object> out = single("step_id", rs.getLong(1));
            out.put("recorded", true);
            return out;
        } finally {
            ps.close();
        }
    }

    /** JSON text checked by parsing it ({@code {}} when empty), so a bad value fails here and not in PostgreSQL. */
    static String json(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "{}";
        }
        return SimpleJson.writeCompact(SimpleJson.parse(s));
    }

    private static Map<String, Object> texts(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            out.put(e.getKey(), text(e.getValue()));
        }
        out.put("id", text(row.get("req_id")));
        return out;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v);
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
        order.put("process_id", o.get("process_id"));
        order.put("process_version", o.get("process_version"));
        order.put("activity_id", o.get("activity_id"));
        order.put("activity_version", o.get("activity_version"));
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
        Map<String, Object> childrenById = new LinkedHashMap<String, Object>();
        List<Object> childOptions = new ArrayList<Object>();
        List<Object> rollbackOptions = new ArrayList<Object>();
        for (Map<String, Object> c : query("SELECT * FROM " + SCHEMA + ".request WHERE parent_req_id = ?"
                + " ORDER BY req_id", orderId)) {
            long childId = ((Number) c.get("req_id")).longValue();
            List<String> mopNodes = mopNodes(childId);
            Map<String, String> runningIn = new LinkedHashMap<String, String>();
            for (Map<String, Object> l : locks(childId)) {
                runningIn.put((String) l.get("node_name"), (String) l.get("kestra_execution_id"));
            }
            List<Object> nodes = new ArrayList<Object>();
            List<Object> childRollbackOptions = new ArrayList<Object>();
            Map<String, Object> lastRollback = new LinkedHashMap<String, Object>();
            for (Map<String, Object> r : latestPerNode(childId, "ROLLBACK")) {
                lastRollback.put((String) r.get("node_name"), r.get("status"));
            }
            Map<String, Object> rollbackEnabled = new LinkedHashMap<String, Object>();
            for (Map<String, Object> r : latestPerNode(childId, "MOP_EXECUTION")) {
                rollbackEnabled.put((String) r.get("node_name"), r.get("rollback_enabled"));
            }
            for (Map<String, Object> n : latestPerNode(childId, "MOP_EXECUTION", "NODE_PIPELINE")) {
                String node = (String) n.get("node_name");
                boolean enabled = "TRUE".equalsIgnoreCase(String.valueOf(rollbackEnabled.get(node)));
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
                    childRollbackOptions.add(opt);
                }
            }
            List<Object> nodeOptions = new ArrayList<Object>();
            if (!mopNodes.isEmpty()) {
                nodeOptions.add(option("*", "All nodes (" + mopNodes.size() + "): " + join(mopNodes)));
            }
            for (String node : mopNodes) {
                Map<String, Object> last = null;
                for (Object entry : nodes) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> n = (Map<String, Object>) entry;
                    if (node.equals(n.get("node"))) {
                        last = n;
                    }
                }
                if (last == null) {
                    last = new LinkedHashMap<String, Object>();
                    last.put("node", node);
                    last.put("status", "NOT_EXECUTED");
                    last.put("rollback_enabled", false);
                    last.put("rolled_back", false);
                    nodes.add(last);
                }
                if (runningIn.containsKey(node)) {
                    last.put("status", "RUNNING");
                    last.put("running_execution_id", runningIn.get(node));
                }
                nodeOptions.add(option(node, node + " | " + last.get("status")));
            }
            Map<String, Object> run = new LinkedHashMap<String, Object>();
            run.put("child_id", String.valueOf(childId));
            run.put("cr_name", c.get("cr_name"));
            run.put("execution_id", c.get("kestra_execution_id"));
            run.put("state", c.get("stage"));
            run.put("at", at(c.get("updated_at")));
            run.put("nodes", nodes);
            run.put("mop_nodes", mopNodes);
            run.put("node_options", nodeOptions);
            run.put("rollback_options", childRollbackOptions);
            childrenById.put(String.valueOf(childId), run);
            if (!mopNodes.isEmpty()) {
                childOptions.add(option(String.valueOf(childId), "child " + childId + " (" + c.get("cr_name") + ") | "
                        + c.get("stage") + " | " + join(mopNodes)));
            }
            @SuppressWarnings("unchecked")
            List<Object> runs = (List<Object>) children.get(c.get("cr_name"));
            if (runs == null) {
                runs = new ArrayList<Object>();
                children.put((String) c.get("cr_name"), runs);
            }
            runs.add(run);
        }
        order.put("children", children);
        order.put("children_by_id", childrenById);
        order.put("child_options", childOptions);
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

    /** Comma-separated values, trimmed, without empty ones and duplicates, in order. */
    static List<String> csv(String s) {
        List<String> out = new ArrayList<String>();
        if (s != null) {
            for (String v : s.split(",")) {
                if (!v.trim().isEmpty() && !out.contains(v.trim())) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            sb.append(sb.length() == 0 ? "" : ",").append(v);
        }
        return sb.toString();
    }

    private static Map<String, Object> option(String value, String label) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("value", value);
        m.put("label", label);
        return m;
    }

    /** The command after {@code --} (locked). */
    static List<String> commandAfterDashes(String[] args) {
        List<String> out = new ArrayList<String>();
        boolean after = false;
        for (String a : args) {
            if (after) {
                out.add(a);
            } else if ("--".equals(a)) {
                after = true;
            }
        }
        return out;
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

    /** {@code --name value} pairs after the command, up to a lone {@code --}. */
    static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                break;
            }
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
