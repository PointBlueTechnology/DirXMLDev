package com.pointblue.dirxml.dev.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Engine ids and keys for every policy set, including Startup (15) and Shutdown (16). */
public class PolicySetTest {

    @Test
    public void startupAndShutdownHaveEngineIds() {
        assertEquals(15, PolicySet.STARTUP.id);
        assertEquals("startup", PolicySet.STARTUP.key);
        assertEquals(16, PolicySet.SHUTDOWN.id);
        assertEquals("shutdown", PolicySet.SHUTDOWN.key);
        assertEquals(PolicySet.STARTUP, PolicySet.byId(15));
        assertEquals(PolicySet.SHUTDOWN, PolicySet.byId(16));
        assertEquals(PolicySet.STARTUP, PolicySet.byKey("startup"));
        assertEquals(PolicySet.SHUTDOWN, PolicySet.byKey("shutdown"));
    }

    @Test
    public void startupAndShutdownAreDriverLevelNotChannels() {
        assertFalse(PolicySet.STARTUP.isSubscriber());
        assertFalse(PolicySet.STARTUP.isPublisher());
        assertFalse(PolicySet.STARTUP.isChannel());
        assertFalse(PolicySet.SHUTDOWN.isSubscriber());
        assertFalse(PolicySet.SHUTDOWN.isPublisher());
        assertFalse(PolicySet.SHUTDOWN.isChannel());
        assertFalse(PolicySet.GCV.isSubscriber());
        assertFalse(PolicySet.GCV.isPublisher());
        assertTrue(PolicySet.SUB_EVENT.isSubscriber());
        assertTrue(PolicySet.PUB_EVENT.isPublisher());
        assertFalse(PolicySet.SUB_PLACEMENT.isPublisher());
        assertTrue(PolicySet.SUB_PLACEMENT.isSubscriber());
    }

    @Test
    public void knownSetCountMatchesEngineTable() {
        assertEquals(17, PolicySet.values().length);
        assertEquals(14, PolicySet.GCV.id);
    }
}
