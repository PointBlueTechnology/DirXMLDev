package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.DriverOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.source.ProjectReader;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Assume;
import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Spike 7b as a test: install the eDirectory driver's four packages the way
 * Designer did in {@code test11}, and compare object by object. Guarded on a
 * local Designer catalog and the test11 project; the unit tests always run.
 */
public class PackageInstallTest {

    static final Path CATALOG = Paths.get("/Applications/Designer/packages/eclipse/plugins");
    static final Path TEST11 = Paths.get(System.getProperty("user.home"), "designer_workspace", "test11");
    static final List<String> JARS = List.of("NOVLEDIRBASE_2.1.2.20190219130306.jar", "NOVLEDIRDCFG_2.1.0.20120831225140.jar",
        "NOVLPWDSYNC_2.1.2.20190806140123.jar", "NOVLEDIRPSYN_1.0.0.jar");

    @Test
    public void designerSetNamesMapToTheModel() {
        assertEquals(PolicySet.INPUT, PackageInstall.policySet("input", ""));
        assertEquals(PolicySet.SCHEMA_MAPPING, PackageInstall.policySet("schema", ""));
        assertEquals(PolicySet.PUB_COMMAND, PackageInstall.policySet("command", "publisher"));
        assertEquals(PolicySet.SUB_CREATE, PackageInstall.policySet("creation", "subscriber"));
        assertEquals(PolicySet.SUB_MATCH, PackageInstall.policySet("subscriber matching", ""));
        assertEquals(PolicySet.GCV, PackageInstall.policySet("gcv", ""));
        assertEquals(PolicySet.ECMASCRIPT, PackageInstall.policySet("ecma-script", ""));
        assertEquals(PolicySet.STARTUP, PackageInstall.policySet("Startup", ""));
        assertEquals(PolicySet.SHUTDOWN, PackageInstall.policySet("Shutdown", ""));
        assertNull(PackageInstall.policySet("not-a-set", ""));
    }

    @Test
    public void filterPrecedenceAndMerge() {
        assertTrue(PackageInstall.precedence("sync") > PackageInstall.precedence("notify"));
        assertTrue(PackageInstall.precedence("notify") > PackageInstall.precedence("ignore"));
        assertTrue(PackageInstall.precedence("ignore") > PackageInstall.precedence("reset"));
        assertTrue(PackageInstall.precedence("reset") > PackageInstall.precedence(""));
        Element into = CanonicalXml.parse("<filter-attr attr-name=\"CN\" publisher=\"ignore\" subscriber=\"sync\"/>").getDocumentElement();
        Element from = CanonicalXml.parse("<filter-attr attr-name=\"CN\" publisher=\"sync\" subscriber=\"notify\" merge-authority=\"app\"/>").getDocumentElement();
        PackageInstall.mergeByPrecedence(from, into, PackageInstall.ATTR_ATTRS);
        assertEquals("sync", into.getAttribute("publisher"));     // raised
        assertEquals("sync", into.getAttribute("subscriber"));    // kept: sync beats notify
        assertEquals("app", into.getAttribute("merge-authority")); // absent → set
    }

    @Test
    public void engineControlsMergeKeepsExistingValues() {
        Element existing = CanonicalXml.parse("<configuration-values><definitions><definition name=\"a\" type=\"string\"><value>old</value></definition></definitions></configuration-values>").getDocumentElement();
        Element incoming = CanonicalXml.parse("<configuration-values><definitions><definition name=\"a\" type=\"string\"><value>new</value></definition><definition name=\"b\" type=\"string\"><value>b</value></definition></definitions></configuration-values>").getDocumentElement();
        Element merged = PackageInstall.Install.mergeGcvDocuments(existing, incoming);
        List<Element> defs = PromptEngine.definitions(merged);
        assertEquals(2, defs.size());
        assertEquals("old", PromptEngine.text(defs.get(0)).trim());
    }

    @Test
    public void versionsCompareNumerically() {
        assertTrue(PackageInstall.compareVersions("2.1.2.20190219130306", "2.1.2") > 0);
        assertTrue(PackageInstall.compareVersions("1.0.0", "1.0.1") < 0);
        assertTrue(PackageInstall.compareVersions("4.10.0.20241023190534", "4.8.8.20240401125038") > 0);
    }

    @Test
    public void installsTheEdirectoryDriverLikeDesigner() throws Exception {
        Assume.assumeTrue(Files.isDirectory(CATALOG) && Files.isDirectory(TEST11));
        for (String j : JARS) {
            Assume.assumeTrue(Files.exists(CATALOG.resolve(j)));
        }
        Path tree = Files.createTempDirectory("p7b");
        DriverSet ref = ProjectReader.read(TEST11);
        Driver designer = ref.driver("eDir2eDirJFWOld");
        assertNotNull(designer);
        AsCodeWriter.write(ref, tree);
        // answers = the values Designer's driver has (prompts must match for content parity)
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("shim-auth-server", "172.17.2.112:8192");
        for (Artifact a : designer.artifacts()) {
            if (a instanceof com.pointblue.dirxml.dev.model.Resource && ((com.pointblue.dirxml.dev.model.Resource) a).isGcvDef()) {
                for (Element def : PromptEngine.definitions(((com.pointblue.dirxml.dev.model.Resource) a).content)) {
                    Element v = PromptEngine.child(def, "value");
                    String text = v == null ? null : PromptEngine.text(v);
                    if (text != null && !text.isBlank()) {
                        answers.putIfAbsent(def.getAttribute("name"), text.trim());
                    }
                }
            }
        }
        List<Path> jars = new ArrayList<>();
        for (String j : JARS) {
            jars.add(CATALOG.resolve(j));
        }
        // one transaction: the driver from the base package + the whole set (Designer validates after installing all)
        Transaction tx = Transaction.open(tree);
        Result r = tx.run(new DriverOps.Add("eDirTest", null, null, false, null, null, null).withPackages(jars, answers), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.customized.isEmpty());

        DriverSet after = AsCodeReader.read(tree);
        Driver ours = after.driver("eDirTest");
        assertEquals("com.novell.nds.dirxml.driver.nds.DriverShimImpl", ours.shimClass);
        assertEquals("172.17.2.112:8192", ours.shimAuthServer);
        // every packaged object Designer installed exists here with the same installed checksum
        Map<String, Artifact> oursByAssoc = new LinkedHashMap<>();
        for (Artifact a : ours.artifacts()) {
            if (com.pointblue.dirxml.dev.model.PackageStamps.assocId(a.meta) != null) {
                oursByAssoc.put(com.pointblue.dirxml.dev.model.PackageStamps.assocId(a.meta), a);
            }
        }
        int compared = 0;
        for (Artifact a : designer.artifacts()) {
            String assoc = com.pointblue.dirxml.dev.model.PackageStamps.assocId(a.meta);
            if (assoc == null) {
                continue;   // hand-made policies in the reference driver
            }
            Artifact o = oursByAssoc.get(assoc);
            assertNotNull("missing " + a.path(), o);
            assertEquals("scope of " + a.name, a.scope, o.scope);
            assertEquals("checksum of " + a.name, com.pointblue.dirxml.dev.model.PackageStamps.checksum(a.meta), o.meta.get(PackageInstall.META_CHECKSUM));
            compared++;
        }
        assertEquals(18, compared);
        // set order: Designer's, minus its hand-made policies (the GCV order is the vault's: weights 120, 140, 500)
        for (PolicySet set : PolicySet.values()) {
            List<String> want = new ArrayList<>();
            for (PolicyLink l : designer.links(set)) {
                Artifact a = ref.resolve(l.ref);
                if (a != null && com.pointblue.dirxml.dev.model.PackageStamps.assocId(a.meta) != null) {
                    want.add(a.name);
                }
            }
            List<String> have = new ArrayList<>();
            for (PolicyLink l : ours.links(set)) {
                have.add(after.resolve(l.ref).name);
            }
            if (set == PolicySet.GCV) {
                assertEquals(List.of("NOVLEDIRDCFG-GCVs", "NOVLEDIRPSYN-GCVs", "NOVLPWDSYNC-GCVs"), have);
            } else {
                assertEquals("set " + set.key, want, have);
            }
        }
        // stamps and records
        assertTrue(ours.meta.get(PackageInstall.META_GUID).startsWith("H32H32B6_201007011046370279;com.novellinc.novledirbase;2.1.2.20190219130306;"));
        assertTrue(ours.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLEDIRBASE").endsWith(";base"));
        assertNotNull(ours.meta.get(PackageInstall.META_INSTALLED_PREFIX + "NOVLPWDSYNC"));
        Artifact gcv = oursByAssoc.get("EC05R5E2_201008101722020853");
        assertTrue(gcv.meta.get(PackageInstall.META_LINKAGES).contains("name=\"gcv\""));
        assertTrue(Files.exists(com.pointblue.dirxml.dev.edit.Packages.baselineFile(tree, gcv)));
        assertFalse(com.pointblue.dirxml.dev.edit.Packages.isCustomized(gcv));
        // a second base package is refused; installing the same package twice is refused
        Result again = Transaction.open(tree).run(new PackageInstall(List.of(jars.get(0)), "eDirTest", answers, true), true, false);
        assertFalse(again.ok());
        assertTrue(again.refusal, again.refusal.contains("already installed"));
    }

    @Test
    public void mandatoryPromptWithoutAnAnswerIsRefused() throws Exception {
        Assume.assumeTrue(Files.isDirectory(CATALOG) && Files.isDirectory(TEST11));
        Path tree = Files.createTempDirectory("p7b2");
        AsCodeWriter.write(ProjectReader.read(TEST11), tree);
        Result r = Transaction.open(tree).run(new DriverOps.Add("eDirTest", null, null, false, null, null, null)
            .withPackages(List.of(CATALOG.resolve(JARS.get(0))), Map.of()), true, false);
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("shim-auth-server"));
    }
}
