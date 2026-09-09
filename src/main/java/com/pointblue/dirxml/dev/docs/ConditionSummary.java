package com.pointblue.dirxml.dev.docs;

import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a DirXML Script rule's {@code <conditions>} as one line of prose — the
 * grammar documented in {@code docs/designer-roundtrip.md}, "1. Documentation
 * from the model":
 * <pre>
 *   if-class-name op="equal"&gt;User            -&gt; class User
 *   if-op-attr name="Title" op="changing"     -&gt; Title changing
 *   if-op-attr name="X" op="equal"&gt;v          -&gt; X = v
 *   if-operation op="equal"&gt;modify            -&gt; op modify
 *   if-xpath&gt;expr                             -&gt; xpath(expr, abbreviated to 40 chars)
 *   if-global-variable name="g" op="equal"&gt;v  -&gt; gcv g = v
 *   if-local-variable name="x" op="equal"&gt;v   -&gt; var x = v
 *   if-src-dn / if-dest-dn                    -&gt; src-dn … / dest-dn …
 *   if-association / if-entitlement           -&gt; association … / entitlement …
 *   (unknown if-*)                            -&gt; the element name minus "if-"
 * </pre>
 * {@code <and>}/{@code <or>} join their children with " and "/" or "; a nested
 * group of more than one condition is parenthesized. An {@code op} starting with
 * {@code "not-"} (or exactly {@code "not-equal"}) prefixes the phrase with "not".
 * Empty conditions summarize as {@code "always"}.
 */
public final class ConditionSummary {

    private static final int XPATH_ABBREVIATE = 40;

    private ConditionSummary() {
    }

    /** The rule's {@code <conditions>} element (may be null or childless) as one line. */
    public static String summarize(Element conditions) {
        if (conditions == null) {
            return "always";
        }
        List<Element> kids = Xds.childElements(conditions);
        if (kids.isEmpty()) {
            return "always";
        }
        List<String> parts = new ArrayList<>();
        for (Element k : kids) {
            parts.add(render(k, false));
        }
        return String.join(" and ", parts);
    }

    private static String render(Element e, boolean nested) {
        String ln = localName(e);
        if (ln.equals("and") || ln.equals("or")) {
            List<Element> kids = Xds.childElements(e);
            if (kids.isEmpty()) {
                return "always";
            }
            String joiner = ln.equals("and") ? " and " : " or ";
            List<String> parts = new ArrayList<>();
            for (Element k : kids) {
                parts.add(render(k, true));
            }
            String joined = String.join(joiner, parts);
            return nested && kids.size() > 1 ? "(" + joined + ")" : joined;
        }
        if (ln.startsWith("if-")) {
            return condition(e, ln.substring(3));
        }
        return ln;
    }

    private static String condition(Element e, String what) {
        String op = e.hasAttribute("op") ? e.getAttribute("op") : null;
        String name = e.hasAttribute("name") ? e.getAttribute("name") : null;
        String value = Xds.text(e).trim();
        switch (what) {
            case "class-name":
                return phrase("class", op, value, true);
            case "operation":
                return phrase("op", op, value, true);
            case "op-attr":
                return phrase(name == null || name.isBlank() ? "attr" : name, op, value, false);
            case "global-variable":
                return phrase("gcv " + (name == null ? "" : name), op, value, false);
            case "local-variable":
                return phrase("var " + (name == null ? "" : name), op, value, false);
            case "xpath":
                return xpath(op, value);
            case "src-dn":
                return phrase("src-dn", op, value, false);
            case "dest-dn":
                return phrase("dest-dn", op, value, false);
            case "association":
                return phrase("association", op, value, false);
            case "entitlement":
                return phrase("entitlement", op, value, false);
            default:
                // unknown if-*: the element name minus "if-", bare
                return what;
        }
    }

    private static String xpath(String op, String value) {
        String abbrev = abbreviate(value.replaceAll("\\s+", " ").trim(), XPATH_ABBREVIATE);
        String core = "xpath(" + abbrev + ")";
        return "false".equals(op) ? "not " + core : core;
    }

    /**
     * {@code label op value} in the engine's spirit: {@code changing}/{@code available}
     * read as bare adjectives ({@code "label changing"}), {@code equal} as
     * {@code "label = value"} (or bare {@code "label value"} when
     * {@code bareForEqual}), anything else as {@code "label op value"}. A
     * {@code not-…} op (or exactly {@code not-equal}) prefixes the whole phrase
     * with {@code "not "}.
     */
    private static String phrase(String label, String op, String value, boolean bareForEqual) {
        boolean negate = op != null && op.startsWith("not-");
        String positive = negate ? op.substring(4) : op;
        String core;
        if (positive == null || positive.isBlank()) {
            core = value.isEmpty() ? label : label + " " + value;
        } else if (positive.equals("changing") || positive.equals("available")) {
            core = label + " " + positive;
        } else if (positive.equals("equal")) {
            core = bareForEqual ? (value.isEmpty() ? label : label + " " + value) : (label + " = " + value);
        } else {
            core = value.isEmpty() ? label + " " + positive : label + " " + positive + " " + value;
        }
        return negate ? "not " + core : core;
    }

    private static String localName(Element e) {
        String ln = e.getLocalName();
        return ln != null ? ln : e.getNodeName();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
