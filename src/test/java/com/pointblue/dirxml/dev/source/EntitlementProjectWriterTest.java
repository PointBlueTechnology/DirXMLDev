package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.EntitlementOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProjectWriter} carrying entitlement changes (add/change/remove) into an
 * existing Designer project (Track W step W4b — see {@code docs/entitlements.md} §2):
 * {@code <id>.Entitlement_} + {@code <id>_contents.xml}, mirroring a driver-scope
 * policy, with the driver's own {@code Idm:Entitlements} relation kept in step —
 * see {@code ProvisioningProjectWriterTest} for the skeleton style this borrows.
 */
public class EntitlementProjectWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private int seq;

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String cobject(String name, String type, String attrsXml, String relationsXml) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<com.novell.designer.model:CObject xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\" name=\"" + name + "\" type=\"" + type + "\">"
            + attrsXml + relationsXml
            + "</com.novell.designer.model:CObject>";
    }

    private static String cstring(String attrName, String value) {
        return "<attributes xsi:type=\"com.novell.designer.model:CString\" attrName=\"" + attrName + "\" value=\"" + value + "\"/>";
    }

    private static String rel(String name, String type, String key) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + key + "\"/>";
    }

    private static final String EXISTING_ENT_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><entitlement conflict-resolution=\"priority\" description=\"\" "
        + "display-name=\"Existing\"><values multi-valued=\"true\"><value>x</value></values></entitlement>";

    /** One driver ("Loopback") with a single pre-existing entititlement, no packages, no provisioning. */
    private Path buildSkeleton() throws IOException {
        Path root = tmp.newFolder("skeleton" + (seq++)).toPath();
        write(root.resolve("Model/DS1.DriverSet_"),
            cobject("driverset1", "DriverSet", cstring("DSetContext", "o=system"), rel("Idm:Drivers", "Reference", "DRV1.Driver_")));
        write(root.resolve("Model/DRV1.Driver_"),
            cobject("Loopback", "Loopback 3.5.0", cstring("DirXML-JavaModule", "com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim"),
                rel("Idm:Entitlements", "Child", "ENT0001.Entitlement_")));
        write(root.resolve("Model/ENT0001.Entitlement_"),
            cobject("Existing", "Entitlement",
                "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"contents\"/>", ""));
        write(root.resolve("Model/ENT0001_contents.xml"), EXISTING_ENT_XML);
        return root;
    }

    private static void run(Path tree, com.pointblue.dirxml.dev.edit.Operation op) throws IOException {
        Result r = Transaction.open(tree).run(op, false, false);
        assertTrue(r.text(), r.ok() && r.written);
    }

    @Test
    public void projectReaderFindsTheExistingEntitlement() throws IOException {
        Path project = buildSkeleton();
        DriverSet ds = ProjectReader.read(project);
        Driver loopback = ds.driver("Loopback");
        assertNotNull(loopback);
        assertEquals(1, loopback.entitlements.size());
        Entitlement e = loopback.entitlement("Existing");
        assertNotNull(e);
        assertEquals("Existing", e.displayName());
        assertEquals("priority", e.conflictResolution());
        assertNotNull(e.meta.get("designer.id"));
    }

    @Test
    public void addChangeRemoveEntitlement() throws IOException {
        Path project = buildSkeleton();
        DriverSet initial = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(initial, tree);

        // add
        run(tree, new EntitlementOps.Add("Loopback", "TestAccess", "Group", null, true, "priority", "a,b", null));
        // change an existing one's document
        run(tree, new EntitlementOps.Set("Loopback", "Existing", "Existing (renamed)", null, null, null, null, null));

        Path metaFile = project.resolve("Model/ENT0001.Entitlement_");
        Path contentsFile = project.resolve("Model/ENT0001_contents.xml");
        assertTrue(Files.exists(metaFile));

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertNull(r.refusal);

        // one new CObject + contents file for the add; the changed one's contents rewritten in place
        assertEquals(2, r.createdFiles.size());
        String newMetaPath = r.createdFiles.stream().filter(f -> f.endsWith(".Entitlement_")).findFirst().orElse(null);
        assertNotNull(r.createdFiles.toString(), newMetaPath);
        String newContentsPath = r.createdFiles.stream().filter(f -> f.endsWith("_contents.xml")).findFirst().orElse(null);
        assertNotNull(newContentsPath);
        assertTrue(r.changedFiles.toString(), r.changedFiles.contains("Model/ENT0001_contents.xml"));
        assertTrue(r.deletedFiles.toString(), r.deletedFiles.isEmpty());

        // the new entitlement's minted id got an Idm:Entitlements relation on the driver CObject
        String driverXml = Files.readString(project.resolve("Model/DRV1.Driver_"));
        assertTrue(driverXml, driverXml.contains("Idm:Entitlements"));
        assertEquals(2, count(driverXml, "Idm:Entitlements"));

        // the changed entitlement's content reflects the new display-name
        assertTrue(Files.readString(contentsFile).contains("Existing (renamed)"));

        // reader parity: re-reading the project matches the tree
        DriverSet reread = ProjectReader.read(project);
        Driver loopback = reread.driver("Loopback");
        assertEquals(2, loopback.entitlements.size());
        Entitlement ta = loopback.entitlement("TestAccess");
        assertNotNull(ta);
        assertEquals("Group", ta.displayName());
        assertEquals("true", ta.multiValued());
        Entitlement existing = loopback.entitlement("Existing");
        assertNotNull(existing);
        assertEquals("Existing (renamed)", existing.displayName());

        // now remove "Existing" via the tree and re-run the writer
        Path tree2 = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(reread, tree2);
        run(tree2, new EntitlementOps.Remove("Loopback", "Existing"));
        ProjectWriter.Result r2 = ProjectWriter.update(tree2, project, false);
        assertTrue(r2.text(), r2.ok);
        assertTrue(r2.deletedFiles.toString(), r2.deletedFiles.contains("Model/ENT0001.Entitlement_"));
        assertTrue(r2.deletedFiles.toString(), r2.deletedFiles.contains("Model/ENT0001_contents.xml"));

        String driverXmlAfter = Files.readString(project.resolve("Model/DRV1.Driver_"));
        assertEquals(1, count(driverXmlAfter, "Idm:Entitlements"));
        assertFalse(driverXmlAfter.contains("ENT0001"));

        DriverSet finalRead = ProjectReader.read(project);
        assertEquals(1, finalRead.driver("Loopback").entitlements.size());
        assertNotNull(finalRead.driver("Loopback").entitlement("TestAccess"));
        assertNull(finalRead.driver("Loopback").entitlement("Existing"));
    }

    @Test
    public void noChangeTouchesNothing() throws IOException {
        Path project = buildSkeleton();
        DriverSet initial = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(initial, tree);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.changedFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
