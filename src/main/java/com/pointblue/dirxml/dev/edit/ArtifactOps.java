package com.pointblue.dirxml.dev.edit;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The structural operations on artifacts — the things a text edit to the tree
 * gets wrong. Each is an {@link Operation}: it mutates the model, records what it
 * touched with the {@link Transaction}, and refuses (with the reason) rather than
 * leaving a dangling reference or a duplicate name.
 *
 * <ul>
 *   <li>{@link Add} — a new policy or resource at a scope, optionally linked;</li>
 *   <li>{@link SetContent} — replace an artifact's content;</li>
 *   <li>{@link Rename} — rename and rewrite every reference (links, driver-set
 *       linkage, includes, Map tokens);</li>
 *   <li>{@link Delete} — remove; refuses while anything references it unless
 *       {@code unlink} is set, which removes the links first (includes and Map
 *       tokens are never removed silently — they're content);</li>
 *   <li>{@link Link} / {@link Unlink} / {@link Reorder} — a driver's policy-set
 *       chains. Orders within a set are renumbered 0..n on every change so the
 *       tree stays deterministic.</li>
 * </ul>
 */
public final class ArtifactOps {

    private ArtifactOps() {
    }

    /** Where to put a link: an explicit order, or relative to the set's current members. */
    public static final class Position {
        public final String mode;   // first | last | after | before | order
        public final String ref;    // for after/before
        public final int order;     // for order

        private Position(String mode, String ref, int order) {
            this.mode = mode;
            this.ref = ref;
            this.order = order;
        }

        public static Position first() { return new Position("first", null, 0); }
        public static Position last() { return new Position("last", null, 0); }
        public static Position after(String ref) { return new Position("after", ref, 0); }
        public static Position before(String ref) { return new Position("before", ref, 0); }
        public static Position at(int order) { return new Position("order", null, order); }

        /** Parse {@code first | last | after:<path> | before:<path> | <int>}. */
        public static Position parse(String s) {
            if (s == null || s.isBlank() || s.equals("last")) {
                return last();
            }
            if (s.equals("first")) {
                return first();
            }
            if (s.startsWith("after:")) {
                return after(s.substring(6));
            }
            if (s.startsWith("before:")) {
                return before(s.substring(7));
            }
            try {
                return at(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("position must be first|last|after:<path>|before:<path>|<order>, not '" + s + "'");
            }
        }
    }

    // ---- add ------------------------------------------------------------------

    public static final class Add implements Operation {
        private final Scope scope;
        private final String driver;      // null for library
        private final String name;
        private final String kind;        // policy | xslt | schema-map | mapping-table | ecmascript | gcv
        private final String content;     // XML or JS text; null → a skeleton
        private final PolicySet linkSet;  // optional
        private final Position position;
        private final String linkDriver;  // for a library artifact: which driver links it

        public Add(Scope scope, String driver, String name, String kind, String content,
                   PolicySet linkSet, Position position, String linkDriver) {
            this.scope = scope;
            this.driver = driver;
            this.name = name;
            this.kind = kind;
            this.content = content;
            this.linkSet = linkSet;
            this.position = position == null ? Position.last() : position;
            this.linkDriver = linkDriver;
        }

        @Override
        public String name() {
            return "policy".equals(kind) || "xslt".equals(kind) || "schema-map".equals(kind) ? "policy.add" : "resource.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            if (name == null || name.isBlank()) {
                throw new Refusal("a name is required");
            }
            String path = Artifact.path(scope, driver, name);
            if (ds.index().containsKey(path)) {
                throw new Refusal("'" + path + "' already exists");
            }
            Driver d = scope == Scope.LIBRARY ? null : driverOrRefuse(ds, driver);
            Artifact a;
            switch (kind) {
                case "policy":
                    a = new Policy(name, scope, driver, parse(content != null ? content : skeletonPolicy(name)));
                    break;
                case "xslt":
                    a = new Policy(name, scope, driver, parse(content != null ? content : SKELETON_XSLT));
                    break;
                case "schema-map":
                    a = new Policy(name, scope, driver, parse(content != null ? content : "<attr-name-map/>"));
                    break;
                case "mapping-table": {
                    Resource r = new Resource(name, scope, driver, Resource.MAPPING_TABLE + " ;charset=UTF-8");
                    r.content = parse(content != null ? content : SKELETON_TABLE);
                    a = r;
                    break;
                }
                case "ecmascript": {
                    Resource r = new Resource(name, scope, driver, Resource.ECMASCRIPT);
                    r.text = content != null ? content : "// " + name + "\n";
                    a = r;
                    break;
                }
                case "gcv": {
                    Resource r = new Resource(name, scope, driver, Resource.GCV_DEF);
                    r.content = parse(content != null ? content : "<configuration-values><definitions/></configuration-values>");
                    a = r;
                    break;
                }
                default:
                    throw new Refusal("kind must be policy|xslt|schema-map|mapping-table|ecmascript|gcv, not '" + kind + "'");
            }
            if (a instanceof Policy) {
                if (((Policy) a).policyKind() == Policy.Kind.OTHER) {
                    throw new Refusal("content root <" + ((Policy) a).content.getNodeName()
                        + "> is not <policy>, an XSLT stylesheet or <attr-name-map>");
                }
            }
            place(ds, d, a);
            tx.touch(a);
            if (linkSet != null) {
                Driver ld = d != null ? d : driverOrRefuse(ds, linkDriver != null ? linkDriver
                    : refuse("a library artifact needs --link-driver to say which driver links it"));
                link(ld, linkSet, path, position);
            }
        }
    }

    // ---- set-content ----------------------------------------------------------

    public static final class SetContent implements Operation {
        private final String path;
        private final String content;

        public SetContent(String path, String content) {
            this.path = path;
            this.content = content;
        }

        @Override
        public String name() {
            return "artifact.set-content";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Artifact a = artifactOrRefuse(ds, path);
            tx.touch(a);   // before the change: the baseline is the pre-edit content
            if (a instanceof Policy) {
                Policy p = (Policy) a;
                Element el = parse(content);
                if (Policy.Kind.of(el) == Policy.Kind.OTHER) {
                    throw new Refusal("content root <" + el.getNodeName() + "> is not <policy>, an XSLT stylesheet or <attr-name-map>");
                }
                p.content = el;
            } else {
                Resource r = (Resource) a;
                if (r.isText() || r.isEcmaScript()) {
                    r.text = content;
                    r.content = null;
                } else {
                    r.content = parse(content);
                    r.text = null;
                }
            }
        }
    }

    // ---- rename ---------------------------------------------------------------

    public static final class Rename implements Operation {
        private final String path;
        private final String newName;

        public Rename(String path, String newName) {
            this.path = path;
            this.newName = newName;
        }

        @Override
        public String name() {
            return "artifact.rename";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Artifact old = artifactOrRefuse(ds, path);
            if (newName == null || newName.isBlank() || newName.contains("/") || newName.contains("\\")) {
                throw new Refusal("new name must be a plain object name");
            }
            String newPath = Artifact.path(old.scope, old.driver, newName);
            if (ds.index().containsKey(newPath)) {
                throw new Refusal("'" + newPath + "' already exists");
            }
            // references first (they're found by the old name)
            List<Refs.Ref> refs = Refs.to(ds, path);
            Artifact fresh = copyWithName(old, newName);
            replace(ds, old, fresh);
            for (Refs.Ref ref : refs) {
                switch (ref.kind) {
                    case "link":
                        ref.link.ref = newPath;
                        break;
                    case "driverset-link": {
                        String key = ref.detail.substring(0, ref.detail.indexOf(" = "));
                        ds.meta.put(key, renameLinkageLeaf(ds.meta.get(key), old.name, newName));
                        break;
                    }
                    case "include": {
                        String v = ref.element.getAttribute("name");
                        ref.element.setAttribute("name", Refs.withLeaf(v, newName));
                        touchOwner(ds, ref.from, tx);
                        break;
                    }
                    case "map": {
                        String v = ref.element.getAttribute("table");
                        ref.element.setAttribute("table", Refs.withLeaf(v, newName));
                        if (ref.element.hasAttribute("table-dot-dn")) {
                            ref.element.setAttribute("table-dot-dn",
                                renameLinkageLeaf(ref.element.getAttribute("table-dot-dn"), old.name, newName));
                        }
                        touchOwner(ds, ref.from, tx);
                        break;
                    }
                    default:
                        break;
                }
            }
            tx.renamed(path, newPath);
            tx.touch(fresh);
        }
    }

    // ---- delete ---------------------------------------------------------------

    public static final class Delete implements Operation {
        private final String path;
        private final boolean unlink;

        public Delete(String path, boolean unlink) {
            this.path = path;
            this.unlink = unlink;
        }

        @Override
        public String name() {
            return "artifact.delete";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Artifact a = artifactOrRefuse(ds, path);
            List<Refs.Ref> refs = Refs.to(ds, path);
            List<String> blocking = new ArrayList<>();
            for (Refs.Ref ref : refs) {
                if (ref.kind.equals("link") && unlink) {
                    continue;
                }
                blocking.add(ref.toString());
            }
            if (!blocking.isEmpty()) {
                throw new Refusal("'" + path + "' is still referenced — " + String.join("; ", blocking)
                    + (unlink ? "" : " (use --unlink to remove policy-set links first)"));
            }
            for (Refs.Ref ref : refs) {
                if (ref.kind.equals("link")) {
                    Driver d = ds.driver(ref.from.substring("drivers/".length()));
                    d.links.remove(ref.link);
                    renumber(d, ref.link.set);
                }
            }
            remove(ds, a);
            tx.touch(a);
        }
    }

    // ---- link / unlink / reorder ---------------------------------------------

    public static final class Link implements Operation {
        private final String path;
        private final String driver;
        private final PolicySet set;
        private final Position position;

        public Link(String path, String driver, PolicySet set, Position position) {
            this.path = path;
            this.driver = driver;
            this.set = set;
            this.position = position == null ? Position.last() : position;
        }

        @Override
        public String name() {
            return "policy.link";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            artifactOrRefuse(ds, path);
            Driver d = driverOrRefuse(ds, driver);
            link(d, set, path, position);
        }
    }

    public static final class Unlink implements Operation {
        private final String path;
        private final String driver;
        private final PolicySet set;

        public Unlink(String path, String driver, PolicySet set) {
            this.path = path;
            this.driver = driver;
            this.set = set;
        }

        @Override
        public String name() {
            return "policy.unlink";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            Driver d = driverOrRefuse(ds, driver);
            PolicyLink found = null;
            for (PolicyLink l : d.links(set)) {
                if (l.ref.equals(path)) {
                    found = l;
                }
            }
            if (found == null) {
                throw new Refusal("'" + path + "' is not linked in " + d.name + " set " + set.key);
            }
            d.links.remove(found);
            renumber(d, set);
        }
    }

    public static final class Reorder implements Operation {
        private final String driver;
        private final PolicySet set;
        private final List<String> order;

        public Reorder(String driver, PolicySet set, List<String> order) {
            this.driver = driver;
            this.set = set;
            this.order = order;
        }

        @Override
        public String name() {
            return "policy.reorder";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            Driver d = driverOrRefuse(ds, driver);
            List<PolicyLink> current = d.links(set);
            List<String> have = new ArrayList<>();
            for (PolicyLink l : current) {
                have.add(l.ref);
            }
            if (order.size() != have.size() || !have.containsAll(order) || !order.containsAll(have)) {
                throw new Refusal("the new order must list exactly the set's current members: " + have);
            }
            d.links.removeAll(current);
            for (int i = 0; i < order.size(); i++) {
                d.links.add(new PolicyLink(set, order.get(i), i));
            }
        }
    }

    // ---- shared mechanics -----------------------------------------------------

    static void link(Driver d, PolicySet set, String path, Position pos) throws Operation.Refusal {
        List<PolicyLink> current = d.links(set);
        for (PolicyLink l : current) {
            if (l.ref.equals(path)) {
                throw new Operation.Refusal("'" + path + "' is already linked in " + d.name + " set " + set.key);
            }
        }
        int at;
        switch (pos.mode) {
            case "first":
                at = 0;
                break;
            case "after":
            case "before": {
                int i = indexOf(current, pos.ref);
                if (i < 0) {
                    throw new Operation.Refusal("'" + pos.ref + "' is not in " + d.name + " set " + set.key);
                }
                at = pos.mode.equals("after") ? i + 1 : i;
                break;
            }
            case "order":
                at = Math.max(0, Math.min(current.size(), pos.order));
                break;
            default:
                at = current.size();
        }
        d.links.removeAll(current);
        List<String> refs = new ArrayList<>();
        for (PolicyLink l : current) {
            refs.add(l.ref);
        }
        refs.add(at, path);
        for (int i = 0; i < refs.size(); i++) {
            d.links.add(new PolicyLink(set, refs.get(i), i));
        }
    }

    static void renumber(Driver d, PolicySet set) {
        List<PolicyLink> current = d.links(set);
        for (int i = 0; i < current.size(); i++) {
            current.get(i).order = i;
        }
    }

    private static int indexOf(List<PolicyLink> links, String ref) {
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).ref.equals(ref)) {
                return i;
            }
        }
        return -1;
    }

    static Driver driverOrRefuse(DriverSet ds, String name) throws Operation.Refusal {
        if (name == null || name.isBlank()) {
            throw new Operation.Refusal("a driver name is required");
        }
        Driver d = ds.driver(name);
        if (d == null) {
            List<String> names = new ArrayList<>();
            for (Driver x : ds.drivers) {
                names.add(x.name);
            }
            throw new Operation.Refusal("no driver '" + name + "'; drivers: " + names);
        }
        return d;
    }

    static Artifact artifactOrRefuse(DriverSet ds, String path) throws Operation.Refusal {
        Artifact a = path == null ? null : ds.index().get(path);
        if (a == null) {
            throw new Operation.Refusal("no artifact at '" + path + "'");
        }
        return a;
    }

    private static String refuse(String msg) throws Operation.Refusal {
        throw new Operation.Refusal(msg);
    }

    static Element parse(String xml) throws Operation.Refusal {
        try {
            return CanonicalXml.parse(xml).getDocumentElement();
        } catch (RuntimeException e) {
            throw new Operation.Refusal("content is not well-formed XML: " + e.getMessage());
        }
    }

    /** Put a new artifact in the list its scope belongs to. */
    static void place(DriverSet ds, Driver d, Artifact a) {
        if (a instanceof Policy) {
            Policy p = (Policy) a;
            switch (a.scope) {
                case LIBRARY: ds.library.policies.add(p); break;
                case DRIVER: d.policies.add(p); break;
                case SUBSCRIBER: d.subscriber.policies.add(p); break;
                case PUBLISHER: d.publisher.policies.add(p); break;
                default: throw new IllegalStateException();
            }
        } else {
            Resource r = (Resource) a;
            if (a.scope == Scope.LIBRARY) {
                ds.library.resources.add(r);
            } else {
                d.resources.add(r);
            }
        }
    }

    static void remove(DriverSet ds, Artifact a) {
        Driver d = a.scope == Scope.LIBRARY ? null : ds.driver(a.driver);
        if (a instanceof Policy) {
            switch (a.scope) {
                case LIBRARY: ds.library.policies.remove(a); break;
                case DRIVER: d.policies.remove(a); break;
                case SUBSCRIBER: d.subscriber.policies.remove(a); break;
                case PUBLISHER: d.publisher.policies.remove(a); break;
                default: throw new IllegalStateException();
            }
        } else if (a.scope == Scope.LIBRARY) {
            ds.library.resources.remove(a);
        } else {
            d.resources.remove(a);
        }
    }

    /** Same list position, new object (names are final on artifacts). */
    static void replace(DriverSet ds, Artifact old, Artifact fresh) {
        List<? extends Artifact> list;
        Driver d = old.scope == Scope.LIBRARY ? null : ds.driver(old.driver);
        if (old instanceof Policy) {
            switch (old.scope) {
                case LIBRARY: list = ds.library.policies; break;
                case DRIVER: list = d.policies; break;
                case SUBSCRIBER: list = d.subscriber.policies; break;
                case PUBLISHER: list = d.publisher.policies; break;
                default: throw new IllegalStateException();
            }
        } else {
            list = old.scope == Scope.LIBRARY ? ds.library.resources : d.resources;
        }
        @SuppressWarnings("unchecked")
        List<Artifact> l = (List<Artifact>) list;
        l.set(l.indexOf(old), fresh);
    }

    static Artifact copyWithName(Artifact old, String newName) {
        Artifact fresh;
        if (old instanceof Policy) {
            fresh = new Policy(newName, old.scope, old.driver, ((Policy) old).content);
        } else {
            Resource o = (Resource) old;
            Resource r = new Resource(newName, old.scope, old.driver, o.contentType);
            r.content = o.content;
            r.text = o.text;
            fresh = r;
        }
        fresh.meta.putAll(old.meta);
        return fresh;
    }

    /** Mark the policy that owns a rewritten include / Map token as touched. */
    private static void touchOwner(DriverSet ds, String path, Transaction tx) throws IOException {
        Artifact owner = ds.index().get(path);
        if (owner != null) {
            tx.touch(owner);
        }
    }

    /** Rename the leaf of a DN in LDAP ({@code cn=X,…}) or slash form. */
    static String renameLinkageLeaf(String value, String oldName, String newName) {
        if (value == null) {
            return null;
        }
        int hash = value.indexOf('#');
        String dn = hash >= 0 ? value.substring(0, hash) : value;
        String rest = hash >= 0 ? value.substring(hash) : "";
        String renamed;
        if (dn.contains("=")) {
            int eq = dn.indexOf('=');
            int comma = dn.indexOf(',');
            String leaf = comma < 0 ? dn.substring(eq + 1) : dn.substring(eq + 1, comma);
            renamed = leaf.trim().equals(oldName)
                ? dn.substring(0, eq + 1) + newName + (comma < 0 ? "" : dn.substring(comma)) : dn;
        } else {
            renamed = Refs.leaf(dn).equals(oldName) ? Refs.withLeaf(dn, newName) : dn;
        }
        return renamed + rest;
    }

    static String skeletonPolicy(String name) {
        return "<policy><rule><description>" + name.replace("&", "&amp;").replace("<", "&lt;")
            + "</description><conditions/><actions/></rule></policy>";
    }

    static final String SKELETON_XSLT =
        "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
            + "<xsl:template match=\"node()|@*\"><xsl:copy><xsl:apply-templates select=\"node()|@*\"/></xsl:copy></xsl:template>"
            + "</xsl:stylesheet>";

    static final String SKELETON_TABLE =
        "<mapping-table><col-def name=\"key\" type=\"nocase\"/><col-def name=\"value\" type=\"nocase\"/></mapping-table>";

    /** Convenience for callers that hold a map of options (the CLI). */
    static String opt(Map<String, String> args, String key) {
        String v = args.get(key);
        return v == null || v.isBlank() ? null : v;
    }
}
