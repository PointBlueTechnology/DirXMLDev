package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
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
}
