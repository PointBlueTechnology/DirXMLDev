package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.ArtifactOps;
import com.pointblue.dirxml.dev.edit.DriverOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.source.ProjectReader;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** package.status and package.adopt over a tree that package.install built (guarded on the Designer catalog + test11). */
public class PackageStatusTest {

    @Test
    public void parsesTheFiveFieldRecord() {
        PackageStatus.Installed i = PackageStatus.parse("H32H32B6_201007011046370279;com.novellinc.novledirbase;2.1.2.20190219130306;eDirectory Base;NOVLEDIRBASE;base");
        assertEquals("NOVLEDIRBASE", i.shortName);
        assertEquals("2.1.2.20190219130306", i.version);
        assertTrue(i.base);
        assertEquals("id:X", PackageStatus.parse("X").shortName);
    }

    @Test
    public void statusAndAdoptOnAnInstalledDriver() throws Exception {
        Assume.assumeTrue(Files.isDirectory(PackageInstallTest.CATALOG) && Files.isDirectory(PackageInstallTest.TEST11));
        Path tree = Files.createTempDirectory("p7s");
        AsCodeWriter.write(ProjectReader.read(PackageInstallTest.TEST11), tree);
        List<Path> jars = List.of(PackageInstallTest.CATALOG.resolve(PackageInstallTest.JARS.get(0)),
            PackageInstallTest.CATALOG.resolve(PackageInstallTest.JARS.get(1)));
        Map<String, String> answers = Map.of("shim-auth-server", "h:8196", "drv.remote.dit.data.users", "data\\users",
            "drv.remote.dit.data.groups", "data\\groups");
        Result r = Transaction.open(tree).run(new DriverOps.Add("eDirS", null, null, false, null, null, null).withPackages(jars, answers), false, false);
        assertTrue(r.text(), r.ok());

        PackageStatus st = PackageStatus.of(tree, "eDirS", null);
        assertEquals(1, st.targets.size());
        PackageStatus.Target t = st.targets.get(0);
        assertEquals(2, t.packages.size());
        assertTrue(t.packages.stream().anyMatch(p -> p.shortName.equals("NOVLEDIRBASE") && p.base && p.inManifest));
        assertTrue(t.packages.stream().anyMatch(p -> p.shortName.equals("NOVLEDIRDCFG") && p.objects == 4 && p.inManifest));
        assertTrue("fresh install is not customized: " + t.customized, t.customized.isEmpty());

        String beforeChecksum = AsCodeReader.read(tree).resolve("drivers/eDirS/publisher/NOVLEDIRDCFG-pub-pp")
            .meta.get(PackageInstall.META_CHECKSUM);
        assertNotNull(beforeChecksum);

        // customize a packaged policy → status reports it customized
        Result c = Transaction.open(tree).run(new ArtifactOps.SetContent("drivers/eDirS/publisher/NOVLEDIRDCFG-pub-pp",
            "<policy><rule><description>x</description><conditions/><actions/></rule></policy>"), false, false);
        assertTrue(c.text(), c.ok());
        st = PackageStatus.of(tree, "eDirS", null);
        assertEquals(List.of("drivers/eDirS/publisher/NOVLEDIRDCFG-pub-pp"), st.targets.get(0).customized);

        // follow-up 2 (docs/vault-deploy.md, package stamps of customized objects): the transaction already
        // refreshed the tree's meta checksum from the new content (Packages.refreshChecksums), so the stamp is
        // now up to date — equal to a fresh recompute of the customized content, not the stale installed value —
        // and package.status must still report it customized on the `package.customized` mark alone, not on a
        // checksum mismatch.
        DriverSet withCustomization = AsCodeReader.read(tree);
        Driver eDirS = withCustomization.driver("eDirS");
        Artifact customized = withCustomization.resolve("drivers/eDirS/publisher/NOVLEDIRDCFG-pub-pp");
        assertTrue(com.pointblue.dirxml.dev.edit.Packages.isCustomized(customized));
        assertEquals("" + InstalledChecksum.of(withCustomization, eDirS, customized),
            customized.meta.get(PackageInstall.META_CHECKSUM));
        assertNotEquals("the recomputed stamp must differ from the pre-customization (installed) value",
            beforeChecksum, customized.meta.get(PackageInstall.META_CHECKSUM));
        st = PackageStatus.of(tree, "eDirS", null);
        assertEquals("still reported customized although its stamp is up to date",
            List.of("drivers/eDirS/publisher/NOVLEDIRDCFG-pub-pp"), st.targets.get(0).customized);

        // strip the manifest records (as a vault import has none) → status notes it, adopt writes them back
        DriverSet ds = AsCodeReader.read(tree);
        List<String> keys = new ArrayList<>(ds.driver("eDirS").meta.keySet());
        for (String k : keys) {
            if (k.startsWith(PackageInstall.META_INSTALLED_PREFIX)) {
                ds.driver("eDirS").meta.remove(k);
            }
        }
        AsCodeWriter.write(ds, tree);
        st = PackageStatus.of(tree, "eDirS", null);
        assertFalse(st.targets.get(0).notes.isEmpty());
        assertTrue(st.targets.get(0).packages.stream().noneMatch(p -> p.inManifest));
        Result a = Transaction.open(tree).run(new PackageAdopt("eDirS", null), false, false);
        assertTrue(a.text(), a.ok());
        st = PackageStatus.of(tree, "eDirS", null);
        assertTrue(st.targets.get(0).packages.stream().allMatch(p -> p.inManifest));
        assertTrue(st.targets.get(0).packages.stream().anyMatch(p -> p.shortName.equals("NOVLEDIRBASE") && p.base));
        // idempotent
        Result again = Transaction.open(tree).run(new PackageAdopt("eDirS", null), true, false);
        assertTrue(again.ok());
        assertTrue(again.notes.get(0), again.notes.get(0).startsWith("0 installed-package"));
    }
}
