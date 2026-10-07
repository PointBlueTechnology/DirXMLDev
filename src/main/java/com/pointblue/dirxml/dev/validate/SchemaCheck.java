package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.VaultSchema;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * With a vault schema in the tree: every class and attribute a filter names, and every eDirectory
 * name a schema map names, must be in it. Warnings ({@code schema-unknown-class},
 * {@code schema-unknown-attr}): the vault may have moved on since the schema was read, which a
 * {@code vault.schema} refresh settles. Nothing is checked without a schema.
 */
public final class SchemaCheck implements Check {

    @Override
    public String name() {
        return "schema";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        VaultSchema s = ds.schema;
        if (s == null) {
            return;
        }
        for (Driver d : ds.drivers) {
            Element filter = d.config.get(Driver.DRIVER_FILTER);
            String fpath = "drivers/" + d.name + "/driver-filter";
            if (filter != null) {
                for (Element c : descendants(filter, "filter-class")) {
                    String cls = c.getAttribute("class-name");
                    if (!cls.isEmpty() && s.classDef(cls) == null) {
                        r.add(Finding.warning("schema-unknown-class", fpath, "filter class '" + cls + "' is not in the vault schema (read " + s.meta.get("read-at") + "); refresh with vault.schema, or define it with schema.add-class"));
                        continue;
                    }
                    List<String> known = cls.isEmpty() ? List.of() : s.attributesOf(cls);
                    for (Element a : descendants(c, "filter-attr")) {
                        String attr = a.getAttribute("attr-name");
                        if (attr.isEmpty()) {
                            continue;
                        }
                        VaultSchema.AttrDef def = s.attribute(attr);
                        if (def == null) {
                            r.add(Finding.warning("schema-unknown-attr", fpath, "filter attribute '" + cls + "." + attr + "' is not in the vault schema"));
                        } else if (!known.isEmpty() && !containsIgnoreCase(known, def.name)) {
                            r.add(Finding.warning("schema-attr-not-of-class", fpath, "'" + attr + "' is not an attribute of class '" + cls + "' (or its superclasses) in the vault schema"));
                        }
                    }
                }
            }
            for (Policy p : policies(d)) {
                if (p.content == null) {
                    continue;
                }
                for (Element map : descendants(p.content, "attr-name-map")) {
                    for (Element cn : descendants(map, "class-name")) {
                        String nds = text(cn, "nds-name");
                        if (nds != null && !nds.isEmpty() && s.classDef(nds) == null) {
                            r.add(Finding.warning("schema-unknown-class", p.path(), "schema map class '" + nds + "' is not in the vault schema"));
                        }
                        for (Element an : descendants(cn, "attr-name")) {
                            String a = text(an, "nds-name");
                            if (a != null && !a.isEmpty() && s.attribute(a) == null) {
                                r.add(Finding.warning("schema-unknown-attr", p.path(), "schema map attribute '" + nds + "." + a + "' is not in the vault schema"));
                            }
                        }
                    }
                    for (Element an : children(map, "attr-name")) {
                        String a = text(an, "nds-name");
                        if (a != null && !a.isEmpty() && s.attribute(a) == null) {
                            r.add(Finding.warning("schema-unknown-attr", p.path(), "schema map attribute '" + a + "' is not in the vault schema"));
                        }
                    }
                }
            }
        }
    }

    private static List<Policy> policies(Driver d) {
        List<Policy> out = new ArrayList<>(d.policies);
        out.addAll(d.subscriber.policies);
        out.addAll(d.publisher.policies);
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) {
            if (x.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static List<Element> descendants(Element root, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                if (name.equals(n.getNodeName())) {
                    out.add((Element) n);
                }
                out.addAll(descendants((Element) n, name));
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? null : c.get(0).getTextContent().trim();
    }
}
