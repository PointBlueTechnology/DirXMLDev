package com.pointblue.dirxml.dev.dn;

import java.util.List;
import java.util.stream.Collectors;

/**
 * LDAP search filters built from values, never from concatenated text. Every value is escaped per
 * RFC 4515 ({@code * ( ) \} and NUL become {@code \2a \28 \29 \5c \00}); attribute names are checked,
 * so neither a name nor a value can change the filter's structure.
 *
 * <pre>
 *   Filters.and(Filters.eq("objectClass", "inetOrgPerson"), Filters.prefix("cn", "Smith (Admin"))
 *   → (&amp;(objectClass=inetOrgPerson)(cn=Smith \28Admin*))
 * </pre>
 */
public final class Filters {

    private Filters() {
    }

    /** RFC 4515 escaping of a value; non-ASCII text stays as UTF-8 characters. */
    public static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '*' -> b.append("\\2a");
                case '(' -> b.append("\\28");
                case ')' -> b.append("\\29");
                case '\\' -> b.append("\\5c");
                case 0 -> b.append("\\00");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Every byte as {@code \xx}: for binary values such as a GUID. */
    public static String escape(byte[] value) {
        StringBuilder b = new StringBuilder(value.length * 3);
        for (byte x : value) {
            b.append('\\').append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return b.toString();
    }

    public static String eq(String attr, String value) {
        return "(" + attr(attr) + "=" + escape(value) + ")";
    }

    public static String eq(String attr, byte[] value) {
        return "(" + attr(attr) + "=" + escape(value) + ")";
    }

    public static String present(String attr) {
        return "(" + attr(attr) + "=*)";
    }

    public static String prefix(String attr, String value) {
        return "(" + attr(attr) + "=" + escape(value) + "*)";
    }

    public static String suffix(String attr, String value) {
        return "(" + attr(attr) + "=*" + escape(value) + ")";
    }

    public static String contains(String attr, String value) {
        return "(" + attr(attr) + "=*" + escape(value) + "*)";
    }

    public static String ge(String attr, String value) {
        return "(" + attr(attr) + ">=" + escape(value) + ")";
    }

    public static String le(String attr, String value) {
        return "(" + attr(attr) + "<=" + escape(value) + ")";
    }

    public static String approx(String attr, String value) {
        return "(" + attr(attr) + "~=" + escape(value) + ")";
    }

    /** Filters made here (or by this class's other methods); a single filter is returned as is. */
    public static String and(String... filters) {
        return join('&', List.of(filters));
    }

    public static String and(List<String> filters) {
        return join('&', filters);
    }

    public static String or(String... filters) {
        return join('|', List.of(filters));
    }

    public static String or(List<String> filters) {
        return join('|', filters);
    }

    public static String not(String filter) {
        return "(!" + filter + ")";
    }

    private static String join(char op, List<String> filters) {
        if (filters.isEmpty()) {
            throw new DnException("an " + (op == '&' ? "AND" : "OR") + " needs at least one filter");
        }
        return filters.size() == 1 ? filters.get(0) : "(" + op + filters.stream().collect(Collectors.joining()) + ")";
    }

    /** An attribute description: a name or OID, optionally with ;options. Refuses anything else. */
    static String attr(String attr) {
        if (attr == null || !attr.matches("([A-Za-z][A-Za-z0-9_-]*|[0-9]+(\\.[0-9]+)+)(;[A-Za-z0-9-]+)*")) {
            throw new DnException("invalid attribute name in a filter: " + (attr == null ? "null" : "'" + attr + "'"));
        }
        return attr;
    }
}
