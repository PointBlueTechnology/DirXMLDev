package com.pointblue.dirxml.dev.deploy;

import com.novell.ldap.LDAPExtendedOperation;
import com.novell.ldap.asn1.ASN1Integer;
import com.novell.ldap.asn1.ASN1OctetString;
import com.novell.ldap.asn1.ASN1Sequence;
import com.novell.ldap.asn1.ASN1Set;
import com.novell.ldap.asn1.LBEREncoder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Guarded (docs/vendor-free-vault.md, verification 1): our {@link IdmOps} requests are byte for byte the
 * vendor's, and our reading of a reply gives what the vendor's response classes give. Runs only when the
 * vendor jars are in {@code lib/}; they are loaded here, in a class loader of their own, and nowhere else —
 * this project does not link them.
 */
public class IdmOpsVendorTest {

    private static final Path MISC = Paths.get("lib", "dirxml_misc.jar");
    private static final Path LDAP = Paths.get("lib", "ldap.jar");
    private static URLClassLoader vendor;

    @BeforeClass
    public static void load() throws Exception {
        if (Files.isRegularFile(MISC) && Files.isRegularFile(LDAP)) {
            vendor = new URLClassLoader(new URL[] {MISC.toUri().toURL(), LDAP.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        }
    }

    @AfterClass
    public static void close() throws Exception {
        if (vendor != null) {
            vendor.close();
        }
    }

    private static Object vendorRequest(String simpleName, Object... args) throws Exception {
        Class<?> c = vendor.loadClass("com.novell.nds.dirxml.ldap." + simpleName);
        for (Constructor<?> k : c.getConstructors()) {
            Class<?>[] types = k.getParameterTypes();
            if (types.length != args.length) {
                continue;
            }
            boolean fits = true;
            for (int i = 0; i < types.length && fits; i++) {
                Class<?> t = types[i];
                Object a = args[i];
                fits = t == int.class ? a instanceof Integer : t == boolean.class ? a instanceof Boolean : t.isInstance(a);
            }
            if (fits) {
                return k.newInstance(args);
            }
        }
        throw new AssertionError("no constructor of " + simpleName + " takes " + Arrays.toString(args));
    }

    private static void same(String vendorClass, Object[] vendorArgs, IdmOps.Op op, Object... ours) throws Exception {
        Object v = vendorRequest(vendorClass, vendorArgs);
        Method id = v.getClass().getMethod("getID");
        Method value = v.getClass().getMethod("getValue");
        LDAPExtendedOperation mine = IdmOps.request(op, ours);
        assertEquals(vendorClass + " OID", id.invoke(v), mine.getID());
        byte[] vb = (byte[]) value.invoke(v);
        byte[] mb = mine.getValue();
        assertEquals(vendorClass + " value", vb == null || vb.length == 0 ? "" : HexFormat.of().formatHex(vb),
            mb == null ? "" : HexFormat.of().formatHex(mb));
    }

    private static Object[] a(Object... args) {
        return args;
    }

    @Test
    public void everyRequestIsTheVendorsBytes() throws Exception {
        assumeTrue("needs lib/dirxml_misc.jar and lib/ldap.jar", vendor != null);
        String dn = "cn=AD Driver,cn=driverset1,o=system";
        String ds = "cn=driverset1,o=system";
        byte[] doc = "<nds><input/></nds>".getBytes(StandardCharsets.UTF_8);
        for (String[] oneDn : new String[][] {
            {"GetDriverStateRequest", "GET_DRIVER_STATE"}, {"StartDriverRequest", "START_DRIVER"}, {"StopDriverRequest", "STOP_DRIVER"},
            {"RestartDriverRequest", "RESTART_DRIVER"}, {"GetDriverStartOptionRequest", "GET_DRIVER_START_OPTION"},
            {"ListNamedPasswordsRequest", "LIST_NAMED_PASSWORDS"}, {"ClearLogEventsRequest", "CLEAR_LOG_EVENTS"},
            {"ClearRemoteLoaderPasswordRequest", "CLEAR_REMOTE_LOADER_PASSWORD"},
            {"ClearMutualAuthKeyPasswordRequest", "CLEAR_MUTUAL_AUTH_KEY_PASSWORD"},
            {"ClearMutualAuthKSPasswordRequest", "CLEAR_MUTUAL_AUTH_KS_PASSWORD"}, {"DriverGetSchemaRequest", "DRIVER_GET_SCHEMA"},
            {"StartJobRequest", "START_JOB"}, {"AbortJobRequest", "ABORT_JOB"}, {"NotifyJobUpdateRequest", "NOTIFY_JOB_UPDATE"},
            {"GetJobStateRequest", "GET_JOB_STATE"}, {"SetDriverSetRequest", "SET_DRIVER_SET"}}) {
            same(oneDn[0], a(dn), IdmOps.Op.valueOf(oneDn[1]), dn);
        }
        same("GetVersionRequest", a(), IdmOps.Op.GET_VERSION);
        same("GetDriverSetRequest", a(), IdmOps.Op.GET_DRIVER_SET);
        same("ClearDriverSetRequest", a(), IdmOps.Op.CLEAR_DRIVER_SET);
        same("SetDriverStartOptionRequest", a(dn, 2, false), IdmOps.Op.SET_DRIVER_START_OPTION, dn, 2, 0);
        same("ViewCacheEntriesRequest", a(dn, 1, 5, 10, 0), IdmOps.Op.VIEW_CACHE_ENTRIES, dn, 1, 5, 10, 0);
        same("DeleteCacheEntriesRequest", a(dn, 3, 1, "ev-7", 0), IdmOps.Op.DELETE_CACHE_ENTRIES, dn, 3, 1, "ev-7", 1);
        same("DeleteCacheEntriesRequest", a(dn, 3, 1, "ev-7", 2), IdmOps.Op.DELETE_CACHE_ENTRIES, dn, 3, 1, "ev-7", 5);
        same("QueueEventRequest", a(dn, doc), IdmOps.Op.QUEUE_EVENT, dn, doc);
        same("MigrateAppRequest", a(dn, doc), IdmOps.Op.MIGRATE_APP, dn, doc);
        same("SubmitEventRequest", a(dn, 1, doc), IdmOps.Op.SUBMIT_EVENT, dn, 1, doc);
        same("SubmitCommandRequest", a(dn, 1, doc), IdmOps.Op.SUBMIT_COMMAND, dn, 1, doc);
        long since = 1_760_000_000_000L;
        same("DriverResyncRequest", a(dn, new Date(since)), IdmOps.Op.DRIVER_RESYNC, dn, (int) (since / 1000L));
        same("GetDriverStatsRequest", a(dn, 30), IdmOps.Op.GET_DRIVER_STATS, dn, 30, 0);
        same("GetJvmStatsRequest", a(1, 30), IdmOps.Op.GET_JVM_STATS, 1, 30);
        same("GetChunkedResultRequest", a(12, 64512, 0), IdmOps.Op.GET_CHUNKED_RESULT, 12, 64512, 0);
        same("CloseChunkedResultRequest", a(12), IdmOps.Op.CLOSE_CHUNKED_RESULT, 12);
        same("SetLogEventsRequest", a(dn, new int[] {100, 101, 108}), IdmOps.Op.SET_LOG_EVENTS, dn, new int[] {100, 101, 108});
        same("RemoveNamedPasswordRequest", a(dn, "key"), IdmOps.Op.REMOVE_NAMED_PASSWORD, dn, "key");
        same("SetNamedPasswordRequest", a(dn, "key", "Key ä", "sëcret".toCharArray()), IdmOps.Op.SET_NAMED_PASSWORD,
            dn, "key", "Key ä", "sëcret".toCharArray());
        same("SetRemoteLoaderPasswordRequest", a(dn, "rl".toCharArray()), IdmOps.Op.SET_REMOTE_LOADER_PASSWORD, dn, "rl".toCharArray());
        same("SetMutualAuthKeyPasswordRequest", a(dn, "k".toCharArray()), IdmOps.Op.SET_MUTUAL_AUTH_KEY_PASSWORD, dn, "k".toCharArray());
        same("SetMutualAuthKSPasswordRequest", a(dn, "ks".toCharArray()), IdmOps.Op.SET_MUTUAL_AUTH_KS_PASSWORD, dn, "ks".toCharArray());
        same("ViewActivationRequest", a(ds, 60), IdmOps.Op.VIEW_ACTIVATION, ds, 60);
        same("ApplyActivationRequest", a(ds, doc), IdmOps.Op.APPLY_ACTIVATION, ds, doc);
    }

    private static byte[] seq(Object... items) throws Exception {
        ASN1Sequence s = new ASN1Sequence();
        for (Object o : items) {
            if (o instanceof Integer i) {
                s.add(new ASN1Integer(i));
            } else if (o instanceof String str) {
                s.add(new ASN1OctetString(str));
            } else if (o instanceof byte[] b) {
                s.add(new ASN1OctetString(b));
            } else if (o instanceof String[] pair) {
                ASN1Set set = new ASN1Set();
                set.add(new ASN1OctetString(pair[0]));
                set.add(new ASN1OctetString(pair[1]));
                s.add(set);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.encode(new LBEREncoder(), out);
        return out.toByteArray();
    }

    private static Object vendorResponse(String simpleName, byte[] value) throws Exception {
        Class<?> c = vendor.loadClass("com.novell.nds.dirxml.ldap." + simpleName);
        return c.getConstructor(byte[].class, int.class, int.class).newInstance(value, 0, value.length);
    }

    private static Object get(Object o, String method) throws Exception {
        Method m = o.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(o);
    }

    @Test
    public void repliesReadAsTheVendorReadsThem() throws Exception {
        assumeTrue("needs lib/dirxml_misc.jar and lib/ldap.jar", vendor != null);
        byte[] state = seq(2);
        assertEquals(get(vendorResponse("GetDriverStateResponse", state), "getDriverState"), IdmOps.decode(state).integer(0));
        byte[] option = seq(1);
        assertEquals(get(vendorResponse("GetDriverStartOptionResponse", option), "getDriverStartOption"), IdmOps.decode(option).integer(0));
        int packed = (4 << 28) | (10 << 24) | (1 << 16);
        byte[] version = seq(packed);
        assertEquals(get(vendorResponse("GetVersionResponse", version), "getVersion"), IdmOps.decode(version).integer(0));
        byte[] driverSet = seq("cn=driverset1,o=system");
        assertEquals(get(vendorResponse("GetDriverSetResponse", driverSet), "getDriverSetDN"), IdmOps.decode(driverSet).string(0));
        byte[] chunk = seq("<status level=\"success\"/>".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals((byte[]) get(vendorResponse("GetChunkedResultResponse", chunk), "getData"), IdmOps.decode(chunk).bytes(0));
        byte[] cache = seq(9, 1234, 17);
        Object vc = vendorResponse("ViewCacheEntriesResponse", cache);
        IdmOps.Reply rc = IdmOps.decode(cache);
        assertEquals(List.of(get(vc, "getDataHandle"), get(vc, "getDataSize"), get(vc, "getPositionToken")),
            List.of(rc.integer(0), rc.integer(1), rc.integer(2)));
        long next = 1_760_000_000L;
        byte[] job = seq(1, 0, (int) next);
        Object vj = vendorResponse("GetJobStateResponse", job);
        IdmOps.Reply rj = IdmOps.decode(job);
        assertEquals(List.of(get(vj, "getRunningState"), get(vj, "getConfigurationState"), get(vj, "isScheduled"), get(vj, "getNextRunTime")),
            List.of(rj.integer(0), rj.integer(1), rj.integer(2) != 0, new Date(rj.integer(2) * 1000L)));
        byte[] names = seq(new String[] {"key1", "Key One"}, new String[] {"key2", "Key Two"});
        @SuppressWarnings("unchecked")
        List<String[]> theirs = (List<String[]>) get(vendorResponse("ListNamedPasswordsResponse", names), "getList");
        List<String[]> ours = IdmOps.decode(names).pairs(0);
        assertEquals(theirs.size(), ours.size());
        for (int i = 0; i < ours.size(); i++) {
            assertArrayEquals(theirs.get(i), ours.get(i));
        }
        assertEquals(Arrays.toString(com.pointblue.dirxml.dev.deploy.IdmOps.parseVersion(packed)),
            Arrays.toString((int[]) vendor.loadClass("com.novell.nds.dirxml.util.DxConst").getMethod("parseDirXMLVersion", int.class).invoke(null, packed)));
    }
}
