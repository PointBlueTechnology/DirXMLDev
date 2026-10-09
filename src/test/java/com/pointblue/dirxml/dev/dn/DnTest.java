package com.pointblue.dirxml.dev.dn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * The hostile-name fixture from the Identity Console defect analysis: every name that broke the
 * console's string handling must parse to the right RDNs and survive a round trip unchanged.
 */
public class DnTest {

    @Test
    public void escapedCommaIsPartOfTheValueAndIsWrittenBack() {
        Dn dn = Dn.parse("cn=Smith\\, John,ou=Users,o=Acme");
        assertEquals(3, dn.size());
        assertEquals("Smith, John", dn.leaf().value());
        assertEquals("ou=Users,o=Acme", dn.parent().toString());
        assertEquals("cn=Smith\\, John,ou=Users,o=Acme", dn.toString());
    }

    @Test
    public void hexEscapesDecodeAsUtf8() {
        // eDirectory and other servers may return \2C for a comma; \C3\A9 is é
        Dn dn = Dn.parse("cn=Smith\\2C John,ou=Caf\\C3\\A9,o=Acme");
        assertEquals("Smith, John", dn.leaf().value());
        assertEquals("Café", dn.parent().leaf().value());
        assertEquals("cn=Smith\\, John,ou=Café,o=Acme", dn.toString());
    }

    @Test
    public void slashHashPercentAndParenthesesAreOrdinaryCharacters() {
        for (String v : List.of("AC/DC", "Team#1", "100%", "Q%41", "Smith (Admin)", "Room 5C", "R&D", "a=b")) {
            Dn dn = Dn.parse("cn=" + Ava.escape(v) + ",o=Acme");
            assertEquals(v, dn.leaf().value());
            assertEquals(2, dn.size());
            assertEquals(dn, Dn.parse(dn.toString()));
        }
        assertEquals("cn=AC/DC,o=Bands", Dn.parse("cn=AC/DC,o=Bands").toString());
    }

    @Test
    public void multiValuedRdnIsOneRdnWithTwoAvas() {
        Dn dn = Dn.parse("cn=A+uid=B,o=Acme");
        assertEquals(2, dn.size());
        assertTrue(dn.leaf().multiValued());
        assertEquals("uid", dn.leaf().avas().get(1).type());
        assertEquals(Dn.parse("uid=b+CN=a,o=acme"), dn);   // order of AVAs does not matter
        assertEquals("A+B", Dn.parse("cn=A\\+B,o=Acme").leaf().value());
    }

    @Test
    public void leadingHashAndLeadingOrTrailingSpacesAreEscaped() {
        Dn dn = Dn.of(List.of(Rdn.of("cn", "#hash"), Rdn.of("ou", " padded "), Rdn.of("o", "Acme")));
        assertEquals("cn=\\#hash,ou=\\ padded\\ ,o=Acme", dn.toString());
        Dn back = Dn.parse(dn.toString());
        assertEquals("#hash", back.leaf().value());
        assertEquals(" padded ", back.parent().leaf().value());
    }

    @Test
    public void spacesAroundSeparatorsAreTolerated() {
        Dn dn = Dn.parse(" cn = Smith\\, John , ou=Users,  o=Acme ");
        assertEquals("cn=Smith\\, John,ou=Users,o=Acme", dn.toString());
    }

    @Test
    public void hexBerValueRoundTrips() {
        Dn dn = Dn.parse("1.3.6.1.4.1.1466.0=#04024869,o=Acme");
        assertNull(dn.leaf().value());
        assertArrayEquals(new byte[] {0x04, 0x02, 0x48, 0x69}, dn.leaf().avas().get(0).ber());
        assertEquals("1.3.6.1.4.1.1466.0=#04024869,o=Acme", dn.toString());
    }

    @Test
    public void equalityIsCaseIgnoredWithSpacesCollapsed() {
        assertEquals(Dn.parse("CN=John  Smith,OU=Users,O=Acme"), Dn.parse("cn=john smith,ou=users,o=acme"));
        assertFalse(Dn.parse("cn=John Smith,o=Acme").equals(Dn.parse("cn=John Smyth,o=Acme")));
    }

    @Test
    public void rootParentChildAndWithin() {
        assertTrue(Dn.parse("").isRoot());
        assertTrue(Dn.parse("o=Acme").parent().isRoot());
        Dn users = Dn.parse("ou=Users,o=Acme");
        Dn smith = users.child(Rdn.of("cn", "Smith, John"));
        assertEquals("cn=Smith\\, John,ou=Users,o=Acme", smith.toString());
        assertTrue(smith.isWithin(users));
        assertTrue(smith.isWithin(Dn.root()));
        assertFalse(users.isWithin(smith));
        assertEquals(List.of(Rdn.of("o", "Acme"), Rdn.of("ou", "Users")), users.rootFirst());
        assertEquals(users, Dn.fromRootFirst(users.rootFirst()));
    }

    @Test
    public void malformedInputIsRefusedWithAPosition() {
        for (String bad : List.of("cn", "cn=a,", "=a", "cn=a\\", "cn=a\\q", "cn=#abc", "c n=a", "cn=a,,o=b", "cn=\\FF\\FE")) {
            DnException e = assertThrows(bad, DnException.class, () -> Dn.parse(bad));
            assertTrue(e.getMessage(), e.getMessage().contains("position") || e.getMessage().contains("type"));
        }
    }

    @Test
    public void parseRdnRequiresExactlyOne() {
        assertEquals("Smith, John", Dn.parseRdn("cn=Smith\\, John").value());
        assertThrows(DnException.class, () -> Dn.parseRdn("cn=a,o=b"));
    }

    @Test
    public void randomValuesRoundTrip() {
        String alphabet = " ,+\"\\<>;#=/%()*.&aZ09éß中\u0000";
        Random r = new Random(4514);
        for (int n = 0; n < 5000; n++) {
            StringBuilder v = new StringBuilder();
            int len = 1 + r.nextInt(12);
            for (int i = 0; i < len; i++) {
                v.append(alphabet.charAt(r.nextInt(alphabet.length())));
            }
            Dn dn = Dn.of(List.of(Rdn.of("cn", v.toString()), Rdn.of("o", "Acme")));
            Dn back = Dn.parse(dn.toString());
            assertEquals("value " + v, v.toString(), back.leaf().value());
            assertEquals(dn.toString(), back.toString());
        }
    }
}
