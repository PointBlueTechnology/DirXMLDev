package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.ascode.DsObjectXml;
import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed edits of the AppConfig objects ({@code docs/appconfig.md} §9): the generic
 * {@code appconfig.set/add/remove} on any object, and {@code role.*}, {@code resource.*},
 * {@code entity.*} that know their kind's shape. All run inside a {@link Transaction}
 * like every other operation: the tree is validated before and after, a packaged object
 * edited for the first time gets a baseline and a {@code package.customized} mark, and
 * its checksum follows the content ({@link Packages#refreshChecksums}).
 *
 * <p>Localized strings ({@code nrfLocalizedNames} …) are given as {@code lang=text}
 * (or bare text for English) and merged per language into the vault's
 * {@code lang~text|…} value.
 */
public final class AppConfigOps {

    private AppConfigOps() {
    }

    // ------------------------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------------------------

    static Driver driverFor(DriverSet ds, String driverFlag) throws Operation.Refusal {
        if (driverFlag != null && !driverFlag.isBlank()) {
            Driver d = ds.driver(driverFlag);
            if (d == null) {
                throw new Operation.Refusal("no driver '" + driverFlag + "'");
            }
            if (d.provisioning == null) {
                throw new Operation.Refusal("driver '" + driverFlag + "' has no provisioning (AppConfig)");
            }
            return d;
        }
        Driver hit = null;
        for (Driver d : ds.drivers) {
            if (d.provisioning != null) {
                if (hit != null) {
                    throw new Operation.Refusal("more than one driver has provisioning (AppConfig) — say --driver");
                }
                hit = d;
            }
        }
        if (hit == null) {
            throw new Operation.Refusal("no driver in the tree has provisioning (AppConfig)");
        }
        return hit;
    }

    /** {@code drivers/<d>/provisioning/objects/<path>}: the result and baseline path of an object. */
    public static String path(Driver d, AppObject o) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/provisioning/objects/" + o.path();
    }

    static AppObject require(Provisioning p, String path) throws Operation.Refusal {
        AppObject o = p.object(path);
        if (o == null) {
            List<AppObject> named = p.objectsNamed(path);
            if (named.size() == 1) {
                return named.get(0);
            }
            if (named.size() > 1) {
                StringBuilder sb = new StringBuilder("'" + path + "' is ambiguous; give a path:");
                for (AppObject n : named) {
                    sb.append("\n  ").append(n.path());
                }
                throw new Operation.Refusal(sb.toString());
            }
            throw new Operation.Refusal("no AppConfig object at or named '" + path + "'");
        }
        return o;
    }

    /** Baseline (the object as it was) + customized mark for a packaged object on its first edit. */
    static void customize(Transaction tx, Driver d, AppObject o, String before) {
        if (!com.pointblue.dirxml.dev.model.PackageStamps.isPackaged(o.meta)) {
            return;
        }
        boolean newly = !"true".equals(o.meta.get(Packages.CUSTOMIZED_KEY));
        Path baseline = tx.tree().resolve(Packages.BASELINE_DIR).resolve(path(d, o) + ".xml");
        if (!Files.exists(baseline) && before != null) {
            tx.pendingBaseline(baseline, before);
        }
        o.meta.put(Packages.CUSTOMIZED_KEY, "true");
        if (newly) {
            tx.customizedNow(path(d, o));
        }
    }

    /** Merges {@code lang=text} entries (bare text = English) into a {@code lang~text|…} value. */
    static String mergeLocalized(String current, List<String> entries) {
        Map<String, String> m = new LinkedHashMap<>(AppConfigPolicy.localized(current));
        for (String e : entries) {
            if (e == null || e.isBlank()) {
                continue;
            }
            int eq = e.indexOf('=');
            String lang = "en";
            String text = e;
            if (eq > 0 && e.substring(0, eq).matches("[A-Za-z]{2,3}(-[A-Za-z]{2,4})?")) {
                lang = e.substring(0, eq);
                text = e.substring(eq + 1);
            }
            m.put(lang, text);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> x : m.entrySet()) {
            sb.append(sb.length() == 0 ? "" : "|").append(x.getKey()).append('~').append(x.getValue());
        }
        return sb.toString();
    }

    static List<String> list(String joined) {
        List<String> out = new ArrayList<>();
        if (joined != null) {
            for (String s : joined.split("\n")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    static List<String> commaList(String joined) {
        List<String> out = new ArrayList<>();
        for (String s : list(joined)) {
            for (String p : s.split(",")) {
                if (!p.isBlank()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    private static String dn(Provisioning p, AppObject o) {
        StringBuilder sb = new StringBuilder();
        for (int i = o.segments.size() - 1; i >= 0; i--) {
            sb.append("cn=").append(o.segments.get(i)).append(',');
        }
        return sb.append(p.dn == null ? "" : p.dn).toString();
    }

    /** Every object whose attributes name {@code target}'s DN, as {@code path (attribute)}. */
    static List<String> referrers(Provisioning p, AppObject target) {
        String dn = dn(p, target).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (AppObject o : p.objects) {
            if (o == target) {
                continue;
            }
            for (Map.Entry<String, List<String>> e : o.attrs.entrySet()) {
                if (AppConfigPolicy.isOperational(e.getKey())) {
                    continue;
                }
                for (String v : e.getValue()) {
                    if (v.toLowerCase(Locale.ROOT).contains(dn)) {
                        out.add(o.path() + " (" + e.getKey() + ")");
                        break;
                    }
                }
            }
        }
        return out;
    }

    static List<AppObject> children(Provisioning p, AppObject o) {
        List<AppObject> out = new ArrayList<>();
        String prefix = o.path().toLowerCase(Locale.ROOT) + "/";
        for (AppObject x : p.objects) {
            if (x.path().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(x);
            }
        }
        return out;
    }

    static AppObject ensureContainer(Provisioning p, List<String> segments, String cls, Transaction tx, Driver d) {
        String path = String.join("/", segments);
        AppObject c = p.object(path);
        if (c != null) {
            return c;
        }
        c = new AppObject(segments);
        c.classes.add("Top");
        c.classes.add(cls);
        c.meta.put("objectClass", cls);
        p.objects.add(c);
        tx.touched(path(d, c));
        tx.note("created container " + path + " (" + cls + ")");
        return c;
    }

    static void sortObjects(Provisioning p) {
        p.objects.sort(java.util.Comparator.comparing(AppObject::path, String.CASE_INSENSITIVE_ORDER));
    }

    static String snapshot(AppObject o) {
        return DsObjectXml.write(o);
    }

    // ------------------------------------------------------------------------------------
    // appconfig.set / add / remove
    // ------------------------------------------------------------------------------------

    /** Set (replace all values of), or remove, one attribute of any object; XML attributes from a file. */
    public static final class Set implements Operation {
        private final String driver;
        private final String path;
        private final String attr;
        private final List<String> values;
        private final String file;
        private final boolean remove;

        public Set(String driver, String path, String attr, String values, String file, boolean remove) {
            this.driver = driver;
            this.path = path;
            this.attr = attr;
            this.values = list(values);
            this.file = file == null || file.isBlank() ? null : file;
            this.remove = remove;
        }

        @Override
        public String name() {
            return "appconfig.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (path == null || path.isBlank() || attr == null || attr.isBlank()) {
                throw new Operation.Refusal("--path and --attr are required");
            }
            Driver d = driverFor(ds, driver);
            AppObject o = require(d.provisioning, path);
            String name = AppConfigPolicy.canonicalAttribute(attr);
            if (AppConfigPolicy.isNotContent(name)) {
                throw new Operation.Refusal("'" + name + "' is the object's identity or a package stamp, not an attribute to set");
            }
            if (AppConfigPolicy.isOperational(name)) {
                throw new Operation.Refusal("'" + name + "' is owned by the Identity Applications (operational); the deploy never writes it");
            }
            String before = snapshot(o);
            if (remove) {
                if (o.attrs.remove(name) == null) {
                    throw new Operation.Refusal("'" + o.path() + "' has no attribute '" + name + "'");
                }
                tx.note("removed " + name + " from " + o.path());
            } else {
                List<String> vs = new ArrayList<>(values);
                if (file != null) {
                    vs.add(Files.readString(Path.of(file), StandardCharsets.UTF_8));
                }
                if (vs.isEmpty()) {
                    throw new Operation.Refusal("--value (repeatable), --file or --remove is required");
                }
                if (AppConfigPolicy.isXmlAttribute(name)) {
                    List<String> canon = new ArrayList<>();
                    for (String v : vs) {
                        String xml = DsObjectXml.asXml(v);
                        if (xml == null) {
                            throw new Operation.Refusal(name + " must be a well-formed XML document");
                        }
                        canon.add(xml);
                    }
                    vs = canon;
                }
                if (AppConfigPolicy.isLocalizedAttribute(name) && vs.size() == 1 && vs.get(0).indexOf('~') < 0) {
                    vs = List.of(mergeLocalized(o.first(name), vs));
                }
                o.put(name, vs);
                tx.note("set " + name + " on " + o.path() + " (" + vs.size() + " value" + (vs.size() == 1 ? "" : "s") + ")");
            }
            customize(tx, d, o, before);
            tx.touched(path(d, o));
        }
    }

    /** Create any object: its structural class, optional auxiliary classes and {@code name=value} attributes. */
    public static final class Add implements Operation {
        private final String driver;
        private final String path;
        private final String cls;
        private final List<String> aux;
        private final List<String> attrs;

        public Add(String driver, String path, String cls, String aux, String attrs) {
            this.driver = driver;
            this.path = path;
            this.cls = cls;
            this.aux = commaList(aux);
            this.attrs = list(attrs);
        }

        @Override
        public String name() {
            return "appconfig.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (path == null || path.isBlank() || cls == null || cls.isBlank()) {
                throw new Operation.Refusal("--path and --class are required");
            }
            Driver d = driverFor(ds, driver);
            Provisioning p = d.provisioning;
            AppObject o = AppObject.ofPath(path);
            if (p.object(o.path()) != null) {
                throw new Operation.Refusal("'" + o.path() + "' already exists");
            }
            if (AppConfigPolicy.isRuntimePath(o.path()) || AppConfigPolicy.isRuntimeClass(cls)) {
                throw new Operation.Refusal("'" + o.path() + "' would be one of the Identity Applications' runtime records; those are never authored");
            }
            if (o.segments.size() > 1 && p.object(o.parentPath()) == null) {
                throw new Operation.Refusal("the container '" + o.parentPath() + "' does not exist (create it first with appconfig.add --class <container class>)");
            }
            o.classes.add("Top");
            o.classes.add(cls);
            for (String a : aux) {
                o.classes.add(a);
            }
            o.meta.put("objectClass", cls);
            for (String a : attrs) {
                int eq = a.indexOf('=');
                if (eq <= 0) {
                    throw new Operation.Refusal("--attr takes name=value, not '" + a + "'");
                }
                String name = AppConfigPolicy.canonicalAttribute(a.substring(0, eq).trim());
                String value = a.substring(eq + 1);
                List<String> vs = new ArrayList<>(o.all(name));
                vs.add(AppConfigPolicy.isXmlAttribute(name) && DsObjectXml.asXml(value) != null ? DsObjectXml.asXml(value) : value);
                o.put(name, vs);
            }
            p.objects.add(o);
            sortObjects(p);
            tx.touched(path(d, o));
            tx.note("created " + o.kind().key + " " + o.path() + " (" + cls + ")");
        }
    }

    /** Delete an object; refuses while it holds objects or anything names its DN. */
    public static final class Remove implements Operation {
        private final String driver;
        private final String path;

        public Remove(String driver, String path) {
            this.driver = driver;
            this.path = path;
        }

        @Override
        public String name() {
            return "appconfig.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject o = require(d.provisioning, path);
            removeObject(tx, d, o);
        }
    }

    static void removeObject(Transaction tx, Driver d, AppObject o) throws Operation.Refusal {
        Provisioning p = d.provisioning;
        for (String runtime : AppConfigPolicy.RUNTIME_CONTAINERS) {
            if (o.path().equalsIgnoreCase(runtime)) {
                throw new Operation.Refusal("'" + o.path() + "' is one of the Identity Applications' runtime containers; never removed");
            }
        }
        List<AppObject> kids = children(p, o);
        if (!kids.isEmpty()) {
            throw new Operation.Refusal("'" + o.path() + "' still holds " + kids.size() + " object(s); remove them first");
        }
        List<String> refs = referrers(p, o);
        if (!refs.isEmpty()) {
            throw new Operation.Refusal("'" + o.path() + "' is referenced by: " + String.join(", ", refs));
        }
        if (com.pointblue.dirxml.dev.model.PackageStamps.isPackaged(o.meta) && !tx.force()) {
            throw new Operation.Refusal("'" + o.path() + "' was installed by package " + com.pointblue.dirxml.dev.model.PackageStamps.packageId(o.meta)
                + "; a packaged object is customized, not removed (--force removes it anyway)");
        }
        p.objects.remove(o);
        tx.touched(path(d, o));
        tx.note("removed " + o.kind().key + " " + o.path());
    }

    // ------------------------------------------------------------------------------------
    // roles
    // ------------------------------------------------------------------------------------

    static AppObject findRole(Provisioning p, String name) throws Operation.Refusal {
        AppObject hit = null;
        for (AppObject o : p.objects) {
            if (o.kind() == AppObject.Kind.ROLE && (o.name().equalsIgnoreCase(name) || o.path().equalsIgnoreCase(name))) {
                if (hit != null) {
                    throw new Operation.Refusal("more than one role is named '" + name + "'; give the path (RoleConfig/RoleDefs/Level<n>/<category>/<name>)");
                }
                hit = o;
            }
        }
        if (hit == null) {
            throw new Operation.Refusal("no role '" + name + "'");
        }
        return hit;
    }

    static void applyLocalized(AppObject o, String attr, List<String> entries) {
        if (!entries.isEmpty()) {
            o.put(attr, List.of(mergeLocalized(o.first(attr), entries)));
        }
    }

    static void applyDns(AppObject o, String attr, List<String> dns, boolean clearWhenEmptyMarker) {
        if (dns.isEmpty()) {
            return;
        }
        if (dns.size() == 1 && dns.get(0).equals("-")) {
            o.attrs.remove(attr);
            return;
        }
        List<String> vs = new ArrayList<>(dns);
        vs.sort(null);
        o.put(attr, vs);
    }

    /** Create a role under {@code RoleConfig/RoleDefs/Level<n>/<category>}, the category container included. */
    public static final class RoleAdd implements Operation {
        private final String driver;
        private final String roleName;
        private final String level;
        private final String category;
        private final List<String> display;
        private final List<String> descr;
        private final List<String> owners;
        private final List<String> approvers;
        private final String quorum;

        public RoleAdd(String driver, String roleName, String level, String category, String display, String descr,
                       String owners, String approvers, String quorum) {
            this.driver = driver;
            this.roleName = roleName;
            this.level = level == null ? null : level.trim().toLowerCase(Locale.ROOT).replace("level", "");
            this.category = category;
            this.display = list(display);
            this.descr = list(descr);
            this.owners = list(owners);
            this.approvers = list(approvers);
            this.quorum = quorum == null || quorum.isBlank() ? null : quorum.trim();
        }

        @Override
        public String name() {
            return "role.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (roleName == null || roleName.isBlank()) {
                throw new Operation.Refusal("--name is required");
            }
            if (level == null || !level.matches("10|20|30")) {
                throw new Operation.Refusal("--level must be 10 (permission), 20 (IT) or 30 (business)");
            }
            if (category == null || category.isBlank()) {
                throw new Operation.Refusal("--category is required (the container under Level" + level + ", e.g. System)");
            }
            Driver d = driverFor(ds, driver);
            Provisioning p = d.provisioning;
            AppObject levelContainer = p.object("RoleConfig/RoleDefs/Level" + level);
            if (levelContainer == null) {
                throw new Operation.Refusal("the tree has no RoleConfig/RoleDefs/Level" + level + " container (import-live to adopt the role catalog first)");
            }
            for (AppObject o : p.objects) {
                if (o.kind() == AppObject.Kind.ROLE && o.name().equalsIgnoreCase(roleName)) {
                    throw new Operation.Refusal("a role named '" + roleName + "' already exists at " + o.path());
                }
            }
            AppObject cat = ensureContainer(p, List.of("RoleConfig", "RoleDefs", "Level" + level, category), "nrfRoleDefs", tx, d);
            AppObject role = new AppObject(List.of("RoleConfig", "RoleDefs", "Level" + level, cat.name(), roleName));
            role.classes.add("Top");
            role.classes.add("nrfRole");
            role.meta.put("objectClass", "nrfRole");
            role.put("nrfRoleLevel", List.of(level));
            role.put("nrfStatus", List.of("50"));
            role.put("nrfRoleCategoryKey", List.of(category.toLowerCase(Locale.ROOT)));
            role.put("nrfLocalizedNames", List.of(mergeLocalized(null, display.isEmpty() ? List.of(roleName) : display)));
            if (!descr.isEmpty()) {
                role.put("nrfLocalizedDescrs", List.of(mergeLocalized(null, descr)));
            }
            applyDns(role, "owner", owners, false);
            applyDns(role, "nrfApprovers", approvers, false);
            if (quorum != null) {
                role.put("nrfQuorum", List.of(quorum));
            }
            p.objects.add(role);
            sortObjects(p);
            tx.touched(path(d, role));
            tx.note("created role " + role.path() + " (level " + level + ")");
        }
    }

    /** Change a role's names, descriptions, category keys, owners, approvers, quorum or status. */
    public static final class RoleSet implements Operation {
        private final String driver;
        private final String roleName;
        private final List<String> display;
        private final List<String> descr;
        private final List<String> categories;
        private final List<String> owners;
        private final List<String> approvers;
        private final String quorum;
        private final String status;

        public RoleSet(String driver, String roleName, String display, String descr, String categories, String owners,
                       String approvers, String quorum, String status) {
            this.driver = driver;
            this.roleName = roleName;
            this.display = list(display);
            this.descr = list(descr);
            this.categories = commaList(categories);
            this.owners = list(owners);
            this.approvers = list(approvers);
            this.quorum = quorum == null || quorum.isBlank() ? null : quorum.trim();
            this.status = status == null || status.isBlank() ? null : status.trim();
        }

        @Override
        public String name() {
            return "role.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject role = findRole(d.provisioning, roleName);
            String before = snapshot(role);
            applyLocalized(role, "nrfLocalizedNames", display);
            applyLocalized(role, "nrfLocalizedDescrs", descr);
            if (!categories.isEmpty()) {
                List<String> vs = new ArrayList<>();
                for (String c : categories) {
                    vs.add(c.toLowerCase(Locale.ROOT));
                }
                vs.sort(null);
                role.put("nrfRoleCategoryKey", vs);
            }
            applyDns(role, "owner", owners, true);
            applyDns(role, "nrfApprovers", approvers, true);
            if (quorum != null) {
                role.put("nrfQuorum", List.of(quorum));
            }
            if (status != null) {
                role.put("nrfStatus", List.of(status));
            }
            if (snapshot(role).equals(before)) {
                throw new Operation.Refusal("nothing to change on role '" + role.name() + "'");
            }
            customize(tx, d, role, before);
            tx.touched(path(d, role));
            tx.note("changed role " + role.path());
        }
    }

    public static final class RoleRemove implements Operation {
        private final String driver;
        private final String roleName;

        public RoleRemove(String driver, String roleName) {
            this.driver = driver;
            this.roleName = roleName;
        }

        @Override
        public String name() {
            return "role.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            removeObject(tx, d, findRole(d.provisioning, roleName));
        }
    }

    // ------------------------------------------------------------------------------------
    // resources
    // ------------------------------------------------------------------------------------

    static AppObject findResource(Provisioning p, String name) throws Operation.Refusal {
        AppObject hit = null;
        for (AppObject o : p.objects) {
            if (o.kind() == AppObject.Kind.RESOURCE && (o.name().equalsIgnoreCase(name) || o.path().equalsIgnoreCase(name))) {
                if (hit != null) {
                    throw new Operation.Refusal("more than one resource is named '" + name + "'; give the path");
                }
                hit = o;
            }
        }
        if (hit == null) {
            throw new Operation.Refusal("no resource '" + name + "'");
        }
        return hit;
    }

    /** {@code dn#0#<ref><src>UA</src><id/><param>…</param></ref>}: an entitlement reference as a resource carries it. */
    static String entitlementRef(String dn, String param) {
        return dn + "#0#<ref><src>UA</src><id/><param>" + DsObjectXml.esc(param == null ? "" : param) + "</param></ref>";
    }

    /** Create a resource under {@code RoleConfig/ResourceDefs/<category>}, bound to an entitlement when given. */
    public static final class ResourceAdd implements Operation {
        private final String driver;
        private final String resName;
        private final String category;
        private final List<String> display;
        private final List<String> descr;
        private final String entitlement;
        private final String param;
        private final boolean multi;
        private final List<String> owners;
        private final List<String> approvers;

        public ResourceAdd(String driver, String resName, String category, String display, String descr, String entitlement,
                           String param, boolean multi, String owners, String approvers) {
            this.driver = driver;
            this.resName = resName;
            this.category = category;
            this.display = list(display);
            this.descr = list(descr);
            this.entitlement = entitlement == null || entitlement.isBlank() ? null : entitlement.trim();
            this.param = param;
            this.multi = multi;
            this.owners = list(owners);
            this.approvers = list(approvers);
        }

        @Override
        public String name() {
            return "resource.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (resName == null || resName.isBlank()) {
                throw new Operation.Refusal("--name is required");
            }
            if (category == null || category.isBlank()) {
                throw new Operation.Refusal("--category is required (the container under ResourceDefs, e.g. System)");
            }
            Driver d = driverFor(ds, driver);
            Provisioning p = d.provisioning;
            if (p.object("RoleConfig/ResourceDefs") == null) {
                throw new Operation.Refusal("the tree has no RoleConfig/ResourceDefs container (import-live to adopt the resource catalog first)");
            }
            for (AppObject o : p.objects) {
                if (o.kind() == AppObject.Kind.RESOURCE && o.name().equalsIgnoreCase(resName)) {
                    throw new Operation.Refusal("a resource named '" + resName + "' already exists at " + o.path());
                }
            }
            if (param != null && entitlement == null) {
                throw new Operation.Refusal("--param needs --entitlement");
            }
            AppObject cat = ensureContainer(p, List.of("RoleConfig", "ResourceDefs", category), "nrfResourceDefs", tx, d);
            AppObject r = new AppObject(List.of("RoleConfig", "ResourceDefs", cat.name(), resName));
            r.classes.add("Top");
            r.classes.add("nrfResource");
            r.meta.put("objectClass", "nrfResource");
            r.put("nrfCategoryKey", List.of(category.toLowerCase(Locale.ROOT)));
            r.put("nrfLocalizedNames", List.of(mergeLocalized(null, display.isEmpty() ? List.of(resName) : display)));
            if (!descr.isEmpty()) {
                r.put("nrfLocalizedDescrs", List.of(mergeLocalized(null, descr)));
            }
            r.put("nrfActive", List.of("FALSE"));
            r.put("nrfAllowAprOveride", List.of("FALSE"));
            r.put("nrfAllowMulti", List.of(multi ? "TRUE" : "FALSE"));
            if (entitlement != null) {
                r.put("nrfEntitlementRef", List.of(entitlementRef(entitlement, param)));
            }
            applyDns(r, "owner", owners, false);
            applyDns(r, "nrfApprovers", approvers, false);
            p.objects.add(r);
            sortObjects(p);
            tx.touched(path(d, r));
            tx.note("created resource " + r.path() + (entitlement != null ? " bound to " + entitlement : ""));
        }
    }

    public static final class ResourceSet implements Operation {
        private final String driver;
        private final String resName;
        private final List<String> display;
        private final List<String> descr;
        private final List<String> categories;
        private final String entitlement;
        private final String param;
        private final Boolean multi;
        private final Boolean active;
        private final List<String> owners;
        private final List<String> approvers;

        public ResourceSet(String driver, String resName, String display, String descr, String categories, String entitlement,
                           String param, Boolean multi, Boolean active, String owners, String approvers) {
            this.driver = driver;
            this.resName = resName;
            this.display = list(display);
            this.descr = list(descr);
            this.categories = commaList(categories);
            this.entitlement = entitlement == null || entitlement.isBlank() ? null : entitlement.trim();
            this.param = param;
            this.multi = multi;
            this.active = active;
            this.owners = list(owners);
            this.approvers = list(approvers);
        }

        @Override
        public String name() {
            return "resource.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject r = findResource(d.provisioning, resName);
            String before = snapshot(r);
            applyLocalized(r, "nrfLocalizedNames", display);
            applyLocalized(r, "nrfLocalizedDescrs", descr);
            if (!categories.isEmpty()) {
                List<String> vs = new ArrayList<>();
                for (String c : categories) {
                    vs.add(c.toLowerCase(Locale.ROOT));
                }
                vs.sort(null);
                r.put("nrfCategoryKey", vs);
            }
            if (entitlement != null) {
                if (entitlement.equals("-")) {
                    r.attrs.remove("nrfEntitlementRef");
                } else {
                    r.put("nrfEntitlementRef", List.of(entitlementRef(entitlement, param)));
                }
            } else if (param != null) {
                throw new Operation.Refusal("--param needs --entitlement");
            }
            if (multi != null) {
                r.put("nrfAllowMulti", List.of(multi ? "TRUE" : "FALSE"));
            }
            if (active != null) {
                r.put("nrfActive", List.of(active ? "TRUE" : "FALSE"));
            }
            applyDns(r, "owner", owners, true);
            applyDns(r, "nrfApprovers", approvers, true);
            if (snapshot(r).equals(before)) {
                throw new Operation.Refusal("nothing to change on resource '" + r.name() + "'");
            }
            customize(tx, d, r, before);
            tx.touched(path(d, r));
            tx.note("changed resource " + r.path());
        }
    }

    public static final class ResourceRemove implements Operation {
        private final String driver;
        private final String resName;

        public ResourceRemove(String driver, String resName) {
            this.driver = driver;
            this.resName = resName;
        }

        @Override
        public String name() {
            return "resource.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            removeObject(tx, d, findResource(d.provisioning, resName));
        }
    }

    // ------------------------------------------------------------------------------------
    // entities (the directory abstraction layer)
    // ------------------------------------------------------------------------------------

    static AppObject findEntity(Provisioning p, String key) throws Operation.Refusal {
        AppObject e = p.object("DirectoryModel/EntityDefs/" + key);
        if (e == null || e.kind() != AppObject.Kind.ENTITY) {
            throw new Operation.Refusal("no entity '" + key + "' (DirectoryModel/EntityDefs)");
        }
        return e;
    }

    static Document entityDoc(AppObject e) throws Operation.Refusal {
        String xml = e.first("XmlData");
        if (xml == null) {
            throw new Operation.Refusal("entity '" + e.name() + "' has no XmlData");
        }
        try {
            return CanonicalXml.parse(xml);
        } catch (RuntimeException ex) {
            throw new Operation.Refusal("entity '" + e.name() + "' XmlData does not parse: " + ex.getMessage());
        }
    }

    static Element attributeElement(Document doc, String key) {
        Element attrs = Xds.firstByName(doc.getDocumentElement(), "attributes");
        if (attrs == null) {
            return null;
        }
        for (Element a : Xds.childrenByName(attrs, "attribute")) {
            Element k = Xds.firstByName(a, "key");
            if (k != null && k.getTextContent().equals(key)) {
                return a;
            }
        }
        return null;
    }

    static void setDisplays(Document doc, Element parent, List<String> entries) {
        Map<String, String> current = new LinkedHashMap<>();
        for (Element d : Xds.childrenByName(parent, "display")) {
            Element label = Xds.firstByName(d, "label");
            current.put(d.getAttribute("xml:lang"), label != null ? label.getTextContent() : d.getTextContent().trim());
        }
        Map<String, String> merged = AppConfigPolicy.localized(mergeLocalized(joinLocalized(current), entries));
        for (Element d : new ArrayList<>(Xds.childrenByName(parent, "display"))) {
            parent.removeChild(d);
        }
        // displays go right after key/ldap-name/nds-name, before type — Designer's order
        Node anchor = null;
        for (Element c : Xds.childElements(parent)) {
            String n = c.getNodeName();
            if (n.equals("key") || n.equals("ldap-name") || n.equals("nds-name")) {
                anchor = c.getNextSibling();
            }
        }
        for (Map.Entry<String, String> e : merged.entrySet()) {
            Element d = doc.createElementNS(null, "display");
            d.setAttribute("xml:lang", e.getKey());
            Element label = doc.createElementNS(null, "label");
            label.appendChild(doc.createTextNode(e.getValue()));
            d.appendChild(label);
            if (anchor != null) {
                parent.insertBefore(d, anchor);
            } else {
                parent.appendChild(d);
            }
        }
    }

    private static String joinLocalized(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append(sb.length() == 0 ? "" : "|").append(e.getKey()).append('~').append(e.getValue());
        }
        return sb.toString();
    }

    static void storeEntity(Transaction tx, Driver d, AppObject e, Document doc, String before) {
        e.put("XmlData", List.of(CanonicalXml.serialize(doc)));
        customize(tx, d, e, before);
        tx.touched(path(d, e));
    }

    static final List<String> ATTR_FLAGS = List.of("editable", "enabled", "hideable", "multivalue", "protected", "readable", "required", "searchable", "viewable");
    static final List<String> ENTITY_FLAGS = List.of("auto-query", "creatable", "editable", "protected", "removable", "system", "viewable");

    /** Create an entity definition: object class, search root, naming attribute, no attributes yet. */
    public static final class EntityAdd implements Operation {
        private final String driver;
        private final String key;
        private final String objectClass;
        private final List<String> auxClasses;
        private final List<String> display;
        private final String searchRoot;
        private final String namingAttribute;
        private final Map<String, String> flags;

        public EntityAdd(String driver, String key, String objectClass, String auxClasses, String display, String searchRoot,
                         String namingAttribute, Map<String, String> flags) {
            this.driver = driver;
            this.key = key;
            this.objectClass = objectClass;
            this.auxClasses = commaList(auxClasses);
            this.display = list(display);
            this.searchRoot = searchRoot;
            this.namingAttribute = namingAttribute == null || namingAttribute.isBlank() ? "cn" : namingAttribute.trim();
            this.flags = flags;
        }

        @Override
        public String name() {
            return "entity.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (key == null || key.isBlank() || objectClass == null || objectClass.isBlank()) {
                throw new Operation.Refusal("--key and --object-class are required");
            }
            Driver d = driverFor(ds, driver);
            Provisioning p = d.provisioning;
            if (p.object("DirectoryModel/EntityDefs") == null) {
                throw new Operation.Refusal("the tree has no DirectoryModel/EntityDefs container (import-live to adopt the directory abstraction layer first)");
            }
            if (p.object("DirectoryModel/EntityDefs/" + key) != null) {
                throw new Operation.Refusal("an entity '" + key + "' already exists");
            }
            StringBuilder sb = new StringBuilder("<entity-definition><entity");
            for (String f : ENTITY_FLAGS) {
                String v = flags.getOrDefault(f, f.equals("auto-query") || f.equals("system") || f.equals("protected") ? "false" : "true");
                sb.append(' ').append(f).append("=\"").append(DsObjectXml.esc(v)).append('"');
            }
            sb.append('>');
            for (Map.Entry<String, String> x : AppConfigPolicy.localized(mergeLocalized(null, display.isEmpty() ? List.of(key) : display)).entrySet()) {
                sb.append("<display xml:lang=\"").append(DsObjectXml.esc(x.getKey())).append("\"><label>").append(DsObjectXml.esc(x.getValue())).append("</label></display>");
            }
            if (searchRoot != null && !searchRoot.isBlank()) {
                sb.append("<search-root>").append(DsObjectXml.esc(searchRoot)).append("</search-root>");
            }
            sb.append("<object-class auxiliary=\"false\" search=\"true\">").append(DsObjectXml.esc(objectClass)).append("</object-class>");
            for (String aux : auxClasses) {
                sb.append("<object-class add-always=\"true\" auxiliary=\"true\" search=\"false\">").append(DsObjectXml.esc(aux)).append("</object-class>");
            }
            sb.append("<naming-attribute>").append(DsObjectXml.esc(namingAttribute)).append("</naming-attribute>");
            sb.append("<edit-entity-key>").append(DsObjectXml.esc(key)).append("</edit-entity-key>");
            sb.append("</entity><attributes/></entity-definition>");
            AppObject e = new AppObject(List.of("DirectoryModel", "EntityDefs", key));
            e.classes.add("Top");
            e.classes.add("srvprvEntity");
            e.meta.put("objectClass", "srvprvEntity");
            e.put("srvprvEntityType", List.of("P"));
            e.put("description", List.of(key));
            e.put("XmlData", List.of(CanonicalXml.canonicalize(sb.toString())));
            p.objects.add(e);
            sortObjects(p);
            tx.touched(path(d, e));
            tx.note("created entity " + key + " (" + objectClass + ")");
        }
    }

    /** Entity-level settings: display names, flags, search root. */
    public static final class EntitySet implements Operation {
        private final String driver;
        private final String key;
        private final List<String> display;
        private final String searchRoot;
        private final Map<String, String> flags;

        public EntitySet(String driver, String key, String display, String searchRoot, Map<String, String> flags) {
            this.driver = driver;
            this.key = key;
            this.display = list(display);
            this.searchRoot = searchRoot;
            this.flags = flags;
        }

        @Override
        public String name() {
            return "entity.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject e = findEntity(d.provisioning, key);
            String before = snapshot(e);
            Document doc = entityDoc(e);
            Element entity = Xds.firstByName(doc.getDocumentElement(), "entity");
            if (entity == null) {
                throw new Operation.Refusal("entity '" + key + "' has no <entity> element");
            }
            for (Map.Entry<String, String> f : flags.entrySet()) {
                if (!ENTITY_FLAGS.contains(f.getKey())) {
                    throw new Operation.Refusal("unknown entity flag '" + f.getKey() + "' (" + String.join(", ", ENTITY_FLAGS) + ")");
                }
                entity.setAttribute(f.getKey(), f.getValue());
            }
            if (!display.isEmpty()) {
                setDisplays(doc, entity, display);
            }
            if (searchRoot != null && !searchRoot.isBlank()) {
                Element sr = Xds.firstByName(entity, "search-root");
                if (sr == null) {
                    sr = doc.createElementNS(null, "search-root");
                    entity.appendChild(sr);
                }
                while (sr.getFirstChild() != null) {
                    sr.removeChild(sr.getFirstChild());
                }
                sr.appendChild(doc.createTextNode(searchRoot));
            }
            storeEntity(tx, d, e, doc, before);
            if (snapshot(e).equals(before)) {
                throw new Operation.Refusal("nothing to change on entity '" + key + "'");
            }
            tx.note("changed entity " + key);
        }
    }

    public static final class EntityRemove implements Operation {
        private final String driver;
        private final String key;

        public EntityRemove(String driver, String key) {
            this.driver = driver;
            this.key = key;
        }

        @Override
        public String name() {
            return "entity.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject e = findEntity(d.provisioning, key);
            if ("S".equalsIgnoreCase(e.first("srvprvEntityType"))) {
                throw new Operation.Refusal("'" + key + "' is a system entity (srvprvEntityType S); the applications need it");
            }
            removeObject(tx, d, e);
        }
    }

    /** Add an attribute to an entity's definition: key, LDAP and NDS names, type, display labels, flags. */
    public static final class EntityAttrAdd implements Operation {
        private final String driver;
        private final String entity;
        private final String key;
        private final String ldap;
        private final String nds;
        private final String type;
        private final List<String> display;
        private final Map<String, String> flags;

        public EntityAttrAdd(String driver, String entity, String key, String ldap, String nds, String type, String display, Map<String, String> flags) {
            this.driver = driver;
            this.entity = entity;
            this.key = key;
            this.ldap = ldap;
            this.nds = nds;
            this.type = type == null || type.isBlank() ? "String" : type.trim();
            this.display = list(display);
            this.flags = flags;
        }

        @Override
        public String name() {
            return "entity.attr.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (entity == null || key == null || key.isBlank() || ldap == null || ldap.isBlank()) {
                throw new Operation.Refusal("--entity, --key and --ldap are required");
            }
            Driver d = driverFor(ds, driver);
            AppObject e = findEntity(d.provisioning, entity);
            String before = snapshot(e);
            Document doc = entityDoc(e);
            if (attributeElement(doc, key) != null) {
                throw new Operation.Refusal("entity '" + entity + "' already has an attribute '" + key + "'");
            }
            Element attrs = Xds.firstByName(doc.getDocumentElement(), "attributes");
            if (attrs == null) {
                attrs = doc.createElementNS(null, "attributes");
                doc.getDocumentElement().appendChild(attrs);
            }
            Element a = doc.createElementNS(null, "attribute");
            for (String f : ATTR_FLAGS) {
                String dflt = f.equals("hideable") || f.equals("multivalue") || f.equals("protected") || f.equals("required") ? "false" : "true";
                a.setAttribute(f, flags.getOrDefault(f, dflt));
            }
            for (String f : flags.keySet()) {
                if (!ATTR_FLAGS.contains(f)) {
                    throw new Operation.Refusal("unknown attribute flag '" + f + "' (" + String.join(", ", ATTR_FLAGS) + ")");
                }
            }
            text(doc, a, "key", key);
            text(doc, a, "ldap-name", ldap);
            text(doc, a, "nds-name", nds == null || nds.isBlank() ? ldap : nds);
            attrs.appendChild(a);
            setDisplays(doc, a, display.isEmpty() ? List.of(key) : display);
            text(doc, a, "type", type);
            storeEntity(tx, d, e, doc, before);
            tx.note("added attribute " + key + " (" + ldap + ", " + type + ") to entity " + entity);
        }
    }

    /** Change an entity attribute's flags, type, names or display labels. */
    public static final class EntityAttrSet implements Operation {
        private final String driver;
        private final String entity;
        private final String key;
        private final String ldap;
        private final String type;
        private final List<String> display;
        private final Map<String, String> flags;

        public EntityAttrSet(String driver, String entity, String key, String ldap, String type, String display, Map<String, String> flags) {
            this.driver = driver;
            this.entity = entity;
            this.key = key;
            this.ldap = ldap;
            this.type = type;
            this.display = list(display);
            this.flags = flags;
        }

        @Override
        public String name() {
            return "entity.attr.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject e = findEntity(d.provisioning, entity);
            String before = snapshot(e);
            Document doc = entityDoc(e);
            Element a = attributeElement(doc, key);
            if (a == null) {
                throw new Operation.Refusal("entity '" + entity + "' has no attribute '" + key + "'");
            }
            for (Map.Entry<String, String> f : flags.entrySet()) {
                if (!ATTR_FLAGS.contains(f.getKey())) {
                    throw new Operation.Refusal("unknown attribute flag '" + f.getKey() + "' (" + String.join(", ", ATTR_FLAGS) + ")");
                }
                a.setAttribute(f.getKey(), f.getValue());
            }
            if (ldap != null && !ldap.isBlank()) {
                text(doc, a, "ldap-name", ldap);
            }
            if (type != null && !type.isBlank()) {
                text(doc, a, "type", type);
            }
            if (!display.isEmpty()) {
                setDisplays(doc, a, display);
            }
            storeEntity(tx, d, e, doc, before);
            if (snapshot(e).equals(before)) {
                throw new Operation.Refusal("nothing to change on attribute '" + key + "' of entity '" + entity + "'");
            }
            tx.note("changed attribute " + key + " of entity " + entity);
        }
    }

    public static final class EntityAttrRemove implements Operation {
        private final String driver;
        private final String entity;
        private final String key;

        public EntityAttrRemove(String driver, String entity, String key) {
            this.driver = driver;
            this.entity = entity;
            this.key = key;
        }

        @Override
        public String name() {
            return "entity.attr.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Driver d = driverFor(ds, driver);
            AppObject e = findEntity(d.provisioning, entity);
            String before = snapshot(e);
            Document doc = entityDoc(e);
            Element a = attributeElement(doc, key);
            if (a == null) {
                throw new Operation.Refusal("entity '" + entity + "' has no attribute '" + key + "'");
            }
            a.getParentNode().removeChild(a);
            storeEntity(tx, d, e, doc, before);
            tx.note("removed attribute " + key + " from entity " + entity);
        }
    }

    /** Sets (creating or replacing) a single-text child element of {@code parent}, keeping Designer's element order where it exists. */
    private static void text(Document doc, Element parent, String name, String value) {
        Element c = null;
        for (Element x : Xds.childrenByName(parent, name)) {
            c = x;
            break;
        }
        if (c == null) {
            c = doc.createElementNS(null, name);
            parent.appendChild(c);
        }
        while (c.getFirstChild() != null) {
            c.removeChild(c.getFirstChild());
        }
        c.appendChild(doc.createTextNode(value));
    }
}
