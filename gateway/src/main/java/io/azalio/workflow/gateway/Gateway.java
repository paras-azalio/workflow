package io.azalio.workflow.gateway;

import io.azalio.workflow.wrapper.SimpleJson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every request the browser (or a script) sends: login / logout, our own API, or Kestra – authenticated,
 * CSRF-checked, authorized by role, audited, then forwarded.
 */
final class Gateway implements HttpHandler {

    static final String SESSION_COOKIE = "CR_SESSION";
    /** The non-HttpOnly cookie Kestra's UI reads to know it is logged in (BasicAuthService.BASIC_AUTH_FLAG_COOKIE_NAME). */
    static final String KESTRA_FLAG_COOKIE = "kestraBasicAuthenticated";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** Kestra endpoints its own login page needs before anyone is logged in. */
    private static final Set<String> ANONYMOUS_API = Set.of("/api/v1/configs", "/api/v1/configs/login",
            "/api/v1/basicAuthValidationErrors");
    private static final int MAX_JSON_BODY = 64 * 1024;
    private static final Duration BASIC_CACHE = Duration.ofMinutes(5);

    /** Who sent a request and how (a cookie session is CSRF-checked, a Basic header is not). */
    record Caller(UserStore.User user, boolean cookie, String ip) {
    }

    private record CachedBasic(UserStore.User user, Instant until) {
    }

    private final UserStore users;
    private final Kestra kestra;
    private final Rules rules;
    private final CrApi api;
    private final java.util.function.Predicate<String> isProjectNamespace;
    private final Map<String, CachedBasic> basicCache = new ConcurrentHashMap<>();

    Gateway(UserStore users, ProcessStore processes, ActivityStore activities, Steps steps, Decisions decisions, OrderSearch search,
            ComponentStore components, Platform platform, Kestra kestra, Rules rules, java.util.function.Predicate<String> isProjectNamespace,
            String defaultProject) {
        this.users = users;
        this.kestra = kestra;
        this.rules = rules;
        this.isProjectNamespace = isProjectNamespace;
        this.api = new CrApi(users, processes, activities, steps, decisions, search, components, platform, kestra, defaultProject, this::forgetBasicLogins);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            dispatch(ex);
        } catch (UserStore.Rejected e) {
            json(ex, e.status, Map.of("message", e.getMessage()));
        } catch (Exception e) {
            System.err.println("gateway: " + ex.getRequestMethod() + " " + ex.getRequestURI() + ": " + e);
            try {
                json(ex, 500, Map.of("message", "The gateway failed: " + e.getMessage()));
            } catch (IOException ignored) {
                // response already started
            }
        } finally {
            ex.close();
        }
    }

    private void dispatch(HttpExchange ex) throws Exception {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        Paths.Normalized path;
        try {
            path = Paths.normalize(ex.getRequestURI().getRawPath());
        } catch (IllegalArgumentException e) {
            json(ex, 400, Map.of("message", e.getMessage()));
            return;
        }
        String p = path.decoded();
        String ip = ex.getRemoteAddress() == null ? "" : ex.getRemoteAddress().getAddress().getHostAddress();

        if ("/api/v1/login".equals(p) || "/api/v1/logout".equals(p)) {
            if (!"POST".equals(method)) {
                json(ex, 405, Map.of("message", "Use POST."));
            } else if (!sameOrigin(ex.getRequestHeaders())) {
                json(ex, 403, Map.of("message", "Cross-site request refused."));
            } else if ("/api/v1/login".equals(p)) {
                login(ex, ip);
            } else {
                logout(ex);
            }
            return;
        }
        boolean isApi = p.startsWith("/api/") || p.startsWith("/cr/api/");
        if (!isApi || (ANONYMOUS_API.contains(p) && SAFE_METHODS.contains(method))) {
            kestra.forward(ex, path.encoded());   // the UI's static files and its pre-login endpoints
            return;
        }

        Optional<Caller> caller = authenticate(ex, ip);
        if (caller.isEmpty()) {
            json(ex, 401, Map.of("message", "Not logged in."));
            return;
        }
        Caller who = caller.get();
        if (who.cookie() && !SAFE_METHODS.contains(method) && !sameOrigin(ex.getRequestHeaders())) {
            users.audit(who.user().username(), who.user().role(), "DENIED", method, p, 403, ip, "cross-site request");
            json(ex, 403, Map.of("message", "Cross-site request refused."));
            return;
        }
        if (p.startsWith("/cr/api/")) {
            api.handle(ex, method, p, who);
            return;
        }

        Rules.Decision d = rules.decide(method, p);
        Denial denied = denial(d, who.user().role());
        if (denied != null) {
            users.audit(who.user().username(), who.user().role(), "DENIED", method, p, denied.status(), ip, denied.message());
            json(ex, denied.status(), Map.of("message", denied.message()));
            return;
        }
        if (!SAFE_METHODS.contains(method)) {
            users.audit(who.user().username(), who.user().role(), "WRITE", method, p, null, ip, null);
        }
        kestra.forward(ex, path.encoded());
    }

    private record Denial(int status, String message) {
    }

    /** Null when allowed, otherwise the status and the reason shown to the user. */
    private Denial denial(Rules.Decision d, Role role) throws IOException, InterruptedException {
        if (d.minRole() == null) {
            return new Denial(403, d.reason() != null ? d.reason() : "Not allowed.");
        }
        if (!d.allows(role)) {
            return new Denial(403, "Your role %s may not do this (needs %s).".formatted(role, d.minRole())
                    + (d.reason() == null ? "" : " " + d.reason()));
        }
        if (role == Role.ADMIN) {
            return null;
        }
        if (d.need() == Rules.Need.PROCESS_FLOW && !kestra.isProcessFlow(d.namespace(), d.flowId())) {
            return new Denial(403, "%s.%s is not a process flow; only admins may run it.".formatted(d.namespace(), d.flowId()));
        }
        if (d.need() == Rules.Need.PROCESS_EXECUTION) {
            String[] flow = kestra.executionFlow(d.executionId());
            if (flow == null) {
                return new Denial(404, "There is no execution %s.".formatted(d.executionId()));
            }
            if (!isProjectNamespace.test(flow[0]) || !kestra.isProcessFlow(flow[0], flow[1])) {
                return new Denial(403, "Execution %s is not one of a process flow; only admins may act on it."
                        .formatted(d.executionId()));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ authentication

    private Optional<Caller> authenticate(HttpExchange ex, String ip) throws SQLException {
        String token = cookie(ex.getRequestHeaders(), SESSION_COOKIE);
        if (token != null) {
            Optional<UserStore.User> u = users.session(token);
            if (u.isPresent()) {
                return Optional.of(new Caller(u.get(), true, ip));
            }
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Basic ", 0, 6)) {
            return basic(auth.substring(6).trim(), ip).map(u -> new Caller(u, false, ip));
        }
        return Optional.empty();
    }

    /** Scripts (deploy.sh, run.sh) log in with a Basic header; a success is remembered briefly. */
    private Optional<UserStore.User> basic(String encoded, String ip) throws SQLException {
        String key = UserStore.sha256(encoded);
        CachedBasic c = basicCache.get(key);
        if (c != null && Instant.now().isBefore(c.until())) {
            return Optional.of(c.user());
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        Optional<UserStore.User> u = users.authenticate(decoded.substring(0, colon), decoded.substring(colon + 1), ip);
        u.ifPresent(user -> basicCache.put(key, new CachedBasic(user, Instant.now().plus(BASIC_CACHE))));
        return u;
    }

    /** Any change to users ends remembered Basic logins at once. */
    void forgetBasicLogins() {
        basicCache.clear();
    }

    private void login(HttpExchange ex, String ip) throws Exception {
        Map<String, Object> body = readJson(ex);
        Optional<UserStore.User> u = users.authenticate(str(body.get("username")), str(body.get("password")), ip);
        if (u.isEmpty()) {
            json(ex, 401, Map.of("message", "Invalid credentials."));
            return;
        }
        String token = users.createSession(u.get(), ip, ex.getRequestHeaders().getFirst("User-Agent"));
        boolean secure = isHttps(ex);
        ex.getResponseHeaders().add("Set-Cookie", cookieHeader(SESSION_COOKIE, token, true, secure, -1));
        ex.getResponseHeaders().add("Set-Cookie", cookieHeader(KESTRA_FLAG_COOKIE, "true", false, secure, -1));
        ex.sendResponseHeaders(204, -1);
    }

    private void logout(HttpExchange ex) throws Exception {
        String token = cookie(ex.getRequestHeaders(), SESSION_COOKIE);
        if (token != null) {
            Optional<UserStore.User> u = users.session(token);
            users.endSession(token);
            u.ifPresent(user -> users.audit(user.username(), user.role(), "LOGOUT", null, null, 204, null, null));
        }
        boolean secure = isHttps(ex);
        ex.getResponseHeaders().add("Set-Cookie", cookieHeader(SESSION_COOKIE, "", true, secure, 0));
        ex.getResponseHeaders().add("Set-Cookie", cookieHeader(KESTRA_FLAG_COOKIE, "", false, secure, 0));
        ex.sendResponseHeaders(204, -1);
    }

    // ------------------------------------------------------------------ helpers

    /** A cookie-authenticated write must come from our own page: Origin, else Referer, must name the request's host. */
    static boolean sameOrigin(Headers h) {
        String host = h.getFirst("Host");
        String origin = h.getFirst("Origin");
        String source = origin != null && !"null".equals(origin) ? origin : h.getFirst("Referer");
        if (host == null || source == null) {
            return false;
        }
        try {
            URI u = URI.create(source);
            String authority = u.getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String cookie(Headers h, String name) {
        List<String> all = h.get("Cookie");
        if (all == null) {
            return null;
        }
        for (String header : all) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) {
                    return p.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    private static String cookieHeader(String name, String value, boolean httpOnly, boolean secure, int maxAge) {
        return name + "=" + value + "; Path=/; SameSite=Strict" + (httpOnly ? "; HttpOnly" : "") + (secure ? "; Secure" : "")
                + (maxAge >= 0 ? "; Max-Age=" + maxAge : "");
    }

    private static boolean isHttps(HttpExchange ex) {
        return "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
    }

    /** The raw request body (an uploaded file), at most {@code maxBytes}. */
    static byte[] readBytes(HttpExchange ex, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) {
                    throw new UserStore.Rejected(413, "The file is larger than %d MB.".formatted(maxBytes >> 20));
                }
            }
        }
        return out.toByteArray();
    }

    static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        return readJson(ex, MAX_JSON_BODY);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readJson(HttpExchange ex, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) {
                    throw new UserStore.Rejected(413, "The request body is too large.");
                }
            }
        }
        if (out.size() == 0) {
            return Map.of();
        }
        try {
            Object parsed = SimpleJson.parse(out.toString(StandardCharsets.UTF_8));
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (RuntimeException e) {
            // reported below
        }
        throw new UserStore.Rejected(400, "The request body must be a JSON object.");
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static void json(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = SimpleJson.writeCompact(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
