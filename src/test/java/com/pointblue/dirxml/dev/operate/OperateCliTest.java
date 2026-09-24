package com.pointblue.dirxml.dev.operate;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OperateCliTest {

    @Test
    public void mutatorsAreTheCommandsThatChangeADriver() {
        assertTrue(OperateCli.mutatesVault("driver.start", null));
        assertTrue(OperateCli.mutatesVault("driver.stop", null));
        assertTrue(OperateCli.mutatesVault("driver.restart", null));
        assertTrue(OperateCli.mutatesVault("driver.migrate", null));
        assertTrue(OperateCli.mutatesVault("driver.resync", null));
        assertTrue(OperateCli.mutatesVault("driver.submit", null));
        assertTrue(OperateCli.mutatesVault("driver.cache", "clear"));
        assertTrue(OperateCli.mutatesVault("driver.secrets", "set"));
        assertTrue(OperateCli.mutatesVault("driver.secrets", "remove"));
        assertTrue(OperateCli.mutatesVault("driver.trace", "set"));
        assertTrue(OperateCli.mutatesVault("driver.trace", "reset"));

        assertFalse(OperateCli.mutatesVault("driverset.status", null));
        assertFalse(OperateCli.mutatesVault("driver.status", null));
        assertFalse(OperateCli.mutatesVault("driver.cache", "view"));
        assertFalse(OperateCli.mutatesVault("driver.cache", null));
        assertFalse(OperateCli.mutatesVault("driver.secrets", "list"));
        assertFalse(OperateCli.mutatesVault("driver.trace", "show"));
        assertFalse(OperateCli.mutatesVault("driver.trace", "tail"));
        assertFalse(OperateCli.mutatesVault("engine.version", null));
        assertFalse(OperateCli.mutatesVault("engine.stats", null));
    }
}
