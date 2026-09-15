package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * {@code DirXML-Entitlement} definitions hanging off a driver (Track W step W4b — see
 * {@code docs/entitlements.md} §2). On by default in {@link Validator#standard},
 * registered after {@link FlowCheck}.
 *
 * <p>Codes: {@code entitlement-name-blank} (E), {@code entitlement-no-document} (E),
 * {@code entitlement-wrong-root} (E), {@code entitlement-conflict-invalid} (E),
 * {@code entitlement-multi-valued-invalid} (E).
 */
public final class EntitlementCheck implements Check {

    @Override
    public String name() {
        return "entitlements";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (Driver d : ds.drivers) {
            for (Entitlement e : d.entitlements) {
                checkEntitlement(d, e, r);
            }
        }
    }

    private static void checkEntitlement(Driver d, Entitlement e, Report r) {
        String path = path(d, e);
        if (e.name == null || e.name.isBlank()) {
            r.add(Finding.error("entitlement-name-blank", path, "entitlement has a blank name"));
        }
        if (e.definition == null) {
            r.add(Finding.error("entitlement-no-document", path, "entitlement has no <entitlement> document"));
            return;
        }
        String root = localName(e.definition);
        if (!"entitlement".equals(root)) {
            r.add(Finding.error("entitlement-wrong-root", path, "document root is <" + root + ">, not <entitlement>"));
            return;
        }
        String cr = e.conflictResolution();
        if (cr != null && !cr.equals("union") && !cr.equals("priority")) {
            r.add(Finding.error("entitlement-conflict-invalid", path,
                "conflict-resolution is '" + cr + "', not one of union, priority"));
        }
        Element values = firstChild(e.definition, "values");
        if (values != null && values.hasAttribute("multi-valued")) {
            String mv = values.getAttribute("multi-valued");
            if (!mv.equals("true") && !mv.equals("false")) {
                r.add(Finding.error("entitlement-multi-valued-invalid", path,
                    "values/@multi-valued is '" + mv + "', not true or false"));
            }
        }
    }

    private static Element firstChild(Element parent, String localName) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(c))) {
                return (Element) c;
            }
        }
        return null;
    }

    private static String localName(Node n) {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    // ---- paths (duplicated from edit.EntitlementOps to avoid a validate -> edit dependency) ------

    private static String path(Driver d, Entitlement e) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/entitlements/" + AsCodeWriter.fileSafe(e.name);
    }
}
