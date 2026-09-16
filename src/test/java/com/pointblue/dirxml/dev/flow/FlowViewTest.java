package com.pointblue.dirxml.dev.flow;

import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;

/**
 * {@link FlowView#text} and {@link FlowView#mermaid} against a small fixture: start
 * &rarr; approval &rarr; finish, plus an activity ({@code orphanlog}) no link ever
 * reaches — exercising the "(unreachable)" tail of the text walk.
 */
public class FlowViewTest {

    private static final String XML =
        "<process id=\"cn=X\" version=\"4.5.0\">"
        + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">Start</display-name></start-activity>"
        + "<user-activity activity-id=\"approval\" approver-type=\"group-approver\" timeout=\"1000\">"
        + "<display-name xml:lang=\"en\">Approve</display-name><addressee>recipient</addressee></user-activity>"
        + "<log-activity activity-id=\"orphanlog\"><display-name xml:lang=\"en\">Orphan</display-name><message>'hi'</message></log-activity>"
        + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"start\" target=\"approval\" type=\"forward\"/>"
        + "<link source=\"approval\" target=\"finish\" type=\"approved\"/>"
        + "<link source=\"approval\" target=\"finish\" type=\"denied\"/>"
        + "</process>";

    private static Flow flow() {
        Prd p = new Prd("X");
        p.process = CanonicalXml.parse(XML).getDocumentElement();
        return Flow.of(p);
    }

    @Test
    public void textListsEachActivityWithKeyAttributesAndLinkSummary() {
        Prd prd = new Prd("X");
        prd.process = CanonicalXml.parse(XML).getDocumentElement();
        Flow flow = Flow.of(prd);
        String out = FlowView.text(new Driver("UA"), prd, flow, "en");

        assertTrue(out, out.contains("start  start-activity  \"Start\""));
        assertTrue(out, out.contains("approval  user-activity  \"Approve\""));
        assertTrue(out, out.contains("timeout=1000"));
        assertTrue(out, out.contains("approver-type=group-approver"));
        assertTrue(out, out.contains("addressee=recipient"));
        assertTrue(out, out.contains("approved: finish"));
        assertTrue(out, out.contains("denied: finish"));
        assertTrue(out, out.contains("finish  finish-activity  \"Finish\""));
    }

    @Test
    public void textFlagsAnUnreachableActivity() {
        Prd prd = new Prd("X");
        prd.process = CanonicalXml.parse(XML).getDocumentElement();
        Flow flow = Flow.of(prd);
        String out = FlowView.text(new Driver("UA"), prd, flow, "en");

        assertTrue(out, out.contains("orphanlog"));
        int line = out.indexOf("orphanlog");
        String tail = out.substring(line, out.indexOf('\n', line));
        assertTrue(tail, tail.contains("(unreachable)"));
    }

    // ---- Track W step W3: the four integration kinds' key-attrs line -----------------------------

    private static final String W3_XML =
        "<process id=\"cn=X\" version=\"4.5.0\">"
        + "<start-activity activity-id=\"start\"><display-name xml:lang=\"en\">Start</display-name></start-activity>"
        + "<rest-activity activity-id=\"call\" protocol=\"https\" host=\"api.example.com\" port=\"443\" path=\"/v1/items\" method=\"POST\">"
        + "<display-name xml:lang=\"en\">Call</display-name></rest-activity>"
        + "<role-request-activity activity-id=\"rr\"><display-name xml:lang=\"en\">RR</display-name>"
        + "<roles>'cn=Role1,o=data'</roles><targets>recipient</targets><action>GRANT</action>"
        + "<request-description>'d'</request-description></role-request-activity>"
        + "<resource-request-activity activity-id=\"res\"><display-name xml:lang=\"en\">Res</display-name>"
        + "<target-resource>'cn=Res1,o=data'</target-resource><target-user>recipient</target-user><action>GRANT</action>"
        + "<request-description>'d'</request-description></resource-request-activity>"
        + "<start-correlated-flow-activity activity-id=\"sf\"><display-name xml:lang=\"en\">SF</display-name>"
        + "<processId>'Other'</processId><recipient>recipient</recipient></start-correlated-flow-activity>"
        + "<finish-activity activity-id=\"finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"start\" target=\"call\" type=\"forward\"/>"
        + "<link source=\"call\" target=\"rr\" type=\"forward\"/>"
        + "<link source=\"rr\" target=\"res\" type=\"forward\"/>"
        + "<link source=\"res\" target=\"sf\" type=\"forward\"/>"
        + "<link source=\"sf\" target=\"finish\" type=\"forward\"/>"
        + "</process>";

    @Test
    public void textShowsKeyAttrsForEachIntegrationKind() {
        Prd prd = new Prd("X");
        prd.process = CanonicalXml.parse(W3_XML).getDocumentElement();
        Flow flow = Flow.of(prd);
        String out = FlowView.text(new Driver("UA"), prd, flow, "en");

        assertTrue(out, out.contains("POST https://api.example.com:443/v1/items"));
        assertTrue(out, out.contains("GRANT 'cn=Role1,o=data' → recipient"));
        assertTrue(out, out.contains("GRANT 'cn=Res1,o=data' → recipient"));
        assertTrue(out, out.contains("'Other' → recipient"));
    }

    @Test
    public void mermaidContainsNodesAndEdges() {
        String out = FlowView.mermaid(flow(), "en");
        assertTrue(out, out.startsWith("flowchart TD\n"));
        assertTrue(out, out.contains("start([\"start\\n#quot;Start#quot;\"])"));
        assertTrue(out, out.contains("approval[\"approval\\n#quot;Approve#quot;\"]"));
        assertTrue(out, out.contains("finish([\"finish\\n#quot;Finish#quot;\"])"));
        assertTrue(out, out.contains("start -- forward --> approval"));
        assertTrue(out, out.contains("approval -- approved --> finish"));
        assertTrue(out, out.contains("approval -- denied --> finish"));
    }
}
