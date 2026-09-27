package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import java.util.List;
import org.junit.Test;

/** A {@code --driver} that names nothing on either side is refused, never reported as "no differences". */
public class VaultDiffNarrowTest {

    private static DriverSet with(String... names) {
        DriverSet ds = new DriverSet("dvs");
        ds.dn = "cn=dvs,o=system";
        for (String n : names) {
            Driver d = new Driver(n);
            d.dn = "cn=" + n + "," + ds.dn;
            ds.drivers.add(d);
        }
        return ds;
    }

    @Test
    public void aTypoIsRefused_aDriverOnOneSideOnlyIsFine() {
        assertNull(VaultDiff.unknownDrivers(with("AD"), with("AD", "New"), List.of("New")));
        assertNull(VaultDiff.unknownDrivers(with("AD", "Old"), with("AD"), List.of("Old")));
        String why = VaultDiff.unknownDrivers(with("AD"), with("AD"), List.of("AD", "Typo"));
        assertTrue(why, why != null && why.contains("[Typo]"));
        try {
            VaultDiff.of(with("AD"), with("AD"), List.of("Typo"));
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Typo"));
        }
    }
}
