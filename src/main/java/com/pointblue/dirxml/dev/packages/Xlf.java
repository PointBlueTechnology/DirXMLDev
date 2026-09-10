package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package localization, Designer's way: text in a package may carry
 * {@code xlfid(<key>)<default text>}; at install Designer replaces each with
 * the key's value from the package's {@code <properties lang="…">} bundle
 * for its own language, else the default text ({@code XmlUtil.localizeXMLWithXLIFF};
 * "No XLIF document, remove XLIF IDs using default"). Applied to every
 * attribute value and text node of a document.
 */
public final class Xlf {

    private static final Pattern XLFID = Pattern.compile("xlfid\\(([^)]*)\\)");

    private Xlf() {
    }

    /** Resolves every {@code xlfid(key)default} in {@code s}; {@code props} may be empty. */
    public static String localize(String s, Map<String, String> props) {
        if (s == null || !s.contains("xlfid(")) {
            return s;
        }
        Matcher m = XLFID.matcher(s);
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (m.find()) {
            out.append(s, pos, m.start());
            String key = m.group(1);
            // the default text runs to the next xlfid( or the end of the string
            int defEnd = s.indexOf("xlfid(", m.end());
            if (defEnd < 0) {
                defEnd = s.length();
            }
            String dflt = s.substring(m.end(), defEnd);
            String v = props == null ? null : props.get(key.trim());
            out.append(v != null ? v : dflt);
            pos = defEnd;
            m.region(pos, s.length());
        }
        out.append(s.substring(pos));
        return out.toString();
    }

    /** Localizes the element and everything under it, in place. */
    public static void localize(Element e, Map<String, String> props) {
        if (e == null) {
            return;
        }
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String v = a.getValue();
            String l = localize(v, props);
            if (l != null && !l.equals(v)) {
                a.setValue(l);
            }
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                localize((Element) c, props);
            } else if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                String v = c.getNodeValue();
                String l = localize(v, props);
                if (l != null && !l.equals(v)) {
                    c.setNodeValue(l);
                }
            }
        }
    }

    /** True if anything under the element still carries an xlfid marker. */
    public static boolean hasXlf(Element e) {
        if (e == null) {
            return false;
        }
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            if (attrs.item(i).getNodeValue().contains("xlfid(")) {
                return true;
            }
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE ? hasXlf((Element) c)
                : (c.getNodeType() == Node.TEXT_NODE && c.getNodeValue().contains("xlfid("))) {
                return true;
            }
        }
        return false;
    }
}
