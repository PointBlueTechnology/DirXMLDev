package com.pointblue.dirxml.dev.packages;

import java.util.Arrays;

/**
 * A package version: {@code major.minor.revision[.build]}, compared as four
 * numeric parts with a missing build treated as 0 (designer-package-layer.md
 * §3.1, {@code PackageVersion}).
 */
public final class PackageVersion implements Comparable<PackageVersion> {

    public final String raw;
    private final int[] parts;

    private PackageVersion(String raw, int[] parts) {
        this.raw = raw;
        this.parts = parts;
    }

    public static PackageVersion parse(String s) {
        int[] p = new int[4];
        if (s != null && !s.isBlank()) {
            String[] bits = s.trim().split("\\.");
            for (int i = 0; i < 4 && i < bits.length; i++) {
                try {
                    p[i] = Integer.parseInt(bits[i].trim());
                } catch (NumberFormatException e) {
                    p[i] = 0;
                }
            }
        }
        return new PackageVersion(s, p);
    }

    @Override
    public int compareTo(PackageVersion o) {
        for (int i = 0; i < 4; i++) {
            int c = Integer.compare(parts[i], o.parts[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PackageVersion && compareTo((PackageVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(parts);
    }

    @Override
    public String toString() {
        return raw;
    }
}
