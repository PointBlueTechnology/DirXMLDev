package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A shim auth id that is a credential comes from the secrets file: applied for a diff and a deploy, never shown, never imported. */
public class SecretShimAuthIdTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=dvs,o=system";
    private static final String CLIENT_ID = "m18j0iCLIENTid";

    private static DriverSet model(String authId) {
        DriverSet ds = new DriverSet("dvs");
        ds.dn = DS;
        Driver d = new Driver("Beeline");
        d.dn = "cn=Beeline," + DS;
        d.shimClass = "com.example.Shim";
        d.shimAuthServer = "https://auth.example/oauth/token";
        d.shimAuthId = authId;
        d.config.put(Driver.DRIVER_FILTER, ValidatorTest.xml("<filter/>"));
        d.config.put(Driver.CONFIG_VALUES, ValidatorTest.xml("<configuration-values><definitions/></configuration-values>"));
        ds.drivers.add(d);
        return ds;
    }

    private Secrets secrets() throws Exception {
        Path f = tmp.newFile("secrets-prd.properties").toPath();
        Files.writeString(f, "Beeline.shim-auth-id=" + CLIENT_ID + "\n", StandardCharsets.UTF_8);
        return Secrets.load(f);
    }

    @Test
    public void theSecretsFileSuppliesTheIdAndNothingShowsIt() throws Exception {
        DriverSet to = model(null);                 // the tree carries no id
        Secrets s = secrets();
        assertEquals(List.of("Beeline"), Overrides.applySecretShimAuthIds(to, s));
        assertEquals(CLIENT_ID, to.driver("Beeline").shimAuthId);
        assertTrue(to.driver("Beeline").shimAuthIdSecret);

        DriverSet from = model("stale-client-id");   // the vault holds another
        ModelDiff diff = ModelDiff.of(from, to);
        String text = diff.text();
        assertTrue(text, text.contains("shim-auth-id: (the secrets file's value differs from the vault's)"));
        assertFalse(text, text.contains(CLIENT_ID));
        assertFalse(text, text.contains("stale-client-id"));

        Plan plan = Plan.of(diff, to, DS, s, "none", null, true, tmp.getRoot().toPath());
        Plan.Step step = plan.steps.stream().filter(x -> VaultMapping.SHIM_AUTH_ID.equals(x.attr)).findFirst().orElseThrow();
        assertEquals(CLIENT_ID, new String(step.values.get(VaultMapping.SHIM_AUTH_ID).get(0), StandardCharsets.UTF_8));
        assertTrue(step.description, step.description.contains("(from the secrets file)"));
        assertFalse(step.description, step.description.contains(CLIENT_ID));
        assertFalse(plan.text("prd", DS), plan.text("prd", DS).contains(CLIENT_ID));

        // the same vault, once written, diffs clean against the tree plus the secret
        assertTrue(ModelDiff.of(model(CLIENT_ID), to).text(), ModelDiff.of(model(CLIENT_ID), to).isEmpty());
    }

    @Test
    public void importNeverWritesTheIdIntoTheTree() throws Exception {
        DriverSet live = model(CLIENT_ID);
        assertEquals(List.of("Beeline"), Overrides.stripSecretShimAuthIds(live, secrets()));
        assertNull(live.driver("Beeline").shimAuthId);
        assertFalse(live.driver("Beeline").shimAuthIdSecret);
    }

    @Test
    public void aNewDriverMissingItsPasswordDoesNotShowTheIdEither() throws Exception {
        DriverSet to = model(null);
        Secrets s = secrets();                                   // supplies the id, not the password
        Overrides.applySecretShimAuthIds(to, s);
        DriverSet from = new DriverSet("dvs");
        from.dn = DS;
        ModelDiff diff = ModelDiff.of(from, to);
        Plan plan = Plan.of(diff, to, DS, s, "none", null, true, tmp.getRoot().toPath());
        String text = plan.text("prd", DS);
        assertTrue(text, text.contains("MISSING SECRET: Beeline.shim-auth-password"));
        assertTrue(text, text.contains("id from the secrets file"));
        assertFalse(text, text.contains(CLIENT_ID));
    }
}
