package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.FormOpsTest;
import com.pointblue.dirxml.dev.edit.ReadCli;
import com.pointblue.dirxml.dev.forms.FormBuilderLocator;
import com.pointblue.dirxml.dev.forms.FormEditCli;
import com.pointblue.dirxml.dev.forms.FormPreview;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The tree commands read their command line against what they take (#104, #105): a flag before a
 * positional keeps it, an unknown option or an option without its value is exit 2, an unknown
 * verb is exit 2, extra positionals are exit 2 — all before the tree, an environment or the
 * vault is read (a tree that doesn't exist would otherwise fail differently).
 */
public class TreeArgsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Never read: a usage error comes first. */
    private static final String NO_TREE = "/nonexistent/idm-tree-args";

    private String tree() throws Exception {
        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(SyntheticDriverSet.model(), dir);
        return dir.toString();
    }

    private interface Run {
        int run() throws Exception;
    }

    /** The exit code, with stdout and stderr kept out of the test output; {@code err[0]} gets stderr. */
    private static int quiet(String[] err, Run r) throws Exception {
        PrintStream out = System.out;
        PrintStream e = System.err;
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        System.setErr(new PrintStream(errBytes));
        try {
            return r.run();
        } finally {
            System.setOut(out);
            System.setErr(e);
            if (err != null) {
                err[0] = errBytes.toString();
            }
        }
    }

    private static int quiet(Run r) throws Exception {
        return quiet(null, r);
    }

    private static String[] a(String... argv) {
        return argv;
    }

    // ---- Cli's own commands ------------------------------------------------------------------

    @Test
    public void cliAFlagBeforeAPositionalKeepsIt() throws Exception {
        String t = tree();
        assertEquals(0, quiet(() -> Cli.treeDiff(a("tree.diff", "--json", t, t))));
        assertEquals(0, quiet(() -> Cli.check(a("check", "--json", t))));
        assertEquals(0, quiet(() -> Cli.refs(a("refs", "--json", t, "library/" + SyntheticDriverSet.LIBRARY_POLICY))));
        Path out = tmp.getRoot().toPath().resolve("docs-out");
        assertEquals(0, quiet(() -> Cli.docs(a("docs", "--format", "HTML", "--driver", SyntheticDriverSet.DRIVER, "--out", out.toString(), t))));
        assertTrue(Files.isDirectory(out));
    }

    @Test
    public void cliAnUnknownOptionIsAUsageError() throws Exception {
        String[] err = new String[1];
        assertEquals(2, quiet(err, () -> Cli.validate(a("validate", NO_TREE, "--bogus"))));
        assertTrue(err[0], err[0].contains("validate has no option --bogus"));
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE, "--out", "x", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.importLive(a("import-live", NO_TREE, "--env", "nowhere", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.simulate(a("simulate", NO_TREE, "--cases", "c", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.exportProject(a("export-project", NO_TREE, "p", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.treeDiff(a("tree.diff", NO_TREE, NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> Cli.importExport(a("import", "x.xml", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> Cli.importProject(a("import-project", "p", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> Cli.importLdif(a("import-ldif", "d.ldif", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> Cli.export(a("export", NO_TREE, "out.xml", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.check(a("check", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> Cli.refs(a("refs", NO_TREE, "p", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.mappingTableExportCsv(a("mapping-table.export-csv", NO_TREE, "p", "--bogus"))));
        assertEquals(2, quiet(() -> Cli.version(a("version", "--bogus"))));
        assertEquals(2, quiet(() -> Doctor.run(a("doctor", "--bogus"))));
    }

    @Test
    public void cliAnOptionWithoutItsValueIsAUsageError() throws Exception {
        // docs used to read past the end of the line (exit 3)
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE, "--out"))));
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE, "--out", "o", "--format"))));
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE, "--driver", "--out", "o"))));
        assertEquals(2, quiet(() -> Cli.importLive(a("import-live", NO_TREE, "--env"))));
        assertEquals(2, quiet(() -> Cli.simulate(a("simulate", NO_TREE, "--cases"))));
        assertEquals(2, quiet(() -> Cli.exportProject(a("export-project", NO_TREE, "p", "--new", "--vault-name"))));
        assertEquals(2, quiet(() -> Doctor.run(a("doctor", "--env"))));
    }

    @Test
    public void cliTakesExactlyThePositionalsItNeeds() throws Exception {
        String[] err = new String[1];
        // validate used to take the last non-flag word as the tree
        assertEquals(2, quiet(err, () -> Cli.validate(a("validate", NO_TREE, "other"))));
        assertTrue(err[0], err[0].contains("unexpected argument 'other'"));
        assertEquals(2, quiet(() -> Cli.validate(a("validate", "--json"))));
        assertEquals(2, quiet(() -> Cli.treeDiff(a("tree.diff", NO_TREE, "--json", "--json"))));
        assertEquals(2, quiet(() -> Cli.importExport(a("import", "x.xml", NO_TREE, "extra"))));
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE, "extra", "--out", "o"))));
        assertEquals(2, quiet(() -> Cli.version(a("version", "extra"))));
        assertEquals(2, quiet(() -> Doctor.run(a("doctor", "extra"))));
        // import-live: a DN and a directory at most, and without --env both — before any environment is loaded
        assertEquals(2, quiet(() -> Cli.importLive(a("import-live", NO_TREE))));
        assertEquals(2, quiet(() -> Cli.importLive(a("import-live", "cn=Set,o=x", NO_TREE, "extra", "--env", "nowhere"))));
    }

    @Test
    public void cliListedValuesAndRequiredOptions() throws Exception {
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE, "--out", "o", "--format", "pdf"))));
        assertEquals(2, quiet(() -> Cli.docs(a("docs", NO_TREE))));
        assertEquals(2, quiet(() -> Cli.simulate(a("simulate", NO_TREE))));
        assertEquals(2, quiet(() -> Cli.exportProject(a("export-project", NO_TREE, "p", "--vault-name", "V"))));
    }

    // ---- ReadCli -----------------------------------------------------------------------------

    @Test
    public void readAFlagBeforeAPositionalKeepsIt() throws Exception {
        String t = tree();
        String policy = "library/" + SyntheticDriverSet.LIBRARY_POLICY;
        assertEquals(0, quiet(() -> ReadCli.query(a("query", t, "drivers", "--json"))));
        assertEquals(0, quiet(() -> ReadCli.query(a("query", t, "artifacts", "--json", SyntheticDriverSet.DRIVER))));
        assertEquals(0, quiet(() -> ReadCli.query(a("query", t, "chain", "--json", SyntheticDriverSet.DRIVER, "sub"))));
        assertEquals(0, quiet(() -> ReadCli.show(a("show", "--json", t, policy))));
        assertEquals(0, quiet(() -> ReadCli.packageDiff(a("package.diff", "--json", t, policy))));
        assertEquals(0, quiet(() -> ReadCli.prdShow(a("prd.show", "--json", t, SyntheticDriverSet.PRD))));
        assertEquals(0, quiet(() -> ReadCli.prdShow(a("prd.show", "--driver", SyntheticDriverSet.DRIVER, t, SyntheticDriverSet.PRD))));
        assertEquals(0, quiet(() -> ReadCli.prdList(a("prd.list", "--driver", SyntheticDriverSet.DRIVER, t))));
        assertEquals(0, quiet(() -> ReadCli.formList(a("form.list", "--driver", SyntheticDriverSet.DRIVER, t))));
        assertEquals(0, quiet(() -> ReadCli.formShow(a("form.show", "--json", t, SyntheticDriverSet.REQUEST_FORM))));
        assertEquals(0, quiet(() -> ReadCli.appConfigList(a("appconfig.list", "--containers", t))));
        assertEquals(0, quiet(() -> ReadCli.entitlementList(a("entitlement.list", "--json", t))));
        assertEquals(0, quiet(() -> ReadCli.entitlementShow(a("entitlement.show", "--name", SyntheticDriverSet.ENTITLEMENT, t))));
    }

    @Test
    public void readAnUnknownOptionIsAUsageError() throws Exception {
        String[] err = new String[1];
        assertEquals(2, quiet(err, () -> ReadCli.show(a("show", NO_TREE, "p", "--bogus"))));
        assertTrue(err[0], err[0].contains("show has no option --bogus"));
        assertEquals(2, quiet(() -> ReadCli.query(a("query", NO_TREE, "drivers", "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.packageDiff(a("package.diff", NO_TREE, "p", "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.formList(a("form.list", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.formShow(a("form.show", NO_TREE, "f", "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.prdList(a("prd.list", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.prdShow(a("prd.show", NO_TREE, "p", "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.appConfigList(a("appconfig.list", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.appConfigShow(a("appconfig.show", NO_TREE, "o", "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.categoryList(a("category.list", NO_TREE, "--for", "role", "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.entitlementList(a("entitlement.list", NO_TREE, "--bogus"))));
        assertEquals(2, quiet(() -> ReadCli.entitlementShow(a("entitlement.show", NO_TREE, "--name", "N", "--bogus"))));
    }

    @Test
    public void readAnOptionWithoutItsValueIsAUsageError() throws Exception {
        assertEquals(2, quiet(() -> ReadCli.formList(a("form.list", NO_TREE, "--driver"))));
        assertEquals(2, quiet(() -> ReadCli.formShow(a("form.show", NO_TREE, "f", "--driver"))));
        assertEquals(2, quiet(() -> ReadCli.prdList(a("prd.list", NO_TREE, "--driver"))));
        assertEquals(2, quiet(() -> ReadCli.prdShow(a("prd.show", NO_TREE, "p", "--driver", "--json"))));
        assertEquals(2, quiet(() -> ReadCli.appConfigList(a("appconfig.list", NO_TREE, "--kind"))));
        assertEquals(2, quiet(() -> ReadCli.appConfigShow(a("appconfig.show", NO_TREE, "o", "--attr"))));
        assertEquals(2, quiet(() -> ReadCli.categoryList(a("category.list", NO_TREE, "--for"))));
        assertEquals(2, quiet(() -> ReadCli.entitlementShow(a("entitlement.show", NO_TREE, "--name"))));
        assertEquals(2, quiet(() -> ReadCli.entitlementList(a("entitlement.list", NO_TREE, "--driver"))));
    }

    @Test
    public void readListedValuesAndPositionals() throws Exception {
        assertEquals(2, quiet(() -> ReadCli.appConfigList(a("appconfig.list", NO_TREE, "--kind", "nonsense"))));
        assertEquals(2, quiet(() -> ReadCli.categoryList(a("category.list", NO_TREE, "--for", "group"))));
        assertEquals(2, quiet(() -> ReadCli.show(a("show", NO_TREE, "p", "extra"))));
        assertEquals(2, quiet(() -> ReadCli.formList(a("form.list", NO_TREE, "extra"))));
        assertEquals(2, quiet(() -> ReadCli.entitlementShow(a("entitlement.show", NO_TREE, "extra", "--name", "N"))));
    }

    @Test
    public void queryAnUnknownVerbIsAUsageErrorNamingTheVerbs() throws Exception {
        String[] err = new String[1];
        assertEquals(2, quiet(err, () -> ReadCli.query(a("query", NO_TREE, "sideways"))));
        assertTrue(err[0], err[0].contains("unknown query 'sideways'") && err[0].contains("artifacts, chain, gcvs"));
        // each verb takes its own positionals and no more
        assertEquals(2, quiet(() -> ReadCli.query(a("query", NO_TREE, "drivers", "extra"))));
        assertEquals(2, quiet(() -> ReadCli.query(a("query", NO_TREE, "artifacts", "D", "extra"))));
        assertEquals(2, quiet(() -> ReadCli.query(a("query", NO_TREE, "chain", "D"))));
        assertEquals(2, quiet(() -> ReadCli.query(a("query", NO_TREE, "chain", "D", "sub", "extra"))));
        assertEquals(2, quiet(() -> ReadCli.query(a("query", NO_TREE, "chain", "D", "sideways"))));
        assertEquals(2, quiet(() -> ReadCli.query(a("query", "--json", NO_TREE, "events"))));
    }

    // ---- form.edit, form.preview -------------------------------------------------------------

    @Test
    public void formPreviewReadsItsOptions() throws Exception {
        Path t = FormOpsTest.tree(tmp, false);
        Path out = tmp.getRoot().toPath().resolve("req.html");
        assertEquals(0, quiet(() -> FormPreview.run(a("form.preview", "--out", out.toString(), "--lang", "en", t.toString(), "Req"))));
        assertTrue(Files.exists(out));
        assertEquals(2, quiet(() -> FormPreview.run(a("form.preview", NO_TREE, "Req", "--bogus", "x"))));
        // a trailing option without a value used to be dropped silently
        assertEquals(2, quiet(() -> FormPreview.run(a("form.preview", NO_TREE, "Req", "--driver"))));
        assertEquals(2, quiet(() -> FormPreview.run(a("form.preview", NO_TREE, "Req", "extra"))));
        assertEquals(2, quiet(() -> FormPreview.run(a("form.preview", NO_TREE))));
    }

    @Test
    public void formEditReadsItsOptions() throws Exception {
        assertEquals(2, quiet(() -> FormEditCli.run(a("form.edit", NO_TREE, "Req", "--bogus"))));
        assertEquals(2, quiet(() -> FormEditCli.run(a("form.edit", NO_TREE, "Req", "--driver"))));
        assertEquals(2, quiet(() -> FormEditCli.run(a("form.edit", NO_TREE, "Req", "extra"))));
        assertEquals(2, quiet(() -> FormEditCli.run(a("form.edit", "--check"))));
        assertEquals(2, quiet(() -> FormEditCli.run(a("form.edit", NO_TREE, "--no-wait=yes", "Req"))));

        // a flag before the tree keeps the tree: --no-wait launches a (fake) builder on the tree's file
        Assume.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        Path exe = tmp.newFolder("fb").toPath().resolve(FormBuilderLocator.Os.current().exeInLib);
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\nexit 0\n");
        assertTrue(exe.toFile().setExecutable(true, false));
        Path t = FormOpsTest.tree(tmp, false);
        String old = System.getProperty("formbuilder");
        System.setProperty("formbuilder", exe.toString());
        try {
            assertEquals(0, quiet(() -> FormEditCli.run(a("form.edit", "--no-wait", "--driver", "UA", t.toString(), "Req"))));
        } finally {
            if (old == null) {
                System.clearProperty("formbuilder");
            } else {
                System.setProperty("formbuilder", old);
            }
        }
    }
}
