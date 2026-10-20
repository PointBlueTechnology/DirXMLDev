package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Job;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tree's servers as an environment's ({@code docs/existing-environments.md}, "Servers"). Tree and server
 * names are globally unique, so the servers the tree names (the set's server list, each driver's
 * {@code servers/<server>/} settings, jobs' servers) are the base environment's; every other environment
 * maps them in its overrides file, one line per tree server, several onto one where it has fewer:
 * <pre>
 *   servers.cn=VAULT01,ou=servers,o=acme = cn=vault01qa,ou=servers,o=acme
 *   servers.cn=VAULT02,ou=servers,o=acme = cn=vault01qa,ou=servers,o=acme
 *   servers.cn=vault01qa,ou=servers,o=acme.prefer = cn=VAULT02,ou=servers,o=acme
 * </pre>
 * Where several tree servers land on one, that server takes the settings of the one listed first, or of
 * the one its {@code .prefer} line names; a driver whose settings differ between them is reported.
 * Reading that environment maps back: its servers take the tree's names, its settings land on the
 * preferred tree server, and the other tree servers keep what the tree holds.
 */
public final class ServerMap {

    public static final String PREFIX = "servers.";
    public static final String PREFER = ".prefer";

    private ServerMap() {
    }

    /** True for an overrides-file key this class reads. */
    public static boolean isKey(String key) {
        return key.startsWith(PREFIX) && key.length() > PREFIX.length();
    }

    /** The tree's server (as written) to the environment's, in file order; empty when the file maps none. */
    public static Map<String, String> of(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (isKey(e.getKey()) && !e.getKey().endsWith(PREFER) && !e.getValue().isBlank()) {
                    out.put(e.getKey().substring(PREFIX.length()), e.getValue().trim());
                }
            }
        }
        return out;
    }

    /** For each environment server, the tree server whose settings it takes: its {@code .prefer} line, else the first mapped to it. */
    public static Map<String, String> preferred(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> m : of(values).entrySet()) {
            out.putIfAbsent(key(m.getValue()), m.getKey());
        }
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (isKey(e.getKey()) && e.getKey().endsWith(PREFER) && !e.getValue().isBlank()) {
                    out.put(key(e.getKey().substring(PREFIX.length(), e.getKey().length() - PREFER.length())), e.getValue().trim());
                }
            }
        }
        return out;
    }

    /**
     * The tree as the environment gets it, in place: every server it names becomes the environment's, the
     * settings of several tree servers landing on one taken from the preferred one. Returns what couldn't be
     * applied or needs a person (a tree server not mapped, two tree servers' settings differing).
     */
    public static List<String> apply(DriverSet ds, Map<String, String> values) {
        List<String> problems = new ArrayList<>();
        Map<String, String> map = of(values);
        if (map.isEmpty()) {
            return problems;
        }
        Map<String, String> prefer = preferred(values);
        for (String s : ds.servers) {
            if (find(map, s) == null) {
                problems.add("server " + s + " is not mapped (" + PREFIX + s + " = <this environment's server>)");
            }
        }
        replaceAll(ds.servers, map);
        for (Job j : ds.jobs) {
            replaceAll(j.servers, map);
        }
        for (Driver d : ds.drivers) {
            for (Job j : d.jobs) {
                replaceAll(j.servers, map);
            }
            d.serverConfig.putAll(remap(d, d.serverConfig, map, prefer, problems, true));
            d.serverSettings.putAll(remap(d, d.serverSettings, map, prefer, problems, false));
        }
        return problems;
    }

    /**
     * A model read from the environment's vault, in place, as the tree names its servers: the server list and
     * jobs' servers take the tree's names (the tree's own list where it maps to the same), and each environment
     * server's settings go to its preferred tree server, the other tree servers keeping what {@code base} holds.
     * Returns notes (an environment server no tree server maps to).
     */
    public static List<String> toTree(DriverSet live, Map<String, String> values, DriverSet base) {
        List<String> notes = new ArrayList<>();
        Map<String, String> map = of(values);
        if (map.isEmpty()) {
            return notes;
        }
        Map<String, String> prefer = preferred(values);
        // the set's list: the tree's own when it maps onto exactly what the vault holds
        if (base != null && sameServers(mapped(base.servers, map), live.servers)) {
            live.servers.clear();
            live.servers.addAll(base.servers);
        } else {
            replaceBack(live.servers, prefer, notes);
        }
        for (Job j : live.jobs) {
            Job mine = base == null ? null : findJob(base.jobs, j.name);
            restoreJob(j, mine, map, prefer, notes);
        }
        for (Driver d : live.drivers) {
            Driver mine = base == null ? null : base.driver(d.name);
            for (Job j : d.jobs) {
                restoreJob(j, mine == null ? null : findJob(mine.jobs, j.name), map, prefer, notes);
            }
            Map<String, Map<String, Element>> config = new LinkedHashMap<>();
            Map<String, Map<String, String>> settings = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Element>> e : d.serverConfig.entrySet()) {
                String tree = prefer.get(key(e.getKey()));
                if (tree == null) {
                    notes.add("server " + e.getKey() + " of this environment is not mapped to a tree server: its settings for '" + d.name + "' are not taken");
                } else {
                    config.put(tree, e.getValue());
                }
            }
            for (Map.Entry<String, Map<String, String>> e : d.serverSettings.entrySet()) {
                String tree = prefer.get(key(e.getKey()));
                if (tree != null) {
                    settings.put(tree, e.getValue());
                }
            }
            // the tree servers that aren't preferred anywhere keep the tree's settings
            if (mine != null) {
                for (Map.Entry<String, Map<String, Element>> e : mine.serverConfig.entrySet()) {
                    if (!containsIgnoreCase(prefer.values(), e.getKey()) && !config.containsKey(e.getKey())) {
                        config.put(e.getKey(), e.getValue());
                    }
                }
                for (Map.Entry<String, Map<String, String>> e : mine.serverSettings.entrySet()) {
                    if (!containsIgnoreCase(prefer.values(), e.getKey()) && !settings.containsKey(e.getKey())) {
                        settings.put(e.getKey(), e.getValue());
                    }
                }
            }
            d.serverConfig.clear();
            d.serverConfig.putAll(config);
            d.serverSettings.clear();
            d.serverSettings.putAll(settings);
        }
        return notes;
    }

    // ---- helpers ----

    /**
     * One driver's per-server settings keyed by the environment's servers: each holds exactly what its preferred
     * tree server holds (nothing, when that one holds the primary's values); another tree server mapped to the same
     * server whose settings differ is reported. Settings of a tree server the map leaves out stay as they are.
     */
    private static <V> Map<String, Map<String, V>> remap(Driver d, Map<String, Map<String, V>> byServer, Map<String, String> map,
                                                          Map<String, String> prefer, List<String> problems, boolean xml) {
        Map<String, Map<String, V>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, V>> e : byServer.entrySet()) {
            if (find(map, e.getKey()) == null) {
                out.put(e.getKey(), e.getValue());   // unmapped: reported once, for the set's list
            }
        }
        Map<String, List<String>> roles = new LinkedHashMap<>();   // environment server -> tree servers, in file order
        for (Map.Entry<String, String> m : map.entrySet()) {
            roles.computeIfAbsent(m.getValue(), k -> new ArrayList<>()).add(m.getKey());
        }
        for (Map.Entry<String, List<String>> r : roles.entrySet()) {
            String env = r.getKey();
            String chosen = prefer.getOrDefault(key(env), r.getValue().get(0));
            Map<String, V> held = get(byServer, chosen);
            for (String other : r.getValue()) {
                if (!other.equalsIgnoreCase(chosen) && !same(get(byServer, other), held, xml)) {
                    problems.add("driver '" + d.name + "': " + chosen + " and " + other + " both map to " + env + " but hold different settings; "
                        + env + " takes " + chosen + "'s (" + PREFIX + env + PREFER + " = <tree server> to choose another)");
                }
            }
            if (held != null) {
                out.put(env, held);
            }
        }
        byServer.clear();
        return out;
    }

    private static <V> Map<String, V> get(Map<String, Map<String, V>> byServer, String server) {
        for (Map.Entry<String, Map<String, V>> e : byServer.entrySet()) {
            if (e.getKey().equalsIgnoreCase(server)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static <V> boolean same(Map<String, V> a, Map<String, V> b, boolean xml) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, V> e : a.entrySet()) {
            V other = b.get(e.getKey());
            boolean equal = xml ? Servers.sameXml((Element) e.getValue(), (Element) other) : java.util.Objects.equals(e.getValue(), other);
            if (!equal) {
                return false;
            }
        }
        return true;
    }

    private static void restoreJob(Job j, Job mine, Map<String, String> map, Map<String, String> prefer, List<String> notes) {
        if (mine != null && sameServers(mapped(mine.servers, map), j.servers)) {
            j.servers.clear();
            j.servers.addAll(mine.servers);
        } else {
            replaceBack(j.servers, prefer, notes);
        }
    }

    private static Job findJob(List<Job> jobs, String name) {
        for (Job j : jobs) {
            if (j.name.equals(name)) {
                return j;
            }
        }
        return null;
    }

    private static void replaceAll(List<String> servers, Map<String, String> map) {
        List<String> out = mapped(servers, map);
        servers.clear();
        servers.addAll(out);
    }

    private static List<String> mapped(List<String> servers, Map<String, String> map) {
        List<String> out = new ArrayList<>();
        for (String s : servers) {
            String env = find(map, s);
            String v = env == null ? s : env;
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
                out.add(v);
            }
        }
        return out;
    }

    private static void replaceBack(List<String> servers, Map<String, String> prefer, List<String> notes) {
        List<String> out = new ArrayList<>();
        for (String s : servers) {
            String tree = prefer.get(key(s));
            if (tree == null) {
                notes.add("server " + s + " of this environment is not mapped to a tree server");
            }
            String v = tree == null ? s : tree;
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(v))) {
                out.add(v);
            }
        }
        servers.clear();
        servers.addAll(out);
    }

    private static boolean sameServers(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (String s : a) {
            if (b.stream().noneMatch(x -> x.equalsIgnoreCase(s))) {
                return false;
            }
        }
        return true;
    }

    /** The environment's server for a tree server (DNs compare without regard to case), or null. */
    private static String find(Map<String, String> map, String treeServer) {
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(treeServer)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean containsIgnoreCase(java.util.Collection<String> values, String s) {
        return values.stream().anyMatch(v -> v.equalsIgnoreCase(s));
    }

    private static String key(String dn) {
        return dn.toLowerCase(Locale.ROOT);
    }
}
