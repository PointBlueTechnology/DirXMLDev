package com.pointblue.dirxml.dev.events;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.json.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The event store at the shell (docs/event-store.md):
 * <pre>
 *   query <tree> events --env E [--dn DN | --under DN | --name RDN] [--driver D] [--policy P [--stage input|output]]
 *                       [--own | --logged] [--type add,modify,…] [--class C] [--since T] [--until T]
 *                       [--event-id ID] [--text S] [--attr A] [--limit N] [--json] [--xml]
 *   query <tree> event <id> --env E [--json] [--xml]
 *   events.describe <tree> --env E [--json]            rows, newest, schema versions, migrated
 *   events.case <tree> --env E --id N --name NAME [--driver D] [--channel subscriber|publisher]
 *                      [--dir DIR] [--replace] [--dry-run] [--json]
 * </pre>
 * DNs are the store's slash form ({@code \TREE\data\people\jdoe}); an LDAP DN is converted with
 * the tree name ({@code eventsTree}, else taken from the store's own rows).
 */
public final class EventsCli {
    private EventsCli() {
    }

    /** {@code query <tree> events|event …}: entered from ReadCli with the whole argv. */
    public static int query(String[] argv) throws Exception {
        String what = argv[2];
        Map<String, List<String>> opts = opts(argv, 3);
        List<String> pos = opts.getOrDefault("", List.of());
        boolean json = opts.containsKey("json");
        boolean xml = opts.containsKey("xml");
        String envName = first(opts, "env");
        if (envName == null) {
            System.err.println("usage: query <tree> " + what + " --env <name> …   (see docs/event-store.md)");
            return 2;
        }
        Environments.Environment env = Environments.load().get(envName);
        try (EventStore store = EventStore.open(env)) {
            EventMask mask = env.events.pseudonymise ? new EventMask() : null;
            if (what.equals("event")) {
                if (pos.isEmpty()) {
                    System.err.println("usage: query <tree> event <id> --env <name> [--json] [--xml]");
                    return 2;
                }
                Event e = store.get(Long.parseLong(pos.get(0)));
                if (e == null) {
                    System.err.println("no row " + pos.get(0));
                    return 1;
                }
                List<Event> family = e.eventId() == null ? List.of() : store.siblings(e.eventId());
                if (mask != null) {
                    e = mask.mask(e);
                    List<Event> m = new ArrayList<>();
                    for (Event f : family) {
                        m.add(mask.mask(f));
                    }
                    family = m;
                }
                if (json) {
                    Map<String, Object> out = new LinkedHashMap<>(e.toMap(xml));
                    List<Object> others = new ArrayList<>();
                    for (Event f : family) {
                        if (f.id() != e.id()) {
                            others.add(f.toMap(false));
                        }
                    }
                    out.put("sameEvent", others);
                    System.out.println(Json.pretty(out));
                } else {
                    printOne(e, family, xml || e.xml() != null);
                }
                return 0;
            }
            EventQuery q = parse(opts, store);
            List<Event> rows = store.query(q);
            if (mask != null) {
                List<Event> m = new ArrayList<>();
                for (Event e : rows) {
                    m.add(mask.mask(e));
                }
                rows = m;
            }
            if (json) {
                List<Object> out = new ArrayList<>();
                for (Event e : rows) {
                    out.add(e.toMap(xml));
                }
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("environment", env.name);
                wrap.put("count", rows.size());
                wrap.put("limit", Math.min(q.limit, EventQuery.MAX_LIMIT));
                wrap.put("events", out);
                System.out.println(Json.pretty(wrap));
            } else {
                printTable(rows, q);
            }
            return 0;
        }
    }

    /** {@code events.describe <tree> --env E [--json]} and {@code events.case <tree> --env E --id N --name NAME …}. */
    public static int command(String[] argv) throws Exception {
        String cmd = argv[0];
        Map<String, List<String>> opts = opts(argv, 1);
        List<String> pos = opts.getOrDefault("", List.of());
        boolean json = opts.containsKey("json");
        String envName = first(opts, "env");
        if (envName == null || pos.isEmpty()) {
            System.err.println("usage: " + cmd + " <tree> --env <name> " + (cmd.equals("events.case") ? "--id N --name NAME [--driver D] [--channel subscriber|publisher] [--dir DIR] [--replace] [--dry-run]" : "") + " [--json]");
            return 2;
        }
        Path tree = Paths.get(pos.get(0));
        Environments.Environment env = Environments.load().get(envName);
        try (EventStore store = EventStore.open(env)) {
            if (cmd.equals("events.describe")) {
                Map<String, Object> d = store.describe();
                d.put("environment", env.name);
                d.put("pseudonymise", env.events.pseudonymise);
                if (json) {
                    System.out.println(Json.pretty(d));
                } else {
                    for (Map.Entry<String, Object> e : d.entrySet()) {
                        System.out.printf("%-18s %s%n", e.getKey(), e.getValue());
                    }
                    if (Boolean.FALSE.equals(d.get("migrated"))) {
                        System.out.println("the table is from before Event Logger 2.0.0: run its sql/MIGRATE 2 policy columns.sql");
                    }
                }
                return 0;
            }
            String id = first(opts, "id");
            String name = first(opts, "name");
            if (id == null || name == null) {
                System.err.println("usage: events.case <tree> --env <name> --id N --name NAME [--driver D] [--channel subscriber|publisher] [--dir DIR] [--replace] [--dry-run] [--json]");
                return 2;
            }
            Event e = store.get(Long.parseLong(id));
            if (e == null) {
                System.err.println("no row " + id);
                return 1;
            }
            boolean masked = env.events.pseudonymise;
            if (masked) {
                e = new EventMask().mask(e);
            }
            String dir = first(opts, "dir");
            EventsCase.Plan p = EventsCase.plan(tree, e, env.name, name, first(opts, "driver"), first(opts, "channel"), dir == null ? null : Paths.get(dir), opts.containsKey("replace"), masked);
            boolean dry = opts.containsKey("dry-run");
            if (p.refusal == null && !dry) {
                EventsCase.write(p);
            }
            if (json) {
                Map<String, Object> m = p.toMap();
                m.put("written", p.refusal == null && !dry);
                System.out.println(Json.pretty(m));
            } else if (p.refusal != null) {
                System.out.println("REFUSED — " + p.refusal);
            } else {
                System.out.println((dry ? "would write " : "wrote ") + p.dir + (p.replaced ? " (replaced)" : "") + (p.reconstructed ? " — input.xds rebuilt from the JSON (lossy)" : ""));
                for (String f : p.files.keySet()) {
                    System.out.println("  " + f);
                }
            }
            return p.refusal == null ? 0 : 1;
        }
    }

    static EventQuery parse(Map<String, List<String>> opts, EventStore store) throws IOException {
        EventQuery q = new EventQuery();
        String tree = store.config().tree;
        q.dn = slash(first(opts, "dn"), tree, store);
        q.under = slash(first(opts, "under"), tree, store);
        q.name = first(opts, "name");
        String driver = first(opts, "driver");
        if (driver != null) {
            q.driverDn = driver.startsWith("\\") ? driver : driverDn(driver, store);
        }
        q.policy = first(opts, "policy");
        q.stage = first(opts, "stage");
        if (opts.containsKey("own")) {
            q.own = true;
        } else if (opts.containsKey("logged")) {
            q.own = false;
        }
        if (first(opts, "type") != null) {
            q.types = EventQuery.types(first(opts, "type"));
        }
        q.className = first(opts, "class");
        if (first(opts, "since") != null) {
            q.since = EventQuery.parseTime(first(opts, "since"));
        }
        if (first(opts, "until") != null) {
            q.until = EventQuery.parseTime(first(opts, "until"));
        }
        q.eventId = first(opts, "event-id");
        q.text = first(opts, "text");
        q.attr = first(opts, "attr");
        if (first(opts, "limit") != null) {
            q.limit = Integer.parseInt(first(opts, "limit"));
        }
        return q;
    }

    /** The store's DN of a driver named as the tree names it: the distinct srcdriver whose leaf matches. */
    public static String driverDn(String name, EventStore store) throws IOException {
        for (String dn : store.drivers()) {
            if (name.equalsIgnoreCase(Event.leaf(dn))) {
                return dn;
            }
        }
        throw new IOException("the store has no rows from a driver named '" + name + "' (query the distinct drivers with events.describe)");
    }

    /** An LDAP DN ({@code cn=jdoe,ou=users,o=data}) in the store's slash form; a slash DN as it is. */
    public static String slash(String dn, String tree, EventStore store) throws IOException {
        if (dn == null || dn.startsWith("\\")) {
            return dn;
        }
        if (!dn.contains("=")) {
            return dn;
        }
        if (tree == null) {
            tree = store.treeName();
            if (tree == null) {
                throw new IOException("the tree name is needed to convert an LDAP DN: set eventsTree=, or give the DN in slash form (\\TREE\\…)");
            }
        }
        String[] parts = dn.split("(?<!\\\\),");
        StringBuilder sb = new StringBuilder("\\").append(tree);
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            int eq = p.indexOf('=');
            sb.append('\\').append(eq < 0 ? p : p.substring(eq + 1));
        }
        return sb.toString();
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    static void printTable(List<Event> rows, EventQuery q) {
        if (rows.isEmpty()) {
            System.out.println("no events");
            return;
        }
        System.out.printf("%-8s %-19s %-7s %-14s %-50s %-24s %s%n", "ID", "TIME", "TYPE", "CLASS", "SRC-DN", "DRIVER", "POLICY");
        for (Event e : rows) {
            System.out.printf("%-8d %-19s %-7s %-14s %-50s %-24s %s%n", e.id(), e.time() == null ? "" : TIME.format(e.time()), e.type(), cut(e.className(), 14),
                cut(e.srcDn(), 50), cut(e.driverName(), 24), e.policy() == null ? "" : e.policy() + "/" + e.stage());
        }
        if (rows.size() >= Math.min(q.limit, EventQuery.MAX_LIMIT)) {
            System.out.println("(limit " + Math.min(q.limit, EventQuery.MAX_LIMIT) + " reached; --limit N up to " + EventQuery.MAX_LIMIT + ", or narrow the selection)");
        }
    }

    static void printOne(Event e, List<Event> family, boolean xml) {
        System.out.println("id          " + e.id());
        System.out.println("event-id    " + e.eventId());
        System.out.println("type        " + e.type() + "  class " + e.className());
        System.out.println("src-dn      " + e.srcDn());
        System.out.println("time        " + (e.time() == null ? "" : TIME.format(e.time())));
        System.out.println("driver      " + e.srcDriver());
        if (e.fromPolicy()) {
            System.out.println("policy      " + e.policy() + " (" + e.channel() + ", " + e.stage() + ")");
        }
        System.out.println("schema      " + e.schemaVersion() + (e.xml() == null ? "  (no XML stored)" : ""));
        for (Event.Change c : e.changes()) {
            System.out.println("  " + c.attribute() + ": " + (c.removeAll() ? "(all removed) " : "") + String.join(", ", c.removed()) + " → " + String.join(", ", c.added()));
        }
        if (family.size() > 1) {
            System.out.println("same event:");
            for (Event f : family) {
                if (f.id() != e.id()) {
                    System.out.println("  " + f.id() + "  " + (f.policy() == null ? "driver row" : f.policy() + "/" + f.stage()) + "  " + Event.leaf(f.srcDriver()));
                }
            }
        }
        System.out.println();
        if (xml && e.xml() != null) {
            System.out.println(e.xml());
        } else {
            System.out.println(Json.pretty(e.json()));
        }
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : "…" + s.substring(s.length() - n + 1);
    }

    /** {@code --key value} pairs (repeatable), flags without a value, positionals under "". */
    static Map<String, List<String>> opts(String[] argv, int from) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String k = a.substring(2).toLowerCase(Locale.ROOT);
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--") && !List.of("json", "xml", "own", "logged", "replace", "dry-run").contains(k)) {
                    out.computeIfAbsent(k, x -> new ArrayList<>()).add(argv[++i]);
                } else {
                    out.computeIfAbsent(k, x -> new ArrayList<>());
                }
            } else {
                out.computeIfAbsent("", x -> new ArrayList<>()).add(a);
            }
        }
        return out;
    }

    static String first(Map<String, List<String>> opts, String k) {
        List<String> v = opts.get(k);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
