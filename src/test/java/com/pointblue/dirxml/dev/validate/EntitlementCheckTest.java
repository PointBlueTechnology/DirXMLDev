package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;
import org.w3c.dom.Element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** One clean driver set (no findings), then one case per {@link EntitlementCheck} code. */
public class EntitlementCheckTest {

    private static Report run(DriverSet ds) {
        Report r = new Report();
        new EntitlementCheck().run(ds, r);
        return r;
    }

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    private static DriverSet withEntitlement(Entitlement e) {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("Loopback");
        d.entitlements.add(e);
        ds.drivers.add(d);
        return ds;
    }

    @Test
    public void cleanEntitlementHasNoFindings() {
        Entitlement e = new Entitlement("TestAccess", xml(
            "<entitlement conflict-resolution=\"priority\" description=\"\" display-name=\"Group\">"
            + "<values multi-valued=\"true\"><value>a</value></values></entitlement>"));
        Report r = run(withEntitlement(e));
        assertTrue(r.text(), r.findings().isEmpty());
    }

    @Test
    public void nameBlank() {
        Entitlement e = new Entitlement("", xml("<entitlement/>"));
        Report r = run(withEntitlement(e));
        assertEquals(1, r.withCode("entitlement-name-blank").size());
    }

    @Test
    public void noDocument() {
        Entitlement e = new Entitlement("E", null);
        Report r = run(withEntitlement(e));
        assertEquals(1, r.withCode("entitlement-no-document").size());
    }

    @Test
    public void wrongRoot() {
        Entitlement e = new Entitlement("E", xml("<not-an-entitlement/>"));
        Report r = run(withEntitlement(e));
        assertEquals(1, r.withCode("entitlement-wrong-root").size());
    }

    @Test
    public void conflictResolutionInvalid() {
        Entitlement e = new Entitlement("E", xml("<entitlement conflict-resolution=\"bogus\"/>"));
        Report r = run(withEntitlement(e));
        assertEquals(1, r.withCode("entitlement-conflict-invalid").size());
    }

    @Test
    public void multiValuedInvalid() {
        Entitlement e = new Entitlement("E", xml("<entitlement><values multi-valued=\"maybe\"/></entitlement>"));
        Report r = run(withEntitlement(e));
        assertEquals(1, r.withCode("entitlement-multi-valued-invalid").size());
    }
}
