package com.pointblue.dirxml.dev.trace;

import com.pointbluetech.dirxml.trace.model.Channel;
import com.pointbluetech.dirxml.trace.model.TraceMarker;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** trace.view on synthetic trace text (the engine's wording from its own message bundles). */
public class TraceViewTest {

    private static final String TRACE = String.join("\n",
        "[10/15/26 10:00:00.000]:AD ST:  Applying rule Left over from before the tail.",           // 1: mid-transaction
        "[10/15/26 10:00:00.010]:AD ST:End transaction.",                                            // 2
        "[10/15/26 10:01:00.000]:AD ST:Start transaction.",                                          // 3
        "[10/15/26 10:01:00.100]:AD ST:Applying event transformation policies.",                     // 4
        "[10/15/26 10:01:00.110]:AD ST:Applying policy: sub-etp-Veto.",                              // 5
        "[10/15/26 10:01:00.120]:AD ST:  Evaluating selection criteria for rule Veto if no surname.", // 6
        "[10/15/26 10:01:00.130]:AD ST:  Rule selected.",                                            // 7
        "[10/15/26 10:01:00.140]:AD ST:  Applying rule Veto if no surname.",                         // 8
        "[10/15/26 10:01:00.150]:AD ST:    Action: do-veto().",                                      // 9
        "[10/15/26 10:01:00.160]:Loop ST:Start transaction.",                                        // 10: another driver interleaves
        "[10/15/26 10:01:00.170]:AD ST:Policy returned:",                                            // 11
        "<nds dtdversion=\"4.0\">",                                                                  // 12
        "  <input>",
        "    <add class-name=\"User\" event-id=\"ev1\" src-dn=\"\\T\\data\\users\\jdoe\"/>",
        "  </input>",
        "</nds>",                                                                                    // 16
        "[10/15/26 10:01:00.180]:AD ST:Operation vetoed by policy.",                                 // 17
        "[10/15/26 10:01:00.190]:AD ST:My shim says: <status> is just a word here",                  // 18: a shim's own text
        "[10/15/26 10:01:00.200]:AD ST:End transaction.",                                            // 19
        "[10/15/26 10:02:00.000]:AD ST:Start transaction.",                                          // 20
        "[10/15/26 10:02:00.050]:AD ST:Submitting document to subscriber shim:",                     // 21
        "<nds><input><modify class-name=\"User\" dest-dn=\"CN=jdoe,OU=x\"/></input></nds>",           // 22
        "[10/15/26 10:02:00.100]:AD ST:SubscriptionShim.execute() returned:",                        // 23
        "<nds><output><status level=\"error\" event-id=\"ev2\">Access <b>denied</b>.</status></output></nds>", // 24
        "[10/15/26 10:02:00.200]:AD ST:End transaction.",                                            // 25
        "[10/15/26 10:03:00.000]:AD PT:Receiving DOM document from application.",                    // 26
        "<nds><input><add class-name=\"Group\" src-dn=\"g1\"/></input></nds>",                        // 27
        "[10/15/26 10:03:00.100]:AD PT:Pumping XDS to eDirectory.",                                  // 28
        "<nds><output><status level=\"success\"/></output></nds>");                                  // 29

    private static TraceView.Result view() {
        return TraceView.of(TraceView.records(Arrays.asList(TRACE.split("\n"))));
    }

    @Test
    public void groupsTransactionsPerDriverAndChannel() {
        TraceView.Result r = view();
        assertEquals(29, r.lines);
        assertEquals(List.of("AD subscriber null", "AD subscriber Subscriber event", "Loop subscriber Subscriber event",
                "AD subscriber Subscriber event", "AD publisher Publisher event"),
            r.transactions.stream().map(t -> t.driver + " " + t.channel.name().toLowerCase() + " " + (t.opener == null ? null : t.opener.label())).toList());
        TraceView.Transaction first = r.transactions.get(0);
        assertNull("the input began inside a transaction", first.opener);
        assertTrue(first.ended);
        TraceView.Transaction veto = r.transactions.get(1);
        assertEquals(List.of(List.of(3, 9), List.of(11, 19)), TraceView.ranges(veto));
        assertTrue("the other driver's line is not in it", !TraceView.text(veto).contains("Loop ST"));
        assertEquals(Channel.SUBSCRIBER, veto.channel);
        assertEquals(TraceMarker.SUBSCRIBER_EVENT, veto.opener);
    }

    @Test
    public void readsTheEnginesWording() {
        TraceView.Transaction t = view().transactions.get(1);
        assertEquals("add", t.operation);
        assertEquals("User", t.className);
        assertEquals("\\T\\data\\users\\jdoe", t.dn);
        assertEquals("ev1", t.eventId);
        assertEquals(1, t.sets.size());
        assertEquals("Applying event transformation policies.", t.sets.get(0).name);
        TraceView.Policy p = t.sets.get(0).policies.get(0);
        assertEquals("sub-etp-Veto", p.name);
        assertEquals(List.of("Veto if no surname"), p.rules);
        assertEquals(List.of("do-veto()"), p.actions);
        assertEquals(List.of("Action: do-veto().", "Operation vetoed by policy."), t.vetoes);
        assertTrue("a <status> word with no level is not a status document", t.statuses.isEmpty());
        assertNull(t.worst());
        assertEquals("the shim's own line is counted, not read", 1, t.unrecognized);
    }

    @Test
    public void statusesAndTheirWorstLevel() {
        TraceView.Result r = view();
        TraceView.Transaction err = r.transactions.get(3);
        assertEquals("modify", err.operation);
        assertEquals("CN=jdoe,OU=x", err.dn);
        assertEquals(1, err.statuses.size());
        assertEquals(new TraceView.Status("error", "ev2", "Access denied ."), err.statuses.get(0));
        assertEquals("error", err.worst());
        assertEquals("success", r.transactions.get(4).worst());
    }

    @Test
    public void jsonFiltersAndOneTransactionsText() {
        TraceView.Result r = view();
        TraceView.Filter f = new TraceView.Filter();
        Map<String, Object> all = TraceView.json("test", r, f, 20, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) all.get("summary");
        assertEquals(5, summary.get("transactions"));
        assertEquals(1, summary.get("vetoed"));
        assertEquals(List.of(2, 4), summary.get("problemTransactions"));
        assertEquals("the Loop transaction never ended", 1, summary.get("unfinished"));
        assertTrue(String.valueOf(all.get("note")).contains("best effort"));

        f.status = "error";
        assertEquals(1, TraceView.json("test", r, f, 20, null).get("matched"));
        f.status = null;
        f.vetoed = true;
        assertEquals(1, TraceView.json("test", r, f, 20, null).get("matched"));
        f.vetoed = false;
        f.channel = Channel.PUBLISHER;
        assertEquals(1, TraceView.json("test", r, f, 20, null).get("matched"));
        f.channel = null;
        f.driver = "loop";
        assertEquals(1, TraceView.json("test", r, f, 20, null).get("matched"));
        f.driver = null;
        assertEquals("--last keeps the most recent", 2, TraceView.json("test", r, f, 2, null).get("shown"));

        @SuppressWarnings("unchecked")
        Map<String, Object> one = (Map<String, Object>) TraceView.json("test", r, f, 20, 4).get("transaction");
        assertTrue(String.valueOf(one.get("text")).contains("Access <b>denied</b>."));
        assertNull(TraceView.json("test", r, f, 20, 99).get("transaction"));
    }

    @Test
    public void textViewNamesEachTransaction() {
        String out = TraceView.text("test", view(), new TraceView.Filter(), 20);
        assertTrue(out, out.contains("VETO") && out.contains("status error") && out.contains("(no event in view)")
            && out.contains("(no end in view)") && out.contains("Best effort"));
    }

    /** trace-core 1.8.0's POLICY_RESULT: a policy's query result is part of the running policy, not the end of it. */
    @Test
    public void aPolicysQueryResultDoesNotEndThePolicy() {
        List<String> lines = List.of(
            "[10/19/26 10:00:00.000]:AD ST:Start transaction.",
            "[10/19/26 10:00:00.100]:AD ST:Applying event transformation policies.",
            "[10/19/26 10:00:00.110]:AD ST:Applying policy: sub-etp-Lookup.",
            "[10/19/26 10:00:00.120]:AD ST:  Applying rule Find the manager.",
            "[10/19/26 10:00:00.130]:AD ST:    Action: do-set-local-variable(\"mgr\").",
            "[10/19/26 10:00:00.140]:AD ST:Query from policy result",
            "<nds><output><instance class-name=\"User\" src-dn=\"\\T\\data\\boss\"/></output></nds>",
            "[10/19/26 10:00:00.150]:AD ST:    Action: do-set-dest-attr-value(\"manager\").",
            "[10/19/26 10:00:00.200]:AD ST:End transaction.");
        TraceView.Result r = TraceView.of(TraceView.records(lines));
        assertEquals(TraceMarker.POLICY_RESULT, TraceMarker.of(TraceView.records(lines).get(5).record()));
        TraceView.Transaction t = r.transactions.get(0);
        assertEquals(1, t.sets.size());
        TraceView.Policy p = t.sets.get(0).policies.get(0);
        assertEquals("sub-etp-Lookup", p.name);
        assertTrue(p.actions.toString(), p.actions.stream().anyMatch(a -> a.contains("do-set-dest-attr-value")));
    }
}
