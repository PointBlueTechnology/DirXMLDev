package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** LDIF/live entries under {@code cn=AppConfig,<driver dn>} -> {@link Provisioning}. */
public class ProvisioningLdifReaderTest {

    private static final String DS = "cn=driverset1,o=system";
    private static final String DRV = "cn=UA,cn=driverset1,o=system";
    private static final String APPCFG = "cn=AppConfig," + DRV;

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", List.of("Top", oc));
        for (int i = 0; i < kv.length; i += 2) {
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    private static final String FORM_JSON = "{\"title\":\"T\",\"components\":[]}";

    private static final String PROCESS_XML =
        "<process id=\"p1\" process-type=\"Normal\">"
        + "<activities><user-activity id=\"Activity\">"
        + "<form-binding activity-id=\"Activity\" form-id=\"My Approval Form\"><content/></form-binding>"
        + "</user-activity></activities></process>";

    private static final String XML_DATA =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><prov-req-defn status=\"Active\">"
        + "<display-name xml:lang=\"en\">My PRD</display-name>" + PROCESS_XML + "</prov-req-defn>";

    private static final String REQUEST_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><provision-request formSrc=\"1\">"
        + "<form-binding form-id=\"My Request Form\"><content>"
        + "<field data-type=\"string\" name=\"a\"><control control-type=\"textfield\"/></field>"
        + "</content></form-binding></provision-request><!-- trailing comment -->";

    private static List<Entry> sampleEntries() {
        return List.of(
            entry(DS, "DirXML-DriverSet"),
            entry(DRV, "DirXML-Driver", "DirXML-JavaModule", "com.novell.idm.driver.ComposerDriverShim"),
            entry(APPCFG, "srvprvAppConfig"),
            entry("cn=WorkflowForms," + APPCFG, "srvprvJSONForms"),
            entry("cn=WorkflowRequestForms,cn=WorkflowForms," + APPCFG, "srvprvJSONForms"),
            entry("cn=WorkflowApprovalForms,cn=WorkflowForms," + APPCFG, "srvprvJSONForms"),
            entry("cn=My Request Form,cn=WorkflowRequestForms,cn=WorkflowForms," + APPCFG, "srvprvJSONForm",
                "srvprvJSONData", FORM_JSON, "DirXML-pkgGUID", "PKG-FORM-1"),
            entry("cn=My Approval Form,cn=WorkflowApprovalForms,cn=WorkflowForms," + APPCFG, "srvprvJSONForm",
                "srvprvJSONData", FORM_JSON),
            entry("cn=Stray Form,cn=AppConfig," + DRV, "srvprvJSONForm", "srvprvJSONData", FORM_JSON),
            entry("cn=RequestDefs," + APPCFG, "srvprvRequestDefs"),
            entry("cn=MyPrd,cn=RequestDefs," + APPCFG, "srvprvRequest",
                "XmlData", XML_DATA,
                "srvprvRequestXML", REQUEST_XML,
                "srvprvStatus", "Active",
                "srvprvFlowStrategy", "SingleFlow",
                "srvprvGrant", "TRUE",
                "srvprvRevoke", "FALSE",
                "srvprvCategoryKey", "accounts",
                "srvprvLocalizedNames", "en~My PRD|fr~Mon PRD",
                "srvprvLocalizedDescrs", "en~A PRD",
                "description", "A PRD"),
            entry("cn=SomeOtherThing," + APPCFG, "srvprvChoiceDefs"));
    }

    @Test
    public void driverGetsProvisioningOnlyWhenAppConfigPresent() {
        DriverSet ds = LdifReader.fromEntries(sampleEntries(), "synthetic");
        Driver ua = ds.driver("UA");
        assertNotNull(ua);
        assertNotNull(ua.provisioning);
        assertEquals(APPCFG, ua.provisioning.dn);
    }

    @Test
    public void formsArePlacedByContainerKind() {
        Provisioning p = LdifReader.fromEntries(sampleEntries(), "synthetic").driver("UA").provisioning;
        assertEquals(2, p.forms.size());
        Form req = p.form(Form.Kind.REQUEST, "My Request Form");
        assertNotNull(req);
        assertEquals(FORM_JSON, req.json);
        assertEquals("PKG-FORM-1", req.meta.get("dirxml-pkgguid"));
        assertNotNull(p.form(Form.Kind.APPROVAL, "My Approval Form"));
    }

    @Test
    public void formOutsideWorkflowFormsIsRecordedAsUnplacedNotSkippedSilently() {
        Provisioning p = LdifReader.fromEntries(sampleEntries(), "synthetic").driver("UA").provisioning;
        assertNull(p.formByName("Stray Form"));
        boolean foundUnplaced = false;
        for (Map.Entry<String, String> m : p.meta.entrySet()) {
            if (m.getKey().startsWith("provisioning.unplaced-form.") && m.getValue().contains("Stray Form")) {
                foundUnplaced = true;
            }
        }
        assertTrue(foundUnplaced);
    }

    @Test
    public void otherAppConfigObjectsBecomeAppObjects() {
        Provisioning p = LdifReader.fromEntries(sampleEntries(), "synthetic").driver("UA").provisioning;
        assertNull(p.meta.get("provisioning.other-objects"));
        assertEquals(1, p.objects.size());
        assertEquals("SomeOtherThing", p.objects.get(0).path());
        assertEquals("srvprvChoiceDefs", p.objects.get(0).structuralClass());
        assertTrue(p.objects.get(0).isContainer());
    }

    @Test
    public void prdSplitsDefinitionRequestAndProcess() {
        Provisioning p = LdifReader.fromEntries(sampleEntries(), "synthetic").driver("UA").provisioning;
        Prd prd = p.prd("MyPrd");
        assertNotNull(prd);
        assertNotNull(prd.definition);
        assertEquals("prov-req-defn", prd.definition.getTagName());
        assertNotNull(prd.request);
        assertEquals("provision-request", prd.request.getTagName());
        assertNotNull(prd.process);
        assertEquals("process", prd.process.getTagName());
        // process stays a child of the definition (XmlData already carries it) rather
        // than being a second, independently-parsed copy of srvprvProcessXML.
        boolean sameNode = false;
        org.w3c.dom.NodeList kids = prd.definition.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) == prd.process) {
                sameNode = true;
            }
        }
        assertTrue(sameNode);
        assertTrue(prd.isJsonForms());
    }

    @Test
    public void prdPropertiesAreLowerCasedAndPrefixStripped() {
        Provisioning p = LdifReader.fromEntries(sampleEntries(), "synthetic").driver("UA").provisioning;
        Prd prd = p.prd("MyPrd");
        assertEquals("Active", prd.property("status"));
        assertEquals("SingleFlow", prd.property("flow-strategy"));
        assertEquals("TRUE", prd.property("grant"));
        assertEquals("FALSE", prd.property("revoke"));
        assertEquals("accounts", prd.property("category-key"));
        assertEquals("en~My PRD|fr~Mon PRD", prd.property("localized-names"));
        assertEquals("en~A PRD", prd.property("localized-descrs"));
        assertEquals("A PRD", prd.property("description"));
    }

    @Test
    public void prdBindingsCombineRequestAndApproval() {
        Provisioning p = LdifReader.fromEntries(sampleEntries(), "synthetic").driver("UA").provisioning;
        List<Prd.FormBinding> bindings = p.prd("MyPrd").bindings();
        assertEquals(2, bindings.size());
        assertEquals("My Request Form", bindings.get(0).formId);
        assertNull(bindings.get(0).activityId);
        assertEquals("My Approval Form", bindings.get(1).formId);
        assertEquals("Activity", bindings.get(1).activityId);
    }

    @Test
    public void driverWithoutAppConfigHasNullProvisioning() {
        List<Entry> entries = List.of(
            entry(DS, "DirXML-DriverSet"),
            entry("cn=Other,cn=driverset1,o=system", "DirXML-Driver"));
        DriverSet ds = LdifReader.fromEntries(entries, "synthetic");
        assertNull(ds.driver("Other").provisioning);
    }
}
