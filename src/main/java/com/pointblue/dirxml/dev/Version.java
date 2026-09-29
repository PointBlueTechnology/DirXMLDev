package com.pointblue.dirxml.dev;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The running DirXMLDev version, from {@code idm-version.properties} (the pom's version,
 * substituted at build time). Null when the resource is absent — the portable CI build compiles
 * with javac and carries no resources — so every caller treats "unknown" as a normal answer.
 */
public final class Version {

    public static final String RESOURCE = "/idm-version.properties";

    private Version() {
    }

    /** The version, e.g. {@code 0.6.0}, or null when unknown. */
    public static String current() {
        try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version");
            return v == null || v.isBlank() || v.startsWith("${") ? null : v.trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Semantic comparison of dotted versions ({@code 0.10.0} is newer than {@code 0.9.1}); a
     * leading {@code v} and anything after a {@code -} are ignored. Negative when {@code a} is
     * older than {@code b}, zero when equal.
     */
    public static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        String s = v == null ? "" : v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int dash = s.indexOf('-');
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        String[] bits = s.isEmpty() ? new String[0] : s.split("\\.");
        int[] out = new int[bits.length];
        for (int i = 0; i < bits.length; i++) {
            try {
                out[i] = Integer.parseInt(bits[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
