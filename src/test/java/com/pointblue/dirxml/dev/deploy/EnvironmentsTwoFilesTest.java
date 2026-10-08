package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The hosted server's two files: the project's definitions and one person's credentials. */
public class EnvironmentsTwoFilesTest {

    private static Path write(Path dir, String name, String text) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    public void credentialsOverlayTheDefinitionsAndOwnNamesAreTheirs() throws Exception {
        Path dir = Files.createTempDirectory("envs");
        Path defs = write(dir.resolve(Files.createDirectories(dir.resolve("environments")).getFileName()), "definitions.properties",
            "stg.url=ldaps://stg:636\nstg.driverSet=cn=driverset1,o=system\nstg.tier=stg\nstg.secrets=secrets-stg.properties\nstg.trustAll=true\n");
        Path creds = write(Files.createDirectories(dir.resolve("users").resolve("alice")), "environments.properties",
            "stg.bindDn=cn=alice,o=system\nstg.password=pw\nstg.trustAll=false\nmine.url=ldaps://mine:636\nmine.bindDn=cn=a,o=x\nmine.password=p\nmine.driverSet=cn=ds,o=x\n");
        Environments e = Environments.load(defs, creds);
        assertEquals(List.of("mine", "stg"), e.names());
        Environments.Environment stg = e.get("stg");
        assertEquals("ldaps://stg:636", stg.url);
        assertEquals("cn=alice,o=system", stg.bindDn);
        assertEquals("pw", stg.password);
        assertFalse("the person's key wins", stg.trustAll);
        assertEquals("a relative secrets file sits beside the definitions", defs.getParent().resolve("secrets-stg.properties").toAbsolutePath(), stg.secretsFile);
        Environments.Described dStg = e.describe().stream().filter(d -> d.name.equals("stg")).findFirst().orElseThrow();
        Environments.Described dMine = e.describe().stream().filter(d -> d.name.equals("mine")).findFirst().orElseThrow();
        assertFalse(dStg.own);
        assertTrue(dMine.own);
        assertTrue(dStg.passwordConfigured && dStg.bindDnPresent);
    }

    @Test
    public void aDefinitionWithoutACredentialIsListedButNotConnected() throws Exception {
        Path dir = Files.createTempDirectory("envs");
        Path defs = write(dir, "definitions.properties", "prd.url=ldaps://prd:636\nprd.driverSet=cn=ds,o=system\nprd.tier=prd\n");
        Environments e = Environments.load(defs, dir.resolve("absent.properties"));
        Environments.Described d = e.describe().get(0);
        assertTrue(d.urlPresent && d.driverSetPresent);
        assertFalse(d.bindDnPresent || d.passwordConfigured);
        assertFalse(d.own);
        try {
            e.get("prd");
            fail("connected without a credential");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("bindDn"));
        }
    }

    @Test
    public void definitionsHoldingASecretKeyAreRefused() throws Exception {
        Path dir = Files.createTempDirectory("envs");
        Path defs = write(dir, "definitions.properties", "stg.url=ldaps://stg:636\nstg.passwordKeychain=idm/stg\nstg.eventsPasswordEnv=X\nstg.appsPassword=y\nstg.eventsUser=reader\n");
        assertEquals(List.of("stg.appsPassword", "stg.eventsPasswordEnv", "stg.passwordKeychain"), Environments.secretKeys(defs));
        try {
            Environments.load(defs, null);
            fail("accepted secret keys in the definitions");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("stg.passwordKeychain"));
        }
        assertTrue(Environments.secretKeys(dir.resolve("none.properties")).isEmpty());
    }

    @Test
    public void oneFileStillReadsAsBefore() throws Exception {
        Path dir = Files.createTempDirectory("envs");
        Path f = write(dir, "environments.properties", "dev.url=ldaps://dev:636\ndev.bindDn=cn=admin,o=system\ndev.password=pw\ndev.driverSet=cn=ds,o=system\n");
        Environments e = Environments.load(f);
        assertFalse(e.describe().get(0).own);
        assertEquals("pw", e.get("dev").password);
    }
}
