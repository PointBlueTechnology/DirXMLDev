package com.pointblue.dirxml.dev.dn;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A distinguished name as a list of RDNs, leaf first ({@code cn=Smith\, John,ou=Users,o=Acme}).
 *
 * <p>This is the one place DN text is taken apart or put together. Values are held unescaped, so a
 * comma, slash, {@code #}, {@code +} or {@code %} in a name is just a character; {@link #toString()}
 * writes RFC 4514 with every escape the value needs. Never split a DN string on {@code ","} or
 * {@code "/"}: parse it here.
 *
 * <p>Parsing follows RFC 4514 and accepts what people and eDirectory actually write: spaces around
 * separators, {@code \XX} hex escapes (UTF-8), {@code #hex} BER values, and unescaped {@code =} in a
 * value. Equality is eDirectory's: types and values case-ignored, runs of spaces collapsed.
 */
public final class Dn {

    private static final Dn ROOT = new Dn(List.of());

    private final List<Rdn> rdns;

    private Dn(List<Rdn> rdns) {
        this.rdns = List.copyOf(rdns);
    }

    public static Dn root() {
        return ROOT;
    }

    /** RDNs leaf first. */
    public static Dn of(List<Rdn> rdns) {
        return rdns.isEmpty() ? ROOT : new Dn(rdns);
    }

    /** RDNs root first, the order a tree path is read in ({@code o=Acme}, {@code ou=Users}, …). */
    public static Dn fromRootFirst(List<Rdn> rdns) {
        List<Rdn> l = new ArrayList<>(rdns);
        Collections.reverse(l);
        return of(l);
    }

    public List<Rdn> rdns() {
        return rdns;
    }

    public boolean isRoot() {
        return rdns.isEmpty();
    }

    public int size() {
        return rdns.size();
    }

    /** The leaf RDN; null for the root. */
    public Rdn leaf() {
        return rdns.isEmpty() ? null : rdns.get(0);
    }

    /** The containing DN; the root's parent is the root. */
    public Dn parent() {
        return rdns.size() <= 1 ? ROOT : new Dn(rdns.subList(1, rdns.size()));
    }

    public Dn child(Rdn rdn) {
        List<Rdn> l = new ArrayList<>(rdns.size() + 1);
        l.add(rdn);
        l.addAll(rdns);
        return new Dn(l);
    }

    /** True when this DN is {@code ancestor} or lies below it. */
    public boolean isWithin(Dn ancestor) {
        int skip = rdns.size() - ancestor.rdns.size();
        return skip >= 0 && rdns.subList(skip, rdns.size()).equals(ancestor.rdns);
    }

    /** RDNs root first. */
    public List<Rdn> rootFirst() {
        List<Rdn> l = new ArrayList<>(rdns);
        Collections.reverse(l);
        return l;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Dn d && rdns.equals(d.rdns);
    }

    @Override
    public int hashCode() {
        return rdns.hashCode();
    }

    /** RFC 4514 string form; the root is the empty string. */
    @Override
    public String toString() {
        return rdns.stream().map(Rdn::toString).collect(Collectors.joining(","));
    }

    // ---- parsing ----------------------------------------------------------------------------

    /** Parse an LDAP DN string. The empty (or blank) string is the root. */
    public static Dn parse(String s) {
        if (s == null) {
            throw new DnException("null DN");
        }
        return new Parser(s).dn();
    }

    /** Parse a single RDN ({@code cn=Smith\, John} or {@code cn=A+uid=B}). */
    public static Rdn parseRdn(String s) {
        Dn d = parse(s);
        if (d.size() != 1) {
            throw new DnException("expected one RDN, found " + d.size() + ": '" + s + "'");
        }
        return d.leaf();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Dn dn() {
            skipSpaces();
            if (i == s.length()) {
                return ROOT;
            }
            List<Rdn> rdns = new ArrayList<>();
            while (true) {
                rdns.add(rdn());
                if (i == s.length()) {
                    return new Dn(rdns);
                }
                char c = s.charAt(i);
                if (c != ',') {
                    throw error("expected ',' between RDNs");
                }
                i++;
                skipSpaces();
                if (i == s.length()) {
                    throw error("a DN cannot end with ','");
                }
            }
        }

        Rdn rdn() {
            List<Ava> avas = new ArrayList<>();
            while (true) {
                avas.add(ava());
                if (i < s.length() && s.charAt(i) == '+') {
                    i++;
                    skipSpaces();
                    continue;
                }
                return new Rdn(avas);
            }
        }

        Ava ava() {
            skipSpaces();
            int start = i;
            while (i < s.length() && s.charAt(i) != '=' && s.charAt(i) != ',' && s.charAt(i) != '+') {
                i++;
            }
            if (i == s.length() || s.charAt(i) != '=') {
                throw error("expected '=' after attribute type '" + s.substring(start, i).trim() + "'");
            }
            String type = s.substring(start, i).trim();
            if (type.regionMatches(true, 0, "oid.", 0, 4)) {
                type = type.substring(4);   // RFC 1779 form
            }
            if (!Ava.TYPE.matcher(type).matches()) {
                i = start;
                throw error("invalid attribute type '" + type + "'");
            }
            i++;   // '='
            skipSpaces();
            if (i < s.length() && s.charAt(i) == '#') {
                return new Ava(type, null, hexValue());
            }
            return new Ava(type, stringValue(), null);
        }

        byte[] hexValue() {
            int start = ++i;
            while (i < s.length() && HexFormat.isHexDigit(s.charAt(i))) {
                i++;
            }
            String hex = s.substring(start, i);
            skipSpaces();
            if (hex.isEmpty() || hex.length() % 2 != 0) {
                throw error("a #hex value needs an even number of hex digits");
            }
            if (i < s.length() && s.charAt(i) != ',' && s.charAt(i) != '+') {
                throw error("unexpected character after #hex value");
            }
            return HexFormat.of().parseHex(hex);
        }

        String stringValue() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int keep = 0;   // length of bytes up to the last escaped or non-space character
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '+') {
                    break;
                }
                if (c == '\\') {
                    if (i + 1 == s.length()) {
                        throw error("a value cannot end with a lone '\\'");
                    }
                    char n = s.charAt(i + 1);
                    if (i + 2 < s.length() && HexFormat.isHexDigit(n) && HexFormat.isHexDigit(s.charAt(i + 2))) {
                        bytes.write(HexFormat.fromHexDigits(s, i + 1, i + 3));
                        i += 3;
                    } else if (" \"#+,;<=>\\".indexOf(n) >= 0) {
                        bytes.write(n);
                        i += 2;
                    } else {
                        throw error("invalid escape '\\" + n + "'");
                    }
                    keep = bytes.size();
                    continue;
                }
                int cp = s.codePointAt(i);
                byte[] enc = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                bytes.write(enc, 0, enc.length);
                if (c != ' ') {
                    keep = bytes.size();
                }
                i += Character.charCount(cp);
            }
            byte[] all = bytes.toByteArray();
            try {
                // unescaped trailing spaces are padding before a separator; escaped ones were kept above
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(all, 0, keep)).toString();
            } catch (CharacterCodingException e) {
                throw error("hex escapes are not valid UTF-8");
            }
        }

        void skipSpaces() {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
        }

        DnException error(String what) {
            return new DnException(what + " at position " + i + " in '" + s + "'");
        }
    }
}
