package com.pointblue.dirxml.dev.apps;

import com.pointblue.dirxml.dev.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;

/**
 * A client for Identity Applications' REST API (docs/idapps-rest.md), vendor-free: the JDK's HTTP client and this
 * project's JSON. It signs a person in once with OSP's password grant and then keeps their refresh token, renewing
 * the access token only when it is about to expire: every password sign-in writes to the person's
 * {@code oidpInstanceData}, and at about 32 KB every sign-in for them fails (-649); a refresh writes nothing (lab,
 * 2026-10-30). OSP does not rotate the refresh token, and it is worth nothing without the client's secret.
 *
 * <p>Each call answers the applications' JSON. A refusal the applications explain ({@code {"Fault": …}}, HTTP 489 or
 * 400) is a {@link Fault}; a refused grant (a wrong password, an expired or revoked refresh token) is
 * {@link Refused}. A 200 with {@code "success": false} is an answer, for the caller to read.
 */
public final class IdApps {

    public static final String TOKEN = "/osp/a/idm/auth/oauth2/token";
    public static final String ACCESS = "/IDMProv/rest/access";
    public static final String CATALOG = "/IDMProv/rest/catalog";
    /** The documented REST client (the dashboard's own is {@code rbpm}). */
    public static final String DEFAULT_CLIENT = "rbpmrest";
    /** An access token is renewed this long before it expires (OSP's last two minutes by default). */
    static final Duration EARLY = Duration.ofSeconds(20);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** OSP's answer to a grant. {@code refresh} is null when OSP sent none (a refresh answers an access token only). */
    public record Tokens(String access, String refresh, Instant expires) {
        @Override
        public String toString() {
            return "Tokens[expires=" + expires + "]";   // never the tokens
        }
    }

    /** OSP refused a grant: {@code error} is OSP's code ({@code invalid_grant}, {@code invalid_client} …). */
    public static final class Refused extends IOException {
        public final int status;
        public final String error;

        Refused(int status, String error, String description) {
            super("OSP refused the sign-in (" + status + (error == null ? "" : " " + error) + ")" + (description == null ? "" : ": " + description));
            this.status = status;
            this.error = error;
        }
    }

    /** The applications refused a call: {@code code} is the fault's subcode ({@code PermissionIndexException}, {@code InvalidInput} …). */
    public static final class Fault extends IOException {
        public final int status;
        public final String code;

        Fault(int status, String code, String reason) {
            super((code == null ? "HTTP " + status : code) + (reason == null ? "" : ": " + reason));
            this.status = status;
            this.code = code;
        }
    }

    private final String base;
    private final String client;
    private final String secret;
    private final HttpClient http;
    private final Clock clock;

    /** {@code baseUrl}: {@code https://<apps host>}; {@code tls}: whom to trust (null: Java's default). */
    public IdApps(String baseUrl, String client, String secret, SSLContext tls) {
        this(baseUrl, client, secret, tls, Clock.systemUTC());
    }

    IdApps(String baseUrl, String client, String secret, SSLContext tls, Clock clock) {
        this.base = baseUrl.replaceAll("/+$", "");
        this.client = client == null || client.isBlank() ? DEFAULT_CLIENT : client;
        this.secret = secret;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER);
        if (tls != null) {
            b.sslContext(tls);
        }
        this.http = b.build();
        this.clock = clock;
    }

    // ---- tokens ----

    /** Sign {@code user} in with their password (OSP's password grant). Use once, then {@link #session}. */
    public Tokens password(String user, char[] password) throws IOException {
        return grant("grant_type=password&username=" + enc(user) + "&password=" + enc(new String(password)));
    }

    /** A new access token from a refresh token; the answer's {@code refresh} is the same token (OSP does not rotate it). */
    public Tokens refresh(String refreshToken) throws IOException {
        Tokens t = grant("grant_type=refresh_token&refresh_token=" + enc(refreshToken));
        return new Tokens(t.access(), t.refresh() == null ? refreshToken : t.refresh(), t.expires());
    }

    private Tokens grant(String form) throws IOException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + TOKEN)).timeout(TIMEOUT)
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((client + ":" + secret).getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> a = send(r);
        Map<String, Object> m = parse(a.body());
        if (a.statusCode() != 200 || m.get("access_token") == null) {
            throw new Refused(a.statusCode(), Json.asString(m.get("error")), Json.asString(m.get("error_description")));
        }
        long seconds = Json.asInt(m.get("expires_in"), 120);
        return new Tokens(Json.asString(m.get("access_token")), Json.asString(m.get("refresh_token")), clock.instant().plusSeconds(seconds));
    }

    /** A person's calls, from the tokens of their sign-in (or a refresh token kept since, with no access token yet). */
    public Session session(Tokens tokens) {
        return new Session(tokens);
    }

    public Session session(String refreshToken) {
        return new Session(new Tokens(null, refreshToken, Instant.EPOCH));
    }

    /** One person's tokens: the access token reused until it is about to expire, then renewed with the refresh token. */
    public final class Session {
        private Tokens tokens;

        Session(Tokens tokens) {
            this.tokens = tokens;
        }

        /** The access token, renewed first when needed; {@link Refused} when the refresh token no longer works. */
        public synchronized String access() throws IOException {
            if (tokens.access() == null || !clock.instant().plus(EARLY).isBefore(tokens.expires())) {
                if (tokens.refresh() == null) {
                    throw new Refused(401, "invalid_grant", "no refresh token");
                }
                tokens = refresh(tokens.refresh());
            }
            return tokens.access();
        }

        /** The refresh token to keep (null when OSP gave none). */
        public synchronized String refreshToken() {
            return tokens.refresh();
        }

        public Map<String, Object> get(String path, Map<String, ?> query) throws IOException {
            return call("GET", path, query, null);
        }

        public Map<String, Object> post(String path, Object body) throws IOException {
            return call("POST", path, null, body);
        }

        /** {@code path} is under the server ({@code /IDMProv/rest/…}); {@code query} values may be lists (a repeated key). */
        public Map<String, Object> call(String method, String path, Map<String, ?> query, Object body) throws IOException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path + query(query))).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + access())
                .header("Accept", "application/json");
            if (body != null) {
                b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(Json.compact(body)));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> a = send(b.build());
            Map<String, Object> m = parse(a.body());
            if (m.get("Fault") instanceof Map<?, ?> f) {
                Map<String, Object> fault = Json.asMap(f);
                Object code = fault.get("Code") instanceof Map<?, ?> c && c.get("Subcode") instanceof Map<?, ?> s ? s.get("Value") : null;
                Object reason = fault.get("Reason") instanceof Map<?, ?> r ? r.get("Text") : null;
                throw new Fault(a.statusCode(), Json.asString(code), Json.asString(reason));
            }
            if (a.statusCode() >= 300) {
                throw new Fault(a.statusCode(), null, a.statusCode() == 401 ? "the token was refused" : a.statusCode() == 404 ? "no such path " + path : null);
            }
            return m;
        }

        // ---- the calls (docs/idapps-rest.md) ----

        /** The person's open tasks (§5): {@code tasks}, {@code nextIndex}, {@code arraySize}. */
        public Map<String, Object> tasks(String q, int fromIndex, int size) throws IOException {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("q", q == null || q.isBlank() ? "*" : q);
            query.put("fromIndex", Math.max(1, fromIndex));
            query.put("size", Math.max(1, size));
            query.put("sortOrder", "desc");
            return get(ACCESS + "/tasks/list", query);
        }

        /** One task with its data items (§5). */
        public Map<String, Object> task(String taskId) throws IOException {
            return post(ACCESS + "/tasks/item", ordered("taskId", taskId));
        }

        /** Act on tasks (§6): {@code action} approve, deny, claim, release, refuse or comment. */
        public Map<String, Object> act(List<String> taskIds, String action, String comment) throws IOException {
            List<Object> tasks = new ArrayList<>();
            taskIds.forEach(t -> tasks.add(ordered("taskId", t)));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("tasks", tasks);
            b.put("action", action);
            if (comment != null && !comment.isBlank()) {
                b.put("comment", comment);
            }
            return post(ACCESS + "/tasks", b);
        }

        /**
         * Start a PRD with a JSON request form (§2): {@code data} maps each form key to its values; {@code recipients}
         * are user DNs (none: the person themselves).
         */
        public Map<String, Object> requestPrd(String prdDn, Map<String, List<String>> data, List<String> recipients) throws IOException {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("reqPermissions", List.of(ordered("id", prdDn, "entityType", "PRD")));
            List<Object> d = new ArrayList<>();
            (data == null ? Map.<String, List<String>>of() : data).forEach((k, v) -> d.add(ordered("key", k, "value", v)));
            b.put("data", d);
            if (recipients != null && !recipients.isEmpty()) {
                b.put("recipients", recipients.stream().map(r -> ordered("dn", r, "type", "user")).toList());
            }
            return post(ACCESS + "/requests/permissions/v2", b);
        }

        /** Request a role or a resource (§8, {@code /requests/permissions/item}); {@code entityType} role or resource. */
        public Map<String, Object> requestPermission(String id, String entityType, List<String> recipients, String reason) throws IOException {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("id", id);
            b.put("entityType", entityType);
            if (reason != null && !reason.isBlank()) {
                b.put("reason", reason);
            }
            if (recipients != null && !recipients.isEmpty()) {
                b.put("recipients", recipients.stream().map(r -> ordered("dn", r, "type", "user")).toList());
            }
            return post(ACCESS + "/requests/permissions/item", b);
        }

        /** The person's requests, newest first (§7): {@code requests}. */
        public Map<String, Object> history(String q, int nextIndex, int size) throws IOException {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("q", q == null || q.isBlank() ? "*" : q);
            query.put("nextIndex", Math.max(1, nextIndex));
            query.put("size", Math.max(1, size));
            query.put("sortOrder", "desc");
            query.put("sortBy", "requestDate");
            return get(ACCESS + "/requests/historylist", query);
        }

        /** One request with its comments (§7); {@code entityType} as {@link #history} gave it ({@code prd}, …). */
        public Map<String, Object> historyItem(String id, String entityType) throws IOException {
            return post(ACCESS + "/requests/history/item", ordered("id", id, "entityType", entityType));
        }

        /** A permission from the permission index (§4): a {@link Fault} {@code PermissionIndexException} when it is not there. */
        public Map<String, Object> permission(String id, String entityType) throws IOException {
            return post(ACCESS + "/permissions/item", ordered("id", id, "entityType", entityType));
        }

        /** A person's role and resource assignments (§11): {@code total}, {@code assignments}. */
        public Map<String, Object> assignments(String userDn, int nextIndex, int size) throws IOException {
            return post(ACCESS + "/assignments/list", ordered("dn", userDn, "nextIndex", Math.max(1, nextIndex), "size", Math.max(1, size)));
        }

        /** The catalog's request definitions (§11): {@code requestDefs}. It can list PRDs since deleted from the vault. */
        public Map<String, Object> catalogPrds(String q, int nextIndex, int size) throws IOException {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("q", q == null || q.isBlank() ? "*" : q);
            query.put("nextIndex", Math.max(1, nextIndex));
            query.put("size", Math.max(1, size));
            return get(CATALOG + "/prds", query);
        }

        /** The permission index's roles or resources matching {@code q} (§13): {@code roles} or {@code resources}. */
        public Map<String, Object> searchPermissions(String entityType, String q, int nextIndex, int size) throws IOException {
            String kind = "resource".equalsIgnoreCase(entityType) ? "resources" : "roles";
            return get(CATALOG + "/" + kind + "/listV2", page(q == null ? "*" : q, nextIndex, size));
        }

        /**
         * Who holds a role or resource (§13): {@code assignmentToList} [{subtype, assignedToDn, assigneeFullName}]. Only
         * holders under the applications' user search root are listed.
         */
        public Map<String, Object> holders(String permissionDn, String entityType, int nextIndex, int size) throws IOException {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("permissionId", permissionDn);
            query.put("permissionType", entityType.toLowerCase(java.util.Locale.ROOT));
            query.put("nextIndex", Math.max(1, nextIndex));
            query.put("size", Math.max(1, size));
            return get(ACCESS + "/assignments/identities", query);
        }

        /** A resource's assignments with their instance ids (§13): {@code assignmentStatusList} [{recipientDn, instanceGuId, …}]. */
        public Map<String, Object> resourceAssignments(String resourceDn, int nextIndex, int size) throws IOException {
            return call("POST", CATALOG + "/resources/resource/assignments", page(null, nextIndex, size), ordered("dn", resourceDn));
        }

        /** One holder of a permission to revoke from: {@code subtype} user, group or container; {@code instanceId} for a resource. */
        public record Holder(String dn, String subtype, String instanceId) {
        }

        /**
         * Revoke a role or resource from {@code holders} (§13, {@code DELETE /assignments/list}). A resource needs each
         * holder's assignment instance id ({@link #resourceAssignments}). With no holder the server answers success and
         * revokes nothing, so none is refused here.
         */
        public Map<String, Object> revoke(String permissionDn, String entityType, List<Holder> holders, String reason) throws IOException {
            if (holders == null || holders.isEmpty()) {
                throw new IllegalArgumentException("name at least one holder: without one the server revokes nothing");
            }
            List<Object> to = new ArrayList<>();
            for (Holder h : holders) {
                Map<String, Object> m = ordered("subtype", h.subtype() == null ? "user" : h.subtype().toLowerCase(java.util.Locale.ROOT),
                    "assignedToDn", h.dn());
                if (h.instanceId() != null) {
                    m.put("instanceId", h.instanceId());
                }
                to.add(m);
            }
            Map<String, Object> body = ordered("reason", reason, "assignments",
                List.of(ordered("id", permissionDn, "entityType", entityType.toLowerCase(java.util.Locale.ROOT), "assignmentToList", to)));
            return call("DELETE", ACCESS + "/assignments/list", null, body);
        }

        /** Retract a request (§13): the workflow ends; a role request it was approving stays as it was. */
        public Map<String, Object> retract(String requestId, String entityType) throws IOException {
            return call("DELETE", ACCESS + "/requests/history", null, ordered("requests", List.of(ordered("id", requestId, "entityType", entityType))));
        }

        /**
         * Act on one task the way the JSON approval form does (§13, {@code POST /tasks/v2}): {@code taskAction}
         * ApprovalAction or DenyAction, {@code data} the approval form's fields. Every refusal reads "Form submission
         * failed to process.".
         */
        public Map<String, Object> actWithForm(String taskId, String taskAction, String comments, Map<String, List<String>> data) throws IOException {
            List<Object> d = new ArrayList<>();
            (data == null ? Map.<String, List<String>>of() : data).forEach((k, v) -> d.add(ordered("key", k, "value", v)));
            return post(ACCESS + "/tasks/v2", ordered("taskId", taskId, "taskAction", taskAction, "comments", comments == null ? "" : comments, "data", d));
        }

        /** Other people's tasks, for a help desk user or an administrator (§13). */
        public Map<String, Object> tasksOfOthers(String q, String recipient, int size) throws IOException {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("q", q == null || q.isBlank() ? "*" : q);
            if (recipient != null && !recipient.isBlank()) {
                query.put("recipient", recipient);
            }
            query.put("size", Math.max(1, size));
            return get(ACCESS + "/tasks/list/others", query);
        }

        /** A request's work entries, its approval tasks with their addressees (§13). */
        public Map<String, Object> workEntries(String requestId, int size) throws IOException {
            return get(ACCESS + "/tasks/workEntries", ordered("processId", requestId, "size", Math.max(1, size)));
        }
    }

    private static Map<String, Object> page(String q, int nextIndex, int size) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (q != null) {
            query.put("q", q.isBlank() ? "*" : q);
        }
        query.put("nextIndex", Math.max(1, nextIndex));
        query.put("size", Math.max(1, size));
        return query;
    }

    // ---- plumbing ----

    private HttpResponse<String> send(HttpRequest r) throws IOException {
        try {
            return http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** A JSON object in the order given (key, value, key, value …): bodies read the same in every log. */
    static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object o = Json.parse(body);
            return o instanceof Map<?, ?> ? Json.asMap(o) : new LinkedHashMap<>(Map.of("items", o));
        } catch (RuntimeException e) {
            // an HTML error page (a wrong path is Tomcat's 404): only its size, never its text
            return new LinkedHashMap<>(Map.of("unparsed", body.length()));
        }
    }

    private static String query(Map<String, ?> q) {
        if (q == null || q.isEmpty()) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        q.forEach((k, v) -> {
            for (Object one : v instanceof List<?> l ? l : List.of(v)) {
                s.append(s.isEmpty() ? '?' : '&').append(enc(k)).append('=').append(enc(String.valueOf(one)));
            }
        });
        return s.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
