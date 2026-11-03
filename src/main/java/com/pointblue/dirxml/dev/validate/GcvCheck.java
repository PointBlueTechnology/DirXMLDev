package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.sim.GcvReferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every GCV a driver's policies reference must be defined somewhere the engine will
 * look: the driver's config-values, a GCV-definition resource linked in its set 14,
 * the driver set's config-values or the driver set's own linked GCV resources.
 * (The engine's {@code dirxml.auto.*} values are always defined.) An undefined GCV
 * doesn't stop the driver loading — it fails the rule at run time, in production —
 * so it's an {@code ERROR}.
 *
 * <p>Library policies are shared: they're checked against the union of every
 * driver's definitions plus the driver set's, and a miss is a {@code WARNING}
 * ({@code gcv-undefined-library}), since the policy may be linked only by drivers
 * that do define it.
 *
 * <p>An {@code <if-global-variable>} condition is different: the engine reads an
 * undefined GCV there as having no value ({@code not-available} is true, {@code equal}
 * is false) and nothing fails. Packages test GCVs another package may define that way.
 * A condition with {@code op="available"} or {@code "not-available"} asks exactly that
 * and is never reported; any other op on an undefined GCV is an {@code INFO}
 * ({@code gcv-undefined-condition}), since its outcome is then fixed.
 *
 * <p>Codes: {@code gcv-undefined} (E), {@code gcv-undefined-library} (W),
 * {@code gcv-undefined-condition} (I).
 */
public final class GcvCheck implements Check {

    @Override
    public String name() {
        return "gcv";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        Map<String, Artifact> index = ds.index();
        Set<String> union = new LinkedHashSet<>();
        for (Driver d : ds.drivers) {
            Set<String> defined = Model.definedGcvs(ds, d, index);
            union.addAll(defined);
            for (Policy p : Model.policies(d)) {
                List<String> missing = missing(p, defined);
                if (!missing.isEmpty()) {
                    r.add(Finding.error("gcv-undefined", p.path(),
                        "references undefined GCV(s): " + String.join(", ", missing)));
                }
                conditionFinding(r, p, defined);
            }
        }
        for (Policy p : ds.library.policies) {
            List<String> missing = missing(p, union);
            if (!missing.isEmpty()) {
                r.add(Finding.warning("gcv-undefined-library", p.path(),
                    "references GCV(s) no driver in this set defines: " + String.join(", ", missing)));
            }
            conditionFinding(r, p, union);
        }
    }

    private static void conditionFinding(Report r, Policy p, Set<String> defined) {
        if (p.content == null) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        conditions(p.content, names);
        names.removeAll(GcvReferences.referenced(p.content));
        List<String> missing = undefined(names, defined);
        if (!missing.isEmpty()) {
            r.add(Finding.info("gcv-undefined-condition", p.path(),
                "tests GCV(s) nothing defines: " + String.join(", ", missing)
                    + " — the engine reads them as having no value, so those tests always come out the same"));
        }
    }

    /**
     * {@code <if-global-variable name=…>} conditions that compare a GCV's value; they have
     * no token form, so the reference scan misses them. {@code available} and
     * {@code not-available} only ask whether it is defined, so they're left out.
     */
    private static void conditions(org.w3c.dom.Element e, Set<String> names) {
        if ("if-global-variable".equals(e.getNodeName()) && !e.getAttribute("name").isEmpty()) {
            String op = e.getAttribute("op");
            if (!"available".equals(op) && !"not-available".equals(op)) {
                names.add(e.getAttribute("name"));
            }
        }
        for (org.w3c.dom.Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof org.w3c.dom.Element) {
                conditions((org.w3c.dom.Element) c, names);
            }
        }
    }

    private static List<String> missing(Policy p, Set<String> defined) {
        if (p.content == null) {
            return new ArrayList<>();
        }
        return undefined(GcvReferences.referenced(p.content), defined);
    }

    private static List<String> undefined(java.util.Collection<String> names, Set<String> defined) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            // A name built from a local variable (drv.x.$entName$) is resolved at run
            // time; nothing static to check.
            if (name.contains("$")) {
                continue;
            }
            if (!defined.contains(name) && !GcvReferences.isEngineProvided(name)) {
                out.add(name);
            }
        }
        return out;
    }
}
