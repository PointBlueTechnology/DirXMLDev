package com.pointblue.dirxml.dev.validate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.forms.BindingSyncTest;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;
import org.w3c.dom.Element;

/**
 * One clean driver set (no findings), then one case per {@link FormCheck} code.
 */
public class FormCheckTest {

    private static Report run(DriverSet ds) {
        Report r = new Report();
        new FormCheck().run(ds, r);
        return r;
    }

    /** A driver with one clean request form (a button, a labeled field, two languages fully covered) and no PRDs. */
    private static DriverSet clean() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("UA");
        Provisioning p = new Provisioning();
        String json = "{\"components\":["
            + "{\"key\":\"reason\",\"type\":\"textfield\",\"label\":\"Reason\"},"
            + "{\"key\":\"submit\",\"type\":\"button\",\"label\":\"Submit\"}"
            + "],\"title\":\"T\",\"display\":\"form\",\"inlinescripts\":\"\","
            + "\"localization\":{\"en\":{\"Reason\":\"Reason\",\"Submit\":\"Submit\"},"
            + "\"fr\":{\"Reason\":\"Motif\",\"Submit\":\"Envoyer\"}},\"externalScripts\":[]}";
        p.forms.add(new Form(Form.Kind.REQUEST, "Clean", json));
        d.provisioning = p;
        ds.drivers.add(d);
        return ds;
    }

    private static Provisioning provisioning(DriverSet ds) {
        return ds.driver("UA").provisioning;
    }

    @Test
    public void cleanDriverSetHasNoFindings() {
        Report r = run(clean());
        assertTrue(r.text(), r.findings().isEmpty());
    }

    @Test
    public void formJsonInvalid() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Bad", "{not json"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-json-invalid").size());
        assertEquals(Finding.Severity.ERROR, r.withCode("form-json-invalid").get(0).severity);
    }

    @Test
    public void formJsonInvalidOnANonObjectRoot() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Bad", "[1,2,3]"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-json-invalid").size());
    }

    @Test
    public void formNoComponents() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Bad", "{\"title\":\"T\"}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-no-components").size());
    }

    @Test
    public void emptyComponentsIsNotAnError() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.APPROVAL, "Blank", "{\"components\":[],\"title\":\"Blank\"}"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("form-no-components").isEmpty());
    }

    @Test
    public void formDuplicateKey() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Dup", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\"},"
            + "{\"key\":\"panel\",\"type\":\"panel\",\"components\":[{\"key\":\"a\",\"type\":\"select\"}]}"
            + "]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-duplicate-key").size());
        assertTrue(r.withCode("form-duplicate-key").get(0).message, r.withCode("form-duplicate-key").get(0).message.contains("'a'"));
    }

    @Test
    public void formKeyMissing() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "NoKey", "{\"components\":[{\"type\":\"textfield\",\"label\":\"X\"}]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-key-missing").size());
    }

    @Test
    public void formUnknownType() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Weird", "{\"components\":[{\"key\":\"x\",\"type\":\"not-a-real-type\"}]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-unknown-type").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("form-unknown-type").get(0).severity);
    }

    @Test
    public void everyCapturedTemplateTypeAndStandardTypeAndDataTypeIsKnown() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("UA");
        Provisioning p = new Provisioning();
        String[] types = {
            "button", "checkbox", "column", "columns", "container", "dataItemMappingTextField", "data_item_mapping",
            "datagrid", "datetime", "dn_display", "dynamic_entity", "htmlelement", "labelelement", "panel",
            "permission_requestDN", "radio", "select", "tabs", "textarea", "textfield", "title", "tree",
            // standard types not otherwise covered
            "well", "fieldset", "table", "content", "hidden", "editgrid", "datamap", "day", "time", "address",
            "resource", "form", "unknown", "custom",
            // a BindingSync.DATA_TYPES-only type
            "email"
        };
        StringBuilder comps = new StringBuilder("[");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                comps.append(',');
            }
            comps.append("{\"key\":\"k").append(i).append("\",\"type\":\"").append(types[i]).append("\"}");
        }
        comps.append(']');
        p.forms.add(new Form(Form.Kind.REQUEST, "AllTypes", "{\"components\":" + comps + "}"));
        d.provisioning = p;
        ds.drivers.add(d);
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("form-unknown-type").isEmpty());
    }

    @Test
    public void formConditionalRef() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Cond", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\"},"
            + "{\"key\":\"b\",\"type\":\"textfield\",\"conditional\":{\"when\":\"nope\",\"eq\":\"x\",\"show\":true}}"
            + "]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-conditional-ref").size());
    }

    @Test
    public void formConditionalRefResolvesAKnownKeyWithoutAFinding() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Cond", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\"},"
            + "{\"key\":\"b\",\"type\":\"textfield\",\"conditional\":{\"when\":\"a\",\"eq\":\"x\",\"show\":true}}"
            + "]}"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("form-conditional-ref").isEmpty());
    }

    @Test
    public void formConditionalRefViaLogicSimpleWhen() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Logic", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\",\"logic\":["
            + "{\"trigger\":{\"type\":\"simple\",\"simple\":{\"when\":\"missing\",\"eq\":\"x\"}}}"
            + "]}]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-conditional-ref").size());
    }

    @Test
    public void formScriptSyntax() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "BadScript", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\",\"calculateValue\":\"value = (;\"}"
            + "]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-script-syntax").size());
        assertTrue(r.withCode("form-script-syntax").get(0).message, r.withCode("form-script-syntax").get(0).message.contains("calculateValue"));
    }

    @Test
    public void formScriptSyntaxOnInlinescripts() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "BadInline",
            "{\"components\":[{\"key\":\"a\",\"type\":\"textfield\"}],\"inlinescripts\":\"function(\"}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-script-syntax").size());
        assertTrue(r.withCode("form-script-syntax").get(0).message, r.withCode("form-script-syntax").get(0).message.contains("inlinescripts"));
    }

    @Test
    public void templateExpressionsAndEmptyScriptsAreNotChecked() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Templated", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\",\"calculateValue\":\"{{ data.other }}\",\"customConditional\":\"\"}"
            + "]}"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("form-script-syntax").isEmpty());
    }

    @Test
    public void formRequestButtons() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "NoButton", "{\"components\":[{\"key\":\"a\",\"type\":\"textfield\"}]}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-request-buttons").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("form-request-buttons").get(0).severity);
    }

    @Test
    public void approvalFormWithNoButtonIsNotFlagged() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.APPROVAL, "ApprovalNoButton", "{\"components\":[{\"key\":\"a\",\"type\":\"textfield\"}]}"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("form-request-buttons").isEmpty());
    }

    @Test
    public void formLocalizationMissing() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Partial", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\",\"label\":\"A\"}],"
            + "\"localization\":{\"en\":{\"A\":\"A\"},\"fr\":{}}}"));
        Report r = run(ds);
        assertEquals(1, r.withCode("form-localization-missing").size());
        Finding f = r.withCode("form-localization-missing").get(0);
        assertEquals(Finding.Severity.INFO, f.severity);
        assertTrue(f.message, f.message.contains("'fr'"));
        assertTrue(f.detail, f.detail.contains("A"));
    }

    @Test
    public void aSingleDeclaredLanguageIsNeverFlagged() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "OneLang", "{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\",\"label\":\"A\"}],\"localization\":{\"en\":{}}}"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("form-localization-missing").isEmpty());
    }

    // ---- PRD codes ------------------------------------------------------------------------

    private static Prd prdBoundTo(String formName) {
        Prd prd = new Prd("P");
        prd.request = BindingSyncTest.el(BindingSyncTest.REQUEST_XML.replace("form-id=\"Req\"", "form-id=\"" + formName + "\""));
        prd.process = BindingSyncTest.el(BindingSyncTest.PROCESS_XML.replace("form-id=\"Req\"", "form-id=\"" + formName + "\""));
        prd.definition = BindingSyncTest.el("<prov-req-defn/>");
        return prd;
    }

    @Test
    public void prdBindingStale() {
        DriverSet ds = clean();
        provisioning(ds).prds.add(prdBoundTo("NoSuchForm"));
        Report r = run(ds);
        // both the request binding and the activity binding name a form that doesn't exist
        assertEquals(2, r.withCode("prd-binding-stale").size());
    }

    @Test
    public void prdBindingFieldsDrift() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Req", BindingSyncTest.FORM_V2));
        provisioning(ds).prds.add(prdBoundTo("Req"));
        Report r = run(ds);
        assertEquals(1, r.withCode("prd-binding-fields-drift").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("prd-binding-fields-drift").get(0).severity);
    }

    @Test
    public void noDriftWhenTheBindingIsAlreadyInSync() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Req", BindingSyncTest.FORM_V1));
        provisioning(ds).prds.add(prdBoundTo("Req"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("prd-binding-fields-drift").isEmpty());
    }

    @Test
    public void prdMappingUnbound() {
        DriverSet ds = clean();
        // FORM_V1 has no "extra" field; the fixture's request-data-items also has no such item to begin
        // with, so add one directly to a PRD whose request form doesn't bind it.
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Req", BindingSyncTest.FORM_V1));
        Prd prd = prdBoundTo("Req");
        Element items = com.pointblue.dirxml.sim.Xds.childrenByName(prd.request, "request-data-items").get(0);
        Element extra = items.getOwnerDocument().createElementNS(null, "data-item");
        extra.setAttribute("name", "notAField");
        extra.setAttribute("data-type", "string");
        extra.setAttribute("target", "flowdata.Start/Req/notAField");
        items.appendChild(extra);
        provisioning(ds).prds.add(prd);
        Report r = run(ds);
        assertEquals(1, r.withCode("prd-mapping-unbound").size());
        assertTrue(r.withCode("prd-mapping-unbound").get(0).message, r.withCode("prd-mapping-unbound").get(0).message.contains("notAField"));
    }

    @Test
    public void noUnboundMappingOnTheStockFixture() {
        DriverSet ds = clean();
        provisioning(ds).forms.add(new Form(Form.Kind.REQUEST, "Req", BindingSyncTest.FORM_V1));
        provisioning(ds).prds.add(prdBoundTo("Req"));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("prd-mapping-unbound").isEmpty());
    }
}
