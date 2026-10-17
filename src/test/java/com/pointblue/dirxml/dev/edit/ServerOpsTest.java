package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.Servers;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Copying a driver's server-specific settings between the servers of a set (#134): idm1 is the primary, idm2
 * holds its own GCV values and shim account, idm3 holds what the primary holds. Each copy leaves the other
 * servers as they were, and the tree keeps only what differs from the primary.
 */
public class ServerOpsTest {

    private static final String S1 = "cn=idm1,ou=servers,o=system";
    private static final String S2 = "cn=idm2,ou=servers,o=system";
    private static final String S3 = "cn=idm3,ou=servers,o=system";
    private static final String GCV_A = "<configuration-values><definitions><definition display-name=\"u\" name=\"drv.users\" type=\"string\"><value>a</value></definition></definitions></configuration-values>";
    private static final String GCV_B = "<configuration-values><definitions><definition display-name=\"u\" name=\"drv.users\" type=\"string\"><value>b</value></definition></definitions></configuration-values>";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path tree;

    @Before
    public void setUp() throws Exception {
        DriverSet ds = ValidatorTest.clean();
        ds.servers.add(S1);
        ds.servers.add(S2);
        ds.servers.add(S3);
        ds.meta.put(Servers.PRIMARY_META, S1);
        Driver ad = ds.driver("AD");
        ad.config.put(Driver.CONFIG_VALUES, ValidatorTest.xml(GCV_A));
        ad.shimAuthId = "svc-primary";
        Map<String, org.w3c.dom.Element> s2 = new LinkedHashMap<>();
        s2.put(Driver.CONFIG_VALUES, ValidatorTest.xml(GCV_B));
        ad.serverConfig.put(S2, s2);
        ad.serverSettings.put(S2, new LinkedHashMap<>(Map.of(Driver.SHIM_AUTH_ID, "svc-idm2")));
        tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    private static String xml(org.w3c.dom.Element e) {
        return e == null ? null : CanonicalXml.serialize(e);
    }

    private Driver ad() throws Exception {
        return AsCodeReader.read(tree).driver("AD");
    }

    @Test
    public void anotherServerTakesWhatTheSourceHoldsAndNothingElseMoves() throws Exception {
        Result r = run(new ServerOps.Copy("idm2", "idm3", "AD", null));
        assertTrue(r.text(), r.ok());
        assertTrue(r.notes.toString(), r.notes.get(0).startsWith("AD: gcv, auth-id copied from idm2 to idm3"));
        Driver d = ad();
        assertEquals(xml(ValidatorTest.xml(GCV_B)), xml(d.serverConfig.get(S3).get(Driver.CONFIG_VALUES)));
        assertEquals("svc-idm2", d.serverSettings.get(S3).get(Driver.SHIM_AUTH_ID));
        assertEquals("idm2 is untouched", xml(ValidatorTest.xml(GCV_B)), xml(d.serverConfig.get(S2).get(Driver.CONFIG_VALUES)));
        assertEquals("the primary is untouched", xml(ValidatorTest.xml(GCV_A)), xml(d.config.get(Driver.CONFIG_VALUES)));
        assertEquals("svc-primary", d.shimAuthId);

        // the primary's values to idm2: idm2 then holds what the primary holds, so its overrides go
        assertTrue(run(new ServerOps.Copy("primary", "idm2", null, null)).ok());
        assertFalse(ad().serverConfig.containsKey(S2));
        assertFalse(ad().serverSettings.containsKey(S2));
    }

    @Test
    public void copyingToThePrimaryKeepsTheServersThatFollowedIt() throws Exception {
        // idm3 follows the primary (value a); the primary takes idm2's value b; idm3 must keep a
        Result r = run(new ServerOps.Copy("idm2", "primary", "AD", "gcv"));
        assertTrue(r.text(), r.ok());
        Driver d = ad();
        assertEquals(xml(ValidatorTest.xml(GCV_B)), xml(d.config.get(Driver.CONFIG_VALUES)));
        assertFalse("idm2 now equals the primary: no override", d.serverConfig.getOrDefault(S2, Map.of()).containsKey(Driver.CONFIG_VALUES));
        assertEquals("idm3 keeps what it held", xml(ValidatorTest.xml(GCV_A)), xml(d.serverConfig.get(S3).get(Driver.CONFIG_VALUES)));
        assertEquals("--only gcv: the account is not copied", "svc-primary", d.shimAuthId);
    }

    @Test
    public void refusalsAndNothingToCopy() throws Exception {
        assertFalse(run(new ServerOps.Copy("idm2", "idm2", null, null)).ok());
        assertFalse(run(new ServerOps.Copy("idm9", "idm2", null, null)).ok());
        assertFalse(run(new ServerOps.Copy("idm2", "idm3", null, "gcv,passwords")).ok());
        Result same = run(new ServerOps.Copy("primary", "idm3", "AD", null));
        assertTrue(same.text(), same.ok());
        assertTrue(same.notes.toString(), same.notes.get(0).contains("nothing to copy"));
        assertNull(ad().serverConfig.get(S3));
    }
}
