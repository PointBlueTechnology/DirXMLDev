package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
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
import com.pointblue.dirxml.sim.JndiLdapSearch;
import com.pointblue.dirxml.sim.LdifDriverSource;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import com.pointblue.dirxml.sim.SchemaModel;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the model from directory entries — an <b>LDIF dump</b> of the driver-set
 * subtree or a <b>live LDAP</b> read (both via the simulator's
 * {@link LdifDriverSource}, which the live reader also produces). Scope and identity
 * come purely from each entry's DN relative to the driver set:
 * <pre>
 *   cn=X,cn=Library,&lt;ds&gt;                 library/X
 *   cn=X,cn=Drv,&lt;ds&gt;                     drivers/Drv/X              (driver scope)
 *   cn=X,cn=Subscriber,cn=Drv,&lt;ds&gt;       drivers/Drv/subscriber/X
 *   cn=X,cn=Publisher,cn=Drv,&lt;ds&gt;        drivers/Drv/publisher/X
 * </pre>
 * so a {@code DirXML-Policies} value ({@code <dn>#<order>#<set>}) maps to a link ref
 * the same way whether or not the target entry is present (absent ⇒ unresolved link).
 *
 * <p>Attributes: policies ({@code DirXML-Rule}, {@code DirXML-StyleSheet}) carry
 * {@code XmlData}; resources ({@code DirXML-Resource}) carry {@code DirXML-Data}
 * (+ {@code DirXML-ContentType}). Driver config blobs: {@code DirXML-ShimConfigInfo},
 * {@code DirXML-ConfigValues}, {@code DirXML-DriverFilter}, {@code DirXML-EngineControlValues}.
 */
public final class LdifReader {

    private LdifReader() {
    }

    /** From an LDIF file (must include the DirXML data attributes — see the simulator docs). */
    public static DriverSet read(Path ldif) {
        return fromEntries(LdifDriverSource.load(ldif).entries(), ldif.getFileName().toString(), driverImages(ldif));
    }

    /**
     * Live: read the driver-set subtree over LDAP through the simulator's search — the text
     * attributes only, so no driver icon; {@code VaultDiff.readLive} (what {@code import-live}
     * uses) reads through our own {@code Vault} and carries the icon too.
     */
    public static DriverSet readLive(JndiLdapSearch.Config ldap, String driverSetDn) {
        LdifDriverSource src = new JndiLdapSearch(ldap, SchemaModel.empty()).readDriverConfig(driverSetDn);
        return fromSource(src, ldap.url + "/" + driverSetDn);
    }

    public static DriverSet fromSource(LdifDriverSource src, String sourceName) {
        return fromEntries(src.entries(), sourceName);
    }

    public static DriverSet fromEntries(Collection<Entry> entries, String sourceName) {
        return fromEntries(entries, sourceName, Map.of());
    }

    /**
     * The same, with each driver's {@code DirXML-DriverImage} by lower-cased DN. A source
     * {@link Entry} holds text only (the simulator decodes every value as UTF-8, which mangles
     * an image), so the one binary attribute the model carries travels beside the entries:
     * {@code VaultDiff} reads it as bytes over LDAP, {@link #read} re-reads it from the LDIF.
     */
    public static DriverSet fromEntries(Collection<Entry> entries, String sourceName, Map<String, byte[]> driverImages) {
        // 1. the driver set
        Entry dsEntry = null;
        for (Entry e : entries) {
            if (e.hasClass("DirXML-DriverSet")) {
                dsEntry = e;
                break;
            }
        }
        String dsDn;
        DriverSet ds;
        if (dsEntry != null) {
            dsDn = dsEntry.dn;
            ds = new DriverSet(rdn(dsDn));
            ds.dn = dsDn;
            ds.configValues = xml(dsEntry.first("DirXML-ConfigValues"), ds.meta, "configvalues");
            copyMeta(dsEntry, ds.meta, "DirXML-DriverSet");
            // the driver set's own linkage (GCV objects in set 14), as the export reader records it
            int n = 0;
            for (String link : dsEntry.all("DirXML-Policies")) {
                ds.meta.put("driverset.linkage." + (n++), link);
            }
        } else {
            // a dump of a single driver: synthesize the set from the driver's parent
            Entry anyDriver = entries.stream().filter(e -> e.hasClass("DirXML-Driver")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no DirXML-DriverSet or DirXML-Driver entry in " + sourceName));
            dsDn = parentDn(anyDriver.dn);
            ds = new DriverSet(rdn(dsDn));
            ds.dn = dsDn;
        }
        ds.meta.put("source.file", sourceName);

        // 2. drivers first (so artifacts can attach)
        Map<String, Driver> driversByLowerName = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!e.hasClass("DirXML-Driver")) {
                continue;
            }
            Driver d = new Driver(rdn(e.dn));
            d.dn = e.dn;
            d.shimClass = e.first("DirXML-JavaModule");
            d.shimAuthServer = e.first("DirXML-ShimAuthServer");
            d.shimAuthId = e.first("DirXML-ShimAuthID");
            putConfig(d, Driver.SHIM_CONFIG_INFO, e.first("DirXML-ShimConfigInfo"));
            putConfig(d, Driver.CONFIG_VALUES, e.first("DirXML-ConfigValues"));
            putConfig(d, Driver.DRIVER_FILTER, e.first("DirXML-DriverFilter"));
            putConfig(d, Driver.ENGINE_CONTROL_VALUES, e.first("DirXML-EngineControlValues"));
            byte[] image = driverImages.get(e.dn.toLowerCase());
            if (image != null && image.length > 0) {
                d.icon = image;
                d.iconExtension = Driver.iconExtensionOf(image);
            }
            copyMeta(e, d.meta, "DirXML-Driver");
            ds.drivers.add(d);
            driversByLowerName.put(d.name.toLowerCase(), d);
        }

        // 3. artifacts, placed by DN structure
        for (Entry e : entries) {
            boolean policy = e.hasClass("DirXML-Rule") || e.hasClass("DirXML-StyleSheet");
            // a package's GCV definitions: class DirXML-GlobalConfigDef, content in
            // DirXML-ConfigValues — linked in policy set 14, so modeled as a resource
            boolean gcvDef = e.hasClass("DirXML-GlobalConfigDef");
            boolean resource = e.hasClass("DirXML-Resource") || gcvDef;
            if (!policy && !resource) {
                continue;
            }
            Placement p = place(e.dn, dsDn);
            Driver d = p.driver == null ? null : driversByLowerName.get(p.driver.toLowerCase());
            if (p.scope != Scope.LIBRARY && d == null) {
                // an artifact under an unknown driver — keep it in the library, flagged
                p = new Placement(Scope.LIBRARY, null);
            }
            String name = rdn(e.dn);
            if (policy) {
                Policy pol = new Policy(name, p.scope, p.driver, xmlOrNull(e.first("XmlData")));
                pol.meta.put("dn", e.dn);
                copyMeta(e, pol.meta, e.hasClass("DirXML-StyleSheet") ? "DirXML-StyleSheet" : "DirXML-Rule");
                if (p.scope == Scope.LIBRARY) {
                    ds.library.policies.add(pol);
                } else if (p.scope == Scope.SUBSCRIBER) {
                    d.subscriber.policies.add(pol);
                } else if (p.scope == Scope.PUBLISHER) {
                    d.publisher.policies.add(pol);
                } else {
                    d.policies.add(pol);
                }
            } else {
                Resource r = new Resource(name, p.scope, p.driver,
                    gcvDef ? Resource.GCV_DEF : e.first("DirXML-ContentType"));
                String data = gcvDef ? e.first("DirXML-ConfigValues") : e.first("DirXML-Data");
                if (data == null) {
                    data = e.first("XmlData");
                }
                if (data != null) {
                    if (r.isEcmaScript() || !data.stripLeading().startsWith("<")) {
                        r.text = data;
                    } else {
                        r.content = xmlOrNull(data);
                        if (r.content == null) {
                            r.text = data;
                        }
                    }
                }
                r.meta.put("dn", e.dn);
                copyMeta(e, r.meta, gcvDef ? "DirXML-GlobalConfigDef" : "DirXML-Resource");
                if (p.scope == Scope.LIBRARY) {
                    ds.library.resources.add(r);
                } else {
                    d.resources.add(r);
                }
            }
        }

        // 3b. entitlements: DirXML-Entitlement objects hanging directly off a driver (docs/entitlements.md)
        for (Entry e : entries) {
            if (!e.hasClass("DirXML-Entitlement")) {
                continue;
            }
            Placement p = place(e.dn, dsDn);
            Driver d = p.driver == null ? null : driversByLowerName.get(p.driver.toLowerCase());
            if (d == null) {
                continue;   // an entitlement under an unknown driver: dropped (unlike artifacts, it has nowhere else to live)
            }
            Entitlement ent = new Entitlement(rdn(e.dn), xmlOrNull(e.first("XmlData")));
            ent.meta.put("dn", e.dn);
            copyMeta(e, ent.meta, "DirXML-Entitlement");
            d.entitlements.add(ent);
        }

        // 4. linkage: DirXML-Policies = "<policyDN>#<order>#<setId>"
        for (Entry e : entries) {
            if (!e.hasClass("DirXML-Driver")) {
                continue;
            }
            Driver d = driversByLowerName.get(rdn(e.dn).toLowerCase());
            int n = 0;
            for (String link : e.all("DirXML-Policies")) {
                int h2 = link.lastIndexOf('#');
                int h1 = h2 < 0 ? -1 : link.lastIndexOf('#', h2 - 1);
                if (h1 < 0) {
                    d.meta.put("linkage.unparsed." + (n++), link);
                    continue;
                }
                String policyDn = link.substring(0, h1);
                int order = parseInt(link.substring(h1 + 1, h2), 0);
                int setId = parseInt(link.substring(h2 + 1), -1);
                PolicySet set;
                try {
                    set = PolicySet.byId(setId);
                } catch (IllegalArgumentException ex) {
                    d.meta.put("linkage.unknown." + (n++), link);
                    continue;
                }
                Placement p = place(policyDn, dsDn);
                String ref = com.pointblue.dirxml.dev.model.Artifact.path(
                    p.scope == Scope.LIBRARY ? Scope.LIBRARY : p.scope, p.driver, rdn(policyDn));
                d.links.add(new PolicyLink(set, ref, order));
            }
        }

        // 5. provisioning: each driver's cn=AppConfig subtree, if it has one
        for (Driver d : ds.drivers) {
            if (d.dn == null) {
                continue;
            }
            d.provisioning = readProvisioning(entries, d.dn);
        }
        return ds;
    }

    // ---- provisioning (cn=AppConfig subtree: JSON forms + PRDs) ----------------------

    /** Builds this driver's {@link Provisioning} from every entry under {@code cn=AppConfig,<driverDn>}
     *  (matched by DN suffix, case-insensitive); null if the container entry itself isn't present. */
    private static Provisioning readProvisioning(Collection<Entry> entries, String driverDn) {
        String appConfigDn = "cn=AppConfig," + driverDn;
        String suffix = ("," + appConfigDn).toLowerCase();
        boolean hasAppConfig = false;
        List<Entry> subtree = new ArrayList<>();
        for (Entry e : entries) {
            boolean isContainer = e.dn.equalsIgnoreCase(appConfigDn);
            if (isContainer) {
                hasAppConfig = true;
            }
            if (isContainer || e.dn.toLowerCase().endsWith(suffix)) {
                subtree.add(e);
            }
        }
        if (!hasAppConfig) {
            return null;
        }
        Provisioning p = new Provisioning();
        p.dn = appConfigDn;
        int unplaced = 0;
        int runtime = 0;
        for (Entry e : subtree) {
            if (e.dn.equalsIgnoreCase(appConfigDn)) {
                copyMeta(e, p.meta, "srvprvAppConfig");
                // the container's own attributes (Version, srvprvPlugins): what an AppConfig is created with
                for (String a : e.attributeNames()) {
                    String name = AppConfigPolicy.canonicalAttribute(a);
                    if (!AppConfigPolicy.isNotContent(name) && !AppConfigPolicy.isOperational(name)) {
                        p.meta.put("appconfig." + name, String.join("\n", e.all(a)));
                    }
                }
                continue;
            }
            if (e.hasClass("srvprvJSONForm")) {
                Form.Kind kind = Form.Kind.byContainer(rdn(parentDn(e.dn)));
                if (kind == null) {
                    p.meta.put("provisioning.unplaced-form." + (unplaced++), e.dn);
                    continue;
                }
                String data = e.first("srvprvJSONData");
                Form f = new Form(kind, rdn(e.dn), data == null ? "" : data);
                f.meta.put("dn", e.dn);
                copyMeta(e, f.meta, "srvprvJSONForm");
                p.forms.add(f);
            } else if (e.hasClass("srvprvRequest")) {
                p.prds.add(readPrd(e));
            } else if (!e.hasClass("srvprvJSONForms") && !e.hasClass("srvprvRequestDefs")
                       && !e.hasClass("srvprvAppConfig")) {
                AppObject o = readAppObject(e, appConfigDn);
                if (o == null) {
                    runtime++;
                } else {
                    p.objects.add(o);
                }
            }
        }
        if (runtime > 0) {
            p.meta.put("provisioning.runtime-objects", String.valueOf(runtime));
        }
        p.objects.sort(Comparator.comparing(AppObject::path, String.CASE_INSENSITIVE_ORDER));
        return p;
    }

    /**
     * Any other AppConfig entry as an {@link AppObject} — every attribute as text, XML
     * attributes canonicalized, names in the schema's spelling, values sorted — or null
     * for one of the applications' runtime records ({@link AppConfigPolicy}).
     */
    static AppObject readAppObject(Entry e, String appConfigDn) {
        List<String> below = components(e.dn);
        List<String> base = components(appConfigDn);
        List<String> segments = new ArrayList<>();
        for (int i = below.size() - base.size() - 1; i >= 0; i--) {
            segments.add(unescape(valueOf(below.get(i))));
        }
        if (segments.isEmpty()) {
            return null;
        }
        AppObject o = new AppObject(segments);
        List<String> classes = new ArrayList<>(e.all("objectClass"));
        classes.sort(String.CASE_INSENSITIVE_ORDER);
        o.classes.addAll(classes);
        if (AppConfigPolicy.isRuntimeClass(o.structuralClass()) || AppConfigPolicy.isRuntimePath(o.path())) {
            return null;
        }
        List<String> names = new ArrayList<>(e.attributeNames());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String a : names) {
            String name = AppConfigPolicy.canonicalAttribute(a);
            if (AppConfigPolicy.isNotContent(name)) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (String v : e.all(a)) {
                String xml = AppConfigPolicy.isXmlAttribute(name) ? com.pointblue.dirxml.dev.ascode.DsObjectXml.asXml(v) : null;
                // an XML parser folds \r\n to \n in text, so a text value must be folded before it is
                // written, or the first write and every write after a read would differ
                values.add(xml != null ? xml : v.replace("\r\n", "\n").replace('\r', '\n'));
            }
            if (values.size() > 1) {
                values.sort(null);
            }
            o.put(name, values);
        }
        o.meta.put("dn", e.dn);
        copyMeta(e, o.meta, o.structuralClass());
        return o;
    }

    private static String valueOf(String component) {
        int eq = component.indexOf('=');
        return eq < 0 ? component : component.substring(eq + 1);
    }

    private static Prd readPrd(Entry e) {
        Prd prd = new Prd(rdn(e.dn));
        // normalize() re-parses through CanonicalXml (see its javadoc): Xds/XmlDocument
        // doesn't fold \r\n to \n in text content the way a conformant parser does, and
        // PRD scripts on the test vault carry CRLF line endings — without this the first
        // as-code write would differ from every write after a tree round trip.
        prd.definition = normalizeOrNull(xmlOrNull(e.first("XmlData")));
        prd.request = normalizeOrNull(xmlOrNull(e.first("srvprvRequestXML")));
        if (prd.definition != null) {
            // the vault's XmlData already contains <process> as a child of
            // <prov-req-defn> (verified on the test vault) — reuse that node
            // rather than re-parsing the redundant srvprvProcessXML copy.
            List<Element> procs = com.pointblue.dirxml.sim.Xds.childrenByName(prd.definition, "process");
            if (!procs.isEmpty()) {
                prd.process = procs.get(0);
            }
        }
        if (prd.process == null) {
            prd.process = normalizeOrNull(xmlOrNull(e.first("srvprvProcessXML")));
        }
        putProp(prd, "status", e.all("srvprvStatus"));
        putProp(prd, "flow-strategy", e.all("srvprvFlowStrategy"));
        putProp(prd, "grant", e.all("srvprvGrant"));
        putProp(prd, "revoke", e.all("srvprvRevoke"));
        putProp(prd, "category-key", e.all("srvprvCategoryKey"));
        putProp(prd, "localized-names", e.all("srvprvLocalizedNames"));
        putProp(prd, "localized-descrs", e.all("srvprvLocalizedDescrs"));
        putProp(prd, "process-type", e.all("srvprvProcessType"));
        putProp(prd, "entitlement-ref", e.all("srvprvEntitlementRef"));
        putProp(prd, "workflow-data", e.all("srvprvWorkflowData"));
        putProp(prd, "description", e.all("description"));
        prd.meta.put("dn", e.dn);
        copyMeta(e, prd.meta, "srvprvRequest");
        return prd;
    }

    private static Element normalizeOrNull(Element e) {
        return e == null ? null : CanonicalXml.normalize(e);
    }

    private static void putProp(Prd prd, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            prd.properties.put(key, new ArrayList<>(values));
        }
    }

    // ---- DN structure → scope/driver ----

    static final class Placement {
        final Scope scope;
        final String driver;
        Placement(Scope scope, String driver) {
            this.scope = scope;
            this.driver = driver;
        }
    }

    /**
     * Where an object DN sits relative to the driver set: components between the
     * object and the driver-set suffix, child first.
     */
    static Placement place(String dn, String dsDn) {
        List<String> rel = relativeComponents(dn, dsDn);   // [X, Subscriber, Drv] etc., values only
        if (rel.size() >= 2) {
            String parent = rel.get(1);
            if (parent.equalsIgnoreCase("Library")) {
                return new Placement(Scope.LIBRARY, null);
            }
            if (rel.size() >= 3 && (parent.equalsIgnoreCase("Subscriber") || parent.equalsIgnoreCase("Publisher"))) {
                return new Placement(parent.equalsIgnoreCase("Subscriber") ? Scope.SUBSCRIBER : Scope.PUBLISHER, rel.get(2));
            }
            return new Placement(Scope.DRIVER, parent);
        }
        return new Placement(Scope.LIBRARY, null);   // directly under the driver set
    }

    /** RDN values of {@code dn} below {@code suffix} (child first); whole DN if not under suffix. */
    static List<String> relativeComponents(String dn, String suffix) {
        List<String> comps = components(dn);
        List<String> sfx = components(suffix);
        int n = comps.size() - sfx.size();
        if (n > 0 && dn.toLowerCase().endsWith(suffix.toLowerCase())) {
            comps = comps.subList(0, n);
        }
        List<String> vals = new ArrayList<>();
        for (String c : comps) {
            int eq = c.indexOf('=');
            vals.add(unescape(eq >= 0 ? c.substring(eq + 1) : c));
        }
        return vals;
    }

    /** Split an LDAP DN on unescaped commas. */
    static List<String> components(String dn) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\' && i + 1 < dn.length()) {
                cur.append(c).append(dn.charAt(++i));
            } else if (c == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    static String rdn(String dn) {
        List<String> c = components(dn);
        if (c.isEmpty()) {
            return dn;
        }
        String first = c.get(0);
        int eq = first.indexOf('=');
        return unescape(eq >= 0 ? first.substring(eq + 1) : first);
    }

    static String parentDn(String dn) {
        List<String> c = components(dn);
        return c.size() <= 1 ? "" : String.join(",", c.subList(1, c.size()));
    }

    private static String unescape(String v) {
        return v.replace("\\,", ",").replace("\\+", "+").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ---- attribute helpers ----

    private static void putConfig(Driver d, String kind, String xml) {
        Element e = xml(xml, d.meta, kind);
        if (e != null) {
            d.config.put(kind, e);
        }
    }

    /** Parse an XML blob; on failure keep the raw text in meta so nothing is lost. */
    private static Element xml(String xml, Map<String, String> meta, String key) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            return Xds.parse(xml).getDocumentElement();
        } catch (Exception e) {
            meta.put("unparsed." + key, xml);
            return null;
        }
    }

    private static Element xmlOrNull(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            return Xds.parse(xml).getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Every {@code DirXML-DriverImage} in an LDIF, by lower-cased DN, read from the file as
     * bytes: an LDIF holds a binary value base64-encoded ({@code attr:: …}), and the
     * simulator's reader decodes that to UTF-8 text, which is lossy for an image.
     */
    static Map<String, byte[]> driverImages(Path ldif) {
        Map<String, byte[]> out = new HashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(ldif, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        List<String> unfolded = new ArrayList<>();
        for (String l : lines) {
            if (l.startsWith(" ") && !unfolded.isEmpty()) {
                unfolded.set(unfolded.size() - 1, unfolded.get(unfolded.size() - 1) + l.substring(1));
            } else {
                unfolded.add(l);
            }
        }
        String dn = null;
        for (String l : unfolded) {
            if (l.isBlank()) {
                dn = null;
                continue;
            }
            int colon = l.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = l.substring(0, colon);
            boolean b64 = l.startsWith("::", colon);
            String value = l.substring(colon + (b64 ? 2 : 1)).strip();
            try {
                if (name.equalsIgnoreCase("dn")) {
                    dn = b64 ? new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8) : value;
                } else if (dn != null && b64 && name.equalsIgnoreCase("DirXML-DriverImage")) {
                    byte[] image = Base64.getDecoder().decode(value);
                    if (image.length > 0) {
                        out.put(dn.toLowerCase(), image);
                    }
                }
            } catch (IllegalArgumentException malformed) {
                // not base64 after all — no image for this entry
            }
        }
        return out;
    }

    /** Preserve package/state attributes the model doesn't interpret. */
    private static void copyMeta(Entry e, Map<String, String> meta, String objectClass) {
        meta.put("objectClass", objectClass);
        for (String a : e.attributeNames()) {
            if (a.equals("dirxml-pkginitialstate")) {
                continue;   // the package initial state is the tree's .package-baseline, not meta
            }
            if (a.startsWith("dirxml-pkg") || a.equals("dirxml-contenttype") || a.equals("dirxml-driverstartoption")
                || a.equals("dirxml-tracelevel") || a.equals("dirxml-tracefile")) {
                String v = e.first(a);
                if (v != null) {
                    meta.put(a, v);
                }
            }
        }
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }
}
