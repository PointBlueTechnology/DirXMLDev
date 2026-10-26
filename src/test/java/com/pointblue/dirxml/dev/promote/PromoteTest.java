package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Two projects, QA and production, each its own tree: QA calls the driver "AD QA" and lives under another driver set
 * DN on its own server; production calls it "AD". The pair's map lines them up, values stay apart, and the record
 * sorts each difference by which side changed it.
 */
public class PromoteTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PROD_SERVER = "cn=vault01,ou=servers,o=system";
    private static final String QA_SERVER = "cn=vault01qa,ou=servers,o=qa";
    private static final String POLICY = "drivers/AD/subscriber/sub-ctp.policy.xml";
    private static final String MANIFEST = "drivers/AD/driver.xml";

    private static String rule(String description) {
        return "<policy><rule><description>" + description + "</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-text>1</token-text></arg-string></do-set-local-variable>"
            + "</actions></rule></policy>";
    }

    private static DriverSet prod() {
        DriverSet ds = ValidatorTest.clean();
        ds.servers.add(PROD_SERVER);
        Driver ad = ds.driver("AD");
        ad.policies.add(new Policy("itp", Scope.DRIVER, "AD", ValidatorTest.xml(rule("input"))));
        ad.links.add(new PolicyLink(PolicySet.INPUT, "drivers/AD/itp", 0));
        return ds;
    }

    /** QA as its own project names it. */
    private static DriverSet qa() {
        DriverSet ds = prod();
        ds.dn = "cn=dvs,o=qa";
        ds.servers.clear();
        ds.servers.add(QA_SERVER);
        assertTrue(ds.renameDriver("AD", "AD QA"));
        ds.driver("AD QA").dn = "cn=AD QA,cn=dvs,o=qa";
        Overrides.resolve(ds, "drivers/AD QA.gcv.drv.users").set("qa-users");
        return ds;
    }

    private Path prodTree;
    private Path qaTree;

    private void setUp() throws IOException {
        prodTree = tmp.newFolder("acme-prod", "tree").toPath();
        qaTree = tmp.newFolder("acme-qa", "tree").toPath();
        AsCodeWriter.write(prod(), prodTree);
        AsCodeWriter.write(qa(), qaTree);
        Files.createDirectories(prodTree.resolve(Pair.DIR));
        Files.writeString(prodTree.resolve(Pair.DIR).resolve("acme-qa.properties"),
            "drivers/AD QA.name = AD\nservers.vault01qa = " + PROD_SERVER + "\n", StandardCharsets.UTF_8);
    }

    /** Rewrite a tree from its model, keeping the pair's files and baselines. */
    private static void rewrite(Path tree, DriverSet ds) throws IOException {
        try (Stream<Path> s = Files.list(tree)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (!n.equals(Pair.DIR) && !n.equals(AsCodeWriter.BASELINE_DIR)) {
                    Sides.delete(p);
                }
            }
        }
        AsCodeWriter.write(ds, tree);
    }

    private interface Edit {
        void apply(DriverSet ds);
    }

    private static void edit(Path tree, Edit e) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        e.apply(ds);
        rewrite(tree, ds);
    }

    private static void setPolicy(DriverSet ds, String driver, String name, String description) {
        ((Policy) ds.resolve("drivers/" + driver + "/subscriber/" + name)).content = ValidatorTest.xml(rule(description));
    }

    private static void setInput(DriverSet ds, String driver, String description) {
        ((Policy) ds.resolve("drivers/" + driver + "/itp")).content = ValidatorTest.xml(rule(description));
    }

    private ProjectCompare compare() throws IOException {
        return ProjectCompare.of(prodTree, qaTree, "acme-qa");
    }

    private static Promote.Selection objects(String... paths) {
        Promote.Selection sel = new Promote.Selection();
        sel.objects.addAll(List.of(paths));
        return sel;
    }

    private Promote.Result promote(Promote.Selection sel) throws IOException {
        return Promote.run(prodTree, qaTree, "acme-qa", sel, false, false, "tester");
    }

    @Test
    public void theMapLinesUpNamesServersAndIdentityAndValuesStandApart() throws Exception {
        setUp();
        ProjectCompare c = compare();
        assertEquals(c.text(), 1, c.items.size());
        ProjectCompare.Item v = c.items.get(0);
        assertTrue(v.value);
        assertEquals("drivers/AD.gcv.drv.users", v.path);
        assertEquals("AD", v.driver);
        assertEquals("qa-users", v.sourceValue);
        assertEquals("users", v.targetValue);
        assertEquals(ProjectCompare.Bucket.UNPAIRED, v.bucket);
        assertTrue(c.notes.toString(), c.notes.isEmpty());
        assertEquals("pair named after the project when the directory is tree/", "acme-qa", Pair.nameOf(qaTree));

        // without the map: the driver is on one side each, proposed as a match, and the servers are named
        Files.delete(prodTree.resolve(Pair.DIR).resolve("acme-qa.properties"));
        ProjectCompare plain = compare();
        assertTrue(plain.items.stream().anyMatch(i -> i.path.startsWith("drivers/AD QA/") && i.state().equals("only in the source")));
        assertEquals(1, plain.matches.size());
        assertEquals("AD QA", plain.matches.get(0).a);
        assertEquals("AD", plain.matches.get(0).b);
        assertTrue(plain.notes.toString(), plain.notes.stream().anyMatch(n -> n.contains(QA_SERVER)));
    }

    @Test
    public void thePairsRecordSortsEachDifferenceByWhoChangedIt() throws Exception {
        setUp();
        assertTrue(Promote.pair(prodTree, qaTree, "acme-qa", false, false, "tester").ok());
        assertFalse("paired: nothing open", compare().hasOpen());
        assertEquals(ProjectCompare.Bucket.UNCHANGED, compare().item("drivers/AD.gcv.drv.users").bucket);
        assertFalse("a second pairing needs --force", Promote.pair(prodTree, qaTree, "acme-qa", false, false, "tester").ok());

        edit(qaTree, ds -> {
            setPolicy(ds, "AD QA", "sub-ctp", "qa changed it");
            setInput(ds, "AD QA", "qa changed this too");
        });
        edit(prodTree, ds -> {
            setPolicy(ds, "AD", "sub-ctp", "prod changed it");   // both
            ((Policy) ds.resolve("drivers/AD/smp")).content = ValidatorTest.xml(
                "<attr-name-map><mapping><app-name>x</app-name><nds-name>Surname</nds-name></mapping></attr-name-map>");   // the schema map: prod's own
        });
        ProjectCompare c = compare();
        assertEquals(c.text(), ProjectCompare.Bucket.BOTH, c.item(POLICY).bucket);
        assertEquals(c.text(), ProjectCompare.Bucket.PENDING, c.item("drivers/AD/itp.policy.xml").bucket);
        assertEquals(c.text(), ProjectCompare.Bucket.OWN, c.item("drivers/AD/smp.policy.xml").bucket);
        assertTrue(c.item(POLICY).detail, c.item(POLICY).detail.contains("- ") && c.item(POLICY).detail.contains("+ "));

        // accepted as known: collapsed while neither side changes it, shown again once one does
        assertTrue(Promote.accept(prodTree, qaTree, "acme-qa", "drivers/AD/smp.policy.xml", "intended", "prod's mapping", "tester", false).ok());
        assertEquals(ProjectCompare.Bucket.KNOWN, compare().item("drivers/AD/smp.policy.xml").bucket);
        edit(qaTree, ds -> ((Policy) ds.resolve("drivers/AD QA/smp")).content = ValidatorTest.xml(
            "<attr-name-map><mapping><app-name>y</app-name><nds-name>Surname</nds-name></mapping></attr-name-map>"));
        ProjectCompare after = compare();
        assertEquals(ProjectCompare.Bucket.BOTH, after.item("drivers/AD/smp.policy.xml").bucket);
        assertTrue(after.item("drivers/AD/smp.policy.xml").changedSinceKnown);
        assertTrue(Promote.forget(prodTree, "acme-qa", "drivers/AD/smp.policy.xml", false).ok());
        assertFalse(Files.exists(prodTree.resolve(Pair.DIR).resolve("acme-qa.known.json")));
    }

    @Test
    public void aPartialPromotionLeavesTheRestPendingAndKeepsTheTargetsValues() throws Exception {
        setUp();
        assertTrue(Promote.pair(prodTree, qaTree, "acme-qa", false, false, "tester").ok());
        edit(qaTree, ds -> {
            setPolicy(ds, "AD QA", "sub-ctp", "qa changed it");
            setInput(ds, "AD QA", "qa changed this too");
            Overrides.resolve(ds, "drivers/AD QA.gcv.drv.users").set("qa-users-2");
        });
        Promote.Result plan = Promote.run(prodTree, qaTree, "acme-qa", objects(POLICY), true, false, "tester");
        assertTrue(plan.text(), plan.ok());
        assertEquals(List.of(POLICY), plan.promoted);
        assertTrue(plan.changedFiles.contains(POLICY));
        assertFalse("a plan writes nothing", Files.readString(prodTree.resolve(POLICY)).contains("qa changed it"));

        Promote.Result r = promote(objects(POLICY));
        assertTrue(r.text(), r.ok() && r.written);
        assertTrue(Files.readString(prodTree.resolve(POLICY)).contains("qa changed it"));
        assertTrue(r.trailer, r.trailer.startsWith("Promoted-from: acme-qa@"));
        ProjectCompare c = compare();
        assertNull("promoted: no longer differs", c.item(POLICY));
        assertEquals(ProjectCompare.Bucket.PENDING, c.item("drivers/AD/itp.policy.xml").bucket);
        ProjectCompare.Item users = c.item("drivers/AD.gcv.drv.users");
        assertEquals("a value changed in QA is pending, but not moved", ProjectCompare.Bucket.PENDING, users.bucket);
        assertEquals("users", users.targetValue);

        // a value moves only when named, even with its file
        Promote.Selection sel = new Promote.Selection();
        sel.pending = true;
        assertTrue(promote(sel).ok());
        assertEquals("users", Overrides.resolve(AsCodeReader.read(prodTree), "drivers/AD.gcv.drv.users").value());
        Promote.Selection value = new Promote.Selection();
        value.values.add("drivers/AD.gcv.drv.users");
        Promote.Result moved = promote(value);
        assertTrue(moved.text(), moved.ok());
        assertEquals("qa-users-2", Overrides.resolve(AsCodeReader.read(prodTree), "drivers/AD.gcv.drv.users").value());
        assertFalse(compare().text(), compare().hasOpen());
    }

    @Test
    public void aNewPolicyComesWithItsManifestAndARemovalTheSame() throws Exception {
        setUp();
        assertTrue(Promote.pair(prodTree, qaTree, "acme-qa", false, false, "tester").ok());
        String added = "drivers/AD/subscriber/sub-new.policy.xml";
        edit(qaTree, ds -> {
            Driver d = ds.driver("AD QA");
            d.subscriber.policies.add(new Policy("sub-new", Scope.SUBSCRIBER, "AD QA", ValidatorTest.xml(rule("new"))));
            d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD QA/subscriber/sub-new", 1));
        });
        ProjectCompare c = compare();
        assertEquals(ProjectCompare.Bucket.PENDING, c.item(added).bucket);
        assertEquals("only in the source", c.item(added).state());
        assertEquals(ProjectCompare.Bucket.PENDING, c.item(MANIFEST).bucket);

        Promote.Result alone = promote(objects(added));
        assertFalse(alone.text(), alone.ok());
        assertTrue(alone.refusal, alone.refusal.contains(MANIFEST));
        assertFalse("refused: nothing written", Files.exists(prodTree.resolve(added)));

        Promote.Result both = promote(objects("drivers/AD"));
        assertTrue(both.text(), both.ok());
        assertTrue(Files.exists(prodTree.resolve(added)));
        DriverSet prod = AsCodeReader.read(prodTree);
        assertNotNull(prod.resolve("drivers/AD/subscriber/sub-new"));
        assertEquals("the target's DN stays", "cn=AD,cn=dvs,o=system", prod.driver("AD").dn);
        assertEquals("cn=dvs,o=system", prod.dn);
        assertEquals(List.of(PROD_SERVER), prod.servers);
        assertEquals("users", Overrides.resolve(prod, "drivers/AD.gcv.drv.users").value());

        // removed in QA since: offered as a removal, and it too needs its manifest
        edit(qaTree, ds -> {
            Driver d = ds.driver("AD QA");
            d.subscriber.policies.removeIf(p -> p.name.equals("sub-new"));
            d.links.removeIf(l -> l.ref.endsWith("/sub-new"));
        });
        ProjectCompare gone = compare();
        assertEquals("only in the target", gone.item(added).state());
        assertEquals(ProjectCompare.Bucket.PENDING, gone.item(added).bucket);
        assertFalse(promote(objects(added)).ok());
        Promote.Result removed = promote(objects(added, MANIFEST));
        assertTrue(removed.text(), removed.ok());
        assertTrue(removed.deletedFiles.contains(added));
        assertFalse(Files.exists(prodTree.resolve(added)));
    }

    @Test
    public void aMergedFileIsRecordedAsTheTargetHasIt() throws Exception {
        setUp();
        assertTrue(Promote.pair(prodTree, qaTree, "acme-qa", false, false, "tester").ok());
        edit(qaTree, ds -> setPolicy(ds, "AD QA", "sub-ctp", "qa changed it"));
        String merged = Files.readString(qaTree.resolve("drivers/AD QA/subscriber/sub-ctp.policy.xml")).replace("qa changed it", "merged by hand");
        Promote.Selection sel = new Promote.Selection();
        sel.contents.put(POLICY, merged);
        Promote.Result r = promote(sel);
        assertTrue(r.text(), r.ok());
        assertTrue(Files.readString(prodTree.resolve(POLICY)).contains("merged by hand"));
        assertEquals("still differs, but as the promotion left it", ProjectCompare.Bucket.UNCHANGED, compare().item(POLICY).bucket);
    }

    @Test
    public void aPreviewShowsEachFileAsAPromotionWritesIt() throws Exception {
        setUp();
        edit(qaTree, ds -> {
            setPolicy(ds, "AD QA", "sub-ctp", "qa changed it");
            Driver d = ds.driver("AD QA");
            d.subscriber.policies.add(new Policy("sub-new", Scope.SUBSCRIBER, "AD QA", ValidatorTest.xml(rule("new"))));
            d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD QA/subscriber/sub-new", 1));
        });
        Promote.Preview pv = Promote.preview(prodTree, qaTree, "acme-qa");
        String added = "drivers/AD/subscriber/sub-new.policy.xml";
        assertNull("not in the target yet", pv.before.get(added));
        assertTrue(new String(pv.after.get(added), StandardCharsets.UTF_8).contains("new"));
        assertTrue(new String(pv.before.get(POLICY), StandardCharsets.UTF_8).contains("r1"));
        assertTrue(new String(pv.after.get(POLICY), StandardCharsets.UTF_8).contains("qa changed it"));
        String manifest = new String(pv.after.get(MANIFEST), StandardCharsets.UTF_8);
        assertTrue(manifest, manifest.contains("sub-new") && manifest.contains("cn=AD,cn=dvs,o=system"));
        assertFalse("the value stays the target's: config-values.xml doesn't differ", pv.after.containsKey("drivers/AD/config-values.xml"));
        assertFalse("a preview writes nothing", Files.exists(prodTree.resolve(added)));
    }

    @Test
    public void aPromotionThatWouldNotValidateIsRefused() throws Exception {
        setUp();
        // QA's driver links a policy QA doesn't have: promoting its manifest alone breaks the target
        edit(qaTree, ds -> ds.driver("AD QA").links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-missing", 0)));
        Promote.Result r = promote(objects(MANIFEST));
        assertFalse(r.text(), r.ok());
        assertTrue(r.refusal, r.refusal.contains("validation error"));
        assertFalse(r.newErrors.isEmpty());
        assertFalse(Files.readString(prodTree.resolve(MANIFEST)).contains("lib-missing"));
        assertFalse(Files.exists(prodTree.resolve(Pair.DIR).resolve("acme-qa.json")));
        assertEquals("nothing differs: refused by path", "nothing differs at 'drivers/AD/nope'", promote(objects("drivers/AD/nope")).refusal);
    }

    @Test
    public void aPackagedFilesBaselineGoesWithIt() throws Exception {
        setUp();
        Path baseline = qaTree.resolve(AsCodeWriter.BASELINE_DIR).resolve("drivers/AD QA/subscriber/sub-ctp.policy.xml");
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, rule("as the package had it"), StandardCharsets.UTF_8);
        edit(qaTree, ds -> setPolicy(ds, "AD QA", "sub-ctp", "qa customized it"));
        Promote.Result r = promote(objects(POLICY));
        assertTrue(r.text(), r.ok());
        Path mine = prodTree.resolve(AsCodeWriter.BASELINE_DIR).resolve(POLICY);
        assertTrue(r.changedFiles.toString(), Files.isRegularFile(mine));
        assertTrue(Files.readString(mine).contains("as the package had it"));
    }

    @Test
    public void theCliComparesAndPromotes() throws Exception {
        setUp();
        java.io.PrintStream was = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            assertEquals(1, PromoteCli.run(new String[] {"tree.compare", prodTree.toString(), "--with", qaTree.toString(), "--json"}));
            assertTrue(buf.toString(StandardCharsets.UTF_8), buf.toString(StandardCharsets.UTF_8).contains("\"bucket\":\"unpaired\""));
            assertEquals(0, PromoteCli.run(new String[] {"promote.pair", prodTree.toString(), "--from", qaTree.toString()}));
            assertEquals(0, PromoteCli.run(new String[] {"tree.compare", prodTree.toString(), "--with", qaTree.toString()}));
            assertEquals(1, PromoteCli.run(new String[] {"promote.apply", prodTree.toString(), "--from", qaTree.toString()}));
            assertEquals(2, PromoteCli.run(new String[] {"promote.apply", prodTree.toString()}));
        } finally {
            System.setOut(was);
        }
    }
}
