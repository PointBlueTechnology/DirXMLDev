package com.pointblue.dirxml.dev.flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.util.List;
import org.junit.Test;

/**
 * A start &rarr; user-activity &rarr; log &rarr; finish chain with data items and a
 * form binding, built from an XML string (as {@code BindingSyncTest} does), asserting
 * everything {@link Flow} reads off it.
 */
public class FlowTest {

    private static final String XML =
        "<process xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
        + " id=\"cn=Sample,cn=RequestDefs\" version=\"4.5.0\" setnotify=\"false\" formSrc=\"1\""
        + " flow-strategy=\"SingleFlow\" xsi:noNamespaceSchemaLocation=\"ApprovalProcess3_6_1.xsd\">"
        + "<display-name xml:lang=\"en\">Sample Flow</display-name>"
        + "<form-binding activity-id=\"approval\" form-id=\"Approval Form\"/>"
        + "<data-items activity-id=\"approval\">"
        + "<data-item data-type=\"string\" name=\"comment\" source=\"flowdata.get('start/Req/comment')\" target-type=\"single-value\"/>"
        + "</data-items>"
        + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">Start</display-name></start-activity>"
        + "<user-activity activity-id=\"approval\" approver-type=\"group-approver\" timeout=\"691200000\">"
        + "<display-name xml:lang=\"en\">Approve</display-name>"
        + "<addressee value=\"IDVault.get(recipient,'user','manager')\"/>"
        + "</user-activity>"
        + "<log-activity activity-id=\"log1\" audit=\"false\">"
        + "<display-name xml:lang=\"en\">Log</display-name>"
        + "<message>'done'</message>"
        + "</log-activity>"
        + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
        + "<link source=\"approval\" target=\"log1\" type=\"approved\"/>"
        + "<link source=\"approval\" target=\"finish\" type=\"denied\"/>"
        + "<link source=\"log1\" target=\"finish\" type=\"forward\"/>"
        + "</process>";

    private static Prd prd() {
        Prd p = new Prd("Sample");
        p.process = CanonicalXml.parse(XML).getDocumentElement();
        return p;
    }

    @Test
    public void ofReturnsNullForAPrdWithNoProcess() {
        assertNull(Flow.of(new Prd("Classic")));
        assertNull(Flow.of(null));
    }

    @Test
    public void processAttributes() {
        Flow flow = Flow.of(prd());
        assertNotNull(flow);
        assertEquals("cn=Sample,cn=RequestDefs", flow.id);
        assertEquals("4.5.0", flow.version);
        assertEquals("Normal", flow.processType);   // absent on the DOM -> engine default
        assertEquals("SingleFlow", flow.flowStrategy);
        assertEquals("false", flow.setnotify);
        assertEquals("1", flow.formSrc);
        assertNull(flow.restrictView);
        assertNull(flow.generateComments);
        assertNull(flow.defaultCompletedApprovalStatus);
    }

    @Test
    public void activitiesInDocumentOrderWithKinds() {
        Flow flow = Flow.of(prd());
        assertEquals(4, flow.activities.size());
        assertEquals(List.of(Flow.Kind.START, Flow.Kind.USER, Flow.Kind.LOG, Flow.Kind.FINISH),
            flow.activities.stream().map(a -> a.kind).toList());
        assertEquals(List.of("start", "approval", "log1", "finish"),
            flow.activities.stream().map(a -> a.id).toList());
    }

    @Test
    public void byIdStartFinish() {
        Flow flow = Flow.of(prd());
        assertEquals(Flow.Kind.USER, flow.byId("approval").kind);
        assertNull(flow.byId("nope"));
        assertEquals("start", flow.start().id);
        assertEquals("finish", flow.finish().id);
    }

    @Test
    public void incomingAndOutgoing() {
        Flow flow = Flow.of(prd());
        assertEquals(0, flow.incoming("start").size());
        assertEquals(1, flow.outgoing("start").size());
        assertEquals(2, flow.incoming("finish").size());
        assertEquals(0, flow.outgoing("finish").size());
        assertEquals(4, flow.links.size());
        assertEquals("start --forward--> approval", flow.outgoing("start").get(0).toString());
    }

    @Test
    public void dataItemsByActivity() {
        Flow flow = Flow.of(prd());
        List<Flow.DataItem> items = flow.dataItemsByActivity.get("approval");
        assertEquals(1, items.size());
        assertEquals("comment", items.get(0).name);
        assertEquals("string", items.get(0).dataType);
        assertEquals("flowdata.get('start/Req/comment')", items.get(0).source);
        assertEquals("single-value", items.get(0).targetType);
        assertNull(flow.dataItemsByActivity.get("log1"));
    }

    @Test
    public void formBindings() {
        Flow flow = Flow.of(prd());
        assertEquals(1, flow.formBindings.size());
        assertEquals("approval", flow.formBindings.get(0).activityId);
        assertEquals("Approval Form", flow.formBindings.get(0).formId);
    }

    @Test
    public void displayNameFallback() {
        Flow flow = Flow.of(prd());
        Flow.Activity approval = flow.byId("approval");
        assertEquals("Approve", approval.displayName("en"));
        assertEquals("Approve", approval.displayName("fr"));   // no fr -> falls back to en
        assertEquals(1, approval.displayNameElements().size());
    }

    @Test
    public void activityAttr() {
        Flow flow = Flow.of(prd());
        Flow.Activity approval = flow.byId("approval");
        assertEquals("691200000", approval.attr("timeout"));
        assertEquals("group-approver", approval.attr("approver-type"));
        assertNull(approval.attr("ontimeout"));
    }

    @Test
    public void kindByElement() {
        assertEquals(Flow.Kind.USER, Flow.Kind.byElement("user-activity"));
        assertEquals(Flow.Kind.START, Flow.Kind.byElement("start-activity"));
        assertEquals(Flow.Kind.FINISH, Flow.Kind.byElement("finish-activity"));
        assertEquals(Flow.Kind.UNKNOWN, Flow.Kind.byElement("bogus-activity"));
        assertTrue(Flow.Kind.CONDITION.restrictsOutgoingLinkTypes());
        assertEquals(java.util.Set.of("true", "false", "error"), Flow.Kind.CONDITION.allowedOutgoingLinkTypes());
    }
}
