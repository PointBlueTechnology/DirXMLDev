package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.SyntheticDriverSet;
import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.flow.Flow;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.sim.Xds;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A PRD read from a tree holds its process in a document of its own ({@code process.xml}), and
 * its definition has no {@code <process>} child at all. Every flow operation re-syncs the two:
 * the copy it places in the definition must belong to the definition's document.
 */
public class FlowOpsSeparateProcessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aFlowEditOnATreeReadFromDiskSyncsTheDefinition() throws Exception {
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(SyntheticDriverSet.model(), tree);
        Prd before = AsCodeReader.read(tree).driver(SyntheticDriverSet.DRIVER).provisioning.prd(SyntheticDriverSet.PRD);
        assertTrue("the fixture keeps process.xml apart from definition.xml", before.definition.getOwnerDocument() != before.process.getOwnerDocument());
        assertTrue(Xds.childrenByName(before.definition, "process").isEmpty());

        Result r = Transaction.open(tree).run(new FlowOps.ActivitySet(null, SyntheticDriverSet.PRD, "Approval", "Manager approval",
            null, null, null, null, null, null, null, null, null, null), false, false);
        assertTrue(r.text(), r.ok());

        Prd after = AsCodeReader.read(tree).driver(SyntheticDriverSet.DRIVER).provisioning.prd(SyntheticDriverSet.PRD);
        assertEquals("Manager approval", Flow.of(after).byId("Approval").displayName("en"));
        List<org.w3c.dom.Element> synced = Xds.childrenByName(after.definition, "process");
        assertTrue("the definition carries the edited process again, or still none", synced.isEmpty() || synced.get(0).getOwnerDocument() == after.definition.getOwnerDocument());
    }
}
