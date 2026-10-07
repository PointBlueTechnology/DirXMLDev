package com.pointblue.dirxml.dev.events;

import com.pointblue.dirxml.dev.clone.Pseudonymiser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pseudonymises what leaves the core from a store that holds real people: the given name, surname,
 * full and display names and mail of each event, and the object's own name in every DN, the way
 * the vault clone's {@link Pseudonymiser} does, consistently within one process so a timeline
 * still lines up. The store is queried as it is; only the output is masked.
 */
public final class EventMask {
    private final Pseudonymiser p = new Pseudonymiser();
    /** real value → pseudonym, longest first when replacing so a surname inside a full name goes last */
    private final Map<String, String> map = new TreeMap<>((a, b) -> b.length() != a.length() ? b.length() - a.length() : a.compareTo(b));

    private static final List<String> GIVEN = List.of("Given Name", "givenName");
    private static final List<String> SURNAME = List.of("Surname", "sn");
    private static final List<String> FULL = List.of("Full Name", "fullName", "displayName", "Display Name");
    private static final List<String> MAIL = List.of("Internet EMail Address", "mail");

    public Event mask(Event e) {
        Map<String, Object> attrs = attributes(e.json());
        String given = first(attrs, GIVEN);
        String sn = first(attrs, SURNAME);
        if (given != null) {
            map.putIfAbsent(given, p.givenName(given));
        }
        if (sn != null) {
            map.putIfAbsent(sn, p.surname(sn));
        }
        for (String k : FULL) {
            String v = first(attrs, List.of(k));
            if (v != null) {
                map.putIfAbsent(v, p.fullName(v, given, sn));
            }
        }
        for (String k : MAIL) {
            String v = first(attrs, List.of(k));
            if (v != null) {
                map.putIfAbsent(v, p.mail(v, given, sn));
            }
        }
        // the object's name: a CN that is a person's name gets a login-like pseudonym from the mapped names
        String leaf = Event.leaf(e.srcDn());
        if (leaf != null && !leaf.isBlank() && (given != null || sn != null)) {
            map.putIfAbsent(leaf, loginOf(given, sn, leaf));
        }
        if (map.isEmpty()) {
            return e;
        }
        String jsonText = com.pointblue.dirxml.dev.json.Json.compact(e.json());
        String masked = replaceAll(jsonText);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) com.pointblue.dirxml.dev.json.Json.parse(masked);
        return new Event(e.id(), e.eventId(), e.className(), replaceAll(e.srcDn()), e.srcEntryId(), e.type(), e.time(), e.srcDriver(),
            e.channel(), e.policy(), e.stage(), e.schemaVersion(), json, e.xml() == null ? null : replaceAll(e.xml()));
    }

    private String loginOf(String given, String sn, String real) {
        String g = given == null ? "" : map.getOrDefault(given, given);
        String s = sn == null ? "" : map.getOrDefault(sn, sn);
        String login = (g.isEmpty() ? "" : g.substring(0, 1) + ".") + s;
        login = login.toLowerCase().replaceAll("[^a-z0-9.]", "");
        return login.isEmpty() ? "user" + Integer.toHexString(real.hashCode() & 0xffffff) : login;
    }

    String replaceAll(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Map.Entry<String, String> m : map.entrySet()) {
            if (!m.getKey().isEmpty() && !m.getKey().equals(m.getValue())) {
                out = out.replace(m.getKey(), m.getValue());
                // JSON holds backslashes escaped; a DN value inside it still matches by its leaf
            }
        }
        return out;
    }

    /** The attribute values of an add, sync or modify (added values) event, by attribute name, first value only. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> attributes(Map<String, Object> json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(json.get("attributes") instanceof Map)) {
            return out;
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) json.get("attributes")).entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map && ((Map<String, Object>) v).containsKey("add-values")) {
                List<String> added = Event.values(((Map<String, Object>) v).get("add-values"));
                if (!added.isEmpty()) {
                    out.put(e.getKey(), added.get(0));
                }
            } else if (v instanceof List) {
                List<String> vals = Event.values(v);
                if (!vals.isEmpty()) {
                    out.put(e.getKey(), vals.get(0));
                }
            } else if (v != null && !(v instanceof Map && ((Map<String, Object>) v).containsKey("remove-values"))) {
                out.put(e.getKey(), Event.valueText(v));
            }
        }
        return out;
    }

    private static String first(Map<String, Object> attrs, List<String> names) {
        for (String n : names) {
            Object v = attrs.get(n);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }
}
