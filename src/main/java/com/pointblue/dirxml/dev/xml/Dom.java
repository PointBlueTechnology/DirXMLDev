package com.pointblue.dirxml.dev.xml;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * DOM traversal for the vault path, with no engine class behind it (docs/vendor-free-vault.md). The same
 * helpers as the simulator's {@code Xds}, whose parsing goes through the engine's {@code XmlDocument};
 * these only walk a DOM, DOM Level 2 safe (the Novell DOM returns null from {@code getTextContent()}).
 */
public final class Dom {

    private Dom() {
    }

    /** {@code ctx} itself or its first descendant element with the given local name, or null. */
    public static Element firstByName(Node ctx, String localName) {
        if (ctx instanceof Element e && localName.equals(e.getLocalName())) {
            return e;
        }
        NodeList kids = ctx.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                Element found = firstByName(k, localName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Immediate child elements with the given local name. */
    public static List<Element> childrenByName(Node parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Element k : childElements(parent)) {
            if (localName.equals(k.getLocalName())) {
                out.add(k);
            }
        }
        return out;
    }

    /** All descendant elements with the given local name, in document order. */
    public static List<Element> descendantsByName(Node root, String localName) {
        List<Element> out = new ArrayList<>();
        collect(root, localName, out);
        return out;
    }

    private static void collect(Node node, String localName, List<Element> out) {
        for (Element k : childElements(node)) {
            if (localName.equals(k.getLocalName())) {
                out.add(k);
            }
            collect(k, localName, out);
        }
    }

    /** Immediate child elements (any name). */
    public static List<Element> childElements(Node parent) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) kids.item(i));
            }
        }
        return out;
    }

    /** The direct text and CDATA children of a node, concatenated ("" for null). */
    public static String text(Node node) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue());
            }
        }
        return sb.toString();
    }
}
