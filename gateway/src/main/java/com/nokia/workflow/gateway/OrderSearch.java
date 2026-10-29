package com.nokia.workflow.gateway;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Orders and child orders from the request store, for the Orders screen: one id (an order with its children, or one
 * child) or the newest ones, optionally only the children waiting for their MOP execution (MOP generated, some
 * nodes not executed yet, or stopped).
 */
final class OrderSearch {

    private static final int LIMIT = 200;

    private final Db db;
    private final String schema;

    OrderSearch(Db db, String schema) {
        this.db = db;
        this.schema = schema;
    }

    /** The executions working on a child now: its MOP generation (stage RUNNING) and those holding its node locks. */
    List<String> running(long orderId, long childId) throws SQLException {
        return db.with(c -> VersionedStore.all(c, "SELECT kestra_execution_id AS id FROM " + schema + ".request"
                        + " WHERE req_id = ? AND parent_req_id = ? AND stage = 'RUNNING' AND kestra_execution_id IS NOT NULL"
                        + " UNION SELECT DISTINCT kestra_execution_id FROM " + schema + ".node_lock WHERE req_id = ?"
                        + " AND EXISTS (SELECT 1 FROM " + schema + ".request WHERE req_id = ? AND parent_req_id = ?)",
                childId, orderId, childId, childId, orderId))
                .stream().map(r -> String.valueOf(r.get("id"))).toList();
    }

    List<Map<String, Object>> find(Long id, boolean waitingOnly) throws SQLException {
        String r = schema + ".request";
        String waiting = "(r.kind = 'CHILD' AND r.stage IN ('MOP_GENERATED', 'PARTIAL', 'STOPPED'))";
        return db.with(c -> VersionedStore.all(c, "SELECT r.req_id, r.parent_req_id, r.kind, r.cr_name, r.stage, r.node_type,"
                + " r.activity, r.kestra_execution_id AS execution_id, r.created_at::text AS created_at, r.updated_at::text AS updated_at, "
                + waiting + " AS waiting FROM " + r + " r WHERE (CAST(? AS BIGINT) IS NULL OR r.req_id = ? OR r.parent_req_id = ?)"
                + (waitingOnly ? " AND " + waiting : "") + " ORDER BY r.req_id DESC LIMIT " + LIMIT, id, id, id));
    }
}
