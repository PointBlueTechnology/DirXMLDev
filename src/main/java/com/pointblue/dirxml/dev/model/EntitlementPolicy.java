package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * A role-based entitlement policy of the Entitlements Service driver ({@code DirXML-SharedProfile},
 * docs/console-gaps.md §2 and §9). Grounded 2026-10-09 on Designer's RBE editor and deploy code and
 * on the shim itself ({@code EntitlementServiceShim.jar}): the policies of a driver set live in
 * <b>one</b> container, {@code cn=Entitlement Policies,<driver set>} ({@code DirXML-SharedProfileSet},
 * whose {@code DirXML-SPPriority} orders them), and each policy is a dynamic group:
 * <pre>
 *   Description                      free text
 *   memberQuery                      ldap:///&lt;base&gt;??&lt;one|sub&gt;?&lt;rfc2254 filter&gt;?x-sparse   -- the dynamic membership
 *   dgIdentity                       the identity the directory evaluates the query as
 *   Member, excludedMember           static members and exclusions (DNs)
 *   DirXML-SPFilterXML               the editor's criteria: &lt;selection-criterion&gt;&lt;group&gt;&lt;group-op&gt;and&lt;/group-op&gt;&lt;row&gt;…
 *   DirXML-EntitlementRef            what it grants: path values "&lt;entitlement dn&gt;#0#&lt;ref&gt;[&lt;param&gt;v&lt;/param&gt;]&lt;/ref&gt;"
 *   DirXML-SPDisplayEntitlements     Designer's display copy of the grants: &lt;Drivers&gt;&lt;Driver&gt;…&lt;Entitlement&gt;…
 *   DirXML-SPEntitlementsXML         legacy (pre-3.5); a non-empty value makes the shim refuse the policy
 * </pre>
 * The shim reads {@code DirXML-EntitlementRef} and {@code memberQuery} and evaluates membership through
 * {@code Member}, which the directory computes for a dynamic group; the rest is the editors'.
 * In the tree: {@code rbe-policies/<name>.xml} beside {@code driverset.xml}.
 */
public final class EntitlementPolicy {
    /** The shim class of the driver these policies belong to. */
    public static final String SERVICE_SHIM_CLASS = "com.novell.nds.dirxml.driver.entitlement.EntitlementServiceDriver";

    public final String name;
    public String description;
    /** {@code memberQuery}: the dynamic membership, an LDAP URL; null when the policy has static members only. */
    public String memberQuery;
    /** {@code dgIdentity}: the identity the query runs as; null when unset. */
    public String identity;
    /** {@code DirXML-SPFilterXML}: the editor's {@code <selection-criterion>} document, or null. */
    public Element criteria;
    /** {@code Member}: static members. */
    public final List<String> members = new ArrayList<>();
    /** {@code excludedMember}. */
    public final List<String> excludedMembers = new ArrayList<>();
    /** {@code DirXML-EntitlementRef} values as the vault holds them: {@code <entitlement dn>#0#<ref>…</ref>}. */
    public final List<String> entitlementRefs = new ArrayList<>();
    /** {@code DirXML-SPDisplayEntitlements}: Designer's {@code <Drivers>} document, kept whole, or null. */
    public Element displayEntitlements;
    /** The level in the container's {@code DirXML-SPPriority}; null when the container does not list the policy. */
    public Integer priority;
    /** DN, Designer id, package stamps, and other source-specific extras. */
    public final Map<String, String> meta = new LinkedHashMap<>();

    public EntitlementPolicy(String name) {
        this.name = name;
    }

    /** The entitlement DN of a {@code DirXML-EntitlementRef} value ({@code dn#0#<ref/>}). */
    public static String refDn(String ref) {
        int i = ref.indexOf('#');
        return i < 0 ? ref : ref.substring(0, i);
    }

    /** The {@code <ref>} XML of a {@code DirXML-EntitlementRef} value, or an empty {@code <ref/>} when it has none. */
    public static String refXml(String ref) {
        int i = ref.indexOf('#');
        int j = i < 0 ? -1 : ref.indexOf('#', i + 1);
        return j < 0 ? "<ref/>" : ref.substring(j + 1);
    }

    /** The entitlement DNs this policy grants, in order, without duplicates. */
    public List<String> entitlementDns() {
        List<String> out = new ArrayList<>();
        for (String r : entitlementRefs) {
            String dn = refDn(r);
            boolean dup = false;
            for (String o : out) {
                if (o.equalsIgnoreCase(dn)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(dn);
            }
        }
        return out;
    }

    /**
     * The {@code DirXML-EntitlementRef} values Designer derives from the display document when it
     * deploys ({@code RBEPolicyImpl.convertSPEntitlementsXMLtoRefs}): one per {@code <value>} under an
     * entitlement's {@code <InputValues>} or {@code <GroupEntitlement>}, as {@code <ref><param>v</param></ref>};
     * one bare {@code <ref/>} for an entitlement with neither. Empty when there is no display document.
     */
    public List<String> refsFromDisplay() {
        List<String> out = new ArrayList<>();
        if (displayEntitlements == null) {
            return out;
        }
        for (Element driver : children(displayEntitlements, "Driver")) {
            for (Element ent : children(driver, "Entitlement")) {
                String dn = null;
                for (Element e : children(ent, "EntitlementDN")) {
                    dn = text(e).trim();
                }
                if (dn == null || dn.isEmpty()) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                for (Element parent : children(ent, "InputValues")) {
                    for (Element v : children(parent, "value")) {
                        values.add(text(v));
                    }
                }
                for (Element parent : children(ent, "GroupEntitlement")) {
                    for (Element v : children(parent, "value")) {
                        values.add(text(v));
                    }
                }
                if (values.isEmpty()) {
                    out.add(dn + "#0#<ref/>");
                } else {
                    for (String v : values) {
                        out.add(dn + "#0#<ref><param>" + escape(v) + "</param></ref>");
                    }
                }
            }
        }
        return out;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE) {
                String l = c.getLocalName() != null ? c.getLocalName() : c.getNodeName();
                if (localName.equals(l)) {
                    out.add((Element) c);
                }
            }
        }
        return out;
    }

    /** Text from the text children: the engine's DOM is Level 2, with no {@code getTextContent}. */
    private static String text(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
