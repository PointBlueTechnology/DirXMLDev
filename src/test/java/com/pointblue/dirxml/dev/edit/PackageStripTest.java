package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSyncTest;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.PackageStamps;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@code package.strip}: every stamp gone from the driver and everything under it, baselines dropped, the driver marked; the Library only with --library. */
public class PackageStripTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String GUID = "B5PAGQ5E_201005261601510810;com.netiqcorporation.novluabase;4.8.0.20190927160316;User Application Base;NOVLUABASE";
    private static final String POLICY = "<policy><rule><description>x</description><conditions/><actions/></rule></policy>";

    private static void stamp(Map<String, String> meta, String assoc) {
        meta.put("dirxml-pkgguid", GUID);
        meta.put("dirxml-pkgassociationid", assoc);
        meta.put("dirxml-pkgchecksum", "123");
        meta.put("dirxml-pkglinkages", "<linkages><package package-id=\"B5PAGQ5E_201005261601510810\"/></linkages>");
    }

    /** {@code FormOpsTest.tree} (UA: packaged form + PRD) plus a packaged and a customized policy, an entitlement, a Library policy and the driver's own records. */
    private Path tree() throws Exception {
        Path t = FormOpsTest.tree(tmp, true);
        DriverSet ds = AsCodeReader.read(t);
        Driver ua = ds.driver("UA");
        ua.meta.put("dirxml-pkgguid", GUID);
        ua.meta.put("dirxml-pkgextensions", "<filter/>");
        ua.meta.put(PackageStamps.INSTALLED_PREFIX + "NOVLUABASE", GUID + ";base");
        Policy p = new Policy("NOVLUABASE-smp", Scope.DRIVER, "UA", ValidatorTest.xml(POLICY));
        stamp(p.meta, "A1");
        ua.policies.add(p);
        Policy custom = new Policy("NOVLUABASE-sub-etp", Scope.SUBSCRIBER, "UA", ValidatorTest.xml(POLICY));
        stamp(custom.meta, "A2");
        custom.meta.put(Packages.CUSTOMIZED_KEY, "true");
        custom.meta.put(PackageRevert.BASELINE_CHECKSUM_KEY, "123");
        ua.subscriber.policies.add(custom);
        Entitlement e = new Entitlement("Group", ValidatorTest.xml("<entitlement conflict-resolution=\"priority\" display-name=\"Group\"><values multi-valued=\"true\"/></entitlement>"));
        stamp(e.meta, "A3");
        ua.entitlements.add(e);
        Policy lib = new Policy("NOVLLIB-shared", Scope.LIBRARY, null, ValidatorTest.xml(POLICY));
        stamp(lib.meta, "L1");
        ds.library.policies.add(lib);
        ds.meta.put("dirxml-pkgguid", "SETPKG;com.x.set;1.0.0");
        ds.meta.put(PackageStamps.INSTALLED_PREFIX + "SETPKG", "SETPKG;com.x.set;1.0.0");
        AsCodeWriter.write(ds, t);
        Path baseline = Packages.baselineFile(t, custom);
        Files.createDirectories(baseline.getParent());
        Files.writeString(baseline, POLICY);
        return t;
    }

    private static Result run(Path tree, Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    @Test
    public void stripsTheDriverAndEverythingUnderIt_libraryUntouched() throws Exception {
        Path t = tree();
        Policy custom = (Policy) AsCodeReader.read(t).resolve("drivers/UA/subscriber/NOVLUABASE-sub-etp");
        Path baseline = Packages.baselineFile(t, custom);
        assertTrue(Files.exists(baseline));

        Result r = run(t, new PackageStrip("UA", false));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(t);
        Driver ua = ds.driver("UA");
        assertEquals("true", ua.meta.get(PackageStrip.STRIPPED_KEY));
        for (String k : ua.meta.keySet()) {
            assertFalse(k, PackageStrip.isPackageKey(k));
        }
        for (var a : ua.artifacts()) {
            assertFalse(a.path(), PackageStamps.isPackaged(a.meta));
            assertNull(a.path(), a.meta.get(Packages.CUSTOMIZED_KEY));
            assertNull(a.path(), a.meta.get(PackageRevert.BASELINE_CHECKSUM_KEY));
        }
        assertFalse(PackageStamps.isPackaged(ua.entitlement("Group").meta));
        assertFalse(PackageStamps.isPackaged(ua.provisioning.form(Form.Kind.REQUEST, "Req").meta));
        assertFalse(PackageStamps.isPackaged(ua.provisioning.prd("P").meta));
        assertEquals("the customized content stays; it is simply the content now", "x",
            ((Policy) ds.resolve("drivers/UA/subscriber/NOVLUABASE-sub-etp")).content.getElementsByTagName("description").item(0).getFirstChild().getNodeValue());
        assertFalse("the baseline is gone", Files.exists(baseline));
        assertTrue(r.deletedFiles.toString(), r.deletedFiles.stream().anyMatch(f -> f.startsWith(".package-baseline/")));
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/NOVLUABASE-smp"));
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/provisioning/forms/request/Req"));
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/entitlements/Group"));
        assertTrue(r.notes.toString(), r.notes.toString().contains("fully custom driver"));

        assertTrue("the Library is shared: untouched without --library", PackageStamps.isPackaged(ds.resolve("library/NOVLLIB-shared").meta));
        assertNotNull(ds.meta.get("dirxml-pkgguid"));
        assertNull(ds.meta.get(PackageStrip.STRIPPED_KEY));
        assertTrue(PackageStrip.isStripped(ds, "UA"));
        assertFalse(PackageStrip.isStripped(ds, null));

        Result again = run(t, new PackageStrip("UA", false));
        assertTrue(again.refusal, again.refusal != null && again.refusal.contains("carries no package stamps"));
    }

    @Test
    public void libraryFlagStripsTheLibraryAndTheDriverSetRecords() throws Exception {
        Path t = tree();
        Result r = run(t, new PackageStrip(null, true));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(t);
        assertFalse(PackageStamps.isPackaged(ds.resolve("library/NOVLLIB-shared").meta));
        assertNull(ds.meta.get("dirxml-pkgguid"));
        assertNull(ds.meta.get(PackageStamps.INSTALLED_PREFIX + "SETPKG"));
        assertEquals("true", ds.meta.get(PackageStrip.STRIPPED_KEY));
        assertTrue("the driver is not implied", PackageStamps.isPackaged(ds.driver("UA").meta));
        assertTrue(r.touched.toString(), r.touched.contains("library/NOVLLIB-shared"));
    }

    @Test
    public void refusals() throws Exception {
        Path t = tree();
        assertTrue(run(t, new PackageStrip(null, false)).refusal.contains("--driver"));
        assertTrue(run(t, new PackageStrip("Nope", false)).refusal.contains("no driver 'Nope'"));
        Result dry = Transaction.open(t).run(new PackageStrip("UA", true), true, false);
        assertTrue(dry.text(), dry.ok());
        assertTrue("dry run writes nothing", PackageStamps.isPackaged(AsCodeReader.read(t).driver("UA").meta));
        assertTrue(Files.exists(Packages.baselineFile(t, (Policy) AsCodeReader.read(t).resolve("drivers/UA/subscriber/NOVLUABASE-sub-etp"))));
    }

    @Test
    public void stripKeysCoverEveryVocabulary() {
        for (String k : new String[] {"dirxml-pkgguid", "DirXML-pkgLinkages", "package-id", "pkg-assoc-id", "project.package-id",
            "project.pkg-assoc-id", "project.pkg-checksum", "package.installed.NOVLUABASE", "package.customized", "package.baseline-checksum"}) {
            assertTrue(k, PackageStrip.isPackageKey(k));
        }
        for (String k : new String[] {"objectClass", "dn", "designer.id", "package.named-passwords", "dirxml-driverstartoption", "linkage.unparsed.0"}) {
            assertFalse(k, PackageStrip.isPackageKey(k));
        }
    }
}
