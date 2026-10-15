package com.pointblue.dirxml.dev.deploy;

import com.novell.ldap.LDAPExtendedOperation;
import com.novell.ldap.asn1.ASN1Integer;
import com.novell.ldap.asn1.ASN1OctetString;
import com.novell.ldap.asn1.ASN1Sequence;
import com.novell.ldap.asn1.ASN1Set;
import com.novell.ldap.asn1.LBEREncoder;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** IdmOps on known bytes: no vendor jar involved (docs/vendor-free-vault.md). */
public class IdmOpsTest {

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @Test
    public void requestsCarryTheOidAndElementsBackToBack() {
        LDAPExtendedOperation op = IdmOps.request(IdmOps.Op.GET_DRIVER_STATE, "cn=a");
        assertEquals("2.16.840.1.113719.1.14.100.13", op.getID());
        assertEquals("0404636e3d61", hex(op.getValue()));   // OCTET STRING "cn=a"
        assertNull("no items, no value", IdmOps.request(IdmOps.Op.GET_VERSION).getValue());
        assertEquals("2.16.840.1.113719.1.14.100.14", IdmOps.Op.GET_DRIVER_STATE.replyOid());
        assertEquals("2.16.840.1.113719.1.14.100.101", IdmOps.Op.RESTART_DRIVER.oid());
        // a string, an int, null, bytes, a string array, an int array: no wrapper around them
        assertEquals("040178" + "020105" + "0400" + "04020102" + "3006040161040162",
            hex(IdmOps.encode("x", 5, null, new byte[] {1, 2}, new String[] {"a", "b"})));
        assertEquals("300604016104016231060201010201ff", hex(IdmOps.encode(new String[] {"a", "b"}, new int[] {1, -1})));
    }

    @Test
    public void aPasswordIsItsUtf8BytesAndIsNotKept() {
        char[] pw = "pä".toCharArray();
        assertEquals("040370c3a4", hex(IdmOps.encode(pw)));
        assertEquals("the caller's array is the caller's to zero", 'p', pw[0]);
    }

    @Test
    public void repliesAreReadByPosition() throws Exception {
        ASN1Sequence seq = new ASN1Sequence();
        seq.add(new ASN1Integer(7));
        seq.add(new ASN1OctetString("cn=ds"));
        ASN1Set pair = new ASN1Set();
        pair.add(new ASN1OctetString("key"));
        pair.add(new ASN1OctetString("Key"));
        seq.add(pair);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        seq.encode(new LBEREncoder(), out);
        IdmOps.Reply r = IdmOps.decode(out.toByteArray());
        assertEquals(3, r.size());
        assertEquals(7, r.integer(0));
        assertEquals("cn=ds", r.string(1));
        assertEquals(1, r.pairs(2).size());
        assertArrayEquals(new String[] {"key", "Key"}, r.pairs(2).get(0));
        assertEquals(0, IdmOps.decode(null).size());
        try {
            r.integer(5);
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("3 element"));
        }
    }

    @Test
    public void theEnginesPackedVersion() {
        // 4.10.1, patch 0, build type 0 — and a 3.x layout with a 16-bit patch
        int packed = (4 << 28) | (10 << 24) | (1 << 16) | (0 << 4);
        assertArrayEquals(new int[] {4, 10, 1, 0, 0}, IdmOps.parseVersion(packed));
        assertArrayEquals(new int[] {4, 8, 7, 123, 2}, IdmOps.parseVersion((4 << 28) | (8 << 24) | (7 << 16) | (123 << 4) | 2));
        assertArrayEquals(new int[] {3, 6, 1, 4567}, IdmOps.parseVersion((3 << 28) | (6 << 24) | (1 << 16) | 4567));
        assertEquals(List.of(4, 10, 1), List.of(IdmOps.parseVersion(packed)[0], IdmOps.parseVersion(packed)[1], IdmOps.parseVersion(packed)[2]));
    }
}
