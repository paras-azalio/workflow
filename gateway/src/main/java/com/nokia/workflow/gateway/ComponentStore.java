package com.nokia.workflow.gateway;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The components of a project (table {@code component}, versions in {@code component_version}, content column
 * {@code spec}): jars and scripts the steps call. A version's files are namespace files of the project under
 * {@code components/<name>/v<version>/}, with the {@code component.properties} the wrapper reads, written whenever
 * the version is saved; a published version's files no longer change.
 */
final class ComponentStore {

    static final String ROOT = "components";
    private static final java.util.regex.Pattern FILE_PATH = java.util.regex.Pattern.compile("[A-Za-z0-9_.][A-Za-z0-9_ .&()+-]*(/[A-Za-z0-9_.][A-Za-z0-9_ .&()+-]*)*");

    private final Db db;
    private final String schema;
    private final String project;
    private final UserStore audit;
    private final Kestra kestra;
    private final Map<String, String> runtimes;
    private final VersionedStore versions;

    ComponentStore(Db db, String schema, String project, UserStore audit, Kestra kestra, Map<String, String> runtimes) {
        this.db = db;
        this.schema = schema;
        this.project = project;
        this.audit = audit;
        this.kestra = kestra;
        this.runtimes = runtimes;
        this.versions = new VersionedStore(db, schema, new VersionedStore.Spec("COMPONENT", "component", "component_id",
                "component_version", "spec"), audit, ComponentSpec::problems);
    }

    ComponentStore of(String otherProject) {
        return otherProject.equals(project) ? this : new ComponentStore(db, schema, otherProject, audit, kestra, runtimes);
    }

    void ensureSchema() throws SQLException {
        db.with(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                st.execute("CREATE TABLE IF NOT EXISTS " + t("component") + " ("
                        + " component_id BIGSERIAL PRIMARY KEY,"
                        + " project VARCHAR(64) NOT NULL,"
                        + " name VARCHAR(64) NOT NULL,"
                        + " description TEXT,"
                        + " created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
                        + " UNIQUE (project, name))");
                versions.ensureVersionTable(st);
            }
            return null;
        });
    }

    static String dir(String name, int version) {
        return ROOT + "/" + name + "/v" + version;
    }

    Map<String, Object> declare(String name, String kind, String description, String actor, String namespace) throws Exception {
        if (name == null || !ComponentSpec.NAME.matcher(name).matches()) {
            throw new UserStore.Rejected(400, "The component name must be 1-32 lower-case letters, digits and _ (starting with a letter), e.g. hlr_check.");
        }
        if (!"WRAPPED".equals(kind) && !"STANDALONE".equals(kind)) {
            throw new UserStore.Rejected(400, "Choose WRAPPED (an NEI jar) or STANDALONE (any jar, Python or shell program).");
        }
        long id = db.transaction(c -> {
            if (VersionedStore.one(c, "SELECT 1 FROM " + t("component") + " WHERE project = ? AND name = ?", project, name) != null) {
                throw new UserStore.Rejected(409, "Component %s is already declared in %s.".formatted(name, project));
            }
            long newId = ((Number) VersionedStore.one(c, "INSERT INTO " + t("component") + " (project, name, description) VALUES (?,?,?)"
                    + " RETURNING component_id", project, name, description).get("component_id")).longValue();
            versions.insertFirst(c, newId, "DRAFT", ComponentSpec.blank(kind), actor);
            return newId;
        });
        writeProperties(name, 1, namespace);
        audit.audit(actor, Role.ADMIN, "COMPONENT_DECLARE", null, null, null, null, label(name) + " (" + kind + ")");
        return Map.of("name", name, "version", 1, "state", "DRAFT", "component_id", id);
    }

    List<Map<String, Object>> list() throws SQLException {
        return db.with(c -> VersionedStore.all(c, "SELECT k.name, k.description,"
                + " (SELECT v.spec ->> 'kind' FROM " + t("component_version") + " v WHERE v.component_id = k.component_id ORDER BY v.version DESC LIMIT 1) AS kind,"
                + " (SELECT max(version) FROM " + t("component_version") + " v WHERE v.component_id = k.component_id AND v.state = 'PUBLISHED') AS published_version,"
                + " (SELECT max(version) FROM " + t("component_version") + " v WHERE v.component_id = k.component_id AND v.state = 'DRAFT') AS draft_version"
                + " FROM " + t("component") + " k WHERE k.project = ? ORDER BY k.name", project));
    }

    List<Map<String, Object>> versions(String name) throws SQLException {
        return versions.versions(requireId(name));
    }

    VersionedStore.Version version(String name, int version) throws SQLException {
        return versions.version(requireId(name), version);
    }

    VersionedStore.Version latestPublished(String name) throws SQLException {
        Long id = id(name);
        return id == null ? null : versions.latestPublished(id);
    }

    /** A new draft copies the version it starts from, its uploaded files included. */
    VersionedStore.Version createDraft(String name, Integer from, String actor, String namespace) throws Exception {
        VersionedStore.Version v = versions.createDraft(requireId(name), label(name), from, actor);
        VersionedStore.Version source = from != null ? version(name, from) : latestPublished(name);
        if (source != null && source.version() != v.version()) {
            String fromDir = dir(name, source.version());
            for (Map<String, Object> f : kestra.listNamespaceFiles(namespace, "/" + fromDir)) {
                String path = String.valueOf(f.get("path"));
                if (path.endsWith("/component.properties")) {
                    continue;
                }
                byte[] content = kestra.namespaceFileBytes(namespace, path);
                if (content != null) {
                    kestra.putNamespaceFile(namespace, "/" + dir(name, v.version()) + path.substring(("/" + fromDir).length()), content);
                }
            }
        }
        writeProperties(name, v.version(), namespace);
        return v;
    }

    List<String> updateDraft(String name, int version, String json, int revision, String actor, String namespace) throws Exception {
        List<String> problems = versions.updateDraft(requireId(name), label(name), version, json, revision, actor);
        writeProperties(name, version, namespace);
        return problems;
    }

    void publish(String name, int version, String actor, String namespace) throws Exception {
        versions.publish(requireId(name), label(name), version, actor, content -> filesProblems(name, version, content, namespace));
        writeProperties(name, version, namespace);
        writePointer(name, namespace);
    }

    void discardDraft(String name, int version, String actor, String namespace) throws Exception {
        versions.discardDraft(requireId(name), label(name), version, actor);
        kestra.deleteNamespaceFile(namespace, "/" + dir(name, version));
    }

    void retire(String name, int version, String actor, String namespace) throws Exception {
        versions.retire(requireId(name), label(name), version, actor);
        writePointer(name, namespace);
    }

    /** The KV key whose value is the folder of the component's latest published version. */
    static String pointerKey(String name) {
        return "COMPONENT_" + name.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Keeps KV {@code COMPONENT_<NAME>} at the latest published version's folder (removed when none is left), so a
     * hand-written flow calls {@code sh bin/component.sh "{{ kv('COMPONENT_<NAME>') }}"} without naming a version.
     */
    private void writePointer(String name, String namespace) throws Exception {
        VersionedStore.Version v = latestPublished(name);
        if (v == null) {
            kestra.deleteKv(namespace, pointerKey(name));
        } else {
            kestra.putKv(namespace, pointerKey(name), dir(name, v.version()));
        }
    }

    // ------------------------------------------------------------------ files

    List<Map<String, Object>> files(String name, int version, String namespace) throws Exception {
        VersionedStore.Version v = requireVersion(name, version);
        String home = ComponentSpec.home(v.content(), dir(name, version));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : kestra.listNamespaceFiles(namespace, "/" + home)) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(f);
            m.put("name", String.valueOf(f.get("path")).substring(("/" + home + "/").length()));
            out.add(m);
        }
        return out;
    }

    /** What an upload wrote, and the one top folder of a zip that was left out (e.g. "target"), if any. */
    record Uploaded(List<String> written, String droppedFolder) {
    }

    /**
     * One uploaded file, or a zip unpacked into {@code path} (a folder then, "" = the component's root). A zip whose files
     * are all inside one folder (a build's target/) is unpacked without it. Every name is checked before anything is written.
     */
    Uploaded upload(String name, int version, String path, byte[] content, boolean unzip, String actor, String namespace) throws Exception {
        requireDraft(name, version);
        String target = path == null ? "" : path.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (!target.isEmpty() && !allowed(target)) {
            throw new UserStore.Rejected(400, "The file path '%s' is not allowed (letters, digits, _ . - & ( ) + and spaces, folders with /).".formatted(path));
        }
        List<String> written = new ArrayList<>();
        String dropped = null;
        String base = "/" + dir(name, version) + (target.isEmpty() ? "" : "/" + target);
        if (unzip) {
            List<String> entries = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    String entry = entryName(e);
                    if (entry != null && !allowed(entry)) {
                        throw new UserStore.Rejected(400, "The zip holds a file the gateway does not take: %s".formatted(entry));
                    }
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
            }
            if (entries.isEmpty()) {
                throw new UserStore.Rejected(400, "The zip holds no files.");
            }
            String first = entries.get(0);
            String top = first.contains("/") ? first.substring(0, first.indexOf('/') + 1) : null;
            if (top != null && entries.stream().allMatch(x -> x.startsWith(top))) {
                dropped = top.substring(0, top.length() - 1);
            }
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                    String entry = entryName(e);
                    if (entry == null) {
                        continue;
                    }
                    String file = dropped == null ? entry : entry.substring(dropped.length() + 1);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    zip.transferTo(bytes);
                    kestra.putNamespaceFile(namespace, base + "/" + file, bytes.toByteArray());
                    written.add((target.isEmpty() ? "" : target + "/") + file);
                }
            }
        } else {
            if (target.isEmpty()) {
                throw new UserStore.Rejected(400, "Say where the file goes (its name, e.g. lib/tool.jar).");
            }
            kestra.putNamespaceFile(namespace, base, content);
            written.add(target);
        }
        audit.audit(actor, Role.ADMIN, "COMPONENT_FILES", null, null, null, null,
                "%s v%d: %d file(s) uploaded%s".formatted(label(name), version, written.size(), unzip ? " from a zip" : ""));
        return new Uploaded(written, dropped);
    }

    /** Letters, digits, _ . - &amp; ( ) + and spaces, folders with /, dot files too; never a "." or ".." part. */
    private static boolean allowed(String path) {
        return FILE_PATH.matcher(path).matches() && java.util.Arrays.stream(path.split("/")).noneMatch(x -> x.equals(".") || x.equals(".."));
    }

    private static String entryName(ZipEntry e) {
        String entry = e.getName().replace('\\', '/').replaceAll("^/+", "");
        return e.isDirectory() || entry.isEmpty() ? null : entry;
    }

    void deleteFile(String name, int version, String path, String actor, String namespace) throws Exception {
        requireDraft(name, version);
        String p = path == null ? "" : path.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (p.isEmpty() || p.contains("..") || "component.properties".equals(p)) {
            throw new UserStore.Rejected(400, "Say which file to delete.");
        }
        kestra.deleteNamespaceFile(namespace, "/" + dir(name, version) + "/" + p);
        audit.audit(actor, Role.ADMIN, "COMPONENT_FILES", null, null, null, null, "%s v%d: %s deleted".formatted(label(name), version, p));
    }

    /** Publishing checks the files the definition names exist (an uploaded component without files cannot run). */
    @SuppressWarnings("unchecked")
    private List<String> filesProblems(String name, int version, Map<String, Object> spec, String namespace) throws Exception {
        List<String> problems = new ArrayList<>();
        String home = ComponentSpec.home(spec, dir(name, version));
        List<Map<String, Object>> present = kestra.listNamespaceFiles(namespace, "/" + home);
        java.util.Set<String> names = new java.util.HashSet<>();
        present.forEach(f -> names.add(String.valueOf(f.get("path")).substring(("/" + home + "/").length())));
        names.remove("component.properties");
        if (names.isEmpty()) {
            problems.add("The component has no files in %s: upload its jar / script first.".formatted(home));
        }
        if ("STANDALONE".equals(spec.get("kind")) && spec.get("standalone") instanceof Map<?, ?> s) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{file:([^}]+)}").matcher(String.valueOf(((Map<String, Object>) s).get("command")));
            while (m.find()) {
                if (!names.contains(m.group(1).trim())) {
                    problems.add("standalone.command names {file:%s}, which is not among the component's files.".formatted(m.group(1).trim()));
                }
            }
        }
        if ("WRAPPED".equals(spec.get("kind")) && names.stream().noneMatch(n -> n.endsWith(".jar"))) {
            problems.add("A wrapped component needs its NEI jar among its files.");
        }
        return problems;
    }

    private void writeProperties(String name, int version, String namespace) throws Exception {
        VersionedStore.Version v = requireVersion(name, version);
        if (ComponentSpec.problems(v.content()).isEmpty()) {
            kestra.putNamespaceFile(namespace, "/" + dir(name, version) + "/component.properties",
                    ComponentSpec.properties(v.content(), name, dir(name, version), runtimes).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private VersionedStore.Version requireVersion(String name, int version) throws SQLException {
        VersionedStore.Version v = version(name, version);
        if (v == null) {
            throw new UserStore.Rejected(404, "Component %s has no version %d.".formatted(name, version));
        }
        return v;
    }

    private void requireDraft(String name, int version) throws SQLException {
        if (!"DRAFT".equals(requireVersion(name, version).state())) {
            throw new UserStore.Rejected(409, "Version %d of %s is not a draft: its files no longer change (make a new draft).".formatted(version, name));
        }
    }

    private Long id(String name) throws SQLException {
        return db.with(c -> {
            Map<String, Object> r = VersionedStore.one(c, "SELECT component_id FROM " + t("component") + " WHERE project = ? AND name = ?", project, name);
            return r == null ? null : ((Number) r.get("component_id")).longValue();
        });
    }

    private long requireId(String name) throws SQLException {
        Long id = id(name);
        if (id == null) {
            throw new UserStore.Rejected(404, "There is no component %s in %s.".formatted(name, project));
        }
        return id;
    }

    private String label(String name) {
        return "component " + project + "/" + name;
    }

    private String t(String table) {
        return schema + "." + table;
    }
}
