package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DeployLogAndInventoryTest {

    @Test
    public void logAppendsReadsAndGates() throws IOException {
        Path tree = Files.createTempDirectory("idm-log");
        assertTrue(DeployLog.read(tree, "stg").isEmpty());
        assertNull(DeployLog.lastOk(tree, "stg"));
        DeployLog.Record a = DeployLog.record("stg", "deploy");
        a.treeCommit = "a".repeat(40);
        a.outcome = "ok";
        a.snapshot = "deploy-snapshots/stg/x.ldif";
        a.changes = 3;
        a.restarted.add("AD Driver");
        a.secretsSet.add("AD Driver.named.svc");
        a.detail = "line1\nline2 \"quoted\" \\ back";
        DeployLog.append(tree, a);
        DeployLog.Record b = DeployLog.record("stg", "deploy");
        b.treeCommit = "b".repeat(40);
        b.outcome = "failed";
        b.changes = 1;
        DeployLog.append(tree, b);
        List<DeployLog.Record> all = DeployLog.read(tree, "stg");
        assertEquals(2, all.size());
        assertEquals("ok", all.get(0).outcome);
        assertEquals(List.of("AD Driver"), all.get(0).restarted);
        assertEquals("line1\nline2 \"quoted\" \\ back", all.get(0).detail);
        assertEquals(3, all.get(0).changes);
        assertNull(all.get(1).snapshot);
        assertEquals("a".repeat(40), DeployLog.lastOk(tree, "stg").treeCommit);
        assertTrue(DeployLog.hasOkDeploy(tree, "stg", "a".repeat(40)));
        assertFalse(DeployLog.hasOkDeploy(tree, "stg", "b".repeat(40)));
        assertFalse(DeployLog.hasOkDeploy(tree, "stg", null));
        assertNull(DeployLog.treeCommit(tree));   // not a git checkout
    }

    /** Two branches that both deployed merge without a conflict in deploy-log/ (the union driver), and the last deploy is the latest by time. */
    @Test
    public void twoBranchesLogsMergeAndTheLatestDeployWins() throws Exception {
        Path tree = Files.createTempDirectory("idm-log-merge");
        DeployLog.writeAttributes(tree);
        assertTrue(Files.readString(tree.resolve(".gitattributes")).contains("deploy-log/*.jsonl merge=union"));
        DeployLog.writeAttributes(tree);   // idempotent
        assertEquals(1, Files.readString(tree.resolve(".gitattributes")).split("merge=union", -1).length - 1);
        git(tree, "init", "-q", "-b", "main");
        DeployLog.Record first = DeployLog.record("dev", "deploy");
        first.timestamp = "2026-10-16T09:00:00Z";
        first.treeCommit = "1".repeat(40);
        first.outcome = "ok";
        DeployLog.append(tree, first);
        commit(tree, "first");
        git(tree, "checkout", "-q", "-b", "feature");
        DeployLog.Record later = DeployLog.record("dev", "deploy");
        later.timestamp = "2026-10-16T11:00:00.5Z";
        later.treeCommit = "3".repeat(40);
        later.outcome = "ok";
        DeployLog.append(tree, later);
        commit(tree, "feature deploy");
        git(tree, "checkout", "-q", "main");
        DeployLog.Record mid = DeployLog.record("dev", "deploy");
        mid.timestamp = "2026-10-16T10:00:00Z";
        mid.treeCommit = "2".repeat(40);
        mid.outcome = "ok";
        DeployLog.append(tree, mid);
        commit(tree, "main deploy");
        git(tree, "-c", "user.name=t", "-c", "user.email=t@example.com", "merge", "-q", "--no-edit", "feature");
        assertEquals(3, DeployLog.read(tree, "dev").size());
        assertEquals("3".repeat(40), DeployLog.lastOk(tree, "dev").treeCommit);
    }

    private static void commit(Path dir, String message) throws Exception {
        git(dir, "-c", "user.name=t", "-c", "user.email=t@example.com", "add", "-A");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "-m", message);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(out, 0, p.waitFor());
    }

    @Test
    public void inventoryFindsEverySecretKind() {
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");
        ad.shimAuthId = "svc-idm";
        ad.config.put(Driver.SHIM_CONFIG_INFO, ValidatorTest.xml(
            "<driver-config><driver-options><configuration-values><definitions>"
                + "<definition name=\"remote-loader-host\" display-name=\"RL\" type=\"string\"><value>rl:8090</value></definition>"
                + "<definition name=\"api-key\" display-name=\"k\" type=\"password-ref\"><value>api-key</value></definition>"
                + "</definitions></configuration-values></driver-options></driver-config>"));
        ad.config.put(Driver.CONFIG_VALUES, ValidatorTest.xml(
            "<configuration-values><definitions>"
                + "<definition name=\"drv.pw\" display-name=\"p\" type=\"password-ref\"><value>smtp-relay</value></definition>"
                + "</definitions></configuration-values>"));
        ad.subscriber.policies.add(new Policy("sub-pw", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><rule><description>r</description><conditions/><actions><do-set-local-variable name=\"x\">"
                + "<arg-string><token-named-password name=\"exchange-service\"/></arg-string></do-set-local-variable></actions></rule></policy>")));
        ds.library.policies.add(new Policy("lib-pw", Scope.LIBRARY, null, ValidatorTest.xml(
            "<policy><rule><description>r</description><conditions/><actions><do-set-local-variable name=\"x\">"
                + "<arg-string><token-named-password name=\"shared-secret\"/></arg-string></do-set-local-variable></actions></rule></policy>")));
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-pw", 0));

        List<SecretInventory.Need> needs = SecretInventory.forDriver(ds, ad);
        List<String> keys = needs.stream().map(n -> n.key).toList();
        assertEquals(List.of("AD.shim-auth-password", "AD.remote-loader-password", "AD.mutual-auth-key-password",
            "AD.mutual-auth-keystore-password", "AD.named.exchange-service", "AD.named.shared-secret", "AD.named.smtp-relay", "AD.named.api-key"), keys);
        assertEquals("shim-auth", needs.get(0).kind);
        // the Remote Loader's mutual-authentication passwords are optional: no missing-secret note when the file lacks them
        assertTrue(!needs.get(1).optional && needs.get(2).optional && needs.get(3).optional);
        assertTrue(needs.get(4).because.contains("drivers/AD/subscriber/sub-pw"));

        // a driver with none
        Driver plain = new Driver("Loopback");
        assertTrue(SecretInventory.forDriver(ds, plain).isEmpty());
    }
}
