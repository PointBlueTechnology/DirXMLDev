package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Every DirXML-Script element carries the attributes its grammar requires
 * ({@link ScriptGrammar}). The engine's compiler ({@link CompileCheck}) does not enforce them
 * all, so a policy can run in the vault and still be one Designer refuses to import and drops
 * — ig4's {@code Send expiration email} (a {@code do-send-email-from-template} with no
 * {@code template-dn}) was found that way, importing the clone (2026-09-21). A warning, not an
 * error: the engine runs such a policy, and a deploy refuses a tree with any error, so an error
 * here would block every deploy of a tree that carries one of these until it is repaired — the
 * Designer round trip is what breaks, and the finding names the rule so it can be repaired. A
 * policy edit that introduces one is still visible in the edit's report.
 */
public final class ScriptCheck implements Check {

    public static final String CODE = "script-required-attribute";

    @Override
    public String name() {
        return "script";
    }

    @Override
    public void run(DriverSet ds, Report report) {
        for (Driver d : ds.drivers) {
            for (Policy p : Model.policies(d)) {
                check(p, report);
            }
        }
        for (Policy p : ds.library.policies) {
            check(p, report);
        }
    }

    private static void check(Policy p, Report report) {
        if (p.content == null || p.policyKind() != Policy.Kind.DIRXML_SCRIPT) {
            return;
        }
        walk(p.content, p, report);
    }

    private static void walk(Element e, Policy p, Report report) {
        String tag = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
        List<String> required = ScriptGrammar.REQUIRED.get(tag);
        if (required != null) {
            for (String attr : required) {
                if (!e.hasAttribute(attr)) {
                    report.add(Finding.warning(CODE, p.path(),
                        "<" + tag + "> lacks its required attribute '" + attr + "'" + where(e)
                            + " — the engine may load it, Designer's importer drops the policy"));
                }
            }
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                walk((Element) n, p, report);
            }
        }
    }

    /** " in rule '<description>'" for an element under a rule that has one; "" otherwise. */
    private static String where(Element e) {
        for (Node n = e.getParentNode(); n instanceof Element; n = n.getParentNode()) {
            Element a = (Element) n;
            String tag = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
            if ("rule".equals(tag)) {
                for (Node c = a.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c instanceof Element && "description".equals(c.getNodeName())) {
                        String text = c.getTextContent() == null ? "" : c.getTextContent().strip();
                        return text.isEmpty() ? "" : " in rule '" + text + "'";
                    }
                }
                return "";
            }
        }
        return "";
    }
}
