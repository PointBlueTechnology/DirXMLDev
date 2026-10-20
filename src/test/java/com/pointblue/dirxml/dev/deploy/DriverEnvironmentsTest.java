package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Environments that hold different drivers, or name a driver differently: the tree says which environments a
 * driver goes to ({@code driver.xml} environments) and what an environment's vault calls it
 * ({@code drivers/<d>.name} in its overrides file); compare, deploy and import follow both.
 */
public class DriverEnvironmentsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=dvs,o=system";

    /** The lab model plus a loopback driver only QA gets. */
    private static DriverSet withQaOnly() {
        DriverSet ds = VaultMappingTest.model();
        Driver qa = new Driver("QA Loopback");
        qa.dn = "cn=QA Loopback," + DS;
        qa.shimClass = "com.example.Loopback";
        qa.config.put(Driver.CONFIG_VALUES, ValidatorTest.xml("<configuration-values><definitions/></configuration-values>"));
        qa.environments.add("QA");
        ds.drivers.add(qa);
        return ds;
    }

    @Test
    public void theListIsKeptInDriverXml() throws Exception {
        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(withQaOnly(), dir);
        assertTrue(Files.readString(dir.resolve("drivers/QA Loopback/driver.xml"), StandardCharsets.UTF_8).contains("environments=\"QA\""));
        assertFalse(Files.readString(dir.resolve("drivers/AD/driver.xml"), StandardCharsets.UTF_8).contains("environments="));
        DriverSet back = AsCodeReader.read(dir);
        assertEquals(List.of("QA"), back.driver("QA Loopback").environments);
        assertTrue(back.driver("AD").environments.isEmpty());
    }

    @Test
    public void anEnvironmentGetsOnlyItsDrivers() throws Exception {
        DriverSet prod = withQaOnly();
        Overrides.Applied a = Overrides.apply(prod, "PROD");
        assertNull(prod.driver("QA Loopback"));
        assertNotNull(prod.driver("AD"));
        assertEquals(List.of("QA Loopback"), a.notDeployed);
        assertTrue(a.summary(), a.summary().contains("not deployed to 'PROD'"));
        assertNotNull(a.refusalFor(List.of("QA Loopback")));
        assertNull(a.refusalFor(List.of("AD")));

        DriverSet qa = withQaOnly();
        assertTrue(Overrides.apply(qa, "qa").notDeployed.isEmpty());   // names match in any case
        assertNotNull(qa.driver("QA Loopback"));

        // PROD's vault never had the QA driver: nothing to deploy, rather than "added"
        FakeVault prodVault = VaultDiffTest.seedVault(VaultMappingTest.model());
        DriverSet to = withQaOnly();
        Overrides.apply(to, "PROD");
        ModelDiff diff = ModelDiff.of(VaultDiff.fromVault(prodVault, DS), to);
        assertTrue(diff.text(), diff.isEmpty());
    }

    @Test
    public void aDriverNamedDifferentlyInAnEnvironmentComparesUnderThatName() throws Exception {
        DriverSet qaModel = VaultMappingTest.model();
        assertTrue(qaModel.renameDriver("AD", "AD-QA"));
        FakeVault qaVault = VaultDiffTest.seedVault(qaModel);

        DriverSet to = VaultMappingTest.model();
        to.overrides.put("QA", new LinkedHashMap<>(Map.of("drivers/AD.name", "AD-QA")));
        Overrides.Applied a = Overrides.apply(to, "QA");
        assertEquals(Map.of("AD", "AD-QA"), a.renamed);
        Driver renamed = to.driver("AD-QA");
        assertNotNull(renamed);
        assertEquals("cn=AD-QA," + DS, renamed.dn);
        assertTrue(renamed.links.stream().anyMatch(l -> l.ref.equals("drivers/AD-QA/PKG-GCVs")));
        assertEquals(List.of("AD-QA"), a.vaultNames(List.of("AD")));
        ModelDiff diff = ModelDiff.of(VaultDiff.fromVault(qaVault, DS), to);
        assertTrue(diff.text(), diff.isEmpty());

        // and back: what QA's vault holds takes the tree's name, matching the tree as it is
        DriverSet live = VaultDiff.fromVault(qaVault, DS);
        assertTrue(Overrides.toTreeNames(live, Map.of("drivers/AD.name", "AD-QA")).isEmpty());
        assertNotNull(live.driver("AD"));
        ModelDiff back = ModelDiff.of(live, VaultMappingTest.model());
        assertTrue(back.text(), back.isEmpty());

        // a name key is a valid key for a driver of the tree, and only for one
        assertTrue(Overrides.resolve(VaultMappingTest.model(), "drivers/AD.name").resolved());
        assertFalse(Overrides.resolve(VaultMappingTest.model(), "drivers/Nope.name").resolved());
        assertFalse("a GCV whose name ends in .name", Overrides.isName("drivers/AD.gcv.drv.domain.dns.name"));
        assertFalse(Overrides.isName("drivers/AD.shim.host.name"));
        assertTrue(Overrides.isName("drivers/AD Driver.name"));
    }

    @Test
    public void anImportKeepsTheDriversThatEnvironmentDoesntGet() {
        // reading PROD, which lacks the QA driver: the tree keeps it, list and all
        DriverSet live = VaultMappingTest.model();
        List<String> notes = DriverEnvironments.keepOnImport(live, "PROD", withQaOnly(), false, null);
        assertTrue(notes.toString(), notes.isEmpty());
        assertEquals(List.of("QA"), live.driver("QA Loopback").environments);

        // PROD holding a driver of that name anyway: the tree's stays, and the import says so
        DriverSet live2 = VaultMappingTest.model();
        Driver stray = new Driver("QA Loopback");
        stray.shimClass = "com.example.Other";
        live2.drivers.add(stray);
        notes = DriverEnvironments.keepOnImport(live2, "PROD", withQaOnly(), false, null);
        assertEquals("com.example.Loopback", live2.driver("QA Loopback").shimClass);
        assertTrue(notes.toString(), notes.get(0).contains("kept as the tree has it"));

        // reading QA into a tree without its driver: marked as QA's only
        DriverSet fromQa = withQaOnly();
        fromQa.driver("QA Loopback").environments.clear();
        notes = DriverEnvironments.keepOnImport(fromQa, "QA", VaultMappingTest.model(), true, null);
        assertEquals(List.of("QA"), fromQa.driver("QA Loopback").environments);
        assertTrue(notes.toString(), notes.get(0).contains("new to the tree"));
        // an update of some drivers only looks at those
        DriverSet some = withQaOnly();
        some.driver("QA Loopback").environments.clear();
        assertTrue(DriverEnvironments.keepOnImport(some, "QA", VaultMappingTest.model(), true, List.of("AD")).isEmpty());
        assertTrue(some.driver("QA Loopback").environments.isEmpty());
    }

    @Test
    public void twoEnvironmentsSideBySide() {
        DriverSet tree = withQaOnly();
        tree.overrides.put("QA", new LinkedHashMap<>(Map.of("drivers/AD.shim-auth-server", "ldap://qa")));

        DriverSet prod = VaultMappingTest.model();
        DriverSet qa = withQaOnly();
        qa.driver("QA Loopback").environments.clear();               // a vault records no lists
        qa.driver("AD").shimAuthServer = "ldap://qa";               // expected: QA's file sets it
        Overrides.Target users = Overrides.resolve(qa, "drivers/AD.gcv.drv.users");
        users.set("qa-users");                                       // not expected: no file sets it
        Policy sub = (Policy) qa.resolve("drivers/AD/subscriber/sub-ctp");
        sub.content = ValidatorTest.xml("<policy><rule><description>qa only</description><conditions/><actions/></rule></policy>");

        EnvironmentCompare c = EnvironmentCompare.of(prod, "PROD", qa, "QA", tree);
        assertEquals(1, c.onlyIn.size());
        assertEquals("QA Loopback", c.onlyIn.get(0).driver);
        assertEquals("QA", c.onlyIn.get(0).env);
        assertTrue(c.onlyIn.get(0).expected);

        Map<String, EnvironmentCompare.Value> byKey = new LinkedHashMap<>();
        for (EnvironmentCompare.Value v : c.values) {
            byKey.put(v.key, v);
        }
        assertTrue(byKey.toString(), byKey.get("drivers/AD.shim-auth-server").expected);
        EnvironmentCompare.Value u = byKey.get("drivers/AD.gcv.drv.users");
        assertFalse(u.expected);
        assertEquals("users", u.a);
        assertEquals("qa-users", u.b);
        assertEquals(2, byKey.size());

        // the values set aside, the policy is what remains
        List<String> drift = new ArrayList<>();
        for (ModelDiff.Change ch : c.drift.changes()) {
            drift.add(ch.kind + " " + ch.path);
        }
        assertEquals(drift.toString(), 1, drift.size());
        assertTrue(drift.toString(), drift.get(0).contains("sub-ctp"));
        assertTrue(c.json(), c.json().contains("\"expected\":true"));
    }

    @Test
    public void aSecretAnswersToEitherName() throws Exception {
        Path f = tmp.newFile("qa.secrets").toPath();
        Files.writeString(f, "AD." + Secrets.SHIM_AUTH + " = s3cret\n", StandardCharsets.UTF_8);
        Secrets s = Secrets.load(f).withNames(Map.of("AD", "AD-QA"));
        assertTrue(s.has(Secrets.shimAuth("AD-QA")));
        assertTrue(s.has(Secrets.shimAuth("AD")));
        assertFalse(Secrets.load(f).has(Secrets.shimAuth("AD-QA")));
    }
}
