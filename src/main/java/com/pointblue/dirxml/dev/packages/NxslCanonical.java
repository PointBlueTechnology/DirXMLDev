package com.pointblue.dirxml.dev.packages;

import com.novell.xml.dom.DOMUtil;
import com.novell.xml.dom.DOMWriter;
import com.novell.xml.dom.DocumentFactory;
import com.novell.xml.parser.XMLParser;
import com.novell.xml.parser.XMLParserFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The canonical XML string Designer's package layer hashes: the engine's own
 * parser ({@code nxsl.jar}), {@code DOMUtil.stripWhitespace} on the root, then
 * {@code DOMWriter} with declaration and tab indentation, no trailing newline.
 * See docs/spikes/designer-package-layer.md §1.2. Designer serializes a
 * package object's content once when reading the jar and re-parses it before
 * hashing; {@link #canonical(Element)} reproduces that double pass.
 */
public final class NxslCanonical {

    private NxslCanonical() {
    }

    public static Document parse(String xml) {
        try {
            XMLParser p = XMLParserFactory.newParser();
            Document d = p.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            if (d.getDocumentElement() != null) {
                DOMUtil.stripWhitespace(d.getDocumentElement());
            }
            return d;
        } catch (Exception e) {
            throw new IllegalArgumentException("not well-formed: " + e.getMessage(), e);
        }
    }

    public static String serialize(Node n) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DOMWriter w = new DOMWriter(n, out, "UTF-8");
            w.setWriteDeclaration(true);
            w.setIndent(true);
            w.setEncoding("UTF-8");
            w.write();
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical string of an XML document given as text (one parse/serialize pass). */
    public static String canonical(String xml) {
        return serialize(parse(xml));
    }

    /** Canonical string of an element from any DOM: import → serialize → re-parse → serialize (Designer's path). */
    public static String canonical(Element e) {
        Document d = DocumentFactory.newDocument();
        d.appendChild(d.importNode(e, true));
        return canonical(serialize(d));
    }
}
