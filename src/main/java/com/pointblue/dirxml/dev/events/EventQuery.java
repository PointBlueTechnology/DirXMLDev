package com.pointblue.dirxml.dev.events;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The selectors of {@code query events}, turned into one SQL statement with bound parameters —
 * each the pattern the store's contract names for it. Selectors combine with AND. Every query
 * has a limit (default 100, at most 1000) and an order: a single object's timeline ascends, all
 * else newest first.
 */
public final class EventQuery {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    public String dn;          // the object's timeline
    public String under;       // a container: everything below it
    public String name;        // the end of a DN
    public String driverDn;    // srcdriver
    public String policy;      // with driverDn: PolicyLogger rows at that policy
    public String stage;       // input | output
    public Boolean own;        // true: the driver's own rows; false: what its policies logged
    public List<String> types = new ArrayList<>();
    public String className;
    public Instant since;
    public Instant until;
    public String eventId;
    public String text;        // free text over the JSON
    public String attr;        // modify events that touched the attribute
    public int limit = DEFAULT_LIMIT;

    /** The statement and its parameters, in order. */
    public record Sql(String text, List<Object> params) {
    }

    /** True when at least one selector an index serves is set: free text alone scans the table. */
    public boolean indexed() {
        return dn != null || under != null || name != null || driverDn != null || eventId != null || since != null || until != null;
    }

    public Sql sql(String table) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (dn != null) {
            where.add("srcdn = ?");
            params.add(dn);
        }
        if (under != null) {
            where.add("srcdn LIKE ? ESCAPE ''");
            params.add((under.endsWith("\\") ? under : under + "\\") + "%");
        }
        if (name != null) {
            where.add("reverse(srcdn) LIKE reverse(?) ESCAPE ''");
            params.add("%" + name);
        }
        if (driverDn != null) {
            where.add("srcdriver = ?");
            params.add(driverDn);
        }
        if (policy != null) {
            where.add("policy = ?");
            params.add(policy);
        }
        if (stage != null) {
            where.add("stage = ?");
            params.add(stage);
        }
        if (own != null) {
            where.add(own ? "policy IS NULL" : "policy IS NOT NULL");
        }
        if (!types.isEmpty()) {
            StringBuilder in = new StringBuilder("eventtype IN (");
            for (int i = 0; i < types.size(); i++) {
                in.append(i == 0 ? "?" : ", ?");
                params.add(types.get(i));
            }
            where.add(in.append(')').toString());
        }
        if (className != null) {
            where.add("classname = ?");
            params.add(className);
        }
        if (since != null) {
            where.add("cachedtime >= ?");
            params.add(java.sql.Timestamp.from(since));
        }
        if (until != null) {
            where.add("cachedtime < ?");
            params.add(java.sql.Timestamp.from(until));
        }
        if (eventId != null) {
            where.add("eventid = ?");
            params.add(eventId);
        }
        if (text != null) {
            where.add("eventjson::text ILIKE ?");
            params.add("%" + text + "%");
        }
        if (attr != null) {
            // jsonb_exists, not the ? operator: JDBC would read ? as a parameter marker
            where.add("eventtype = 'modify' AND jsonb_exists(eventjson -> 'attributes', ?)");
            params.add(attr);
        }
        String order = eventId != null ? "policy NULLS FIRST, id" : dn != null ? "cachedtime, id" : "cachedtime DESC, id DESC";
        StringBuilder sb = new StringBuilder("SELECT id, eventid, classname, srcdn, srcentryid, eventtype, cachedtime, srcdriver, channel, policy, stage, schemaversion, eventjson::text AS eventjson, xmlevent FROM ")
            .append(table);
        if (!where.isEmpty()) {
            sb.append(" WHERE ").append(String.join(" AND ", where));
        }
        sb.append(" ORDER BY ").append(order).append(" LIMIT ").append(Math.max(1, Math.min(limit, MAX_LIMIT)));
        return new Sql(sb.toString(), params);
    }

    /** {@code 24h}, {@code 7d}, {@code 30m}, or an ISO instant / date. */
    public static Instant parseTime(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.matches("\\d+[smhd]")) {
            long n = Long.parseLong(t.substring(0, t.length() - 1));
            Duration d = switch (t.charAt(t.length() - 1)) {
                case 's' -> Duration.ofSeconds(n);
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
            return Instant.now().minus(d);
        }
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            return java.time.LocalDate.parse(s.trim()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        }
    }

    public static List<String> types(String csv) {
        List<String> out = new ArrayList<>();
        for (String t : csv.split(",")) {
            String x = t.trim().toLowerCase(Locale.ROOT);
            if (!x.isEmpty()) {
                if (!Arrays.asList("add", "modify", "delete", "sync", "rename", "move").contains(x)) {
                    throw new IllegalArgumentException("event type '" + x + "' is not add, modify, delete, sync, rename or move");
                }
                out.add(x);
            }
        }
        return out;
    }
}
