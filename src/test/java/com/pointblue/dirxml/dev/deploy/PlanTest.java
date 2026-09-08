package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
