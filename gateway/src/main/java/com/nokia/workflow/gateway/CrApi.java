package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import com.sun.net.httpserver.HttpExchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gateway's own API under {@code /cr/api}:
 * <pre>
 *   GET    /cr/api/me                                      who is logged in, what the role allows       any role
 *   POST   /cr/api/me/password                             own password: {current, password}           any role
 *   GET    /cr/api/users | POST | PUT /{u} | DELETE /{u}   users                                       ADMIN
 *   GET    /cr/api/audit?limit=N                           latest audit entries                        ADMIN
 *
 *   GET    /cr/api/orders[?id=N&waiting=true]              orders and children (one id: the order with  any role
 *                                                           its children, or the child); waiting = children
 *                                                           whose MOP execution is still to be done
 *   GET    /cr/api/orders/{order}/next[?child=C]           steps offered now (from the pinned version) and
 *                                                           the open decisions of the order / child       any role
 *   POST   /cr/api/orders/{order}/steps/{step}[?child=C]   start one: {inputs, files, request_key}     EXECUTOR
 *   POST   /cr/api/orders/{order}/decisions/{id}           take a decision: {decision, comment}        EXECUTOR
 *   POST   /cr/api/orders/{order}/stop?child=C             stop the child: kill what runs for it now     EXECUTOR
 *                                                           (the child becomes STOPPED, no summary report)
 *   GET    /cr/api/processes                               projects (with their namespace) and versions any role
 *   POST   /cr/api/processes                               new project: {project, name, namespace,     ADMIN
 *                                                           from_project?} - its process starts as a draft
 *   POST   /cr/api/processes/{p}/delete | /restore         soft delete (hidden; orders and versions stay) ADMIN
 *                                                           / bring back; ?hidden=true lists the deleted ones
 *   /cr/api/projects/{p}/activities[/...]                   as /cr/api/activities, for project p (the plain
 *   GET /cr/api/projects/{p}/context-fields                 routes are the gateway's default project)
 *   /cr/api/projects/{p}/components                         GET list; POST {name, kind, description} (ADMIN)
 *     /{c}/versions[/{v}]                                   GET; POST new draft {from_version?}; PUT {spec, revision};
 *                                                           DELETE draft; /{v}/publish | /retire (ADMIN)
 *     /{c}/versions/{v}/files[?path=P&unzip=true]           GET list; PUT the file's bytes (a zip unpacked into P);
 *                                                           DELETE ?path=P (ADMIN, drafts only)
 *     /{c}/versions/{v}/try                                 POST {action, params}: runs it once, answers IO (ADMIN)
 *   GET    /cr/api/processes/{p}/start                     steps that start an order                   any role
 *   POST   /cr/api/processes/{p}/start/{step}              start an order: {inputs, files, request_key} EXECUTOR
 *   GET    /cr/api/processes/{p}/versions[/{v}]            versions / one version with its definition  any role
 *   POST   /cr/api/processes/{p}/versions                  new draft: {from_version?}                  ADMIN
 *   PUT    /cr/api/processes/{p}/versions/{v}              save draft: {definition, revision}           ADMIN
 *   DELETE /cr/api/processes/{p}/versions/{v}              discard a draft                              ADMIN
 *   POST   /cr/api/processes/{p}/versions/{v}/publish      publish a valid draft (its pipelines are     ADMIN
 *                                                           compiled, checked by Kestra and deployed)
 *   POST   /cr/api/processes/{p}/versions/{v}/retire       retire a published version                   ADMIN
 *
 *   GET    /cr/api/context-fields                          order / child field names (designer paths)  any role
 *   GET    /cr/api/activities                              declared activities + known step names      any role
 *   GET    /cr/api/activities/parameters                   what each step sends, and the project's     any role
 *                                                           login / GRC values per component (config file)
 *   POST   /cr/api/activities                              declare: {node_type, sub_activity, description} ADMIN
 *   GET    /cr/api/activities/{nt}/{sa}/versions[/{v}]     versions / one version with its config      any role
 *   POST   /cr/api/activities/{nt}/{sa}/versions           new draft: {from_version?}                  ADMIN
 *   PUT    /cr/api/activities/{nt}/{sa}/versions/{v}       save draft: {config, revision}              ADMIN
 *   DELETE /cr/api/activities/{nt}/{sa}/versions/{v}       discard a draft                             ADMIN
 *   POST   /cr/api/activities/{nt}/{sa}/versions/{v}/publish | /retire                                 ADMIN
 * </pre>
 */
final class CrApi {

    /** Step starts may carry a CIQ (base64 in JSON). */
    private static final int MAX_STEP_BODY = 30 * 1024 * 1024;

    private final UserStore users;
    private final ProcessStore processes;
    private final ActivityStore activities;
    private final Steps steps;
    private final Decisions decisions;
    private final OrderSearch search;
    private final ComponentStore components;
    private final ComponentTry tryRun;
    private final Kestra kestra;
    private final String defaultProject;
    private final Runnable onUserChange;

    CrApi(UserStore users, ProcessStore processes, ActivityStore activities, Steps steps, Decisions decisions, OrderSearch search,
          ComponentStore components, Kestra kestra, String defaultProject, Runnable onUserChange) {
        this.search = search;
        this.components = components;
        this.tryRun = new ComponentTry(kestra);
        this.users = users;
        this.processes = processes;
        this.activities = activities;
        this.steps = steps;
        this.decisions = decisions;
        this.kestra = kestra;
        this.defaultProject = defaultProject;
        this.onUserChange = onUserChange;
    }

    void handle(HttpExchange ex, String method, String path, Gateway.Caller who) throws Exception {
        UserStore.User me = who.user();
        String[] s = path.substring("/cr/api/".length()).split("/");
        try {
            switch (s[0]) {
                case "me" -> me(ex, method, s, who);
                case "users", "audit" -> {
                    requireRole(me, Role.ADMIN, method, path, who);
                    admin(ex, method, s, me);
                }
                case "orders" -> orders(ex, method, s, who);
                case "processes" -> processes(ex, method, s, who, path);
                case "activities" -> activities(ex, method, s, who, path, defaultProject);
                case "context-fields" -> {
                    if (s.length != 1 || !"GET".equals(method)) {
                        notFound(method, path);
                    }
                    Gateway.json(ex, 200, steps.contextFields(defaultProject));
                }
                case "projects" -> {
                    if (s.length < 3) {
                        notFound(method, path);
                    }
                    String p = Paths.decode(s[1]);
                    requireProject(p);
                    String[] rest = java.util.Arrays.copyOfRange(s, 2, s.length);
                    if ("activities".equals(rest[0])) {
                        activities(ex, method, rest, who, path, p);
                    } else if ("components".equals(rest[0])) {
                        components(ex, method, rest, who, path, p);
                    } else if ("context-fields".equals(rest[0]) && rest.length == 1 && "GET".equals(method)) {
                        Gateway.json(ex, 200, steps.contextFields(p));
                    } else {
                        notFound(method, path);
                    }
                }
                default -> notFound(method, path);
            }
        } catch (ProcessDefinition.Invalid e) {
            Gateway.json(ex, 400, Map.of("message", e.getMessage(), "problems", e.problems));
        } catch (Kestra.KestraError e) {
            Gateway.json(ex, e.status, Map.of("message", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ me, users, audit

    private void me(HttpExchange ex, String method, String[] s, Gateway.Caller who) throws Exception {
        UserStore.User me = who.user();
        if (s.length == 1 && "GET".equals(method)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", me.username());
            m.put("display_name", me.displayName());
            m.put("role", me.role().name());
            Map<String, Object> can = new LinkedHashMap<>();
            can.put("view", true);
            can.put("execute", me.role().atLeast(Role.EXECUTOR));
            can.put("admin", me.role().atLeast(Role.ADMIN));
            m.put("can", can);
            Gateway.json(ex, 200, m);
        } else if (s.length == 2 && "password".equals(s[1]) && "POST".equals(method)) {
            Map<String, Object> body = Gateway.readJson(ex);
            users.changeOwnPassword(me, Gateway.str(body.get("current")), Gateway.str(body.get("password")), who.ip());
            onUserChange.run();
            Gateway.json(ex, 200, Map.of("message", "Password changed - log in again."));
        } else {
            notFound(method, String.join("/", s));
        }
    }

    private void admin(HttpExchange ex, String method, String[] s, UserStore.User me) throws Exception {
        if ("users".equals(s[0]) && s.length == 1 && "GET".equals(method)) {
            Gateway.json(ex, 200, Map.of("users", users.listUsers()));
        } else if ("users".equals(s[0]) && s.length == 1 && "POST".equals(method)) {
            Map<String, Object> b = Gateway.readJson(ex);
            Map<String, Object> created = users.createUser(me.username(), me.role(), Gateway.str(b.get("username")),
                    Gateway.str(b.get("display_name")), Gateway.str(b.get("role")), Gateway.str(b.get("password")));
            onUserChange.run();
            Gateway.json(ex, 201, created);
        } else if ("users".equals(s[0]) && s.length == 2 && "PUT".equals(method)) {
            Map<String, Object> b = Gateway.readJson(ex);
            Object enabled = b.get("enabled");
            Map<String, Object> updated = users.updateUser(me.username(), me.role(), Paths.decode(s[1]), Gateway.str(b.get("role")),
                    enabled == null ? null : Boolean.valueOf(String.valueOf(enabled)), Gateway.str(b.get("display_name")),
                    Gateway.str(b.get("password")));
            onUserChange.run();
            Gateway.json(ex, 200, updated);
        } else if ("users".equals(s[0]) && s.length == 2 && "DELETE".equals(method)) {
            users.deleteUser(me.username(), me.role(), Paths.decode(s[1]));
            onUserChange.run();
            Gateway.json(ex, 200, Map.of("message", "User deleted."));
        } else if ("audit".equals(s[0]) && s.length == 1 && "GET".equals(method)) {
            String limit = query(ex, "limit");
            int n = 200;
            try {
                n = limit == null ? 200 : Integer.parseInt(limit);
            } catch (NumberFormatException ignored) {
                // default
            }
            Gateway.json(ex, 200, Map.of("entries", users.auditLog(n)));
        } else {
            notFound(method, String.join("/", s));
        }
    }

    // ------------------------------------------------------------------ orders: next steps, start a step

    private void orders(HttpExchange ex, String method, String[] s, Gateway.Caller who) throws Exception {
        String child = query(ex, "child");
        if (child != null && !child.matches("[1-9][0-9]{0,17}")) {
            throw new UserStore.Rejected(400, "The child order id must be a whole number.");
        }
        if (s.length == 1 && "GET".equals(method)) {
            String id = query(ex, "id");
            if (id != null && !id.isBlank() && !id.trim().matches("[1-9][0-9]{0,17}")) {
                throw new UserStore.Rejected(400, "Search by an order or child order id (a whole number).");
            }
            Long n = id == null || id.isBlank() ? null : Long.valueOf(id.trim());
            String project = query(ex, "project");
            Gateway.json(ex, 200, Map.of("orders", search.find(n, "true".equals(query(ex, "waiting")),
                    project == null || project.isBlank() ? null : project)));
        } else if (s.length == 3 && "next".equals(s[2]) && "GET".equals(method)) {
            Map<String, Object> next = new LinkedHashMap<>(steps.next(s[1], child, who.user().role()));
            next.put("decisions", decisions.open(Long.parseLong(s[1]), child == null ? null : Long.valueOf(child)));
            if (child != null) {
                next.put("running", search.running(Long.parseLong(s[1]), Long.parseLong(child)));
            }
            Gateway.json(ex, 200, next);
        } else if (s.length == 4 && "decisions".equals(s[2]) && "POST".equals(method)) {
            if (!s[1].matches("[1-9][0-9]{0,17}") || !s[3].matches("[1-9][0-9]{0,17}")) {
                throw new UserStore.Rejected(400, "The order and decision ids must be whole numbers.");
            }
            Gateway.json(ex, 200, decisions.decide(Long.parseLong(s[1]), Long.parseLong(s[3]), Gateway.readJson(ex), who.user(), who.ip()));
        } else if (s.length == 3 && "stop".equals(s[2]) && "POST".equals(method)) {
            if (!who.user().role().atLeast(Role.EXECUTOR)) {
                throw new UserStore.Rejected(403, "Only an order executor or an admin can stop a child order.");
            }
            if (child == null || !s[1].matches("[1-9][0-9]{0,17}")) {
                throw new UserStore.Rejected(400, "Say which child order to stop (?child=<id>).");
            }
            List<String> running = search.running(Long.parseLong(s[1]), Long.parseLong(child));
            if (running.isEmpty()) {
                throw new UserStore.Rejected(409, "Nothing runs for child order %s now.".formatted(child));
            }
            for (String e : running) {
                kestra.kill(e);
            }
            users.audit(who.user().username(), who.user().role(), "STOP_CHILD", method, null, 200, who.ip(),
                    "order %s child %s: killed %s".formatted(s[1], child, running));
            Gateway.json(ex, 200, Map.of("message", "Child order %s is being stopped.".formatted(child), "killed", running));
        } else if (s.length == 4 && "steps".equals(s[2]) && "POST".equals(method)) {
            Map<String, Object> body = Gateway.readJson(ex, MAX_STEP_BODY);
            Gateway.json(ex, 201, steps.start(s[1], child, s[3], body, who.user(), who.ip()));
        } else {
            notFound(method, String.join("/", s));
        }
    }

    // ------------------------------------------------------------------ processes

    private void processes(HttpExchange ex, String method, String[] s, Gateway.Caller who, String path) throws Exception {
        UserStore.User me = who.user();
        if (s.length == 1 && "GET".equals(method)) {
            boolean withHidden = "true".equals(query(ex, "hidden")) && me.role().atLeast(Role.ADMIN);
            Gateway.json(ex, 200, Map.of("processes", processes.processes(withHidden), "default_project", defaultProject));
            return;
        }
        if (s.length == 1 && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex);
            String fromProject = Gateway.str(b.get("from_project"));
            if (fromProject != null && !fromProject.isBlank()) {
                requireProject(fromProject);
            }
            Gateway.json(ex, 201, processes.createProject(Gateway.str(b.get("project")), Gateway.str(b.get("name")),
                    Gateway.str(b.get("namespace")), fromProject, me.username()));
            return;
        }
        String p = s.length > 1 ? Paths.decode(s[1]) : null;
        if (s.length == 3 && ("delete".equals(s[2]) || "restore".equals(s[2])) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            requireProject(p);
            if ("delete".equals(s[2])) {
                if (defaultProject.equals(p)) {
                    throw new UserStore.Rejected(409, "%s is the gateway's default project and cannot be deleted.".formatted(p));
                }
                processes.hide(p, me.username());
                Gateway.json(ex, 200, Map.of("message", "Project %s is deleted (hidden). Its orders and versions stay; an admin can restore it.".formatted(p)));
            } else {
                processes.restore(p, me.username());
                Gateway.json(ex, 200, Map.of("message", "Project %s is restored.".formatted(p)));
            }
            return;
        }
        if (s.length == 3 && "start".equals(s[2]) && "GET".equals(method)) {
            requireProject(p);
            Gateway.json(ex, 200, steps.startSteps(p, me.role()));
        } else if (s.length == 4 && "start".equals(s[2]) && "POST".equals(method)) {
            requireProject(p);
            if (processes.hidden(p)) {
                throw new UserStore.Rejected(409, "Project %s is deleted: restore it to start new orders.".formatted(p));
            }
            Map<String, Object> body = Gateway.readJson(ex, MAX_STEP_BODY);
            Gateway.json(ex, 201, steps.startOrder(p, s[3], body, me, who.ip()));
        } else if (s.length == 3 && "versions".equals(s[2]) && "GET".equals(method)) {
            Gateway.json(ex, 200, Map.of("project", p, "versions", processes.versions(p)));
        } else if (s.length == 4 && "versions".equals(s[2]) && "GET".equals(method)) {
            ProcessStore.Version v = processes.version(p, version(s[3]));
            if (v == null) {
                throw new UserStore.Rejected(404, "Process %s has no version %s.".formatted(p, s[3]));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project", v.project());
            m.put("version", v.version());
            m.put("state", v.state());
            m.put("revision", v.revision());
            m.put("definition", v.definition());
            m.put("problems", ProcessStore.problems(v.definition()));
            m.put("compiled", processes.compiled(p, v.version()));
            Gateway.json(ex, 200, m);
        } else if (s.length == 3 && "versions".equals(s[2]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex);
            Object from = b.get("from_version");
            ProcessStore.Version v = processes.createDraft(p, from == null ? null : version(String.valueOf(from)), me.username());
            Gateway.json(ex, 201, Map.of("project", v.project(), "version", v.version(), "state", v.state(), "revision", v.revision()));
        } else if (s.length == 4 && "versions".equals(s[2]) && "PUT".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex, MAX_STEP_BODY);
            Object def = b.get("definition");
            if (def == null || b.get("revision") == null) {
                throw new UserStore.Rejected(400, "Send {definition, revision} (revision: the one you loaded).");
            }
            String json = def instanceof String str ? str : SimpleJson.writeCompact(def);
            List<String> problems = new ArrayList<>(processes.updateDraft(p, version(s[3]), json,
                    version(String.valueOf(b.get("revision"))), me.username()));
            if (problems.isEmpty()) {
                try {
                    problems.addAll(pipelineProblems(p, version(s[3]), ProcessDefinition.parse(SimpleJson.parse(json)), null));
                } catch (java.io.IOException e) {
                    problems.add("Kestra could not check the pipelines now (" + e.getMessage() + "); publishing checks them again.");
                }
            }
            Gateway.json(ex, 200, Map.of("saved", true, "problems", problems));
        } else if (s.length == 4 && "versions".equals(s[2]) && "DELETE".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            processes.discardDraft(p, version(s[3]), me.username());
            Gateway.json(ex, 200, Map.of("message", "Draft %s of %s discarded.".formatted(s[3], p)));
        } else if (s.length == 5 && "versions".equals(s[2]) && "publish".equals(s[4]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            int v = version(s[3]);
            Map<String, Map<String, String>> compiled = new LinkedHashMap<>();
            processes.publish(p, v, me.username(), def -> publishProblems(p, v, def, compiled));
            processes.setCompiled(p, v, compiled);
            int flows = compiled.values().stream().mapToInt(Map::size).sum();
            Gateway.json(ex, 200, Map.of("message", "Version %s of %s is published; new orders use it.".formatted(s[3], p)
                    + (flows == 0 ? "" : " %d pipeline flow(s) deployed.".formatted(flows))));
        } else if (s.length == 5 && "versions".equals(s[2]) && "retire".equals(s[4]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            processes.retire(p, version(s[3]), me.username());
            Gateway.json(ex, 200, Map.of("message", "Version %s of %s is retired.".formatted(s[3], p)));
        } else {
            notFound(method, path);
        }
    }

    // ------------------------------------------------------------------ components

    /** Up to 300 MB per upload (a jar with its lib/ as a zip). */
    private static final int MAX_UPLOAD = 300 * 1024 * 1024;

    @SuppressWarnings("unchecked")
    private void components(HttpExchange ex, String method, String[] s, Gateway.Caller who, String path, String project) throws Exception {
        UserStore.User me = who.user();
        ComponentStore store = components.of(project);
        String namespace = processes.namespace(project);
        if (s.length == 1 && "GET".equals(method)) {
            java.util.Set<String> builtins = new java.util.TreeSet<>();
            for (Map<String, Object> f : kestra.listNamespaceFiles(namespace, "/nei")) {
                String[] parts = String.valueOf(f.get("path")).split("/");
                if (parts.length > 3) {
                    builtins.add(parts[2]);
                }
            }
            List<Map<String, Object>> listed = new ArrayList<>();
            for (Map<String, Object> c : store.list()) {
                Map<String, Object> m = new LinkedHashMap<>(c);
                VersionedStore.Version published = store.latestPublished(String.valueOf(c.get("name")));
                m.put("actions", published == null ? List.of() : published.content().getOrDefault("actions", List.of()));
                listed.add(m);
            }
            Gateway.json(ex, 200, Map.of("project", project, "components", listed, "builtins", List.copyOf(builtins),
                    "runtimes", List.copyOf(ComponentSpec.RUNTIMES)));
            return;
        }
        requireRole(me, Role.VIEWER, method, path, who);
        if (s.length == 1 && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex);
            Gateway.json(ex, 201, store.declare(Gateway.str(b.get("name")), Gateway.str(b.get("kind")), Gateway.str(b.get("description")),
                    me.username(), namespace));
            return;
        }
        if (s.length < 3 || !"versions".equals(s[2])) {
            notFound(method, path);
        }
        String name = Paths.decode(s[1]);
        if (s.length == 3 && "GET".equals(method)) {
            Gateway.json(ex, 200, Map.of("name", name, "versions", store.versions(name)));
            return;
        }
        if (s.length == 3 && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Object from = Gateway.readJson(ex).get("from_version");
            VersionedStore.Version v = store.createDraft(name, from == null ? null : version(String.valueOf(from)), me.username(), namespace);
            Gateway.json(ex, 201, Map.of("name", name, "version", v.version(), "state", v.state(), "revision", v.revision()));
            return;
        }
        int v = version(s[3]);
        if (s.length == 4 && "GET".equals(method)) {
            VersionedStore.Version ver = store.version(name, v);
            if (ver == null) {
                throw new UserStore.Rejected(404, "Component %s has no version %d.".formatted(name, v));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("version", ver.version());
            m.put("state", ver.state());
            m.put("revision", ver.revision());
            m.put("spec", ver.content());
            m.put("problems", ComponentSpec.problems(ver.content()));
            m.put("dir", ComponentStore.dir(name, v));
            m.put("files", store.files(name, v, namespace));
            Gateway.json(ex, 200, m);
        } else if (s.length == 4 && "PUT".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex, MAX_STEP_BODY);
            if (b.get("spec") == null || b.get("revision") == null) {
                throw new UserStore.Rejected(400, "Send {spec, revision} (revision: the one you loaded).");
            }
            Object spec = b.get("spec");
            Gateway.json(ex, 200, Map.of("saved", true, "problems", store.updateDraft(name, v,
                    spec instanceof String str ? str : SimpleJson.writeCompact(spec), version(String.valueOf(b.get("revision"))), me.username(), namespace)));
        } else if (s.length == 4 && "DELETE".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            store.discardDraft(name, v, me.username(), namespace);
            Gateway.json(ex, 200, Map.of("message", "Draft %d of %s discarded.".formatted(v, name)));
        } else if (s.length == 5 && "publish".equals(s[4]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            store.publish(name, v, me.username(), namespace);
            Gateway.json(ex, 200, Map.of("message", "Version %d of %s is published; pipelines published from now on use it.".formatted(v, name)));
        } else if (s.length == 5 && "retire".equals(s[4]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            store.retire(name, v, me.username(), namespace);
            Gateway.json(ex, 200, Map.of("message", "Version %d of %s is retired.".formatted(v, name)));
        } else if (s.length == 5 && "files".equals(s[4]) && "GET".equals(method)) {
            Gateway.json(ex, 200, Map.of("files", store.files(name, v, namespace)));
        } else if (s.length == 5 && "files".equals(s[4]) && "PUT".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            byte[] content = Gateway.readBytes(ex, MAX_UPLOAD);
            if (content.length == 0) {
                throw new UserStore.Rejected(400, "The file is empty.");
            }
            ComponentStore.Uploaded u = store.upload(name, v, query(ex, "path"), content, "true".equals(query(ex, "unzip")), me.username(), namespace);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("written", u.written());
            if (u.droppedFolder() != null) {
                out.put("dropped_folder", u.droppedFolder());
            }
            Gateway.json(ex, 200, out);
        } else if (s.length == 5 && "files".equals(s[4]) && "DELETE".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            store.deleteFile(name, v, query(ex, "path"), me.username(), namespace);
            Gateway.json(ex, 200, Map.of("message", "Deleted."));
        } else if (s.length == 5 && "try".equals(s[4]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            VersionedStore.Version ver = store.version(name, v);
            if (ver == null) {
                throw new UserStore.Rejected(404, "Component %s has no version %d.".formatted(name, v));
            }
            List<String> problems = ComponentSpec.problems(ver.content());
            if (!problems.isEmpty()) {
                throw new UserStore.Rejected(400, "Save a valid definition first: " + String.join("; ", problems));
            }
            Map<String, Object> b = Gateway.readJson(ex, MAX_STEP_BODY);
            String action = Gateway.str(b.get("action"));
            if (action == null || !ComponentSpec.actions(ver.content()).containsKey(action)) {
                throw new UserStore.Rejected(400, "Choose one of the component's actions %s.".formatted(ComponentSpec.actions(ver.content()).keySet()));
            }
            Map<String, Object> params = b.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
            Map<String, Object> standalone = ver.content().get("standalone") instanceof Map<?, ?> sm ? (Map<String, Object>) sm : Map.of();
            Long timeout = ProcessDefinition.number(standalone.getOrDefault("timeout_sec", "300"));
            String dir = ComponentStore.dir(name, v);
            users.audit(me.username(), me.role(), "COMPONENT_TRY", method, null, 200, who.ip(), "%s/%s v%d: %s".formatted(project, name, v, action));
            Gateway.json(ex, 200, tryRun.run(namespace, dir, ComponentSpec.home(ver.content(), dir), action, params, timeout == null ? 300 : timeout));
        } else {
            notFound(method, path);
        }
    }

    // ------------------------------------------------------------------ activities

    private void activities(HttpExchange ex, String method, String[] s, Gateway.Caller who, String path, String project) throws Exception {
        UserStore.User me = who.user();
        ActivityStore activities = this.activities.of(project);
        if (s.length == 1 && "GET".equals(method)) {
            Gateway.json(ex, 200, Map.of("project", project, "activities", activities.list(),
                    "known_steps", new ArrayList<>(activities.knownSteps())));
            return;
        }
        if (s.length == 2 && "parameters".equals(s[1]) && "GET".equals(method)) {
            String namespace = processes.namespace(project);
            Gateway.json(ex, 200, Map.of("steps", activities.stepParameters(), "components",
                    ActivityStore.projectDefaults(namespace == null ? null : kestra.projectConfig(namespace))));
            return;
        }
        if (s.length == 1 && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex);
            Gateway.json(ex, 201, activities.declare(Gateway.str(b.get("node_type")), Gateway.str(b.get("sub_activity")),
                    Gateway.str(b.get("description")), me.username(), users));
            return;
        }
        if (s.length < 4 || !"versions".equals(s[3])) {
            notFound(method, path);
        }
        String nt = Paths.decode(s[1]);
        String sa = Paths.decode(s[2]);
        if (s.length == 4 && "GET".equals(method)) {
            Gateway.json(ex, 200, Map.of("node_type", nt, "sub_activity", sa, "versions", activities.versions(nt, sa)));
        } else if (s.length == 5 && "GET".equals(method)) {
            VersionedStore.Version v = activities.version(nt, sa, version(s[4]));
            if (v == null) {
                throw new UserStore.Rejected(404, "Activity %s/%s has no version %s.".formatted(nt, sa, s[4]));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node_type", nt);
            m.put("sub_activity", sa);
            m.put("version", v.version());
            m.put("state", v.state());
            m.put("revision", v.revision());
            m.put("config", v.content());
            m.put("problems", ActivityConfig.problems(v.content()));
            Gateway.json(ex, 200, m);
        } else if (s.length == 4 && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Object from = Gateway.readJson(ex).get("from_version");
            VersionedStore.Version v = activities.createDraft(nt, sa, from == null ? null : version(String.valueOf(from)), me.username());
            Gateway.json(ex, 201, Map.of("node_type", nt, "sub_activity", sa, "version", v.version(), "state", v.state(),
                    "revision", v.revision()));
        } else if (s.length == 5 && "PUT".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            Map<String, Object> b = Gateway.readJson(ex, MAX_STEP_BODY);
            Object cfg = b.get("config");
            if (cfg == null || b.get("revision") == null) {
                throw new UserStore.Rejected(400, "Send {config, revision} (revision: the one you loaded).");
            }
            List<String> problems = activities.updateDraft(nt, sa, version(s[4]),
                    cfg instanceof String str ? str : SimpleJson.writeCompact(cfg), version(String.valueOf(b.get("revision"))), me.username());
            Gateway.json(ex, 200, Map.of("saved", true, "problems", problems));
        } else if (s.length == 5 && "DELETE".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            activities.discardDraft(nt, sa, version(s[4]), me.username());
            Gateway.json(ex, 200, Map.of("message", "Draft %s of %s/%s discarded.".formatted(s[4], nt, sa)));
        } else if (s.length == 6 && "publish".equals(s[5]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            activities.publish(nt, sa, version(s[4]), me.username());
            Gateway.json(ex, 200, Map.of("message", "Version %s of %s/%s is published; new orders of the activity use it."
                    .formatted(s[4], nt, sa)));
        } else if (s.length == 6 && "retire".equals(s[5]) && "POST".equals(method)) {
            requireRole(me, Role.ADMIN, method, path, who);
            activities.retire(nt, sa, version(s[4]), me.username());
            Gateway.json(ex, 200, Map.of("message", "Version %s of %s/%s is retired.".formatted(s[4], nt, sa)));
        } else {
            notFound(method, path);
        }
    }

    /** Every step must run a process flow of the process namespace, which must exist in Kestra. */
    /**
     * The checks only Kestra can make on the pipelines: the step's flow offers the hook (label pipeline_hooks), and
     * Kestra accepts the compiled flow. With {@code deployed} (publish, no problems): deploys them and fills it in.
     */
    private List<String> pipelineProblems(String project, int version, ProcessDefinition def,
                                          Map<String, Map<String, String>> deployed) throws Exception {
        List<String> problems = new ArrayList<>();
        Map<String, PipelineCompiler.Compiled> compiled = new LinkedHashMap<>();
        Map<String, String[]> target = new LinkedHashMap<>();
        Map<String, PipelineCompiler.ComponentRef> refs = new LinkedHashMap<>();
        Map<String, Map<String, Object>> specs = new LinkedHashMap<>();
        for (ProcessDefinition.Step step : def.steps) {
            for (Map.Entry<Pipeline.Hook, Pipeline> e : step.pipelines().entrySet()) {
                for (Pipeline.Widget w : Pipeline.flatten(e.getValue().widgets)) {
                    if (w instanceof Pipeline.Nei n) {
                        componentProblems(project, def.namespace, "step '%s', pipeline %s, widget '%s'".formatted(step.id(), e.getKey().key, n.id()),
                                n, refs, specs, problems);
                    }
                }
            }
        }
        for (ProcessDefinition.Step step : def.steps) {
            if (step.pipelines().isEmpty()) {
                continue;
            }
            String hooks = kestra.flowLabel(def.namespace, step.flow(), "pipeline_hooks");
            List<String> offered = hooks == null ? List.of() : List.of(hooks.replace(" ", "").split(","));
            for (Map.Entry<Pipeline.Hook, Pipeline> e : step.pipelines().entrySet()) {
                String at = "step '%s', pipeline %s".formatted(step.id(), e.getKey().key);
                if (!offered.contains(e.getKey().key)) {
                    problems.add(at + ": flow %s does not run a '%s' pipeline (its label pipeline_hooks: %s)."
                            .formatted(step.flow(), e.getKey().key, hooks == null ? "none" : hooks));
                    continue;
                }
                PipelineCompiler.Compiled c = PipelineCompiler.compile(def.namespace, project, version, step.id(), e.getValue(), refs);
                for (String v : kestra.validateFlow(c.yaml())) {
                    problems.add(at + ": Kestra: " + v);
                }
                compiled.put(c.flowId(), c);
                target.put(c.flowId(), new String[] {step.flow(), e.getKey().key});
            }
        }
        if (deployed != null && problems.isEmpty()) {
            for (PipelineCompiler.Compiled c : compiled.values()) {
                kestra.deployFlow(def.namespace, c.flowId(), c.yaml());
                String[] t = target.get(c.flowId());
                deployed.computeIfAbsent(t[0], k -> new LinkedHashMap<>()).put(t[1], c.flowId());
            }
        }
        return problems;
    }

    /**
     * What a NEI box calls: a registered component (its latest published version - pinned into the compiled flow -,
     * one of its actions, every required parameter given) or a built-in NEI folder nei/&lt;component&gt; of the namespace.
     */
    @SuppressWarnings("unchecked")
    private void componentProblems(String project, String namespace, String at, Pipeline.Nei n,
                                   Map<String, PipelineCompiler.ComponentRef> refs, Map<String, Map<String, Object>> specs,
                                   List<String> problems) throws Exception {
        ComponentStore store = components.of(project);
        if (!refs.containsKey(n.component())) {
            boolean declared = store.list().stream().anyMatch(c -> n.component().equals(c.get("name")));
            if (declared) {
                VersionedStore.Version v = store.latestPublished(n.component());
                if (v == null) {
                    problems.add(at + ": component " + n.component() + " has no published version yet.");
                    return;
                }
                Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
                ComponentSpec.actions(v.content()).forEach((name, a) -> {
                    Map<String, String> d = new LinkedHashMap<>();
                    if (a.get("params") instanceof List<?> ps) {
                        for (Object o : ps) {
                            Map<String, Object> p = (Map<String, Object>) o;
                            if (p.get("default") != null && !String.valueOf(p.get("default")).isEmpty()) {
                                d.put(String.valueOf(p.get("name")), String.valueOf(p.get("default")));
                            }
                        }
                    }
                    defaults.put(name, d);
                });
                String dir = ComponentStore.dir(n.component(), v.version());
                refs.put(n.component(), new PipelineCompiler.ComponentRef(dir, ComponentSpec.home(v.content(), dir),
                        String.valueOf(v.content().get("kind")), defaults));
                specs.put(n.component(), v.content());
            } else if (kestra.listNamespaceFiles(namespace, "/nei/" + n.component()).isEmpty()) {
                problems.add(at + ": '" + n.component() + "' is neither a component of " + project + " nor an NEI folder nei/"
                        + n.component() + " of namespace " + namespace + ".");
                return;
            }
        }
        Map<String, Object> spec = specs.get(n.component());
        if (spec == null) {
            return;
        }
        Map<String, Object> action = ComponentSpec.actions(spec).get(n.action());
        if (action == null) {
            problems.add(at + ": component " + n.component() + " has no action " + n.action() + " (its actions: "
                    + ComponentSpec.actions(spec).keySet() + ").");
            return;
        }
        if (action.get("params") instanceof List<?> ps) {
            for (Object o : ps) {
                Map<String, Object> p = (Map<String, Object>) o;
                String name = String.valueOf(p.get("name"));
                boolean required = ProcessDefinition.bool(p.get("required"));
                boolean hasDefault = p.get("default") != null && !String.valueOf(p.get("default")).isEmpty();
                if (required && !hasDefault && !n.params().containsKey(name)) {
                    problems.add(at + ": " + n.component() + " " + n.action() + " needs the parameter " + name + ".");
                }
            }
        }
    }

    private List<String> publishProblems(String project, int version, ProcessDefinition def,
                                         Map<String, Map<String, String>> deployed) throws Exception {
        List<String> problems = new ArrayList<>();
        String namespace = processes.namespace(project);
        if (namespace == null || !namespace.equals(def.namespace)) {
            problems.add("namespace must be '%s' (the namespace of project %s), not '%s'.".formatted(namespace, project, def.namespace));
            return problems;
        }
        for (ProcessDefinition.Step step : def.steps) {
            if (!kestra.isProcessFlow(def.namespace, step.flow())) {
                problems.add("step '%s': flow %s.%s does not exist or is not labelled process: \"true\"."
                        .formatted(step.id(), def.namespace, step.flow()));
            }
        }
        if (problems.isEmpty()) {
            problems.addAll(pipelineProblems(project, version, def, deployed));
        }
        return problems;
    }

    // ------------------------------------------------------------------ helpers

    private void requireRole(UserStore.User me, Role role, String method, String path, Gateway.Caller who) {
        if (!me.role().atLeast(role)) {
            users.audit(me.username(), me.role(), "DENIED", method, path, 403, who.ip(), "needs " + role);
            throw new UserStore.Rejected(403, "Your role %s may not do this (needs %s).".formatted(me.role(), role));
        }
    }

    private void requireProject(String p) throws java.sql.SQLException {
        if (p == null || !processes.exists(p)) {
            throw new UserStore.Rejected(404, "There is no project %s.".formatted(p));
        }
    }

    private static int version(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 1) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new UserStore.Rejected(400, "'%s' is not a version number.".formatted(s));
        }
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String part : q.split("&")) {
            if (part.startsWith(name + "=")) {
                return Paths.decode(part.substring(name.length() + 1));
            }
        }
        return null;
    }

    private static void notFound(String method, String path) {
        throw new UserStore.Rejected(404, "Unknown gateway API: %s %s".formatted(method, path));
    }
}
