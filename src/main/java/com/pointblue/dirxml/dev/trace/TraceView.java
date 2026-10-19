package com.pointblue.dirxml.dev.trace;

import com.pointbluetech.dirxml.trace.model.Channel;
import com.pointbluetech.dirxml.trace.model.TraceMarker;
import com.pointbluetech.dirxml.trace.model.TraceParser;
import com.pointbluetech.dirxml.trace.model.TraceRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A driver trace as transactions, for an agent (docs/trace-view.md): the records grouped per driver
 * and channel from one event to the next, and in each what the <em>engine's own wording</em> says —
 * the event document's first operation, the policy sets and policies applied, the rules and actions,
 * vetoes and {@code <status>} documents. It is best effort: a shim can print anything, so a line
 * that is not the engine's wording is kept as it is (counted, never interpreted), and finding no
 * status or veto means none was recognized, not that none happened. Every transaction names its
 * lines in the input so the raw text is always one step away ({@link #text}).
 * <p>
 * Records come from {@code dirxml-trace-core}'s parser, the one the desktop viewer and the web use.
 */
public final class TraceView {

    /** Worst first is last: the order a transaction's statuses are ranked by. */
    static final List<String> LEVELS = List.of("success", "warning", "retry", "error", "fatal");

    private static final Pattern RULE = Pattern.compile("^\\s*Applying rule (.+?)\\.\\s*$", Pattern.MULTILINE);
    private static final Pattern ACTION = Pattern.compile("^\\s*Action: (.+?)\\.\\s*$", Pattern.MULTILINE);
    private static final Pattern VETO = Pattern.compile("(Operation vetoed[^\\r\\n]*?\\.)|(Action: do-veto[^\\r\\n]*)");
    private static final Pattern STATUS = Pattern.compile("<status\\b([^>]*?)(?:/>|>(.*?)</status>)", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern OPERATION = Pattern.compile(
        "<(add|modify|delete|rename|move|query|sync|instance|modify-password|check-object-password|trigger|init-params|heartbeat)\\b([^>]*)>");
    private static final Pattern POLICY_NAME = Pattern.compile("Applying policy:\\s*(.*?)\\.?\\s*$");
    private static final int CAP = 40;

    /** One record and where it sits in the input (1-based, inclusive). */
    public record Line(TraceRecord record, int first, int last) {
    }

    /** A policy as applied: its name, the rules applied and the actions performed (as the engine words them). */
    public static final class Policy {
        public final String name;
        public final List<String> rules = new ArrayList<>();
        public final List<String> actions = new ArrayList<>();

        Policy(String name) {
            this.name = name;
        }
    }

    /** A policy set ("Applying input transformation policies.") and its policies in order. */
    public static final class PolicySet {
        public final String name;
        public final List<Policy> policies = new ArrayList<>();

        PolicySet(String name) {
            this.name = name;
        }
    }

    /** A {@code <status>} document: its level, event-id and text (tags removed, trimmed). */
    public record Status(String level, String eventId, String message) {
    }

    /** One transaction: a driver's channel from an event to its end (or the next event). */
    public static final class Transaction {
        public int n;
        public final String driver;
        public final Channel channel;
        /** The marker that opened it, or null when the input begins inside one. */
        public final TraceMarker opener;
        public String start;
        public String end;
        public int firstLine;
        public int lastLine;
        public int records;
        public String operation;
        public String className;
        public String dn;
        public String eventId;
        public final List<PolicySet> sets = new ArrayList<>();
        public final List<Status> statuses = new ArrayList<>();
        public final List<String> vetoes = new ArrayList<>();
        /** Records whose first line is none of the engine's wording this view knows (a shim's own text, mostly). */
        public int unrecognized;
        public boolean ended;
        final List<Line> lines = new ArrayList<>();

        Transaction(String driver, Channel channel, TraceMarker opener) {
            this.driver = driver;
            this.channel = channel;
            this.opener = opener;
        }

        /** The worst status level recognized, or null when none was. */
        public String worst() {
            String w = null;
            for (Status s : statuses) {
                if (LEVELS.contains(s.level) && (w == null || LEVELS.indexOf(s.level) > LEVELS.indexOf(w))) {
                    w = s.level;
                }
            }
            return w;
        }

        public int policyCount() {
            int n = 0;
            for (PolicySet s : sets) {
                n += s.policies.size();
            }
            return n;
        }
    }

    /** The whole input: its transactions in order, and what was outside any. */
    public static final class Result {
        public final List<Transaction> transactions = new ArrayList<>();
        public int records;
        public int lines;
        /** Records outside any transaction (engine-level messages, a channel with no event yet). */
        public int outside;
    }

    private TraceView() {
    }

    /** Groups trace-file lines into records the way {@code TraceFileReader} does: a header line starts one. */
    public static List<Line> records(List<String> input) {
        TraceParser parser = new TraceParser();
        List<Line> out = new ArrayList<>();
        StringBuilder message = new StringBuilder();
        int first = 1;
        for (int i = 0; i < input.size(); i++) {
            String line = input.get(i);
            if (TraceParser.isHeaderLine(line) && message.length() > 0) {
                out.add(new Line(parser.parseText("", message.toString()), first, i));
                message.setLength(0);
                first = i + 1;
            }
            message.append(line).append('\n');
        }
        if (message.length() > 0) {
            out.add(new Line(parser.parseText("", message.toString()), first, input.size()));
        }
        return out;
    }

    /** The transactions in the records. */
    public static Result of(List<Line> records) {
        Result r = new Result();
        r.records = records.size();
        Map<String, Transaction> open = new HashMap<>();
        Map<String, PolicySet> openSet = new HashMap<>();
        Map<String, Policy> openPolicy = new HashMap<>();
        for (Line l : records) {
            TraceRecord rec = l.record();
            r.lines = Math.max(r.lines, l.last());
            String key = rec.server() + '\u0000' + rec.driverName() + '\u0000' + rec.channel();
            TraceMarker m = TraceMarker.of(rec);
            boolean starts = m == TraceMarker.SUBSCRIBER_EVENT || m == TraceMarker.PUBLISHER_EVENT || m == TraceMarker.DRIVER_START;
            Transaction t = open.get(key);
            if (rec.driverName() == null || rec.channel() == Channel.OTHER && !starts && t == null) {
                r.outside++;
                continue;
            }
            if (!starts && t != null && t.ended) {
                // after "End transaction." whatever comes before the next event stays with the transaction
                // it follows (a shim's own output, a returned document), so nothing is lost or invented
                add(t, l, m);
                continue;
            }
            if (starts || t == null) {
                t = new Transaction(rec.driverName(), rec.channel(), starts ? m : null);
                t.n = r.transactions.size() + 1;
                t.firstLine = l.first();
                t.start = stamp(rec);
                r.transactions.add(t);
                open.put(key, t);
                openSet.remove(key);
                openPolicy.remove(key);
            }
            if (m == TraceMarker.POLICY_SET) {
                PolicySet s = new PolicySet(firstLine(rec).trim());
                t.sets.add(s);
                openSet.put(key, s);
                openPolicy.remove(key);
            } else if (m == TraceMarker.POLICY) {
                Matcher n = POLICY_NAME.matcher(firstLine(rec));
                Policy p = new Policy(n.find() ? n.group(1) : firstLine(rec).trim());
                PolicySet s = openSet.get(key);
                if (s == null) {
                    s = new PolicySet("(no policy set traced)");
                    t.sets.add(s);
                    openSet.put(key, s);
                }
                s.policies.add(p);
                openPolicy.put(key, p);
            } else if (m != null && m != TraceMarker.LOG_EVENT && m != TraceMarker.POLICY_RESULT) {
                // a log event, or a policy's query result ("Query from policy result", trace-core 1.8.0), happens inside
                // the policy running: neither ends it (the viewer's folding treats them the same way)
                openPolicy.remove(key);
                if (m != TraceMarker.SUBSCRIBER_EVENT && m != TraceMarker.PUBLISHER_EVENT && m != TraceMarker.DRIVER_START) {
                    openSet.remove(key);
                }
            }
            Policy p = openPolicy.get(key);
            if (p != null) {
                String body = rec.header() == null ? rec.text() : rec.text().substring(Math.min(rec.header().end(), rec.text().length()));
                collect(RULE, body, p.rules);
                collect(ACTION, body, p.actions);
            }
            add(t, l, m);
            if (m == TraceMarker.END_TRANSACTION) {
                t.ended = true;
            }
        }
        return r;
    }

    private static void add(Transaction t, Line l, TraceMarker m) {
        TraceRecord rec = l.record();
        t.lines.add(l);
        t.records++;
        t.lastLine = l.last();
        if (rec.header() != null) {
            t.end = stamp(rec);
        }
        String text = rec.text();
        if (m == null && rec.header() != null && !known(firstLine(rec))) {
            t.unrecognized++;
        }
        if (t.operation == null) {
            Matcher op = OPERATION.matcher(text);
            if (op.find()) {
                Map<String, String> a = attrs(op.group(2));
                t.operation = op.group(1);
                t.className = a.get("class-name");
                t.dn = a.containsKey("src-dn") ? a.get("src-dn") : a.get("dest-dn");
                t.eventId = a.get("event-id");
            }
        }
        Matcher v = VETO.matcher(text);
        while (v.find() && t.vetoes.size() < CAP) {
            t.vetoes.add((v.group(1) != null ? v.group(1) : v.group(2)).trim());
        }
        Matcher s = STATUS.matcher(text);
        while (s.find() && t.statuses.size() < CAP) {
            Map<String, String> a = attrs(s.group(1));
            String level = a.getOrDefault("level", "").toLowerCase(Locale.ROOT);
            if (level.isEmpty()) {
                continue;   // a <status> element that is not the engine's status document (a value, e.g.)
            }
            String msg = s.group(2) == null ? "" : s.group(2).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            t.statuses.add(new Status(level, a.get("event-id"), msg.length() > 300 ? msg.substring(0, 300) + "…" : msg));
        }
    }

    /** Engine wording this view reads, beyond the markers: rules, actions, policy results, veto warnings. */
    private static boolean known(String first) {
        String f = first.trim();
        return f.startsWith("Applying rule") || f.startsWith("Action:") || f.startsWith("Evaluating selection criteria")
            || f.startsWith("Rule selected") || f.startsWith("Rule rejected") || f.startsWith("Policy returned:")
            || f.startsWith("Operation vetoed") || f.startsWith("Applying to ") || f.startsWith("Resolving association")
            || f.startsWith("Processing returned document") || f.startsWith("SubscriptionShim.execute")
            || f.startsWith("Filtering ") || f.startsWith("Fixing up association") || f.startsWith("Query from policy")
            || f.startsWith("Query from driver") || f.startsWith("Pumping") || f.startsWith("Submitting");
    }

    private static void collect(Pattern p, String text, List<String> into) {
        Matcher m = p.matcher(text);
        while (m.find() && into.size() < CAP) {
            into.add(m.group(1).trim());
        }
    }

    private static Map<String, String> attrs(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ATTR.matcher(s == null ? "" : s);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private static String firstLine(TraceRecord rec) {
        String text = rec.text();
        int from = rec.header() == null ? 0 : Math.min(rec.header().end(), text.length());
        int nl = text.indexOf('\n', from);
        return text.substring(from, nl < 0 ? text.length() : nl);
    }

    private static String stamp(TraceRecord rec) {
        TraceRecord.Header h = rec.header();
        return h == null ? null : rec.text().substring(h.timestampStart() + 1, h.timestampEnd() - 1);
    }

    /** A transaction's records exactly as they were in the input. */
    public static String text(Transaction t) {
        StringBuilder sb = new StringBuilder();
        for (Line l : t.lines) {
            sb.append(l.record().text());
        }
        return sb.toString();
    }

    // ---- filtering and output ------------------------------------------------------------

    /** What to keep; null fields keep everything. */
    public static final class Filter {
        public String driver;
        public Channel channel;
        public String status;
        public boolean vetoed;
        public Pattern text;

        boolean keeps(Transaction t) {
            if (driver != null && (t.driver == null || !t.driver.equalsIgnoreCase(driver))) {
                return false;
            }
            if (channel != null && t.channel != channel) {
                return false;
            }
            if (status != null && t.statuses.stream().noneMatch(s -> s.level.equalsIgnoreCase(status))) {
                return false;
            }
            if (vetoed && t.vetoes.isEmpty()) {
                return false;
            }
            return text == null || text.matcher(text(t)).find();
        }
    }

    /** {@code subscriber}/{@code publisher}/{@code service}/{@code other} (or the trace's ST/PT/SST tag); null when unknown. */
    public static Channel channel(String s) {
        if (s == null) {
            return null;
        }
        String c = s.trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "subscriber", "sub", "st" -> Channel.SUBSCRIBER;
            case "publisher", "pub", "pt" -> Channel.PUBLISHER;
            case "service", "sst" -> Channel.SERVICE;
            case "other", "engine" -> Channel.OTHER;
            default -> null;
        };
    }

    private static String tag(Channel c) {
        return switch (c) {
            case SUBSCRIBER -> "subscriber";
            case PUBLISHER -> "publisher";
            case SERVICE -> "service";
            default -> "other";
        };
    }

    /**
     * The JSON document: the source, a summary of every transaction, then the {@code last} most recent
     * that the filter keeps (each without its text), or — with {@code only} — that one transaction with
     * its raw text.
     */
    public static Map<String, Object> json(String source, Result r, Filter f, int last, Integer only) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("lines", r.lines);
        out.put("records", r.records);
        out.put("note", "best effort: the engine's own wording is read (markers, rules, actions, vetoes, <status>); "
            + "anything a shim prints is kept as text, counted as unrecognized, never interpreted — no status or veto "
            + "found means none was recognized. Each transaction's lines locate its raw text; --transaction N returns it.");
        out.put("summary", summary(r));
        if (only != null) {
            Transaction t = only < 1 || only > r.transactions.size() ? null : r.transactions.get(only - 1);
            out.put("transaction", t == null ? null : transaction(t, true));
            return out;
        }
        List<Transaction> kept = new ArrayList<>();
        for (Transaction t : r.transactions) {
            if (f.keeps(t)) {
                kept.add(t);
            }
        }
        List<Object> shown = new ArrayList<>();
        for (Transaction t : kept.subList(Math.max(0, kept.size() - last), kept.size())) {
            shown.add(transaction(t, false));
        }
        out.put("matched", kept.size());
        out.put("shown", shown.size());
        out.put("transactions", shown);
        return out;
    }

    private static Map<String, Object> summary(Result r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("transactions", r.transactions.size());
        Map<String, Map<String, Integer>> byDriver = new LinkedHashMap<>();
        Map<String, Integer> worst = new LinkedHashMap<>();
        int vetoed = 0;
        int unfinished = 0;
        int unrecognized = 0;
        List<Integer> problems = new ArrayList<>();
        for (Transaction t : r.transactions) {
            byDriver.computeIfAbsent(String.valueOf(t.driver), k -> new LinkedHashMap<>()).merge(tag(t.channel), 1, Integer::sum);
            String w = t.worst();
            worst.merge(w == null ? "none recognized" : w, 1, Integer::sum);
            if (!t.vetoes.isEmpty()) {
                vetoed++;
            }
            if (t.channel == Channel.SUBSCRIBER && t.opener == TraceMarker.SUBSCRIBER_EVENT && !t.ended) {
                unfinished++;
            }
            unrecognized += t.unrecognized;
            if (w != null && LEVELS.indexOf(w) >= LEVELS.indexOf("retry") || !t.vetoes.isEmpty()) {
                problems.add(t.n);
            }
        }
        s.put("byDriver", byDriver);
        s.put("worstStatus", worst);
        s.put("vetoed", vetoed);
        s.put("unfinished", unfinished);
        s.put("outsideTransactions", r.outside);
        s.put("unrecognizedRecords", unrecognized);
        s.put("problemTransactions", problems.size() > 100 ? problems.subList(problems.size() - 100, problems.size()) : problems);
        return s;
    }

    private static Map<String, Object> transaction(Transaction t, boolean withText) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", t.n);
        m.put("driver", t.driver);
        m.put("channel", tag(t.channel));
        m.put("opener", t.opener == null ? null : t.opener.label());
        m.put("start", t.start);
        m.put("end", t.end);
        m.put("lines", List.of(t.firstLine, t.lastLine));
        m.put("lineRanges", ranges(t));
        m.put("records", t.records);
        m.put("ended", t.ended);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("operation", t.operation);
        ev.put("class", t.className);
        ev.put("dn", t.dn);
        ev.put("eventId", t.eventId);
        m.put("event", t.operation == null ? null : ev);
        List<Object> sets = new ArrayList<>();
        for (PolicySet s : t.sets) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("set", s.name);
            List<Object> ps = new ArrayList<>();
            for (Policy p : s.policies) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("policy", p.name);
                pm.put("rules", p.rules);
                pm.put("actions", p.actions);
                ps.add(pm);
            }
            sm.put("policies", ps);
            sets.add(sm);
        }
        m.put("policySets", sets);
        List<Object> st = new ArrayList<>();
        for (Status s : t.statuses) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("level", s.level);
            sm.put("eventId", s.eventId);
            sm.put("message", s.message);
            st.add(sm);
        }
        m.put("statuses", st);
        m.put("worstStatus", t.worst());
        m.put("vetoes", t.vetoes);
        m.put("unrecognizedRecords", t.unrecognized);
        if (withText) {
            String text = text(t);
            m.put("text", text.length() > 200_000 ? text.substring(0, 200_000) : text);
            m.put("textTruncated", text.length() > 200_000);
        }
        return m;
    }

    /** The input lines a transaction's records occupy, as [first, last] runs (other drivers' records interleave). */
    public static List<List<Integer>> ranges(Transaction t) {
        List<List<Integer>> out = new ArrayList<>();
        int from = -1;
        int to = -1;
        for (Line l : t.lines) {
            if (from < 0) {
                from = l.first();
                to = l.last();
            } else if (l.first() == to + 1) {
                to = l.last();
            } else {
                out.add(List.of(from, to));
                from = l.first();
                to = l.last();
            }
        }
        if (from >= 0) {
            out.add(List.of(from, to));
        }
        return out;
    }

    /** One line per transaction, most recent last, for a person at a terminal. */
    public static String text(String source, Result r, Filter f, int last) {
        StringBuilder sb = new StringBuilder();
        List<Transaction> kept = r.transactions.stream().filter(f::keeps).toList();
        sb.append(source).append(": ").append(r.lines).append(" line(s), ").append(r.records).append(" record(s), ")
            .append(r.transactions.size()).append(" transaction(s)");
        if (kept.size() != r.transactions.size()) {
            sb.append(", ").append(kept.size()).append(" matching");
        }
        sb.append('\n');
        for (Transaction t : kept.subList(Math.max(0, kept.size() - last), kept.size())) {
            List<List<Integer>> runs = ranges(t);
            sb.append(String.format("#%-4d %-20s %-10s %-24s lines %d-%d%s  ", t.n, t.driver, tag(t.channel),
                t.start == null ? "" : t.start, t.firstLine, t.lastLine, runs.size() > 1 ? " (" + runs.size() + " runs)" : ""));
            if (t.opener == null) {
                sb.append("(no event in view)  ");
            }
            if (t.operation != null) {
                sb.append(t.operation).append(t.className == null ? "" : " " + t.className).append(t.dn == null ? "" : " " + t.dn).append("  ");
            }
            sb.append(t.sets.size()).append(" set(s), ").append(t.policyCount()).append(" polic").append(t.policyCount() == 1 ? "y" : "ies");
            String w = t.worst();
            sb.append("  status ").append(w == null ? "-" : w);
            if (!t.vetoes.isEmpty()) {
                sb.append("  VETO");
            }
            if (t.opener == TraceMarker.SUBSCRIBER_EVENT && !t.ended) {
                sb.append("  (no end in view)");
            }
            sb.append('\n');
        }
        sb.append("Best effort: only the engine's own wording is read; --transaction N shows one transaction's raw text.\n");
        return sb.toString();
    }
}
