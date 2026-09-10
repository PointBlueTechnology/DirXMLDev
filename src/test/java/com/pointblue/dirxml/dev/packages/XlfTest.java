package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;
import org.w3c.dom.Element;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class XlfTest {

    @Test
    public void resolvesFromTheBundleElseDefault() {
        Map<String, String> en = Map.of("pkg.gcv.header", "Synchronization Settings (en)");
        assertEquals("Synchronization Settings (en)", Xlf.localize("xlfid(pkg.gcv.header)Synchronization Settings", en));
        assertEquals("Placement type", Xlf.localize("xlfid(pkg.unknown)Placement type", en));
        assertEquals("plain", Xlf.localize("plain", en));
        assertEquals("A; B", Xlf.localize("xlfid(a)A; xlfid(b)B", Map.of()));
    }

    @Test
    public void localizesAttributesAndText() {
        Element e = CanonicalXml.parse("<definitions display-name=\"xlfid(k1)Default\"><definition display-name=\"xlfid(k2)Name\">"
            + "<description>xlfid(k3)Text\nmore</description><enum-choice display-name=\"xlfid(k4)mirrored\">mirrored</enum-choice></definition></definitions>")
            .getDocumentElement();
        Xlf.localize(e, Map.of("k2", "Localized name"));
        assertFalse(Xlf.hasXlf(e));
        assertEquals("Default", e.getAttribute("display-name"));
        Element def = (Element) e.getFirstChild();
        assertEquals("Localized name", def.getAttribute("display-name"));
        assertEquals("Text\nmore", PromptEngine.text(PromptEngine.child(def, "description")));
        assertEquals("mirrored", PromptEngine.child(def, "enum-choice").getTextContent());
    }
}
