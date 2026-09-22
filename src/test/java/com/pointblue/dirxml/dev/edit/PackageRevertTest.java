package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSyncTest;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.source.AppConfigLdifReaderTest;
import com.pointblue.dirxml.dev.source.LdifReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** {@code package.revert}: baseline back, mark dropped, the package's checksum restored, the baseline file gone. */
public class PackageRevertTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Result run(Path tree, Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    @Test
    public void appConfigObjectRoundTrip() throws Exception {
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(LdifReader.fromEntries(AppConfigLdifReaderTest.entries(), "synthetic"), tree);
        String path = "drivers/UA/provisioning/objects/DirectoryModel/EntityDefs/user";
        Result before = run(tree, new PackageRevert(path, null));
        assertTrue(before.refusal, before.refusal.contains("not marked customized"));

        Result edit = run(tree, new AppConfigOps.EntityAttrAdd(null, "user", "room", "roomNumber", null, null, null, Map.of()));
        assertTrue(edit.text(), edit.ok());
        AppObject user = AsCodeReader.read(tree).driver("UA").provisioning.object("DirectoryModel/EntityDefs/user");
        assertEquals("true", user.meta.get(Packages.CUSTOMIZED_KEY));
        assertEquals("the package's checksum is remembered", "123", user.meta.get(PackageRevert.BASELINE_CHECKSUM_KEY));
        assertFalse("123".equals(user.meta.get("dirxml-pkgchecksum")));
        Path baseline = tree.resolve(".package-baseline/" + path + ".xml");
        assertTrue(Files.exists(baseline));

        Result r = run(tree, new PackageRevert(path, null));
        assertTrue(r.text(), r.ok());
        user = AsCodeReader.read(tree).driver("UA").provisioning.object("DirectoryModel/EntityDefs/user");
        assertFalse(user.first("XmlData").contains("roomNumber"));
        assertNull(user.meta.get(Packages.CUSTOMIZED_KEY));
        assertNull(user.meta.get(PackageRevert.BASELINE_CHECKSUM_KEY));
        assertEquals("123", user.meta.get("dirxml-pkgchecksum"));
        assertFalse("the baseline is gone", Files.exists(baseline));
        assertTrue(r.deletedFiles.toString(), r.deletedFiles.contains(".package-baseline/" + path + ".xml"));
        assertTrue(r.touched.contains(path));
    }

    @Test
    public void formRoundTrip_andUnrecordedChecksumIsNoted() throws Exception {
        Path tree = FormOpsTest.tree(tmp, true);
        String path = "drivers/UA/provisioning/forms/request/Req";
        Result edit = run(tree, new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2));
        assertTrue(edit.text(), edit.ok());
        Form f = AsCodeReader.read(tree).driver("UA").provisioning.form(Form.Kind.REQUEST, "Req");
        assertEquals("536857469", f.meta.get(PackageRevert.BASELINE_CHECKSUM_KEY));
        Result r = run(tree, new PackageRevert(path, null));
        assertTrue(r.text(), r.ok());
        f = AsCodeReader.read(tree).driver("UA").provisioning.form(Form.Kind.REQUEST, "Req");
        assertEquals(Json.parse(BindingSyncTest.FORM_V1), Json.parse(f.json));
        assertNull(f.meta.get(Packages.CUSTOMIZED_KEY));
        assertEquals("536857469", f.meta.get("dirxml-pkgchecksum"));
        assertFalse(Files.exists(tree.resolve(".package-baseline/" + path + ".form.json")));
    }

    @Rule
    public TemporaryFolder tmp2 = new TemporaryFolder();

    /** A form customized before the tree recorded the package checksum: reverted, with the note. */
    @Test
    public void unrecordedChecksumIsNoted() throws Exception {
        String path = "drivers/UA/provisioning/forms/request/Req";
        Path tree2 = FormOpsTest.tree(tmp2, true);
        assertTrue(run(tree2, new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2)).ok());
        DriverSet ds = AsCodeReader.read(tree2);
        ds.driver("UA").provisioning.form(Form.Kind.REQUEST, "Req").meta.remove(PackageRevert.BASELINE_CHECKSUM_KEY);
        AsCodeWriter.write(ds, tree2);
        Result r2 = run(tree2, new PackageRevert(path, null));
        assertTrue(r2.text(), r2.ok());
        assertTrue(r2.notes.toString(), r2.notes.stream().anyMatch(n -> n.contains("not on record")));
        assertNull(AsCodeReader.read(tree2).driver("UA").provisioning.form(Form.Kind.REQUEST, "Req").meta.get(Packages.CUSTOMIZED_KEY));
    }

    @Test
    public void refusals() throws Exception {
        Path tree = FormOpsTest.tree(tmp, false);
        Result notPackaged = run(tree, new PackageRevert("drivers/UA/provisioning/forms/request/Req", null));
        assertTrue(notPackaged.refusal, notPackaged.refusal.contains("not a packaged object"));
        Result nowhere = run(tree, new PackageRevert("drivers/UA/provisioning/forms/request/Nope", null));
        assertTrue(nowhere.refusal, nowhere.refusal.contains("no form"));
        Result artifact = run(tree, new PackageRevert("library/Nope", null));
        assertTrue(artifact.refusal, artifact.refusal.contains("no artifact"));
        assertTrue(Registry.get("package.revert") != null);
    }
}
