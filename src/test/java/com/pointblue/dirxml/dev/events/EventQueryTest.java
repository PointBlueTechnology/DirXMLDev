package com.pointblue.dirxml.dev.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.Test;

public class EventQueryTest {
    @Test
    public void anObjectsTimelineAscends() {
        EventQuery q = new EventQuery();
        q.dn = "\\T\\data\\people\\jdoe";
        EventQuery.Sql s = q.sql("public.dxmlevent");
        assertTrue(s.text(), s.text().contains("WHERE srcdn = ?"));
        assertTrue(s.text(), s.text().endsWith("ORDER BY cachedtime, id LIMIT 100"));
        assertEquals(List.of("\\T\\data\\people\\jdoe"), s.params());
    }

    @Test
    public void aSubtreeEndsTheContainerWithABackslashAndEscapesNothing() {
        EventQuery q = new EventQuery();
        q.under = "\\T\\data\\people";
        EventQuery.Sql s = q.sql("t");
        assertTrue(s.text().contains("srcdn LIKE ? ESCAPE ''"));
        assertEquals("\\T\\data\\people\\%", s.params().get(0));
        q.under = "\\T\\data\\people\\";
        assertEquals("\\T\\data\\people\\%", q.sql("t").params().get(0));
    }

    @Test
    public void everySelectorCombinesWithAndAndTheLimitIsCapped() {
        EventQuery q = new EventQuery();
        q.name = "\\jdoe";
        q.driverDn = "\\T\\system\\ds\\AD";
        q.policy = "sub-ctp";
        q.stage = "input";
        q.own = false;
        q.types = EventQuery.types("add, Modify");
        q.className = "User";
        q.since = Instant.parse("2026-10-01T00:00:00Z");
        q.until = Instant.parse("2026-10-02T00:00:00Z");
        q.text = "jdoe";
        q.attr = "Surname";
        q.limit = 5000;
        EventQuery.Sql s = q.sql("t");
        String t = s.text();
        assertTrue(t, t.contains("reverse(srcdn) LIKE reverse(?) ESCAPE ''"));
        assertTrue(t, t.contains("srcdriver = ?"));
        assertTrue(t, t.contains("policy = ?"));
        assertTrue(t, t.contains("stage = ?"));
        assertTrue(t, t.contains("policy IS NOT NULL"));
        assertTrue(t, t.contains("eventtype IN (?, ?)"));
        assertTrue(t, t.contains("classname = ?"));
        assertTrue(t, t.contains("cachedtime >= ?") && t.contains("cachedtime < ?"));
        assertTrue(t, t.contains("eventjson::text ILIKE ?"));
        assertTrue(t, t.contains("jsonb_exists(eventjson -> 'attributes', ?)"));
        assertTrue(t, t.endsWith("ORDER BY cachedtime DESC, id DESC LIMIT 1000"));
        assertEquals("%\\jdoe", s.params().get(0));
        assertEquals("%jdoe%", s.params().get(9));
        assertEquals(11, s.params().size());
        assertTrue(q.indexed());
    }

    @Test
    public void oneEngineEventListsTheDriverRowFirst() {
        EventQuery q = new EventQuery();
        q.eventId = "edir3#1#1";
        assertTrue(q.sql("t").text().contains("ORDER BY policy NULLS FIRST, id"));
    }

    @Test
    public void freeTextAloneIsNotIndexed() {
        EventQuery q = new EventQuery();
        q.text = "x";
        assertFalse(q.indexed());
        q.since = Instant.now();
        assertTrue(q.indexed());
    }

    @Test
    public void timesAndTypesParse() {
        assertTrue(EventQuery.parseTime("24h").isAfter(Instant.now().minusSeconds(25 * 3600)));
        assertTrue(EventQuery.parseTime("7d").isBefore(Instant.now().minusSeconds(6 * 86400)));
        assertEquals(Instant.parse("2026-10-07T12:00:00Z"), EventQuery.parseTime("2026-10-07T12:00:00Z"));
        assertEquals(List.of("add", "delete"), EventQuery.types("add,delete"));
        try {
            EventQuery.types("purge");
            throw new AssertionError("accepted");
        } catch (IllegalArgumentException expected) {
            // refused
        }
    }
}
