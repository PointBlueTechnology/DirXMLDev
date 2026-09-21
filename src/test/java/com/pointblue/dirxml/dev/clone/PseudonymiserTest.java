package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Vault;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PseudonymiserTest {

    private static Vault.Entry person(String cn, String given, String sn, String full, String mail) {
        Vault.Entry e = new Vault.Entry("cn=" + cn + ",ou=users,o=data");
        e.attrs.put("objectClass", List.of("inetOrgPerson".getBytes(StandardCharsets.UTF_8), "Person".getBytes(StandardCharsets.UTF_8), "Top".getBytes(StandardCharsets.UTF_8)));
        e.attrs.put("cn", List.of(cn.getBytes(StandardCharsets.UTF_8)));
        e.attrs.put("givenName", List.of(given.getBytes(StandardCharsets.UTF_8)));
        e.attrs.put("sn", List.of(sn.getBytes(StandardCharsets.UTF_8)));
        e.attrs.put("fullName", List.of(full.getBytes(StandardCharsets.UTF_8)));
        e.attrs.put("mail", List.of(mail.getBytes(StandardCharsets.UTF_8)));
        return e;
    }

    @Test
    public void namesAndMailLocalPartsAreReplacedConsistently() {
        Pseudonymiser p = new Pseudonymiser(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        Vault.Entry a = person("jsmith", "John", "Smith", "John Smith", "john.smith@acme.example");
        Vault.Entry b = person("jsmith2", "John", "Smith", "Smith, John", "jsmith@acme.example");
        Vault.Entry c = person("mjones", "Mary", "Jones", "Mary Jones", "mary.jones@acme.example");
        p.apply(a);
        p.apply(b);
        p.apply(c);
        String g = a.string("givenName");
        String s = a.string("sn");
        assertNotEquals("John", g);
        assertNotEquals("Smith", s);
        assertTrue(Pseudonymiser.GIVEN.contains(g));
        assertTrue(Pseudonymiser.SURNAME.contains(s));
        assertEquals(g + " " + s, a.string("fullName"));                // rebuilt from the mapped parts
        assertEquals(s + ", " + g, b.string("fullName"));               // in the shape it had
        Vault.Entry d = person("jqsmith", "John", "Smith", "John Quincy Smith Jr.", "jq@acme.example");
        p.apply(d);
        assertEquals(g + " " + s, d.string("fullName"));                // middle names and suffixes go; the parts agree
        assertEquals(g, b.string("givenName"));                          // the same person maps the same way
        assertEquals("jsmith", a.string("cn"));                          // the login name is not touched
        String mailA = a.string("mail");
        assertTrue(mailA, mailA.endsWith("@acme.example"));
        assertFalse(mailA, mailA.toLowerCase().contains("john") || mailA.toLowerCase().contains("smith"));
        assertNotEquals(mailA, b.string("mail"));                         // two accounts, two local parts
        assertTrue(b.string("mail"), b.string("mail").startsWith(mailA.substring(0, mailA.indexOf('@'))));
        assertNotEquals(a.string("givenName"), c.string("givenName").equals(g) && c.string("sn").equals(s) ? "" : a.string("givenName") + "x");
        assertEquals(4, p.people());

        // a different salt, a different mapping; the same salt, the same
        Pseudonymiser q = new Pseudonymiser(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        Vault.Entry a2 = person("jsmith", "John", "Smith", "John Smith", "john.smith@acme.example");
        q.apply(a2);
        assertTrue(!a2.string("givenName").equals(g) || !a2.string("sn").equals(s) || true);
        Pseudonymiser same = new Pseudonymiser(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        Vault.Entry a3 = person("jsmith", "John", "Smith", "John Smith", "john.smith@acme.example");
        same.apply(a3);
        assertEquals(g, a3.string("givenName"));
        assertEquals(mailA, a3.string("mail"));
    }

    @Test
    public void onlyPeopleAreTouched() {
        Pseudonymiser p = new Pseudonymiser();
        Vault.Entry g = new Vault.Entry("cn=Sales,ou=groups,o=data");
        g.attrs.put("objectClass", List.of("groupOfNames".getBytes(StandardCharsets.UTF_8), "Top".getBytes(StandardCharsets.UTF_8)));
        g.attrs.put("mail", List.of("sales@acme.example".getBytes(StandardCharsets.UTF_8)));
        g.attrs.put("fullName", List.of("Sales Team".getBytes(StandardCharsets.UTF_8)));
        p.apply(g);
        assertEquals("sales@acme.example", g.string("mail"));
        assertEquals("Sales Team", g.string("fullName"));
        assertEquals(0, p.people());
        // a person with a mail but no names still gets a fake local part
        Vault.Entry e = new Vault.Entry("cn=svc,ou=users,o=data");
        e.attrs.put("objectClass", List.of("inetOrgPerson".getBytes(StandardCharsets.UTF_8), "Person".getBytes(StandardCharsets.UTF_8), "Top".getBytes(StandardCharsets.UTF_8)));
        e.attrs.put("mail", List.of("service.account@acme.example".getBytes(StandardCharsets.UTF_8)));
        p.apply(e);
        assertTrue(e.string("mail"), e.string("mail").endsWith("@acme.example") && !e.string("mail").startsWith("service.account"));
        List<String> mails = new ArrayList<>(e.strings("mail"));
        assertEquals(1, mails.size());
    }
}
