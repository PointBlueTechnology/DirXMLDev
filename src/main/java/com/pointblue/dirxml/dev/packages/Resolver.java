package com.pointblue.dirxml.dev.packages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dependency closure for installing a base package plus requested
 * features onto a target (docs/packages.md §3.1, designer-package-layer.md
 * §3.2/3.3). Reads the catalog only — it never installs anything.
 *
 * <p>Type-2 dependencies (and mandatory/requested-optional features) are
 * resolved recursively into the same install list, dependencies before
 * dependants, base first. Type-3 dependencies must be satisfied by the
 * packages already on the target's driver set, type-4 by the vault; unmet
 * ones are reported as {@link Missing}, not installed. A package id met
 * twice keeps the version first chosen and merges the new constraint;
 * a chain that cannot be satisfied at all (the package itself, or every one
 * of its acceptable versions) refuses the whole resolve.
 */
public final class Resolver {

    public static final class Chosen {
        public final String shortName;
        public final String version;
        public final String reason;

        Chosen(String shortName, String version, String reason) {
            this.shortName = shortName;
            this.version = version;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return shortName + "_" + version + " (" + reason + ")";
        }
    }

    public static final class Missing {
        public final String name;
        public final String packageId;
        public final String constraint;
        public final String neededBy;

        Missing(String name, String packageId, String constraint, String neededBy) {
            this.name = name;
            this.packageId = packageId;
            this.constraint = constraint;
            this.neededBy = neededBy;
        }

        @Override
        public String toString() {
            return name + " (" + packageId + ", " + constraint + ") needed by " + neededBy;
        }
    }

    public static final class Result {
        public final List<Chosen> install = new ArrayList<>();
        public final List<Missing> missing = new ArrayList<>();
        public String refusal;

        public boolean ok() {
            return refusal == null;
        }
    }

    private final Catalog catalog;
    private final Map<String, String> idToShort;
    private final Set<String> requestedFeatures;
    private final Set<String> driverSetHas;
    private final Set<String> vaultHas;
    private final Map<String, String> chosenVersion = new LinkedHashMap<>();   // shortName -> version
    private final Map<String, Dependency> chosenConstraint = new LinkedHashMap<>();
    private final Set<String> inProgress = new LinkedHashSet<>();
    private final Result result = new Result();

    private Resolver(Catalog catalog, List<String> requestedFeatures, Set<String> driverSetHas, Set<String> vaultHas) {
        this.catalog = catalog;
        this.idToShort = catalog.idIndex();
        this.requestedFeatures = new LinkedHashSet<>(requestedFeatures);
        this.driverSetHas = driverSetHas;
        this.vaultHas = vaultHas;
    }

    public static Result resolve(Catalog catalog, String baseSpec, List<String> features,
                                  Set<String> driverSetHas, Set<String> vaultHas) {
        Resolver r = new Resolver(catalog, features, driverSetHas, vaultHas);
        return r.run(baseSpec);
    }

    private Result run(String baseSpec) {
        String[] sv = splitSpec(baseSpec);
        String baseShort = sv[0];
        String baseVersion = sv[1];
        Catalog.PackageEntry base = catalog.get(baseShort);
        if (base == null) {
            result.refusal = "base package " + baseShort + " is not in the catalog";
            return result;
        }
        if (baseVersion == null) {
            baseVersion = base.newestVersion();
        }
        if (baseVersion == null || !base.versions.containsKey(baseVersion)) {
            result.refusal = "base package " + baseShort + " has no version " + (baseVersion == null ? "(none in catalog)" : baseVersion);
            return result;
        }
        Dependency wantBase = new Dependency();
        wantBase.name = baseShort;
        wantBase.versions.add(baseVersion);
        if (!resolveOne(baseShort, wantBase, "base", "the resolve request")) {
            return result;
        }
        // Base packages carry features; feature entries reference package ids, not the chosen version's own dependencies.
        Catalog.VersionEntry chosenBaseVe = base.versions.get(chosenVersion.get(baseShort));
        for (Dependency.Feature f : chosenBaseVe.features) {
            String fshort = idToShort.get(f.packageId);
            String label = f.displayName == null || f.displayName.isBlank() ? f.packageId : f.displayName;
            if (!f.mandatory && !requestedFeatures.contains(fshort)) {
                continue;   // optional, not requested
            }
            if (fshort == null) {
                result.missing.add(new Missing(label, f.packageId, "any version", baseShort));
                continue;
            }
            Dependency want = new Dependency();
            want.name = label;
            resolveOne(fshort, want, (f.mandatory ? "mandatory feature of " : "optional feature (requested) of ") + baseShort, baseShort);
        }
        return result;
    }

    /**
     * Resolve one package (already known by SHORT name) against a constraint, adding it (and its own
     * type-2/feature closure) to the result. Returns false on an unresolvable chain (result.refusal is set).
     *
     * @param reason   why this package was chosen, recorded on the {@link Chosen} entry verbatim
     * @param neededBy who asked for it, used only in refusal/missing messages
     */
    private boolean resolveOne(String shortName, Dependency constraint, String reason, String neededBy) {
        if (chosenVersion.containsKey(shortName)) {
            // met twice: keep the first choice, merge the constraint, and check it's still acceptable.
            Dependency merged = chosenConstraint.get(shortName);
            merged.mergeFrom(constraint);
            String already = chosenVersion.get(shortName);
            if (!merged.accepts(already)) {
                result.refusal = "unresolvable: " + shortName + "_" + already + " was chosen for " + neededBy
                    + " but does not satisfy " + merged.constraintText();
                return false;
            }
            return true;
        }
        if (inProgress.contains(shortName)) {
            result.refusal = "unresolvable: dependency cycle involving " + shortName;
            return false;
        }
        Catalog.PackageEntry entry = catalog.get(shortName);
        if (entry == null) {
            result.refusal = "unresolvable: " + shortName + " (needed by " + neededBy + ") is not in the catalog";
            return false;
        }
        List<String> acceptable = constraint.acceptable(entry.versions.keySet());
        if (acceptable.isEmpty()) {
            result.refusal = "unresolvable: " + shortName + " (needed by " + neededBy + "): no version in the catalog satisfies "
                + constraint.constraintText();
            return false;
        }
        inProgress.add(shortName);
        String version = acceptable.get(0);   // newest acceptable first; no candidate whose own deps fail is expected in practice
        Catalog.VersionEntry ve = entry.versions.get(version);
        for (Dependency dep : ve.dependencies) {
            if (dep.type == 3) {
                if (!satisfied(driverSetHas, dep)) {
                    result.missing.add(new Missing(dep.name, dep.packageId, dep.constraintText(), shortName));
                }
                continue;
            }
            if (dep.type == 4) {
                if (!satisfied(vaultHas, dep)) {
                    result.missing.add(new Missing(dep.name, dep.packageId, dep.constraintText(), shortName));
                }
                continue;
            }
            String depShort = idToShort.get(dep.packageId);
            if (depShort == null) {
                result.refusal = "unresolvable: " + dep.name + " (" + dep.packageId + ", needed by " + shortName + ") is not in the catalog";
                inProgress.remove(shortName);
                return false;
            }
            if (!resolveOne(depShort, dep.copy(), "dependency of " + shortName, shortName)) {
                inProgress.remove(shortName);
                return false;
            }
        }
        inProgress.remove(shortName);
        chosenVersion.put(shortName, version);
        chosenConstraint.put(shortName, constraint);
        result.install.add(new Chosen(shortName, version, reason));
        return true;
    }

    private boolean satisfied(Set<String> has, Dependency dep) {
        for (String sv : has) {
            String[] parts = splitSpec(sv);
            String short_ = parts[0];
            String version = parts[1];
            if (dep.packageId != null && dep.packageId.equals(idOf(short_)) && (version == null || dep.accepts(version))) {
                return true;
            }
        }
        return false;
    }

    private String idOf(String shortName) {
        Catalog.PackageEntry e = catalog.get(shortName);
        return e == null ? null : e.id;
    }

    /** {@code SHORT} or {@code SHORT_version} → {shortName, version-or-null}. */
    static String[] splitSpec(String spec) {
        int i = spec.indexOf('_');
        if (i < 0) {
            return new String[]{spec, null};
        }
        return new String[]{spec.substring(0, i), spec.substring(i + 1)};
    }
}
