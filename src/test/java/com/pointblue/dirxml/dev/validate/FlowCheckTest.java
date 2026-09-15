package com.pointblue.dirxml.dev.validate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A clean process (no findings), then one case per {@link FlowCheck} code, each a
 * minimal mutation or a bespoke minimal fixture chosen so the target code fires — a
 * fixture may also trip an unrelated code as an unavoidable side effect (e.g. an
 * unlinked activity is inherently dangling); tests only assert the code under test.
 */
public class FlowCheckTest {

    /** start &rarr; condition (true &rarr; approval user-activity, false &rarr; log) &rarr; finish; version 4.5.0. */
    private static final String CLEAN = "<process id=\"cn=Test\" version=\"4.5.0\" flow-strategy=\"SingleFlow\" formSrc=\"1\">"
        + "<display-name xml:lang=\"en\">Test Flow</display-name>"
        + "<data-items activity-id=\"approval\"><data-item data-type=\"string\" name=\"note\" source=\"'ok'\"/></data-items>"
        + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">Start</display-name></start-activity>"
        + "<condition-activity activity-id=\"cond\"><display-name xml:lang=\"en\">Check</display-name><expression>true</expression></condition-activity>"
        + "<user-activity activity-id=\"approval\" approver-type=\"group-approver\">"
        + "<display-name xml:lang=\"en\">Approve</display-name><addressee>recipient</addressee></user-activity>"
        + "<log-activity activity-id=\"log1\"><display-name xml:lang=\"en\">Log</display-name><message>'done'</message></log-activity>"
        + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"start\" target=\"cond\" type=\"forward\"/>"
        + "<link source=\"cond\" target=\"approval\" type=\"true\"/>"
        + "<link source=\"cond\" target=\"log1\" type=\"false\"/>"
        + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
        + "<link source=\"approval\" target=\"finish\" type=\"denied\"/>"
        + "<link source=\"log1\" target=\"finish\" type=\"forward\"/>"
        + "</process>";

    private static DriverSet ds(String processXml, String status) {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("UA");
        Provisioning p = new Provisioning();
        Prd prd = new Prd("P");
        prd.process = CanonicalXml.parse(processXml).getDocumentElement();
        if (status != null) {
            prd.properties.put("status", new ArrayList<>(List.of(status)));
        }
        p.prds.add(prd);
        d.provisioning = p;
        ds.drivers.add(d);
        return ds;
    }

    private static Report run(String processXml) {
        return run(processXml, null);
    }

    private static Report run(String processXml, String status) {
        Report r = new Report();
        new FlowCheck().run(ds(processXml, status), r);
        return r;
    }

    @Test
    public void cleanProcessHasNoFindings() {
        Report r = run(CLEAN);
        assertTrue(r.text(), r.findings().isEmpty());
    }

    @Test
    public void wholeValidatorReportHasNoFlowFindingsOnAClean() {
        Report r = Validator.standard().validate(ds(CLEAN, null));
        for (Finding f : r.findings()) {
            assertFalse(f.toString(), f.code.startsWith("flow-"));
        }
    }

    @Test
    public void flowVersionUnsupported() {
        Report r = run(CLEAN.replace("version=\"4.5.0\"", "version=\"9.9.9\""));
        assertEquals(1, r.withCode("flow-version-unsupported").size());
        assertEquals(Finding.Severity.ERROR, r.withCode("flow-version-unsupported").get(0).severity);
    }

    @Test
    public void flowStartMissing() {
        Report r = run("<process version=\"4.5.0\"><finish-activity activity-id=\"finish\">"
            + "<display-name xml:lang=\"en\">F</display-name></finish-activity></process>");
        assertEquals(1, r.withCode("flow-start-missing").size());
    }

    @Test
    public void flowFinishMissing() {
        Report r = run("<process version=\"4.5.0\"><start-activity activity-id=\"start\">"
            + "<display-name xml:lang=\"en\">S</display-name></start-activity></process>");
        assertEquals(1, r.withCode("flow-finish-missing").size());
    }

    @Test
    public void flowStartMultiple() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"s1\"><display-name xml:lang=\"en\">S1</display-name></start-activity>"
            + "<start-activity activity-id=\"s2\"><display-name xml:lang=\"en\">S2</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "</process>");
        assertEquals(1, r.withCode("flow-start-multiple").size());
    }

    @Test
    public void flowFinishMultiple() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"f1\"><display-name xml:lang=\"en\">F1</display-name></finish-activity>"
            + "<finish-activity activity-id=\"f2\"><display-name xml:lang=\"en\">F2</display-name></finish-activity>"
            + "</process>");
        assertEquals(1, r.withCode("flow-finish-multiple").size());
    }

    @Test
    public void flowActivityIdMissing() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<log-activity><display-name xml:lang=\"en\">L</display-name></log-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-activity-id-missing").size());
    }

    @Test
    public void flowActivityIdDuplicate() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<log-activity activity-id=\"dup\"><display-name xml:lang=\"en\">L1</display-name></log-activity>"
            + "<log-activity activity-id=\"dup\"><display-name xml:lang=\"en\">L2</display-name></log-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-activity-id-duplicate").size());
    }

    @Test
    public void flowActivityKindUnknown() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<widget-activity activity-id=\"w1\"><display-name xml:lang=\"en\">W</display-name></widget-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-activity-kind-unknown").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("flow-activity-kind-unknown").get(0).severity);
    }

    @Test
    public void flowLinkSourceUnknown() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"ghost\" target=\"finish\" type=\"forward\"/>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-link-source-unknown").size());
    }

    @Test
    public void flowLinkTargetUnknown() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"ghost\" type=\"forward\"/>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-link-target-unknown").size());
    }

    @Test
    public void flowLinkTypeInvalid() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"bogus\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-link-type-invalid").size());
    }

    @Test
    public void flowLinkTypeNotAllowed() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<log-activity activity-id=\"log1\"><display-name xml:lang=\"en\">L</display-name></log-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"log1\" type=\"forward\"/>"
            + "<link source=\"log1\" target=\"finish\" type=\"approved\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-link-type-not-allowed").size());
    }

    @Test
    public void flowConditionLinks() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<condition-activity activity-id=\"cond\"><display-name xml:lang=\"en\">C</display-name><expression>true</expression></condition-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"cond\" type=\"forward\"/>"
            + "<link source=\"cond\" target=\"finish\" type=\"true\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-condition-links").size());
    }

    @Test
    public void flowStartIncoming() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<log-activity activity-id=\"log1\"><display-name xml:lang=\"en\">L</display-name></log-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"log1\" target=\"start\" type=\"forward\"/>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-start-incoming").size());
    }

    @Test
    public void flowFinishOutgoing() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<log-activity activity-id=\"log1\"><display-name xml:lang=\"en\">L</display-name></log-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "<link source=\"finish\" target=\"log1\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-finish-outgoing").size());
    }

    @Test
    public void flowActivityDangling() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<log-activity activity-id=\"orphan\"><display-name xml:lang=\"en\">O</display-name></log-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(2, r.withCode("flow-activity-dangling").size());   // orphan: no incoming AND no outgoing
    }

    @Test
    public void flowBranchMerge() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<branch-activity activity-id=\"br\"><display-name xml:lang=\"en\">B</display-name></branch-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"br\" type=\"forward\"/>"
            + "<link source=\"br\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-branch-merge").size());
    }

    @Test
    public void flowOntimeoutLink() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<user-activity activity-id=\"approval\" ontimeout=\"timedout\" approver-type=\"group-approver\">"
            + "<display-name xml:lang=\"en\">A</display-name><addressee>recipient</addressee></user-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
            + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-ontimeout-link").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("flow-ontimeout-link").get(0).severity);
    }

    @Test
    public void flowFormBindingStart() {
        Report r = run("<process version=\"4.5.0\">"
            + "<form-binding activity-id=\"start\" form-id=\"F\"/>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-form-binding-start").size());
    }

    @Test
    public void flowDataItemsActivityUnknown() {
        Report r = run("<process version=\"4.5.0\">"
            + "<data-items activity-id=\"ghost\"><data-item data-type=\"string\" name=\"x\" source=\"'a'\"/></data-items>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-data-items-activity-unknown").size());
    }

    @Test
    public void flowDataItemsOnStart() {
        Report r = run("<process version=\"4.5.0\">"
            + "<data-items activity-id=\"start\"><data-item data-type=\"string\" name=\"x\" source=\"'a'\"/></data-items>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-data-items-on-start").size());
    }

    @Test
    public void flowRoleBinding() {
        Report r = run("<process version=\"4.5.0\" process-type=\"RBAC\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertFalse(r.withCode("flow-role-binding").isEmpty());
        assertEquals(Finding.Severity.ERROR, r.withCode("flow-role-binding").get(0).severity);
    }

    @Test
    public void flowResourceBinding() {
        Report r = run("<process version=\"4.5.0\" process-type=\"Resource\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertFalse(r.withCode("flow-resource-binding").isEmpty());
    }

    @Test
    public void roleBindingSatisfiedByBindActivitiesIsClean() {
        Report r = run("<process version=\"4.5.0\" process-type=\"RBAC\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<user-activity activity-id=\"approval\" approver-type=\"group-approver\">"
            + "<display-name xml:lang=\"en\">A</display-name><addressee>recipient</addressee></user-activity>"
            + "<bind-role-activity activity-id=\"bindA\" action=\"APPROVED\"><display-name xml:lang=\"en\">BA</display-name></bind-role-activity>"
            + "<bind-role-activity activity-id=\"bindD\" action=\"DENIED\"><display-name xml:lang=\"en\">BD</display-name></bind-role-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
            + "<link source=\"approval\" target=\"bindA\" type=\"approved\"/>"
            + "<link source=\"approval\" target=\"bindD\" type=\"denied\"/>"
            + "<link source=\"bindA\" target=\"finish\" type=\"forward\"/>"
            + "<link source=\"bindD\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertTrue(r.text(), r.withCode("flow-role-binding").isEmpty());
    }

    @Test
    public void flowAddresseeMissing() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<user-activity activity-id=\"approval\" approver-type=\"group-approver\"><display-name xml:lang=\"en\">A</display-name></user-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
            + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-addressee-missing").size());
    }

    @Test
    public void flowFlowdataExpression() {
        Report r = run("<process version=\"4.5.0\">"
            + "<data-items activity-id=\"m1\"><data-item data-type=\"string\" name=\"x\" source=\"flowdata.foo\"/></data-items>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<mapping-activity activity-id=\"m1\"><display-name xml:lang=\"en\">M</display-name></mapping-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"m1\" type=\"forward\"/>"
            + "<link source=\"m1\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-flowdata-expression").size());
    }

    @Test
    public void flowApproverTypeInvalid() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<user-activity activity-id=\"approval\" approver-type=\"bogus\">"
            + "<display-name xml:lang=\"en\">A</display-name><addressee>recipient</addressee></user-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
            + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-approver-type-invalid").size());
    }

    @Test
    public void flowApproverConditionBoth() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<user-activity activity-id=\"approval\" approver-type=\"group-approver\" approver-condition=\"x\" approver-condition-expr=\"y\">"
            + "<display-name xml:lang=\"en\">A</display-name><addressee>recipient</addressee></user-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
            + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-approver-condition-both").size());
    }

    @Test
    public void flowApproverTargetItems() {
        Report r = run("<process version=\"4.5.0\">"
            + "<data-items activity-id=\"approval\"><data-item data-type=\"string\" name=\"x\" source=\"'a'\" target=\"flowdata.x\"/></data-items>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<user-activity activity-id=\"approval\" approver-type=\"quorum-approver\">"
            + "<display-name xml:lang=\"en\">A</display-name><addressee>recipient</addressee></user-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
            + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-approver-target-items").size());
    }

    @Test
    public void flowEmailTemplateMissing() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name><notify template=\"\"/></finish-activity>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-email-template-missing").size());
    }

    @Test
    public void flowAttributeEnum() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<provision-activity activity-id=\"prov\" category=\"bogus\" operation=\"grant\"><display-name xml:lang=\"en\">P</display-name></provision-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"prov\" type=\"forward\"/>"
            + "<link source=\"prov\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-attribute-enum").size());
    }

    @Test
    public void flowExpressionSyntax() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<condition-activity activity-id=\"cond\"><display-name xml:lang=\"en\">C</display-name><expression>true &amp;&amp;</expression></condition-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"cond\" type=\"forward\"/>"
            + "<link source=\"cond\" target=\"finish\" type=\"true\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-expression-syntax").size());
    }

    @Test
    public void flowPlaceholderIsWarningWhenActive() {
        Report r = run("<process version=\"4.5.0\">"
            + "<data-items activity-id=\"prov\"><data-item data-type=\"string\" name=\"x\" source=\"'{enter Entitlement DN here}'\"/></data-items>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<mapping-activity activity-id=\"prov\"><display-name xml:lang=\"en\">P</display-name></mapping-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"prov\" type=\"forward\"/>"
            + "<link source=\"prov\" target=\"finish\" type=\"forward\"/>"
            + "</process>", "Active");
        assertEquals(1, r.withCode("flow-placeholder").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("flow-placeholder").get(0).severity);
    }

    @Test
    public void flowPlaceholderIsInfoWhenNotActive() {
        Report r = run("<process version=\"4.5.0\">"
            + "<data-items activity-id=\"prov\"><data-item data-type=\"string\" name=\"x\" source=\"'{enter Entitlement DN here}'\"/></data-items>"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<mapping-activity activity-id=\"prov\"><display-name xml:lang=\"en\">P</display-name></mapping-activity>"
            + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">F</display-name></finish-activity>"
            + "<link source=\"start\" target=\"prov\" type=\"forward\"/>"
            + "<link source=\"prov\" target=\"finish\" type=\"forward\"/>"
            + "</process>", "Template");
        assertEquals(1, r.withCode("flow-placeholder").size());
        assertEquals(Finding.Severity.INFO, r.withCode("flow-placeholder").get(0).severity);
    }

    @Test
    public void flowDisplayNameMissing() {
        Report r = run("<process version=\"4.5.0\">"
            + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">S</display-name></start-activity>"
            + "<finish-activity activity-id=\"finish\"/>"
            + "<link source=\"start\" target=\"finish\" type=\"forward\"/>"
            + "</process>");
        assertEquals(1, r.withCode("flow-display-name-missing").size());
        assertEquals(Finding.Severity.WARNING, r.withCode("flow-display-name-missing").get(0).severity);
    }
}
