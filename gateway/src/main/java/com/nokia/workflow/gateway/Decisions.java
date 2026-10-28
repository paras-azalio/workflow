package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decisions a pipeline is waiting for (DECISION widgets). A pipeline records an open decision as step DECISION,
 * status WAITING, with the Kestra execution that pauses for it; it is closed by a later DECIDED row of the same
 * execution, widget, node and item. A MANUAL decision is taken here: the paused execution is resumed with the
 * choice, a comment and who decided. A REPLY decision is only shown: it waits for the mail reply.
 */
final class Decisions {

    private static final Set<String> FINISHED = Set.of("SUCCESS", "FAILED", "KILLED", "WARNING", "CANCELLED");

    private final Db db;
    private final String schema;
    private final Kestra kestra;
    private final UserStore audit;

    Decisions(Db db, String schema, Kestra kestra, UserStore audit) {
        this.db = db;
        this.schema = schema;
        this.kestra = kestra;
        this.audit = audit;
    }

    /** The open decisions of an order (all its children) or of one child. */
    List<Map<String, Object>> open(long orderId, Long childId) throws Exception {
        List<Map<String, Object>> rows = rows(childId == null ? "(w.req_id = ? OR w.parent_req_id = ?)" : "w.req_id = ? AND w.parent_req_id = ?",
                childId == null ? new Object[] {orderId, orderId} : new Object[] {childId, orderId});
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String execution = (String) r.get("kestra_execution_id");
            String state = execution == null ? null : kestra.executionState(execution);
            if (state == null || FINISHED.contains(state)) {
                continue;   // the pipeline ended (killed, failed) without recording the decision
            }
            out.add(view(r, state));
        }
        return out;
    }

    /** Takes a MANUAL decision: {decision, comment}. */
    @SuppressWarnings("unchecked")
    Map<String, Object> decide(long orderId, long decisionId, Map<String, Object> body, UserStore.User user, String ip) throws Exception {
        if (!user.role().atLeast(Role.EXECUTOR)) {
            throw new UserStore.Rejected(403, "Only an order executor or an admin can take a decision.");
        }
        List<Map<String, Object>> rows = rows("w.step_id = ? AND (w.req_id = ? OR w.parent_req_id = ?)",
                new Object[] {decisionId, orderId, orderId});
        if (rows.isEmpty()) {
            throw new UserStore.Rejected(409, "Decision %d of order %d is not open (taken already, or not of this order).".formatted(decisionId, orderId));
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> about = about(r);
        if (!"MANUAL".equals(about.get("mode"))) {
            throw new UserStore.Rejected(409, "This decision is taken from a mail reply, not here.");
        }
        String decision = Gateway.str(body.get("decision"));
        List<Object> options = about.get("options") instanceof List<?> l ? (List<Object>) l : List.of();
        if (decision == null || !options.contains(decision)) {
            throw new UserStore.Rejected(400, "Choose one of %s.".formatted(options));
        }
        String comment = Gateway.str(body.get("comment"));
        if (comment != null && comment.length() > 2000) {
            throw new UserStore.Rejected(400, "The comment is longer than 2000 characters.");
        }
        String execution = (String) r.get("kestra_execution_id");
        String state = kestra.executionState(execution);
        if (!"PAUSED".equals(state)) {
            throw new UserStore.Rejected(409, "The pipeline is not waiting for this decision (execution %s is %s).".formatted(execution, state));
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("decision", decision);
        inputs.put("comment", comment == null ? "" : comment);
        inputs.put("decided_by", user.username());
        try {
            kestra.resume(execution, inputs);
        } catch (Kestra.KestraError e) {
            if (e.status == 409) {
                throw new UserStore.Rejected(409, "Someone else took this decision a moment ago.");
            }
            throw e;
        }
        audit.audit(user.username(), user.role(), "DECISION", "POST", null, 200, ip,
                "order %d decision %d (%s, node %s): %s".formatted(orderId, decisionId, about.get("widget"), r.get("node_name"), decision));
        return Map.of("message", "Decision %s sent; the pipeline continues.".formatted(decision), "execution_id", execution);
    }

    private List<Map<String, Object>> rows(String where, Object[] params) throws SQLException {
        String t = schema + ".request_step";
        return db.with(c -> VersionedStore.all(c, "SELECT w.step_id, w.req_id, w.node_name, w.kestra_execution_id, w.message,"
                + " w.request::text AS about, w.recorded_at FROM " + t + " w WHERE w.step = 'DECISION' AND w.status_text = 'WAITING'"
                + " AND " + where + " AND NOT EXISTS (SELECT 1 FROM " + t + " d WHERE d.step = 'DECISION'"
                + " AND d.status_text = 'DECIDED' AND d.req_id = w.req_id AND d.step_id > w.step_id"
                + " AND d.kestra_execution_id IS NOT DISTINCT FROM w.kestra_execution_id"
                + " AND d.node_name IS NOT DISTINCT FROM w.node_name"
                + " AND d.request ->> 'widget' IS NOT DISTINCT FROM w.request ->> 'widget'"
                + " AND d.request ->> 'item' IS NOT DISTINCT FROM w.request ->> 'item') ORDER BY w.step_id", params));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> about(Map<String, Object> r) {
        Object a = r.get("about") == null ? null : SimpleJson.parse(String.valueOf(r.get("about")));
        return a instanceof Map ? (Map<String, Object>) a : Map.of();
    }

    private static Map<String, Object> view(Map<String, Object> r, String state) {
        Map<String, Object> about = about(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision_id", r.get("step_id"));
        m.put("child_id", String.valueOf(r.get("req_id")));
        m.put("node", r.get("node_name"));
        m.put("widget", about.get("widget"));
        m.put("mode", about.get("mode"));
        m.put("question", about.get("question") == null ? r.get("message") : about.get("question"));
        m.put("options", about.get("options"));
        m.put("item", about.get("item"));
        m.put("timeout", about.get("timeout"));
        m.put("waiting_since", String.valueOf(r.get("recorded_at")));
        m.put("execution_id", r.get("kestra_execution_id"));
        m.put("can_decide", "MANUAL".equals(about.get("mode")) && "PAUSED".equals(state));
        return m;
    }
}
