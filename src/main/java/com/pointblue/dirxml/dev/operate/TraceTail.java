package com.pointblue.dirxml.dev.operate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Read a driver's trace file on the engine host over key-based SSH
 * (docs/operate.md, {@code driver.trace tail}). The file is streamed, never
 * copied or stored; only stdout is read — ssh writes its banners and warnings
 * to stderr (spike 5). {@code ssh} is invoked as {@code ssh <user@host> <cmd>}
 * with the file path single-quoted; the host and user come from the
 * environment ({@code sshHost}, {@code sshUser}).
 */
public final class TraceTail {

    private final String target;   // user@host
    private final String sshBinary;

    public TraceTail(String user, String host) {
        this(user, host, "ssh");
    }

    TraceTail(String user, String host, String sshBinary) {
        this.target = (user == null || user.isBlank() ? "" : user + "@") + host;
        this.sshBinary = sshBinary;
    }

    /** The last {@code lines} lines of the file, optionally only those matching {@code grep} (a regex). */
    public List<String> tail(String file, int lines, String grep) throws IOException {
        // grep on the host so a filtered tail still yields `lines` matching lines
        String cmd = grep == null || grep.isBlank()
            ? "tail -n " + lines + " " + q(file)
            : "grep -E -- " + q(grep) + " " + q(file) + " | tail -n " + lines;
        List<String> out = new ArrayList<>();
        run(cmd, out::add);
        return out;
    }

    /** Follow the file ({@code tail -F}) until the process is destroyed; each line goes to {@code sink}. */
    public Process follow(String file, int lines, String grep, Consumer<String> sink) throws IOException {
        String cmd = "tail -n " + lines + " -F " + q(file)
            + (grep == null || grep.isBlank() ? "" : " | grep --line-buffered -E -- " + q(grep));
        Process proc = start(cmd);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException ignored) {
                // the process was destroyed
            }
        }, "trace-tail");
        reader.setDaemon(true);
        reader.start();
        return proc;
    }

    /**
     * Lines whose DirXML timestamp is within the last {@code minutes}; untimestamped lines follow
     * the last timestamped one. A trace line starts with {@code [MM/dd/yy HH:mm:ss.SSS]} in the
     * engine's local time with no zone, so the cutoff is taken from the engine's own clock
     * ({@code date} over the same ssh), never from this machine's zone. The whole file is
     * streamed through the filter (never stored), so a very large trace takes a while.
     */
    public List<String> since(String file, int minutes, String grep) throws IOException {
        java.time.LocalDateTime cutoff = engineNow().minusMinutes(minutes);
        List<String> out = new ArrayList<>();
        Pattern p = grep == null || grep.isBlank() ? null : Pattern.compile(grep);
        boolean[] in = {false};
        run("cat " + q(file), line -> {
            java.time.LocalDateTime ts = timestamp(line);
            if (ts != null) {
                in[0] = !ts.isBefore(cutoff);
            }
            if (in[0] && (p == null || p.matcher(line).find())) {
                out.add(line);
            }
        });
        return out;
    }

    /** The file's size in bytes, or -1 when absent. */
    public long size(String file) throws IOException {
        List<String> out = new ArrayList<>();
        run("stat -c %s " + q(file) + " 2>/dev/null || stat -f %z " + q(file) + " 2>/dev/null || echo -1", out::add);
        for (String s : out) {
            if (s.strip().matches("-?\\d+")) {
                return Long.parseLong(s.strip());
            }
        }
        return -1;
    }

    private static final java.time.format.DateTimeFormatter STAMP = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS");

    /** The engine host's current local time, in the trace stamp's form; this machine's clock when the host does not answer. */
    java.time.LocalDateTime engineNow() {
        List<String> out = new ArrayList<>();
        try {
            run("date '+%m/%d/%y %H:%M:%S.000'", out::add);
            if (!out.isEmpty()) {
                return java.time.LocalDateTime.parse(out.get(0).trim(), STAMP);
            }
        } catch (IOException | RuntimeException e) {
            // fall through
        }
        return java.time.LocalDateTime.now();
    }

    /** The DirXML trace timestamp of a line (the engine's local time, no zone), or null. */
    static java.time.LocalDateTime timestamp(String line) {
        // [09/09/26 08:59:28.431]:QT ST:…
        if (line.length() < 24 || line.charAt(0) != '[' || line.charAt(22) != ']') {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(line.substring(1, 22), STAMP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void run(String cmd, Consumer<String> sink) throws IOException {
        Process proc = start(cmd);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sink.accept(line);
            }
        }
        try {
            if (proc.waitFor() == 255) {
                throw new IOException("ssh to " + target + " failed (exit 255): check the environment's sshHost/sshUser and the key");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Process start(String cmd) throws IOException {
        return new ProcessBuilder(sshBinary, "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", target, cmd)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
