package com.pointblue.dirxml.dev.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.pointblue.dirxml.dev.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FormEditorTest {

    private static Map<String, Object> parse(String json) {
        return Json.asMap(Json.parse(json));
    }

    private static final String DOC = "{\"components\":["
        + "{\"key\":\"a\",\"type\":\"textfield\",\"label\":\"A\"},"
        + "{\"key\":\"panel1\",\"type\":\"panel\",\"components\":["
        + "{\"key\":\"b\",\"type\":\"textfield\"}]},"
        + "{\"key\":\"cols\",\"type\":\"columns\",\"columns\":["
        + "{\"components\":[{\"key\":\"c\",\"type\":\"textfield\"}]},"
        + "{\"components\":[]}]},"
        + "{\"key\":\"submit\",\"type\":\"button\"}"
        + "]}";

    @Test
    public void allFindsEveryComponentIncludingNested() {
        Map<String, Object> root = parse(DOC);
        List<FormEditor.Located> all = FormEditor.all(root);
        List<String> keys = all.stream().map(l -> Json.asString(l.component.get("key"))).toList();
        assertEquals(List.of("a", "panel1", "b", "cols", "c", "submit"), keys);
    }

    @Test
    public void findLocatesNestedComponent() {
        Map<String, Object> root = parse(DOC);
        FormEditor.Located l = FormEditor.find(root, "c");
        assertNotNull(l);
        assertEquals("textfield", Json.asString(l.component.get("type")));
        assertNull(FormEditor.find(root, "nope"));
    }

    @Test
    public void globalKeyCountsSeesDuplicatesAnywhere() {
        Map<String, Object> root = parse("{\"components\":["
            + "{\"key\":\"a\",\"type\":\"textfield\"},"
            + "{\"key\":\"panel1\",\"type\":\"panel\",\"components\":[{\"key\":\"a\",\"type\":\"select\"}]}"
            + "]}");
        Map<String, Integer> counts = FormEditor.globalKeyCounts(root);
        assertEquals(Integer.valueOf(2), counts.get("a"));
        assertEquals(Integer.valueOf(1), counts.get("panel1"));
    }

    @Test
    public void insertAtTopLevelPositions() {
        Map<String, Object> root = parse(DOC);
        Map<String, Object> n1 = FormEditor.minimal("textfield", "n1", "N1");
        FormEditor.insertAt(FormEditor.target(root, null), n1, FormEditor.Placement.first(null));
        assertEquals("n1", key(root, 0));

        Map<String, Object> n2 = FormEditor.minimal("textfield", "n2", "N2");
        FormEditor.insertAt(FormEditor.target(root, null), n2, FormEditor.Placement.after("a", null));
        assertEquals(List.of("n1", "a", "n2", "panel1", "cols", "submit"), topKeys(root));

        Map<String, Object> n3 = FormEditor.minimal("textfield", "n3", "N3");
        FormEditor.insertAt(FormEditor.target(root, null), n3, FormEditor.Placement.before("submit", null));
        assertEquals(List.of("n1", "a", "n2", "panel1", "cols", "n3", "submit"), topKeys(root));

        Map<String, Object> n4 = FormEditor.minimal("textfield", "n4", "N4");
        FormEditor.insertAt(FormEditor.target(root, null), n4, FormEditor.Placement.last(null));
        assertEquals("n4", topKeys(root).get(topKeys(root).size() - 1));
    }

    @Test
    public void insertIntoPanelAndIntoColumnsFirstColumn() {
        Map<String, Object> root = parse(DOC);
        Map<String, Object> np = FormEditor.minimal("textfield", "np", "NP");
        FormEditor.insertAt(FormEditor.target(root, "panel1"), np, FormEditor.Placement.last("panel1"));
        FormEditor.Located inPanel = FormEditor.find(root, "np");
        assertNotNull(inPanel);

        Map<String, Object> nc = FormEditor.minimal("textfield", "nc", "NC");
        FormEditor.insertAt(FormEditor.target(root, "cols"), nc, FormEditor.Placement.last("cols"));
        // landed in the FIRST column, alongside "c"
        @SuppressWarnings("unchecked")
        List<Object> columns = (List<Object>) root.get("components");
        Map<String, Object> colsComp = Json.asMap(columns.get(2));
        @SuppressWarnings("unchecked")
        List<Object> colList = (List<Object>) colsComp.get("columns");
        @SuppressWarnings("unchecked")
        List<Object> firstColComps = (List<Object>) Json.asMap(colList.get(0)).get("components");
        assertEquals(2, firstColComps.size());
        assertEquals("nc", Json.asString(Json.asMap(firstColComps.get(1)).get("key")));
    }

    @Test
    public void insertIntoColumnsCreatesFirstColumnWhenEmpty() {
        Map<String, Object> root = parse("{\"components\":[{\"key\":\"cols\",\"type\":\"columns\",\"columns\":[]}]}");
        Map<String, Object> comp = FormEditor.minimal("textfield", "x", "X");
        FormEditor.insertAt(FormEditor.target(root, "cols"), comp, FormEditor.Placement.last("cols"));
        assertNotNull(FormEditor.find(root, "x"));
    }

    @Test
    public void targetThrowsForUnknownContainer() {
        Map<String, Object> root = parse(DOC);
        try {
            FormEditor.target(root, "nope");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nope"));
        }
    }

    @Test
    public void removeDeletesAComponentAnywhere() {
        Map<String, Object> root = parse(DOC);
        assertTrue(FormEditor.remove(root, "c"));
        assertNull(FormEditor.find(root, "c"));
        assertFalse(FormEditor.remove(root, "c"));
        assertFalse(FormEditor.remove(root, "nope"));
    }

    @Test
    public void moveReordersWithinTheSameList() {
        Map<String, Object> root = parse(DOC);
        FormEditor.move(root, "a", FormEditor.Placement.last(null));
        assertEquals(List.of("panel1", "cols", "submit", "a"), topKeys(root));

        FormEditor.move(root, "a", FormEditor.Placement.first(null));
        assertEquals(List.of("a", "panel1", "cols", "submit"), topKeys(root));

        FormEditor.move(root, "a", FormEditor.Placement.after("cols", null));
        assertEquals(List.of("panel1", "cols", "a", "submit"), topKeys(root));
    }

    @Test
    public void moveReparentsIntoAnotherContainer() {
        Map<String, Object> root = parse(DOC);
        FormEditor.move(root, "a", FormEditor.Placement.last("panel1"));
        assertFalse(topKeys(root).contains("a"));
        FormEditor.Located found = FormEditor.find(root, "a");
        assertNotNull(found);
        @SuppressWarnings("unchecked")
        List<Object> panelComps = (List<Object>) Json.asMap(((List<Object>) root.get("components")).get(0)).get("components");
        assertTrue(panelComps.stream().anyMatch(o -> "a".equals(Json.asMap(o).get("key"))));
    }

    @Test
    public void moveRefusesToMoveIntoItself() {
        Map<String, Object> root = parse(DOC);
        try {
            FormEditor.move(root, "panel1", FormEditor.Placement.last("panel1"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("itself"));
        }
    }

    @Test
    public void moveOfUnknownKeyThrows() {
        Map<String, Object> root = parse(DOC);
        try {
            FormEditor.move(root, "nope", FormEditor.Placement.last(null));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("nope"));
        }
    }

    @Test
    public void setPropertyCreatesIntermediateObjects() {
        Map<String, Object> comp = FormEditor.minimal("textfield", "x", "X");
        FormEditor.setProperty(comp, "validate.maxLength", Json.Num.of("50"));
        Map<String, Object> validate = Json.asMap(comp.get("validate"));
        assertEquals(Json.Num.of("50"), validate.get("maxLength"));

        FormEditor.setProperty(comp, "data.values", List.of());
        assertNotNull(comp.get("data"));
    }

    @Test
    public void templateLoadsACapturedComponentAndMinimalFallsBackForUnknownType() {
        Map<String, Object> t = FormEditor.template("textfield");
        assertNotNull(t);
        assertEquals("textfield", Json.asString(t.get("type")));
        assertNull(FormEditor.template("no-such-type"));

        Map<String, Object> m = FormEditor.minimal("no-such-type", "k", "L");
        assertEquals("no-such-type", Json.asString(m.get("type")));
        assertEquals("k", Json.asString(m.get("key")));
        assertEquals("L", Json.asString(m.get("label")));
        assertEquals(Boolean.TRUE, m.get("input"));
    }

    @Test
    public void templateCopiesAreIndependentOfEachOther() {
        Map<String, Object> t1 = FormEditor.template("textfield");
        Map<String, Object> t2 = FormEditor.template("textfield");
        t1.put("key", "one");
        assertNotEquals(t1.get("key"), t2.get("key"));
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(java.util.Objects.equals(a, b));
    }

    @Test
    public void deepMergeIntoMergesObjectsAndReplacesEverythingElse() {
        Map<String, Object> base = parse("{\"validate\":{\"required\":false,\"maxLength\":10},\"tags\":[\"a\"],\"label\":\"L\"}");
        Map<String, Object> overlay = parse("{\"validate\":{\"required\":true},\"tags\":[\"b\",\"c\"],\"label\":\"M\"}");
        FormEditor.deepMergeInto(base, overlay);
        Map<String, Object> validate = Json.asMap(base.get("validate"));
        assertEquals(Boolean.TRUE, validate.get("required"));
        assertEquals(Json.Num.of("10"), validate.get("maxLength"));   // untouched key survives the merge
        assertEquals(List.of("b", "c"), base.get("tags"));            // arrays replace, not merge
        assertEquals("M", base.get("label"));
    }

    @Test
    public void deepMergeDoesNotAliasTheOverlay() {
        Map<String, Object> base = parse("{}");
        Map<String, Object> overlay = parse("{\"data\":{\"values\":[1]}}");
        FormEditor.deepMergeInto(base, overlay);
        Map<String, Object> overlayData = Json.asMap(overlay.get("data"));
        @SuppressWarnings("unchecked")
        List<Object> overlayValues = (List<Object>) overlayData.get("values");
        Map<String, Object> baseData = Json.asMap(base.get("data"));
        @SuppressWarnings("unchecked")
        List<Object> baseValues = (List<Object>) baseData.get("values");
        baseValues.add(2);
        assertEquals(1, overlayValues.size());
    }

    private static String key(Map<String, Object> root, int i) {
        return topKeys(root).get(i);
    }

    @SuppressWarnings("unchecked")
    private static List<String> topKeys(Map<String, Object> root) {
        return ((List<Object>) root.get("components")).stream().map(o -> Json.asString(Json.asMap(o).get("key"))).toList();
    }
}
