package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The object-level diff between two versions of the same (or related)
 * package, for {@code package.diff} (docs/packages.md §3.1): objects added,
 * removed or changed by association id (rename-aware), package-level
 * driver-attribute changes, and dependency/feature changes. Content is
 * compared through {@link NxslCanonical#canonical}, exactly as installed
 * content is compared to detect a customization.
 */
public final class PackageDiff {

    public final List<String> added = new ArrayList<>();
    public final List<String> removed = new ArrayList<>();
    public final List<String> renamed = new ArrayList<>();
    public final List<String> contentChanged = new ArrayList<>();
    public final List<String> directiveChanged = new ArrayList<>();
    public final List<String> driverAttributesChanged = new ArrayList<>();
    public final List<String> dependencyChanges = new ArrayList<>();
    public final List<String> featureChanges = new ArrayList<>();

    public boolean isEmpty() {
        return added.isEmpty() && removed.isEmpty() && renamed.isEmpty() && contentChanged.isEmpty()
            && directiveChanged.isEmpty() && driverAttributesChanged.isEmpty() && dependencyChanges.isEmpty()
            && featureChanges.isEmpty();
    }

    public static PackageDiff of(PackageJar a, PackageJar b) {
        PackageDiff d = new PackageDiff();
        Map<String, PackageJar.Item> ai = keyed(a);
        Map<String, PackageJar.Item> bi = keyed(b);
        for (Map.Entry<String, PackageJar.Item> e : ai.entrySet()) {
            PackageJar.Item ia = e.getValue();
            PackageJar.Item ib = bi.remove(e.getKey());
            if (ib == null) {
                d.removed.add(describe(ia));
                continue;
            }
            if (!eq(ia.name, ib.name)) {
                d.renamed.add(describe(ia) + " -> " + ib.name);
            }
            if (!canonicalOf(ia).equals(canonicalOf(ib))) {
                d.contentChanged.add(describe(ib));
            }
            if (!eq(ia.directive, ib.directive)) {
                d.directiveChanged.add(describe(ib));
            }
        }
        for (PackageJar.Item ib : bi.values()) {
            d.added.add(describe(ib));
        }
        diffPackageAttributes(a, b, d);
        diffDependencies(a, b, d);
        diffFeatures(a, b, d);
        return d;
    }

    private static Map<String, PackageJar.Item> keyed(PackageJar p) {
        Map<String, PackageJar.Item> m = new LinkedHashMap<>();
        for (PackageJar.Item it : p.items) {
            String key = it.assocId != null ? "id:" + it.assocId : "nf:" + it.folderId + "/" + it.name;
            m.put(key, it);
        }
        return m;
    }

    private static String describe(PackageJar.Item it) {
        return "[" + it.folderId + "-" + it.folderName + "] " + it.objectClass + " '" + it.name + "'";
    }

    private static String canonicalOf(PackageJar.Item it) {
        if (it.content != null) {
            return NxslCanonical.canonical(it.content);
        }
        if (it.text != null) {
            return it.text.replace("\r\n", "\n");
        }
        return "";
    }

    private static Element directiveRoot(PackageJar p) {
        return p.directive == null ? null : NxslCanonical.parse(p.directive).getDocumentElement();
    }

    private static void diffPackageAttributes(PackageJar a, PackageJar b, PackageDiff d) {
        Map<String, String> aAttrs = packageDsAttributes(directiveRoot(a));
        Map<String, String> bAttrs = packageDsAttributes(directiveRoot(b));
        for (Map.Entry<String, String> e : aAttrs.entrySet()) {
            String bv = bAttrs.remove(e.getKey());
            if (bv == null) {
                d.driverAttributesChanged.add(e.getKey() + ": removed (was " + e.getValue() + ")");
            } else if (!bv.equals(e.getValue())) {
                d.driverAttributesChanged.add(e.getKey() + ": " + e.getValue() + " -> " + bv);
            }
        }
        for (Map.Entry<String, String> e : bAttrs.entrySet()) {
            d.driverAttributesChanged.add(e.getKey() + ": added (" + e.getValue() + ")");
        }
    }

    private static Map<String, String> packageDsAttributes(Element directiveRoot) {
        Map<String, String> out = new LinkedHashMap<>();
        if (directiveRoot == null) {
            return out;
        }
        Element attrs = PackageChecksum.child(directiveRoot, "ds-attributes");
        for (Element a : PackageChecksum.children(attrs, "ds-attribute")) {
            String name = a.getAttribute("ds-attr-name");
            Element v = PackageChecksum.child(a, "ds-value");
            out.put(name, v == null ? "" : NxslCanonical.serialize(v));
        }
        return out;
    }

    private static void diffDependencies(PackageJar a, PackageJar b, PackageDiff d) {
        Map<String, Dependency> ad = byPackageId(Dependency.parseDependencies(directiveRoot(a)));
        Map<String, Dependency> bd = byPackageId(Dependency.parseDependencies(directiveRoot(b)));
        for (Map.Entry<String, Dependency> e : ad.entrySet()) {
            Dependency bDep = bd.remove(e.getKey());
            if (bDep == null) {
                d.dependencyChanges.add("removed: " + e.getValue().name + " [" + e.getKey() + "]");
            } else if (!e.getValue().constraintText().equals(bDep.constraintText()) || e.getValue().type != bDep.type) {
                d.dependencyChanges.add("changed: " + bDep.name + " [" + e.getKey() + "] " + e.getValue().constraintText()
                    + " -> " + bDep.constraintText());
            }
        }
        for (Dependency bDep : bd.values()) {
            d.dependencyChanges.add("added: " + bDep.name + " [" + bDep.packageId + "] " + bDep.constraintText());
        }
    }

    private static Map<String, Dependency> byPackageId(List<Dependency> deps) {
        Map<String, Dependency> m = new LinkedHashMap<>();
        for (Dependency dep : deps) {
            m.put(dep.packageId, dep);
        }
        return m;
    }

    private static void diffFeatures(PackageJar a, PackageJar b, PackageDiff d) {
        Map<String, Dependency.Feature> af = byFeatureId(Dependency.Feature.parse(directiveRoot(a)));
        Map<String, Dependency.Feature> bf = byFeatureId(Dependency.Feature.parse(directiveRoot(b)));
        for (Map.Entry<String, Dependency.Feature> e : af.entrySet()) {
            Dependency.Feature bFeat = bf.remove(e.getKey());
            if (bFeat == null) {
                d.featureChanges.add("removed: " + e.getValue().displayName + " [" + e.getKey() + "]");
            } else if (e.getValue().mandatory != bFeat.mandatory) {
                d.featureChanges.add("changed: " + bFeat.displayName + " [" + e.getKey() + "] "
                    + (e.getValue().mandatory ? "mandatory" : "optional") + " -> " + (bFeat.mandatory ? "mandatory" : "optional"));
            }
        }
        for (Dependency.Feature bFeat : bf.values()) {
            d.featureChanges.add("added: " + bFeat.displayName + " [" + bFeat.packageId + "]");
        }
    }

    private static Map<String, Dependency.Feature> byFeatureId(List<Dependency.Feature> features) {
        Map<String, Dependency.Feature> m = new LinkedHashMap<>();
        for (Dependency.Feature f : features) {
            m.put(f.packageId, f);
        }
        return m;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "added", added);
        appendSection(sb, "removed", removed);
        appendSection(sb, "renamed", renamed);
        appendSection(sb, "content changed", contentChanged);
        appendSection(sb, "directive changed", directiveChanged);
        appendSection(sb, "package ds-attributes changed", driverAttributesChanged);
        appendSection(sb, "dependencies changed", dependencyChanges);
        appendSection(sb, "features changed", featureChanges);
        if (isEmpty()) {
            sb.append("(no differences)\n");
        }
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append(title).append(":\n");
        for (String l : lines) {
            sb.append("  ").append(l).append('\n');
        }
    }

    public String json() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"added\":").append(arr(added));
        sb.append(",\"removed\":").append(arr(removed));
        sb.append(",\"renamed\":").append(arr(renamed));
        sb.append(",\"contentChanged\":").append(arr(contentChanged));
        sb.append(",\"directiveChanged\":").append(arr(directiveChanged));
        sb.append(",\"driverAttributesChanged\":").append(arr(driverAttributesChanged));
        sb.append(",\"dependencyChanges\":").append(arr(dependencyChanges));
        sb.append(",\"featureChanges\":").append(arr(featureChanges));
        return sb.append('}').toString();
    }

    private static String arr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(com.pointblue.dirxml.dev.deploy.DeployLog.q(items.get(i)));
        }
        return sb.append(']').toString();
    }
}
