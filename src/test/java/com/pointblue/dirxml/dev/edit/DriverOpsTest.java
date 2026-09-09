package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.source.ExportWriter;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DriverOpsTest {

    private Path tree;

    @Before
    public void writeTree() throws IOException {
        tree = Files.createTempDirectory("idm-driver-add");
        AsCodeWriter.write(ValidatorTest.clean(), tree);
    }

    private Result run(Operation op) throws IOException {
        return Transaction.open(tree).run(op, false, false);
    }

    @Test
    public void copyOfClonesArtifactsLinksAndConfig() throws IOException {
        Result r = run(new DriverOps.Add("AD Copy", null, "AD", true, null, null, null));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(tree);
        Driver c = ds.driver("AD Copy");
        assertNotNull(c);
        assertEquals("com.example.Shim", c.shimClass);
        assertEquals(1, c.subscriber.policies.size());
        assertEquals("drivers/AD Copy/subscriber/sub-ctp", c.links(PolicySet.SUB_COMMAND).get(0).ref);
        assertEquals("drivers/AD Copy/smp", c.links(PolicySet.SCHEMA_MAPPING).get(0).ref);
        assertNotNull(c.config.get(Driver.DRIVER_FILTER));
        assertEquals("cn=AD Copy,cn=dvs,o=system", c.dn);
        assertTrue(r.report.ok());
        // the original is untouched and the clone is independent
        assertEquals(1, ds.driver("AD").subscriber.policies.size());
        assertFalse(run(new DriverOps.Add("AD Copy", null, "AD", true, null, null, null)).ok());
        assertTrue(run(new DriverOps.Add("X", null, "Nope", true, null, null, null)).refusal.contains("no driver 'Nope'"));
    }

    @Test
    public void blankDriver() throws IOException {
        Result r = run(new DriverOps.Add("Loop", null, null, false, "com.example.Loop", "ldap://x", "svc"));
        assertTrue(r.text(), r.ok());
        Driver d = AsCodeReader.read(tree).driver("Loop");
        assertEquals("com.example.Loop", d.shimClass);
        assertEquals("svc", d.shimAuthId);
        assertNotNull(d.config.get(Driver.DRIVER_FILTER));
        assertNotNull(d.config.get(Driver.SHIM_CONFIG_INFO));
        assertTrue(r.report.ok());
        assertTrue(run(new DriverOps.Add("Blank2", null, null, false, null, null, null)).refusal.contains("--shim-class"));
    }

    @Test
    public void fromExportMergesTheDriverAndMissingLibraryArtifacts() throws IOException {
        // an export of a driver from another set, whose Library has an extra policy
        DriverSet other = ValidatorTest.clean();
        other.library.policies.add(new com.pointblue.dirxml.dev.model.Policy("lib-extra", com.pointblue.dirxml.dev.model.Scope.LIBRARY, null,
            ValidatorTest.xml("<policy><rule><description>x</description><conditions/><actions/></rule></policy>")));
        other.driver("AD").links.add(new com.pointblue.dirxml.dev.model.PolicyLink(PolicySet.SUB_EVENT, "library/lib-extra", 0));
        // a driver-set export keeps Library scope (our single-driver form embeds Library
        // artifacts in the channel for the simulator's sake; Designer's own single-driver
        // export with referenced policies keeps them addressable under cn=Library)
        Path export = Files.createTempFile("ad", ".xml");
        ExportWriter.write(other, export);

        Result r = run(new DriverOps.Add("AD Imported", export, "AD", false, null, null, null));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(tree);
        Driver d = ds.driver("AD Imported");
        assertEquals("drivers/AD Imported/subscriber/sub-ctp", d.links(PolicySet.SUB_COMMAND).get(0).ref);
        assertEquals("library/lib-extra", d.links(PolicySet.SUB_EVENT).get(0).ref);
        assertNotNull(ds.resolve("library/lib-extra"));        // added: was absent
        assertNotNull(ds.resolve("library/CodeMap"));          // kept: was present
        assertTrue(r.report.ok());
        assertTrue(ds.unresolvedLinks().isEmpty());
    }

    @Test
    public void fromRealExport() throws IOException {
        Path rfi = Path.of("/Users/jcombs/tmp/RFI-DriverSet.xml");
        Assume.assumeTrue(Files.exists(rfi));
        // into a tree from the same set, so the driver-set GCVs its policies read exist
        AsCodeWriter.write(com.pointblue.dirxml.dev.source.ExportReader.read(rfi), tree);
        Result none = run(new DriverOps.Add("RLand2", rfi, null, false, null, null, null));
        assertTrue(none.refusal, none.refusal != null && none.refusal.contains("--source-driver"));
        Result r = run(new DriverOps.Add("RLand2", rfi, "RLand", false, null, null, null));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(tree);
        assertFalse(ds.driver("RLand2").links.isEmpty());
        assertTrue(ds.unresolvedLinks().isEmpty());
    }
}
