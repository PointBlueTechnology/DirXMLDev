package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.PackageStrip;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A stripped driver deploys as the removal of every package stamp the vault still holds: the five
 * {@code DirXML-pkg*} attributes and {@code DirXML-PkgItemAux} on each object, the driver's record,
 * extension cache and both package aux classes — and nothing of the kind without the mark.
 */
public class PackageStripDeployTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String GUID = "B5PAGQ5E_201005261601510810;com.netiqcorporation.novluabase;4.8.0.20190927160316;User Application Base;NOVLUABASE";
    private static final String POLICY = "<policy><rule><description>x</description><conditions/><actions/></rule></policy>";
    private static final String ENT = "<entitlement conflict-resolution=\"priority\" display-name=\"Group\"><values multi-valued=\"true\"/></entitlement>";

    /** The packaged driver as the vault holds it (stamps on the driver, a policy and an entitlement). */
    private static DriverSet packaged() {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = DS;
        Driver d = new Driver("Loop");
        d.dn = "cn=Loop," + DS;
        d.shimClass = "com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim";
        d.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        d.meta.put("dirxml-pkgguid", GUID);
        d.meta.put("dirxml-pkgextensions", "<filter/>");
        Policy p = new Policy("NOVLUABASE-smp", Scope.DRIVER, "Loop", ValidatorTest.xml(POLICY));
        stamp(p.meta, "A1");
        d.policies.add(p);
        Entitlement e = new Entitlement("Group", ValidatorTest.xml(ENT));
        stamp(e.meta, "A3");
        d.entitlements.add(e);
        ds.drivers.add(d);
        return ds;
    }

    private static void stamp(Map<String, String> meta, String assoc) {
        meta.put("dirxml-pkgguid", GUID);
        meta.put("dirxml-pkgassociationid", assoc);
        meta.put("dirxml-pkgchecksum", "123");
        meta.put("dirxml-pkglinkages", "<linkages/>");
    }

    /** The same driver with every stamp removed; {@code marked} says {@code package.strip} did it. */
    private static DriverSet stripped(boolean marked) {
        DriverSet ds = packaged();
        Driver d = ds.driver("Loop");
        PackageStrip.strip(d.meta);
        PackageStrip.strip(d.policies.get(0).meta);
        PackageStrip.strip(d.entitlements.get(0).meta);
        if (marked) {
            d.meta.put(PackageStrip.STRIPPED_KEY, "true");
        }
        return ds;
    }

    private Path write(DriverSet ds) throws Exception {
        Path t = tmp.newFolder("tree-" + System.nanoTime()).toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    private static List<Plan.Step> ofKind(Plan plan, Plan.Op op) {
        List<Plan.Step> out = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            if (s.op == op) {
                out.add(s);
            }
        }
        return out;
    }

    @Test
    public void markedTreePlansTheRemovalOfEveryStamp() throws Exception {
        Path t = write(stripped(true));
        DriverSet to = AsCodeReader.read(t);
        ModelDiff diff = ModelDiff.of(packaged(), to);
        String text = diff.text();
        assertTrue(text, text.contains("package stamps of driver Loop"));
        assertTrue("the linkage record counts as removed on a stripped driver", text.contains("dirxml-pkglinkages"));
        assertTrue(text, text.contains("stripped of its packages"));

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        String policyDn = "cn=NOVLUABASE-smp,cn=Loop," + DS;
        String entDn = "cn=Group,cn=Loop," + DS;
        String driverDn = "cn=Loop," + DS;
        List<Plan.Step> drops = ofKind(plan, Plan.Op.DROP_AUX_CLASS);
        assertEquals(drops.toString(), 3, drops.size());
        for (String dn : List.of(policyDn, entDn)) {
            for (String attr : List.of(VaultMapping.PKG_GUID, VaultMapping.PKG_ASSOC, VaultMapping.PKG_CHECKSUM, VaultMapping.PKG_LINKAGES, VaultMapping.PKG_INITIAL_STATE)) {
                assertTrue(dn + " " + attr, plan.steps.stream().anyMatch(s -> s.op == Plan.Op.MODIFY && s.dn.equals(dn) && attr.equals(s.attr) && s.values.get(attr).isEmpty()));
            }
            assertTrue(dn, drops.stream().anyMatch(s -> s.dn.equals(dn) && s.objectClasses.equals(List.of(VaultMapping.PKG_ITEM_AUX))));
        }
        assertTrue(plan.steps.stream().anyMatch(s -> s.op == Plan.Op.MODIFY && s.dn.equals(driverDn) && VaultMapping.PKG_GUID.equals(s.attr) && s.values.get(VaultMapping.PKG_GUID).isEmpty()));
        assertTrue(plan.steps.stream().anyMatch(s -> s.op == Plan.Op.MODIFY && s.dn.equals(driverDn) && VaultMapping.PKG_EXTENSIONS.equals(s.attr) && s.values.get(VaultMapping.PKG_EXTENSIONS).isEmpty()));
        assertTrue(drops.stream().anyMatch(s -> s.dn.equals(driverDn) && s.objectClasses.contains(VaultMapping.PKG_TARGET_AUX) && s.objectClasses.contains(VaultMapping.PKG_ITEM_AUX)));
        // attributes go before the class that carries them
        int lastModify = -1;
        int drop = -1;
        for (int i = 0; i < plan.steps.size(); i++) {
            Plan.Step s = plan.steps.get(i);
            if (s.dn.equals(policyDn) && s.op == Plan.Op.MODIFY) {
                lastModify = i;
            }
            if (s.dn.equals(policyDn) && s.op == Plan.Op.DROP_AUX_CLASS) {
                drop = i;
            }
        }
        assertTrue(lastModify < drop);
        assertTrue(plan.notes.toString(), plan.notes.stream().anyMatch(n -> n.contains("stripped of its packages")));
    }

    @Test
    public void unmarkedTreeNeverRemovesStamps() throws Exception {
        Path t = write(stripped(false));
        DriverSet to = AsCodeReader.read(t);
        ModelDiff diff = ModelDiff.of(packaged(), to);
        assertFalse("a tree that merely lacks the linkage record is not asking for its removal", diff.text().contains("dirxml-pkglinkages"));
        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertTrue(ofKind(plan, Plan.Op.DROP_AUX_CLASS).isEmpty());
        assertFalse(plan.steps.stream().anyMatch(s -> VaultMapping.PKG_ASSOC.equals(s.attr) || VaultMapping.PKG_INITIAL_STATE.equals(s.attr)));
    }

    @Test
    public void libraryMarkPlansTheDriverSetRecordRemoval() throws Exception {
        DriverSet vault = packaged();
        vault.meta.put("dirxml-pkgguid", "SETPKG;com.x.set;1.0.0");
        DriverSet tree = stripped(true);
        tree.meta.put(PackageStrip.STRIPPED_KEY, "true");
        Path t = write(tree);
        DriverSet to = AsCodeReader.read(t);
        ModelDiff diff = ModelDiff.of(vault, to);
        assertTrue(diff.text(), diff.text().contains("package stamps of the driver set"));
        assertTrue("no restart for the driver set's own stamps", diff.affectedDrivers().stream().noneMatch(n -> n.equals("Other")));
        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertTrue(plan.steps.stream().anyMatch(s -> s.op == Plan.Op.MODIFY && s.dn.equals(DS) && VaultMapping.PKG_GUID.equals(s.attr)));
        assertTrue(plan.steps.stream().anyMatch(s -> s.op == Plan.Op.DROP_AUX_CLASS && s.dn.equals(DS)));
    }

    /** End to end against the fake vault: the stamps and aux classes are gone afterwards and the verify pass agrees. */
    @Test
    public void deployRemovesStampsAndAuxClasses() throws Exception {
        FakeVault vault = new FakeVault();
        vault.seed(entry(DS, List.of("Top", "DirXML-DriverSet"), Map.of()));
        vault.seed(entry("cn=Library," + DS, List.of("Top", "DirXML-Library"), Map.of()));
        String driverDn = "cn=Loop," + DS;
        Map<String, List<byte[]>> da = VaultMapping.driverAttributes(packaged().driver("Loop"));
        da.put("DirXML-pkgGUID", Vault.value(GUID));
        da.put("DirXML-pkgExtensions", Vault.value("<filter/>"));
        vault.seed(entry(driverDn, List.of("Top", "DirXML-Driver", "DirXML-PkgTargetAux", "DirXML-PkgItemAux"), da));
        vault.seed(entry("cn=Subscriber," + driverDn, List.of("Top", "DirXML-Subscriber"), Map.of()));
        vault.seed(entry("cn=Publisher," + driverDn, List.of("Top", "DirXML-Publisher"), Map.of()));
        Map<String, List<byte[]>> pa = VaultMapping.attributes(packaged().driver("Loop").policies.get(0));
        stampAttrs(pa, "A1");
        vault.seed(entry("cn=NOVLUABASE-smp," + driverDn, List.of("Top", "DirXML-Rule", "DirXML-PkgItemAux"), pa));
        Map<String, List<byte[]>> ea = VaultMapping.entitlementAttributes(packaged().driver("Loop").entitlements.get(0));
        stampAttrs(ea, "A3");
        vault.seed(entry("cn=Group," + driverDn, List.of("Top", "DirXML-Entitlement", "DirXML-PkgItemAux"), ea));

        Path t = write(stripped(true));
        Deployer.Options o = new Deployer.Options();
        o.tree = t;
        o.env = new Environments.Environment("test", "ldaps://fake:636", "cn=admin,o=system", "pw", DS,
            Environments.Tier.STG, null, null, true, null, null);
        o.yes = true;
        Deployer.Result r = new Deployer(o, vault).run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.text(), r.verified);
        for (String dn : List.of(driverDn, "cn=NOVLUABASE-smp," + driverDn, "cn=Group," + driverDn)) {
            Vault.Entry e = vault.read(dn);
            assertNotNull(dn, e);
            for (String attr : e.attrs.keySet()) {
                assertFalse(dn + " still has " + attr, attr.toLowerCase().startsWith("dirxml-pkg"));
            }
            assertFalse(dn, e.hasClass("DirXML-PkgItemAux"));
            assertFalse(dn, e.hasClass("DirXML-PkgTargetAux"));
        }
        assertTrue("the content is untouched", vault.read("cn=NOVLUABASE-smp," + driverDn).attrs.containsKey("XmlData"));
    }

    private static void stampAttrs(Map<String, List<byte[]>> attrs, String assoc) {
        attrs.put("DirXML-pkgGUID", Vault.value(GUID));
        attrs.put("DirXML-pkgAssociationId", Vault.value(assoc));
        attrs.put("DirXML-pkgChecksum", Vault.value("123"));
        attrs.put("DirXML-pkgLinkages", Vault.value("<linkages/>"));
        attrs.put("DirXML-pkgInitialState", Vault.value(POLICY));
    }

    private static Vault.Entry entry(String dn, List<String> classes, Map<String, List<byte[]>> attrs) {
        Vault.Entry e = new Vault.Entry(dn);
        List<byte[]> oc = new ArrayList<>();
        for (String c : classes) {
            oc.add(c.getBytes(StandardCharsets.UTF_8));
        }
        e.attrs.put("objectClass", oc);
        e.attrs.putAll(attrs);
        return e;
    }
}
