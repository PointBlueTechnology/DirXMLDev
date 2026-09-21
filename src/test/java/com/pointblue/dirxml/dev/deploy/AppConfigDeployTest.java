package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.source.AppConfigLdifReaderTest;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.sim.LdifDriverSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** AppConfig objects through ModelDiff → Plan → Deployer (docs/appconfig.md §7). */
public class AppConfigDeployTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String APPCFG = "cn=AppConfig,cn=UA," + DS;

    private static DriverSet model() {
        return LdifReader.fromEntries(AppConfigLdifReaderTest.entries(), "synthetic");
    }

    private static AppObject role(Provisioning p, String name, String level, String category) {
        AppObject r = AppObject.ofPath("RoleConfig/RoleDefs/Level" + level + "/" + category + "/" + name);
        r.classes.addAll(List.of("Top", "nrfRole"));
        r.put("nrfRoleLevel", List.of(level));
        r.put("nrfStatus", List.of("50"));
        r.put("nrfRoleCategoryKey", List.of(category.toLowerCase()));
        r.put("nrfLocalizedNames", List.of("en~" + name));
        p.objects.add(r);
        return r;
    }

    private static List<String> steps(Plan plan) {
        List<String> out = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            out.add(s.op + " " + s.dn + (s.attr == null ? "" : " " + s.attr));
        }
        return out;
    }

    // ---- diff ----------------------------------------------------------------------------

    @Test
    public void identicalModelsDiffEmpty_operationalAttributesNeverCount() {
        DriverSet from = model();
        DriverSet to = model();
        assertTrue(ModelDiff.of(from, to).text(), ModelDiff.of(from, to).isEmpty());
        AppObject role = to.driver("UA").provisioning.object("RoleConfig/RoleDefs/Level20/System/provManager");
        role.put("equivalentToMe", List.of("cn=someone,o=data"));
        role.attrs.remove("DirXML-Associations");
        assertTrue("operational attributes are never a difference", ModelDiff.of(from, to).isEmpty());
        // a hand-edited, non-canonical XML value still compares equal
        AppObject user = to.driver("UA").provisioning.object("DirectoryModel/EntityDefs/user");
        user.put("XmlData", List.of(user.first("XmlData").replace("\n", "\n\n").replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "")));
        assertTrue(ModelDiff.of(from, to).text(), ModelDiff.of(from, to).isEmpty());
    }

    @Test
    public void addedChangedRemovedObjectsAreReportedWithParts_noRestart() {
        DriverSet from = model();
        DriverSet to = model();
        Provisioning p = to.driver("UA").provisioning;
        role(p, "helpdesk", "20", "System");
        AppObject nav = p.object("UIConfig/NavItems/AccessRptTool");
        nav.put("nrfLocalizedNames", List.of("en~Access Reports"));
        nav.put("nrfDefault", List.of("true"));
        p.objects.remove(p.object("DirectoryModel/EntityDefs/user"));
        ModelDiff diff = ModelDiff.of(from, to);
        List<String> kinds = new ArrayList<>();
        for (ModelDiff.Change c : diff.changes()) {
            kinds.add(c.kind + " " + c.path + (c.parts == null ? "" : " " + c.parts));
        }
        assertEquals(List.of(
            "OBJECT_REMOVED drivers/UA/provisioning/objects/DirectoryModel/EntityDefs/user",
            "OBJECT_ADDED drivers/UA/provisioning/objects/RoleConfig/RoleDefs/Level20/System/helpdesk",
            "OBJECT_CHANGED drivers/UA/provisioning/objects/UIConfig/NavItems/AccessRptTool [nrfDefault, nrfLocalizedNames]"), kinds);
        assertTrue("the applications read these, not the engine", diff.affectedDrivers().isEmpty());
        assertTrue(diff.text(), diff.text().contains("+ nrfLocalizedNames: en~Access Reports"));
        assertEquals("entity", diff.changes().get(0).what);
        // the guard: the tree still has entities? no — the only entity is gone
        assertEquals(1, diff.emptyKinds().size());
        assertEquals("entities", diff.emptyKinds().get(0).kind);
    }

    // ---- plan ----------------------------------------------------------------------------

    @Test
    public void planWritesOnlyChangedAttributes_parentsFirst_childrenDeletedFirst() throws Exception {
        DriverSet from = model();
        DriverSet to = model();
        Provisioning p = to.driver("UA").provisioning;
        // a new category container with a role inside: the container must be added first
        AppObject cat = AppObject.ofPath("RoleConfig/RoleDefs/Level20/Custom");
        cat.classes.addAll(List.of("Top", "nrfRoleDefs"));
        p.objects.add(cat);
        role(p, "auditor", "20", "Custom");
        AppObject nav = p.object("UIConfig/NavItems/AccessRptTool");
        nav.put("nrfLocalizedNames", List.of("en~Access Reports"));
        nav.attrs.remove("nrfNavItemType");
        // remove the RoleDefs/Level20/System container with its role: the role must go first
        p.objects.remove(p.object("RoleConfig/RoleDefs/Level20/System/provManager"));
        p.objects.remove(p.object("RoleConfig/RoleDefs/Level20/System"));
        ModelDiff diff = ModelDiff.of(from, to);
        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, null, List.of(), null, List.of("roles"));
        assertTrue(plan.restart.isEmpty());
        String catDn = "cn=Custom,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG;
        String roleDn = "cn=auditor," + catDn;
        String navDn = "cn=AccessRptTool,cn=NavItems,cn=UIConfig," + APPCFG;
        String sysDn = "cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG;
        assertEquals(steps(plan).toString(), List.of(
            "ADD " + catDn,
            "ADD " + roleDn,
            "MODIFY " + navDn + " nrfLocalizedNames",
            "MODIFY " + navDn + " nrfNavItemType",
            "DELETE cn=provManager," + sysDn,
            "DELETE " + sysDn), steps(plan));
        Plan.Step add = plan.steps.get(1);
        assertEquals(List.of("Top", "nrfRole"), add.objectClasses);
        assertEquals("20", new String(add.values.get("nrfRoleLevel").get(0), StandardCharsets.UTF_8));
        assertNull("no operational attribute is ever written", add.values.get("DirXML-Associations"));
        Plan.Step remove = plan.steps.get(3);
        assertTrue("an emptied attribute is removed", remove.values.get("nrfNavItemType").isEmpty());
    }

    @Test
    public void guardHoldsBackTheLastOfAKind_deleteAllOverrides_runtimeContainersNever() throws Exception {
        DriverSet from = model();
        DriverSet to = model();
        Provisioning p = to.driver("UA").provisioning;
        p.objects.remove(p.object("RoleConfig/RoleDefs/Level20/System/provManager"));
        p.objects.remove(p.object("RoleConfig/Requests"));
        ModelDiff diff = ModelDiff.of(from, to);
        Plan guarded = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, null, List.of(), null, List.of());
        assertTrue(steps(guarded).toString(), steps(guarded).isEmpty());
        assertTrue(guarded.notes.toString(), guarded.notes.stream().anyMatch(n -> n.contains("the tree has no roles but the vault has 1")));
        assertTrue(guarded.notes.toString(), guarded.notes.stream().anyMatch(n -> n.contains("runtime container is never deleted")));
        Plan overridden = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, null, List.of(), null, List.of("roles", "containers"));
        assertEquals(List.of("DELETE cn=provManager,cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG), steps(overridden));
    }

    @Test
    public void customizedPackagedObjectGetsAContentChecksum() throws Exception {
        DriverSet from = model();
        DriverSet to = model();
        AppObject user = to.driver("UA").provisioning.object("DirectoryModel/EntityDefs/user");
        user.put("XmlData", List.of(user.first("XmlData").replace("key=\"cn\"", "key=\"cn\" enabled=\"true\"")));
        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(Set.of("XmlData"), diff.changes().get(0).parts);
        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, null);
        String dn = "cn=user,cn=EntityDefs,cn=DirectoryModel," + APPCFG;
        assertEquals(List.of("MODIFY " + dn + " XmlData", "MODIFY " + dn + " DirXML-pkgChecksum"), steps(plan));
        String checksum = new String(plan.steps.get(1).values.get("DirXML-pkgChecksum").get(0), StandardCharsets.UTF_8);
        assertFalse("123".equals(checksum));
        assertTrue(plan.notes.toString(), plan.notes.stream().anyMatch(n -> n.contains("customized in the tree")));

        // after that deploy the vault holds the customized content with the derived checksum while the tree
        // still records the package's: the same state, so the verify diff is empty …
        DriverSet vault = model();
        AppObject live = vault.driver("UA").provisioning.object("DirectoryModel/EntityDefs/user");
        live.put("XmlData", user.all("XmlData"));
        live.meta.put("dirxml-pkgchecksum", checksum);
        assertTrue(ModelDiff.of(vault, to).text(), ModelDiff.of(vault, to).isEmpty());
        // … and reverting the content writes the content and the package checksum back, nothing else
        ModelDiff revert = ModelDiff.of(vault, from);
        assertEquals(Set.of("XmlData", "stamps"), revert.changes().get(0).parts);
        Plan back = Plan.of(revert, from, DS, Secrets.none(), "none", null, true, null);
        assertEquals(List.of("MODIFY " + dn + " XmlData", "MODIFY " + dn + " DirXML-pkgChecksum"), steps(back));
        assertEquals("123", new String(back.steps.get(1).values.get("DirXML-pkgChecksum").get(0), StandardCharsets.UTF_8));
    }

    // ---- deploy end to end against the fake vault ----------------------------------------

    private static FakeVault seeded() {
        FakeVault vault = new FakeVault();
        for (LdifDriverSource.Entry e : AppConfigLdifReaderTest.entries()) {
            Vault.Entry ve = new Vault.Entry(e.dn);
            for (String name : e.attributeNames()) {
                List<byte[]> bytes = new ArrayList<>();
                for (String v : e.all(name)) {
                    bytes.add(v.getBytes(StandardCharsets.UTF_8));
                }
                ve.attrs.put(name, bytes);
            }
            vault.seed(ve);
        }
        return vault;
    }

    private static Deployer.Options options(Path tree) {
        Deployer.Options o = new Deployer.Options();
        o.tree = tree;
        o.env = new Environments.Environment("test", "ldaps://fake:636", "cn=admin,o=system", "pw", DS,
            Environments.Tier.STG, null, null, true, null, null);
        o.yes = true;
        o.restart = false;
        return o;
    }

    @Test
    public void deployAddsChangesAndDeletesObjects_verifiesAndAudits() throws Exception {
        FakeVault vault = seeded();
        DriverSet to = model();
        Provisioning p = to.driver("UA").provisioning;
        AppObject r = role(p, "helpdesk", "20", "System");
        r.put("nrfRoleCategoryKey", List.of("system", "helpdesk"));   // multi-valued: verified as a set
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(to, tree);
        Deployer.Result r1 = new Deployer(options(tree), vault).run();
        assertTrue(r1.text(), r1.ok);
        assertTrue(r1.text(), r1.verified);
        String roleDn = "cn=helpdesk,cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG;
        Vault.Entry live = vault.read(roleDn);
        assertNotNull(live);
        assertEquals("en~helpdesk", live.string("nrfLocalizedNames"));
        assertTrue(live.hasClass("nrfRole"));

        // change one attribute: exactly one write, verified
        DriverSet t2 = AsCodeReader.read(tree);
        t2.driver("UA").provisioning.object("RoleConfig/RoleDefs/Level20/System/helpdesk").put("nrfLocalizedNames", List.of("en~Help desk"));
        AsCodeWriter.write(t2, tree);
        Deployer.Result r2 = new Deployer(options(tree), vault).run();
        assertTrue(r2.text(), r2.ok && r2.verified);
        assertEquals(1, r2.done.size());
        assertEquals("en~Help desk", vault.read(roleDn).string("nrfLocalizedNames"));

        // remove it: another role remains, so no guard; deleted and verified
        DriverSet t3 = AsCodeReader.read(tree);
        Provisioning p3 = t3.driver("UA").provisioning;
        p3.objects.remove(p3.object("RoleConfig/RoleDefs/Level20/System/helpdesk"));
        AsCodeWriter.write(t3, tree);
        Deployer.Result r3 = new Deployer(options(tree), vault).run();
        assertTrue(r3.text(), r3.ok && r3.verified);
        assertFalse(vault.exists(roleDn));
        assertTrue(vault.exists("cn=provManager,cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG));
    }
}
