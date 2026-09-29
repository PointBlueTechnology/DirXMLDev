package com.pointblue.dirxml.dev;

import com.pointblue.dirxml.dev.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Is there a newer DirXMLDev release on GitHub? Two uses (docs/install.md §2.5):
 *
 * <ul>
 *   <li>{@code doctor} asks live ({@link #latest}) and reports the answer as its {@code release}
 *       line — never a failure, and skipped when the check is off.</li>
 *   <li>Every other command prints at most one line a day on stderr when a newer release is
 *       known ({@link #schedule}): the cache in {@code ~/.idm/release-check.json} is read at
 *       start, refreshed in a daemon thread when older than a day, and the notice goes out
 *       from a shutdown hook — so a command is never slowed and never fails because of it.</li>
 * </ul>
 *
 * <p>Off when {@code IDM_NO_UPDATE_CHECK} is set, when {@code CI} is set, or when there is no
 * console (stdout piped: a script, the MCP server). Never inside {@code --json} output. The
 * request has a two-second timeout and every failure is silent. {@code GITHUB_TOKEN} is sent
 * when present, which lifts GitHub's unauthenticated rate limit (sixty an hour; the daily cache
 * never approaches it). Pre-releases are ignored.
 */
public final class ReleaseCheck {

    public static final String REPO = "PointBlueTechnology/DirXMLDev";
    public static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    public static final String OFF_ENV = "IDM_NO_UPDATE_CHECK";
    public static final Duration TTL = Duration.ofHours(24);
    static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** What GitHub said: the latest non-prerelease tag (without its {@code v}) and its page. */
    public static final class Latest {
        public final String version;
        public final String url;

        public Latest(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    /** The cache file: when it was refreshed, what it found, and when a notice last went out. */
    public static final class Cache {
        public long checkedAt;
        public String latest;
        public String url;
        public long noticedAt;

        public boolean fresh(long now) {
            return checkedAt > 0 && now - checkedAt < TTL.toMillis();
        }
    }

    private ReleaseCheck() {
    }

    // ---- the question ----

    /** Why the check is off for this process, or null when it is on. */
    public static String disabledReason(Map<String, String> env, boolean console) {
        if (env.get(OFF_ENV) != null && !env.get(OFF_ENV).isBlank()) {
            return OFF_ENV + " is set";
        }
        if (env.get("CI") != null && !env.get("CI").isBlank()) {
            return "CI is set";
        }
        if (!console) {
            return "no console";
        }
        return null;
    }

    /** Ask GitHub for the latest release. Null on any failure (network, rate limit, a page that is not a release). */
    public static Latest latest(String url, Map<String, String> env) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json").header("User-Agent", "DirXMLDev").GET();
            String token = env.get("GITHUB_TOKEN");
            if (token != null && !token.isBlank()) {
                b.header("Authorization", "Bearer " + token.trim());
            }
            HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                return null;
            }
            return parse(res.body());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** The release out of GitHub's JSON, or null when it is a pre-release, a draft, or not a release at all. */
    public static Latest parse(String body) {
        try {
            Map<String, Object> o = Json.asMap(Json.parse(body));
            if (Boolean.TRUE.equals(o.get("prerelease")) || Boolean.TRUE.equals(o.get("draft"))) {
                return null;
            }
            String tag = Json.asString(o.get("tag_name"));
            if (tag == null || tag.isBlank()) {
                return null;
            }
            String url = Json.asString(o.get("html_url"));
            return new Latest(tag.startsWith("v") ? tag.substring(1) : tag, url == null || url.isBlank() ? RELEASES_PAGE : url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The one-line notice for a newer release, or null when the running version is current or unknown. */
    public static String notice(String current, String latest, String url) {
        if (current == null || latest == null || Version.compare(current, latest) >= 0) {
            return null;
        }
        return "note: DirXMLDev " + latest + " is available (running " + current + "): " + (url == null ? RELEASES_PAGE : url)
            + " — git pull && mvn -o package, or the release jar";
    }

    // ---- the cache ----

    public static Path cacheFile() {
        return Path.of(System.getProperty("user.home"), ".idm", "release-check.json");
    }

    public static Cache readCache(Path file) {
        Cache c = new Cache();
        try {
            if (!Files.isRegularFile(file)) {
                return c;
            }
            Map<String, Object> o = Json.asMap(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
            c.checkedAt = o.get("checkedAt") instanceof Number ? ((Number) o.get("checkedAt")).longValue() : 0;
            c.noticedAt = o.get("noticedAt") instanceof Number ? ((Number) o.get("noticedAt")).longValue() : 0;
            c.latest = Json.asString(o.get("latest"));
            c.url = Json.asString(o.get("url"));
        } catch (IOException | RuntimeException e) {
            // an unreadable cache is an empty one
        }
        return c;
    }

    public static void writeCache(Path file, Cache c) {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("checkedAt", c.checkedAt);
            o.put("latest", c.latest);
            o.put("url", c.url);
            o.put("noticedAt", c.noticedAt);
            Files.writeString(file, Json.compact(o) + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    // ---- the passive notice ----

    /**
     * Wire the passive notice into a command: refresh the cache in the background when it is
     * stale, and print the notice at exit when a newer release is known and none went out today.
     * Does nothing when the check is off, when the command already reports releases
     * ({@code doctor}, {@code version}) or when the output is {@code --json}.
     */
    public static void schedule(String[] args, Map<String, String> env, boolean console, String current, Path cacheFile) {
        if (current == null || args.length == 0 || disabledReason(env, console) != null) {
            return;
        }
        String cmd = args[0];
        if (cmd.equals("doctor") || cmd.equals("version") || cmd.equals("help")) {
            return;
        }
        for (String a : args) {
            if (a.equals("--json")) {
                return;
            }
        }
        Cache cache = readCache(cacheFile);
        long now = System.currentTimeMillis();
        if (!cache.fresh(now)) {
            Thread t = new Thread(() -> {
                Latest l = latest(LATEST_URL, env);
                if (l != null) {
                    Cache c = readCache(cacheFile);
                    c.checkedAt = System.currentTimeMillis();
                    c.latest = l.version;
                    c.url = l.url;
                    writeCache(cacheFile, c);
                }
            }, "release-check");
            t.setDaemon(true);
            t.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Cache c = readCache(cacheFile);
            String n = notice(current, c.latest, c.url);
            if (n != null && System.currentTimeMillis() - c.noticedAt >= TTL.toMillis()) {
                System.err.println(n);
                c.noticedAt = System.currentTimeMillis();
                writeCache(cacheFile, c);
            }
        }, "release-notice"));
    }
}
