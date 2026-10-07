package com.pointblue.dirxml.dev.model;

import com.pointblue.dirxml.dev.clone.Schema;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The Identity Vault's schema as the tree keeps it ({@code schema/vault.xml}): every attribute and
 * class with its NDS and LDAP names, syntax, flags and, for a class, its superclasses, mandatory and
 * optional attributes, containment and naming. Read from the vault's subschema ({@code cn=schema},
 * {@link #fromLdap}) or from a Designer project's {@code _schema.xml} ({@link #fromDesigner});
 * extended in the tree with definitions marked {@code custom}, which {@code vault.deploy-schema}
 * pushes as LDAP definitions ({@link #ldapDefinition}). Nothing here is read on its own: the
 * schema is refreshed only when asked.
 *
 * <pre>
 *   &lt;vault-schema source="ldaps://host:636" read-at="2026-10-07T…"&gt;
 *     &lt;attribute name="Given Name" ldap="givenName" oid="2.5.4.42" syntax="1.3.6.1.4.1.1466.115.121.1.15" single="1"/&gt;
 *     &lt;class name="User" ldap="inetOrgPerson" oid="…" kind="structural" container="0"&gt;
 *       &lt;superclasses&gt;Organizational Person&lt;/superclasses&gt;
 *       &lt;mandatory&gt;CN, Surname&lt;/mandatory&gt;  &lt;optional&gt;…&lt;/optional&gt;
 *       &lt;containment&gt;Organization, Organizational Unit&lt;/containment&gt;  &lt;naming&gt;CN&lt;/naming&gt;
 *     &lt;/class&gt;
 *   &lt;/vault-schema&gt;
 * </pre>
 */
public final class VaultSchema {

    /** An attribute definition. Names are NDS names; {@code ldap} is the LDAP name. */
    public static final class AttrDef {
        public String name;
        public String ldap;
        public String oid;
        public String syntax;          // LDAP syntax OID, or Designer's short syntax when that is all we have
        public boolean single;
        public boolean noUserModification;
        public boolean custom;         // defined in the tree, to be pushed to the vault

        public AttrDef(String name, String ldap) {
            this.name = name;
            this.ldap = ldap;
        }
    }

    /** A class definition. Attribute lists hold NDS names. */
    public static final class ClassDef {
        public String name;
        public String ldap;
        public String oid;
        public String kind = "structural";   // structural | auxiliary | abstract
        public boolean container;
        public final List<String> superclasses = new ArrayList<>();
        public final List<String> mandatory = new ArrayList<>();
        public final List<String> optional = new ArrayList<>();
        public final List<String> containment = new ArrayList<>();
        public final List<String> naming = new ArrayList<>();
        public boolean custom;

        public ClassDef(String name, String ldap) {
            this.name = name;
            this.ldap = ldap;
        }
    }

    public final List<AttrDef> attributes = new ArrayList<>();
    public final List<ClassDef> classes = new ArrayList<>();
    /** Where it came from and when (source, read-at), for the page and the record. */
    public final Map<String, String> meta = new LinkedHashMap<>();

    private final Map<String, AttrDef> attrIndex = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, ClassDef> classIndex = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public void add(AttrDef a) {
        attributes.add(a);
        attrIndex.put(a.name, a);
        if (a.ldap != null && !a.ldap.isBlank()) {
            attrIndex.putIfAbsent(a.ldap, a);
        }
    }

    public void add(ClassDef c) {
        classes.add(c);
        classIndex.put(c.name, c);
        if (c.ldap != null && !c.ldap.isBlank()) {
            classIndex.putIfAbsent(c.ldap, c);
        }
    }

    /** By NDS or LDAP name, case-insensitively; null when unknown. */
    public AttrDef attribute(String name) {
        return name == null ? null : attrIndex.get(name);
    }

    public ClassDef classDef(String name) {
        return name == null ? null : classIndex.get(name);
    }

    public boolean remove(String name) {
        AttrDef a = attribute(name);
        if (a != null) {
            attributes.remove(a);
            attrIndex.values().removeIf(x -> x == a);
            return true;
        }
        ClassDef c = classDef(name);
        if (c != null) {
            classes.remove(c);
            classIndex.values().removeIf(x -> x == c);
            return true;
        }
        return false;
    }

    /** The attributes a class holds, its superclasses' included, mandatory first; by NDS name, without duplicates. */
    public List<String> attributesOf(String className) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        collect(classDef(className), out, seen, true);
        collect(classDef(className), out, seen, false);
        return new ArrayList<>(out);
    }

    private void collect(ClassDef c, Set<String> out, Set<String> seen, boolean mandatory) {
        if (c == null || !seen.add((mandatory ? "m:" : "o:") + c.name.toLowerCase(Locale.ROOT))) {
            return;
        }
        out.addAll(mandatory ? c.mandatory : c.optional);
        for (String s : c.superclasses) {
            collect(classDef(s), out, seen, mandatory);
        }
    }

    public List<AttrDef> customAttributes() {
        List<AttrDef> out = new ArrayList<>();
        for (AttrDef a : attributes) {
            if (a.custom) {
                out.add(a);
            }
        }
        return out;
    }

    public List<ClassDef> customClasses() {
        List<ClassDef> out = new ArrayList<>();
        for (ClassDef c : classes) {
            if (c.custom) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- from the vault's subschema ----

    private static final Pattern NDS_NAME = Pattern.compile("X-NDS_NAME '([^']*)'");
    private static final Pattern EXT_LIST = Pattern.compile("X-NDS_(CONTAINMENT|NAMING) \\( ((?:'[^']*' ?)+)\\)");
    private static final Pattern EXT_ONE = Pattern.compile("X-NDS_(CONTAINMENT|NAMING) '([^']*)'");

    /** From {@code cn=schema}'s {@code attributeTypes} and {@code objectClasses} (parsed by the clone's {@link Schema}). */
    public static VaultSchema fromLdap(Schema s, String source) {
        VaultSchema v = new VaultSchema();
        v.meta.put("source", source == null ? "" : source);
        v.meta.put("read-at", java.time.Instant.now().toString());
        for (Schema.Def d : s.attributes()) {
            if (d.name().contains(";")) {
                continue;   // transfer aliases such as userCertificate;binary
            }
            AttrDef a = new AttrDef(ndsName(d), d.name());
            a.oid = d.oid;
            a.syntax = s.syntaxOf(d.name());
            a.single = d.singleValue;
            a.noUserModification = d.noUserModification;
            v.add(a);
        }
        for (Schema.Def d : s.classes()) {
            ClassDef c = new ClassDef(ndsName(d), d.name());
            c.oid = d.oid;
            c.kind = d.raw.contains(" ABSTRACT") ? "abstract" : d.raw.contains(" AUXILIARY") ? "auxiliary" : "structural";
            for (String sup : d.sups) {
                Schema.Def sd = s.objectClass(sup);
                c.superclasses.add(sd == null ? sup : ndsName(sd));
            }
            for (String m : d.must) {
                c.mandatory.add(v.ndsOf(m));
            }
            for (String m : d.may) {
                c.optional.add(v.ndsOf(m));
            }
            Matcher ml = EXT_LIST.matcher(d.raw);
            while (ml.find()) {
                List<String> items = new ArrayList<>();
                Matcher q = Pattern.compile("'([^']*)'").matcher(ml.group(2));
                while (q.find()) {
                    items.add(q.group(1));
                }
                (ml.group(1).equals("CONTAINMENT") ? c.containment : c.naming).addAll(items);
            }
            Matcher mo = EXT_ONE.matcher(d.raw);
            while (mo.find()) {
                (mo.group(1).equals("CONTAINMENT") ? c.containment : c.naming).add(mo.group(2));
            }
            c.container = d.raw.contains("X-NDS_CONTAINER '1'") || !c.containment.isEmpty() && d.raw.contains("CONTAINER");
            v.add(c);
        }
        return v;
    }

    private static String ndsName(Schema.Def d) {
        Matcher m = NDS_NAME.matcher(d.raw);
        return m.find() ? m.group(1) : d.name();
    }

    private String ndsOf(String ldapOrNds) {
        AttrDef a = attribute(ldapOrNds);
        return a == null ? ldapOrNds : a.name;
    }

    // ---- from a Designer project's _schema.xml ----

    /** From Designer's {@code <schema><attr …/><class …><sup/><mand/><opt/><ctn/><nmng/></class></schema>}. */
    public static VaultSchema fromDesigner(Element root, String source) {
        return fromDesigner(root, source, java.time.Instant.now());
    }

    /** {@code readAt}: the project file's own time, so two reads of one project agree (the tree is compared file by file). */
    public static VaultSchema fromDesigner(Element root, String source, java.time.Instant readAt) {
        VaultSchema v = new VaultSchema();
        v.meta.put("source", source == null ? "designer-project" : source);
        v.meta.put("read-at", readAt.toString());
        for (Element e : children(root, "attr")) {
            AttrDef a = new AttrDef(e.getAttribute("name"), e.hasAttribute("ldap") ? e.getAttribute("ldap") : e.getAttribute("name"));
            a.oid = e.hasAttribute("aid") ? e.getAttribute("aid") : null;
            a.syntax = e.hasAttribute("syn") ? e.getAttribute("syn") : null;
            a.single = "1".equals(e.getAttribute("sngl"));
            a.noUserModification = "1".equals(e.getAttribute("nonrem")) && false;   // nonrem is "non-removable", not NO-USER-MODIFICATION
            a.custom = "1".equals(e.getAttribute("user-mod"));
            v.add(a);
        }
        for (Element e : children(root, "class")) {
            ClassDef c = new ClassDef(e.getAttribute("name"), e.hasAttribute("ldap") ? e.getAttribute("ldap") : e.getAttribute("name"));
            c.oid = e.hasAttribute("aid") ? e.getAttribute("aid") : null;
            c.container = "1".equals(e.getAttribute("ctn"));
            c.kind = "1".equals(e.getAttribute("aux")) ? "auxiliary" : "1".equals(e.getAttribute("eff")) ? "structural" : "abstract";
            c.custom = "1".equals(e.getAttribute("user-mod"));
            c.superclasses.addAll(csv(text(e, "sup")));
            c.mandatory.addAll(csv(text(e, "mand")));
            c.optional.addAll(csv(text(e, "opt")));
            c.containment.addAll(csv(text(e, "ctn")));
            c.naming.addAll(csv(text(e, "nmng")));
            v.add(c);
        }
        return v;
    }

    // ---- our file ----

    public static VaultSchema fromXml(Element root) {
        VaultSchema v = new VaultSchema();
        for (String k : List.of("source", "read-at")) {
            if (root.hasAttribute(k)) {
                v.meta.put(k, root.getAttribute(k));
            }
        }
        for (Element e : children(root, "attribute")) {
            AttrDef a = new AttrDef(e.getAttribute("name"), e.getAttribute("ldap"));
            a.oid = e.hasAttribute("oid") ? e.getAttribute("oid") : null;
            a.syntax = e.hasAttribute("syntax") ? e.getAttribute("syntax") : null;
            a.single = "1".equals(e.getAttribute("single"));
            a.noUserModification = "1".equals(e.getAttribute("no-user-modification"));
            a.custom = "1".equals(e.getAttribute("custom"));
            v.add(a);
        }
        for (Element e : children(root, "class")) {
            ClassDef c = new ClassDef(e.getAttribute("name"), e.getAttribute("ldap"));
            c.oid = e.hasAttribute("oid") ? e.getAttribute("oid") : null;
            c.kind = e.hasAttribute("kind") ? e.getAttribute("kind") : "structural";
            c.container = "1".equals(e.getAttribute("container"));
            c.custom = "1".equals(e.getAttribute("custom"));
            c.superclasses.addAll(csv(text(e, "superclasses")));
            c.mandatory.addAll(csv(text(e, "mandatory")));
            c.optional.addAll(csv(text(e, "optional")));
            c.containment.addAll(csv(text(e, "containment")));
            c.naming.addAll(csv(text(e, "naming")));
            v.add(c);
        }
        return v;
    }

    public Element toXml() {
        Document doc = CanonicalXml.parse("<vault-schema/>");
        Element root = doc.getDocumentElement();
        for (Map.Entry<String, String> m : meta.entrySet()) {
            root.setAttribute(m.getKey(), m.getValue());
        }
        for (AttrDef a : attributes) {
            Element e = doc.createElementNS(null, "attribute");
            e.setAttribute("name", a.name);
            e.setAttribute("ldap", a.ldap == null ? a.name : a.ldap);
            if (a.oid != null) {
                e.setAttribute("oid", a.oid);
            }
            if (a.syntax != null) {
                e.setAttribute("syntax", a.syntax);
            }
            if (a.single) {
                e.setAttribute("single", "1");
            }
            if (a.noUserModification) {
                e.setAttribute("no-user-modification", "1");
            }
            if (a.custom) {
                e.setAttribute("custom", "1");
            }
            root.appendChild(e);
        }
        for (ClassDef c : classes) {
            Element e = doc.createElementNS(null, "class");
            e.setAttribute("name", c.name);
            e.setAttribute("ldap", c.ldap == null ? c.name : c.ldap);
            if (c.oid != null) {
                e.setAttribute("oid", c.oid);
            }
            e.setAttribute("kind", c.kind);
            if (c.container) {
                e.setAttribute("container", "1");
            }
            if (c.custom) {
                e.setAttribute("custom", "1");
            }
            child(doc, e, "superclasses", c.superclasses);
            child(doc, e, "mandatory", c.mandatory);
            child(doc, e, "optional", c.optional);
            child(doc, e, "containment", c.containment);
            child(doc, e, "naming", c.naming);
            root.appendChild(e);
        }
        return root;
    }

    // ---- what the vault is told ----

    /** The RFC 4512 definition of a custom attribute, as {@code attributeTypes} takes it. */
    public String ldapDefinition(AttrDef a) {
        StringBuilder sb = new StringBuilder("( ").append(a.oid == null ? "" : a.oid).append(" NAME '").append(a.ldap == null ? a.name : a.ldap).append("'");
        sb.append(" SYNTAX ").append(a.syntax == null ? "1.3.6.1.4.1.1466.115.121.1.15" : a.syntax);
        if (a.single) {
            sb.append(" SINGLE-VALUE");
        }
        if (a.noUserModification) {
            sb.append(" NO-USER-MODIFICATION");
        }
        if (a.name != null && !a.name.equals(a.ldap)) {
            sb.append(" X-NDS_NAME '").append(a.name).append("'");
        }
        return sb.append(" )").toString();
    }

    /** The RFC 4512 definition of a custom class, as {@code objectClasses} takes it; attribute lists by LDAP name. */
    public String ldapDefinition(ClassDef c) {
        StringBuilder sb = new StringBuilder("( ").append(c.oid == null ? "" : c.oid).append(" NAME '").append(c.ldap == null ? c.name : c.ldap).append("'");
        if (!c.superclasses.isEmpty()) {
            sb.append(" SUP ").append(ldapList(c.superclasses, true));
        }
        sb.append(" ").append(c.kind.toUpperCase(Locale.ROOT));
        if (!c.mandatory.isEmpty()) {
            sb.append(" MUST ").append(ldapList(c.mandatory, false));
        }
        if (!c.optional.isEmpty()) {
            sb.append(" MAY ").append(ldapList(c.optional, false));
        }
        if (!c.name.equals(c.ldap)) {
            sb.append(" X-NDS_NAME '").append(c.name).append("'");
        }
        if (!c.containment.isEmpty()) {
            sb.append(" X-NDS_CONTAINMENT ").append(quotedList(c.containment, true));
        }
        if (!c.naming.isEmpty()) {
            sb.append(" X-NDS_NAMING ").append(quotedList(c.naming, false));
        }
        return sb.append(" )").toString();
    }

    private String ldapList(List<String> names, boolean classes) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (classes) {
                ClassDef c = classDef(n);
                out.add(c == null || c.ldap == null ? n : c.ldap);
            } else {
                AttrDef a = attribute(n);
                out.add(a == null || a.ldap == null ? n : a.ldap);
            }
        }
        return out.size() == 1 ? out.get(0) : "( " + String.join(" $ ", out) + " )";
    }

    private String quotedList(List<String> names, boolean classes) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String ldap = n;
            if (classes) {
                ClassDef c = classDef(n);
                ldap = c == null || c.ldap == null ? n : c.ldap;
            } else {
                AttrDef a = attribute(n);
                ldap = a == null || a.ldap == null ? n : a.ldap;
            }
            out.add("'" + ldap + "'");
        }
        return out.size() == 1 ? out.get(0) : "( " + String.join(" ", out) + " )";
    }

    // ---- helpers ----

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? "" : c.get(0).getTextContent();
    }

    private static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (String p : s.split(",")) {
            if (!p.trim().isEmpty()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static void child(Document doc, Element parent, String name, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        Element e = doc.createElementNS(null, name);
        e.setTextContent(String.join(", ", values));
        parent.appendChild(e);
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>(meta);
        m.put("attributes", attributes.size());
        m.put("classes", classes.size());
        m.put("customAttributes", customAttributes().size());
        m.put("customClasses", customClasses().size());
        return Collections.unmodifiableMap(m);
    }
}
