package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.json.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The DirXML Trace Viewer (https://github.com/PointBlueTechnology/DirXMLTraceViewer), the desktop
 * viewer for driver trace, driven from here: found on the workstation, installed from the project's
 * GitHub releases or built from its source, and launched already connected to an environment's
 * vault with a driver selected, or already showing a trace file ({@code driver.trace view}).
 *
 * <p>Where it is looked for, in order: {@code IDM_TRACE_VIEWER} (a jar, a macOS app bundle, a
 * launcher script, or the directory holding {@code dirxml-trace-viewer.jar}), the {@code traceViewer}
 * system property, {@code ~/.idm/trace-viewer/dirxml-trace-viewer.jar} (where {@code viewer.install}
 * puts it), and on macOS {@code /Applications} and {@code ~/Applications}. The bind password is
 * handed to the viewer on its standard input, never as an argument.
 */
public final class TraceViewer {

    public static final String REPO = "PointBlueTechnology/DirXMLTraceViewer";
    public static final String REPO_URL = "https://github.com/" + REPO;
    public static final String ENV = "IDM_TRACE_VIEWER";
    public static final String PROPERTY = "traceViewer";
    public static final String JAR_NAME = "dirxml-trace-viewer.jar";
    public static final String APP_NAME = "DirXML Trace Viewer.app";
    /** The viewer reads the bind password from this variable or from stdin ({@code --password-stdin}). */
    public static final String PASSWORD_ENV = "DIRXML_TRACE_VIEWER_PASSWORD";

    /** Where the viewer is and how it was found. */
    public static final class Status {
        public final Path path;        // null when nothing was found
        public final String source;    // env IDM_TRACE_VIEWER | property traceViewer | installed | /Applications | none
        public final String version;   // from the install directory's version file, when known

        Status(Path path, String source, String version) {
            this.path = path;
            this.source = source;
            this.version = version;
        }

        public boolean ready() {
            return path != null && Files.exists(path);
        }

        public String describe() {
            if (path == null) {
                return "trace viewer: not installed (optional) — bin/idm viewer.install downloads the latest release from " + REPO_URL
                    + " into " + defaultDir() + "; or set " + ENV + " to a jar, the macOS app, or a launcher";
            }
            return "trace viewer: " + path + " (" + source + (version == null ? "" : ", " + version) + ")" + (Files.exists(path) ? "" : "  MISSING");
        }
    }

    private TraceViewer() {
    }

    public static Path defaultDir() {
        return Paths.get(System.getProperty("user.home"), ".idm", "trace-viewer");
    }

    public static Status locate() {
        return locate(System.getenv(ENV), System.getProperty(PROPERTY), defaultDir(), macCandidates());
    }

    static Status locate(String envPath, String propPath, Path installDir, List<Path> appCandidates) {
        if (envPath != null && !envPath.isBlank()) {
            return new Status(normalize(Paths.get(envPath)), "env " + ENV, null);
        }
        if (propPath != null && !propPath.isBlank()) {
            return new Status(normalize(Paths.get(propPath)), "property " + PROPERTY, null);
        }
        Path jar = installDir.resolve(JAR_NAME);
        if (Files.isRegularFile(jar)) {
            String version = null;
            try {
                Path v = installDir.resolve("version");
                version = Files.isRegularFile(v) ? Files.readString(v).strip() : null;
            } catch (IOException ignore) {
                // unknown version
            }
            return new Status(jar, "installed", version);
        }
        for (Path app : appCandidates) {
            if (Files.isDirectory(app)) {
                return new Status(app, app.getParent().toString(), null);
            }
        }
        return new Status(null, "none", null);
    }

    private static List<Path> macCandidates() {
        List<Path> out = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            out.add(Paths.get("/Applications", APP_NAME));
            out.add(Paths.get(System.getProperty("user.home"), "Applications", APP_NAME));
        }
        return out;
    }

    /** A directory given for the jar means the jar inside it. */
    private static Path normalize(Path p) {
        if (Files.isDirectory(p) && !p.getFileName().toString().endsWith(".app") && Files.isRegularFile(p.resolve(JAR_NAME))) {
            return p.resolve(JAR_NAME);
        }
        return p;
    }

    /** The command line for the viewer at {@code path} with {@code args}: the app's executable, {@code java -jar}, or the launcher script. */
    public static List<String> command(Path path, List<String> args) {
        List<String> cmd = new ArrayList<>();
        String name = path.getFileName().toString();
        if (name.endsWith(".app")) {
            cmd.add(path.resolve("Contents").resolve("MacOS").resolve("DirXML Trace Viewer").toString());
        } else if (name.endsWith(".jar")) {
            cmd.add(ProcessHandle.current().info().command().orElse("java"));
            cmd.add("-Xmx2g");
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("-jar");
            cmd.add(path.toString());
        } else {
            cmd.add(path.toString());
        }
        cmd.addAll(args);
        return cmd;
    }

    /** The viewer's arguments to open a file. */
    public static List<String> openArgs(Path file) {
        return List.of("--open", file.toAbsolutePath().toString());
    }

    /** The viewer's arguments to connect to an environment's vault; the password goes on stdin. */
    public static List<String> connectArgs(Environments.Environment env, String driver) {
        List<String> a = new ArrayList<>(List.of("--connect", env.url, "--bind-dn", env.bindDn, "--password-stdin"));
        String base = env.driverSetDn.indexOf(',') < 0 ? "" : env.driverSetDn.substring(env.driverSetDn.indexOf(',') + 1);
        if (!base.isBlank()) {
            a.add("--search-base");
            a.add(base);
        }
        if (driver != null && !driver.isBlank()) {
            a.add("--driver");
            a.add(driver);
        }
        return a;
    }

    /**
     * Start the viewer detached with {@code args}; {@code password}, when given, is written to its
     * standard input and nothing else is. Returns the process id.
     */
    public static long launch(Path path, List<String> args, String password) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(path, args));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        try (OutputStream in = p.getOutputStream()) {
            if (password != null) {
                in.write((password + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return p.pid();
    }

    // ---- install ----

    /** What {@link #install} did. */
    public static final class Installed {
        public final Path jar;
        public final String version;
        public final String how;

        Installed(Path jar, String version, String how) {
            this.jar = jar;
            this.version = version;
            this.how = how;
        }
    }

    /**
     * Download {@code dirxml-trace-viewer.jar} of the latest release (or of {@code tag}) from GitHub
     * into {@code dir}, checked against the release's {@code SHA256SUMS}; {@code apiBase} is GitHub's
     * API for the repository (a test points it at a local server).
     */
    public static Installed installRelease(Path dir, String tag, String apiBase, Consumer<String> notes) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
        String url = apiBase + "/releases/" + (tag == null || tag.isBlank() ? "latest" : "tags/" + tag);
        Map<String, Object> release = Json.asMap(Json.parse(get(client, url)));
        String version = Json.asString(release.get("tag_name"));
        String jarUrl = null;
        String sumsUrl = null;
        for (Object o : Json.asList(release.get("assets"))) {
            Map<String, Object> a = Json.asMap(o);
            String name = Json.asString(a.get("name"));
            if (JAR_NAME.equals(name)) {
                jarUrl = Json.asString(a.get("browser_download_url"));
            } else if ("SHA256SUMS".equals(name)) {
                sumsUrl = Json.asString(a.get("browser_download_url"));
            }
        }
        if (jarUrl == null) {
            throw new IOException("release " + version + " has no " + JAR_NAME + " asset");
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(JAR_NAME + ".part");
        download(client, jarUrl, tmp);
        if (sumsUrl != null) {
            String expected = null;
            for (String line : get(client, sumsUrl).split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2 && parts[parts.length - 1].endsWith(JAR_NAME)) {
                    expected = parts[0].toLowerCase(Locale.ROOT);
                }
            }
            if (expected != null) {
                String actual = sha256(tmp);
                if (!expected.equals(actual)) {
                    Files.deleteIfExists(tmp);
                    throw new IOException(JAR_NAME + " of " + version + " does not match SHA256SUMS (expected " + expected + ", got " + actual + ")");
                }
                notes.accept("SHA-256 verified against the release's SHA256SUMS");
            } else {
                notes.accept("SHA256SUMS has no line for " + JAR_NAME + "; the download was not verified");
            }
        } else {
            notes.accept("the release has no SHA256SUMS asset; the download was not verified");
        }
        Path jar = dir.resolve(JAR_NAME);
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve("version"), version + "\n");
        return new Installed(jar, version, "release " + version + " from " + REPO_URL);
    }

    /** Clone the repository (at {@code tag}, or its default branch) under {@code dir/source} and build the jar with Maven. */
    public static Installed installFromSource(Path dir, String tag, String repoUrl, Consumer<String> notes) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path src = dir.resolve("source");
        if (Files.isDirectory(src.resolve(".git"))) {
            run(src, notes, "git", "fetch", "-q", "--tags", "origin");
            run(src, notes, "git", "checkout", "-q", tag == null || tag.isBlank() ? "master" : tag);
            if (tag == null || tag.isBlank()) {
                run(src, notes, "git", "pull", "-q", "--ff-only");
            }
        } else {
            List<String> clone = new ArrayList<>(List.of("git", "clone", "-q", "--depth", "1"));
            if (tag != null && !tag.isBlank()) {
                clone.add("--branch");
                clone.add(tag);
            }
            clone.add(repoUrl);
            clone.add(src.toString());
            run(dir, notes, clone.toArray(new String[0]));
        }
        run(src, notes, mavenCommand(), "-q", "-DskipTests", "package");
        Path built = src.resolve("target").resolve(JAR_NAME);
        if (!Files.isRegularFile(built)) {
            throw new IOException("the build produced no " + built);
        }
        Path jar = dir.resolve(JAR_NAME);
        Files.copy(built, jar, StandardCopyOption.REPLACE_EXISTING);
        String version = describe(src);
        Files.writeString(dir.resolve("version"), version + "\n");
        return new Installed(jar, version, "built from " + repoUrl + " (" + version + ")");
    }

    private static String mavenCommand() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "mvn.cmd" : "mvn";
    }

    private static String describe(Path src) {
        try {
            Process p = new ProcessBuilder("git", "describe", "--tags", "--always").directory(src.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor();
            return out.isEmpty() ? "source" : out;
        } catch (Exception e) {
            return "source";
        }
    }

    private static void run(Path cwd, Consumer<String> notes, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.putIfAbsent("JAVA_HOME", System.getProperty("java.home"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IOException(String.join(" ", cmd) + " failed (" + rc + "): " + out.strip());
        }
        notes.accept(String.join(" ", cmd) + ": ok");
    }

    private static String get(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException(url + ": HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private static void download(HttpClient client, String url, Path out) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(out,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(out);
            throw new IOException(url + ": HTTP " + resp.statusCode());
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
