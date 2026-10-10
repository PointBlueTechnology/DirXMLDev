package com.pointblue.dirxml.dev.dn;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NDS dot notation: typed ({@code cn=Smith\, John.ou=Users.o=Acme}) and typeless
 * ({@code Smith\, John.Users.Acme}). In a dot name a period, comma, plus, equals or backslash inside a
 * value is escaped with a backslash; everything else is literal.
 *
 * <p>A typeless name cannot be turned into a DN without the directory: which component is an
 * {@code ou} and which an {@code o} depends on the tree. {@link #parseTypeless} returns the values
 * for a server-side resolution search; {@link #parse} accepts only typed names.
 */
public final class DotNotation {

    private static final String SPECIAL = ".,+=\\";

    private DotNotation() {
    }

    /** {@code cn=Smith\, John.ou=Users.o=Acme} */
    public static String typed(Dn dn) {
        return dn.rdns().stream()
                .map(r -> r.avas().stream().map(a -> a.type() + "=" + escape(text(a))).collect(Collectors.joining("+")))
                .collect(Collectors.joining("."));
    }

    /** {@code Smith\, John.Users.Acme} */
    public static String typeless(Dn dn) {
        return dn.rdns().stream()
                .map(r -> r.avas().stream().map(a -> escape(text(a))).collect(Collectors.joining("+")))
                .collect(Collectors.joining("."));
    }

    /** Escape one value for a dot name. */
    public static String escape(String value) {
        StringBuilder b = new StringBuilder(value.length() + 4);
        for (char c : value.toCharArray()) {
            if (SPECIAL.indexOf(c) >= 0) {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    /** Parse a typed dot name ({@code cn=Smith\, John.ou=Users.o=Acme}); a leading period is ignored. */
    public static Dn parse(String s) {
        List<Rdn> rdns = new ArrayList<>();
        for (List<String> comps : split(s, '.')) {
            List<Ava> avas = new ArrayList<>();
            for (String ava : comps) {
                List<String> tv = splitFirst(ava, '=');
                if (tv.size() != 2) {
                    throw new DnException("typeless component '" + ava + "' in '" + s + "': resolve it with parseTypeless and a directory search");
                }
                avas.add(Ava.of(tv.get(0).trim(), unescape(tv.get(1))));
            }
            rdns.add(new Rdn(avas));
        }
        return Dn.of(rdns);
    }

    /** True when every component carries a type ({@code cn=…}). */
    public static boolean isTyped(String s) {
        try {
            parse(s);
            return true;
        } catch (DnException e) {
            return false;
        }
    }

    /** The unescaped values of a typeless dot name, leaf first; each inner list is one RDN's values. */
    public static List<List<String>> parseTypeless(String s) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> comps : split(s, '.')) {
            List<String> values = new ArrayList<>();
            for (String c : comps) {
                values.add(unescape(c));
            }
            out.add(values);
        }
        return out;
    }

    private static String text(Ava a) {
        if (a.value() == null) {
            throw new DnException("a #hex value has no dot-notation form: " + a);
        }
        return a.value();
    }

    /** Components split on unescaped {@code sep}, each split again on unescaped '+'. Escapes are kept. */
    private static List<List<String>> split(String s, char sep) {
        if (s == null || s.isBlank()) {
            throw new DnException("empty dot name");
        }
        String t = s.startsWith(".") ? s.substring(1) : s;
        List<List<String>> out = new ArrayList<>();
        for (String comp : splitUnescaped(t, sep)) {
            if (comp.isEmpty()) {
                throw new DnException("empty component in '" + s + "'");
            }
            out.add(splitUnescaped(comp, '+'));
        }
        return out;
    }

    private static List<String> splitUnescaped(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 == s.length()) {
                    throw new DnException("a dot name cannot end with a lone '\\': '" + s + "'");
                }
                cur.append(c).append(s.charAt(++i));
            } else if (c == sep) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static List<String> splitFirst(String s, char sep) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == sep) {
                return List.of(s.substring(0, i), s.substring(i + 1));
            }
        }
        return List.of(s);
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c == '\\' ? s.charAt(++i) : c);
        }
        return b.toString();
    }
}
