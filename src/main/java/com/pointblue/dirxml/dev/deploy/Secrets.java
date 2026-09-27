package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
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
 *   AD Driver.shim-auth-id=…                 # when the id is a client id / API key, not a user name
 *   AD Driver.named.exchange-service=…
 *   driverset.named.smtp-relay=…
 *   # per key, instead of a literal:
 *   AD Driver.shim-auth-passwordEnv=AD_SHIM_PW
 *   AD Driver.named.exchange-serviceCommand=op read "op://vault/item/field"
 *   AD Driver.remote-loader-passwordKeychain=idm-stg/ad-remote-loader     # macOS Keychain (service/account)
 * </pre>
 * Keys: {@code <driver>.shim-auth-password}, {@code <driver>.remote-loader-password},
 * {@code <driver>.shim-auth-id}, {@code <driver>.named.<name>}, {@code driverset.named.<name>}. A value is a
 * literal, or resolved from an environment variable ({@code …Env}), a
 * command's stdout ({@code …Command}, trailing newline stripped) or the macOS
 * Keychain ({@code …Keychain}) — see {@link SecretSource}. Values are
 * returned as {@code char[]} and never printed by anything in this package.
 */
public final class Secrets {

    public static final String SHIM_AUTH = "shim-auth-password";
    public static final String REMOTE_LOADER = "remote-loader-password";
    /**
     * {@code <driver>.shim-auth-id}: a shim authentication id that is a credential (an OAuth client
     * id, an API key) rather than a user name, kept out of the tree. It wins over the tree's and the
     * overrides' value; a diff and a plan never show it; an import never writes it into the tree.
     */
    public static final String SHIM_AUTH_ID = "shim-auth-id";
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
            SecretSource.warnIfShared(file);
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

    /** True if a value (literal, Env, Command or Keychain) is configured for the key. */
    public boolean has(String key) {
        return SecretSource.has(props, key);
    }

    /** The keys with a value configured (for the plan: names only). */
    public List<String> keys() {
        TreeSet<String> out = new TreeSet<>();
        for (String k : props.stringPropertyNames()) {
            out.add(SecretSource.baseKey(k));
        }
        return new ArrayList<>(out);
    }

    /** Resolve a secret; null when not configured. The caller zeroes the array when done. */
    public char[] get(String key) throws IOException {
        return SecretSource.resolve(props, key, "secret '" + key + "'");
    }

    // ---- key helpers ----

    public static String shimAuth(String driver) {
        return driver + "." + SHIM_AUTH;
    }

    public static String remoteLoader(String driver) {
        return driver + "." + REMOTE_LOADER;
    }

    public static String shimAuthId(String driver) {
        return driver + "." + SHIM_AUTH_ID;
    }

    // ---- shim auth ids the secrets file supplies ----

    /**
     * Put every {@code <driver>.shim-auth-id} this file holds into the model (the driver's
     * {@code shimAuthId}, flagged secret), for a diff or a deploy. Returns the drivers touched.
     */
    public List<String> applyShimAuthIds(com.pointblue.dirxml.dev.model.DriverSet ds) throws IOException {
        List<String> out = new ArrayList<>();
        for (com.pointblue.dirxml.dev.model.Driver d : ds.drivers) {
            String key = shimAuthId(d.name);
            if (has(key)) {
                char[] v = get(key);
                if (v != null) {
                    d.shimAuthId = new String(v);
                    d.shimAuthIdSecret = true;
                    java.util.Arrays.fill(v, '\0');
                    out.add(d.name);
                }
            }
        }
        return out;
    }

    /** {@code import-live}: drop the shim auth id of every driver this file supplies one for, so the tree never carries it. Returns the drivers touched. */
    public List<String> stripShimAuthIds(com.pointblue.dirxml.dev.model.DriverSet ds) {
        List<String> out = new ArrayList<>();
        for (com.pointblue.dirxml.dev.model.Driver d : ds.drivers) {
            if (has(shimAuthId(d.name))) {
                d.shimAuthId = null;
                d.shimAuthIdSecret = false;
                out.add(d.name);
            }
        }
        return out;
    }

    public static String named(String driverOrDriverset, String name) {
        return driverOrDriverset + ".named." + name;
    }
}
