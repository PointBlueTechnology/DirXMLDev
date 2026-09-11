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
     * Give every customized packaged <b>form or PRD</b> touched by a transaction a content-derived
     * {@code dirxml-pkgchecksum} — the value the vault will hold, so Designer's modified test
     * (checksum ≠ the package baseline's) trips and the tree, the vault diff and the Designer
     * writer agree. Artifacts are left alone: their stamp stays the installed checksum and
     * {@code package.status} detects customization by recomputing Designer's recipe
     * ({@code InstalledChecksum}); srvprv objects have no such recipe. Paths are the
     * transaction's touched paths ({@code drivers/<d>/provisioning/forms/<kind>/<name>} /
     * {@code …/prds/<name>}, file-safe names); anything else is ignored.
     */
    static void refreshChecksums(com.pointblue.dirxml.dev.model.DriverSet ds, java.util.Set<String> touched) {
        for (String path : touched) {
            int i = path.indexOf("/provisioning/");
            if (!path.startsWith("drivers/") || i < 0) {
                continue;
            }
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
