package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.ArtifactOps;
import com.pointblue.dirxml.dev.edit.DriverOps;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.source.ProjectReader;
import org.junit.Assume;
import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code package.uninstall} / {@code package.upgrade} against the same catalog
 * jars {@code PackageInstallTest} proves parity with (spike 7b): a fresh
 * eDirectory driver built from NOVLEDIRBASE + NOVLEDIRDCFG + NOVLPWDSYNC +
 * NOVLEDIRPSYN, then uninstalled and upgraded the way Designer would
 * (docs/packages.md §3.3, docs/spikes/designer-package-layer.md §4). Guarded
 * on the same local Designer catalog and test11 project as
 * {@code PackageInstallTest}.
 */
public class PackageLifecycleTest {

    static final Path CATALOG = Paths.get("/Applications/Designer/packages/eclipse/plugins");
    static final Path TEST11 = Paths.get(System.getProperty("user.home"), "designer_workspace", "test11");

    static final String NOVLEDIRBASE = "NOVLEDIRBASE_2.1.2.20190219130306";
    static final String NOVLEDIRDCFG_OLD = "NOVLEDIRDCFG_1.0.1";
    static final String NOVLEDIRDCFG_NEW = "NOVLEDIRDCFG_2.1.0.20120831225140";
    static final String NOVLPWDSYNC = "NOVLPWDSYNC_2.1.2.20190806140123";
    static final String NOVLEDIRPSYN = "NOVLEDIRPSYN_1.0.0";

    private static void assumeCatalog() {
        Assume.assumeTrue(Files.isDirectory(CATALOG) && Files.isDirectory(TEST11));
    }

    /** A DirXMLDev catalog directory seeded (via {@link Catalog#add}) with the given SHORT_version jars from Designer's local catalog. */
    private Path buildCatalog(String... shortVersions) throws Exception {
        Path dir = Files.createTempDirectory("p7lc-catalog");
        Catalog catalog = Catalog.open(dir);
        for (String sv : shortVersions) {
            Path jar = CATALOG.resolve(sv + ".jar");
            Assume.assumeTrue(Files.exists(jar));
            Catalog.AddResult r = catalog.add(jar, "designer:" + jar);
            assertTrue(sv + ": " + r.refusal, r.ok());
        }
        return dir;
    }

    /** The answers test11's real eDirectory driver was configured with (the same recipe {@code PackageInstallTest} uses). */
    private Map<String, String> answers() throws Exception {
        DriverSet ref = ProjectReader.read(TEST11);
        Driver designer = ref.driver("eDir2eDirJFWOld");
        assertNotNull(designer);
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("shim-auth-server", "172.17.2.112:8192");
        for (Artifact a : designer.artifacts()) {
            if (a instanceof Resource && ((Resource) a).isGcvDef()) {
                for (Element def : PromptEngine.definitions(((Resource) a).content)) {
                    Element v = PromptEngine.child(def, "value");
                    String text = v == null ? null : PromptEngine.text(v);
                    if (text != null && !text.isBlank()) {
                        answers.putIfAbsent(def.getAttribute("name"), text.trim());
                    }
                }
            }
        }
        return answers;
    }

    /**
     * A tree seeded with test11's own driver set — its Library carries the
     * driver-set-level GCVs (e.g. {@code idv.dit.data.users}) NOVLEDIRDCFG's
     * policies reference, exactly as {@code PackageInstallTest} sets up (a
     * brand-new, empty Library would leave those undefined and fail validation).
     * A new driver is then added into it for each scenario, so scenarios don't
     * interfere with each other.
     */
    private Path freshTree() throws Exception {
        Path tree = Files.createTempDirectory("p7lc-tree");
        AsCodeWriter.write(ProjectReader.read(TEST11), tree);
        return tree;
    }

    private Result installFour(Path tree, Path catalogDir, String driverName, Map<String, String> answers) throws Exception {
        List<Path> jars = new ArrayList<>();
        for (String sv : List.of(NOVLEDIRBASE, NOVLEDIRDCFG_NEW, NOVLPWDSYNC, NOVLEDIRPSYN)) {
            jars.add(PackageInstall.jarOf(null, catalogDir.toString(), sv));
        }
        return Transaction.open(tree).run(new DriverOps.Add(driverName, null, null, false, null, null, null)
            .withPackages(jars, answers), false, false);
    }

    /** Every current link of {@code shortName}'s objects, by policy set (for comparing before/after an unrelated uninstall). */
    private Map<PolicySet, List<String>> linksOfPackage(DriverSet ds, Driver d, String shortName) {
        String rec = d.meta.get(PackageInstall.META_INSTALLED_PREFIX + shortName);
        String id = PackageStatus.parse(rec).id;
        Set<String> paths = new LinkedHashSet<>();
        for (Artifact a : ds.index().values()) {
            if (id.equals(a.meta.get(PackageInstall.META_PACKAGE_ID))) {
                paths.add(a.path());
            }
        }
        Map<PolicySet, List<String>> out = new LinkedHashMap<>();
        for (PolicySet set : PolicySet.values()) {
            List<String> refs = new ArrayList<>();
            for (PolicyLink l : d.links(set)) {
                if (paths.contains(l.ref)) {
                    refs.add(l.ref);
                }
            }
            if (!refs.isEmpty()) {
                out.put(set, refs);
            }
        }
        return out;
    }

    private static String sha256(byte[] b) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static Map<String, String> hashFiles(Path root, Predicate<String> exclude) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (var s = Files.walk(root)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (!exclude.test(rel)) {
                    out.put(rel, sha256(Files.readAllBytes(f)));
                }
            }
        }
        return out;
    }

    // ======================================================================
    // uninstall
    // ======================================================================

    /**
     * Reasoning (documented per the task's instruction to explain the uninstall
     * of a linking package, and to decide from research whether Designer removes
     * those links): reading NOVLPWDSYNC's own jar shows every one of its items
     * carries an <em>empty</em> {@code <policy-linkage/>} of its own — placement
     * only, no linkage. The only thing that ever links ten of NOVLPWDSYNC's
     * policies into the driver's input/output/publisher-command/subscriber-command
     * sets is NOVLEDIRPSYN's <b>package-level</b> {@code <policy-linkage>} (its
     * directive names them by {@code pkg-assoc-id}, alongside its declared
     * {@code <dependency package-id="…NOVLPWDSYNC's id…">}). Only NOVLPWDSYNC's
     * own {@code NOVLPWDSYNC-GCVs} object is linked by NOVLPWDSYNC's own per-item
     * directive (into the {@code gcv} set).
     *
     * <p>docs/spikes/designer-package-layer.md §4.1 says removing a package does
     * "package-level unlink" first — undoing exactly the links <em>that
     * package's own directive</em> made — and §2.4 says a link "owned by another
     * installed package's directive is kept". Since NOVLEDIRPSYN's package-level
     * directive is the sole owner of those ten links, uninstalling NOVLEDIRPSYN
     * removes them: NOVLPWDSYNC's policies stay installed (their package remains,
     * unaffected) but become unlinked from those sets — orphaned until someone
     * relinks them (e.g. re-running {@code package.install} for NOVLEDIRPSYN, or
     * a future NOVLPWDSYNC version that links them itself). Only the GCV link,
     * which NOVLPWDSYNC's own directive owns, survives. This is what the test
     * below confirms; an earlier draft of it assumed the opposite (that
     * NOVLPWDSYNC's own per-item directives had already linked those same items,
     * making NOVLEDIRPSYN's package-level linkage a no-op) — the jar shows that
     * assumption was wrong for this package pair.
     */
    @Test
    public void uninstallLinkingPackageDropsOnlyTheLinksItOwnsAndTouchesNothingElse() throws Exception {
        assumeCatalog();
        Path catalogDir = buildCatalog(NOVLEDIRBASE, NOVLEDIRDCFG_NEW, NOVLPWDSYNC, NOVLEDIRPSYN);
        Path tree = freshTree();
        Map<String, String> answers = answers();
        Result installed = installFour(tree, catalogDir, "eDirU1", answers);
        assertTrue(installed.text(), installed.ok());

        DriverSet before = AsCodeReader.read(tree);
        Driver dBefore = before.driver("eDirU1");
        assertNotNull(dBefore.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRPSYN"));
        Map<PolicySet, List<String>> pwdsyncLinksBefore = linksOfPackage(before, dBefore, "NOVLPWDSYNC");
        // NOVLEDIRPSYN's package-level linkage put ten NOVLPWDSYNC policies into four sets, plus NOVLPWDSYNC's
        // own GCV link — five sets total (see the reasoning above)
        assertEquals(java.util.Set.of(PolicySet.INPUT, PolicySet.OUTPUT, PolicySet.PUB_COMMAND, PolicySet.SUB_COMMAND, PolicySet.GCV),
            pwdsyncLinksBefore.keySet());
        Map<String, String> hashesBefore = hashFiles(tree, p -> false);

        Result r = Transaction.open(tree).run(new PackageUninstall("eDirU1", "NOVLEDIRPSYN", false, false, catalogDir), false, true);
        assertTrue(r.text(), r.ok());

        DriverSet after = AsCodeReader.read(tree);
        Driver d = after.driver("eDirU1");
        for (Artifact a : d.artifacts()) {
            assertFalse("NOVLEDIRPSYN object survived: " + a.path(), a.name.startsWith("NOVLEDIRPSYN"));
        }
        assertNull(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRPSYN"));
        for (PolicyLink l : d.links) {
            assertFalse("a link still names NOVLEDIRPSYN", l.ref.contains("NOVLEDIRPSYN"));
        }
        // NOVLPWDSYNC itself is untouched (still installed, its own objects intact) but the links
        // NOVLEDIRPSYN's package-level directive owned are gone; only its own GCV link (its own directive's) remains
        assertNotNull(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLPWDSYNC"));
        Map<PolicySet, List<String>> pwdsyncLinksAfter = linksOfPackage(after, d, "NOVLPWDSYNC");
        assertEquals(Map.of(PolicySet.GCV, pwdsyncLinksBefore.get(PolicySet.GCV)), pwdsyncLinksAfter);
        for (Artifact a : d.artifacts()) {
            if (a.name.startsWith("NOVLPWDSYNC")) {
                assertNotNull("NOVLPWDSYNC object removed: " + a.path(), a);   // still present, just possibly unlinked
            }
        }

        // no other file changed: everything not reported as changed/deleted is byte-identical
        Set<String> expectedChanges = new HashSet<>(r.changedFiles);
        expectedChanges.addAll(r.deletedFiles);
        Map<String, String> hashesBeforeFiltered = new TreeMap<>(hashesBefore);
        hashesBeforeFiltered.keySet().removeAll(expectedChanges);
        Map<String, String> hashesAfter = hashFiles(tree, expectedChanges::contains);
        assertEquals(hashesBeforeFiltered, hashesAfter);
    }

    @Test
    public void uninstallDependencyIsRefusedThenAllRemovesBoth() throws Exception {
        assumeCatalog();
        Path catalogDir = buildCatalog(NOVLEDIRBASE, NOVLEDIRDCFG_NEW, NOVLPWDSYNC, NOVLEDIRPSYN);
        Path tree = freshTree();
        Result installed = installFour(tree, catalogDir, "eDirU2", answers());
        assertTrue(installed.text(), installed.ok());

        Result refused = Transaction.open(tree).run(new PackageUninstall("eDirU2", "NOVLPWDSYNC", false, false, catalogDir), false, false);
        assertFalse(refused.ok());
        assertTrue(refused.refusal, refused.refusal.contains("NOVLEDIRPSYN"));

        Result both = Transaction.open(tree).run(new PackageUninstall("eDirU2", "NOVLPWDSYNC", false, true, catalogDir), false, true);
        assertTrue(both.text(), both.ok());
        DriverSet after = AsCodeReader.read(tree);
        Driver d = after.driver("eDirU2");
        assertNull(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLPWDSYNC"));
        assertNull(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRPSYN"));
        for (Artifact a : d.artifacts()) {
            assertFalse(a.name.startsWith("NOVLPWDSYNC") || a.name.startsWith("NOVLEDIRPSYN"));
        }
        // the base and NOVLEDIRDCFG remain
        assertNotNull(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRBASE"));
        assertNotNull(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRDCFG"));
    }

    @Test
    public void uninstallBaseWithFeaturesIsRefusedThenAllEmptiesTheDriver() throws Exception {
        assumeCatalog();
        Path catalogDir = buildCatalog(NOVLEDIRBASE, NOVLEDIRDCFG_NEW, NOVLPWDSYNC, NOVLEDIRPSYN);
        Path tree = freshTree();
        Result installed = installFour(tree, catalogDir, "eDirU3", answers());
        assertTrue(installed.text(), installed.ok());

        Result refused = Transaction.open(tree).run(new PackageUninstall("eDirU3", "NOVLEDIRBASE", false, false, catalogDir), false, false);
        assertFalse(refused.ok());

        Result all = Transaction.open(tree).run(new PackageUninstall("eDirU3", "NOVLEDIRBASE", false, true, catalogDir), false, true);
        assertTrue(all.text(), all.ok());

        DriverSet after = AsCodeReader.read(tree);
        Driver d = after.driver("eDirU3");
        assertNotNull(d);   // the driver itself survives; only its packaged content is gone
        assertTrue(d.artifacts().isEmpty());
        assertTrue(d.links.isEmpty());
        for (String k : d.meta.keySet()) {
            assertFalse(k, k.startsWith(PackageInstall.META_INSTALLED_PREFIX));
        }
        assertNull(d.meta.get(PackageInstall.META_GUID));
    }

    @Test
    public void dryRunUninstallWritesNothing() throws Exception {
        assumeCatalog();
        Path catalogDir = buildCatalog(NOVLEDIRBASE, NOVLEDIRDCFG_NEW, NOVLPWDSYNC, NOVLEDIRPSYN);
        Path tree = freshTree();
        Result installed = installFour(tree, catalogDir, "eDirU4", answers());
        assertTrue(installed.text(), installed.ok());
        Map<String, String> before = hashFiles(tree, p -> false);

        Result dry = Transaction.open(tree).run(new PackageUninstall("eDirU4", "NOVLEDIRPSYN", false, false, catalogDir), true, true);
        assertTrue(dry.text(), dry.ok());
        assertFalse(dry.written);
        assertFalse("a dry run should still report what it would change", dry.deletedFiles.isEmpty());

        Map<String, String> after = hashFiles(tree, p -> false);
        assertEquals(before, after);
    }

    // ======================================================================
    // upgrade / downgrade
    // ======================================================================

    @Test
    public void upgradeKeepsCustomizedContentAndMovesStampsForward() throws Exception {
        assumeCatalog();
        Path catalogDir = buildCatalog(NOVLEDIRBASE, NOVLEDIRDCFG_OLD, NOVLEDIRDCFG_NEW);
        Path tree = freshTree();
        Map<String, String> answers = answers();
        List<Path> jars = List.of(
            PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRBASE),
            PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRDCFG_OLD));
        Result installed = Transaction.open(tree).run(new DriverOps.Add("eDirUpg", null, null, false, null, null, null)
            .withPackages(jars, answers), false, false);
        assertTrue(installed.text(), installed.ok());

        String customPath = "drivers/eDirUpg/publisher/NOVLEDIRDCFG-pub-mp-Scoping";
        String customContent = "<policy><rule><description>customized scoping</description><conditions/><actions/></rule></policy>";
        Result customize = Transaction.open(tree).run(new ArtifactOps.SetContent(customPath, customContent), false, false);
        assertTrue(customize.text(), customize.ok());
        assertEquals(List.of(customPath), customize.customized);

        DriverSet before = AsCodeReader.read(tree);
        Artifact scopingBefore = before.resolve(customPath);
        assertTrue(Packages.isCustomized(scopingBefore));
        String oldChecksum = scopingBefore.meta.get(PackageInstall.META_CHECKSUM);
        assertNotNull(oldChecksum);

        Path newJar = PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRDCFG_NEW);
        Result upgrade = Transaction.open(tree).run(new PackageUpgrade("eDirUpg", newJar, answers, false, false, catalogDir), false, true);
        assertTrue(upgrade.text(), upgrade.ok());

        DriverSet after = AsCodeReader.read(tree);
        Driver d = after.driver("eDirUpg");

        // the customized policy keeps its customized content and its mark
        Artifact scoping = after.resolve(customPath);
        assertNotNull(scoping);
        assertTrue(Packages.isCustomized(scoping));
        String scopingContent = Packages.currentContent(scoping);
        assertTrue(scopingContent, scopingContent.contains("customized scoping"));
        // its baseline now equals the new package's content (not the customization, not the old package's content)
        String baseline = Packages.baseline(tree, scoping);
        assertNotNull(baseline);
        assertFalse(baseline.contains("customized scoping"));
        // its stamp names the new version and was recomputed (differs from the pre-upgrade stamp)
        assertTrue(scoping.meta.get(PackageInstall.META_GUID), scoping.meta.get(PackageInstall.META_GUID).contains("2.1.0.20120831225140"));
        assertNotEquals(oldChecksum, scoping.meta.get(PackageInstall.META_CHECKSUM));

        // non-customized objects: new content and stamps, checksum recomputed from the new state
        Artifact pubPp = after.resolve("drivers/eDirUpg/publisher/NOVLEDIRDCFG-pub-pp");
        assertNotNull(pubPp);
        assertFalse(Packages.isCustomized(pubPp));
        assertEquals("" + InstalledChecksum.of(after, d, pubPp), pubPp.meta.get(PackageInstall.META_CHECKSUM));
        assertTrue(pubPp.meta.get(PackageInstall.META_GUID).contains("2.1.0.20120831225140"));

        Artifact pubMp = after.resolve("drivers/eDirUpg/publisher/NOVLEDIRDCFG-pub-mp");
        assertNotNull(pubMp);
        assertFalse(Packages.isCustomized(pubMp));
        assertEquals("" + InstalledChecksum.of(after, d, pubMp), pubMp.meta.get(PackageInstall.META_CHECKSUM));

        // the manifest record names the new version
        assertTrue(d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRDCFG"),
            d.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRDCFG").contains("2.1.0.20120831225140"));

        // package.status reports exactly the customized one
        PackageStatus st = PackageStatus.of(tree, "eDirUpg", null);
        assertEquals(1, st.targets.size());
        assertEquals(List.of(customPath), st.targets.get(0).customized);

        // upgrading to the same version is refused
        Result same = Transaction.open(tree).run(new PackageUpgrade("eDirUpg", newJar, answers, false, false, catalogDir), true, false);
        assertFalse(same.ok());

        // dry-run of the (still pending) downgrade writes nothing
        Path oldJar = PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRDCFG_OLD);
        Map<String, String> beforeDowngrade = hashFiles(tree, p -> false);
        Result dryDowngrade = Transaction.open(tree).run(new PackageUpgrade("eDirUpg", oldJar, answers, true, true, catalogDir), true, true);
        assertTrue(dryDowngrade.text(), dryDowngrade.ok());
        assertFalse(dryDowngrade.written);
        assertEquals(beforeDowngrade, hashFiles(tree, p -> false));

        // downgrade back is allowed, with a note
        Result down = Transaction.open(tree).run(new PackageUpgrade("eDirUpg", oldJar, answers, true, true, catalogDir), false, true);
        assertTrue(down.text(), down.ok());
        assertTrue(down.notes.toString(), down.notes.stream().anyMatch(n -> n.contains("downgraded")));
        DriverSet afterDown = AsCodeReader.read(tree);
        assertTrue(afterDown.driver("eDirUpg").meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRDCFG").contains(";1.0.1;"));
    }

    @Test
    public void dryRunUpgradeWritesNothing() throws Exception {
        assumeCatalog();
        Path catalogDir = buildCatalog(NOVLEDIRBASE, NOVLEDIRDCFG_OLD, NOVLEDIRDCFG_NEW);
        Path tree = freshTree();
        Map<String, String> answers = answers();
        List<Path> jars = List.of(
            PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRBASE),
            PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRDCFG_OLD));
        Result installed = Transaction.open(tree).run(new DriverOps.Add("eDirUpgDry", null, null, false, null, null, null)
            .withPackages(jars, answers), false, false);
        assertTrue(installed.text(), installed.ok());

        Map<String, String> before = hashFiles(tree, p -> false);
        Path newJar = PackageInstall.jarOf(null, catalogDir.toString(), NOVLEDIRDCFG_NEW);
        Result dry = Transaction.open(tree).run(new PackageUpgrade("eDirUpgDry", newJar, answers, false, false, catalogDir), true, true);
        assertTrue(dry.text(), dry.ok());
        assertFalse(dry.written);
        assertFalse(dry.changedFiles.isEmpty());
        assertEquals(before, hashFiles(tree, p -> false));
    }
}
