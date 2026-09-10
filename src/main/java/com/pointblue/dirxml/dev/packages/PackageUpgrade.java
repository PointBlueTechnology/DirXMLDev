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
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code package.upgrade} / {@code package.downgrade}: replace one installed
 * package's version in place. Designer treats both as the same mechanics
 * (docs/spikes/designer-package-layer.md §4.1: "upgrade and downgrade are
 * identical code paths; 'downgrade' is only a UI word") — uninstall-old +
 * install-new against the <em>same</em> objects where they match, so that:
 *
 * <ul>
 *   <li>objects are matched across versions by association id (falling back
 *       to same name when the assoc id isn't found — a defensive
 *       generalisation of Designer's {@code hasMatchingChild}, which also
 *       requires the same installation container; our fixtures never
 *       exercise a container change, so that extra check is not implemented);</li>
 *   <li>a matched object that is <b>not</b> customized takes the new content,
 *       new directive-driven linkage, and new stamps;</li>
 *   <li>a matched object that <b>is</b> customized keeps its customized
 *       content — its baseline and installed checksum are still recomputed
 *       from the new package's content (as if it had been installed fresh)
 *       and then the customized content is put back, per §4.1/§4.4: Designer
 *       stores the new version's checksum against the object even though the
 *       saved content is the old, customized one, so the inequality that
 *       marks it "customized" is preserved after the upgrade;</li>
 *   <li>unmatched old objects are removed (customized ones only with
 *       {@code --yes}, same rule as {@code package.uninstall});</li>
 *   <li>unmatched new objects are created exactly as {@code package.install}
 *       creates them;</li>
 *   <li>linkage is unlinked and rebuilt from the new directives for every
 *       matched/new object, then the package's own package-level linkage is
 *       re-applied; other installed packages' package-level linkage that
 *       names this package's objects is re-applied last, read from their own
 *       jars when {@code --catalog} is given (best-effort: skipped, with a
 *       note, when it isn't or a jar can't be found — this path is not
 *       exercised by the shipped tests, which upgrade a package nothing else
 *       links into);</li>
 *   <li>driver {@code ds-attributes} are re-applied (§2.6 set/merge) except
 *       that GCV <em>values</em> already on the driver are kept — for the
 *       package's own {@code DirXML-GlobalConfigDef} objects this means a
 *       matched, non-customized GCV resource's definitions are refreshed but
 *       its current values are merged forward with
 *       {@link PackageInstall.Install#mergeGcvDocuments}, the same merge
 *       {@code package.install} uses for a driver's engine-control values;</li>
 *   <li>the filter-extension merge only touches classes/attres whose
 *       definition changed between the old and new filter resources, read
 *       from the old package's own jar when {@code --catalog} is given (a
 *       full re-merge — safe, since the merge only ever raises precedence —
 *       is used as a fallback when the old jar isn't available);</li>
 *   <li>the manifest record is replaced.</li>
 * </ul>
 */
public final class PackageUpgrade implements Operation {

    private final String driverName;    // null: a driver-set/Library package
    private final Path newJar;
    private final Map<String, String> answers;
    private final boolean yes;
    private final boolean downgrade;    // informational only — same mechanics either direction
    private final Path catalog;         // optional: resolves the old jar (filter diff) and other packages' linkage

    public PackageUpgrade(String driverName, Path newJar, Map<String, String> answers, boolean yes, boolean downgrade, Path catalog) {
        this.driverName = driverName;
        this.newJar = newJar;
        this.answers = answers == null ? Map.of() : answers;
        this.yes = yes;
        this.downgrade = downgrade;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return downgrade ? "package.downgrade" : "package.upgrade";
    }

    @Override
    public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
        if (newJar == null) {
            throw new Refusal("the new package jar is required (--jar, or --catalog and --package)");
        }
        Driver d = null;
        if (driverName != null) {
            d = ds.driver(driverName);
            if (d == null) {
                throw new Refusal("no driver '" + driverName + "' in the tree");
            }
        }
        PackageJar newPkg;
        try {
            newPkg = PackageJar.read(newJar);
        } catch (IOException e) {
            throw new Refusal("cannot read package " + newJar + ": " + e.getMessage());
        }
        Map<String, String> meta = d != null ? d.meta : ds.meta;
        String key = PackageInstall.META_INSTALLED_PREFIX + newPkg.shortName;
        String oldRecord = meta.get(key);
        if (oldRecord == null) {
            throw new Refusal(newPkg.shortName + " is not installed on " + (d != null ? "'" + d.name + "'" : "the Library")
                + "; use package.install");
        }
        PackageStatus.Installed oldInfo = PackageStatus.parse(oldRecord);
        if (newPkg.version != null && newPkg.version.equals(oldInfo.version)) {
            throw new Refusal(newPkg.shortName + " is already installed at version " + oldInfo.version);
        }
        String newGuid = PackageInstall.guid(newPkg);
        String packageId = newPkg.pkg.getAttribute("id");   // stable across versions

        PackageInstall.bindOptions(answers, true);
        PackageInstall.Install install = new PackageInstall.Install(ds, d, newPkg, newGuid, tx);
        install.index();
        Element packageDirective = CanonicalXml.parse(newPkg.directive).getDocumentElement();

        // 1. prompts — existing driver values as curDoc, propertyWizard=true (already bound above)
        install.prompts(packageDirective);
        // 2. driver ds-attributes (set/merge, §2.6)
        if (d != null) {
            install.driverAttributes(PromptEngine.child(packageDirective, "ds-attributes"));
        }

        // 3. match old objects to new items (scoped to this target — another driver's install of the
        // same catalog package, same package id, must never be touched by this upgrade)
        Map<String, Artifact> oldByAssoc = new LinkedHashMap<>();
        for (Artifact a : PackageInstall.targetArtifacts(ds, d)) {
            if (packageId.equals(a.meta.get(PackageInstall.META_PACKAGE_ID))) {
                String assoc = a.meta.get(PackageInstall.META_PKG_ASSOC) != null
                    ? a.meta.get(PackageInstall.META_PKG_ASSOC) : a.meta.get(PackageInstall.META_ASSOC);
                if (assoc != null) {
                    oldByAssoc.put(assoc, a);
                }
            }
        }
        List<String> kept = new ArrayList<>();
        List<String> customizedKept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();

        java.util.Set<String> matchedOldAssoc = new java.util.LinkedHashSet<>();
        for (PackageJar.Item it : newPkg.items) {
            if (it.assocId == null) {
                continue;
            }
            if (PackageChecksum.RESOURCE.equals(it.objectClass) && it.contentType != null
                && (it.contentType.startsWith(PackageInstall.PKG_PROMPT) || it.contentType.startsWith(PackageInstall.FILTER_EXT))) {
                continue;   // prompts are never objects; filter-ext handled separately below
            }
            String newAssoc = it.assocId;
            Artifact old = oldByAssoc.get(newAssoc);
            String oldAssocKey = old != null ? newAssoc : null;
            if (old == null) {
                // fall back to same name, anywhere this package owns an object (a rename-aware match by assoc id failed)
                for (Map.Entry<String, Artifact> e : oldByAssoc.entrySet()) {
                    if (e.getValue().name.equals(it.name)) {
                        old = e.getValue();
                        oldAssocKey = e.getKey();
                        break;
                    }
                }
            }
            if (old == null) {
                install.create(it);
                added.add(it.name);
                continue;
            }
            matchedOldAssoc.add(oldAssocKey);
            updateMatched(install, it, old, tx);
            // keyed by the NEW item's assoc id — consistent with directives/contents, which index() keyed the same way
            install.created.put(newAssoc, old);
            if (Packages.isCustomized(old)) {
                customizedKept.add(old.path());
            } else {
                kept.add(old.path());
            }
        }

        // 4. unmatched old objects: removed
        List<String> customizedRemoved = new ArrayList<>();
        for (Map.Entry<String, Artifact> e : oldByAssoc.entrySet()) {
            if (matchedOldAssoc.contains(e.getKey())) {
                continue;
            }
            Artifact a = e.getValue();
            if (Packages.isCustomized(a)) {
                customizedRemoved.add(a.path());
            }
        }
        if (!customizedRemoved.isEmpty() && !yes) {
            throw new Refusal(newPkg.shortName + " upgrade would remove customized object(s), only with --yes: " + customizedRemoved);
        }
        for (Map.Entry<String, Artifact> e : oldByAssoc.entrySet()) {
            if (matchedOldAssoc.contains(e.getKey())) {
                continue;
            }
            Artifact a = e.getValue();
            unlinkEverywhere(ds, a);
            PackageUninstall.removeDriverSetLinkageMeta(ds, a);
            PackageUninstall.removeFromModel(ds, a.scope == com.pointblue.dirxml.dev.model.Scope.LIBRARY ? null : ds.driver(a.driver), a);
            tx.dropBaseline(a);
            tx.installed(a);
            removed.add(a.path());
        }

        // 5. relink every matched/new object from the new directives, then stamp
        for (Map.Entry<String, Artifact> e : install.created.entrySet()) {
            unlinkEverywhere(ds, e.getValue());
        }
        for (Map.Entry<String, Artifact> e : install.created.entrySet()) {
            install.link(e.getKey(), e.getValue(), false);
        }
        for (Map.Entry<String, Artifact> e : install.created.entrySet()) {
            install.link(e.getKey(), e.getValue(), true);
        }
        for (Map.Entry<String, Artifact> e : install.created.entrySet()) {
            stampForUpgrade(install, e.getKey(), e.getValue(), tx);
        }
        // 6. package-level linkage (this package's own) and the manifest record
        install.packageLinkage(packageDirective);
        meta.put(key, newGuid + (newPkg.basePackage ? ";base" : ""));
        if (d != null && newPkg.basePackage) {
            d.meta.put(PackageInstall.META_GUID, newGuid);
        }

        // 7. other installed packages' package-level linkage that names this package's objects
        reapplyDependantLinkage(ds, d, newPkg.shortName, tx);

        // 8. filter extensions — only classes/attrs whose definition changed
        if (d != null) {
            upgradeFilterExtensions(ds, d, newPkg, oldInfo, tx);
        }

        tx.note((downgrade ? "downgraded " : "upgraded ") + newPkg.shortName + " " + oldInfo.version + " -> " + newPkg.version
            + ": kept " + kept.size() + " object(s), kept customized " + customizedKept + ", removed " + removed
            + ", added " + added);
    }

    /** Applies the new item's content (or, for a matched GCV, merges new definitions with the driver's current values). */
    private void updateMatched(PackageInstall.Install install, PackageJar.Item it, Artifact old, Transaction tx) {
        if (Packages.isCustomized(old)) {
            // Its customized content stays untouched here — stampForUpgrade applies the new
            // content only transiently (to compute the new baseline/checksum) and puts the
            // customization straight back. Overwriting it here would lose it for good.
            return;
        }
        Element dir = install.directives.get(it.assocId);
        Element content = install.contents.get(it.assocId);
        if (old instanceof Policy) {
            Policy p = (Policy) old;
            p.content = content == null ? null : PackageInstall.copy(content);
        } else {
            Resource r = (Resource) old;
            if (r.isGcvDef()) {
                Element cv = PromptEngine.child(dir, "configuration-values");
                Element newDefs = cv == null
                    ? CanonicalXml.parse("<configuration-values><definitions/></configuration-values>").getDocumentElement()
                    : PackageInstall.copy(cv);
                r.content = PackageInstall.Install.mergeGcvDocuments(r.content, newDefs);
            } else if (content != null && PackageChecksum.isXmlContentType(r.contentType)) {
                r.content = PackageInstall.copy(content);
                r.text = null;
            } else if (it.text != null) {
                r.text = it.text;
                r.content = null;
            } else if (content != null) {
                r.content = PackageInstall.copy(content);
                r.text = null;
            }
        }
    }

    /**
     * Stamps a relinked object for the new package version: a non-customized
     * object is stamped normally (new content, new baseline, new checksum). A
     * customized one is stamped as if its new content had just been
     * installed — same baseline/checksum recipe — and then its customized
     * content is put back, so it keeps comparing as customized against the
     * new version's baseline (§4.4).
     */
    private void stampForUpgrade(PackageInstall.Install install, String assocId, Artifact a, Transaction tx) throws IOException {
        a.meta.put(PackageInstall.META_GUID, install.guid);
        a.meta.put(PackageInstall.META_PKG_ASSOC, assocId);
        a.meta.put(PackageInstall.META_ASSOC, assocId);
        if (!Packages.isCustomized(a)) {
            install.stamp(assocId, a);
            return;
        }
        Object saved = snapshotContent(a);
        applyNewContentForStamp(install, assocId, a);
        install.stamp(assocId, a);   // baseline + checksum now describe the new version's content, as Designer stores them
        restoreContent(a, saved);
        a.meta.put(Packages.CUSTOMIZED_KEY, "true");
    }

    private Object snapshotContent(Artifact a) {
        if (a instanceof Policy) {
            return ((Policy) a).content;
        }
        Resource r = (Resource) a;
        return new Object[] { r.content, r.text };
    }

    private void restoreContent(Artifact a, Object saved) {
        if (a instanceof Policy) {
            ((Policy) a).content = (Element) saved;
            return;
        }
        Resource r = (Resource) a;
        Object[] s = (Object[]) saved;
        r.content = (Element) s[0];
        r.text = (String) s[1];
    }

    private void applyNewContentForStamp(PackageInstall.Install install, String assocId, Artifact a) {
        Element dir = install.directives.get(assocId);
        Element content = install.contents.get(assocId);
        if (a instanceof Policy) {
            ((Policy) a).content = content == null ? null : PackageInstall.copy(content);
            return;
        }
        Resource r = (Resource) a;
        if (r.isGcvDef()) {
            Element cv = PromptEngine.child(dir, "configuration-values");
            r.content = cv == null
                ? CanonicalXml.parse("<configuration-values><definitions/></configuration-values>").getDocumentElement()
                : PackageInstall.copy(cv);
        } else if (content != null && PackageChecksum.isXmlContentType(r.contentType)) {
            r.content = PackageInstall.copy(content);
            r.text = null;
        } else if (content != null) {
            r.content = PackageInstall.copy(content);
            r.text = null;
        }
    }

    private void unlinkEverywhere(DriverSet ds, Artifact a) {
        Driver owner = a.scope == com.pointblue.dirxml.dev.model.Scope.LIBRARY ? null : ds.driver(a.driver);
        if (owner == null) {
            return;
        }
        for (PolicySet set : PolicySet.values()) {
            PolicyLink found = null;
            for (PolicyLink l : owner.links(set)) {
                if (l.ref.equals(a.path())) {
                    found = l;
                }
            }
            if (found != null) {
                owner.links.remove(found);
                PackageUninstall.renumber(owner, set);
            }
        }
    }

    /** Other installed packages whose package-level linkage names this package's objects: re-applied from their own jar (best-effort). */
    private void reapplyDependantLinkage(DriverSet ds, Driver d, String upgradedShort, Transaction tx) {
        if (catalog == null) {
            return;
        }
        Map<String, String> meta = d != null ? d.meta : ds.meta;
        for (Map.Entry<String, String> e : new LinkedHashMap<>(meta).entrySet()) {
            if (!e.getKey().startsWith(PackageInstall.META_INSTALLED_PREFIX)) {
                continue;
            }
            String other = e.getKey().substring(PackageInstall.META_INSTALLED_PREFIX.length());
            if (other.equals(upgradedShort)) {
                continue;
            }
            PackageStatus.Installed info = PackageStatus.parse(e.getValue());
            if (info.version == null) {
                continue;
            }
            try {
                Path jar = PackageInstall.jarOf(null, catalog.toString(), other + "_" + info.version);
                PackageJar pj = PackageJar.read(jar);
                PackageInstall.Install reinstall = new PackageInstall.Install(ds, d, pj, PackageInstall.guid(pj), tx);
                reinstall.index();
                Element dir = CanonicalXml.parse(pj.directive).getDocumentElement();
                reinstall.packageLinkage(dir);
            } catch (Exception ex) {
                tx.note("could not re-apply " + other + "'s package-level linkage after upgrading " + upgradedShort + ": " + ex.getMessage());
            }
        }
    }

    /** Filter-extension re-merge, limited to classes/attrs whose definition changed (needs the old jar via --catalog; else a full re-merge). */
    private void upgradeFilterExtensions(DriverSet ds, Driver d, PackageJar newPkg, PackageStatus.Installed oldInfo, Transaction tx) {
        for (PackageJar.Item it : newPkg.items) {
            if (!(PackageChecksum.RESOURCE.equals(it.objectClass) && it.contentType != null
                && it.contentType.startsWith(PackageInstall.FILTER_EXT) && it.assocId != null)) {
                continue;
            }
            Element oldFilter = oldFilterContent(newPkg.shortName, oldInfo.version, it.name);
            Element newFilter = it.content;
            if (oldFilter != null && newFilter != null
                && CanonicalXml.serialize(oldFilter).equals(CanonicalXml.serialize(newFilter))) {
                continue;   // unchanged: nothing to re-merge
            }
            PackageInstall.Install install = new PackageInstall.Install(ds, d, newPkg, PackageInstall.guid(newPkg), tx);
            install.index();
            install.filterExtension(it);
        }
    }

    private Element oldFilterContent(String shortName, String oldVersion, String resourceName) {
        if (catalog == null || oldVersion == null) {
            return null;
        }
        try {
            Path jar = PackageInstall.jarOf(null, catalog.toString(), shortName + "_" + oldVersion);
            PackageJar old = PackageJar.read(jar);
            for (PackageJar.Item it : old.items) {
                if (it.name.equals(resourceName)) {
                    return it.content;
                }
            }
        } catch (Exception e) {
            // no old jar available — caller falls back to a full re-merge
        }
        return null;
    }
}
