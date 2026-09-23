package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.FakeVault;
import com.pointblue.dirxml.dev.deploy.Vault;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Export from a fake source, bundle round trip, import into a fake lab, verify. */
public class CloneTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Vault.Entry entry(String dn, List<String> classes, String... kv) {
        Vault.Entry e = new Vault.Entry(dn);
        List<byte[]> oc = new ArrayList<>();
        for (String c : classes) {
            oc.add(c.getBytes(StandardCharsets.UTF_8));
        }
        e.attrs.put("objectClass", oc);
        for (int i = 0; i < kv.length; i += 2) {
            e.attrs.computeIfAbsent(kv[i], k -> new ArrayList<>()).add(kv[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return e;
    }

    private static Vault.Entry schemaEntry(List<String> attrs, List<String> classes) {
        Vault.Entry s = new Vault.Entry("cn=schema");
        List<byte[]> a = new ArrayList<>();
        for (String x : attrs) {
            a.add(x.getBytes(StandardCharsets.UTF_8));
        }
        List<byte[]> c = new ArrayList<>();
        for (String x : classes) {
            c.add(x.getBytes(StandardCharsets.UTF_8));
        }
        s.attrs.put("objectClass", List.of("subschema".getBytes(StandardCharsets.UTF_8)));
        s.attrs.put("attributeTypes", a);
        s.attrs.put("objectClasses", c);
        return s;
    }

    /** A source tree: system/servers/Security, a driver set with a driver and a job, identity data, RBS. */
    static FakeVault source() {
        FakeVault v = new FakeVault();
        v.seed(schemaEntry(SchemaTest.ATTRS, SchemaTest.CLASSES));
        v.seed(entry("o=system", List.of("Organization", "Top", "Partition"), "o", "system", "GUID", "abc"));
        v.seed(entry("ou=servers,o=system", List.of("organizationalUnit", "Top"), "ou", "servers"));
        v.seed(entry("cn=srv1,ou=servers,o=system", List.of("ncpServer", "Top"), "cn", "srv1"));
        v.seed(entry("ou=sa,o=system", List.of("organizationalUnit", "Top"), "ou", "sa"));
        v.seed(entry("cn=admin,ou=sa,o=system", List.of("inetOrgPerson", "Person", "Top"), "cn", "admin", "sn", "admin"));
        v.seed(entry("cn=Security", List.of("sASSecurity", "Top"), "cn", "Security"));
        v.seed(entry("cn=KAP,cn=Security", List.of("nDSPKISDKeyAccessPartition", "Top"), "cn", "KAP"));
        v.seed(entry("cn=Password Policies,cn=Security", List.of("nspmPasswordPolicyContainer", "Top"), "cn", "Password Policies"));
        v.seed(entry("cn=Lab Policy,cn=Password Policies,cn=Security", List.of("nspmPasswordPolicy", "Top"), "cn", "Lab Policy"));
        v.seed(entry("o=data", List.of("Organization", "Top", "Partition"), "o", "data",
            "nspmPasswordPolicyDN", "cn=Lab Policy,cn=Password Policies,cn=Security",
            "ACL", "2#subtree#cn=admin,ou=sa,o=system#[All Attributes Rights]", "ACL", "1#subtree#[Public]#[Entry Rights]"));
        v.seed(entry("cn=Default,o=data", List.of("rbsCollection2", "Top"), "cn", "Default"));
        v.seed(entry("cn=g1,o=data", List.of("groupOfNames", "Top"), "cn", "g1", "member", "cn=admin,ou=sa,o=system"));
        v.seed(entry("cn=driverset1,o=system", List.of("DirXML-DriverSet", "Top"), "cn", "driverset1",
            "DirXML-ServerList", "cn=srv1,ou=servers,o=system"));
        Vault.Entry drv = entry("cn=AD,cn=driverset1,o=system", List.of("DirXML-Driver", "Top"), "cn", "AD",
            "DirXML-DriverStartOption", "2", "DirXML-ShimAuthPassword", "s3cret", "DirXML-ShimConfigInfo", "<cfg server=\"srv1\"/>",
            "DirXML-Policies", "cn=pol1,cn=AD,cn=driverset1,o=system#0#6",
            "securityEquals", "cn=admin,ou=sa,o=system", "securityEquals", "cn=gone,o=data",
            "ACL", "2#entry#cn=g1,o=data#[Entry Rights]", "GUID", "xyz", "equivalentToMe", "cn=admin,ou=sa,o=system");
        drv.attrs.put("DirXML-DriverImage", List.of(new byte[] {'G', 'I', 'F', 0, (byte) 0xFF}));
        v.seed(drv);
        v.seed(entry("cn=pol1,cn=AD,cn=driverset1,o=system", List.of("DirXML-Rule", "Top"), "cn", "pol1", "XmlData", "<policy/>"));
        v.seed(entry("cn=job1,cn=driverset1,o=system", List.of("DirXML-Job", "Top"), "cn", "job1", "DirXML-ServerList", "cn=srv1,ou=servers,o=system"));
        v.seed(entry("cn=Old,cn=driverset1,o=system", List.of("DirXML-Driver", "Top"), "cn", "Old"));
        return v;
    }

    /** A lab: same names, its own server, a bare schema. */
    static FakeVault lab() {
        FakeVault v = new FakeVault();
        v.seed(schemaEntry(List.of(SchemaTest.ATTRS.get(1), SchemaTest.ATTRS.get(0)), List.of(SchemaTest.CLASSES.get(0), SchemaTest.CLASSES.get(1), SchemaTest.CLASSES.get(2), SchemaTest.CLASSES.get(6))));
        v.seed(entry("o=system", List.of("Organization", "Top"), "o", "system"));
        v.seed(entry("ou=servers,o=system", List.of("organizationalUnit", "Top"), "ou", "servers"));
        v.seed(entry("cn=lab1,ou=servers,o=system", List.of("ncpServer", "Top"), "cn", "lab1"));
        v.seed(entry("ou=sa,o=system", List.of("organizationalUnit", "Top"), "ou", "sa"));
        v.seed(entry("cn=admin,ou=sa,o=system", List.of("inetOrgPerson", "Person", "Top"), "cn", "admin", "sn", "admin"));
        v.seed(entry("cn=Security", List.of("sASSecurity", "Top"), "cn", "Security"));
        return v;
    }

    private static Vault.Entry find(List<Vault.Entry> list, String dn) {
        for (Vault.Entry e : list) {
            if (e.dn.equalsIgnoreCase(dn)) {
                return e;
            }
        }
        return null;
    }

    private static String s(Vault.Entry e, String attr) {
        return e.string(attr);
    }

    @Test
    public void exportAppliesThePolicyAndSplitsThePhases() throws Exception {
        CloneExporter.Options o = new CloneExporter.Options();
        o.sourceName = "fake";
        CloneExporter.Report r = new CloneExporter(source(), o).run();
        CloneBundle b = r.bundle;

        // never cloned: servers, KAP, RBS, identity data (the group), the admin user
        List<Vault.Entry> all = new ArrayList<>(b.containers);
        all.addAll(b.objects);
        assertNull(find(all, "cn=srv1,ou=servers,o=system"));
        assertNull(find(all, "ou=servers,o=system"));
        assertNull(find(all, "cn=KAP,cn=Security"));
        assertNull(find(all, "cn=Default,o=data"));
        assertNull(find(all, "cn=g1,o=data"));
        assertNull(find(all, "cn=admin,ou=sa,o=system"));
        assertNotNull(find(all, "cn=Lab Policy,cn=Password Policies,cn=Security"));
        assertTrue(r.skippedSubtrees.toString(), r.skippedSubtrees.stream().anyMatch(x -> x.startsWith("ou=servers,o=system")));

        // parents first, containers split out
        assertTrue(b.containers.get(0).dn.equals("o=system") || b.containers.get(0).dn.equals("cn=Security") || b.containers.get(0).dn.equals("o=data"));
        assertNotNull(find(b.containers, "cn=driverset1,o=system"));
        assertNotNull(find(b.objects, "cn=pol1,cn=AD,cn=driverset1,o=system"));
        int ds = all.indexOf(find(all, "cn=driverset1,o=system"));
        int ad = all.indexOf(find(all, "cn=AD,cn=driverset1,o=system"));
        assertTrue(ds < ad);

        // the driver: secret out (and listed), GUID / inverse link out, start option forced, image kept as bytes
        Vault.Entry drv = find(all, "cn=AD,cn=driverset1,o=system");
        assertFalse(drv.attrs.containsKey("DirXML-ShimAuthPassword"));
        assertFalse(drv.attrs.containsKey("GUID"));
        assertFalse(drv.attrs.containsKey("equivalentToMe"));
        assertArrayEquals(new byte[] {'G', 'I', 'F', 0, (byte) 0xFF}, drv.attrs.get("DirXML-DriverImage").get(0));
        assertEquals(List.of("cn=AD,cn=driverset1,o=system: DirXML-ShimAuthPassword (on cn=srv1,ou=servers,o=system)"), b.secretsNeeded);
        // server-specific values live per server, not on the entry: start option forced, shim config as read
        assertFalse(drv.attrs.containsKey("DirXML-DriverStartOption"));
        assertFalse(drv.attrs.containsKey("DirXML-ShimConfigInfo"));
        Map<String, List<byte[]>> onSrv1 = b.serverValues.get("cn=srv1,ou=servers,o=system").get("cn=AD,cn=driverset1,o=system");
        assertEquals("1", new String(onSrv1.get("DirXML-DriverStartOption").get(0), StandardCharsets.UTF_8));
        assertEquals("<cfg server=\"srv1\"/>", new String(onSrv1.get("DirXML-ShimConfigInfo").get(0), StandardCharsets.UTF_8));
        assertEquals("1", new String(b.serverValues.get("cn=srv1,ou=servers,o=system").get("cn=Old,cn=driverset1,o=system").get("DirXML-DriverStartOption").get(0), StandardCharsets.UTF_8));
        assertEquals(2, r.driversForcedManual);
        assertEquals("cn=srv1,ou=servers,o=system", b.primaryServerDn());
        assertEquals("cn=srv1,ou=servers,o=system", com.pointblue.dirxml.dev.json.Json.asString(b.placement("cn=AD,cn=driverset1,o=system").get("runsOn")));
        // held back: policies, securityEquals; ACLs apart; the server list stays (association, and mandatory on the job)
        assertFalse(drv.attrs.containsKey("DirXML-Policies"));
        assertEquals(1, b.references.get("cn=AD,cn=driverset1,o=system").get("DirXML-Policies").size());
        assertEquals(2, b.references.get("cn=AD,cn=driverset1,o=system").get("securityEquals").size());
        assertEquals(1, b.acls.get("cn=AD,cn=driverset1,o=system").get("ACL").size());
        assertFalse(drv.attrs.containsKey("ACL"));
        assertEquals("cn=srv1,ou=servers,o=system", s(find(all, "cn=driverset1,o=system"), "DirXML-ServerList"));
        assertEquals("cn=srv1,ou=servers,o=system", s(find(all, "cn=job1,cn=driverset1,o=system"), "DirXML-ServerList"));
        // Partition dropped from o=data; its policy DN held back
        assertEquals(List.of("Organization", "Top"), find(all, "o=data").objectClasses());
        assertNotNull(b.references.get("o=data").get("nspmPasswordPolicyDN"));
        assertEquals("cn=srv1,ou=servers,o=system", b.sourceServerDn());
        assertEquals(SchemaTest.ATTRS.size(), b.attributeTypes.size());

        // disk round trip is exact
        Path dir = tmp.newFolder("bundle").toPath();
        b.write(dir);
        CloneBundle back = CloneBundle.read(dir);
        assertEquals(b.containers.size(), back.containers.size());
        assertEquals(b.objects.size(), back.objects.size());
        assertEquals(b.attributeTypes, back.attributeTypes);
        assertEquals(b.objectClasses, back.objectClasses);
        assertEquals(b.references.keySet(), back.references.keySet());
        assertEquals(b.acls.keySet(), back.acls.keySet());
        assertEquals(b.secretsNeeded, back.secretsNeeded);
        List<Vault.Entry> backAll = new ArrayList<>(back.containers);
        backAll.addAll(back.objects);
        assertArrayEquals(drv.attrs.get("DirXML-DriverImage").get(0), find(backAll, "cn=AD,cn=driverset1,o=system").attrs.get("DirXML-DriverImage").get(0));
        assertEquals("cn=srv1,ou=servers,o=system", back.sourceServerDn());
        assertEquals(b.serverValues.keySet(), back.serverValues.keySet());
        assertEquals("<cfg server=\"srv1\"/>", new String(back.serverValues.get("cn=srv1,ou=servers,o=system").get("cn=AD,cn=driverset1,o=system").get("DirXML-ShimConfigInfo").get(0), StandardCharsets.UTF_8));
    }

    @Test
    public void importPlansThenWritesInPhasesAndVerifies() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        CloneBundle b = new CloneExporter(source(), eo).run().bundle;
        Path dir = tmp.newFolder("bundle2").toPath();
        b.write(dir);
        b = CloneBundle.read(dir);

        FakeVault lab = lab();
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        CloneImporter.Result dry = new CloneImporter(b, lab, o).run();
        assertTrue(dry.text(), dry.ok);
        assertFalse(dry.applied);
        assertEquals(SchemaTest.ATTRS.size() - 2, dry.schemaAttributes);
        assertEquals(SchemaTest.CLASSES.size() - 4, dry.schemaClasses);
        assertEquals(3, dry.existing);                     // o=system, ou=sa, cn=Security
        assertTrue(dry.plan, dry.plan.contains("cn=srv1,ou=servers,o=system -> cn=lab1,ou=servers,o=system"));
        assertNull(lab.read("cn=driverset1,o=system"));

        o.dryRun = false;
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.applied);
        assertEquals(dry.toAdd, r.added);
        assertEquals(0, r.verifyMismatches);
        // schema arrived
        Vault.Entry schema = lab.read("cn=schema");
        assertEquals(SchemaTest.ATTRS.size(), schema.attrs.get("attributeTypes").size());
        assertEquals(SchemaTest.CLASSES.size(), schema.attrs.get("objectClasses").size());
        // the driver set is tied to the lab server; the job too
        assertEquals("cn=lab1,ou=servers,o=system", lab.read("cn=driverset1,o=system").string("DirXML-ServerList"));
        assertEquals("cn=lab1,ou=servers,o=system", lab.read("cn=job1,cn=driverset1,o=system").string("DirXML-ServerList"));
        // references landed; the one naming an object that never exists was dropped with a note
        Vault.Entry drv = lab.read("cn=AD,cn=driverset1,o=system");
        assertEquals("cn=pol1,cn=AD,cn=driverset1,o=system#0#6", drv.string("DirXML-Policies"));
        assertEquals(List.of("cn=admin,ou=sa,o=system"), drv.strings("securityEquals"));
        assertEquals(1, r.droppedReferenceValues);
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("cn=gone,o=data")));
        // ACLs: the trustee that exists on the target stays, the group that was never cloned is dropped
        assertEquals(List.of("2#subtree#cn=admin,ou=sa,o=system#[All Attributes Rights]", "1#subtree#[Public]#[Entry Rights]"),
            lab.read("o=data").strings("ACL"));
        assertNull(drv.attrs.get("ACL"));
        assertEquals(1, r.droppedAclValues);
        assertEquals("1", drv.string("DirXML-DriverStartOption"));          // the chosen server's values merged into the entry
        assertEquals("<cfg server=\"srv1\"/>", drv.string("DirXML-ShimConfigInfo"));
        assertFalse(drv.attrs.containsKey("DirXML-ShimAuthPassword"));
        assertEquals("cn=Lab Policy,cn=Password Policies,cn=Security", lab.read("o=data").string("nspmPasswordPolicyDN"));
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("associated with cn=lab1")));

        // a second run converges: everything exists, nothing written, the clone's own driver set is recognised
        CloneImporter.Result again = new CloneImporter(b, lab, o).run();
        assertTrue(again.text(), again.ok);
        assertEquals(0, again.added);
        assertEquals(dry.toAdd + 3, again.existing);
        assertTrue(again.notes.toString(), again.notes.stream().anyMatch(n -> n.contains("re-run converges")));
        assertEquals(0, again.schemaAttributes);
        // a driver set that is not the clone's (a foreign driver in it) is refused
        lab.seed(entry("cn=Foreign,cn=driverset1,o=system", List.of("DirXML-Driver", "Top"), "cn", "Foreign"));
        CloneImporter.Result foreign = new CloneImporter(b, lab, o).run();
        assertFalse(foreign.text(), foreign.ok);
        assertTrue(foreign.refusals.get(0), foreign.refusals.get(0).contains("cn=Foreign,cn=driverset1,o=system"));
        o.replaceDriverSet = true;
        assertTrue(new CloneImporter(b, lab, o).run().ok);
    }

    @Test
    public void importRefusesWithoutAServerWhenTheSourceNamesOne() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        CloneBundle b = new CloneExporter(source(), eo).run().bundle;
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        CloneImporter.Result r = new CloneImporter(b, lab(), o).run();
        assertFalse(r.ok);
        assertTrue(r.refusals.get(0), r.refusals.get(0).contains("--server"));
    }

    // ---- two servers at the source ----

    /** The source tree with a second server: AD ran there (state running, enabled), with its own shim config. */
    static FakeVault secondServer() {
        FakeVault v = new FakeVault();
        v.seed(entry("cn=driverset1,o=system", List.of("DirXML-DriverSet", "Top"), "cn", "driverset1"));
        v.seed(entry("cn=AD,cn=driverset1,o=system", List.of("DirXML-Driver", "Top"), "cn", "AD",
            "DirXML-DriverStartOption", "2", "DirXML-State", "2", "DirXML-ShimConfigInfo", "<cfg server=\"srv2\"/>",
            "DirXML-ShimAuthPassword", "other"));
        v.seed(entry("cn=Old,cn=driverset1,o=system", List.of("DirXML-Driver", "Top"), "cn", "Old", "DirXML-DriverStartOption", "0"));
        return v;
    }

    private static FakeVault twoServerSource() {
        FakeVault v = source();
        Vault.Entry ds = v.read("cn=driverset1,o=system");
        ds.attrs.put("DirXML-ServerList", List.of("cn=srv1,ou=servers,o=system".getBytes(StandardCharsets.UTF_8), "cn=srv2,ou=servers,o=system".getBytes(StandardCharsets.UTF_8)));
        Vault.Entry ad = v.read("cn=AD,cn=driverset1,o=system");
        ad.attrs.put("DirXML-DriverStartOption", List.of("0".getBytes(StandardCharsets.UTF_8)));   // disabled on srv1
        v.seed(entry("", List.of("Top"), "dsaName", "cn=srv1,ou=servers,o=system", "directoryTreeName", "SRC_TREE"));
        return v;
    }

    @Test
    public void exportReadsEveryServerAndRecordsWhereEachDriverRan() throws Exception {
        CloneExporter.Options o = new CloneExporter.Options();
        o.sourceName = "fake";
        o.serverUrls.put("cn=srv2,ou=servers,o=system", "ldaps://srv2:636");
        FakeVault srv2 = secondServer();
        o.connector = url -> {
            assertEquals("ldaps://srv2:636", url);
            return srv2;
        };
        CloneExporter.Report r = new CloneExporter(twoServerSource(), o).run();
        CloneBundle b = r.bundle;
        assertEquals(2, b.servers().size());
        assertEquals("cn=srv1,ou=servers,o=system", b.primaryServerDn());
        assertTrue(b.serverReachable("cn=srv2,ou=servers,o=system"));
        Map<String, List<byte[]>> onSrv2 = b.serverValues.get("cn=srv2,ou=servers,o=system").get("cn=AD,cn=driverset1,o=system");
        assertEquals("<cfg server=\"srv2\"/>", new String(onSrv2.get("DirXML-ShimConfigInfo").get(0), StandardCharsets.UTF_8));
        assertEquals("1", new String(onSrv2.get("DirXML-DriverStartOption").get(0), StandardCharsets.UTF_8));
        assertFalse(onSrv2.containsKey("DirXML-State"));                   // run-time state is never cloned
        assertFalse(onSrv2.containsKey("DirXML-ShimAuthPassword"));
        assertTrue(b.secretsNeeded.toString(), b.secretsNeeded.contains("cn=AD,cn=driverset1,o=system: DirXML-ShimAuthPassword (on cn=srv2,ou=servers,o=system)"));
        Map<String, Object> pl = b.placement("cn=AD,cn=driverset1,o=system");
        assertEquals("cn=srv2,ou=servers,o=system", pl.get("runsOn"));    // running there, disabled on srv1
        assertEquals("cn=srv1,ou=servers,o=system", b.placement("cn=Old,cn=driverset1,o=system").get("runsOn"));   // nowhere: the primary
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("2 servers")));

        // unreachable second server: noted, primary values only
        CloneExporter.Options o2 = new CloneExporter.Options();
        o2.sourceName = "fake";
        o2.serverUrls.put("cn=srv2,ou=servers,o=system", "ldaps://srv2:636");
        o2.connector = url -> {
            throw new IllegalStateException("connection refused");
        };
        CloneExporter.Report r2 = new CloneExporter(twoServerSource(), o2).run();
        assertFalse(r2.bundle.serverReachable("cn=srv2,ou=servers,o=system"));
        assertFalse(r2.bundle.serverValues.containsKey("cn=srv2,ou=servers,o=system"));
        assertTrue(r2.notes.toString(), r2.notes.stream().anyMatch(n -> n.contains("unreachable")));
    }

    @Test
    public void importMergesOntoOneServerTakingEachDriverFromWhereItRan() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        eo.serverUrls.put("cn=srv2,ou=servers,o=system", "ldaps://srv2:636");
        FakeVault srv2 = secondServer();
        eo.connector = url -> srv2;
        CloneBundle b = new CloneExporter(twoServerSource(), eo).run().bundle;
        Path dir = tmp.newFolder("bundle3").toPath();
        b.write(dir);
        b = CloneBundle.read(dir);

        FakeVault lab = lab();
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        o.dryRun = false;
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.plan, r.plan.contains("cn=srv2,ou=servers,o=system (1 driver)"));
        Vault.Entry ad = lab.read("cn=AD,cn=driverset1,o=system");
        assertEquals("<cfg server=\"srv2\"/>", ad.string("DirXML-ShimConfigInfo"));    // AD ran on srv2
        assertEquals("1", ad.string("DirXML-DriverStartOption"));
        assertEquals("1", lab.read("cn=Old,cn=driverset1,o=system").string("DirXML-DriverStartOption"));
        // both source servers collapse to one value on the driver set
        assertEquals(List.of("cn=lab1,ou=servers,o=system"), lab.read("cn=driverset1,o=system").strings("DirXML-ServerList"));
        assertEquals(0, r.verifyMismatches);

        // the operator chooses otherwise
        FakeVault lab2 = lab();
        CloneImporter.Options o2 = new CloneImporter.Options();
        o2.envName = "lab";
        o2.serverDn = "cn=lab1,ou=servers,o=system";
        o2.dryRun = false;
        o2.driverServer.put("AD", "cn=srv1,ou=servers,o=system");
        CloneImporter.Result r2 = new CloneImporter(b, lab2, o2).run();
        assertTrue(r2.text(), r2.ok);
        assertEquals("<cfg server=\"srv1\"/>", lab2.read("cn=AD,cn=driverset1,o=system").string("DirXML-ShimConfigInfo"));
    }

    @Test
    public void importMapsServersOneToOneWritingEachLabServerItsOwnValues() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        eo.serverUrls.put("cn=srv2,ou=servers,o=system", "ldaps://srv2:636");
        FakeVault srv2 = secondServer();
        eo.connector = url -> srv2;
        CloneBundle b = new CloneExporter(twoServerSource(), eo).run().bundle;

        FakeVault lab = lab();
        lab.seed(entry("", List.of("Top"), "dsaName", "cn=lab1,ou=servers,o=system"));
        lab.seed(entry("cn=lab2,ou=servers,o=system", List.of("ncpServer", "Top"), "cn", "lab2"));
        FakeVault lab2Conn = new FakeVault();      // the second lab server's own replica view
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.dryRun = false;
        o.dnMap.put("cn=srv1,ou=servers,o=system", "cn=lab1,ou=servers,o=system");
        o.dnMap.put("cn=srv2,ou=servers,o=system", "cn=lab2,ou=servers,o=system");
        o.targetServerUrls.put("cn=lab2,ou=servers,o=system", "ldaps://lab2:636");
        o.targetConnector = url -> {
            assertEquals("ldaps://lab2:636", url);
            // a replica sees the same entries; seed what phase 2 created so replace() has a target
            for (Vault.Entry e : lab.search("cn=driverset1,o=system", "(objectClass=*)", 2)) {
                Vault.Entry copy = new Vault.Entry(e.dn);
                copy.attrs.putAll(e.attrs);            // its own attribute map: a replica, not the same object
                lab2Conn.seed(copy);
            }
            return lab2Conn;
        };
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.plan, r.plan.contains("one to one"));
        // the lab primary mirrors srv1; lab2 got srv2's values through its own connection
        assertEquals("<cfg server=\"srv1\"/>", lab.read("cn=AD,cn=driverset1,o=system").string("DirXML-ShimConfigInfo"));
        assertEquals("<cfg server=\"srv2\"/>", lab2Conn.read("cn=AD,cn=driverset1,o=system").string("DirXML-ShimConfigInfo"));
        assertTrue(r.serverValuesWritten > 0);
        assertEquals(List.of("cn=lab1,ou=servers,o=system", "cn=lab2,ou=servers,o=system"), lab.read("cn=driverset1,o=system").strings("DirXML-ServerList"));
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("cn=lab1,ou=servers,o=system, cn=lab2,ou=servers,o=system")));
    }

    /** eDirectory 9.3 marks the start option server-owned: the clone sets it through the engine, as deploy does. */
    @Test
    public void serverOwnedStartOptionGoesThroughTheEngine() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        CloneBundle b = new CloneExporter(source(), eo).run().bundle;
        FakeVault lab = lab();
        Vault.Entry schema = lab.read("cn=schema");
        List<byte[]> attrs = new ArrayList<>(schema.attrs.get("attributeTypes"));
        attrs.add("( 2.16.840.1.113719.1.14.4.1.9 NAME 'DirXML-DriverStartOption' SYNTAX 1.3.6.1.4.1.1466.115.121.1.27 NO-USER-MODIFICATION USAGE directoryOperation )".getBytes(StandardCharsets.UTF_8));
        schema.attrs.put("attributeTypes", attrs);
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        o.dryRun = false;
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        Vault.Entry ad = lab.read("cn=AD,cn=driverset1,o=system");
        assertFalse(ad.attrs.containsKey("DirXML-DriverStartOption"));                 // never written as an attribute
        assertEquals(Vault.START_MANUAL, lab.driverStartOption("cn=AD,cn=driverset1,o=system"));
        assertEquals(Vault.START_MANUAL, lab.driverStartOption("cn=Old,cn=driverset1,o=system"));
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("through the engine")));
        assertEquals(0, r.verifyMismatches);

        // the engine answers only after a restart: a re-run sets every cloned driver, written or not
        lab.setDriverStartOption("cn=AD,cn=driverset1,o=system", Vault.START_AUTO);
        CloneImporter.Result again = new CloneImporter(b, lab, o).run();
        assertTrue(again.text(), again.ok);
        assertEquals(0, again.added);
        assertEquals(Vault.START_MANUAL, lab.driverStartOption("cn=AD,cn=driverset1,o=system"));
    }

    /** The schema says nothing (a 9.2.8 definition brought by the clone) but an engine answers: the start option still goes through it. */
    @Test
    public void anAnsweringEngineOwnsTheStartOptionWhateverTheSchemaSays() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        CloneBundle b = new CloneExporter(source(), eo).run().bundle;
        FakeVault lab = lab();
        lab.engineVersion = 0x040A0200;
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        o.dryRun = false;
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        assertFalse(lab.read("cn=AD,cn=driverset1,o=system").attrs.containsKey("DirXML-DriverStartOption"));
        assertEquals(Vault.START_MANUAL, lab.driverStartOption("cn=AD,cn=driverset1,o=system"));
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("an engine answers on the target")));
        assertEquals(0, r.verifyMismatches);
    }

    /** No answer to GetVersion yet, but the engine refuses the attribute on the add: the add is retried without it. */
    @Test
    public void aRefusedStartOptionWriteIsRetriedWithoutIt() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        CloneBundle b = new CloneExporter(source(), eo).run().bundle;
        FakeVault lab = lab();
        lab.refuseStartOptionWrites = "[LDAP: error code 50 - NDS error: no access (-672)]";
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        o.dryRun = false;
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.text(), r.failures.isEmpty());
        assertTrue(lab.exists("cn=AD,cn=driverset1,o=system"));
        assertFalse(lab.read("cn=AD,cn=driverset1,o=system").attrs.containsKey("DirXML-DriverStartOption"));
        assertEquals(0, r.verifyMismatches);
    }

    // ---- identity data ----

    private static FakeVault sourceWithPeople() {
        FakeVault v = source();
        v.seed(entry("ou=users,o=data", List.of("organizationalUnit", "Top"), "ou", "users"));
        Vault.Entry u = entry("cn=jsmith,ou=users,o=data", List.of("inetOrgPerson", "organizationalPerson", "Person", "Top"),
            "cn", "jsmith", "givenName", "John", "sn", "Smith", "fullName", "John Smith", "mail", "john.smith@acme.example",
            "userPassword", "{SSHA}xyz", "loginDisabled", "FALSE", "manager", "cn=mjones,ou=users,o=data",
            "ACL", "2#entry#[Self]#loginScript", "ACL", "2#entry#[Self]#printJobConfiguration",
            "DirXML-Associations", "cn=AD,cn=driverset1,o=system#1#S-1-5-21-123", "GUID", "g1");
        v.seed(u);
        v.seed(entry("cn=mjones,ou=users,o=data", List.of("inetOrgPerson", "organizationalPerson", "Person", "Top"),
            "cn", "mjones", "givenName", "Mary", "sn", "Jones", "fullName", "Mary Jones", "mail", "mary.jones@acme.example"));
        v.seed(entry("cn=Sales,ou=users,o=data", List.of("groupOfNames", "Top"), "cn", "Sales",
            "member", "cn=jsmith,ou=users,o=data", "member", "cn=mjones,ou=users,o=data", "member", "cn=admin,ou=sa,o=system"));
        v.seed(entry("cn=outside,o=system", List.of("inetOrgPerson", "Person", "Top"), "cn", "outside", "sn", "Out"));
        return v;
    }

    @Test
    public void identityDataUnderTheNamedContainersIsClonedPseudonymisedAndPasswordsNever() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        eo.dataContainers = List.of("o=data");
        eo.pseudonymise = true;
        CloneExporter.Report r = new CloneExporter(sourceWithPeople(), eo).run();
        CloneBundle b = r.bundle;
        List<Vault.Entry> all = new ArrayList<>(b.containers);
        all.addAll(b.objects);
        Vault.Entry u = find(all, "cn=jsmith,ou=users,o=data");
        assertNotNull(u);
        assertNotNull(find(all, "cn=Sales,ou=users,o=data"));
        assertNull("data outside the named containers stays out", find(all, "cn=outside,o=system"));
        assertEquals(4, r.dataEntries);                                  // two people, two groups (g1 from the base fixture)
        assertEquals(2, r.pseudonymised);
        assertFalse(u.attrs.containsKey("userPassword"));
        assertFalse(u.attrs.containsKey("GUID"));
        assertEquals("FALSE", u.string("loginDisabled"));
        assertNotEquals("John", u.string("givenName"));
        assertNotEquals("Smith", u.string("sn"));
        assertEquals(u.string("givenName") + " " + u.string("sn"), u.string("fullName"));
        assertTrue(u.string("mail"), u.string("mail").endsWith("@acme.example") && !u.string("mail").contains("john"));
        assertEquals("jsmith", u.string("cn"));
        // the person's references and associations travel as references, phase 3
        Map<String, List<byte[]>> refs = b.references.get("cn=jsmith,ou=users,o=data");
        assertEquals("cn=mjones,ou=users,o=data", new String(refs.get("manager").get(0), StandardCharsets.UTF_8));
        assertEquals("cn=AD,cn=driverset1,o=system#1#S-1-5-21-123", new String(refs.get("DirXML-Associations").get(0), StandardCharsets.UTF_8));
        assertEquals(3, b.references.get("cn=Sales,ou=users,o=data").get("member").size());
        // the bundle on disk carries the fake names only
        Path dir = tmp.newFolder("bundle4").toPath();
        b.write(dir);
        String objects = java.nio.file.Files.readString(dir.resolve(CloneBundle.OBJECTS), StandardCharsets.UTF_8);
        assertFalse(objects, objects.contains("John") || objects.contains("Smith") || objects.contains("john.smith"));
        assertEquals(Boolean.TRUE, CloneBundle.read(dir).manifest.get("pseudonymised"));

        // import: every person gets the one lab password, groups and associations resolve; the lab's
        // server grants each new entry the same default ACL the source had, and that is no duplicate
        FakeVault lab = lab();
        lab.defaultAclOnAdd = "2#entry#[Self]#loginScript";
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        o.dryRun = false;
        o.userPassword = "Lab-Pass-1".toCharArray();
        CloneImporter.Result ir = new CloneImporter(CloneBundle.read(dir), lab, o).run();
        assertTrue(ir.text(), ir.ok);
        assertEquals(2, ir.passwordsSet);
        assertEquals("Lab-Pass-1", lab.read("cn=jsmith,ou=users,o=data").string("userPassword"));
        assertNull(lab.read("cn=Sales,ou=users,o=data").string("userPassword"));
        assertEquals(3, lab.read("cn=Sales,ou=users,o=data").strings("member").size());
        assertEquals("cn=AD,cn=driverset1,o=system#1#S-1-5-21-123", lab.read("cn=jsmith,ou=users,o=data").string("DirXML-Associations"));
        assertEquals(2, lab.read("cn=jsmith,ou=users,o=data").strings("ACL").size());     // the server's default plus the one it lacked, no duplicate
        assertTrue(ir.failures.toString(), ir.failures.isEmpty());
        assertEquals(0, ir.verifyMismatches);
        assertTrue(ir.plan, ir.plan.contains("identity data: 4 entries, pseudonymised, one lab password"));
    }

    @Test
    public void withoutDataAssociationsAndPeopleStayOut() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        CloneExporter.Report r = new CloneExporter(sourceWithPeople(), eo).run();
        List<Vault.Entry> all = new ArrayList<>(r.bundle.containers);
        all.addAll(r.bundle.objects);
        assertNull(find(all, "cn=jsmith,ou=users,o=data"));
        assertNull(find(all, "cn=Sales,ou=users,o=data"));
        assertNotNull(find(all, "ou=users,o=data"));
        assertEquals(0, r.dataEntries);
    }

    /** The cloned password policy refuses the lab password: people are still created, and a re-run sets a compliant one. */
    @Test
    public void aRefusedLabPasswordDoesNotStopTheImport() throws Exception {
        CloneExporter.Options eo = new CloneExporter.Options();
        eo.sourceName = "fake";
        eo.dataContainers = List.of("o=data");
        CloneBundle b = new CloneExporter(sourceWithPeople(), eo).run().bundle;
        FakeVault lab = lab();
        lab.refuseUserPasswords = "[LDAP: error code 19 - NDS error: no additional information available (-16000)]";
        CloneImporter.Options o = new CloneImporter.Options();
        o.envName = "lab";
        o.serverDn = "cn=lab1,ou=servers,o=system";
        o.dryRun = false;
        o.userPassword = "bad-pass!".toCharArray();
        CloneImporter.Result r = new CloneImporter(b, lab, o).run();
        assertTrue(r.text(), r.ok);
        assertNotNull(r.passwordRefused);
        assertEquals(0, r.passwordsSet);
        assertEquals(2, r.passwordsRefused);
        assertNotNull(lab.read("cn=jsmith,ou=users,o=data"));
        assertNull(lab.read("cn=jsmith,ou=users,o=data").string("userPassword"));
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("refused for 2 person(s)")));

        lab.refuseUserPasswords = null;
        o.userPassword = "GoodPass1234".toCharArray();
        CloneImporter.Result again = new CloneImporter(b, lab, o).run();
        assertTrue(again.text(), again.ok);
        assertEquals(0, again.added);
        assertEquals(2, again.passwordsSet);
        assertEquals("GoodPass1234", lab.read("cn=jsmith,ou=users,o=data").string("userPassword"));
        assertEquals("GoodPass1234", lab.read("cn=mjones,ou=users,o=data").string("userPassword"));
    }
}
