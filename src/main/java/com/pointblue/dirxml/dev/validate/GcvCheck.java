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
 * <p>Codes: {@code gcv-undefined} (E), {@code gcv-undefined-library} (W).
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
            }
        }
        for (Policy p : ds.library.policies) {
            List<String> missing = missing(p, union);
            if (!missing.isEmpty()) {
                r.add(Finding.warning("gcv-undefined-library", p.path(),
                    "references GCV(s) no driver in this set defines: " + String.join(", ", missing)));
            }
        }
    }

    private static List<String> missing(Policy p, Set<String> defined) {
        List<String> out = new ArrayList<>();
        if (p.content == null) {
            return out;
        }
        for (String name : GcvReferences.referenced(p.content)) {
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
