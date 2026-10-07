package com.pointblue.dirxml.dev.events;

import com.pointblue.dirxml.dev.json.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the Event Logger's store (its {@code docs/store.md}, schema version 2): an event the
 * EventLogger driver captured on its subscriber channel ({@code policy == null}), or a document a
 * policy logged at a named point of its channel through {@code PolicyLogger}.
 *
 * @param id            the row's key
 * @param eventId       the engine's event-id (the same for every PolicyLogger row of one event)
 * @param className     class-name of the event
 * @param srcDn         src-dn in the engine's slash form, may be null
 * @param srcEntryId    src-entry-id, may be null
 * @param type          add, modify, delete, sync, rename or move
 * @param time          cachedtime
 * @param srcDriver     DN of the driver the event came from (slash form), may be null
 * @param channel       subscriber or publisher for PolicyLogger rows, else null
 * @param policy        the policy name or DN as the policy passed it, else null
 * @param stage         input or output for PolicyLogger rows, else null
 * @param schemaVersion the JSON's shape: 2 now, 1 before release 2.0.0
 * @param json          eventjson, parsed
 * @param xml           xmlevent, or null when the driver ran with storeXML=false
 */
public record Event(long id, String eventId, String className, String srcDn, String srcEntryId, String type, Instant time,
                    String srcDriver, String channel, String policy, String stage, int schemaVersion,
                    Map<String, Object> json, String xml) {

    /** The leaf of a slash-form DN ({@code \TREE\system\ds\AD} → {@code AD}); the DN itself when it has no backslash. */
    public static String leaf(String slashDn) {
        if (slashDn == null) {
            return null;
        }
        int i = slashDn.lastIndexOf('\\');
        return i < 0 ? slashDn : slashDn.substring(i + 1);
    }

    /** The driver's name as a tree knows it: the leaf of {@code srcDriver}. */
    public String driverName() {
        return leaf(srcDriver);
    }

    /** True for a document a policy logged, false for the EventLogger driver's own rows. */
    public boolean fromPolicy() {
        return policy != null;
    }

    /** From a row as the store returns it (column names lower-case, {@code eventjson} as text or map). */
    @SuppressWarnings("unchecked")
    public static Event fromRow(Map<String, Object> row) {
        Object j = row.get("eventjson");
        Map<String, Object> json;
        if (j instanceof Map) {
            json = (Map<String, Object>) j;
        } else if (j != null) {
            Object parsed = Json.parse(String.valueOf(j));
            json = parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
        } else {
            json = new LinkedHashMap<>();
        }
        Object t = row.get("cachedtime");
        Instant time = t instanceof Instant ? (Instant) t : t instanceof java.sql.Timestamp ? ((java.sql.Timestamp) t).toInstant() : t == null ? null : Instant.parse(String.valueOf(t));
        Object sv = row.get("schemaversion");
        int schema = sv == null ? 1 : ((Number) sv).intValue();
        Object id = row.get("id");
        return new Event(id == null ? 0 : ((Number) id).longValue(), str(row.get("eventid")), str(row.get("classname")), str(row.get("srcdn")),
            str(row.get("srcentryid")), str(row.get("eventtype")), time, str(row.get("srcdriver")), str(row.get("channel")),
            str(row.get("policy")), str(row.get("stage")), schema, json, str(row.get("xmlevent")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** A change of one attribute in a modify event, as the diff view shows it. */
    public record Change(String attribute, boolean removeAll, List<String> removed, List<String> added) {
    }

    /**
     * The attribute changes of a modify event (schema version 2; version 1 rows give their whole
     * element text as one value). Empty for any other type.
     */
    @SuppressWarnings("unchecked")
    public List<Change> changes() {
        List<Change> out = new ArrayList<>();
        if (!"modify".equals(type) || !(json.get("attributes") instanceof Map)) {
            return out;
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) json.get("attributes")).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            out.add(new Change(e.getKey(), Boolean.TRUE.equals(m.get("remove-all-values")), values(m.get("remove-values")), values(m.get("add-values"))));
        }
        return out;
    }

    /** The plain text of a value as the store writes it: a string, {@code value}, or the components joined. */
    @SuppressWarnings("unchecked")
    public static String valueText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            if (m.get("value") != null) {
                return String.valueOf(m.get("value"));
            }
            if (m.get("components") instanceof Map) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Object> c : ((Map<String, Object>) m.get("components")).entrySet()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(c.getKey()).append('=').append(c.getValue());
                }
                return sb.toString();
            }
            return Json.compact(m);
        }
        return String.valueOf(v);
    }

    static List<String> values(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) {
                out.add(valueText(o));
            }
        } else if (v != null) {
            out.add(valueText(v));
        }
        return out;
    }

    /** The row for JSON output: the columns, the parsed JSON, the XML when asked. */
    public Map<String, Object> toMap(boolean withXml) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("eventId", eventId);
        m.put("type", type);
        m.put("class", className);
        m.put("srcDn", srcDn);
        m.put("srcEntryId", srcEntryId);
        m.put("time", time == null ? null : time.toString());
        m.put("srcDriver", srcDriver);
        m.put("driver", driverName());
        m.put("channel", channel);
        m.put("policy", policy);
        m.put("stage", stage);
        m.put("schemaVersion", schemaVersion);
        m.put("json", json);
        if (withXml) {
            m.put("xml", xml);
        } else {
            m.put("hasXml", xml != null);
        }
        if ("modify".equals(type)) {
            List<Object> ch = new ArrayList<>();
            for (Change c : changes()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("attribute", c.attribute());
                cm.put("removeAll", c.removeAll());
                cm.put("removed", c.removed());
                cm.put("added", c.added());
                ch.add(cm);
            }
            m.put("changes", ch);
        }
        return m;
    }
}
