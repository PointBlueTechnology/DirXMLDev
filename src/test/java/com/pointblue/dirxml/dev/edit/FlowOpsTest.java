package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.flow.Flow;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

/**
 * Track W step W2: {@link FlowOps}'s typed operations on a PRD's {@code <process>}.
 * The fixture ({@link #tree}) is a {@code NoApproval}-shaped PRD (start &rarr; prov &rarr;
 * finish, docs/workflows.md &sect;4 W1) built the way {@code FormOpsTest}'s fixture is —
 * an in-memory model written once with {@link AsCodeWriter}, then edited through real
 * {@link Transaction}s so every assertion here is on what {@code validate} and {@link
 * Flow#of} see after a real load → apply → validate → write cycle.
 */
public class FlowOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String PROCESS_XML =
        "<process xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
        + " id=\"cn=P,cn=RequestDefs,cn=AppConfig,cn=UA,cn=driverset1,o=system\" version=\"4.5.0\" formSrc=\"1\""
        + " xsi:noNamespaceSchemaLocation=\"ApprovalProcess3_6_1.xsd\">"
        + "<display-name xml:lang=\"en\">No Approval</display-name>"
        + "<data-items activity-id=\"prov\">"
        + "<data-item data-type=\"string\" name=\"dn\" source=\"recipient\"/>"
        + "<data-item data-type=\"string\" name=\"DirXML-Entitlement-DN\" source=\"'cn=Ent,ou=x,o=data'\"/>"
        + "<data-item data-type=\"string\" name=\"DirXML-Entitlement-Action\" source=\"'1'\"/>"
        + "<data-item data-type=\"string\" name=\"DirXML-Entitlement-Parameter\" source=\"''\"/>"
        + "<data-item data-type=\"boolean\" name=\"DirXML-Entitlement-MultiValueAllowed\" source=\"'true'\"/>"
        + "</data-items>"
        + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">Start</display-name></start-activity>"
        + "<provision-activity activity-id=\"prov\" category=\"entitlement\" entity-type=\"sys-entitlement-request\" operation=\"grant\">"
        + "<display-name xml:lang=\"en\">Entitlement</display-name></provision-activity>"
        + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"start\" target=\"prov\" type=\"forward\"/>"
        + "<link source=\"prov\" target=\"finish\" type=\"forward\"/>"
        + "</process>";

    static Element el(String xml) {
        return CanonicalXml.parse(xml).getDocumentElement();
    }

    private static int treeCounter = 0;

    /** A NoApproval-shaped PRD "P" (start -> prov -> finish) on one UA driver, no forms. */
    public static Path tree(TemporaryFolder tmp) throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";
        Driver ua = new Driver("UA");
        ua.dn = "cn=UA,cn=driverset1,o=system";
        Provisioning p = new Provisioning();
        p.dn = "cn=AppConfig," + ua.dn;
        Prd prd = new Prd("P");
        prd.definition = el("<prov-req-defn status=\"Active\">" + PROCESS_XML + "</prov-req-defn>");
        prd.process = com.pointblue.dirxml.sim.Xds.childrenByName(prd.definition, "process").get(0);
        prd.properties.put("status", List.of("Active"));
        p.prds.add(prd);
        ua.provisioning = p;
        ds.drivers.add(ua);
        Path t = tmp.newFolder("tree" + (treeCounter++)).toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    private static Flow flowOf(Path t) throws Exception {
        Prd prd = AsCodeReader.read(t).drivers.get(0).provisioning.prd("P");
        return Flow.of(prd);
    }

    private static void assertNoFlowErrors(Report r) {
        for (Finding f : r.of(Finding.Severity.ERROR)) {
            assertFalse("unexpected flow error: " + f, f.code.startsWith("flow-"));
        }
    }

    // ---- 1: add approval after start on a linear flow -----------------------------------------

    @Test
    public void addApprovalAfterStart() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r.text() + "\n" + r.report.text(), r.ok());
        assertNoFlowErrors(r.report);

        Flow flow = flowOf(t);
        Flow.Activity appr = flow.byId("appr");
        assertNotNull(appr);
        assertEquals(Flow.Kind.USER, appr.kind);
        assertEquals("[start --forward--> appr]", flow.outgoing("start").toString());
        assertEquals("[appr --approved--> prov]", linksOfType(flow, "appr", "approved"));
        assertEquals("[appr --denied--> status_denied]", linksOfType(flow, "appr", "denied"));
        assertEquals("[status_denied --forward--> finish]", flow.outgoing("status_denied").toString());
        assertEquals(Flow.Kind.MAPPING, flow.byId("status_denied").kind);
        assertEquals("Workflow Status Denied", flow.byId("status_denied").displayName("en"));
        Flow.DataItem status = flow.dataItemsByActivity.get("status_denied").get(0);
        assertEquals("approvalstatus", status.name);
        assertEquals("'denied'", status.source);
        assertEquals(FlowOps.STATUS_TARGET, status.target);
        assertTrue(r.text(), r.text().contains("new status mapping 'status_denied'"));
        assertEquals(FlowOps.APPROVAL_DEFAULT_ADDRESSEE, appr.element.getElementsByTagName("addressee").item(0).getTextContent());
        assertEquals(String.valueOf(FlowOps.APPROVAL_DEFAULT_TIMEOUT_MS), appr.attr("timeout"));
        assertEquals("denied", appr.attr("ontimeout"));
        assertTrue(appr.element.getElementsByTagName("notify").getLength() > 0);
        assertTrue(appr.element.getElementsByTagName("retry").getLength() > 0);
        assertTrue(flow.dataItemsByActivity.containsKey("appr"));
        assertTrue(flow.dataItemsByActivity.get("appr").isEmpty());
    }

    private static String linksOfType(Flow flow, String from, String type) {
        List<Flow.Link> out = new java.util.ArrayList<>();
        for (Flow.Link l : flow.outgoing(from)) {
            if (type.equals(l.type)) {
                out.add(l);
            }
        }
        return out.toString();
    }

    // ---- 2: condition with --on-false; log; provision with --entitlement-dn; mapping ------------

    @Test
    public void addConditionWithOnFalse() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "condition", "cond", "start", null, null,
            null, "finish", null, null, null, null, "flowdata.get('reason') != null", null, null, null, null), false, false);
        assertTrue(r.text(), r.ok());
        assertNoFlowErrors(r.report);
        Flow flow = flowOf(t);
        assertEquals(Flow.Kind.CONDITION, flow.byId("cond").kind);
        assertEquals("[cond --true--> prov]", linksOfType(flow, "cond", "true"));
        assertEquals("[cond --false--> finish]", linksOfType(flow, "cond", "false"));
    }

    @Test
    public void addLogActivity() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok());
        assertNoFlowErrors(r.report);
        Flow flow = flowOf(t);
        Flow.Activity log = flow.byId("log1");
        assertEquals(Flow.Kind.LOG, log.kind);
        assertEquals("initiator", log.element.getElementsByTagName("author").item(0).getTextContent());
        assertEquals("'Activity log1'", log.element.getElementsByTagName("message").item(0).getTextContent());
    }

    @Test
    public void addProvisionWithEntitlementDn() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "provision", "prov2", "start", null, null,
            null, null, null, null, null, null, null, null, null, "cn=NewEnt,ou=x,o=data", null), false, false);
        assertTrue(r.text(), r.ok());
        assertNoFlowErrors(r.report);
        for (Finding f : r.report.of(Finding.Severity.WARNING)) {
            assertFalse("unexpected placeholder: " + f, "flow-placeholder".equals(f.code));
        }
        Flow flow = flowOf(t);
        List<Flow.DataItem> items = flow.dataItemsByActivity.get("prov2");
        assertNotNull(items);
        assertEquals(5, items.size());
        boolean foundDn = false;
        for (Flow.DataItem di : items) {
            if (di.name.equals("DirXML-Entitlement-DN")) {
                assertEquals("'cn=NewEnt,ou=x,o=data'", di.source);
                foundDn = true;
            }
        }
        assertTrue(foundDn);
    }

    @Test
    public void addMappingActivity() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "mapping", "map1", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok());
        assertNoFlowErrors(r.report);
        Flow flow = flowOf(t);
        assertEquals(Flow.Kind.MAPPING, flow.byId("map1").kind);
        assertTrue(flow.dataItemsByActivity.containsKey("map1"));
        assertTrue(flow.dataItemsByActivity.get("map1").isEmpty());
    }

    // ---- 3: --after with multiple outgoing links and no --via -> refusal naming the types --------

    @Test
    public void afterWithMultipleOutgoingLinksRequiresVia() throws Exception {
        Path t = tree(tmp);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "condition", "cond", "start", null, null,
            null, "finish", null, null, null, null, "true", null, null, null, null), false, false);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "cond", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("2 outgoing links"));
        assertTrue(r.refusal, r.refusal.contains("true"));
        assertTrue(r.refusal, r.refusal.contains("false"));

        Result r2 = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "cond", "true", null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r2.text(), r2.ok());
    }

    // ---- 4: branch.add + two legs -----------------------------------------------------------------

    @Test
    public void branchAddPlusTwoLegs() throws Exception {
        Path t = tree(tmp);
        Result r1 = Transaction.open(t).run(new FlowOps.BranchAdd(null, "P", "branch", "merge", "start", null), false, false);
        assertTrue(r1.text(), r1.ok());
        assertNoFlowErrors(r1.report);

        Result r2 = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "leg1", "branch", null, "merge",
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r2.text(), r2.ok());
        Result r3 = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "leg2", "branch", null, "merge",
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r3.text(), r3.ok());
        assertNoFlowErrors(r3.report);
        for (Finding f : r3.report.of(Finding.Severity.ERROR)) {
            assertFalse("flow-branch-merge finding present: " + f, "flow-branch-merge".equals(f.code));
        }

        Flow flow = flowOf(t);
        assertEquals(Flow.Kind.BRANCH, flow.byId("branch").kind);
        assertEquals(Flow.Kind.MERGE, flow.byId("merge").kind);
        assertEquals("branch", flow.byId("merge").attr("branch-activity-id"));
        assertEquals(3, flow.outgoing("branch").size());   // direct leg + leg1 + leg2
        for (Flow.Link l : flow.outgoing("branch")) {
            assertEquals("forward", l.type);
        }
    }

    // ---- 5: remove reconnects; remove of start refused ---------------------------------------------

    @Test
    public void removeReconnectsIncomingLinks() throws Exception {
        Path t = tree(tmp);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        Result r = Transaction.open(t).run(new FlowOps.ActivityRemove(null, "P", "log1"), false, false);
        assertTrue(r.text(), r.ok());
        assertNoFlowErrors(r.report);
        Flow flow = flowOf(t);
        assertNull(flow.byId("log1"));
        assertEquals("[start --forward--> prov]", flow.outgoing("start").toString());
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.START) {
                assertFalse("dangling incoming on " + a.id, flow.incoming(a.id).isEmpty());
            }
            if (a.kind != Flow.Kind.FINISH) {
                assertFalse("dangling outgoing on " + a.id, flow.outgoing(a.id).isEmpty());
            }
        }
    }

    @Test
    public void removeOfStartRefused() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityRemove(null, "P", "start"), false, false);
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("start"));
    }

    // ---- 6: rename rewrites links, data-items, form-binding and an expression --------------------

    @Test
    public void renameRewritesEverything() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityRename(null, "P", "prov", "granting"), false, false);
        assertTrue(r.text(), r.ok());
        Flow flow = flowOf(t);
        assertNull(flow.byId("prov"));
        assertNotNull(flow.byId("granting"));
        assertEquals("[start --forward--> granting]", flow.outgoing("start").toString());
        assertEquals("[granting --forward--> finish]", flow.outgoing("granting").toString());
        assertTrue(flow.dataItemsByActivity.containsKey("granting"));
        assertFalse(flow.dataItemsByActivity.containsKey("prov"));

        // an expression reference ('prov/' path and 'prov.' info-object) is rewritten too
        Path t2 = tree(tmp);
        Transaction tx = Transaction.open(t2);
        Result added = tx.run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "start", null, null,
            null, null, null, null, null, null, null,
            "'seen ' + flowdata.get('prov/x') + prov.getName()", null, null, null), false, false);
        assertTrue(added.text(), added.ok());
        Result renamed = Transaction.open(t2).run(new FlowOps.ActivityRename(null, "P", "prov", "granting"), false, false);
        assertTrue(renamed.text(), renamed.ok());
        Prd prd2 = AsCodeReader.read(t2).drivers.get(0).provisioning.prd("P");
        Flow flow2 = Flow.of(prd2);
        String msg = flow2.byId("log1").element.getElementsByTagName("message").item(0).getTextContent();
        assertTrue(msg, msg.contains("flowdata.get('granting/x')"));
        assertTrue(msg, msg.contains("granting.getName()"));
        assertFalse(msg, msg.contains("prov"));
    }

    // ---- 7: link.retype to a disallowed type is refused; link.add passes ---------------------------

    @Test
    public void linkRetypeToDisallowedTypeRefused() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.LinkRetype(null, "P", "prov", "finish", "forward", "approved"), false, false);
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("provision-activity"));
    }

    @Test
    public void linkAddCreatingDanglingFreeGraphPasses() throws Exception {
        Path t = tree(tmp);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        // log1 already forwards to finish; add an extra "error" link (allowed for a log-activity's
        // engine check, since only forward is enforced there — flow-link-type-not-allowed only fires
        // for kinds that restrict outgoing types, and log-activity does not) — still dangling-free.
        Result r = Transaction.open(t).run(new FlowOps.LinkAdd(null, "P", "start", "finish", "error"), false, false);
        assertTrue(r.text(), r.ok());
        assertNoFlowErrors(r.report);
        Flow flow = flowOf(t);
        assertEquals(2, flow.outgoing("start").size());
    }

    // ---- 8: set enum violation refused; --attr activity-id refused ---------------------------------

    @Test
    public void setEnumViolationRefused() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivitySet(null, "P", "prov", null, null, null, null, null,
            null, null, null, null, null, null), false, false);
        assertFalse(r.ok());   // nothing given

        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        Result bad = Transaction.open(t).run(new FlowOps.ActivitySet(null, "P", "appr", null, null, null, null,
            "not-a-status", null, null, null, null, null, null), false, false);
        assertFalse(bad.ok());
        assertTrue(bad.refusal, bad.refusal.contains("ontimeout"));
    }

    @Test
    public void setAttrActivityIdRefused() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivitySet(null, "P", "prov", null, List.of("activity-id=x"),
            null, null, null, null, null, null, null, null, null), false, false);
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("flow.activity.rename"));
    }

    // ---- 9: data.set/remove; flow.set version -------------------------------------------------------

    @Test
    public void dataSetAndRemove() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.DataSet(null, "P", "prov", "extra",
            "flowdata.get('start/Req/extra')", null, "string", null), false, false);
        assertTrue(r.text(), r.ok());
        Flow flow = flowOf(t);
        boolean found = false;
        for (Flow.DataItem di : flow.dataItemsByActivity.get("prov")) {
            if (di.name.equals("extra")) {
                found = true;
                assertEquals("flowdata.get('start/Req/extra')", di.source);
            }
        }
        assertTrue(found);

        Result r2 = Transaction.open(t).run(new FlowOps.DataRemove(null, "P", "prov", "extra"), false, false);
        assertTrue(r2.text(), r2.ok());
        Flow flow2 = flowOf(t);
        for (Flow.DataItem di : flow2.dataItemsByActivity.get("prov")) {
            assertFalse(di.name.equals("extra"));
        }
    }

    @Test
    public void flowSetVersion() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.SetProcess(null, "P", "4.0.1", null, null, null, null, null, null),
            false, false);
        assertTrue(r.text(), r.ok());
        Flow flow = flowOf(t);
        assertEquals("4.0.1", flow.version);

        Result bad = Transaction.open(t).run(new FlowOps.SetProcess(null, "P", "9.9.9", null, null, null, null, null, null),
            false, false);
        assertFalse(bad.ok());
    }

    // ---- 10: end-to-end W4-shaped flow -----------------------------------------------------------

    @Test
    public void endToEndFlowBuiltFromNoApprovalShape() throws Exception {
        Path t = tree(tmp);
        Transaction.open(t).run(new FlowOps.ActivityRemove(null, "P", "prov"), false, false);

        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "condition", "cond", "start", null, null,
            null, "finish", null, null, null, null, "flowdata.get('reason') != null", null, null, null, null), false, false);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "approval_1", "cond", "true", null,
            null, null, null, "'cn=uaadmin,ou=sa,o=data'", null, null, null, null, null, null, null), false, false);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "approval_2", "approval_1", "approved", null,
            null, null, null, "'cn=uaadmin,ou=sa,o=data'", null, null, null, null, null, null, null), false, false);
        Result last = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "log1", "approval_2", "approved", null,
            null, null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(last.text(), last.ok());

        Report report = last.report;
        int flowErrors = 0;
        int placeholders = 0;
        for (Finding f : report.findings()) {
            if (f.code.startsWith("flow-") && f.severity == Finding.Severity.ERROR) {
                flowErrors++;
            }
            if (f.code.equals("flow-placeholder")) {
                placeholders++;
            }
        }
        assertEquals(report.text(), 0, flowErrors);
        assertEquals(report.text(), 0, placeholders);

        Flow flow = flowOf(t);
        assertNotNull(flow.byId("cond"));
        assertNotNull(flow.byId("approval_1"));
        assertNotNull(flow.byId("approval_2"));
        assertNotNull(flow.byId("log1"));
        assertEquals("[log1 --forward--> finish]", linksOfType(flow, "log1", "forward"));
    }

    // ---- approval form binding (dashboard needs one to open the task) --------------------------

    private static final String APPROVAL_FORM_JSON = "{\"components\":["
        + "{\"key\":\"title\",\"type\":\"title\",\"label\":\"Title\"},"
        + "{\"key\":\"initiator\",\"type\":\"dn_display\",\"label\":\"Initiator\"},"
        + "{\"key\":\"recipient\",\"type\":\"dn_display\",\"label\":\"Recipient\"},"
        + "{\"key\":\"reason\",\"type\":\"labelelement\",\"label\":\"Reason\"},"
        + "{\"key\":\"requestDate\",\"type\":\"datetime\",\"label\":\"Date\"},"
        + "{\"key\":\"extra\",\"type\":\"textfield\",\"label\":\"Extra\"},"
        + "{\"key\":\"apwaComment\",\"type\":\"textarea\",\"label\":\"Comment\"},"
        + "{\"key\":\"approve\",\"type\":\"button\",\"label\":\"Approve\"}"
        + "],\"title\":\"Approval Form\",\"display\":\"form\",\"inlinescripts\":\"\",\"localization\":{},\"externalScripts\":[]}";

    private static final String REQUEST_FORM_JSON = "{\"components\":["
        + "{\"key\":\"reason\",\"type\":\"textfield\",\"label\":\"Reason\"},"
        + "{\"key\":\"submit\",\"type\":\"button\",\"label\":\"Submit\"}"
        + "],\"title\":\"Req\",\"display\":\"form\",\"inlinescripts\":\"\",\"localization\":{},\"externalScripts\":[]}";

    /** {@link #tree} plus the stock "Approval Form" and a request form "Req" (field reason) bound to the PRD. */
    private static Path treeWithForms(TemporaryFolder tmp) throws Exception {
        Path t = tree(tmp);
        DriverSet ds = AsCodeReader.read(t);
        Provisioning p = ds.drivers.get(0).provisioning;
        p.forms.add(new Form(Form.Kind.APPROVAL, "Approval Form", APPROVAL_FORM_JSON));
        p.forms.add(new Form(Form.Kind.REQUEST, "Req", REQUEST_FORM_JSON));
        Prd prd = p.prd("P");
        prd.request = el("<provision-request formSrc=\"1\"><form-binding form-id=\"Req\"><content>"
            + "<field data-type=\"string\" name=\"reason\"><control control-type=\"textfield\"/></field></content></form-binding>"
            + "<request-data-items><data-item data-type=\"string\" name=\"reason\" target=\"flowdata.start/Req/reason\" target-type=\"single-value\"/></request-data-items>"
            + "</provision-request>");
        Path t2 = tmp.newFolder("treeforms" + (treeCounter++)).toPath();
        AsCodeWriter.write(ds, t2);
        return t2;
    }

    private static Prd prdOf(Path t) throws Exception {
        return AsCodeReader.read(t).drivers.get(0).provisioning.prd("P");
    }

    private static String sourceOf(Flow flow, String activity, String name) {
        for (Flow.DataItem d : flow.dataItemsByActivity.getOrDefault(activity, List.of())) {
            if (d.name.equals(name)) {
                return d.source == null ? "<none>" : d.source;
            }
        }
        return null;
    }

    @Test
    public void addApprovalBindsStockApprovalFormByDefault() throws Exception {
        Path t = treeWithForms(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            null, null, null, "recipient", null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok() && r.written);
        assertTrue(r.text(), r.text().contains("bound approval form 'Approval Form'"));
        Prd prd = prdOf(t);
        Flow flow = Flow.of(prd);
        assertEquals(1, com.pointblue.dirxml.sim.Xds.childrenByName(prd.process, "form").size());
        assertEquals("Approval Form", com.pointblue.dirxml.sim.Xds.childrenByName(prd.process, "form").get(0).getAttribute("form-id"));
        assertEquals(1, flow.formBindings.size());
        assertEquals("appr", flow.formBindings.get(0).activityId);
        assertEquals("Approval Form", flow.formBindings.get(0).formId);
        assertEquals("appr.getName(locale)", sourceOf(flow, "appr", "title"));
        assertEquals("recipient", sourceOf(flow, "appr", "recipient"));
        assertEquals("initiator", sourceOf(flow, "appr", "initiator"));
        assertEquals("process.getTimestamp()", sourceOf(flow, "appr", "requestDate"));
        assertEquals("flowdata.get('start/Req/reason')", sourceOf(flow, "appr", "reason"));
        assertNull(sourceOf(flow, "appr", "apwaComment"));   // the comment field is never a JSON-form data item
        assertNull(sourceOf(flow, "appr", "extra"));
        assertTrue(r.text(), r.text().contains("unmapped [extra]"));
        assertNull(sourceOf(flow, "appr", "approve"));
        assertNoFlowErrors(r.report);
    }

    @Test
    public void addApprovalWithoutAnApprovalFormNotes() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            null, null, null, "recipient", null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok() && r.written);
        assertTrue(r.text(), r.text().contains("no approval form bound"));
        assertTrue(Flow.of(prdOf(t)).formBindings.isEmpty());
    }

    @Test
    public void setFormRebindsAndRefusesUnknownOrWrongKind() throws Exception {
        Path t = treeWithForms(tmp);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            null, null, null, "recipient", null, null, null, null, null, null, null), false, false);
        Result again = Transaction.open(t).run(new FlowOps.ActivitySet(null, "P", "appr", null, null, null, null, null,
            null, null, null, null, null, null, "Approval Form"), false, false);
        assertTrue(again.text(), again.ok() && again.written);
        Prd prd = prdOf(t);
        assertEquals(1, com.pointblue.dirxml.sim.Xds.childrenByName(prd.process, "form").size());
        assertEquals(1, Flow.of(prd).formBindings.size());
        Result unknown = Transaction.open(t).run(new FlowOps.ActivitySet(null, "P", "appr", null, null, null, null, null,
            null, null, null, null, null, null, "Nope"), false, false);
        assertFalse(unknown.text(), unknown.ok());
        assertTrue(unknown.text(), unknown.text().contains("approval form 'Nope' not found"));
        Result wrongKind = Transaction.open(t).run(new FlowOps.ActivitySet(null, "P", "prov", null, null, null, null, null,
            null, null, null, null, null, null, "Approval Form"), false, false);
        assertFalse(wrongKind.text(), wrongKind.ok());
        assertTrue(wrongKind.text(), wrongKind.text().contains("--form only applies to an approval"));
    }

    // ---- denied path status (spike W4 follow-up) ---------------------------------------------

    @Test
    public void secondApprovalReusesTheDeniedStatusMapping() throws Exception {
        Path t = tree(tmp);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "a1", "start", null, null,
            null, null, null, "recipient", null, null, null, null, null, null, null), false, false);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "a2", "a1", "approved", null,
            null, null, null, "recipient", null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok() && r.written);
        Flow flow = flowOf(t);
        assertEquals("[a2 --denied--> status_denied]", linksOfType(flow, "a2", "denied"));
        assertEquals(1, flow.activities.stream().filter(a -> a.kind == Flow.Kind.MAPPING).count());
        assertTrue(r.text(), r.text().contains("denied → 'status_denied'"));
        assertNoFlowErrors(r.report);
    }

    @Test
    public void explicitOnDeniedSkipsTheStatusMapping() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            "finish", null, null, "recipient", null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok() && r.written);
        Flow flow = flowOf(t);
        assertEquals("[appr --denied--> finish]", linksOfType(flow, "appr", "denied"));
        assertNull(flow.byId("status_denied"));
    }

    @Test
    public void mappingWithStatusSetsTheCompletedStatus() throws Exception {
        Path t = tree(tmp);
        Result r = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "mapping", "ok", "start", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, "approved"), false, false);
        assertTrue(r.text(), r.ok() && r.written);
        Flow flow = flowOf(t);
        assertEquals("Workflow Status Approved", flow.byId("ok").displayName("en"));
        Flow.DataItem d = flow.dataItemsByActivity.get("ok").get(0);
        assertEquals("'approved'", d.source);
        assertEquals(FlowOps.STATUS_TARGET, d.target);
        assertEquals("ok", FlowOps.findStatusMapping(flow, "approved"));
        Result bad = Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "log", "l", "ok", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, "denied"), false, false);
        assertFalse(bad.text(), bad.ok());
        assertTrue(bad.text(), bad.text().contains("--status only applies to a mapping"));
    }

    @Test
    public void removingTheLastApprovalDropsTheOrphanedStatusMapping() throws Exception {
        Path t = tree(tmp);
        Transaction.open(t).run(new FlowOps.ActivityAdd(null, "P", "approval", "appr", "start", null, null,
            null, null, null, "recipient", null, null, null, null, null, null, null), false, false);
        Result r = Transaction.open(t).run(new FlowOps.ActivityRemove(null, "P", "appr"), false, false);
        assertTrue(r.text(), r.ok() && r.written);
        Flow flow = flowOf(t);
        assertNull(flow.byId("status_denied"));
        assertTrue(r.text(), r.text().contains("[status_denied]"));
        assertNoFlowErrors(r.report);
        assertEquals("[start --forward--> prov]", flow.outgoing("start").toString());
    }
}
