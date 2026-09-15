package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.sim.Xds;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Entitlement operations (Track W step W4b — docs/entitlements.md §2):
 * {@code entitlement.add/set/remove} through {@link Transaction}, and
 * {@code entitlement.list/show} through {@link ReadCli}.
 */
public class EntitlementOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PROCESS_REFERENCING_TEST_ACCESS =
        "<process id=\"p1\" process-type=\"Normal\">"
        + "<provision-activity activity-id=\"Prov\"/>"
        + "<data-items activity-id=\"Prov\">"
        + "<data-item name=\"DirXML-Entitlement-DN\" data-type=\"string\" "
        + "source=\"'cn=TestAccess,cn=Loopback,cn=driverset1,o=system'\"/>"
        + "</data-items></process>";

    /** One driver ("Loopback") with two entitlements ("TestAccess", "Unreferenced") and one PRD
     *  whose provision activity's {@code DirXML-Entitlement-DN} names "TestAccess". */
    private Path tree() throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";
        Driver d = new Driver("Loopback");
        d.dn = "cn=Loopback,cn=driverset1,o=system";
        d.entitlements.add(new Entitlement("TestAccess", com.pointblue.dirxml.dev.xml.CanonicalXml.parse(
            "<entitlement conflict-resolution=\"priority\" description=\"\" display-name=\"TA\">"
            + "<values multi-valued=\"true\"/></entitlement>").getDocumentElement()));
        d.entitlements.add(new Entitlement("Unreferenced", com.pointblue.dirxml.dev.xml.CanonicalXml.parse(
            "<entitlement conflict-resolution=\"priority\" description=\"\" display-name=\"U\">"
            + "<values multi-valued=\"false\"/></entitlement>").getDocumentElement()));
        ds.drivers.add(d);

        Provisioning p = new Provisioning();
        p.dn = "cn=AppConfig," + d.dn;
        Prd prd = new Prd("Grant");
        prd.definition = com.pointblue.dirxml.dev.xml.CanonicalXml.parse(
            "<prov-req-defn status=\"Active\">" + PROCESS_REFERENCING_TEST_ACCESS + "</prov-req-defn>").getDocumentElement();
        prd.process = Xds.childrenByName(prd.definition, "process").get(0);
        prd.properties.put("status", java.util.List.of("Active"));
        p.prds.add(prd);
        d.provisioning = p;

        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    private static Result run(Path tree, Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    @Test
    public void addWithValuesBuildsTheDocument() throws Exception {
        Path t = tree();
        Result r = run(t, new EntitlementOps.Add("Loopback", "NewEnt", "New Display", "a description",
            true, "union", "v1,v2, v3", null));
        assertTrue(r.text(), r.ok());
        assertTrue(r.touched.toString(), r.touched.contains("drivers/Loopback/entitlements/NewEnt"));

        DriverSet ds = AsCodeReader.read(t);
        Entitlement e = ds.driver("Loopback").entitlement("NewEnt");
        assertNotNull(e);
        assertEquals("New Display", e.displayName());
        assertEquals("a description", e.description());
        assertEquals("union", e.conflictResolution());
        assertEquals("true", e.multiValued());
        String xml = com.pointblue.dirxml.dev.xml.CanonicalXml.serialize(e.definition);
        assertTrue(xml, xml.contains("<value>v1</value>"));
        assertTrue(xml, xml.contains("<value>v2</value>"));
        assertTrue(xml, xml.contains("<value>v3</value>"));
    }

    @Test
    public void addRefusesADuplicateName() throws Exception {
        Path t = tree();
        Result r = run(t, new EntitlementOps.Add("Loopback", "TestAccess", null, null, false, null, null, null));
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("already exists"));
    }

    @Test
    public void setChangesAttributesAndValuesInPlace() throws Exception {
        Path t = tree();
        Result r = run(t, new EntitlementOps.Set("Loopback", "Unreferenced", "Changed", "new descr",
            true, "union", "x,y", null));
        assertTrue(r.text(), r.ok());

        DriverSet ds = AsCodeReader.read(t);
        Entitlement e = ds.driver("Loopback").entitlement("Unreferenced");
        assertEquals("Changed", e.displayName());
        assertEquals("new descr", e.description());
        assertEquals("union", e.conflictResolution());
        assertEquals("true", e.multiValued());
        String xml = com.pointblue.dirxml.dev.xml.CanonicalXml.serialize(e.definition);
        assertTrue(xml, xml.contains("<value>x</value>"));
        assertTrue(xml, xml.contains("<value>y</value>"));
    }

    @Test
    public void setWithDefinitionFileReplacesTheWholeDocument() throws Exception {
        Path t = tree();
        Path defFile = tmp.newFile("def.xml").toPath();
        java.nio.file.Files.writeString(defFile,
            "<entitlement conflict-resolution=\"union\" description=\"d\" display-name=\"Whole\">"
            + "<values multi-valued=\"false\"/></entitlement>");
        Result r = run(t, new EntitlementOps.Set("Loopback", "Unreferenced", null, null, null, null, null, defFile.toString()));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(t);
        assertEquals("Whole", ds.driver("Loopback").entitlement("Unreferenced").displayName());
    }

    @Test
    public void removeIsRefusedWhileAProvisionActivityNamesIt() throws Exception {
        Path t = tree();
        Result r = run(t, new EntitlementOps.Remove("Loopback", "TestAccess"));
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("Loopback/Grant"));
        assertTrue(r.refusal, r.refusal.contains("--force does not override"));

        // --force does not override this refusal either
        Result forced = Transaction.open(t).run(new EntitlementOps.Remove("Loopback", "TestAccess"), false, true);
        assertFalse(forced.ok());
    }

    @Test
    public void removeSucceedsForAnUnreferencedEntitlement() throws Exception {
        Path t = tree();
        Result r = run(t, new EntitlementOps.Remove("Loopback", "Unreferenced"));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(t);
        assertEquals(1, ds.driver("Loopback").entitlements.size());
        assertNotNull(ds.driver("Loopback").entitlement("TestAccess"));
    }

    @Test
    public void listShowsEveryEntitlement() throws Exception {
        Path t = tree();
        String out = capture(() -> ReadCli.entitlementList(new String[]{"entitlement.list", t.toString()}));
        assertTrue(out, out.contains("Loopback/TestAccess"));
        assertTrue(out, out.contains("Loopback/Unreferenced"));
        assertTrue(out, out.contains("2 entitlement(s)"));
    }

    @Test
    public void showReportsDocumentAndReferencingPrds() throws Exception {
        Path t = tree();
        String out = capture(() -> ReadCli.entitlementShow(
            new String[]{"entitlement.show", t.toString(), "--driver", "Loopback", "--name", "TestAccess"}));
        assertTrue(out, out.contains("Loopback/TestAccess"));
        assertTrue(out, out.contains("TA"));
        assertTrue(out, out.contains("Loopback/Grant"));
    }

    private interface CliCall {
        int run() throws Exception;
    }

    private static String capture(CliCall call) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            int rc = call.run();
            assertEquals(0, rc);
        } finally {
            System.setOut(old);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
