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
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads an IDM-as-code directory (as written by {@link AsCodeWriter}) back into a
 * {@link DriverSet}. Manifests are the registry: every artifact, config blob and
 * link comes from them; content files are loaded by the manifest's {@code file}.
 */
public final class AsCodeReader {

    private AsCodeReader() {
    }

    public static DriverSet read(Path root) throws IOException {
        Element dsm = manifest(root.resolve(AsCodeWriter.DRIVERSET_MANIFEST));
        DriverSet ds = new DriverSet(attr(dsm, "name"));
        ds.dn = attr(dsm, "dn");
        readMeta(dsm, ds.meta);
        for (Element c : children(dsm, "config")) {
            if (Driver.CONFIG_VALUES.equals(attr(c, "kind"))) {
                ds.configValues = xml(root.resolve(attr(c, "file")));
            }
        }

        // library
        Path lib = root.resolve("library");
        if (Files.exists(lib.resolve(AsCodeWriter.LIBRARY_MANIFEST))) {
            Element lm = manifest(lib.resolve(AsCodeWriter.LIBRARY_MANIFEST));
            for (Element a : children(lm, "artifact")) {
                readArtifact(a, lib, null, ds.library.policies, ds.library.resources);
            }
        }

        // drivers
        for (Element d : children(dsm, "driver")) {
            Path dir = root.resolve(attr(d, "dir"));
            ds.drivers.add(readDriver(dir));
        }
        return ds;
    }

    private static Driver readDriver(Path dir) throws IOException {
        Element m = manifest(dir.resolve(AsCodeWriter.DRIVER_MANIFEST));
        Driver d = new Driver(attr(m, "name"));
        d.dn = attr(m, "dn");
        d.shimClass = attr(m, "shim-class");
        d.shimAuthServer = attr(m, "shim-auth-server");
        d.shimAuthId = attr(m, "shim-auth-id");
        readMeta(m, d.meta);
        for (Element c : children(m, "config")) {
            d.config.put(attr(c, "kind"), xml(dir.resolve(attr(c, "file"))));
        }
        Element icon = child(m, "icon");
        if (icon != null && attr(icon, "file") != null) {
            Path iconFile = dir.resolve(attr(icon, "file"));
            if (Files.exists(iconFile)) {
                d.icon = Files.readAllBytes(iconFile);
                String name = iconFile.getFileName().toString();
                int dot = name.lastIndexOf('.');
                d.iconExtension = dot > 0 ? name.substring(dot + 1) : null;
            }
        }
        for (Element a : children(m, "artifact")) {
            Scope scope = Scope.byKey(attr(a, "scope"));
            List<Policy> policies = scope == Scope.SUBSCRIBER ? d.subscriber.policies
                : scope == Scope.PUBLISHER ? d.publisher.policies : d.policies;
            readArtifact(a, dir, d.name, policies, d.resources);
        }
        for (Element set : children(child(m, "linkage"), "set")) {
            PolicySet ps = PolicySet.byKey(attr(set, "key"));
            for (Element l : children(set, "link")) {
                d.links.add(new PolicyLink(ps, attr(l, "ref"), Integer.parseInt(attr(l, "order"))));
            }
        }
        promoteKnownUnknownLinkage(d);
        for (Element ee : children(m, "entitlement")) {
            Path file = dir.resolve(attr(ee, "file"));
            Entitlement e = new Entitlement(attr(ee, "name"), Files.exists(file) ? xml(file) : null);
            readMeta(ee, e.meta);
            d.entitlements.add(e);
        }
        Path provManifest = dir.resolve("provisioning").resolve("provisioning.xml");
        if (Files.exists(provManifest)) {
            d.provisioning = readProvisioning(dir.resolve("provisioning"));
        }
        return d;
    }

    private static Provisioning readProvisioning(Path dir) throws IOException {
        Element m = manifest(dir.resolve("provisioning.xml"));
        Provisioning p = new Provisioning();
        p.dn = attr(m, "dn");
        readMeta(m, p.meta);
        for (Element fe : children(m, "form")) {
            Form.Kind kind = Form.Kind.byDir(attr(fe, "kind"));
            Path file = dir.resolve(attr(fe, "file"));
            String json = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            Form f = new Form(kind, attr(fe, "name"), json);
            readMeta(fe, f.meta);
            p.forms.add(f);
        }
        for (Element pe : children(m, "prd")) {
            Prd prd = new Prd(attr(pe, "name"));
            Path prdDir = dir.resolve(attr(pe, "dir"));
            Path defFile = prdDir.resolve("definition.xml");
            if (Files.exists(defFile)) {
                prd.definition = xml(defFile);
            }
            Path reqFile = prdDir.resolve("request.xml");
            if (Files.exists(reqFile)) {
                prd.request = xml(reqFile);
            }
            Path procFile = prdDir.resolve("process.xml");
            if (Files.exists(procFile)) {
                prd.process = xml(procFile);
            } else if (prd.definition != null) {
                List<Element> procs = Xds.childrenByName(prd.definition, "process");
                if (!procs.isEmpty()) {
                    prd.process = procs.get(0);
                }
            }
            for (Element propEl : children(pe, "property")) {
                prd.properties.computeIfAbsent(attr(propEl, "key"), k -> new ArrayList<>()).add(propEl.getTextContent());
            }
            readMeta(pe, prd.meta);
            p.prds.add(prd);
        }
        for (Element oe : children(m, "object")) {
            Path file = dir.resolve(attr(oe, "file"));
            if (!Files.exists(file)) {
                continue;
            }
            AppObject o = DsObjectXml.read(Files.readString(file, StandardCharsets.UTF_8), List.of(attr(oe, "path").split("/")));
            readMeta(oe, o.meta);
            p.objects.add(o);
        }
        return p;
    }

    private static void readArtifact(Element a, Path baseDir, String driver,
                                     List<Policy> policies, List<Resource> resources) throws IOException {
        String kind = attr(a, "kind");
        String name = attr(a, "name");
        Scope scope = a.hasAttribute("scope") ? Scope.byKey(attr(a, "scope")) : Scope.LIBRARY;
        Path file = baseDir.resolve(attr(a, "file"));
        if ("policy".equals(kind)) {
            Policy p = new Policy(name, scope, driver, Files.exists(file) ? xml(file) : null);
            readMeta(a, p.meta);
            policies.add(p);
        } else {
            Resource r = new Resource(name, scope, driver, attr(a, "content-type"));
            if ("true".equals(attr(a, "text"))) {
                r.text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            } else if (Files.exists(file)) {
                r.content = xml(file);
            }
            readMeta(a, r.meta);
            resources.add(r);
        }
    }

    /**
     * Trees written before {@link PolicySet} knew Startup (15) / Shutdown (16)
     * keep those DirXML-Policies values as {@code linkage.unknown.n} metas
     * ({@code dn#order#setId}). Promote any meta whose set id is now in the enum
     * into {@link Driver#links} and drop it so a subsequent write emits
     * {@code <set key="startup">} (and does not duplicate a named link).
     */
    static void promoteKnownUnknownLinkage(Driver d) {
        List<String> keys = new ArrayList<>();
        for (String k : d.meta.keySet()) {
            if (k.startsWith("linkage.unknown.")) {
                keys.add(k);
            }
        }
        for (String k : keys) {
            String raw = d.meta.get(k);
            ParsedUnknown parsed = parseUnknownLinkage(raw);
            if (parsed == null) {
                continue;
            }
            PolicySet set = PolicySet.findById(parsed.setId);
            if (set == null) {
                continue;
            }
            String ref = refFromLinkageDn(parsed.dn, d.name);
            boolean dup = false;
            for (PolicyLink l : d.links) {
                if (l.set == set && l.ref.equals(ref)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                d.links.add(new PolicyLink(set, ref, parsed.order));
            }
            d.meta.remove(k);
        }
    }

    static ParsedUnknown parseUnknownLinkage(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        int h2 = v.lastIndexOf('#');
        int h1 = h2 < 0 ? -1 : v.lastIndexOf('#', h2 - 1);
        if (h1 < 0) {
            return null;
        }
        try {
            int order = Integer.parseInt(v.substring(h1 + 1, h2));
            int setId = Integer.parseInt(v.substring(h2 + 1));
            return new ParsedUnknown(v.substring(0, h1), order, setId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Same rule as ExportReader.resolveRef: first CN is the artifact name; second
     * CN of Library / Publisher / Subscriber picks scope; anything else is
     * driver-scope of this driver.
     */
    static String refFromLinkageDn(String dn, String driverName) {
        String[] comps = dn.split(",", 3);
        String name = stripCn(comps.length > 0 ? comps[0] : "");
        String second = stripCn(comps.length > 1 ? comps[1] : "");
        if (second.equalsIgnoreCase("Library")) {
            return Artifact.path(Scope.LIBRARY, null, name);
        }
        if (second.equalsIgnoreCase("Publisher")) {
            return Artifact.path(Scope.PUBLISHER, driverName, name);
        }
        if (second.equalsIgnoreCase("Subscriber")) {
            return Artifact.path(Scope.SUBSCRIBER, driverName, name);
        }
        return Artifact.path(Scope.DRIVER, driverName, name);
    }

    private static String stripCn(String comp) {
        String s = comp.trim();
        int eq = s.indexOf('=');
        return eq >= 0 ? s.substring(eq + 1).trim() : s;
    }

    static final class ParsedUnknown {
        final String dn;
        final int order;
        final int setId;

        ParsedUnknown(String dn, int order, int setId) {
            this.dn = dn;
            this.order = order;
            this.setId = setId;
        }
    }

    // ---- helpers ----

    private static Element manifest(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("missing manifest: " + file);
        }
        return xml(file);
    }

    private static Element xml(Path file) throws IOException {
        return CanonicalXml.parse(Files.readString(file, StandardCharsets.UTF_8)).getDocumentElement();
    }

    private static void readMeta(Element parent, Map<String, String> into) {
        for (Element m : children(parent, "meta")) {
            into.put(attr(m, "key"), m.getTextContent());
        }
    }

    private static String attr(Element e, String name) {
        return e != null && e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private static Element child(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(k))) {
                out.add((Element) k);
            }
        }
        return out;
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }
}
