package com.pointblue.dirxml.dev.model;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A parsed Form.io + NetIQ document: title/display, component walk, languages, scripts. */
public class FormDocumentTest {

    private static final String FORM_JSON =
        "{"
        + "\"title\":\"Sample\","
        + "\"display\":\"form\","
        + "\"inlinescripts\":\"function f(){}\","
        + "\"externalScripts\":[\"https://example.com/a.js\"],"
        + "\"localization\":{\"en\":{\"Title\":\"Sample\"},\"fr\":{\"Title\":\"Exemple\"}},"
        + "\"components\":["
        + "  {\"key\":\"title\",\"type\":\"title\",\"label\":\"title\",\"input\":false},"
        + "  {\"key\":\"name\",\"type\":\"textfield\",\"label\":\"Name\",\"input\":true,"
        + "   \"validate\":{\"required\":true},\"hidden\":false},"
        + "  {\"key\":\"panel1\",\"type\":\"panel\",\"input\":false,\"components\":["
        + "     {\"key\":\"nested\",\"type\":\"textfield\",\"input\":true,\"hidden\":true}"
        + "  ]},"
        + "  {\"key\":\"cols\",\"type\":\"columns\",\"input\":false,\"columns\":["
        + "     {\"width\":6,\"components\":[{\"key\":\"c1\",\"type\":\"textfield\",\"input\":true}]},"
        + "     {\"width\":6,\"components\":[{\"key\":\"c2\",\"type\":\"textfield\",\"input\":true,"
        + "        \"conditional\":{\"show\":true,\"when\":\"name\",\"eq\":\"x\"}}]}"
        + "  ]}"
        + "]}";

    @Test
    public void titleDisplayScriptsAndLanguages() {
        FormDocument fd = FormDocument.parse(FORM_JSON);
        assertEquals("Sample", fd.title());
        assertEquals("form", fd.display());
        assertTrue(fd.hasInlineScripts());
        assertEquals(List.of("https://example.com/a.js"), fd.externalScripts());
        assertEquals(2, fd.languages().size());
        assertTrue(fd.languages().contains("en"));
        assertTrue(fd.languages().contains("fr"));
    }

    @Test
    public void noInlineScriptsWhenBlank() {
        FormDocument fd = FormDocument.parse("{\"components\":[],\"inlinescripts\":\"\"}");
        assertFalse(fd.hasInlineScripts());
    }

    @Test
    public void componentsWalkTopLevelPanelAndColumns() {
        FormDocument fd = FormDocument.parse(FORM_JSON);
        List<FormDocument.Component> comps = fd.components();
        // top-level: title, name, panel1, cols; plus nested + c1 + c2
        assertEquals(7, comps.size());

        FormDocument.Component name = find(comps, "name");
        assertTrue(name.input);
        assertTrue(name.required);
        assertFalse(name.hidden);

        FormDocument.Component nested = find(comps, "nested");
        assertEquals("components[2].components[0]", nested.path);
        assertTrue(nested.hidden);

        FormDocument.Component c1 = find(comps, "c1");
        assertEquals("components[3].columns[0].components[0]", c1.path);

        FormDocument.Component c2 = find(comps, "c2");
        assertTrue(c2.conditional != null && c2.conditional.contains("name"));
    }

    private static FormDocument.Component find(List<FormDocument.Component> comps, String key) {
        for (FormDocument.Component c : comps) {
            if (key.equals(c.key)) {
                return c;
            }
        }
        throw new AssertionError("no component with key " + key);
    }
}
