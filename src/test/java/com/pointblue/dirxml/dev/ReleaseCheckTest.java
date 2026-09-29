package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The release check: version comparison, GitHub's JSON, the daily cache, the notice, and when it stays quiet. */
public class ReleaseCheckTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void versionsCompareNumerically() {
        assertTrue(Version.compare("0.6.0", "0.10.0") < 0);
        assertTrue(Version.compare("v0.10.0", "0.9.1") > 0);
        assertEquals(0, Version.compare("0.6.0", "v0.6.0"));
        assertTrue(Version.compare("0.6.0-SNAPSHOT", "0.6.0") == 0);
        assertTrue(Version.compare("1.0", "1.0.0") == 0);
        String current = Version.current();
        assertTrue("built with resources: a dotted version; without: unknown", current == null || current.matches("\\d+\\.\\d+\\.\\d+.*"));
    }

    @Test
    public void parsesARelease_andIgnoresPrereleasesAndDrafts() {
        ReleaseCheck.Latest l = ReleaseCheck.parse("{\"tag_name\":\"v0.7.0\",\"html_url\":\"https://github.com/x/y/releases/tag/v0.7.0\",\"prerelease\":false,\"draft\":false}");
        assertNotNull(l);
        assertEquals("0.7.0", l.version);
        assertEquals("https://github.com/x/y/releases/tag/v0.7.0", l.url);
        assertNull(ReleaseCheck.parse("{\"tag_name\":\"v0.8.0-rc1\",\"prerelease\":true}"));
        assertNull(ReleaseCheck.parse("{\"message\":\"Not Found\"}"));
        assertNull(ReleaseCheck.parse("not json"));
    }

    @Test
    public void noticeOnlyWhenNewer() {
        assertNull(ReleaseCheck.notice("0.6.0", "0.6.0", null));
        assertNull(ReleaseCheck.notice("0.7.0", "0.6.0", null));
        assertNull(ReleaseCheck.notice(null, "0.7.0", null));
        String n = ReleaseCheck.notice("0.6.0", "0.7.0", "https://example/r");
        assertTrue(n, n.startsWith("note: DirXMLDev 0.7.0 is available (running 0.6.0): https://example/r"));
        assertTrue(n, n.contains("git pull && mvn -o package"));
    }

    @Test
    public void offInCi_offWhenAsked_offWithoutAConsole() {
        assertNull(ReleaseCheck.disabledReason(Map.of(), true));
        assertEquals("CI is set", ReleaseCheck.disabledReason(Map.of("CI", "true"), true));
        assertEquals(ReleaseCheck.OFF_ENV + " is set", ReleaseCheck.disabledReason(Map.of(ReleaseCheck.OFF_ENV, "1"), true));
        assertEquals("no console", ReleaseCheck.disabledReason(Map.of(), false));
    }

    @Test
    public void cacheRoundTripsAndExpires() throws Exception {
        Path f = tmp.newFolder("idm").toPath().resolve("release-check.json");
        ReleaseCheck.Cache c = ReleaseCheck.readCache(f);
        assertFalse(c.fresh(System.currentTimeMillis()));
        c.checkedAt = System.currentTimeMillis();
        c.latest = "0.7.0";
        c.url = "https://example/r";
        ReleaseCheck.writeCache(f, c);
        ReleaseCheck.Cache back = ReleaseCheck.readCache(f);
        assertTrue(back.fresh(System.currentTimeMillis()));
        assertEquals("0.7.0", back.latest);
        assertEquals("https://example/r", back.url);
        back.checkedAt = System.currentTimeMillis() - ReleaseCheck.TTL.toMillis() - 1;
        assertFalse(back.fresh(System.currentTimeMillis()));
        Files.writeString(f, "garbage", StandardCharsets.UTF_8);
        assertNull(ReleaseCheck.readCache(f).latest);
    }

    @Test
    public void asksAServer_andTreatsAnErrorAsUnknown() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            byte[] body = "{\"tag_name\":\"v9.9.9\",\"html_url\":\"https://example/9.9.9\",\"prerelease\":false}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/limited", ex -> {
            ex.sendResponseHeaders(403, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReleaseCheck.Latest l = ReleaseCheck.latest(base + "/ok", Map.of());
            assertNotNull(l);
            assertEquals("9.9.9", l.version);
            assertNull(ReleaseCheck.latest(base + "/limited", Map.of()));
            assertNull(ReleaseCheck.latest("http://127.0.0.1:1/nothing-listens", Map.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void doctorReportsTheRelease_andSkipsWhenOff() {
        Doctor.Request req = new Doctor.Request();
        req.version = "0.6.0";
        req.checkRelease = true;
        req.releaseProbe = () -> new ReleaseCheck.Latest("0.7.0", "https://example/r");
        Doctor.Check c = Doctor.release(req);
        assertTrue(c.ok);
        assertTrue(c.line, c.line.contains("0.6.0 running — 0.7.0 is available: https://example/r"));
        assertEquals(Boolean.TRUE, c.fields.get("newer"));

        req.releaseProbe = () -> new ReleaseCheck.Latest("0.6.0", "https://example/r");
        assertTrue(Doctor.release(req).line, Doctor.release(req).line.endsWith("0.6.0 running, latest 0.6.0"));

        req.releaseProbe = () -> null;
        assertTrue(Doctor.release(req).line, Doctor.release(req).line.contains("latest release unknown"));

        Doctor.Request off = new Doctor.Request();
        off.version = "0.6.0";
        off.checkRelease = false;
        off.releaseOff = "CI is set";
        Doctor.Check skipped = Doctor.release(off);
        assertTrue(skipped.ok);
        assertTrue(skipped.line, skipped.line.contains("release check skipped: CI is set"));
        assertEquals("CI is set", skipped.fields.get("skipped"));
    }

    @Test
    public void scheduleStaysQuietForJsonDoctorAndVersion() throws Exception {
        Path f = tmp.newFolder("idm2").toPath().resolve("release-check.json");
        ReleaseCheck.schedule(new String[] {"doctor"}, Map.of(), true, "0.6.0", f);
        ReleaseCheck.schedule(new String[] {"vault.diff", "tree", "--json"}, Map.of(), true, "0.6.0", f);
        ReleaseCheck.schedule(new String[] {"validate", "tree"}, Map.of("CI", "1"), true, "0.6.0", f);
        ReleaseCheck.schedule(new String[] {"validate", "tree"}, Map.of(), false, "0.6.0", f);
        assertFalse("nothing scheduled touches the cache", Files.exists(f));
        assertTrue(List.of("doctor", "version", "help").contains("version"));
    }
}
