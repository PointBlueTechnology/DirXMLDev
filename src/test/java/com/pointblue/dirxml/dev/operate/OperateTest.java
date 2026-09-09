package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.DeployLog;
import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Secrets;
import com.pointblue.dirxml.dev.deploy.Vault;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link Operate} against an in-memory {@link FakeEngine} (never a live
 * vault — {@link Vault} is {@code final}, so {@link Operate.Engine} is the seam).
 * The guarded test at the bottom is the only one that touches a real vault.
 */
public class OperateTest {

    private static final String DS_DN = "cn=driverset1,o=system";
    private static final String DRIVER = "Querytest";
    private static final String DRIVER_DN = "cn=" + DRIVER + "," + DS_DN;

    // from docs/spikes/operate.md: <driver-info driver-dn server-dn timestamp> with
    // <subscriber><cache><size>/<unprocessed-size>/transaction counts.
    private static final String DRIVER_INFO_XML =
        "<driver-info driver-dn=\"" + DRIVER_DN + "\" server-dn=\"cn=server1,o=system\" timestamp=\"20260909120000.000Z\">"
            + "<subscriber>"
            + "<cache><size>5</size><unprocessed-size>2</unprocessed-size></cache>"
            + "<last-reset-time>20260101000000.000Z</last-reset-time>"
            + "<reported-event-count>120</reported-event-count>"
            + "<post-event-transformation-count>118</post-event-transformation-count>"
            + "<post-input-transformation-count>115</post-input-transformation-count>"
            + "<command-count>110</command-count>"
            + "<command-result-count>110</command-result-count>"
            + "</subscriber>"
            + "<publisher><reported-event-count>0</reported-event-count></publisher>"
            + "</driver-info>";

    // A jvm-stats sample matching the spike's prose: heap total 3072 MB, used 390 MB.
    private static final String JVM_STATS_XML =
        "<jvm-stats>"
            + "<memory>"
            + "<heap><initial>256</initial><committed>512</committed><used>390</used><total>3072</total></heap>"
            + "<non-heap><initial>24</initial><committed>48</committed><used>40</used><total>64</total></non-heap>"
            + "</memory>"
            + "<threads><daemon>12</daemon><current>15</current><peak>20</peak></threads>"
            + "</jvm-stats>";

    // ---- a small in-memory fake of Operate.Engine ----------------------------------------

    static final class FakeEngine implements Operate.Engine {
        final Map<String, Vault.Entry> entries = new LinkedHashMap<>();
        final Map<String, List<Vault.Entry>> childrenOf = new LinkedHashMap<>();
        final Map<String, Integer> states = new LinkedHashMap<>();
        final Map<String, Integer> startOptions = new LinkedHashMap<>();
        final Map<String, String> driverStats = new LinkedHashMap<>();
        final Map<String, List<String>> events = new LinkedHashMap<>();   // dn -> raw event element strings
        final Map<String, List<String>> namedPasswords = new LinkedHashMap<>();
        final List<String> deletedCalls = new ArrayList<>();
        String jvmStats = "";
        int engineVersion;
        RuntimeException failNextViewCache;
        RuntimeException failDeleteOnce;
        int waitForStateResultState = -1;   // if >=0, driverState() returns this after waitForState is called
        RuntimeException waitForStateThrows;

        static Vault.Entry entry(String dn, String cn, List<String> classes) {
            Vault.Entry e = newEntry(dn);
            put(e, "objectClass", classes);
            if (cn != null) {
                put(e, "cn", List.of(cn));
            }
            return e;
        }

        // Vault.Entry's constructor is package-private to `deploy`; its `attrs` field is public,
        // so only the construction needs reflection.
        static Vault.Entry newEntry(String dn) {
            try {
                Constructor<Vault.Entry> c = Vault.Entry.class.getDeclaredConstructor(String.class);
                c.setAccessible(true);
                return c.newInstance(dn);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        static void put(Vault.Entry e, String attr, List<String> values) {
            List<byte[]> bytes = new ArrayList<>();
            for (String v : values) {
                bytes.add(v.getBytes(StandardCharsets.UTF_8));
            }
            e.attrs.put(attr, bytes);
        }

        void setAttr(String dn, String attr, String value) {
            Vault.Entry e = entries.get(dn);
            put(e, attr, value == null ? List.of() : List.of(value));
        }

        public int driverState(String dn) {
            return states.getOrDefault(dn, Vault.STATE_STOPPED);
        }

        public int driverStartOption(String dn) {
            return startOptions.getOrDefault(dn, Vault.START_MANUAL);
        }

        public void startDriver(String dn) {
            states.put(dn, Vault.STATE_STARTING);
        }

        public void stopDriver(String dn) {
            states.put(dn, Vault.STATE_STOPPING);
        }

        public void restartDriver(String dn) {
            states.put(dn, Vault.STATE_STARTING);
        }

        public String waitForState(String dn, int wanted, int seconds) {
            if (waitForStateThrows != null) {
                throw waitForStateThrows;
            }
            int result = waitForStateResultState >= 0 ? waitForStateResultState : wanted;
            states.put(dn, result);
            return Vault.stateName(Vault.STATE_STARTING) + " → " + Vault.stateName(result);
        }

        public Vault.Entry read(String dn) {
            return entries.get(dn);
        }

        public List<Vault.Entry> children(String base) {
            return childrenOf.getOrDefault(base, List.of());
        }

        public void replace(String dn, String attr, List<byte[]> values) {
            Vault.Entry e = entries.get(dn);
            if (values.isEmpty()) {
                e.attrs.remove(attr);
            } else {
                e.attrs.put(attr, values);
            }
        }

        public Operate.CachePage viewCache(String dn, int position, int count) {
            if (failNextViewCache != null) {
                RuntimeException e = failNextViewCache;
                failNextViewCache = null;
                throw e;
            }
            List<String> all = events.getOrDefault(dn, List.of());
            if (position >= all.size()) {
                return new Operate.CachePage("", position);
            }
            int end = Math.min(position + count, all.size());
            StringBuilder input = new StringBuilder();
            for (int i = position; i < end; i++) {
                input.append(all.get(i));
            }
            String xds = "<nds dtdversion=\"4.0\" ndsversion=\"8.x\"><source><product edition=\"Advanced\" version=\"4.8.7.0000\">DirXML</product>"
                + "<contact>NetIQ Corporation</contact></source><input>" + input + "</input></nds>";
            return new Operate.CachePage(xds, end);
        }

        public void deleteCacheEntries(String dn, int p2, int p3, String p4, int priority) {
            deletedCalls.add(p2 + "," + p3 + "," + p4 + "," + priority);
            if (failDeleteOnce != null) {
                RuntimeException e = failDeleteOnce;
                failDeleteOnce = null;
                throw e;
            }
            List<String> all = new ArrayList<>(events.getOrDefault(dn, List.of()));
            int count = p3 == 0 ? all.size() : p3;
            for (int i = 0; i < count && !all.isEmpty(); i++) {
                all.remove(0);
            }
            events.put(dn, all);
        }

        public void migrateApp(String dn, byte[] xds) {
        }

        public void resync(String dn, long sinceMillis) {
        }

        public int engineVersion() {
            return engineVersion;
        }

        public String driverStats(String dn, int arg) {
            return driverStats.getOrDefault(dn, "");
        }

        public String jvmStats(int a, int b) {
            return jvmStats;
        }

        public List<String> namedPasswords(String dn) {
            return namedPasswords.getOrDefault(dn, List.of());
        }

        public void setNamedPassword(String dn, String name, String displayName, char[] value) {
            namedPasswords.computeIfAbsent(dn, k -> new ArrayList<>()).add(name);
        }

        public void removeNamedPassword(String dn, String name) {
            List<String> l = namedPasswords.get(dn);
            if (l != null) {
                l.remove(name);
            }
        }
    }

    private FakeEngine fake;
    private Path tree;
    private Environments.Environment dev;
    private Environments.Environment stg;
    private Environments.Environment prd;

    @Before
    public void setup() throws IOException {
        fake = new FakeEngine();
        tree = Files.createTempDirectory("operate-test");
        dev = env("dev", Environments.Tier.DEV);
        stg = env("stg", Environments.Tier.STG);
        prd = env("prd", Environments.Tier.PRD);
    }

    private static Environments.Environment env(String name, Environments.Tier tier) throws IOException {
        // Environments.Environment's constructor is package-private (deploy); load it the same
        // way the real code does, from a tiny in-memory properties file.
        String props = name + ".url=ldaps://x:636\n" + name + ".bindDn=cn=x\n" + name + ".password=x\n"
            + name + ".driverSet=" + DS_DN + "\n" + name + ".tier=" + tier.name().toLowerCase() + "\n";
        Path f = Files.createTempFile("env-" + name, ".properties");
        Files.writeString(f, props);
        return Environments.load(f).get(name);
    }

    // ---- driverset.status / driver.status ------------------------------------------------

    @Test
    public void driversetStatusRendersATableSortedByName() {
        String dnB = "cn=Zeta," + DS_DN;
        String dnA = "cn=Alpha," + DS_DN;
        fake.childrenOf.put(DS_DN, List.of(
            FakeEngine.entry(dnB, "Zeta", List.of("Top", "DirXML-Driver")),
            FakeEngine.entry(dnA, "Alpha", List.of("Top", "DirXML-Driver")),
            FakeEngine.entry("cn=Library," + DS_DN, "Library", List.of("Top", "DirXML-Library"))));
        fake.entries.put(dnA, FakeEngine.entry(dnA, "Alpha", List.of("Top", "DirXML-Driver")));
        fake.entries.put(dnB, FakeEngine.entry(dnB, "Zeta", List.of("Top", "DirXML-Driver")));
        fake.states.put(dnA, Vault.STATE_RUNNING);
        fake.states.put(dnB, Vault.STATE_STOPPED);
        fake.startOptions.put(dnA, Vault.START_AUTO);
        fake.startOptions.put(dnB, Vault.START_MANUAL);
        fake.driverStats.put(dnA, DRIVER_INFO_XML);
        fake.setAttr(dnA, Vault.TRACE_LEVEL, "3");

        Operate.Result r = Operate.driversetStatus(fake, dev);
        assertTrue(r.ok);
        int posAlpha = r.text().indexOf("Alpha");
        int posZeta = r.text().indexOf("Zeta");
        assertTrue("Alpha (sorted first) should appear before Zeta", posAlpha >= 0 && posAlpha < posZeta);
        assertTrue(r.text().contains("running"));
        assertTrue(r.text().contains("5"));   // cache size parsed from DRIVER_INFO_XML
        assertTrue(r.json().contains("\"cacheSize\":\"5\""));
        assertTrue(r.json().contains("\"unprocessedSize\":\"2\""));
        // library is not a DirXML-Driver: excluded
        assertFalse(r.text().contains("Library"));
    }

    @Test
    public void driverStatusIncludesTraceNamedPasswordsAndRecentAudit() throws IOException {
        seedQuerytest();
        fake.namedPasswords.put(DRIVER_DN, new ArrayList<>(List.of("svc-a")));
        fake.setAttr(DRIVER_DN, Vault.TRACE_LEVEL, "3");
        fake.setAttr(DRIVER_DN, Vault.TRACE_FILE, "/tmp/t.log");

        DeployLog.Record rec = DeployLog.record("dev", "operate");
        rec.outcome = "ok";
        rec.detail = "driver.start '" + DRIVER + "': stopped → running";
        DeployLog.append(tree, rec);
        DeployLog.Record other = DeployLog.record("dev", "operate");
        other.outcome = "ok";
        other.detail = "driver.start 'OtherDriver': stopped → running";
        DeployLog.append(tree, other);

        Operate.Result r = Operate.driverStatus(fake, dev, DRIVER, tree);
        assertTrue(r.ok);
        assertTrue(r.text().contains("svc-a"));
        assertTrue(r.text().contains("/tmp/t.log"));
        assertTrue(r.text().contains("driver.start '" + DRIVER + "'"));
        assertFalse(r.text().contains("OtherDriver"));
    }

    private void seedQuerytest() {
        fake.entries.put(DRIVER_DN, FakeEngine.entry(DRIVER_DN, DRIVER, List.of("Top", "DirXML-Driver")));
        fake.states.put(DRIVER_DN, Vault.STATE_STOPPED);
        fake.startOptions.put(DRIVER_DN, Vault.START_MANUAL);
        fake.driverStats.put(DRIVER_DN, DRIVER_INFO_XML);
    }

    // ---- driver.start/stop/restart + audit -------------------------------------------------

    @Test
    public void startWritesAuditRecordOnDevWithoutYes() throws IOException {
        seedQuerytest();
        Operate.Result r = Operate.lifecycle(fake, dev, DRIVER, "start", 5, false, null, tree);
        assertTrue(r.ok);
        assertTrue(r.text().contains("stopped → running"));
        List<DeployLog.Record> log = DeployLog.read(tree, "dev");
        assertEquals(1, log.size());
        assertEquals("operate", log.get(0).operation);
        assertEquals("ok", log.get(0).outcome);
        assertTrue(log.get(0).detail.contains("driver.start '" + DRIVER + "'"));
        assertEquals(List.of(DRIVER), log.get(0).restarted);
    }

    @Test
    public void stopOnDevNeedsYes() throws IOException {
        seedQuerytest();
        fake.states.put(DRIVER_DN, Vault.STATE_RUNNING);
        Operate.Result refused = Operate.lifecycle(fake, dev, DRIVER, "stop", 5, false, null, tree);
        assertFalse(refused.ok);
        assertTrue(refused.text().contains("REFUSED"));
    }

    @Test
    public void startRefusalIsReportedPlainly() throws IOException {
        seedQuerytest();
        fake.waitForStateThrows = new Vault.VaultException(
            "driver " + DRIVER_DN + " started and stopped again — the engine refused it; check the driver trace", null);
        Operate.Result r = Operate.lifecycle(fake, dev, DRIVER, "start", 5, false, null, tree);
        assertFalse(r.ok);
        assertTrue(r.text().contains("the engine refused it"));
    }

    // ---- gating: table-driven over commands x tiers x flags --------------------------------

    @Test
    public void gatingMatchesTheSafeguardsTable() {
        // status/read-only: always free
        assertNull(Operate.gate(dev, Operate.OpClass.READ_ONLY, false, null));
        assertNull(Operate.gate(prd, Operate.OpClass.READ_ONLY, false, null));

        // LIGHT (start/restart/trace set|reset/secrets set|remove): dev free, stg needs --yes, prd needs --yes+confirm
        assertNull(Operate.gate(dev, Operate.OpClass.LIGHT, false, null));
        assertNotNull(Operate.gate(stg, Operate.OpClass.LIGHT, false, null));
        assertNull(Operate.gate(stg, Operate.OpClass.LIGHT, true, null));
        assertNotNull(Operate.gate(prd, Operate.OpClass.LIGHT, true, null));
        assertNotNull(Operate.gate(prd, Operate.OpClass.LIGHT, true, "wrong"));
        assertNull(Operate.gate(prd, Operate.OpClass.LIGHT, true, "prd"));

        // HEAVY (stop/resync/migrate): dev needs --yes, stg needs --yes, prd needs --yes+confirm
        assertNotNull(Operate.gate(dev, Operate.OpClass.HEAVY, false, null));
        assertNull(Operate.gate(dev, Operate.OpClass.HEAVY, true, null));
        assertNotNull(Operate.gate(stg, Operate.OpClass.HEAVY, false, null));
        assertNull(Operate.gate(stg, Operate.OpClass.HEAVY, true, null));
        assertNotNull(Operate.gate(prd, Operate.OpClass.HEAVY, true, null));
        assertNull(Operate.gate(prd, Operate.OpClass.HEAVY, true, "prd"));

        // CACHE_CLEAR: dev needs --yes only; stg/prd need --yes + --confirm
        assertNotNull(Operate.gate(dev, Operate.OpClass.CACHE_CLEAR, false, null));
        assertNull(Operate.gate(dev, Operate.OpClass.CACHE_CLEAR, true, null));
        assertNotNull(Operate.gate(stg, Operate.OpClass.CACHE_CLEAR, true, null));
        assertNull(Operate.gate(stg, Operate.OpClass.CACHE_CLEAR, true, "stg"));
        assertNotNull(Operate.gate(prd, Operate.OpClass.CACHE_CLEAR, true, null));
        assertNull(Operate.gate(prd, Operate.OpClass.CACHE_CLEAR, true, "prd"));
    }

    private static void assertNotNull(Object o) {
        org.junit.Assert.assertNotNull(o);
    }

    // ---- driver.cache view: paging and --out -----------------------------------------------

    @Test
    public void cacheViewPagesThroughTheWholeCacheAndWritesOutFiles() throws IOException {
        seedQuerytest();
        fake.events.put(DRIVER_DN, sampleEvents(5));

        // page size 2: exercises the nextToken-follows-until-empty loop
        Path out = tree.resolve("case-out");
        Operate.Result r = Operate.cacheView(fake, dev, DRIVER, 2, out);
        assertTrue(r.ok);
        assertTrue(r.text().startsWith("5 event(s)"));
        assertTrue(r.text().contains("event-id=evt-1"));
        assertTrue(r.text().contains("event-id=evt-5"));

        assertTrue(Files.isRegularFile(out.resolve("cache.xds")));
        assertTrue(Files.isRegularFile(out.resolve("input.xds")));
        assertTrue(Files.isRegularFile(out.resolve("README")));
        String cacheXds = Files.readString(out.resolve("cache.xds"));
        String inputXds = Files.readString(out.resolve("input.xds"));
        assertTrue(cacheXds.contains("evt-1"));
        assertTrue(cacheXds.contains("evt-5"));
        assertTrue(inputXds.contains("<nds dtdversion=\"4.0\">"));
        assertTrue(inputXds.contains("evt-1"));
        String readme = Files.readString(out.resolve("README"));
        assertTrue(readme.contains("driver.cache view"));
    }

    private static List<String> sampleEvents(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add("<modify class-name=\"User\" src-dn=\"\\\\tree\\\\u" + i + "\" event-id=\"evt-" + i + "\"/>");
        }
        return out;
    }

    // ---- driver.cache clear: refuses without --yes, snapshots, deletes (0,size), verifies ----

    @Test
    public void cacheClearRefusesWithoutYes() throws IOException {
        seedQuerytest();
        fake.events.put(DRIVER_DN, sampleEvents(3));
        Operate.Result r = Operate.cacheClear(fake, stg, DRIVER, false, null, tree);
        assertFalse(r.ok);
        assertTrue(r.text().contains("3 event(s)"));
        assertTrue(r.text().contains("REFUSED"));
        assertTrue(fake.deletedCalls.isEmpty());
        assertEquals(3, fake.events.get(DRIVER_DN).size());   // untouched
    }

    @Test
    public void cacheClearSnapshotsDeletesAndVerifies() throws IOException {
        seedQuerytest();
        fake.events.put(DRIVER_DN, sampleEvents(4));
        Operate.Result r = Operate.cacheClear(fake, dev, DRIVER, true, null, tree);
        assertTrue(r.text(), r.ok);
        assertEquals(List.of("0,4,,0"), fake.deletedCalls);
        assertTrue(fake.events.getOrDefault(DRIVER_DN, List.of()).isEmpty());

        Path snapDir = tree.resolve("deploy-snapshots").resolve("dev");
        assertTrue(Files.isDirectory(snapDir));
        List<Path> snaps = Files.list(snapDir).toList();
        assertEquals(1, snaps.size());
        String snapText = Files.readString(snaps.get(0));
        assertTrue(snapText.contains("evt-1"));
        assertTrue(snapText.contains("evt-4"));

        List<DeployLog.Record> log = DeployLog.read(tree, "dev");
        assertEquals(1, log.size());
        assertEquals("ok", log.get(0).outcome);
        assertEquals(4, log.get(0).changes);
        assertTrue(log.get(0).snapshot.contains("deploy-snapshots"));
    }

    @Test
    public void cacheClearFallsBackToOneAtATimeWhenBulkDeleteFails() throws IOException {
        seedQuerytest();
        fake.events.put(DRIVER_DN, sampleEvents(2));
        fake.failDeleteOnce = new RuntimeException("Other");
        Operate.Result r = Operate.cacheClear(fake, dev, DRIVER, true, null, tree);
        assertTrue(r.text(), r.ok);
        // first call (0,2,...) fails, then the fallback issues (0,1,...) calls
        assertEquals(3, fake.deletedCalls.size());
        assertEquals("0,2,,0", fake.deletedCalls.get(0));
        assertEquals("0,1,,0", fake.deletedCalls.get(1));
        assertEquals("0,1,,0", fake.deletedCalls.get(2));
    }

    @Test
    public void cacheClearOnEmptyCacheIsANoOp() throws IOException {
        seedQuerytest();
        Operate.Result r = Operate.cacheClear(fake, prd, DRIVER, false, null, tree);
        assertTrue(r.ok);
        assertTrue(r.text().contains("already empty"));
    }

    // ---- driver.secrets --------------------------------------------------------------------

    @Test
    public void secretsSetReadsFromSecretsFileNeverFromAnArgument() throws IOException {
        seedQuerytest();
        Path secretsFile = Files.createTempFile("secrets", ".properties");
        Files.writeString(secretsFile, DRIVER + ".named.svc=s3cr3t\n");
        Secrets secrets = Secrets.load(secretsFile);

        Operate.Result r = Operate.secretsSet(fake, dev, DRIVER, "svc", secrets, false, false, null, tree);
        assertTrue(r.ok);
        assertFalse(r.text().contains("s3cr3t"));
        assertFalse(r.json().contains("s3cr3t"));
        assertTrue(fake.namedPasswords.get(DRIVER_DN).contains("svc"));

        List<DeployLog.Record> log = DeployLog.read(tree, "dev");
        assertEquals(List.of(DRIVER + ".named.svc"), log.get(0).secretsSet);
        assertFalse(log.get(0).detail.contains("s3cr3t"));
    }

    @Test
    public void secretsListAndRemove() throws IOException {
        seedQuerytest();
        fake.namedPasswords.put(DRIVER_DN, new ArrayList<>(List.of("svc")));
        Operate.Result list = Operate.secretsList(fake, dev, DRIVER);
        assertTrue(list.text().contains("svc"));

        Operate.Result removed = Operate.secretsRemove(fake, dev, DRIVER, "svc", false, null, tree);
        assertTrue(removed.ok);
        assertFalse(fake.namedPasswords.get(DRIVER_DN).contains("svc"));
    }

    // ---- driver.trace: set/reset round trip -------------------------------------------------

    @Test
    public void traceSetThenResetRoundTrips() throws IOException {
        seedQuerytest();
        fake.setAttr(DRIVER_DN, Vault.TRACE_LEVEL, "1");
        fake.setAttr(DRIVER_DN, Vault.TRACE_FILE, "/var/log/orig.log");

        Operate.Result setResult = Operate.traceSet(fake, dev, DRIVER, 5, "/tmp/scratch.log", false, null, tree);
        assertTrue(setResult.ok);
        assertEquals("5", fake.entries.get(DRIVER_DN).string(Vault.TRACE_LEVEL));
        assertEquals("/tmp/scratch.log", fake.entries.get(DRIVER_DN).string(Vault.TRACE_FILE));

        Operate.Result resetResult = Operate.traceReset(fake, dev, DRIVER, false, null, tree);
        assertTrue(resetResult.text(), resetResult.ok);
        assertEquals("1", fake.entries.get(DRIVER_DN).string(Vault.TRACE_LEVEL));
        assertEquals("/var/log/orig.log", fake.entries.get(DRIVER_DN).string(Vault.TRACE_FILE));
    }

    @Test
    public void traceResetRefusesWithoutAPriorSet() throws IOException {
        seedQuerytest();
        Operate.Result r = Operate.traceReset(fake, dev, DRIVER, false, null, tree);
        assertFalse(r.ok);
        assertTrue(r.text().contains("REFUSED"));
    }

    // ---- engine.version / engine.stats ------------------------------------------------------

    @Test
    public void engineVersionDecodesThePackedInt() {
        fake.engineVersion = 1208418307;
        Operate.Result r = Operate.engineVersion(fake);
        assertTrue(r.ok);
        assertEquals("4.8.7.0 build 3\n", r.text());
        assertTrue(r.json().contains("\"version\":\"4.8.7.0 build 3\""));
    }

    @Test
    public void engineStatsParsesJvmAndDriverStats() {
        fake.jvmStats = JVM_STATS_XML;
        seedQuerytest();
        Operate.Result r = Operate.engineStats(fake, dev, List.of(DRIVER));
        assertTrue(r.ok);
        assertTrue(r.text().contains("used=390"));
        assertTrue(r.text().contains("total=3072"));
        assertTrue(r.text().contains("current=15"));
        assertTrue(r.text().contains("cache=5"));
        assertTrue(r.text().contains("unprocessed=2"));
        assertTrue(r.json().contains("\"heapUsed\":\"390\""));
        assertTrue(r.json().contains("\"cacheSize\":\"5\""));
    }

    @Test
    public void jvmStatsParsingFromTheSpikeSample() {
        Operate.JvmStats s = Operate.parseJvmStats(JVM_STATS_XML);
        assertEquals("390", s.heapUsed);
        assertEquals("512", s.heapCommitted);
        assertEquals("3072", s.heapTotal);
        assertEquals("15", s.threadsCurrent);
        assertEquals("12", s.threadsDaemon);
        assertEquals("20", s.threadsPeak);
    }

    @Test
    public void jvmStatsParsingFailsSoftlyToQuestionMarks() {
        Operate.JvmStats s = Operate.parseJvmStats("not xml at all <<<");
        assertEquals("?", s.heapUsed);
        assertEquals("?", s.threadsPeak);
    }

    @Test
    public void cacheSizeParsingFromDriverInfo() {
        String[] sizes = Operate.parseCacheSizes(DRIVER_INFO_XML);
        assertEquals("5", sizes[0]);
        assertEquals("2", sizes[1]);
        assertEquals("?", Operate.parseCacheSizes("")[0]);
    }

    // ---- guarded live test (read-only) -------------------------------------------------------

    @Test
    public void liveDriversetAndDriverStatusReadOnly() throws IOException {
        Assume.assumeTrue("set -Dvault.url/.bindDn/.password/.driverSetDn (and vault.driver) to run",
            System.getProperty("vault.url") != null);
        Vault.Config cfg = new Vault.Config();
        cfg.url = System.getProperty("vault.url");
        cfg.bindDn = System.getProperty("vault.bindDn");
        cfg.password = System.getProperty("vault.password");
        String dsDn = System.getProperty("vault.driverSetDn");
        String driver = System.getProperty("vault.driver");
        Assume.assumeTrue("set -Dvault.driver=<driver cn>", driver != null);

        Environments.Environment liveEnv = liveEnv(dsDn, cfg);
        try (Vault v = Vault.connect(cfg)) {
            Operate.Engine engine = Operate.vaultEngine(v);
            Operate.Result status = Operate.driversetStatus(engine, liveEnv);
            assertTrue(status.ok);
            Operate.Result driverStatus = Operate.driverStatus(engine, liveEnv, driver, tree);
            assertTrue(driverStatus.ok);
        }
    }

    private static Environments.Environment liveEnv(String dsDn, Vault.Config cfg) throws IOException {
        String props = "live.url=" + cfg.url + "\nlive.bindDn=" + cfg.bindDn + "\nlive.password=" + cfg.password
            + "\nlive.driverSet=" + dsDn + "\nlive.tier=dev\n";
        Path f = Files.createTempFile("env-live", ".properties");
        Files.writeString(f, props);
        return Environments.load(f).get("live");
    }
}
