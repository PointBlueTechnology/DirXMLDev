package com.pointblue.dirxml.dev.packages;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The dependency closure over a small synthetic graph:
 * <pre>
 *   BASE (base package)
 *     mandatory feature -&gt; FEATMAND
 *     optional feature  -&gt; FEATOPT, BROKENFEAT
 *     dependency (type 2, min 1.5.0) -&gt; COMMONLIB (1.0.0 / 1.5.0 / 2.0.0)
 *     dependency (type 3, any)       -&gt; DRVSET
 *   FEATMAND depends (type 2, any) on COMMONLIB too — met twice, same choice kept
 *   BROKENFEAT depends (type 2) on a package id that is not in the catalog — unresolvable
 * </pre>
 */
public class ResolverTest {

    private Catalog buildCatalog() throws Exception {
        Path work = Files.createTempDirectory("pkg-resolver");
        Catalog catalog = Catalog.open(work.resolve("catalog"));
        Path jarsDir = Files.createDirectory(work.resolve("build"));

        addPkg(catalog, jarsDir, "BASE", "BASEID001_20260101000000", "com.pointblue.base", 2, true, "1.0.0",
            "<installation-directive>"
                + "<features><mandatory><package id=\"FEATID001_20260101000000\" display-name=\"Feature Mandatory\"/></mandatory>"
                + "<optional><package id=\"FEATID002_20260101000000\" display-name=\"Feature Optional\"/>"
                + "<package id=\"BROKID001_20260101000000\" display-name=\"Broken Feature\"/></optional></features>"
                + "<dependencies>"
                + "<dependency name=\"Common Lib\" package-id=\"COMMID001_20260101000000\" type=\"2\"><min-version value=\"1.5.0\"/></dependency>"
                + "<dependency name=\"Driver Set Thing\" package-id=\"DRVSETID1_20260101000000\" type=\"3\"/>"
                + "</dependencies><ds-attributes/></installation-directive>");

        addPkg(catalog, jarsDir, "FEATMAND", "FEATID001_20260101000000", "com.pointblue.featmand", 2, false, "1.0.0",
            "<installation-directive><dependencies>"
                + "<dependency name=\"Common Lib\" package-id=\"COMMID001_20260101000000\" type=\"2\"/>"
                + "</dependencies><ds-attributes/></installation-directive>");

        addPkg(catalog, jarsDir, "FEATOPT", "FEATID002_20260101000000", "com.pointblue.featopt", 2, false, "1.0.0",
            "<installation-directive><ds-attributes/></installation-directive>");

        addPkg(catalog, jarsDir, "BROKENFEAT", "BROKID001_20260101000000", "com.pointblue.broken", 2, false, "1.0.0",
            "<installation-directive><dependencies>"
                + "<dependency name=\"Missing Thing\" package-id=\"MISSINGID0_20260101000000\" type=\"2\"/>"
                + "</dependencies><ds-attributes/></installation-directive>");

        for (String v : List.of("1.0.0", "1.5.0", "2.0.0")) {
            addPkg(catalog, jarsDir, "COMMONLIB", "COMMID001_20260101000000", "com.pointblue.commonlib", 2, false, v,
                "<installation-directive><ds-attributes/></installation-directive>");
        }

        addPkg(catalog, jarsDir, "DRVSET", "DRVSETID1_20260101000000", "com.pointblue.drvset", 3, false, "1.0.0",
            "<installation-directive><ds-attributes/></installation-directive>");

        return catalog;
    }

    private static void addPkg(Catalog catalog, Path jarsDir, String shortName, String id, String symbolic,
                                int type, boolean base, String version, String installDirectiveXml) throws Exception {
        TestPackageJars.Spec s = new TestPackageJars.Spec();
        s.id = id;
        s.shortName = shortName;
        s.symbolicName = symbolic;
        s.displayName = shortName;
        s.version = version;
        s.type = type;
        s.basePackage = base;
        s.installDirectiveXml = installDirectiveXml;
        Path jar = TestPackageJars.build(jarsDir, s);
        Catalog.AddResult r = catalog.add(jar, "test");
        assertTrue(shortName + "_" + version + ": " + r.refusal, r.ok());
    }

    @Test
    public void mandatoryFeaturePulledInOptionalNotAndType3ReportedMissing() throws Exception {
        Catalog catalog = buildCatalog();
        Resolver.Result r = Resolver.resolve(catalog, "BASE", List.of(), Set.of(), Set.of());
        assertTrue(r.refusal, r.ok());
        List<String> shorts = r.install.stream().map(c -> c.shortName).toList();
        assertTrue(shorts.contains("BASE"));
        assertTrue(shorts.contains("FEATMAND"));
        assertTrue(shorts.contains("COMMONLIB"));
        assertFalse("optional feature not requested", shorts.contains("FEATOPT"));
        assertFalse("optional feature not requested", shorts.contains("BROKENFEAT"));
        // dependencies before dependants: BASE's own hard dependency (COMMONLIB) must precede it;
        // base itself precedes its feature packages (features are siblings chosen via base, not its dependencies).
        int commonIdx = shorts.indexOf("COMMONLIB");
        int baseIdx = shorts.indexOf("BASE");
        int featIdx = shorts.indexOf("FEATMAND");
        assertTrue("dependency before dependant", commonIdx >= 0 && commonIdx < baseIdx);
        assertTrue("base before its feature packages", baseIdx < featIdx);
        assertEquals(1, shorts.stream().filter("COMMONLIB"::equals).count());

        assertEquals(1, r.missing.size());
        assertEquals("Driver Set Thing", r.missing.get(0).name);
    }

    @Test
    public void versionConstraintChoosesTheNewerAcceptableVersion() throws Exception {
        Catalog catalog = buildCatalog();
        Resolver.Result r = Resolver.resolve(catalog, "BASE", List.of(), Set.of(), Set.of());
        assertTrue(r.ok());
        String commonVersion = r.install.stream().filter(c -> c.shortName.equals("COMMONLIB")).findFirst().get().version;
        assertEquals("2.0.0", commonVersion);
    }

    @Test
    public void optionalFeatureRequestedAndType3SatisfiedByDriverSetHas() throws Exception {
        Catalog catalog = buildCatalog();
        Resolver.Result r = Resolver.resolve(catalog, "BASE", List.of("FEATOPT"), Set.of("DRVSET_1.0.0"), Set.of());
        assertTrue(r.refusal, r.ok());
        List<String> shorts = r.install.stream().map(c -> c.shortName).toList();
        assertTrue(shorts.contains("FEATOPT"));
        assertTrue("type-3 dependency satisfied, nothing missing", r.missing.isEmpty());
    }

    @Test
    public void unresolvableChainIsRefused() throws Exception {
        Catalog catalog = buildCatalog();
        Resolver.Result r = Resolver.resolve(catalog, "BASE", List.of("BROKENFEAT"), Set.of(), Set.of());
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("MISSINGID0_20260101000000") || r.refusal.contains("BROKENFEAT"));
    }

    @Test
    public void unknownBaseIsRefused() throws Exception {
        Catalog catalog = buildCatalog();
        Resolver.Result r = Resolver.resolve(catalog, "NOSUCHPKG", List.of(), Set.of(), Set.of());
        assertFalse(r.ok());
        assertTrue(r.install.isEmpty());
    }
}
