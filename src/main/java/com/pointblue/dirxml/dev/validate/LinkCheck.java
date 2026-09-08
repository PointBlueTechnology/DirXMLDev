package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Linkage integrity — the {@code DirXML-Policies} chains. A link must resolve, must
 * point at the right kind of artifact for its set (schema map in set 0, ECMAScript
 * resources in set 3, GCV definitions in set 14, DirXML Script / XSLT policies
 * elsewhere), and orders within a set should be unique. Also flags policies no set
 * links (dead weight, or a link someone forgot) and drivers with no shim class.
 *
 * <p>Codes: {@code link-unresolved} (E), {@code link-kind} (E), {@code link-duplicate-order}
 * (W), {@code link-cross-driver} (W), {@code link-channel-mismatch} (W),
 * {@code policy-unlinked} (I), {@code driver-no-shim} (E), {@code driver-no-config} (W).
 */
public final class LinkCheck implements Check {

    @Override
    public String name() {
        return "links";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        Map<String, Artifact> index = ds.index();
        Set<String> linked = new HashSet<>();
        for (Driver d : ds.drivers) {
            String dpath = "drivers/" + d.name;
            if (d.shimClass == null || d.shimClass.isBlank()) {
                r.add(Finding.error("driver-no-shim", dpath, "driver has no shim class (DirXML-JavaModule)"));
            }
            if (!d.config.containsKey(Driver.SHIM_CONFIG_INFO)) {
                r.add(Finding.warning("driver-no-config", dpath, "driver has no shim-config-info (DirXML-ShimConfigInfo)"));
            }
            if (!d.config.containsKey(Driver.DRIVER_FILTER)) {
                r.add(Finding.warning("driver-no-filter", dpath, "driver has no filter (DirXML-DriverFilter)"));
            }
            Map<PolicySet, Map<Integer, PolicyLink>> orders = new HashMap<>();
            for (PolicyLink l : d.links) {
                Artifact a = index.get(l.ref);
                if (a == null) {
                    r.add(Finding.error("link-unresolved", dpath,
                        "policy set " + l.set.key + " order " + l.order + " links to '" + l.ref + "', which does not exist"));
                    continue;
                }
                linked.add(a.path());
                checkKind(l, a, dpath, r);
                if (a.scope != Scope.LIBRARY && !d.name.equals(a.driver)) {
                    r.add(Finding.warning("link-cross-driver", dpath,
                        "policy set " + l.set.key + " links to another driver's artifact '" + l.ref + "'"));
                }
                Scope want = Model.scopeOf(l.set);
                if ((a.scope == Scope.SUBSCRIBER && want == Scope.PUBLISHER)
                    || (a.scope == Scope.PUBLISHER && want == Scope.SUBSCRIBER)) {
                    r.add(Finding.warning("link-channel-mismatch", a.path(),
                        "a " + a.scope.key + " policy is linked into the " + want.key + " set " + l.set.key));
                }
                PolicyLink prev = orders.computeIfAbsent(l.set, k -> new HashMap<>()).put(l.order, l);
                if (prev != null) {
                    r.add(Finding.warning("link-duplicate-order", dpath,
                        "policy set " + l.set.key + " has two links at order " + l.order + ": '"
                            + prev.ref + "' and '" + l.ref + "' (execution order is undefined)"));
                }
            }
        }
        // Unlinked policies. Library policies are shared by design; only report a
        // driver's own policies, which have exactly one intended home.
        for (Driver d : ds.drivers) {
            for (Policy p : Model.policies(d)) {
                if (!linked.contains(p.path())) {
                    r.add(Finding.info("policy-unlinked", p.path(), "policy is not linked into any policy set"));
                }
            }
        }
    }

    private static void checkKind(PolicyLink l, Artifact a, String dpath, Report r) {
        String problem = null;
        switch (l.set) {
            case SCHEMA_MAPPING:
                // The schema-mapping set holds the <attr-name-map> and may also hold
                // DirXML Script / XSLT policies that run around it.
                if (!(a instanceof Policy) || ((Policy) a).policyKind() == Policy.Kind.OTHER) {
                    problem = "must be a schema-mapping (<attr-name-map>), DirXML Script or XSLT policy";
                }
                break;
            case ECMASCRIPT:
                if (!(a instanceof Resource) || !((Resource) a).isEcmaScript()) {
                    problem = "must be an ECMAScript resource (text/ecmascript)";
                }
                break;
            case GCV:
                if (!(a instanceof Resource) || !((Resource) a).isGcvDef()) {
                    problem = "must be a GCV-definition resource";
                }
                break;
            default:
                if (!(a instanceof Policy)) {
                    problem = "must be a policy, not a " + a.kind();
                } else {
                    Policy.Kind k = ((Policy) a).policyKind();
                    if (k != Policy.Kind.DIRXML_SCRIPT && k != Policy.Kind.XSLT) {
                        problem = "must be a DirXML Script or XSLT policy (found " + k + ")";
                    }
                }
        }
        if (problem != null) {
            r.add(Finding.error("link-kind", dpath,
                "policy set " + l.set.key + " links '" + l.ref + "': " + problem));
        }
    }
}
