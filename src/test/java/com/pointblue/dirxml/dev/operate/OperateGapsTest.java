package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Vault;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Identity Console gaps closed in the core: start option, associations, password sync, object inspection. */
public class OperateGapsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Environments.Environment env(Path dir) throws Exception {
        Path f = dir.resolve("environments.properties");
        Files.writeString(f, "lab.url=ldaps://lab:636\nlab.bindDn=cn=admin,o=system\nlab.password=pw\nlab.driverSet=cn=driverset1,o=system\nlab.tier=dev\n", StandardCharsets.UTF_8);
        return Environments.load(f).get("lab");
    }

    @Test
    public void associationAndPasswordSyncValuesParse() {
        Operate.Association a = Operate.parseAssociation("cn=AD,cn=driverset1,o=system#1#8e19b92af7b8a6459a99fe01638a9430");
        assertEquals("AD", a.driverName());
        assertEquals("processed", Operate.associationState(a.state));
        assertEquals("8e19b92af7b8a6459a99fe01638a9430", a.value);
        assertNull(Operate.parseAssociation("no hashes"));
        assertNull(Operate.parseAssociation("cn=x#notanumber#v"));
        assertEquals("disabled", Operate.associationState(0));
        assertEquals("migrate", Operate.associationState(4));
        assertEquals(2, Operate.associationStateOf("pending"));
        assertEquals(-1, Operate.associationStateOf("whatever"));
        String[] p = Operate.parsePasswordSync("cn=AD,cn=driverset1,o=system#20261009120000#Password set");
        assertEquals("20261009120000", p[1]);
        assertEquals("Password set", p[2]);
        assertEquals(Vault.START_AUTO, Operate.startOptionOf("auto"));
        assertEquals(-1, Operate.startOptionOf("sometimes"));
    }

    @Test
    public void theStartOptionIsSetLiveGatedAndAudited() throws Exception {
        Path tree = tmp.newFolder("tree").toPath();
        Environments.Environment lab = env(tmp.newFolder("etc").toPath());
        OperateTest.FakeEngine engine = new OperateTest.FakeEngine();
        String dn = "cn=IG Update,cn=driverset1,o=system";
        engine.startOptions.put(dn, Vault.START_MANUAL);
        Operate.Result r = Operate.startOption(engine, lab, "IG Update", "disabled", true, null, tree);
        assertTrue(r.text, r.ok);
        assertEquals(Integer.valueOf(Vault.START_DISABLED), engine.startOptions.get(dn));
        assertTrue(r.text.contains("manual → disabled"));
        assertTrue(Files.readString(tree.resolve("deploy-log").resolve("lab.jsonl")).contains("driver.start-option"));
        assertFalse(Operate.startOption(engine, lab, "IG Update", "overflow", true, null, tree).ok);
    }

    @Test
    public void associationsAreSearchedByStateAndAnObjectInspected() throws Exception {
        Environments.Environment lab = env(tmp.newFolder("etc").toPath());
        OperateTest.FakeEngine engine = new OperateTest.FakeEngine();
        String dn = "cn=AD,cn=driverset1,o=system";
        Vault.Entry u = new Vault.Entry("cn=jdoe,ou=users,o=data");
        u.attrs.put("objectClass", List.of("User".getBytes(StandardCharsets.UTF_8)));
        u.attrs.put("DirXML-Associations", List.of((dn + "#1#abc").getBytes(StandardCharsets.UTF_8), "cn=Other,cn=driverset1,o=system#2#def".getBytes(StandardCharsets.UTF_8)));
        u.attrs.put("DirXML-PasswordSyncStatus", List.of((dn + "#20261009120000#ok").getBytes(StandardCharsets.UTF_8)));
        engine.entries.put(u.dn, u);
        engine.searches.put("(DirXML-Associations=" + dn + "#1#*)", List.of(u));
        Operate.Result r = Operate.driverAssociations(engine, lab, "AD", null, null, 50);
        assertTrue(r.ok);
        assertTrue(r.text, r.text.contains("processed  1") && r.text.contains("cn=jdoe,ou=users,o=data") && r.text.contains("abc"));
        assertTrue(r.json.contains("\"processed\":1") && r.json.contains("\"total\":1"));
        Operate.Result one = Operate.driverAssociations(engine, lab, "AD", "pending", null, 50);
        assertTrue(one.text.contains("pending    0") && !one.text.contains("processed"));
        assertFalse(Operate.driverAssociations(engine, lab, "AD", "odd", null, 50).ok);
        Operate.Result i = Operate.inspectObject(engine, lab, u.dn);
        assertTrue(i.ok);
        assertTrue(i.text, i.text.contains("AD") && i.text.contains("processed") && i.text.contains("Other") && i.text.contains("pending") && i.text.contains("20261009120000"));
        assertTrue(i.json.contains("\"passwordSync\":[{\"driver\":\"AD\""));
        assertFalse(Operate.inspectObject(engine, lab, "cn=nobody,o=data").ok);
    }
}
