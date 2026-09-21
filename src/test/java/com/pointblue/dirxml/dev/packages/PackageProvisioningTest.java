package com.pointblue.dirxml.dev.packages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.AppObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A User Application package's AppConfig ({@code children/provisioning}, base64) is read, checksummed and unpacked. */
public class PackageProvisioningTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PROVISIONING =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><provisioning packages=\"true\" version=\"4.8\">"
        + "<ds-object ds-object-class=\"srvprvAppConfig\" ds-object-name=\"AppConfig\"><ds-attributes>"
        + "<ds-attribute ds-attr-name=\"Version\"><ds-value>4.8</ds-value></ds-attribute></ds-attributes>"
        + "<ds-object ds-object-class=\"srvprvDirectoryModel\" ds-object-name=\"DirectoryModel\">"
        + "<ds-object ds-object-class=\"srvprvEntityDefs\" ds-object-name=\"EntityDefs\">"
        + "<ds-object checksum=\"77\" ds-object-class=\"srvprvEntity\" ds-object-name=\"user\" guid=\"G1\" package-id=\"PKG1\" pkg-assoc-id=\"A1\">"
        + "<ds-attributes><ds-attribute ds-attr-name=\"XmlData\"><ds-value><entity-definition><entity><key>user</key></entity></entity-definition></ds-value></ds-attribute>"
        + "<ds-attribute ds-attr-name=\"srvprvEntityType\"><ds-value>1</ds-value></ds-attribute></ds-attributes></ds-object>"
        + "</ds-object></ds-object>"
        + "<ds-object ds-object-class=\"nrfConfig\" ds-object-name=\"RoleConfig\"><ds-object ds-object-class=\"nrfRoleDefs\" ds-object-name=\"RoleDefs\">"
        + "<ds-object ds-object-class=\"nrfRoleDefs\" ds-object-name=\"Level20\"><ds-object ds-object-class=\"nrfRoleDefs\" ds-object-name=\"System\">"
        + "<ds-object ds-object-class=\"nrfRole\" ds-object-name=\"provManager\" package-id=\"PKG1\" pkg-assoc-id=\"A2\"><ds-attributes>"
        + "<ds-attribute ds-attr-name=\"nrfRoleLevel\"><ds-value>20</ds-value></ds-attribute>"
        + "<ds-attribute ds-attr-name=\"nrfLocalizedNames\"><ds-value>en~Provisioning Manager</ds-value></ds-attribute>"
        + "</ds-attributes></ds-object></ds-object></ds-object></ds-object></ds-object>"
        + "</ds-object></provisioning>";

    private Path jar() throws Exception {
        TestPackageJars.Spec s = new TestPackageJars.Spec();
        s.id = "PKG1";
        s.shortName = "TESTUA";
        s.symbolicName = "com.test.testua";
        s.version = "1.0.0";
        s.basePackage = true;
        s.provisioningXml = PROVISIONING;
        s.objects.add(new TestPackageJars.Obj("DirXML-Rule", "TESTUA-pol", "<policy/>",
            "<installation-directive><ds-attributes/></installation-directive>", "A0", "PKG1"));
        return TestPackageJars.build(tmp.newFolder("jars").toPath(), s);
    }

    @Test
    public void provisioningObjectsAreReadWithPathsAndStamps() throws Exception {
        PackageJar p = PackageJar.read(jar());
        assertNotNull(p.provisioning);
        assertEquals("AppConfig", p.provisioning.getAttribute("ds-object-name"));
        List<String> paths = p.provisioningObjects.stream().map(AppObject::path).toList();
        assertEquals(List.of("DirectoryModel", "DirectoryModel/EntityDefs", "DirectoryModel/EntityDefs/user",
            "RoleConfig", "RoleConfig/RoleDefs", "RoleConfig/RoleDefs/Level20", "RoleConfig/RoleDefs/Level20/System",
            "RoleConfig/RoleDefs/Level20/System/provManager"), paths);
        AppObject user = p.provisioningObjects.get(2);
        assertEquals(AppObject.Kind.ENTITY, user.kind());
        assertEquals("PKG1", user.meta.get("package-id"));
        assertEquals("A1", user.meta.get("pkg-assoc-id"));
        assertEquals("77", user.meta.get("checksum"));
        assertEquals("G1", user.meta.get("designer.guid"));
        assertTrue(user.first("XmlData").contains("<entity-definition>"));
        assertEquals("1", user.first("srvprvEntityType"));
        assertTrue("the decoded document is what the folder checksum covers", p.folderProvisioningData.get(5).startsWith("<?xml"));
        assertTrue("package checksum recomputes with the provisioning folder",
            ChecksumAudit.of(p).lines.stream().anyMatch(l -> "package".equals(l.kind) && l.stored.equals("" + l.recomputed)));
    }

    @Test
    public void catalogUnpacksThemUnderTheProvisioningFolder() throws Exception {
        Catalog catalog = Catalog.open(tmp.newFolder("catalog").toPath());
        Catalog.AddResult r = catalog.add(jar(), "test");
        assertTrue(r.refusal, r.ok());
        Path version = catalog.dir.resolve("packages/TESTUA/1.0.0");
        assertTrue(Files.exists(version.resolve("objects/5-Provisioning/DirectoryModel/EntityDefs/user.xml")));
        assertTrue(Files.exists(version.resolve("objects/5-Provisioning/RoleConfig/RoleDefs/Level20/System/provManager.xml")));
        assertTrue(Files.exists(version.resolve("objects/5-Provisioning/DirectoryModel.xml")));
        String user = Files.readString(version.resolve("objects/5-Provisioning/DirectoryModel/EntityDefs/user.xml"));
        assertTrue(user.contains("ds-object-class=\"srvprvEntity\""));
        assertTrue(user.contains("<entity-definition>"));
    }
}
