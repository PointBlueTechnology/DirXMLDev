package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.JndiLdapSearch;
import com.pointblue.dirxml.sim.LdifDriverSource;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import com.pointblue.dirxml.sim.SchemaModel;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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
        return fromSource(LdifDriverSource.load(ldif), ldif.getFileName().toString());
    }

    /** Live: read the driver-set subtree over LDAP. */
    public static DriverSet readLive(JndiLdapSearch.Config ldap, String driverSetDn) {
        LdifDriverSource src = new JndiLdapSearch(ldap, SchemaModel.empty()).readDriverConfig(driverSetDn);
        return fromSource(src, ldap.url + "/" + driverSetDn);
    }

    public static DriverSet fromSource(LdifDriverSource src, String sourceName) {
        return fromEntries(src.entries(), sourceName);
    }

    public static DriverSet fromEntries(Collection<Entry> entries, String sourceName) {
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
        return ds;
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

    /** Preserve package/state attributes the model doesn't interpret. */
    private static void copyMeta(Entry e, Map<String, String> meta, String objectClass) {
        meta.put("objectClass", objectClass);
        for (String a : e.attributeNames()) {
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
