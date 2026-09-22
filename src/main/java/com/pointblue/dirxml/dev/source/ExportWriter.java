package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Writes the model back out as a Designer "Export to Configuration File" —
 * always the {@code <driver-set-configuration>} root form, even for a single
 * driver, since that form is a strict superset. This is the exact inverse of
 * {@link ExportReader}: {@code ExportReader.read(toXml(ds))} must yield a model
 * equal to {@code ds} (see that class's Javadoc for the shape this mirrors).
 *
 * <p>Meta round-trips selectively: keys the reader copies verbatim from export
 * attributes ({@code package-id}, {@code pkg-assoc-id}, {@code checksum},
 * {@code modified}, {@code package-version} on drivers, and the driver set's own
 * {@code driverset.linkage.<n>} policy-linkage notes) are written back as the
 * attributes/elements they came from. Reader bookkeeping that was never a real
 * export attribute ({@code source.file}, {@code packages.count}, {@code
 * jobs.count}, {@code rbe-policies.count}, {@code library.base-dn} when absent,
 * a driver's {@code linkage.unknown.<n>}) has nothing to round-trip to and is
 * left out — see the class Javadoc on {@link ExportReader} for which meta keys
 * are bookkeeping-only.
 */
public final class ExportWriter {

    private ExportWriter() {
    }

    /** Write {@code ds} to {@code file} as a Designer export XML document. */
    public static void write(DriverSet ds, Path file) {
        try {
            Files.write(file, toXml(ds).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write export to " + file, e);
        }
    }

    /** Render {@code ds} as a {@code <driver-set-configuration>} export document string. */
    public static String toXml(DriverSet ds) {
        Document doc = CanonicalXml.parse("<driver-set-configuration/>");
        Element root = doc.getDocumentElement();
        root.setAttribute("name", ds.name);
        root.setAttribute("dn", nz(ds.dn));

        Element dsAttrs = doc.createElement("driver-set-attributes");
        root.appendChild(dsAttrs);
        if (ds.configValues != null) {
            Element gcv = doc.createElement("global-config-values");
            gcv.appendChild(doc.importNode(ds.configValues, true));
            dsAttrs.appendChild(gcv);
        }
        writeDriverSetLinkage(doc, dsAttrs, ds);

        Element children = doc.createElement("children");
        root.appendChild(children);

        List<Resource> dsGcvDefs = new ArrayList<>();
        List<Resource> libraryResources = new ArrayList<>();
        for (Resource r : ds.library.resources) {
            if (r.isGcvDef()) {
                dsGcvDefs.add(r);
            } else {
                libraryResources.add(r);
            }
        }

        if (!ds.library.policies.isEmpty() || !libraryResources.isEmpty()) {
            Element lib = doc.createElement("policy-library");
            lib.setAttribute("name", "Library");
            lib.setAttribute("base-dn", ds.meta.getOrDefault("library.base-dn", "cn=Library," + dsDn(ds)));
            for (Policy p : ds.library.policies) {
                lib.appendChild(policyWrapper(doc, p));
            }
            for (Resource r : libraryResources) {
                lib.appendChild(resourceElement(doc, r));
            }
            children.appendChild(lib);
        }
        for (Resource r : dsGcvDefs) {
            children.appendChild(gcvDefElement(doc, r));
        }

        for (Driver d : ds.drivers) {
            root.appendChild(driverElement(doc, ds, d));
        }

        return CanonicalXml.serialize(doc);
    }

    // ---- single-driver export ("Export Driver Configuration") -----------------

    /** Write the single-driver export of {@code driverName} to {@code file}. */
    public static void writeDriver(DriverSet ds, String driverName, Path file) {
        try {
            Files.write(file, toDriverXml(ds, driverName).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write export to " + file, e);
        }
    }

    /**
     * Render the Designer "Export Driver Configuration" of one driver: a root
     * {@code <driver-configuration>} carrying that driver's own {@code
     * <attributes>}/{@code <children>}, the driver set's GCVs at root level
     * (sibling of {@code <attributes>}/{@code <children>}, per {@link
     * ExportReader#read}), and — mirroring Designer's "include referenced
     * policies" option — every Library artifact the driver's policy-set links
     * reach, plus any Library mapping table a {@code <token-map table=…>} in one
     * of the driver's own policies names even when it isn't linked.
     *
     * <p>{@link ExportReader#readSingleDriver} has no notion of a nested Library
     * container in this export form (unlike the driver-set form's {@code
     * <policy-library>}): a referenced Library artifact is instead embedded as a
     * plain driver/channel artifact, at whichever scope its link belongs to
     * (subscriber, publisher, or driver for schema-mapping/input/output/ECMAScript/
     * GCV-set links), with a {@code <linkage-item dn=…>} synthesized to match that
     * placement — so both {@code ExportReader.readSingleDriver} (path-based ref
     * resolution) and the simulator's {@code DriverExport} (name/channel-keyed
     * lookup) find it.
     */
    public static String toDriverXml(DriverSet ds, String driverName) {
        Driver d = ds.driver(driverName);
        if (d == null) {
            throw new IllegalArgumentException("unknown driver: " + driverName);
        }

        Document doc = CanonicalXml.parse("<driver-configuration/>");
        Element root = doc.getDocumentElement();
        root.setAttribute("name", d.name);
        root.setAttribute("dn", driverDn(ds, d.name));
        root.setAttribute("driver-set-dn", nz(ds.dn));
        putMetaAttr(root, d.meta, "package-id");
        putMetaAttr(root, d.meta, "package-version");
        putMetaAttr(root, d.meta, "modified");

        if (ds.configValues != null) {
            Element gcv = doc.createElement("global-config-values");
            gcv.appendChild(doc.importNode(ds.configValues, true));
            root.appendChild(gcv);
        }

        // Every Library artifact this driver's links reach, and which scope (channel
        // or driver) to embed each one at — keyed by artifact path (= library/<name>).
        Map<String, Scope> libraryPlacement = collectLibraryPlacement(ds, d);

        Element attrs = doc.createElement("attributes");
        root.appendChild(attrs);
        writeCommonDriverAttrs(doc, attrs, d);
        writeLinkage(doc, attrs, d, l -> dnForRefSingle(ds, d, l.ref, libraryPlacement));

        List<Element> driverIncludes = libraryElementsFor(doc, ds, libraryPlacement, Scope.DRIVER);
        List<Element> publisherIncludes = libraryElementsFor(doc, ds, libraryPlacement, Scope.PUBLISHER);
        List<Element> subscriberIncludes = libraryElementsFor(doc, ds, libraryPlacement, Scope.SUBSCRIBER);

        // Library mapping tables referenced by <token-map table=…> but never linked —
        // the engine still resolves them at compile time, so Designer includes them too.
        for (Resource table : unlinkedTokenMapTables(ds, d, libraryPlacement.keySet())) {
            driverIncludes.add(resourceElement(doc, table));
        }

        Element children = doc.createElement("children");
        root.appendChild(children);
        for (Policy p : d.policies) {
            children.appendChild(policyWrapper(doc, p));
        }
        for (Resource r : d.resources) {
            if (r.scope == Scope.DRIVER) {
                children.appendChild(r.isGcvDef() ? gcvDefElement(doc, r) : resourceElement(doc, r));
            }
        }
        for (Element e : driverIncludes) {
            children.appendChild(e);
        }
        for (Entitlement ent : d.entitlements) {
            children.appendChild(entitlementElement(doc, ent));
        }
        children.appendChild(channelElement(doc, "publisher", "Publisher", d.publisher.policies,
            d.resources, Scope.PUBLISHER, publisherIncludes));
        children.appendChild(channelElement(doc, "subscriber", "Subscriber", d.subscriber.policies,
            d.resources, Scope.SUBSCRIBER, subscriberIncludes));

        return CanonicalXml.serialize(doc);
    }

    /**
     * Which scope each Library artifact reached by one of {@code d}'s links should
     * be embedded at in a single-driver export: the channel of the linking
     * policy-set (subscriber/publisher), or driver scope for a set that belongs to
     * neither channel (schema-mapping, input/output transform, ECMAScript, GCV).
     * An artifact linked more than once keeps the scope of its first link.
     */
    private static Map<String, Scope> collectLibraryPlacement(DriverSet ds, Driver d) {
        Map<String, Scope> placement = new LinkedHashMap<>();
        for (PolicyLink l : d.links) {
            Artifact a = ds.resolve(l.ref);
            if (a != null && a.scope == Scope.LIBRARY) {
                placement.putIfAbsent(a.path(), channelOf(l.set));
            }
        }
        return placement;
    }

    private static Scope channelOf(PolicySet set) {
        if (set.isSubscriber()) {
            return Scope.SUBSCRIBER;
        }
        if (set.isPublisher()) {
            return Scope.PUBLISHER;
        }
        return Scope.DRIVER;
    }

    /** The rendered {@code <rule>}/{@code <stylesheet>}/{@code <resource>}/{@code <global-config-def>}
     * elements for every Library artifact placed at {@code scope}. */
    private static List<Element> libraryElementsFor(Document doc, DriverSet ds, Map<String, Scope> placement,
                                                      Scope scope) {
        List<Element> out = new ArrayList<>();
        for (Map.Entry<String, Scope> e : placement.entrySet()) {
            if (e.getValue() != scope) {
                continue;
            }
            Artifact a = ds.resolve(e.getKey());
            if (a instanceof Policy) {
                out.add(policyWrapper(doc, (Policy) a));
            } else if (a instanceof Resource) {
                Resource r = (Resource) a;
                out.add(r.isGcvDef() ? gcvDefElement(doc, r) : resourceElement(doc, r));
            }
        }
        return out;
    }

    /**
     * Library mapping-table resources named by a {@code <token-map table=…>} in one
     * of {@code d}'s own policies (driver, subscriber, or publisher scope) but not
     * already reached by a link (per {@code alreadyIncluded}, artifact paths).
     */
    private static List<Resource> unlinkedTokenMapTables(DriverSet ds, Driver d, Set<String> alreadyIncluded) {
        List<Resource> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(alreadyIncluded);
        List<Policy> scan = new ArrayList<>();
        scan.addAll(d.policies);
        scan.addAll(d.subscriber.policies);
        scan.addAll(d.publisher.policies);
        for (Policy p : scan) {
            if (p.content == null) {
                continue;
            }
            for (Element map : Xds.descendantsByName(p.content, "token-map")) {
                String table = map.getAttribute("table");
                if (table == null || table.isBlank() || table.contains("$")) {
                    continue;
                }
                String name = leafName(table);
                String path = Artifact.path(Scope.LIBRARY, null, name);
                if (seen.contains(path)) {
                    continue;
                }
                Artifact a = ds.resolve(path);
                if (a instanceof Resource && ((Resource) a).isMappingTable()) {
                    out.add((Resource) a);
                    seen.add(path);
                }
            }
        }
        return out;
    }

    /** The last path/DN component of a {@code table="..\..\Library\Name"} (or {@code cn=Name,...}) reference. */
    private static String leafName(String s) {
        String t = s.trim();
        if (t.contains("=")) {
            String first = t.split("(?<!\\\\),")[0];
            return first.substring(first.indexOf('=') + 1).trim();
        }
        int i = Math.max(t.lastIndexOf('\\'), t.lastIndexOf('/'));
        return i >= 0 ? t.substring(i + 1) : t;
    }

    /**
     * {@code dnForRef}, specialized for a single-driver export: a link to this
     * driver's own artifact resolves exactly as in the driver-set form; a link to a
     * Library artifact instead points at wherever it was embedded in this document
     * ({@code libraryPlacement}), since this form has no Library container.
     */
    private static String dnForRefSingle(DriverSet ds, Driver owner, String ref, Map<String, Scope> libraryPlacement) {
        Artifact a = ds.resolve(ref);
        if (a == null) {
            return dnForUnresolvedRef(ds, owner, ref);
        }
        if (a.scope != Scope.LIBRARY) {
            return dnForArtifact(ds, a);
        }
        Scope placed = libraryPlacement.getOrDefault(a.path(), Scope.DRIVER);
        switch (placed) {
            case SUBSCRIBER:
                return "cn=" + a.name + ",cn=Subscriber," + driverDn(ds, owner.name);
            case PUBLISHER:
                return "cn=" + a.name + ",cn=Publisher," + driverDn(ds, owner.name);
            case DRIVER:
            default:
                return "cn=" + a.name + "," + driverDn(ds, owner.name);
        }
    }

    // ---- driver-set-level policy-linkage (meta round trip only) ----------------

    private static void writeDriverSetLinkage(Document doc, Element dsAttrs, DriverSet ds) {
        List<Integer> idx = new ArrayList<>();
        for (String k : ds.meta.keySet()) {
            if (k.startsWith("driverset.linkage.")) {
                try {
                    idx.add(Integer.parseInt(k.substring("driverset.linkage.".length())));
                } catch (NumberFormatException ignore) {
                    // not a well-formed index; skip
                }
            }
        }
        if (idx.isEmpty()) {
            return;
        }
        idx.sort(null);
        Element linkage = doc.createElement("policy-linkage");
        for (int i : idx) {
            String raw = ds.meta.get("driverset.linkage." + i);
            String[] parts = splitLinkageDescriptor(raw);
            if (parts == null) {
                continue;
            }
            Element item = doc.createElement("linkage-item");
            item.setAttribute("dn", parts[0]);
            item.setAttribute("order", parts[1]);
            item.setAttribute("policy-set", parts[2]);
            linkage.appendChild(item);
        }
        dsAttrs.appendChild(linkage);
    }

    /** Reverses {@code ExportReader.describeLinkageItem}: {@code <dn>#<order>#<policy-set>}. */
    private static String[] splitLinkageDescriptor(String s) {
        if (s == null) {
            return null;
        }
        int i1 = s.indexOf('#');
        int i2 = i1 < 0 ? -1 : s.indexOf('#', i1 + 1);
        if (i1 < 0 || i2 < 0) {
            return null;
        }
        return new String[]{s.substring(0, i1), s.substring(i1 + 1, i2), s.substring(i2 + 1)};
    }

    // ---- one <driver-configuration> --------------------------------------------

    private static Element driverElement(Document doc, DriverSet ds, Driver d) {
        Element de = doc.createElement("driver-configuration");
        de.setAttribute("name", d.name);
        de.setAttribute("dn", driverDn(ds, d.name));
        putMetaAttr(de, d.meta, "package-id");
        putMetaAttr(de, d.meta, "package-version");
        putMetaAttr(de, d.meta, "modified");

        Element attrs = doc.createElement("attributes");
        de.appendChild(attrs);

        writeCommonDriverAttrs(doc, attrs, d);
        writeLinkage(doc, attrs, d, l -> dnForRef(ds, d, l.ref));

        Element children = doc.createElement("children");
        de.appendChild(children);
        for (Policy p : d.policies) {
            children.appendChild(policyWrapper(doc, p));
        }
        for (Resource r : d.resources) {
            if (r.scope == Scope.DRIVER) {
                children.appendChild(r.isGcvDef() ? gcvDefElement(doc, r) : resourceElement(doc, r));
            }
        }
        for (Entitlement ent : d.entitlements) {
            children.appendChild(entitlementElement(doc, ent));
        }
        children.appendChild(channelElement(doc, "publisher", "Publisher", d.publisher.policies,
            d.resources, Scope.PUBLISHER));
        children.appendChild(channelElement(doc, "subscriber", "Subscriber", d.subscriber.policies,
            d.resources, Scope.SUBSCRIBER));

        return de;
    }

    /** The driver attribute blobs common to both export forms (shim, filter, own GCVs, engine values). */
    private static void writeCommonDriverAttrs(Document doc, Element attrs, Driver d) {
        if (d.shimClass != null) {
            Element jm = doc.createElement("java-module");
            jm.setAttribute("value", d.shimClass);
            attrs.appendChild(jm);
        }
        if (d.shimAuthServer != null) {
            Element e = doc.createElement("shim-auth-server");
            e.setAttribute("value", d.shimAuthServer);
            attrs.appendChild(e);
        }
        if (d.shimAuthId != null) {
            Element e = doc.createElement("shim-auth-id");
            e.setAttribute("value", d.shimAuthId);
            attrs.appendChild(e);
        }

        Element filter = d.config.get(Driver.DRIVER_FILTER);
        if (filter != null) {
            Element wrap = doc.createElement("driver-filter-xml");
            wrap.appendChild(doc.importNode(filter, true));
            attrs.appendChild(wrap);
        }
        Element cv = d.config.get(Driver.CONFIG_VALUES);
        if (cv != null) {
            Element wrap = doc.createElement("global-config-values");
            wrap.appendChild(doc.importNode(cv, true));
            attrs.appendChild(wrap);
        }
        Element engine = d.config.get(Driver.ENGINE_CONTROL_VALUES);
        if (engine != null) {
            Element wrap = doc.createElement("global-engine-values");
            wrap.appendChild(doc.importNode(engine, true));
            attrs.appendChild(wrap);
        }
        Element shimInfo = d.config.get(Driver.SHIM_CONFIG_INFO);
        if (shimInfo != null) {
            Element wrap = doc.createElement("shim-config-info-xml");
            wrap.appendChild(doc.importNode(shimInfo, true));
            attrs.appendChild(wrap);
        }
        if (d.icon != null && d.icon.length > 0) {
            // DirXML-DriverImage the way Designer serializes it: base64 text in <driver-image>
            Element image = doc.createElement("driver-image");
            image.appendChild(doc.createTextNode(java.util.Base64.getEncoder().encodeToString(d.icon)));
            attrs.appendChild(image);
        }
    }

    /** Writes {@code <policy-linkage>} for {@code d}'s links, resolving each link's dn via {@code dnFn}. */
    private static void writeLinkage(Document doc, Element attrs, Driver d, Function<PolicyLink, String> dnFn) {
        if (d.links.isEmpty()) {
            return;
        }
        Element linkage = doc.createElement("policy-linkage");
        for (PolicyLink l : d.links) {
            Element item = doc.createElement("linkage-item");
            item.setAttribute("dn", dnFn.apply(l));
            item.setAttribute("order", Integer.toString(l.order));
            item.setAttribute("policy-set", Integer.toString(l.set.id));
            linkage.appendChild(item);
        }
        attrs.appendChild(linkage);
    }

    private static Element channelElement(Document doc, String tag, String name, List<Policy> policies,
                                           List<Resource> driverResources, Scope scope) {
        return channelElement(doc, tag, name, policies, driverResources, scope, Collections.emptyList());
    }

    private static Element channelElement(Document doc, String tag, String name, List<Policy> policies,
                                           List<Resource> driverResources, Scope scope, List<Element> extraChildren) {
        Element ch = doc.createElement(tag);
        ch.setAttribute("name", name);
        Element children = doc.createElement("children");
        ch.appendChild(children);
        for (Policy p : policies) {
            children.appendChild(policyWrapper(doc, p));
        }
        for (Resource r : driverResources) {
            if (r.scope == scope) {
                children.appendChild(r.isGcvDef() ? gcvDefElement(doc, r) : resourceElement(doc, r));
            }
        }
        for (Element e : extraChildren) {
            children.appendChild(e);
        }
        return ch;
    }

    // ---- policy / resource / gcv-def elements ----------------------------------

    private static Element policyWrapper(Document doc, Policy p) {
        String tag = p.policyKind() == Policy.Kind.XSLT ? "stylesheet" : "rule";
        Element w = doc.createElement(tag);
        w.setAttribute("name", p.name);
        copyArtifactMetaAttrs(w, p.meta);
        if (p.content != null) {
            w.appendChild(doc.importNode(p.content, true));
        }
        return w;
    }

    private static Element resourceElement(Document doc, Resource r) {
        Element e = doc.createElement("resource");
        e.setAttribute("name", r.name);
        e.setAttribute("content-type", nz(r.contentType));
        copyArtifactMetaAttrs(e, r.meta);
        Element content = doc.createElement("content");
        if (r.isText()) {
            content.setAttribute("contains", "text");
            content.appendChild(doc.createTextNode(r.text == null ? "" : r.text));
        } else {
            content.setAttribute("contains", "xml");
            if (r.content != null) {
                content.appendChild(doc.importNode(r.content, true));
            }
        }
        e.appendChild(content);
        return e;
    }

    /** {@code <entitlement-definition name=…>} holding the entitlement document, the way Designer's export carries one. */
    private static Element entitlementElement(Document doc, Entitlement ent) {
        Element e = doc.createElement("entitlement-definition");
        e.setAttribute("name", ent.name);
        copyArtifactMetaAttrs(e, ent.meta);
        if (ent.definition != null) {
            e.appendChild(doc.importNode(ent.definition, true));
        }
        return e;
    }

    private static Element gcvDefElement(Document doc, Resource r) {
        Element e = doc.createElement("global-config-def");
        e.setAttribute("name", r.name);
        copyArtifactMetaAttrs(e, r.meta);
        if (r.content != null) {
            e.appendChild(doc.importNode(r.content, true));
        }
        return e;
    }

    private static void copyArtifactMetaAttrs(Element el, Map<String, String> meta) {
        putMetaAttr(el, meta, "package-id");
        putMetaAttr(el, meta, "pkg-assoc-id");
        putMetaAttr(el, meta, "checksum");
        putMetaAttr(el, meta, "modified");
    }

    /**
     * An export attribute from the tree's meta. A tree read from a Designer
     * export or project carries the attribute under its own name; a tree read
     * from the vault (live or LDIF) carries the vault's package stamps instead —
     * {@code dirxml-pkgguid} ({@code id;symbolicName;version;name;shortName}),
     * {@code dirxml-pkgassociationid}, {@code dirxml-pkgchecksum} and the
     * {@code package.customized} flag — which map onto the export attributes
     * one-to-one, so Designer sees the same package associations either way.
     */
    private static void putMetaAttr(Element el, Map<String, String> meta, String key) {
        String v = meta.get(key);
        if (v == null) {
            v = fromVaultStamps(meta, key);
        }
        if (v != null) {
            el.setAttribute(key, v);
        }
    }

    static String fromVaultStamps(Map<String, String> meta, String key) {
        String guid = meta.get("dirxml-pkgguid");
        switch (key) {
            case "package-id":
                return guid == null ? null : field(guid, 0);
            case "package-version":
                return guid == null ? null : field(guid, 2);
            case "pkg-assoc-id":
                return meta.get("dirxml-pkgassociationid");
            case "checksum":
                return meta.get("dirxml-pkgchecksum");
            case "modified":
                return "true".equals(meta.get(com.pointblue.dirxml.dev.edit.Packages.CUSTOMIZED_KEY)) ? "true" : null;
            default:
                return null;
        }
    }

    private static String field(String record, int i) {
        String[] f = record.split(";", -1);
        return i < f.length && !f[i].isBlank() ? f[i].trim() : null;
    }

    // ---- linkage dn synthesis ----------------------------------------------------

    /**
     * The {@code <linkage-item dn=…>} for a link's {@code ref}, in the form {@link
     * ExportReader#resolveRef} maps back to the same path: resolve the ref to its
     * artifact (for the correct scope/driver) when possible, else fall back to a
     * structural parse of the path relative to the owning driver.
     */
    private static String dnForRef(DriverSet ds, Driver owner, String ref) {
        Artifact a = ds.resolve(ref);
        if (a != null) {
            return dnForArtifact(ds, a);
        }
        return dnForUnresolvedRef(ds, owner, ref);
    }

    private static String dnForArtifact(DriverSet ds, Artifact a) {
        switch (a.scope) {
            case LIBRARY:
                if (a instanceof Resource && ((Resource) a).isGcvDef()) {
                    return "cn=" + a.name + "," + dsDn(ds);
                }
                return "cn=" + a.name + ",cn=Library," + dsDn(ds);
            case SUBSCRIBER:
                return "cn=" + a.name + ",cn=Subscriber," + driverDn(ds, a.driver);
            case PUBLISHER:
                return "cn=" + a.name + ",cn=Publisher," + driverDn(ds, a.driver);
            case DRIVER:
            default:
                return "cn=" + a.name + "," + driverDn(ds, a.driver);
        }
    }

    private static String dnForUnresolvedRef(DriverSet ds, Driver owner, String ref) {
        if (ref.startsWith("library/")) {
            return "cn=" + ref.substring("library/".length()) + ",cn=Library," + dsDn(ds);
        }
        String prefix = "drivers/" + owner.name + "/";
        if (ref.startsWith(prefix + "subscriber/")) {
            return "cn=" + ref.substring((prefix + "subscriber/").length()) + ",cn=Subscriber," + driverDn(ds, owner.name);
        }
        if (ref.startsWith(prefix + "publisher/")) {
            return "cn=" + ref.substring((prefix + "publisher/").length()) + ",cn=Publisher," + driverDn(ds, owner.name);
        }
        if (ref.startsWith(prefix)) {
            return "cn=" + ref.substring(prefix.length()) + "," + driverDn(ds, owner.name);
        }
        // best effort: keep it unique and non-crashing even though it won't resolve
        return "cn=" + ref.replace('/', '_') + "," + dsDn(ds);
    }

    private static String dsDn(DriverSet ds) {
        return (ds.dn != null && !ds.dn.isEmpty()) ? ds.dn : "cn=" + ds.name + ",o=system";
    }

    private static String driverDn(DriverSet ds, String driverName) {
        Driver d = ds.driver(driverName);
        if (d != null && d.dn != null && !d.dn.isEmpty()) {
            return d.dn;
        }
        return "cn=" + driverName + "," + dsDn(ds);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
