package com.pointblue.dirxml.dev.dn;

import java.util.HexFormat;

/**
 * An eDirectory object GUID: the 16 bytes of the {@code GUID} attribute, which never change on rename
 * or move. Its text form is 32 lowercase hex digits, safe in any URL path segment.
 */
public record Guid(byte[] bytes) {

    public Guid {
        if (bytes == null || bytes.length != 16) {
            throw new DnException("a GUID is 16 bytes, got " + (bytes == null ? "null" : bytes.length));
        }
        bytes = bytes.clone();
    }

    /** Parse 32 hex digits (case-insensitive). */
    public static Guid parse(String hex) {
        if (hex == null || !hex.matches("[0-9A-Fa-f]{32}")) {
            throw new DnException("a GUID is 32 hex digits: " + (hex == null ? "null" : "'" + hex + "'"));
        }
        return new Guid(HexFormat.of().parseHex(hex));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** {@code (GUID=\xx\xx…)}: the filter that finds this object from the tree root. */
    public String filter() {
        return Filters.eq("GUID", bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Guid g && java.util.Arrays.equals(bytes, g.bytes);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(bytes);
    }
}
