package com.pointblue.dirxml.dev.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.SyntheticDriverSet;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EventsCaseTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private Path tree;

    @Before
    public void tree() throws Exception {
        Path ldif = SyntheticDriverSet.copy(SyntheticDriverSet.LDIF, tmp.newFolder("synth").toPath());
        tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(LdifReader.read(ldif), tree);
    }

    private static Event event(String driverDn, String xml, int schema, String policy) {
        Map<String, Object> r = EventTest.row("add", "{\"event-type\":\"add\",\"schemaVersion\":2,\"class-name\":\"User\"}", xml, schema);
        r.put("srcdriver", driverDn);
        if (policy != null) {
            r.put("policy", policy);
            r.put("channel", "publisher");
            r.put("stage", "output");
        }
        return Event.fromRow(r);
    }

    @Test
    public void aRowBecomesACaseInTheTreesCasesDirectory() throws Exception {
        Event e = event("\\SYNTH\\synth\\SynthSet\\Loop", "<nds><input><add class-name=\"User\" src-dn=\"x\"/></input></nds>", 2, null);
        EventsCase.Plan p = EventsCase.plan(tree, e, "lab", "loop-add-sample", null, null, null, false, false);
        assertNull(p.refusal, p.refusal);
        assertEquals(tree.resolve("cases").resolve("loop-add-sample"), p.dir);
        String props = p.files.get("case.properties");
        assertTrue(props, props.contains("driver=Loop\n") && props.contains("channel=subscriber\n") && props.contains("source=events:lab:4711\n"));
        assertTrue(p.files.get("input.xds").startsWith("<nds>"));
        EventsCase.write(p);
        assertTrue(Files.exists(p.dir.resolve("input.xds")));
        // exists now: refused without --replace, replaced with it
        assertNotNull(EventsCase.plan(tree, e, "lab", "loop-add-sample", null, null, null, false, false).refusal);
        assertTrue(EventsCase.plan(tree, e, "lab", "loop-add-sample", null, null, null, true, false).replaced);
    }

    @Test
    public void aPolicyRowRecordsItsPolicyAndChannelAndAChosenDriverWins() throws Exception {
        Event e = event("\\SYNTH\\synth\\SynthSet\\Other", "<nds/>", 2, "pub-ctp-X");
        assertNotNull(EventsCase.plan(tree, e, "lab", "x", null, null, null, false, true).refusal);   // Other is not in the tree
        EventsCase.Plan p = EventsCase.plan(tree, e, "lab", "x", "Loop", null, tmp.newFolder("elsewhere").toPath(), false, true);
        assertNull(p.refusal, p.refusal);
        String props = p.files.get("case.properties");
        assertTrue(props, props.contains("channel=publisher\n") && props.contains("sourcePolicy=pub-ctp-X\n") && props.contains("sourceStage=output\n") && props.contains("pseudonymised=true\n"));
        assertTrue(p.dir.toString().contains("elsewhere"));
    }

    @Test
    public void refusalsAreSpecific() throws Exception {
        Event noXmlV1 = event("\\SYNTH\\synth\\SynthSet\\Loop", null, 1, null);
        assertTrue(EventsCase.plan(tree, noXmlV1, "lab", "a", null, null, null, false, false).refusal.contains("schema version 1"));
        Event noXmlV2 = event("\\SYNTH\\synth\\SynthSet\\Loop", null, 2, null);
        String r = EventsCase.plan(tree, noXmlV2, "lab", "a", null, null, null, false, false).refusal;
        assertTrue(r, r.contains("storeXML=false") && r.contains("dirxml-event-logger"));
        assertTrue(EventsCase.plan(tree, event("\\S\\d\\Loop", "<nds/>", 2, null), "lab", "bad/name", null, null, null, false, false).refusal.contains("case name"));
        assertTrue(EventsCase.plan(tree, event("\\S\\d\\Loop", "<nds/>", 2, null), "lab", "a", null, "sideways", null, false, false).refusal.contains("channel"));
    }
}
