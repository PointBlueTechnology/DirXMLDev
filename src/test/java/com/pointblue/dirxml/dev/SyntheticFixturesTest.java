package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.source.ExportReader;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The synthetic driver set validates clean, and the committed renderings of it are what the generator produces today. */
public class SyntheticFixturesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void theModelValidatesWithNoErrors() {
        Report r = Validator.standard().validate(SyntheticDriverSet.model());
        assertTrue(r.text(), r.ok());
        assertEquals(r.text(), 0, r.count(Finding.Severity.WARNING));
    }

    @Test
    public void committedFixturesMatchTheGenerator() throws Exception {
        assertEquals("driverset-export.xml is stale: run SyntheticDriverSet.main", SyntheticDriverSet.exportXml(), SyntheticDriverSet.committed(SyntheticDriverSet.EXPORT));
        assertEquals("loop-driver-export.xml is stale: run SyntheticDriverSet.main", SyntheticDriverSet.driverExportXml(), SyntheticDriverSet.committed(SyntheticDriverSet.DRIVER_EXPORT));
        assertEquals("driverset.ldif is stale: run SyntheticDriverSet.main", SyntheticDriverSet.ldif(), SyntheticDriverSet.committed(SyntheticDriverSet.LDIF));
    }

    @Test
    public void everyRenderingReadsBackToTheModel() throws Exception {
        Path dir = tmp.newFolder("fx").toPath();
        DriverSet fromExport = ExportReader.read(SyntheticDriverSet.copy(SyntheticDriverSet.EXPORT, dir));
        DriverSet fromLdif = LdifReader.read(SyntheticDriverSet.copy(SyntheticDriverSet.LDIF, dir));
        for (DriverSet ds : new DriverSet[] {fromExport, fromLdif}) {
            assertEquals(1, ds.drivers.size());
            assertNotNull(ds.driver(SyntheticDriverSet.DRIVER));
            assertEquals(1, ds.library.policies.size());
            assertEquals(1, ds.library.resources.size());
            assertEquals(1, ds.driver(SyntheticDriverSet.DRIVER).entitlements.size());
            assertTrue(ds.unresolvedLinks().toString(), ds.unresolvedLinks().isEmpty());
        }
        // a Designer export carries no AppConfig; the vault (so the LDIF) does — and only because the
        // deploy of a new driver now creates its forms and PRDs (it did not, before this fixture)
        assertTrue(fromExport.driver(SyntheticDriverSet.DRIVER).provisioning == null
            || fromExport.driver(SyntheticDriverSet.DRIVER).provisioning.forms.isEmpty());
        assertNotNull(fromLdif.driver(SyntheticDriverSet.DRIVER).provisioning);
        assertEquals(2, fromLdif.driver(SyntheticDriverSet.DRIVER).provisioning.forms.size());
        assertEquals(1, fromLdif.driver(SyntheticDriverSet.DRIVER).provisioning.prds.size());
    }
}
