package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
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
        DRIVERSET_GCVS, DRIVERSET_LINKAGE,
        FORM_ADDED, FORM_REMOVED, FORM_CHANGED, PRD_ADDED, PRD_REMOVED, PRD_CHANGED;

        /** Provisioning objects (JSON forms, PRDs) are read by the Identity Applications, not the engine: no driver restart. */
        public boolean isProvisioning() {
            return this == FORM_ADDED || this == FORM_REMOVED || this == FORM_CHANGED
                || this == PRD_ADDED || this == PRD_REMOVED || this == PRD_CHANGED;
        }
    }

    /**
     * One difference between {@code from} and {@code to}. {@code driver} is null for a
     * Library artifact or the driver set itself. {@code path} is an artifact path,
     * {@code drivers/<D>} for a driver-level change, or {@code driverset}. {@code what}
     * is the setting name, config key, or policy-set key (null when not applicable).
     * {@code detail} is a text diff or a before/after listing; it may be null.
     */
    public static final class Change {
        public final Kind kind;
        public final String driver;
        public final String path;
        public final String what;
        public final String summary;
        public final String detail;

        Change(Kind kind, String driver, String path, String what, String summary, String detail) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.driver = driver;
            this.path = Objects.requireNonNull(path, "path");
            this.what = what;
            this.summary = Objects.requireNonNull(summary, "summary");
            this.detail = detail;
        }

        @Override
        public String toString() {
            return summary;
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

    /** Computes the diff of {@code from} (current state) against {@code to} (desired state). */
    public static ModelDiff of(DriverSet from, DriverSet to) {
        ModelDiff d = new ModelDiff(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
        d.compute();
        return d;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public List<Change> changes() {
        return Collections.unmodifiableList(changes);
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
            if (c.driver != null && c.kind != Kind.DRIVER_ADDED && c.kind != Kind.DRIVER_REMOVED && !c.kind.isProvisioning()) {
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
        for (String name : driverNames) {
            List<Change> forDriver = new ArrayList<>();
            for (Change c : changes) {
                if (name.equals(c.driver)) {
                    forDriver.add(c);
                }
            }
            sb.append(name).append(":\n");
            appendChanges(sb, forDriver);
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
        List<String> lines = new ArrayList<>();
        for (String k : com.pointblue.dirxml.dev.deploy.VaultMapping.STAMP_KEYS) {
            String x = a.meta.get(k);
            String y = b.meta.get(k);
            if (!Objects.equals(x, y)) {
                lines.add("- " + k + ": " + display(x));
                lines.add("+ " + k + ": " + display(y));
            }
        }
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

    /** PRDs compare their three XML parts canonically plus their plain properties; stamps otherwise. */
    private void prdMaybeChanged(Driver d, Prd x, Prd y) {
        List<String> lines = new ArrayList<>();
        String[][] parts = {
            {"definition", serializeOrNull(x.definition), serializeOrNull(y.definition)},
            {"request", serializeOrNull(x.request), serializeOrNull(y.request)},
            {"process", serializeOrNull(x.process), serializeOrNull(y.process)},
        };
        for (String[] p : parts) {
            if (!Objects.equals(p[1], p[2])) {
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
                lines.add("- " + k + ": " + display(vx == null ? null : String.join(" | ", vx)));
                lines.add("+ " + k + ": " + display(vy == null ? null : String.join(" | ", vy)));
            }
        }
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.PRD_CHANGED, d.name, prdPath(d, y), null,
                "~ changed PRD " + prdPath(d, y), String.join("\n", lines)));
            return;
        }
        List<String> stamps = stampLines(x.meta, y.meta);
        if (!stamps.isEmpty()) {
            changes.add(new Change(Kind.PRD_CHANGED, d.name, prdPath(d, y), "package-stamps",
                "~ package stamps PRD " + prdPath(d, y), String.join("\n", stamps)));
        }
    }

    private static List<String> stampLines(Map<String, String> ma, Map<String, String> mb) {
        List<String> lines = new ArrayList<>();
        for (String k : com.pointblue.dirxml.dev.deploy.VaultMapping.STAMP_KEYS) {
            String x = ma.get(k);
            String y = mb.get(k);
            if (!Objects.equals(x, y)) {
                lines.add("- " + k + ": " + display(x));
                lines.add("+ " + k + ": " + display(y));
            }
        }
        return lines;
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
        List<String> lines = new ArrayList<>();
        for (String k : List.of("dirxml-pkgguid", "dirxml-pkgextensions")) {
            String x = a.meta.get(k);
            String y = b.meta.get(k);
            if (!Objects.equals(x, y)) {
                lines.add("- " + k + ": " + (x == null ? "(none)" : k.equals("dirxml-pkgextensions") ? "(" + x.length() + " chars)" : x));
                lines.add("+ " + k + ": " + (y == null ? "(none)" : k.equals("dirxml-pkgextensions") ? "(" + y.length() + " chars)" : y));
            }
        }
        if (!lines.isEmpty()) {
            changes.add(new Change(Kind.DRIVER_STAMPS, a.name, "drivers/" + a.name, "package-stamps",
                "~ package stamps of driver " + a.name, String.join("\n", lines)));
        }
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
