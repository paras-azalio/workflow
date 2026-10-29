package com.nokia.workflow.gateway;

import java.util.List;
import java.util.Set;

/**
 * Which role may send which request to Kestra: an allow list of method + normalized path patterns, first match
 * wins. {@code *} matches one path segment, {@code **} the rest. Anything not listed needs ADMIN when it is not a
 * read (GET / HEAD), VIEWER when it is. The write endpoints are the 114 the Kestra 2.0.7 UI calls
 * (ui/packages/kestra-sdk/src/openapi/sdk.gen.ts); see analysis/07_process_designer_and_roles.md §2.
 */
final class Rules {

    /** What else must hold for an allowed request: checked against Kestra by the gateway. */
    enum Need {
        NONE,
        /** the flow (path segments namespace / flow id) is a process flow of the process namespace */
        PROCESS_FLOW,
        /** the execution (path segment id) belongs to a process flow of the process namespace */
        PROCESS_EXECUTION
    }

    /** {@code minRole} null: nobody, whatever the role. */
    record Decision(Role minRole, Need need, String namespace, String flowId, String executionId, String reason) {
        boolean allows(Role role) {
            return minRole != null && role.atLeast(minRole);
        }
    }

    private record Rule(Set<String> methods, String pattern, Role minRole, Need need, String reason) {
    }

    private static final Set<String> READS = Set.of("GET", "HEAD");
    private static final Set<String> POST = Set.of("POST");
    private static final Set<String> GET = Set.of("GET");

    private static final List<Rule> RULES = List.of(
            // Kestra's own login settings: the technical login is the gateway's configuration, never changed in the UI
            new Rule(POST, "/api/v1/*/basicAuth", null, Need.NONE,
                    "Kestra's login is managed by the gateway configuration."),
            // reads with side effects or secret content
            new Rule(GET, "/api/v1/*/executions/webhook/**", Role.ADMIN, Need.NONE, "Webhooks start executions."),
            new Rule(GET, "/api/v1/*/secrets", Role.ADMIN, Need.NONE, "Secrets are for admins."),
            new Rule(GET, "/api/v1/*/namespaces/*/inherited-secrets", Role.ADMIN, Need.NONE, "Secrets are for admins."),
            // POSTs that only read
            new Rule(POST, "/api/v1/*/executions/latest", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/executions/*/*/validate", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/executions/*/actions/resume/validate", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/flows/validate", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/flows/validate/task", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/flows/validate/trigger", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/flows/graph", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/flows/source/replace/preview", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/dashboards/charts/preview", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/dashboards/charts/export", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/dashboards/*/charts/*/export", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/*/namespaces/autocomplete", Role.VIEWER, Need.NONE, null),
            new Rule(POST, "/api/v1/plugins/pluginUiManifest", Role.VIEWER, Need.NONE, null),
            // running the order process: one execution at a time, process flows only
            new Rule(POST, "/api/v1/*/executions/*/actions/resume", Role.EXECUTOR, Need.PROCESS_EXECUTION, null),
            new Rule(POST, "/api/v1/*/executions/*/actions/restart", Role.EXECUTOR, Need.PROCESS_EXECUTION, null),
            new Rule(POST, "/api/v1/*/executions/*/actions/replay", Role.EXECUTOR, Need.PROCESS_EXECUTION, null),
            new Rule(POST, "/api/v1/*/executions/*/actions/replay-with-inputs", Role.EXECUTOR, Need.PROCESS_EXECUTION, null),
            new Rule(Set.of("DELETE"), "/api/v1/*/executions/*/actions/kill", Role.EXECUTOR, Need.PROCESS_EXECUTION, null),
            new Rule(POST, "/api/v1/*/executions/*/*", Role.EXECUTOR, Need.PROCESS_FLOW, null)
    );

    private final java.util.function.Predicate<String> isProjectNamespace;

    Rules(String processNamespace) {
        this(processNamespace::equals);
    }

    /** @param isProjectNamespace whether a namespace holds a project's flows (executors may run its process flows) */
    Rules(java.util.function.Predicate<String> isProjectNamespace) {
        this.isProjectNamespace = isProjectNamespace;
    }

    Decision decide(String method, String path) {
        String[] segments = segments(path);
        for (Rule rule : RULES) {
            if (rule.methods().contains(method) && matches(segments, segments(rule.pattern()))) {
                if (rule.need() == Need.PROCESS_FLOW) {
                    String namespace = segments[4];
                    if (!isProjectNamespace.test(namespace)) {
                        continue;
                    }
                    return new Decision(rule.minRole(), rule.need(), namespace, segments[5], null, rule.reason());
                }
                if (rule.need() == Need.PROCESS_EXECUTION) {
                    return new Decision(rule.minRole(), rule.need(), null, null, segments[4], rule.reason());
                }
                return new Decision(rule.minRole(), rule.need(), null, null, null, rule.reason());
            }
        }
        return READS.contains(method)
                ? new Decision(Role.VIEWER, Need.NONE, null, null, null, null)
                : new Decision(Role.ADMIN, Need.NONE, null, null, null, null);
    }

    private static String[] segments(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? new String[0] : p.split("/");
    }

    private static boolean matches(String[] path, String[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if ("**".equals(pattern[i])) {
                return path.length > i;
            }
            if (i >= path.length || !("*".equals(pattern[i]) || pattern[i].equals(path[i]))) {
                return false;
            }
        }
        return path.length == pattern.length;
    }
}
