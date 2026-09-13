package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.edit.FormOps;
import com.pointblue.dirxml.dev.edit.FormOpsTest;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.forms.BindingSyncTest;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Provisioning objects through ModelDiff → Plan (the deployer executes plan steps generically). */
public class ProvisioningDeployTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";

    @Test
    public void editedFormAndPrdDiffAndPlanWithoutARestart() throws Exception {
        Path t = FormOpsTest.tree(tmp, false);
        DriverSet from = AsCodeReader.read(t);
        Result r = Transaction.open(t).run(new FormOps.SetContent(null, "Req", BindingSyncTest.FORM_V2), false, false);
        assertTrue(r.text(), r.ok());
        DriverSet to = AsCodeReader.read(t);

        ModelDiff diff = ModelDiff.of(from, to);
        List<String> kinds = new ArrayList<>();
        for (ModelDiff.Change c : diff.changes()) {
            kinds.add(c.kind + " " + c.path);
        }
        assertEquals("[FORM_CHANGED drivers/UA/provisioning/forms/request/Req, PRD_CHANGED drivers/UA/provisioning/prds/P]", kinds.toString());
        assertTrue("no engine restart for provisioning", diff.affectedDrivers().isEmpty());
        assertTrue(diff.text(), diff.text().contains("justification"));

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertTrue(plan.restart.isEmpty());
        List<String> steps = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            steps.add(s.op + " " + s.dn + (s.attr == null ? "" : " " + s.attr));
        }
        String formDn = "cn=Req,cn=WorkflowRequestForms,cn=WorkflowForms,cn=AppConfig,cn=UA," + DS;
        String prdDn = "cn=P,cn=RequestDefs,cn=AppConfig,cn=UA," + DS;
        assertTrue(steps.toString(), steps.contains("MODIFY " + formDn + " srvprvJSONData"));
        // the binding resync changes the PRD's definition, request and process XML (the process element is
        // a child of the definition, so both list as changed) but no plain property — trimmed to exactly those
        // three (follow-up 1, docs/vault-deploy.md): no srvprvStatus, no other property, no stamp steps.
        assertEquals(steps.toString(), List.of(
            "MODIFY " + formDn + " srvprvJSONData",
            "MODIFY " + prdDn + " XmlData",
            "MODIFY " + prdDn + " srvprvRequestXML",
            "MODIFY " + prdDn + " srvprvProcessXML"), steps);
        assertFalse(steps.toString(), steps.toString().contains("srvprvStatus"));
        assertFalse(steps.toString(), steps.toString().contains("ENSURE_CONTAINER"));
        assertTrue(plan.touchedDns.contains(formDn) && plan.touchedDns.contains(prdDn));

        // the form goes to the vault compact, as the vendor builder writes it
        for (Plan.Step s : plan.steps) {
            if ("srvprvJSONData".equals(s.attr)) {
                String wire = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
                assertFalse(wire, wire.contains("\n"));
                assertEquals(Json.parse(BindingSyncTest.FORM_V2), Json.parse(wire));
            }
            if ("XmlData".equals(s.attr)) {
                String xml = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
                assertTrue(xml, xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>") && xml.contains("<prov-req-defn"));
            }
        }
    }

    /** Follow-up 1 (docs/vault-deploy.md, "The plan and the deploy"): only the changed XML part is written. */
    @Test
    public void changedPrdRequestXmlOnlyProducesOneModifyStep() throws Exception {
        Path t = FormOpsTest.tree(tmp, false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        Prd p = to.drivers.get(0).provisioning.prd("P");
        p.request = CanonicalXml.parse(BindingSyncTest.REQUEST_XML.replace(
            "name=\"reason\" target=\"flowdata.Start/Req/reason\"", "name=\"reason\" target=\"flowdata.Start/Req/reason2\""))
            .getDocumentElement();

        ModelDiff diff = ModelDiff.of(from, to);
        List<ModelDiff.Change> changes = diff.changes();
        assertEquals(1, changes.size());
        assertEquals(ModelDiff.Kind.PRD_CHANGED, changes.get(0).kind);
        assertEquals(java.util.Set.of("request"), changes.get(0).parts);

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        String prdDn = "cn=P,cn=RequestDefs,cn=AppConfig,cn=UA," + DS;
        List<String> steps = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            steps.add(s.op + " " + s.dn + (s.attr == null ? "" : " " + s.attr));
        }
        assertEquals(List.of("MODIFY " + prdDn + " srvprvRequestXML"), steps);
    }

    /** Follow-up 1: a changed plain property (status) writes only that attribute. */
    @Test
    public void changedPrdPropertyOnlyProducesOneModifyStep() throws Exception {
        Path t = FormOpsTest.tree(tmp, false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        Prd p = to.drivers.get(0).provisioning.prd("P");
        p.properties.put("status", List.of("Draft"));

        ModelDiff diff = ModelDiff.of(from, to);
        List<ModelDiff.Change> changes = diff.changes();
        assertEquals(1, changes.size());
        assertEquals(ModelDiff.Kind.PRD_CHANGED, changes.get(0).kind);
        assertEquals(java.util.Set.of("status"), changes.get(0).parts);

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        String prdDn = "cn=P,cn=RequestDefs,cn=AppConfig,cn=UA," + DS;
        List<String> steps = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            steps.add(s.op + " " + s.dn + (s.attr == null ? "" : " " + s.attr));
        }
        assertEquals(List.of("MODIFY " + prdDn + " srvprvStatus"), steps);
    }

    @Test
    public void newFormEnsuresContainersAndRemovedFormDeletes() throws Exception {
        Path t = FormOpsTest.tree(tmp, false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        Form appr = new Form(Form.Kind.APPROVAL, "New Appr", BindingSyncTest.FORM_V1);
        to.drivers.get(0).provisioning.forms.add(appr);
        to.drivers.get(0).provisioning.forms.removeIf(f -> f.name.equals("Req"));

        ModelDiff diff = ModelDiff.of(from, to);
        List<String> kinds = new ArrayList<>();
        for (ModelDiff.Change c : diff.changes()) {
            kinds.add(c.kind + " " + c.path);
        }
        assertEquals("[FORM_ADDED drivers/UA/provisioning/forms/approval/New Appr, FORM_REMOVED drivers/UA/provisioning/forms/request/Req]", kinds.toString());

        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        List<String> steps = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            steps.add(s.op + " " + s.dn);
        }
        String base = "cn=AppConfig,cn=UA," + DS;
        assertEquals(List.of(
            "ENSURE_CONTAINER cn=WorkflowForms," + base,
            "ENSURE_CONTAINER cn=WorkflowApprovalForms,cn=WorkflowForms," + base,
            "ADD cn=New Appr,cn=WorkflowApprovalForms,cn=WorkflowForms," + base,
            "DELETE cn=Req,cn=WorkflowRequestForms,cn=WorkflowForms," + base), steps);
        Plan.Step add = plan.steps.get(2);
        assertEquals(List.of("Top", "srvprvJSONForm"), add.objectClasses);
        assertNotNull(add.values.get("srvprvJSONData"));
        assertEquals(List.of("Top", "srvprvJSONForms"), plan.steps.get(0).objectClasses);
    }

    @Test
    public void customizedPackagedFormCarriesStampsAndAContentChecksum() throws Exception {
        Path t = FormOpsTest.tree(tmp, true);
        DriverSet from = AsCodeReader.read(t);
        Result r = Transaction.open(t).run(new FormOps.SetContent("UA", "Req", BindingSyncTest.FORM_V2), false, false);
        assertTrue(r.text(), r.ok());
        DriverSet to = AsCodeReader.read(t);

        Plan plan = Plan.of(ModelDiff.of(from, to), to, DS, Secrets.none(), "none", null, true, t);
        String formDn = "cn=Req,cn=WorkflowRequestForms,cn=WorkflowForms,cn=AppConfig,cn=UA," + DS;
        boolean aux = false;
        String checksum = null;
        String initial = null;
        for (Plan.Step s : plan.steps) {
            if (!s.dn.equals(formDn)) {
                continue;
            }
            if (s.op == Plan.Op.AUX_CLASS) {
                aux = true;
            }
            if ("DirXML-pkgChecksum".equals(s.attr)) {
                checksum = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
            }
            if ("DirXML-pkgInitialState".equals(s.attr)) {
                initial = new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8);
            }
        }
        assertTrue("aux class step for a stamped object", aux);
        assertNotNull(checksum);
        assertFalse("content-derived, not the installed value", "536857469".equals(checksum));
        assertNotNull("baseline becomes the initial state", initial);
        assertEquals(Json.parse(BindingSyncTest.FORM_V1), Json.parse(initial));
        assertFalse(initial, initial.contains("\n"));
    }

    @Test
    public void provisioningPathsMapToDns() {
        assertEquals("cn=Help-desk Request Form,cn=WorkflowRequestForms,cn=WorkflowForms,cn=AppConfig,cn=User Application Driver," + DS,
            VaultMapping.provisioningPathDn(DS, "drivers/User Application Driver/provisioning/forms/request/Help-desk Request Form"));
        assertEquals("cn=HelpdeskTicket,cn=RequestDefs,cn=AppConfig,cn=User Application Driver," + DS,
            VaultMapping.provisioningPathDn(DS, "drivers/User Application Driver/provisioning/prds/HelpdeskTicket"));
    }
}
