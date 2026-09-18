package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.VaultMapping;
import com.pointblue.dirxml.dev.edit.ArtifactOps;
import com.pointblue.dirxml.dev.edit.DriverOps;
import com.pointblue.dirxml.dev.edit.GcvOps;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.packages.InstalledChecksum;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProjectWriter} against a copy of the real hand-built Designer project at
 * {@code ~/designer_workspace/test11} (read-only; every test copies it) — never the
 * synthetic fixture {@code ProjectReaderTest} uses, since the writer's contract is
 * about the exact on-disk shape a real project has.
 *
 * <p>Each test builds the "tree" by reading the copy ({@link ProjectReader}), writing
 * it as IDM-as-code ({@code AsCodeWriter}), then applying one edit with the
 * {@code edit} package's operations (the same ones {@code idm} exposes) — never by
 * hand-editing the tree's files — so the diff {@link ProjectWriter} sees is exactly
 * what a real edit session would produce.
 */
public class ProjectWriterTest {

    private static final String TEST11 = "/Users/jcombs/designer_workspace/test11";
    private static final String AMICA =
        "/private/tmp/claude-501/-Users-jcombs-Dev-DirXML-Engine-Analysis/34814343-5cce-492a-8498-a04381e36292"
            + "/scratchpad/amica-prd/AMICA-PRD-20260627";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private int seq;

    // ---- fixture helpers --------------------------------------------------------------

    private Path copyProject() throws IOException {
        return copyDirectory(Paths.get(TEST11), tmp.newFolder("project" + (seq++)).toPath());
    }

    private static Path copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        return dst;
    }

    private Path buildTree(Path project) throws IOException {
        DriverSet ds = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(ds, tree);
        return tree;
    }

    private static Path findFile(Path root, String filename) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equals(filename))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not found under " + root + ": " + filename));
        }
    }

    private static Map<String, String> hashAll(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                out.put(root.relativize(p).toString().replace('\\', '/'), sha256(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Every file not in {@code allowedChangedOrNew}/{@code allowedDeleted} must be byte-identical before/after. */
    private static void assertOnlyChanged(Map<String, String> before, Map<String, String> after,
                                           Set<String> allowedChangedOrNew, Set<String> allowedDeleted) {
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            boolean inBefore = before.containsKey(k);
            boolean inAfter = after.containsKey(k);
            if (inBefore && inAfter) {
                if (!before.get(k).equals(after.get(k))) {
                    assertTrue("unexpected change to untouched file: " + k, allowedChangedOrNew.contains(k));
                }
            } else if (inBefore) {
                assertTrue("unexpected deletion: " + k, allowedDeleted.contains(k));
            } else {
                assertTrue("unexpected creation: " + k, allowedChangedOrNew.contains(k));
            }
        }
    }

    /** Every as-code file of a model, minus reader bookkeeping meta, as one comparable string. */
    private static String asCode(DriverSet ds) throws IOException {
        Path dir = Files.createTempDirectory("idm-pw-ascode");
        AsCodeWriter.write(ds, dir);
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                sb.append("== ").append(dir.relativize(p)).append('\n');
                for (String line : AsCodeRoundTripTest.content(p).split("\n")) {
                    if (line.contains("<meta key=\"")) {
                        continue;   // designer.id / designer.type / dn.synthesized bookkeeping differs on new objects
                    }
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString().replaceAll("(<artifact [^>]*)>\\n\\s*</artifact>", "$1/>");
    }

    // ---- no change ---------------------------------------------------------------------

    @Test
    public void noChangeTouchesNothing() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.changedFiles.isEmpty());
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());

        assertEquals(before, hashAll(project));
    }

    // ---- content change ------------------------------------------------------------------

    @Test
    public void contentChangeRewritesOnlyThatContentsFile() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        String path = "drivers/AcctExpNotif/subscriber/Veto subscriber events";
        run(tree, new ArtifactOps.SetContent(path,
            "<policy><rule><description>updated</description><conditions/><actions><do-veto/></actions></rule></policy>"));

        Path contentsFile = findFile(project, "05WW5M7Y_contents.xml");
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        String expected = project.relativize(contentsFile).toString().replace('\\', '/');
        assertEquals(List.of(expected), r.changedFiles);
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());

        assertOnlyChanged(before, hashAll(project), Set.of(expected), Set.of());

        assertEquals(asCode(AsCodeReader.read(tree)), asCode(ProjectReader.read(project)));
    }

    // ---- add + link ------------------------------------------------------------------------

    @Test
    public void addPolicyAndLinkIntoChannelSet() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        String newPath = "drivers/AcctExpNotif/subscriber/Extra Veto Rule";
        run(tree, new ArtifactOps.Add(Scope.SUBSCRIBER, "AcctExpNotif", "Extra Veto Rule", "policy",
            "<policy><rule><description>extra</description><conditions/><actions><do-veto/></actions></rule></policy>",
            PolicySet.SUB_EVENT, ArtifactOps.Position.last(), null));

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertEquals(1, r.mintedIds.size());
        assertNotNull(r.mintedIds.get(newPath));
        assertEquals(2, r.createdFiles.size());   // <ID>.ScriptPolicy_ + <ID>_contents.xml
        assertEquals(1, r.changedFiles.size());   // the Subscriber_ CObject (relations updated)
        assertTrue(r.deletedFiles.isEmpty());

        Set<String> allowed = new HashSet<>(r.createdFiles);
        allowed.addAll(r.changedFiles);
        assertOnlyChanged(before, hashAll(project), allowed, Set.of());

        DriverSet reread = ProjectReader.read(project);
        Driver d = reread.driver("AcctExpNotif");
        List<String> refs = new ArrayList<>();
        d.links(PolicySet.SUB_EVENT).forEach(l -> refs.add(l.ref));
        assertTrue(refs.contains(newPath));
        assertTrue(reread.unresolvedLinks().stream().noneMatch(l -> l.ref.equals(newPath)));
        assertNotNull(reread.resolve(newPath));

        assertEquals(asCode(AsCodeReader.read(tree)), asCode(reread));
    }

    // ---- remove (after unlinking) ------------------------------------------------------------

    @Test
    public void removePolicyDeletesFilesAndAllReferences() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        String path = "drivers/AcctExpNotif/publisher/Send expiration email";
        run(tree, new ArtifactOps.Delete(path, true));

        Path metaFile = findFile(project, "70YC68YW.ScriptPolicy_");
        Path contentsFile = findFile(project, "70YC68YW_contents.xml");
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertEquals(2, r.deletedFiles.size());
        assertFalse(Files.exists(metaFile));
        assertFalse(Files.exists(contentsFile));

        try (Stream<Path> s = Files.walk(project)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                // ISO-8859-1: some files under the project (icons, etc.) aren't valid UTF-8; a byte-preserving
                // decode is all a plain substring search needs.
                String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                assertFalse(p.toString(), text.contains("70YC68YW.ScriptPolicy_"));
            }
        }

        Set<String> allowedChangedOrNew = new HashSet<>(r.changedFiles);
        assertOnlyChanged(before, hashAll(project), allowedChangedOrNew, new HashSet<>(r.deletedFiles));

        DriverSet reread = ProjectReader.read(project);
        assertNull(reread.resolve(path));
    }

    // ---- rename ------------------------------------------------------------------------------

    @Test
    public void renameKeepsIdAndLeavesContentUntouched() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        String oldPath = "drivers/AcctExpNotif/publisher/Hoist operation-data";
        String newName = "Hoist operation-data v2";
        run(tree, new ArtifactOps.Rename(oldPath, newName));

        Path metaFile = findFile(project, "4YE87XRB.ScriptPolicy_");
        Path contentsFile = findFile(project, "4YE87XRB_contents.xml");
        String contentsBefore = Files.readString(contentsFile, StandardCharsets.UTF_8);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.mintedIds.isEmpty());
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());
        assertEquals(1, r.changedFiles.size());

        assertEquals(contentsBefore, Files.readString(contentsFile, StandardCharsets.UTF_8));
        String metaAfter = Files.readString(metaFile, StandardCharsets.UTF_8);
        assertTrue(metaAfter, metaAfter.contains("name=\"" + newName + "\""));

        DriverSet reread = ProjectReader.read(project);
        assertNotNull(reread.resolve("drivers/AcctExpNotif/publisher/" + newName));
        assertNull(reread.resolve(oldPath));
    }

    // ---- customized packaged policy -----------------------------------------------------------

    @Test
    public void customizedPackagedPolicySetsContentChecksum() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        String path = "drivers/Data Collection Service Driver/NOVLIDMDCSB-otp-ReformatMemberQuery";
        Path metaFile = findFile(project, "4PRD2MRS.ScriptPolicy_");

        DriverSet before = ProjectReader.read(project);
        Artifact a = before.resolve(path);
        assertNotNull(a);
        assertTrue(Packages.isPackaged(a));
        String originalChecksum = com.pointblue.dirxml.dev.model.PackageStamps.checksum(a.meta);
        assertNotNull(originalChecksum);

        String newContent = "<policy><rule><description>customized</description><conditions/><actions/></rule></policy>";
        Result opResult = Transaction.open(tree).run(new ArtifactOps.SetContent(path, newContent), false, false);
        assertTrue(opResult.text(), opResult.ok());
        assertEquals(List.of(path), opResult.customized);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertEquals(2, r.changedFiles.size());   // the contents file + the CObject's Idm:ContentChecksum

        String metaAfter = Files.readString(metaFile, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("Idm:ContentChecksum\" value=\"(\\d+)\"").matcher(metaAfter);
        assertTrue(metaAfter, m.find());
        String checksum = m.group(1);
        // follow-up 2 (docs/vault-deploy.md, package stamps of customized objects): the project's stamp is
        // now Designer's own installed-content recipe (InstalledChecksum) — the same one the vault stamp and
        // package.status use — not the CRC this writer used to compute; a customized artifact's checksum in
        // the tree, the vault and the project must all agree.
        DriverSet treeAfter = AsCodeReader.read(tree);
        Artifact treeArtifact = treeAfter.resolve(path);
        assertNotNull(treeArtifact);
        Driver owner = treeArtifact.driver == null ? null : treeAfter.driver(treeArtifact.driver);
        String expected = Long.toString(InstalledChecksum.of(treeAfter, owner, treeArtifact));
        assertEquals(expected, checksum);
        assertNotEquals(originalChecksum, checksum);

        assertTrue(metaAfter.contains("Idm:PackageGuid"));
        assertTrue(metaAfter.contains("Idm:PackageAssocGuid"));
    }

    // ---- linkage reorder -----------------------------------------------------------------------

    @Test
    public void linkageReorderChangesOnlyTheOwningRelations() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        DriverSet treeDsBefore = AsCodeReader.read(tree);
        List<String> current = new ArrayList<>();
        treeDsBefore.driver("AcctExpNotif").links(PolicySet.PUB_EVENT).forEach(l -> current.add(l.ref));
        assertEquals(2, current.size());
        List<String> reversed = new ArrayList<>(current);
        Collections.reverse(reversed);
        assertNotEquals(current, reversed);

        run(tree, new ArtifactOps.Reorder("AcctExpNotif", PolicySet.PUB_EVENT, reversed));

        Path pubMetaFile = findFile(project, "POEJUE0Y.Publisher_");
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        String expected = project.relativize(pubMetaFile).toString().replace('\\', '/');
        assertEquals(List.of(expected), r.changedFiles);
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());

        assertOnlyChanged(before, hashAll(project), Set.of(expected), Set.of());

        DriverSet reread = ProjectReader.read(project);
        List<String> rereadOrder = new ArrayList<>();
        reread.driver("AcctExpNotif").links(PolicySet.PUB_EVENT).forEach(l -> rereadOrder.add(l.ref));
        assertEquals(reversed, rereadOrder);
    }

    // ---- GCV value change ------------------------------------------------------------------------

    @Test
    public void gcvValueChangeRewritesDriverConfigValuesFile() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        run(tree, new GcvOps.Set("Querytest", "driver.field.delimiter", "~", null, null));

        Path cvFile = findFile(project, "3CZ0ZBBA_1TETWT10_DirXML-ConfigValues.xml");
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        String expected = project.relativize(cvFile).toString().replace('\\', '/');
        assertEquals(List.of(expected), r.changedFiles);

        assertOnlyChanged(before, hashAll(project), Set.of(expected), Set.of());
        assertTrue(Files.readString(cvFile, StandardCharsets.UTF_8).contains("<value>~</value>"));
    }

    // ---- driver add: packaged refused, blank written -------------------------------------------

    @Test
    public void packagedDriverAddedIsRefused() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        // test11's "Data Collection Service Driver" already has pre-existing broken ECMAScript linkage
        // (a package quirk unrelated to this test); force the clone through so we have a packaged driver
        // in the tree to test the writer's refusal against.
        Result setup = Transaction.open(tree).run(
            new DriverOps.Add("ClonedPkgDriver", null, "Data Collection Service Driver", true, null, null, null),
            false, true);
        assertTrue(setup.text(), setup.written);

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertFalse(r.ok);
        assertNotNull(r.refusal);
        assertTrue(r.refusal, r.refusal.contains("ClonedPkgDriver"));
        assertEquals(before, hashAll(project));
    }

    @Test
    public void nonPackagedBlankDriverAddedIsWritten() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        run(tree, new DriverOps.Add("BlankDriver", null, null, false, "com.example.idm.BlankShim", null, null));

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertFalse(r.createdFiles.isEmpty());
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("BlankDriver") && n.contains("spike 6a")));

        DriverSet reread = ProjectReader.read(project);
        Driver d = reread.driver("BlankDriver");
        assertNotNull(d);
        assertEquals("com.example.idm.BlankShim", d.shimClass);
    }

    // ---- driver icon ----------------------------------------------------------------------------

    /** {@code AcctExpNotif}'s CObject id in test11 — the driver whose icon these tests move around. */
    private static final String ACCT_EXP_ID = "8G96HRHC";
    private static final String ICON_ATTR =
        "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"icon\" extension=\"gif\"/>";

    private static Path projectIcon(Path project, String ext) {
        return project.resolve("Model/EdirOrphan/ZEZTZUKV").resolve(ACCT_EXP_ID + "_icon." + ext);
    }

    @Test
    public void aChangedIconRewritesOnlyTheIconFile() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        Path treeIcon = tree.resolve("drivers/AcctExpNotif/icon.gif");
        assertTrue("the tree must carry the project's icon", Files.exists(treeIcon));
        Files.write(treeIcon, AsCodeRoundTripTest.TINY_GIF);

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);

        String expected = project.relativize(projectIcon(project, "gif")).toString().replace('\\', '/');
        assertEquals(List.of(expected), r.changedFiles);
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());
        // the CObject already said extension="gif": new bytes give it no reason to be rewritten
        assertOnlyChanged(before, hashAll(project), Set.of(expected), Set.of());
        assertArrayEquals(AsCodeRoundTripTest.TINY_GIF,
            ProjectReader.read(project).driver("AcctExpNotif").icon);
    }

    /**
     * A tree without an icon has no opinion (a tree written before icons were carried, or one
     * imported from an LDIF that lacked the attribute): the project's icon stays as it is.
     */
    @Test
    public void aTreeWithoutAnIconLeavesTheProjectIconAlone() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        byte[] before = ProjectReader.read(project).driver("AcctExpNotif").icon;
        assertNotNull(before);
        Files.delete(tree.resolve("drivers/AcctExpNotif/icon.gif"));
        Path manifest = tree.resolve("drivers/AcctExpNotif/driver.xml");
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
            .replaceAll("\n *<icon file=\"icon\\.gif\"/>", ""), StandardCharsets.UTF_8);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(Files.exists(projectIcon(project, "gif")));
        assertEquals(List.of(), r.deletedFiles);
        String driverFile = Files.readString(
            project.resolve("Model/EdirOrphan/ZEZTZUKV/" + ACCT_EXP_ID + ".Driver_"), StandardCharsets.UTF_8);
        assertTrue(driverFile, driverFile.contains("attrName=\"icon\""));
        assertArrayEquals(before, ProjectReader.read(project).driver("AcctExpNotif").icon);
    }

    /** A driver Designer never drew an icon for gets the tree's, attribute and file together. */
    @Test
    public void anIconTheProjectLacksIsAdded() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);                      // the tree keeps the icon …
        Path meta = project.resolve("Model/EdirOrphan/ZEZTZUKV/" + ACCT_EXP_ID + ".Driver_");
        String stripped = Files.readString(meta, StandardCharsets.UTF_8).replace(ICON_ATTR, "");
        assertFalse("the icon attribute must really have been there", stripped.contains("attrName=\"icon\""));
        Files.writeString(meta, stripped, StandardCharsets.UTF_8);
        Files.delete(projectIcon(project, "gif"));           // … which the project no longer has

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.createdFiles.toString(), r.createdFiles.stream().anyMatch(f -> f.endsWith("_icon.gif")));
        Driver back = ProjectReader.read(project).driver("AcctExpNotif");
        assertNotNull(back.icon);
        assertEquals("gif", back.iconExtension);
        assertArrayEquals(Files.readAllBytes(tree.resolve("drivers/AcctExpNotif/icon.gif")), back.icon);
    }

    /** A different image format replaces the old file rather than leaving two behind. */
    @Test
    public void aChangedIconFormatReplacesTheFile() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        Files.delete(tree.resolve("drivers/AcctExpNotif/icon.gif"));
        Files.write(tree.resolve("drivers/AcctExpNotif/icon.png"), AsCodeRoundTripTest.TINY_GIF);
        Path manifest = tree.resolve("drivers/AcctExpNotif/driver.xml");
        Files.writeString(manifest, Files.readString(manifest, StandardCharsets.UTF_8)
            .replace("<icon file=\"icon.gif\"/>", "<icon file=\"icon.png\"/>"), StandardCharsets.UTF_8);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertFalse(Files.exists(projectIcon(project, "gif")));
        assertTrue(Files.exists(projectIcon(project, "png")));
        Driver back = ProjectReader.read(project).driver("AcctExpNotif");
        assertEquals("png", back.iconExtension);
        assertArrayEquals(AsCodeRoundTripTest.TINY_GIF, back.icon);
    }

    // ---- dry run --------------------------------------------------------------------------------

    @Test
    public void dryRunComputesButWritesNothing() throws IOException {
        Path project = copyProject();
        Path tree = buildTree(project);
        run(tree, new ArtifactOps.Add(Scope.SUBSCRIBER, "AcctExpNotif", "DryRunPolicy", "policy", null,
            PolicySet.SUB_EVENT, ArtifactOps.Position.last(), null));

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, true);
        assertTrue(r.text(), r.ok);
        assertFalse(r.createdFiles.isEmpty());
        assertFalse(r.changedFiles.isEmpty());
        assertEquals(before, hashAll(project));
    }

    // ---- Amica (guarded) --------------------------------------------------------------------------

    @Test
    public void amicaNoChangeTouchesNothing() throws IOException {
        Assume.assumeTrue(Files.isDirectory(Paths.get(AMICA)));
        Path project = copyDirectory(Paths.get(AMICA), tmp.newFolder("amica" + (seq++)).toPath());
        Path tree = buildTree(project);
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.changedFiles.isEmpty());
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());
        assertEquals(before, hashAll(project));
    }

    @Test
    public void amicaOneContentChangeChangesOneFile() throws IOException {
        Assume.assumeTrue(Files.isDirectory(Paths.get(AMICA)));
        Path project = copyDirectory(Paths.get(AMICA), tmp.newFolder("amica" + (seq++)).toPath());
        DriverSet ds = ProjectReader.read(project);
        Policy target = findUnpackagedPolicy(ds);
        Path tree = buildTree(project);
        run(tree, new ArtifactOps.SetContent(target.path(),
            "<policy><rule><description>amica writer test change</description><conditions/><actions/></rule></policy>"));

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertEquals(1, r.changedFiles.size());
        assertOnlyChanged(before, hashAll(project), new HashSet<>(r.changedFiles), Set.of());
    }

    private static Policy findUnpackagedPolicy(DriverSet ds) {
        for (Policy p : ds.library.policies) {
            if (!Packages.isPackaged(p) && p.content != null) {
                return p;
            }
        }
        for (Driver d : ds.drivers) {
            for (Policy p : d.policies) {
                if (!Packages.isPackaged(p) && p.content != null) {
                    return p;
                }
            }
            for (Policy p : d.subscriber.policies) {
                if (!Packages.isPackaged(p) && p.content != null) {
                    return p;
                }
            }
            for (Policy p : d.publisher.policies) {
                if (!Packages.isPackaged(p) && p.content != null) {
                    return p;
                }
            }
        }
        throw new AssertionError("no unpackaged policy with content found in the Amica project");
    }

    // ---- helper --------------------------------------------------------------------------------

    private static void run(Path tree, com.pointblue.dirxml.dev.edit.Operation op) throws IOException {
        Result r = Transaction.open(tree).run(op, false, false);
        assertTrue(r.text(), r.ok() && r.written);
    }
}
