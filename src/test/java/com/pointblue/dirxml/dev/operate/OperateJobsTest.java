package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Vault;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** job.list, job.status, job.start and job.abort through the fake engine. */
public class OperateJobsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String JOB_XML = "<job-aggregation><job-definition disabled=\"false\" type=\"java\"><java-class>com.novell.nds.dirxml.job.statistics.StatisticsJob</java-class></job-definition></job-aggregation>";

    private static Environments.Environment env(Path dir) throws Exception {
        Path f = dir.resolve("environments.properties");
        Files.writeString(f, "lab.url=ldaps://lab:636\nlab.bindDn=cn=admin,o=system\nlab.password=pw\nlab.driverSet=" + DS + "\nlab.tier=dev\n", StandardCharsets.UTF_8);
        return Environments.load(f).get("lab");
    }

    @Test
    public void listStatusStartAndAbort() throws Exception {
        Environments.Environment lab = env(tmp.newFolder("etc").toPath());
        Path tree = tmp.newFolder("tree").toPath();
        OperateTest.FakeEngine engine = new OperateTest.FakeEngine();
        String setJob = "cn=StatisticsJob," + DS;
        String drvJob = "cn=trigger,cn=CyberArk," + DS;
        Vault.Entry a = new Vault.Entry(setJob);
        a.attrs.put("XmlData", List.of(JOB_XML.getBytes(StandardCharsets.UTF_8)));
        a.attrs.put("DirXML-ServerList", List.of("cn=s1,o=system".getBytes(StandardCharsets.UTF_8)));
        Vault.Entry b = new Vault.Entry(drvJob);
        b.attrs.put("XmlData", List.of(JOB_XML.replace("disabled=\"false\"", "disabled=\"true\"").getBytes(StandardCharsets.UTF_8)));
        engine.entries.put(setJob, a);
        engine.entries.put(drvJob, b);
        engine.searches.put("(objectClass=DirXML-Job)", List.of(a, b));
        engine.jobStates.put(setJob, new Vault.JobState(1, 0, true, new Date(0)));

        Operate.Result list = Operate.jobList(engine, lab, null);
        assertTrue(list.ok);
        assertTrue(list.text, list.text.contains("StatisticsJob") && list.text.contains("driver set") && list.text.contains("running") && list.text.contains("1970-01-01"));
        assertTrue(list.text, list.text.contains("trigger") && list.text.contains("CyberArk") && list.text.contains("unknown (") && list.text.contains("disabled in its configuration"));
        assertTrue(list.json, list.json.contains("\"name\":\"StatisticsJob\"") && list.json.contains("\"driver\":null") && list.json.contains("\"driver\":\"CyberArk\""));

        Operate.Result st = Operate.jobStatus(engine, lab, "StatisticsJob", null);
        assertTrue(st.ok);
        assertTrue(st.text, st.text.contains("com.novell.nds.dirxml.job.statistics.StatisticsJob") && st.text.contains("running") && st.text.contains("scheduled       yes"));
        assertFalse(Operate.jobStatus(engine, lab, "nope", null).ok);

        Operate.Result started = Operate.jobAction(engine, lab, "StatisticsJob", null, "start", true, null, tree);
        assertTrue(started.text, started.ok);
        assertEquals(List.of("start " + setJob), engine.jobCalls);
        assertTrue(Files.readString(tree.resolve("deploy-log").resolve("lab.jsonl")).contains("job.start 'StatisticsJob'"));
        Operate.Result aborted = Operate.jobAction(engine, lab, "trigger", "CyberArk", "abort", true, null, tree);
        assertTrue(aborted.ok);
        assertEquals("abort " + drvJob, engine.jobCalls.get(1));
        assertFalse(Operate.jobAction(engine, lab, "x", null, "pause", true, null, tree).ok);
    }
}
