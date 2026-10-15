package com.pointblue.dirxml.dev.deploy;

import com.novell.ldap.LDAPExtendedOperation;
import com.novell.ldap.asn1.ASN1Integer;
import com.novell.ldap.asn1.ASN1Object;
import com.novell.ldap.asn1.ASN1OctetString;
import com.novell.ldap.asn1.ASN1Sequence;
import com.novell.ldap.asn1.ASN1SequenceOf;
import com.novell.ldap.asn1.ASN1Set;
import com.novell.ldap.asn1.ASN1SetOf;
import com.novell.ldap.asn1.LBERDecoder;
import com.novell.ldap.asn1.LBEREncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Identity Manager engine's LDAP extended operations, encoded with open-source JLDAP's BER classes
 * so no vendor library is needed (docs/vendor-free-vault.md). The trace viewer does the same for four
 * of them.
 * <p>
 * The wire format: a request's OID is {@code 2.16.840.1.113719.1.14.100.<n>} (its reply has n+1); its
 * value is absent, or BER elements written back to back — a string as a UTF-8 OCTET STRING, an int as
 * an INTEGER, bytes as an OCTET STRING, a string array as a SEQUENCE OF OCTET STRING, an int array as
 * a SET OF INTEGER, a password as the OCTET STRING of its UTF-8 bytes (the LDAPS session protects it),
 * and null as an empty OCTET STRING. A reply's value is a SEQUENCE read by position. Large results come
 * back as a data handle and size, read with {@link Op#GET_CHUNKED_RESULT} and closed with
 * {@link Op#CLOSE_CHUNKED_RESULT}.
 */
public final class IdmOps {

    public static final String OID_BASE = "2.16.840.1.113719.1.14.100.";

    /** The operations this project uses, by their request number. */
    public enum Op {
        GET_DRIVER_SET(1), SET_DRIVER_SET(3), CLEAR_DRIVER_SET(5),
        GET_DRIVER_START_OPTION(7), SET_DRIVER_START_OPTION(9),
        GET_VERSION(11),
        GET_DRIVER_STATE(13), START_DRIVER(15), STOP_DRIVER(17), RESTART_DRIVER(101),
        GET_DRIVER_STATS(19), DRIVER_GET_SCHEMA(21), DRIVER_RESYNC(23),
        MIGRATE_APP(25), QUEUE_EVENT(27), SUBMIT_COMMAND(29), SUBMIT_EVENT(31),
        GET_CHUNKED_RESULT(33), CLOSE_CHUNKED_RESULT(35),
        VIEW_CACHE_ENTRIES(41), DELETE_CACHE_ENTRIES(43),
        NOTIFY_JOB_UPDATE(53), START_JOB(55), ABORT_JOB(57), GET_JOB_STATE(59),
        SET_LOG_EVENTS(63), CLEAR_LOG_EVENTS(65),
        SET_REMOTE_LOADER_PASSWORD(71), CLEAR_REMOTE_LOADER_PASSWORD(73),
        SET_NAMED_PASSWORD(75), REMOVE_NAMED_PASSWORD(77), LIST_NAMED_PASSWORDS(81),
        GET_JVM_STATS(93),
        APPLY_ACTIVATION(95), VIEW_ACTIVATION(97),
        SET_MUTUAL_AUTH_KS_PASSWORD(103), CLEAR_MUTUAL_AUTH_KS_PASSWORD(105),
        SET_MUTUAL_AUTH_KEY_PASSWORD(107), CLEAR_MUTUAL_AUTH_KEY_PASSWORD(109);

        public final int number;

        Op(int number) {
            this.number = number;
        }

        public String oid() {
            return OID_BASE + number;
        }

        public String replyOid() {
            return OID_BASE + (number + 1);
        }
    }

    private IdmOps() {
    }

    /** The request: {@code op}'s OID and the items encoded; no items is no value at all. */
    public static LDAPExtendedOperation request(Op op, Object... items) {
        return new LDAPExtendedOperation(op.oid(), items.length == 0 ? null : encode(items));
    }

    /**
     * The items as BER elements back to back. A {@code char[]} password is encoded from its UTF-8 bytes,
     * which are zeroed once written; the caller zeroes the returned value (and its own array) after use.
     */
    public static byte[] encode(Object... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        LBEREncoder enc = new LBEREncoder();
        try {
            for (Object item : items) {
                if (item == null) {
                    new ASN1OctetString(new byte[0]).encode(enc, out);
                } else if (item instanceof String s) {
                    new ASN1OctetString(s).encode(enc, out);
                } else if (item instanceof Integer i) {
                    new ASN1Integer(i).encode(enc, out);
                } else if (item instanceof byte[] b) {
                    new ASN1OctetString(b).encode(enc, out);
                } else if (item instanceof String[] ss) {
                    ASN1SequenceOf seq = new ASN1SequenceOf(ss.length);
                    for (String s : ss) {
                        seq.add(new ASN1OctetString(s));
                    }
                    seq.encode(enc, out);
                } else if (item instanceof int[] is) {
                    ASN1SetOf set = new ASN1SetOf(is.length);
                    for (int i : is) {
                        set.add(new ASN1Integer(i));
                    }
                    set.encode(enc, out);
                } else if (item instanceof char[] pw) {
                    byte[] bytes = utf8(pw);
                    try {
                        new ASN1OctetString(bytes).encode(enc, out);
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                } else {
                    throw new IllegalArgumentException("cannot encode a " + item.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // cannot happen writing to memory
        }
        return out.toByteArray();
    }

    private static byte[] utf8(char[] pw) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pw));
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
        if (bb.hasArray()) {
            Arrays.fill(bb.array(), (byte) 0);
        }
        return bytes;
    }

    /** A reply value as its SEQUENCE; empty when there is no value. */
    public static Reply decode(byte[] value) {
        if (value == null || value.length == 0) {
            return new Reply(List.of());
        }
        ASN1Object o = new LBERDecoder().decode(value);
        if (!(o instanceof ASN1Sequence seq)) {
            throw new IllegalStateException("the reply is not a BER SEQUENCE");
        }
        List<ASN1Object> items = new ArrayList<>();
        for (int i = 0; i < seq.size(); i++) {
            items.add(seq.get(i));
        }
        return new Reply(items);
    }

    /** A decoded reply, read by position. */
    public record Reply(List<ASN1Object> items) {

        public int size() {
            return items.size();
        }

        public int integer(int i) {
            return ((ASN1Integer) need(i)).intValue();
        }

        public String string(int i) {
            return ((ASN1OctetString) need(i)).stringValue();
        }

        public byte[] bytes(int i) {
            return ((ASN1OctetString) need(i)).byteValue();
        }

        /** Element {@code i} as a list of string pairs: a sequence of SETs, each two OCTET STRINGs (named passwords). */
        public List<String[]> pairs(int from) {
            List<String[]> out = new ArrayList<>();
            for (int i = from; i < items.size(); i++) {
                ASN1Set set = (ASN1Set) items.get(i);
                out.add(new String[] {((ASN1OctetString) set.get(0)).stringValue(), ((ASN1OctetString) set.get(1)).stringValue()});
            }
            return out;
        }

        private ASN1Object need(int i) {
            if (i >= items.size()) {
                throw new IllegalStateException("the reply has " + items.size() + " element(s), not " + (i + 1));
            }
            return items.get(i);
        }
    }

    /**
     * The engine's packed version: major (4 bits), minor (4), revision (8), then from major 4 on a
     * patch (12) and a build type (4), before that a patch (16). Returns {major, minor, revision, patch}
     * and, from 4 on, the build type.
     */
    public static int[] parseVersion(int packed) {
        int major = packed >>> 28 & 0xF;
        if (major >= 4) {
            return new int[] {major, packed >> 24 & 0xF, packed >> 16 & 0xFF, packed >> 4 & 0xFFF, packed & 0xF};
        }
        return new int[] {major, packed >> 24 & 0xF, packed >> 16 & 0xFF, packed & 0xFFFF};
    }
}
