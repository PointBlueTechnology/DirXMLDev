package com.pointblue.dirxml.dev.simulate;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.RuleOps;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.source.ExportWriter;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.sim.BatchRunner;
import com.pointblue.dirxml.sim.Case;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The simulate gate end to end: a tree, a case, the simulator's verdict, and the
 * before/after comparison when a rule changes.
 */
public class SimulateTest {

    private static final String VETO_SURNAME =
        "<policy><rule><description>veto surname changes</description>"
            + "<conditions><and><if-op-attr name=\"Surname\" op=\"changing\"/></and></conditions>"
            + "<actions><do-veto/></actions></rule></policy>";

    private static final String INPUT =
        "<nds><input><modify class-name=\"User\" src-dn=\"\\dvs\\users\\alice\" event-id=\"1\">"
            + "<association>alice</association>"
            + "<modify-attr attr-name=\"Surname\"><add-value><value>Smith</value></add-value></modify-attr>"
            + "</modify></input></nds>";

    private Path tree;
    private Path cases;

    @Before
    public void setUp() throws IOException {
        tree = Files.createTempDirectory("idm-sim-tree");
        DriverSet ds = ValidatorTest.clean();
        Policy ctp = (Policy) ds.resolve("drivers/AD/subscriber/sub-ctp");
        ctp.content = ValidatorTest.xml(VETO_SURNAME);
        AsCodeWriter.write(ds, tree);

        cases = Files.createTempDirectory("idm-sim-cases");
        Path c = cases.resolve("veto");
        Files.createDirectories(c);
        Files.writeString(c.resolve("input.xds"), INPUT, StandardCharsets.UTF_8);
        Files.writeString(c.resolve("case.properties"),
            "driver=AD\nchannel=subscriber\ndriverDN=\\\\dvs\\\\AD\nfromNDS=true\ntraceLevel=3\n", StandardCharsets.UTF_8);
    }

    private Simulate gate() {
        return new Simulate(ExportWriter::writeDriver);
    }

    @Test
    public void caseWithoutGoldenSkipsButRuns() throws IOException {
        Simulate.Outcome o = gate().run(tree, cases, null);
        assertEquals(o.text(), 1, o.cases.size());
        assertEquals(BatchRunner.Outcome.SKIP, o.cases.get(0).outcome);
        assertTrue(o.ok());
    }

    @Test
    public void goldenPassesThenFailsAfterEdit() throws IOException {
        // record the tree's current output as the golden
        Path rendered = Simulate.render(cases.resolve("veto"), Simulate.load(cases.resolve("veto")),
            Files.createTempDirectory("r").resolve("veto"), export(tree));
        String golden = Case.load(rendered).run().finalXds;
        assertFalse(golden, golden.contains("<modify"));   // vetoed: no operation reaches the shim
        Files.writeString(cases.resolve("veto/expected-output.xds"), golden, StandardCharsets.UTF_8);

        Simulate.Outcome pass = gate().run(tree, cases, null);
        assertEquals(pass.text(), BatchRunner.Outcome.PASS, pass.cases.get(0).outcome);
        assertTrue(pass.ok());

        // disable the veto: the modify now flows, the golden no longer matches, and --against shows it
        Path edited = copy(tree);
        assertTrue(Transaction.open(edited).run(new RuleOps.SetDisabled("drivers/AD/subscriber/sub-ctp", "#1", true), false, false).ok());
        Simulate.Outcome after = gate().run(edited, cases, tree);
        Simulate.CaseOutcome c = after.cases.get(0);
        assertEquals(after.text(), BatchRunner.Outcome.FAIL, c.outcome);
        assertFalse(c.against.finalSame);
        assertEquals("sub-ctp", c.against.firstDivergesAt.replaceAll(".*sub-ctp.*", "sub-ctp"));
        assertFalse(after.ok());
        assertTrue(after.json(), after.json().contains("\"changed\":1"));

        // the tree against itself: unchanged
        Simulate.Outcome same = gate().run(tree, cases, tree);
        assertTrue(same.cases.get(0).against.finalSame);
        assertTrue(same.ok());
    }

    @Test
    public void unknownDriverIsAnError() throws IOException {
        Files.writeString(cases.resolve("veto/case.properties"), "driver=Nope\nchannel=subscriber\n", StandardCharsets.UTF_8);
        Simulate.Outcome o = gate().run(tree, cases, null);
        assertEquals(BatchRunner.Outcome.ERROR, o.cases.get(0).outcome);
        assertTrue(o.cases.get(0).detail, o.cases.get(0).detail.contains("no driver 'Nope'"));
    }

    @Test
    public void driverNameComesFromTheExportWhenNotGiven() throws IOException {
        Path export = export(tree);
        Files.writeString(cases.resolve("veto/case.properties"),
            "export=" + export.toString().replace("\\", "\\\\") + "\nchannel=subscriber\n", StandardCharsets.UTF_8);
        Properties p = Simulate.load(cases.resolve("veto"));
        assertEquals("AD", Simulate.driverOf(p, cases.resolve("veto")));
        assertNull(Simulate.driverOf(new Properties(), cases.resolve("veto")));
        Simulate.Outcome o = gate().run(tree, cases, null);
        assertEquals(o.text(), BatchRunner.Outcome.SKIP, o.cases.get(0).outcome);
    }

    private static Path export(Path tree) throws IOException {
        Path f = Files.createTempDirectory("exp").resolve("AD.xml");
        ExportWriter.writeDriver(com.pointblue.dirxml.dev.ascode.AsCodeReader.read(tree), "AD", f);
        return f;
    }

    private static Path copy(Path tree) throws IOException {
        Path t = Files.createTempDirectory("idm-sim-copy");
        AsCodeWriter.write(com.pointblue.dirxml.dev.ascode.AsCodeReader.read(tree), t);
        return t;
    }
}
