package com.pointblue.dirxml.dev.deploy;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.novell.nds.dirxml.ldap.CloseChunkedResultRequest;
import com.novell.nds.dirxml.ldap.ChunkedResultResponseBase;
import com.novell.nds.dirxml.ldap.DeleteCacheEntriesRequest;
import com.novell.nds.dirxml.ldap.DriverResyncRequest;
import com.novell.nds.dirxml.ldap.GetChunkedResultRequest;
import com.novell.nds.dirxml.ldap.GetChunkedResultResponse;
import com.novell.nds.dirxml.ldap.GetDriverStartOptionRequest;
import com.novell.nds.dirxml.ldap.GetDriverStatsRequest;
import com.novell.nds.dirxml.ldap.GetDriverStatsResponse;
import com.novell.nds.dirxml.ldap.GetJvmStatsRequest;
import com.novell.nds.dirxml.ldap.GetJvmStatsResponse;
import com.novell.nds.dirxml.ldap.GetVersionRequest;
import com.novell.nds.dirxml.ldap.GetVersionResponse;
import com.novell.nds.dirxml.ldap.MigrateAppRequest;
import com.novell.nds.dirxml.ldap.QueueEventRequest;
import com.novell.nds.dirxml.ldap.SubmitCommandRequest;
import com.novell.nds.dirxml.ldap.SubmitCommandResponse;
import com.novell.nds.dirxml.ldap.SubmitEventRequest;
import com.novell.nds.dirxml.ldap.SubmitEventResponse;
import com.novell.nds.dirxml.ldap.ViewCacheEntriesRequest;
import com.novell.nds.dirxml.ldap.ViewCacheEntriesResponse;
import com.novell.nds.dirxml.ldap.GetDriverStartOptionResponse;
import com.novell.nds.dirxml.ldap.GetDriverStateRequest;
import com.novell.nds.dirxml.ldap.GetDriverStateResponse;
import com.novell.nds.dirxml.ldap.ListNamedPasswordsRequest;
import com.novell.nds.dirxml.ldap.ListNamedPasswordsResponse;
import com.novell.nds.dirxml.ldap.RemoveNamedPasswordRequest;
import com.novell.nds.dirxml.ldap.RestartDriverRequest;
import com.novell.nds.dirxml.ldap.SetDriverStartOptionRequest;
import com.novell.nds.dirxml.ldap.SetNamedPasswordRequest;
import com.novell.nds.dirxml.ldap.StartDriverRequest;
import com.novell.nds.dirxml.ldap.StopDriverRequest;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The vault side of a deploy: LDAP reads and writes of DirXML objects (JNDI,
 * with the XML-bearing attributes handled as bytes so content round-trips
 * exactly — spike 1), and the DirXML extended operations (Novell JLDAP —
 * driver state / start / stop / restart / start option, named passwords).
 *
 * <p>Everything here is a primitive: one LDAP or extended operation per call,
 * no policy about what to write or when. The plan and the deployer own that.
 * Nothing is retried silently; an {@code LDAPException} / {@code NamingException}
 * surfaces as a {@link VaultException} with the DN and operation in the message.
 */
public final class Vault implements AutoCloseable {

    /** Attributes read and written as raw bytes. Names are matched case-insensitively by the server. */
    public static final List<String> BINARY_ATTRS = List.of(
        "XmlData", "DirXML-Data", "DirXML-ConfigValues", "DirXML-ShimConfigInfo", "DirXML-DriverFilter",
        "DirXML-EngineControlValues", "DirXML-pkgInitialState", "DirXML-pkgExtensions");

    public static final int STATE_STOPPED = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_STOPPING = 3;

    public static final int START_DISABLED = 0;
    public static final int START_MANUAL = 1;
    public static final int START_AUTO = 2;

    public static final class Config {
        public String url;          // ldaps://host:636
        public String bindDn;
        public String password;
        public boolean trustAll = true;
    }

    /** One LDAP entry: DN, object classes, and every attribute as byte values. */
    public static final class Entry {
        public final String dn;
        public final Map<String, List<byte[]>> attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        Entry(String dn) {
            this.dn = dn;
        }

        public List<String> objectClasses() {
            return strings("objectClass");
        }

        public boolean hasClass(String oc) {
            for (String c : objectClasses()) {
                if (c.equalsIgnoreCase(oc)) {
                    return true;
                }
            }
            return false;
        }

        public String string(String attr) {
            List<byte[]> v = attrs.get(attr);
            return v == null || v.isEmpty() ? null : new String(v.get(0), StandardCharsets.UTF_8);
        }

        public List<String> strings(String attr) {
            List<byte[]> v = attrs.get(attr);
            if (v == null) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>();
            for (byte[] b : v) {
                out.add(new String(b, StandardCharsets.UTF_8));
            }
            return out;
        }

        public byte[] bytes(String attr) {
            List<byte[]> v = attrs.get(attr);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
    }

    public static final class VaultException extends RuntimeException {
        public VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Config config;
    private final DirContext ldap;
    private LDAPConnection ops;   // lazily connected: only deploys that touch drivers need it

    private Vault(Config config, DirContext ldap) {
        this.config = config;
        this.ldap = ldap;
    }

    public static Vault connect(Config c) {
        try {
            Hashtable<String, Object> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, c.url);
            env.put(Context.SECURITY_PRINCIPAL, c.bindDn);
            env.put(Context.SECURITY_CREDENTIALS, c.password);
            env.put("java.naming.ldap.attributes.binary", String.join(" ", BINARY_ATTRS));
            if (c.url.startsWith("ldaps") && c.trustAll) {
                env.put("java.naming.ldap.factory.socket", "com.pointblue.dirxml.sim.TrustAllSocketFactory");
            }
            return new Vault(c, new InitialDirContext(env));
        } catch (Exception e) {
            throw new VaultException("connect " + c.url + " as " + c.bindDn + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            ldap.close();
        } catch (Exception ignored) {
            // closing
        }
        if (ops != null) {
            try {
                ops.disconnect();
            } catch (Exception ignored) {
                // closing
            }
        }
    }

    // ---- reads --------------------------------------------------------------------

    /** The entry with every attribute, or null if the DN doesn't exist. */
    public Entry read(String dn) {
        try {
            Attributes a = ldap.getAttributes(dn);
            return toEntry(dn, a);
        } catch (NameNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new VaultException("read " + dn + ": " + e.getMessage(), e);
        }
    }

    public boolean exists(String dn) {
        return read(dn) != null;
    }

    /** One-level children of {@code base} (all attributes). */
    public List<Entry> children(String base) {
        return search(base, "(objectClass=*)", SearchControls.ONELEVEL_SCOPE);
    }

    public List<Entry> search(String base, String filter, int scope) {
        List<Entry> out = new ArrayList<>();
        try {
            SearchControls sc = new SearchControls();
            sc.setSearchScope(scope);
            sc.setReturningAttributes(new String[] {"*"});
            NamingEnumeration<SearchResult> results = ldap.search(base, filter, sc);
            while (results.hasMore()) {
                SearchResult r = results.next();
                out.add(toEntry(r.getNameInNamespace(), r.getAttributes()));
            }
        } catch (NameNotFoundException e) {
            return out;
        } catch (Exception e) {
            throw new VaultException("search " + base + " " + filter + ": " + e.getMessage(), e);
        }
        return out;
    }

    private static Entry toEntry(String dn, Attributes a) throws Exception {
        Entry e = new Entry(dn);
        for (NamingEnumeration<? extends Attribute> ids = a.getAll(); ids.hasMore(); ) {
            Attribute at = ids.next();
            List<byte[]> values = new ArrayList<>();
            for (NamingEnumeration<?> vs = at.getAll(); vs.hasMore(); ) {
                Object v = vs.next();
                values.add(v instanceof byte[] ? (byte[]) v : String.valueOf(v).getBytes(StandardCharsets.UTF_8));
            }
            e.attrs.put(at.getID(), values);
        }
        return e;
    }

    // ---- writes -------------------------------------------------------------------

    /** Create an entry. Values are bytes; string attributes are UTF-8. */
    public void add(String dn, List<String> objectClasses, Map<String, List<byte[]>> attrs) {
        try {
            BasicAttributes a = new BasicAttributes(true);
            BasicAttribute oc = new BasicAttribute("objectClass");
            for (String c : objectClasses) {
                oc.add(c);
            }
            a.put(oc);
            for (Map.Entry<String, List<byte[]>> at : attrs.entrySet()) {
                BasicAttribute b = new BasicAttribute(at.getKey());
                for (byte[] v : at.getValue()) {
                    b.add(isBinary(at.getKey()) ? v : new String(v, StandardCharsets.UTF_8));
                }
                a.put(b);
            }
            ldap.createSubcontext(dn, a);
        } catch (Exception e) {
            throw new VaultException("add " + dn + ": " + e.getMessage(), e);
        }
    }

    /** Replace an attribute's values (an empty list removes the attribute). */
    public void replace(String dn, String attr, List<byte[]> values) {
        try {
            BasicAttribute b = new BasicAttribute(attr);
            for (byte[] v : values) {
                b.add(isBinary(attr) ? v : new String(v, StandardCharsets.UTF_8));
            }
            ModificationItem[] mods = {new ModificationItem(
                values.isEmpty() ? DirContext.REMOVE_ATTRIBUTE : DirContext.REPLACE_ATTRIBUTE, b)};
            ldap.modifyAttributes(dn, mods);
        } catch (Exception e) {
            throw new VaultException("modify " + dn + " " + attr + ": " + e.getMessage(), e);
        }
    }

    public void replace(String dn, String attr, String value) {
        replace(dn, attr, List.of(value.getBytes(StandardCharsets.UTF_8)));
    }

    public void replaceAll(String dn, String attr, List<String> values) {
        List<byte[]> b = new ArrayList<>();
        for (String v : values) {
            b.add(v.getBytes(StandardCharsets.UTF_8));
        }
        replace(dn, attr, b);
    }

    /** Delete a leaf entry. */
    public void delete(String dn) {
        try {
            ldap.destroySubcontext(dn);
        } catch (Exception e) {
            throw new VaultException("delete " + dn + ": " + e.getMessage(), e);
        }
    }

    static boolean isBinary(String attr) {
        for (String b : BINARY_ATTRS) {
            if (b.equalsIgnoreCase(attr)) {
                return true;
            }
        }
        return false;
    }

    // ---- extended operations --------------------------------------------------------

    private LDAPConnection ops() {
        if (ops == null) {
            try {
                GetDriverStateResponse.register();
                GetDriverStartOptionResponse.register();
                ListNamedPasswordsResponse.register();
                ViewCacheEntriesResponse.register();
                GetChunkedResultResponse.register();
                GetVersionResponse.register();
                GetDriverStatsResponse.register();
                GetJvmStatsResponse.register();
                SubmitEventResponse.register();
                SubmitCommandResponse.register();
                URI u = URI.create(config.url);
                int port = u.getPort() > 0 ? u.getPort() : ("ldaps".equals(u.getScheme()) ? 636 : 389);
                LDAPConnection conn;
                if ("ldaps".equals(u.getScheme())) {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, config.trustAll ? new TrustManager[] {TRUST_ALL} : null, new java.security.SecureRandom());
                    conn = new LDAPConnection(new LDAPJSSESecureSocketFactory(ctx.getSocketFactory()));
                } else {
                    conn = new LDAPConnection();
                }
                conn.connect(u.getHost(), port);
                conn.bind(LDAPConnection.LDAP_V3, config.bindDn, config.password.getBytes(StandardCharsets.UTF_8));
                ops = conn;
            } catch (Exception e) {
                throw new VaultException("extended-op connection to " + config.url + ": " + e.getMessage(), e);
            }
        }
        return ops;
    }

    public int driverState(String driverDn) {
        try {
            return ((GetDriverStateResponse) ops().extendedOperation(new GetDriverStateRequest(driverDn))).getDriverState();
        } catch (Exception e) {
            throw new VaultException("GetDriverState " + driverDn + ": " + e.getMessage(), e);
        }
    }

    public static String stateName(int s) {
        switch (s) {
            case STATE_STOPPED: return "stopped";
            case STATE_STARTING: return "starting";
            case STATE_RUNNING: return "running";
            case STATE_STOPPING: return "stopping";
            default: return "state-" + s;
        }
    }

    public void startDriver(String driverDn) {
        extOp("StartDriver", driverDn, () -> ops().extendedOperation(new StartDriverRequest(driverDn)));
    }

    public void stopDriver(String driverDn) {
        extOp("StopDriver", driverDn, () -> ops().extendedOperation(new StopDriverRequest(driverDn)));
    }

    public void restartDriver(String driverDn) {
        extOp("RestartDriver", driverDn, () -> ops().extendedOperation(new RestartDriverRequest(driverDn)));
    }

    public int driverStartOption(String driverDn) {
        try {
            return ((GetDriverStartOptionResponse) ops().extendedOperation(new GetDriverStartOptionRequest(driverDn))).getDriverStartOption();
        } catch (Exception e) {
            throw new VaultException("GetDriverStartOption " + driverDn + ": " + e.getMessage(), e);
        }
    }

    public void setDriverStartOption(String driverDn, int option) {
        extOp("SetDriverStartOption", driverDn, () -> ops().extendedOperation(new SetDriverStartOptionRequest(driverDn, option, false)));
    }

    /**
     * Poll until the driver reaches {@code wanted} (or, when {@code wanted} is
     * RUNNING and the driver drops back to STOPPED after STARTING, fail early:
     * the engine refused to start it). Returns the states seen, for the log.
     */
    public String waitForState(String driverDn, int wanted, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int last = -1;
        boolean sawStarting = false;
        StringBuilder seen = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            int now = driverState(driverDn);
            if (now != last) {
                seen.append(seen.length() == 0 ? "" : " → ").append(stateName(now));
                last = now;
            }
            if (now == wanted) {
                return seen.toString();
            }
            if (now == STATE_STARTING) {
                sawStarting = true;
            } else if (wanted == STATE_RUNNING && sawStarting && now == STATE_STOPPED) {
                throw new VaultException("driver " + driverDn + " started and stopped again (" + seen
                    + ") — the engine refused it; check the driver trace", null);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new VaultException("driver " + driverDn + " did not reach " + stateName(wanted) + " within " + seconds
            + "s (states seen: " + seen + ")", null);
    }

    // ---- operate: cache, events, stats, trace ----------------------------------------

    public static final String TRACE_LEVEL = "DirXML-TraceLevel";
    public static final String TRACE_FILE = "DirXML-TraceFile";
    private static final int MAX_CHUNK = 64512;   // dxcmd's chunk size

    /** One page of a driver's event cache. */
    public static final class CachePage {
        public final String xds;          // "" when empty
        public final int nextToken;
        public final boolean empty;

        CachePage(String xds, int nextToken) {
            this.xds = xds;
            this.nextToken = nextToken;
            this.empty = xds.isEmpty();
        }
    }

    /** Read up to {@code count} cached events from {@code position} (0 = first). Works on a running driver too. */
    public CachePage viewCache(String driverDn, int position, int count) {
        try {
            ViewCacheEntriesResponse resp = (ViewCacheEntriesResponse) ops().extendedOperation(
                new ViewCacheEntriesRequest(driverDn, 1, position, count, 0));
            int next = resp.getPositionToken();
            if (resp.getDataHandle() == 0 || resp.getDataSize() == 0) {
                return new CachePage("", next);
            }
            return new CachePage(new String(chunked(resp), StandardCharsets.UTF_8), next);
        } catch (Exception e) {
            throw new VaultException("ViewCacheEntries " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /** Raw DeleteCacheEntries (parameter semantics settled by spike 5a). */
    public void deleteCacheEntries(String driverDn, int p2, int p3, String p4, int priority) {
        extOp("DeleteCacheEntries", driverDn, () -> ops().extendedOperation(new DeleteCacheEntriesRequest(driverDn, p2, p3, p4, priority)));
    }

    /** Queue an XDS event into the driver's subscriber cache (no result document). */
    public void queueEvent(String driverDn, byte[] xds) {
        extOp("QueueEvent", driverDn, () -> ops().extendedOperation(new QueueEventRequest(driverDn, xds)));
    }

    /** Submit an XDS document to a running driver's publisher channel; returns the result document (may be empty). */
    public String submitEvent(String driverDn, byte[] xds) {
        try {
            ChunkedResultResponseBase resp = (ChunkedResultResponseBase) ops().extendedOperation(new SubmitEventRequest(driverDn, 1, xds));
            return resp.getDataHandle() == 0 || resp.getDataSize() == 0 ? "" : new String(chunked(resp), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VaultException("SubmitEvent " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /** Submit an XDS command to a running driver's subscriber channel; returns the result document (may be empty). */
    public String submitCommand(String driverDn, byte[] xds) {
        try {
            ChunkedResultResponseBase resp = (ChunkedResultResponseBase) ops().extendedOperation(new SubmitCommandRequest(driverDn, 1, xds));
            return resp.getDataHandle() == 0 || resp.getDataSize() == 0 ? "" : new String(chunked(resp), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VaultException("SubmitCommand " + driverDn + ": " + e.getMessage(), e);
        }
    }

    public void migrateApp(String driverDn, byte[] xds) {
        extOp("MigrateApp", driverDn, () -> ops().extendedOperation(new MigrateAppRequest(driverDn, xds)));
    }

    /** Resync events since {@code since} (epoch millis; 0 = full resync). */
    public void resync(String driverDn, long sinceMillis) {
        extOp("DriverResync", driverDn, () -> ops().extendedOperation(new DriverResyncRequest(driverDn, new java.util.Date(sinceMillis))));
    }

    /** The engine version as the packed int the engine reports. */
    public int engineVersion() {
        try {
            return ((GetVersionResponse) ops().extendedOperation(new GetVersionRequest())).getVersion();
        } catch (Exception e) {
            throw new VaultException("GetVersion: " + e.getMessage(), e);
        }
    }

    /** The driver's statistics document (XML), or "" when the engine returns none. */
    public String driverStats(String driverDn, int arg) {
        try {
            ChunkedResultResponseBase resp = (ChunkedResultResponseBase) ops().extendedOperation(new GetDriverStatsRequest(driverDn, arg));
            return resp.getDataHandle() == 0 || resp.getDataSize() == 0 ? "" : new String(chunked(resp), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VaultException("GetDriverStats " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /** The engine JVM's statistics document (XML), or "" when none. */
    public String jvmStats(int a, int b) {
        try {
            ChunkedResultResponseBase resp = (ChunkedResultResponseBase) ops().extendedOperation(new GetJvmStatsRequest(a, b));
            return resp.getDataHandle() == 0 || resp.getDataSize() == 0 ? "" : new String(chunked(resp), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VaultException("GetJvmStats: " + e.getMessage(), e);
        }
    }

    private byte[] chunked(ChunkedResultResponseBase resp) throws Exception {
        int handle = resp.getDataHandle();
        int size = resp.getDataSize();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(size);
        int remaining = size;
        while (remaining > 0) {
            byte[] reply = ((GetChunkedResultResponse) ops().extendedOperation(
                new GetChunkedResultRequest(handle, Math.min(remaining, MAX_CHUNK), 0))).getData();
            if (reply == null || reply.length == 0) {
                break;
            }
            out.write(reply, 0, reply.length);
            remaining -= reply.length;
        }
        ops().extendedOperation(new CloseChunkedResultRequest(handle));
        return out.toByteArray();
    }

    // ---- named passwords -------------------------------------------------------------

    /** Names of the named passwords set on a driver (or driver set). */
    @SuppressWarnings("unchecked")
    public List<String> namedPasswords(String dn) {
        try {
            ListNamedPasswordsResponse r = (ListNamedPasswordsResponse) ops().extendedOperation(new ListNamedPasswordsRequest(dn));
            // each entry is a String[] {name, displayName}; the name is what we key on
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) r.getList()) {
                if (o instanceof String[]) {
                    String[] pair = (String[]) o;
                    out.add(pair.length > 0 ? pair[0] : "");
                } else {
                    out.add(String.valueOf(o));
                }
            }
            return out;
        } catch (Exception e) {
            throw new VaultException("ListNamedPasswords " + dn + ": " + e.getMessage(), e);
        }
    }

    public void setNamedPassword(String dn, String name, String displayName, char[] value) {
        SetNamedPasswordRequest req = null;
        try {
            req = new SetNamedPasswordRequest(dn, name, displayName == null ? name : displayName, value);
            ops().extendedOperation(req);
        } catch (Exception e) {
            throw new VaultException("SetNamedPassword " + dn + " '" + name + "': " + e.getMessage(), e);
        } finally {
            if (req != null) {
                req.zero();
            }
            Arrays.fill(value, '\0');
        }
    }

    public void removeNamedPassword(String dn, String name) {
        extOp("RemoveNamedPassword '" + name + "'", dn, () -> ops().extendedOperation(new RemoveNamedPasswordRequest(dn, name)));
    }

    // ---- helpers ------------------------------------------------------------------

    private interface Op {
        void run() throws Exception;
    }

    private static void extOp(String what, String dn, Op op) {
        try {
            op.run();
        } catch (Exception e) {
            throw new VaultException(what + " " + dn + ": " + e.getMessage(), e);
        }
    }

    /** A byte-valued attribute map builder. */
    public static Map<String, List<byte[]>> attrs() {
        return new LinkedHashMap<>();
    }

    public static List<byte[]> value(String s) {
        return List.of(s.getBytes(StandardCharsets.UTF_8));
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(X509Certificate[] c, String a) {
        }

        public void checkServerTrusted(X509Certificate[] c, String a) {
        }
    };
}
