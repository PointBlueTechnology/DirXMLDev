package com.pointblue.dirxml.dev.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class EventTest {
    static final String MODIFY = "{\"event-type\":\"modify\",\"schemaVersion\":2,\"class-name\":\"User\",\"event-id\":\"edir3#2\",\"src-dn\":\"\\\\TREE\\\\data\\\\people\\\\jdoe\","
        + "\"attributes\":{\"Given Name\":{\"remove-values\":[{\"type\":\"string\",\"value\":\"Johnny\"}],\"add-values\":[{\"type\":\"string\",\"value\":\"John\"}]},"
        + "\"Telephone Number\":{\"remove-all-values\":true,\"add-values\":[{\"type\":\"teleNumber\",\"value\":\"555-0102\"},{\"type\":\"teleNumber\",\"value\":\"555-0103\"}]},"
        + "\"Facsimile Telephone Number\":{\"add-values\":[{\"type\":\"structured\",\"components\":{\"faxNumber\":\"555-0199\",\"faxBitCount\":\"0\"}}]}}}";

    static Map<String, Object> row(String type, String json, String xml, int schema) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", 4711L);
        r.put("eventid", "edir3#2");
        r.put("classname", "User");
        r.put("srcdn", "\\TREE\\data\\people\\jdoe");
        r.put("srcentryid", "35868");
        r.put("eventtype", type);
        r.put("cachedtime", java.sql.Timestamp.from(Instant.parse("2026-10-07T14:16:00Z")));
        r.put("srcdriver", "\\TREE\\system\\driverset1\\Active Directory Driver");
        r.put("channel", null);
        r.put("policy", null);
        r.put("stage", null);
        r.put("schemaversion", (short) schema);
        r.put("eventjson", json);
        r.put("xmlevent", xml);
        return r;
    }

    @Test
    public void aRowBecomesAnEventWithItsChanges() {
        Event e = Event.fromRow(row("modify", MODIFY, "<nds/>", 2));
        assertEquals(4711L, e.id());
        assertEquals("Active Directory Driver", e.driverName());
        assertEquals("jdoe", Event.leaf(e.srcDn()));
        assertFalse(e.fromPolicy());
        assertEquals(Instant.parse("2026-10-07T14:16:00Z"), e.time());
        List<Event.Change> ch = e.changes();
        assertEquals(3, ch.size());
        assertEquals("Given Name", ch.get(0).attribute());
        assertEquals(List.of("Johnny"), ch.get(0).removed());
        assertEquals(List.of("John"), ch.get(0).added());
        assertTrue(ch.get(1).removeAll());
        assertEquals(List.of("555-0102", "555-0103"), ch.get(1).added());
        assertEquals(List.of("faxNumber=555-0199, faxBitCount=0"), ch.get(2).added());
        Map<String, Object> m = e.toMap(false);
        assertEquals(Boolean.TRUE, m.get("hasXml"));
        assertNull(m.get("xml"));
        assertEquals(3, ((List<?>) m.get("changes")).size());
        assertEquals("<nds/>", e.toMap(true).get("xml"));
    }

    @Test
    public void aPolicyRowKnowsItsPolicyAndAVersionOneRowItsAge() {
        Map<String, Object> r = row("add", "{\"event-type\":\"add\",\"attributes\":{\"Surname\":\"Doe\"}}", null, 1);
        r.put("policy", "NOVLADDCFG-sub-ctp");
        r.put("channel", "subscriber");
        r.put("stage", "input");
        Event e = Event.fromRow(r);
        assertTrue(e.fromPolicy());
        assertEquals(1, e.schemaVersion());
        assertTrue(e.changes().isEmpty());
        assertEquals("Doe", Event.valueText(((Map<?, ?>) e.json().get("attributes")).get("Surname")));
    }

    @Test
    public void valuesReadPlainStringsObjectsAndComponents() {
        assertEquals("x", Event.valueText("x"));
        assertEquals("", Event.valueText(null));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("type", "string");
        v.put("value", "y");
        assertEquals("y", Event.valueText(v));
        assertEquals(List.of("a", "b"), Event.values(List.of("a", "b")));
    }
}
