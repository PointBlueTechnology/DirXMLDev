package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package prompts, Designer's way (docs/spikes/designer-package-layer.md §2.5):
 * a {@code pkg-prompt+xml} resource carries the prompt definitions
 * ({@code <configuration-values>}) as its content and, in its directive, the
 * targets it applies to plus two XSLT 1.0 stylesheets — the <em>prompt</em>
 * stylesheet pre-populates the definitions, the <em>target</em> stylesheet
 * transforms each target's installation directive (and a non-GCV target's
 * content) with the answered definitions as {@code $defsDoc}. Answers are keyed
 * by definition name (display-name accepted); {@code eNull} means empty;
 * {@code password-ref} answers are recorded by name, never as values.
 */
public final class PromptEngine {

    /** One prompt page: its answered definitions and the targets it transforms. */
    public static final class Prompt {
        public String name;
        public int order;
        public Element definitions;            // <configuration-values>, answered
        public List<String> targetAssocIds = new ArrayList<>();
        public Element promptStylesheet;       // may be null
        public Element targetStylesheet;       // may be null
        public List<String> unanswered = new ArrayList<>();
        public List<String> passwordRefs = new ArrayList<>();
    }

    private PromptEngine() {
    }

    /** Reads a prompt resource (its content and directive) into a {@link Prompt}. */
    public static Prompt read(PackageJar.Item item) {
        return read(item, Map.of());
    }

    /** Reads a prompt resource; {@code lang} is the package's language bundle for xlfid markers. */
    public static Prompt read(PackageJar.Item item, Map<String, String> lang) {
        Prompt p = new Prompt();
        p.name = item.name;
        Element dir = CanonicalXml.parse(item.directive).getDocumentElement();
        Xlf.localize(dir, lang);
        Element placement = child(dir, "placement");
        p.order = placement == null ? 0 : parseInt(placement.getAttribute("order"), 0);
        Element targets = child(dir, "package-item-targets");
        if (targets != null) {
            for (Element t : children(targets, "package-item")) {
                String id = t.getAttribute("pkg-assoc-id");
                if (!id.isEmpty()) {
                    p.targetAssocIds.add(id);   // an empty id means "the package itself"
                }
            }
        }
        Element ps = child(dir, "prompt-stylesheet");
        p.promptStylesheet = ps == null ? null : firstElement(ps);
        Element ts = child(dir, "target-stylesheet");
        p.targetStylesheet = ts == null ? null : firstElement(ts);
        p.definitions = item.content == null ? CanonicalXml.parse("<configuration-values><definitions/></configuration-values>").getDocumentElement()
            : CanonicalXml.parse(NxslCanonical.serialize(item.content)).getDocumentElement();
        Xlf.localize(p.definitions, lang);
        return p;
    }

    /** Runs the prompt stylesheet (if any) to pre-populate, then applies the answers. */
    public static void answer(Prompt p, Map<String, String> answers, Element curDoc, Element npDoc, boolean propertyWizard) {
        if (p.promptStylesheet != null) {
            Element out = transform(p.promptStylesheet, p.definitions, params(p.definitions, curDoc, npDoc, null, propertyWizard));
            if (out != null && "configuration-values".equals(out.getNodeName())) {
                p.definitions = out;
            }
        }
        Map<String, String> byDisplay = new LinkedHashMap<>();
        for (Element def : definitions(p.definitions)) {
            byDisplay.put(stripXlf(def.getAttribute("display-name")), def.getAttribute("name"));
        }
        for (Element def : definitions(p.definitions)) {
            String name = def.getAttribute("name");
            String type = def.getAttribute("type");
            String v = answers.get(name);
            if (v == null) {
                String dn = stripXlf(def.getAttribute("display-name"));
                v = answers.get(dn);
            }
            if ("password-ref".equals(type)) {
                p.passwordRefs.add(name);
                continue;
            }
            if (v == null) {
                if (!hasValue(def) && "true".equals(def.getAttribute("mandatory"))) {
                    p.unanswered.add(name);
                }
                continue;
            }
            setValue(def, "eNull".equals(v) ? "" : v);
        }
    }

    /** Transforms a target's directive (or content) with the target stylesheet; null when there is none. */
    public static Element applyToTarget(Prompt p, Element target, Element curDoc, Element npDoc, Element directiveDoc,
                                        boolean propertyWizard) {
        if (p.targetStylesheet == null || target == null) {
            return null;
        }
        return transform(p.targetStylesheet, target, params(p.definitions, curDoc, npDoc, directiveDoc, propertyWizard));
    }

    private static Map<String, Object> params(Element defs, Element curDoc, Element npDoc, Element directiveDoc, boolean wizard) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defsDoc", asDocument(defs));
        m.put("curDoc", asDocument(curDoc != null ? curDoc : CanonicalXml.parse("<ds-attributes/>").getDocumentElement()));
        m.put("npDoc", asDocument(npDoc != null ? npDoc : CanonicalXml.parse("<named-passwords/>").getDocumentElement()));
        m.put("directiveDoc", asDocument(directiveDoc != null ? directiveDoc : CanonicalXml.parse("<installation-directive/>").getDocumentElement()));
        m.put("opDoc", asDocument(CanonicalXml.parse("<operation/>").getDocumentElement()));
        m.put("propertyWizard", wizard ? "true" : "false");
        return m;
    }

    /** Runs an XSLT 1.0 stylesheet with the engine's own processor ({@code nxsl.jar}); document parameters are DOM nodes. */
    static Element transform(Element stylesheet, Element input, Map<String, Object> params) {
        try {
            org.w3c.dom.Document xsl = NxslCanonical.parse(CanonicalXml.serialize(stylesheet));
            com.novell.xsl.Stylesheet ss = new com.novell.xsl.Stylesheet(xsl);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Document) {
                    ss.setParameter(e.getKey(), (Object) NxslCanonical.parse(CanonicalXml.serialize(((Document) v).getDocumentElement())));
                } else {
                    ss.setParameter(e.getKey(), String.valueOf(v));
                }
            }
            org.w3c.dom.Document in = NxslCanonical.parse(CanonicalXml.serialize(input));
            com.novell.xsl.result.DOMResultHandler out = new com.novell.xsl.result.DOMResultHandler(com.novell.xml.dom.DocumentFactory.newDocument());
            ss.setResultHandler(out);
            ss.process(in, null, true);
            Element root = out.getDocument().getDocumentElement();
            return root == null ? null : CanonicalXml.parse(NxslCanonical.serialize(root)).getDocumentElement();
        } catch (Exception e) {
            throw new IllegalStateException("prompt stylesheet failed: " + e.getMessage(), e);
        }
    }

    static Document asDocument(Element e) {
        return CanonicalXml.parse(CanonicalXml.serialize(e));
    }

    public static List<Element> definitions(Element configurationValues) {
        List<Element> out = new ArrayList<>();
        collectDefinitions(configurationValues, out);
        return out;
    }

    private static void collectDefinitions(Node n, List<Element> out) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            if ("definition".equals(c.getNodeName())) {
                out.add((Element) c);
            }
            collectDefinitions(c, out);
        }
    }

    static boolean hasValue(Element def) {
        Element v = child(def, "value");
        return v != null && !text(v).isEmpty();
    }

    /** The concatenated text/CDATA children (DOM Level 2 safe: {@code getTextContent} may be null in the engine's DOM). */
    public static String text(Node e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            } else if (c.getNodeType() == Node.ELEMENT_NODE) {
                sb.append(text(c));
            }
        }
        return sb.toString();
    }

    static void setValue(Element def, String value) {
        Element v = child(def, "value");
        if (v == null) {
            v = def.getOwnerDocument().createElementNS(null, "value");
            def.appendChild(v);
        }
        while (v.getFirstChild() != null) {
            v.removeChild(v.getFirstChild());
        }
        v.appendChild(def.getOwnerDocument().createTextNode(value));
    }

    /** {@code xlfid(key)Text} → {@code Text}. */
    public static String stripXlf(String s) {
        if (s != null && s.startsWith("xlfid(")) {
            int i = s.indexOf(')');
            return i < 0 ? s : s.substring(i + 1);
        }
        return s;
    }

    static Element child(Element e, String name) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && name.equals(c.getNodeName())) {
                return (Element) c;
            }
        }
        return null;
    }

    static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && name.equals(c.getNodeName())) {
                out.add((Element) c);
            }
        }
        return out;
    }

    static Element firstElement(Node n) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) c;
            }
        }
        return null;
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }
}
