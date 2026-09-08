package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Secrets a tree can't carry, per environment, from a gitignored properties
 * file ({@code <env>.secrets} in the environments file):
 * <pre>
 *   AD Driver.shim-auth-password=…
 *   AD Driver.remote-loader-password=…
 *   AD Driver.named.exchange-service=…
 *   driverset.named.smtp-relay=…
 *   # per key, instead of a literal:
 *   AD Driver.shim-auth-passwordEnv=AD_SHIM_PW
 *   AD Driver.named.exchange-serviceCommand=op read "op://vault/item/field"
 * </pre>
 * Keys: {@code <driver>.shim-auth-password}, {@code <driver>.remote-loader-password},
 * {@code <driver>.named.<name>}, {@code driverset.named.<name>}. A value is a
 * literal, or resolved from an environment variable ({@code …Env}) or a
 * command's stdout ({@code …Command}, trailing newline stripped). Values are
 * returned as {@code char[]} and never printed by anything in this package.
 */
public final class Secrets {

    public static final String SHIM_AUTH = "shim-auth-password";
    public static final String REMOTE_LOADER = "remote-loader-password";
    public static final String DRIVERSET = "driverset";

    private final Properties props;

    private Secrets(Properties props) {
        this.props = props;
    }

    public static Secrets none() {
        return new Secrets(new Properties());
    }

    public static Secrets load(Path file) throws IOException {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            p = parse(Files.readString(file, StandardCharsets.UTF_8));
        }
        return new Secrets(p);
    }

    /**
     * {@code key=value} lines, {@code #} comments, no escaping: keys hold driver
     * names with spaces and values hold DNs and passwords with backslashes, which
     * {@code Properties.load} would mangle. The first {@code =} splits; both sides
     * are trimmed.
     */
    public static Properties parse(String text) {
        Properties p = new Properties();
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            p.setProperty(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return p;
    }

    /** True if a value (literal, Env or Command) is configured for the key. */
    public boolean has(String key) {
        return props.containsKey(key) || props.containsKey(key + "Env") || props.containsKey(key + "Command");
    }

    /** The keys with a value configured (for the plan: names only). */
    public List<String> keys() {
        TreeSet<String> out = new TreeSet<>();
        for (String k : props.stringPropertyNames()) {
            if (k.endsWith("Env")) {
                out.add(k.substring(0, k.length() - 3));
            } else if (k.endsWith("Command")) {
                out.add(k.substring(0, k.length() - 7));
            } else {
                out.add(k);
            }
        }
        return new ArrayList<>(out);
    }

    /** Resolve a secret; null when not configured. The caller zeroes the array when done. */
    public char[] get(String key) throws IOException {
        String literal = props.getProperty(key);
        if (literal != null) {
            return literal.toCharArray();
        }
        String env = props.getProperty(key + "Env");
        if (env != null) {
            String v = System.getenv(env.trim());
            if (v == null) {
                throw new IOException("secret '" + key + "': environment variable " + env.trim() + " is not set");
            }
            return v.toCharArray();
        }
        String cmd = props.getProperty(key + "Command");
        if (cmd != null) {
            return run(key, cmd.trim());
        }
        return null;
    }

    private static char[] run(String key, String cmd) throws IOException {
        Process p = new ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(false).start();
        try (InputStream in = p.getInputStream()) {
            String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("secret '" + key + "': interrupted");
            }
            if (code != 0) {
                throw new IOException("secret '" + key + "': command exited " + code);
            }
            while (out.endsWith("\n") || out.endsWith("\r")) {
                out = out.substring(0, out.length() - 1);
            }
            return out.toCharArray();
        }
    }

    // ---- key helpers ----

    public static String shimAuth(String driver) {
        return driver + "." + SHIM_AUTH;
    }

    public static String remoteLoader(String driver) {
        return driver + "." + REMOTE_LOADER;
    }

    public static String named(String driverOrDriverset, String name) {
        return driverOrDriverset + ".named." + name;
    }
}
