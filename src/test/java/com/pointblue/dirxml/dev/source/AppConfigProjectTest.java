package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Provisioning;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** AppConfig objects in and out of a Designer project (docs/appconfig.md §8): a new project from a tree, and updates to it. */
public class AppConfigProjectTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static DriverSet model() {
        return LdifReader.fromEntries(AppConfigLdifReaderTest.entries(), "synthetic");
    }

    private static List<String> objectDiffs(DriverSet project, DriverSet tree) {
        return ModelDiff.of(project, tree).changes().stream().filter(c -> c.kind.isObject()).map(c -> c.summary).toList();
    }

    @Test
    public void newProjectCarriesEveryObjectAndReadsBackEqual() throws Exception {
        DriverSet ds = model();
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);
        Path project = tmp.newFolder("p").toPath().resolve("New");
        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), false);
        assertTrue(r.text(), r.ok);
        Path app = project.resolve("Model/Provisioning/AppConfig");
        // a packaged ('P' would be a file; the fixture's user entity is stamped but typed '1'): files with digests …
        assertTrue(Files.exists(app.resolve("DirectoryModel/EntityDefs/user.entity")));
        assertTrue(Files.exists(app.resolve("DirectoryModel/EntityDefs/user.digest")));
        assertTrue(Files.exists(app.resolve("DirectoryModel/EntityDefs/EntityDefs.digest")));
        assertTrue(Files.exists(app.resolve("RoleConfig/RoleDefs/Level20/System/provManager.role20")));
        String digest = Files.readString(app.resolve("RoleConfig/RoleDefs/Level20/System/provManager.digest"), StandardCharsets.UTF_8);
        assertTrue(digest, digest.contains("type=\"nrfRoleLevel20\"") && digest.contains("<display xml:lang=\"en\">Provisioning Manager</display>"));
        String userDigest = Files.readString(app.resolve("DirectoryModel/EntityDefs/user.digest"), StandardCharsets.UTF_8);
        assertTrue(userDigest, userDigest.contains("<package-id>PKGID</package-id>") && userDigest.contains("<pkg-assoc-id>ASSOC1</pkg-assoc-id>"));
        String system = Files.readString(app.resolve("RoleConfig/RoleDefs/Level20/System/System.digest"), StandardCharsets.UTF_8);
        assertTrue(system, system.contains("type=\"nrfRoleDefsLevel20-System\""));
        // … and the nav item inline in .appconfig
        String appconfig = Files.readString(app.resolve(".appconfig"), StandardCharsets.UTF_8);
        assertTrue(appconfig, appconfig.contains("ds-object-name=\"AccessRptTool\"") && appconfig.contains("en~Access Report"));
        assertTrue("the runtime container is inline too", appconfig.contains("ds-object-name=\"Requests\""));

        DriverSet back = ProjectReader.read(project);
        Provisioning p = back.driver("UA").provisioning;
        assertNotNull(p);
        assertEquals(List.of(), objectDiffs(back, AsCodeReader.read(tree)));
        AppObject role = p.object("RoleConfig/RoleDefs/Level20/System/provManager");
        assertEquals("file", role.meta.get("project.storage"));
        assertNotNull(role.meta.get("project.guid"));
        assertEquals("the id alone: this synthetic project has no IdmPackage_ record (as for PRDs)", "PKGID",
            com.pointblue.dirxml.dev.model.PackageStamps.packageId(p.object("DirectoryModel/EntityDefs/user").meta));
        assertEquals("inline", p.object("UIConfig/NavItems/AccessRptTool").meta.get("project.storage"));
    }

    @Test
    public void updateAddsChangesAndRemovesObjects() throws Exception {
        DriverSet ds = model();
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);
        Path project = tmp.newFolder("p").toPath().resolve("Upd");
        assertTrue(ProjectWriter.create(tree, project, NewProject.defaults(), false).ok);

        DriverSet t2 = AsCodeReader.read(tree);
        Provisioning p2 = t2.driver("UA").provisioning;
        AppObject cat = AppObject.ofPath("RoleConfig/RoleDefs/Level20/Custom");
        cat.classes.addAll(List.of("Top", "nrfRoleDefs"));
        p2.objects.add(cat);
        AppObject auditor = AppObject.ofPath("RoleConfig/RoleDefs/Level20/Custom/auditor");
        auditor.classes.addAll(List.of("Top", "nrfRole"));
        auditor.put("nrfRoleLevel", List.of("20"));
        auditor.put("nrfStatus", List.of("50"));
        auditor.put("nrfLocalizedNames", List.of("en~Auditor"));
        p2.objects.add(auditor);
        p2.object("UIConfig/NavItems/AccessRptTool").put("nrfLocalizedNames", List.of("en~Access Reporting"));
        p2.objects.remove(p2.object("DirectoryModel/EntityDefs/user"));
        AsCodeWriter.write(t2, tree);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        Path app = project.resolve("Model/Provisioning/AppConfig");
        assertTrue(r.createdFiles.toString(), r.createdFiles.contains("Model/Provisioning/AppConfig/RoleConfig/RoleDefs/Level20/Custom/Custom.digest"));
        assertTrue(r.createdFiles.toString(), r.createdFiles.contains("Model/Provisioning/AppConfig/RoleConfig/RoleDefs/Level20/Custom/auditor.role20"));
        assertTrue(r.deletedFiles.toString(), r.deletedFiles.contains("Model/Provisioning/AppConfig/DirectoryModel/EntityDefs/user.entity"));
        assertTrue(r.changedFiles.toString(), r.changedFiles.contains("Model/Provisioning/AppConfig/.appconfig"));
        assertTrue(Files.readString(app.resolve(".appconfig")).contains("en~Access Reporting"));
        assertTrue(Files.readString(app.resolve("RoleConfig/RoleDefs/Level20/Custom/Custom.digest")).contains("nrfRoleDefsLevel20-Custom"));

        DriverSet back = ProjectReader.read(project);
        assertEquals(List.of(), objectDiffs(back, AsCodeReader.read(tree)));
        assertNull(back.driver("UA").provisioning.object("DirectoryModel/EntityDefs/user"));

        // a second update with nothing changed touches no AppConfig file (the writer's driver-set orphan rewrite predates this)
        ProjectWriter.Result r2 = ProjectWriter.update(tree, project, false);
        assertTrue(r2.text(), r2.ok);
        List<String> touched = new java.util.ArrayList<>(r2.changedFiles);
        touched.addAll(r2.createdFiles);
        touched.addAll(r2.deletedFiles);
        assertTrue(touched.toString(), touched.stream().noneMatch(f -> f.contains("/Provisioning/")));
    }

    @Test
    public void designerProjectWithoutObjectsStillReads() throws IOException {
        // a bare skeleton .appconfig (what a tree without objects gets) yields no objects
        Path project = tmp.newFolder("bare").toPath();
        Path tree = tmp.newFolder("tree").toPath();
        DriverSet ds = model();
        ds.driver("UA").provisioning.objects.clear();
        AsCodeWriter.write(ds, tree);
        Path np = project.resolve("N");
        assertTrue(ProjectWriter.create(tree, np, NewProject.defaults(), false).ok);
        Driver ua = ProjectReader.read(np).driver("UA");
        assertTrue(ua.provisioning.objects.isEmpty());
    }
}
