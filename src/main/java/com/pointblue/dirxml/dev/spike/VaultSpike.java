package com.pointblue.dirxml.dev.spike;

import com.pointblue.dirxml.dev.deploy.Vault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 4 spikes, against the TEST vault only (writes scratch objects, cleans up):
 * <ol>
 *   <li><b>object classes</b> — create a DirXML-Rule, a DirXML-StyleSheet, a
 *       DirXML-Resource (with DirXML-ContentType + DirXML-Data) and a
 *       DirXML-GlobalConfigDef under cn=Library; read back byte-exact; delete;</li>
 *   <li><b>package checksum</b> — copy a packaged Library object's DirXML-pkg*
 *       attributes onto a scratch object, modify its content, and see whether the
 *       server changes DirXML-pkgChecksum (deploy must know whether the vault
 *       marks a customized object by itself);</li>
 *   <li><b>secrets (4a)</b> — on the side-effect-free Querytest driver: set/list/
 *       remove a scratch named password through the extended ops; check whether
 *       DirXML-ShimAuthPassword is LDAP-writable by the deploy identity (write a
 *       value, restore by removing the attribute if it wasn't set before).</li>
 * </ol>
 * Run with -Dspike.url/-Dspike.bindDn/-Dspike.password/-Dspike.driverSetDn
 * [-Dspike.driver=cn=Querytest,…] [-Dspike.packaged=<dn of a packaged Library object>].
 */
public final class VaultSpike {

    private static final String POLICY =
        "<policy><rule><description>dirxmldev vault spike</description><conditions/><actions/></rule></policy>";
    private static final String XSLT =
        "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\"><xsl:template match=\"/\"><xsl:copy-of select=\".\"/></xsl:template></xsl:stylesheet>";
    private static final String TABLE =
        "<mapping-table><col-def name=\"k\" type=\"nocase\"/><col-def name=\"v\" type=\"nocase\"/><row><col>1</col><col>one</col></row></mapping-table>";
    private static final String GCVS =
        "<configuration-values><definitions><definition display-name=\"Spike\" name=\"spike.value\" type=\"string\"><value>1</value></definition></definitions></configuration-values>";

    public static void main(String[] args) throws Exception {
        Vault.Config c = new Vault.Config();
        c.url = need("spike.url");
        c.bindDn = need("spike.bindDn");
        c.password = need("spike.password");
        String dsDn = need("spike.driverSetDn");
        String driverDn = System.getProperty("spike.driver");
        String packagedDn = System.getProperty("spike.packaged");
        String stamp = String.valueOf(System.currentTimeMillis());
        List<String> created = new ArrayList<>();

        try (Vault v = Vault.connect(c)) {
            System.out.println("== 1. object classes under cn=Library," + dsDn);
            String lib = "cn=Library," + dsDn;
            Vault.Entry libEntry = v.read(lib);
            System.out.println("   Library container classes: " + (libEntry == null ? "(absent)" : libEntry.objectClasses()));

            String rule = "cn=dirxmldev-spike-rule-" + stamp + "," + lib;
            Map<String, List<byte[]>> a = Vault.attrs();
            a.put("XmlData", Vault.value(POLICY));
            v.add(rule, List.of("Top", "DirXML-Rule"), a);
            created.add(rule);
            check("DirXML-Rule XmlData byte-exact", POLICY.equals(v.read(rule).string("XmlData")));

            String xsl = "cn=dirxmldev-spike-xslt-" + stamp + "," + lib;
            a = Vault.attrs();
            a.put("XmlData", Vault.value(XSLT));
            v.add(xsl, List.of("Top", "DirXML-StyleSheet"), a);
            created.add(xsl);
            check("DirXML-StyleSheet XmlData byte-exact", XSLT.equals(v.read(xsl).string("XmlData")));

            String res = "cn=dirxmldev-spike-table-" + stamp + "," + lib;
            a = Vault.attrs();
            a.put("DirXML-ContentType", Vault.value("application/vnd.novell.dirxml.mapping-table+xml ;charset=UTF-8"));
            a.put("DirXML-Data", Vault.value(TABLE));
            v.add(res, List.of("Top", "DirXML-Resource"), a);
            created.add(res);
            Vault.Entry re = v.read(res);
            check("DirXML-Resource DirXML-Data byte-exact + content type", TABLE.equals(re.string("DirXML-Data"))
                && re.string("DirXML-ContentType") != null);
            System.out.println("   resource attrs: " + re.attrs.keySet());

            String gcv = "cn=dirxmldev-spike-gcv-" + stamp + "," + lib;
            a = Vault.attrs();
            a.put("DirXML-ConfigValues", Vault.value(GCVS));
            v.add(gcv, List.of("Top", "DirXML-GlobalConfigDef"), a);
            created.add(gcv);
            check("DirXML-GlobalConfigDef DirXML-ConfigValues byte-exact", GCVS.equals(v.read(gcv).string("DirXML-ConfigValues")));

            v.replace(rule, "XmlData", POLICY.replace("spike", "spike v2"));
            check("modify XmlData", v.read(rule).string("XmlData").contains("spike v2"));

            System.out.println("== 2. package checksum");
            if (packagedDn == null) {
                // find a packaged policy anywhere in the driver set to copy from
                for (Vault.Entry e : v.search(dsDn, "(&(DirXML-pkgChecksum=*)(XmlData=*))",
                        javax.naming.directory.SearchControls.SUBTREE_SCOPE)) {
                    packagedDn = e.dn;
                    break;
                }
            }
            if (packagedDn == null) {
                System.out.println("   no packaged Library object with XmlData found; skipping");
            } else {
                Vault.Entry src = v.read(packagedDn);
                System.out.println("   source " + packagedDn + " pkg attrs: "
                    + src.attrs.keySet().stream().filter(k -> k.toLowerCase().startsWith("dirxml-pkg")).toList()
                    + " checksum=" + src.string("DirXML-pkgChecksum"));
                String pk = "cn=dirxmldev-spike-pkg-" + stamp + "," + lib;
                a = Vault.attrs();
                a.put("XmlData", List.of(src.bytes("XmlData")));
                for (String k : src.attrs.keySet()) {
                    if (k.toLowerCase().startsWith("dirxml-pkg")) {
                        a.put(k, src.attrs.get(k));
                    }
                }
                try {
                    v.add(pk, src.objectClasses(), a);
                    created.add(pk);
                    Vault.Entry before = v.read(pk);
                    System.out.println("   created with pkg attrs; server checksum now=" + before.string("DirXML-pkgChecksum"));
                    v.replace(pk, "XmlData", new String(src.bytes("XmlData"), StandardCharsets.UTF_8).replaceFirst("<policy", "<policy xml:space=\"preserve\""));
                    Vault.Entry after = v.read(pk);
                    String b = before.string("DirXML-pkgChecksum");
                    String af = after.string("DirXML-pkgChecksum");
                    System.out.println("   after XmlData modify: checksum before=" + b + " after=" + af
                        + " -> " + (String.valueOf(b).equals(String.valueOf(af)) ? "SERVER DOES NOT UPDATE the checksum" : "server UPDATED the checksum"));
                } catch (Vault.VaultException e) {
                    System.out.println("   cannot create a scratch object with pkg attrs: " + e.getMessage());
                }
            }

            System.out.println("== 3. secrets (4a)");
            if (driverDn == null) {
                System.out.println("   no -Dspike.driver; skipping");
            } else {
                System.out.println("   driver state: " + Vault.stateName(v.driverState(driverDn))
                    + ", start option: " + v.driverStartOption(driverDn));
                List<String> before = v.namedPasswords(driverDn);
                System.out.println("   named passwords before: " + before);
                String name = "dirxmldev-spike-" + stamp;
                v.setNamedPassword(driverDn, name, "spike", "s3cret".toCharArray());
                List<String> after = v.namedPasswords(driverDn);
                check("SetNamedPassword then ListNamedPasswords shows it", after.contains(name));
                v.removeNamedPassword(driverDn, name);
                check("RemoveNamedPassword", !v.namedPasswords(driverDn).contains(name));
                // driver-set scope
                List<String> dsBefore = v.namedPasswords(dsDn);
                System.out.println("   driver-set named passwords: " + dsBefore);
                v.setNamedPassword(dsDn, name, "spike", "s3cret".toCharArray());
                check("driver-set SetNamedPassword", v.namedPasswords(dsDn).contains(name));
                v.removeNamedPassword(dsDn, name);

                Vault.Entry d = v.read(driverDn);
                boolean hadPw = d.attrs.containsKey("DirXML-ShimAuthPassword");
                System.out.println("   DirXML-ShimAuthPassword present? " + hadPw + " value-length="
                    + (hadPw ? d.bytes("DirXML-ShimAuthPassword").length : 0) + " (attrs: "
                    + d.attrs.keySet().stream().filter(k -> k.toLowerCase().contains("auth") || k.toLowerCase().contains("password")).toList() + ")");
                if (!Boolean.getBoolean("spike.writeShimPassword")) {
                    System.out.println("   (shim password write skipped; -Dspike.writeShimPassword=true to test it)");
                } else try {
                    v.replace(driverDn, "DirXML-ShimAuthPassword", "spike-pw");
                    System.out.println("   OK   DirXML-ShimAuthPassword LDAP-writable (value set)");
                    if (!hadPw) {
                        try {
                            v.replace(driverDn, "DirXML-ShimAuthPassword", List.of());
                            System.out.println("   OK   removed again (was absent before)");
                        } catch (Vault.VaultException e) {
                            System.out.println("   NOTE could not remove it: " + e.getMessage());
                        }
                    } else {
                        System.out.println("   NOTE the driver had a shim password; it is now 'spike-pw' — reset it (Querytest needs none)");
                    }
                } catch (Vault.VaultException e) {
                    System.out.println("   FAIL DirXML-ShimAuthPassword not LDAP-writable: " + e.getMessage());
                }
            }
        } finally {
            try (Vault v = Vault.connect(c)) {
                for (String dn : created) {
                    try {
                        v.delete(dn);
                        System.out.println("   cleaned " + dn);
                    } catch (Exception e) {
                        System.out.println("   *** clean up manually: " + dn + " (" + e.getMessage() + ")");
                    }
                }
            }
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println("   " + (ok ? "OK   " : "FAIL ") + what);
    }

    private static String need(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing -D" + prop);
        }
        return v;
    }
}
