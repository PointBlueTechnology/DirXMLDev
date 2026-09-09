package com.pointblue.dirxml.dev.source;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal serializer/template for Designer CObject metadata files
 * ({@code <ID>.<Type>_}): element-only content (no text/CDATA — real projects never
 * put mixed content in these), 2-space block indentation, attributes kept in
 * whatever order the DOM holds them (never sorted, unlike {@link
 * com.pointblue.dirxml.dev.xml.CanonicalXml}), {@code encoding="utf-8"} lower-case —
 * Designer's own on-disk shape, as closely as a generic DOM edit can reproduce it.
 *
 * <p>Known, accepted differences from a file Designer itself would write (see
 * {@code docs/designer-roundtrip.md} and {@code ProjectWriter}'s report): a file this
 * class rewrites is re-indented in full (Designer's original inter-element
 * whitespace is not preserved token-for-token), and a freshly written attribute
 * value that embeds XML (e.g. a rewritten {@code DirXML-ShimConfigInfo}) escapes
 * control characters as decimal character references ({@code &#10;}) rather than
 * Designer's hexadecimal form ({@code &#xA;}) — both parse back to the same
 * characters, so this never affects what the reader sees.
 */
final class CObjectXml {

    private static final String DECL = "<?xml version=\"1.0\" encoding=\"utf-8\"?>";

    private CObjectXml() {
    }

    /** Serializes a CObject (or any element-only) document element in Designer's block style. */
    static String serialize(Element root) {
        StringBuilder sb = new StringBuilder();
        sb.append(DECL).append('\n');
        writeElement(root, sb, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeElement(Element el, StringBuilder sb, int depth) {
        sb.append(indent(depth)).append('<').append(el.getTagName());
        NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            sb.append(' ').append(a.getName()).append("=\"").append(esc(a.getValue())).append('"');
        }
        List<Element> children = new ArrayList<>();
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) n);
            }
        }
        if (children.isEmpty()) {
            sb.append("/>");
            return;
        }
        sb.append(">\n");
        for (Element c : children) {
            writeElement(c, sb, depth + 1);
            sb.append('\n');
        }
        sb.append(indent(depth)).append("</").append(el.getTagName()).append('>');
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
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

    /** A minimal CObject document string, as Designer writes one, for a newly minted object. */
    static String cobject(String name, String type, String attrsXml, String relationsXml) {
        return DECL + "\n"
            + "<com.novell.designer.model:CObject xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\" name=\"" + esc(name)
            + "\" type=\"" + esc(type) + "\">"
            + attrsXml + relationsXml
            + "</com.novell.designer.model:CObject>\n";
    }

    /** A {@code <attributes .../>} snippet for {@link #cobject}. */
    static String attr(String attrName, String value, String xsiType) {
        return "<attributes xsi:type=\"com.novell.designer.model:" + xsiType + "\" attrName=\""
            + esc(attrName) + "\" value=\"" + esc(value) + "\"/>";
    }
}
