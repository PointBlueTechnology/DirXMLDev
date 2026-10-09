package com.pointblue.dirxml.dev.dn;

import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One attribute type and value of an RDN ({@code cn=Smith, John}). The value is held unescaped,
 * exactly as stored in the directory; {@link #toString()} escapes it per RFC 4514. A value that was
 * written in the {@code #hex} BER form keeps that encoding in {@link #ber()} and is written back the
 * same way; its {@link #value()} is then null.
 */
public record Ava(String type, String value, byte[] ber) {

    /** descr (RFC 4512 keystring, plus '_' which eDirectory allows) or a numeric OID. */
    static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*|[0-9]+(\\.[0-9]+)+");

    public Ava {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new DnException("invalid attribute type: " + (type == null ? "null" : "'" + type + "'"));
        }
        if ((value == null) == (ber == null)) {
            throw new DnException("an AVA has a string value or a BER value, not both or neither");
        }
        ber = ber == null ? null : ber.clone();
    }

    public static Ava of(String type, String value) {
        return new Ava(type, value, null);
    }

    @Override
    public byte[] ber() {
        return ber == null ? null : ber.clone();
    }

    /** The comparison key eDirectory's naming attributes use: type and value case-ignored, spaces collapsed. */
    String key() {
        String v = value != null ? normalizeValue(value) : "#" + HexFormat.of().formatHex(ber);
        return type.toLowerCase(Locale.ROOT) + "=" + v;
    }

    static String normalizeValue(String v) {
        return v.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ava a && key().equals(a.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    /** RFC 4514 string form. */
    @Override
    public String toString() {
        return type + "=" + (ber != null ? "#" + HexFormat.of().formatHex(ber) : escape(value));
    }

    /** Escape a value for an RFC 4514 string (section 2.4). Non-ASCII characters are kept as they are. */
    public static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean first = i == 0, last = i == value.length() - 1;
            if (c == '"' || c == '+' || c == ',' || c == ';' || c == '<' || c == '>' || c == '\\'
                    || (first && (c == '#' || c == ' ')) || (last && c == ' ')) {
                b.append('\\').append(c);
            } else if (c == 0) {
                b.append("\\00");
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
