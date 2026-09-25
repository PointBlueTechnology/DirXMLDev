package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.LdifDriverSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A driver set on two servers: the second server's own driver settings are read through its own
 * connection, kept in the tree only where they differ, diffed and deployed per server, and a
 * change to the primary's value reaches every server that has no override of its own.
 */
public class ServersTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=dvs,o=system";
    private static final String S1 = "cn=idm1,ou=servers,o=system";
    private static final String S2 = "cn=idm2,ou=servers,o=system";
    private static final String GCV_A = "<configuration-values><definitions><definition name=\"x\" type=\"string\"><value>a</value></definition></definitions></configuration-values>";
    private static final String GCV_B = "<configuration-values><definitions><definition name=\"x\" type=\"string\"><value>b</value></definition></definitions></configuration-values>";
    private static final String SHIM = "<driver-config name=\"AD\"><driver-options/></driver-config>";
    private static final String ECV = "<engine-control-values><engine-control name=\"a\"><value>1</value></engine-control></engine-control-values>";

    private static DriverSet model(String gcv, String shim) {
        DriverSet ds = new DriverSet("dvs");
        ds.dn = DS;
        ds.meta.put(Servers.PRIMARY_META, S1);
        ds.servers.add(S1);
        ds.servers.add(S2);
        Driver d = new Driver("AD");
        d.dn = "cn=AD," + DS;
        d.shimClass = "com.example.Shim";
        d.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        d.config.put(Driver.CONFIG_VALUES, ValidatorTest.xml(gcv));
        d.config.put(Driver.SHIM_CONFIG_INFO, ValidatorTest.xml(shim));
        ds.drivers.add(d);
        return ds;
    }

    /** A fake vault for one server: the driver set (with its server list) and the driver with the given settings. */
    private static FakeVault server(String dsaName, String gcv, String shim, String ecv) {
        FakeVault v = new FakeVault();
        Vault.Entry root = new Vault.Entry("");
        root.attrs.put("dsaName", Vault.value(dsaName));
        v.seed(root);
        DriverSet ds = model(gcv, shim);
        if (ecv != null) {
            ds.drivers.get(0).config.put(Driver.ENGINE_CONTROL_VALUES, ValidatorTest.xml(ecv));
        }
        for (LdifDriverSource.Entry e : VaultMappingTest.entries(ds)) {
            Vault.Entry ve = new Vault.Entry(e.dn);
            for (String name : e.attributeNames()) {
                List<byte[]> bytes = new ArrayList<>();
                for (String val : e.all(name)) {
                    bytes.add(val.getBytes(StandardCharsets.UTF_8));
                }
                ve.attrs.put(name, bytes);
            }
            if (e.dn.equalsIgnoreCase(DS)) {
                ve.attrs.put(VaultMapping.SERVER_LIST, List.of(S1.getBytes(StandardCharsets.UTF_8), S2.getBytes(StandardCharsets.UTF_8)));
            }
            v.seed(ve);
        }
        return v;
    }

    @Test
    public void treeKeepsOverridesPerServerAndRoundTrips() throws Exception {
        DriverSet ds = model(GCV_A, SHIM);
        Driver d = ds.drivers.get(0);
        Map<String, org.w3c.dom.Element> s2 = new java.util.LinkedHashMap<>();
        s2.put(Driver.CONFIG_VALUES, ValidatorTest.xml(GCV_B));
        s2.put(Driver.ENGINE_CONTROL_VALUES, null);
        d.serverConfig.put(S2, s2);
        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        assertTrue(Files.isRegularFile(t.resolve("drivers/AD/servers/idm2/config-values.xml")));
        String manifest = Files.readString(t.resolve("drivers/AD/driver.xml"));
        assertTrue(manifest, manifest.contains("<server dn=\"" + S2 + "\">"));
        assertTrue(manifest, manifest.contains("absent=\"true\""));
        assertTrue(Files.readString(t.resolve("driverset.xml")).contains("<server dn=\"" + S1 + "\"/>"));
        DriverSet back = AsCodeReader.read(t);
        assertEquals(List.of(S1, S2), back.servers);
        Map<String, org.w3c.dom.Element> got = back.drivers.get(0).serverConfig.get(S2);
        assertEquals(CanonicalXml.serialize(ValidatorTest.xml(GCV_B)), CanonicalXml.serialize(got.get(Driver.CONFIG_VALUES)));
        assertTrue(got.containsKey(Driver.ENGINE_CONTROL_VALUES));
        assertNull(got.get(Driver.ENGINE_CONTROL_VALUES));
    }

    @Test
    public void readOverridesKeepsOnlyWhatDiffersAndNotesAnUnreachableServer() {
        FakeVault primary = server(S1, GCV_A, SHIM, null);
        FakeVault second = server(S2, GCV_B, SHIM, ECV);
        DriverSet ds = VaultDiff.fromVault(primary, DS);
        List<String> notes = new ArrayList<>();
        Servers.readOverrides(ds, DS, primary, s -> s.equals(S2) ? second : null, notes::add);
        assertEquals(List.of(S1, S2), ds.servers);
        Map<String, org.w3c.dom.Element> o = ds.drivers.get(0).serverConfig.get(S2);
        assertEquals("gcvs differ, shim does not, ecv only there: " + o.keySet(), 2, o.size());
        assertEquals(CanonicalXml.serialize(ValidatorTest.xml(GCV_B)), CanonicalXml.serialize(o.get(Driver.CONFIG_VALUES)));
        assertEquals(CanonicalXml.serialize(ValidatorTest.xml(ECV)), CanonicalXml.serialize(o.get(Driver.ENGINE_CONTROL_VALUES)));
        assertTrue(notes.isEmpty());

        DriverSet ds2 = VaultDiff.fromVault(primary, DS);
        Servers.readOverrides(ds2, DS, primary, s -> null, notes::add);
        assertTrue(ds2.drivers.get(0).serverConfig.isEmpty());
        assertEquals(1, notes.size());
        assertTrue(notes.get(0), notes.get(0).contains(S2) && notes.get(0).contains("not read"));
    }

    @Test
    public void diffAndPlanPerServer_andFanOutOfAPrimaryChange() throws Exception {
        DriverSet from = model(GCV_A, SHIM);
        from.drivers.get(0).serverConfig.put(S2, new java.util.LinkedHashMap<>(Map.of(Driver.CONFIG_VALUES, ValidatorTest.xml(GCV_B))));
        // to: the override changes, and the primary's shim config changes (which every server without a shim override must get)
        DriverSet to = model(GCV_A, SHIM.replace("driver-options/", "driver-options><x>1</x></driver-options"));
        to.drivers.get(0).serverConfig.put(S2, new java.util.LinkedHashMap<>(Map.of(Driver.CONFIG_VALUES, ValidatorTest.xml(GCV_B.replace(">b<", ">c<")))));
        Path t = tmp.newFolder("tree2").toPath();
        AsCodeWriter.write(to, t);
        ModelDiff diff = ModelDiff.of(from, AsCodeReader.read(t));
        String text = diff.text();
        assertTrue(text, text.contains("changed server-specific config config-values on " + S2));
        assertTrue(text, text.contains("changed config shim-config-info"));

        Plan plan = Plan.of(diff, AsCodeReader.read(t), DS, Secrets.none(), "none", null, true, t);
        List<Plan.Step> onS2 = new ArrayList<>();
        for (Plan.Step s : plan.steps) {
            if (S2.equals(s.server)) {
                onS2.add(s);
            }
        }
        assertEquals(onS2.toString(), 2, onS2.size());
        assertTrue(onS2.stream().anyMatch(s -> VaultMapping.CONFIG_VALUES.equals(s.attr) && new String(s.values.get(s.attr).get(0), StandardCharsets.UTF_8).contains(">c<")));
        assertTrue("the primary's shim change fans out to the server without a shim override",
            onS2.stream().anyMatch(s -> VaultMapping.SHIM_CONFIG_INFO.equals(s.attr)));
        assertTrue(plan.steps.stream().anyMatch(s -> s.server == null && VaultMapping.SHIM_CONFIG_INFO.equals(s.attr)));
        assertEquals(java.util.Set.of(S2), plan.serverRestarts.get("AD"));
        assertTrue(plan.text("t", DS).contains("@ " + S2));

        // an override removed: the server is written the primary's value
        DriverSet to2 = model(GCV_A, SHIM);
        Path t2 = tmp.newFolder("tree3").toPath();
        AsCodeWriter.write(to2, t2);
        ModelDiff diff2 = ModelDiff.of(from, AsCodeReader.read(t2));
        assertTrue(diff2.text(), diff2.text().contains("removed server-specific config config-values on " + S2));
        Plan plan2 = Plan.of(diff2, AsCodeReader.read(t2), DS, Secrets.none(), "none", null, true, t2);
        Plan.Step back = plan2.steps.stream().filter(s -> S2.equals(s.server)).findFirst().orElseThrow();
        assertTrue(new String(back.values.get(VaultMapping.CONFIG_VALUES).get(0), StandardCharsets.UTF_8).contains(">a<"));
        assertTrue(back.description, back.description.contains("back to the primary's value"));
    }

    @Test
    public void deployWritesEachServerThroughItsOwnConnectionAndVerifies() throws Exception {
        FakeVault primary = server(S1, GCV_A, SHIM, null);
        FakeVault second = server(S2, GCV_A, SHIM, null);
        // the tree: primary gcvs → b (fans out), plus a shim override for the second server only
        DriverSet to = model(GCV_B, SHIM);
        to.drivers.get(0).serverConfig.put(S2, new java.util.LinkedHashMap<>(Map.of(Driver.SHIM_CONFIG_INFO,
            ValidatorTest.xml(SHIM.replace("driver-options/", "driver-options><only>idm2</only></driver-options")))));
        Path t = tmp.newFolder("tree4").toPath();
        AsCodeWriter.write(to, t);
        Deployer.Options o = new Deployer.Options();
        o.tree = t;
        o.env = new Environments.Environment("test", "ldaps://fake:636", "cn=admin,o=system", "pw", DS,
            Environments.Tier.STG, null, null, true, null, null);
        o.yes = true;
        Deployer dep = new Deployer(o, primary);
        dep.testServers = Map.of(S2, second);
        Deployer.Result r = dep.run();
        assertTrue(r.text(), r.ok);
        assertTrue(r.text(), r.verified);
        String dn = "cn=AD," + DS;
        assertTrue(primary.read(dn).string(VaultMapping.CONFIG_VALUES).contains(">b<"));
        assertTrue("fanned out", second.read(dn).string(VaultMapping.CONFIG_VALUES).contains(">b<"));
        assertTrue("the override landed on the second server only", second.read(dn).string(VaultMapping.SHIM_CONFIG_INFO).contains("<only>idm2</only>"));
        assertFalse(primary.read(dn).string(VaultMapping.SHIM_CONFIG_INFO).contains("<only>"));
        assertTrue(r.text(), r.skipped.stream().anyMatch(s -> s.startsWith("snapshot for " + S2)));
        assertTrue(Files.isDirectory(t.resolve("deploy-snapshots/test/idm2")));
    }
}
