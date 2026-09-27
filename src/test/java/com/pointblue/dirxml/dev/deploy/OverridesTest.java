package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.GcvOps;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Values that differ per stage: kept per environment beside the tree, applied on deploy, folded back on import. */
public class OverridesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=dvs,o=system";
    private static final String GCVS = "<configuration-values><definitions>"
        + "<definition display-name=\"Domain\" name=\"drv.domain.dns.name\" type=\"string\"><value>lab.example</value></definition>"
        + "<definition display-name=\"Base\" name=\"drv.base\" type=\"dn\"><value>o=data</value></definition>"
        + "</definitions></configuration-values>";
    private static final String SHIM = "<driver-config name=\"AD\"><publisher-options><configuration-values><definitions>"
        + "<definition display-name=\"Heartbeat\" name=\"pub-heartbeat-interval\" type=\"integer\"><value>1</value></definition>"
        + "</definitions></configuration-values></publisher-options></driver-config>";
    private static final String SET_GCVS = "<configuration-values><definitions>"
        + "<definition display-name=\"Company\" name=\"company.name\" type=\"string\"><value>ACME Lab</value></definition>"
        + "</definitions></configuration-values>";

    private static DriverSet model() {
        DriverSet ds = new DriverSet("dvs");
        ds.dn = DS;
        ds.configValues = ValidatorTest.xml(SET_GCVS);
        Driver d = new Driver("AD Driver");
        d.dn = "cn=AD Driver," + DS;
        d.shimClass = "com.example.Shim";
        d.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        d.config.put(Driver.CONFIG_VALUES, ValidatorTest.xml(GCVS));
        d.config.put(Driver.SHIM_CONFIG_INFO, ValidatorTest.xml(SHIM));
        ds.drivers.add(d);
        return ds;
    }

    private static String gcv(DriverSet ds, String driver, String name) {
        return Overrides.valueOf(GcvOps.find(ds, driver == null ? null : ds.driver(driver), name, ds.index()).definition);
    }

    @Test
    public void parseFormatAndTreeRoundTrip() throws Exception {
        Map<String, String> v = Overrides.parse("# comment\n\ndrivers/AD Driver.gcv.drv.domain.dns.name = corp.example.com\n"
            + "driverset.gcv.company.name=ACME\nnonsense line\n");
        assertEquals("corp.example.com", v.get("drivers/AD Driver.gcv.drv.domain.dns.name"));
        assertEquals("ACME", v.get("driverset.gcv.company.name"));
        assertEquals("nonsense line", v.get("?5"));
        String text = Overrides.format("prd", v);
        assertTrue(text, text.contains("drivers/AD Driver.gcv.drv.domain.dns.name = corp.example.com"));
        assertFalse("malformed lines are not written back", text.contains("nonsense"));

        DriverSet ds = model();
        ds.overrides.put("prd", Map.of("drivers/AD Driver.gcv.drv.domain.dns.name", "corp.example.com"));
        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        assertTrue(Files.isRegularFile(t.resolve("overrides/prd.properties")));
        DriverSet back = AsCodeReader.read(t);
        assertEquals("corp.example.com", back.overrides.get("prd").get("drivers/AD Driver.gcv.drv.domain.dns.name"));
        assertEquals("the tree's own file keeps the base value", "lab.example", gcv(back, "AD Driver", "drv.domain.dns.name"));
    }

    @Test
    public void applyPutsAnEnvironmentsValuesIntoTheModel() {
        DriverSet ds = model();
        ds.overrides.put("prd", Map.of(
            "drivers/AD Driver.gcv.drv.domain.dns.name", "corp.example.com",
            "drivers/AD Driver.shim.pub-heartbeat-interval", "5",
            "driverset.gcv.company.name", "ACME Corp",
            "drivers/AD Driver.gcv.nope", "x",
            "drivers/Other.gcv.drv.base", "y"));
        Overrides.Applied a = Overrides.apply(ds, "prd");
        assertEquals(3, a.applied.size());
        assertEquals(2, a.problems.size());
        assertEquals("corp.example.com", gcv(ds, "AD Driver", "drv.domain.dns.name"));
        assertEquals("o=data", gcv(ds, "AD Driver", "drv.base"));
        assertEquals("ACME Corp", gcv(ds, null, "company.name"));
        assertEquals("5", Overrides.valueOf(GcvOps.definition(ds.driver("AD Driver").config.get(Driver.SHIM_CONFIG_INFO), "pub-heartbeat-interval")));
        assertTrue(a.summary(), a.summary().contains("3 value(s) applied") && a.summary().contains("no driver 'Other'"));
        assertNull("an environment without a file applies nothing", Overrides.apply(model(), "stg").summary());
    }

    @Test
    public void foldBackKeepsTheBaseAndRefreshesTheEnvironmentsFile() {
        DriverSet base = model();
        DriverSet live = model();
        // prd's vault holds prd's domain, and someone changed it there since the file was written
        GcvOps.find(live, live.driver("AD Driver"), "drv.domain.dns.name", live.index()).definition
            .getElementsByTagName("value").item(0).setTextContent("corp2.example.com");
        live.overrides.put("prd", new java.util.LinkedHashMap<>(Map.of("drivers/AD Driver.gcv.drv.domain.dns.name", "corp.example.com")));
        List<String> notes = Overrides.foldBack(live, "prd", base);
        assertEquals("corp2.example.com", live.overrides.get("prd").get("drivers/AD Driver.gcv.drv.domain.dns.name"));
        assertEquals("the tree keeps the base value", "lab.example", gcv(live, "AD Driver", "drv.domain.dns.name"));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0), notes.get(0).contains("updated from the vault"));
    }

    @Test
    public void validationReportsUnknownMalformedAndMissingKeys() throws Exception {
        DriverSet ds = model();
        ds.overrides.put("stg", Overrides.parse("drivers/AD Driver.gcv.drv.domain.dns.name = stg.example\nbroken\n"));
        ds.overrides.put("prd", Overrides.parse("drivers/AD Driver.gcv.drv.domain.dns.name = corp.example.com\ndrivers/AD Driver.gcv.nope = 1\n"));
        Report r = Validator.standard().validate(ds);
        assertTrue(r.text(), r.of(Finding.Severity.ERROR).stream().anyMatch(f -> f.code.equals("override-malformed") && f.path.equals("overrides/stg.properties")));
        assertTrue(r.text(), r.of(Finding.Severity.ERROR).stream().anyMatch(f -> f.code.equals("override-unknown") && f.message.contains("nope")));
        assertTrue(r.text(), r.of(Finding.Severity.WARNING).stream().anyMatch(f -> f.code.equals("override-missing-env") && f.path.equals("overrides/stg.properties") && f.message.contains("nope")));
    }

    @Test
    public void deployWritesTheEnvironmentsValue() throws Exception {
        DriverSet ds = model();
        ds.overrides.put("prd", Map.of("drivers/AD Driver.gcv.drv.domain.dns.name", "corp.example.com"));
        Path t = tmp.newFolder("tree2").toPath();
        AsCodeWriter.write(ds, t);
        for (String envName : List.of("stg", "prd")) {
            FakeVault vault = new FakeVault();
            for (com.pointblue.dirxml.sim.LdifDriverSource.Entry e : VaultMappingTest.entries(model())) {
                Vault.Entry ve = new Vault.Entry(e.dn);
                for (String name : e.attributeNames()) {
                    List<byte[]> bytes = new java.util.ArrayList<>();
                    for (String val : e.all(name)) {
                        bytes.add(val.getBytes(StandardCharsets.UTF_8));
                    }
                    ve.attrs.put(name, bytes);
                }
                vault.seed(ve);
            }
            Deployer.Options o = new Deployer.Options();
            o.tree = t;
            o.env = new Environments.Environment(envName, "ldaps://fake:636", "cn=admin,o=system", "pw", DS,
                Environments.Tier.DEV, null, null, true, null, null);
            o.yes = true;
            Deployer.Result r = new Deployer(o, vault).run();
            assertTrue(envName + ": " + r.text(), r.ok);
            String written = vault.read("cn=AD Driver," + DS).string(VaultMapping.CONFIG_VALUES);
            if (envName.equals("prd")) {
                assertTrue(r.planText, r.planText.contains("overrides for 'prd': 1 value(s) applied"));
                assertTrue(written, written.contains("corp.example.com"));
            } else {
                assertFalse("stg has no file: the vault already held the base, nothing to deploy", r.planText.contains("overrides for"));
                assertTrue(written, written.contains("lab.example"));
            }
        }
    }
}
