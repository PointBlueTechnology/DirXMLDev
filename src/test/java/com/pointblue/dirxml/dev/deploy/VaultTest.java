package com.pointblue.dirxml.dev.deploy;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Integration test against a TEST vault — runs only when
 * {@code -Dvault.url -Dvault.bindDn -Dvault.password -Dvault.driverSetDn} are
 * set (never in the default build). Writes scratch objects under cn=Library and
 * removes them.
 */
public class VaultTest {

    private Vault.Config cfg;
    private String dsDn;

    @Before
    public void guard() {
        Assume.assumeTrue("set -Dvault.url/.bindDn/.password/.driverSetDn to run", System.getProperty("vault.url") != null);
        cfg = new Vault.Config();
        cfg.url = System.getProperty("vault.url");
        cfg.bindDn = System.getProperty("vault.bindDn");
        cfg.password = System.getProperty("vault.password");
        dsDn = System.getProperty("vault.driverSetDn");
    }

    @Test
    public void createModifyDeleteRule() {
        String dn = "cn=dirxmldev-test-" + System.currentTimeMillis() + ",cn=Library," + dsDn;
        String xml = "<policy><rule><description>t</description><conditions/><actions/></rule></policy>";
        try (Vault v = Vault.connect(cfg)) {
            assertNull(v.read(dn));
            Map<String, List<byte[]>> a = Vault.attrs();
            a.put("XmlData", Vault.value(xml));
            v.add(dn, List.of("Top", "DirXML-Rule"), a);
            try {
                Vault.Entry e = v.read(dn);
                assertTrue(e.hasClass("DirXML-Rule"));
                assertEquals(xml, e.string("XmlData"));
                v.replace(dn, "XmlData", xml.replace("t<", "t2<"));
                assertEquals(xml.replace("t<", "t2<"), v.read(dn).string("XmlData"));
                assertFalse(v.children("cn=Library," + dsDn).isEmpty());
            } finally {
                v.delete(dn);
            }
            assertNull(v.read(dn));
        }
    }

    @Test
    public void driverStateAndNamedPasswords() {
        String driver = System.getProperty("vault.driver");
        Assume.assumeTrue("set -Dvault.driver=<side-effect-free driver DN>", driver != null);
        try (Vault v = Vault.connect(cfg)) {
            int st = v.driverState(driver);
            assertTrue(st >= 0 && st <= 3);
            String name = "dirxmldev-test-" + System.currentTimeMillis();
            v.setNamedPassword(driver, name, null, "x".toCharArray());
            try {
                assertTrue(v.namedPasswords(driver).contains(name));
            } finally {
                v.removeNamedPassword(driver, name);
            }
            assertFalse(v.namedPasswords(driver).contains(name));
        }
    }
}
