package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.EntitlementPolicy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import java.util.List;
import org.junit.Test;

/** Which validation errors hold back a deploy scoped to some drivers. */
public class ValidationScopeTest {

    private static DriverSet set() {
        DriverSet ds = new DriverSet("ds");
        Driver ad = new Driver("AD");
        ad.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "library/NOVLLIBAJC-JS", 0));
        ds.drivers.add(ad);
        ds.drivers.add(new Driver("Okta"));
        Driver es = new Driver("Entitlements Service");
        es.shimClass = EntitlementPolicy.SERVICE_SHIM_CLASS;
        ds.drivers.add(es);
        return ds;
    }

    private static Report errors(String... paths) {
        Report r = new Report();
        for (String p : paths) {
            r.add(new Finding(Finding.Severity.ERROR, "x", p, "m", null));
        }
        r.add(new Finding(Finding.Severity.WARNING, "w", "drivers/AD/p", "a warning never blocks", null));
        return r;
    }

    private static List<String> blocking(ValidationScope.Split s) {
        return s.blocking.stream().map(f -> f.path).toList();
    }

    @Test
    public void aScopedDeployIsHeldToItsDriversTheLibraryTheyLinkAndTheDriverSet() {
        Report r = errors("drivers/AD/subscriber/p", "drivers/Okta/p", "library/NOVLLIBAJC-JS", "library/Other",
            "rbe-policies/P", "jobs/J", "templates/T", "driverset.xml", null);
        ValidationScope.Split s = ValidationScope.split(r, set(), List.of("AD"));
        assertEquals(java.util.Arrays.asList("drivers/AD/subscriber/p", "library/NOVLLIBAJC-JS", "driverset.xml", "driverset"), blocking(s));
        assertEquals(5, s.outside.size());
        assertTrue(s.note(), s.note().startsWith("validation: 5 error(s) outside this deploy's scope, not deployed by it (jobs, Library, Okta, rbe-policies, templates)"));
    }

    @Test
    public void rbePoliciesBlockOnlyWithTheEntitlementsServiceDriverInScope() {
        Report r = errors("rbe-policies/P");
        assertTrue(ValidationScope.split(r, set(), List.of("AD")).blocking.isEmpty());
        assertEquals(1, ValidationScope.split(r, set(), List.of("Entitlements Service")).blocking.size());
    }

    @Test
    public void driverNamesMatchWithoutCaseAndAPrefixIsNotAMatch() {
        assertTrue(ValidationScope.blocks("drivers/ad/p", new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER) {{ add("AD"); }}, java.util.Set.of(), false));
        assertFalse(ValidationScope.blocks("drivers/AD-Driver/p", java.util.Set.of("AD"), java.util.Set.of(), false));
        assertTrue(ValidationScope.blocks("drivers/AD", java.util.Set.of("AD"), java.util.Set.of(), false));
    }
}
