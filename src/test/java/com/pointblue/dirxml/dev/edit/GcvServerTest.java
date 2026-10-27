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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A driver's GCVs on a set served by several servers (#134): idm1 is the primary, idm2 holds its own copy of
 * the driver's values (drv.users = b, and drv.extra only there), idm3 follows the primary. {@code gcv.set
 * --server} writes one server's copy; {@link GcvOps#onServers} says where a value differs.
 */
public class GcvServerTest {

    private static final String S1 = "cn=idm1,ou=servers,o=system";
    private static final String S2 = "cn=idm2,ou=servers,o=system";
    private static final String S3 = "cn=idm3,ou=servers,o=system";
    private static final String GCV_A = "<configuration-values><definitions><definition display-name=\"u\" name=\"drv.users\" type=\"string\"><value>a</value></definition></definitions></configuration-values>";
    private static final String GCV_B = "<configuration-values><definitions><definition display-name=\"u\" name=\"drv.users\" type=\"string\"><value>b</value></definition>"
        + "<definition display-name=\"x\" name=\"drv.extra\" type=\"string\"><value>x</value></definition></definitions></configuration-values>";

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
        Map<String, org.w3c.dom.Element> s2 = new LinkedHashMap<>();
        s2.put(Driver.CONFIG_VALUES, ValidatorTest.xml(GCV_B));
        ad.serverConfig.put(S2, s2);
        tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    private static GcvOps.Set set(String name, String value, String server) {
        return new GcvOps.Set("AD", name, value, null, null, server);
    }

    @Test
    public void onServersNamesWhereAValueDiffers() throws Exception {
        DriverSet ds = AsCodeReader.read(tree);
        Driver d = ds.driver("AD");
        assertEquals(Map.of(S2, "b"), GcvOps.onServers(ds, d, "drv.users"));
        Map<String, String> extra = GcvOps.onServers(ds, d, "drv.extra");
        assertEquals(1, extra.size());
        assertEquals("x", extra.get(S2));
        assertEquals(List.of("drv.extra"), GcvOps.onlyOnServers(d));
        assertTrue("the driver set's own GCVs are every server's", GcvOps.onServers(ds, null, "drv.users").isEmpty());
    }

    @Test
    public void serverValueIsWrittenToThatServersCopyOnly() throws Exception {
        Result r = run(set("drv.users", "c", "idm2"));
        assertTrue(r.text(), r.ok());
        DriverSet ds = AsCodeReader.read(tree);
        Driver d = ds.driver("AD");
        assertEquals("c", GcvOps.valueIn(d.serverConfig.get(S2).get(Driver.CONFIG_VALUES), "drv.users"));
        assertEquals("the primary keeps its value", "a", GcvOps.valueIn(d.config.get(Driver.CONFIG_VALUES), "drv.users"));
        assertFalse("idm3 still follows the primary", d.serverConfig.containsKey(S3));
    }

    @Test
    public void aServerThatFollowsThePrimaryGetsItsOwnCopy() throws Exception {
        assertTrue(run(set("drv.users", "z", "idm3")).ok());
        Driver d = AsCodeReader.read(tree).driver("AD");
        assertEquals("z", GcvOps.valueIn(d.serverConfig.get(S3).get(Driver.CONFIG_VALUES), "drv.users"));
        assertEquals("a", GcvOps.valueIn(d.config.get(Driver.CONFIG_VALUES), "drv.users"));

        // and back to the primary's value: the copy equals the primary's again and goes
        assertTrue(run(set("drv.users", "a", "idm3")).ok());
        assertFalse(AsCodeReader.read(tree).driver("AD").serverConfig.containsKey(S3));
    }

    @Test
    public void primaryOrNoServerIsTheDriversOwnValue() throws Exception {
        assertTrue(run(set("drv.users", "p", "primary")).ok());
        Driver d = AsCodeReader.read(tree).driver("AD");
        assertEquals("p", GcvOps.valueIn(d.config.get(Driver.CONFIG_VALUES), "drv.users"));
        assertEquals("idm2 keeps its own", "b", GcvOps.valueIn(d.serverConfig.get(S2).get(Driver.CONFIG_VALUES), "drv.users"));
    }

    @Test
    public void refusals() throws Exception {
        Result missing = run(set("drv.extra", "y", "idm3"));
        assertFalse(missing.ok());
        assertTrue(missing.text(), missing.text().contains("driver.server.copy --from primary --to idm3"));

        Result unknown = run(set("drv.users", "y", "idm9"));
        assertFalse(unknown.ok());
        assertTrue(unknown.text(), unknown.text().contains("not a server of this driver set"));

        Result noDriver = run(new GcvOps.Set(null, "drv.users", "y", null, null, "idm2"));
        assertFalse(noDriver.ok());
        assertTrue(noDriver.text(), noDriver.text().contains("--server needs --driver"));

        Result define = run(new GcvOps.Set("AD", "drv.new", "y", "string", null, "idm2"));
        assertFalse(define.ok());
        assertTrue(define.text(), define.text().contains("--define makes a GCV on the primary"));
    }

    @Test
    public void aValueOnlyAnotherServerDefinesIsSetThere() throws Exception {
        assertTrue(run(set("drv.extra", "y", "idm2")).ok());
        Driver d = AsCodeReader.read(tree).driver("AD");
        assertEquals("y", GcvOps.valueIn(d.serverConfig.get(S2).get(Driver.CONFIG_VALUES), "drv.extra"));
        assertNull(GcvOps.valueIn(d.config.get(Driver.CONFIG_VALUES), "drv.extra"));
    }
}
