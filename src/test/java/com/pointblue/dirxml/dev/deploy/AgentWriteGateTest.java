package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
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
        assertFalse(DeployCli.writesVault("vault.diff", Map.of()));
        assertFalse(DeployCli.writesVault("vault.verify", Map.of("yes", List.of(""))));
        assertFalse(DeployCli.writesVault("vault.export-clone", Map.of("yes", List.of(""))));
        assertFalse(DeployCli.writesVault("vault.deploy", Map.of()));
        assertFalse(DeployCli.writesVault("vault.deploy", Map.of("dry-run", List.of(""))));
        assertFalse(DeployCli.writesVault("vault.deploy", Map.of("yes", List.of(""), "dry-run", List.of(""))));
        assertTrue(DeployCli.writesVault("vault.deploy", Map.of("yes", List.of(""))));
        assertTrue(DeployCli.writesVault("vault.deploy", Map.of("step", List.of(""))));
        assertFalse(DeployCli.writesVault("vault.rollback", Map.of()));
        assertTrue(DeployCli.writesVault("vault.rollback", Map.of("yes", List.of(""))));
        assertFalse(DeployCli.writesVault("vault.import-clone", Map.of()));
        assertTrue(DeployCli.writesVault("vault.import-clone", Map.of("yes", List.of(""))));
    }
}
