package com.pointblue.dirxml.dev.events;

import com.pointblue.dirxml.dev.Args;
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

    /** What {@code query <tree> events} and {@code query <tree> event} take (docs/commands.md). */
    static final Map<String, Args.Spec> QUERY_SPECS = Map.of(
        "events", Args.Spec.of("own logged xml",
            "env dn under name driver policy stage type class since until event-id text attr limit"),
        "event", Args.Spec.of("xml", "env"));

    /** What {@code events.describe} and {@code events.case} take (docs/commands.md). */
    static final Map<String, Args.Spec> SPECS = Map.of(
        "events.describe", Args.Spec.of("", "env"),
        "events.case", Args.Spec.of("replace dry-run", "env id name driver channel dir"));

    /** {@code query <tree> events|event …}: entered from ReadCli with the whole argv. */
    public static int query(String[] argv) throws Exception {
        String what = argv[2];
        Args opts;
        EventQuery q = null;
        long rowId = 0;
        try {
            Args.Spec spec = QUERY_SPECS.get(what);
            if (spec == null) {
                throw new Args.UsageError("query <tree> events|event, not '" + what + "'");
            }
            opts = Args.parse("query " + what, argv, 3, spec);
            if (opts.first("env") == null) {
                throw new Args.UsageError("usage: query <tree> " + what + " --env <name> …   (see docs/event-store.md)");
            }
            if (what.equals("event")) {
                if (opts.positional.size() != 1) {
                    throw new Args.UsageError((opts.positional.size() > 1 ? "unexpected argument '" + opts.positional.get(1) + "'\n" : "")
                        + "usage: query <tree> event <id> --env <name> [--json] [--xml]");
                }
                rowId = number("<id>", opts.positional.get(0));
            } else {
                if (!opts.positional.isEmpty()) {
                    throw new Args.UsageError("unexpected argument '" + opts.positional.get(0) + "'");
                }
                q = selectors(opts);
            }
        } catch (Args.UsageError e) {
            System.err.println(e.getMessage());
            return 2;
        }
        boolean json = opts.has("json");
        boolean xml = opts.has("xml");
        String envName = opts.first("env");
        Environments.Environment env = Environments.load().get(envName);
        try (EventStore store = EventStore.open(env)) {
            EventMask mask = env.events.pseudonymise ? new EventMask() : null;
            if (what.equals("event")) {
                Event e = store.get(rowId);
                if (e == null) {
                    System.err.println("no row " + rowId);
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
            resolveDns(q, opts, store);
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
        String usage = "usage: " + cmd + " <tree> --env <name> " + (cmd.equals("events.case") ? "--id N --name NAME [--driver D] [--channel subscriber|publisher] [--dir DIR] [--replace] [--dry-run]" : "") + " [--json]";
        Args opts;
        long rowId = 0;
        try {
            Args.Spec spec = SPECS.get(cmd);
            if (spec == null) {
                throw new Args.UsageError("unknown command " + cmd);
            }
            opts = Args.parse(cmd, argv, 1, spec);
            if (opts.positional.size() > 1) {
                throw new Args.UsageError("unexpected argument '" + opts.positional.get(1) + "'\n" + usage);
            }
            if (opts.first("env") == null || opts.positional.isEmpty()) {
                throw new Args.UsageError(usage);
            }
            if (cmd.equals("events.case")) {
                if (opts.first("id") == null || opts.first("name") == null) {
                    throw new Args.UsageError(usage);
                }
                rowId = opts.longOpt("id", 0);
            }
        } catch (Args.UsageError e) {
            System.err.println(e.getMessage());
            return 2;
        }
        List<String> pos = opts.positional;
        boolean json = opts.has("json");
        String envName = opts.first("env");
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
            String name = opts.first("name");
            Event e = store.get(rowId);
            if (e == null) {
                System.err.println("no row " + rowId);
                return 1;
            }
            boolean masked = env.events.pseudonymise;
            if (masked) {
                e = new EventMask().mask(e);
            }
            String dir = opts.first("dir");
            EventsCase.Plan p = EventsCase.plan(tree, e, env.name, name, opts.first("driver"), opts.first("channel"), dir == null ? null : Paths.get(dir), opts.has("replace"), masked);
            boolean dry = opts.has("dry-run");
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

    /** The selectors the command line alone decides; a bad value is a usage error, before any store is opened. */
    static EventQuery selectors(Args opts) throws Args.UsageError {
        EventQuery q = new EventQuery();
        q.name = opts.first("name");
        q.policy = opts.first("policy");
        q.stage = opts.oneOf("stage", null, List.of("input", "output"));
        if (opts.has("own")) {
            q.own = true;
        } else if (opts.has("logged")) {
            q.own = false;
        }
        if (opts.first("type") != null) {
            try {
                q.types = EventQuery.types(opts.first("type"));
            } catch (IllegalArgumentException e) {
                throw new Args.UsageError("--type: " + e.getMessage());
            }
        }
        q.className = opts.first("class");
        q.since = time(opts, "since");
        q.until = time(opts, "until");
        q.eventId = opts.first("event-id");
        q.text = opts.first("text");
        q.attr = opts.first("attr");
        q.limit = opts.intOpt("limit", q.limit);
        return q;
    }

    /** The DN selectors, which need the store (an LDAP DN needs its tree name, a driver its DN). */
    static void resolveDns(EventQuery q, Args opts, EventStore store) throws IOException {
        String tree = store.config().tree;
        q.dn = slash(opts.first("dn"), tree, store);
        q.under = slash(opts.first("under"), tree, store);
        String driver = opts.first("driver");
        if (driver != null) {
            q.driverDn = driver.startsWith("\\") ? driver : driverDn(driver, store);
        }
    }

    private static java.time.Instant time(Args opts, String option) throws Args.UsageError {
        String v = opts.first(option);
        if (v == null) {
            return null;
        }
        try {
            return EventQuery.parseTime(v);
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw new Args.UsageError("--" + option + " is 30s, 30m, 24h or 7d ago, an ISO instant or a date (2026-10-28), not '" + v + "'");
        }
    }

    private static long number(String what, String v) throws Args.UsageError {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new Args.UsageError(what + " needs a whole number, not '" + v + "'");
        }
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
}
