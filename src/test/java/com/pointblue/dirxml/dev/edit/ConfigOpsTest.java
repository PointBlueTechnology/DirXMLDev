package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ConfigOpsTest {

    private Path tree;

    @Before
    public void writeTree() throws IOException {
        tree = Files.createTempDirectory("idm-config");
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");
        ad.config.put(Driver.SHIM_CONFIG_INFO, ValidatorTest.xml(
            "<driver-config name=\"AD\"><driver-options><configuration-values><definitions>"
                + "<definition display-name=\"Use SSL\" name=\"use-ssl\" type=\"boolean\"><value>false</value></definition>"
                + "</definitions></configuration-values></driver-options></driver-config>"));
        ad.config.put(Driver.ENGINE_CONTROL_VALUES, ValidatorTest.xml(
            "<configuration-values><definitions>"
                + "<definition display-name=\"Retry\" name=\"dirxml.engine.retry-interval\" type=\"integer\"><value>30</value></definition>"
                + "</definitions></configuration-values>"));
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws IOException {
        return Transaction.open(tree).run(op, false, false);
    }

    private Element filter() throws IOException {
        return AsCodeReader.read(tree).driver("AD").config.get(Driver.DRIVER_FILTER);
    }

    private String schemaMap() throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        return CanonicalXml.serialize(((Policy) ds.resolve("drivers/AD/smp")).content).replaceAll("\\s+", "");
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put(kv[i], kv[i + 1]);
        }
        return out;
    }

    // ---- filter ----

    @Test
    public void filterClassAndAttr() throws IOException {
        Result c = run(new ConfigOps.FilterSetClass("AD", "Group", m("publisher", "ignore", "subscriber", "sync")));
        assertTrue(c.text(), c.ok());
        Element g = ConfigOps.filterClass(filter(), "Group");
        assertEquals("ignore", g.getAttribute("publisher"));
        Result a = run(new ConfigOps.FilterSetAttr("AD", "Group", "Member", m("merge-authority", "app", "publisher", "notify")));
        assertTrue(a.text(), a.ok());
        Element mem = ConfigOps.filterAttr(ConfigOps.filterClass(filter(), "Group"), "Member");
        assertEquals("app", mem.getAttribute("merge-authority"));
        assertEquals("notify", mem.getAttribute("publisher"));
        assertEquals("sync", mem.getAttribute("subscriber"));   // default
        // update in place, no duplicate
        assertTrue(run(new ConfigOps.FilterSetAttr("AD", "Group", "Member", m("subscriber", "ignore"))).ok());
        assertEquals(1, Xds.childrenByName(ConfigOps.filterClass(filter(), "Group"), "filter-attr").size());
        assertEquals("ignore", ConfigOps.filterAttr(ConfigOps.filterClass(filter(), "Group"), "Member").getAttribute("subscriber"));
        // the validator's enums are enforced
        assertTrue(run(new ConfigOps.FilterSetAttr("AD", "Group", "Member", m("publisher", "maybe"))).refusal.contains("sync|ignore|notify|reset"));
        assertTrue(run(new ConfigOps.FilterSetAttr("AD", "Nope", "X", m())).refusal.contains("filter.set-class first"));
        // remove
        assertTrue(run(new ConfigOps.FilterRemoveAttr("AD", "Group", "Member")).ok());
        assertNull(ConfigOps.filterAttr(ConfigOps.filterClass(filter(), "Group"), "Member"));
        assertTrue(run(new ConfigOps.FilterRemoveClass("AD", "Group")).ok());
        assertNull(ConfigOps.filterClass(filter(), "Group"));
        assertTrue(run(new ConfigOps.FilterRemoveClass("AD", "Group")).refusal.contains("not in the filter"));
        assertTrue(run(new ConfigOps.FilterRemoveAttr("AD", "User", "Nope")).refusal.contains("not in the filter"));
    }

    // ---- schema map ----

    @Test
    public void schemaMapSetAndRemove() throws IOException {
        Result c = run(new ConfigOps.SchemaMapSet("AD", "Group", "group", null, null));
        assertTrue(c.text(), c.ok());
        assertTrue(schemaMap(), schemaMap().contains("<class-name><app-name>group</app-name><nds-name>Group</nds-name></class-name>"));
        Result a = run(new ConfigOps.SchemaMapSet("AD", "User", null, "Given Name", "givenName"));
        assertTrue(a.text(), a.ok());
        assertTrue(schemaMap(), schemaMap().contains("<nds-name>User</nds-name><attr-name><app-name>givenName</app-name><nds-name>GivenName</nds-name></attr-name>"));
        Result top = run(new ConfigOps.SchemaMapSet("AD", null, null, "Surname", "sn"));
        assertTrue(top.text(), top.ok());
        assertTrue(schemaMap(), schemaMap().contains("</class-name><attr-name><app-name>sn</app-name><nds-name>Surname</nds-name></attr-name></attr-name-map>"));
        // update app name in place
        assertTrue(run(new ConfigOps.SchemaMapSet("AD", "User", null, "Given Name", "firstName")).ok());
        assertTrue(schemaMap().contains("<app-name>firstName</app-name><nds-name>GivenName</nds-name>"));
        assertFalse(schemaMap().contains("givenName"));
        // refusals
        assertTrue(run(new ConfigOps.SchemaMapSet("AD", "Nope", null, null, null)).refusal.contains("--app-class"));
        assertTrue(run(new ConfigOps.SchemaMapSet("AD", null, null, "X", null)).refusal.contains("--app-attr"));
        assertTrue(run(new ConfigOps.SchemaMapRemove("AD", "User", "Nope")).refusal.contains("not mapped"));
        // remove
        assertTrue(run(new ConfigOps.SchemaMapRemove("AD", "User", "Given Name")).ok());
        assertFalse(schemaMap().contains("firstName"));
        assertTrue(run(new ConfigOps.SchemaMapRemove("AD", null, "Surname")).ok());
        assertFalse(schemaMap().contains("Surname"));
        assertTrue(run(new ConfigOps.SchemaMapRemove("AD", "Group", null)).ok());
        assertFalse(schemaMap().contains("Group"));
        assertTrue(run(new ConfigOps.SchemaMapRemove("AD", "User", null)).report.ok());
    }

    // ---- driver.set ----

    @Test
    public void driverSet() throws IOException {
        assertTrue(run(new ConfigOps.DriverSet_("AD", "shim-class", "com.example.NewShim")).ok());
        assertTrue(run(new ConfigOps.DriverSet_("AD", "shim-auth-id", "svc-idm")).ok());
        assertTrue(run(new ConfigOps.DriverSet_("AD", "param:use-ssl", "true")).ok());
        assertTrue(run(new ConfigOps.DriverSet_("AD", "engine:dirxml.engine.retry-interval", "60")).ok());
        Driver d = AsCodeReader.read(tree).driver("AD");
        assertEquals("com.example.NewShim", d.shimClass);
        assertEquals("svc-idm", d.shimAuthId);
        assertEquals("true", Xds.text(Xds.childrenByName(GcvOps.definition(d.config.get(Driver.SHIM_CONFIG_INFO), "use-ssl"), "value").get(0)));
        assertEquals("60", Xds.text(Xds.childrenByName(GcvOps.definition(d.config.get(Driver.ENGINE_CONTROL_VALUES), "dirxml.engine.retry-interval"), "value").get(0)));
        assertTrue(run(new ConfigOps.DriverSet_("AD", "param:nope", "x")).refusal.contains("use-ssl"));
        assertTrue(run(new ConfigOps.DriverSet_("AD", "colour", "x")).refusal.contains("key must be"));
    }

    // ---- mapping tables ----

    @Test
    public void tableRowsAndColumns() throws IOException {
        Result add = run(new ConfigOps.TableSetRow("library/CodeMap", null, m("code", "2", "dn", "ou=b")));
        assertTrue(add.text(), add.ok());
        Result upd = run(new ConfigOps.TableSetRow("library/CodeMap", null, m("code", "1", "dn", "ou=a2")));
        assertTrue(upd.ok());
        Element t = ((Resource) AsCodeReader.read(tree).resolve("library/CodeMap")).content;
        assertEquals(2, Xds.childrenByName(t, "row").size());
        assertEquals("ou=a2", Xds.text(Xds.childrenByName(ConfigOps.rowByKey(t, 0, "1"), "col").get(1)));
        assertTrue(run(new ConfigOps.TableSetRow("library/CodeMap", null, m("nope", "x"))).refusal.contains("no column"));
        assertTrue(run(new ConfigOps.TableSetRow("library/CodeMap", null, m("dn", "x"))).refusal.contains("key column"));

        Result col = run(new ConfigOps.TableAddColumn("library/CodeMap", "region", null));
        assertTrue(col.text(), col.ok());
        t = ((Resource) AsCodeReader.read(tree).resolve("library/CodeMap")).content;
        assertEquals(3, ConfigOps.columns(t).size());
        assertEquals(3, Xds.childrenByName(Xds.childrenByName(t, "row").get(0), "col").size());
        assertTrue(col.report.withCode("mapping-table-ragged-row").isEmpty());
        assertTrue(run(new ConfigOps.TableSetRow("library/CodeMap", "dn", m("dn", "ou=b", "region", "west"))).ok());
        t = ((Resource) AsCodeReader.read(tree).resolve("library/CodeMap")).content;
        assertEquals("west", Xds.text(Xds.childrenByName(ConfigOps.rowByKey(t, 1, "ou=b"), "col").get(2)));

        assertTrue(run(new ConfigOps.TableDeleteRow("library/CodeMap", null, "2")).ok());
        assertEquals(1, Xds.childrenByName(((Resource) AsCodeReader.read(tree).resolve("library/CodeMap")).content, "row").size());
        assertTrue(run(new ConfigOps.TableDeleteRow("library/CodeMap", null, "2")).refusal.contains("no row"));
        assertTrue(run(new ConfigOps.TableAddColumn("library/CodeMap", "region", null)).refusal.contains("already exists"));
        assertTrue(run(new ConfigOps.TableSetRow("drivers/AD/smp", null, m("a", "b"))).refusal.contains("not a mapping table"));
    }

    @Test
    public void cliParsesRepeatedCols() throws Exception {
        int code = EditCli.run(new String[] {"mapping-table.set-row", tree.toString(), "--path", "library/CodeMap",
            "--col", "code=9", "--col", "dn=ou=nine", "--json"});
        assertEquals(0, code);
        Element t = ((Resource) AsCodeReader.read(tree).resolve("library/CodeMap")).content;
        assertEquals("ou=nine", Xds.text(Xds.childrenByName(ConfigOps.rowByKey(t, 0, "9"), "col").get(1)));
    }
}
