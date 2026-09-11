package com.pointblue.dirxml.dev.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.util.List;
import org.junit.Test;
import org.w3c.dom.Element;

public class BindingSyncTest {

    public static final String FORM_V1 = "{\"components\":[" +
        "{\"key\":\"title\",\"type\":\"title\",\"input\":false}," +
        "{\"key\":\"recipient\",\"type\":\"dn_display\",\"input\":true}," +
        "{\"key\":\"reason\",\"type\":\"textfield\",\"input\":true}," +
        "{\"key\":\"columns\",\"type\":\"columns\",\"columns\":[{\"components\":[" +
        "{\"key\":\"submit\",\"type\":\"button\",\"input\":true}]}]}" +
        "],\"title\":\"T\",\"display\":\"form\",\"inlinescripts\":\"\",\"localization\":{},\"externalScripts\":[]}";

    /** v2: reason renamed to justification, a multi-valued select added, an htmlelement added, recipient dropped. */
    public static final String FORM_V2 = "{\"components\":[" +
        "{\"key\":\"title\",\"type\":\"title\",\"input\":false}," +
        "{\"key\":\"justification\",\"type\":\"textarea\",\"input\":true}," +
        "{\"key\":\"groups\",\"type\":\"select\",\"multiple\":true,\"input\":true}," +
        "{\"key\":\"info\",\"type\":\"htmlelement\",\"input\":false}," +
        "{\"key\":\"apwaComment\",\"type\":\"textfield\"}," +
        "{\"key\":\"submit\",\"type\":\"button\",\"input\":true}" +
        "],\"title\":\"T\"}";

    public static final String REQUEST_XML =
        "<provision-request formSrc=\"1\" version=\"3.6.1\">" +
        "<form-binding form-id=\"Req\"><content>" +
        "<field data-type=\"string\" name=\"title\"><control control-type=\"title\"/></field>" +
        "<field data-type=\"dn\" name=\"recipient\"><control control-type=\"dn_display\"/></field>" +
        "<field data-type=\"string\" name=\"reason\"><control control-type=\"textfield\"/></field>" +
        "<field data-type=\"button\" name=\"submit\"><control control-type=\"button\"/></field>" +
        "</content></form-binding>" +
        "<request-data-items>" +
        "<data-item data-type=\"string\" name=\"title\" target=\"flowdata.Start/Req/title\" target-type=\"single-value\"/>" +
        "<data-item data-type=\"dn\" name=\"recipient\" source=\"recipient\" target=\"flowdata.Start/Req/recipient\" target-type=\"single-value\"/>" +
        "<data-item data-type=\"string\" name=\"reason\" target=\"flowdata.Start/Req/reason\" target-type=\"single-value\"/>" +
        "</request-data-items>" +
        "</provision-request>";

    public static final String PROCESS_XML =
        "<process formSrc=\"1\">" +
        "<form-binding activity-id=\"Activity\" form-id=\"Req\"/>" +
        "<data-items activity-id=\"Activity\">" +
        "<data-item data-type=\"string\" name=\"title\" source=\"flowdata.get('Start/Req/title')\" target-type=\"single-value\"/>" +
        "<data-item data-type=\"dn\" name=\"recipient\" source=\"flowdata.get('Start/Req/recipient')\" target-type=\"single-value\"/>" +
        "<data-item data-type=\"string\" name=\"reason\" source=\"flowdata.get('Start/Req/reason')\" target-type=\"single-value\"/>" +
        "</data-items>" +
        "<start-activity activity-id=\"Start\"/>" +
        "<user-activity activity-id=\"Activity\"/>" +
        "</process>";

    public static Element el(String xml) {
        return CanonicalXml.parse(xml).getDocumentElement();
    }

    private static Prd prd() {
        Prd p = new Prd("P");
        p.request = el(REQUEST_XML);
        p.process = el(PROCESS_XML);
        p.definition = el("<prov-req-defn/>");
        return p;
    }

    @Test
    public void itemsFollowDesignersParseJson() {
        List<BindingSync.Item> items = BindingSync.items(FORM_V2);
        assertEquals("[title:title, justification:textarea, groups:select[], info:htmlelement, apwaComment:textfield, submit:button]", items.toString());
        assertEquals("string", items.get(1).dataType());
        assertEquals("dn", BindingSync.items(FORM_V1).get(1).dataType());
        assertNull(items.get(5).dataType());   // button: not in FormDataConfig
        assertEquals(33, BindingSync.DATA_TYPES.size());   // FormDataConfig.json lists "day" twice
    }

    @Test
    public void unchangedFormChangesNothing() {
        Prd p = prd();
        Form f = new Form(Form.Kind.REQUEST, "Req", FORM_V1);
        String before = CanonicalXml.serialize(p.request) + CanonicalXml.serialize(p.process);
        List<BindingSync.Change> changes = BindingSync.sync(p, f);
        assertTrue(changes.toString(), changes.isEmpty());
        assertEquals(before, CanonicalXml.serialize(p.request) + CanonicalXml.serialize(p.process));
    }

    @Test
    public void formChangeRewritesFieldsAndPrunesMappings() {
        Prd p = prd();
        Form f = new Form(Form.Kind.REQUEST, "Req", FORM_V2);
        List<BindingSync.Change> changes = BindingSync.sync(p, f);
        String req = CanonicalXml.serialize(p.request);

        // request binding fields: title kept, justification/groups/info added, button kept, recipient/reason gone, apwaComment skipped
        List<Element> fields = BindingSync.children(BindingSync.firstChild(BindingSync.firstChild(p.request, "form-binding"), "content"), "field");
        assertEquals("[title, justification, groups, info, submit]", names(fields).toString());
        assertEquals("string", fields.get(2).getAttribute("data-type"));
        assertEquals("select", BindingSync.firstChild(fields.get(2), "control").getAttribute("control-type"));
        assertFalse(req.contains("apwaComment"));

        // request data items are persisted mappings: title kept untouched, recipient/reason dropped, nothing added
        List<Element> items = BindingSync.children(BindingSync.firstChild(p.request, "request-data-items"), "data-item");
        assertEquals("[title]", names(items).toString());
        assertEquals("flowdata.Start/Req/title", items.get(0).getAttribute("target"));

        // activity binding stays a bare reference; its data items are pruned the same way
        Element ab = BindingSync.children(p.process, "form-binding").get(0);
        assertNull(BindingSync.firstChild(ab, "content"));
        List<Element> aitems = BindingSync.children(BindingSync.children(p.process, "data-items").get(0), "data-item");
        assertEquals("[title]", names(aitems).toString());

        assertTrue(changes.toString(), changes.toString().contains("unbound field 'recipient'"));
        assertTrue(changes.toString(), changes.toString().contains("P request form: data item 'reason' removed"));
        assertTrue(changes.toString(), changes.toString().contains("P activity 'Activity': data item 'recipient' removed"));
    }

    @Test
    public void mappingsFollowMultipleAndDataTypeChanges() {
        Prd p = new Prd("Q");
        p.request = el("<provision-request formSrc=\"1\"><form-binding form-id=\"My Form\"><content>"
            + "<field data-type=\"string\" name=\"groups\"><control control-type=\"textfield\"/></field>"
            + "</content></form-binding>"
            + "<data-item data-type=\"string\" name=\"groups\" target=\"flowdata.Start/My_Form/groups\" target-type=\"single-value\"/>"
            + "</provision-request>");
        Form f = new Form(Form.Kind.REQUEST, "My Form", FORM_V2);   // groups is now a multi-valued select
        List<BindingSync.Change> changes = BindingSync.sync(p, f);
        List<Element> items = BindingSync.children(p.request, "data-item");   // no wrapper → direct children
        assertEquals("[groups]", names(items).toString());
        assertEquals("multi-value-list", items.get(0).getAttribute("target-type"));
        assertEquals("flowdata.Start/My_Form/groups", items.get(0).getAttribute("target"));
        assertTrue(changes.toString(), changes.toString().contains("field 'groups' is now select/string (was textfield/string)"));
        assertTrue(changes.toString(), changes.toString().contains("target-type → multi-value-list"));
        assertEquals("Start", BindingSync.startActivityId(p));
        assertEquals("Start", BindingSync.startActivityId(prd()));
    }

    @Test
    public void bindsTellsWhichPrdsReferenceAForm() {
        Prd p = prd();
        assertTrue(BindingSync.binds(p, "Req"));
        assertFalse(BindingSync.binds(p, "Other"));
    }

    private static java.util.List<String> names(List<Element> es) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Element e : es) {
            out.add(e.getAttribute("name"));
        }
        return out;
    }
}
