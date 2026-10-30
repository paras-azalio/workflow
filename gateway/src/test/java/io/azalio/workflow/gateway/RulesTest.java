package io.azalio.workflow.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RulesTest {

    private final Rules rules = new Rules("cr_automation");

    private boolean allowed(Role role, String method, String path) {
        return rules.decide(method, path).allows(role);
    }

    @Test
    public void viewerReadsEverythingButSecretsAndWebhooks() {
        assertTrue(allowed(Role.VIEWER, "GET", "/api/v1/main/executions/search"));
        assertTrue(allowed(Role.VIEWER, "GET", "/api/v1/main/namespaces/cr_automation/kv/ORDER_77"));
        assertTrue(allowed(Role.VIEWER, "HEAD", "/api/v1/main/flows/cr_automation/cr_child_order"));
        assertFalse(allowed(Role.VIEWER, "GET", "/api/v1/main/secrets"));
        assertFalse(allowed(Role.EXECUTOR, "GET", "/api/v1/main/namespaces/cr_automation/inherited-secrets"));
        assertFalse(allowed(Role.EXECUTOR, "GET", "/api/v1/main/executions/webhook/cr_automation/x/key"));
        assertTrue(allowed(Role.ADMIN, "GET", "/api/v1/main/secrets"));
    }

    @Test
    public void viewerMayUseThePostsThatOnlyRead() {
        assertTrue(allowed(Role.VIEWER, "POST", "/api/v1/main/executions/cr_automation/cr_child_order/validate"));
        assertTrue(allowed(Role.VIEWER, "POST", "/api/v1/main/flows/graph"));
        assertTrue(allowed(Role.VIEWER, "POST", "/api/v1/main/executions/latest"));
        assertTrue(allowed(Role.VIEWER, "POST", "/api/v1/main/dashboards/charts/preview"));
        assertFalse(allowed(Role.VIEWER, "POST", "/api/v1/main/executions/cr_automation/cr_child_order"));
    }

    @Test
    public void executorStartsProcessFlowsOfTheProcessNamespaceOnly() {
        Rules.Decision d = rules.decide("POST", "/api/v1/main/executions/cr_automation/cr_mop_execution");
        assertEquals(Role.EXECUTOR, d.minRole());
        assertEquals(Rules.Need.PROCESS_FLOW, d.need());
        assertEquals("cr_mop_execution", d.flowId());
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/main/executions/other_ns/some_flow"));
        assertTrue(allowed(Role.ADMIN, "POST", "/api/v1/main/executions/other_ns/some_flow"));
    }

    @Test
    public void bulkExecutionActionsAreNotMistakenForStartingAFlow() {
        assertEquals(Role.ADMIN, rules.decide("POST", "/api/v1/main/executions/resume/by-ids").minRole());
        assertEquals(Role.ADMIN, rules.decide("POST", "/api/v1/main/executions/restart/by-query").minRole());
        assertEquals(Role.ADMIN, rules.decide("DELETE", "/api/v1/main/executions/kill/by-ids").minRole());
    }

    @Test
    public void executorActsOnOneProcessExecution() {
        for (String action : new String[] {"resume", "restart", "replay", "replay-with-inputs"}) {
            Rules.Decision d = rules.decide("POST", "/api/v1/main/executions/abc123/actions/" + action);
            assertEquals(Role.EXECUTOR, d.minRole(), action);
            assertEquals(Rules.Need.PROCESS_EXECUTION, d.need(), action);
            assertEquals("abc123", d.executionId(), action);
        }
        assertEquals(Role.EXECUTOR, rules.decide("DELETE", "/api/v1/main/executions/abc123/actions/kill").minRole());
        assertEquals(Role.ADMIN, rules.decide("POST", "/api/v1/main/executions/abc123/actions/change-status").minRole());
        assertEquals(Role.ADMIN, rules.decide("DELETE", "/api/v1/main/executions/abc123").minRole());
    }

    @Test
    public void expressionEvaluationIsAdminOnlyBecauseItCanReadSecrets() {
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/main/expressions/render"));
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/main/executions/abc/actions/eval"));
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/main/flows/expressions"));
    }

    @Test
    public void editingFlowsFilesKvAndPluginsIsAdminOnly() {
        assertFalse(allowed(Role.EXECUTOR, "PUT", "/api/v1/main/flows/cr_automation/cr_child_order"));
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/main/flows"));
        assertFalse(allowed(Role.EXECUTOR, "PUT", "/api/v1/main/namespaces/cr_automation/kv/ORDERS"));
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/main/namespaces/cr_automation/files"));
        assertFalse(allowed(Role.EXECUTOR, "POST", "/api/v1/plugins/install"));
        assertTrue(allowed(Role.ADMIN, "PUT", "/api/v1/main/flows/cr_automation/cr_child_order"));
    }

    @Test
    public void kestraLoginSettingsAreBlockedForEveryone() {
        Rules.Decision d = rules.decide("POST", "/api/v1/main/basicAuth");
        assertNull(d.minRole());
        assertFalse(d.allows(Role.ADMIN));
    }
}
