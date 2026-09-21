package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.util.List;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Designer's shapes for AppConfig objects, both ways (docs/appconfig.md §8). */
public class DesignerAppConfigTest {

    private static final List<String> ROLE_PATH = List.of("RoleConfig", "RoleDefs", "Level20", "Custom", "auditor");

    @Test
    public void roleXmiRoundTrip() {
        AppObject r = new AppObject(ROLE_PATH);
        r.classes.addAll(List.of("Top", "nrfRole"));
        r.put("nrfRoleLevel", List.of("20"));
        r.put("nrfStatus", List.of("50"));
        r.put("nrfRoleCategoryKey", List.of("custom", "system"));
        r.put("nrfLocalizedNames", List.of("de~Prüfer|en~Auditor"));
        r.put("nrfLocalizedDescrs", List.of("en~Audits <things> & \"more\""));
        r.put("owner", List.of("cn=boss,o=data"));
        r.put("ACL", List.of("4#entry#cn=Team A,cn=TeamDefs,cn=AppConfig,cn=UA,o=x#nrfAccessMgrAssignRole",
            "4#subtree#o=data#nrfAccessViewRole"));
        String xmi = DesignerAppConfig.roleToXmi(r);
        assertTrue(xmi, xmi.contains("<role:Role id=\"cn=auditor\" roleLevel=\"Level20\""));
        assertTrue(xmi, xmi.contains("<trustee dn=\"cn=Team A,cn=TeamDefs,cn=AppConfig,cn=UA,o=x#nrfAccessMgrAssignRole\"/>"));
        assertTrue("a subtree ACL has no XMI form", !xmi.contains("o=data#nrfAccessViewRole"));
        AppObject back = DesignerAppConfig.roleFromXmi(xmi, ROLE_PATH);
        assertEquals("20", back.first("nrfRoleLevel"));
        assertEquals("50", back.first("nrfStatus"));
        assertEquals(List.of("custom", "system"), back.all("nrfRoleCategoryKey"));
        assertEquals("de~Prüfer|en~Auditor", back.first("nrfLocalizedNames"));
        assertEquals("en~Audits <things> & \"more\"", back.first("nrfLocalizedDescrs"));
        assertEquals("cn=boss,o=data", back.first("owner"));
        assertEquals(List.of("4#entry#cn=Team A,cn=TeamDefs,cn=AppConfig,cn=UA,o=x#nrfAccessMgrAssignRole"), back.all("ACL"));
        assertEquals("nrfRoleLevel20", DesignerAppConfig.itemType(back));
    }

    @Test
    public void resourceXmiRoundTrip() {
        AppObject r = new AppObject(List.of("RoleConfig", "ResourceDefs", "System", "Helpdesk-Group"));
        r.classes.addAll(List.of("Top", "nrfResource"));
        r.put("nrfAllowMulti", List.of("TRUE"));
        r.put("nrfActive", List.of("FALSE"));
        r.put("nrfAllowAprOveride", List.of("FALSE"));
        r.put("nrfCategoryKey", List.of("helpdesk", "system"));
        r.put("nrfLocalizedNames", List.of("en~Group Access"));
        r.put("nrfResourceParms", List.of("<parameters><parameter binding=\"dynamic\" hide=\"false\" instance=\"false\" multivalue=\"false\" scope=\"request\">"
            + "<key>param1</key><display xml:lang=\"en\"><label>Client</label></display><type>List</type><code-map-key>Helpdesk Entitlement</code-map-key></parameter></parameters>"));
        r.put("nrfEntitlementRef", List.of("cn=Groups,cn=LB,cn=ds,o=x#0#<ref><src>UA</src><id/><param>\\T\\APPS\\G1</param></ref>"));
        r.put("owner", List.of("cn=g1,o=apps"));
        String xmi = DesignerAppConfig.resourceToXmi(r);
        assertTrue(xmi, xmi.contains("allowMultipleAssignment=\"true\""));
        assertTrue(xmi, xmi.contains("<resourceParameter binding=\"dynamic\" codeMapKey=\"Helpdesk Entitlement\" key=\"param1\" type=\"List\"><localizedDisplay label=\"Client\" locale=\"en\"/></resourceParameter>"));
        assertTrue(xmi, xmi.contains("<entitlement dn=\"cn=Groups,cn=LB,cn=ds,o=x\" driver=\"\" parameter=\"\\T\\APPS\\G1\""));
        AppObject back = DesignerAppConfig.resourceFromXmi(xmi, r.segments);
        assertEquals("TRUE", back.first("nrfAllowMulti"));
        assertEquals("FALSE", back.first("nrfActive"));
        assertEquals("FALSE", back.first("nrfAllowAprOveride"));
        assertEquals(List.of("helpdesk", "system"), back.all("nrfCategoryKey"));
        assertEquals(CanonicalXml.canonicalize(r.first("nrfResourceParms")), back.first("nrfResourceParms"));
        assertEquals("cn=Groups,cn=LB,cn=ds,o=x#0#<ref><src>UA</src><id/><param>\\T\\APPS\\G1</param></ref>",
            back.first("nrfEntitlementRef").replaceAll(">\\s+<", "><"));
        assertEquals("cn=g1,o=apps", back.first("owner"));
    }

    @Test
    public void roleConfigRoundTrip() {
        AppObject c = new AppObject(List.of("RoleConfig", "configuration"));
        c.classes.addAll(List.of("Top", "nrfConfiguration"));
        c.put("nrfRolesContainer", List.of("cn=RoleDefs,cn=RoleConfig,cn=AppConfig,cn=UA,o=x"));
        c.put("nrfRemovalGracePeriod", List.of("0"));
        c.put("nrfRoleLevels", List.of("cn=Level10,cn=RoleDefs,cn=RoleConfig,cn=AppConfig,cn=UA,o=x#10#<xml>\n<display-name xml:lang=\"en\">Permission Role</display-name>\n<description xml:lang=\"en\">Lowest</description>\n</xml>\n"));
        c.put("nrfEntitlementConfigDefault", List.of("<xml xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"roles.xsd\"><query-config><refresh-rate>1440</refresh-rate><nds-timeout>10</nds-timeout>"
            + "<codemap-display concat=\"false\"><result-element>description</result-element><result-element>ent-value</result-element></codemap-display></query-config></xml>"));
        String xml = DesignerAppConfig.roleConfigToXml(c);
        assertTrue(xml, xml.contains("<configuration:nrfRoleLevels><configuration:containerDN>cn=Level10,"));
        assertTrue(xml, xml.contains("<configuration:description xml:lang=\"en\">Lowest</configuration:description>"));
        assertTrue(xml, xml.contains("<configuration:query-timeout>10</configuration:query-timeout>"));
        AppObject back = DesignerAppConfig.roleConfigFromXml(xml, c.segments);
        assertEquals(c.first("nrfRolesContainer"), back.first("nrfRolesContainer"));
        assertEquals("0", back.first("nrfRemovalGracePeriod"));
        assertEquals(c.first("nrfRoleLevels"), back.first("nrfRoleLevels"));
        assertEquals(CanonicalXml.canonicalize(c.first("nrfEntitlementConfigDefault")), back.first("nrfEntitlementConfigDefault"));
    }

    @Test
    public void inlineObjectsDecodeBase64AndWriteBack() {
        String appconfig = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><provisioning><ds-object ds-object-class=\"srvprvAppConfig\" ds-object-name=\"AppConfig\"><ds-attributes/>"
            + "<ds-object ds-object-class=\"srvprvDirectoryModel\" ds-object-name=\"DirectoryModel\"><ds-attributes/>"
            + "<ds-object ds-object-class=\"srvprvEntityDefs\" ds-object-name=\"EntityDefs\"><ds-attributes/>"
            + "<ds-object ds-object-class=\"srvprvEntity\" ds-object-name=\"sys-x\"><ds-attributes>"
            + "<ds-attribute ds-attr-name=\"Description\"><ds-value>System X</ds-value></ds-attribute>"
            + "<ds-attribute ds-attr-name=\"srvprvEntityType\"><ds-value>S</ds-value></ds-attribute>"
            + "<ds-attribute ds-attr-name=\"XmlData\"><ds-value>" + java.util.Base64.getEncoder().encodeToString("\r\n<entity-definition><entity/></entity-definition>".getBytes()) + "</ds-value></ds-attribute>"
            + "</ds-attributes></ds-object></ds-object></ds-object>"
            + "<ds-object ds-object-class=\"srvprvRequestDefs\" ds-object-name=\"RequestDefs\"><ds-attributes/>"
            + "<ds-object ds-object-class=\"srvprvRequest\" ds-object-name=\"P\"><ds-attributes/></ds-object></ds-object>"
            + "</ds-object></provisioning>";
        Document doc = CanonicalXml.parse(appconfig);
        List<AppObject> objects = DesignerAppConfig.readInline(doc);
        List<String> paths = objects.stream().map(AppObject::path).toList();
        assertEquals(List.of("DirectoryModel", "DirectoryModel/EntityDefs", "DirectoryModel/EntityDefs/sys-x"), paths);
        AppObject e = objects.get(2);
        assertEquals("System X", e.first("description"));
        assertEquals("S", e.first("srvprvEntityType"));
        assertTrue(e.first("XmlData"), e.first("XmlData").startsWith("<?xml") && e.first("XmlData").contains("<entity-definition>"));
        assertNull("the storage decides: an 'S' entity stays inline", DesignerAppConfig.fileExtension(e));
        assertEquals("ACL", e.meta.get(AppConfigPolicy.ABSENT_ATTRS_META));
        assertEquals("inline", e.meta.get("project.storage"));

        // write a nav item inline under a container the document does not have yet
        AppObject nav = AppObject.ofPath("UIConfig/NavItems/Tool");
        nav.classes.addAll(List.of("Top", "nrfNavItem"));
        nav.put("nrfNavItemId", List.of("Tool"));
        nav.put("nrfLocalizedNames", List.of("en~Tool"));
        AppObject ui = AppObject.ofPath("UIConfig");
        ui.classes.addAll(List.of("Top", "nrfUIConfig"));
        AppObject items = AppObject.ofPath("UIConfig/NavItems");
        items.classes.addAll(List.of("Top", "nrfNavItems"));
        Element el = DesignerAppConfig.inlineElement(doc, nav.segments, true, List.of(ui, items, nav));
        DesignerAppConfig.writeInline(doc, el, nav);
        List<AppObject> again = DesignerAppConfig.readInline(doc);
        AppObject navBack = again.stream().filter(o -> o.path().equals("UIConfig/NavItems/Tool")).findFirst().orElseThrow();
        assertEquals("Tool", navBack.first("nrfNavItemId"));
        assertEquals("nrfNavItems", again.stream().filter(o -> o.path().equals("UIConfig/NavItems")).findFirst().orElseThrow().structuralClass());
        // an entity written inline goes back out as base64 XmlData
        AppObject sysY = AppObject.ofPath("DirectoryModel/EntityDefs/sys-y");
        sysY.classes.addAll(List.of("Top", "srvprvEntity"));
        sysY.put("XmlData", List.of("<entity-definition><entity/></entity-definition>"));
        DesignerAppConfig.writeInline(doc, DesignerAppConfig.inlineElement(doc, sysY.segments, true, List.of()), sysY);
        String out = CanonicalXml.serialize(doc);
        assertTrue(out, !out.contains("&lt;entity-definition") && out.contains("PD94bWwg"));
    }

    @Test
    public void fileItemsAndTypes() {
        AppObject entity = DesignerAppConfig.readItem("entity", "<entity-definition><entity><display xml:lang=\"en\"><label>User</label></display></entity></entity-definition>",
            "srvprvEntity", List.of("DirectoryModel", "EntityDefs", "user"), null);
        assertEquals("srvprvEntity", entity.structuralClass());
        assertEquals("P", entity.first("srvprvEntityType"));
        assertEquals("description,ACL", entity.meta.get(AppConfigPolicy.ABSENT_ATTRS_META));
        assertEquals("User", DesignerAppConfig.displayNames(entity).get("en"));
        assertEquals("entity", DesignerAppConfig.fileExtension(entity));
        AppObject cat = AppObject.ofPath("RoleConfig/RoleDefs/Level20/Custom");
        cat.classes.addAll(List.of("Top", "nrfRoleDefs"));
        assertEquals("nrfRoleDefsLevel20-Custom", DesignerAppConfig.containerType(cat));
        assertEquals("nrfRoleDefs", DesignerAppConfig.classOfContainerType("nrfRoleDefsLevel20-Custom"));
        assertEquals("nrfRole", DesignerAppConfig.classOfItemType("nrfRoleLevel30"));
        assertEquals("srvprvWebAppConfig", DesignerAppConfig.classOfItemType("srvprvLocales"));
        AppObject att = DesignerAppConfig.readItem("attestation",
            "<ds-object ds-object-class=\"nrfAttestation\" ds-object-name=\"A\"><ds-attributes><ds-attribute ds-attr-name=\"CN\"><ds-value>A</ds-value></ds-attribute>"
            + "<ds-attribute ds-attr-name=\"nrfAttestationDefault\"><ds-value>true</ds-value></ds-attribute></ds-attributes></ds-object>",
            "nrfAttestation", List.of("RoleConfig", "Attestations", "A"), null);
        assertNull("cn is identity", att.first("cn"));
        assertEquals("booleans as the vault spells them", "TRUE", att.first("nrfAttestationDefault"));
    }
}
