package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** {@code DirXML-Entitlement} entries directly under a driver -&gt; {@link Driver#entitlements}. */
public class EntitlementLdifReaderTest {

    private static final String DS = "cn=driverset1,o=system";
    private static final String DRV = "cn=Loopback,cn=driverset1,o=system";

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", List.of("Top", oc));
        for (int i = 0; i < kv.length; i += 2) {
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    private static final String PLAIN_XML =
        "<?xml version=\"1.0\"?><entitlement conflict-resolution=\"priority\" description=\"\" "
        + "display-name=\"Group\"><values multi-valued=\"true\"/></entitlement>";

    private static final String PACKAGED_XML =
        "<?xml version=\"1.0\"?><entitlement conflict-resolution=\"union\" description=\"AD group\" "
        + "display-name=\"AD Group\"><values multi-valued=\"false\"><query-app><query-xml>"
        + "<nds><input><query class-name=\"Group\" scope=\"subtree\"/></input></nds></query-xml></query-app></values>"
        + "</entitlement>";

    private static List<Entry> sampleEntries() {
        return List.of(
            entry(DS, "DirXML-DriverSet"),
            entry(DRV, "DirXML-Driver", "DirXML-JavaModule", "com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim"),
            entry("cn=TestAccess," + DRV, "DirXML-Entitlement", "XmlData", PLAIN_XML),
            entry("cn=ADGroup," + DRV, "DirXML-Entitlement", "XmlData", PACKAGED_XML,
                "DirXML-pkgGUID", "PKG-GUID-1", "DirXML-pkgAssociationId", "ASSOC-1",
                "DirXML-pkgChecksum", "12345", "DirXML-pkgInitialState", PACKAGED_XML));
    }

    @Test
    public void entitlementsAreReadOntoTheirDriver() {
        DriverSet ds = LdifReader.fromEntries(sampleEntries(), "synthetic");
        Driver loopback = ds.driver("Loopback");
        assertNotNull(loopback);
        assertEquals(2, loopback.entitlements.size());
    }

    @Test
    public void unpackagedEntitlementCarriesItsDocument() {
        DriverSet ds = LdifReader.fromEntries(sampleEntries(), "synthetic");
        Entitlement e = ds.driver("Loopback").entitlement("TestAccess");
        assertNotNull(e);
        assertNotNull(e.definition);
        assertEquals("entitlement", e.definition.getTagName());
        assertEquals("Group", e.displayName());
        assertEquals("priority", e.conflictResolution());
        assertEquals("true", e.multiValued());
        assertTrue(e.meta.get("dn").endsWith(DRV));
        assertEquals(null, e.meta.get("dirxml-pkgguid"));
    }

    @Test
    public void packagedEntitlementCarriesItsStamps() {
        DriverSet ds = LdifReader.fromEntries(sampleEntries(), "synthetic");
        Entitlement e = ds.driver("Loopback").entitlement("ADGroup");
        assertNotNull(e);
        assertEquals("union", e.conflictResolution());
        assertEquals("AD Group", e.displayName());
        assertEquals("false", e.multiValued());
        assertEquals("PKG-GUID-1", e.meta.get("dirxml-pkgguid"));
        assertEquals("ASSOC-1", e.meta.get("dirxml-pkgassociationid"));
        assertEquals("12345", e.meta.get("dirxml-pkgchecksum"));
        // the package initial state is the tree's .package-baseline, not meta (same as forms/PRDs)
        assertEquals(null, e.meta.get("dirxml-pkginitialstate"));
    }

    @Test
    public void driverWithoutEntitlementsHasEmptyList() {
        List<Entry> entries = List.of(
            entry(DS, "DirXML-DriverSet"),
            entry("cn=Other,cn=driverset1,o=system", "DirXML-Driver"));
        DriverSet ds = LdifReader.fromEntries(entries, "synthetic");
        assertTrue(ds.driver("Other").entitlements.isEmpty());
    }
}
