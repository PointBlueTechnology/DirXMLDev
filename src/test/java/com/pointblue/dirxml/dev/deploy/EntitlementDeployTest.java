package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.EntitlementOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Entitlements through ModelDiff -&gt; Plan (docs/entitlements.md §2), mirroring {@code ProvisioningDeployTest}. */
public class EntitlementDeployTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String ENT_XML =
        "<entitlement conflict-resolution=\"priority\" description=\"\" display-name=\"Group\">"
        + "<values multi-valued=\"true\"><value>a</value></values></entitlement>";

    /** A tree with one driver ("Loopback"); {@code withEntitlement} adds one existing entitlement. */
    private Path tree(boolean withEntitlement, boolean packaged) throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = DS;
        Driver d = new Driver("Loopback");
        d.dn = "cn=Loopback," + DS;
        if (withEntitlement) {
            Entitlement e = new Entitlement("Existing", CanonicalXml.parse(ENT_XML).getDocumentElement());
            if (packaged) {
                e.meta.put("dirxml-pkgguid", "PKG-1");
                e.meta.put("dirxml-pkgassociationid", "ASSOC-1");
            }
            d.entitlements.add(e);
        }
        ds.drivers.add(d);
        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    @Test
    public void addedEntitlementDiffsAndPlansAnAddStep() throws Exception {
        Path t = tree(false, false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        Entitlement e = new Entitlement("TestAccess", CanonicalXml.parse(ENT_XML).getDocumentElement());
        to.driver("Loopback").entitlements.add(e);

        ModelDiff diff = ModelDiff.of(from, to);
        List<ModelDiff.Change> changes = diff.changes();
        assertEquals(1, changes.size());
        assertEquals(ModelDiff.Kind.ENTITLEMENT_ADDED, changes.get(0).kind);
        assertEquals("drivers/Loopback/entitlements/TestAccess", changes.get(0).path);
        assertTrue("no engine restart for an entitlement change", diff.affectedDrivers().isEmpty());

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertTrue(plan.restart.isEmpty());
        assertEquals(1, plan.steps.size());
        Plan.Step add = plan.steps.get(0);
        assertEquals(Plan.Op.ADD, add.op);
        assertEquals("cn=TestAccess,cn=Loopback," + DS, add.dn);
        assertEquals(List.of("Top", "DirXML-Entitlement"), add.objectClasses);
        String xml = new String(add.values.get("XmlData").get(0), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("display-name=\"Group\""));
    }

    @Test
    public void changedEntitlementProducesOneModifyStep() throws Exception {
        Path t = tree(true, false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        Entitlement e = to.driver("Loopback").entitlement("Existing");
        e.definition = CanonicalXml.parse(ENT_XML.replace("Group", "Group2")).getDocumentElement();

        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(1, diff.changes().size());
        assertEquals(ModelDiff.Kind.ENTITLEMENT_CHANGED, diff.changes().get(0).kind);

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertEquals(1, plan.steps.size());
        Plan.Step s = plan.steps.get(0);
        assertEquals(Plan.Op.MODIFY, s.op);
        assertEquals("XmlData", s.attr);
        assertEquals("cn=Existing,cn=Loopback," + DS, s.dn);
        String xml = new String(s.values.get("XmlData").get(0), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains("Group2"));
    }

    @Test
    public void removedEntitlementProducesADeleteStep() throws Exception {
        Path t = tree(true, false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        to.driver("Loopback").entitlements.clear();

        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(1, diff.changes().size());
        assertEquals(ModelDiff.Kind.ENTITLEMENT_REMOVED, diff.changes().get(0).kind);

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertEquals(1, plan.steps.size());
        Plan.Step s = plan.steps.get(0);
        assertEquals(Plan.Op.DELETE, s.op);
        assertEquals("cn=Existing,cn=Loopback," + DS, s.dn);
    }

    @Test
    public void customizedPackagedEntitlementCarriesStampsAndAContentChecksum() throws Exception {
        Path t = tree(true, true);
        DriverSet from = AsCodeReader.read(t);
        Result r = Transaction.open(t).run(
            new EntitlementOps.Set("Loopback", "Existing", "Group2", null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok());
        DriverSet to = AsCodeReader.read(t);

        Plan plan = Plan.of(ModelDiff.of(from, to), to, DS, Secrets.none(), "none", null, true, t);
        String dn = "cn=Existing,cn=Loopback," + DS;
        boolean aux = false;
        String checksum = null;
        String initial = null;
        for (Plan.Step s : plan.steps) {
            if (!s.dn.equals(dn)) {
                continue;
            }
            if (s.op == Plan.Op.AUX_CLASS) {
                aux = true;
            }
            if ("DirXML-pkgChecksum".equals(s.attr)) {
                checksum = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
            }
            if ("DirXML-pkgInitialState".equals(s.attr)) {
                initial = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
            }
        }
        assertTrue("aux class step for a stamped object", aux);
        assertNotNull(checksum);
        assertNotNull("baseline becomes the initial state", initial);
        assertTrue(initial, initial.contains("Group") && !initial.contains("Group2"));
    }
}
