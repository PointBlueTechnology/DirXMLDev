package com.pointblue.dirxml.dev.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.Args;
import com.pointblue.dirxml.dev.events.EventsCli;
import com.pointblue.dirxml.dev.promote.PromoteCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/**
 * The catalog, promotion and event-store commands read their command lines against what each takes (#104, #105):
 * a flag never takes the positional after it, and an unknown option, an option without its value, a bad number,
 * an unlisted value or an unknown verb is a usage error (exit 2) found before any catalog, store or tree is opened.
 */
public class CatalogArgsTest {

    @FunctionalInterface
    interface Command {
        int run() throws Exception;
    }

    /** The exit code and what went to stderr. */
    private record Outcome(int rc, String err) {
    }

    private static Outcome run(Command c) throws Exception {
        PrintStream old = System.err;
        PrintStream oldOut = System.out;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            return new Outcome(c.run(), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(old);
            System.setOut(oldOut);
        }
    }

    private static Outcome pkg(String... argv) throws Exception {
        return run(() -> PackageCli.run(argv));
    }

    private static Outcome promote(String... argv) throws Exception {
        return run(() -> PromoteCli.run(argv));
    }

    private static Outcome events(String... argv) throws Exception {
        return run(() -> EventsCli.command(argv));
    }

    private static Outcome query(String... argv) throws Exception {
        return run(() -> EventsCli.query(argv));
    }

    private static void usage(Outcome o, String says) {
        assertEquals(o.err, 2, o.rc);
        assertTrue("stderr '" + o.err + "' lacks '" + says + "'", o.err.contains(says));
    }

    // ---- package.* ----

    @Test
    public void packageFlagBeforeAPositionalKeepsIt() throws Exception {
        Path catalog = Files.createTempDirectory("catalog-args");
        // --json used to take NOVLNONE as its value, leaving no package: a usage error; now the package is looked up
        Outcome o = pkg("package.show", "--catalog", catalog.toString(), "--json", "NOVLNONE");
        assertEquals(o.err, 1, o.rc);
        assertTrue(o.err, o.err.contains("no such package in the catalog: NOVLNONE"));
        o = pkg("package.diff", "--catalog", catalog.toString(), "--json", "NOVLA_1.0.0", "NOVLA_2.0.0");
        assertEquals(o.err, 1, o.rc);
        assertTrue(o.err, o.err.contains("not in the catalog: NOVLA_1.0.0"));
        Args a = Args.parse("package.status", new String[] {"package.status", "--strict", "tree", "--json"}, 1, PackageStatus.SPEC);
        assertEquals(List.of("tree"), a.positional);
    }

    @Test
    public void packageUsageErrorsComeBeforeTheCatalogIsCreated() throws Exception {
        Path catalog = Files.createTempDirectory("catalog-args").resolve("new");
        String dir = catalog.toString();
        usage(pkg("package.list", "--catalog", dir, "--latest"), "package.list has no option --latest");
        usage(pkg("package.fetch", "--catalog", dir, "--short", "NOVLA", "--latest"), "package.fetch has no option --latest");
        usage(pkg("package.list", "--catalog", dir, "--type"), "--type needs a value");
        usage(pkg("package.list", "--catalog", dir, "--type", "driver"), "--type needs a whole number, not 'driver'");
        usage(pkg("package.list", "--catalog", dir, "--base=yes"), "--base takes no value");
        usage(pkg("package.resolve", "--catalog", dir, "--base"), "--base needs a value");
        usage(pkg("package.resolve", "--catalog", dir), "--base is required");
        usage(pkg("package.show", "--catalog", dir, "A", "B"), "unexpected argument 'B'");
        usage(pkg("package.diff", "--catalog", dir, "A"), "usage: package.diff");
        usage(pkg("package.site", "--catalog", dir, "--out"), "--out needs a value");
        usage(pkg("package.build", "--catalog", dir, "tree", "--short", "PBTX", "--name", "X", "--gcvs", "some"),
            "--gcvs is referenced, all, none, not 'some'");
        usage(pkg("package.build", "--catalog", dir, "tree", "--short", "PBTX", "--name", "X", "--customized", "yes"),
            "--customized is refuse, keep, not 'yes'");
        usage(pkg("package.build", "--catalog", dir, "tree", "--name", "X"), "--short is required");
        usage(pkg("package.list"), "--catalog <dir> is required");
        usage(pkg("package.nope", "--catalog", dir), "unknown command package.nope");
        assertFalse("a usage error created the catalog", Files.exists(catalog));
    }

    @Test
    public void packageStatusUsageErrors() throws Exception {
        usage(run(() -> PackageStatus.cli(new String[] {"package.status", "tree", "--deep"})), "package.status has no option --deep");
        usage(run(() -> PackageStatus.cli(new String[] {"package.status", "tree", "--driver"})), "--driver needs a value");
        usage(run(() -> PackageStatus.cli(new String[] {"package.status", "tree", "other"})), "unexpected argument 'other'");
        usage(run(() -> PackageStatus.cli(new String[] {"package.status", "--strict"})), "usage: package.status");
        // package.status alone reaches PackageCli, which hands it on
        usage(pkg("package.status"), "usage: package.status");
    }

    // ---- tree.compare, promote.* ----

    @Test
    public void promoteFlagBeforeAPositionalKeepsIt() throws Exception {
        // the pair's name is checked before either tree is read: reaching it means the target was kept
        usage(promote("promote.pair", "--propose-map", "prod/tree", "--from", "qa/tree", "--name", "bad name"), "a pair's name is");
        usage(promote("promote.apply", "--pending", "prod/tree", "--from", "qa/tree", "--name", "bad name"), "a pair's name is");
        usage(promote("promote.forget", "--dry-run", "prod/tree", "--name", "bad name", "--object", "x"), "a pair's name is");
    }

    @Test
    public void promoteCommandsTakeOnlyTheirOwnOptions() throws Exception {
        usage(promote("tree.compare", "prod/tree", "--with", "qa/tree", "--force"), "tree.compare has no option --force");
        usage(promote("promote.plan", "prod/tree", "--from", "qa/tree", "--by", "jdoe"), "promote.plan has no option --by");
        usage(promote("promote.map", "prod/tree", "--from", "qa/tree", "--pending"), "promote.map has no option --pending");
        usage(promote("promote.forget", "prod/tree", "--name", "p", "--note", "x"), "promote.forget has no option --note");
        usage(promote("promote.accept", "prod/tree", "--from", "qa/tree", "--accept", "x"), "promote.accept has no option --accept");
        usage(promote("promote.nope", "prod/tree"), "unknown command promote.nope");
    }

    @Test
    public void promoteValuesAndAliases() throws Exception {
        usage(promote("tree.compare", "prod/tree", "--with"), "--with needs a value");
        usage(promote("promote.pair", "prod/tree", "--from", "qa/tree", "--pending"), "--pending needs a value");
        usage(promote("promote.apply", "prod/tree", "--from", "qa/tree", "--pending=yes"), "--pending takes no value");
        usage(promote("promote.accept", "prod/tree", "--from", "qa/tree", "--object"), "--object needs a value");
        usage(promote("promote.plan", "prod/tree", "other/tree", "--from", "qa/tree"), "unexpected argument 'other/tree'");
        usage(promote("promote.plan", "--from", "qa/tree"), "the target tree is required");
        // --with and --from name the source on every command
        usage(promote("tree.compare", "prod/tree", "--from", "qa/tree", "--name", "bad name"), "a pair's name is");
        usage(promote("promote.map", "prod/tree", "--with", "qa/tree", "--name", "bad name"), "a pair's name is");
    }

    // ---- events.*, query … events|event ----

    @Test
    public void eventsFlagBeforeAPositionalKeepsIt() throws Exception {
        // the id is read before the environment is loaded: reaching it means the tree was kept
        usage(events("events.case", "--dry-run", "tree", "--env", "test", "--id", "x", "--name", "n"), "--id needs a whole number, not 'x'");
        usage(query("query", "tree", "event", "--xml", "abc", "--env", "test"), "<id> needs a whole number, not 'abc'");
    }

    @Test
    public void eventsUsageErrorsComeBeforeTheEnvironment() throws Exception {
        usage(events("events.describe", "tree", "--env", "test", "--id", "1"), "events.describe has no option --id");
        usage(events("events.describe", "tree", "--env"), "--env needs a value");
        usage(events("events.describe", "tree", "more", "--env", "test"), "unexpected argument 'more'");
        usage(events("events.case", "tree", "--env", "test", "--id", "1"), "usage: events.case");
        usage(events("events.case", "tree", "--env", "test", "--id", "1", "--name"), "--name needs a value");
        usage(events("events.case", "tree", "--env", "test", "--id", "1", "--name", "n", "--replace=yes"), "--replace takes no value");
        usage(query("query", "tree", "events", "--env", "test", "--limit", "many"), "--limit needs a whole number, not 'many'");
        usage(query("query", "tree", "events", "--env", "test", "--stage", "middle"), "--stage is input, output, not 'middle'");
        usage(query("query", "tree", "events", "--env", "test", "--type", "add,touch"), "event type 'touch'");
        usage(query("query", "tree", "events", "--env", "test", "--since", "yesterday"), "--since is 30s");
        usage(query("query", "tree", "events", "--env", "test", "--xml", "extra"), "unexpected argument 'extra'");
        usage(query("query", "tree", "events", "--env", "test", "--id", "1"), "query events has no option --id");
        usage(query("query", "tree", "event", "1", "--env", "test", "--limit", "5"), "query event has no option --limit");
        usage(query("query", "tree", "events", "--dn"), "--dn needs a value");
        usage(query("query", "tree", "events"), "usage: query <tree> events --env");
        usage(query("query", "tree", "evnts", "--env", "test"), "query <tree> events|event, not 'evnts'");
    }
}
