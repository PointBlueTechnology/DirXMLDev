package com.pointblue.dirxml.dev.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public class EventMaskTest {
    static final String ADD = "{\"event-type\":\"add\",\"schemaVersion\":2,\"class-name\":\"User\",\"src-dn\":\"\\\\TREE\\\\data\\\\people\\\\jdoe\","
        + "\"attributes\":{\"Given Name\":{\"type\":\"string\",\"value\":\"John\"},\"Surname\":{\"type\":\"string\",\"value\":\"Doe\"},"
        + "\"Full Name\":\"John Doe\",\"Internet EMail Address\":\"john.doe@example.com\",\"Title\":\"Engineer\"}}";

    @Test
    public void namesMailAndTheObjectsNameAreMaskedConsistently() {
        EventMask mask = new EventMask();
        Map<String, Object> r = EventTest.row("add", ADD, "<add src-dn=\"\\TREE\\data\\people\\jdoe\"><value>John</value><value>Doe</value><value>john.doe@example.com</value></add>", 2);
        Event e = mask.mask(Event.fromRow(r));
        String json = com.pointblue.dirxml.dev.json.Json.compact(e.json());
        assertFalse(json, json.contains("John"));
        assertFalse(json, json.contains("Doe"));
        assertFalse(json, json.contains("john.doe@"));
        assertTrue(json, json.contains("Engineer"));
        assertFalse(e.srcDn(), e.srcDn().endsWith("\\jdoe"));
        assertFalse(e.xml(), e.xml().contains("John") || e.xml().contains("jdoe"));
        // the same person again maps the same way
        Event again = mask.mask(Event.fromRow(r));
        assertEquals(e.srcDn(), again.srcDn());
        assertEquals(e.json().get("attributes"), again.json().get("attributes"));
        // another person maps differently
        Map<String, Object> r2 = EventTest.row("add", ADD.replace("John", "Mary").replace("Doe", "Roe").replace("jdoe", "mroe"), null, 2);
        r2.put("srcdn", "\\TREE\\data\\people\\mroe");
        Event other = mask.mask(Event.fromRow(r2));
        assertNotEquals(e.srcDn(), other.srcDn());
    }

    @Test
    public void anEventWithoutPeopleIsUntouched() {
        Event e = Event.fromRow(EventTest.row("delete", "{\"event-type\":\"delete\",\"class-name\":\"Group\"}", "<delete/>", 2));
        assertEquals(e, new EventMask().mask(e));
    }
}
