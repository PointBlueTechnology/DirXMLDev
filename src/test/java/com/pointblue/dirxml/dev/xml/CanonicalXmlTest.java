package com.pointblue.dirxml.dev.xml;

import com.pointblue.dirxml.sim.Xds;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Offline tests for {@link CanonicalXml}: idempotence, attribute ordering/escaping,
 * block vs. mixed layout, DOCTYPE handling, namespace round-trips, and cross-DOM
 * (JDK vs. Novell) agreement.
 */
public class CanonicalXmlTest {

    @Test
    public void idempotentOnRealisticDirXmlScriptPolicyWithSignificantText() {
        String policyXml =
            "<?xml version=\"1.0\"?>\n" +
            "<policy>\n" +
            "  <rule>\n" +
            "    <description>Build Full Name</description>\n" +
            "    <conditions/>\n" +
            "    <actions>\n" +
            "      <do-set-dest-attr-value class-name=\"User\" name=\"Full Name\">\n" +
            "        <arg-value type=\"string\">\n" +
            "          <token-text> Smith  Jane </token-text>\n" +
            "        </arg-value>\n" +
            "      </do-set-dest-attr-value>\n" +
            "      <do-set-local-variable name=\"notes\">\n" +
            "        <arg-value type=\"string\">\n" +
            "          <token-text>Line one\n" +
            "Line two\n" +
            "  indented line three</token-text>\n" +
            "        </arg-value>\n" +
            "      </do-set-local-variable>\n" +
            "    </actions>\n" +
            "  </rule>\n" +
            "</policy>\n";

        String originalSpaced = " Smith  Jane ";
        String originalMultiLine = "Line one\nLine two\n  indented line three";

        Document doc = CanonicalXml.parse(policyXml);
        NodeList tokenTexts = doc.getElementsByTagName("token-text");
        assertEquals(2, tokenTexts.getLength());
        assertEquals(originalSpaced, tokenTexts.item(0).getTextContent());
        assertEquals(originalMultiLine, tokenTexts.item(1).getTextContent());

        String round1 = CanonicalXml.serialize(doc);
        Document doc2 = CanonicalXml.parse(round1);
        String round2 = CanonicalXml.serialize(doc2);

        assertEquals("serialize must be idempotent", round1, round2);

        NodeList tokenTexts2 = doc2.getElementsByTagName("token-text");
        assertEquals(originalSpaced, tokenTexts2.item(0).getTextContent());
        assertEquals(originalMultiLine, tokenTexts2.item(1).getTextContent());
    }

    @Test
    public void attributesAreSortedXmlnsFirstThenByNameWithValuesEscaped() {
        String xml = "<root b=\"2\" xmlns:b=\"urn:b\" a=\"1\" xmlns=\"urn:default\" xmlns:a=\"urn:a\">"
            + "<child q=\"say &quot;hi&quot;\" nl=\"line1&#10;line2\"/>"
            + "</root>";

        String expected =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<root xmlns=\"urn:default\" xmlns:a=\"urn:a\" xmlns:b=\"urn:b\" a=\"1\" b=\"2\">\n" +
            "  <child nl=\"line1&#10;line2\" q=\"say &quot;hi&quot;\"/>\n" +
            "</root>\n";

        assertEquals(expected, CanonicalXml.canonicalize(xml));
    }

    @Test
    public void elementOnlyContentIsReindentedAndWhitespaceDroppedRegardlessOfSourceIndentation() {
        String compact = "<root><a/><b><c/></b></root>";
        String prettyPrinted = "<root>\n  <a/>\n  <b>\n    <c/>\n  </b>\n</root>\n";
        String differentlyIndented = "<root>\n<a/>\n<b><c/>\n</b>\n\n</root>";

        String expected =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<root>\n" +
            "  <a/>\n" +
            "  <b>\n" +
            "    <c/>\n" +
            "  </b>\n" +
            "</root>\n";

        String canonCompact = CanonicalXml.canonicalize(compact);
        assertEquals(expected, canonCompact);
        assertEquals(canonCompact, CanonicalXml.canonicalize(prettyPrinted));
        assertEquals(canonCompact, CanonicalXml.canonicalize(differentlyIndented));
    }

    @Test
    public void mixedContentIsPreservedVerbatimIncludingWhitespaceOnlyTextAndCdata() {
        String mixed = "<mix>a<x/> <y/>b</mix>";
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + mixed + "\n",
            CanonicalXml.canonicalize(mixed));

        String withCdata = "<data><![CDATA[<tag>&amp;not-an-entity]]></data>";
        String canonCdata = CanonicalXml.canonicalize(withCdata);
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + withCdata + "\n",
            canonCdata);
        // Idempotent: re-parsing the CDATA output must reproduce it exactly.
        assertEquals(canonCdata, CanonicalXml.canonicalize(canonCdata));
    }

    @Test
    public void emptyElementSelfClosesAndCommentsArePreserved() {
        String xml = "<root><empty/><!-- a comment --><child></child></root>";

        String expected =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<root>\n" +
            "  <empty/>\n" +
            "  <!-- a comment -->\n" +
            "  <child/>\n" +
            "</root>\n";

        assertEquals(expected, CanonicalXml.canonicalize(xml));
    }

    @Test
    public void doctypeParsesWithoutNetworkOrFileAccessAndIsDroppedFromOutput() {
        String xml = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE policy SYSTEM \"dirxmlscript.dtd\">"
            + "<policy><rule/></policy>";

        Document doc = CanonicalXml.parse(xml);
        String out = CanonicalXml.serialize(doc);

        assertFalse("DOCTYPE must be dropped", out.contains("DOCTYPE"));
        assertFalse(out.contains("dirxmlscript.dtd"));
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<policy>\n  <rule/>\n</policy>\n",
            out);
    }

    @Test
    public void xsltStylesheetRoundTripsWithNamespacePrefixesIntact() {
        String xslt = "<?xml version=\"1.0\"?>"
            + "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
            + "<xsl:template match=\"/\">"
            + "<xsl:value-of select=\".\"/>"
            + "</xsl:template>"
            + "</xsl:stylesheet>";

        String canon = CanonicalXml.canonicalize(xslt);

        assertTrue(canon.contains("<xsl:stylesheet"));
        assertTrue(canon.contains("xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\""));
        assertTrue(canon.contains("<xsl:template"));
        assertTrue(canon.contains("<xsl:value-of"));
        assertEquals("must be idempotent", canon, CanonicalXml.canonicalize(canon));
    }

    @Test
    public void novellDomProducesTheSameCanonicalOutputAsTheJdkDom() {
        String samplePolicy =
            "<policy>"
            + "<rule>"
            + "<description>Sample</description>"
            + "<conditions/>"
            + "<actions>"
            + "<do-set-dest-attr-value class-name=\"User\" name=\"Full Name\">"
            + "<arg-value type=\"string\"><token-text> Jane  Smith </token-text></arg-value>"
            + "</do-set-dest-attr-value>"
            + "</actions>"
            + "</rule>"
            + "</policy>";

        Document jdkDoc = CanonicalXml.parse(samplePolicy);
        Document novellDoc = Xds.parse(samplePolicy);

        assertEquals(CanonicalXml.serialize(jdkDoc), CanonicalXml.serialize(novellDoc));
    }
}
