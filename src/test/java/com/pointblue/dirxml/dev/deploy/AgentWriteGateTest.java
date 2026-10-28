package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AgentWriteGateTest {

    @Test
    public void readOnlyAndDryRunAreOpen() {
        assertNull(AgentWriteGate.refusal("stg", false, null, null));
        assertNull(AgentWriteGate.refusal("prd", false, null, null));
    }

    @Test
    public void writeNeedsAllowOrConfirm() {
        String refused = AgentWriteGate.refusal("stg", true, null, null);
        assertTrue(refused, refused.contains("IDM_AGENT_ALLOW_WRITE=1"));
        assertTrue(refused, refused.contains("--confirm stg"));
        assertTrue(AgentWriteGate.refusal("stg", true, "other", null).contains("--confirm stg"));
        assertTrue(AgentWriteGate.refusal("stg", true, "other", "0").contains("IDM_AGENT_ALLOW_WRITE"));
        assertNull(AgentWriteGate.refusal("stg", true, "stg", "0"));
        assertNull(AgentWriteGate.refusal("stg", true, null, "1"));
        assertNull(AgentWriteGate.refusal("stg", true, "stg", null));
        assertNull(AgentWriteGate.refusal("prd", true, "prd", ""));
    }

    @Test
    public void deployAndCloneWrites() {
        assertFalse(AgentWriteGate.writesVault("vault.diff", Map.of()));
        assertFalse(AgentWriteGate.writesVault("vault.verify", Map.of("yes", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.export-clone", Map.of("yes", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.deploy", Map.of()));
        assertFalse(AgentWriteGate.writesVault("vault.deploy", Map.of("dry-run", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.deploy", Map.of("yes", List.of(""), "dry-run", List.of(""))));
        assertTrue(AgentWriteGate.writesVault("vault.deploy", Map.of("yes", List.of(""))));
        assertTrue(AgentWriteGate.writesVault("vault.deploy", Map.of("step", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.rollback", Map.of()));
        assertTrue(AgentWriteGate.writesVault("vault.rollback", Map.of("yes", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.import-clone", Map.of()));
        assertTrue(AgentWriteGate.writesVault("vault.import-clone", Map.of("yes", List.of(""))));
    }

    @Test
    public void schemaWrites() {
        assertFalse(AgentWriteGate.writesVault("vault.schema", Map.of("yes", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.app-schema", Map.of("yes", List.of(""))));
        assertFalse(AgentWriteGate.writesVault("vault.deploy-schema", Map.of()));
        assertTrue(AgentWriteGate.writesVault("vault.deploy-schema", Map.of("yes", List.of(""))));
        // gated on every tier, not only where SchemaOps itself asks for --confirm (prd)
        String refused = AgentWriteGate.refusal("dev", AgentWriteGate.writesVault("vault.deploy-schema",
            Map.of("yes", List.of(""))), null, null);
        assertNotNull(refused);
        assertTrue(refused.contains("--confirm dev"));
        assertNull(AgentWriteGate.refusal("dev", true, "dev", null));
    }

    @Test
    public void operateMutators() {
        assertTrue(AgentWriteGate.mutatesVault("driver.start", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.stop", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.restart", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.migrate", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.resync", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.submit", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.cache", "clear"));
        assertTrue(AgentWriteGate.mutatesVault("driver.secrets", "set"));
        assertTrue(AgentWriteGate.mutatesVault("driver.secrets", "remove"));
        assertTrue(AgentWriteGate.mutatesVault("driver.trace", "set"));
        assertTrue(AgentWriteGate.mutatesVault("driver.trace", "reset"));

        assertFalse(AgentWriteGate.mutatesVault("driverset.status", null));
        assertFalse(AgentWriteGate.mutatesVault("driver.status", null));
        assertFalse(AgentWriteGate.mutatesVault("driver.cache", "view"));
        assertFalse(AgentWriteGate.mutatesVault("driver.cache", null));
        assertFalse(AgentWriteGate.mutatesVault("driver.secrets", "list"));
        assertFalse(AgentWriteGate.mutatesVault("driver.trace", "show"));
        assertFalse(AgentWriteGate.mutatesVault("driver.trace", "tail"));
        assertFalse(AgentWriteGate.mutatesVault("engine.version", null));
        assertFalse(AgentWriteGate.mutatesVault("engine.stats", null));
    }

    /** #108: a migrate dry run only lists what would go; driver.query asks the connected system through the driver. */
    @Test
    public void migrateDryRunAndDriverQuery() {
        assertFalse(AgentWriteGate.mutatesVault("driver.migrate", null, true));
        assertTrue(AgentWriteGate.mutatesVault("driver.migrate", null, false));
        assertTrue(AgentWriteGate.mutatesVault("driver.query", null));
        assertTrue(AgentWriteGate.mutatesVault("driver.query", null, false));
        // --dry-run means nothing to the other operate commands
        assertTrue(AgentWriteGate.mutatesVault("driver.start", null, true));
        assertTrue(AgentWriteGate.mutatesVault("driver.cache", "clear", true));
        assertFalse(AgentWriteGate.mutatesVault("driver.status", null, true));
        // the 2-arg form is the 3-arg form without a dry run
        assertTrue(AgentWriteGate.mutatesVault("driver.migrate", null));
    }
}
