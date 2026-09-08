package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Structural operations on the {@code <rule>}s of a DirXML Script policy — the
 * ones agents do constantly and get the XML wrong: insert a rule at a position,
 * delete or move one, disable/enable one ({@code <rule disabled="true">}, what
 * the engine honours and Designer's checkbox sets). Rule content is still a file
 * edit; these only move whole rules.
 *
 * <p>A rule is identified by its {@code <description>} text, or by {@code #n}
 * (1-based position) when descriptions repeat or are missing.
 */
public final class RuleOps {

    private RuleOps() {
    }

    /** {@code first | last | after:<rule> | before:<rule>} for rules. */
    static int insertionIndex(List<Element> rules, String position) throws Operation.Refusal {
        if (position == null || position.isBlank() || position.equals("last")) {
            return rules.size();
        }
        if (position.equals("first")) {
            return 0;
        }
        if (position.startsWith("after:")) {
            return indexOf(rules, position.substring(6)) + 1;
        }
        if (position.startsWith("before:")) {
            return indexOf(rules, position.substring(7));
        }
        throw new Operation.Refusal("position must be first|last|after:<rule>|before:<rule>, not '" + position + "'");
    }

    /** The index of the rule named by description or {@code #n}; refuses when absent or ambiguous. */
    static int indexOf(List<Element> rules, String id) throws Operation.Refusal {
        if (id == null || id.isBlank()) {
            throw new Operation.Refusal("a rule (description or #n) is required");
        }
        if (id.startsWith("#")) {
            try {
                int n = Integer.parseInt(id.substring(1));
                if (n < 1 || n > rules.size()) {
                    throw new Operation.Refusal("rule " + id + " is out of range; the policy has " + rules.size() + " rule(s)");
                }
                return n - 1;
            } catch (NumberFormatException e) {
                throw new Operation.Refusal("'" + id + "' is not a rule position");
            }
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            if (id.equals(description(rules.get(i)))) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            throw new Operation.Refusal("no rule '" + id + "'; rules: " + describe(rules));
        }
        if (hits.size() > 1) {
            throw new Operation.Refusal("'" + id + "' names " + hits.size() + " rules; use #n (positions " + positions(hits) + ")");
        }
        return hits.get(0);
    }

    static String description(Element rule) {
        List<Element> d = Xds.childrenByName(rule, "description");
        return d.isEmpty() ? "" : Xds.text(d.get(0)).trim();
    }

    static List<String> describe(List<Element> rules) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            out.add("#" + (i + 1) + " " + description(rules.get(i)));
        }
        return out;
    }

    private static String positions(List<Integer> hits) {
        List<String> p = new ArrayList<>();
        for (int h : hits) {
            p.add("#" + (h + 1));
        }
        return String.join(", ", p);
    }

    static Policy scriptPolicy(DriverSet ds, String path) throws Operation.Refusal {
        Artifact a = ArtifactOps.artifactOrRefuse(ds, path);
        if (!(a instanceof Policy) || ((Policy) a).policyKind() != Policy.Kind.DIRXML_SCRIPT) {
            throw new Operation.Refusal("'" + path + "' is not a DirXML Script policy");
        }
        return (Policy) a;
    }

    // ---- add ------------------------------------------------------------------

    public static final class Add implements Operation {
        private final String path;
        private final String ruleXml;
        private final String position;

        public Add(String path, String ruleXml, String position) {
            this.path = path;
            this.ruleXml = ruleXml;
            this.position = position;
        }

        @Override
        public String name() {
            return "rule.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Policy p = scriptPolicy(ds, path);
            if (ruleXml == null || ruleXml.isBlank()) {
                throw new Refusal("the rule's XML is required (--content-file)");
            }
            Element rule = ArtifactOps.parse(ruleXml);
            if (!"rule".equals(rule.getLocalName() != null ? rule.getLocalName() : rule.getNodeName())) {
                throw new Refusal("content root must be <rule>, not <" + rule.getNodeName() + ">");
            }
            List<Element> rules = Xds.childrenByName(p.content, "rule");
            int at = insertionIndex(rules, position);
            tx.touch(p);
            Node imported = p.content.getOwnerDocument().importNode(rule, true);
            if (at >= rules.size()) {
                p.content.appendChild(imported);
            } else {
                p.content.insertBefore(imported, rules.get(at));
            }
        }
    }

    // ---- delete ---------------------------------------------------------------

    public static final class Delete implements Operation {
        private final String path;
        private final String rule;

        public Delete(String path, String rule) {
            this.path = path;
            this.rule = rule;
        }

        @Override
        public String name() {
            return "rule.delete";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Policy p = scriptPolicy(ds, path);
            List<Element> rules = Xds.childrenByName(p.content, "rule");
            Element r = rules.get(indexOf(rules, rule));
            tx.touch(p);
            p.content.removeChild(r);
        }
    }

    // ---- move -----------------------------------------------------------------

    public static final class Move implements Operation {
        private final String path;
        private final String rule;
        private final String position;

        public Move(String path, String rule, String position) {
            this.path = path;
            this.rule = rule;
            this.position = position;
        }

        @Override
        public String name() {
            return "rule.move";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Policy p = scriptPolicy(ds, path);
            List<Element> rules = Xds.childrenByName(p.content, "rule");
            Element r = rules.get(indexOf(rules, rule));
            List<Element> others = new ArrayList<>(rules);
            others.remove(r);
            int at = insertionIndex(others, position);
            tx.touch(p);
            p.content.removeChild(r);
            if (at >= others.size()) {
                p.content.appendChild(r);
            } else {
                p.content.insertBefore(r, others.get(at));
            }
        }
    }

    // ---- disable / enable -----------------------------------------------------

    public static final class SetDisabled implements Operation {
        private final String path;
        private final String rule;
        private final boolean disabled;

        public SetDisabled(String path, String rule, boolean disabled) {
            this.path = path;
            this.rule = rule;
            this.disabled = disabled;
        }

        @Override
        public String name() {
            return disabled ? "rule.disable" : "rule.enable";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Policy p = scriptPolicy(ds, path);
            List<Element> rules = Xds.childrenByName(p.content, "rule");
            Element r = rules.get(indexOf(rules, rule));
            tx.touch(p);
            if (disabled) {
                r.setAttribute("disabled", "true");
            } else {
                r.removeAttribute("disabled");
            }
        }
    }
}
