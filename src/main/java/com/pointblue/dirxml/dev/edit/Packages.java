package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Package awareness for edits. An artifact that came from a package carries its
 * identity in {@code meta} (the readers record {@code package-id} /
 * {@code pkg-assoc-id} from projects and exports, {@code dirxml-pkg*} from the
 * vault). The first edit to such an artifact keeps its pre-edit content under
 * {@code .package-baseline/<artifact path>} in the tree and marks the artifact
 * {@code package.customized = true} — overriding packaged content is the supported
 * customization method, and this is what makes the override visible and
 * diffable later. The server-side checksum is not computed here (it can't be —
 * Phase 0); the deployer sets it by writing the object.
 */
public final class Packages {

    public static final String BASELINE_DIR = ".package-baseline";
    public static final String CUSTOMIZED_KEY = "package.customized";

    private Packages() {
    }

    /** True if the artifact's meta says a package installed it. */
    public static boolean isPackaged(Artifact a) {
        for (String k : a.meta.keySet()) {
            String lk = k.toLowerCase();
            if (lk.equals("package-id") || lk.equals("pkg-assoc-id") || lk.startsWith("dirxml-pkg")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCustomized(Artifact a) {
        return "true".equals(a.meta.get(CUSTOMIZED_KEY));
    }

    /**
     * Give every customized packaged object touched by a transaction a checksum that
     * matches what the vault and Designer will recompute, so Designer's modified test
     * (checksum ≠ the package baseline's) trips and the tree, the vault and the Designer
     * writer agree.
     *
     * <p><b>Forms and PRDs</b> (no Designer recipe of their own): a content-derived
     * {@code dirxml-pkgchecksum} (CRC32 of the vault content). Paths are the transaction's
     * touched paths ({@code drivers/<d>/provisioning/forms/<kind>/<name>} /
     * {@code …/prds/<name>}, file-safe names).
     *
     * <p><b>Artifacts</b> (policies, resources, GCV objects…): Designer's own installed-content
     * recipe ({@link com.pointblue.dirxml.dev.packages.InstalledChecksum}) — the same one
     * {@code package.status} recomputes to detect customization and the same one
     * {@code PackageInstall} uses to stamp a freshly installed object — so a customized
     * artifact's stamp differs from the package baseline the same way Designer's would.
     * Only an artifact that already carries an installed checksum (stamped by a package)
     * is touched; an unstamped one is handled by the deployer's fallback
     * ({@code VaultMapping#customizedChecksum}). Anything not packaged-and-customized, or
     * not resolvable, is ignored.
     */
    static void refreshChecksums(com.pointblue.dirxml.dev.model.DriverSet ds, java.util.Set<String> touched) {
        for (String path : touched) {
            int i = path.indexOf("/provisioning/");
            int j = path.indexOf("/entitlements/");
            if (path.startsWith("drivers/") && i >= 0) {
                refreshProvisioningChecksum(ds, path, i);
            } else if (path.startsWith("drivers/") && j >= 0) {
                refreshEntitlementChecksum(ds, path, j);
            } else {
                refreshArtifactChecksum(ds, path);
            }
        }
    }

    /** Same recipe as a form/PRD (no Designer checksum recipe of its own): content-derived CRC32. */
    private static void refreshEntitlementChecksum(com.pointblue.dirxml.dev.model.DriverSet ds, String path, int j) {
        String driverSafe = path.substring("drivers/".length(), j);
        String name = path.substring(j + "/entitlements/".length());
        for (com.pointblue.dirxml.dev.model.Driver d : ds.drivers) {
            if (!AsCodeWriter.fileSafe(d.name).equals(driverSafe)) {
                continue;
            }
            for (com.pointblue.dirxml.dev.model.Entitlement e : d.entitlements) {
                if (AsCodeWriter.fileSafe(e.name).equals(name)
                    && e.meta.get("dirxml-pkgguid") != null && "true".equals(e.meta.get(CUSTOMIZED_KEY))) {
                    java.util.List<byte[]> xml = com.pointblue.dirxml.dev.deploy.VaultMapping.entitlementAttributes(e)
                        .get(com.pointblue.dirxml.dev.deploy.VaultMapping.XML_DATA);
                    if (xml != null && !xml.isEmpty()) {
                        e.meta.put("dirxml-pkgchecksum", com.pointblue.dirxml.dev.deploy.VaultMapping.customizedChecksum(xml.get(0)));
                    }
                }
            }
        }
    }

    private static void refreshProvisioningChecksum(com.pointblue.dirxml.dev.model.DriverSet ds, String path, int i) {
        String driverSafe = path.substring("drivers/".length(), i);
        String tail = path.substring(i + "/provisioning/".length());
        for (com.pointblue.dirxml.dev.model.Driver d : ds.drivers) {
            if (d.provisioning == null || !AsCodeWriter.fileSafe(d.name).equals(driverSafe)) {
                continue;
            }
            if (tail.startsWith("forms/")) {
                String[] parts = tail.substring("forms/".length()).split("/", 2);
                for (com.pointblue.dirxml.dev.model.Form f : d.provisioning.forms) {
                    if (f.kind.dir.equals(parts[0]) && AsCodeWriter.fileSafe(f.name).equals(parts[1])
                        && f.meta.get("dirxml-pkgguid") != null && "true".equals(f.meta.get(CUSTOMIZED_KEY))) {
                        f.meta.put("dirxml-pkgchecksum", com.pointblue.dirxml.dev.deploy.VaultMapping.customizedChecksum(
                            com.pointblue.dirxml.dev.deploy.VaultMapping.formBytes(f)));
                    }
                }
            } else if (tail.startsWith("prds/")) {
                String name = tail.substring("prds/".length());
                for (com.pointblue.dirxml.dev.model.Prd p : d.provisioning.prds) {
                    if (AsCodeWriter.fileSafe(p.name).equals(name)
                        && p.meta.get("dirxml-pkgguid") != null && "true".equals(p.meta.get(CUSTOMIZED_KEY))) {
                        java.util.List<byte[]> xml = com.pointblue.dirxml.dev.deploy.VaultMapping.prdAttributes(p)
                            .get(com.pointblue.dirxml.dev.deploy.VaultMapping.XML_DATA);
                        if (xml != null && !xml.isEmpty()) {
                            p.meta.put("dirxml-pkgchecksum", com.pointblue.dirxml.dev.deploy.VaultMapping.customizedChecksum(xml.get(0)));
                        }
                    }
                }
            }
        }
    }

    private static void refreshArtifactChecksum(com.pointblue.dirxml.dev.model.DriverSet ds, String path) {
        Artifact a = ds.resolve(path);
        if (a == null || !isPackaged(a) || !isCustomized(a)) {
            return;
        }
        if (a.meta.get("dirxml-pkgchecksum") == null) {
            return;   // an unstamped packaged artifact: the deployer falls back to a content CRC
        }
        com.pointblue.dirxml.dev.model.Driver owner = a.driver == null ? null : ds.driver(a.driver);
        long recomputed = com.pointblue.dirxml.dev.packages.InstalledChecksum.of(ds, owner, a);
        a.meta.put("dirxml-pkgchecksum", Long.toString(recomputed));
    }

    /** Where the artifact's package baseline lives in a tree. */
    public static Path baselineFile(Path tree, Artifact a) {
        String[] parts = a.path().split("/");
        Path p = tree.resolve(BASELINE_DIR);
        for (String part : parts) {
            p = p.resolve(AsCodeWriter.fileSafe(part));
        }
        return p.resolveSibling(p.getFileName() + AsCodeWriter.extension(a));
    }

    /**
     * Keep the artifact's current content as its package baseline if this is the
     * first customization, and mark it customized. Returns true if the mark was
     * newly set by this call.
     */
    static boolean customize(Path tree, Artifact a, Transaction tx) throws IOException {
        if (!isPackaged(a)) {
            return false;
        }
        boolean newlyMarked = !isCustomized(a);
        Path baseline = baselineFile(tree, a);
        if (!Files.exists(baseline)) {
            String content = currentContent(a);
            if (content != null) {
                tx.pendingBaseline(baseline, content);
            }
        }
        a.meta.put(CUSTOMIZED_KEY, "true");
        return newlyMarked;
    }

    /** The artifact's content as the as-code writer would serialize it (null if none). */
    public static String currentContent(Artifact a) {
        if (a instanceof Policy) {
            Policy p = (Policy) a;
            return p.content == null ? null : CanonicalXml.serialize(p.content);
        }
        Resource r = (Resource) a;
        if (r.isText()) {
            return r.text;
        }
        return r.content == null ? null : CanonicalXml.serialize(r.content);
    }

    /** The baseline content for a customized artifact, or null if none was kept. */
    public static String baseline(Path tree, Artifact a) throws IOException {
        Path f = baselineFile(tree, a);
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }
}
