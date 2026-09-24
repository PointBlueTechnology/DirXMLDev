package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a {@link DriverSet} as IDM-as-code (see {@code docs/model.md}): pure content
 * files serialized by {@link CanonicalXml}, plus manifests ({@code driverset.xml},
 * {@code library/library.xml}, {@code drivers/<d>/driver.xml}) carrying names, kinds,
 * config, linkage and meta. Output is deterministic and idempotent — the same model
 * always produces the same bytes — which is what keeps git history clean.
 */
public final class AsCodeWriter {

    private AsCodeWriter() {
    }

    public static final String DRIVERSET_MANIFEST = "driverset.xml";
    public static final String LIBRARY_MANIFEST = "library.xml";
    public static final String DRIVER_MANIFEST = "driver.xml";
    public static final String CONFIG_VALUES_FILE = "config-values.xml";

    /** Write the whole driver set under {@code root} (created if needed; files overwritten). */
    public static void write(DriverSet ds, Path root) throws IOException {
        Files.createDirectories(root);

        // driverset manifest + GCVs
        Manifest m = new Manifest("driverset");
        m.attr("name", ds.name).attr("dn", ds.dn);
        m.meta(ds.meta);
        if (ds.configValues != null) {
            writeXml(root.resolve(CONFIG_VALUES_FILE), ds.configValues);
            m.child("config").attr("kind", Driver.CONFIG_VALUES).attr("file", CONFIG_VALUES_FILE);
        }
        List<Driver> drivers = new ArrayList<>(ds.drivers);
        drivers.sort(Comparator.comparing(d -> d.name));
        for (Driver d : drivers) {
            m.child("driver").attr("name", d.name).attr("dir", "drivers/" + fileSafe(d.name));
        }
        writeText(root.resolve(DRIVERSET_MANIFEST), m.toXml());

        // library
        Path lib = root.resolve("library");
        Files.createDirectories(lib);
        Manifest lm = new Manifest("library");
        writeArtifacts(lm, lib, sorted(ds.library.artifacts()), Scope.LIBRARY);
        writeText(lib.resolve(LIBRARY_MANIFEST), lm.toXml());

        // drivers
        for (Driver d : drivers) {
            writeDriver(d, root.resolve("drivers").resolve(fileSafe(d.name)));
        }
    }

    private static void writeDriver(Driver d, Path dir) throws IOException {
        Files.createDirectories(dir);
        Manifest m = new Manifest("driver");
        m.attr("name", d.name).attr("dn", d.dn).attr("shim-class", d.shimClass)
         .attr("shim-auth-server", d.shimAuthServer).attr("shim-auth-id", d.shimAuthId);
        m.meta(d.meta);

        List<String> kinds = new ArrayList<>(d.config.keySet());
        kinds.sort(null);
        for (String kind : kinds) {
            Element e = d.config.get(kind);
            if (e == null) {
                continue;
            }
            String file = kind + ".xml";
            writeXml(dir.resolve(file), e);
            m.child("config").attr("kind", kind).attr("file", file);
        }

        // the driver's icon: opaque bytes beside driver.xml, named by the extension Designer
        // recorded on the heavy-data attribute (docs/designer-new-project.md §7.2c)
        if (d.icon != null) {
            String file = "icon." + iconExtension(d);
            Files.write(dir.resolve(file), d.icon);
            m.child("icon").attr("file", file);
        }

        // artifacts: driver scope in the driver dir, channels in subdirs
        List<Artifact> driverScope = new ArrayList<>(d.policies);
        driverScope.addAll(d.resources);
        writeArtifacts(m, dir, sorted(driverScope), Scope.DRIVER);
        Files.createDirectories(dir.resolve("subscriber"));
        writeArtifacts(m, dir.resolve("subscriber"), sorted(new ArrayList<>(d.subscriber.policies)), Scope.SUBSCRIBER);
        Files.createDirectories(dir.resolve("publisher"));
        writeArtifacts(m, dir.resolve("publisher"), sorted(new ArrayList<>(d.publisher.policies)), Scope.PUBLISHER);

        // linkage, in set order then position
        Manifest linkage = m.child("linkage");
        for (PolicySet set : PolicySet.values()) {
            List<PolicyLink> links = d.links(set);
            if (links.isEmpty()) {
                continue;
            }
            Manifest s = linkage.child("set").attr("key", set.key);
            for (PolicyLink l : links) {
                s.child("link").attr("ref", l.ref).attr("order", Integer.toString(l.order));
            }
        }
        // entitlements: DirXML-Entitlement objects hanging directly off the driver (docs/entitlements.md
        // §2) — listed in the driver's own manifest, the way policies/resources are (a dedicated
        // entitlements.xml would just duplicate the driver manifest's job for one more object kind).
        if (!d.entitlements.isEmpty()) {
            Path entDir = dir.resolve("entitlements");
            Files.createDirectories(entDir);
            Set<String> usedEnt = new HashSet<>();
            List<Entitlement> ents = new ArrayList<>(d.entitlements);
            ents.sort(Comparator.comparing(e -> e.name));
            for (Entitlement e : ents) {
                String file = uniqueFile(fileSafe(e.name) + ".xml", usedEnt);
                if (e.definition != null) {
                    writeXml(entDir.resolve(file), e.definition);
                }
                Manifest em = m.child("entitlement").attr("name", e.name).attr("file", "entitlements/" + file);
                em.meta(e.meta);
            }
        }
        writeText(dir.resolve(DRIVER_MANIFEST), m.toXml());

        if (d.provisioning != null) {
            writeProvisioning(d.provisioning, dir.resolve("provisioning"));
        }
    }

    /**
     * Writes a driver's provisioning tree (see {@code docs/forms.md} §3): forms
     * pretty-printed by kind, one directory per PRD with its {@code definition.xml}/
     * {@code request.xml}/{@code process.xml}, and a {@code provisioning.xml}
     * manifest. {@code process.xml} is omitted when {@link Prd#process} is null or
     * is the very node already serialized inside {@link Prd#definition} (true on
     * the test vault: {@code XmlData} carries {@code <process>} inline) — writing
     * it twice would make the tree lie about there being two independent copies.
     */
    private static void writeProvisioning(Provisioning p, Path dir) throws IOException {
        Files.createDirectories(dir);
        Manifest m = new Manifest("provisioning");
        m.attr("dn", p.dn);
        m.meta(p.meta);

        Map<Form.Kind, Set<String>> usedByKind = new EnumMap<>(Form.Kind.class);
        List<Form> forms = new ArrayList<>(p.forms);
        forms.sort(Comparator.comparing((Form f) -> f.kind.dir).thenComparing(f -> f.name));
        for (Form f : forms) {
            Path kindDir = dir.resolve("forms").resolve(f.kind.dir);
            Files.createDirectories(kindDir);
            Set<String> used = usedByKind.computeIfAbsent(f.kind, k -> new HashSet<>());
            String file = uniqueFile(fileSafe(f.name) + ".form.json", used);
            String rel = "forms/" + f.kind.dir + "/" + file;
            writeText(dir.resolve(rel), prettyForm(f.json));
            Manifest fm = m.child("form").attr("kind", f.kind.dir).attr("name", f.name).attr("file", rel);
            fm.meta(f.meta);
        }

        Set<String> usedPrdDirs = new HashSet<>();
        List<Prd> prds = new ArrayList<>(p.prds);
        prds.sort(Comparator.comparing(pr -> pr.name));
        for (Prd prd : prds) {
            String prdDirName = uniqueFile(fileSafe(prd.name), usedPrdDirs);
            Path prdDir = dir.resolve("prds").resolve(prdDirName);
            Files.createDirectories(prdDir);
            if (prd.definition != null) {
                writeXml(prdDir.resolve("definition.xml"), prd.definition);
            }
            if (prd.request != null) {
                writeXml(prdDir.resolve("request.xml"), prd.request);
            }
            if (prd.process != null && !isChildOf(prd.process, prd.definition)) {
                writeXml(prdDir.resolve("process.xml"), prd.process);
            }
            Manifest pm = m.child("prd").attr("name", prd.name).attr("dir", "prds/" + prdDirName);
            for (Map.Entry<String, List<String>> e : prd.properties.entrySet()) {
                for (String v : e.getValue()) {
                    pm.child("property").attr("key", e.getKey()).text(v);
                }
            }
            pm.meta(prd.meta);
        }
        Set<String> usedObjectFiles = new HashSet<>();
        List<AppObject> objects = new ArrayList<>(p.objects);
        objects.sort(Comparator.comparing(AppObject::path, String.CASE_INSENSITIVE_ORDER));
        for (AppObject o : objects) {
            StringBuilder rel = new StringBuilder("objects");
            for (String seg : o.segments.subList(0, o.segments.size() - 1)) {
                rel.append('/').append(fileSafe(seg));
            }
            Path objDir = dir.resolve(rel.toString());
            Files.createDirectories(objDir);
            String file = uniqueFile(fileSafe(o.name()) + ".xml", usedObjectFiles);
            rel.append('/').append(file);
            writeText(dir.resolve(rel.toString()), DsObjectXml.write(o));
            Manifest om = m.child("object").attr("kind", o.kind().key).attr("path", o.path()).attr("file", rel.toString());
            om.meta(o.meta);
        }
        writeText(dir.resolve("provisioning.xml"), m.toXml());
    }

    /** {@code process} is a direct child of {@code definition} (same DOM node) — see {@link Prd} class doc. */
    private static boolean isChildOf(Element process, Element definition) {
        if (definition == null) {
            return false;
        }
        for (Element e : Xds.childrenByName(definition, "process")) {
            if (e == process) {
                return true;
            }
        }
        return false;
    }

    /** Re-pretty-prints a form document for the tree; falls back to the raw text if it doesn't parse as JSON. */
    private static String prettyForm(String json) {
        try {
            return com.pointblue.dirxml.dev.json.Json.pretty(com.pointblue.dirxml.dev.json.Json.parse(json == null ? "" : json));
        } catch (RuntimeException e) {
            return json == null ? "" : json;
        }
    }

    /** Write each artifact's content file and register it in the manifest. */
    private static void writeArtifacts(Manifest m, Path dir, List<Artifact> artifacts, Scope scope) throws IOException {
        Set<String> used = new HashSet<>();
        String prefix = scope == Scope.SUBSCRIBER ? "subscriber/" : scope == Scope.PUBLISHER ? "publisher/" : "";
        for (Artifact a : artifacts) {
            String file = uniqueFile(fileSafe(a.name) + extension(a), used);
            Manifest e = m.child("artifact").attr("kind", a.kind()).attr("scope", a.scope.key)
                .attr("name", a.name).attr("file", prefix + file);
            if (a instanceof Resource) {
                Resource r = (Resource) a;
                e.attr("content-type", r.contentType);
                if (r.isText()) {
                    e.attr("text", "true");
                    writeText(dir.resolve(file), r.text == null ? "" : r.text);
                } else if (r.content != null) {
                    writeXml(dir.resolve(file), r.content);
                }
            } else {
                Policy p = (Policy) a;
                if (p.content != null) {
                    writeXml(dir.resolve(file), p.content);
                }
            }
            e.meta(a.meta);
        }
    }

    // ---- naming ----

    public static String extension(Artifact a) {
        if (a instanceof Policy) {
            return ".policy.xml";
        }
        Resource r = (Resource) a;
        if (r.isMappingTable()) {
            return ".mapping-table.xml";
        }
        if (r.isEcmaScript()) {
            return ".js";
        }
        if (r.isGcvDef()) {
            return ".gcv.xml";
        }
        return r.isText() ? ".txt" : ".resource.xml";
    }

    /**
     * The file extension for a driver's icon: exactly what Designer recorded (kept as written,
     * case and all, so the round trip back into a project is faithful), reduced to the
     * characters a file name can safely hold, and {@code "gif"} — Designer's own default, and
     * what every project sampled but two drivers carries — when the driver records none.
     */
    public static String iconExtension(Driver d) {
        String ext = d.iconExtension == null ? "" : d.iconExtension.strip();
        StringBuilder sb = new StringBuilder();
        for (char c : ext.toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "gif" : sb.toString();
    }

    /**
     * Every artifact's content file relative to the tree root, by artifact path — exactly the
     * names {@link #write} gives them (per-scope order and collision suffixes included), so a
     * reader of the model can name files without reading the manifests again.
     */
    public static Map<String, String> files(DriverSet ds) {
        Map<String, String> out = new LinkedHashMap<>();
        fileNames(out, "library/", sorted(ds.library.artifacts()), "");
        for (Driver d : ds.drivers) {
            String dir = "drivers/" + fileSafe(d.name) + "/";
            List<Artifact> driverScope = new ArrayList<>(d.policies);
            driverScope.addAll(d.resources);
            fileNames(out, dir, sorted(driverScope), "");
            fileNames(out, dir, sorted(new ArrayList<>(d.subscriber.policies)), "subscriber/");
            fileNames(out, dir, sorted(new ArrayList<>(d.publisher.policies)), "publisher/");
        }
        return out;
    }

    private static void fileNames(Map<String, String> out, String dir, List<Artifact> artifacts, String prefix) {
        Set<String> used = new HashSet<>();
        for (Artifact a : artifacts) {
            out.put(a.path(), dir + prefix + uniqueFile(fileSafe(a.name) + extension(a), used));
        }
    }

    /** A filesystem-safe file/dir name; the manifest keeps the real name. */
    public static String fileSafe(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append((c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) ? '_' : c);
        }
        String s = sb.toString().strip();
        return s.isEmpty() ? "_" : s;
    }

    private static String uniqueFile(String file, Set<String> used) {
        String candidate = file;
        int n = 2;
        while (!used.add(candidate.toLowerCase())) {
            int dot = file.indexOf('.');
            candidate = dot < 0 ? file + "~" + n : file.substring(0, dot) + "~" + n + file.substring(dot);
            n++;
        }
        return candidate;
    }

    private static List<Artifact> sorted(List<Artifact> in) {
        List<Artifact> out = new ArrayList<>(in);
        out.sort(Comparator.comparing((Artifact a) -> a.kind()).thenComparing(a -> a.name));
        return out;
    }

    // ---- io ----

    private static void writeXml(Path file, Element e) throws IOException {
        writeText(file, CanonicalXml.serialize(e));
    }

    private static void writeText(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A tiny deterministic manifest builder (attributes in insertion order, escaped). */
    static final class Manifest {
        private final String tag;
        private final List<String[]> attrs = new ArrayList<>();
        private final List<Manifest> children = new ArrayList<>();
        private String text;

        Manifest(String tag) {
            this.tag = tag;
        }

        Manifest attr(String k, String v) {
            if (v != null) {
                attrs.add(new String[]{k, v});
            }
            return this;
        }

        Manifest child(String tag) {
            Manifest c = new Manifest(tag);
            children.add(c);
            return c;
        }

        Manifest text(String v) {
            this.text = v;
            return this;
        }

        Manifest meta(Map<String, String> meta) {
            List<String> keys = new ArrayList<>(meta.keySet());
            keys.sort(null);
            for (String k : keys) {
                Manifest c = child("meta").attr("key", k);
                c.text = meta.get(k);
            }
            return this;
        }

        String toXml() {
            StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            render(sb, 0);
            return sb.toString();
        }

        private void render(StringBuilder sb, int depth) {
            sb.append("  ".repeat(depth)).append('<').append(tag);
            for (String[] a : attrs) {
                sb.append(' ').append(a[0]).append("=\"").append(escAttr(a[1])).append('"');
            }
            if (children.isEmpty() && text == null) {
                sb.append("/>\n");
                return;
            }
            if (text != null && children.isEmpty()) {
                sb.append('>').append(escText(text)).append("</").append(tag).append(">\n");
                return;
            }
            sb.append(">\n");
            for (Manifest c : children) {
                c.render(sb, depth + 1);
            }
            sb.append("  ".repeat(depth)).append("</").append(tag).append(">\n");
        }

        private static String escText(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String escAttr(String s) {
            return escText(s).replace("\"", "&quot;").replace("\n", "&#10;").replace("\r", "&#13;").replace("\t", "&#9;");
        }
    }
}
