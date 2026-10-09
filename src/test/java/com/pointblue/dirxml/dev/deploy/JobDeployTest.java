package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Job;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Jobs through ModelDiff → Plan (docs/console-gaps.md §8), mirroring EntitlementDeployTest. */
public class JobDeployTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String JOB_XML = "<job-aggregation><job-definition disabled=\"false\" type=\"java\"><java-class>x.Trigger</java-class></job-definition></job-aggregation>";

    private Path tree(boolean withJobs) throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = DS;
        ds.servers.add("cn=s1,o=system");
        Driver d = new Driver("Loopback");
        d.dn = "cn=Loopback," + DS;
        if (withJobs) {
            Job j = new Job("trigger", CanonicalXml.parse(JOB_XML).getDocumentElement());
            j.servers.add("cn=s1,o=system");
            j.meta.put("trace-level", "3");
            d.jobs.add(j);
            Job s = new Job("StatisticsJob", CanonicalXml.parse(JOB_XML).getDocumentElement());
            s.servers.add("cn=s1,o=system");
            ds.jobs.add(s);
        }
        ds.drivers.add(d);
        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        return t;
    }

    @Test
    public void addedJobsPlanAnAddAndANotifyWithoutARestart() throws Exception {
        Path t = tree(false);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        Job j = new Job("trigger", CanonicalXml.parse(JOB_XML).getDocumentElement());
        j.servers.add("cn=s1,o=system");
        j.scopes.add("o=data#0#<scope-def scope=\"subtree\"/>");
        j.meta.put("trace-level", "3");
        to.driver("Loopback").jobs.add(j);
        to.jobs.add(new Job("StatisticsJob", CanonicalXml.parse(JOB_XML).getDocumentElement()));
        ModelDiff diff = ModelDiff.of(from, to);
        List<ModelDiff.Change> changes = diff.changes();
        assertEquals(2, changes.size());
        assertEquals(ModelDiff.Kind.JOB_ADDED, changes.get(0).kind);
        assertEquals("drivers/Loopback/jobs/trigger", changes.get(0).path);
        assertEquals("jobs/StatisticsJob", changes.get(1).path);
        assertTrue("no engine restart for a job change", diff.affectedDrivers().isEmpty());
        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        assertTrue(plan.restart.isEmpty());
        assertEquals(4, plan.steps.size());
        Plan.Step add = plan.steps.get(0);
        assertEquals(Plan.Op.ADD, add.op);
        assertEquals("cn=trigger,cn=Loopback," + DS, add.dn);
        assertEquals(List.of("Top", "DirXML-Job"), add.objectClasses);
        assertTrue(new String(add.values.get("XmlData").get(0), StandardCharsets.UTF_8).contains("x.Trigger"));
        assertEquals("cn=s1,o=system", new String(add.values.get("DirXML-ServerList").get(0), StandardCharsets.UTF_8));
        assertEquals("3", new String(add.values.get("DirXML-TraceLevel").get(0), StandardCharsets.UTF_8));
        assertTrue(add.values.containsKey("DirXML-Scope"));
        assertEquals(Plan.Op.NOTIFY_JOB, plan.steps.get(1).op);
        assertEquals(add.dn, plan.steps.get(1).dn);
        assertEquals("cn=StatisticsJob," + DS, plan.steps.get(2).dn);
        assertEquals(Plan.Op.NOTIFY_JOB, plan.steps.get(3).op);
    }

    @Test
    public void aChangedTraceLevelIsOneModifyThenANotifyAndARemovalNeedsDeleteAll() throws Exception {
        Path t = tree(true);
        DriverSet from = AsCodeReader.read(t);
        DriverSet to = AsCodeReader.read(t);
        to.driver("Loopback").job("trigger").meta.put("trace-level", "4");
        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(1, diff.changes().size());
        assertEquals(ModelDiff.Kind.JOB_CHANGED, diff.changes().get(0).kind);
        assertTrue(diff.changes().get(0).detail, diff.changes().get(0).detail.contains("trace-level: 4"));
        Plan plan = Plan.of(diff, to, DS, Secrets.none(), "none", null, true, t);
        List<Plan.Op> ops = plan.steps.stream().map(s -> s.op).toList();
        assertTrue(ops.toString(), ops.contains(Plan.Op.MODIFY) && ops.get(ops.size() - 1) == Plan.Op.NOTIFY_JOB);
        assertTrue(plan.steps.stream().anyMatch(s -> "DirXML-TraceLevel".equals(s.attr)));

        DriverSet gone = AsCodeReader.read(t);
        gone.driver("Loopback").jobs.clear();
        ModelDiff removal = ModelDiff.of(from, gone);
        assertEquals(ModelDiff.Kind.JOB_REMOVED, removal.changes().get(0).kind);
        assertEquals("jobs", ModelDiff.removalKind(ModelDiff.Kind.JOB_REMOVED));
    }
}
