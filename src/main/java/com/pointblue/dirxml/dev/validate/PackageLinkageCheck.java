package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.PackageStamps;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package linkage records — a packaged artifact that a policy set links should carry the
 * package's own record of that link, {@code dirxml-pkglinkages} (the vault's
 * {@code DirXML-pkgLinkages}: {@code <policy-set name channel order="Weight" value=…/>}).
 * Nothing at run time reads it, but every later {@code package.install} / {@code package.upgrade}
 * into that set does: Designer's weight rule places a new package policy before the first
 * existing link whose recorded weight is greater, and a link with no record counts as
 * hand-made (weight −1) and is never displaced — so a missing record puts new package
 * policies after this one regardless of weight (docs/packages.md, "Link by weight").
 *
 * <p>A tree from the vault ({@code import-live}, {@code import-ldif}), one the package
 * installer built, and one read from a Designer project (its {@code Idm:InstalledLinkages},
 * since 2026-09-18) carry the records; a tree from an export never does (the format has no
 * place for them), nor does a project tree written before that date. Designer itself writes
 * the record for policies and ECMAScript resources but, on the vaults and projects sampled,
 * for only some GCV objects (set 14), so a GCV object without one is reported as
 * information, not as a warning.
 *
 * <p>Codes: {@code package-linkage-missing} (W), {@code package-linkage-missing-gcv} (I).
 */
public final class PackageLinkageCheck implements Check {

    @Override
    public String name() {
        return "package-linkage";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        Map<String, Artifact> index = ds.index();
        // artifact path -> the sets (across drivers) that link it, in link order
        Map<String, Set<PolicySet>> linkedIn = new LinkedHashMap<>();
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                linkedIn.computeIfAbsent(l.ref, k -> new LinkedHashSet<>()).add(l.set);
            }
        }
        for (Map.Entry<String, Set<PolicySet>> e : linkedIn.entrySet()) {
            Artifact a = index.get(e.getKey());
            if (a == null || !PackageStamps.isPackaged(a.meta) || PackageStamps.linkages(a.meta) != null) {
                continue;
            }
            List<String> sets = new ArrayList<>();
            boolean onlyGcv = true;
            for (PolicySet s : e.getValue()) {
                sets.add(s.key);
                if (s != PolicySet.GCV) {
                    onlyGcv = false;
                }
            }
            String where = String.join(", ", sets);
            String pkg = PackageStamps.packageId(a.meta);
            if (onlyGcv) {
                r.add(Finding.info("package-linkage-missing-gcv", a.path(),
                    "packaged GCV object linked in " + where + " without a package linkage record (dirxml-pkglinkages)",
                    "Designer itself records the link for only some GCV objects; a later package install into this set "
                        + "treats it as hand-made (weight -1). Package " + pkg + "."));
            } else {
                r.add(Finding.warning("package-linkage-missing", a.path(),
                    "packaged artifact linked in " + where + " without a package linkage record (dirxml-pkglinkages)",
                    "a later package.install or package.upgrade into " + where + " treats it as hand-made (weight -1) and "
                        + "places new package policies after it regardless of weight. The record comes from the vault "
                        + "(import-live, import-ldif), from package.install or from a Designer project (import-project); "
                        + "an export never carries it, nor does a project tree imported before 2026-09-18 — re-import. "
                        + "Package " + pkg + "."));
            }
        }
    }
}
