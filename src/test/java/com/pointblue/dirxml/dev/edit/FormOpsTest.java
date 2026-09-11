package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSyncTest;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

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
}
