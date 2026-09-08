package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;
import org.w3c.dom.Element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One clean driver set (no findings), then one case per {@link FilterCheck} code.
 */
public class FilterCheckTest {

    private static final String FILTER =
        "<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
            + "<filter-attr attr-name=\"Given Name\" publisher=\"sync\" subscriber=\"sync\" merge-authority=\"app\"/>"
            + "</filter-class></filter>";

    private static final String SCHEMA_MAP =
        "<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name>"
            + "<attr-name><app-name>givenName</app-name><nds-name>Given Name</nds-name></attr-name>"
            + "</class-name></attr-name-map>";

    static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /** A driver with a well-formed filter and a schema map that matches it, linked in set 0. */
    static DriverSet clean() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("AD");
        d.shimClass = "com.example.Shim";
        d.config.put(Driver.DRIVER_FILTER, xml(FILTER));
        d.policies.add(new Policy("smp", Scope.DRIVER, "AD", xml(SCHEMA_MAP)));
        d.links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/AD/smp", 0));
        ds.drivers.add(d);
        return ds;
    }

    private static Report run(DriverSet ds) {
        Report r = new Report();
        new FilterCheck().run(ds, r);
        return r;
    }

    private static Driver ad(DriverSet ds) {
        return ds.driver("AD");
    }

    @Test
    public void cleanDriverSetHasNoFindings() {
        Report r = run(clean());
        assertTrue(r.text(), r.findings().isEmpty());
    }

    @Test
    public void driverWithNoFilterIsSkipped() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("AD");
        d.shimClass = "com.example.Shim";
        // Even a broken schema map produces nothing, since there's no filter to check it against.
        d.policies.add(new Policy("smp", Scope.DRIVER, "AD", xml("<attr-name-map><class-name/></attr-name-map>")));
        d.links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/AD/smp", 0));
        ds.drivers.add(d);
        assertTrue(run(ds).findings().isEmpty());
    }

    @Test
    public void filterMalformedRoot() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml("<not-a-filter/>"));
        Report r = run(ds);
        assertEquals(1, r.withCode("filter-malformed").size());
        assertEquals("drivers/AD", r.withCode("filter-malformed").get(0).path);
    }

    @Test
    public void filterMalformedMissingClassName() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml("<filter><filter-class publisher=\"sync\" subscriber=\"sync\"/></filter>"));
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("filter-malformed").size());
    }

    @Test
    public void filterMalformedMissingAttrName() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml(
            "<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
                + "<filter-attr publisher=\"sync\" subscriber=\"sync\"/></filter-class></filter>"));
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("filter-malformed").size());
    }

    @Test
    public void filterInvalidValue() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml(
            "<filter><filter-class class-name=\"User\" publisher=\"bogus\" subscriber=\"sync\">"
                + "<filter-attr attr-name=\"Given Name\" publisher=\"sync\" subscriber=\"sync\" merge-authority=\"nope\"/>"
                + "</filter-class></filter>"));
        Report r = run(ds);
        assertEquals(r.text(), 2, r.withCode("filter-invalid-value").size());
    }

    @Test
    public void filterDuplicateClass() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml(
            "<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\"/>"
                + "<filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\"/></filter>"));
        Report r = run(ds);
        assertEquals(1, r.withCode("filter-duplicate-class").size());
    }

    @Test
    public void filterDuplicateAttr() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml(
            "<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
                + "<filter-attr attr-name=\"Given Name\" publisher=\"sync\" subscriber=\"sync\"/>"
                + "<filter-attr attr-name=\"Given Name\" publisher=\"sync\" subscriber=\"sync\"/>"
                + "</filter-class></filter>"));
        Report r = run(ds);
        assertEquals(1, r.withCode("filter-duplicate-attr").size());
    }

    @Test
    public void filterDeadAttr() {
        DriverSet ds = clean();
        ad(ds).config.put(Driver.DRIVER_FILTER, xml(
            "<filter><filter-class class-name=\"User\" publisher=\"ignore\" subscriber=\"ignore\">"
                + "<filter-attr attr-name=\"Given Name\" publisher=\"sync\" subscriber=\"ignore\"/>"
                + "</filter-class></filter>"));
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("filter-dead-attr").size());
    }

    @Test
    public void schemaMapMalformed() {
        DriverSet ds = clean();
        ad(ds).policies.get(0).content = xml("<attr-name-map><class-name><nds-name>User</nds-name></class-name></attr-name-map>");
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("schema-map-malformed").size());
        assertEquals("drivers/AD/smp", r.withCode("schema-map-malformed").get(0).path);
    }

    @Test
    public void schemaMapDuplicateNdsName() {
        DriverSet ds = clean();
        ad(ds).policies.get(0).content = xml(
            "<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name></class-name>"
                + "<class-name><app-name>person</app-name><nds-name>User</nds-name></class-name></attr-name-map>");
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("schema-map-duplicate").size());
    }

    @Test
    public void schemaMapDuplicateAppNameToDifferentNdsNames() {
        DriverSet ds = clean();
        ad(ds).policies.get(0).content = xml(
            "<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name></class-name>"
                + "<class-name><app-name>user</app-name><nds-name>Person</nds-name></class-name></attr-name-map>");
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("schema-map-duplicate").size());
    }

    @Test
    public void schemaMapUnfilteredClass() {
        DriverSet ds = clean();
        ad(ds).policies.get(0).content = xml(
            "<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name></class-name>"
                + "<class-name><app-name>group</app-name><nds-name>Group</nds-name></class-name></attr-name-map>");
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("schema-map-unfiltered-class").size());
        Finding f = r.withCode("schema-map-unfiltered-class").get(0);
        assertEquals("drivers/AD/smp", f.path);
        assertTrue(f.message, f.message.contains("Group"));
    }
}
