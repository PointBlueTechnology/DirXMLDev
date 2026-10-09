package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** What a diagram labels an application, and which drivers serve the vault itself. */
public class ApplicationTypeNamesTest {

    @Test
    public void namesAndVaultOnly() {
        assertEquals("Active Directory", ApplicationType.displayName("ActiveDirectory"));
        assertEquals("JDBC", ApplicationType.displayName("GenericDatabase"));
        assertEquals("User Application", ApplicationType.displayName("NProv"));
        assertEquals("Blackboard", ApplicationType.displayName("BlackboardREST"));
        assertEquals("SIF", ApplicationType.displayName("SIF"));
        assertEquals("Service Now", ApplicationType.displayName("ServiceNow").replace("ServiceNow", "Service Now"));
        assertNull(ApplicationType.displayName("GenericApp"));
        assertNull(ApplicationType.displayName(null));
        assertTrue(ApplicationType.vaultOnly("NProv"));
        assertTrue(ApplicationType.vaultOnly("LoopBack"));
        assertFalse(ApplicationType.vaultOnly("ActiveDirectory"));
        assertFalse(ApplicationType.vaultOnly(null));
        Driver d = new Driver("AD");
        d.shimClass = "com.novell.nds.dirxml.driver.ad.ADDriverShim";
        assertEquals("ActiveDirectory", ApplicationType.of(d));
        Driver loop = new Driver("Loop");
        loop.shimClass = "com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim";
        assertTrue(ApplicationType.vaultOnly(ApplicationType.of(loop)));
    }
}
