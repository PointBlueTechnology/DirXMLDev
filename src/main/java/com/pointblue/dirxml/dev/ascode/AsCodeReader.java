package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
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
        return d;
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
