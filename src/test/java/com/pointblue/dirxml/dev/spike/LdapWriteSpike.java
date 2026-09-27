package com.pointblue.dirxml.dev.spike;

import javax.naming.Context;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;

/**
 * Phase 0 spike #1 — <b>can we deploy over LDAP?</b> Proves the write path the
 * vault deployer will use, with zero impact on existing objects:
 *
 * <ol>
 *   <li><b>Create</b> a scratch {@code DirXML-Rule} in the driver set's Library with
 *       {@code XmlData} holding a trivial policy — an unlinked Library policy that no
 *       driver runs.</li>
 *   <li><b>Read back</b> and byte-compare {@code XmlData} (learns whether the server
 *       returns it as bytes/UTF-8, and whether it normalizes anything).</li>
 *   <li><b>Modify</b> {@code XmlData} in place and read back again.</li>
 *   <li><b>Delete</b> the scratch object — always, in a {@code finally}.</li>
 * </ol>
 *
 * Every step prints OK/FAIL with the server's response so the findings are
 * evidence, not inference (objectClassViolation, insufficientAccess, attribute
 * syntax, etc. are exactly what we want to learn).
 *
 * <p>Step 2 of the spike — <i>link the policy to a driver and prove the engine picks
 * it up after {@code RestartDriver}</i> — is deliberately <b>not</b> automated here:
 * it needs the extended-op API reference (spike #3) and a driver chosen by a human
 * whose restart has no side effects. It will be added as an explicit opt-in.
 *
 * <p>Run (test vault only — this writes):
 * <pre>
 *   java -cp … com.pointblue.dirxml.dev.spike.LdapWriteSpike \
 *     -Dspike.url=ldaps://host:636 -Dspike.bindDn=cn=admin,… -Dspike.password=… \
 *     -Dspike.driverSetDn=cn=driverset1,o=system
 * </pre>
 * Never point this at production.
 */
public final class LdapWriteSpike {

    private static final String POLICY_V1 =
        "<policy><rule><description>dirxml-dev spike v1</description><conditions/>"
        + "<actions><do-trace-message><arg-string><token-text>dirxml-dev spike v1</token-text>"
        + "</arg-string></do-trace-message></actions></rule></policy>";
    private static final String POLICY_V2 = POLICY_V1.replace("v1", "v2");

    public static void main(String[] args) throws Exception {
        String url = need("spike.url");
        String bindDn = need("spike.bindDn");
        String password = need("spike.password");
        String driverSetDn = need("spike.driverSetDn");
        String cn = "dirxmldev-spike-" + System.currentTimeMillis();
        String dn = "cn=" + cn + ",cn=Library," + driverSetDn;

        DirContext ctx = connect(url, bindDn, password);
        boolean created = false;
        try {
            // 1. create
            Attributes attrs = new BasicAttributes(true);
            BasicAttribute oc = new BasicAttribute("objectClass");
            oc.add("Top");
            oc.add("DirXML-Rule");
            attrs.put(oc);
            attrs.put("cn", cn);
            attrs.put("XmlData", POLICY_V1.getBytes(StandardCharsets.UTF_8));
            step("create " + dn, () -> ctx.createSubcontext(dn, attrs));
            created = true;

            // 2. read back
            String got = readXmlData(ctx, dn);
            report("read-back equals what we wrote", POLICY_V1.equals(got),
                got == null ? "(null)" : abbreviate(got));

            // 3. modify
            ModificationItem[] mods = {new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                new BasicAttribute("XmlData", POLICY_V2.getBytes(StandardCharsets.UTF_8)))};
            step("modify XmlData", () -> ctx.modifyAttributes(dn, mods));
            String got2 = readXmlData(ctx, dn);
            report("read-back after modify equals v2", POLICY_V2.equals(got2),
                got2 == null ? "(null)" : abbreviate(got2));

            // What does the server stamp on it? (package attrs, modify timestamps…)
            Attributes all = ctx.getAttributes(dn);
            StringBuilder sb = new StringBuilder();
            for (var e = all.getIDs(); e.hasMore(); ) {
                String id = e.next();
                if (!id.equalsIgnoreCase("XmlData")) {
                    sb.append(id).append('=').append(all.get(id).get()).append("  ");
                }
            }
            System.out.println("   server-side attributes on the scratch object: " + sb);
        } finally {
            if (created) {
                // 4. delete — always
                try {
                    ctx.destroySubcontext(dn);
                    System.out.println("OK   delete " + dn);
                } catch (Exception e) {
                    System.out.println("FAIL delete " + dn + " -> " + e.getMessage()
                        + "   *** clean up manually ***");
                }
            }
            ctx.close();
        }
        System.out.println("\nStep 2 (link to a driver + RestartDriver + verify) is a separate, opt-in run.");
    }

    // ---- helpers ----

    private interface Op {
        void run() throws Exception;
    }

    private static void step(String what, Op op) {
        try {
            op.run();
            System.out.println("OK   " + what);
        } catch (Exception e) {
            System.out.println("FAIL " + what + " -> " + e);
            throw new RuntimeException("spike step failed: " + what, e);
        }
    }

    private static void report(String what, boolean ok, String detail) {
        System.out.println((ok ? "OK   " : "FAIL ") + what + (ok ? "" : "   got: " + detail));
    }

    private static String readXmlData(DirContext ctx, String dn) throws Exception {
        Attributes a = ctx.getAttributes(dn, new String[]{"XmlData"});
        if (a.get("XmlData") == null) {
            return null;
        }
        Object v = a.get("XmlData").get();
        return v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : String.valueOf(v);
    }

    private static DirContext connect(String url, String bindDn, String password) throws Exception {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_PRINCIPAL, bindDn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        // XmlData is treated as binary so we get exact bytes back.
        env.put("java.naming.ldap.attributes.binary", "XmlData");
        if (url.startsWith("ldaps")) {
            // test vaults use self-signed certs; reuse the simulator's trust-all factory
            env.put("java.naming.ldap.factory.socket", "com.pointblue.dirxml.sim.TrustAllSocketFactory");
        }
        return new InitialDirContext(env);
    }

    private static String need(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing -D" + prop);
        }
        return v;
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }
}
