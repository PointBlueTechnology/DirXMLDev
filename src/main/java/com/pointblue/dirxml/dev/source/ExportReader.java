package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.PackageStamps;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.Xds;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the model from a Designer "Export to Configuration File": either a
 * single {@code <driver-configuration>} (one driver; the driver set is
 * synthesized) or a {@code <driver-set-configuration>} (the set, its
 * {@code <policy-library>}, and every {@code <driver-configuration>} it holds).
 *
 * <p>Empirically (both export forms, checked against real Designer exports):
 * <pre>
 *   driver-set-configuration[dn name]
 *     driver-set-attributes/                          driver-set config
 *       global-config-values/configuration-values         -&gt; DriverSet.configValues
 *       policy-linkage/linkage-item                        driver-set-scope GCV link (noted in meta only —
 *                                                           the model has no driver-set-level links[])
 *     packages/package*                                identifying only; noted in meta
 *     children/
 *       policy-library[base-dn name]                   -&gt; Library
 *         rule[name] &gt; (policy|attr-name-map)             -&gt; Policy(LIBRARY)
 *         stylesheet[name] &gt; xsl:stylesheet                -&gt; Policy(LIBRARY, kind XSLT)
 *         resource[content-type name] &gt; content[contains]  -&gt; Resource(LIBRARY)
 *         jobs, rbe-policies, global-config-def          noted in meta only
 *     driver-configuration[dn name]*                   direct children of the ROOT, not of &lt;children&gt;
 *                                                       (tolerated nested under &lt;children&gt; too)
 *
 *   driver-configuration[dn name driver-set-dn]         (single-driver export; driver-set-dn
 *                                                        synthesizes the DriverSet)
 *     global-config-values/configuration-values         root-level, sibling of attributes/children:
 *                                                        the ANCESTOR driver set's GCVs, included for
 *                                                        context -&gt; DriverSet.configValues
 *     attributes/
 *       java-module[value]                              -&gt; Driver.shimClass
 *       shim-auth-server[value] shim-auth-id[value]      -&gt; Driver.shimAuthServer/shimAuthId
 *                                                        (fallback: authentication-info/server,user)
 *       driver-filter-xml/filter                         -&gt; Driver.config[DRIVER_FILTER]
 *       global-config-values/configuration-values        the DRIVER's own GCVs -&gt; Driver.config[CONFIG_VALUES]
 *       global-engine-values/configuration-values         -&gt; Driver.config[ENGINE_CONTROL_VALUES]
 *       shim-config-info-xml/&lt;driver-config&gt;              the shim's whole init-param tree (incl. its
 *                                                        driver-options/subscriber-options/publisher-options
 *                                                        sub-blocks) -&gt; Driver.config[SHIM_CONFIG_INFO]
 *       policy-linkage/linkage-item[dn order policy-set]  -&gt; Driver.links
 *     children/
 *       rule|stylesheet, resource                        driver scope -&gt; Driver.policies / Driver.resources
 *       publisher/children/ , subscriber/children/        rule|stylesheet -&gt; Channel.policies;
 *                                                        resource -&gt; Driver.resources (scope PUBLISHER/SUBSCRIBER;
 *                                                        the model has no per-channel resource list)
 *       global-config-def                                 driver-scope GCV defs; not linked via policy-linkage
 *                                                        in observed exports -&gt; noted in meta only
 * </pre>
 *
 * <p>A {@code <rule name=…>} wrapper holds {@code <policy>} or {@code <attr-name-map>};
 * an XSLT policy instead uses its own top-level {@code <stylesheet name=…>} wrapper
 * (not {@code <rule>}) holding the actual {@code <xsl:stylesheet>}. Both wrapper kinds
 * are treated the same way here: first child element = the policy content.
 *
 * <p>Linkage {@code dn}s are resolved purely from their first two {@code cn}s: first
 * = artifact name; second {@code Publisher}/{@code Subscriber} = that channel of this
 * driver; second matching the policy-library's container name (or literally
 * {@code Library}) = {@code library/<name>}; anything else = driver scope of this
 * driver. A link whose target wasn't loaded (e.g. a Library policy excluded from a
 * single-driver export, or a GCV-set link to a {@code global-config-def} — which
 * isn't wrapped in {@code <rule>} and so never becomes a {@link Policy}) is still
 * added; {@link DriverSet#unresolvedLinks()} reports it. An unknown policy-set id is
 * recorded in {@code driver.meta} under {@code linkage.unknown.<n>} instead.
 */
public final class ExportReader {

    private ExportReader() {
    }

    /** Read a Designer export file (either root form) into the model. */
    public static DriverSet read(Path exportFile) {
        Element root = Xds.parseFile(exportFile).getDocumentElement();
        return read(root, exportFile.getFileName().toString());
    }

    /** Read an already-parsed export root ({@code <driver-configuration>} or {@code <driver-set-configuration>}). */
    public static DriverSet read(Element root, String fileNameForMeta) {
        String ln = localName(root);
        DriverSet ds;
        if ("driver-set-configuration".equals(ln)) {
            ds = readDriverSet(root);
        } else if ("driver-configuration".equals(ln)) {
            ds = readSingleDriver(root);
        } else {
            throw new IllegalArgumentException("not a Designer export root: <" + ln + ">");
        }
        ds.meta.put("source.file", fileNameForMeta);
        return ds;
    }

    // ---- driver-set-configuration form -------------------------------------

    private static DriverSet readDriverSet(Element root) {
        String name = attr(root, "name", "driverset");
        DriverSet ds = new DriverSet(name.isEmpty() ? "driverset" : name);
        ds.dn = root.getAttribute("dn");

        Element dsAttrs = directChild(root, "driver-set-attributes");
        String libraryContainerName = "Library";
        if (dsAttrs != null) {
            ds.configValues = configValuesOf(dsAttrs);
            Element linkage = directChild(dsAttrs, "policy-linkage");
            if (linkage != null) {
                int n = 0;
                for (Element item : Xds.childrenByName(linkage, "linkage-item")) {
                    ds.meta.put("driverset.linkage." + (n++), describeLinkageItem(item));
                }
            }
        }
        Element packages = directChild(root, "packages");
        if (packages != null) {
            int count = Xds.childrenByName(packages, "package").size();
            if (count > 0) {
                ds.meta.put("packages.count", String.valueOf(count));
            }
        }

        Element childrenEl = directChild(root, "children");
        List<Element> driverEls = new ArrayList<>(Xds.childrenByName(root, "driver-configuration"));
        if (childrenEl != null) {
            Element lib = directChild(childrenEl, "policy-library");
            if (lib != null) {
                libraryContainerName = attr(lib, "name", "Library");
                ds.meta.put("library.base-dn", lib.getAttribute("base-dn"));
                readLibrary(lib, ds);
            }
            for (Element c : Xds.childElements(childrenEl)) {
                String ln = localName(c);
                if (ln.equals("global-config-def")) {
                    // a driver-set-level GCV definition object: a real, linkable
                    // artifact (policy set 14) that lives beside the Library
                    ds.library.resources.add(gcvDefResource(c, Scope.LIBRARY, null));
                } else if (ln.equals("jobs") || ln.equals("rbe-policies")) {
                    ds.meta.put(ln + ".count", String.valueOf(Xds.childElements(c).size()));
                }
            }
            // tolerate an export shape that nests driver-configurations under <children>
            driverEls.addAll(Xds.childrenByName(childrenEl, "driver-configuration"));
        }

        for (Element de : driverEls) {
            ds.drivers.add(readDriver(de, libraryContainerName, ds.name));
        }
        return ds;
    }

    // ---- single driver-configuration form -----------------------------------

    private static DriverSet readSingleDriver(Element root) {
        String dsDn = root.getAttribute("driver-set-dn");
        if (dsDn.isEmpty()) {
            dsDn = parentDn(root.getAttribute("dn"));
        }
        String dsName = dsDn.isEmpty() ? "driverset" : rdn(dsDn);
        DriverSet ds = new DriverSet(dsName.isEmpty() ? "driverset" : dsName);
        ds.dn = dsDn;

        // The ancestor driver set's own GCVs, included by Designer for context —
        // distinct from this driver's own <attributes>/<global-config-values>.
        Element rootGcv = directChild(root, "global-config-values");
        if (rootGcv != null) {
            Element cv = Xds.firstByName(rootGcv, "configuration-values");
            if (cv != null) {
                ds.configValues = cv;
            }
        }

        ds.drivers.add(readDriver(root, "Library", ds.name));
        return ds;
    }

    // ---- one <driver-configuration> ------------------------------------------

    private static Driver readDriver(Element driverEl, String libraryContainerName, String driverSetName) {
        String name = attr(driverEl, "name", "driver");
        Driver d = new Driver(name);
        d.dn = driverEl.getAttribute("dn");
        // the driver's package record: an export names the id and the version only
        String pkgId = nullIfEmpty(driverEl.getAttribute("package-id"));
        if (pkgId != null) {
            d.meta.put(PackageStamps.GUID, new PackageStamps.Guid(pkgId, null,
                nullIfEmpty(driverEl.getAttribute("package-version")), null, null, false).format());
        }
        copyAttr(driverEl, "modified", d.meta, "modified");

        Element attrs = directChild(driverEl, "attributes");
        Node scanRoot = attrs != null ? attrs : driverEl;

        Element jm = Xds.firstByName(scanRoot, "java-module");
        if (jm != null && !jm.getAttribute("value").isEmpty()) {
            d.shimClass = jm.getAttribute("value");
        }

        // Designer's actual shape: <shim-auth-server value=…>, <shim-auth-id value=…>.
        // Fall back to <authentication-info>/<server>,<user> for other export shapes.
        Element authServer = directChild(attrs, "shim-auth-server");
        Element authId = directChild(attrs, "shim-auth-id");
        if (authServer != null || authId != null) {
            d.shimAuthServer = authServer == null ? null : nullIfEmpty(authServer.getAttribute("value"));
            d.shimAuthId = authId == null ? null : nullIfEmpty(authId.getAttribute("value"));
        } else {
            Element auth = Xds.firstByName(scanRoot, "authentication-info");
            if (auth != null) {
                Element server = Xds.firstByName(auth, "server");
                Element user = Xds.firstByName(auth, "user");
                d.shimAuthServer = server == null ? null : nullIfEmpty(Xds.text(server));
                d.shimAuthId = user == null ? null : nullIfEmpty(Xds.text(user));
            }
        }

        Element filter = Xds.firstByName(scanRoot, "filter");
        if (filter != null) {
            d.config.put(Driver.DRIVER_FILTER, filter);
        }

        Element ownGcvValues = configValuesOf(attrs);
        if (ownGcvValues != null) {
            d.config.put(Driver.CONFIG_VALUES, ownGcvValues);
        }

        Element engine = directChild(attrs, "global-engine-values");
        Element engineValues = engine == null ? null : Xds.firstByName(engine, "configuration-values");
        if (engineValues != null) {
            d.config.put(Driver.ENGINE_CONTROL_VALUES, engineValues);
        }

        // the driver's icon: DirXML-DriverImage as Designer serializes it — base64 text (or a
        // CDATA section) in <driver-image>, or delete-value="true" for none
        Element image = attrs != null ? directChild(attrs, "driver-image") : Xds.firstByName(scanRoot, "driver-image");
        if (image != null && !"true".equalsIgnoreCase(image.getAttribute("delete-value"))) {
            try {
                byte[] bytes = java.util.Base64.getMimeDecoder().decode(Xds.text(image).strip());
                if (bytes.length > 0) {
                    d.icon = bytes;
                    d.iconExtension = Driver.iconExtensionOf(bytes);
                }
            } catch (IllegalArgumentException notBase64) {
                d.meta.put("unparsed.driver-image", Xds.text(image).strip());
            }
        }

        Element shimInfo = directChild(attrs, "shim-config-info-xml");
        if (shimInfo != null) {
            List<Element> kids = Xds.childElements(shimInfo);
            if (!kids.isEmpty()) {
                d.config.put(Driver.SHIM_CONFIG_INFO, kids.get(0));
            }
        }

        Element childrenEl = directChild(driverEl, "children");
        readArtifactContainer(childrenEl != null ? childrenEl : driverEl, Scope.DRIVER, d);

        Element linkage = Xds.firstByName(scanRoot, "policy-linkage");
        if (linkage != null) {
            int unknown = 0;
            for (Element item : Xds.childrenByName(linkage, "linkage-item")) {
                int order = parseInt(item.getAttribute("order"), 0);
                int setId = parseInt(item.getAttribute("policy-set"), -1);
                PolicySet set;
                try {
                    set = PolicySet.byId(setId);
                } catch (IllegalArgumentException ex) {
                    d.meta.put("linkage.unknown." + (unknown++), describeLinkageItem(item));
                    continue;
                }
                String ref = resolveRef(item.getAttribute("dn"), d.name, libraryContainerName, driverSetName);
                d.links.add(new PolicyLink(set, ref, order));
            }
        }

        return d;
    }

    // ---- library -------------------------------------------------------------

    private static void readLibrary(Element libEl, DriverSet ds) {
        for (Element child : Xds.childElements(libEl)) {
            switch (localName(child)) {
                case "rule":
                case "stylesheet":
                    Policy p = readPolicy(child, Scope.LIBRARY, null);
                    if (p != null) {
                        ds.library.policies.add(p);
                    }
                    break;
                case "resource":
                    ds.library.resources.add(readResource(child, Scope.LIBRARY, null));
                    break;
                default:
                    // pkg-initial-states, descriptions — empty in observed exports; not modeled.
            }
        }
    }

    // ---- driver-scope / channel artifacts -------------------------------------

    private static void readArtifactContainer(Node container, Scope scope, Driver d) {
        for (Element child : Xds.childElements(container)) {
            switch (localName(child)) {
                case "publisher":
                    readChannel(child, Scope.PUBLISHER, d);
                    break;
                case "subscriber":
                    readChannel(child, Scope.SUBSCRIBER, d);
                    break;
                case "rule":
                case "stylesheet":
                    Policy p = readPolicy(child, scope, d.name);
                    if (p != null) {
                        addPolicy(d, scope, p);
                    }
                    break;
                case "resource":
                    d.resources.add(readResource(child, scope, d.name));
                    break;
                case "global-config-def":
                    // a real, linkable object (policy set 14) — model it so links resolve
                    d.resources.add(gcvDefResource(child, scope, d.name));
                    break;
                default:
                    // not modeled at this level (e.g. driver-image, pkg-initial-states)
            }
        }
    }

    private static void readChannel(Element channelEl, Scope scope, Driver d) {
        Element childrenEl = directChild(channelEl, "children");
        readArtifactContainer(childrenEl != null ? childrenEl : channelEl, scope, d);
    }

    private static void addPolicy(Driver d, Scope scope, Policy p) {
        if (scope == Scope.SUBSCRIBER) {
            d.subscriber.policies.add(p);
        } else if (scope == Scope.PUBLISHER) {
            d.publisher.policies.add(p);
        } else {
            d.policies.add(p);
        }
    }

    // ---- one <rule>/<stylesheet> wrapper, one <resource> ------------------------

    private static Policy readPolicy(Element wrapper, Scope scope, String driverName) {
        String name = attr(wrapper, "name", null);
        if (name == null) {
            return null;
        }
        List<Element> kids = Xds.childElements(wrapper);
        if (kids.isEmpty()) {
            return null;
        }
        Policy p = new Policy(name, scope, driverName, kids.get(0));
        copyArtifactMeta(wrapper, p.meta);
        return p;
    }

    private static Resource readResource(Element resEl, Scope scope, String driverName) {
        String name = attr(resEl, "name", "");
        Resource r = new Resource(name, scope, driverName, resEl.getAttribute("content-type"));
        Element content = directChild(resEl, "content");
        if (content != null) {
            List<Element> kids = Xds.childElements(content);
            if (!kids.isEmpty() && !"text".equalsIgnoreCase(content.getAttribute("contains"))) {
                r.content = kids.get(0);
            } else {
                r.text = Xds.text(content);
            }
        }
        copyArtifactMeta(resEl, r.meta);
        return r;
    }

    /**
     * An item's package stamps, in the tree's (the vault's) vocabulary: the export names the
     * package by id alone, so the record is partial ({@code id}) until a deploy or a diff
     * completes it from the vault's or the catalog's fuller record ({@link PackageStamps.Index}).
     */
    private static void copyArtifactMeta(Element el, Map<String, String> meta) {
        String pkgId = nullIfEmpty(el.getAttribute("package-id"));
        if (pkgId != null) {
            meta.put(PackageStamps.GUID, pkgId);
        }
        copyAttr(el, "pkg-assoc-id", meta, PackageStamps.ASSOC);
        copyAttr(el, "checksum", meta, PackageStamps.CHECKSUM);
        copyAttr(el, "modified", meta, "modified");
    }

    /**
     * A {@code <global-config-def name=…>} (a package's GCV definitions) as a
     * {@link Resource} of type {@link Resource#GCV_DEF}, content kept whole, so the
     * policy-set-14 links that reference it resolve and nothing is lost.
     */
    private static Resource gcvDefResource(Element el, Scope scope, String driverName) {
        Resource r = new Resource(attr(el, "name", "gcv"), scope, driverName, Resource.GCV_DEF);
        // Content is the <configuration-values> itself — the same shape the vault holds
        // in DirXML-ConfigValues on a DirXML-GlobalConfigDef object — so both sources
        // produce identical as-code and a deploy can write it back verbatim.
        Element cv = Xds.firstByName(el, "configuration-values");
        r.content = cv != null ? cv : el;
        copyArtifactMeta(el, r.meta);
        return r;
    }

    // ---- linkage dn -> artifact path -------------------------------------------

    /**
     * Resolve a {@code <linkage-item dn=…>} to an artifact path, from its first two
     * {@code cn}s alone: first = artifact name; second {@code Publisher}/{@code
     * Subscriber} = that channel of {@code thisDriverName}; second matching the
     * library container's name (or literally {@code Library}) = {@code library/<name>};
     * anything else = driver scope of {@code thisDriverName}.
     */
    static String resolveRef(String dn, String thisDriverName, String libraryContainerName) {
        return resolveRef(dn, thisDriverName, libraryContainerName, null);
    }

    /**
     * As above, plus: a second {@code cn} equal to the driver set's own name means the
     * target sits directly under the driver set (a driver-set-level GCV definition),
     * which the model keeps at library scope.
     */
    static String resolveRef(String dn, String thisDriverName, String libraryContainerName, String driverSetName) {
        String[] comps = dn.split(",", 3);
        String name = comps.length > 0 ? stripCn(comps[0]) : "";
        String second = comps.length > 1 ? stripCn(comps[1]) : "";
        if (second.equalsIgnoreCase("Library") || second.equalsIgnoreCase(libraryContainerName)
            || (driverSetName != null && second.equalsIgnoreCase(driverSetName))) {
            return Artifact.path(Scope.LIBRARY, null, name);
        }
        if (second.equalsIgnoreCase("Publisher")) {
            return Artifact.path(Scope.PUBLISHER, thisDriverName, name);
        }
        if (second.equalsIgnoreCase("Subscriber")) {
            return Artifact.path(Scope.SUBSCRIBER, thisDriverName, name);
        }
        return Artifact.path(Scope.DRIVER, thisDriverName, name);
    }

    private static String stripCn(String comp) {
        String s = comp.trim();
        int eq = s.indexOf('=');
        return eq >= 0 ? s.substring(eq + 1).trim() : s;
    }

    private static String describeLinkageItem(Element item) {
        return item.getAttribute("dn") + "#" + item.getAttribute("order") + "#" + item.getAttribute("policy-set");
    }

    // ---- generic DOM / DN helpers -----------------------------------------------

    private static String localName(Node n) {
        String ln = n.getLocalName();
        return ln != null ? ln : n.getNodeName();
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        List<Element> kids = Xds.childrenByName(parent, name);
        return kids.isEmpty() ? null : kids.get(0);
    }

    private static String attr(Element el, String name, String dflt) {
        String v = el.getAttribute(name);
        return v.isEmpty() ? dflt : v;
    }

    private static void copyAttr(Element el, String attrName, Map<String, String> meta, String key) {
        if (el.hasAttribute(attrName)) {
            meta.put(key, el.getAttribute(attrName));
        }
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** The driver's or driver-set's {@code <global-config-values>/<configuration-values>}, or null. */
    private static Element configValuesOf(Element parent) {
        Element gcv = directChild(parent, "global-config-values");
        return gcv == null ? null : Xds.firstByName(gcv, "configuration-values");
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static String parentDn(String dn) {
        int idx = dn.indexOf(',');
        return idx < 0 ? "" : dn.substring(idx + 1).trim();
    }

    private static String rdn(String dn) {
        int idx = dn.indexOf(',');
        String first = idx < 0 ? dn : dn.substring(0, idx);
        int eq = first.indexOf('=');
        return eq < 0 ? first.trim() : first.substring(eq + 1).trim();
    }
}
