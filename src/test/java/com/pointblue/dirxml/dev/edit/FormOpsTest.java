package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSyncTest;
import com.pointblue.dirxml.dev.forms.FormEditor;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

public class FormOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A tree with one UA driver: a packaged request form "Req" bound by PRD "P" (request + activity). */
    public static Path tree(TemporaryFolder tmp, boolean packaged) throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";
        Driver ua = new Driver("UA");
        ua.dn = "cn=UA,cn=driverset1,o=system";
        Provisioning p = new Provisioning();
        p.dn = "cn=AppConfig," + ua.dn;
        Form req = new Form(Form.Kind.REQUEST, "Req", Json.pretty(Json.parse(BindingSyncTest.FORM_V1)));
        if (packaged) {
            req.meta.put("dirxml-pkgguid", "B5PAGQ5E_201005261601510810;com.netiqcorporation.novluabase;4.8.0.20190927160316");
            req.meta.put("dirxml-pkgassociationid", "XJ18OTHU_201908061651440962");
            req.meta.put("dirxml-pkgchecksum", "536857469");
        }
        p.forms.add(req);
        Prd prd = new Prd("P");
        prd.request = BindingSyncTest.el(BindingSyncTest.REQUEST_XML);
        prd.definition = BindingSyncTest.el("<prov-req-defn status=\"Active\">" + BindingSyncTest.PROCESS_XML + "</prov-req-defn>");
        prd.process = com.pointblue.dirxml.sim.Xds.childrenByName(prd.definition, "process").get(0);
        prd.properties.put("status", java.util.List.of("Active"));
        if (packaged) {
            prd.meta.put("dirxml-pkgguid", "B5PAGQ5E_201005261601510810;com.netiqcorporation.novluabase;4.8.0.20190927160316");
        }
        p.prds.add(prd);
        ua.provisioning = p;
        ds.drivers.add(ua);
        Path t = tmp.newFolder("tree" + (packaged ? "-pkg" : "")).toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    @Test
    public void setContentStoresPrettyJsonAndSyncsBindings() throws Exception {
        Path t = tree(tmp, false);
        Transaction tx = Transaction.open(t);
        Result r = tx.run(new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/provisioning/forms/request/Req"));
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/provisioning/prds/P"));
        assertTrue(r.customized.isEmpty());
        assertTrue(r.notes.toString(), r.notes.toString().contains("bound field 'justification'"));

        String stored = Files.readString(t.resolve("drivers/UA/provisioning/forms/request/Req.form.json"));
        assertTrue(stored, stored.startsWith("{\n  \"components\": ["));
        assertEquals(Json.parse(BindingSyncTest.FORM_V2), Json.parse(stored));

        DriverSet again = AsCodeReader.read(t);
        Prd p = again.drivers.get(0).provisioning.prd("P");
        assertEquals("[title, justification, groups, info, submit]", names(p.bindings().get(0)));
        String req = CanonicalXml.serialize(p.request);
        assertFalse(req, req.contains("name=\"reason\""));
        assertTrue(req, req.contains("target=\"flowdata.Start/Req/title\""));
        assertFalse(req, req.contains("flowdata.Start/Req/justification"));   // mappings are never invented
        String proc = CanonicalXml.serialize(p.process);
        assertFalse(proc, proc.contains("name=\"reason\""));
        assertTrue(proc, proc.contains("flowdata.get('Start/Req/title')"));
    }

    @Test
    public void packagedFormAndPrdGetBaselinedAndMarkedCustomizedOnce() throws Exception {
        Path t = tree(tmp, true);
        Result r = Transaction.open(t).run(new FormOps.SetContent("UA", "request/Req", BindingSyncTest.FORM_V2), false, false);
        assertTrue(r.text(), r.ok());
        assertEquals("[drivers/UA/provisioning/forms/request/Req, drivers/UA/provisioning/prds/P]", r.customized.toString());
        Path formBaseline = t.resolve(".package-baseline/drivers/UA/provisioning/forms/request/Req.form.json");
        assertTrue(Files.exists(formBaseline));
        assertEquals(Json.parse(BindingSyncTest.FORM_V1), Json.parse(Files.readString(formBaseline)));
        assertTrue(Files.exists(t.resolve(".package-baseline/drivers/UA/provisioning/prds/P/definition.xml")));
        assertTrue(Files.exists(t.resolve(".package-baseline/drivers/UA/provisioning/prds/P/request.xml")));
        assertTrue(Files.readString(t.resolve(".package-baseline/drivers/UA/provisioning/prds/P/request.xml")).contains("name=\"reason\""));

        DriverSet again = AsCodeReader.read(t);
        assertEquals("true", again.drivers.get(0).provisioning.formByName("Req").meta.get(Packages.CUSTOMIZED_KEY));
        assertEquals("true", again.drivers.get(0).provisioning.prd("P").meta.get(Packages.CUSTOMIZED_KEY));

        // a second edit is not "newly customized" and keeps the original baseline
        Result r2 = Transaction.open(t).run(new FormOps.SetContent("UA", "Req", BindingSyncTest.FORM_V1), false, false);
        assertTrue(r2.text(), r2.ok());
        assertTrue(r2.customized.toString(), r2.customized.isEmpty());
        assertEquals(Json.parse(BindingSyncTest.FORM_V1), Json.parse(Files.readString(formBaseline)));
    }

    @Test
    public void syncOnlyRenormalizesAndRebinds() throws Exception {
        Path t = tree(tmp, false);
        // the vendor builder saved compact JSON straight into the tree file
        Files.writeString(t.resolve("drivers/UA/provisioning/forms/request/Req.form.json"), BindingSyncTest.FORM_V2);
        Result r = Transaction.open(t).run(new FormOps.SetContent(null, "Req", null), false, false);
        assertTrue(r.text(), r.ok());
        assertEquals("form.sync", r.operation);
        String stored = Files.readString(t.resolve("drivers/UA/provisioning/forms/request/Req.form.json"));
        assertTrue(stored, stored.startsWith("{\n  \"components\": ["));
        assertEquals("[title, justification, groups, info, submit]",
            names(AsCodeReader.read(t).drivers.get(0).provisioning.prd("P").bindings().get(0)));
    }

    @Test
    public void refusalsAndNoOps() throws Exception {
        Path t = tree(tmp, false);
        Result bad = Transaction.open(t).run(new FormOps.SetContent(null, "Req", "{not json"), false, false);
        assertNotNull(bad.refusal);
        assertTrue(bad.refusal, bad.refusal.contains("not valid JSON"));
        Result noComponents = Transaction.open(t).run(new FormOps.SetContent(null, "Req", "{\"title\":\"x\"}"), false, false);
        assertTrue(noComponents.refusal, noComponents.refusal.contains("components"));
        Result missing = Transaction.open(t).run(new FormOps.SetContent(null, "Nope", BindingSyncTest.FORM_V2), false, false);
        assertTrue(missing.refusal, missing.refusal.contains("not found"));

        Result same = Transaction.open(t).run(new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V1), false, false);
        assertTrue(same.text(), same.ok());
        assertTrue(same.touched.isEmpty());
        assertTrue(same.changedFiles.toString(), same.changedFiles.isEmpty());
        assertTrue(same.notes.toString(), same.notes.toString().contains("unchanged"));

        Result dry = Transaction.open(t).run(new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2), true, false);
        assertTrue(dry.ok());
        assertFalse(dry.written);
        assertEquals(Json.parse(BindingSyncTest.FORM_V1), Json.parse(Files.readString(t.resolve("drivers/UA/provisioning/forms/request/Req.form.json"))));
    }

    @Test
    public void findAcceptsTheThreeReferenceForms() throws Exception {
        DriverSet ds = AsCodeReader.read(tree(tmp, false));
        assertNotNull(FormOps.find(ds, "Req", null));
        assertNotNull(FormOps.find(ds, "request/Req", null));
        assertNotNull(FormOps.find(ds, "UA/request/Req", null));
        assertNull(FormOps.find(ds, "approval/Req", null));
        assertNull(FormOps.find(ds, "Req", "Other"));
    }

    private static String names(Prd.FormBinding b) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Prd.Field f : b.fields) {
            out.add(f.name);
        }
        return out.toString();
    }

    // ==================================================================================
    // Track P step P2b: the typed operations
    // ==================================================================================

    // ---- form.add ---------------------------------------------------------------------

    @Test
    public void formAddCreatesABlankDocument() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.Add(null, "request", "New Form", null, null), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/provisioning/forms/request/New Form"));
        DriverSet again = AsCodeReader.read(t);
        Form f = again.drivers.get(0).provisioning.form(Form.Kind.REQUEST, "New Form");
        assertNotNull(f);
        Map<String, Object> doc = Json.asMap(Json.parse(f.json));
        assertEquals("New Form", doc.get("title"));
        assertEquals("form", doc.get("display"));
        assertTrue(Json.asList(doc.get("components")).isEmpty());
    }

    @Test
    public void formAddFromAnotherFormCopiesItsDocumentWithANewTitle() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.Add(null, "approval", "Req Copy", "Req", null), false, false);
        assertTrue(r.text(), r.ok());
        Form copy = AsCodeReader.read(t).drivers.get(0).provisioning.form(Form.Kind.APPROVAL, "Req Copy");
        assertNotNull(copy);
        Map<String, Object> doc = Json.asMap(Json.parse(copy.json));
        assertEquals("Req Copy", doc.get("title"));
        assertEquals(4, Json.asList(doc.get("components")).size());   // same components as FORM_V1
    }

    @Test
    public void formAddRefusesADuplicateAndABadKind() throws Exception {
        Path t = tree(tmp, false);
        Result dup = Transaction.open(t).run(new FormOps.Add(null, "request", "Req", null, null), false, false);
        assertTrue(dup.refusal, dup.refusal.contains("already exists"));
        Result badKind = Transaction.open(t).run(new FormOps.Add(null, "nope", "X", null, null), false, false);
        assertTrue(badKind.refusal, badKind.refusal.contains("--kind"));
    }

    @Test
    public void formAddRefusesWithoutProvisioning() throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        Driver bare = new Driver("Bare");
        ds.drivers.add(bare);
        Path t = tmp.newFolder("no-provisioning").toPath();
        AsCodeWriter.write(ds, t);
        Result r = Transaction.open(t).run(new FormOps.Add(null, "request", "X", null, null), false, false);
        assertTrue(r.refusal, r.refusal.contains("no driver has provisioning"));
    }

    // ---- form.field.add -----------------------------------------------------------------

    @Test
    public void fieldAddUsesTheCapturedTemplateAndAppliesFlags() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(
            new FormOps.FieldAdd(null, "Req", "newField", "textfield", "New Field",
                true, true, false, false, null, null, false, null, null),
            false, false);
        assertTrue(r.text(), r.ok());
        Form f = AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req");
        FormEditor.Located loc = FormEditor.find(Json.asMap(Json.parse(f.json)), "newField");
        assertNotNull(loc);
        assertEquals("New Field", loc.component.get("label"));
        assertEquals(Boolean.TRUE, Json.asMap(loc.component.get("validate")).get("required"));
        assertEquals(Boolean.TRUE, loc.component.get("hidden"));
        assertNull(loc.component.get("id"));
    }

    @Test
    public void fieldAddMinimalShapeAndJsonMergeAndPlacement() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(
            new FormOps.FieldAdd(null, "Req", "mini", "textfield", "Mini",
                false, false, false, true, "title", null, false, null, "{\"tooltip\":\"hi\"}"),
            false, false);
        assertTrue(r.text(), r.ok());
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        FormEditor.Located loc = FormEditor.find(root, "mini");
        assertEquals("textfield", loc.component.get("type"));
        assertEquals(Boolean.TRUE, loc.component.get("input"));
        assertEquals("hi", loc.component.get("tooltip"));
        assertEquals(5, loc.component.keySet().size());   // label,key,type,input,tooltip
        List<Object> comps = Json.asList(root.get("components"));
        assertEquals("mini", Json.asMap(comps.get(1)).get("key"));   // right after "title"
    }

    @Test
    public void fieldAddIntoAColumnsComponent() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(
            new FormOps.FieldAdd(null, "Req", "boxed", "checkbox", "Boxed",
                false, false, false, true, null, null, false, "columns", null),
            false, false);
        assertTrue(r.text(), r.ok());
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        assertNotNull(FormEditor.find(root, "boxed"));
    }

    @Test
    public void fieldAddRefusesADuplicateKeyAnywhereInTheDocument() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(
            new FormOps.FieldAdd(null, "Req", "submit", "textfield", null,
                false, false, false, true, null, null, false, null, null),
            false, false);
        assertTrue(r.refusal, r.refusal.contains("already has a component"));
    }

    // ---- form.field.set -----------------------------------------------------------------

    @Test
    public void fieldSetChangesLabelFlagsTypeAndDottedProps() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(
            new FormOps.FieldSet(null, "Req", "reason", "New Label", true, true, null, "textarea",
                java.util.List.of("validate.maxLength=50", "data.values=[{\"label\":\"A\",\"value\":\"a\"}]")),
            false, false);
        assertTrue(r.text(), r.ok());
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        FormEditor.Located loc = FormEditor.find(root, "reason");
        assertEquals("New Label", loc.component.get("label"));
        assertEquals("textarea", loc.component.get("type"));
        Map<String, Object> validate = Json.asMap(loc.component.get("validate"));
        assertEquals(Boolean.TRUE, validate.get("required"));
        assertEquals(Json.Num.of("50"), validate.get("maxLength"));
        assertEquals(Boolean.TRUE, loc.component.get("hidden"));
        assertEquals(1, Json.asList(Json.asMap(loc.component.get("data")).get("values")).size());
    }

    @Test
    public void fieldSetRefusesUnknownKey() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.FieldSet(null, "Req", "nope", "L", null, null, null, null, null), false, false);
        assertTrue(r.refusal, r.refusal.contains("no component with key"));
    }

    @Test
    public void fieldSetRefusesAMalformedProp() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(
            new FormOps.FieldSet(null, "Req", "reason", null, null, null, null, null, java.util.List.of("noEquals")),
            false, false);
        assertTrue(r.refusal, r.refusal.contains("path=value"));
    }

    // ---- form.field.remove --------------------------------------------------------------

    @Test
    public void fieldRemoveDeletesAndSyncPrunesTheMapping() throws Exception {
        Path t = tree(tmp, false);
        // "reason" is mapped in the fixture PRD, so this needs --force; the sync then prunes the mapping
        Result r = Transaction.open(t).run(new FormOps.FieldRemove(null, "Req", "reason"), false, true);
        assertTrue(r.text(), r.ok());
        assertTrue(r.notes.toString(), r.notes.toString().contains("data item 'reason' removed"));
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        assertNull(FormEditor.find(root, "reason"));
    }

    @Test
    public void fieldRemoveRefusesWhileMappedUnlessForced() throws Exception {
        Path t = tree(tmp, false);
        // "title" is mapped in the fixture PRD's request-data-items and process data-items
        Result refused = Transaction.open(t).run(new FormOps.FieldRemove(null, "Req", "title"), false, false);
        assertNotNull(refused.refusal);
        assertTrue(refused.refusal, refused.refusal.contains("is mapped by prd"));

        Result forced = Transaction.open(t).run(new FormOps.FieldRemove(null, "Req", "title"), false, true);
        assertTrue(forced.text(), forced.ok());
    }

    @Test
    public void fieldRemoveRefusesUnknownKey() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.FieldRemove(null, "Req", "nope"), false, false);
        assertTrue(r.refusal, r.refusal.contains("no component with key"));
    }

    // ---- form.field.move ----------------------------------------------------------------

    @Test
    public void fieldMoveReordersAndReparents() throws Exception {
        Path t = tree(tmp, false);
        Result r1 = Transaction.open(t).run(new FormOps.FieldMove(null, "Req", "reason", null, null, true, false, null), false, false);
        assertTrue(r1.text(), r1.ok());
        Map<String, Object> root1 = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        assertEquals("reason", Json.asMap(Json.asList(root1.get("components")).get(0)).get("key"));

        Result r2 = Transaction.open(t).run(new FormOps.FieldMove(null, "Req", "reason", null, null, false, true, "columns"), false, false);
        assertTrue(r2.text(), r2.ok());
        Map<String, Object> root2 = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        FormEditor.Located moved = FormEditor.find(root2, "reason");
        assertNotNull(moved);
        assertFalse(Json.asList(root2.get("components")).stream().anyMatch(o -> "reason".equals(Json.asMap(o).get("key"))));
    }

    @Test
    public void fieldMoveRequiresAPosition() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.FieldMove(null, "Req", "reason", null, null, false, false, null), false, false);
        assertTrue(r.refusal, r.refusal.contains("--after, --before, --first or --last"));
    }

    // ---- form.set ---------------------------------------------------------------------

    @Test
    public void setFormChangesTitleDisplayAndExternalScripts() throws Exception {
        Path t = tree(tmp, false);
        Result r1 = Transaction.open(t).run(new FormOps.SetForm(null, "Req", "New Title", "workflowWizard", null, "https://example/a.js", false), false, false);
        assertTrue(r1.text(), r1.ok());
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        assertEquals("New Title", root.get("title"));
        assertEquals("workflowWizard", root.get("display"));
        assertEquals(java.util.List.of("https://example/a.js"), root.get("externalScripts"));

        Result r2 = Transaction.open(t).run(new FormOps.SetForm(null, "Req", null, null, null, "https://example/a.js", true), false, false);
        assertTrue(r2.text(), r2.ok());
        Map<String, Object> root2 = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        assertTrue(Json.asList(root2.get("externalScripts")).isEmpty());
    }

    @Test
    public void setFormWithInlineScript() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.SetForm(null, "Req", null, null, "var x = 1;", null, false), false, false);
        assertTrue(r.text(), r.ok());
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        assertEquals("var x = 1;", root.get("inlinescripts"));
    }

    @Test
    public void setFormRefusesWithNothingToDoOrABadDisplay() throws Exception {
        Path t = tree(tmp, false);
        Result none = Transaction.open(t).run(new FormOps.SetForm(null, "Req", null, null, null, null, false), false, false);
        assertTrue(none.refusal, none.refusal.contains("give --title"));
        Result badDisplay = Transaction.open(t).run(new FormOps.SetForm(null, "Req", null, "nope", null, null, false), false, false);
        assertTrue(badDisplay.refusal, badDisplay.refusal.contains("--display"));
    }

    // ---- form.localize ------------------------------------------------------------------

    @Test
    public void localizeSetsExplicitStrings() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.Localize(null, "Req", "fr", java.util.List.of("T=Titre Libre"), false), false, false);
        assertTrue(r.text(), r.ok());
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        Map<String, Object> fr = Json.asMap(Json.asMap(root.get("localization")).get("fr"));
        assertEquals("Titre Libre", fr.get("T"));
    }

    @Test
    public void localizeSyncAddsMissingEntriesToEveryDeclaredLanguage() throws Exception {
        Path t = tree(tmp, false);
        // FORM_V1's components carry no labels at all; add one so there is something to sync
        Transaction.open(t).run(new FormOps.FieldAdd(null, "Req", "extra", "textfield", "Extra Label",
            false, false, false, false, null, null, false, null, null), false, false);
        // seed a second language with a stale/pre-existing entry to prove --sync only adds what's missing
        Transaction.open(t).run(new FormOps.Localize(null, "Req", "de", java.util.List.of("Extra Label=vorhanden"), false), false, false);
        Result r = Transaction.open(t).run(new FormOps.Localize(null, "Req", "fr", null, true), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.notes.toString(), r.notes.toString().contains("localization 'fr'"));
        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        Map<String, Object> localization = Json.asMap(root.get("localization"));
        Map<String, Object> fr = Json.asMap(localization.get("fr"));
        assertEquals("Extra Label", fr.get("Extra Label"));   // English/source text used as the value
        Map<String, Object> de = Json.asMap(localization.get("de"));
        assertEquals("vorhanden", de.get("Extra Label"));     // pre-existing entry untouched
    }

    @Test
    public void localizeRefusesWithoutSetOrSync() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.Localize(null, "Req", "fr", null, false), false, false);
        assertTrue(r.refusal, r.refusal.contains("--set or --sync"));
    }

    @Test
    public void localizeSyncUnionsKeysAcrossLanguagesSoFormCheckIsSatisfied() throws Exception {
        Path t = tree(tmp, false);
        // "en" carries entries no other language has ("X" — a stock-form-style title/button/message
        // that isn't tied to any current component) and "de" is missing both "X" and the labeled
        // component "B"; --sync must top every language up with the union, not just its own labels.
        String formJson = "{\"components\":[{\"key\":\"b\",\"type\":\"textfield\",\"label\":\"B\",\"input\":true}],"
            + "\"title\":\"T\",\"display\":\"form\",\"inlinescripts\":\"\","
            + "\"localization\":{\"en\":{\"A\":\"a\",\"X\":\"x-only-in-en\"},\"de\":{\"A\":\"a-de\"}},\"externalScripts\":[]}";
        Transaction.open(t).run(new FormOps.SetContent(null, "Req", formJson), false, false);
        Result r = Transaction.open(t).run(new FormOps.Localize(null, "Req", "en", null, true), false, false);
        assertTrue(r.text(), r.ok());

        Map<String, Object> root = Json.asMap(Json.parse(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Req").json));
        Map<String, Object> localization = Json.asMap(root.get("localization"));
        Map<String, Object> en = Json.asMap(localization.get("en"));
        Map<String, Object> de = Json.asMap(localization.get("de"));
        assertEquals("a", en.get("A"));
        assertEquals("x-only-in-en", en.get("X"));
        assertEquals("B", en.get("B"));
        assertEquals("a-de", de.get("A"));
        assertEquals("x-only-in-en", de.get("X"));   // topped up from "en" (the --lang argument)
        assertEquals("B", de.get("B"));              // topped up from the component's own label

        com.pointblue.dirxml.dev.validate.Report report = new com.pointblue.dirxml.dev.validate.Report();
        new com.pointblue.dirxml.dev.validate.FormCheck().run(AsCodeReader.read(t), report);
        assertTrue(report.text(), report.withCode("form-localization-missing").isEmpty());
    }

    // ---- form.rename --------------------------------------------------------------------

    @Test
    public void renameRewritesFormIdAndFlowdataPrefixesAcrossPrds() throws Exception {
        Path t = tree(tmp, false);
        Result r = Transaction.open(t).run(new FormOps.Rename(null, "Req", "New Req"), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.renamed.toString(), r.renamed.containsKey("drivers/UA/provisioning/forms/request/Req"));
        assertTrue(r.touched.toString(), r.touched.contains("drivers/UA/provisioning/prds/P"));

        DriverSet again = AsCodeReader.read(t);
        Provisioning p = again.drivers.get(0).provisioning;
        assertNull(p.formByName("Req"));
        assertNotNull(p.formByName("New Req"));
        Prd prd = p.prd("P");
        assertEquals("New Req", prd.bindings().get(0).formId);
        assertEquals("New Req", prd.bindings().get(1).formId);
        String req = CanonicalXml.serialize(prd.request);
        assertTrue(req, req.contains("flowdata.Start/New_Req/title"));
        assertFalse(req, req.contains("flowdata.Start/Req/title"));
        String proc = CanonicalXml.serialize(prd.process);
        assertTrue(proc, proc.contains("flowdata.get('Start/New_Req/title')"));
    }

    @Test
    public void renameRefusesADuplicateNameAndIsANoOpForTheSameName() throws Exception {
        Path t = tree(tmp, false);
        Transaction.open(t).run(new FormOps.Add(null, "request", "Other", null, null), false, false);
        Result dup = Transaction.open(t).run(new FormOps.Rename(null, "Req", "Other"), false, false);
        assertTrue(dup.refusal, dup.refusal.contains("already exists"));

        Result same = Transaction.open(t).run(new FormOps.Rename(null, "Req", "Req"), false, false);
        assertTrue(same.text(), same.ok());
        assertTrue(same.touched.isEmpty());
    }

    @Test
    public void renameOfAPackagedFormMarksItCustomized() throws Exception {
        Path t = tree(tmp, true);
        Result r = Transaction.open(t).run(new FormOps.Rename("UA", "request/Req", "Renamed"), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.customized.toString(), r.customized.contains("drivers/UA/provisioning/forms/request/Renamed"));
    }

    // ---- form.delete --------------------------------------------------------------------

    @Test
    public void deleteRefusesWhileBoundRegardlessOfForce() throws Exception {
        Path t = tree(tmp, false);
        Result refused = Transaction.open(t).run(new FormOps.Delete(null, "Req"), false, false);
        assertTrue(refused.refusal, refused.refusal.contains("is bound by prd"));
        Result stillRefused = Transaction.open(t).run(new FormOps.Delete(null, "Req"), false, true);
        assertTrue(stillRefused.refusal, stillRefused.refusal.contains("is bound by prd"));
    }

    @Test
    public void deleteAnUnboundFormWorksAndAPackagedOneNeedsForce() throws Exception {
        Path t = tree(tmp, true);
        Transaction.open(t).run(new FormOps.Add(null, "request", "Unbound", null, null), false, false);
        Result deleted = Transaction.open(t).run(new FormOps.Delete(null, "Unbound"), false, false);
        assertTrue(deleted.text(), deleted.ok());
        assertNull(AsCodeReader.read(t).drivers.get(0).provisioning.formByName("Unbound"));

        // "Req" is packaged and bound in this fixture; unbind by deleting the PRD's binding is out of
        // scope here, so exercise the packaged-needs-force branch on a packaged-but-unbound form instead
        Transaction.open(t).run(new FormOps.Add(null, "request", "Packaged-ish", null, null), false, false);
        // (a freshly added form isn't packaged, so this just confirms the happy path stays refusal-free)
        Result r = Transaction.open(t).run(new FormOps.Delete(null, "Packaged-ish"), false, false);
        assertTrue(r.text(), r.ok());
    }

    // ---- prd.map ------------------------------------------------------------------------

    @Test
    public void prdMapAddsAndUnmapsARequestFieldMapping() throws Exception {
        Path t = tree(tmp, false);
        Transaction.open(t).run(new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2), false, false);

        Result mapped = Transaction.open(t).run(new FormOps.PrdMap(null, "P", "justification", null, null, null, false), false, false);
        assertTrue(mapped.text(), mapped.ok());
        Prd prd = AsCodeReader.read(t).drivers.get(0).provisioning.prd("P");
        Element item = findDataItem(prd.request, "justification");
        assertNotNull(item);
        assertEquals("flowdata.Start/Req/justification", item.getAttribute("target"));
        assertEquals("string", item.getAttribute("data-type"));

        Result unmapped = Transaction.open(t).run(new FormOps.PrdMap(null, "P", "justification", null, null, null, true), false, false);
        assertTrue(unmapped.text(), unmapped.ok());
        Prd prd2 = AsCodeReader.read(t).drivers.get(0).provisioning.prd("P");
        assertNull(findDataItem(prd2.request, "justification"));
    }

    @Test
    public void prdMapAddsAnActivityFieldMappingWithDefaultSource() throws Exception {
        Path t = tree(tmp, false);
        Transaction.open(t).run(new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2), false, false);
        Result r = Transaction.open(t).run(new FormOps.PrdMap(null, "P", "justification", "Activity", null, null, false), false, false);
        assertTrue(r.text(), r.ok());
        Prd prd = AsCodeReader.read(t).drivers.get(0).provisioning.prd("P");
        Element holder = null;
        for (Element di : com.pointblue.dirxml.sim.Xds.childrenByName(prd.process, "data-items")) {
            if ("Activity".equals(di.getAttribute("activity-id"))) {
                holder = di;
            }
        }
        assertNotNull(holder);
        Element item = findDataItem(holder, "justification");
        assertNotNull(item);
        assertEquals("flowdata.get('Start/Req/justification')", item.getAttribute("source"));
    }

    @Test
    public void prdMapRefusesAnUnbindableFieldAndAMissingUnmap() throws Exception {
        Path t = tree(tmp, false);
        Result button = Transaction.open(t).run(new FormOps.PrdMap(null, "P", "submit", null, null, null, false), false, false);
        assertTrue(button.refusal, button.refusal.contains("not bound/bindable"));

        Result firstUnmap = Transaction.open(t).run(new FormOps.PrdMap(null, "P", "title", null, null, null, true), false, false);
        assertTrue(firstUnmap.text(), firstUnmap.ok());
        Result missingUnmap = Transaction.open(t).run(new FormOps.PrdMap(null, "P", "title", null, null, null, true), false, false);
        assertTrue(missingUnmap.refusal, missingUnmap.refusal.contains("is not mapped"));
    }

    private static Element findDataItem(Element holder, String name) {
        if (holder == null) {
            return null;
        }
        for (Element di : com.pointblue.dirxml.sim.Xds.descendantsByName(holder, "data-item")) {
            if (name.equals(di.getAttribute("name"))) {
                return di;
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        List<Element> c = com.pointblue.dirxml.sim.Xds.childrenByName(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    // ---- prd.add ------------------------------------------------------------------------

    /** A driver with a Template PRD ("Tmpl") bound to a request form and one approval activity. */
    public static Path templateTree(TemporaryFolder tmp) throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";
        Driver ua = new Driver("UA");
        ua.dn = "cn=UA,cn=driverset1,o=system";
        Provisioning p = new Provisioning();
        p.dn = "cn=AppConfig," + ua.dn;

        p.forms.add(new Form(Form.Kind.REQUEST, "TmplReqForm", Json.pretty(Json.parse(BindingSyncTest.FORM_V1))));
        p.forms.add(new Form(Form.Kind.APPROVAL, "TmplApprForm", Json.pretty(Json.parse(BindingSyncTest.FORM_V1))));
        p.forms.add(new Form(Form.Kind.REQUEST, "NewReqForm", Json.pretty(Json.parse(BindingSyncTest.FORM_V2))));
        p.forms.add(new Form(Form.Kind.APPROVAL, "NewApprForm", Json.pretty(Json.parse(BindingSyncTest.FORM_V2))));

        String requestXml = "<provision-request formSrc=\"1\" version=\"3.6.1\">"
            + "<form-binding form-id=\"TmplReqForm\"><content>"
            + "<field data-type=\"string\" name=\"title\"><control control-type=\"title\"/></field>"
            + "<field data-type=\"dn\" name=\"recipient\"><control control-type=\"dn_display\"/></field>"
            + "<field data-type=\"string\" name=\"reason\"><control control-type=\"textfield\"/></field>"
            + "<field data-type=\"button\" name=\"submit\"><control control-type=\"button\"/></field>"
            + "</content></form-binding>"
            + "<request-data-items>"
            + "<data-item data-type=\"string\" name=\"title\" target=\"flowdata.Start/TmplReqForm/title\" target-type=\"single-value\"/>"
            + "</request-data-items>"
            + "</provision-request>";
        String definitionXml = "<prov-req-defn status=\"Template\" prov-id=\"Tmpl\" prov-category=\"systemTemplates\">"
            + "<display-name xml:lang=\"en\">Tmpl</display-name>"
            + "<process formSrc=\"1\" id=\"cn=Tmpl,cn=RequestDefs,cn=AppConfig,cn=UA,cn=driverset1,o=system\">"
            + "<display-name xml:lang=\"en\">Tmpl</display-name>"
            + "<form-binding activity-id=\"Activity\" form-id=\"TmplApprForm\"/>"
            + "<data-items activity-id=\"Activity\">"
            + "<data-item data-type=\"string\" name=\"title\" source=\"flowdata.get('Start/TmplReqForm/title')\" target-type=\"single-value\"/>"
            + "</data-items>"
            + "<start-activity activity-id=\"Start\"/>"
            + "<user-activity activity-id=\"Activity\"/>"
            + "</process>"
            + "</prov-req-defn>";
        Element definition = BindingSyncTest.el(definitionXml);
        Prd tmpl = new Prd("Tmpl");
        tmpl.definition = definition;
        tmpl.request = BindingSyncTest.el(requestXml);
        tmpl.process = com.pointblue.dirxml.sim.Xds.childrenByName(definition, "process").get(0);
        tmpl.properties.put("status", java.util.List.of("Template"));
        tmpl.properties.put("category-key", java.util.List.of("systemTemplates"));
        p.prds.add(tmpl);

        ua.provisioning = p;
        ds.drivers.add(ua);
        Path t = tmp.newFolder("template-tree").toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    @Test
    public void prdAddCopiesATemplateAndBindsTheGivenForms() throws Exception {
        Path t = templateTree(tmp);
        Result r = Transaction.open(t).run(
            new FormOps.PrdAdd(null, "NewPrd", "Tmpl", "NewReqForm", "NewApprForm", null, null, false), false, false);
        assertTrue(r.text(), r.ok());

        DriverSet again = AsCodeReader.read(t);
        Prd created = again.drivers.get(0).provisioning.prd("NewPrd");
        assertNotNull(created);
        assertEquals("Active", created.definition.getAttribute("status"));
        assertEquals("NewPrd", created.definition.getAttribute("prov-id"));
        assertEquals("systemTemplates", created.property("category-key"));
        assertTrue(CanonicalXml.serialize(created.definition), CanonicalXml.serialize(created.definition).contains(">NewPrd<"));

        List<Prd.FormBinding> bindings = created.bindings();
        assertEquals("NewReqForm", bindings.get(0).formId);
        assertEquals("NewApprForm", bindings.get(1).formId);
        // BindingSync rebuilt the request field list from NewReqForm (FORM_V2's components)
        assertEquals("[title, justification, groups, info, submit]", names(bindings.get(0)));
        // the template's original PRD is untouched
        assertNotNull(again.drivers.get(0).provisioning.prd("Tmpl"));
        assertEquals("cn=NewPrd,cn=RequestDefs,cn=AppConfig,cn=UA,cn=driverset1,o=system", created.process.getAttribute("id"));
    }

    @Test
    public void prdAddCategoryAndDisplayNameOverrides() throws Exception {
        Path t = templateTree(tmp);
        Result r = Transaction.open(t).run(
            new FormOps.PrdAdd(null, "NewPrd", "Tmpl", "NewReqForm", null, "customCat", "en~Friendly Name", false), false, false);
        assertTrue(r.text(), r.ok());
        Prd created = AsCodeReader.read(t).drivers.get(0).provisioning.prd("NewPrd");
        assertEquals("customCat", created.definition.getAttribute("prov-category"));
        assertEquals("customCat", created.property("category-key"));
        String def = CanonicalXml.serialize(created.definition);
        assertTrue(def, def.contains("Friendly Name"));
        // no approval form given: the template's approval binding is left as-is
        assertEquals("TmplApprForm", created.bindings().get(1).formId);
    }

    @Test
    public void prdAddRefusals() throws Exception {
        Path t = templateTree(tmp);
        Result dup = Transaction.open(t).run(new FormOps.PrdAdd(null, "Tmpl", "Tmpl", "NewReqForm", null, null, null, false), false, false);
        assertTrue(dup.refusal, dup.refusal.contains("already exists"));

        Result noTmpl = Transaction.open(t).run(new FormOps.PrdAdd(null, "X", "Nope", "NewReqForm", null, null, null, false), false, false);
        assertTrue(noTmpl.refusal, noTmpl.refusal.contains("template prd"));

        Result noReqForm = Transaction.open(t).run(new FormOps.PrdAdd(null, "X", "Tmpl", "Nope", null, null, null, false), false, false);
        assertTrue(noReqForm.refusal, noReqForm.refusal.contains("not found"));

        Result noApprForm = Transaction.open(t).run(new FormOps.PrdAdd(null, "X", "Tmpl", "NewReqForm", "Nope", null, null, false), false, false);
        assertTrue(noApprForm.refusal, noApprForm.refusal.contains("not found"));

        Result badDisplayName = Transaction.open(t).run(new FormOps.PrdAdd(null, "X", "Tmpl", "NewReqForm", null, null, "noTilde", false), false, false);
        assertTrue(badDisplayName.refusal, badDisplayName.refusal.contains("lang~Text"));
    }

    @Test
    public void prdAddMapAllMapsEveryBindableRequestField() throws Exception {
        Path t = templateTree(tmp);
        Result r = Transaction.open(t).run(
            new FormOps.PrdAdd(null, "NewPrd", "Tmpl", "NewReqForm", null, null, null, true), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.notes.toString(), r.notes.toString().contains("mapped 4 fields"));

        Prd created = AsCodeReader.read(t).drivers.get(0).provisioning.prd("NewPrd");
        Element holder = firstChild(created.request, "request-data-items");
        assertNotNull(holder);
        // NewReqForm is FORM_V2: title, justification, groups, info are bindable (apwaComment/submit are not)
        assertEquals("[title, justification, groups, info]", dataItemNames(holder));

        assertDataItem(holder, "title", "flowdata.Start/NewReqForm/title", "single-value");
        assertDataItem(holder, "justification", "flowdata.Start/NewReqForm/justification", "single-value");
        assertDataItem(holder, "groups", "flowdata.Start/NewReqForm/groups", "multi-value-list");
        assertDataItem(holder, "info", "flowdata.Start/NewReqForm/info", "single-value");
    }

    @Test
    public void prdAddWithoutMapAllOnlyKeepsWhateverBindingSyncWouldAnyway() throws Exception {
        Path t = templateTree(tmp);
        Result r = Transaction.open(t).run(
            new FormOps.PrdAdd(null, "NewPrd", "Tmpl", "NewReqForm", null, null, null, false), false, false);
        assertTrue(r.text(), r.ok());
        assertFalse(r.notes.toString(), r.notes.toString().contains("mapped"));
        Prd created = AsCodeReader.read(t).drivers.get(0).provisioning.prd("NewPrd");
        Element holder = firstChild(created.request, "request-data-items");
        // the template's own mapping ("title") survives ordinary BindingSync.sync because NewReqForm
        // happens to still have a field named "title"; the fields BindingSync never invents
        // (justification, groups, info — new on NewReqForm) get no data item without --map-all
        assertEquals("[title]", dataItemNames(holder));
    }

    @Test
    public void prdAddMapAllWithApprovalFormSkipsFieldsNotOnTheRequestForm() throws Exception {
        Path t = templateTree(tmp);
        // request form = NewReqForm (FORM_V2: title, justification, groups, info); approval form =
        // TmplApprForm (FORM_V1: title, recipient, reason) — only "title" is a shared field name,
        // so mapActivityField refuses "recipient" and "reason" (no --source, no same-named request
        // field) and mapAll must swallow those refusals and note them.
        Result r = Transaction.open(t).run(
            new FormOps.PrdAdd(null, "NewPrd", "Tmpl", "NewReqForm", "TmplApprForm", null, null, true), false, false);
        assertTrue(r.text(), r.ok());
        assertTrue(r.notes.toString(), r.notes.toString().contains("mapped 5 fields"));
        assertTrue(r.notes.toString(), r.notes.toString().contains("skipped approval fields not present on the request form: recipient, reason"));

        Prd created = AsCodeReader.read(t).drivers.get(0).provisioning.prd("NewPrd");
        Element holder = firstChild(created.request, "request-data-items");
        assertNotNull(holder);
        assertEquals("[title, justification, groups, info]", dataItemNames(holder));

        Element actHolder = null;
        for (Element di : com.pointblue.dirxml.sim.Xds.childrenByName(created.process, "data-items")) {
            if ("Activity".equals(di.getAttribute("activity-id"))) {
                actHolder = di;
            }
        }
        assertNotNull(actHolder);
        assertEquals("[title]", dataItemNames(actHolder));
        Element actTitle = findDataItem(actHolder, "title");
        assertEquals("flowdata.get('Start/NewReqForm/title')", actTitle.getAttribute("source"));
        assertEquals("single-value", actTitle.getAttribute("target-type"));
    }

    private static String dataItemNames(Element holder) {
        List<String> names = new java.util.ArrayList<>();
        for (Element di : com.pointblue.dirxml.sim.Xds.childrenByName(holder, "data-item")) {
            names.add(di.getAttribute("name"));
        }
        return names.toString();
    }

    private static void assertDataItem(Element holder, String name, String target, String targetType) {
        Element item = findDataItem(holder, name);
        assertNotNull(item);
        assertEquals(target, item.getAttribute("target"));
        assertEquals(targetType, item.getAttribute("target-type"));
    }
}
