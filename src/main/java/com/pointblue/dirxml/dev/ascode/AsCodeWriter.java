package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
        writeText(dir.resolve(DRIVER_MANIFEST), m.toXml());
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

    static String extension(Artifact a) {
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

    /** A filesystem-safe file/dir name; the manifest keeps the real name. */
    static String fileSafe(String name) {
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
