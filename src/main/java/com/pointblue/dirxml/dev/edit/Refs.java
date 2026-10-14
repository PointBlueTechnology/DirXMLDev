package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The reverse-reference index: everything that refers to an artifact. The model
 * indexes what an artifact <em>is</em> ({@code DriverSet.index()}) and what a
 * driver <em>links</em>; rename and delete need the other direction — who would
 * break if this artifact changed its name or went away.
 *
 * <p>Reference kinds:
 * <ul>
 *   <li>{@code link} — a driver's policy-set link ({@code DirXML-Policies});</li>
 *   <li>{@code driverset-link} — the driver set's own linkage to a GCV object
 *       ({@code driverset.linkage.N} meta);</li>
 *   <li>{@code include} — a DirXML Script {@code <include name="…\X">};</li>
 *   <li>{@code map} — a {@code <token-map table="…\X">} naming a mapping table
 *       (the last DN component is the table's name; a {@code $var$} reference
 *       can't be indexed).</li>
 *   <li>{@code driver} — ({@link #withDrivers} only) a driver that runs a policy reading the mapping table.</li>
 * </ul>
 * Name-based kinds (include, map) match on the artifact's name within the scope
 * the reference can reach, the way the engine resolves them.
 */
public final class Refs {

    /** One reference to an artifact. */
    public static final class Ref {
        public final String kind;      // link | driverset-link | include | map
        public final String from;      // "drivers/D" for a link, an artifact path for include/map, "driverset"
        public final String detail;    // "set subscriber-command order 3", the attribute value, …
        public final Element element;  // the referencing element for include/map (to rewrite), else null
        public final PolicyLink link;  // the link for kind=link, else null

        Ref(String kind, String from, String detail, Element element, PolicyLink link) {
            this.kind = kind;
            this.from = from;
            this.detail = detail;
            this.element = element;
            this.link = link;
        }

        @Override
        public String toString() {
            return kind + " from " + from + " (" + detail + ")";
        }
    }

    private Refs() {
    }

    /** Every reference to the artifact at {@code path}. */
    public static List<Ref> to(DriverSet ds, String path) {
        Map<String, Artifact> index = ds.index();
        Artifact target = index.get(path);
        List<Ref> out = new ArrayList<>();
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                if (l.ref.equals(path)) {
                    out.add(new Ref("link", "drivers/" + d.name, "set " + l.set.key + " order " + l.order, null, l));
                }
            }
        }
        if (target == null) {
            return out;
        }
        for (Map.Entry<String, String> m : ds.meta.entrySet()) {
            if (m.getKey().startsWith("driverset.linkage.") && target.scope == Scope.LIBRARY
                && target.name.equals(leafOfLinkage(m.getValue()))) {
                out.add(new Ref("driverset-link", "driverset", m.getKey() + " = " + m.getValue(), null, null));
            }
        }
        // includes and map tokens: by name, from every policy that can reach the target
        for (Policy p : ds.library.policies) {
            scanContent(p, target, null, out);
        }
        for (Driver d : ds.drivers) {
            List<Policy> ps = new ArrayList<>(d.policies);
            ps.addAll(d.subscriber.policies);
            ps.addAll(d.publisher.policies);
            for (Policy p : ps) {
                scanContent(p, target, d, out);
            }
        }
        return out;
    }

    /**
     * {@link #to}, and for a mapping table also the drivers that use it ({@code driver} references, for reading:
     * rename and delete act on {@link #to} alone).
     */
    public static List<Ref> withDrivers(DriverSet ds, String path) {
        List<Ref> out = to(ds, path);
        Artifact target = ds.index().get(path);
        if (target instanceof Resource && ((Resource) target).isMappingTable()) {
            out.addAll(drivers(ds, out));
        }
        return out;
    }

    /**
     * The drivers that use a mapping table, through the policies that read it (its {@code map} references): a
     * driver's own policy, or a Library policy the driver links (or the driver set links, for every driver). A
     * table is not linked into a driver the way an ECMAScript resource is, so this is how a driver reaches it.
     */
    private static List<Ref> drivers(DriverSet ds, List<Ref> maps) {
        List<Ref> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Ref m : maps) {
            if (!"map".equals(m.kind)) {
                continue;
            }
            String policy = m.from;
            if (policy.startsWith("drivers/")) {
                String rest = policy.substring("drivers/".length());
                for (Driver d : ds.drivers) {
                    if (rest.startsWith(d.name + "/") && seen.add(d.name + "\u0000" + policy)) {
                        out.add(new Ref("driver", "drivers/" + d.name, "runs " + policy, null, null));
                    }
                }
                continue;
            }
            for (Driver d : ds.drivers) {
                for (PolicyLink l : d.links) {
                    if (l.ref.equals(policy) && seen.add(d.name + "\u0000" + policy)) {
                        out.add(new Ref("driver", "drivers/" + d.name, "links " + policy + " (set " + l.set.key + ")", null, null));
                    }
                }
            }
            for (Map.Entry<String, String> e : ds.meta.entrySet()) {
                Artifact pa = ds.index().get(policy);
                if (pa != null && e.getKey().startsWith("driverset.linkage.") && pa.name.equals(leafOfLinkage(e.getValue()))
                    && seen.add("driverset\u0000" + policy)) {
                    out.add(new Ref("driver", "driverset", "links " + policy + " for every driver (" + e.getKey() + ")", null, null));
                }
            }
        }
        return out;
    }

    private static void scanContent(Policy p, Artifact target, Driver from, List<Ref> out) {
        if (p.content == null) {
            return;
        }
        // a driver-scope artifact is only reachable from its own driver (or the library, never)
        if (target.scope != Scope.LIBRARY && (from == null || !from.name.equals(target.driver))) {
            return;
        }
        if (target instanceof Policy) {
            for (Element inc : Xds.descendantsByName(p.content, "include")) {
                String name = inc.getAttribute("name");
                if (target.name.equals(leaf(name))) {
                    out.add(new Ref("include", p.path(), "name=\"" + name + "\"", inc, null));
                }
            }
        } else if (target instanceof Resource && ((Resource) target).isMappingTable()) {
            for (Element map : Xds.descendantsByName(p.content, "token-map")) {
                String table = map.getAttribute("table");
                if (!table.contains("$") && target.name.equals(leaf(table))) {
                    out.add(new Ref("map", p.path(), "table=\"" + table + "\"", map, null));
                }
            }
        }
    }

    /** The last component of a slash DN ({@code ..\Library\X} → {@code X}) or bare name. */
    static String leaf(String dn) {
        if (dn == null) {
            return null;
        }
        String s = dn.trim();
        int i = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    /** The leaf of the DN in a {@code <dn>#<order>#<set>} linkage value (LDAP or slash form). */
    static String leafOfLinkage(String value) {
        int hash = value.indexOf('#');
        String dn = hash >= 0 ? value.substring(0, hash) : value;
        if (dn.contains("=")) {
            String first = dn.split("(?<!\\\\),")[0];
            return first.substring(first.indexOf('=') + 1).trim();
        }
        return leaf(dn);
    }

    /** Replace the last component of a slash DN / bare name with {@code newLeaf}. */
    static String withLeaf(String dn, String newLeaf) {
        int i = Math.max(dn.lastIndexOf('\\'), dn.lastIndexOf('/'));
        return i >= 0 ? dn.substring(0, i + 1) + newLeaf : newLeaf;
    }
}
