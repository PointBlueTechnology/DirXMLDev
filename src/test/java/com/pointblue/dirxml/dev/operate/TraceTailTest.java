package com.pointblue.dirxml.dev.operate;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TraceTailTest {

    @Test
    public void timestampsAndQuoting() {
        assertNotNull(TraceTail.timestamp("[09/09/26 08:59:28.431]:QT ST:QTestSubscriptionShim: execute"));
        assertNull(TraceTail.timestamp("<nds dtdversion=\"4.0\">"));
        assertNull(TraceTail.timestamp(""));
        assertEquals("'/opt/novell/it''s.trace'".replace("''", "'\\''"), TraceTail.q("/opt/novell/it's.trace"));
    }

    /** A fake ssh: a script that runs the remote command locally, so tail/grep/since are exercised end to end. */
    @Test
    public void tailGrepAndSinceThroughAFakeSsh() throws IOException {
        Path dir = Files.createTempDirectory("tt");
        Path fake = dir.resolve("ssh");
        Files.writeString(fake, "#!/bin/sh\n# drop ssh options and the target, run the command\nwhile [ \"$1\" != \"\" ]; do case \"$1\" in -o) shift 2;; *@*|host) shift; break;; *) shift;; esac; done\nexec /bin/sh -c \"$1\"\n");
        fake.toFile().setExecutable(true);
        Path trace = dir.resolve("d.trace");
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS"));
        String old = java.time.LocalDateTime.now().minusHours(3).format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS"));
        Files.writeString(trace, String.join("\n",
            "[" + old + "]:D ST:old line",
            "<nds/>",
            "[" + now + "]:D ST:Applying rule 'x'.",
            "[" + now + "]:D PT:Receiving DOM document from application.",
            "[" + now + "]:D ST:Policy returned:") + "\n", StandardCharsets.UTF_8);
        TraceTail t = new TraceTail("me", "host", fake.toString());
        assertEquals(2, t.tail(trace.toString(), 2, null).size());
        List<String> st = t.tail(trace.toString(), 10, " ST:");
        assertEquals(3, st.size());
        List<String> recent = t.since(trace.toString(), 60, null);
        assertEquals(3, recent.size());
        assertTrue(recent.get(0).contains("Applying rule"));
        assertEquals(1, t.since(trace.toString(), 60, "PT:").size());
        assertTrue(t.size(trace.toString()) > 0);
        assertEquals(-1, t.size(dir.resolve("nope").toString()));
    }

    @Test
    public void liveTailOfQuerytest() throws IOException {
        Assume.assumeTrue(System.getProperty("vault.ssh") != null);
        String[] ssh = System.getProperty("vault.ssh").split("@");
        TraceTail t = new TraceTail(ssh[0], ssh[1]);
        List<String> lines = t.tail("/opt/novell/querytest.txt", 5, null);
        assertEquals(5, lines.size());
    }
}
