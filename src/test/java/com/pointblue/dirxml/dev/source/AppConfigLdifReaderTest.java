package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** The rest of AppConfig — entities, roles, nav items, containers — read from LDIF/live entries. */
public class AppConfigLdifReaderTest {

    private static final String DRV = "cn=UA,cn=driverset1,o=system";
    private static final String APPCFG = "cn=AppConfig," + DRV;

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", new java.util.ArrayList<>(List.of("Top", oc)));
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i].equalsIgnoreCase("objectClass")) {
                attrs.get("objectclass").add(kv[i + 1]);
                continue;
            }
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    static final String ENTITY_XML = "<entity-definition>\r\n<entity auto-query=\"false\"><key>user</key></entity>"
        + "<attributes><attribute key=\"cn\"/></attributes></entity-definition>";

    public static List<Entry> entries() {
        return List.of(
            entry("cn=driverset1,o=system", "DirXML-DriverSet"),
            entry(DRV, "DirXML-Driver", "DirXML-JavaModule", "com.novell.idm.driver.ComposerDriverShim"),
            entry(APPCFG, "srvprvAppConfig", "Version", "4.8"),
            entry("cn=DirectoryModel," + APPCFG, "srvprvDirectoryModel", "srvprvModified", "20240101"),
            entry("cn=EntityDefs,cn=DirectoryModel," + APPCFG, "srvprvEntityDefs"),
            entry("cn=user,cn=EntityDefs,cn=DirectoryModel," + APPCFG, "srvprvEntity",
                "objectClass", "DirXML-PkgItemAux", "XmlData", ENTITY_XML, "srvprvEntityType", "P", "description", "User",
                "DirXML-pkgGUID", "PKGID;com.x.y;1.0.0", "DirXML-pkgAssociationId", "ASSOC1", "DirXML-pkgChecksum", "123"),
            entry("cn=RoleConfig," + APPCFG, "nrfConfig", "Version", "2.0"),
            entry("cn=RoleDefs,cn=RoleConfig," + APPCFG, "nrfRoleDefs"),
            entry("cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG, "nrfRoleDefs"),
            entry("cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG, "nrfRoleDefs"),
            entry("cn=provManager,cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG, "nrfRole",
                "nrfRoleLevel", "20", "nrfStatus", "50", "nrfRoleCategoryKey", "system",
                "nrfLocalizedNames", "de~Bereitstellungsmanager|en~Provisioning Manager",
                "equivalentToMe", "cn=uaadmin,ou=sa,o=data", "DirXML-Associations", "cn=DCS,cn=driverset1,o=system#1#X",
                "ACL", "4#entry#cn=roleAdmin,cn=System,cn=Level20,cn=RoleDefs,cn=RoleConfig," + APPCFG + "#nrfAccessViewRole"),
            entry("cn=Requests,cn=RoleConfig," + APPCFG, "nrfRequests"),
            entry("cn=20240101-1,cn=Requests,cn=RoleConfig," + APPCFG, "nrfRequest", "nrfStatus", "50"),
            entry("cn=ResourceAssociations,cn=RoleConfig," + APPCFG, "nrfResourceAssociations"),
            entry("cn=x,cn=ResourceAssociations,cn=RoleConfig," + APPCFG, "nrfResourceAssociation"),
            entry("cn=NavItems,cn=UIConfig," + APPCFG, "nrfNavItems"),
            entry("cn=UIConfig," + APPCFG, "nrfUIConfig"),
            entry("cn=AccessRptTool,cn=NavItems,cn=UIConfig," + APPCFG, "nrfNavItem",
                "nrfNavItemId", "AccessRptTool", "nrfNavItemType", "DASHBOARD", "nrfLocalizedNames", "en~Access Report"),
            entry("cn=WorkflowForms," + APPCFG, "srvprvJSONForms"),
            entry("cn=RequestDefs," + APPCFG, "srvprvRequestDefs"));
    }

    @Test
    public void everyDesignObjectIsRead_containersIncluded_formsMachineryExcluded() {
        Provisioning p = LdifReader.fromEntries(entries(), "synthetic").driver("UA").provisioning;
        assertNotNull(p);
        assertEquals("4.8", p.meta.get("appconfig.Version"));   // the container's own attribute, on the provisioning meta
        List<String> paths = p.objects.stream().map(AppObject::path).toList();
        assertTrue(paths.contains("DirectoryModel"));
        assertTrue(paths.contains("DirectoryModel/EntityDefs/user"));
        assertTrue(paths.contains("RoleConfig/RoleDefs/Level20/System/provManager"));
        assertTrue(paths.contains("RoleConfig/Requests"));               // the container is design
        assertTrue(paths.contains("UIConfig/NavItems/AccessRptTool"));
        assertTrue(!paths.contains("WorkflowForms") && !paths.contains("RequestDefs"));   // owned by forms/PRDs
        assertEquals("sorted by path", paths.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), paths);
    }

    @Test
    public void runtimeRecordsAreSkippedAndCounted() {
        Provisioning p = LdifReader.fromEntries(entries(), "synthetic").driver("UA").provisioning;
        assertNull(p.object("RoleConfig/Requests/20240101-1"));
        assertNull(p.object("RoleConfig/ResourceAssociations/x"));
        assertEquals("2", p.meta.get("provisioning.runtime-objects"));
    }

    @Test
    public void attributesAreCanonicalXmlCanonicalizedStampsInMeta() {
        Provisioning p = LdifReader.fromEntries(entries(), "synthetic").driver("UA").provisioning;
        AppObject user = p.object("DirectoryModel/EntityDefs/user");
        assertEquals(AppObject.Kind.ENTITY, user.kind());
        assertEquals(List.of("DirXML-PkgItemAux", "srvprvEntity", "Top"), user.classes);
        assertEquals("P", user.first("srvprvEntityType"));
        String xml = user.first("XmlData");
        assertTrue(xml.startsWith("<?xml"));
        assertTrue("CRLF folded", !xml.contains("\r"));
        assertTrue(xml.contains("<entity-definition>"));
        assertNull("stamps are meta, not content", user.first("DirXML-pkgGUID"));
        assertEquals("PKGID;com.x.y;1.0.0", user.meta.get("dirxml-pkgguid"));
        assertEquals("ASSOC1", user.meta.get("dirxml-pkgassociationid"));
        assertEquals("cn=user,cn=EntityDefs,cn=DirectoryModel," + APPCFG, user.meta.get("dn"));
        assertEquals("srvprvEntity", user.meta.get("objectClass"));
        assertNull("cn is identity, not content", user.first("cn"));
    }

    @Test
    public void operationalAttributesAreKeptForTheRecord() {
        Provisioning p = LdifReader.fromEntries(entries(), "synthetic").driver("UA").provisioning;
        AppObject role = p.object("RoleConfig/RoleDefs/Level20/System/provManager");
        assertEquals("cn=uaadmin,ou=sa,o=data", role.first("equivalentToMe"));
        assertNotNull(role.first("DirXML-Associations"));
        assertNotNull(role.first("ACL"));
        assertEquals("Provisioning Manager", role.displayName());
    }
}
