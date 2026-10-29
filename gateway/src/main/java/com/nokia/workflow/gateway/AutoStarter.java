package com.nokia.workflow.gateway;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts the steps whose definition says {@code auto_start} as soon as they are offered, without a person: every few
 * seconds it looks at the orders the request store changed lately, evaluates their pinned process version like the
 * Order tab does, and starts each offered automatic step once - or once per value of its {@code for_each} input (e.g.
 * once per CR). Table {@code auto_start} remembers every start, also a person's start of such a step, so nothing is
 * started twice; {@code max_running} holds back the rest until earlier starts have finished.
 */
final class AutoStarter {

    private static final Set<String> FINISHED = Set.of("SUCCESS", "FAILED", "KILLED", "WARNING", "CANCELLED");
    private static final int MAX_ATTEMPTS = 3;
    static final UserStore.User AUTO = new UserStore.User(0, "auto", "Automatic start", Role.EXECUTOR, true);

    private final Db db;
    private final String schema;
    private final Kestra kestra;
    private final ProcessStore processes;
    private final UserStore audit;
    private Steps steps;

    AutoStarter(Db db, String schema, Kestra kestra, ProcessStore processes, UserStore audit) {
        this.db = db;
        this.schema = schema;
        this.kestra = kestra;
        this.processes = processes;
        this.audit = audit;
    }

    void ensureSchema() throws SQLException {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + t("auto_start") + " ("
                        + " auto_id BIGSERIAL PRIMARY KEY,"
                        + " order_id BIGINT NOT NULL, child_id BIGINT NOT NULL DEFAULT 0,"
                        + " step_id VARCHAR(64) NOT NULL, item TEXT NOT NULL DEFAULT '',"
                        + " process_version INT, state VARCHAR(16) NOT NULL, started_by VARCHAR(256),"
                        + " execution_id VARCHAR(64), message TEXT, attempts INT NOT NULL DEFAULT 0,"
                        + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " UNIQUE (order_id, child_id, step_id, item))");
            }
            return null;
        });
    }

    /** Checks every {@code seconds}; orders changed within {@code windowMinutes} are looked at. */
    void start(Steps steps, int seconds, int windowMinutes) {
        this.steps = steps;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "auto-start");
            th.setDaemon(true);
            return th;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick(windowMinutes);
            } catch (Exception e) {
                System.out.println("gateway: automatic start check failed: " + e);
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    /** Remembers a person's start of an automatic step, so the gateway does not start the same one again. */
    void startedByPerson(long orderId, Long childId, ProcessDefinition.Step step, String item, int version, String user,
                         Object executionId) throws SQLException {
        db.with(c -> VersionedStore.update(c, "INSERT INTO " + t("auto_start") + " (order_id, child_id, step_id, item,"
                + " process_version, state, started_by, execution_id) VALUES (?, ?, ?, ?, ?, 'STARTED', ?, ?)"
                + " ON CONFLICT DO NOTHING", orderId, childId == null ? 0L : childId, step.id(), item == null ? "" : item,
                version, user, executionId == null ? null : String.valueOf(executionId)));
    }

    /** The automatic starts of an order (all its children) or of one child, for the Order tab. */
    List<Map<String, Object>> list(long orderId, Long childId) throws SQLException {
        return db.with(c -> VersionedStore.all(c, "SELECT step_id, child_id, item, state, started_by, execution_id, message,"
                + " attempts, created_at::text AS created_at FROM " + t("auto_start") + " WHERE order_id = ?"
                + (childId == null ? "" : " AND child_id = " + childId) + " AND started_by = ? ORDER BY auto_id",
                orderId, AUTO.username()));
    }

    void tick(int windowMinutes) throws Exception {
        refreshRunning();
        String r = t("request");
        List<Map<String, Object>> orders = db.with(c -> VersionedStore.all(c,
                "SELECT DISTINCT COALESCE(parent_req_id, req_id) AS order_id FROM " + r
                        + " WHERE updated_at > now() - make_interval(mins => ?)"
                        + " UNION SELECT order_id FROM " + t("auto_start") + " WHERE state = 'FAILED' AND attempts < ?",
                windowMinutes, MAX_ATTEMPTS));
        for (Map<String, Object> o : orders) {
            long orderId = ((Number) o.get("order_id")).longValue();
            try {
                order(orderId);
            } catch (UserStore.Rejected e) {
                // not in the registry (yet) or not continuable: looked at again while it keeps changing
            } catch (Exception e) {
                System.out.println("gateway: automatic start check of order " + orderId + " failed: " + e);
            }
        }
    }

    private void order(long orderId) throws Exception {
        ProcessStore.Version v = processes.pinnedVersion(orderId);
        if (v == null) {
            return;
        }
        ProcessDefinition def = ProcessDefinition.parse(v.definition());
        boolean orderSteps = def.steps.stream().anyMatch(s -> s.autoStart() != null && s.scope() == ProcessDefinition.Scope.ORDER);
        boolean childSteps = def.steps.stream().anyMatch(s -> s.autoStart() != null && s.scope() == ProcessDefinition.Scope.CHILD);
        if (!orderSteps && !childSteps) {
            return;
        }
        Steps.Context ctx = steps.context(String.valueOf(orderId), null);
        if (!ctx.continuable()) {
            return;
        }
        if (orderSteps) {
            context(orderId, null, ctx);
        }
        if (childSteps && ctx.order().get("children_by_id") instanceof Map<?, ?> children) {
            for (Object childId : children.keySet()) {
                context(orderId, Long.valueOf(String.valueOf(childId)), steps.context(String.valueOf(orderId), String.valueOf(childId)));
            }
        }
    }

    private void context(long orderId, Long childId, Steps.Context ctx) throws Exception {
        for (ProcessDefinition.Step step : Steps.available(ctx)) {
            ProcessDefinition.AutoStart auto = step.autoStart();
            if (auto == null) {
                continue;
            }
            List<String> items = new ArrayList<>();
            if (auto.forEach() == null) {
                items.add("");
            } else {
                ProcessDefinition.Input in = step.inputs().stream().filter(i -> i.name().equals(auto.forEach())).findFirst().orElseThrow();
                Steps.options(in, ctx.order(), ctx.child()).forEach(opt -> items.add(String.valueOf(opt.get("value"))));
            }
            for (String item : items) {
                if (auto.maxRunning() > 0 && running(orderId, childId, step.id()) >= auto.maxRunning()) {
                    break;
                }
                Long id = claim(orderId, childId, step.id(), item, ctx.version().version());
                if (id != null) {
                    startOne(id, orderId, childId, ctx, step, item);
                }
            }
        }
    }

    private void startOne(long autoId, long orderId, Long childId, Steps.Context ctx, ProcessDefinition.Step step, String item) throws SQLException {
        String what = "order " + orderId + (childId == null ? "" : " child " + childId) + ": " + step.id()
                + (item.isEmpty() ? "" : " for " + item);
        try {
            Map<String, Object> inputs = new LinkedHashMap<>();
            if (step.autoStart().forEach() != null) {
                inputs.put(step.autoStart().forEach(), item);
            }
            Map<String, Object> started = steps.startAutomatically(ctx, step, Map.of("inputs", inputs), AUTO);
            db.with(c -> VersionedStore.update(c, "UPDATE " + t("auto_start") + " SET state = 'STARTED', execution_id = ?,"
                    + " message = NULL, updated_at = now() WHERE auto_id = ?", String.valueOf(started.get("execution_id")), autoId));
            System.out.println("gateway: started automatically, " + what + " -> " + started.get("execution_id"));
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            db.with(c -> VersionedStore.update(c, "UPDATE " + t("auto_start") + " SET state = 'FAILED', message = ?,"
                    + " updated_at = now() WHERE auto_id = ?", message, autoId));
            audit.audit(AUTO.username(), AUTO.role(), "AUTO_START_FAILED", null, null, 500, null, what + ": " + message);
            System.out.println("gateway: automatic start failed, " + what + ": " + message);
        }
    }

    /** The row id when this start is taken now: a new row, or a failed one with attempts left. */
    private Long claim(long orderId, Long childId, String stepId, String item, int version) throws SQLException {
        long child = childId == null ? 0L : childId;
        return db.with(c -> {
            Map<String, Object> row = VersionedStore.one(c, "INSERT INTO " + t("auto_start") + " (order_id, child_id, step_id, item,"
                    + " process_version, state, started_by, attempts) VALUES (?, ?, ?, ?, ?, 'STARTING', ?, 1)"
                    + " ON CONFLICT DO NOTHING RETURNING auto_id", orderId, child, stepId, item, version, AUTO.username());
            if (row == null) {
                row = VersionedStore.one(c, "UPDATE " + t("auto_start") + " SET state = 'STARTING', attempts = attempts + 1,"
                        + " updated_at = now() WHERE order_id = ? AND child_id = ? AND step_id = ? AND item = ?"
                        + " AND state = 'FAILED' AND attempts < ? RETURNING auto_id", orderId, child, stepId, item, MAX_ATTEMPTS);
            }
            return row == null ? null : ((Number) row.get("auto_id")).longValue();
        });
    }

    private long running(long orderId, Long childId, String stepId) throws SQLException {
        Map<String, Object> row = db.with(c -> VersionedStore.one(c, "SELECT count(*) AS n FROM " + t("auto_start")
                + " WHERE order_id = ? AND child_id = ? AND step_id = ? AND state IN ('STARTING', 'STARTED')",
                orderId, childId == null ? 0L : childId, stepId));
        return row == null ? 0 : ((Number) row.get("n")).longValue();
    }

    /** Started executions that have ended become DONE (with their final state), which frees their max_running place. */
    private void refreshRunning() throws Exception {
        List<Map<String, Object>> rows = db.with(c -> VersionedStore.all(c, "SELECT auto_id, execution_id FROM " + t("auto_start")
                + " WHERE state = 'STARTED' AND execution_id IS NOT NULL"));
        for (Map<String, Object> row : rows) {
            String state = kestra.executionState(String.valueOf(row.get("execution_id")));
            if (state == null || FINISHED.contains(state)) {
                String finalState = state == null ? "not found in Kestra" : state;
                db.with(c -> VersionedStore.update(c, "UPDATE " + t("auto_start") + " SET state = 'DONE', message = ?,"
                        + " updated_at = now() WHERE auto_id = ?", finalState, row.get("auto_id")));
            }
        }
    }

    private String t(String table) {
        return schema + "." + table;
    }
}
