package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Stage overrides and whole configuration documents as edit operations (what a UI's editors call). */
public class OverrideAndConfigContentOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path tree;

    @Before
    public void setUp() throws Exception {
        tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ValidatorTest.clean(), tree);
    }

    private Result run(Operation op) throws Exception {
        return Transaction.open(tree).run(op, false, false);
    }

    @Test
    public void overrideSetWritesTheEnvironmentsFile_andRemoveTakesItAway() throws Exception {
        Result r = run(new OverrideOps.Set("prd", "drivers/AD.gcv.drv.users", "ou=people,o=corp"));
        assertTrue(r.text(), r.ok());
        assertTrue(r.changedFiles.toString(), r.changedFiles.contains("overrides/prd.properties"));
        assertEquals("ou=people,o=corp", AsCodeReader.read(tree).overrides.get("prd").get("drivers/AD.gcv.drv.users"));
        DriverSet ds = AsCodeReader.read(tree);
        assertEquals("the base is untouched", "users", Overrides.resolve(ds, "drivers/AD.gcv.drv.users").value());
        assertTrue(r.notes.toString(), r.notes.get(0).contains("base value is users"));

        Result again = run(new OverrideOps.Set("prd", "drivers/AD.gcv.drv.users", "ou=staff,o=corp"));
        assertTrue(again.notes.toString(), again.notes.get(0).contains("was ou=people,o=corp"));

        Result gone = run(new OverrideOps.Remove("prd", "drivers/AD.gcv.drv.users"));
        assertTrue(gone.text(), gone.ok());
        assertTrue(gone.deletedFiles.toString(), gone.deletedFiles.contains("overrides/prd.properties"));
        assertFalse(Files.exists(tree.resolve("overrides/prd.properties")));
        assertNull(AsCodeReader.read(tree).overrides.get("prd"));
    }

    @Test
    public void overrideRefusals() throws Exception {
        Result unknown = run(new OverrideOps.Set("prd", "drivers/AD.gcv.no.such", "x"));
        assertFalse(unknown.ok());
        assertTrue(unknown.refusal, unknown.refusal.contains("define no GCV 'no.such'"));
        assertFalse(run(new OverrideOps.Set("../etc", "drivers/AD.gcv.drv.users", "x")).ok());
        assertFalse(run(new OverrideOps.Remove("prd", "drivers/AD.gcv.drv.users")).ok());
        assertFalse(Files.exists(tree.resolve("overrides")));
    }

    @Test
    public void configSetContentReplacesADocument_andRefusesTheWrongRoot() throws Exception {
        String gcvs = "<configuration-values><definitions>"
            + "<definition display-name=\"Users\" name=\"drv.users\" type=\"string\"><value>people</value></definition>"
            + "<definition display-name=\"Extra\" name=\"drv.extra\" type=\"string\"><value>1</value></definition>"
            + "</definitions></configuration-values>";
        Result r = run(new ConfigOps.SetContent("AD", Driver.CONFIG_VALUES, gcvs));
        assertTrue(r.text(), r.ok());
        assertTrue(r.changedFiles.toString(), r.changedFiles.contains("drivers/AD/config-values.xml"));
        DriverSet ds = AsCodeReader.read(tree);
        assertEquals("people", Overrides.resolve(ds, "drivers/AD.gcv.drv.users").value());
        assertEquals("1", Overrides.resolve(ds, "drivers/AD.gcv.drv.extra").value());

        Result set = run(new ConfigOps.SetContent(null, Driver.CONFIG_VALUES,
            "<configuration-values><definitions><definition display-name=\"Co\" name=\"co.name\" type=\"string\"><value>ACME</value></definition></definitions></configuration-values>"));
        assertTrue(set.text(), set.ok());
        assertEquals("ACME", Overrides.resolve(AsCodeReader.read(tree), "driverset.gcv.co.name").value());

        assertTrue(run(new ConfigOps.SetContent("AD", Driver.DRIVER_FILTER, "<configuration-values/>")).refusal.contains("<filter>"));
        assertTrue(run(new ConfigOps.SetContent("AD", Driver.CONFIG_VALUES, "<configuration-values>")).refusal.contains("not well-formed"));
        assertTrue(run(new ConfigOps.SetContent(null, Driver.DRIVER_FILTER, "<filter/>")).refusal.contains("name a --driver"));
        assertTrue(run(new ConfigOps.SetContent("AD", "nope", "<x/>")).refusal.contains("--kind is one of"));
    }

    @Test
    public void theOperationsAreInTheCatalog() throws Exception {
        for (String name : new String[] {"override.set", "override.remove", "config.set-content"}) {
            assertTrue(name, Registry.get(name) != null);
        }
        assertTrue(Registry.missing(Registry.get("override.set"), Map.of("env", "prd")) != null);
    }
}
