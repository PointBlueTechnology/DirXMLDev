package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model queries shared by the checks: which artifacts a driver can see, which GCVs
 * are defined for it, which mapping tables it can reach, its DN in the engine's
 * slash form. Pure functions over the model — no findings here.
 */
final class Model {

    private Model() {
    }

    /** The driver's DN in the engine's slash form ({@code \[root]\o\ds\drv}); synthesized when the source carried none. */
    static String slashDn(DriverSet ds, Driver d) {
        if (d.dn != null && !d.dn.isBlank()) {
            return toSlash(d.dn);
        }
        String dsDn = ds.dn != null && !ds.dn.isBlank() ? toSlash(ds.dn) : "\\[root]\\" + ds.name;
        return dsDn + "\\" + d.name;
    }

    /** {@code cn=a,ou=b,o=c} → {@code \[root]\c\b\a}; slash-form input is returned as is. */
    static String toSlash(String dn) {
        if (dn.startsWith("\\")) {
            return dn;
        }
        List<String> rdns = new ArrayList<>();
        for (String part : dn.split("(?<!\\\\),")) {
            int eq = part.indexOf('=');
            rdns.add(0, (eq >= 0 ? part.substring(eq + 1) : part).trim());
        }
        return "\\[root]\\" + String.join("\\", rdns);
    }

    /** Policies of every scope the driver owns (driver, subscriber, publisher). */
    static List<Policy> policies(Driver d) {
        List<Policy> out = new ArrayList<>(d.policies);
        out.addAll(d.subscriber.policies);
        out.addAll(d.publisher.policies);
        return out;
    }

    /** Resources linked into a driver's policy set (e.g. set 3 ECMAScript, set 14 GCV definitions), resolved. */
    static List<Resource> linkedResources(DriverSet ds, Driver d, PolicySet set, Map<String, Artifact> index) {
        List<Resource> out = new ArrayList<>();
        for (PolicyLink l : d.links(set)) {
            Artifact a = index.get(l.ref);
            if (a instanceof Resource) {
                out.add((Resource) a);
            }
        }
        return out;
    }

    /**
     * GCV names defined for a driver: its own config-values, GCV-definition resources
     * linked in policy set 14 (driver linkage), the driver set's config-values, and
     * GCV-definition resources linked from the driver set itself (recorded as
     * {@code driverset.linkage.N} meta by the readers).
     */
    static Set<String> definedGcvs(DriverSet ds, Driver d, Map<String, Artifact> index) {
        Set<String> names = new LinkedHashSet<>();
        addDefinitions(names, d.config.get(Driver.CONFIG_VALUES));
        for (Resource r : linkedResources(ds, d, PolicySet.byId(14), index)) {
            addDefinitions(names, r.content);
        }
        addDefinitions(names, ds.configValues);
        for (Resource r : driverSetGcvResources(ds)) {
            addDefinitions(names, r.content);
        }
        return names;
    }

    /** GCV-definition resources the driver set links (from {@code driverset.linkage.N} meta, by leaf name). */
    static List<Resource> driverSetGcvResources(DriverSet ds) {
        List<Resource> out = new ArrayList<>();
        for (Map.Entry<String, String> m : ds.meta.entrySet()) {
            if (!m.getKey().startsWith("driverset.linkage.")) {
                continue;
            }
            String v = m.getValue();
            int hash = v.indexOf('#');
            String dn = hash >= 0 ? v.substring(0, hash) : v;
            String leaf = leafName(dn);
            for (Resource r : ds.library.resources) {
                if (r.name.equals(leaf) && r.isGcvDef()) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static void addDefinitions(Set<String> names, Element configValues) {
        if (configValues == null) {
            return;
        }
        for (Element def : Xds.descendantsByName(configValues, "definition")) {
            String n = def.getAttribute("name");
            if (n != null && !n.isBlank()) {
                names.add(n.trim());
            }
        }
    }

    /** Mapping tables visible to a driver, by name: its own driver-scope tables first, then the library's. */
    static Map<String, Resource> mappingTables(DriverSet ds, Driver d) {
        Map<String, Resource> out = new java.util.LinkedHashMap<>();
        for (Resource r : ds.library.resources) {
            if (r.isMappingTable()) {
                out.put(r.name, r);
            }
        }
        if (d != null) {
            for (Resource r : d.resources) {
                if (r.isMappingTable()) {
                    out.put(r.name, r);
                }
            }
        }
        return out;
    }

    /** The last component of a slash / LDAP / dot DN: {@code ..\..\Library\LocCodeMap} → {@code LocCodeMap}. */
    static String leafName(String dn) {
        if (dn == null) {
            return null;
        }
        String s = dn.trim();
        if (s.contains("=")) {
            // LDAP form, first RDN is the leaf
            String first = s.split("(?<!\\\\),")[0];
            return first.substring(first.indexOf('=') + 1).trim();
        }
        int i = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** Which channel (or driver scope) a policy set belongs to. */
    static Scope scopeOf(PolicySet set) {
        if (set.isSubscriber()) {
            return Scope.SUBSCRIBER;
        }
        if (set.isPublisher()) {
            return Scope.PUBLISHER;
        }
        return Scope.DRIVER;
    }
}
