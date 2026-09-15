package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The as-code tree layout for entitlements (docs/entitlements.md §2): one file per
 * entitlement under {@code drivers/<d>/entitlements/}, listed with its stamps in the
 * driver's own manifest ({@code driver.xml}) the way policies/resources are.
 */
public class EntitlementAsCodeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    private static DriverSet sample() {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";

        Driver loopback = new Driver("Loopback");
        loopback.dn = "cn=Loopback,cn=driverset1,o=system";
        Entitlement plain = new Entitlement("TestAccess",
            xml("<entitlement conflict-resolution=\"priority\" description=\"\" display-name=\"Group\">"
                + "<values multi-valued=\"true\"><value>a</value><value>b</value></values></entitlement>"));
        loopback.entitlements.add(plain);
        Entitlement packaged = new Entitlement("PkgEnt",
            xml("<entitlement conflict-resolution=\"union\" description=\"d\" display-name=\"P\">"
                + "<values multi-valued=\"false\"/></entitlement>"));
        packaged.meta.put("dirxml-pkgguid", "GUID-1");
        packaged.meta.put("dirxml-pkgassociationid", "ASSOC-1");
        loopback.entitlements.add(packaged);
        ds.drivers.add(loopback);

        Driver noEnt = new Driver("NoEnt");
        noEnt.dn = "cn=NoEnt,cn=driverset1,o=system";
        ds.drivers.add(noEnt);

        return ds;
    }

    @Test
    public void layoutMatchesSpec() throws Exception {
        Path a = tmp.newFolder("a").toPath();
        AsCodeWriter.write(sample(), a);

        Path driverDir = a.resolve("drivers/Loopback");
        assertTrue(Files.exists(driverDir.resolve("entitlements/PkgEnt.xml")));
        assertTrue(Files.exists(driverDir.resolve("entitlements/TestAccess.xml")));

        String manifest = Files.readString(driverDir.resolve("driver.xml"));
        assertTrue(manifest.contains("<entitlement name=\"TestAccess\" file=\"entitlements/TestAccess.xml\""));
        assertTrue(manifest.contains("<entitlement name=\"PkgEnt\" file=\"entitlements/PkgEnt.xml\""));
        assertTrue(manifest.contains("dirxml-pkgguid"));
        assertTrue(manifest.contains("GUID-1"));

        assertTrue(Files.readString(driverDir.resolve("entitlements/TestAccess.xml")).contains("display-name=\"Group\""));
    }

    @Test
    public void driverWithoutEntitlementsWritesNoEntitlementsDir() throws Exception {
        Path a = tmp.newFolder("a").toPath();
        AsCodeWriter.write(sample(), a);
        assertTrue(java.nio.file.Files.notExists(a.resolve("drivers/NoEnt/entitlements")));
    }

    @Test
    public void writeIsIdempotentAndReadPreservesEverything() throws Exception {
        DriverSet ds = sample();
        Path a = tmp.newFolder("a").toPath();
        Path b = tmp.newFolder("b").toPath();

        AsCodeWriter.write(ds, a);
        DriverSet back = AsCodeReader.read(a);
        AsCodeWriter.write(back, b);

        assertEquals(AsCodeRoundTripTest.snapshot(a), AsCodeRoundTripTest.snapshot(b));

        Driver loopback = back.driver("Loopback");
        assertEquals(2, loopback.entitlements.size());
        Entitlement plain = loopback.entitlement("TestAccess");
        assertNotNull(plain);
        assertEquals("Group", plain.displayName());
        assertEquals("priority", plain.conflictResolution());
        assertEquals("true", plain.multiValued());

        Entitlement pkg = loopback.entitlement("PkgEnt");
        assertNotNull(pkg);
        assertEquals("GUID-1", pkg.meta.get("dirxml-pkgguid"));
        assertEquals("ASSOC-1", pkg.meta.get("dirxml-pkgassociationid"));
        assertEquals("false", pkg.multiValued());

        assertTrue(back.driver("NoEnt").entitlements.isEmpty());
    }

    @Test
    public void rewritingAnUnchangedTreeChangesNoFile() throws Exception {
        Path a = tmp.newFolder("a").toPath();
        DriverSet ds = sample();
        AsCodeWriter.write(ds, a);
        Map<String, String> before = AsCodeRoundTripTest.snapshot(a);

        DriverSet reread = AsCodeReader.read(a);
        AsCodeWriter.write(reread, a);
        Map<String, String> after = AsCodeRoundTripTest.snapshot(a);

        assertEquals(before, after);
    }
}
