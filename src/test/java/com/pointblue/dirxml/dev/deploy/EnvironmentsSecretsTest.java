package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EnvironmentsSecretsTest {

    @Test
    public void environmentsLoadAndValidate() throws IOException {
        Path dir = Files.createTempDirectory("idm-env");
        Path f = dir.resolve("environments.properties");
        Files.writeString(f, String.join("\n",
            "stg.url=ldaps://idm-stg:636",
            "stg.bindDn=cn=deploy,o=system",
            "stg.password=pw",
            "stg.driverSet=cn=driverset1,o=system",
            "stg.tier=stg",
            "stg.secrets=secrets-stg.properties",
            "prd.url=ldaps://idm-prd:636",
            "prd.bindDn=cn=deploy,o=system",
            "prd.passwordEnv=IDM_TEST_NO_SUCH_VAR",
            "prd.driverSet=cn=driverset1,o=system",
            "prd.tier=prd",
            "prd.requires=stg",
            "bad.url=x", "bad.bindDn=y", "bad.password=z", "bad.driverSet=w", "bad.tier=qa"), StandardCharsets.UTF_8);
        Environments envs = Environments.load(f);
        assertEquals(List.of("bad", "prd", "stg"), envs.names());
        Environments.Environment stg = envs.get("stg");
        assertEquals(Environments.Tier.STG, stg.tier);
        assertEquals(dir.resolve("secrets-stg.properties").toAbsolutePath(), stg.secretsFile.toAbsolutePath());
        assertNull(stg.requires);
        assertEquals("ldaps://idm-stg:636", stg.vaultConfig().url);
        try {
            envs.get("prd");
            fail("passwordEnv not set must fail");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("IDM_TEST_NO_SUCH_VAR"));
        }
        try {
            envs.get("bad");
            fail("bad tier must fail");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("tier"));
        }
        try {
            envs.get("nope");
            fail("unknown env must fail");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("environments: [bad, prd, stg]"));
        }
    }

    @Test
    public void secretsLiteralEnvAndCommand() throws IOException {
        Path f = Files.createTempFile("secrets", ".properties");
        Files.writeString(f, String.join("\n",
            "AD Driver.shim-auth-password=literal-pw",
            "AD Driver.named.svcEnv=HOME",
            "driverset.named.relayCommand=printf 'from-cmd\\n'",
            "with space.named.x=v w",
            "AD Driver.remote-loader-passwordEnv=IDM_TEST_NO_SUCH_VAR"), StandardCharsets.UTF_8);
        Secrets s = Secrets.load(f);
        assertTrue(s.has(Secrets.shimAuth("AD Driver")));
        assertTrue(s.has(Secrets.named("AD Driver", "svc")));
        assertTrue(s.has(Secrets.named(Secrets.DRIVERSET, "relay")));
        assertFalse(s.has(Secrets.shimAuth("Other")));
        assertArrayEquals("literal-pw".toCharArray(), s.get(Secrets.shimAuth("AD Driver")));
        assertArrayEquals(System.getenv("HOME").toCharArray(), s.get(Secrets.named("AD Driver", "svc")));
        assertArrayEquals("from-cmd".toCharArray(), s.get(Secrets.named(Secrets.DRIVERSET, "relay")));
        assertNull(s.get(Secrets.shimAuth("Other")));
        assertEquals(List.of("AD Driver.named.svc", "AD Driver.remote-loader-password",
            "AD Driver.shim-auth-password", "driverset.named.relay", "with space.named.x"), s.keys());
        try {
            s.get(Secrets.remoteLoader("AD Driver"));
            fail("unset env var must fail");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("IDM_TEST_NO_SUCH_VAR"));
        }
        assertFalse(Secrets.none().has("x"));
        assertArrayEquals("v w".toCharArray(), s.get(Secrets.named("with space", "x")));
    }
}
