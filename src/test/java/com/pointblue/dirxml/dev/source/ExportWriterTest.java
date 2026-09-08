package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.DriverExport;
import com.pointblue.dirxml.sim.Xds;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * {@link ExportWriter} is the exact inverse of {@link ExportReader}: {@code
 * read(write(model))} must yield a model whose as-code form (see {@link
 * AsCodeWriter}) is byte-identical to the original's — that's a stronger check
 * than field-by-field equality since it walks every artifact, link and config
 * blob the same way {@code dirxml-dev} itself would when diffing a driver set.
 */
public class ExportWriterTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    private static final String LIB_POLICY_XML =
        "<policy><rule><description>shared</description><conditions/><actions>"
        + "<do-veto/></actions></rule></policy>";

    private static final String XSLT_XML =
        "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
        + "<xsl:template match=\"/\"><xsl:copy-of select=\".\"/></xsl:template></xsl:stylesheet>";

    private static final String GCV_DEF_XML =
        "<configuration-values><definitions>"
        + "<definition name=\"drv.enabled\" display-name=\"Enabled\" type=\"boolean\"><value>true</value></definition>"
        + "</definitions></configuration-values>";

    /**
     * {@link ValidatorTest#clean()} plus a Library policy, an XSLT policy, a
     * driver-set-level GCV-definition resource linked in policy set 14,
     * driver-set linkage meta, and packaged meta on both a driver and an artifact
     * — everything {@link ExportWriter} needs to round-trip.
     */
    private static DriverSet sample() {
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");

        // library policy, shared via the subscriber-event set
        Policy shared = new Policy("lib-shared", Scope.LIBRARY, null, xml(LIB_POLICY_XML));
        ds.library.policies.add(shared);
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-shared", 0));

        // XSLT policy on the publisher channel, linked in the output set
        Policy xslt = new Policy("pub-xslt", Scope.PUBLISHER, "AD", xml(XSLT_XML));
        ad.publisher.policies.add(xslt);
        ad.links.add(new PolicyLink(PolicySet.OUTPUT, "drivers/AD/publisher/pub-xslt", 0));

        // driver-set-level GCV-definition resource, linked in policy set 14
        Resource gcvDef = new Resource("dvs-GCVs", Scope.LIBRARY, null, Resource.GCV_DEF);
        gcvDef.content = xml(GCV_DEF_XML);
        ds.library.resources.add(gcvDef);
        ad.links.add(new PolicyLink(PolicySet.GCV, "library/dvs-GCVs", 0));

        // the driver set's own policy-linkage (a GCV-set link the reader keeps as meta only)
        ds.meta.put("driverset.linkage.0", "cn=dvs-GCVs,cn=dvs,o=system#0#14");
        // matches ExportWriter's own default so re-reading doesn't invent a new value
        ds.meta.put("library.base-dn", "cn=Library," + ds.dn);
        // the filename read() will be told about, so "source.file" round-trips too
        ds.meta.put("source.file", "sample.xml");

        // packaged meta: on a driver, and on an artifact
        ad.meta.put("package-id", "PKG-DRV-1");
        ad.meta.put("package-version", "3.2.1");
        ad.meta.put("modified", "2024-05-01T00:00:00Z");
        Policy smp = ad.policies.get(0);
        smp.meta.put("package-id", "PKG-ART-1");
        smp.meta.put("pkg-assoc-id", "ASSOC-1");
        smp.meta.put("checksum", "123456789");
        smp.meta.put("modified", "2024-05-02T00:00:00Z");

        return ds;
    }

    @Test
    public void roundTripsThroughAsCode() throws Exception {
        DriverSet ds1 = sample();
        Element writtenRoot = CanonicalXml.parse(ExportWriter.toXml(ds1)).getDocumentElement();
        DriverSet ds2 = ExportReader.read(writtenRoot, "sample.xml");

        assertAsCodeIdentical(ds1, ds2);

        // a few structural spot checks beyond the byte comparison
        assertEquals(ds1.index().keySet(), ds2.index().keySet());
        assertTrue(ds2.unresolvedLinks().isEmpty());
        Driver ad = ds2.driver("AD");
        assertEquals("PKG-DRV-1", ad.meta.get("package-id"));
        assertEquals("3.2.1", ad.meta.get("package-version"));
        Policy smp = (Policy) ds2.resolve("drivers/AD/smp");
        assertEquals("PKG-ART-1", smp.meta.get("package-id"));
        assertEquals("123456789", smp.meta.get("checksum"));
        Resource gcv = (Resource) ds2.resolve("library/dvs-GCVs");
        assertTrue(gcv.isGcvDef());
        assertEquals("cn=dvs-GCVs,cn=dvs,o=system#0#14", ds2.meta.get("driverset.linkage.0"));
    }

    @Test
    public void realRfiDriverSetRoundTripsThroughAsCode() throws Exception {
        Path rfi = Path.of(System.getProperty("user.home"), "tmp", "RFI-DriverSet.xml");
        assumeTrue("needs the local RFI-DriverSet.xml export", Files.exists(rfi));

        DriverSet ds1 = ExportReader.read(rfi);

        // Reader bookkeeping that was never a real per-artifact/driver attribute and so
        // has nothing for the writer to reconstruct: <packages>/<jobs>/<rbe-policies>
        // counts noted on the driver set, and any "unknown policy-set" diagnostic on a
        // driver. (RFI-DriverSet.xml happens to have no unknown policy-set ids, but we
        // strip defensively so the assertion documents the exclusion rather than relying
        // on that.) Everything else — artifacts, content, links, GCVs, package/checksum
        // meta — must round-trip exactly.
        ds1.meta.remove("packages.count");
        ds1.meta.remove("jobs.count");
        ds1.meta.remove("rbe-policies.count");
        for (Driver d : ds1.drivers) {
            d.meta.keySet().removeIf(k -> k.startsWith("linkage.unknown."));
        }

        // write under the same file name so "source.file" round-trips too
        Path written = tmp.newFolder().toPath().resolve("RFI-DriverSet.xml");
        ExportWriter.write(ds1, written);
        DriverSet ds2 = ExportReader.read(written);

        assertAsCodeIdentical(ds1, ds2);

        assertEquals(ds1.drivers.size(), ds2.drivers.size());
        assertTrue("expected at least 1 driver", ds2.drivers.size() >= 1);
        long mappingTables = ds2.library.resources.stream().filter(Resource::isMappingTable).count();
        assertTrue("expected >=4 mapping-table resources, got " + mappingTables, mappingTables >= 4);
    }

    @Test
    public void simulatorLoadsTheWrittenExport() throws Exception {
        Path rfi = Path.of(System.getProperty("user.home"), "tmp", "RFI-DriverSet.xml");
        assumeTrue("needs the local RFI-DriverSet.xml export", Files.exists(rfi));

        DriverSet ds = ExportReader.read(rfi);
        Path written = tmp.newFolder().toPath().resolve("RFI-DriverSet-written.xml");
        ExportWriter.write(ds, written);

        Document doc = Xds.parseFile(written);
        Element root = doc.getDocumentElement();
        List<String> loadedNames = new ArrayList<>();
        NodeList kids = root.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && "driver-configuration".equals(((Element) k).getLocalName())) {
                Element driverEl = (Element) k;
                String standalone = Xds.serializeElement(driverEl);
                DriverExport export = DriverExport.load(Xds.parse(standalone));
                loadedNames.add(export.driverName());
            }
        }

        List<String> modelNames = ds.drivers.stream().map(d -> d.name).sorted().collect(Collectors.toList());
        loadedNames.sort(null);
        assertEquals(modelNames, loadedNames);
    }

    // ---- as-code comparison helper ---------------------------------------------

    private void assertAsCodeIdentical(DriverSet a, DriverSet b) throws IOException {
        Path pa = tmp.newFolder().toPath();
        Path pb = tmp.newFolder().toPath();
        AsCodeWriter.write(a, pa);
        AsCodeWriter.write(b, pb);
        assertEquals(snapshot(pa), snapshot(pb));
    }

    /** relative path -> file bytes (as string), for whole-tree comparison. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    out.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
                }
            }
        }
        return out;
    }
}
