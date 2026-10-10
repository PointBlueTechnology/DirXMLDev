package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.EntitlementPolicy;
import com.pointblue.dirxml.dev.model.NotificationTemplate;

/**
 * Entitlement policies and notification templates read from a Designer project, in the shapes real projects
 * have (grounded 2026-10-09 on client and lab projects; the fixture below is synthetic): the driver set's
 * {@code Idm:RbeContainer} → {@code .RBEContainer_} with a {@code DirXML-SPPriority} structure →
 * {@code .RBEPolicy_} objects whose display document is a hex byte array with dot-form entitlement DNs; and the
 * driver set's back reference to its Identity Vault → {@code Idm:TemplateCollections} → {@code Idm:NotfTemplates}.
 */
public class RbeTemplatesProjectReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String cobject(String name, String type, String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<com.novell.designer.model:CObject xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\" name=\"" + name + "\""
            + (type == null ? "" : " type=\"" + type + "\"") + ">" + body + "</com.novell.designer.model:CObject>";
    }

    private static String cstring(String attrName, String value) {
        return "<attributes xsi:type=\"com.novell.designer.model:CString\" attrName=\"" + attrName + "\" value=\"" + value + "\"/>";
    }

    private static String structure(String attrName, String... values) {
        StringBuilder sb = new StringBuilder("<attributes xsi:type=\"com.novell.designer.model:CStructure\" attrName=\"" + attrName + "\">");
        for (String v : values) {
            sb.append("<attributes xsi:type=\"com.novell.designer.model:CString\" value=\"").append(v).append("\"/>");
        }
        return sb.append("</attributes>").toString();
    }

    private static String rel(String name, String type, String key) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + key + "\"/>";
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static String esc(String xml) {
        return xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String DISPLAY = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Drivers><Driver><name>AD</name><value>AD.driverset1.system</value>"
        + "<description></description><Entitlement><name>UserAccount</name><value>UserAccount.AD.driverset1.system</value><description></description>"
        + "<type>NoValues</type><EntitlementMulti>false</EntitlementMulti><EntitlementPackedValues>PP</EntitlementPackedValues>"
        + "<EntitlementDN>UserAccount.AD.driverset1.system</EntitlementDN><hasInterpretiveVariables>false</hasInterpretiveVariables>"
        + "<conflict-resolution>priority</conflict-resolution></Entitlement></Driver></Drivers>";
    private static final String CRITERIA = "<selection-criterion><group><group-op>and</group-op><row><attribute>Title</attribute><operation>AS.Op.isEqual</operation><value>Clerk</value><row-op>and</row-op></row></group></selection-criterion>";

    private Path project() throws IOException {
        Path root = tmp.newFolder("project").toPath();
        Path m = root.resolve("Model/EdirOrphan");
        write(m.resolve("VAULT1.IdentityVault_"), cobject("TREE", "IdentityVault",
            rel("Idm:DriverSets", "Child", "DS1.DriverSet_") + rel("Idm:TemplateCollections", "ContainedReference", "COLL1.NotfTemplateCollection_")));
        write(m.resolve("DS1.DriverSet_"), cobject("driverset1", "DriverSet",
            cstring("DSetContext", "o=system") + rel("Idm:Drivers", "Reference", "DRV1.Driver_") + rel("Idm:RbeContainer", "Child", "RBE1.RBEContainer_")
            + rel("Idm:IdentityVaults", "BackReference", "VAULT1.IdentityVault_")));
        write(m.resolve("DRV1.Driver_"), cobject("AD", "Active Directory", cstring("DirXML-JavaModule", "com.novell.nds.dirxml.remote.driver.DriverShimImpl")));
        write(m.resolve("RBE1.RBEContainer_"), cobject("Entitlement Policies", "RBEContainer",
            structure("DirXML-SPPriority", "Clerks#0#0", "Everyone#1#0") + rel("Idm:RbePolicies", "Child", "POL1.RBEPolicy_") + rel("Idm:RbePolicies", "Child", "POL2.RBEPolicy_")));
        write(m.resolve("POL1.RBEPolicy_"), cobject("Clerks", "RBEPolicy",
            cstring("Description", "clerks get an account")
            + "<attributes xsi:type=\"com.novell.designer.model:CByteArray\" attrName=\"DirXML-SPDisplayEntitlements\" value=\"" + hex(DISPLAY) + "\"/>"
            + cstring("DirXML-SPFilterXML", esc(CRITERIA))
            + "<attributes xsi:type=\"com.novell.designer.model:CStructure\" attrName=\"excludedMember\"/>"
            + cstring("dgIdentity", "admin.sa.system")
            + structure("Member", "\\TREE\\data\\users\\a", "\\TREE\\data\\users\\b")
            + cstring("memberQuery", "ldap:///O=data??sub?(Title=Clerk)?x-sparse")));
        write(m.resolve("POL2.RBEPolicy_"), cobject("Everyone", "RBEPolicy",
            "<attributes xsi:type=\"com.novell.designer.model:CByteArray\" attrName=\"DirXML-SPDisplayEntitlements\" value=\"" + hex("<Drivers><Driver><name>AD</name><value>AD.driverset1.system</value><description></description></Driver></Drivers>") + "\"/>"
            + cstring("dgIdentity", "admin.sa.system")));
        write(m.resolve("COLL1.NotfTemplateCollection_"), cobject("Default Notification Collection", null,
            rel("Idm:IdentityVaults", "BackReference", "VAULT1.IdentityVault_") + rel("Idm:NotfTemplates", "Child", "TPL1.NotfTemplate_")));
        write(m.resolve("TPL1.NotfTemplate_"), cobject("Welcome", null,
            "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"contents\"/>" + cstring("notfMergeTemplateSubject", "Welcome $UserFullName$")
            + cstring("Idm:PackageAssocGuid", "{A1}") + cstring("Idm:ContentChecksum", "123")));
        write(m.resolve("TPL1_contents.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body>Dear $UserFullName$</body></html>");
        // a template file in the project's package cache, not in the vault's collection: not read
        write(root.resolve("Model/Project/packages/x/y/z/STRAY.NotfTemplate_"), cobject("Stray", null, cstring("notfMergeTemplateSubject", "stray")));
        return root;
    }

    @Test
    public void policiesComeFromTheContainerWithPriorityMembersCriteriaAndLdapRefs() throws Exception {
        DriverSet ds = ProjectReader.read(project());
        assertEquals(2, ds.rbePolicies.size());
        EntitlementPolicy clerks = ds.rbePolicy("Clerks");
        assertEquals(Integer.valueOf(0), clerks.priority);
        assertEquals(Integer.valueOf(1), ds.rbePolicy("Everyone").priority);
        assertEquals("clerks get an account", clerks.description);
        assertEquals("ldap:///O=data??sub?(Title=Clerk)?x-sparse", clerks.memberQuery);
        assertEquals(List.of("\\TREE\\data\\users\\a", "\\TREE\\data\\users\\b"), clerks.members);
        assertTrue(clerks.excludedMembers.isEmpty());
        assertNotNull(clerks.criteria);
        assertNotNull(clerks.displayEntitlements);
        assertEquals("Drivers", clerks.displayEntitlements.getNodeName());
        // the display document's dot-form DN becomes the vault's LDAP DN for the ref the shim reads
        assertEquals(List.of("cn=UserAccount,cn=AD," + ds.dn + "#0#<ref/>"), clerks.entitlementRefs);
        assertTrue(ds.rbePolicy("Everyone").entitlementRefs.isEmpty());
    }

    @Test
    public void templatesComeFromTheVaultsCollectionOnly() throws Exception {
        DriverSet ds = ProjectReader.read(project());
        assertEquals(1, ds.templates.size());
        NotificationTemplate t = ds.templates.get(0);
        assertEquals("Welcome", t.name);
        assertEquals("Welcome $UserFullName$", t.subject);
        assertNotNull(t.data);
        assertEquals("html", t.data.getNodeName());
        assertEquals(NotificationTemplate.DEFAULT_COLLECTION_DN, ds.templatesCollectionDn());
        assertTrue(t.meta.toString(), t.meta.containsValue("{A1}"));
    }

    @Test
    public void dotFormRefsOutsideTheSetAreLeftAlone() {
        assertEquals("cn=E,cn=D,cn=driverset1,o=system#0#<ref><param>v</param></ref>",
            EntitlementPolicy.ldapRef("E.D.driverset1.system#0#<ref><param>v</param></ref>", "driverset1", "cn=driverset1,o=system"));
        assertEquals("E.D.otherset.system#0#<ref/>", EntitlementPolicy.ldapRef("E.D.otherset.system#0#<ref/>", "driverset1", "cn=driverset1,o=system"));
        assertEquals("cn=E,cn=D,o=x#0#<ref/>", EntitlementPolicy.ldapRef("cn=E,cn=D,o=x#0#<ref/>", "driverset1", "cn=driverset1,o=system"));
        assertNull(EntitlementPolicy.ldapRef(null, "a", "b"));
        assertEquals("<?xml", ProjectReader.hexOrText("3C3F786D6C"));
        assertEquals("3C3", ProjectReader.hexOrText("3C3"));
    }
}
