package com.pointblue.dirxml.dev.xml;

import org.w3c.dom.Attr;
import org.w3c.dom.CDATASection;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The one canonical XML serializer/parser for IDM-as-code files and vault writes.
 *
 * <p>IDM content (DirXML Script policies, XSLT, filters, GCV definitions, mapping
 * tables) is XML where <b>text is significant</b> in mixed-content elements (e.g.
 * {@code <token-text> Smith </token-text>} — the spaces are data) but inter-element
 * whitespace in element-only content is not. This class produces one deterministic,
 * idempotent rendering so that git diffs stay stable and round-trips through the
 * engine or Designer are byte-identical.
 *
 * <h2>Rules</h2>
 * <ol>
 *   <li>Output starts with exactly {@code <?xml version="1.0" encoding="UTF-8"?>}
 *       followed by a newline, and ends with a single trailing newline. Any
 *       {@code DOCTYPE} on the source document is dropped.</li>
 *   <li>Attributes are sorted: {@code xmlns}/{@code xmlns:*} declarations first
 *       (sorted by name), then all other attributes sorted by qualified name
 *       (String natural order). Attribute values escape {@code & < > "}, plus
 *       {@code \t \n \r} as {@code &#9; &#10; &#13;} so they survive XML attribute-value
 *       normalization on re-parse.</li>
 *   <li>An element with no children (after dropping whitespace-only text, if the
 *       element is otherwise element-only) self-closes as {@code <name/>}. An
 *       element whose children are all elements/comments/PIs (ignoring
 *       whitespace-only text, which is dropped) uses <b>block layout</b>: each
 *       child on its own line, indented two spaces per depth. An element with any
 *       non-whitespace text, CDATA, or entity-reference child uses
 *       <b>mixed layout</b>: children are serialized in document order with text
 *       verbatim (escaping only {@code & < >}; CDATA sections stay CDATA), no
 *       indentation or newlines are added, and the closing tag follows immediately.
 *       Whitespace-only text nodes inside a mixed-content element are preserved —
 *       only element-only content drops them.</li>
 *   <li>Comments and processing instructions are preserved: on their own line in
 *       block layout, inline in mixed layout.</li>
 *   <li>Namespace prefixes are emitted exactly as they appear in the DOM node
 *       names; none are invented or renamed. {@code xmlns} attributes are emitted
 *       only where the DOM already carries them as attributes — none are
 *       synthesized.</li>
 *   <li>{@link #serialize} is deterministic and idempotent:
 *       {@code serialize(parse(serialize(x)))} equals {@code serialize(x)}
 *       byte-for-byte.</li>
 * </ol>
 *
 * <p>Only {@code org.w3c.dom} interfaces are used (never implementation classes),
 * so this class works identically on the JDK's DOM and on the Novell DOM
 * ({@code com.novell.xml.dom.*}) that the IDM engine and shims hand back.
 */
public final class CanonicalXml {

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String INDENT_UNIT = "  ";

    private CanonicalXml() {
    }

    /**
     * Parses {@code xml} with a namespace-aware, non-validating {@link DocumentBuilder}
     * that never fetches an external DTD or entity — many IDM files carry a
     * {@code <!DOCTYPE ... SYSTEM "....dtd">} that must not be resolved over the
     * network or filesystem.
     */
    public static Document parse(String xml) {
        Objects.requireNonNull(xml, "xml");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setCoalescing(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Failed to parse XML", e);
        }
    }

    /** Serializes {@code doc}'s document element per the class rules. */
    public static String serialize(Document doc) {
        Objects.requireNonNull(doc, "doc");
        Element root = doc.getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("Document has no document element");
        }
        return serialize(root);
    }

    /** Serializes {@code el} as though it were the document element of a standalone document. */
    public static String serialize(Element el) {
        Objects.requireNonNull(el, "el");
        StringBuilder sb = new StringBuilder();
        sb.append(XML_DECL).append('\n');
        writeElement(el, sb, 0, false);
        sb.append('\n');
        return sb.toString();
    }

    /** Shorthand for {@code serialize(parse(xml))}. */
    public static String canonicalize(String xml) {
        return serialize(parse(xml));
    }

    /**
     * Re-parses {@code el} through this class's own parser/serializer pipeline
     * and returns the resulting (structurally equivalent) element. Use this on
     * content read through a different parser — e.g. the engine's own
     * {@code XmlDocument} DOM, which (unlike a conformant JDK parser) does not
     * normalize {@code \r\n} line endings in text content to {@code \n} on parse
     * — <i>before</i> keeping it in a model that will later round-trip through
     * {@link #parse}/{@link #serialize} itself (as the as-code tree does): without
     * this, the first write (straight from the other parser's DOM) keeps the
     * {@code \r}, while every later write (after an as-code read, which reparses
     * with this class) does not, so the tree would never stabilize.
     */
    public static Element normalize(Element el) {
        return parse(serialize(el)).getDocumentElement();
    }

    // ------------------------------------------------------------------
    // Serialization internals
    // ------------------------------------------------------------------

    private static void writeElement(Element el, StringBuilder sb, int depth, boolean indentSelf) {
        if (indentSelf) {
            sb.append(indent(depth));
        }

        String name = el.getTagName();
        sb.append('<').append(name);
        writeAttributes(el, sb);

        NodeList children = el.getChildNodes();
        boolean mixed = isMixedContent(children);

        if (!mixed) {
            List<Node> effective = elementOnlyChildren(children);
            if (effective.isEmpty()) {
                sb.append("/>");
                return;
            }
            sb.append(">\n");
            for (Node child : effective) {
                writeBlockChild(child, sb, depth + 1);
            }
            sb.append(indent(depth)).append("</").append(name).append('>');
            return;
        }

        sb.append('>');
        for (int i = 0; i < children.getLength(); i++) {
            writeMixedChild(children.item(i), sb, depth + 1);
        }
        sb.append("</").append(name).append('>');
    }

    private static void writeBlockChild(Node child, StringBuilder sb, int depth) {
        switch (child.getNodeType()) {
            case Node.ELEMENT_NODE:
                writeElement((Element) child, sb, depth, true);
                sb.append('\n');
                break;
            case Node.COMMENT_NODE:
                sb.append(indent(depth)).append("<!--").append(((Comment) child).getData()).append("-->\n");
                break;
            case Node.PROCESSING_INSTRUCTION_NODE:
                sb.append(indent(depth));
                writeProcessingInstruction((ProcessingInstruction) child, sb);
                sb.append('\n');
                break;
            default:
                // Unreachable: elementOnlyChildren() only admits element/comment/PI nodes.
                throw new IllegalStateException("Unexpected node type in block content: " + child.getNodeType());
        }
    }

    private static void writeMixedChild(Node child, StringBuilder sb, int depth) {
        switch (child.getNodeType()) {
            case Node.ELEMENT_NODE:
                writeElement((Element) child, sb, depth, false);
                break;
            case Node.TEXT_NODE:
                sb.append(escapeText(child.getNodeValue()));
                break;
            case Node.CDATA_SECTION_NODE:
                sb.append("<![CDATA[").append(((CDATASection) child).getData()).append("]]>");
                break;
            case Node.COMMENT_NODE:
                sb.append("<!--").append(((Comment) child).getData()).append("-->");
                break;
            case Node.PROCESSING_INSTRUCTION_NODE:
                writeProcessingInstruction((ProcessingInstruction) child, sb);
                break;
            case Node.ENTITY_REFERENCE_NODE:
                sb.append('&').append(child.getNodeName()).append(';');
                break;
            default:
                // Ignore anything else (e.g. stray notation/entity declaration nodes).
                break;
        }
    }

    private static void writeProcessingInstruction(ProcessingInstruction pi, StringBuilder sb) {
        String data = pi.getData();
        if (data == null || data.isEmpty()) {
            sb.append("<?").append(pi.getTarget()).append("?>");
        } else {
            sb.append("<?").append(pi.getTarget()).append(' ').append(data).append("?>");
        }
    }

    private static void writeAttributes(Element el, StringBuilder sb) {
        NamedNodeMap attrs = el.getAttributes();
        if (attrs == null || attrs.getLength() == 0) {
            return;
        }
        List<Attr> xmlnsAttrs = new ArrayList<>();
        List<Attr> otherAttrs = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String n = a.getName();
            if (n.equals("xmlns") || n.startsWith("xmlns:")) {
                xmlnsAttrs.add(a);
            } else {
                otherAttrs.add(a);
            }
        }
        Comparator<Attr> byName = Comparator.comparing(Attr::getName);
        xmlnsAttrs.sort(byName);
        otherAttrs.sort(byName);

        for (Attr a : xmlnsAttrs) {
            sb.append(' ').append(a.getName()).append("=\"").append(escapeAttrValue(a.getValue())).append('"');
        }
        for (Attr a : otherAttrs) {
            sb.append(' ').append(a.getName()).append("=\"").append(escapeAttrValue(a.getValue())).append('"');
        }
    }

    /**
     * True if {@code children} contains any non-whitespace text, any CDATA section, or any
     * entity reference — the trigger for mixed-content layout.
     */
    private static boolean isMixedContent(NodeList children) {
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            switch (n.getNodeType()) {
                case Node.TEXT_NODE:
                    if (!isWhitespaceOnly(n.getNodeValue())) {
                        return true;
                    }
                    break;
                case Node.CDATA_SECTION_NODE:
                case Node.ENTITY_REFERENCE_NODE:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /** Element/comment/PI children, in document order, with whitespace-only text dropped. */
    private static List<Node> elementOnlyChildren(NodeList children) {
        List<Node> result = new ArrayList<>();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE:
                case Node.COMMENT_NODE:
                case Node.PROCESSING_INSTRUCTION_NODE:
                    result.add(n);
                    break;
                case Node.TEXT_NODE:
                    // whitespace-only (isMixedContent() would have already returned true otherwise) — drop.
                    break;
                default:
                    break;
            }
        }
        return result;
    }

    private static boolean isWhitespaceOnly(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return false;
            }
        }
        return true;
    }

    private static String indent(int depth) {
        return INDENT_UNIT.repeat(depth);
    }

    private static String escapeText(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeAttrValue(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\t': out.append("&#9;"); break;
                case '\n': out.append("&#10;"); break;
                case '\r': out.append("&#13;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
