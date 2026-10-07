package com.pointblue.dirxml.dev.deploy;

import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps static-RSA key exchange ({@code TLS_RSA_*}) available, on by default.
 *
 * <p>eDirectory's LDAPS listener often offers only those suites (for example
 * {@code AES256-GCM-SHA384}, no ECDHE, no TLS 1.3), and recent JDK builds — 24 and later, and the
 * 21 updates from mid-2026 — list {@code TLS_RSA_*} in {@code jdk.tls.disabledAlgorithms}, so a
 * bind to such a vault fails with "simple bind failed" although the credentials are right. The
 * JDK reads that property once, when its TLS stack first initialises, so this runs at start-up
 * and before every vault connection; a call after TLS initialised is too late and says so.
 *
 * <p>Opt out with {@code -Didm.tls.legacy=false} or {@code IDM_TLS_LEGACY=false} when every vault
 * supports forward-secrecy suites. Everything else in the JDK's list stays disabled.
 */
public final class LegacyTls {
    private static final String PROPERTY = "jdk.tls.disabledAlgorithms";
    private static final String RSA_KEY_EXCHANGE = "TLS_RSA_*";
    private static boolean done;

    private LegacyTls() {
    }

    /** Whether the suites are to be kept (the default) — the property, then the environment variable. */
    public static boolean wanted() {
        String p = System.getProperty("idm.tls.legacy", System.getenv("IDM_TLS_LEGACY"));
        return p == null || !p.trim().equalsIgnoreCase("false");
    }

    /** Removes {@code TLS_RSA_*} from the disabled list once; a no-op when opted out or already done. */
    public static synchronized void enable() {
        if (done || !wanted()) {
            return;
        }
        String disabled = Security.getProperty(PROPERTY);
        if (disabled != null && disabled.contains(RSA_KEY_EXCHANGE)) {
            Security.setProperty(PROPERTY, Arrays.stream(disabled.split(","))
                .map(String::trim)
                .filter(a -> !a.isEmpty() && !a.equals(RSA_KEY_EXCHANGE))
                .collect(Collectors.joining(", ")));
        }
        done = true;
    }

    /** True when the JDK's current list still disables the suites (for doctor). */
    public static boolean stillDisabled() {
        String disabled = Security.getProperty(PROPERTY);
        return disabled != null && disabled.contains(RSA_KEY_EXCHANGE);
    }
}
