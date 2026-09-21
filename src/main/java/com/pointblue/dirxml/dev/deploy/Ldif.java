package com.pointblue.dirxml.dev.deploy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RFC 2849 LDIF, the subset the tool writes and reads: content records ({@code dn:} plus
 * attribute lines, {@code ::} base64 for values that are not safe strings, folded at 76
 * columns) and {@code changetype: modify} records with {@code add:} / {@code replace:}
 * operations. Shared by {@link Snapshot} and the clone bundle; ICE and ldapmodify read
 * what this writes.
 */
public final class Ldif {

    private Ldif() {
    }

    /** One attribute line, base64 when the value is not an RFC 2849 safe string, folded. */
    public static String line(String name, byte[] value) {
        String raw = isSafe(value)
            ? name + ": " + new String(value, StandardCharsets.UTF_8)
            : name + ":: " + Base64.getEncoder().encodeToString(value);
        return fold(raw);
    }

    /** A content record for {@code e} (dn first, then every attribute in map order), no trailing blank line. */
    public static String entry(Vault.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(line("dn", e.dn.getBytes(StandardCharsets.UTF_8))).append('\n');
        for (Map.Entry<String, List<byte[]>> a : e.attrs.entrySet()) {
            for (byte[] v : a.getValue()) {
                sb.append(line(a.getKey(), v)).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** A {@code changetype: modify} record adding (or replacing) the given attributes' values. */
    public static String modify(String dn, String op, Map<String, List<byte[]>> attrs) {
        StringBuilder sb = new StringBuilder();
        sb.append(line("dn", dn.getBytes(StandardCharsets.UTF_8))).append('\n');
        sb.append("changetype: modify\n");
        for (Map.Entry<String, List<byte[]>> a : attrs.entrySet()) {
            sb.append(op).append(": ").append(a.getKey()).append('\n');
            for (byte[] v : a.getValue()) {
                sb.append(line(a.getKey(), v)).append('\n');
            }
            sb.append("-\n");
        }
        return sb.toString().stripTrailing();
    }

    /** RFC 2849 "safe string": printable ASCII, not starting with space, colon or less-than. */
    public static boolean isSafe(byte[] v) {
        if (v.length == 0) {
            return true;
        }
        if (v[0] == ' ' || v[0] == ':' || v[0] == '<') {
            return false;
        }
        for (byte b : v) {
            int c = b & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    public static String fold(String line) {
        if (line.length() <= 76) {
            return line;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(line, 0, 76);
        int i = 76;
        while (i < line.length()) {
            int end = Math.min(i + 75, line.length());
            sb.append('\n').append(' ').append(line, i, end);
            i = end;
        }
        return sb.toString();
    }

    /** Merge continuation lines (a single leading space) back into the logical line they fold. */
    public static List<String> unfold(String text) {
        String[] raw = text.split("\n", -1);
        List<String> logical = new ArrayList<>();
        for (String line : raw) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.startsWith(" ") && !logical.isEmpty() && !logical.get(logical.size() - 1).isEmpty()) {
                int last = logical.size() - 1;
                logical.set(last, logical.get(last) + line.substring(1));
            } else {
                logical.add(line);
            }
        }
        return logical;
    }

    /** Records: runs of non-blank logical lines (comment lines kept — {@link Snapshot} marks absent entries with one). */
    public static List<List<String>> blocks(List<String> logicalLines) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String line : logicalLines) {
            if (line.isEmpty()) {
                if (!cur.isEmpty()) {
                    out.add(cur);
                    cur = new ArrayList<>();
                }
            } else {
                cur.add(line);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    /** One parsed attribute line: the name and the decoded value. */
    public static final class AttrLine {
        public final String name;
        public final byte[] value;

        AttrLine(String name, byte[] value) {
            this.name = name;
            this.value = value;
        }
    }

    public static AttrLine parseAttrLine(String line) {
        int c = line.indexOf(':');
        if (c < 0) {
            throw new IllegalArgumentException("bad LDIF line: " + line);
        }
        String name = line.substring(0, c);
        if (c + 1 < line.length() && line.charAt(c + 1) == ':') {
            String b64 = line.substring(c + 2).trim();
            return new AttrLine(name, b64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(b64));
        }
        String rest = c + 1 < line.length() ? line.substring(c + 1) : "";
        if (rest.startsWith(" ")) {
            rest = rest.substring(1);
        }
        return new AttrLine(name, rest.getBytes(StandardCharsets.UTF_8));
    }

    /** One record, content or change. */
    public static final class Record {
        public final String dn;
        /** null for a content record; otherwise {@code modify} (the only change type read). */
        public final String changeType;
        /** Content record: the attributes. Modify record: per operation, in order. */
        public final Vault.Entry entry;
        public final List<Op> ops = new ArrayList<>();

        Record(String dn, String changeType, Vault.Entry entry) {
            this.dn = dn;
            this.changeType = changeType;
            this.entry = entry;
        }
    }

    /** One modify operation: {@code add} / {@code replace} / {@code delete} of an attribute's values. */
    public static final class Op {
        public final String op;
        public final String attr;
        public final List<byte[]> values = new ArrayList<>();

        Op(String op, String attr) {
            this.op = op;
            this.attr = attr;
        }
    }

    /** Parses every record of an LDIF text; comment lines and a leading {@code version:} are ignored. */
    public static List<Record> parse(String text) {
        List<Record> out = new ArrayList<>();
        for (List<String> block : blocks(unfold(text))) {
            List<String> lines = new ArrayList<>();
            for (String l : block) {
                if (!l.startsWith("#") && !(lines.isEmpty() && l.startsWith("version:"))) {
                    lines.add(l);
                }
            }
            if (!lines.isEmpty()) {
                out.add(parseRecord(lines));
            }
        }
        return out;
    }

    public static Record parseRecord(List<String> block) {
        AttrLine dnLine = parseAttrLine(block.get(0));
        if (!dnLine.name.equalsIgnoreCase("dn")) {
            throw new IllegalArgumentException("expected 'dn:' line, got: " + block.get(0));
        }
        String dn = new String(dnLine.value, StandardCharsets.UTF_8);
        int i = 1;
        String changeType = null;
        if (i < block.size() && block.get(i).toLowerCase().startsWith("changetype:")) {
            changeType = block.get(i).substring("changetype:".length()).trim();
            i++;
        }
        if (changeType == null) {
            Vault.Entry entry = new Vault.Entry(dn);
            for (; i < block.size(); i++) {
                AttrLine a = parseAttrLine(block.get(i));
                entry.attrs.computeIfAbsent(a.name, k -> new ArrayList<>()).add(a.value);
            }
            return new Record(dn, null, entry);
        }
        if (!changeType.equalsIgnoreCase("modify")) {
            throw new IllegalArgumentException("unsupported changetype '" + changeType + "' for " + dn);
        }
        Record r = new Record(dn, "modify", null);
        Op cur = null;
        for (; i < block.size(); i++) {
            String l = block.get(i);
            if (l.equals("-")) {
                cur = null;
                continue;
            }
            AttrLine a = parseAttrLine(l);
            String lname = a.name.toLowerCase();
            if (cur == null && (lname.equals("add") || lname.equals("replace") || lname.equals("delete"))) {
                cur = new Op(lname, new String(a.value, StandardCharsets.UTF_8).trim());
                r.ops.add(cur);
            } else if (cur != null && a.name.equalsIgnoreCase(cur.attr)) {
                cur.values.add(a.value);
            } else {
                throw new IllegalArgumentException("bad modify line for " + dn + ": " + l);
            }
        }
        return r;
    }

    /** The attribute map of a modify record's {@code add} operations, in order. */
    public static Map<String, List<byte[]>> adds(Record r) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        for (Op o : r.ops) {
            if (o.op.equals("add") || o.op.equals("replace")) {
                m.computeIfAbsent(o.attr, k -> new ArrayList<>()).addAll(o.values);
            }
        }
        return m;
    }
}
