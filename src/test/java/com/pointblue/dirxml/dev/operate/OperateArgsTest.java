package com.pointblue.dirxml.dev.operate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.pointblue.dirxml.dev.deploy.AgentWriteGate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The operate, viewer and trace.view command lines read against what each command (and verb) takes
 * (#104, #105): every case here fails before an environment is loaded, so none touches a vault.
 */
public class OperateArgsTest {

    /** Exit code, stdout and stderr of one in-process run. */
    private static final class Run {
        int code;
        String out;
        String err;
    }

    private static Run operate(String... argv) throws Exception {
        return run(false, argv);
    }

    private static Run viewer(String... argv) throws Exception {
        return run(true, argv);
    }

    private static Run run(boolean viewer, String... argv) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        System.setOut(new PrintStream(o, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
        Run r = new Run();
        try {
            r.code = viewer ? ViewerCli.run(argv) : OperateCli.run(argv);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        r.out = o.toString(StandardCharsets.UTF_8);
        r.err = e.toString(StandardCharsets.UTF_8);
        return r;
    }

    private static void usageError(String expect, String... argv) throws Exception {
        Run r = operate(argv);
        assertEquals(List.of(argv) + ": " + r.err, 2, r.code);
        assertTrue(List.of(argv) + " says '" + expect + "': " + r.err, r.err.contains(expect));
    }

    @Test
    public void aFlagBeforeTheVerbKeepsTheVerb() throws Exception {
        OperateCli.Parsed p = OperateCli.parse(new String[] {"driver.health", "--json", "clear", "--env", "x", "--driver", "D", "--yes"});
        assertEquals("clear", p.sub);
        assertTrue(p.args.has("json") && p.args.has("yes"));
        assertEquals("D", p.args.first("driver"));

        p = OperateCli.parse(new String[] {"driver.trace", "--json", "tail", "--env", "x", "--driver", "D", "--follow", "--lines", "5"});
        assertEquals("tail", p.sub);
        assertEquals("5", p.args.first("lines"));

        p = OperateCli.parse(new String[] {"driver.log-level", "--env", "x", "--json"});
        assertNull(p.sub);
    }

    @Test
    public void everyVerbOfEveryCommandReads() throws Exception {
        for (Map.Entry<String, List<String>> e : OperateCli.VERBS.entrySet()) {
            for (String verb : e.getValue()) {
                OperateCli.Parsed p = OperateCli.parse(new String[] {e.getKey(), verb, "--env", "x", "--json"});
                assertEquals(verb, p.sub);
            }
        }
    }

    @Test
    public void anUnknownOptionIsAUsageError() throws Exception {
        usageError("driver.status has no option --bogus", "driver.status", "--env", "x", "--driver", "D", "--bogus");
        usageError("has no option --yes", "driver.cache", "view", "--env", "x", "--driver", "D", "--yes");
        usageError("driver.health has no option --yes", "driver.health", "--env", "x", "--driver", "D", "--yes");
        usageError("has no option --count", "trace.view", "--file", "f", "--count", "3");
    }

    @Test
    public void anOptionWithoutItsValueIsAUsageError() throws Exception {
        usageError("--env needs a value", "driver.status", "--env");
        usageError("--driver needs a value", "driver.start", "--driver", "--env", "x");
        usageError("--host needs a value", "vault.email-server", "set", "--env", "x", "--host", "--yes");
        usageError("--yes takes no value", "driver.stop", "--env", "x", "--driver", "D", "--yes=1");
    }

    @Test
    public void aBadNumberIsAUsageError() throws Exception {
        usageError("--wait needs a whole number", "driver.restart", "--env", "x", "--driver", "D", "--wait", "soon");
        usageError("--count needs a whole number", "driver.cache", "view", "--env", "x", "--driver", "D", "--count", "lots");
        usageError("--limit needs a whole number", "workorder.list", "--env", "x", "--limit", "1e3");
        usageError("--limit needs a whole number", "driver.associations", "--env", "x", "--driver", "D", "--limit", "x");
        usageError("--max needs a whole number", "driver.migrate", "--env", "x", "--driver", "D", "--direction", "vault", "--max", "x");
        usageError("--level needs a whole number", "driver.trace", "set", "--env", "x", "--driver", "D", "--level", "high");
        usageError("--lines needs a whole number", "driver.trace", "tail", "--env", "x", "--driver", "D", "--lines", "x");
        usageError("--seconds needs a whole number", "trace.view", "--env", "x", "--driver", "D", "--ldap", "--seconds", "x");
        usageError("--last needs a whole number", "trace.view", "--file", "f", "--last", "x");
        usageError("--events-type needs a whole number", "driver.log-level", "set", "--env", "x", "--events-type", "x");
        usageError("--events needs event ids", "driver.log-level", "set", "--env", "x", "--events", "1,two");
    }

    @Test
    public void anUnknownOrMissingVerbIsAUsageError() throws Exception {
        usageError("driver.cache has no verb 'bogus'; it takes view|clear", "driver.cache", "bogus", "--env", "x");
        usageError("driver.health has no verb 'show'; it takes clear, or none to show", "driver.health", "show", "--env", "x");
        usageError("driver.trace has no verb 'bogus'", "driver.trace", "--json", "bogus", "--env", "x");
        usageError("usage: driver.secrets list|set|remove", "driver.secrets", "--env", "x", "--driver", "D");
        usageError("takes one verb", "driverset.servers", "add", "remove", "--env", "x");
    }

    @Test
    public void aStrayPositionalIsAUsageError() throws Exception {
        usageError("driverset.status takes no positional argument, not 'extra'", "driverset.status", "--env", "x", "extra");
        usageError("takes no positional argument", "engine.version", "--json", "test");
    }

    @Test
    public void listedValuesAndInstantsAreCheckedBeforeTheEnvironment() throws Exception {
        usageError("--scope is subtree, subordinates, entry, not 'tree'", "driver.query", "--env", "x", "--driver", "D", "--scope", "tree");
        usageError("--mode is command, event, queue, not 'push'", "driver.submit", "--env", "x", "--driver", "D", "--xds", "f", "--mode", "push");
        usageError("--direction is app, vault, not 'both'", "driver.migrate", "--env", "x", "--driver", "D", "--direction", "both");
        usageError("usage: driver.migrate", "driver.migrate", "--env", "x", "--driver", "D");
        usageError("--dry-run goes with --direction vault", "driver.migrate", "--env", "x", "--driver", "D", "--xds", "f", "--dry-run");
        usageError("--since needs an ISO-8601 instant", "driver.resync", "--env", "x", "--driver", "D", "--since", "yesterday");
        usageError("--level is errors", "driver.log-level", "set", "--env", "x", "--level", "loud");
        usageError("--status must be one of", "trace.view", "--file", "f", "--status", "bad");
        usageError("--text is not a regular expression", "trace.view", "--file", "f", "--text", "(");
    }

    @Test
    public void anUnknownCommandShowsTheUsage() throws Exception {
        Run r = operate("driver.nonesuch", "--env", "x");
        assertEquals(2, r.code);
        assertTrue(r.err.contains("usage:"));
    }

    /** #108: driver.query asks the connected system, so the agent gate covers it on every tier, before any vault access. */
    @Test
    public void driverQueryNeedsTheAgentConfirmation() throws Exception {
        assumeTrue("IDM_AGENT_ALLOW_WRITE is set in this shell", System.getenv(AgentWriteGate.ENV) == null);
        Run r = operate("driver.query", "--env", "nosuch-env", "--driver", "D", "--class", "User");
        assertEquals(r.err, 1, r.code);
        assertTrue(r.out, r.out.contains("refusing vault write to 'nosuch-env'"));
        assertTrue(r.out, r.out.contains("--confirm nosuch-env"));
    }

    /** #108: --direction app with --dry-run is refused, so a dry run that skips the agent gate is always a real one. */
    @Test
    public void aMigrateDryRunIsOnlyTheVaultDirection() throws Exception {
        usageError("--dry-run goes with --direction vault", "driver.migrate", "--env", "x", "--driver", "D", "--direction", "app", "--xds", "f", "--dry-run");
        assumeTrue("IDM_AGENT_ALLOW_WRITE is set in this shell", System.getenv(AgentWriteGate.ENV) == null);
        Run r = operate("driver.migrate", "--env", "nosuch-env", "--driver", "D", "--direction", "vault", "--base", "o=x", "--filter", "(cn=*)", "--class", "User");
        assertEquals(r.err, 1, r.code);
        assertTrue(r.out, r.out.contains("refusing vault write"));
    }

    @Test
    public void viewerCommands() throws Exception {
        Run r = viewer("viewer.install", "--bogus");
        assertEquals(2, r.code);
        assertTrue(r.err, r.err.contains("viewer.install has no option --bogus"));
        r = viewer("viewer.install", "--dir");
        assertEquals(2, r.code);
        assertTrue(r.err, r.err.contains("--dir needs a value"));
        r = viewer("viewer.check", "--json", "extra");
        assertEquals(2, r.code);
        assertTrue(r.err, r.err.contains("viewer.check takes no positional argument, not 'extra'"));
        r = viewer("viewer.nonesuch");
        assertEquals(2, r.code);
        assertTrue(r.err, r.err.contains("usage: viewer.check"));
    }
}
