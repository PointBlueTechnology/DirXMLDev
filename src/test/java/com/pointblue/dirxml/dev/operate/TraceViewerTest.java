package com.pointblue.dirxml.dev.operate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Finding, launching and installing the DirXML Trace Viewer, without a network or a screen. */
public class TraceViewerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void locateInOrder() throws Exception {
        Path dir = tmp.newFolder("tv").toPath();
        assertEquals("none", TraceViewer.locate(null, null, dir, List.of()).source);
        assertFalse(TraceViewer.locate(null, null, dir, List.of()).ready());
        Path jar = dir.resolve(TraceViewer.JAR_NAME);
        Files.write(jar, new byte[] {1});
        Files.writeString(dir.resolve("version"), "v1.2.0\n");
        TraceViewer.Status installed = TraceViewer.locate(null, null, dir, List.of());
        assertEquals("installed", installed.source);
        assertEquals("v1.2.0", installed.version);
        assertEquals(jar, installed.path);
        TraceViewer.Status env = TraceViewer.locate(dir.toString(), null, tmp.newFolder("other").toPath(), List.of());
        assertEquals("a directory given for the jar means the jar inside it", jar, env.path);
        assertTrue(env.source.startsWith("env"));
        Path app = tmp.newFolder("DirXML Trace Viewer.app").toPath();
        TraceViewer.Status mac = TraceViewer.locate(null, null, tmp.newFolder("empty").toPath(), List.of(app));
        assertEquals(app, mac.path);
        assertTrue(TraceViewer.locate(null, "/nowhere/x.jar", dir, List.of()).describe().contains("MISSING"));
    }

    @Test
    public void commandsForEachShape() throws Exception {
        List<String> jar = TraceViewer.command(Path.of("/x/dirxml-trace-viewer.jar"), List.of("--demo"));
        assertEquals("-jar", jar.get(jar.size() - 3));
        assertEquals("--demo", jar.get(jar.size() - 1));
        List<String> app = TraceViewer.command(Path.of("/Applications/DirXML Trace Viewer.app"), List.of());
        assertTrue(app.get(0).endsWith("Contents/MacOS/DirXML Trace Viewer"));
        List<String> script = TraceViewer.command(Path.of("/opt/tv/dirxml-trace-viewer.sh"), List.of("--open", "f"));
        assertEquals(List.of("/opt/tv/dirxml-trace-viewer.sh", "--open", "f"), script);
    }

    @Test
    public void installReleaseDownloadsAndVerifiesAgainstSha256Sums() throws Exception {
        byte[] jarBytes = "fake jar".getBytes(StandardCharsets.UTF_8);
        Path stage = tmp.newFolder("stage").toPath();
        Files.write(stage.resolve("jar"), jarBytes);
        String sha = TraceViewer.sha256(stage.resolve("jar"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        server.createContext("/repos/x/releases/latest", ex -> reply(ex, 200, ("{\"tag_name\":\"v9.9.9\",\"assets\":["
            + "{\"name\":\"dirxml-trace-viewer.jar\",\"browser_download_url\":\"" + base + "/dl/dirxml-trace-viewer.jar\"},"
            + "{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"" + base + "/dl/SHA256SUMS\"}]}").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/dl/dirxml-trace-viewer.jar", ex -> reply(ex, 200, jarBytes));
        server.createContext("/dl/SHA256SUMS", ex -> reply(ex, 200, (sha + "  dirxml-trace-viewer.jar\nabc  other.zip\n").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/repos/bad/releases/latest", ex -> reply(ex, 200, ("{\"tag_name\":\"v0\",\"assets\":["
            + "{\"name\":\"dirxml-trace-viewer.jar\",\"browser_download_url\":\"" + base + "/dl/dirxml-trace-viewer.jar\"},"
            + "{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"" + base + "/dl/BADSUMS\"}]}").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/dl/BADSUMS", ex -> reply(ex, 200, ("0000  dirxml-trace-viewer.jar\n").getBytes(StandardCharsets.UTF_8)));
        server.start();
        try {
            Path dir = tmp.newFolder("install").toPath();
            List<String> notes = new ArrayList<>();
            TraceViewer.Installed done = TraceViewer.installRelease(dir, null, base + "/repos/x", notes::add);
            assertEquals("v9.9.9", done.version);
            assertTrue(Files.isRegularFile(done.jar));
            assertEquals("v9.9.9", Files.readString(dir.resolve("version")).strip());
            assertTrue(notes.toString(), notes.stream().anyMatch(n -> n.contains("verified")));
            assertEquals("installed", TraceViewer.locate(null, null, dir, List.of()).source);
            assertNull(Files.exists(dir.resolve("dirxml-trace-viewer.jar.part")) ? "part file left behind" : null);

            Path dir2 = tmp.newFolder("install2").toPath();
            try {
                TraceViewer.installRelease(dir2, null, base + "/repos/bad", notes::add);
                org.junit.Assert.fail("expected the checksum mismatch to refuse");
            } catch (java.io.IOException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("does not match SHA256SUMS"));
                assertFalse(Files.exists(dir2.resolve(TraceViewer.JAR_NAME)));
            }
        } finally {
            server.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, byte[] body) throws java.io.IOException {
        ex.sendResponseHeaders(code, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }
}
