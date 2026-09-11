package com.pointblue.dirxml.dev.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader/writer (the build is offline; no
 * gson/jackson). Objects are {@code LinkedHashMap<String,Object>} — key order is
 * always the order first seen on parse, or insertion order if built by hand —
 * arrays are {@code List<Object>}, scalars are {@code String}/{@link Num}/
 * {@code Boolean}/{@code null}. Numbers keep their original text ({@link Num}
 * wraps it) so a re-serialized document doesn't silently rewrite {@code 1.0} as
 * {@code 1} or drop trailing zeros.
 *
 * <p>{@link #pretty} (2-space indent, used for the as-code tree — see
 * {@code docs/forms.md} §3) and {@link #compact} (no whitespace, matching
 * {@code JSON.stringify}, the shape the vault/Designer store) both preserve key
 * order and serialize numbers/booleans/null/strings verbatim (no HTML escaping).
 */
public final class Json {

    private Json() {
    }

    /** A JSON number, keeping its original textual form. */
    public static final class Num extends Number {
        public final String text;
        private final BigDecimal value;

        Num(String text) {
            this.text = text;
            this.value = new BigDecimal(text);
        }

        public static Num of(String text) {
            return new Num(text);
        }

        @Override
        public int intValue() {
            return value.intValue();
        }

        @Override
        public long longValue() {
            return value.longValue();
        }

        @Override
        public float floatValue() {
            return value.floatValue();
        }

        @Override
        public double doubleValue() {
            return value.doubleValue();
        }

        @Override
        public String toString() {
            return text;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Num && value.compareTo(((Num) o).value) == 0;
        }

        @Override
        public int hashCode() {
            return value.stripTrailingZeros().hashCode();
        }
    }

    // ---- write ----

    /** 2-space indented, key order preserved — the as-code tree's on-disk form. */
    public static String pretty(Object root) {
        StringBuilder sb = new StringBuilder();
        write(root, sb, 0, true);
        return sb.toString();
    }

    /** No whitespace, key order preserved — the vendor/vault wire form. */
    public static String compact(Object root) {
        StringBuilder sb = new StringBuilder();
        write(root, sb, 0, false);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb, int indent, boolean pretty) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            if (pretty) {
                sb.append('\n');
            }
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (pretty) {
                    pad(sb, indent + 1);
                }
                sb.append(quote(e.getKey())).append(':');
                if (pretty) {
                    sb.append(' ');
                }
                write(e.getValue(), sb, indent + 1, pretty);
                if (++i < m.size()) {
                    sb.append(',');
                }
                if (pretty) {
                    sb.append('\n');
                }
            }
            if (pretty) {
                pad(sb, indent);
            }
            sb.append('}');
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            if (pretty) {
                sb.append('\n');
            }
            for (int i = 0; i < l.size(); i++) {
                if (pretty) {
                    pad(sb, indent + 1);
                }
                write(l.get(i), sb, indent + 1, pretty);
                if (i + 1 < l.size()) {
                    sb.append(',');
                }
                if (pretty) {
                    sb.append('\n');
                }
            }
            if (pretty) {
                pad(sb, indent);
            }
            sb.append(']');
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else {
            sb.append(quote(v.toString()));
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        sb.append("  ".repeat(indent));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ---- read ----

    public static Object parse(String s) {
        Parser p = new Parser(s);
        Object v = p.parseValue();
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object v = parseValue();
                m.put(key, v);
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') {
                    break;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or } at " + i);
                }
            }
            return m;
        }

        List<Object> parseArray() {
            List<Object> l = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(parseValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') {
                    break;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + i);
                }
                skipWs();
            }
            return l;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Num parseNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return new Num(s.substring(start, i));
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            return s.charAt(i);
        }

        void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + i);
            }
            i++;
        }
    }

    // ---- typed accessors ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    public static boolean asBool(Object o) {
        return Boolean.TRUE.equals(o);
    }

    public static int asInt(Object o, int dflt) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return dflt;
    }
}
