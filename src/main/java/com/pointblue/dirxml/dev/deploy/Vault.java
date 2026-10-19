package com.pointblue.dirxml.dev.deploy;

import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPExtendedOperation;
import com.novell.ldap.LDAPExtendedResponse;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.pointblue.dirxml.dev.deploy.IdmOps.Op;
import com.pointblue.dirxml.dev.deploy.IdmOps.Reply;

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
public final class Vault implements VaultAccess {

    /** Attributes read and written as raw bytes. Names are matched case-insensitively by the server. */
    public static final List<String> BINARY_ATTRS = List.of(
        "XmlData", "DirXML-Data", "DirXML-ConfigValues", "DirXML-ShimConfigInfo", "DirXML-DriverFilter",
        "DirXML-EngineControlValues", "DirXML-pkgInitialState", "DirXML-pkgExtensions", "DirXML-DriverImage");

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
        public boolean trustAll = false;   // opt in: accept any certificate (a lab, a tunnel); verified otherwise
        /** The vault certificate's SHA-256 the environment trusts besides the JVM's roots ({@code <env>.certSha256}); null: none. */
        public String certSha256;
        /** Attributes beyond {@link #BINARY_ATTRS} to read as bytes (a clone marks every octet-string attribute of the source schema). */
        public java.util.Collection<String> binaryAttrs = List.of();

        /** The same credentials and trust for another URL (another server of the same tree). */
        public Config withUrl(String otherUrl) {
            Config c = new Config();
            c.url = otherUrl;
            c.bindDn = bindDn;
            c.password = password;
            c.trustAll = trustAll;
            c.certSha256 = certSha256;
            c.binaryAttrs = binaryAttrs;
            return c;
        }
    }

    /** One LDAP entry: DN, object classes, and every attribute as byte values. */
    public static final class Entry {
        public final String dn;
        public final Map<String, List<byte[]>> attrs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public Entry(String dn) {
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
    private boolean ownsLdap = true;   // false when the caller lent its own directory connection (open)

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
            List<String> binary = new ArrayList<>(BINARY_ATTRS);
            binary.addAll(c.binaryAttrs);
            env.put("java.naming.ldap.attributes.binary", String.join(" ", binary));
            // an unreachable vault answers in seconds, not after the OS's TCP timeout (a page checks sign-ins on load)
            env.put("com.sun.jndi.ldap.connect.timeout", "10000");
            com.pointblue.dirxml.dev.LegacyTls.enable();   // before the TLS stack initialises: eDirectory often offers static-RSA suites only
            if (c.url.startsWith("ldaps") && c.trustAll) {
                env.put("java.naming.ldap.factory.socket", TrustAllSockets.class.getName());
                // trust-all also means the certificate's name need not match the URL's host: a lab reached
                // through an SSH tunnel (127.0.0.1:6637) presents its own hostname's certificate, and JNDI's
                // LDAPS endpoint identification would otherwise turn that into "simple bind failed"
                System.setProperty("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");
            } else if (c.url.startsWith("ldaps")) {
                // the JVM's trust, or the environment's pinned certificate (PinnedTrust); a refusal names the certificate
                env.put("java.naming.ldap.factory.socket", PinnedSockets.class.getName());
                PinnedSockets.PIN.set(c.certSha256);
                try {
                    return new Vault(c, new InitialDirContext(env));
                } finally {
                    PinnedSockets.PIN.remove();
                }
            }
            return new Vault(c, new InitialDirContext(env));
        } catch (Exception e) {
            throw new VaultException("connect " + c.url + " as " + c.bindDn + ": " + e.getMessage(), e);
        }
    }

    /**
     * A vault over a directory connection the caller already holds, for a service that signs a person
     * in and must not keep their password (Directory Console). {@code ldap} is used as is and left open
     * by {@link #close}. The extended-operation connection is bound here, once, to {@code host}:{@code port}
     * through {@code sockets} (the caller's TLS trust), as {@code bindDn}; {@code password} is used for that
     * bind only and kept nowhere. Nothing is connected lazily afterwards.
     */
    public static Vault open(DirContext ldap, String host, int port, javax.net.ssl.SSLSocketFactory sockets, String bindDn, char[] password) {
        Config c = new Config();
        c.url = "ldaps://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        c.bindDn = bindDn;
        Vault v = new Vault(c, ldap);
        v.ownsLdap = false;
        try {
            LDAPConnection conn = new LDAPConnection(new LDAPJSSESecureSocketFactory(sockets));
            conn.connect(host, port);
            byte[] pw = new String(password).getBytes(StandardCharsets.UTF_8);
            try {
                conn.bind(LDAPConnection.LDAP_V3, bindDn, pw);
            } finally {
                java.util.Arrays.fill(pw, (byte) 0);
            }
            v.ops = conn;
        } catch (Exception e) {
            throw new VaultException("extended-op connection to " + host + ":" + port + " as " + bindDn + ": " + e.getMessage(), e);
        }
        return v;
    }

    @Override
    public void close() {
        if (ownsLdap) {
            try {
                ldap.close();
            } catch (Exception ignored) {
                // closing
            }
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

    @Override
    public Entry read(String dn, String... attrs) {
        try {
            Attributes a = ldap.getAttributes(dn, attrs);
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

    @Override
    public List<String> names(String base, String filter, int scope) {
        List<String> out = new ArrayList<>();
        try {
            SearchControls sc = new SearchControls();
            sc.setSearchScope(scope);
            sc.setReturningAttributes(new String[] {"1.1"});   // no attributes: nothing to encode
            NamingEnumeration<SearchResult> results = ldap.search(base, filter, sc);
            try {
                while (results.hasMore()) {
                    out.add(results.next().getNameInNamespace());
                }
            } finally {
                results.close();
            }
        } catch (NameNotFoundException e) {
            return out;
        } catch (Exception e) {
            throw new VaultException("search " + base + " " + filter + ": " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<String> attributeNames(String dn) {
        DirContext types = null;
        try {
            // a context of its own on the same connection, asking for attribute types only
            types = (DirContext) ldap.lookup("");
            types.addToEnvironment("java.naming.ldap.typesOnly", "true");
            List<String> out = new ArrayList<>();
            NamingEnumeration<String> ids = types.getAttributes(dn).getIDs();
            while (ids.hasMore()) {
                out.add(ids.next());
            }
            return out;
        } catch (NameNotFoundException e) {
            return List.of();
        } catch (Exception e) {
            throw new VaultException("read the attribute names of " + dn + ": " + e.getMessage(), e);
        } finally {
            if (types != null) {
                try {
                    types.close();
                } catch (Exception ignore) {
                    // the shared connection stays open
                }
            }
        }
    }

    /**
     * eDirectory's "invalid attribute syntax" (LDAP 21, NDS -325): a value the server can't return in
     * its attribute's syntax. One such value fails a whole search, naming only the search's base.
     */
    public static boolean invalidSyntax(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof javax.naming.directory.InvalidAttributeValueException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && (m.contains("(-325)") || m.contains("error code 21 "))) {
                return true;
            }
        }
        return false;
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
            try {
                while (results.hasMore()) {
                    SearchResult r = results.next();
                    out.add(toEntry(r.getNameInNamespace(), r.getAttributes()));
                }
            } finally {
                results.close();
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
        NamingEnumeration<? extends Attribute> ids = a.getAll();
        try {
            while (ids.hasMore()) {
                Attribute at = ids.next();
                List<byte[]> values = new ArrayList<>();
                NamingEnumeration<?> vs = at.getAll();
                try {
                    while (vs.hasMore()) {
                        Object v = vs.next();
                        values.add(v instanceof byte[] ? (byte[]) v : String.valueOf(v).getBytes(StandardCharsets.UTF_8));
                    }
                } finally {
                    vs.close();
                }
                e.attrs.put(at.getID(), values);
            }
        } finally {
            ids.close();
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

    /** Add auxiliary object classes to an existing entry (no-op for classes it already has). */
    public void addObjectClasses(String dn, List<String> classes) {
        try {
            Entry e = read(dn);
            if (e == null) {
                throw new VaultException("add object classes " + dn + ": no such object", null);
            }
            BasicAttribute oc = new BasicAttribute("objectClass");
            for (String c : classes) {
                if (!e.hasClass(c)) {
                    oc.add(c);
                }
            }
            if (oc.size() == 0) {
                return;
            }
            ldap.modifyAttributes(dn, javax.naming.directory.DirContext.ADD_ATTRIBUTE, new BasicAttributes(true) {{ put(oc); }});
        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultException("add object classes " + dn + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void removeObjectClasses(String dn, List<String> classes) {
        try {
            Entry e = read(dn);
            if (e == null) {
                throw new VaultException("remove object classes " + dn + ": no such object", null);
            }
            BasicAttribute oc = new BasicAttribute("objectClass");
            for (String c : classes) {
                if (e.hasClass(c)) {
                    oc.add(c);
                }
            }
            if (oc.size() == 0) {
                return;
            }
            ldap.modifyAttributes(dn, javax.naming.directory.DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(true) {{ put(oc); }});
        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultException("remove object classes " + dn + ": " + e.getMessage(), e);
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
        } catch (javax.naming.directory.NoSuchAttributeException e) {
            if (!values.isEmpty()) {
                throw new VaultException("modify " + dn + " " + attr + ": " + e.getMessage(), e);
            }
            // removing an attribute the object never had (a new driver's empty DirXML-Policies) is a no-op
        } catch (Exception e) {
            throw new VaultException("modify " + dn + " " + attr + ": " + e.getMessage(), e);
        }
    }

    /** Add values to an attribute (modify-add): the attribute is created when absent, existing values stay. */
    @Override
    public void addValues(String dn, String attr, List<byte[]> values) {
        if (values.isEmpty()) {
            return;
        }
        try {
            BasicAttribute b = new BasicAttribute(attr);
            for (byte[] v : values) {
                b.add(isBinary(attr) ? v : new String(v, StandardCharsets.UTF_8));
            }
            ldap.modifyAttributes(dn, new ModificationItem[] {new ModificationItem(DirContext.ADD_ATTRIBUTE, b)});
        } catch (Exception e) {
            throw new VaultException("modify-add " + dn + " " + attr + ": " + e.getMessage(), e);
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

    boolean isBinary(String attr) {
        for (String b : BINARY_ATTRS) {
            if (b.equalsIgnoreCase(attr)) {
                return true;
            }
        }
        for (String b : config.binaryAttrs) {
            if (b.equalsIgnoreCase(attr)) {
                return true;
            }
        }
        return false;
    }

    // ---- extended operations --------------------------------------------------------

    /**
     * One IDM extended operation ({@link IdmOps}, our own encoding with JLDAP's BER classes — no vendor
     * classes): its reply decoded. The request value, which can hold a password, is zeroed afterwards.
     */
    private Reply call(Op op, Object... items) throws Exception {
        LDAPExtendedOperation req = IdmOps.request(op, items);
        try {
            LDAPExtendedResponse resp = ops().extendedOperation(req);
            return IdmOps.decode(resp == null ? null : resp.getValue());
        } finally {
            byte[] v = req.getValue();
            if (v != null) {
                Arrays.fill(v, (byte) 0);
            }
        }
    }

    private LDAPConnection ops() {
        if (ops == null) {
            try {
                URI u = URI.create(config.url);
                int port = u.getPort() > 0 ? u.getPort() : ("ldaps".equals(u.getScheme()) ? 636 : 389);
                LDAPConnection conn;
                if ("ldaps".equals(u.getScheme())) {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[] {config.trustAll ? TRUST_ALL : new PinnedTrust(config.certSha256)}, new java.security.SecureRandom());
                    // the JNDI channel gets host-name checking from the JVM; a raw JSSE socket does not
                    // unless asked, so ask (trustAll opts out of both, as documented)
                    conn = new LDAPConnection(new LDAPJSSESecureSocketFactory(
                        config.trustAll ? ctx.getSocketFactory() : endpointChecking(ctx.getSocketFactory())));
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
            return call(Op.GET_DRIVER_STATE, driverDn).integer(0);
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
        extOp("StartDriver", driverDn, () -> call(Op.START_DRIVER, driverDn));
    }

    // ---- jobs (DirXML-Job; docs/console-gaps.md §1) ----

    /** {@code StartJob}: run the job now. */
    public void startJob(String jobDn) {
        extOp("StartJob", jobDn, () -> call(Op.START_JOB, jobDn));
    }

    /** {@code AbortJob}: stop a running job. */
    public void abortJob(String jobDn) {
        extOp("AbortJob", jobDn, () -> call(Op.ABORT_JOB, jobDn));
    }

    /** {@code NotifyJobUpdate}: tell the scheduler the job object changed. */
    @Override
    public void notifyJobUpdate(String jobDn) {
        extOp("NotifyJobUpdate", jobDn, () -> call(Op.NOTIFY_JOB_UPDATE, jobDn));
    }

    /** What the engine says about a job: {@code GetJobState} — running state, configuration state, whether it is scheduled and when it next runs. */
    public static final class JobState {
        public final int runningState;
        public final int configurationState;
        public final boolean scheduled;
        public final java.util.Date nextRun;

        public JobState(int runningState, int configurationState, boolean scheduled, java.util.Date nextRun) {
            this.runningState = runningState;
            this.configurationState = configurationState;
            this.scheduled = scheduled;
            this.nextRun = nextRun;
        }
    }

    public JobState jobState(String jobDn) {
        try {
            // running state, configuration state, then 0 or the next run (epoch seconds) — the same element
            Reply r = call(Op.GET_JOB_STATE, jobDn);
            int next = r.integer(2);
            return new JobState(r.integer(0), r.integer(1), next != 0, next != 0 ? new java.util.Date(next * 1000L) : null);
        } catch (Exception e) {
            throw new VaultException("GetJobState " + jobDn + ": " + e.getMessage(), e);
        }
    }

    public void stopDriver(String driverDn) {
        extOp("StopDriver", driverDn, () -> call(Op.STOP_DRIVER, driverDn));
    }

    public void restartDriver(String driverDn) {
        extOp("RestartDriver", driverDn, () -> call(Op.RESTART_DRIVER, driverDn));
    }

    public int driverStartOption(String driverDn) {
        try {
            return call(Op.GET_DRIVER_START_OPTION, driverDn).integer(0);
        } catch (Exception e) {
            throw new VaultException("GetDriverStartOption " + driverDn + ": " + e.getMessage(), e);
        }
    }

    public void setDriverStartOption(String driverDn, int option) {
        extOp("SetDriverStartOption", driverDn, () -> call(Op.SET_DRIVER_START_OPTION, driverDn, option, 0));
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
            // timeout 1, position, count, cache type 0 (×2 = view); the reply: handle, size, next position
            Reply resp = call(Op.VIEW_CACHE_ENTRIES, driverDn, 1, position, count, 0);
            int next = resp.integer(2);
            return new CachePage(chunkedText(resp), next);
        } catch (Exception e) {
            throw new VaultException("ViewCacheEntries " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /** Raw DeleteCacheEntries (parameter semantics settled by spike 5a). */
    public void deleteCacheEntries(String driverDn, int p2, int p3, String p4, int priority) {
        extOp("DeleteCacheEntries", driverDn, () -> call(Op.DELETE_CACHE_ENTRIES, driverDn, p2, p3, p4, priority * 2 + 1));
    }

    /** Queue an XDS event into the driver's subscriber cache (no result document). */
    public void queueEvent(String driverDn, byte[] xds) {
        extOp("QueueEvent", driverDn, () -> call(Op.QUEUE_EVENT, driverDn, xds));
    }

    /** Submit an XDS document to a running driver's publisher channel; returns the result document (may be empty). */
    public String submitEvent(String driverDn, byte[] xds) {
        try {
            return chunkedText(call(Op.SUBMIT_EVENT, driverDn, 1, xds));
        } catch (Exception e) {
            throw new VaultException("SubmitEvent " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /** Submit an XDS command to a running driver's subscriber channel; returns the result document (may be empty). */
    public String submitCommand(String driverDn, byte[] xds) {
        try {
            return chunkedText(call(Op.SUBMIT_COMMAND, driverDn, 1, xds));
        } catch (Exception e) {
            throw new VaultException("SubmitCommand " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /**
     * The application schema the shim reports. {@code DriverGetSchema} makes the engine ask the shim
     * (starting it for the question when the driver is stopped) and store the answer on the driver
     * object as {@code DirXML-ApplicationSchema} — the response itself carries nothing, as with
     * iManager's "Refresh Application Schema" — so that attribute is read afterwards. XML, or "".
     */
    public String driverGetSchema(String driverDn) {
        extOp("DriverGetSchema", driverDn, () -> call(Op.DRIVER_GET_SCHEMA, driverDn));
        return applicationSchema(driverDn);
    }

    /** The last application schema the engine stored on the driver ({@code DirXML-ApplicationSchema}), or "". */
    public String applicationSchema(String driverDn) {
        Entry e = read(driverDn, "DirXML-ApplicationSchema");
        String s = e == null ? null : e.string("DirXML-ApplicationSchema");
        return s == null ? "" : s;
    }

    public void migrateApp(String driverDn, byte[] xds) {
        extOp("MigrateApp", driverDn, () -> call(Op.MIGRATE_APP, driverDn, xds));
    }

    /** Resync events since {@code since} (epoch millis; 0 = full resync). */
    public void resync(String driverDn, long sinceMillis) {
        extOp("DriverResync", driverDn, () -> call(Op.DRIVER_RESYNC, driverDn, (int) (sinceMillis / 1000L)));
    }

    /** The engine version as the packed int the engine reports. */
    @Override
    public int engineVersion() {
        try {
            return call(Op.GET_VERSION).integer(0);
        } catch (Exception e) {
            throw new VaultException("GetVersion: " + e.getMessage(), e);
        }
    }

    /** The driver's statistics document (XML), or "" when the engine returns none. */
    public String driverStats(String driverDn, int arg) {
        try {
            return chunkedText(call(Op.GET_DRIVER_STATS, driverDn, arg, 0));   // timeout, cache type 0
        } catch (Exception e) {
            throw new VaultException("GetDriverStats " + driverDn + ": " + e.getMessage(), e);
        }
    }

    /** The engine JVM's statistics document (XML), or "" when none. */
    public String jvmStats(int a, int b) {
        try {
            return chunkedText(call(Op.GET_JVM_STATS, a, b));   // version, timeout
        } catch (Exception e) {
            throw new VaultException("GetJvmStats: " + e.getMessage(), e);
        }
    }

    /** A chunked result (the reply's data handle and size) read whole as UTF-8; "" when it has none. */
    private String chunkedText(Reply resp) throws Exception {
        if (resp.size() < 2 || resp.integer(0) == 0 || resp.integer(1) == 0) {
            return "";
        }
        return new String(chunked(resp.integer(0), resp.integer(1)), StandardCharsets.UTF_8);
    }

    private byte[] chunked(int handle, int size) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(size);
        int remaining = size;
        while (remaining > 0) {
            byte[] reply = call(Op.GET_CHUNKED_RESULT, handle, Math.min(remaining, MAX_CHUNK), 0).bytes(0);
            if (reply == null || reply.length == 0) {
                break;
            }
            out.write(reply, 0, reply.length);
            remaining -= reply.length;
        }
        call(Op.CLOSE_CHUNKED_RESULT, handle);
        return out.toByteArray();
    }

    // ---- named passwords -------------------------------------------------------------

    /** Names of the named passwords set on a driver (or driver set). */
    public List<String> namedPasswords(String dn) {
        try {
            // each element is a SET {name, displayName}; the name is what we key on
            List<String> out = new ArrayList<>();
            for (String[] pair : call(Op.LIST_NAMED_PASSWORDS, dn).pairs(0)) {
                out.add(pair[0]);
            }
            return out;
        } catch (Exception e) {
            throw new VaultException("ListNamedPasswords " + dn + ": " + e.getMessage(), e);
        }
    }

    public void setNamedPassword(String dn, String name, String displayName, char[] value) {
        try {
            call(Op.SET_NAMED_PASSWORD, dn, name, displayName == null ? name : displayName, value);
        } catch (Exception e) {
            throw new VaultException("SetNamedPassword " + dn + " '" + name + "': " + e.getMessage(), e);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public void setRemoteLoaderPassword(String driverDn, char[] value) {
        try {
            call(Op.SET_REMOTE_LOADER_PASSWORD, driverDn, value);
        } catch (Exception e) {
            throw new VaultException("SetRemoteLoaderPassword " + driverDn + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public void setMutualAuthKeyPassword(String driverDn, char[] value) {
        try {
            call(Op.SET_MUTUAL_AUTH_KEY_PASSWORD, driverDn, value);
        } catch (Exception e) {
            throw new VaultException("SetMutualAuthKeyPassword " + driverDn + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public void setMutualAuthKeystorePassword(String driverDn, char[] value) {
        try {
            call(Op.SET_MUTUAL_AUTH_KS_PASSWORD, driverDn, value);
        } catch (Exception e) {
            throw new VaultException("SetMutualAuthKSPassword " + driverDn + ": " + e.getMessage(), e);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    public void clearRemoteLoaderPassword(String driverDn) {
        extOp("ClearRemoteLoaderPassword", driverDn, () -> call(Op.CLEAR_REMOTE_LOADER_PASSWORD, driverDn));
    }

    public void clearMutualAuthKeyPassword(String driverDn) {
        extOp("ClearMutualAuthKeyPassword", driverDn, () -> call(Op.CLEAR_MUTUAL_AUTH_KEY_PASSWORD, driverDn));
    }

    public void clearMutualAuthKeystorePassword(String driverDn) {
        extOp("ClearMutualAuthKSPassword", driverDn, () -> call(Op.CLEAR_MUTUAL_AUTH_KS_PASSWORD, driverDn));
    }

    /** {@code ViewActivation}: the driver set's activation document (docs/console-gaps.md §13), or "" when it has none. */
    public String viewActivation(String driverSetDn) {
        try {
            return chunkedText(call(Op.VIEW_ACTIVATION, driverSetDn, 60));   // timeout
        } catch (Exception e) {
            throw new VaultException("ViewActivation " + driverSetDn + ": " + e.getMessage(), e);
        }
    }

    /** {@code ApplyActivation}: an activation credential document (the file the vendor sends) onto the driver set. */
    public void applyActivation(String driverSetDn, byte[] credential) {
        extOp("ApplyActivation", driverSetDn, () -> call(Op.APPLY_ACTIVATION, driverSetDn, credential));
    }

    /** {@code GetDriverSet}: the driver set the connected server runs, or null when it runs none. */
    public String serverDriverSet() {
        try {
            Reply r = call(Op.GET_DRIVER_SET);
            String dn = r.size() == 0 ? null : r.string(0);
            return dn == null || dn.isBlank() ? null : dn;
        } catch (Exception e) {
            throw new VaultException("GetDriverSet: " + e.getMessage(), e);
        }
    }

    /** {@code SetDriverSet}: associate the connected server with a driver set. */
    public void setServerDriverSet(String driverSetDn) {
        extOp("SetDriverSet", driverSetDn, () -> call(Op.SET_DRIVER_SET, driverSetDn));
    }

    /** {@code ClearDriverSet}: the connected server runs no driver set any more. */
    public void clearServerDriverSet() {
        extOp("ClearDriverSet", "(this server)", () -> call(Op.CLEAR_DRIVER_SET));
    }

    /**
     * eDirectory's effective rights of {@code trusteeDn} on {@code objectDn} for {@code attribute}
     * ({@code [Entry Rights]}, {@code [All Attributes Rights]} or one attribute), as the LDAP extension
     * reports them: a bit set of {@code LDAPDSConstants.LDAP_DS_ENTRY_*} / {@code LDAP_DS_ATTR_*}.
     */
    public int effectivePrivileges(String objectDn, String trusteeDn, String attribute) {
        try {
            com.novell.ldap.extensions.GetEffectivePrivilegesResponse r = (com.novell.ldap.extensions.GetEffectivePrivilegesResponse)
                ops().extendedOperation(new com.novell.ldap.extensions.GetEffectivePrivilegesRequest(objectDn, trusteeDn, attribute));
            return r.getPrivileges();
        } catch (Exception e) {
            throw new VaultException("GetEffectivePrivileges " + objectDn + " for " + trusteeDn + " on " + attribute + ": " + e.getMessage(), e);
        }
    }

    /** How many entries a search finds, without reading their attributes. */
    public int count(String base, String filter, int scope) {
        int n = 0;
        try {
            SearchControls sc = new SearchControls();
            sc.setSearchScope(scope);
            sc.setReturningAttributes(new String[] {"1.1"});
            NamingEnumeration<SearchResult> results = ldap.search(base, filter, sc);
            try {
                while (results.hasMore()) {
                    results.next();
                    n++;
                }
            } finally {
                results.close();
            }
        } catch (NameNotFoundException e) {
            return 0;
        } catch (Exception e) {
            throw new VaultException("count " + base + " " + filter + ": " + e.getMessage(), e);
        }
        return n;
    }

    /** {@code SetLogEvents}: the audit event ids a driver or driver set logs ({@code DirXML-LogEvents} is engine-written; docs/console-gaps.md §11). */
    public void setLogEvents(String dn, int[] eventIds) {
        extOp("SetLogEvents", dn, () -> call(Op.SET_LOG_EVENTS, dn, eventIds));
    }

    /** {@code ClearLogEvents}: no events logged. */
    public void clearLogEvents(String dn) {
        extOp("ClearLogEvents", dn, () -> call(Op.CLEAR_LOG_EVENTS, dn));
    }

    public void removeNamedPassword(String dn, String name) {
        extOp("RemoveNamedPassword '" + name + "'", dn, () -> call(Op.REMOVE_NAMED_PASSWORD, dn, name));
    }

    // ---- helpers ------------------------------------------------------------------

    private interface Action {
        void run() throws Exception;
    }

    private static void extOp(String what, String dn, Action op) {
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

    /** A socket factory whose SSL sockets verify that the certificate matches the host (RFC 2818 rules, as HTTPS does). */
    static javax.net.ssl.SSLSocketFactory endpointChecking(javax.net.ssl.SSLSocketFactory delegate) {
        return new javax.net.ssl.SSLSocketFactory() {
            private java.net.Socket checked(java.net.Socket s) {
                if (s instanceof javax.net.ssl.SSLSocket) {
                    javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) s;
                    javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(params);
                }
                return s;
            }

            @Override
            public String[] getDefaultCipherSuites() {
                return delegate.getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return delegate.getSupportedCipherSuites();
            }

            @Override
            public java.net.Socket createSocket() throws java.io.IOException {
                return checked(delegate.createSocket());
            }

            @Override
            public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
                return checked(delegate.createSocket(s, host, port, autoClose));
            }

            @Override
            public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
                return checked(delegate.createSocket(host, port));
            }

            @Override
            public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
                return checked(delegate.createSocket(host, port, localHost, localPort));
            }

            @Override
            public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
                return checked(delegate.createSocket(host, port));
            }

            @Override
            public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
                return checked(delegate.createSocket(address, port, localAddress, localPort));
            }
        };
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
