package com.pointblue.dirxml.dev.model;

import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;
import org.w3c.dom.Element;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** {@link Prd}: JSON-forms detection and the request/approval form-binding walk. */
public class PrdTest {

    private static Element el(String xml) {
        return CanonicalXml.parse(xml).getDocumentElement();
    }

    @Test
    public void isJsonFormsReflectsRequestFormSrcAttribute() {
        Prd jsonForms = new Prd("X");
        jsonForms.request = el("<provision-request formSrc=\"1\"/>");
        assertTrue(jsonForms.isJsonForms());

        Prd classic = new Prd("Y");
        classic.request = el("<provision-request/>");
        assertFalse(classic.isJsonForms());

        Prd noRequest = new Prd("Z");
        assertFalse(noRequest.isJsonForms());
    }

    @Test
    public void bindingsCollectsRequestAndApprovalBindingsWithFields() {
        Prd prd = new Prd("HelpdeskTicket");
        prd.request = el(
            "<provision-request formSrc=\"1\">"
            + "  <form-binding form-id=\"Help-desk Request Form\">"
            + "    <content>"
            + "      <field data-type=\"string\" name=\"ticketTitle\"><control control-type=\"textfield\"/></field>"
            + "      <field data-type=\"button\" name=\"submit\"><control control-type=\"button\"/></field>"
            + "    </content>"
            + "  </form-binding>"
            + "</provision-request>");
        prd.process = el(
            "<process id=\"p1\">"
            + "  <activities>"
            + "    <user-activity id=\"Activity\">"
            + "      <form-binding activity-id=\"Activity\" form-id=\"Help-desk Approval Form\">"
            + "        <content>"
            + "          <field data-type=\"string\" name=\"comment\"><control control-type=\"textarea\"/></field>"
            + "        </content>"
            + "      </form-binding>"
            + "    </user-activity>"
            + "  </activities>"
            + "</process>");

        List<Prd.FormBinding> bindings = prd.bindings();
        assertEquals(2, bindings.size());

        Prd.FormBinding request = bindings.get(0);
        assertNull(request.activityId);
        assertEquals("Help-desk Request Form", request.formId);
        assertEquals(2, request.fields.size());
        assertEquals("ticketTitle", request.fields.get(0).name);
        assertEquals("string", request.fields.get(0).dataType);
        assertEquals("textfield", request.fields.get(0).controlType);

        Prd.FormBinding approval = bindings.get(1);
        assertEquals("Activity", approval.activityId);
        assertEquals("Help-desk Approval Form", approval.formId);
        assertEquals(1, approval.fields.size());
        assertEquals("comment", approval.fields.get(0).name);
    }

    @Test
    public void bindingsEmptyWhenNoRequestOrProcess() {
        Prd prd = new Prd("Bare");
        assertTrue(prd.bindings().isEmpty());
    }

    @Test
    public void propertyReturnsFirstValueOrNull() {
        Prd prd = new Prd("P");
        prd.properties.put("status", List.of("Active"));
        assertEquals("Active", prd.property("status"));
        assertNull(prd.property("missing"));
    }
}
