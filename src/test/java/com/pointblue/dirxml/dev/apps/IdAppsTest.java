package com.pointblue.dirxml.dev.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** The Identity Applications client against a stand-in for OSP and the applications, as the lab answered (docs/idapps-rest.md). */
public class IdAppsTest {

    private HttpServer server;
    private String base;
    /** What the stand-in saw: one line per request. */
    private final List<String> seen = new ArrayList<>();
    private int grants;

    private static final class Moving extends Clock {
        Instant now = Instant.parse("2026-10-30T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/osp/a/idm/auth/oauth2/token", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean client = ("Basic " + Base64.getEncoder().encodeToString("rbpmrest:secret".getBytes(StandardCharsets.UTF_8))).equals(auth);
            if (!client) {
                send(ex, 401, Map.of("error", "invalid_client"));
                return;
            }
            grants++;
            seen.add("grant " + form.replaceAll("password=[^&]*", "password=…"));
            if (form.contains("grant_type=password") && form.contains("username=pat") && form.contains("password=right")) {
                send(ex, 200, Map.of("access_token", "a" + grants, "token_type", "bearer", "expires_in", 120, "refresh_token", "r1"));
            } else if (form.contains("grant_type=refresh_token&refresh_token=r1")) {
                send(ex, 200, Map.of("access_token", "a" + grants, "token_type", "bearer", "expires_in", 120));
            } else {
                send(ex, 400, Map.of("error", "invalid_grant", "error_description", "nope"));
            }
        });
        server.createContext("/IDMProv/rest/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + ex.getRequestHeaders().getFirst("Authorization") + " " + body);
            String path = ex.getRequestURI().getPath();
            if (path.equals("/IDMProv/rest/access/permissions/item")) {
                send(ex, 489, Map.of("Fault", Map.of("Code", Map.of("Value", "Sender", "Subcode", Map.of("Value", "PermissionIndexException")),
                    "Reason", Map.of("Text", "Permission with id [x] does not exist."))));
            } else if (path.endsWith("/tasks/list")) {
                send(ex, 200, Map.of("tasks", List.of(Map.of("taskId", "t1", "processName", "Access")), "nextIndex", 0, "arraySize", 1));
            } else if (path.endsWith("/nowhere")) {
                ex.sendResponseHeaders(404, 0);
                ex.getResponseBody().write("<!doctype html><title>404</title>".getBytes(StandardCharsets.UTF_8));
                ex.close();
            } else {
                send(ex, 200, Map.of("success", true));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] b = Json.compact(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    public void oneSignInThenTheAccessTokenIsReusedAndRenewedWithTheRefreshToken() throws IOException {
        Moving clock = new Moving();
        IdApps apps = new IdApps(base, null, "secret", null, clock);
        IdApps.Tokens t = apps.password("pat", "right".toCharArray());
        assertEquals("r1", t.refresh());
        assertFalse("a token never prints", t.toString().contains("r1"));
        IdApps.Session s = apps.session(t);
        s.tasks(null, 1, 10);
        s.tasks("x", 1, 10);
        assertEquals("one sign-in for two calls", 1, grants);
        clock.now = clock.now.plusSeconds(101);
        s.tasks(null, 1, 10);
        assertEquals("renewed 20 s before it expires, with the refresh token", 2, grants);
        assertTrue(seen.contains("grant grant_type=refresh_token&refresh_token=r1"));
        assertEquals("OSP does not rotate it: the same refresh token is kept", "r1", s.refreshToken());
        assertTrue(seen.stream().anyMatch(x -> x.startsWith("GET /IDMProv/rest/access/tasks/list?q=x&fromIndex=1&size=10&sortOrder=desc Bearer a1")));
    }

    @Test
    public void aKeptRefreshTokenSignsInWithoutAPassword() throws IOException {
        IdApps apps = new IdApps(base, "rbpmrest", "secret", null);
        IdApps.Session s = apps.session("r1");
        s.act(List.of("t1", "t2"), "approve", "ok");
        assertTrue(seen.stream().anyMatch(x -> x.contains("grant_type=refresh_token")));
        assertTrue(seen.stream().anyMatch(x -> x.startsWith("POST /IDMProv/rest/access/tasks Bearer a1 ")
            && x.contains("\"tasks\":[{\"taskId\":\"t1\"},{\"taskId\":\"t2\"}]") && x.contains("\"action\":\"approve\"")));
        assertTrue(seen.stream().noneMatch(x -> x.contains("grant_type=password")));
    }

    @Test
    public void refusalsAndFaults() throws IOException {
        IdApps apps = new IdApps(base, null, "secret", null);
        IdApps.Refused bad = assertThrows(IdApps.Refused.class, () -> apps.password("pat", "wrong".toCharArray()));
        assertEquals("invalid_grant", bad.error);
        IdApps.Refused stale = assertThrows(IdApps.Refused.class, () -> apps.session("r-gone").tasks(null, 1, 1));
        assertEquals(400, stale.status);
        assertEquals("a wrong client secret: the refresh token alone is worth nothing", "invalid_client",
            assertThrows(IdApps.Refused.class, () -> new IdApps(base, null, "other", null).refresh("r1")).error);

        IdApps.Session s = apps.session(apps.password("pat", "right".toCharArray()));
        IdApps.Fault f = assertThrows(IdApps.Fault.class, () -> s.permission("cn=x", "prd"));
        assertEquals(489, f.status);
        assertEquals("PermissionIndexException", f.code);
        IdApps.Fault nowhere = assertThrows(IdApps.Fault.class, () -> s.get("/IDMProv/rest/access/nowhere", null));
        assertEquals(404, nowhere.status);
        assertNull(nowhere.code);
    }

    @Test
    public void revokeRetractAndTheOtherCallsSendWhatTheLabTook() throws IOException {
        IdApps apps = new IdApps(base, null, "secret", null);
        IdApps.Session s = apps.session(apps.password("pat", "right".toCharArray()));
        s.revoke("cn=Role,o=system", "ROLE", List.of(new IdApps.Session.Holder("cn=a,o=acme", "USER", null),
            new IdApps.Session.Holder("cn=b,o=acme", null, null)), "no longer needed");
        s.revoke("cn=Res,o=system", "resource", List.of(new IdApps.Session.Holder("cn=a,o=acme", "user", "8e38a4a7")), "gone");
        assertThrows("no holder: the server would revoke nothing", IllegalArgumentException.class, () -> s.revoke("cn=Role,o=system", "role", List.of(), "x"));
        s.retract("f517", "prd");
        s.actWithForm("t9", "DenyAction", "no", Map.of());
        s.holders("cn=Role,o=system", "ROLE", 1, 10);
        s.resourceAssignments("cn=Res,o=system", 1, 10);
        s.searchPermissions("resource", null, 1, 5);
        s.tasksOfOthers(null, "cn=a,o=acme", 5);
        s.workEntries("f517", 5);
        String all = String.join("\n", seen);
        assertTrue(all.contains("DELETE /IDMProv/rest/access/assignments/list Bearer a1 {\"reason\":\"no longer needed\",\"assignments\":[{\"id\":\"cn=Role,o=system\",\"entityType\":\"role\","
            + "\"assignmentToList\":[{\"subtype\":\"user\",\"assignedToDn\":\"cn=a,o=acme\"},{\"subtype\":\"user\",\"assignedToDn\":\"cn=b,o=acme\"}]}]}"));
        assertTrue("a resource holder carries its instance id", all.contains("{\"subtype\":\"user\",\"assignedToDn\":\"cn=a,o=acme\",\"instanceId\":\"8e38a4a7\"}"));
        assertTrue(all.contains("DELETE /IDMProv/rest/access/requests/history Bearer a1 {\"requests\":[{\"id\":\"f517\",\"entityType\":\"prd\"}]}"));
        assertTrue(all.contains("POST /IDMProv/rest/access/tasks/v2 Bearer a1 {\"taskId\":\"t9\",\"taskAction\":\"DenyAction\",\"comments\":\"no\",\"data\":[]}"));
        assertTrue(all.contains("GET /IDMProv/rest/access/assignments/identities?permissionId=cn%3DRole%2Co%3Dsystem&permissionType=role&nextIndex=1&size=10"));
        assertTrue(all.contains("POST /IDMProv/rest/catalog/resources/resource/assignments?nextIndex=1&size=10 Bearer a1 {\"dn\":\"cn=Res,o=system\"}"));
        assertTrue(all.contains("GET /IDMProv/rest/catalog/resources/listV2?q=*&nextIndex=1&size=5"));
        assertTrue(all.contains("GET /IDMProv/rest/access/tasks/list/others?q=*&recipient=cn%3Da%2Co%3Dacme&size=5"));
        assertTrue(all.contains("GET /IDMProv/rest/access/tasks/workEntries?processId=f517&size=5"));
    }

        @Test
    public void requestBodiesAreTheOnesTheLabTook() throws IOException {
        IdApps apps = new IdApps(base, null, "secret", null);
        IdApps.Session s = apps.session(apps.password("pat", "right".toCharArray()));
        s.requestPrd("cn=Access,cn=RequestDefs,o=system", Map.of("reason", List.of("for a test")), List.of("cn=pat,o=acme"));
        s.requestPermission("cn=Role,o=system", "role", List.of(), "needed");
        s.historyItem("p1", "prd");
        s.assignments("cn=pat,o=acme", 1, 5);
        s.catalogPrds(null, 1, 5);
        String all = String.join("\n", seen);
        assertTrue(all.contains("\"reqPermissions\":[{\"id\":\"cn=Access,cn=RequestDefs,o=system\",\"entityType\":\"PRD\"}]"));
        assertTrue(all.contains("\"data\":[{\"key\":\"reason\",\"value\":[\"for a test\"]}]"));
        assertTrue(all.contains("\"recipients\":[{\"dn\":\"cn=pat,o=acme\",\"type\":\"user\"}]"));
        assertTrue(all.contains("POST /IDMProv/rest/access/requests/permissions/item") && all.contains("\"entityType\":\"role\""));
        assertTrue(all.contains("\"id\":\"p1\"") && all.contains("\"entityType\":\"prd\""));
        assertTrue(all.contains("POST /IDMProv/rest/access/assignments/list") && all.contains("\"dn\":\"cn=pat,o=acme\""));
        assertTrue(all.contains("GET /IDMProv/rest/catalog/prds?q=*&nextIndex=1&size=5"));
    }
}
