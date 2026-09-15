package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link FlowCheck}'s two entitlement codes (docs/entitlements.md §2): a provision
 * activity's {@code DirXML-Entitlement-DN} literal resolved against the tree's drivers.
 */
public class EntitlementFlowCheckTest {

    private static String processReferencing(String dn) {
        return "<process id=\"cn=Test\" version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"/>"
            + "<provision-activity activity-id=\"prov\" category=\"entitlement\"/>"
            + "<finish-activity activity-id=\"finish\"/>"
            + "<data-items activity-id=\"prov\">"
            + "<data-item name=\"DirXML-Entitlement-DN\" data-type=\"string\" source=\"'" + dn + "'\"/>"
            + "</data-items>"
            + "<link source=\"start\" target=\"prov\" type=\"forward\"/>"
            + "<link source=\"prov\" target=\"finish\" type=\"forward\"/>"
            + "</process>";
    }

    private static Report run(DriverSet ds) {
        Report r = new Report();
        new FlowCheck().run(ds, r);
        return r;
    }

    private static DriverSet withProcess(Driver driver, String processXml) {
        DriverSet ds = new DriverSet("dvs");
        Provisioning p = new Provisioning();
        Prd prd = new Prd("P");
        prd.process = CanonicalXml.parse(processXml).getDocumentElement();
        prd.properties.put("status", new ArrayList<>(List.of("Active")));
        p.prds.add(prd);
        driver.provisioning = p;
        ds.drivers.add(driver);
        return ds;
    }

    @Test
    public void unknownEntitlementOnADriverInTheTreeWarns() {
        Driver ua = new Driver("UA");   // no entitlements
        DriverSet ds = withProcess(ua, processReferencing("cn=TestAccess,cn=UA,cn=driverset1,o=system"));
        Report r = run(ds);
        assertEquals(1, r.withCode("flow-entitlement-unknown").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("flow-entitlement-unknown").get(0).severity);
        assertTrue(r.withCode("flow-entitlement-external").isEmpty());
    }

    @Test
    public void entitlementOnADriverNotInTheTreeIsInfoOnly() {
        Driver ua = new Driver("UA");
        DriverSet ds = withProcess(ua, processReferencing("cn=TestAccess,cn=SomeOtherDriver,cn=driverset1,o=system"));
        Report r = run(ds);
        assertEquals(1, r.withCode("flow-entitlement-external").size());
        assertEquals(Finding.Severity.INFO, r.withCode("flow-entitlement-external").get(0).severity);
        assertTrue(r.withCode("flow-entitlement-unknown").isEmpty());
    }

    @Test
    public void knownEntitlementOnADriverInTheTreeHasNoFinding() {
        Driver ua = new Driver("UA");
        ua.entitlements.add(new Entitlement("TestAccess",
            CanonicalXml.parse("<entitlement conflict-resolution=\"priority\"/>").getDocumentElement()));
        DriverSet ds = withProcess(ua, processReferencing("cn=TestAccess,cn=UA,cn=driverset1,o=system"));
        Report r = run(ds);
        assertTrue(r.withCode("flow-entitlement-unknown").isEmpty());
        assertTrue(r.withCode("flow-entitlement-external").isEmpty());
    }
}
