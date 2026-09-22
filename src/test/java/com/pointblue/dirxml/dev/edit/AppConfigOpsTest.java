package com.pointblue.dirxml.dev.edit;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The typed AppConfig operations (docs/appconfig.md §9) on the synthetic tree, through Transaction. */
public class AppConfigOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path tree;

    @Before
    public void tree() throws Exception {
        tree = tmp.newFolder("tree").toPath();
        DriverSet ds = LdifReader.fromEntries(AppConfigLdifReaderTest.entries(), "synthetic");
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    private Result force(Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, true);
    }

    private Provisioning prov() throws Exception {
        return AsCodeReader.read(tree).driver("UA").provisioning;
    }

    @Test
    public void setMergesLocalizedTextAndRefusesOperationalAndIdentity() throws Exception {
        Result r = run(new AppConfigOps.Set(null, "UIConfig/NavItems/AccessRptTool", "nrfLocalizedNames", "de=Zugriffsbericht", null, false));
        assertTrue(r.text(), r.ok());
        assertEquals("en~Access Report|de~Zugriffsbericht", prov().object("UIConfig/NavItems/AccessRptTool").first("nrfLocalizedNames"));
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/provisioning/objects/UIConfig/NavItems/AccessRptTool"));
        Result bad = run(new AppConfigOps.Set(null, "AccessRptTool", "equivalentToMe", "cn=x", null, false));
        assertFalse(bad.ok());
        assertTrue(bad.refusal, bad.refusal.contains("operational"));
        Result id = run(new AppConfigOps.Set(null, "AccessRptTool", "cn", "x", null, false));
        assertTrue(id.refusal, id.refusal.contains("identity"));
        Result gone = run(new AppConfigOps.Set(null, "AccessRptTool", "nrfDefault", null, null, true));
        assertTrue(gone.refusal, gone.refusal.contains("no attribute"));
        Result rm = run(new AppConfigOps.Set(null, "AccessRptTool", "nrfNavItemType", null, null, true));
        assertTrue(rm.text(), rm.ok());
        assertNull(prov().object("UIConfig/NavItems/AccessRptTool").first("nrfNavItemType"));
    }

    @Test
    public void setXmlFromFileCanonicalizes_andPackagedEditIsBaselinedAndChecksummed() throws Exception {
        Path f = tmp.newFile("x.xml").toPath();
        Files.writeString(f, "<entity-definition>\n  <entity><key>user</key></entity>\n<attributes/></entity-definition>");
        Result r = run(new AppConfigOps.Set(null, "DirectoryModel/EntityDefs/user", "XmlData", null, f.toString(), false));
        assertTrue(r.text(), r.ok());
        AppObject user = prov().object("DirectoryModel/EntityDefs/user");
        assertTrue(user.first("XmlData").startsWith("<?xml"));
        assertEquals("true", user.meta.get(Packages.CUSTOMIZED_KEY));
        assertFalse("checksum follows the content", "123".equals(user.meta.get("dirxml-pkgchecksum")));
        assertTrue(r.customized.toString(), r.customized.contains("drivers/UA/provisioning/objects/DirectoryModel/EntityDefs/user"));
        assertTrue(Files.exists(tree.resolve(".package-baseline/drivers/UA/provisioning/objects/DirectoryModel/EntityDefs/user.xml")));
        Result bad = run(new AppConfigOps.Set(null, "user", "XmlData", "<not xml", null, false));
        assertTrue(bad.refusal, bad.refusal.contains("well-formed"));
    }

    @Test
    public void addAndRemoveWithGuards() throws Exception {
        Result missing = run(new AppConfigOps.Add(null, "Nowhere/Thing", "nrfNavItem", null, null));
        assertTrue(missing.refusal, missing.refusal.contains("does not exist"));
        Result runtime = run(new AppConfigOps.Add(null, "RoleConfig/Requests/r1", "nrfRequest", null, null));
        assertTrue(runtime.refusal, runtime.refusal.contains("runtime"));
        Result ok = run(new AppConfigOps.Add(null, "UIConfig/NavItems/Tool", "nrfNavItem", null,
            "nrfNavItemId=Tool\nnrfNavItemType=DASHBOARD\nnrfLocalizedNames=en~Tool"));
        assertTrue(ok.text(), ok.ok());
        AppObject tool = prov().object("UIConfig/NavItems/Tool");
        assertEquals(List.of("Top", "nrfNavItem"), tool.classes);
        assertEquals("DASHBOARD", tool.first("nrfNavItemType"));
        assertTrue(run(new AppConfigOps.Add(null, "UIConfig/NavItems/Tool", "nrfNavItem", null, null)).refusal.contains("already exists"));
        // a container that holds objects refuses; the runtime container always refuses
        assertTrue(run(new AppConfigOps.Remove(null, "UIConfig/NavItems")).refusal.contains("still holds"));
        assertTrue(run(new AppConfigOps.Remove(null, "RoleConfig/Requests")).refusal.contains("runtime"));
        Result rm = run(new AppConfigOps.Remove(null, "UIConfig/NavItems/Tool"));
        assertTrue(rm.text(), rm.ok());
        assertNull(prov().object("UIConfig/NavItems/Tool"));
    }

    @Test
    public void removeRefusesWhileReferenced_andPackagedWithoutForce() throws Exception {
        // the role configuration names the role container: the container cannot go while it does
        Result cfg = run(new AppConfigOps.Add(null, "RoleConfig/configuration", "nrfConfiguration", null,
            "nrfRolesContainer=cn=RoleDefs,cn=RoleConfig,cn=AppConfig,cn=UA,cn=driverset1,o=system"));
        assertTrue(cfg.text(), cfg.ok());
        // packaged: refused without --force
        Result pk = run(new AppConfigOps.Remove(null, "DirectoryModel/EntityDefs/user"));
        assertTrue(pk.refusal, pk.refusal.contains("installed by package"));
        assertTrue(force(new AppConfigOps.Remove(null, "DirectoryModel/EntityDefs/user")).ok());
        Result ref = run(new AppConfigOps.Remove(null, "RoleConfig/RoleDefs"));
        assertTrue(ref.refusal, ref.refusal.contains("still holds") || ref.refusal.contains("referenced by"));
    }

    @Test
    public void roleLifecycle() throws Exception {
        Result bad = run(new AppConfigOps.RoleAdd(null, "auditor", "40", "Custom", null, null, null, null, null));
        assertTrue(bad.refusal, bad.refusal.contains("--level"));
        Result r = run(new AppConfigOps.RoleAdd(null, "auditor", "20", "Custom", "Auditor\nde=Prüfer", "Audits things", "cn=boss,o=data", null, "2"));
        assertTrue(r.text(), r.ok());
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("created container RoleConfig/RoleDefs/Level20/Custom")));
        Provisioning p = prov();
        AppObject cat = p.object("RoleConfig/RoleDefs/Level20/Custom");
        assertEquals("nrfRoleDefs", cat.structuralClass());
        AppObject role = p.object("RoleConfig/RoleDefs/Level20/Custom/auditor");
        assertEquals(AppObject.Kind.ROLE, role.kind());
        assertEquals("20", role.first("nrfRoleLevel"));
        assertEquals("50", role.first("nrfStatus"));
        assertEquals("en~Auditor|de~Prüfer", role.first("nrfLocalizedNames"));
        assertEquals("en~Audits things", role.first("nrfLocalizedDescrs"));
        assertEquals(List.of("custom"), role.all("nrfRoleCategoryKey"));
        assertEquals("cn=boss,o=data", role.first("owner"));
        assertEquals("2", role.first("nrfQuorum"));
        assertTrue(run(new AppConfigOps.RoleAdd(null, "auditor", "20", "Other", null, null, null, null, null)).refusal.contains("already exists"));

        Result s = run(new AppConfigOps.RoleSet(null, "auditor", "fr=Auditeur", null, "custom,audit", "-", null, null, "10"));
        assertTrue(s.text(), s.ok());
        role = prov().object("RoleConfig/RoleDefs/Level20/Custom/auditor");
        assertEquals("en~Auditor|de~Prüfer|fr~Auditeur", role.first("nrfLocalizedNames"));
        assertEquals(List.of("audit", "custom"), role.all("nrfRoleCategoryKey"));
        assertNull("'-' clears", role.first("owner"));
        assertEquals("10", role.first("nrfStatus"));
        assertTrue(run(new AppConfigOps.RoleSet(null, "auditor", null, null, null, null, null, null, null)).refusal.contains("nothing to change"));

        assertTrue(run(new AppConfigOps.RoleRemove(null, "nobody")).refusal.contains("no role"));
        Result rm = run(new AppConfigOps.RoleRemove(null, "auditor"));
        assertTrue(rm.text(), rm.ok());
        assertNull(prov().object("RoleConfig/RoleDefs/Level20/Custom/auditor"));
        assertNotNull("the category container stays until removed explicitly", prov().object("RoleConfig/RoleDefs/Level20/Custom"));
    }

    @Test
    public void resourceLifecycle() throws Exception {
        Result none = run(new AppConfigOps.ResourceAdd(null, "r1", "Custom", null, null, null, null, false, null, null));
        assertTrue(none.refusal, none.refusal.contains("ResourceDefs"));
        assertTrue(run(new AppConfigOps.Add(null, "RoleConfig/ResourceDefs", "nrfResourceDefs", null, null)).ok());
        Result param = run(new AppConfigOps.ResourceAdd(null, "r1", "Custom", null, null, null, "p", false, null, null));
        assertTrue(param.refusal, param.refusal.contains("--param needs --entitlement"));
        Result r = run(new AppConfigOps.ResourceAdd(null, "r1", "Custom", "Resource One", null,
            "cn=Groups,cn=LB,cn=driverset1,o=system", "\\T\\G1", true, null, null));
        assertTrue(r.text(), r.ok());
        AppObject res = prov().object("RoleConfig/ResourceDefs/Custom/r1");
        assertEquals(AppObject.Kind.RESOURCE, res.kind());
        assertEquals("TRUE", res.first("nrfAllowMulti"));
        assertEquals("FALSE", res.first("nrfActive"));
        assertEquals("cn=Groups,cn=LB,cn=driverset1,o=system#0#<ref><src>UA</src><id/><param>\\T\\G1</param></ref>", res.first("nrfEntitlementRef"));
        assertEquals("nrfResourceDefs", prov().object("RoleConfig/ResourceDefs/Custom").structuralClass());
        Result s = run(new AppConfigOps.ResourceSet(null, "r1", null, "en=Grants G1", null, "-", null, null, true, null, null));
        assertTrue(s.text(), s.ok());
        res = prov().object("RoleConfig/ResourceDefs/Custom/r1");
        assertNull(res.first("nrfEntitlementRef"));
        assertEquals("TRUE", res.first("nrfActive"));
        assertEquals("en~Grants G1", res.first("nrfLocalizedDescrs"));
        assertTrue(run(new AppConfigOps.ResourceRemove(null, "r1")).ok());
        assertNull(prov().object("RoleConfig/ResourceDefs/Custom/r1"));
    }

    @Test
    public void entityLifecycleAndAttributes() throws Exception {
        Result e = run(new AppConfigOps.EntityAdd(null, "device", "device", "extensibleObject", "Device\nde=Gerät", "%device-root%", "cn",
            Map.of("removable", "false")));
        assertTrue(e.text(), e.ok());
        AppObject dev = prov().object("DirectoryModel/EntityDefs/device");
        assertEquals("P", dev.first("srvprvEntityType"));
        String xml = dev.first("XmlData");
        assertTrue(xml, xml.contains("<entity auto-query=\"false\" creatable=\"true\" editable=\"true\" protected=\"false\" removable=\"false\""));
        assertTrue(xml, xml.contains("<object-class auxiliary=\"false\" search=\"true\">device</object-class>")
            && xml.contains("<object-class add-always=\"true\" auxiliary=\"true\" search=\"false\">extensibleObject</object-class>"));
        assertTrue(xml, xml.contains("<label>Gerät</label>") && xml.contains("<search-root>%device-root%</search-root>"));
        assertTrue(run(new AppConfigOps.EntityAdd(null, "device", "x", null, null, null, null, Map.of())).refusal.contains("already exists"));

        Result a = run(new AppConfigOps.EntityAttrAdd(null, "device", "serial", "serialNumber", null, null, "Serial\nde=Seriennummer",
            Map.of("required", "true", "multivalue", "false")));
        assertTrue(a.text(), a.ok());
        xml = prov().object("DirectoryModel/EntityDefs/device").first("XmlData");
        assertTrue(xml, xml.contains("<key>serial</key>") && xml.contains("<ldap-name>serialNumber</ldap-name>")
            && xml.contains("<nds-name>serialNumber</nds-name>") && xml.contains("<type>String</type>"));
        assertTrue(xml, xml.contains("required=\"true\"") && xml.contains("<label>Seriennummer</label>"));
        assertTrue(run(new AppConfigOps.EntityAttrAdd(null, "device", "serial", "x", null, null, null, Map.of())).refusal.contains("already has"));
        assertTrue(run(new AppConfigOps.EntityAttrAdd(null, "device", "z", "z", null, null, null, Map.of("bogus", "true"))).refusal.contains("unknown attribute flag"));

        Result s = run(new AppConfigOps.EntityAttrSet(null, "device", "serial", null, "Integer", "fr=Numéro", Map.of("editable", "false")));
        assertTrue(s.text(), s.ok());
        xml = prov().object("DirectoryModel/EntityDefs/device").first("XmlData");
        assertTrue(xml, xml.contains("<type>Integer</type>") && xml.contains("editable=\"false\"") && xml.contains("<label>Numéro</label>") && xml.contains("<label>Serial</label>"));
        assertTrue(run(new AppConfigOps.EntityAttrSet(null, "device", "serial", null, null, null, Map.of())).refusal.contains("nothing to change"));

        Result es = run(new AppConfigOps.EntitySet(null, "device", "fr=Appareil", "ou=devices,o=data", Map.of("viewable", "false")));
        assertTrue(es.text(), es.ok());
        xml = prov().object("DirectoryModel/EntityDefs/device").first("XmlData");
        assertTrue(xml, xml.contains("viewable=\"false\"") && xml.contains("<search-root>ou=devices,o=data</search-root>") && xml.contains("<label>Appareil</label>"));

        assertTrue(run(new AppConfigOps.EntityAttrRemove(null, "device", "serial")).ok());
        assertFalse(prov().object("DirectoryModel/EntityDefs/device").first("XmlData").contains("<key>serial</key>"));
        assertTrue(run(new AppConfigOps.EntityAttrRemove(null, "device", "serial")).refusal.contains("has no attribute"));
        assertTrue(run(new AppConfigOps.EntityRemove(null, "device")).ok());
        assertNull(prov().object("DirectoryModel/EntityDefs/device"));
        // a packaged entity's attribute edit: baseline + customized mark
        Result pk = run(new AppConfigOps.EntityAttrAdd(null, "user", "room", "roomNumber", null, null, null, Map.of()));
        assertTrue(pk.text(), pk.ok());
        assertEquals("true", prov().object("DirectoryModel/EntityDefs/user").meta.get(Packages.CUSTOMIZED_KEY));
        assertTrue(pk.customized.toString(), pk.customized.contains("drivers/UA/provisioning/objects/DirectoryModel/EntityDefs/user"));
    }

    @Test
    public void everyOperationIsRegistered() {
        for (String n : List.of("appconfig.set", "appconfig.add", "appconfig.remove", "role.add", "role.set", "role.remove",
            "resource.add", "resource.set", "resource.remove", "entity.add", "entity.set", "entity.remove",
            "entity.attr.add", "entity.attr.set", "entity.attr.remove")) {
            assertNotNull(n, Registry.get(n));
        }
        assertTrue(Registry.usage(Registry.get("role.add")).contains("--level"));
    }
}
