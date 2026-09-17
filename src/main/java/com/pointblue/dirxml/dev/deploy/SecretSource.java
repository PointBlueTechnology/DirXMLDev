package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One way to read a credential from a properties file, shared by the
 * environments file (the bind password, the applications password) and the
 * secrets file (driver secrets). For a key {@code k} the file may hold, in
 * this order of precedence:
 * <pre>
 *   k=…                 a literal (avoid for stg/prd)
 *   kEnv=VAR            the value of an environment variable
 *   kCommand=cmd        the stdout of a shell command (trailing newlines stripped), e.g. a password-manager CLI
 *   kKeychain=service[/account]   macOS Keychain: security find-generic-password -s service [-a account] -w
 * </pre>
 * {@code kCommand} runs through {@code /bin/sh -c} (macOS, Linux) or {@code cmd.exe /c}
 * (Windows) — on Windows point it at a PowerShell one-liner that reads the
 * Credential Manager, or use {@code kEnv}.
 * Values come back as {@code char[]} and are never printed by anything in this
 * package. {@link #warnIfShared(Path)} prints one warning per file when a
 * credentials file is readable by anyone but its owner.
 */
public final class SecretSource {

    /** The suffixes, in the order a key is tried after the literal. */
    public static final List<String> FORMS = List.of("Env", "Command", "Keychain");

    /** The keychain tool; a test points it at a script (system property {@code idm.keychain.command}). */
    static String keychainCommand() {
        return System.getProperty("idm.keychain.command", "security");
    }

    private SecretSource() {
    }

    /** True when a literal or any indirect form is configured for the key. */
    public static boolean has(Properties props, String key) {
        if (props.containsKey(key)) {
            return true;
        }
        for (String f : FORMS) {
            if (props.containsKey(key + f)) {
                return true;
            }
        }
        return false;
    }

    /** The key a property name configures ({@code AD Driver.named.xEnv} → {@code AD Driver.named.x}). */
    public static String baseKey(String propertyName) {
        for (String f : FORMS) {
            if (propertyName.endsWith(f) && propertyName.length() > f.length()) {
                return propertyName.substring(0, propertyName.length() - f.length());
            }
        }
        return propertyName;
    }

    /** Resolve the key; null when nothing is configured. {@code what} names it in errors (never the value). */
    public static char[] resolve(Properties props, String key, String what) throws IOException {
        String literal = props.getProperty(key);
        if (literal != null && !literal.isBlank()) {
            return literal.toCharArray();
        }
        String env = props.getProperty(key + "Env");
        if (env != null && !env.isBlank()) {
            String v = System.getenv(env.trim());
            if (v == null) {
                throw new IOException(what + ": " + key + "Env names " + env.trim() + ", which is not set");
            }
            return v.toCharArray();
        }
        String cmd = props.getProperty(key + "Command");
        if (cmd != null && !cmd.isBlank()) {
            return run(what + " (" + key + "Command)", shell(cmd.trim()));
        }
        String kc = props.getProperty(key + "Keychain");
        if (kc != null && !kc.isBlank()) {
            return keychain(what + " (" + key + "Keychain)", kc.trim());
        }
        return null;
    }

    /**
     * macOS Keychain: {@code service} or {@code service/account}. Add an item with
     * {@code security add-generic-password -s <service> -a <account> -w} (prompts for
     * the value, so it never lands in shell history); the first read from a
     * command-line tool asks once for permission in the Keychain dialog.
     */
    static char[] keychain(String what, String spec) throws IOException {
        String service = spec;
        String account = null;
        int slash = spec.indexOf('/');
        if (slash > 0) {
            service = spec.substring(0, slash).trim();
            account = spec.substring(slash + 1).trim();
        }
        List<String> cmd = new ArrayList<>(List.of(keychainCommand(), "find-generic-password", "-s", service));
        if (account != null && !account.isEmpty()) {
            cmd.add("-a");
            cmd.add(account);
        }
        cmd.add("-w");
        try {
            return run(what, cmd);
        } catch (IOException e) {
            throw new IOException(what + ": no keychain item for service '" + service + "'"
                + (account == null ? "" : " account '" + account + "'")
                + " — add one with: security add-generic-password -s '" + service + "' -a '"
                + (account == null ? System.getProperty("user.name") : account) + "' -w", e);
        }
    }

    /** The platform shell for a {@code …Command} value: {@code cmd.exe /c} on Windows, {@code /bin/sh -c} elsewhere. */
    static List<String> shell(String cmd) {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? List.of("cmd.exe", "/c", cmd) : List.of("/bin/sh", "-c", cmd);
    }

    private static char[] run(String what, List<String> command) throws IOException {
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new IOException(what + ": cannot run " + command.get(0) + ": " + e.getMessage(), e);
        }
        try (InputStream in = p.getInputStream()) {
            String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(what + ": interrupted");
            }
            if (code != 0) {
                throw new IOException(what + ": command exited " + code);
            }
            while (out.endsWith("\n") || out.endsWith("\r")) {
                out = out.substring(0, out.length() - 1);
            }
            if (out.isEmpty()) {
                throw new IOException(what + ": command printed nothing");
            }
            return out.toCharArray();
        }
    }

    // ---- file permissions ----

    private static final Set<Path> WARNED = ConcurrentHashMap.newKeySet();

    /** True when the file grants any permission to group or others (POSIX only; false elsewhere). */
    public static boolean isShared(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            for (PosixFilePermission p : perms) {
                if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE
                    && p != PosixFilePermission.OWNER_EXECUTE) {
                    return true;
                }
            }
            return false;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }

    /** Warn once per file (to stderr) when a credentials file is readable by group or others. */
    public static void warnIfShared(Path file) {
        if (file == null || !isShared(file)) {
            return;
        }
        Path abs = file.toAbsolutePath().normalize();
        if (WARNED.add(abs)) {
            System.err.println("WARNING: " + abs + " is readable by other users — run: chmod 600 '" + abs + "'");
        }
    }
}
