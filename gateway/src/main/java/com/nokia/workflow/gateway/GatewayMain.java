package com.nokia.workflow.gateway;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The cr_automation gateway in front of Kestra: users, roles, sessions, audit, and the process API.
 *
 * <p>Environment (secrets only from the environment, never from files):
 * <pre>
 *   GATEWAY_PORT                 8080
 *   KESTRA_INTERNAL_URL          http://kestra:8080          Kestra, reachable only from the gateway
 *   KESTRA_USER, KESTRA_PASSWORD Kestra's single technical login
 *   GATEWAY_JDBC_URL             jdbc:postgresql://postgres:5432/kestra
 *   GATEWAY_JDBC_USER            kestra
 *   GATEWAY_JDBC_PASSWORD        the database password
 *   GATEWAY_SCHEMA               cr_automation
 *   GATEWAY_ADMIN_USER / GATEWAY_ADMIN_PASSWORD   the first admin, used only while no user exists
 *   GATEWAY_SESSION_IDLE_HOURS   8      GATEWAY_SESSION_MAX_HOURS 24
 *   GATEWAY_MAX_FAILED_LOGINS    5      GATEWAY_LOCK_MINUTES      15
 *   GATEWAY_PROCESS_NAMESPACE    cr_automation               the default project's namespace (its seed)
 *   GATEWAY_PROJECT              CR_AUTOMATION               the default project, seeded from process/&lt;project&gt;.json;
 *                                                            more projects are created in the designer, each
 *                                                            with its own namespace
 *   GATEWAY_KNOWN_STEPS          NEI step names an activity configuration may name before any flow recorded them
 *   GATEWAY_AUTO_START_SECONDS   15     how often automatic steps (auto_start) are looked for; 0 = never
 *   GATEWAY_AUTO_START_WINDOW_MINUTES 60   orders changed within this time are looked at
 * </pre>
 */
public final class GatewayMain {

    private GatewayMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("GATEWAY_PORT", "8080"));
        String namespace = env.getOrDefault("GATEWAY_PROCESS_NAMESPACE", "cr_automation");
        Db db = new Db(env.getOrDefault("GATEWAY_JDBC_URL", "jdbc:postgresql://postgres:5432/kestra"),
                env.getOrDefault("GATEWAY_JDBC_USER", "kestra"), require(env, "GATEWAY_JDBC_PASSWORD"));
        UserStore users = new UserStore(db, env.getOrDefault("GATEWAY_SCHEMA", "cr_automation"), new UserStore.Settings(
                Duration.ofHours(Long.parseLong(env.getOrDefault("GATEWAY_SESSION_IDLE_HOURS", "8"))),
                Duration.ofHours(Long.parseLong(env.getOrDefault("GATEWAY_SESSION_MAX_HOURS", "24"))),
                Integer.parseInt(env.getOrDefault("GATEWAY_MAX_FAILED_LOGINS", "5")),
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("GATEWAY_LOCK_MINUTES", "15")))));
        String schema = env.getOrDefault("GATEWAY_SCHEMA", "cr_automation");
        String project = env.getOrDefault("GATEWAY_PROJECT", "CR_AUTOMATION");
        waitForDatabase(users);
        if (users.bootstrapAdmin(env.get("GATEWAY_ADMIN_USER"), env.get("GATEWAY_ADMIN_PASSWORD"))) {
            System.out.println("gateway: first admin created: " + env.get("GATEWAY_ADMIN_USER"));
        }
        ProcessStore processes = new ProcessStore(db, schema, users);
        processes.ensureSchema();
        seedProcess(processes, project);
        ActivityStore activities = new ActivityStore(db, schema, project, users, new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                env.getOrDefault("GATEWAY_KNOWN_STEPS", "GENERATE_TEMPLATE,CIQ_VALIDATION,MOP_GENERATION,MOP_EXECUTION,"
                        + "FAILURE_REPORT,SUMMARY_REPORT,ROLLBACK,ROLLBACK_REPORT").split("\\s*,\\s*"))));
        activities.ensureSchema();
        Kestra kestra = new Kestra(URI.create(env.getOrDefault("KESTRA_INTERNAL_URL", "http://kestra:8080")),
                require(env, "KESTRA_USER"), require(env, "KESTRA_PASSWORD"), "main");
        AutoStarter autoStarter = new AutoStarter(db, schema, kestra, processes, users);
        autoStarter.ensureSchema();
        Steps steps = new Steps(kestra, processes, activities, users, namespace, autoStarter);
        int autoSeconds = Integer.parseInt(env.getOrDefault("GATEWAY_AUTO_START_SECONDS", "15"));
        if (autoSeconds > 0) {
            autoStarter.start(steps, autoSeconds, Integer.parseInt(env.getOrDefault("GATEWAY_AUTO_START_WINDOW_MINUTES", "60")));
        }

        java.util.function.Predicate<String> isProjectNamespace = ns -> {
            try {
                return processes.namespaces().contains(ns);
            } catch (SQLException e) {
                throw new IllegalStateException("The project namespaces could not be read.", e);
            }
        };
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 256);
        Decisions decisions = new Decisions(db, schema, kestra, users);
        server.createContext("/", new Gateway(users, processes, activities, steps, decisions,
                new OrderSearch(db, schema), kestra, new Rules(isProjectNamespace), isProjectNamespace, project));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("gateway: listening on " + port + ", Kestra at " + env.getOrDefault("KESTRA_INTERNAL_URL", "http://kestra:8080"));
    }

    /** Version 1 of the project's process, from the definition shipped with the gateway - only when there is none yet. */
    private static void seedProcess(ProcessStore processes, String project) throws java.io.IOException, SQLException {
        try (java.io.InputStream in = GatewayMain.class.getResourceAsStream("/process/" + project + ".json")) {
            if (in == null) {
                System.out.println("gateway: no shipped process definition for " + project + " (process/" + project + ".json)");
                return;
            }
            String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (processes.seed(project, project + " order process", json)) {
                System.out.println("gateway: process " + project + " created with the shipped definition as version 1");
            }
        }
    }

    private static void waitForDatabase(UserStore users) throws InterruptedException, SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                users.ensureSchema();
                return;
            } catch (SQLException e) {
                if (attempt >= 30) {
                    throw e;
                }
                System.out.println("gateway: database not ready (" + e.getMessage() + "), retrying");
                Thread.sleep(2000);
            }
        }
    }

    private static String require(Map<String, String> env, String name) {
        String v = env.get(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("The environment variable %s is required.".formatted(name));
        }
        return v;
    }
}
