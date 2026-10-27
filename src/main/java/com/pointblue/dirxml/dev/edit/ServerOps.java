package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.deploy.Servers;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A driver set served by several servers keeps each driver's server-specific settings once per server
 * (eDirectory never-sync attributes; {@link Servers}). The tree holds the primary server's in the driver's
 * own files and, per other server, what differs ({@link Driver#serverConfig}, {@link Driver#serverSettings}).
 * These operations copy them from one server to another (#134), so the target holds what the source holds; a
 * deploy then writes them to the target server.
 */
public final class ServerOps {

    /** What can be copied, by its {@code --only} name: the three config documents and the two plain settings. */
    static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("gcv", Driver.CONFIG_VALUES);
        KINDS.put("shim", Driver.SHIM_CONFIG_INFO);
        KINDS.put("ecv", Driver.ENGINE_CONTROL_VALUES);
        KINDS.put("auth-server", Driver.SHIM_AUTH_SERVER);
        KINDS.put("auth-id", Driver.SHIM_AUTH_ID);
    }

    private ServerOps() {
    }

    /**
     * {@code driver.server.copy --from S --to S [--driver D] [--only gcv,shim,ecv,auth-server,auth-id]}: the
     * target server takes the source server's values of each kind, for one driver or every driver. No other
     * server changes: when the target is the primary, a server that took the primary's old value keeps it.
     */
    public static final class Copy implements Operation {
        private final String from;
        private final String to;
        private final String driver;
        private final String only;

        public Copy(String from, String to, String driver, String only) {
            this.from = from;
            this.to = to;
            this.driver = driver;
            this.only = only;
        }

        @Override
        public String name() {
            return "driver.server.copy";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            if (ds.servers.size() < 2) {
                throw new Refusal("the driver set has " + (ds.servers.isEmpty() ? "no servers on record" : "one server")
                    + ": server-specific settings exist only where several servers serve it (read it from a vault with them)");
            }
            String primary = ds.meta.get(Servers.PRIMARY_META);
            if (primary == null) {
                throw new Refusal("the tree doesn't record which server it was read through (" + Servers.PRIMARY_META
                    + "): read it from the vault again");
            }
            String src = server(ds, from, primary, "--from");
            String dst = server(ds, to, primary, "--to");
            if (src.equalsIgnoreCase(dst)) {
                throw new Refusal("--from and --to are the same server");
            }
            List<String> kinds = kinds(only);
            List<Driver> drivers = new ArrayList<>();
            if (driver == null || driver.isBlank()) {
                drivers.addAll(ds.drivers);
            } else {
                Driver d = ds.driver(driver.trim());
                if (d == null) {
                    throw new Refusal("no driver '" + driver.trim() + "'");
                }
                drivers.add(d);
            }
            int changed = 0;
            for (Driver d : drivers) {
                List<String> what = new ArrayList<>();
                for (String kind : kinds) {
                    if (Servers.SERVER_SETTING_KINDS.contains(kind)) {
                        if (copySetting(ds, d, kind, src, dst, primary)) {
                            what.add(label(kind));
                        }
                    } else if (copyConfig(ds, d, kind, src, dst, primary)) {
                        what.add(label(kind));
                    }
                }
                if (!what.isEmpty()) {
                    changed++;
                    tx.note(d.name + ": " + String.join(", ", what) + " copied from " + Servers.dirName(src) + " to " + Servers.dirName(dst));
                }
            }
            if (changed == 0) {
                tx.note(Servers.dirName(dst) + " already holds what " + Servers.dirName(src) + " holds"
                    + (drivers.size() == 1 ? " for " + drivers.get(0).name : "") + ": nothing to copy");
            }
        }
    }

    private static String label(String kind) {
        for (Map.Entry<String, String> e : KINDS.entrySet()) {
            if (e.getValue().equals(kind)) {
                return e.getKey();
            }
        }
        return kind;
    }

    /** A server by DN, by its short name (its leaf CN, as {@code servers/<name>/} in the tree), or {@code primary}. */
    static String server(DriverSet ds, String name, String primary, String option) throws Operation.Refusal {
        if (name == null || name.isBlank()) {
            throw new Operation.Refusal(option + " names a server: its DN, its name (" + names(ds) + ") or primary");
        }
        String n = name.trim();
        if (n.equalsIgnoreCase("primary")) {
            return primary;
        }
        for (String s : ds.servers) {
            if (s.equalsIgnoreCase(n) || Servers.dirName(s).equalsIgnoreCase(n)) {
                return s;
            }
        }
        throw new Operation.Refusal(option + " " + n + ": not a server of this driver set (" + names(ds) + ")");
    }

    private static String names(DriverSet ds) {
        List<String> out = new ArrayList<>();
        for (String s : ds.servers) {
            out.add(Servers.dirName(s));
        }
        return String.join(", ", out);
    }

    static List<String> kinds(String only) throws Operation.Refusal {
        if (only == null || only.isBlank()) {
            return new ArrayList<>(KINDS.values());
        }
        List<String> out = new ArrayList<>();
        for (String k : only.split("[,\\s]+")) {
            String kind = KINDS.get(k.trim().toLowerCase(Locale.ROOT));
            if (kind == null) {
                throw new Operation.Refusal("--only takes " + String.join(", ", KINDS.keySet()) + ", not '" + k.trim() + "'");
            }
            if (!out.contains(kind)) {
                out.add(kind);
            }
        }
        return out;
    }

    // ---- config documents (null: the server holds none) ----

    /** What {@code server} holds of a config kind: the primary's own, or the server's override (null = none). */
    static Element configOn(Driver d, String kind, String server, String primary) {
        if (server.equalsIgnoreCase(primary)) {
            return d.config.get(kind);
        }
        Map<String, Element> o = d.serverConfig.get(server);
        return o != null && o.containsKey(kind) ? o.get(kind) : d.config.get(kind);
    }

    private static boolean copyConfig(DriverSet ds, Driver d, String kind, String src, String dst, String primary) {
        Element value = configOn(d, kind, src, primary);
        if (Servers.sameXml(value, configOn(d, kind, dst, primary))) {
            return false;
        }
        Element copy = value == null ? null : Xds.parse(CanonicalXml.serialize(value)).getDocumentElement();
        if (dst.equalsIgnoreCase(primary)) {
            // every other server that follows the primary keeps what it holds now
            Element old = d.config.get(kind);
            for (String s : Servers.others(ds, primary)) {
                Map<String, Element> o = d.serverConfig.computeIfAbsent(s, k -> new LinkedHashMap<>());
                if (!o.containsKey(kind)) {
                    o.put(kind, old);
                }
            }
            if (copy == null) {
                d.config.remove(kind);
            } else {
                d.config.put(kind, copy);
            }
        } else {
            d.serverConfig.computeIfAbsent(dst, k -> new LinkedHashMap<>()).put(kind, copy);
        }
        normalize(d);
        return true;
    }

    // ---- plain settings ("" or null: the server holds none) ----

    private static String settingOn(Driver d, String kind, String server, String primary) {
        String own = Servers.settingOf(d, kind);
        if (server.equalsIgnoreCase(primary)) {
            return own == null ? "" : own;
        }
        Map<String, String> o = d.serverSettings.get(server);
        String v = o != null && o.containsKey(kind) ? o.get(kind) : own;
        return v == null ? "" : v;
    }

    private static boolean copySetting(DriverSet ds, Driver d, String kind, String src, String dst, String primary) {
        String value = settingOn(d, kind, src, primary);
        if (value.equals(settingOn(d, kind, dst, primary))) {
            return false;
        }
        if (dst.equalsIgnoreCase(primary)) {
            String old = settingOn(d, kind, primary, primary);
            for (String s : Servers.others(ds, primary)) {
                Map<String, String> o = d.serverSettings.computeIfAbsent(s, k -> new LinkedHashMap<>());
                if (!o.containsKey(kind)) {
                    o.put(kind, old);
                }
            }
            if (Driver.SHIM_AUTH_SERVER.equals(kind)) {
                d.shimAuthServer = value.isEmpty() ? null : value;
            } else {
                d.shimAuthId = value.isEmpty() ? null : value;
            }
        } else {
            d.serverSettings.computeIfAbsent(dst, k -> new LinkedHashMap<>()).put(kind, value);
        }
        normalize(d);
        return true;
    }

    /** Overrides keep only what differs from the primary: an override equal to it goes, and so does an empty server. */
    static void normalize(Driver d) {
        for (Map<String, Element> o : d.serverConfig.values()) {
            o.entrySet().removeIf(e -> Servers.sameXml(e.getValue(), d.config.get(e.getKey())));
        }
        d.serverConfig.values().removeIf(Map::isEmpty);
        for (Map<String, String> o : d.serverSettings.values()) {
            o.entrySet().removeIf(e -> {
                String own = Servers.settingOf(d, e.getKey());
                return (e.getValue() == null ? "" : e.getValue()).equals(own == null ? "" : own);
            });
        }
        d.serverSettings.values().removeIf(Map::isEmpty);
    }
}
