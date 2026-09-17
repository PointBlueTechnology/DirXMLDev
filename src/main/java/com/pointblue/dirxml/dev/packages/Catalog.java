package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The package catalog repository (docs/packages.md §2): a directory kept in
 * git holding every package jar fetched or imported, a deterministic unpacked
 * form for review, an index ({@code catalog.json}) and the update sites to
 * fetch from ({@code sites.properties}).
 *
 * <pre>
 *   catalog.json                       generated index
 *   sites.properties                   update sites: name=url
 *   jars/&lt;SHORT&gt;/&lt;SHORT&gt;_&lt;ver&gt;.jar     the package exactly as downloaded/imported
 *   packages/&lt;SHORT&gt;/&lt;ver&gt;/             the unpacked, diffable form (generated; never hand-edited)
 *   deprecations.properties            mirrored from a site, if any
 * </pre>
 */
public final class Catalog {

    public static final String DEFAULT_SITE_1 = "https://nu.novell.com/designer/packages/idm/updatesite1_0_0/";
    public static final String DEFAULT_SITE_2 = "https://nu.novell.com/designer/packages/idm/updatesite2_0_0/";

    /** One version of one package, as recorded in the index. */
    public static final class VersionEntry {
        public String sha256;
        public String source;
        public String fetched;      // ISO-8601 instant
        public List<Dependency> dependencies = new ArrayList<>();
        public List<Dependency.Feature> features = new ArrayList<>();
        public List<Dependency.SupportedDriver> supportedDrivers = new ArrayList<>();
        public boolean hasPrompts;
    }

    /** One package (a SHORT name), across every version the catalog holds. */
    public static final class PackageEntry {
        public String shortName;
        public String id;              // idm package id, stable across versions
        public String symbolicName;
        public String displayName;
        public int type;
        public boolean base;
        public final Map<String, VersionEntry> versions =
            new TreeMap<>(Comparator.comparing(PackageVersion::parse));

        /** Newest version present, or null if none. */
        public String newestVersion() {
            return versions.keySet().stream().max(Comparator.comparing(PackageVersion::parse)).orElse(null);
        }
    }

    public final Path dir;
    private final Map<String, PackageEntry> packages;

    private Catalog(Path dir, Map<String, PackageEntry> packages) {
        this.dir = dir;
        this.packages = packages;
    }

    public Map<String, PackageEntry> packages() {
        return packages;
    }

    public PackageEntry get(String shortName) {
        return packages.get(shortName);
    }

    /**
     * The jar file holding one version of one package, whether or not it is on disk
     * ({@code jars/<SHORT>/<SHORT>_<version>.jar} — the layout {@link #add} writes).
     */
    public Path jar(String shortName, String version) {
        return dir.resolve("jars").resolve(shortName).resolve(shortName + "_" + version + ".jar");
    }

    /** Every package id → SHORT name in this catalog (dependency/feature entries reference packages by id). */
    public Map<String, String> idIndex() {
        Map<String, String> out = new LinkedHashMap<>();
        for (PackageEntry e : packages.values()) {
            if (e.id != null) {
                out.put(e.id, e.shortName);
            }
        }
        return out;
    }

    public static Catalog open(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("jars"));
        Files.createDirectories(dir.resolve("packages"));
        Path sites = dir.resolve("sites.properties");
        if (!Files.exists(sites)) {
            Files.writeString(sites, "Novell Public=" + DEFAULT_SITE_1 + "\nNovell Public 2.0=" + DEFAULT_SITE_2 + "\n");
        }
        Path idx = dir.resolve("catalog.json");
        Map<String, PackageEntry> packages = new TreeMap<>();
        if (Files.exists(idx)) {
            packages = readIndex(Files.readString(idx, StandardCharsets.UTF_8));
        }
        return new Catalog(dir, packages);
    }

    public Map<String, String> sites() throws IOException {
        Path f = dir.resolve("sites.properties");
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.exists(f)) {
            java.util.Properties p = new java.util.Properties();
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            }
            for (String name : p.stringPropertyNames()) {
                out.put(name, p.getProperty(name));
            }
        }
        return out;
    }

    // ---- add ----

    public static final class AddResult {
        public boolean added;       // false if this exact jar was already in the catalog
        public String shortName;
        public String version;
        public String sha256;
        public String refusal;      // non-null if the jar failed verification (nothing was written)

        public boolean ok() {
            return refusal == null;
        }
    }

    /**
     * Verifies the jar (every stored checksum must recompute, or be a known
     * exception — the package-level {@code checksum} attribute, or a
     * {@code DirXML-Job}: docs/spikes/package-checksums.md), then copies it
     * into {@code jars/}, regenerates its unpacked form, and updates the
     * index. Idempotent: adding the same jar again is a no-op.
     */
    public AddResult add(Path jar, String source) throws IOException {
        AddResult r = new AddResult();
        PackageJar p;
        try {
            p = PackageJar.read(jar);
        } catch (Exception e) {
            r.refusal = jar + ": " + e.getMessage();
            return r;
        }
        ChecksumAudit audit = ChecksumAudit.of(p);
        List<ChecksumAudit.Line> bad = audit.mismatches().stream()
            .filter(l -> !"package".equals(l.kind) && !"DirXML-Job".equals(l.objectClass))
            .toList();
        if (!bad.isEmpty()) {
            r.refusal = jar + ": checksum verification failed:\n"
                + bad.stream().map(ChecksumAudit.Line::toString).collect(Collectors.joining("\n"));
            return r;
        }
        r.shortName = p.shortName;
        r.version = p.version;
        byte[] bytes = Files.readAllBytes(jar);
        r.sha256 = sha256(bytes);

        PackageEntry existing = packages.get(p.shortName);
        VersionEntry existingVersion = existing == null ? null : existing.versions.get(p.version);
        if (existingVersion != null && r.sha256.equals(existingVersion.sha256)) {
            r.added = false;
            return r;
        }

        Path jarDir = dir.resolve("jars").resolve(p.shortName);
        Files.createDirectories(jarDir);
        Path jarOut = jarDir.resolve(p.shortName + "_" + p.version + ".jar");
        Files.write(jarOut, bytes);

        Path versionDir = dir.resolve("packages").resolve(p.shortName).resolve(p.version);
        unpack(p, versionDir);

        PackageEntry entry = existing != null ? existing : new PackageEntry();
        entry.shortName = p.shortName;
        entry.id = p.pkg.getAttribute("id");
        entry.symbolicName = p.symbolicName;
        entry.displayName = p.pkg.getAttribute("name");
        entry.type = p.type;
        entry.base = p.basePackage;
        packages.put(p.shortName, entry);

        Element directiveRoot = p.directive == null ? null : NxslCanonical.parse(p.directive).getDocumentElement();
        VersionEntry ve = new VersionEntry();
        ve.sha256 = r.sha256;
        ve.source = source;
        ve.fetched = Instant.now().toString();
        ve.dependencies = Dependency.parseDependencies(directiveRoot);
        ve.features = Dependency.Feature.parse(directiveRoot);
        ve.supportedDrivers = Dependency.SupportedDriver.parse(directiveRoot);
        ve.hasPrompts = p.items.stream().anyMatch(it -> it.contentType != null
            && it.contentType.toLowerCase().contains("pkg-prompt"));
        entry.versions.put(p.version, ve);

        save();
        r.added = true;
        return r;
    }

    // ---- unpacked form ----

    private static final String[] FOLDER_ONLY_ATTRS = {"base-package", "category", "category-folder", "checksum",
        "directive-checksum", "id", "name", "symbolic-name", "type", "version"};

    private void unpack(PackageJar p, Path versionDir) throws IOException {
        if (Files.exists(versionDir)) {
            deleteRecursive(versionDir);
        }
        Files.createDirectories(versionDir);

        // package.xml: the <package> element's metadata + installation directive, without package-folder or the
        // base64 license/readme blobs (those go to README.md).
        Document freshDoc = com.novell.xml.dom.DocumentFactory.newDocument();
        Element pkgCopy = (Element) freshDoc.importNode(p.pkg, true);
        freshDoc.appendChild(pkgCopy);
        List<Node> toRemove = new ArrayList<>();
        for (Node c = pkgCopy.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                String n = c.getNodeName();
                if (n.equals("package-folder") || n.equals("license") || n.equals("readme")) {
                    toRemove.add(c);
                }
            }
        }
        for (Node n : toRemove) {
            pkgCopy.removeChild(n);
        }
        Files.writeString(versionDir.resolve("package.xml"), NxslCanonical.serialize(freshDoc), StandardCharsets.UTF_8);

        // objects/<folderId>-<folderName>/<name>.xml and prompts/<name>.xml, sorted by folder id then name.
        Path objectsDir = versionDir.resolve("objects");
        Path promptsDir = versionDir.resolve("prompts");
        List<PackageJar.Item> sorted = new ArrayList<>(p.items);
        sorted.sort(Comparator.<PackageJar.Item, Integer>comparing(it -> it.folderId)
            .thenComparing(it -> it.name == null ? "" : it.name));
        for (PackageJar.Item it : sorted) {
            Document d = com.novell.xml.dom.DocumentFactory.newDocument();
            Element obj = (Element) d.importNode(it.dsObject, true);
            d.appendChild(obj);
            replaceDirectiveAttribute(d, obj, it.directive);
            boolean isPrompt = it.contentType != null && it.contentType.toLowerCase().contains("pkg-prompt");
            Path targetDir = isPrompt ? promptsDir : objectsDir.resolve(sanitize(it.folderId + "-" + it.folderName));
            Files.createDirectories(targetDir);
            Path file = targetDir.resolve(sanitize(it.name) + ".xml");
            Files.writeString(file, NxslCanonical.serialize(d), StandardCharsets.UTF_8);
        }

        // README.md: decoded readme + license
        String readme = base64Text(PackageChecksum.child(p.pkg, "readme"));
        String license = base64Text(PackageChecksum.child(p.pkg, "license"));
        StringBuilder md = new StringBuilder("# ").append(p.pkg.getAttribute("name")).append('\n');
        if (readme != null && !readme.isBlank()) {
            md.append('\n').append(readme).append('\n');
        }
        if (license != null && !license.isBlank()) {
            md.append("\n## License\n\n").append(license).append('\n');
        }
        Files.writeString(versionDir.resolve("README.md"), md.toString(), StandardCharsets.UTF_8);
    }

    /** Replace the {@code idm-installationdirective} ds-attribute (base64) with a decoded {@code <installation-directive>} element, in place. */
    private static void replaceDirectiveAttribute(Document d, Element dsObject, String directiveXml) {
        Element attrs = PackageChecksum.child(dsObject, "ds-attributes");
        if (attrs == null || directiveXml == null) {
            return;
        }
        for (Element a : PackageChecksum.children(attrs, "ds-attribute")) {
            if ("idm-installationdirective".equalsIgnoreCase(a.getAttribute("ds-attr-name"))) {
                Element decoded = (Element) d.importNode(NxslCanonical.parse(directiveXml).getDocumentElement(), true);
                attrs.replaceChild(decoded, a);
                return;
            }
        }
    }

    private static String base64Text(Element e) {
        if (e == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            }
        }
        String b64 = sb.toString().replaceAll("\\s", "");
        if (b64.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return sb.toString();
        }
    }

    static String sanitize(String name) {
        return name == null ? "" : name.replace('/', '_').replace('\\', '_').replace(':', '_');
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(f);
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- index (catalog.json) persistence ----

    private void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> pkgsJson = new LinkedHashMap<>();
        List<String> shorts = new ArrayList<>(packages.keySet());
        shorts.sort(String::compareTo);
        for (String s : shorts) {
            PackageEntry e = packages.get(s);
            Map<String, Object> pj = new LinkedHashMap<>();
            pj.put("id", e.id);
            pj.put("symbolicName", e.symbolicName);
            pj.put("displayName", e.displayName);
            pj.put("type", (long) e.type);
            pj.put("base", e.base);
            Map<String, Object> versionsJson = new LinkedHashMap<>();
            List<String> vers = new ArrayList<>(e.versions.keySet());
            vers.sort(Comparator.comparing(PackageVersion::parse));
            for (String v : vers) {
                versionsJson.put(v, versionJson(e.versions.get(v)));
            }
            pj.put("versions", versionsJson);
            pkgsJson.put(s, pj);
        }
        root.put("packages", pkgsJson);
        Files.writeString(dir.resolve("catalog.json"), Json.write(root) + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, Object> versionJson(VersionEntry ve) {
        Map<String, Object> vj = new LinkedHashMap<>();
        vj.put("sha256", ve.sha256);
        vj.put("source", ve.source);
        vj.put("fetched", ve.fetched);
        List<Object> deps = new ArrayList<>();
        for (Dependency d : ve.dependencies) {
            Map<String, Object> dj = new LinkedHashMap<>();
            dj.put("name", d.name);
            dj.put("packageId", d.packageId);
            dj.put("type", (long) d.type);
            dj.put("minVersion", d.minVersion);
            dj.put("maxVersion", d.maxVersion);
            dj.put("versions", new ArrayList<Object>(d.versions));
            deps.add(dj);
        }
        vj.put("dependencies", deps);
        List<Object> mand = new ArrayList<>();
        List<Object> opt = new ArrayList<>();
        for (Dependency.Feature f : ve.features) {
            Map<String, Object> fj = new LinkedHashMap<>();
            fj.put("packageId", f.packageId);
            fj.put("displayName", f.displayName);
            fj.put("group", f.group);
            (f.mandatory ? mand : opt).add(fj);
        }
        vj.put("featuresMandatory", mand);
        vj.put("featuresOptional", opt);
        List<Object> sds = new ArrayList<>();
        for (Dependency.SupportedDriver s : ve.supportedDrivers) {
            Map<String, Object> sj = new LinkedHashMap<>();
            sj.put("displayName", s.displayName);
            sj.put("driverId", s.driverId);
            sj.put("id", s.id);
            sds.add(sj);
        }
        vj.put("supportedDrivers", sds);
        vj.put("hasPrompts", ve.hasPrompts);
        return vj;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, PackageEntry> readIndex(String json) {
        Map<String, PackageEntry> out = new TreeMap<>();
        Object root = Json.parse(json);
        Map<String, Object> pkgsJson = Json.asMap(Json.asMap(root).get("packages"));
        for (Map.Entry<String, Object> pe : pkgsJson.entrySet()) {
            Map<String, Object> pj = Json.asMap(pe.getValue());
            PackageEntry e = new PackageEntry();
            e.shortName = pe.getKey();
            e.id = Json.asString(pj.get("id"));
            e.symbolicName = Json.asString(pj.get("symbolicName"));
            e.displayName = Json.asString(pj.get("displayName"));
            e.type = Json.asInt(pj.get("type"), 0);
            e.base = Json.asBool(pj.get("base"));
            Map<String, Object> versionsJson = Json.asMap(pj.get("versions"));
            for (Map.Entry<String, Object> ve : versionsJson.entrySet()) {
                e.versions.put(ve.getKey(), readVersion(Json.asMap(ve.getValue())));
            }
            out.put(e.shortName, e);
        }
        return out;
    }

    private static VersionEntry readVersion(Map<String, Object> vj) {
        VersionEntry ve = new VersionEntry();
        ve.sha256 = Json.asString(vj.get("sha256"));
        ve.source = Json.asString(vj.get("source"));
        ve.fetched = Json.asString(vj.get("fetched"));
        for (Object o : Json.asList(vj.get("dependencies"))) {
            Map<String, Object> dj = Json.asMap(o);
            Dependency d = new Dependency();
            d.name = Json.asString(dj.get("name"));
            d.packageId = Json.asString(dj.get("packageId"));
            d.type = Json.asInt(dj.get("type"), 2);
            d.minVersion = Json.asString(dj.get("minVersion"));
            d.maxVersion = Json.asString(dj.get("maxVersion"));
            for (Object v : Json.asList(dj.get("versions"))) {
                d.versions.add(Json.asString(v));
            }
            ve.dependencies.add(d);
        }
        for (Object o : Json.asList(vj.get("featuresMandatory"))) {
            ve.features.add(readFeature(Json.asMap(o), true));
        }
        for (Object o : Json.asList(vj.get("featuresOptional"))) {
            ve.features.add(readFeature(Json.asMap(o), false));
        }
        for (Object o : Json.asList(vj.get("supportedDrivers"))) {
            Map<String, Object> sj = Json.asMap(o);
            Dependency.SupportedDriver s = new Dependency.SupportedDriver();
            s.displayName = Json.asString(sj.get("displayName"));
            s.driverId = Json.asString(sj.get("driverId"));
            s.id = Json.asString(sj.get("id"));
            ve.supportedDrivers.add(s);
        }
        ve.hasPrompts = Json.asBool(vj.get("hasPrompts"));
        return ve;
    }

    private static Dependency.Feature readFeature(Map<String, Object> fj, boolean mandatory) {
        Dependency.Feature f = new Dependency.Feature();
        f.packageId = Json.asString(fj.get("packageId"));
        f.displayName = Json.asString(fj.get("displayName"));
        f.group = Json.asString(fj.get("group"));
        f.mandatory = mandatory;
        return f;
    }
}
