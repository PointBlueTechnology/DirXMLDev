package com.pointblue.dirxml.dev.packages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader/writer for {@code catalog.json} — no external
 * dependency needed for a small, hand-shaped document. Objects are
 * {@code LinkedHashMap<String,Object>} (order preserved on write, so the
 * caller controls the stable key order the catalog requires), arrays are
 * {@code List<Object>}, scalars are {@code String}/{@code Long}/{@code
 * Boolean}/{@code null}.
 */
final class Json {

    private Json() {
    }

    // ---- write ----

    static String write(Object root) {
        StringBuilder sb = new StringBuilder();
        write(root, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                pad(sb, indent + 1);
                sb.append(quote(e.getKey())).append(": ");
                write(e.getValue(), sb, indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append("}");
        } else if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            if (l.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(sb, indent + 1);
                write(l.get(i), sb, indent + 1);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append("]");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
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

    static Object parse(String s) {
        return new Parser(s).parseValue();
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

        Object parseNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String num = s.substring(start, i);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
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
    static Map<String, Object> asMap(Object o) {
        return o == null ? new LinkedHashMap<>() : (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o == null ? new ArrayList<>() : (List<Object>) o;
    }

    static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    static boolean asBool(Object o) {
        return Boolean.TRUE.equals(o);
    }

    static int asInt(Object o, int dflt) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return dflt;
    }
}
