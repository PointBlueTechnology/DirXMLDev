package com.pointblue.dirxml.dev.packages;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The catalog repository: adding a jar verifies it, unpacks it deterministically,
 * updates {@code catalog.json}, and is idempotent; {@link PackageDiff} reads two
 * cataloged versions.
 */
public class CatalogTest {

    private static TestPackageJars.Spec baseSpec(String version, String policyText, boolean withSecondObject) {
        TestPackageJars.Spec s = new TestPackageJars.Spec();
        s.id = "TESTID001_20260101000000";
        s.shortName = "TESTBASE";
        s.symbolicName = "com.pointblue.testbase";
        s.displayName = "Test Base";
        s.version = version;
        s.type = 2;
        s.basePackage = true;
        s.objects.add(new TestPackageJars.Obj("DirXML-Rule", "TESTBASE-pub-pp",
            "<policy><rule><description>" + policyText + "</description><conditions/><actions/></rule></policy>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><installation-directive><placement location=\"publisher\"/>"
                + "<ds-attributes/><policy-linkage><policy-set channel=\"publisher\" name=\"placement\" order=\"Weight\" value=\"500\"/></policy-linkage></installation-directive>",
            "ASSOC0001_20260101000000", "GUID00001_20260101000000"));
        if (withSecondObject) {
            s.objects.add(new TestPackageJars.Obj("DirXML-Rule", "TESTBASE-sub-event",
                "<policy><rule><description>second</description><conditions/><actions/></rule></policy>",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><installation-directive><placement context=\"subscriber\" location=\"event\"/>"
                    + "<ds-attributes/><policy-linkage><policy-set channel=\"subscriber\" name=\"event\" order=\"Weight\" value=\"300\"/></policy-linkage></installation-directive>",
                "ASSOC0002_20260101000000", "GUID00001_20260101000000"));
        }
        return s;
    }

    @Test
    public void addVerifiesUnpacksIndexesAndIsIdempotent() throws Exception {
        Path work = Files.createTempDirectory("pkg-catalog");
        Path catalogDir = work.resolve("catalog");
        Path jarsDir = Files.createDirectory(work.resolve("build"));
        Path jar = TestPackageJars.build(jarsDir, baseSpec("1.0.0", "first version", true));

        Catalog catalog = Catalog.open(catalogDir);
        assertTrue("sites.properties created with defaults", Files.exists(catalogDir.resolve("sites.properties")));
        String sites = Files.readString(catalogDir.resolve("sites.properties"));
        assertTrue(sites.contains(Catalog.DEFAULT_SITE_1));
        assertTrue(sites.contains(Catalog.DEFAULT_SITE_2));

        Catalog.AddResult r1 = catalog.add(jar, "import:" + jar);
        assertTrue(r1.ok());
        assertTrue(r1.added);
        assertEquals("TESTBASE", r1.shortName);
        assertEquals("1.0.0", r1.version);
        assertEquals(64, r1.sha256.length());

        // the jar landed under jars/<SHORT>/<SHORT>_<ver>.jar
        Path jarOut = catalogDir.resolve("jars/TESTBASE/TESTBASE_1.0.0.jar");
        assertTrue(Files.exists(jarOut));

        // the unpacked form: package.xml, objects/<folder>/<name>.xml, README.md
        Path versionDir = catalogDir.resolve("packages/TESTBASE/1.0.0");
        assertTrue(Files.exists(versionDir.resolve("package.xml")));
        assertTrue(Files.exists(versionDir.resolve("README.md")));
        Path obj1 = versionDir.resolve("objects/1-Policies/TESTBASE-pub-pp.xml");
        assertTrue(Files.exists(obj1));
        String obj1Xml = Files.readString(obj1);
        assertTrue("directive decoded in place", obj1Xml.contains("<installation-directive>"));
        assertTrue("directive decoded in place", obj1Xml.contains("<placement location=\"publisher\"/>"));
        assertFalse("no base64 directive left behind", obj1Xml.contains("idm-installationdirective"));
        assertTrue("stored checksums kept as ds-attributes", obj1Xml.contains("idm-contentchecksum"));
        assertTrue(obj1Xml.contains("first version"));

        // package.xml carries metadata + directive but not the package-folder tree
        String packageXml = Files.readString(versionDir.resolve("package.xml"));
        assertTrue(packageXml.contains("idm-shortname"));
        assertTrue(packageXml.contains("installation-directive"));
        assertFalse(packageXml.contains("package-folder"));
        assertFalse("license/readme moved to README.md", packageXml.contains("<readme>"));

        String readme = Files.readString(versionDir.resolve("README.md"));
        assertTrue(readme.contains("a test package"));

        // catalog.json indexes it
        Catalog reopened = Catalog.open(catalogDir);
        Catalog.PackageEntry e = reopened.get("TESTBASE");
        assertTrue(e != null);
        assertEquals("TESTID001_20260101000000", e.id);
        assertEquals(2, e.type);
        assertTrue(e.base);
        assertTrue(e.versions.containsKey("1.0.0"));
        assertEquals(r1.sha256, e.versions.get("1.0.0").sha256);

        // idempotent: adding the same jar again makes no change
        String catalogJsonBefore = Files.readString(catalogDir.resolve("catalog.json"));
        Catalog.AddResult r2 = catalog.add(jar, "import:" + jar);
        assertTrue(r2.ok());
        assertFalse(r2.added);
        String catalogJsonAfter = Files.readString(catalogDir.resolve("catalog.json"));
        assertEquals(catalogJsonBefore, catalogJsonAfter);
    }

    @Test
    public void catalogJsonHasStableKeyOrder() throws Exception {
        Path work = Files.createTempDirectory("pkg-catalog-order");
        Path catalogDir = work.resolve("catalog");
        Path jarsDir = Files.createDirectory(work.resolve("build"));
        Catalog catalog = Catalog.open(catalogDir);
        // add "z" short before "a" short: the index must still sort keys
        TestPackageJars.Spec z = baseSpec("1.0.0", "z", false);
        z.shortName = "ZPKG";
        z.id = "ZID000001_20260101000000";
        TestPackageJars.Spec a = baseSpec("1.0.0", "a", false);
        a.shortName = "APKG";
        a.id = "AID000001_20260101000000";
        catalog.add(TestPackageJars.build(jarsDir, z), "import");
        catalog.add(TestPackageJars.build(jarsDir, a), "import");
        String json = Files.readString(catalogDir.resolve("catalog.json"));
        assertTrue(json.indexOf("\"APKG\"") < json.indexOf("\"ZPKG\""));
    }

    @Test
    public void diffReportsAddedRemovedAndChangedObjects() throws Exception {
        Path work = Files.createTempDirectory("pkg-catalog-diff");
        Path catalogDir = work.resolve("catalog");
        Path jarsDir = Files.createDirectory(work.resolve("build"));
        Catalog catalog = Catalog.open(catalogDir);

        Path jar1 = TestPackageJars.build(jarsDir, baseSpec("1.0.0", "first version", true));
        catalog.add(jar1, "import");

        TestPackageJars.Spec v2 = baseSpec("1.1.0", "changed content", false);   // drops TESTBASE-sub-event, changes pub-pp
        v2.objects.add(new TestPackageJars.Obj("DirXML-Rule", "TESTBASE-new-rule",
            "<policy><rule><description>brand new</description><conditions/><actions/></rule></policy>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><installation-directive><placement location=\"publisher\"/>"
                + "<ds-attributes/><policy-linkage><policy-set channel=\"publisher\" name=\"placement\" order=\"Weight\" value=\"600\"/></policy-linkage></installation-directive>",
            "ASSOC0003_20260101000000", "GUID00001_20260101000000"));
        Path jar2 = TestPackageJars.build(jarsDir, v2);
        Catalog.AddResult r2 = catalog.add(jar2, "import");
        assertTrue(r2.ok());

        PackageJar p1 = PackageJar.read(jar1);
        PackageJar p2 = PackageJar.read(jar2);
        PackageDiff diff = PackageDiff.of(p1, p2);
        assertFalse(diff.isEmpty());
        assertEquals(1, diff.added.size());
        assertTrue(diff.added.get(0).contains("TESTBASE-new-rule"));
        assertEquals(1, diff.removed.size());
        assertTrue(diff.removed.get(0).contains("TESTBASE-sub-event"));
        assertEquals(1, diff.contentChanged.size());
        assertTrue(diff.contentChanged.get(0).contains("TESTBASE-pub-pp"));
    }

    @Test
    public void addRefusesAJarWithABadContentChecksum() throws Exception {
        Path work = Files.createTempDirectory("pkg-catalog-bad");
        Path catalogDir = work.resolve("catalog");
        Path jarsDir = Files.createDirectory(work.resolve("build"));
        TestPackageJars.Spec bad = baseSpec("1.0.0", "tampered content", true);
        bad.objects.get(0).forcedContentChecksum = 1L;   // deliberately wrong
        Path jar = TestPackageJars.build(jarsDir, bad);

        Catalog catalog = Catalog.open(catalogDir);
        Catalog.AddResult r = catalog.add(jar, "import");
        assertFalse(r.ok());
        assertTrue(r.refusal.contains("checksum"));
        assertNull(catalog.get("TESTBASE"));
        assertFalse("nothing written on refusal", Files.exists(catalogDir.resolve("jars/TESTBASE")));
    }
}
