package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.DriverOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.source.ProjectReader;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** package.build: a tree's hand-made policies become a package Designer's checks accept, and it installs back identically. */
public class PackageBuildTest {

    @Test
    public void buildsInstallsAndVersions() throws Exception {
        Assume.assumeTrue(Files.isDirectory(PackageInstallTest.CATALOG) && Files.isDirectory(PackageInstallTest.TEST11));
        Path tree = Files.createTempDirectory("pbuild");
        AsCodeWriter.write(ProjectReader.read(PackageInstallTest.TEST11), tree);
        Path out = Files.createTempDirectory("pbuild-out");

        PackageBuilder.Options o = new PackageBuilder.Options();
        o.tree = tree;
        o.driver = "eDir2eDirJFWOld";   // two hand-made policies among packaged ones
        o.shortName = "PBTTEST";
        o.name = "Test custom";
        o.vendor = "Point Blue";
        PackageBuilder.Result r = PackageBuilder.build(o, out);
        assertTrue(r.text(), r.ok);
        assertEquals(3, r.objects.size());   // two policies + the GCV object carrying the definitions they read
        // Designer's import check: every stored checksum recomputes; the catalog accepts it
        PackageJar p = PackageJar.read(r.jar);
        assertTrue(ChecksumAudit.of(p).allMatch());
        assertEquals("PBTTEST", p.shortName);
        assertEquals(2, p.type);
        assertEquals("com.pointblue.pbttest", p.symbolicName);
        assertTrue(p.version.startsWith("1.0.0."));
        // Designer offers a package only on drivers of a declared type: derived from the driver's type here (no base jar in the catalog)
        String sd = new String(java.util.Base64.getMimeDecoder().decode(p.manifestAttr("Supported-Drivers")), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(sd, sd.contains("driver-id=\"EDIR-Driver\""));
        Catalog catalog = Catalog.open(Files.createTempDirectory("pbuild-cat"));
        Catalog.AddResult a = catalog.add(r.jar, "built");
        assertTrue(a.refusal, a.ok());

        // customized packaged objects are refused unless --customized keep
        PackageBuilder.Options bad = new PackageBuilder.Options();
        bad.tree = tree;
        bad.driver = "eDir2eDirJFWOld";
        bad.shortName = "PBTTEST2";
        bad.name = "x";
        bad.include.add("drivers/eDir2eDirJFWOld/publisher/NOVLEDIRDCFG-pub-pp");
        PackageBuilder.Result rb = PackageBuilder.build(bad, out);
        assertFalse(rb.ok);
        assertTrue(rb.refusal, rb.refusal.contains("one package"));

        // install it onto a driver that has the base/config packages the policies' GCVs come from
        List<Path> jars = List.of(PackageInstallTest.CATALOG.resolve(PackageInstallTest.JARS.get(0)),
            PackageInstallTest.CATALOG.resolve(PackageInstallTest.JARS.get(1)));
        Map<String, String> answers = Map.of("shim-auth-server", "h:8196", "drv.remote.dit.data.users", "data\\users",
            "drv.remote.dit.data.groups", "data\\groups");
        Result add = Transaction.open(tree).run(new DriverOps.Add("Cust", null, null, false, null, null, null).withPackages(jars, answers), false, false);
        assertTrue(add.text(), add.ok());
        Result in = Transaction.open(tree).run(new PackageInstall(List.of(r.jar), "Cust", Map.of(), true), false, false);
        assertTrue(in.text(), in.ok());
        DriverSet ds = AsCodeReader.read(tree);
        for (String name : List.of("subscriber/sub-etp-Scoping", "publisher/pub-ctp-Handle Driver modes")) {
            Artifact src = ds.resolve("drivers/eDir2eDirJFWOld/" + name);
            Artifact dst = ds.resolve("drivers/Cust/" + name);
            assertNotNull(name, dst);
            assertEquals(CanonicalXml.serialize(((Policy) src).content), CanonicalXml.serialize(((Policy) dst).content));
            assertEquals(p.pkg.getAttribute("id"), dst.meta.get(PackageInstall.META_PACKAGE_ID));
            assertNotNull(dst.meta.get(PackageInstall.META_CHECKSUM));
        }
        assertNotNull(ds.driver("Cust").meta.get(PackageInstall.META_INSTALLED_PREFIX + "PBTTEST"));

        // a new version reuses the package id and the association ids
        PackageBuilder.Options v2 = new PackageBuilder.Options();
        v2.tree = tree;
        v2.driver = "eDir2eDirJFWOld";
        v2.shortName = "PBTTEST";
        v2.name = "Test custom";
        v2.vendor = "Point Blue";
        v2.version = "1.0.1";
        v2.newVersionOf = r.jar;
        PackageBuilder.Result r2 = PackageBuilder.build(v2, out);
        assertTrue(r2.text(), r2.ok);
        PackageJar p2 = PackageJar.read(r2.jar);
        assertEquals(p.pkg.getAttribute("id"), p2.pkg.getAttribute("id"));
        assertTrue(p2.version.startsWith("1.0.1."));
        for (PackageJar.Item it : p.items) {
            assertTrue(it.name, p2.items.stream().anyMatch(x -> x.name.equals(it.name) && x.assocId.equals(it.assocId)));
        }
        // and the site renders both
        Catalog.AddResult a2 = catalog.add(r2.jar, "built");
        assertTrue(a2.refusal, a2.ok());
        Path site = Files.createTempDirectory("pbuild-site");
        List<String> written = SiteWriter.write(catalog, site, "test");
        assertEquals(2, written.size());
        assertTrue(Files.exists(site.resolve("site.xml")));
        assertTrue(Files.readString(site.resolve("site.xml")).contains("PBTTEST.feature_" + p.version + ".jar"));
        assertTrue(Files.exists(site.resolve("plugins").resolve("PBTTEST_" + p.version + ".jar")));
    }
}
