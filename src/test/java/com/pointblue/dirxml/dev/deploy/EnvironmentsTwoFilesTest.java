package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    /**
     * A hosted server resolves a reference as itself (its own environment, shell or Keychain), so a person who is
     * not a deployer may not use one: with references off, their {@code …Env}, {@code …Command} and
     * {@code …Keychain} keys are left out, listed with their form, and asking for that secret says why. The
     * command is never run and the server's variable never read. A literal in their own file still works.
     */
    @Test
    public void aPersonsReferencesAreLeftOutWhenReferencesAreOff() throws Exception {
        Path dir = Files.createTempDirectory("envs");
        Path marker = dir.resolve("ran");
        Path defs = write(dir, "definitions.properties", "stg.url=ldaps://stg:636\nstg.driverSet=cn=ds,o=system\nstg.eventsUrl=db:5432/events\n"
            + "dev.url=ldaps://dev:636\ndev.driverSet=cn=ds,o=system\n");
        Path creds = write(dir, "creds.properties", "stg.bindDn=cn=bob,o=system\nstg.passwordCommand=touch " + marker + "; echo pw\n"
            + "stg.eventsPasswordCommand=touch " + marker + "; echo ev\nstg.appsPasswordEnv=PATH\nstg.otherPasswordKeychain=svc/acct\n"
            + "dev.bindDn=cn=bob,o=system\ndev.password=literal\n");

        Environments off = Environments.load(defs, creds, false);
        assertEquals(Map.of("stg.eventsPassword", "Command", "stg.password", "Command", "stg.appsPassword", "Env", "stg.otherPassword", "Keychain"),
            off.refusedReferences());
        assertFalse("listed as not configured", off.describe().stream().filter(d -> d.name.equals("stg")).findFirst().get().passwordConfigured);
        try {
            off.get("stg");
            fail("a refused command reference was used");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("read by a command, which only a deployer may use"));
        }
        try {
            off.secret("stg", "appsPassword");
            fail("the server's environment variable was read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("read from an environment variable, which only a deployer may use"));
        }
        assertFalse("the command never ran", Files.exists(marker));
        assertEquals("a literal in their own file is theirs", "literal", off.get("dev").password);

        Environments on = Environments.load(defs, creds, true);
        assertTrue(on.refusedReferences().isEmpty());
        assertEquals("pw", on.get("stg").password);
        assertTrue(Files.exists(marker));
        assertEquals(System.getenv("PATH"), new String(on.secret("stg", "appsPassword")));
        assertEquals("the two-argument form keeps references", Map.of(), Environments.load(defs, creds).refusedReferences());
    }

    /**
     * The CLI on a hosted install (the container's idm): the environments file it finds holds the definitions,
     * IDM_CREDENTIALS (here the idm.credentials property) names the person's own file, and the two merge as
     * the server merges them. Without it the file is read alone, as before.
     */
    @Test
    public void theCliMergesTheFileNamedByIdmCredentials() throws Exception {
        Path dir = Files.createTempDirectory("envs");
        Path defs = write(dir, "environments.properties", "stg.url=ldaps://stg:636\nstg.driverSet=cn=ds,o=system\nstg.tier=stg\n");
        Path mine = write(dir, "mine.properties", "stg.bindDn=cn=me,o=system\nstg.password=mine\n");
        String before = System.getProperty("idm.credentials");
        try {
            System.clearProperty("idm.credentials");
            assertEquals(null, Environments.credentialsFile());
            try {
                Environments.loadFound(defs).get("stg");
                fail("bound without a password");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("bindDn") || expected.getMessage().contains("password"));
            }
            System.setProperty("idm.credentials", mine.toString());
            Environments.Environment env = Environments.loadFound(defs).get("stg");
            assertEquals("cn=me,o=system", env.bindDn);
            assertEquals("mine", env.password);
            assertEquals("ldaps://stg:636", env.url);

            System.setProperty("idm.credentials", dir.resolve("absent.properties").toString());
            assertEquals("a missing credentials file is an empty one", List.of("stg"), Environments.loadFound(defs).names());
        } finally {
            if (before == null) {
                System.clearProperty("idm.credentials");
            } else {
                System.setProperty("idm.credentials", before);
            }
        }
    }
}
