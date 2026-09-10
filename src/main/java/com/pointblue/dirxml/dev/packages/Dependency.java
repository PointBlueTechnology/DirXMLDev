package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A package dependency, feature or supported-driver entry as read from a
 * package's installation directive (manifest {@code Dependencies}/{@code
 * Features}/{@code Supported-Drivers} — base64 of the same XML — and/or the
 * package directive itself; designer-package-layer.md §3.1):
 * <pre>
 *   &lt;dependency name="…" package-id="…" type="2|3|4"&gt;
 *     &lt;min-version value="…"/&gt;&lt;max-version value="…"/&gt;&lt;version value="…"/&gt;*
 *   &lt;/dependency&gt;
 *   &lt;features&gt;&lt;mandatory&gt;…&lt;/mandatory&gt;&lt;optional&gt;…&lt;/optional&gt;&lt;/features&gt;
 *   &lt;supported-drivers&gt;&lt;definition display-name driver-id id/&gt;&lt;/supported-drivers&gt;
 * </pre>
 * {@code type} is the package type required: 2 driver, 3 driver set, 4 Identity Vault.
 */
public final class Dependency {

    public String name;
    public String packageId;
    public int type = 2;
    public String minVersion;
    public String maxVersion;
    public final List<String> versions = new ArrayList<>();

    /** Explicit list without min/max = exact matches only; min/max exclude beyond a listed exception; no constraint = any version. */
    public boolean accepts(String version) {
        PackageVersion v = PackageVersion.parse(version);
        if (!versions.isEmpty()) {
            for (String ex : versions) {
                if (PackageVersion.parse(ex).compareTo(v) == 0) {
                    return true;
                }
            }
            if (minVersion == null && maxVersion == null) {
                return false;
            }
        }
        if (minVersion != null && v.compareTo(PackageVersion.parse(minVersion)) < 0) {
            return false;
        }
        return maxVersion == null || v.compareTo(PackageVersion.parse(maxVersion)) <= 0;
    }

    /** Candidates this dependency accepts, newest first. */
    public List<String> acceptable(Collection<String> candidates) {
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            if (accepts(c)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> PackageVersion.parse(b).compareTo(PackageVersion.parse(a)));
        return out;
    }

    /** Human-readable constraint, for reports ({@code package.resolve}'s "missing" list). */
    public String constraintText() {
        if (!versions.isEmpty()) {
            return "version in " + versions;
        }
        if (minVersion != null && maxVersion != null) {
            return "version " + minVersion + "–" + maxVersion;
        }
        if (minVersion != null) {
            return "version >= " + minVersion;
        }
        if (maxVersion != null) {
            return "version <= " + maxVersion;
        }
        return "any version";
    }

    /** A copy safe to mutate ({@link #mergeFrom}) without touching the catalog's stored constraint. */
    public Dependency copy() {
        Dependency d = new Dependency();
        d.name = name;
        d.packageId = packageId;
        d.type = type;
        d.minVersion = minVersion;
        d.maxVersion = maxVersion;
        d.versions.addAll(versions);
        return d;
    }

    /** Merge another constraint on the same package id into this one (intersection: tighter min/max, union of exact versions kept only where both allow). */
    public void mergeFrom(Dependency other) {
        if (other.minVersion != null
            && (minVersion == null || PackageVersion.parse(other.minVersion).compareTo(PackageVersion.parse(minVersion)) > 0)) {
            minVersion = other.minVersion;
        }
        if (other.maxVersion != null
            && (maxVersion == null || PackageVersion.parse(other.maxVersion).compareTo(PackageVersion.parse(maxVersion)) < 0)) {
            maxVersion = other.maxVersion;
        }
        if (!other.versions.isEmpty()) {
            if (versions.isEmpty()) {
                versions.addAll(other.versions);
            } else {
                versions.retainAll(other.versions);
            }
        }
    }

    public static List<Dependency> parseDependencies(Element directiveRoot) {
        List<Dependency> out = new ArrayList<>();
        if (directiveRoot == null) {
            return out;
        }
        Element deps = "dependencies".equals(directiveRoot.getNodeName())
            ? directiveRoot : PackageChecksum.child(directiveRoot, "dependencies");
        for (Element d : PackageChecksum.children(deps, "dependency")) {
            Dependency dep = new Dependency();
            dep.name = d.getAttribute("name");
            dep.packageId = d.getAttribute("package-id");
            dep.type = parseInt(d.getAttribute("type"), 2);
            Element min = PackageChecksum.child(d, "min-version");
            if (min != null) {
                dep.minVersion = min.getAttribute("value");
            }
            Element max = PackageChecksum.child(d, "max-version");
            if (max != null) {
                dep.maxVersion = max.getAttribute("value");
            }
            for (Element v : PackageChecksum.children(d, "version")) {
                dep.versions.add(v.getAttribute("value"));
            }
            out.add(dep);
        }
        return out;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    /** {@code <features><mandatory>…</mandatory><optional>…</optional></features>} — a base package's feature list. */
    public static final class Feature {
        public String packageId;
        public String displayName;
        public boolean mandatory;
        public String group;   // optional-only, may be null (ungrouped)

        public static List<Feature> parse(Element directiveRoot) {
            List<Feature> out = new ArrayList<>();
            if (directiveRoot == null) {
                return out;
            }
            Element featuresEl = "features".equals(directiveRoot.getNodeName())
                ? directiveRoot : PackageChecksum.child(directiveRoot, "features");
            if (featuresEl == null) {
                return out;
            }
            addPackages(PackageChecksum.child(featuresEl, "mandatory"), true, null, out);
            Element opt = PackageChecksum.child(featuresEl, "optional");
            if (opt != null) {
                addPackages(opt, false, null, out);
                for (Element g : PackageChecksum.children(opt, "group")) {
                    addPackages(g, false, g.getAttribute("display-name"), out);
                }
            }
            return out;
        }

        private static void addPackages(Element parent, boolean mandatory, String group, List<Feature> out) {
            for (Element p : PackageChecksum.children(parent, "package")) {
                Feature f = new Feature();
                f.packageId = p.getAttribute("id");
                f.displayName = p.getAttribute("display-name");
                f.mandatory = mandatory;
                f.group = group;
                out.add(f);
            }
        }
    }

    /** {@code <supported-drivers><definition display-name driver-id id/></supported-drivers>}. */
    public static final class SupportedDriver {
        public String displayName;
        public String driverId;
        public String id;

        public static List<SupportedDriver> parse(Element directiveRoot) {
            List<SupportedDriver> out = new ArrayList<>();
            if (directiveRoot == null) {
                return out;
            }
            Element sd = "supported-drivers".equals(directiveRoot.getNodeName())
                ? directiveRoot : PackageChecksum.child(directiveRoot, "supported-drivers");
            for (Element d : PackageChecksum.children(sd, "definition")) {
                SupportedDriver s = new SupportedDriver();
                s.displayName = d.getAttribute("display-name");
                s.driverId = d.getAttribute("driver-id");
                s.id = d.getAttribute("id");
                out.add(s);
            }
            return out;
        }
    }
}
