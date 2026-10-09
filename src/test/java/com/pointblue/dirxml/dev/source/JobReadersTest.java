package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Job;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Jobs (DirXML-Job) through every reader and the tree, and what validate says about them. */
public class JobReadersTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String DRV = "cn=CyberArk,cn=driverset1,o=system";
    private static final String JOB_XML = "<?xml version=\"1.0\"?><job-aggregation><job-definition auto-delete=\"false\" disabled=\"true\" display-name=\"xlfid(job-display-name)Subscriber channel trigger\" type=\"java\">"
        + "<description>trigger</description><containment>DirXML-Driver</containment><java-class>com.novell.nds.dirxml.job.trigger.Trigger</java-class></job-definition></job-aggregation>";

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", List.of("Top", oc));
        for (int i = 0; i < kv.length; i += 2) {
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    @Test
    public void ldifJobsLandUnderTheirDriverOrTheSetAndRoundTripTheTree() throws Exception {
        List<Entry> entries = List.of(
            entry(DS, "DirXML-DriverSet", "DirXML-ServerList", "cn=edir-test3,ou=servers,o=system"),
            entry(DRV, "DirXML-Driver", "DirXML-JavaModule", "com.pointbluetech.idm.cyberark.scim.CyberarkShim"),
            entry("cn=process ent Refs," + DRV, "DirXML-Job", "XmlData", JOB_XML, "DirXML-ServerList", "cn=edir-test3,ou=servers,o=system",
                "DirXML-Scope", "ou=users,o=data#0#<scope-def scope=\"subtree\"/>", "DirXML-TraceLevel", "3", "DirXML-TraceFile", "/opt/novell/trace/ca/caJob.xml", "DirXML-TraceSizeLimit", "100000"),
            entry("cn=StatisticsJob," + DS, "DirXML-Job", "XmlData", JOB_XML.replace("disabled=\"true\"", "disabled=\"false\""), "DirXML-ServerList", "cn=edir-test3,ou=servers,o=system"));
        DriverSet ds = LdifReader.fromEntries(entries, "test");
        Driver d = ds.driver("CyberArk");
        assertNotNull(d);
        assertEquals(1, d.jobs.size());
        Job j = d.jobs.get(0);
        assertEquals("process ent Refs", j.name);
        assertEquals(List.of("cn=edir-test3,ou=servers,o=system"), j.servers);
        assertEquals(1, j.scopes.size());
        assertEquals("3", j.meta.get("trace-level"));
        assertEquals("com.novell.nds.dirxml.job.trigger.Trigger", j.javaClass());
        assertTrue(j.disabled());
        assertEquals("Subscriber channel trigger", j.displayName());
        assertEquals(1, ds.jobs.size());
        assertEquals("StatisticsJob", ds.jobs.get(0).name);
        assertFalse(ds.jobs.get(0).disabled());

        // the tree: jobs/<name>.xml beside the manifests, back the same
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);
        assertTrue(Files.isRegularFile(tree.resolve("drivers/CyberArk/jobs/process ent Refs.xml").normalize()) || Files.isRegularFile(tree.resolve("drivers/CyberArk/jobs").resolve(AsCodeWriter.fileSafe("process ent Refs") + ".xml")));
        assertTrue(Files.isRegularFile(tree.resolve("jobs").resolve(AsCodeWriter.fileSafe("StatisticsJob") + ".xml")));
        DriverSet back = AsCodeReader.read(tree);
        Job bj = back.driver("CyberArk").job("process ent Refs");
        assertNotNull(bj);
        assertEquals(j.servers, bj.servers);
        assertEquals(j.scopes, bj.scopes);
        assertEquals("3", bj.meta.get("trace-level"));
        assertEquals("/opt/novell/trace/ca/caJob.xml", bj.meta.get("trace-file"));
        assertNotNull(bj.jobDefinition());
        assertEquals(1, back.jobs.size());

        // validate: the disabled one is an info, nothing else to say
        Report r = Validator.standard().validate(back);
        assertTrue(r.text(), r.text().contains("job-disabled"));
        assertFalse(r.text(), r.text().contains("job-no-server") || r.text().contains("job-unknown-server") || r.text().contains("job-no-class"));
    }

    @Test
    public void validateNamesAJobWithoutAServerOrOnAnUnknownOne() throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.servers.add("cn=srv1,o=system");
        Driver d = new Driver("AD");
        ds.drivers.add(d);
        Job none = new Job("no-server", CanonicalXml.parse(JOB_XML).getDocumentElement());
        d.jobs.add(none);
        Job odd = new Job("odd", CanonicalXml.parse(JOB_XML).getDocumentElement());
        odd.servers.add("cn=elsewhere,o=system");
        ds.jobs.add(odd);
        Job bare = new Job("bare", CanonicalXml.parse("<nope/>").getDocumentElement());
        ds.jobs.add(bare);
        String text = Validator.standard().validate(ds).text();
        assertTrue(text, text.contains("job-no-server") && text.contains("drivers/AD/jobs/no-server"));
        assertTrue(text, text.contains("job-unknown-server") && text.contains("jobs/odd"));
        assertTrue(text, text.contains("job-wrong-root") && text.contains("jobs/bare"));
    }

    @Test
    public void anExportCarriesJobsBothWaysAndTheReaderTakesThemBack() throws Exception {
        List<Entry> entries = List.of(
            entry(DS, "DirXML-DriverSet"),
            entry(DRV, "DirXML-Driver", "DirXML-JavaModule", "x.Shim"),
            entry("cn=trigger," + DRV, "DirXML-Job", "XmlData", JOB_XML, "DirXML-ServerList", "cn=s1,o=system", "DirXML-Scope", "o=data#0#<scope-def scope=\"subtree\"/>", "DirXML-TraceLevel", "2"),
            entry("cn=stats," + DS, "DirXML-Job", "XmlData", JOB_XML, "DirXML-ServerList", "cn=s1,o=system"));
        DriverSet ds = LdifReader.fromEntries(entries, "test");
        String xml = ExportWriter.toXml(ds);
        assertTrue(xml, xml.contains("<jobs>") && xml.contains("name=\"trigger\"") && xml.contains("name=\"stats\"") && xml.contains("trace-level=\"2\"") && xml.contains("<scope value="));
        DriverSet back = ExportReader.read(CanonicalXml.parse(xml).getDocumentElement(), "again.xml");
        Job t = back.driver("CyberArk").job("trigger");
        assertNotNull(t);
        assertEquals(List.of("cn=s1,o=system"), t.servers);
        assertEquals(1, t.scopes.size());
        assertEquals("2", t.meta.get("trace-level"));
        assertEquals("com.novell.nds.dirxml.job.trigger.Trigger", t.javaClass());
        assertEquals(1, back.jobs.size());
        assertEquals("stats", back.jobs.get(0).name);
    }
}
