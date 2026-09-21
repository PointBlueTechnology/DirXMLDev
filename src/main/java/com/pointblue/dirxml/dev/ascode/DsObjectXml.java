package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.model.AppConfigPolicy;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ds-object} document — the shape Designer's {@code .appconfig} and
 * digest items and the User Application Base package carry AppConfig objects in —
 * as the as-code tree's file for an {@link AppObject}:
 * <pre>
 * &lt;ds-object ds-object-class="srvprvEntity" ds-object-name="user"&gt;
 *   &lt;ds-attributes&gt;
 *     &lt;ds-attribute ds-attr-name="objectClass"&gt;&lt;ds-value&gt;Top&lt;/ds-value&gt;…
 *     &lt;ds-attribute ds-attr-name="XmlData"&gt;&lt;ds-value&gt;&lt;entity-definition&gt;…&lt;/entity-definition&gt;&lt;/ds-value&gt;
 *     &lt;ds-attribute ds-attr-name="description"&gt;&lt;ds-value&gt;User&lt;/ds-value&gt;
 * </pre>
 * XML-valued attributes are written as XML (readable diffs), text otherwise. The
 * object's path and package stamps live in the {@code provisioning.xml} manifest,
 * not here, so the file is exactly what Designer or a package would hold.
 */
public final class DsObjectXml {

    private DsObjectXml() {
    }

    public static String write(AppObject o) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ds-object ds-object-class=\"").append(esc(o.structuralClass())).append("\" ds-object-name=\"")
            .append(esc(o.name())).append("\">\n  <ds-attributes>\n");
        if (!o.classes.isEmpty()) {
            sb.append("    <ds-attribute ds-attr-name=\"objectClass\">\n");
            for (String c : o.classes) {
                sb.append("      <ds-value>").append(esc(c)).append("</ds-value>\n");
            }
            sb.append("    </ds-attribute>\n");
        }
        for (var e : o.attrs.entrySet()) {
            sb.append("    <ds-attribute ds-attr-name=\"").append(esc(e.getKey())).append("\">\n");
            for (String v : e.getValue()) {
                String xml = AppConfigPolicy.isXmlAttribute(e.getKey()) ? asXml(v) : null;
                if (xml != null) {
                    sb.append("      <ds-value>\n").append(indent(xml, "        ")).append("      </ds-value>\n");
                } else {
                    sb.append("      <ds-value>").append(esc(v)).append("</ds-value>\n");
                }
            }
            sb.append("    </ds-attribute>\n");
        }
        sb.append("  </ds-attributes>\n</ds-object>\n");
        return sb.toString();
    }

    /** Reads a ds-object document; {@code segments} gives the object its path (the file knows only its name). */
    public static AppObject read(String xml, List<String> segments) {
        Element root = CanonicalXml.parse(xml).getDocumentElement();
        return read(root, segments);
    }

    public static AppObject read(Element dsObject, List<String> segments) {
        AppObject o = new AppObject(segments);
        String cls = dsObject.getAttribute("ds-object-class");
        Element attrs = Xds.firstByName(dsObject, "ds-attributes");
        if (attrs != null) {
            for (Element a : Xds.childrenByName(attrs, "ds-attribute")) {
                String name = a.getAttribute("ds-attr-name");
                List<String> values = new ArrayList<>();
                for (Element v : Xds.childrenByName(a, "ds-value")) {
                    values.add(value(v));
                }
                if (name.equalsIgnoreCase("objectClass")) {
                    o.classes.addAll(values);
                } else if (!name.equalsIgnoreCase("cn") && !name.equalsIgnoreCase("CN")) {
                    o.put(name, values);
                }
            }
        }
        if (o.classes.isEmpty() && !cls.isEmpty()) {
            o.classes.add("Top");
            o.classes.add(cls);
        }
        return o;
    }

    /** A ds-value's content: its one element child serialized canonically, else its text. */
    static String value(Element dsValue) {
        Element child = null;
        for (Node n = dsValue.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                child = (Element) n;
                break;
            }
        }
        if (child != null) {
            return CanonicalXml.serialize(child);
        }
        // not getTextContent(): the Novell DOM a package is parsed with is DOM Level 2
        StringBuilder sb = new StringBuilder();
        for (Node n = dsValue.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    /** The canonical serialization of {@code text} when it is an XML document, else null. */
    public static String asXml(String text) {
        if (text == null || text.indexOf('<') < 0) {
            return null;
        }
        try {
            return CanonicalXml.canonicalize(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The canonical XML without its declaration, otherwise untouched: lines are never re-indented,
     * because a multi-line text node (a certificate, a script) would gain the indent on re-read.
     */
    private static String indent(String xml, String prefix) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String line : xml.split("\n")) {
            if (first && line.startsWith("<?xml")) {
                first = false;
                continue;
            }
            first = false;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
