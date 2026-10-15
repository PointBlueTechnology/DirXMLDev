package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.sim.LdifDriverSource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link Deployer#run()} end to end against {@link FakeVault} (the package-private test
 * constructor — {@link Vault} is {@code final}, see {@link VaultAccess}) — focused on
 * {@code --delete-driver}: snapshot, deletion order, verify, audit, and rollback from that
 * snapshot. Everything else in {@code Deployer} is already covered through {@link Plan} and
 * {@link Snapshot} (the deployer executes plan steps generically).
 */
public class DeployerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=dvs,o=system";   // matches VaultMappingTest's fixture DNs

    /** {@code VaultMappingTest.model()} plus a vault-only driver "Old" (stopped, a small subtree). */
    private static DriverSet withVaultOnlyDriver() {
        DriverSet ds = VaultMappingTest.model();
        Driver old = new Driver("Old");
        old.shimClass = "com.example.OldShim";
        old.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        old.subscriber.policies.add(new Policy("sub-old", Scope.SUBSCRIBER, "Old", ValidatorTest.xml(
            "<policy><rule><description>x</description><conditions/><actions/></rule></policy>")));
        ds.drivers.add(old);
        return ds;
    }

    /** Seeds a {@link FakeVault} with every entry {@code ds} maps to (the same mapping {@code Vault} writes). */
    private static FakeVault seedVault(DriverSet ds) {
        FakeVault vault = new FakeVault();
        for (LdifDriverSource.Entry e : VaultMappingTest.entries(ds)) {
            vault.seed(toVaultEntry(e));
        }
        return vault;
    }

    private static Vault.Entry toVaultEntry(LdifDriverSource.Entry e) {
        Vault.Entry ve = new Vault.Entry(e.dn);
        for (String name : e.attributeNames()) {
            List<byte[]> bytes = new ArrayList<>();
            for (String v : e.all(name)) {
                bytes.add(v.getBytes(StandardCharsets.UTF_8));
            }
            ve.attrs.put(name, bytes);
        }
        return ve;
    }

    private static Environments.Environment stgEnv(String name) {
        return new Environments.Environment(name, "ldaps://fake:636", "cn=admin,o=system", "pw", DS,
            Environments.Tier.STG, null, null, true, null, null);
    }

    private Deployer.Options options(Path tree, Environments.Environment env, List<String> deleteDrivers) {
        Deployer.Options o = new Deployer.Options();
        o.tree = tree;
        o.env = env;
        o.yes = true;
        o.deleteDrivers = deleteDrivers;
        return o;
    }

    @Test
    public void deleteDriverSnapshotsDeletesDeepestFirstVerifiesAndAudits() throws Exception {
        String driverDn = VaultMapping.driverDn(DS, "Old");
        String subDn = "cn=Subscriber," + driverDn;
        String pubDn = "cn=Publisher," + driverDn;
        String policyDn = "cn=sub-old," + subDn;

        FakeVault vault = seedVault(withVaultOnlyDriver());
        assertTrue(vault.exists(driverDn));

        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(VaultMappingTest.model(), tree);   // the tree: no "Old"

        Environments.Environment env = stgEnv("test");
        Deployer.Options o = options(tree, env, List.of("Old"));

        Deployer.Result r = new Deployer(o, vault).run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.text(), r.verified);
        assertFalse(vault.exists(driverDn));
        assertFalse(vault.exists(subDn));
        assertFalse(vault.exists(pubDn));
        assertFalse(vault.exists(policyDn));

        // deletion order: deepest first, the driver object last
        assertEquals(4, vault.deleted.size());
        assertEquals(policyDn, vault.deleted.get(0));
        assertEquals(driverDn, vault.deleted.get(vault.deleted.size() - 1));

        // snapshot: the whole subtree, every attribute, before any write
        assertNotNull(r.snapshot);
        Path snapFile = tree.resolve(r.snapshot);
        Snapshot snap = Snapshot.read(snapFile);
        assertEntryPresent(snap, driverDn);
        assertEntryPresent(snap, subDn);
        assertEntryPresent(snap, pubDn);
        assertEntryPresent(snap, policyDn);
        String ldif = Files.readString(snapFile, StandardCharsets.UTF_8);
        assertTrue(ldif, ldif.toLowerCase(java.util.Locale.ROOT).contains("dirxml-javamodule"));   // a driver attribute, not just the DN line

        // audit: names the driver and the object count
        List<DeployLog.Record> log = DeployLog.read(tree, "test");
        assertFalse(log.isEmpty());
        DeployLog.Record last = log.get(log.size() - 1);
        assertEquals("ok", last.outcome);
        assertTrue(last.detail, last.detail.contains("deleted driver(s): Old (4 object(s))"));

        // rollback from that snapshot: the subtree comes back, parents before children
        Snapshot.RestoreResult rr = snap.restore(vault);
        assertTrue(rr.text(), rr.ok());
        assertTrue(vault.exists(driverDn));
        assertTrue(vault.exists(subDn));
        assertTrue(vault.exists(policyDn));
        int driverAt = rr.actions.indexOf("recreated " + driverDn);
        int subAt = rr.actions.indexOf("recreated " + subDn);
        int policyAt = rr.actions.indexOf("recreated " + policyDn);
        assertTrue(rr.actions.toString(), driverAt >= 0 && subAt >= 0 && policyAt >= 0);
        assertTrue(rr.actions.toString(), driverAt < subAt);
        assertTrue(rr.actions.toString(), subAt < policyAt);
        assertTrue("rolled back state matches the snapshot", snap.differences(vault).isEmpty());
    }

    private static void assertEntryPresent(Snapshot snap, String dn) {
        boolean found = snap.entries.stream().anyMatch(e -> e.dn.equals(dn) && !e.absent);
        assertTrue(dn + " missing from snapshot: " + snap.text(), found);
    }

    @Test
    public void deleteDriverInTheTreeRefusesTheWholeDeploy() throws Exception {
        FakeVault vault = seedVault(VaultMappingTest.model());   // "AD" only, and it's in the tree too
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(VaultMappingTest.model(), tree);

        Deployer.Options o = options(tree, stgEnv("test"), List.of("AD"));
        Deployer.Result r = new Deployer(o, vault).run();

        assertFalse(r.ok);
        assertNotNull(r.refusal);
        assertTrue(r.refusal, r.refusal.contains("in the tree"));
        assertTrue("nothing should have been written", vault.deleted.isEmpty());
    }

    @Test
    public void deleteDriverRunningRefusesTheWholeDeploy() throws Exception {
        String driverDn = VaultMapping.driverDn(DS, "Old");
        FakeVault vault = seedVault(withVaultOnlyDriver());
        vault.setDriverState(driverDn, Vault.STATE_RUNNING);

        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(VaultMappingTest.model(), tree);

        Deployer.Options o = options(tree, stgEnv("test"), List.of("Old"));
        Deployer.Result r = new Deployer(o, vault).run();

        assertFalse(r.ok);
        assertNotNull(r.refusal);
        assertTrue(r.refusal, r.refusal.contains("stop it first"));
        assertTrue(vault.exists(driverDn));
        assertTrue("nothing should have been written", vault.deleted.isEmpty());
    }

    // ---- --delete-all (docs/vault-deploy.md, "Deploy never empties a kind") ---------------------

    /**
     * The vault has an entitlement on "AD" that the tree lacks entirely: without {@code --delete-all}
     * the guard holds the delete back (deploy still succeeds — nothing to verify differs since the
     * plan has no steps for it); with {@code --delete-all entitlements} the delete goes through and
     * the audit line names the override.
     */
    @Test
    public void deleteAllEntitlementsIsGuardedThenOverriddenAndAudited() throws Exception {
        DriverSet ds = VaultMappingTest.model();
        FakeVault vault = seedVault(ds);
        String entDn = "cn=Existing,cn=AD," + DS;
        vault.add(entDn, List.of("Top", VaultMapping.OC_ENTITLEMENT),
            java.util.Map.of("XmlData", List.of("<entitlement/>".getBytes(StandardCharsets.UTF_8))));

        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);   // no entitlements in the tree

        // without --delete-all: the guard holds the delete back, deploy still reports ok
        Deployer.Options guarded = options(tree, stgEnv("test"), List.of());
        Deployer.Result r1 = new Deployer(guarded, vault).run();
        assertTrue(r1.text(), r1.ok);
        assertTrue(r1.planText, r1.planText.contains("the tree has no entitlements but the vault has 1"));
        assertTrue("the guarded entitlement is untouched", vault.exists(entDn));

        // with --delete-all entitlements: the delete goes through and the audit line names it
        Deployer.Options overridden = options(tree, stgEnv("test"), List.of());
        overridden.deleteAllKinds = List.of("entitlements");
        Deployer.Result r2 = new Deployer(overridden, vault).run();
        assertTrue(r2.text(), r2.ok);
        assertFalse("the entitlement is deleted", vault.exists(entDn));

        List<DeployLog.Record> log = DeployLog.read(tree, "test");
        DeployLog.Record last = log.get(log.size() - 1);
        assertEquals("ok", last.outcome);
        assertTrue(last.detail, last.detail.contains("--delete-all: entitlements"));
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
    }

    private static String gitOut(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }

    /**
     * {@code --capture-drift} on a production environment with no deploy on record: the vault's state is captured as an
     * {@code as-found/…} branch that holds exactly the vault — a set job the tree still carries but the vault no longer
     * holds leaves no stale file behind (the capture clears every path the writer owns, jobs, policies and templates too).
     */
    @Test
    public void captureDriftLeavesNoStaleJobPolicyOrTemplateFiles() throws Exception {
        DriverSet vaultSide = VaultMappingTest.model();
        FakeVault vault = seedVault(vaultSide);

        DriverSet treeSide = VaultMappingTest.model();
        treeSide.jobs.add(new com.pointblue.dirxml.dev.model.Job("OldJob", ValidatorTest.xml("<job-aggregation><job-definition><java-class>x.Job</java-class></job-definition></job-aggregation>")));
        Path tree = tmp.newFolder("prdtree").toPath();
        AsCodeWriter.write(treeSide, tree);
        assertTrue(java.nio.file.Files.exists(tree.resolve("jobs/OldJob.xml")));
        git(tree, "init", "-q", "-b", "main");
        git(tree, "-c", "user.name=t", "-c", "user.email=t@example.com", "add", "-A");
        git(tree, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", "import");

        Environments.Environment prd = new Environments.Environment("prd", "ldaps://fake:636", "cn=admin,o=system", "pw", DS,
            Environments.Tier.PRD, null, null, true, null, null);
        Deployer.Options o = options(tree, prd, List.of());
        o.confirm = "prd";
        o.captureDrift = true;
        Deployer.Result r = new Deployer(o, vault).run();
        assertFalse(r.text(), r.ok);   // a capture stops the deploy: the change goes on top of the captured state
        assertTrue(r.text(), r.refusal != null && r.refusal.contains("as-found/prd/"));
        String branch = gitOut(tree, "branch", "--list", "as-found/prd/*").replace("*", "").trim();
        assertTrue("an as-found branch: " + gitOut(tree, "branch"), branch.startsWith("as-found/prd/"));
        String files = gitOut(tree, "ls-tree", "-r", "--name-only", branch);
        assertFalse(files, files.contains("jobs/OldJob.xml"));
        assertTrue(files, files.contains("driverset.xml"));
    }

    // ---- the validation gate of a scoped deploy (ValidationScope) ----

    /** model() plus a driver "Broken" whose policy names a GCV nobody defines: a validation error outside AD. */
    private static DriverSet withBrokenDriver() {
        DriverSet ds = VaultMappingTest.model();
        Driver broken = new Driver("Broken");
        broken.shimClass = "com.example.BrokenShim";
        broken.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        broken.subscriber.policies.add(new Policy("sub-broken", Scope.SUBSCRIBER, "Broken", ValidatorTest.xml(
            "<policy><rule><description>x</description><conditions/><actions><do-set-local-variable name=\"v\">"
                + "<arg-string><token-global-variable name=\"no-such-gcv\"/></arg-string></do-set-local-variable></actions></rule></policy>")));
        broken.links.add(new PolicyLink(PolicySet.SUB_EVENT, "drivers/Broken/subscriber/sub-broken", 0));
        ds.drivers.add(broken);
        return ds;
    }

    /** The same driver set with a new subscriber policy on AD: the change a scoped deploy carries. */
    private static DriverSet withAdChange() {
        DriverSet ds = withBrokenDriver();
        Driver ad = ds.driver("AD");
        ad.subscriber.policies.add(new Policy("sub-new", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><rule><description>new</description><conditions/><actions/></rule></policy>")));
        ad.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-new", 0));
        return ds;
    }

    @Test
    public void aDeployScopedToADriverIsNotHeldBackByAnErrorInAnotherDriver() throws Exception {
        FakeVault vault = seedVault(withBrokenDriver());
        Path tree = tmp.newFolder("scoped").toPath();
        AsCodeWriter.write(withAdChange(), tree);
        com.pointblue.dirxml.dev.validate.Report report = com.pointblue.dirxml.dev.validate.Validator.standard().validate(tree);
        assertFalse("the fixture has an error", report.ok());
        assertTrue(report.text(), report.of(com.pointblue.dirxml.dev.validate.Finding.Severity.ERROR).stream().allMatch(f -> f.path != null && f.path.startsWith("drivers/Broken")));

        Deployer.Options o = options(tree, stgEnv("test"), List.of());
        o.drivers = List.of("AD");
        List<String> progress = new ArrayList<>();
        o.progress = progress::add;
        Deployer.Result r = new Deployer(o, vault).run();
        assertTrue(r.text(), r.ok && r.verified);
        // each step as it happens, in order (what the web page shows while a deploy runs)
        String steps = String.join("\n", progress);
        int validating = steps.indexOf("validating the tree"), reading = steps.indexOf("reading the vault"),
            planned = steps.indexOf("planned: "), snapshot = steps.indexOf("snapshot: deploy-snapshots/test/"),
            write = steps.indexOf("write 1/"), verifying = steps.indexOf("verifying"), done = steps.indexOf("done: verified");
        assertTrue(steps, 0 <= validating && validating < reading && reading < planned && planned < snapshot
            && snapshot < write && write < verifying && verifying < done);
        assertTrue(r.planText, r.planText.startsWith("validation: 1 error(s) outside this deploy's scope, not deployed by it (Broken)"));
        assertTrue(vault.exists("cn=sub-new,cn=Subscriber," + VaultMapping.driverDn(DS, "AD")));
    }

    @Test
    public void aWholeSetDeployAndADeployScopedToTheBrokenDriverAreRefused() throws Exception {
        FakeVault vault = seedVault(withBrokenDriver());
        Path tree = tmp.newFolder("whole").toPath();
        AsCodeWriter.write(withAdChange(), tree);

        Deployer.Result whole = new Deployer(options(tree, stgEnv("test"), List.of()), vault).run();
        assertFalse(whole.ok);
        assertTrue(whole.refusal, whole.refusal.startsWith("the tree does not validate: FAIL"));

        Deployer.Options o = options(tree, stgEnv("test"), List.of());
        o.drivers = List.of("Broken");
        Deployer.Result scoped = new Deployer(o, vault).run();
        assertFalse(scoped.ok);
        assertTrue(scoped.refusal, scoped.refusal.contains("1 error(s) in what this deploy writes") && scoped.refusal.contains("drivers/Broken/subscriber/sub-broken"));
        assertFalse(vault.exists("cn=sub-new,cn=Subscriber," + VaultMapping.driverDn(DS, "AD")));
    }
}
