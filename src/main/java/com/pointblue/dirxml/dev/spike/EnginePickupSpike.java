package com.pointblue.dirxml.dev.spike;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.novell.nds.dirxml.ldap.CloseChunkedResultRequest;
import com.novell.nds.dirxml.ldap.GetChunkedResultRequest;
import com.novell.nds.dirxml.ldap.GetChunkedResultResponse;
import com.novell.nds.dirxml.ldap.GetDriverStateRequest;
import com.novell.nds.dirxml.ldap.GetDriverStateResponse;
import com.novell.nds.dirxml.ldap.RestartDriverRequest;
import com.novell.nds.dirxml.ldap.StartDriverRequest;
import com.novell.nds.dirxml.ldap.StopDriverRequest;
import com.novell.nds.dirxml.ldap.SubmitEventRequest;
import com.novell.nds.dirxml.ldap.SubmitEventResponse;

import javax.naming.Context;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Phase 0 spike #1b — <b>does the engine run a policy we deployed over LDAP?</b>
 *
 * <p>Two independent observations per submit, so the answer doesn't hinge on what
 * the extended-op response carries:
 * <ul>
 *   <li><b>direct eDir write</b> — the policy's rule does
 *       {@code do-set-dest-attr-value direct="true"} of a marker onto a scratch OU
 *       we create; we read it back over LDAP. If the marker lands, the policy ran.</li>
 *   <li><b>status in the response</b> — the rule also emits {@code do-status} with
 *       the marker, then {@code do-veto}s so nothing else happens.</li>
 * </ul>
 * The driver's filter is replaced by a permissive one for the run (original saved
 * and restored) so an empty filter can't drop the event first. Policy is linked into
 * sets 1/2/4/5. Every write is undone in {@code finally}. Test vault only.
 */
public final class EnginePickupSpike {

    private static final String MARK_V1 = "dirxmldev-spike-v1";
    private static final String MARK_V2 = "dirxmldev-spike-v2";
    private static final int[] LINK_SETS = {1, 2, 4, 5};
    private static final int MAX_CHUNK = 65536;

    private static final String PERMISSIVE_FILTER =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><filter>"
        + "<filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
        + "<filter-attr attr-name=\"Surname\" publisher=\"sync\" subscriber=\"sync\"/>"
        + "</filter-class></filter>";

    /**
     * The policy, by {@code -Dspike.mode}:
     * <ul>
     *   <li>{@code status} (default) — emit a {@code do-status} marker only. No veto (a
     *       veto in the input transform discards the op <em>and</em> its statuses,
     *       emptying the output), no direct write. Loads cleanly; tells us whether the
     *       response carries the channel's output.</li>
     *   <li>{@code direct} — additionally {@code do-set-dest-attr-value direct="true"}
     *       the marker onto the scratch OU, then veto. (Observed: the shim pushes a
     *       document through the channel at startup, this fires on it, and the direct
     *       write throws — aborting driver start.)</li>
     * </ul>
     */
    private static String policy(String marker, String targetSlashDn) {
        boolean direct = "direct".equals(System.getProperty("spike.mode", "status"));
        StringBuilder sb = new StringBuilder("<policy><rule><description>dirxmldev spike</description><conditions/><actions>");
        if (direct) {
            sb.append("<do-set-dest-attr-value direct=\"true\" name=\"Description\">")
              .append("<arg-dn><token-text>").append(targetSlashDn).append("</token-text></arg-dn>")
              .append("<arg-value type=\"string\"><token-text>").append(marker).append("</token-text></arg-value>")
              .append("</do-set-dest-attr-value>");
        }
        sb.append("<do-status level=\"warning\"><arg-string><token-text>").append(marker)
          .append("</token-text></arg-string></do-status>");
        if (direct) {
            sb.append("<do-veto/>");
        }
        return sb.append("</actions></rule></policy>").toString();
    }

    private static final String EVENT =
        "<nds dtdversion=\"4.0\"><input>"
        + "<modify class-name=\"User\" src-dn=\"dirxmldev-spike-src\" event-id=\"dirxmldev-spike\">"
        + "<association>dirxmldev-spike-assoc</association>"
        + "<modify-attr attr-name=\"Surname\"><add-value><value>X</value></add-value></modify-attr>"
        + "</modify></input></nds>";

    public static void main(String[] args) throws Exception {
        String host = need("spike.host");
        int port = Integer.parseInt(System.getProperty("spike.port", "636"));
        String bindDn = need("spike.bindDn");
        String password = need("spike.password");
        String driverSetDn = need("spike.driverSetDn");
        String driverDn = need("spike.driverDn");
        String tree = need("spike.tree");
        String targetParent = System.getProperty("spike.targetParent", "o=system");
        int submitVersion = Integer.parseInt(System.getProperty("spike.submitVersion", "1"));

        long ts = System.currentTimeMillis();
        String policyDn = "cn=dirxmldev-pickup-" + ts + ",cn=Library," + driverSetDn;
        String targetOu = "dirxmldev-target-" + ts;
        String targetDn = "ou=" + targetOu + "," + targetParent;
        String targetSlash = "\\" + tree + "\\" + ldapToSlashPath(targetParent) + "\\" + targetOu;
        List<String> links = new ArrayList<>();
        for (int set : LINK_SETS) {
            links.add(policyDn + "#0#" + set);
        }

        DirContext ldap = jndi("ldaps://" + host + ":" + port, bindDn, password);
        LDAPConnection ops = novell(host, port, bindDn, password);
        GetDriverStateResponse.register();
        SubmitEventResponse.register();
        GetChunkedResultResponse.register();

        byte[] originalFilter = null;
        String originalTraceLevel = null, originalTraceFile = null;
        boolean filterSet = false, traceSet = false, targetCreated = false, created = false, linked = false, started = false;
        try {
            int st = state(ops, driverDn);
            System.out.println("driver state before: " + name(st));
            if (st != 0) {
                throw new IllegalStateException("driver must be STOPPED (state=" + st + ")");
            }

            // 0. (opt-in) permissive filter — the input transform (set 1) runs before the
            // publisher filter, so the direct-write marker lands even with an empty
            // filter; replacing the filter was the one variable in the run where the
            // driver failed to start, so it's off unless -Dspike.setFilter=true.
            if (Boolean.getBoolean("spike.setFilter")) {
                Attribute f = ldap.getAttributes(driverDn, new String[]{"DirXML-DriverFilter"}).get("DirXML-DriverFilter");
                originalFilter = f == null ? null : toBytes(f.get());
                ldap.modifyAttributes(driverDn, new ModificationItem[]{new ModificationItem(
                    DirContext.REPLACE_ATTRIBUTE,
                    new BasicAttribute("DirXML-DriverFilter", PERMISSIVE_FILTER.getBytes(StandardCharsets.UTF_8)))});
                filterSet = true;
                System.out.println("OK   filter set to permissive (original saved, "
                    + (originalFilter == null ? "absent" : originalFilter.length + " bytes") + ")");
            }

            // 0a. (opt-in) per-driver trace so the run is observable on the server:
            // -Dspike.traceFile=/path sets DirXML-TraceLevel=3 + DirXML-TraceFile
            // on the driver (originals saved and restored in finally).
            String traceFile = System.getProperty("spike.traceFile");
            if (traceFile != null && !traceFile.isBlank()) {
                Attributes t = ldap.getAttributes(driverDn, new String[]{"DirXML-TraceLevel", "DirXML-TraceFile"});
                originalTraceLevel = t.get("DirXML-TraceLevel") == null ? null : String.valueOf(t.get("DirXML-TraceLevel").get());
                originalTraceFile = t.get("DirXML-TraceFile") == null ? null : String.valueOf(t.get("DirXML-TraceFile").get());
                ldap.modifyAttributes(driverDn, new ModificationItem[]{
                    new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("DirXML-TraceLevel", "3")),
                    new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("DirXML-TraceFile", traceFile))});
                traceSet = true;
                System.out.println("OK   trace level 3 -> " + traceFile + " (originals: level="
                    + originalTraceLevel + ", file=" + originalTraceFile + ")");
            }

            // 0b. scratch target OU the policy will write to
            Attributes ou = new BasicAttributes(true);
            BasicAttribute ouc = new BasicAttribute("objectClass");
            ouc.add("Top");
            ouc.add("organizationalUnit");
            ou.put(ouc);
            ou.put("ou", targetOu);
            ldap.createSubcontext(targetDn, ou);
            targetCreated = true;
            System.out.println("OK   created target " + targetDn + "  (slash: " + targetSlash + ")");

            // 1. scratch policy
            Attributes attrs = new BasicAttributes(true);
            BasicAttribute oc = new BasicAttribute("objectClass");
            oc.add("Top");
            oc.add("DirXML-Rule");
            attrs.put(oc);
            attrs.put("cn", policyDn.substring(3, policyDn.indexOf(',')));
            attrs.put("XmlData", policy(MARK_V1, targetSlash).getBytes(StandardCharsets.UTF_8));
            ldap.createSubcontext(policyDn, attrs);
            created = true;
            System.out.println("OK   created " + policyDn);

            // 2. link
            BasicAttribute link = new BasicAttribute("DirXML-Policies");
            links.forEach(link::add);
            ldap.modifyAttributes(driverDn, new ModificationItem[]{new ModificationItem(DirContext.ADD_ATTRIBUTE, link)});
            linked = true;
            System.out.println("OK   linked into sets " + java.util.Arrays.toString(LINK_SETS));

            // 3. start + submit
            ops.extendedOperation(new StartDriverRequest(driverDn));
            started = true;
            waitFor(ops, driverDn, 2, 60);
            System.out.println("OK   driver running");
            String r1 = submit(ops, driverDn, submitVersion);
            Thread.sleep(2000);
            String d1 = description(ldap, targetDn);
            verdict("policy RAN (direct eDir write of marker v1 observed)", MARK_V1.equals(d1), "Description=" + d1);
            verdict("response carried the status marker v1", r1.contains(MARK_V1), r1);

            // 4. modify policy in place, submit WITHOUT restart
            ldap.modifyAttributes(policyDn, new ModificationItem[]{new ModificationItem(
                DirContext.REPLACE_ATTRIBUTE,
                new BasicAttribute("XmlData", policy(MARK_V2, targetSlash).getBytes(StandardCharsets.UTF_8)))});
            System.out.println("OK   modified policy -> v2 (no restart)");
            submit(ops, driverDn, submitVersion);
            Thread.sleep(2000);
            String d2 = description(ldap, targetDn);
            verdict("engine re-read the modified policy WITHOUT restart (marker v2 written)", MARK_V2.equals(d2), "Description=" + d2);

            // 5. restart, submit again
            ops.extendedOperation(new RestartDriverRequest(driverDn));
            waitFor(ops, driverDn, 2, 90);
            System.out.println("OK   driver restarted and running");
            String r3 = submit(ops, driverDn, submitVersion);
            Thread.sleep(2000);
            String d3 = description(ldap, targetDn);
            verdict("engine runs the modified policy AFTER RestartDriver (marker v2 written)", MARK_V2.equals(d3), "Description=" + d3);
            verdict("response after restart carried status v2", r3.contains(MARK_V2), r3);
        } finally {
            if (started) {
                try {
                    ops.extendedOperation(new StopDriverRequest(driverDn));
                    waitFor(ops, driverDn, 0, 150);
                    System.out.println("OK   driver stopped");
                } catch (Exception e) {
                    System.out.println("FAIL stop -> " + e.getMessage() + "   *** stop it manually ***");
                }
            }
            if (linked) {
                cleanup("unlink", () -> {
                    BasicAttribute link = new BasicAttribute("DirXML-Policies");
                    links.forEach(link::add);
                    ldap.modifyAttributes(driverDn, new ModificationItem[]{new ModificationItem(DirContext.REMOVE_ATTRIBUTE, link)});
                });
            }
            if (filterSet) {
                byte[] orig = originalFilter;
                cleanup("restore filter", () -> ldap.modifyAttributes(driverDn, new ModificationItem[]{
                    orig == null
                        ? new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute("DirXML-DriverFilter"))
                        : new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("DirXML-DriverFilter", orig))}));
            }
            if (traceSet) {
                String lvl = originalTraceLevel, file = originalTraceFile;
                cleanup("restore trace settings", () -> ldap.modifyAttributes(driverDn, new ModificationItem[]{
                    lvl == null
                        ? new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute("DirXML-TraceLevel"))
                        : new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("DirXML-TraceLevel", lvl)),
                    file == null
                        ? new ModificationItem(DirContext.REMOVE_ATTRIBUTE, new BasicAttribute("DirXML-TraceFile"))
                        : new ModificationItem(DirContext.REPLACE_ATTRIBUTE, new BasicAttribute("DirXML-TraceFile", file))}));
            }
            if (created) {
                cleanup("delete policy", () -> ldap.destroySubcontext(policyDn));
            }
            if (targetCreated) {
                cleanup("delete target OU", () -> ldap.destroySubcontext(targetDn));
            }
            try {
                ops.disconnect();
            } catch (Exception ignored) {
                // done
            }
            ldap.close();
        }
    }

    // ---- observations ----

    private static String description(DirContext ldap, String dn) throws Exception {
        Attribute a = ldap.getAttributes(dn, new String[]{"description"}).get("description");
        return a == null ? null : String.valueOf(a.get());
    }

    // ---- engine ops ----

    private static int state(LDAPConnection ops, String driverDn) throws Exception {
        return ((GetDriverStateResponse) ops.extendedOperation(new GetDriverStateRequest(driverDn))).getDriverState();
    }

    private static void waitFor(LDAPConnection ops, String driverDn, int want, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int st = -1;
        StringBuilder seen = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            int now = state(ops, driverDn);
            if (now != st) {
                seen.append(seen.length() == 0 ? "" : " -> ").append(name(now));
                st = now;
            }
            if (st == want) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("driver did not reach " + name(want) + " within " + seconds
            + "s (states seen: " + seen + ")");
    }

    private static String submit(LDAPConnection ops, String driverDn, int version) throws Exception {
        SubmitEventResponse resp = (SubmitEventResponse) ops.extendedOperation(
            new SubmitEventRequest(driverDn, version, EVENT.getBytes(StandardCharsets.UTF_8)));
        int handle = resp.getDataHandle();
        int size = resp.getDataSize();
        if (handle == 0 || size == 0) {
            return "(empty result: handle=" + handle + " size=" + size + ")";
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
        int remaining = size;
        while (remaining > 0) {
            byte[] chunk = ((GetChunkedResultResponse) ops.extendedOperation(
                new GetChunkedResultRequest(handle, Math.min(remaining, MAX_CHUNK), 0))).getData();
            if (chunk == null || chunk.length == 0) {
                break;
            }
            baos.write(chunk, 0, chunk.length);
            remaining -= chunk.length;
        }
        ops.extendedOperation(new CloseChunkedResultRequest(handle));
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- helpers ----

    private interface Op {
        void run() throws Exception;
    }

    private static void cleanup(String what, Op op) {
        try {
            op.run();
            System.out.println("OK   " + what);
        } catch (Exception e) {
            System.out.println("FAIL " + what + " -> " + e.getMessage() + "   *** do it manually ***");
        }
    }

    /** "ou=a,o=b" -> "b\a" (slash form, root first, no tree). */
    private static String ldapToSlashPath(String ldapDn) {
        String[] parts = ldapDn.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            if (sb.length() > 0) {
                sb.append('\\');
            }
            sb.append(eq >= 0 ? p.substring(eq + 1) : p);
        }
        return sb.toString();
    }

    private static byte[] toBytes(Object v) {
        return v instanceof byte[] ? (byte[]) v : String.valueOf(v).getBytes(StandardCharsets.UTF_8);
    }

    private static String name(int st) {
        switch (st) {
            case 0: return "stopped";
            case 1: return "starting";
            case 2: return "running";
            case 3: return "stopping";
            default: return "state " + st;
        }
    }

    private static void verdict(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        System.out.println("     " + (detail.length() > 500 ? detail.substring(0, 500) + "…" : detail));
    }

    // ---- connections ----

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] c, String a) { }
        public void checkServerTrusted(X509Certificate[] c, String a) { }
    };

    private static LDAPConnection novell(String host, int port, String bindDn, String password) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{TRUST_ALL}, new java.security.SecureRandom());
        LDAPConnection conn = new LDAPConnection(new LDAPJSSESecureSocketFactory(ctx.getSocketFactory()));
        conn.connect(host, port);
        conn.bind(LDAPConnection.LDAP_V3, bindDn, password.getBytes(StandardCharsets.UTF_8));
        return conn;
    }

    private static DirContext jndi(String url, String bindDn, String password) throws Exception {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_PRINCIPAL, bindDn);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put("java.naming.ldap.attributes.binary", "XmlData DirXML-DriverFilter");
        env.put("java.naming.ldap.factory.socket", "com.pointblue.dirxml.sim.TrustAllSocketFactory");
        return new InitialDirContext(env);
    }

    private static String need(String prop) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing -D" + prop);
        }
        return v;
    }
}
