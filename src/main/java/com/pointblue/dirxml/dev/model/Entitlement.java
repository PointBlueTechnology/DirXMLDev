package com.pointblue.dirxml.dev.model;

import org.w3c.dom.Element;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code DirXML-Entitlement}: the definition document a driver's Identity Applications
 * consult when a workflow grants or revokes it (see {@code docs/entitlements.md}). Unlike
 * a {@link Form} or {@link Prd}, an entitlement hangs directly off the {@link Driver} — it
 * is not part of {@link Provisioning} ({@code cn=AppConfig}); the vault holds it as a plain
 * child of the driver object, {@code cn=<name>,cn=<driver>,<driver set>}.
 *
 * <p>{@link #definition} is the whole {@code <entitlement>} document, held verbatim (see
 * {@code CanonicalXml}) — the vault's {@code XmlData}:
 * <pre>
 *   &lt;entitlement conflict-resolution="union|priority" description="…" display-name="…"&gt;
 *     &lt;values multi-valued="true|false"&gt;
 *       &lt;value&gt;…&lt;/value&gt;*                     -- static values
 *       | &lt;query-app&gt;&lt;query-xml&gt;…&lt;/query-xml&gt;&lt;/query-app&gt;   -- a queried value set (packaged, e.g. AD)
 *     &lt;/values&gt;
 *   &lt;/entitlement&gt;
 * </pre>
 */
public final class Entitlement {

    public final String name;
    public Element definition;
    /** Package stamps ({@code dirxml-pkg*}), {@code package.customized}, DN, and other source-specific extras. */
    public final Map<String, String> meta = new LinkedHashMap<>();

    public Entitlement(String name, Element definition) {
        this.name = name;
        this.definition = definition;
    }

    public String displayName() {
        return attr("display-name");
    }

    public String description() {
        return attr("description");
    }

    public String conflictResolution() {
        return attr("conflict-resolution");
    }

    /** {@code <values multi-valued>}, as a string ({@code "true"}/{@code "false"}), or null if absent. */
    public String multiValued() {
        if (definition == null) {
            return null;
        }
        for (org.w3c.dom.Node c = definition.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && "values".equals(localName(c))) {
                Element values = (Element) c;
                String v = values.getAttribute("multi-valued");
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    private String attr(String name) {
        if (definition == null) {
            return null;
        }
        String v = definition.getAttribute(name);
        return v.isEmpty() ? null : v;
    }

    private static String localName(org.w3c.dom.Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    @Override
    public String toString() {
        return "entitlement " + name;
    }
}
