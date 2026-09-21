package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.PackageStamps;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A pure, structured diff of two driver-set models — {@code from} (the current state:
 * a vault, or tree A) against {@code to} (the desired state: the tree, or tree B). See
 * {@code docs/vault-deploy.md}, "The diff": this is the engine behind {@code vault.diff}
 * and the offline {@code idm tree.diff a/ b/}.
 *
 * <p>Compares, by artifact path, the whole set (added / removed / changed / kind
 * changed), each driver's settings, config blobs and policy-set linkage, drivers only
 * on one side, and the driver set's GCVs and GCV linkage. Meta is never compared.
 * Content comparisons are canonical ({@link CanonicalXml#serialize}), so formatting
 * differences that don't change meaning never show up as a change.
 */
public final class ModelDiff {

    /** The kind of one {@link Change}. */
    public enum Kind {
        ARTIFACT_ADDED, ARTIFACT_REMOVED, ARTIFACT_CHANGED, ARTIFACT_KIND_CHANGED,
        DRIVER_ADDED, DRIVER_REMOVED, DRIVER_SETTING, DRIVER_CONFIG, DRIVER_LINKAGE, DRIVER_STAMPS,
        DRIVER_ICON,
        DRIVERSET_GCVS, DRIVERSET_LINKAGE,
        FORM_ADDED, FORM_REMOVED, FORM_CHANGED, PRD_ADDED, PRD_REMOVED, PRD_CHANGED,
        OBJECT_ADDED, OBJECT_REMOVED, OBJECT_CHANGED,
        ENTITLEMENT_ADDED, ENTITLEMENT_REMOVED, ENTITLEMENT_CHANGED;

        /** Provisioning objects (JSON forms, PRDs, the rest of AppConfig) are read by the Identity Applications, not the engine: no driver restart. */
        public boolean isProvisioning() {
            return this == FORM_ADDED || this == FORM_REMOVED || this == FORM_CHANGED
                || this == PRD_ADDED || this == PRD_REMOVED || this == PRD_CHANGED
                || isObject();
        }

        /** The generic AppConfig objects ({@code AppObject}: entities, roles, resources, reports, nav items, containers …). */
        public boolean isObject() {
            return this == OBJECT_ADDED || this == OBJECT_REMOVED || this == OBJECT_CHANGED;
        }

        /** An entitlement is read from the vault by the Identity Applications at grant time, not by the engine: no driver restart either. */
        public boolean isEntitlement() {
            return this == ENTITLEMENT_ADDED || this == ENTITLEMENT_REMOVED || this == ENTITLEMENT_CHANGED;
        }

        /**
         * The driver's icon — the vault's {@code DirXML-DriverImage}, a Designer project's
         * {@code icon} heavy data, a tree's {@code icon.<ext>}. {@link Plan} deploys it as one
         * attribute write that never restarts the driver (the engine does not read it; iManager
         * and Designer do). Reported as added or changed only: a {@code to} side without an icon
         * leaves the other side's alone (see {@code docs/designer-new-project.md} §7.2e).
         */
        public boolean isIcon() {
            return this == DRIVER_ICON;
        }

        /** None of these kinds needs the owning driver restarted. */
        public boolean noRestart() {
            return isProvisioning() || isEntitlement() || isIcon();
        }
    }

    /**
     * One difference between {@code from} and {@code to}. {@code driver} is null for a
     * Library artifact or the driver set itself. {@code path} is an artifact path,
     * {@code drivers/<D>} for a driver-level change, or {@code driverset}. {@code what}
     * is the setting name, config key, or policy-set key (null when not applicable).
     * {@code detail} is a text diff or a before/after listing; it may be null.
     *
     * <p>{@code parts} is non-null only for {@link Kind#PRD_CHANGED}: the names of the parts of the PRD
     * that actually differ — {@code "definition"}, {@code "request"}, {@code "process"}, a changed
     * property's key ({@link VaultMapping#PRD_PROPERTY_ATTRS}), and {@code "stamps"} when the package
     * stamps differ — so {@link Plan} can write only those attributes instead of every one a PRD has.
     * Null for every other kind (nothing else needs the trim: a form has a single content attribute,
     * an artifact's content is one attribute, and "package-stamps" changes are already narrow via
     * {@code what}).
     */
    public static final class Change {
        public final Kind kind;
        public final String driver;
        public final String path;
        public final String what;
        public final String summary;
        public final String detail;
        public final Set<String> parts;

        Change(Kind kind, String driver, String path, String what, String summary, String detail) {
            this(kind, driver, path, what, summary, detail, null);
        }

        Change(Kind kind, String driver, String path, String what, String summary, String detail, Set<String> parts) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.driver = driver;
            this.path = Objects.requireNonNull(path, "path");
            this.what = what;
            this.summary = Objects.requireNonNull(summary, "summary");
            this.detail = detail;
            this.parts = parts == null ? null : Collections.unmodifiableSet(parts);
        }

        @Override
        public String toString() {
            return summary;
        }
    }

    /**
     * A driver + object-kind pair where the tree has <b>zero</b> objects of that kind while the
     * vault ({@code from}) has one or more — the mass-deletion trap from the 2026-09-16 incident
     * (docs/vault-deploy.md, "Deploy never empties a kind"): a tree imported before an object kind
     * existed (or that lost every instance of one some other way) would otherwise diff as N
     * individual removals and delete every one of them. {@code kind} is one of {@code "entitlements"},
     * {@code "forms"}, {@code "prds"} — the same words {@code --delete-all} takes. Individual removals
     * (the tree still has &ge;1 of the kind) are not reported here; they stay ordinary REMOVED changes.
     */
    public static final class EmptyKind {
        public final String driver;
        public final String kind;
        public final int count;

        EmptyKind(String driver, String kind, int count) {
            this.driver = driver;
            this.kind = kind;
            this.count = count;
        }

        /** {@code driver 'X': the tree has no <kind> but the vault has N — …}. */
        public String note() {
            return "driver '" + driver + "': the tree has no " + kind + " but the vault has " + count
                + " — an older tree? re-import (import-live) to adopt them, or pass --delete-all " + kind + " to delete them";
        }
    }

    private static final List<String> CONFIG_KEYS = Arrays.asList(
        Driver.SHIM_CONFIG_INFO, Driver.CONFIG_VALUES, Driver.DRIVER_FILTER, Driver.ENGINE_CONTROL_VALUES);

    private final DriverSet from;
    private final DriverSet to;
    private final List<Change> changes = new ArrayList<>();

    private ModelDiff(DriverSet from, DriverSet to) {
        this.from = from;
        this.to = to;
    }

    /**
     * Computes the diff of {@code from} (current state) against {@code to} (desired state).
     * Driver icons are compared like everything else — the vault holds one
     * ({@code DirXML-DriverImage}), as do a tree, a Designer project and an export — with one
     * rule: a {@code to} side without an icon has no opinion, so an icon is reported added or
     * changed, never removed (a tree written before icons were carried must not strip every
     * driver's icon on its next deploy). See {@link Kind#DRIVER_ICON}.
     */
    public static ModelDiff of(DriverSet from, DriverSet to) {
        ModelDiff d = new ModelDiff(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
        d.compute();
        return d;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /** The current-state side. */
    public DriverSet from() {
        return from;
    }

    /** The desired-state side. */
    public DriverSet to() {
        return to;
    }

    /** True when every change is a driver icon — nothing the engine would notice. */
    public boolean isEmptyButForIcons() {
        for (Change c : changes) {
            if (!c.kind.isIcon()) {
                return false;
            }
        }
        return true;
    }

    public List<Change> changes() {
        return Collections.unmodifiableList(changes);
    }

    /**
     * The {@link EmptyKind} guards this diff trips: for each driver + kind ({@code "entitlements"},
     * {@code "forms"}, {@code "prds"}) with one or more REMOVED changes, true when the tree side
     * ({@code to}) has zero objects of that kind for the driver. Ordered by driver, then kind, for a
     * stable report. {@link Plan} uses the same list to decide which deletes to hold back.
     */
    public List<EmptyKind> emptyKinds() {
        Map<String, Map<String, Integer>> counts = new TreeMap<>();   // driver -> kind -> count
        for (Change c : changes) {
            String kind = removalKind(c);
            if (kind != null) {
                counts.computeIfAbsent(c.driver, k -> new TreeMap<>()).merge(kind, 1, Integer::sum);
            }
        }
        List<EmptyKind> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> byDriver : counts.entrySet()) {
            String driver = byDriver.getKey();
            for (Map.Entry<String, Integer> byKind : byDriver.getValue().entrySet()) {
                String kind = byKind.getKey();
                if (treeHasNoneOfKind(driver, kind)) {
                    out.add(new EmptyKind(driver, kind, byKind.getValue()));
                }
            }
        }
        return out;
    }

    /**
     * {@code "entitlements"}, {@code "forms"}, {@code "prds"} for the three REMOVED kinds the guard covers;
     * null otherwise. Package-private: {@link Plan} reuses it to recognize the same changes.
     */
    static String removalKind(Kind k) {
        if (k == Kind.ENTITLEMENT_REMOVED) {
            return "entitlements";
        }
        if (k == Kind.FORM_REMOVED) {
            return "forms";
        }
        if (k == Kind.PRD_REMOVED) {
            return "prds";
        }
        return null;
    }

    /**
     * The guard kind of a change: as {@link #removalKind(Kind)}, plus an {@link Kind#OBJECT_REMOVED}
     * change's own object kind in the plural ({@code roles}, {@code entities}, {@code nav-items} …,
     * carried in {@link Change#what}).
     */
    static String removalKind(Change c) {
        if (c.kind == Kind.OBJECT_REMOVED) {
            AppObject.Kind k = c.what == null ? null : AppObject.Kind.byKey(c.what);
            return k == null ? "objects" : k.plural();
        }
        return removalKind(c.kind);
    }

    private boolean treeHasNoneOfKind(String driver, String kind) {
        Driver d = to.driver(driver);
        if (d == null) {
            return true;   // the driver itself only exists on the vault side (DRIVER_REMOVED handles that)
        }
        switch (kind) {
            case "entitlements":
                return d.entitlements.isEmpty();
            case "forms":
                return d.provisioning == null || d.provisioning.forms.isEmpty();
            case "prds":
                return d.provisioning == null || d.provisioning.prds.isEmpty();
            default: {
                AppObject.Kind ok = AppObject.Kind.byPlural(kind);
                if (ok == null || d.provisioning == null) {
                    return ok != null;
                }
                for (AppObject o : d.provisioning.objects) {
                    if (o.kind() == ok) {
                        return false;
                    }
                }
                return true;
            }
        }
    }

    /**
     * Every driver that must restart: drivers with any change of their own (settings,
     * config, linkage, or one of their artifacts), plus every driver (in either model)
     * whose links reference a changed/added/removed Library artifact, plus every driver
     * in {@code to} when the driver set's GCVs or GCV linkage changed.
     */
    public List<String> affectedDrivers() {
        Set<String> affected = new TreeSet<>();
        boolean allAffected = false;
        for (Change c : changes) {
            if (c.kind == Kind.DRIVERSET_GCVS || c.kind == Kind.DRIVERSET_LINKAGE) {
                allAffected = true;
                break;
            }
        }
        if (allAffected) {
            for (Driver d : to.drivers) {
                affected.add(d.name);
            }
            return new ArrayList<>(affected);
        }
        for (Change c : changes) {
            if (c.driver != null && c.kind != Kind.DRIVER_ADDED && c.kind != Kind.DRIVER_REMOVED && !c.kind.noRestart()) {
                affected.add(c.driver);
            }
        }
        for (Change c : changes) {
            if (c.driver == null && c.path.startsWith("library/") && isArtifactKind(c.kind)) {
                addLinkingDrivers(from, c.path, affected);
                addLinkingDrivers(to, c.path, affected);
            }
        }
        return new ArrayList<>(affected);
    }

    private static boolean isArtifactKind(Kind k) {
        return k == Kind.ARTIFACT_ADDED || k == Kind.ARTIFACT_REMOVED
            || k == Kind.ARTIFACT_CHANGED || k == Kind.ARTIFACT_KIND_CHANGED;
    }

    private static void addLinkingDrivers(DriverSet ds, String path, Set<String> out) {
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                if (l.ref.equals(path)) {
                    out.add(d.name);
                    break;
                }
            }
        }
    }

    /** Grouped by driver (alphabetical), then Library, then driver set; ends with a summary line. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        Set<String> driverNames = new TreeSet<>();
        for (Change c : changes) {
            if (c.driver != null) {
                driverNames.add(c.driver);
            }
        }
        List<EmptyKind> emptyKinds = emptyKinds();
        for (String name : driverNames) {
            List<Change> forDriver = new ArrayList<>();
            for (Change c : changes) {
                if (name.equals(c.driver)) {
                    forDriver.add(c);
                }
            }
            sb.append(name).append(":\n");
            appendChanges(sb, forDriver);
            for (EmptyKind ek : emptyKinds) {
                if (name.equals(ek.driver)) {
                    sb.append("  note: ").append(ek.note()).append('\n');
                }
            }
        }
        List<Change> libraryChanges = new ArrayList<>();
        for (Change c : changes) {
            if (c.driver == null && c.path.startsWith("library/")) {
                libraryChanges.add(c);
            }
        }
        if (!libraryChanges.isEmpty()) {
            sb.append("Library:\n");
            appendChanges(sb, libraryChanges);
        }
        List<Change> dsChanges = new ArrayList<>();
        for (Change c : changes) {
            if (c.driver == null && "driverset".equals(c.path)) {
                dsChanges.add(c);
            }
        }
        if (!dsChanges.isEmpty()) {
            sb.append("driver set:\n");
            appendChanges(sb, dsChanges);
        }
        if (changes.isEmpty()) {
            sb.append("no differences\n");
        } else {
            List<String> affected = affectedDrivers();
            sb.append(changes.size()).append(" change(s), ").append(affected.size())
                .append(" driver(s) affected: ").append(String.join(", ", affected)).append('\n');
        }
        return sb.toString();
    }

    private static void appendChanges(StringBuilder sb, List<Change> list) {
        for (Change c : list) {
            sb.append("  ").append(c.summary).append('\n');
            if (c.detail != null && !c.detail.isBlank()) {
                for (String line : c.detail.split("\n", -1)) {
                    sb.append("      ").append(line).append('\n');
                }
            }
        }
    }

    /** {@code {"empty":…, "changes":[{kind,driver,path,what,summary,detail}…], "affectedDrivers":[…]}}. */
    public String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"empty\":").append(isEmpty());
        sb.append(",\"changes\":[");
        boolean first = true;
        for (Change c : changes) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"kind\":").append(q(c.kind.name()))
                .append(",\"driver\":").append(c.driver == null ? "null" : q(c.driver))
                .append(",\"path\":").append(q(c.path))
                .append(",\"what\":").append(c.what == null ? "null" : q(c.what))
                .append(",\"summary\":").append(q(c.summary))
                .append(",\"detail\":").append(c.detail == null ? "null" : q(c.detail))
                .append('}');
        }
        sb.append(']');
        sb.append(",\"affectedDrivers\":[");
        List<String> affected = affectedDrivers();
        for (int i = 0; i < affected.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(affected.get(i)));
        }
        sb.append(']');
        sb.append(",\"emptyKinds\":[");
        List<EmptyKind> emptyKinds = emptyKinds();
        for (int i = 0; i < emptyKinds.size(); i++) {
            EmptyKind ek = emptyKinds.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"driver\":").append(q(ek.driver))
                .append(",\"kind\":").append(q(ek.kind))
                .append(",\"count\":").append(ek.count)
                .append(",\"note\":").append(q(ek.note()))
                .append('}');
        }
        sb.append(']');
        return sb.append('}').toString();
    }

    // ------------------------------------------------------------------
    // Computation
    // ------------------------------------------------------------------

    private void compute() {
        Set<String> fromNames = names(from);
        Set<String> toNames = names(to);
        Set<String> onlyFrom = new TreeSet<>(fromNames);
        onlyFrom.removeAll(toNames);
        Set<String> onlyTo = new TreeSet<>(toNames);
        onlyTo.removeAll(fromNames);
        Set<String> both = new TreeSet<>(fromNames);
        both.retainAll(toNames);

        for (String name : onlyTo) {
            driverAdded(to.driver(name));
        }
        for (String name : onlyFrom) {
            driverRemoved(from.driver(name));
        }

        diffArtifacts(onlyFrom, onlyTo);

        for (String name : both) {
            Driver a = from.driver(name);
            Driver b = to.driver(name);
            diffDriverSettings(a, b);
            diffDriverConfig(a, b);
            diffDriverLinkage(a, b);
            diffProvisioning(a, b);
            diffEntitlements(a, b);
        }

        diffDriverSetGcvs();
        diffDriverSetLinkage();
    }

    private static Set<String> names(DriverSet ds) {
        Set<String> out = new LinkedHashSet<>();
        for (Driver d : ds.drivers) {
            out.add(d.name);
        }
        return out;
    }

    // ---- artifacts ----

    private void diffArtifacts(Set<String> onlyFromDrivers, Set<String> onlyToDrivers) {
        Map<String, Artifact> fromIdx = from.index();
        Map<String, Artifact> toIdx = to.index();
        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(fromIdx.keySet());
        allPaths.addAll(toIdx.keySet());
        for (String path : allPaths) {
            Artifact a = fromIdx.get(path);
            Artifact b = toIdx.get(path);
            String owner = a != null ? a.driver : b.driver;
            // an artifact of a wholly new/removed driver is covered by that DRIVER_ADDED/REMOVED change
            if (owner != null && (onlyFromDrivers.contains(owner) || onlyToDrivers.contains(owner))) {
                continue;
            }
            if (a == null) {
                changes.add(new Change(Kind.ARTIFACT_ADDED, b.driver, b.path(), null,
                    "+ added " + describeKind(b) + " " + b.path(), null));
            } else if (b == null) {
                changes.add(new Change(Kind.ARTIFACT_REMOVED, a.driver, a.path(), null,
                    "- removed " + describeKind(a) + " " + a.path(), null));
            } else {
                artifactMaybeChanged(a, b);
            }
        }
    }

    private void artifactMaybeChanged(Artifact a, Artifact b) {
        boolean aPolicy = a instanceof Policy;
        boolean bPolicy = b instanceof Policy;
        if (aPolicy != bPolicy) {
            kindChanged(a, b);
            return;
        }
        if (aPolicy) {
            Policy pa = (Policy) a;
            Policy pb = (Policy) b;
            if (pa.policyKind() != pb.policyKind()) {
                kindChanged(a, b);
                return;
            }
            String oldXml = serializeOrNull(pa.content);
            String newXml = serializeOrNull(pb.content);
            if (!Objects.equals(oldXml, newXml)) {
                changes.add(new Change(Kind.ARTIFACT_CHANGED, a.driver, a.path(), null,
                    "~ changed " + describeKind(a) + " " + a.path(), textDiff(oldXml, newXml)));
            } else {
                stampsMaybeChanged(a, b);
            }
            return;
        }
        Resource ra = (Resource) a;
        Resource rb = (Resource) b;
        boolean ctChanged = !Objects.equals(ra.contentType, rb.contentType);
        String oldRepr = ra.isText() ? ra.text : serializeOrNull(ra.content);
        String newRepr = rb.isText() ? rb.text : serializeOrNull(rb.content);
        boolean contentChanged = !Objects.equals(oldRepr, newRepr);
        if (!ctChanged && !contentChanged) {
            stampsMaybeChanged(a, b);
            return;
        }
        List<String> lines = new ArrayList<>();
        if (ctChanged) {
            lines.add("- content-type: " + ra.contentType);
            lines.add("+ content-type: " + rb.contentType);
        }
        if (contentChanged) {
            lines.addAll(lcsDiff(lines(oldRepr), lines(newRepr)));
        }
        changes.add(new Change(Kind.ARTIFACT_CHANGED, a.driver, a.path(), null,
            "~ changed " + describeKind(a) + " " + a.path(), String.join("\n", lines)));
    }

    /** Package stamps (GUID, association id, installed checksum, linkage record) differ while the content is the same. */
    private void stampsMaybeChanged(Artifact a, Artifact b) {
        List<String> lines = stampLines(a.meta, b.meta);
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.ARTIFACT_CHANGED, a.driver, a.path(), "package-stamps",
                "~ package stamps " + describeKind(a) + " " + a.path(), String.join("\n", lines)));
        }
    }

    // ---- provisioning (JSON forms + PRDs) ----

    /** Paths: {@code drivers/<d>/provisioning/forms/<kind>/<name>} and {@code drivers/<d>/provisioning/prds/<name>}. */
    public static String formPath(Driver d, Form f) {
        return "drivers/" + d.name + "/provisioning/forms/" + f.kind.dir + "/" + f.name;
    }

    public static String prdPath(Driver d, Prd p) {
        return "drivers/" + d.name + "/provisioning/prds/" + p.name;
    }

    private void diffProvisioning(Driver a, Driver b) {
        Provisioning pa = a.provisioning;
        Provisioning pb = b.provisioning;
        if (pa == null && pb == null) {
            return;
        }
        Map<String, Form> fromForms = new TreeMap<>();
        Map<String, Form> toForms = new TreeMap<>();
        if (pa != null) {
            for (Form f : pa.forms) {
                fromForms.put(f.kind.dir + "/" + f.name, f);
            }
        }
        if (pb != null) {
            for (Form f : pb.forms) {
                toForms.put(f.kind.dir + "/" + f.name, f);
            }
        }
        Set<String> keys = new TreeSet<>(fromForms.keySet());
        keys.addAll(toForms.keySet());
        for (String k : keys) {
            Form x = fromForms.get(k);
            Form y = toForms.get(k);
            if (x == null) {
                changes.add(new Change(Kind.FORM_ADDED, b.name, formPath(b, y), null, "+ added form " + formPath(b, y), null));
            } else if (y == null) {
                changes.add(new Change(Kind.FORM_REMOVED, a.name, formPath(a, x), null, "- removed form " + formPath(a, x), null));
            } else {
                formMaybeChanged(a, x, y);
            }
        }
        Map<String, Prd> fromPrds = new TreeMap<>();
        Map<String, Prd> toPrds = new TreeMap<>();
        if (pa != null) {
            for (Prd p : pa.prds) {
                fromPrds.put(p.name, p);
            }
        }
        if (pb != null) {
            for (Prd p : pb.prds) {
                toPrds.put(p.name, p);
            }
        }
        Set<String> names = new TreeSet<>(fromPrds.keySet());
        names.addAll(toPrds.keySet());
        for (String n : names) {
            Prd x = fromPrds.get(n);
            Prd y = toPrds.get(n);
            if (x == null) {
                changes.add(new Change(Kind.PRD_ADDED, b.name, prdPath(b, y), null, "+ added PRD " + prdPath(b, y), null));
            } else if (y == null) {
                changes.add(new Change(Kind.PRD_REMOVED, a.name, prdPath(a, x), null, "- removed PRD " + prdPath(a, x), null));
            } else {
                prdMaybeChanged(a, x, y);
            }
        }
        Map<String, AppObject> fromObjects = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, AppObject> toObjects = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (pa != null) {
            for (AppObject o : pa.objects) {
                fromObjects.put(o.path(), o);
            }
        }
        if (pb != null) {
            for (AppObject o : pb.objects) {
                toObjects.put(o.path(), o);
            }
        }
        Set<String> paths = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        paths.addAll(fromObjects.keySet());
        paths.addAll(toObjects.keySet());
        for (String path : paths) {
            AppObject x = fromObjects.get(path);
            AppObject y = toObjects.get(path);
            if (x == null) {
                changes.add(new Change(Kind.OBJECT_ADDED, b.name, objectPath(b, y), y.kind().key,
                    "+ added " + y.kind().key + " " + objectPath(b, y), null));
            } else if (y == null) {
                changes.add(new Change(Kind.OBJECT_REMOVED, a.name, objectPath(a, x), x.kind().key,
                    "- removed " + x.kind().key + " " + objectPath(a, x), null));
            } else {
                objectMaybeChanged(a, x, y);
            }
        }
    }

    /** Path: {@code drivers/<d>/provisioning/objects/<Container>/…/<name>}. */
    public static String objectPath(Driver d, AppObject o) {
        return "drivers/" + d.name + "/provisioning/objects/" + o.path();
    }

    /**
     * AppConfig objects compare their classes and every design attribute — the operational ones
     * ({@code AppConfigPolicy.isOperational}) never count, XML values canonically, multi-values as
     * sorted lists. {@link Change#parts} names what differs: each attribute, {@code "classes"}, and
     * {@code "stamps"}; a stamps-only difference has exactly {@code {"stamps"}}, so {@link Plan} writes
     * only the changed attributes, as it does for PRDs.
     */
    private void objectMaybeChanged(Driver d, AppObject x, AppObject y) {
        List<String> lines = new ArrayList<>();
        Set<String> changedParts = new LinkedHashSet<>();
        List<String> cx = sortedClasses(x);
        List<String> cy = sortedClasses(y);
        if (!cx.equals(cy)) {
            changedParts.add("classes");
            lines.add("- objectClass: " + String.join(", ", cx));
            lines.add("+ objectClass: " + String.join(", ", cy));
        }
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(x.attrs.keySet());
        names.addAll(y.attrs.keySet());
        for (String n : names) {
            if (AppConfigPolicy.isOperational(n) || AppConfigPolicy.isAbsent(x.meta, n) || AppConfigPolicy.isAbsent(y.meta, n)) {
                continue;   // operational, or one side (a Designer project) cannot carry it: no opinion
            }
            List<String> vx = normalizedValues(n, x.attrs.get(n));
            List<String> vy = normalizedValues(n, y.attrs.get(n));
            if (!Objects.equals(vx, vy)) {
                changedParts.add(AppConfigPolicy.canonicalAttribute(n));
                if (AppConfigPolicy.isXmlAttribute(n) && vx.size() <= 1 && vy.size() <= 1) {
                    lines.add("## " + n);
                    lines.add(textDiff(vx.isEmpty() ? "" : vx.get(0), vy.isEmpty() ? "" : vy.get(0)));
                } else {
                    lines.add("- " + n + ": " + display(vx.isEmpty() ? null : String.join(" | ", vx)));
                    lines.add("+ " + n + ": " + display(vy.isEmpty() ? null : String.join(" | ", vy)));
                }
            }
        }
        List<String> stamps = stampLines(x.meta, y.meta);
        if (!stamps.isEmpty() && lines.isEmpty() && vaultHoldsDerivedChecksum(x, y)) {
            // a customized packaged object: the vault's checksum is the one the deploy derives from the
            // content the tree holds, while the tree still records the package's — the same state, not drift
            stamps = Collections.emptyList();
        }
        if (!stamps.isEmpty()) {
            changedParts.add("stamps");
        }
        String kind = y.kind().key;
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.OBJECT_CHANGED, d.name, objectPath(d, y), kind,
                "~ changed " + kind + " " + objectPath(d, y), String.join("\n", lines), changedParts));
            return;
        }
        if (!stamps.isEmpty()) {
            changes.add(new Change(Kind.OBJECT_CHANGED, d.name, objectPath(d, y), kind,
                "~ package stamps " + kind + " " + objectPath(d, y), String.join("\n", stamps), changedParts));
        }
    }

    /** True when the only stamp that differs is the checksum and the vault's is {@code customizedChecksum} of the tree's content. */
    private static boolean vaultHoldsDerivedChecksum(AppObject vault, AppObject tree) {
        if (!PackageStamps.sameGuid(PackageStamps.guid(vault.meta), PackageStamps.guid(tree.meta))
            || !Objects.equals(PackageStamps.assocId(vault.meta), PackageStamps.assocId(tree.meta))) {
            return false;
        }
        String live = PackageStamps.checksum(vault.meta);
        return live != null && live.equals(VaultMapping.customizedChecksum(VaultMapping.objectContentBytes(tree)));
    }

    private static List<String> sortedClasses(AppObject o) {
        List<String> c = new ArrayList<>(o.classes);
        c.sort(String.CASE_INSENSITIVE_ORDER);
        return c;
    }

    /** Values as compared: XML canonicalized (a hand-edited file may not be canonical), multi-values sorted. */
    private static List<String> normalizedValues(String attr, List<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            String xml = AppConfigPolicy.isXmlAttribute(attr) ? com.pointblue.dirxml.dev.ascode.DsObjectXml.asXml(v) : null;
            out.add(xml != null ? xml : AppConfigPolicy.isLocalizedAttribute(attr) ? AppConfigPolicy.sortedLocalized(v) : v);
        }
        if (out.size() > 1) {
            out.sort(null);
        }
        return out;
    }

    /** Forms compare as parsed JSON (the tree is pretty, the vault compact); stamps when the document is the same. */
    private void formMaybeChanged(Driver d, Form x, Form y) {
        Object ox;
        Object oy;
        try {
            ox = Json.parse(x.json);
            oy = Json.parse(y.json);
        } catch (RuntimeException e) {
            ox = x.json;
            oy = y.json;
        }
        if (!Objects.equals(ox, oy)) {
            String oldText = ox instanceof String ? x.json : Json.pretty(ox);
            String newText = oy instanceof String ? y.json : Json.pretty(oy);
            changes.add(new Change(Kind.FORM_CHANGED, d.name, formPath(d, y), null,
                "~ changed form " + formPath(d, y), textDiff(oldText, newText)));
            return;
        }
        List<String> lines = stampLines(x.meta, y.meta);
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.FORM_CHANGED, d.name, formPath(d, y), "package-stamps",
                "~ package stamps form " + formPath(d, y), String.join("\n", lines)));
        }
    }

    /**
     * PRDs compare their three XML parts canonically plus their plain properties; stamps otherwise.
     * Records which parts actually differ in {@link Change#parts} so {@link Plan} can write only those
     * attributes (note: the definition's canonical serialization includes the process element when it is
     * a child of the definition, so a process-only edit naturally lists both "definition" and "process").
     */
    private void prdMaybeChanged(Driver d, Prd x, Prd y) {
        List<String> lines = new ArrayList<>();
        Set<String> changedParts = new LinkedHashSet<>();
        String[][] parts = {
            {"definition", serializeOrNull(x.definition), serializeOrNull(y.definition)},
            {"request", serializeOrNull(x.request), serializeOrNull(y.request)},
            {"process", serializeOrNull(x.process), serializeOrNull(y.process)},
        };
        for (String[] p : parts) {
            if (!Objects.equals(p[1], p[2])) {
                changedParts.add(p[0]);
                lines.add("## " + p[0]);
                lines.add(textDiff(p[1] == null ? "" : p[1], p[2] == null ? "" : p[2]));
            }
        }
        Set<String> props = new TreeSet<>(x.properties.keySet());
        props.addAll(y.properties.keySet());
        for (String k : props) {
            List<String> vx = x.properties.get(k);
            List<String> vy = y.properties.get(k);
            if (!Objects.equals(vx, vy)) {
                changedParts.add(k);
                lines.add("- " + k + ": " + display(vx == null ? null : String.join(" | ", vx)));
                lines.add("+ " + k + ": " + display(vy == null ? null : String.join(" | ", vy)));
            }
        }
        List<String> stamps = stampLines(x.meta, y.meta);
        if (!stamps.isEmpty()) {
            changedParts.add("stamps");
        }
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.PRD_CHANGED, d.name, prdPath(d, y), null,
                "~ changed PRD " + prdPath(d, y), String.join("\n", lines), changedParts));
            return;
        }
        if (!stamps.isEmpty()) {
            changes.add(new Change(Kind.PRD_CHANGED, d.name, prdPath(d, y), "package-stamps",
                "~ package stamps PRD " + prdPath(d, y), String.join("\n", stamps), changedParts));
        }
    }

    /**
     * The stamp differences between two metas, read through {@link PackageStamps} so either
     * vocabulary compares: the package record field by field where both sides know the field
     * (an export's id-only record agrees with the vault's full one), the association id and the
     * checksum exactly, and the linkage record only when the {@code to} side carries one — a
     * project or an export never does, and that is not a request to remove the vault's.
     */
    private static List<String> stampLines(Map<String, String> ma, Map<String, String> mb) {
        List<String> lines = new ArrayList<>();
        PackageStamps.Guid ga = PackageStamps.guid(ma);
        PackageStamps.Guid gb = PackageStamps.guid(mb);
        if (!PackageStamps.sameGuid(ga, gb)) {
            lines.add("- " + PackageStamps.GUID + ": " + display(ga == null ? null : ga.format()));
            lines.add("+ " + PackageStamps.GUID + ": " + display(gb == null ? null : gb.format()));
        }
        stampLine(lines, PackageStamps.ASSOC, PackageStamps.assocId(ma), PackageStamps.assocId(mb), false);
        stampLine(lines, PackageStamps.CHECKSUM, PackageStamps.checksum(ma), PackageStamps.checksum(mb), false);
        stampLine(lines, PackageStamps.LINKAGES, PackageStamps.linkages(ma), PackageStamps.linkages(mb), true);
        return lines;
    }

    private static void stampLine(List<String> lines, String key, String x, String y, boolean toMayLack) {
        if (toMayLack && y == null) {
            return;
        }
        if (!Objects.equals(x, y)) {
            lines.add("- " + key + ": " + display(x));
            lines.add("+ " + key + ": " + display(y));
        }
    }

    // ---- entitlements (DirXML-Entitlement objects hanging directly off a driver) ----

    /** Path: {@code drivers/<d>/entitlements/<name>}. */
    public static String entitlementPath(Driver d, Entitlement e) {
        return "drivers/" + d.name + "/entitlements/" + e.name;
    }

    private void diffEntitlements(Driver a, Driver b) {
        Map<String, Entitlement> fromEnts = new TreeMap<>();
        Map<String, Entitlement> toEnts = new TreeMap<>();
        for (Entitlement e : a.entitlements) {
            fromEnts.put(e.name, e);
        }
        for (Entitlement e : b.entitlements) {
            toEnts.put(e.name, e);
        }
        Set<String> names = new TreeSet<>(fromEnts.keySet());
        names.addAll(toEnts.keySet());
        for (String n : names) {
            Entitlement x = fromEnts.get(n);
            Entitlement y = toEnts.get(n);
            if (x == null) {
                changes.add(new Change(Kind.ENTITLEMENT_ADDED, b.name, entitlementPath(b, y), null,
                    "+ added entitlement " + entitlementPath(b, y), null));
            } else if (y == null) {
                changes.add(new Change(Kind.ENTITLEMENT_REMOVED, a.name, entitlementPath(a, x), null,
                    "- removed entitlement " + entitlementPath(a, x), null));
            } else {
                entitlementMaybeChanged(a, x, y);
            }
        }
    }

    /** Entitlements compare their whole document canonically; stamps otherwise (as PRDs do). */
    private void entitlementMaybeChanged(Driver d, Entitlement x, Entitlement y) {
        String oldXml = serializeOrNull(x.definition);
        String newXml = serializeOrNull(y.definition);
        if (!Objects.equals(oldXml, newXml)) {
            changes.add(new Change(Kind.ENTITLEMENT_CHANGED, d.name, entitlementPath(d, y), null,
                "~ changed entitlement " + entitlementPath(d, y), textDiff(oldXml, newXml)));
            return;
        }
        List<String> lines = stampLines(x.meta, y.meta);
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.ENTITLEMENT_CHANGED, d.name, entitlementPath(d, y), "package-stamps",
                "~ package stamps entitlement " + entitlementPath(d, y), String.join("\n", lines)));
        }
    }

    private void kindChanged(Artifact a, Artifact b) {
        changes.add(new Change(Kind.ARTIFACT_KIND_CHANGED, b.driver, b.path(), null,
            "~ kind changed " + a.path() + " (" + describeKind(a) + " -> " + describeKind(b) + ")", null));
    }

    private static String describeKind(Artifact a) {
        if (a instanceof Policy) {
            return "policy (" + ((Policy) a).policyKind().name().toLowerCase().replace('_', '-') + ")";
        }
        Resource r = (Resource) a;
        return "resource (" + (r.contentType == null ? "?" : r.contentType) + ")";
    }

    // ---- driver settings / config / linkage ----

    private void diffDriverSettings(Driver a, Driver b) {
        settingChange(a, "shim-class", a.shimClass, b.shimClass);
        settingChange(a, "shim-auth-server", a.shimAuthServer, b.shimAuthServer);
        settingChange(a, "shim-auth-id", a.shimAuthId, b.shimAuthId);
        diffDriverIcon(a, b);
        List<String> lines = new ArrayList<>();
        // the driver's own record (its base package) — either vocabulary, field by field; the
        // filter-extension cache only when the to side carries one (a project never does)
        PackageStamps.Guid ga = PackageStamps.guid(a.meta);
        PackageStamps.Guid gb = PackageStamps.guid(b.meta);
        if (!PackageStamps.sameGuid(ga, gb)) {
            lines.add("- " + PackageStamps.GUID + ": " + (ga == null ? "(none)" : ga.format()));
            lines.add("+ " + PackageStamps.GUID + ": " + (gb == null ? "(none)" : gb.format()));
        }
        String xe = a.meta.get(PackageStamps.EXTENSIONS);
        String ye = b.meta.get(PackageStamps.EXTENSIONS);
        if (ye != null && !Objects.equals(xe, ye)) {
            lines.add("- " + PackageStamps.EXTENSIONS + ": " + (xe == null ? "(none)" : "(" + xe.length() + " chars)"));
            lines.add("+ " + PackageStamps.EXTENSIONS + ": (" + ye.length() + " chars)");
        }
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.DRIVER_STAMPS, a.name, "drivers/" + a.name, "package-stamps",
                "~ package stamps of driver " + a.name, String.join("\n", lines)));
        }
    }

    /**
     * The driver's icon (see {@link Kind#DRIVER_ICON}). A {@code to} side without one has no
     * opinion; equal bytes are the same icon unless both sides name a format and they differ
     * (a project's {@code gif} against {@code png} renames the file). The change text
     * says the size and the format and never the bytes: an icon is a binary blob, and dumping
     * it into a diff — or a git-committed docs page — helps nobody.
     */
    private void diffDriverIcon(Driver a, Driver b) {
        if (b.icon == null) {
            return;
        }
        if (a.icon != null && Arrays.equals(a.icon, b.icon) && sameIconFormat(a.iconExtension, b.iconExtension)) {
            return;
        }
        String summary = a.icon == null ? "+ icon added (" + describeIcon(b) + ")"
            : "~ icon changed (" + describeIcon(a) + " -> " + describeIcon(b) + ")";
        changes.add(new Change(Kind.DRIVER_ICON, a.name, "drivers/" + a.name, "icon", summary, null));
    }

    /** {@code "1234 bytes, gif"} — never the bytes themselves. */
    private static boolean sameIconFormat(String a, String b) {
        return a == null || b == null || a.strip().equalsIgnoreCase(b.strip());
    }

    private static String describeIcon(Driver d) {
        return d.icon == null ? "(none)"
            : d.icon.length + " bytes, " + (d.iconExtension == null ? "?" : d.iconExtension);
    }

    private void settingChange(Driver a, String what, String oldV, String newV) {
        if (!Objects.equals(oldV, newV)) {
            changes.add(new Change(Kind.DRIVER_SETTING, a.name, "drivers/" + a.name, what,
                "~ " + what + ": " + display(oldV) + " -> " + display(newV), null));
        }
    }

    private static String display(String s) {
        return s == null ? "(none)" : s;
    }

    private void diffDriverConfig(Driver a, Driver b) {
        Set<String> keys = new LinkedHashSet<>(CONFIG_KEYS);
        keys.addAll(a.config.keySet());
        keys.addAll(b.config.keySet());
        for (String key : keys) {
            Element ea = a.config.get(key);
            Element eb = b.config.get(key);
            if (ea == null && eb == null) {
                continue;
            }
            if (ea == null) {
                changes.add(new Change(Kind.DRIVER_CONFIG, a.name, "drivers/" + a.name, key,
                    "+ added config " + key, null));
            } else if (eb == null) {
                changes.add(new Change(Kind.DRIVER_CONFIG, a.name, "drivers/" + a.name, key,
                    "- removed config " + key, null));
            } else {
                String oldXml = CanonicalXml.serialize(ea);
                String newXml = CanonicalXml.serialize(eb);
                if (!oldXml.equals(newXml)) {
                    changes.add(new Change(Kind.DRIVER_CONFIG, a.name, "drivers/" + a.name, key,
                        "~ changed config " + key, textDiff(oldXml, newXml)));
                }
            }
        }
    }

    private void diffDriverLinkage(Driver a, Driver b) {
        for (PolicySet set : PolicySet.values()) {
            List<String> oldRefs = refs(a.links(set));
            List<String> newRefs = refs(b.links(set));
            if (!oldRefs.equals(newRefs)) {
                changes.add(new Change(Kind.DRIVER_LINKAGE, a.name, "drivers/" + a.name, set.key,
                    "~ linkage changed: " + set.key,
                    textDiff(String.join("\n", oldRefs), String.join("\n", newRefs))));
            }
        }
    }

    private static List<String> refs(List<PolicyLink> links) {
        List<String> out = new ArrayList<>();
        for (PolicyLink l : links) {
            out.add(l.ref);
        }
        return out;
    }

    private void driverAdded(Driver d) {
        List<String> lines = new ArrayList<>();
        for (String key : CONFIG_KEYS) {
            if (d.config.containsKey(key)) {
                lines.add("config " + key);
            }
        }
        for (Artifact a : d.artifacts()) {
            lines.add(describeKind(a) + " " + a.path());
        }
        if (!d.links.isEmpty()) {
            lines.add(d.links.size() + " link(s)");
        }
        changes.add(new Change(Kind.DRIVER_ADDED, d.name, "drivers/" + d.name, null,
            "+ added driver " + d.name + " (" + d.artifacts().size() + " artifact(s), " + d.links.size() + " link(s))",
            lines.isEmpty() ? null : String.join("\n", lines)));
    }

    private void driverRemoved(Driver d) {
        List<String> lines = new ArrayList<>();
        for (Artifact a : d.artifacts()) {
            lines.add(describeKind(a) + " " + a.path());
        }
        changes.add(new Change(Kind.DRIVER_REMOVED, d.name, "drivers/" + d.name, null,
            "- removed driver " + d.name + " (never deleted by deploy; " + d.artifacts().size()
                + " artifact(s) in vault)",
            lines.isEmpty() ? null : String.join("\n", lines)));
    }

    // ---- driver set ----

    private void diffDriverSetGcvs() {
        String oldXml = serializeOrNull(from.configValues);
        String newXml = serializeOrNull(to.configValues);
        if (!Objects.equals(oldXml, newXml)) {
            changes.add(new Change(Kind.DRIVERSET_GCVS, null, "driverset", Driver.CONFIG_VALUES,
                "~ driver-set GCVs changed", textDiff(oldXml, newXml)));
        }
    }

    private void diffDriverSetLinkage() {
        Map<String, List<String>> oldSets = driverSetLinkage(from);
        Map<String, List<String>> newSets = driverSetLinkage(to);
        Set<String> allSets = new TreeSet<>();
        allSets.addAll(oldSets.keySet());
        allSets.addAll(newSets.keySet());
        for (String setId : allSets) {
            List<String> oldLeaves = oldSets.getOrDefault(setId, Collections.emptyList());
            List<String> newLeaves = newSets.getOrDefault(setId, Collections.emptyList());
            if (!oldLeaves.equals(newLeaves)) {
                String what = policySetName(setId);
                changes.add(new Change(Kind.DRIVERSET_LINKAGE, null, "driverset", what,
                    "~ driver-set linkage changed: " + what,
                    textDiff(String.join("\n", oldLeaves), String.join("\n", newLeaves))));
            }
        }
    }

    private static String policySetName(String setId) {
        try {
            return PolicySet.byId(Integer.parseInt(setId)).key;
        } catch (RuntimeException e) {
            return setId;
        }
    }

    /** {@code driverset.linkage.N} meta ({@code <dn>#<order>#<set>}), grouped by set id, ordered by order. */
    private static Map<String, List<String>> driverSetLinkage(DriverSet ds) {
        Map<String, TreeMap<Integer, String>> bySet = new TreeMap<>();
        for (Map.Entry<String, String> m : ds.meta.entrySet()) {
            if (!m.getKey().startsWith("driverset.linkage.")) {
                continue;
            }
            String value = m.getValue();
            int lastHash = value.lastIndexOf('#');
            if (lastHash < 0) {
                continue;
            }
            int secondHash = value.lastIndexOf('#', lastHash - 1);
            String setId = value.substring(lastHash + 1);
            String orderStr = secondHash >= 0 ? value.substring(secondHash + 1, lastHash) : "0";
            int order;
            try {
                order = Integer.parseInt(orderStr.trim());
            } catch (NumberFormatException e) {
                order = 0;
            }
            String leaf = leafOfLinkage(value);
            bySet.computeIfAbsent(setId, k -> new TreeMap<>()).put(order, leaf);
        }
        Map<String, List<String>> out = new TreeMap<>();
        for (Map.Entry<String, TreeMap<Integer, String>> e : bySet.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
        }
        return out;
    }

    /**
     * The leaf of the DN in a {@code <dn>#<order>#<set>} linkage value (LDAP or slash
     * form). Copied from {@code com.pointblue.dirxml.dev.edit.Refs} (package-private there).
     */
    private static String leafOfLinkage(String value) {
        int hash = value.indexOf('#');
        String dn = hash >= 0 ? value.substring(0, hash) : value;
        if (dn.contains("=")) {
            String first = dn.split("(?<!\\\\),")[0];
            return first.substring(first.indexOf('=') + 1).trim();
        }
        return leaf(dn);
    }

    /** The last component of a slash DN ({@code ..\Library\X} -> {@code X}) or bare name. */
    private static String leaf(String dn) {
        if (dn == null) {
            return null;
        }
        String s = dn.trim();
        int i = Math.max(s.lastIndexOf('\\'), s.lastIndexOf('/'));
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // ---- content comparison / text diff ----

    private static String serializeOrNull(Element e) {
        return e == null ? null : CanonicalXml.serialize(e);
    }

    private static List<String> lines(String s) {
        if (s == null || s.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(s.split("\n", -1));
    }

    private static String textDiff(String oldText, String newText) {
        List<String> out = lcsDiff(lines(oldText), lines(newText));
        return out.isEmpty() ? null : String.join("\n", out);
    }

    /**
     * A minimal LCS line diff — enough to read a change. Copied from
     * {@code com.pointblue.dirxml.dev.edit.ReadCli#diff} (package-private there).
     */
    private static List<String> lcsDiff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add("  " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + a.get(i++));
            } else {
                out.add("+ " + b.get(j++));
            }
        }
        while (i < n) {
            out.add("- " + a.get(i++));
        }
        while (j < m) {
            out.add("+ " + b.get(j++));
        }
        return out;
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
