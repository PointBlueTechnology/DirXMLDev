package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Job;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production's four servers mapped onto QA's two: the set's list, jobs and per-server settings follow, the preferred
 * server's settings win where two land on one, the primary's become the base, and a read of QA maps back.
 */
public class ServerMapTest {

    private static final String P1 = "cn=VAULT01,ou=servers,o=acme";
    private static final String P2 = "cn=VAULT02,ou=servers,o=acme";
    private static final String P3 = "cn=VAULT03,ou=servers,o=acme";
    private static final String P4 = "cn=VAULT04,ou=servers,o=acme";
    private static final String Q1 = "cn=vault01qa,ou=servers,o=acme";
    private static final String Q2 = "cn=vault02qa,ou=servers,o=acme";

    private static org.w3c.dom.Element gcvs(String v) {
        return ValidatorTest.xml("<configuration-values><definitions><definition display-name=\"v\" name=\"v\" type=\"string\"><value>" + v
            + "</value></definition></definitions></configuration-values>");
    }

    /** Production as the tree has it: P1 the primary (no entry of its own), P2 differs, P3 and P4 agree. */
    private static DriverSet prod() {
        DriverSet ds = VaultMappingTest.model();
        ds.servers.addAll(List.of(P1, P2, P3, P4));
        Driver ad = ds.driver("AD");
        ad.serverConfig.put(P2, new LinkedHashMap<>(Map.of(Driver.CONFIG_VALUES, gcvs("two"))));
        ad.serverConfig.put(P3, new LinkedHashMap<>(Map.of(Driver.CONFIG_VALUES, gcvs("three"))));
        ad.serverConfig.put(P4, new LinkedHashMap<>(Map.of(Driver.CONFIG_VALUES, gcvs("three"))));
        Job job = new Job("nightly", null);
        job.servers.addAll(List.of(P1, P3));
        ad.jobs.add(job);
        return ds;
    }

    private static Map<String, String> qaFile(boolean preferP2) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("servers." + P1, Q1);
        f.put("servers." + P2, Q1);
        f.put("servers." + P3, Q2);
        f.put("servers." + P4, Q2);
        if (preferP2) {
            f.put("servers." + Q1 + ".prefer", P2);
        }
        return f;
    }

    private static String value(org.w3c.dom.Element gcvs) {
        return Overrides.valueOf(com.pointblue.dirxml.dev.edit.GcvOps.definition(gcvs, "v"));
    }

    @Test
    public void fourServersOntoTwo() {
        DriverSet ds = prod();
        List<String> problems = ServerMap.apply(ds, qaFile(false));
        assertEquals(List.of(Q1, Q2), ds.servers);
        Driver ad = ds.driver("AD");
        assertFalse("Q1 takes P1's: the primary's values, no entry", ad.serverConfig.containsKey(Q1));
        assertEquals("three", value(ad.serverConfig.get(Q2).get(Driver.CONFIG_VALUES)));
        assertEquals(List.of(Q1, Q2), ad.jobs.get(0).servers);
        assertEquals(problems.toString(), 1, problems.size());
        assertTrue(problems.get(0), problems.get(0).contains(P2) && problems.get(0).contains(".prefer"));
    }

    @Test
    public void thePreferredServerWinsAndThePrimaryTakesItAsTheBase() {
        DriverSet ds = prod();
        ServerMap.apply(ds, qaFile(true));
        Driver ad = ds.driver("AD");
        assertEquals("two", value(ad.serverConfig.get(Q1).get(Driver.CONFIG_VALUES)));
        Servers.foldPrimary(ds, Q1);
        assertNull(ad.serverConfig.get(Q1));
        assertEquals("two", value(ad.config.get(Driver.CONFIG_VALUES)));
    }

    @Test
    public void aReadOfQaMapsBack() {
        DriverSet base = prod();
        DriverSet live = VaultMappingTest.model();   // what QA's vault holds
        live.servers.addAll(List.of(Q1, Q2));
        live.driver("AD").serverConfig.put(Q2, new LinkedHashMap<>(Map.of(Driver.CONFIG_VALUES, gcvs("qa-three"))));
        Job job = new Job("nightly", null);
        job.servers.addAll(List.of(Q1, Q2));
        live.driver("AD").jobs.add(job);

        List<String> notes = ServerMap.toTree(live, qaFile(false), base);
        assertTrue(notes.toString(), notes.isEmpty());
        assertEquals("the tree's own list", List.of(P1, P2, P3, P4), live.servers);
        Driver ad = live.driver("AD");
        assertEquals("QA's Q2 lands on P3, the server it prefers", "qa-three", value(ad.serverConfig.get(P3).get(Driver.CONFIG_VALUES)));
        assertEquals("P4 keeps the tree's", "three", value(ad.serverConfig.get(P4).get(Driver.CONFIG_VALUES)));
        assertEquals("P2 keeps the tree's", "two", value(ad.serverConfig.get(P2).get(Driver.CONFIG_VALUES)));
        assertEquals(List.of(P1, P3), ad.jobs.get(0).servers);
    }

    @Test
    public void theKeysAreValidOnlyForTheTreesServers() {
        DriverSet ds = prod();
        assertTrue(Overrides.resolve(ds, "servers." + P3).resolved());
        assertTrue("any case", Overrides.resolve(ds, "servers." + P3.toUpperCase()).resolved());
        assertFalse(Overrides.resolve(ds, "servers.cn=nope,o=acme").resolved());
        assertTrue(Overrides.resolve(ds, "servers." + Q1 + ".prefer").resolved());
    }
}
