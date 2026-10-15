package com.pointblue.dirxml.dev.operate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.novell.ldap.events.edir.EdirEventConstant;
import com.pointbluetech.dirxml.trace.model.RawTraceEvent;
import com.pointbluetech.dirxml.trace.model.TraceParser;
import com.pointbluetech.dirxml.trace.model.TraceRecord;
import com.pointblue.dirxml.dev.trace.TraceView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * One driver's messages out of the engine's stream (trace-core's parser): continuations follow their
 * driver, the engine's channel only on request, and the lines as a trace file has them.
 */
public class LdapTraceTest {

    private static final int DRV = EdirEventConstant.EVT_DB_DIRXML_DRIVERS;
    private static final int ENGINE = EdirEventConstant.EVT_DB_DIRXML;

    private final TraceParser parser = new TraceParser();

    private TraceRecord rec(int type, String raw) {
        return parser.parse(new RawTraceEvent("idm254", type, 1_760_540_000_000L, "", raw, List.of()));
    }

    @Test
    public void keepsOneDriverAndItsContinuations() {
        LdapTrace t = new LdapTrace("User Application Driver", null, false);
        assertEquals(List.of(), t.accept(rec(DRV, "%10CRole and Resource driver ST:DirXML Log Event")));
        List<String> first = t.accept(rec(DRV, "%10CUser Application Driver PT:javax.net.ssl.SSLPeerUnverifiedException: peer not authenticated"));
        assertEquals(1, first.size());
        assertTrue(first.get(0), first.get(0).matches("\\[[^\\]]+\\]:User Application Driver PT:javax\\.net\\.ssl\\.SSLPeerUnverifiedException: peer not authenticated"));
        List<String> cont = t.accept(rec(DRV, "%12C\tat sun.security.ssl.SSLSessionImpl.getPeerCertificates(Unknown Source)"));
        assertEquals(List.of("\tat sun.security.ssl.SSLSessionImpl.getPeerCertificates(Unknown Source)"), cont);
        assertEquals(List.of(), t.accept(rec(DRV, "%10CData Collection Service Driver ST:something")));
        assertEquals("the continuation now follows the DCS line", List.of(), t.accept(rec(DRV, "%12C\tat y")));
    }

    @Test
    public void everyDriverGrepAndTheEnginesChannel() {
        LdapTrace all = new LdapTrace(null, "Log Event", false);
        List<String> s = all.accept(rec(DRV, "%10CRole and Resource driver ST:DirXML Log Event"));
        assertTrue(s.toString(), s.size() == 1 && s.get(0).endsWith("]:Role and Resource driver ST:DirXML Log Event"));
        assertEquals(List.of(), all.accept(rec(DRV, "%10CRole and Resource driver ST:Updating DirXML-DriverStorage")));
        assertEquals("the engine's channel is left out unless asked for", List.of(),
            new LdapTrace(null, null, false).accept(rec(ENGINE, "DirXML Engine: Loading driver set")));
        assertEquals(1, new LdapTrace(null, null, true).accept(rec(ENGINE, "DirXML Engine: Loading driver set")).size());
    }

    @Test
    public void whatItPrintsIsWhatTraceViewReads() {
        LdapTrace t = new LdapTrace("AD", null, false);
        List<String> lines = new ArrayList<>();
        for (String raw : List.of("%10CAD ST:Start transaction.", "%10CAD ST:Applying event transformation policies.",
                "%10CAD ST:Applying policy: sub-etp-X.", "%10CAD ST:  Applying rule Veto.", "%10CAD ST:    Action: do-veto().",
                "%10CAD ST:End transaction.")) {
            lines.addAll(t.accept(rec(DRV, raw)));
        }
        TraceView.Result r = TraceView.of(TraceView.records(lines));
        assertEquals(1, r.transactions.size());
        TraceView.Transaction tx = r.transactions.get(0);
        assertTrue(tx.ended);
        assertEquals("sub-etp-X", tx.sets.get(0).policies.get(0).name);
        assertEquals(List.of("Veto"), tx.sets.get(0).policies.get(0).rules);
        assertEquals(List.of("Action: do-veto()."), tx.vetoes);
    }
}
