package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Element;

import javax.naming.directory.SearchControls;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A driver set served by several servers ({@code DirXML-ServerList} names them). IDM keeps a
 * driver's server-specific settings in eDirectory never-sync attributes: each server holds its
 * own {@code DirXML-ConfigValues}, {@code DirXML-ShimConfigInfo} and
 * {@code DirXML-EngineControlValues}, and only that server's LDAP hands them out or takes them.
 * The tree models that as the primary's values (the driver's own config files) plus, per other
 * server, the values that differ ({@link Driver#serverConfig}, {@code drivers/<d>/servers/<s>/}).
 * A server without an override holds what the primary holds, and a deploy keeps it so.
 *
 * <p>This class is the one place that knows how to reach the other servers: the environment's
 * {@code <env>.servers=<serverDn>=<url>;…}, else a URL derived from the server object (the IP
 * from its TCP {@code networkAddress}, the LDAPS port from its LDAP Server object). The clone
 * (docs/vault-clone.md §9) uses the same rules.
 */
public final class Servers {

    /** Driver-set meta: the server the tree was read through (its DN), so a plan without a live connection still knows the primary. */
    public static final String PRIMARY_META = "servers.primary";

    /** The never-sync driver settings the tree carries per server, by config kind. */
    public static final List<String> SERVER_CONFIG_KINDS = List.of(Driver.CONFIG_VALUES, Driver.SHIM_CONFIG_INFO, Driver.ENGINE_CONTROL_VALUES);

    private Servers() {
    }

    /** {@code <env>.servers=<serverDn>=<url>;<serverDn>=<url>} — the tree's other servers, when it cannot describe them. */
    public static Map<String, String> urls(Environments.Environment env) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw;
        try {
            raw = Environments.load().property(env.name, "servers");
        } catch (Exception e) {
            return out;
        }
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** The server the connection is on: the root DSE's {@code dsaName}, or null when it does not say. */
    public static String primary(VaultAccess v) {
        try {
            Vault.Entry root = v.read("", "dsaName");
            return root == null ? null : root.string("dsaName");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The driver set's {@code DirXML-ServerList}. */
    public static List<String> serverList(VaultAccess v, String driverSetDn) {
        Vault.Entry ds = v.read(driverSetDn, VaultMapping.SERVER_LIST);
        return ds == null ? List.of() : ds.strings(VaultMapping.SERVER_LIST);
    }

    /** The LDAPS URL of a server, from its objects: null when they do not say (no TCP address). */
    public static String deriveUrl(VaultAccess v, String serverDn) {
        try {
            Vault.Entry srv = v.read(serverDn, "networkAddress");
            String ip = null;
            if (srv != null) {
                for (byte[] b : srv.attrs.getOrDefault("networkAddress", List.of())) {
                    int hash = -1;
                    for (int i = 0; i < b.length; i++) {
                        if (b[i] == '#') {
                            hash = i;
                            break;
                        }
                    }
                    if (hash < 0) {
                        continue;
                    }
                    String type = new String(b, 0, hash, StandardCharsets.US_ASCII);
                    if ((type.equals("9") || type.equals("8")) && b.length >= hash + 7) {
                        ip = (b[hash + 3] & 0xFF) + "." + (b[hash + 4] & 0xFF) + "." + (b[hash + 5] & 0xFF) + "." + (b[hash + 6] & 0xFF);
                        break;
                    }
                }
            }
            if (ip == null) {
                return null;
            }
            String port = "636";
            String parent = serverDn.indexOf(',') < 0 ? "" : serverDn.substring(serverDn.indexOf(',') + 1);
            for (Vault.Entry ls : v.search(parent, "(&(objectClass=ldapServer)(ldapHostServer=" + serverDn + "))", SearchControls.SUBTREE_SCOPE)) {
                for (String i : ls.strings("ldapInterfaces")) {
                    if (i.startsWith("ldaps://")) {
                        String p = i.substring(i.lastIndexOf(':') + 1).replace("/", "");
                        if (!p.isEmpty()) {
                            port = p;
                        }
                    }
                }
            }
            return "ldaps://" + ip + ":" + port;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The URL to reach {@code serverDn}: the environment's, else derived through {@code primary}. */
    public static String urlOf(Environments.Environment env, VaultAccess primary, String serverDn) {
        for (Map.Entry<String, String> e : urls(env).entrySet()) {
            if (e.getKey().equalsIgnoreCase(serverDn)) {
                return e.getValue();
            }
        }
        return deriveUrl(primary, serverDn);
    }

    /** A connector for the environment: the same credentials at another server's URL. */
    public static Function<String, VaultAccess> connector(Environments.Environment env) {
        return url -> Vault.connect(env.vaultConfig().withUrl(url));
    }

    /**
     * Read every other server's never-sync driver settings and keep, per driver and server, the
     * ones that differ from the primary's — the tree's {@code servers/} overrides. {@code connect}
     * maps a server DN to an open connection (the caller closes what it opened); a null return
     * means the server cannot be reached and {@code notes} says so. The primary's own DN is skipped.
     */
    public static void readOverrides(DriverSet ds, String driverSetDn, VaultAccess primaryVault,
                                     Function<String, VaultAccess> connect, Consumer<String> notes) {
        List<String> servers = serverList(primaryVault, driverSetDn);
        ds.servers.clear();
        ds.servers.addAll(servers);
        String primary = primary(primaryVault);
        if (primary != null) {
            ds.meta.put(PRIMARY_META, primary);
        }
        if (servers.size() < 2) {
            return;
        }
        for (String s : servers) {
            if (primary != null && s.equalsIgnoreCase(primary)) {
                continue;
            }
            VaultAccess other;
            try {
                other = connect.apply(s);
            } catch (RuntimeException e) {
                other = null;
                notes.accept("server " + s + " unreachable: " + e.getMessage());
            }
            if (other == null) {
                notes.accept("server " + s + ": not read — its server-specific driver settings are unknown to this tree "
                    + "(give " + "<env>.servers=" + s + "=ldaps://host:port)");
                continue;
            }
            try {
                for (Driver d : ds.drivers) {
                    String dn = VaultMapping.driverDn(driverSetDn, d.name);
                    Vault.Entry e = other.read(dn, VaultMapping.CONFIG_VALUES, VaultMapping.SHIM_CONFIG_INFO, VaultMapping.ENGINE_CONTROL_VALUES);
                    if (e == null) {
                        continue;
                    }
                    for (String kind : SERVER_CONFIG_KINDS) {
                        String attr = VaultMapping.driverConfigAttribute(kind);
                        String text = e.string(attr);
                        Element mine = text == null || text.isBlank() ? null : CanonicalXml.parse(text).getDocumentElement();
                        Element primaryValue = d.config.get(kind);
                        if (!sameXml(mine, primaryValue)) {
                            d.serverConfig.computeIfAbsent(s, k -> new LinkedHashMap<>()).put(kind, mine);
                        }
                    }
                }
            } finally {
                if (other != primaryVault) {
                    other.close();
                }
            }
        }
    }

    /** Servers of the set other than the primary, by the tree's record ({@code driverset.xml}). */
    public static List<String> others(DriverSet ds, String primary) {
        List<String> out = new ArrayList<>();
        for (String s : ds.servers) {
            if (primary == null || !s.equalsIgnoreCase(primary)) {
                out.add(s);
            }
        }
        return out;
    }

    static boolean sameXml(Element a, Element b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return CanonicalXml.serialize(a).equals(CanonicalXml.serialize(b));
    }

    /** A file-safe directory name for a server: its leaf CN. */
    public static String dirName(String serverDn) {
        String leaf = serverDn.split(",")[0];
        int eq = leaf.indexOf('=');
        return com.pointblue.dirxml.dev.ascode.AsCodeWriter.fileSafe(eq < 0 ? leaf : leaf.substring(eq + 1)).toLowerCase(Locale.ROOT);
    }
}
