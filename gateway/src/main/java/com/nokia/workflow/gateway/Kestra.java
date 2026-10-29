package com.nokia.workflow.gateway;

import com.nokia.workflow.wrapper.SimpleJson;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kestra behind the gateway: forwards allowed requests with Kestra's single technical login (streaming bodies
 * both ways, so uploads, downloads and server-sent events pass unbuffered) and answers the questions the rules
 * ask about flows and executions.
 */
final class Kestra {

    /** Request headers never forwarded: hop-by-hop, the ones Java's HTTP client sets itself, and credentials. */
    private static final Set<String> SKIP_REQUEST = Set.of("host", "connection", "content-length", "expect", "upgrade",
            "keep-alive", "transfer-encoding", "te", "trailer", "proxy-authorization", "proxy-connection",
            "authorization", "cookie", "http2-settings");
    private static final Set<String> SKIP_RESPONSE = Set.of("connection", "content-length", "keep-alive",
            "transfer-encoding", "date", "www-authenticate");
    private static final Set<String> SKIP_COOKIES = Set.of(Gateway.SESSION_COOKIE, "BASIC_AUTH");
    private static final Duration LOOKUP_TTL = Duration.ofSeconds(30);

    private record Cached(Object value, Instant until) {
    }

    private final URI base;
    private final String authorization;
    private final String tenant;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    Kestra(URI base, String user, String password, String tenant) {
        this.base = base;
        this.authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.tenant = tenant;
    }

    /** Forwards the exchange to Kestra on the normalized path and streams the answer back. */
    void forward(HttpExchange ex, String encodedPath) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        URI target = base.resolve(encodedPath + (query == null ? "" : "?" + query));
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        Headers in = ex.getRequestHeaders();
        boolean hasBody = in.containsKey("Transfer-encoding") || parseLong(in.getFirst("Content-length")) > 0;
        HttpRequest.Builder b = HttpRequest.newBuilder(target)
                .method(method, hasBody ? HttpRequest.BodyPublishers.ofInputStream(ex::getRequestBody)
                        : HttpRequest.BodyPublishers.noBody())
                .header("Authorization", authorization);
        for (Map.Entry<String, List<String>> h : in.entrySet()) {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            if (SKIP_REQUEST.contains(name)) {
                continue;
            }
            for (String v : h.getValue()) {
                try {
                    b.header(h.getKey(), v);
                } catch (IllegalArgumentException restricted) {
                    // a header Java's HTTP client sets itself; Kestra gets the client's own value
                }
            }
        }
        String cookies = forwardedCookies(in.get("Cookie"));
        if (!cookies.isEmpty()) {
            b.header("Cookie", cookies);
        }
        String ip = ex.getRemoteAddress() == null ? "" : ex.getRemoteAddress().getAddress().getHostAddress();
        b.header("X-Forwarded-For", ip);
        b.header("X-Forwarded-Host", String.valueOf(in.getFirst("Host")));

        HttpResponse<InputStream> r;
        try {
            r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Gateway.json(ex, 502, Map.of("message", "Kestra did not answer (interrupted)."));
            return;
        } catch (IOException e) {
            Gateway.json(ex, 502, Map.of("message", "Kestra is not reachable: " + e.getMessage()));
            return;
        }
        if (r.statusCode() == 401 && encodedPath.startsWith("/api/")) {
            r.body().close();
            Gateway.json(ex, 502, Map.of("message", "The gateway's Kestra login was refused - check KESTRA_USER / KESTRA_PASSWORD of the gateway."));
            return;
        }
        Headers out = ex.getResponseHeaders();
        for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) {
            String name = h.getKey().toLowerCase(Locale.ROOT);
            if (name.startsWith(":") || SKIP_RESPONSE.contains(name)) {
                continue;
            }
            out.put(h.getKey(), h.getValue());
        }
        long length = r.headers().firstValueAsLong("content-length").orElse(-1);
        int status = r.statusCode();
        boolean noBody = "HEAD".equals(method) || status == 204 || status == 304 || length == 0;
        ex.sendResponseHeaders(status, noBody ? -1 : Math.max(length, 0));
        try (InputStream body = r.body(); OutputStream os = ex.getResponseBody()) {
            if (noBody) {
                return;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = body.read(buf)) >= 0) {
                os.write(buf, 0, n);
                os.flush();   // server-sent events must reach the browser at once
            }
        }
    }

    /** Is the flow a process flow (label {@code process: "true"})? Cached briefly. */
    boolean isProcessFlow(String namespace, String flowId) throws IOException, InterruptedException {
        String key = "flow:" + namespace + "/" + flowId;
        Object v = cached(key);
        if (v == null) {
            Map<String, Object> flow = getJson("/api/v1/" + tenant + "/flows/" + Paths.encode(namespace) + "/" + Paths.encode(flowId));
            v = flow != null && hasLabel(flow.get("labels"), "process", "true");
            cache.put(key, new Cached(v, Instant.now().plus(LOOKUP_TTL)));
        }
        return (Boolean) v;
    }

    /** The value of a flow's label, or null (also when the flow does not exist). Not cached: used at publish time. */
    String flowLabel(String namespace, String flowId, String key) throws IOException, InterruptedException {
        Map<String, Object> flow = getJson("/api/v1/" + tenant + "/flows/" + Paths.encode(namespace) + "/" + Paths.encode(flowId));
        if (flow != null && flow.get("labels") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && key.equals(m.get("key"))) {
                    return String.valueOf(m.get("value"));
                }
            }
        }
        return null;
    }

    /** Kestra's own check of a flow source: the constraint messages, empty when the flow is valid. */
    List<String> validateFlow(String yaml) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/flows/validate"))
                .header("Authorization", authorization).header("Content-Type", "application/x-yaml")
                .POST(HttpRequest.BodyPublishers.ofString(yaml)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("Kestra answered %d when validating a flow: %s".formatted(r.statusCode(), r.body()));
        }
        List<String> out = new ArrayList<>();
        if (SimpleJson.parse(r.body()) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("constraints") != null && !String.valueOf(m.get("constraints")).isBlank()) {
                    out.add(String.valueOf(m.get("constraints")));
                }
            }
        }
        return out;
    }

    /** Creates the flow, or updates it when it exists. */
    void deployFlow(String namespace, String flowId, String yaml) throws IOException, InterruptedException {
        boolean exists = getJson("/api/v1/" + tenant + "/flows/" + Paths.encode(namespace) + "/" + Paths.encode(flowId)) != null;
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/flows"
                        + (exists ? "/" + Paths.encode(namespace) + "/" + Paths.encode(flowId) : "")))
                .header("Authorization", authorization).header("Content-Type", "application/x-yaml");
        HttpRequest request = exists ? b.PUT(HttpRequest.BodyPublishers.ofString(yaml)).build()
                : b.POST(HttpRequest.BodyPublishers.ofString(yaml)).build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new KestraError(r.statusCode() == 401 ? 502 : 400, "Kestra refused flow %s: %s".formatted(flowId, r.body()));
        }
    }

    /** The execution's state (e.g. PAUSED), or null when Kestra does not know it. */
    String executionState(String executionId) throws IOException, InterruptedException {
        Map<String, Object> e = getJson("/api/v1/" + tenant + "/executions/" + Paths.encode(executionId));
        return e != null && e.get("state") instanceof Map<?, ?> s ? String.valueOf(s.get("current")) : null;
    }

    /** Kills an execution and its subflow executions; a finished one is left as it is (Kestra answers 409). */
    void kill(String executionId) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/executions/"
                        + Paths.encode(executionId) + "/actions/kill?isOnKillCascade=true"))
                .header("Authorization", authorization).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2 && r.statusCode() != 409) {
            throw new KestraError(r.statusCode() == 401 ? 502 : r.statusCode(), "Kestra refused to kill " + executionId + ": " + r.body());
        }
    }

    /** Resumes a paused execution with these inputs (the Pause task's onResume inputs). */
    void resume(String executionId, Map<String, String> inputs) throws IOException, InterruptedException {
        String boundary = "----crgateway" + java.util.UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/executions/"
                        + Paths.encode(executionId) + "/actions/resume"))
                .header("Authorization", authorization)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, inputs, Map.of()))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new KestraError(r.statusCode() == 401 ? 502 : r.statusCode(), "Kestra refused to resume " + executionId + ": " + r.body());
        }
    }

    /** The namespace and flow id of an execution, or null when Kestra does not know it. */
    String[] executionFlow(String executionId) throws IOException, InterruptedException {
        String key = "exec:" + executionId;
        Object v = cached(key);
        if (v == null) {
            Map<String, Object> e = getJson("/api/v1/" + tenant + "/executions/" + Paths.encode(executionId));
            if (e == null) {
                return null;
            }
            v = new String[] {String.valueOf(e.get("namespace")), String.valueOf(e.get("flowId"))};
            cache.put(key, new Cached(v, Instant.now().plus(LOOKUP_TTL)));
        }
        return (String[]) v;
    }

    /** A file input of an execution. */
    record FilePart(String filename, byte[] content) {
    }

    /** Kestra refused a request; {@code status} and its message are passed on to the user. */
    static final class KestraError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        KestraError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** The value of a namespace KV entry, or null when there is none. */
    /**
     * The project's config file in the namespace: the one {@code config/*.properties} (when there are several, the
     * one named after the namespace); null when there is none.
     */
    @SuppressWarnings("unchecked")
    String projectConfig(String namespace) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/namespaces/"
                        + Paths.encode(namespace) + "/files/directory?path=%2Fconfig"))
                .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200 || !(SimpleJson.parse(r.body()) instanceof List<?> files)) {
            return null;
        }
        List<String> names = files.stream().filter(f -> f instanceof Map<?, ?> m && "File".equals(String.valueOf(m.get("type"))))
                .map(f -> String.valueOf(((Map<String, Object>) f).get("fileName"))).filter(n -> n.endsWith(".properties")).sorted().toList();
        if (names.isEmpty()) {
            return null;
        }
        String name = names.contains(namespace + ".properties") ? namespace + ".properties" : names.get(0);
        return namespaceFile(namespace, "/config/" + name);
    }

    /** A namespace file's text, or null when there is none. */
    String namespaceFile(String namespace, String path) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/namespaces/"
                        + Paths.encode(namespace) + "/files?path=" + java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", authorization).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) {
            return null;
        }
        if (r.statusCode() != 200) {
            throw new IOException("Kestra answered %d for namespace file %s%s.".formatted(r.statusCode(), namespace, path));
        }
        return r.body();
    }

    Object kv(String namespace, String key) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/namespaces/"
                        + Paths.encode(namespace) + "/kv/" + Paths.encode(key)))
                .header("Authorization", authorization).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) {
            return null;
        }
        if (r.statusCode() != 200) {
            throw new IOException("Kestra answered %d for KV %s.%s.".formatted(r.statusCode(), namespace, key));
        }
        Object parsed = SimpleJson.parse(r.body());
        return parsed instanceof Map<?, ?> m && m.containsKey("value") && m.containsKey("type") ? m.get("value") : parsed;
    }

    /**
     * Starts an execution of a flow with these inputs (multipart, as Kestra's own execute form sends them) and
     * labels; returns Kestra's answer (the execution).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> startExecution(String namespace, String flowId, List<String> labels, Map<String, String> fields,
                                       Map<String, FilePart> files) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder();
        for (String l : labels) {
            query.append(query.isEmpty() ? "?" : "&").append("labels=")
                 .append(java.net.URLEncoder.encode(l, StandardCharsets.UTF_8));
        }
        String boundary = "----crgateway" + java.util.UUID.randomUUID().toString().replace("-", "");
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/" + tenant + "/executions/"
                        + Paths.encode(namespace) + "/" + Paths.encode(flowId) + query))
                .header("Authorization", authorization)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, fields, files))).build(),
                HttpResponse.BodyHandlers.ofString());
        Object parsed;
        try {
            parsed = SimpleJson.parse(r.body());
        } catch (RuntimeException e) {
            parsed = null;
        }
        if (r.statusCode() / 100 != 2) {
            String message = parsed instanceof Map<?, ?> m && m.get("message") != null ? String.valueOf(m.get("message")) : r.body();
            throw new KestraError(r.statusCode() == 401 ? 502 : r.statusCode(), "Kestra refused to start " + flowId + ": " + message);
        }
        return parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
    }

    static byte[] multipart(String boundary, Map<String, String> fields, Map<String, FilePart> files) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + headerSafe(f.getKey()) + "\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n");
            write(out, f.getValue());
            write(out, "\r\n");
        }
        for (Map.Entry<String, FilePart> f : files.entrySet()) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + headerSafe(f.getKey())
                    + "\"; filename=\"" + headerSafe(f.getValue().filename()) + "\"\r\nContent-Type: application/octet-stream\r\n\r\n");
            out.writeBytes(f.getValue().content());
            write(out, "\r\n");
        }
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static String headerSafe(String s) {
        return s.replace("\"", "%22").replace("\r", "").replace("\n", "");
    }

    private static void write(java.io.ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private Object cached(String key) {
        Cached c = cache.get(key);
        if (c == null || Instant.now().isAfter(c.until())) {
            cache.remove(key);
            return null;
        }
        return c.value();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String path) throws IOException, InterruptedException {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(base.resolve(path))
                .header("Authorization", authorization).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) {
            return null;
        }
        if (r.statusCode() != 200) {
            throw new IOException("Kestra answered %d to GET %s.".formatted(r.statusCode(), path));
        }
        Object parsed = SimpleJson.parse(r.body());
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }

    @SuppressWarnings("unchecked")
    static boolean hasLabel(Object labels, String key, String value) {
        if (labels instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && key.equals(m.get("key")) && value.equalsIgnoreCase(String.valueOf(m.get("value")))) {
                    return true;
                }
            }
        }
        return false;
    }

    static String forwardedCookies(List<String> headers) {
        if (headers == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String header : headers) {
            for (String part : header.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                String name = eq < 0 ? p : p.substring(0, eq);
                if (p.isEmpty() || SKIP_COOKIES.contains(name)) {
                    continue;
                }
                sb.append(sb.isEmpty() ? "" : "; ").append(p);
            }
        }
        return sb.toString();
    }

    private static long parseLong(String s) {
        try {
            return s == null ? -1 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
