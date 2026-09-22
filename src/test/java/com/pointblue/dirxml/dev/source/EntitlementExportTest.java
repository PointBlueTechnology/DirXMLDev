package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.w3c.dom.Element;

/** Entitlements in Designer's driver export ({@code <entitlement-definition>} under the driver's children), both ways. */
public class EntitlementExportTest {

    private static final String EXPORT =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<driver-configuration dn=\"cn=AD,cn=driverset1,o=system\" driver-set-dn=\"cn=driverset1,o=system\" name=\"AD\">"
        + "<children>"
        + "<rule name=\"p1\"><policy><rule><description>x</description><conditions/><actions/></rule></policy></rule>"
        + "<entitlement-definition name=\"UserAccount\" package-id=\"PKG1\" pkg-assoc-id=\"A1\" checksum=\"77\">"
        + "<entitlement conflict-resolution=\"union\" description=\"Grants an account\" display-name=\"User Account\">"
        + "<values multi-valued=\"false\"/></entitlement></entitlement-definition>"
        + "<publisher><children/></publisher><subscriber><children/></subscriber>"
        + "</children></driver-configuration>";

    @Test
    public void readAndWriteBack() throws Exception {
        Element root = CanonicalXml.parse(EXPORT).getDocumentElement();
        DriverSet ds = ExportReader.read(root, "AD.xml");
        Driver ad = ds.driver("AD");
        assertNotNull(ad);
        assertEquals(1, ad.entitlements.size());
        Entitlement e = ad.entitlements.get(0);
        assertEquals("UserAccount", e.name);
        assertEquals("User Account", e.displayName());
        assertEquals("union", e.conflictResolution());
        assertEquals("entitlement", e.definition.getTagName());
        assertEquals("PKG1", com.pointblue.dirxml.dev.model.PackageStamps.packageId(e.meta));
        assertEquals("A1", e.meta.get("dirxml-pkgassociationid"));
        assertEquals("77", e.meta.get("dirxml-pkgchecksum"));

        String xml = ExportWriter.toXml(ds);
        assertTrue(xml, xml.contains("<entitlement-definition") && xml.contains("name=\"UserAccount\"") && xml.contains("package-id=\"PKG1\""));
        DriverSet again = ExportReader.read(CanonicalXml.parse(xml).getDocumentElement(), "again.xml");
        Entitlement e2 = again.driver("AD").entitlements.get(0);
        assertEquals(CanonicalXml.serialize(e.definition), CanonicalXml.serialize(e2.definition));
        assertEquals(e.meta, e2.meta);
    }

    /** The real thing (guarded): Designer's export of an Active Directory driver with three entitlements. */
    @Test
    public void designerExportOfAnActiveDirectoryDriver() throws Exception {
        Path f = Path.of(System.getProperty("user.home"), "Downloads", "Active Directory Driver.xml");
        assumeTrue("needs " + f, Files.isRegularFile(f));
        DriverSet ds = ExportReader.read(f);
        Driver ad = ds.driver("Active Directory Driver");
        assertNotNull(ad);
        assertEquals(3, ad.entitlements.size());
        assertNotNull(ad.entitlement("ExchangeMailbox"));
        assertEquals("Exchange Mailbox Entitlement", ad.entitlement("ExchangeMailbox").displayName());
        String xml = ExportWriter.toXml(ds);
        assertEquals(3, ExportReader.read(CanonicalXml.parse(xml).getDocumentElement(), "w.xml").driver("Active Directory Driver").entitlements.size());
    }
}
