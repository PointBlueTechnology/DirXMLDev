package com.pointblue.dirxml.dev.packages;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Seeds a catalog offline from a local Designer install — the same jars the
 * update site would hand back, without touching the network (docs/packages.md
 * §3.1's {@code package.import} of a Designer packages directory). Guarded on
 * that install being present.
 */
public class DesignerCatalogGuardedTest {

    static final Path DESIGNER_PLUGINS = Paths.get("/Applications/Designer/packages/eclipse/plugins");
    static final String EDIRBASE = "NOVLEDIRBASE_2.1.2.20190219130306.jar";
    static final String EDIRDCFG = "NOVLEDIRDCFG_2.1.0.20120831225140.jar";
    static final String COMSET = "NOVLCOMSET_2.0.1.20190806144720.jar";   // newest present locally

    @Before
    public void requireDesignerCatalog() {
        Assume.assumeTrue(Files.isDirectory(DESIGNER_PLUGINS));
        for (String jar : List.of(EDIRBASE, EDIRDCFG, COMSET)) {
            Assume.assumeTrue(Files.exists(DESIGNER_PLUGINS.resolve(jar)));
        }
    }

    private Catalog importAll() throws Exception {
        Path work = Files.createTempDirectory("pkg-designer-catalog");
        Catalog catalog = Catalog.open(work.resolve("catalog"));
        for (String jar : List.of(EDIRBASE, EDIRDCFG, COMSET)) {
            Catalog.AddResult r = catalog.add(DESIGNER_PLUGINS.resolve(jar), "import:" + jar);
            assertTrue(jar + ": " + r.refusal, r.ok());
            assertTrue(jar + " should have been newly added", r.added);
        }
        return catalog;
    }

    @Test
    public void importedEverythingAndIndexedIt() throws Exception {
        Catalog catalog = importAll();
        assertTrue(catalog.get("NOVLEDIRBASE") != null);
        assertTrue(catalog.get("NOVLEDIRBASE").base);
        assertEquals(2, catalog.get("NOVLEDIRBASE").type);
        assertTrue(catalog.get("NOVLEDIRDCFG") != null);
        assertFalse(catalog.get("NOVLEDIRDCFG").base);
        assertTrue(catalog.get("NOVLCOMSET") != null);
        assertEquals(3, catalog.get("NOVLCOMSET").type);

        // re-opening the catalog from disk sees the same index
        Catalog reopened = Catalog.open(catalog.dir);
        assertTrue(reopened.get("NOVLEDIRBASE").versions.containsKey("2.1.2.20190219130306"));
    }

    @Test
    public void resolveReportsOptionalFeatureAndMissingType3Dependency() throws Exception {
        Catalog catalog = importAll();

        // without requesting the feature: NOVLEDIRDCFG is not pulled in
        Resolver.Result r0 = Resolver.resolve(catalog, "NOVLEDIRBASE", List.of(), Set.of(), Set.of());
        assertTrue(r0.refusal, r0.ok());
        assertFalse(r0.install.stream().anyMatch(c -> c.shortName.equals("NOVLEDIRDCFG")));

        // requesting the feature pulls it in; its type-3 dependency on Common Settings is unmet
        Resolver.Result r1 = Resolver.resolve(catalog, "NOVLEDIRBASE", List.of("NOVLEDIRDCFG"), Set.of(), Set.of());
        assertTrue(r1.refusal, r1.ok());
        assertTrue(r1.install.stream().anyMatch(c -> c.shortName.equals("NOVLEDIRDCFG")));
        assertTrue("Common Settings reported missing", r1.missing.stream().anyMatch(m -> m.name.contains("Common Settings")));

        // satisfied once the driver set is said to already carry it
        Resolver.Result r2 = Resolver.resolve(catalog, "NOVLEDIRBASE", List.of("NOVLEDIRDCFG"),
            Set.of("NOVLCOMSET_2.0.1.20190806144720"), Set.of());
        assertTrue(r2.refusal, r2.ok());
        assertTrue("Common Settings now satisfied", r2.missing.isEmpty());
    }

    @Test
    public void cliImportShowAndResolveEndToEnd() throws Exception {
        Path work = Files.createTempDirectory("pkg-designer-cli");
        Path catalogDir = work.resolve("catalog");
        for (String jar : List.of(EDIRBASE, EDIRDCFG, COMSET)) {
            int rc = PackageCli.run(new String[]{"package.import", "--catalog", catalogDir.toString(),
                DESIGNER_PLUGINS.resolve(jar).toString()});
            assertEquals(jar, 0, rc);
        }
        String show = capture(() -> {
            int rc = PackageCli.run(new String[]{"package.show", "--catalog", catalogDir.toString(), "NOVLEDIRBASE"});
            assertEquals(0, rc);
        });
        assertTrue(show.contains("NOVLEDIRBASE"));
        assertTrue(show.contains("eDirectory Default Configuration"));

        String resolve = capture(() -> {
            int rc = PackageCli.run(new String[]{"package.resolve", "--catalog", catalogDir.toString(),
                "--base", "NOVLEDIRBASE", "--feature", "NOVLEDIRDCFG"});
            assertEquals(0, rc);
        });
        assertTrue(resolve.contains("NOVLEDIRDCFG"));
        assertTrue(resolve.toLowerCase().contains("common settings"));
    }

    private interface Action {
        void run() throws Exception;
    }

    private static String capture(Action action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
