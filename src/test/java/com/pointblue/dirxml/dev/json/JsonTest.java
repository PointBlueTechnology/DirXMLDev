package com.pointblue.dirxml.dev.json;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The hand-rolled JSON reader/writer promoted for forms/PRD use. */
public class JsonTest {

    @Test
    public void parsePreservesKeyOrderAndNumberText() {
        String src = "{\"z\":1,\"a\":2.50,\"m\":[1,2,3],\"t\":true,\"f\":false,\"n\":null,\"s\":\"hi\"}";
        Object v = Json.parse(src);
        Map<String, Object> m = Json.asMap(v);
        assertEquals(List.of("z", "a", "m", "t", "f", "n", "s"), List.copyOf(m.keySet()));
        assertEquals("2.50", m.get("a").toString());   // Num keeps original text, not 2.5
        assertEquals(2, ((Number) m.get("a")).intValue());
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertTrue(m.containsKey("n"));
        assertEquals(null, m.get("n"));
        assertEquals(List.of(1L, 2L, 3L).size(), Json.asList(m.get("m")).size());
    }

    @Test
    public void prettyIsTwoSpaceIndentedKeyOrderPreserved() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", "x");
        String pretty = Json.pretty(m);
        assertEquals("{\n  \"b\": 1,\n  \"a\": \"x\"\n}", pretty);
    }

    @Test
    public void compactHasNoWhitespace() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", "x");
        String compact = Json.compact(m);
        assertEquals("{\"b\":1,\"a\":\"x\"}", compact);
        assertFalse(compact.contains(" "));
        assertFalse(compact.contains("\n"));
    }

    @Test
    public void compactOfParseRoundTripsSemanticaly() {
        String src = "{\"components\":[{\"key\":\"a\",\"type\":\"textfield\",\"validate\":{\"required\":true}}],"
            + "\"title\":\"T\",\"inlinescripts\":\"\",\"externalScripts\":[],\"localization\":{\"en\":{\"Title\":\"T\"}}}";
        Object parsed = Json.parse(src);
        String compact = Json.compact(parsed);
        Object reparsed = Json.parse(compact);
        assertEquals(parsed, reparsed);
    }

    @Test
    public void prettyThenParseIsSemanticallyEqual() {
        String src = "{\"a\":[1,2,{\"b\":\"x\\ny\"}],\"c\":null}";
        Object v1 = Json.parse(src);
        Object v2 = Json.parse(Json.pretty(v1));
        assertEquals(v1, v2);
    }

    @Test
    public void numEqualityIgnoresTrailingZerosButKeepsText() {
        Json.Num a = Json.Num.of("1.0");
        Json.Num b = Json.Num.of("1.00");
        assertEquals(a, b);
        assertEquals("1.0", a.toString());
        assertEquals("1.00", b.toString());
    }

    @Test
    public void typedAccessorsAreLenient() {
        assertEquals(new LinkedHashMap<>(), Json.asMap("not a map"));
        assertTrue(Json.asList(null).isEmpty());
        assertFalse(Json.asBool(null));
        assertEquals(5, Json.asInt(Json.Num.of("5"), 0));
        assertEquals(0, Json.asInt("nope", 0));
    }
}
