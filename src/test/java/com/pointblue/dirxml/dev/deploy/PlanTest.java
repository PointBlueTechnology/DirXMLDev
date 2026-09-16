package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.packages.InstalledChecksum;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The plan builder: steps, order, grouping, linkage, secrets, restarts — pure, no vault. */
public class PlanTest {

    private static final String DS = "cn=dvs,o=system";

    private static Plan plan(DriverSet from, DriverSet to, Secrets secrets, String secretsMode) {
        return Plan.of(ModelDiff.of(from, to), to, DS, secrets, secretsMode, Map.of(), true);
    }

    @Test
    public void nothingToDo() {
        DriverSet a = VaultMappingTest.model();
        Plan p = plan(a, VaultMappingTest.model(), Secrets.none(), "none");
        assertTrue(p.text("stg", DS), p.isEmpty());
    }

    @Test
    public void addedPolicyIsAddThenLinkageThenRestart() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Driver ad = to.driver("AD");
        ad.subscriber.policies.add(new Policy("sub-new", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><rule><description>n</description><conditions/><actions/></rule></policy>")));
        ad.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-new", 1));
        Plan p = plan(from, to, Secrets.none(), "none");
        List<Plan.Step> steps = p.steps;
        assertEquals(p.text("stg", DS), 2, steps.size());
        assertEquals(Plan.Op.ADD, steps.get(0).op);
        assertEquals("cn=sub-new,cn=Subscriber,cn=AD,cn=dvs,o=system", steps.get(0).dn);
        assertEquals(List.of("Top", "DirXML-Rule"), steps.get(0).objectClasses);
        assertEquals(Plan.Op.MODIFY, steps.get(1).op);
        assertEquals(VaultMapping.POLICIES, steps.get(1).attr);
        assertEquals(5, steps.get(1).values.get(VaultMapping.POLICIES).size());
        assertEquals(List.of("AD"), List.copyOf(p.restart));
        assertEquals(List.of("drivers/AD/subscriber/sub-new", "drivers/AD#linkage"), p.changes());
        assertTrue(p.touchedDns.contains("cn=AD,cn=dvs,o=system"));
    }

    @Test
    public void libraryChangeComesFirstAndRestartsLinkingDrivers() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Policy lib = (Policy) to.resolve("library/lib-shared");
        lib.content = ValidatorTest.xml("<policy><rule><description>shared v2</description><conditions/><actions/></rule></policy>");
        Driver ad = to.driver("AD");
        ad.shimAuthId = "svc2";
        Plan p = plan(from, to, Secrets.none(), "none");
        assertEquals(p.text("stg", DS), 2, p.steps.size());
        assertEquals("cn=lib-shared,cn=Library,cn=dvs,o=system", p.steps.get(0).dn);   // Library before driver attrs
        assertEquals(VaultMapping.XML_DATA, p.steps.get(0).attr);
        assertEquals(VaultMapping.SHIM_AUTH_ID, p.steps.get(1).attr);
        assertEquals(List.of("AD"), List.copyOf(p.restart));
    }

    @Test
    public void removedArtifactIsDeletedAfterLinkage() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Driver ad = to.driver("AD");
        Policy ctp = (Policy) to.resolve("drivers/AD/subscriber/sub-ctp");
        ad.subscriber.policies.remove(ctp);
        ad.links.removeIf(l -> l.ref.equals("drivers/AD/subscriber/sub-ctp"));
        Plan p = plan(from, to, Secrets.none(), "none");
        assertEquals(p.text("stg", DS), 2, p.steps.size());
        assertEquals(Plan.Op.MODIFY, p.steps.get(0).op);   // linkage first
        assertEquals(Plan.Op.DELETE, p.steps.get(1).op);
        assertEquals("cn=sub-ctp,cn=Subscriber,cn=AD,cn=dvs,o=system", p.steps.get(1).dn);
    }

    @Test
    public void customizedPackagedArtifactGetsAChecksum() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Policy ctp = (Policy) to.resolve("drivers/AD/subscriber/sub-ctp");
        ctp.content = ValidatorTest.xml("<policy><rule><description>custom</description><conditions/><actions/></rule></policy>");
        ctp.meta.put("package-id", "PKG");
        ctp.meta.put("package.customized", "true");
        Plan p = plan(from, to, Secrets.none(), "none");
        boolean sawChecksum = false;
        for (Plan.Step s : p.steps) {
            if (VaultMapping.PKG_CHECKSUM.equals(s.attr)) {
                sawChecksum = true;
                assertTrue(Long.parseLong(new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8)) > 0);
            }
        }
        assertTrue(p.text("stg", DS), sawChecksum);
    }

    /**
     * Follow-up 2 (docs/vault-deploy.md, "Packages" — a customized artifact's checksum): Plan reads the
     * checksum straight from the artifact's meta ({@code VaultMapping.packageAttributes}); it never recomputes
     * it. So once {@code Packages.refreshChecksums} has replaced the stale installed stamp with Designer's own
     * recipe ({@link InstalledChecksum}), as a real transaction does, the step the plan emits must carry that
     * recomputed value — not the old, now-stale one.
     */
    @Test
    public void customizedStampedArtifactCarriesTheRecomputedChecksumNotTheOldOne() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Policy ctp = (Policy) to.resolve("drivers/AD/subscriber/sub-ctp");
        ctp.content = ValidatorTest.xml("<policy><rule><description>custom</description><conditions/><actions/></rule></policy>");
        ctp.meta.put("dirxml-pkgguid", "PKGGUID_1;com.example.pkg;1.0.0");
        ctp.meta.put("dirxml-pkgassociationid", "ASSOC1");
        ctp.meta.put("package.customized", "true");
        String staleChecksum = "111111111";   // the pre-edit installed checksum: stale once the content changed
        ctp.meta.put("dirxml-pkgchecksum", staleChecksum);
        // what Packages.refreshChecksums does at the end of a real transaction: recompute from the new content
        long recomputed = InstalledChecksum.of(to, to.driver("AD"), ctp);
        ctp.meta.put("dirxml-pkgchecksum", Long.toString(recomputed));

        Plan p = plan(from, to, Secrets.none(), "none");
        String checksum = null;
        for (Plan.Step s : p.steps) {
            if (VaultMapping.PKG_CHECKSUM.equals(s.attr)) {
                checksum = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
            }
        }
        assertEquals(p.text("stg", DS), Long.toString(recomputed), checksum);
        assertNotEquals(staleChecksum, checksum);
    }

    @Test
    public void newDriverIsCreatedStoppedWithItsSecrets() throws IOException {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Driver n = new Driver("New");
        n.shimClass = "com.example.Shim";
        n.shimAuthId = "svc";
        n.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        n.subscriber.policies.add(new Policy("sub-pw", Scope.SUBSCRIBER, "New", ValidatorTest.xml(
            "<policy><rule><description>r</description><conditions/><actions><do-set-local-variable name=\"x\">"
                + "<arg-string><token-named-password name=\"svc-secret\"/></arg-string></do-set-local-variable></actions></rule></policy>")));
        n.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/New/subscriber/sub-pw", 0));
        to.drivers.add(n);

        Plan missing = plan(from, to, Secrets.none(), "none");
        assertEquals(2, missing.missingSecrets.size());
        assertTrue(missing.missingSecrets.get(0).startsWith("New.shim-auth-password"));

        Path f = Files.createTempFile("secrets", ".properties");
        Files.writeString(f, "New.shim-auth-password=pw\nNew.named.svc-secret=s\n");
        Plan p = plan(from, to, Secrets.load(f), "none");
        assertTrue(p.text("stg", DS), p.missingSecrets.isEmpty());
        assertEquals(List.of("New"), List.copyOf(p.newDrivers));
        List<Plan.Op> ops = p.steps.stream().map(s -> s.op).toList();
        assertEquals(Plan.Op.ADD, ops.get(0));                                  // the driver object
        assertEquals("cn=New,cn=dvs,o=system", p.steps.get(0).dn);
        assertEquals("cn=Subscriber,cn=New,cn=dvs,o=system", p.steps.get(1).dn); // containers next
        assertTrue(ops.contains(Plan.Op.START_OPTION));
        assertEquals(2, ops.stream().filter(op -> op == Plan.Op.SET_SECRET).count());
        assertTrue(ops.indexOf(Plan.Op.SET_SECRET) > ops.lastIndexOf(Plan.Op.MODIFY));   // secrets after writes
        assertFalse(p.restart.contains("New"));                                  // never restarted: created stopped
        assertTrue(p.text("stg", DS).contains("created stopped"));
    }

    @Test
    public void forcedSecretsOnExistingDrivers() throws IOException {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Path f = Files.createTempFile("secrets", ".properties");
        Files.writeString(f, "AD.shim-auth-password=pw\n");
        Plan none = plan(from, to, Secrets.load(f), "none");
        assertTrue(none.isEmpty());
        Plan all = plan(from, to, Secrets.load(f), "all");
        assertEquals(1, all.steps.size());
        assertEquals(Plan.Op.SET_SECRET, all.steps.get(0).op);
        assertEquals("AD.shim-auth-password", all.steps.get(0).attr);
        assertTrue(all.text("stg", DS).contains("set secret AD.shim-auth-password"));
        assertFalse(all.text("stg", DS).contains("pw"));   // never the value
    }

    @Test
    public void driverSetGcvsRestartEveryDriver() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Resource setGcv = (Resource) to.resolve("library/SetGCVs");
        setGcv.content = ValidatorTest.xml("<configuration-values><definitions><definition display-name=\"t\" name=\"set.tree\" type=\"string\"><value>t2</value></definition></definitions></configuration-values>");
        Plan p = plan(from, to, Secrets.none(), "none");
        assertEquals(1, p.steps.size());
        assertEquals("cn=SetGCVs,cn=Library,cn=dvs,o=system", p.steps.get(0).dn);
        assertEquals(List.of("AD"), List.copyOf(p.restart));
        assertTrue(p.json().contains("\"op\":\"modify\""));
    }

    @Test
    public void newDriverBringsItsEntitlements() throws IOException {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Driver n = new Driver("New");
        n.shimClass = "com.example.Shim";
        n.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        com.pointblue.dirxml.dev.model.Entitlement e = new com.pointblue.dirxml.dev.model.Entitlement("Access", ValidatorTest.xml("<entitlement conflict-resolution=\"union\" display-name=\"Access\"><values multi-valued=\"true\"><value>a</value></values></entitlement>"));
        n.entitlements.add(e);
        to.drivers.add(n);
        Plan p = plan(from, to, Secrets.none(), "none");
        Plan.Step ent = p.steps.stream().filter(s -> s.dn.equals("cn=Access,cn=New,cn=dvs,o=system")).findFirst().orElse(null);
        assertNotNull(p.text("stg", DS), ent);
        assertEquals(Plan.Op.ADD, ent.op);
        assertEquals(List.of("Top", VaultMapping.OC_ENTITLEMENT), ent.objectClasses);
        assertTrue(ent.values.containsKey(VaultMapping.XML_DATA));
    }

    // ---- --delete-driver ---------------------------------------------------------------

    /** {@code from} with an extra driver "Old" (vault-only, stopped, a small subtree) that {@code to} lacks. */
    private static DriverSet withVaultOnlyDriver(DriverSet from) {
        Driver old = new Driver("Old");
        old.shimClass = "com.example.OldShim";
        old.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        old.subscriber.policies.add(new Policy("sub-old", Scope.SUBSCRIBER, "Old", ValidatorTest.xml(
            "<policy><rule><description>x</description><conditions/><actions/></rule></policy>")));
        from.drivers.add(old);
        return from;
    }

    /** Driver DN + Subscriber + Publisher + one policy = 4 objects. */
    private static FakeVault vaultWithOldSubtree(String driverDn) {
        FakeVault vault = new FakeVault();
        vault.add(driverDn, List.of("Top", "DirXML-Driver"), Map.of());
        String subDn = "cn=Subscriber," + driverDn;
        String pubDn = "cn=Publisher," + driverDn;
        vault.add(subDn, List.of("Top", "DirXML-Subscriber"), Map.of());
        vault.add(pubDn, List.of("Top", "DirXML-Publisher"), Map.of());
        vault.add("cn=sub-old," + subDn, List.of("Top", "DirXML-Rule"), Map.of());
        return vault;
    }

    @Test
    public void deleteDriverForVaultOnlyDriverProducesLastStepWithCount() {
        DriverSet from = withVaultOnlyDriver(VaultMappingTest.model());
        DriverSet to = VaultMappingTest.model();
        String oldDn = VaultMapping.driverDn(DS, "Old");
        FakeVault vault = vaultWithOldSubtree(oldDn);

        Plan p = Plan.of(ModelDiff.of(from, to), to, DS, Secrets.none(), "none", Map.of(), true, null,
            List.of("Old"), vault);

        assertTrue(p.text("stg", DS), p.deleteDriverRefusals.isEmpty());
        assertEquals(List.of("Old"), List.copyOf(p.driversDeleted));
        assertEquals(Integer.valueOf(4), p.deletedObjectCounts.get("Old"));
        Plan.Step last = p.steps.get(p.steps.size() - 1);
        assertEquals(Plan.Op.DELETE_SUBTREE, last.op);
        assertEquals(oldDn, last.dn);
        assertTrue(last.description, last.description.contains("(4 objects)"));
        assertEquals(4, last.subtreeDns.size());
        // deepest first: the driver object (fewest RDNs) is last
        assertEquals(oldDn, last.subtreeDns.get(last.subtreeDns.size() - 1));
        assertTrue(p.touchedDns.containsAll(last.subtreeDns));
    }

    @Test
    public void deleteDriverInTheTreeIsRefused() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();   // "AD" is in both — nothing removed
        Plan p = Plan.of(ModelDiff.of(from, to), to, DS, Secrets.none(), "none", Map.of(), true, null,
            List.of("AD"), new FakeVault());
        assertEquals(1, p.deleteDriverRefusals.size());
        assertTrue(p.deleteDriverRefusals.get(0), p.deleteDriverRefusals.get(0).contains("in the tree"));
        assertTrue(p.driversDeleted.isEmpty());
        assertTrue(p.steps.stream().noneMatch(s -> s.op == Plan.Op.DELETE_SUBTREE));
    }

    @Test
    public void deleteDriverUnknownIsRefused() {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Plan p = Plan.of(ModelDiff.of(from, to), to, DS, Secrets.none(), "none", Map.of(), true, null,
            List.of("Ghost"), new FakeVault());
        assertEquals(1, p.deleteDriverRefusals.size());
        assertTrue(p.deleteDriverRefusals.get(0), p.deleteDriverRefusals.get(0).contains("not found in the vault"));
    }

    @Test
    public void deleteDriverRunningIsRefused() {
        DriverSet from = withVaultOnlyDriver(VaultMappingTest.model());
        DriverSet to = VaultMappingTest.model();
        String oldDn = VaultMapping.driverDn(DS, "Old");
        FakeVault vault = vaultWithOldSubtree(oldDn);
        vault.setDriverState(oldDn, Vault.STATE_RUNNING);

        Plan p = Plan.of(ModelDiff.of(from, to), to, DS, Secrets.none(), "none", Map.of(), true, null,
            List.of("Old"), vault);

        assertEquals(1, p.deleteDriverRefusals.size());
        assertTrue(p.deleteDriverRefusals.get(0), p.deleteDriverRefusals.get(0).contains("running"));
        assertTrue(p.deleteDriverRefusals.get(0), p.deleteDriverRefusals.get(0).contains("stop it first"));
        assertTrue(p.driversDeleted.isEmpty());
        assertTrue(p.steps.stream().noneMatch(s -> s.op == Plan.Op.DELETE_SUBTREE));
    }

    @Test
    public void deleteDriverStepAbsentWithoutTheFlag() {
        DriverSet from = withVaultOnlyDriver(VaultMappingTest.model());
        DriverSet to = VaultMappingTest.model();
        Plan p = plan(from, to, Secrets.none(), "none");   // no --delete-driver
        assertTrue(p.steps.stream().noneMatch(s -> s.op == Plan.Op.DELETE_SUBTREE));
        assertTrue(p.driversDeleted.isEmpty());
        assertTrue(p.deleteDriverRefusals.isEmpty());
        assertTrue(p.notes.toString(), p.notes.stream().anyMatch(n -> n.contains("Old") && n.contains("--delete-driver to remove it explicitly")));
    }

    @Test
    public void engineControlValuesAbsentFromTreeAreNotRemoved() throws IOException {
        DriverSet from = VaultMappingTest.model();
        DriverSet to = VaultMappingTest.model();
        Driver live = from.drivers.get(0);
        live.config.put(Driver.ENGINE_CONTROL_VALUES, ValidatorTest.xml("<engine-control-values><engine-control name=\"x\">1</engine-control></engine-control-values>"));
        Plan p = plan(from, to, Secrets.none(), "none");
        assertTrue(p.text("stg", DS), p.steps.stream().noneMatch(s -> VaultMapping.ENGINE_CONTROL_VALUES.equals(s.attr)));
        assertTrue(p.notes.toString(), p.notes.stream().anyMatch(n -> n.contains("engine control values")));
    }
}
