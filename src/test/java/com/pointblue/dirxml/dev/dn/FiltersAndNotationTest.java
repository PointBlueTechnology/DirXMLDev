package com.pointblue.dirxml.dev.dn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HexFormat;
import java.util.List;
import org.junit.Test;

public class FiltersAndNotationTest {

    // ---- filters (Identity Console defects U2, U3) ----------------------------------------------

    @Test
    public void metacharactersAreEscapedAndOrdinaryTextIsNot() {
        assertEquals("(cn=Smith \\28Admin\\29)", Filters.eq("cn", "Smith (Admin)"));
        assertEquals("(cn=Room 5C)", Filters.eq("cn", "Room 5C"));        // "5C" is text, never deleted
        assertEquals("(cn=a\\5cb)", Filters.eq("cn", "a\\b"));
        assertEquals("(cn=\\2a)", Filters.eq("cn", "*"));                 // a literal star, not a wildcard
        assertEquals("(cn=R&D)", Filters.eq("cn", "R&D"));
        assertEquals("(cn=Zoë)", Filters.eq("cn", "Zoë"));
    }

    @Test
    public void wildcardsComeOnlyFromTheOperator() {
        assertEquals("(cn=Smith \\28Admin*)", Filters.prefix("cn", "Smith (Admin"));
        assertEquals("(cn=*\\2a*)", Filters.contains("cn", "*"));
        assertEquals("(mail=*@acme.com)", Filters.suffix("mail", "@acme.com"));
        assertEquals("(loginDisabled=*)", Filters.present("loginDisabled"));
    }

    @Test
    public void combinatorsNest() {
        String f = Filters.and(Filters.eq("objectClass", "inetOrgPerson"),
                Filters.or(Filters.eq("cn", "a)(uid=*"), Filters.not(Filters.present("loginDisabled"))));
        assertEquals("(&(objectClass=inetOrgPerson)(|(cn=a\\29\\28uid=\\2a)(!(loginDisabled=*))))", f);
        assertEquals("(cn=x)", Filters.and(List.of(Filters.eq("cn", "x"))));
        assertThrows(DnException.class, () -> Filters.or(List.of()));
    }

    @Test
    public void attributeNamesCannotInject() {
        assertThrows(DnException.class, () -> Filters.eq("cn)(objectClass=*", "x"));
        assertThrows(DnException.class, () -> Filters.eq("", "x"));
        assertEquals("(DirXML-Associations=x)", Filters.eq("DirXML-Associations", "x"));
        assertEquals("(2.5.4.3=x)", Filters.eq("2.5.4.3", "x"));
        assertEquals("(cn;lang-en=x)", Filters.eq("cn;lang-en", "x"));
    }

    // ---- GUID ----------------------------------------------------------------------------------

    @Test
    public void guidRoundTripsAndFiltersEveryByte() {
        String hex = "4f1c9a7e2b6d4e0a9c3f5b7d1e2a8c40";
        Guid g = Guid.parse(hex.toUpperCase());
        assertEquals(hex, g.toString());
        assertEquals(Guid.parse(hex), new Guid(HexFormat.of().parseHex(hex)));
        assertEquals("(GUID=\\4f\\1c\\9a\\7e\\2b\\6d\\4e\\0a\\9c\\3f\\5b\\7d\\1e\\2a\\8c\\40)", g.filter());
        assertThrows(DnException.class, () -> Guid.parse("4f1c"));
        assertThrows(DnException.class, () -> new Guid(new byte[15]));
    }

    // ---- dot notation (defect S4) ----------------------------------------------------------------

    @Test
    public void dotNotationEscapesPeriodsAndOtherSeparators() {
        Dn dn = Dn.parse("cn=john.smith,ou=Users,o=Acme");
        assertEquals("john\\.smith.Users.Acme", DotNotation.typeless(dn));
        assertEquals("cn=john\\.smith.ou=Users.o=Acme", DotNotation.typed(dn));
        Dn comma = Dn.parse("cn=Smith\\, John,ou=Users,o=Acme");
        assertEquals("Smith\\, John.Users.Acme", DotNotation.typeless(comma));
    }

    @Test
    public void typedDotNamesParseBackToTheSameDn() {
        for (String ldap : List.of("cn=john.smith,ou=Users,o=Acme", "cn=Smith\\, John,ou=Users,o=Acme",
                "cn=A+uid=B,o=Acme", "cn=a=b\\+c,o=Acme", "cn=back\\\\slash,o=Acme")) {
            Dn dn = Dn.parse(ldap);
            assertEquals(ldap, dn, DotNotation.parse(DotNotation.typed(dn)));
        }
        assertEquals(Dn.parse("cn=admin,ou=sa,o=system"), DotNotation.parse(".cn=admin.ou=sa.o=system"));
    }

    @Test
    public void typelessNamesAreNotGuessed() {
        assertFalse(DotNotation.isTyped("admin.sa.system"));
        assertTrue(DotNotation.isTyped("cn=admin.ou=sa.o=system"));
        assertThrows(DnException.class, () -> DotNotation.parse("admin.sa.system"));
        assertEquals(List.of(List.of("john.smith"), List.of("Users"), List.of("Acme")),
                DotNotation.parseTypeless("john\\.smith.Users.Acme"));
        assertThrows(DnException.class, () -> DotNotation.parseTypeless("a..b"));
    }
}
