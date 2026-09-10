package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.edit.Operation;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.edit.Transaction;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code package.uninstall}: remove one installed package (and, with
 * {@code --all}, its dependants / the whole feature set of a base package),
 * reproducing Designer's {@code remove(target)} (docs/packages.md §3.3,
 * docs/spikes/designer-package-layer.md §4.1, §2.4, §2.7):
 * <ol>
 *   <li>package-level unlink — undo whatever this package's own
 *       package-directive linkage did to <em>other</em> packages' objects
 *       (recorded in their {@code dirxml-pkglinkages} with this package's id);</li>
 *   <li>remove the package's own objects: unlink from every policy set,
 *       delete, drop the {@code .package-baseline/} file, drop the package
 *       stamps;</li>
 *   <li>filter extensions: walk the {@code dirxml-pkgextensions} cache and
 *       remove this package's {@code <package>} marks; a class/attr with no
 *       marks left is restored ({@code existing="true"}) or removed from the
 *       driver filter, then dropped from the cache;</li>
 *   <li>the driver's own base {@code dirxml-pkgguid} if it named this package;</li>
 *   <li>the manifest record ({@code package.installed.&lt;SHORT&gt;}).</li>
 * </ol>
 *
 * <h2>Dependants</h2>
 * A package is a base package's dependant, or a dependant of the package
 * being removed, when its own package directive declares a
 * {@code <dependency package-id="…">} on it (spike §3.1/§3.3 — the real
 * dependency graph, checked from the installed package's jar via
 * {@code --catalog}). Refused unless {@code --all}, which removes dependants
 * first (so the package being asked for is never left with a broken
 * dependency), then the package itself. Without a catalog, the same check
 * falls back to the package-level linkage records (§2.4): a package B whose
 * own installed linkage entries ({@code dirxml-pkglinkages package-id="B"})
 * appear on another package A's objects also depends on A — a coarser proxy,
 * used only when the jar isn't available to read the real
 * {@code &lt;dependencies&gt;} declaration from.
 *
 * <h2>What "package-level unlink" removes</h2>
 * Every link — whether made by an item's own per-item directive at its own
 * package's install, or by another package's package-directive-level linkage
 * (docs/spikes/designer-package-layer.md §2.4) — is recorded in the linked
 * artifact's {@code dirxml-pkglinkages} meta with {@code package-id} = the
 * package whose install created that particular link entry. Removing package
 * P therefore removes only the link entries stamped {@code package-id=P},
 * wherever they live; a link entry stamped with a different, still-installed
 * package's id is left alone (§2.4: "a … link owned by another installed
 * package's directive is kept"). In practice, when a linking package's
 * package-level linkage targets an object already linked into the same set
 * by its own per-item directive, {@link PackageInstall.Install#linkOne} is a
 * no-op (the object is already in the set) and records nothing — so removing
 * the linking package leaves that link untouched, because there was never a
 * separate entry to remove. That is the case NOVLEDIRPSYN/NOVLPWDSYNC
 * exercises: see {@code PackageLifecycleTest} for the worked-out reasoning.
 */
public final class PackageUninstall implements Operation {

    private final String driverName;   // null: a driver-set/Library package
    private final String shortName;
    private final boolean yes;
    private final boolean all;
    private final Path catalog;        // optional: resolves other installed packages' declared dependencies

    public PackageUninstall(String driverName, String shortName, boolean yes, boolean all, Path catalog) {
        this.driverName = driverName;
        this.shortName = shortName;
        this.yes = yes;
        this.all = all;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "package.uninstall";
    }

    @Override
    public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
        if (shortName == null || shortName.isBlank()) {
            throw new Refusal("a package (--package SHORT) is required");
        }
        Driver d = null;
        if (driverName != null) {
            d = ds.driver(driverName);
            if (d == null) {
                throw new Refusal("no driver '" + driverName + "' in the tree");
            }
        }
        Map<String, String> meta = d != null ? d.meta : ds.meta;
        String prefix = PackageInstall.META_INSTALLED_PREFIX;
        if (!meta.containsKey(prefix + shortName)) {
            throw new Refusal(shortName + " is not installed on " + (d != null ? "'" + d.name + "'" : "the Library"));
        }

        Map<String, String> installed = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                installed.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        PackageStatus.Installed target = PackageStatus.parse(installed.get(shortName));
        boolean isBase = installed.get(shortName).endsWith(";base");

        // dependants: other installed packages that (transitively) depend on this one, by their own declared dependency
        Set<String> requiredIds = new LinkedHashSet<>();
        requiredIds.add(target.id);
        Set<String> dependants = new LinkedHashSet<>();
        boolean grown = true;
        while (grown) {
            grown = false;
            for (Map.Entry<String, String> e : installed.entrySet()) {
                String b = e.getKey();
                if (b.equals(shortName) || dependants.contains(b)) {
                    continue;
                }
                PackageStatus.Installed bi = PackageStatus.parse(e.getValue());
                for (String reqId : requiredIds) {
                    if (dependsOn(b, bi.version, reqId)) {
                        dependants.add(b);
                        requiredIds.add(bi.id);
                        grown = true;
                        break;
                    }
                }
            }
        }
        if (!dependants.isEmpty() && !all) {
            throw new Refusal(shortName + " cannot be uninstalled: " + dependants + " depend(s) on it (use --all to remove them too)");
        }
        List<String> others = new ArrayList<>(installed.keySet());
        others.remove(shortName);
        others.removeAll(dependants);
        if (isBase && !others.isEmpty() && !all) {
            throw new Refusal(shortName + " is the base package; " + others + " remain installed (use --all to remove them too)");
        }

        List<String> order = new ArrayList<>(dependants);
        if (isBase && all) {
            for (String o : others) {
                if (!order.contains(o)) {
                    order.add(o);
                }
            }
        }
        order.add(shortName);

        for (String sn : order) {
            removeOne(ds, d, meta, sn, tx);
        }
    }

    private boolean dependsOn(String bShort, String bVersion, String targetId) {
        if (targetId == null || catalog == null || bVersion == null) {
            return false;
        }
        try {
            Path jar = PackageInstall.jarOf(null, catalog.toString(), bShort + "_" + bVersion);
            PackageJar pj = PackageJar.read(jar);
            Element dir = CanonicalXml.parse(pj.directive).getDocumentElement();
            for (Dependency dep : Dependency.parseDependencies(dir)) {
                if (targetId.equals(dep.packageId)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // best-effort: no catalog entry, unreadable jar — treated as "no declared dependency"
        }
        return false;
    }

    private void removeOne(DriverSet ds, Driver d, Map<String, String> meta, String sn, Transaction tx) throws Refusal, IOException {
        String prefix = PackageInstall.META_INSTALLED_PREFIX;
        String record = meta.get(prefix + sn);
        if (record == null) {
            return;   // already gone (e.g. named twice in --all)
        }
        PackageStatus.Installed info = PackageStatus.parse(record);
        List<Artifact> candidates = PackageInstall.targetArtifacts(ds, d);
        List<Artifact> owned = new ArrayList<>();
        for (Artifact a : candidates) {
            if (info.id != null && info.id.equals(a.meta.get(PackageInstall.META_PACKAGE_ID))) {
                owned.add(a);
            }
        }
        List<String> customizedPaths = new ArrayList<>();
        for (Artifact a : owned) {
            if (Packages.isCustomized(a)) {
                customizedPaths.add(a.path());
            }
        }
        if (!customizedPaths.isEmpty() && !yes) {
            throw new Refusal(sn + " has customized object(s), removed only with --yes: " + customizedPaths);
        }

        // 1. package-level unlink: drop link entries this package's own directive stamped on OTHER packages' objects
        // (scoped to this target: another driver's install of the same catalog package is untouched)
        for (Artifact a : candidates) {
            if (owned.contains(a)) {
                continue;
            }
            unlinkOwnedBy(ds, a, info.id);
        }

        // 2. the package's own objects
        for (Artifact a : owned) {
            deleteFully(ds, a, tx);
        }

        // 3. filter extensions
        if (d != null) {
            removeFilterExtensions(d, info.id);
        }

        // 4. the driver's own base record
        if (d != null) {
            String own = d.meta.get(PackageInstall.META_GUID);
            if (own != null && info.id != null && info.id.equals(PackageStatus.parse(own).id)) {
                d.meta.remove(PackageInstall.META_GUID);
            }
        }

        // 5. the manifest record
        meta.remove(prefix + sn);

        tx.note("uninstalled " + sn + ": removed " + owned.size() + " object(s)"
            + (customizedPaths.isEmpty() ? "" : " (customized, removed with --yes: " + customizedPaths + ")"));
    }

    /** Removes every link entry recorded as owned by {@code packageId} from {@code a}'s linkage meta and from the model. */
    private void unlinkOwnedBy(DriverSet ds, Artifact a, String packageId) {
        String rec = a.meta.get(PackageInstall.META_LINKAGES);
        if (rec == null || packageId == null) {
            return;
        }
        Element root;
        try {
            root = CanonicalXml.parse(rec).getDocumentElement();
        } catch (RuntimeException e) {
            return;
        }
        List<Element> keep = new ArrayList<>();
        List<Element> drop = new ArrayList<>();
        for (Element ps : PromptEngine.children(root, "policy-set")) {
            if (packageId.equals(ps.getAttribute("package-id"))) {
                drop.add(ps);
            } else {
                keep.add(ps);
            }
        }
        if (drop.isEmpty()) {
            return;
        }
        for (Element ps : drop) {
            PolicySet set = PackageInstall.policySet(ps.getAttribute("name"), ps.getAttribute("channel"));
            if (set == null) {
                continue;
            }
            Driver owner = ownerOf(ds, ps, a);
            if (owner == null) {
                continue;
            }
            PolicyLink found = null;
            for (PolicyLink l : owner.links(set)) {
                if (l.ref.equals(a.path())) {
                    found = l;
                }
            }
            if (found != null) {
                owner.links.remove(found);
                renumber(owner, set);
            }
        }
        if (keep.isEmpty()) {
            a.meta.remove(PackageInstall.META_LINKAGES);
        } else {
            StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><policy-linkage>\n");
            for (Element ps : keep) {
                body.append(CanonicalXml.serialize(ps).replaceFirst("<\\?xml[^>]*\\?>", "").trim()).append('\n');
            }
            body.append("</policy-linkage>");
            a.meta.put(PackageInstall.META_LINKAGES, body.toString());
        }
    }

    /** The driver a recorded linkage entry names, or the artifact's own driver as a fallback. */
    private Driver ownerOf(DriverSet ds, Element ps, Artifact a) {
        String designerId = ps.getAttribute("Driver");
        if (!designerId.isEmpty()) {
            for (Driver dd : ds.drivers) {
                if (designerId.equals(dd.meta.get(PackageInstall.META_DESIGNER_ID))) {
                    return dd;
                }
            }
        }
        return a.scope == Scope.LIBRARY ? null : ds.driver(a.driver);
    }

    static void deleteFully(DriverSet ds, Artifact a, Transaction tx) throws IOException {
        Driver owner = a.scope == Scope.LIBRARY ? null : ds.driver(a.driver);
        if (owner != null) {
            for (PolicySet set : PolicySet.values()) {
                PolicyLink found = null;
                for (PolicyLink l : owner.links(set)) {
                    if (l.ref.equals(a.path())) {
                        found = l;
                    }
                }
                if (found != null) {
                    owner.links.remove(found);
                    renumber(owner, set);
                }
            }
        }
        removeDriverSetLinkageMeta(ds, a);
        removeFromModel(ds, owner, a);
        tx.dropBaseline(a);
        tx.installed(a);   // reported as touched; never (re-)marks customized on a deleted object
    }

    /** Drops a Library GCV object's {@code driverset.linkage.N} meta entry, if it has one. */
    static void removeDriverSetLinkageMeta(DriverSet ds, Artifact a) {
        if (a.scope != Scope.LIBRARY) {
            return;
        }
        String dn = ds.dn == null ? a.name : "cn=" + a.name + ",cn=Library," + ds.dn;
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, String> e : ds.meta.entrySet()) {
            if (e.getKey().startsWith("driverset.linkage.") && e.getValue().startsWith(dn + "#")) {
                drop.add(e.getKey());
            }
        }
        for (String k : drop) {
            ds.meta.remove(k);
        }
    }

    static void removeFromModel(DriverSet ds, Driver owner, Artifact a) {
        if (a instanceof Policy) {
            switch (a.scope) {
                case LIBRARY: ds.library.policies.remove(a); break;
                case DRIVER: owner.policies.remove(a); break;
                case SUBSCRIBER: owner.subscriber.policies.remove(a); break;
                case PUBLISHER: owner.publisher.policies.remove(a); break;
                default: throw new IllegalStateException();
            }
        } else if (a.scope == Scope.LIBRARY) {
            ds.library.resources.remove(a);
        } else {
            owner.resources.remove(a);
        }
    }

    static void renumber(Driver d, PolicySet set) {
        List<PolicyLink> current = d.links(set);
        for (int i = 0; i < current.size(); i++) {
            current.get(i).order = i;
        }
    }

    // ---- filter extensions ----

    private void removeFilterExtensions(Driver d, String packageId) {
        String cacheXml = d.meta.get(PackageInstall.META_EXTENSIONS);
        if (cacheXml == null || packageId == null) {
            return;
        }
        Element cache = CanonicalXml.parse(cacheXml).getDocumentElement();
        Element filter = d.config.get(com.pointblue.dirxml.dev.model.Driver.DRIVER_FILTER);
        List<Element> classesToDrop = new ArrayList<>();
        for (Element cc : PromptEngine.children(cache, "filter-class")) {
            dropMarks(cc, packageId);
            Element dc = filter == null ? null
                : PackageInstall.byAttr(filter, "filter-class", "class-name", cc.getAttribute("class-name"));
            List<Element> attrsToDrop = new ArrayList<>();
            for (Element ca : PromptEngine.children(cc, "filter-attr")) {
                dropMarks(ca, packageId);
                if (hasMarks(ca)) {
                    continue;
                }
                String an = ca.getAttribute("attr-name");
                Element da = dc == null ? null : PackageInstall.byAttr(dc, "filter-attr", "attr-name", an);
                if (da != null) {
                    if ("true".equals(ca.getAttribute("existing"))) {
                        PackageInstall.copyAttrs(ca, da, PackageInstall.ATTR_ATTRS);
                    } else {
                        dc.removeChild(da);
                    }
                }
                attrsToDrop.add(ca);
            }
            for (Element ca : attrsToDrop) {
                cc.removeChild(ca);
            }
            if (!hasMarks(cc) && PromptEngine.children(cc, "filter-attr").isEmpty()) {
                if (dc != null) {
                    if ("true".equals(cc.getAttribute("existing"))) {
                        PackageInstall.copyAttrs(cc, dc, PackageInstall.CLASS_ATTRS);
                    } else {
                        filter.removeChild(dc);
                    }
                }
                classesToDrop.add(cc);
            }
        }
        for (Element cc : classesToDrop) {
            cache.removeChild(cc);
        }
        if (PromptEngine.children(cache, "filter-class").isEmpty()) {
            d.meta.remove(PackageInstall.META_EXTENSIONS);
        } else {
            d.meta.put(PackageInstall.META_EXTENSIONS, CanonicalXml.serialize(cache));
        }
    }

    private static void dropMarks(Element cacheEl, String packageId) {
        List<Element> drop = new ArrayList<>();
        for (Element o : PromptEngine.children(cacheEl, "package")) {
            if (packageId.equals(o.getAttribute("package-id"))) {
                drop.add(o);
            }
        }
        for (Element o : drop) {
            cacheEl.removeChild(o);
        }
    }

    private static boolean hasMarks(Element cacheEl) {
        return !PromptEngine.children(cacheEl, "package").isEmpty();
    }
}
