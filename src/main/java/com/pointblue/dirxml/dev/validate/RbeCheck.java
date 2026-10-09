package com.pointblue.dirxml.dev.validate;

import java.util.Locale;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.EntitlementPolicy;

/**
 * Role-based entitlement policies ({@code DirXML-SharedProfile}, docs/console-gaps.md §9), on by default
 * in {@link Validator#standard}.
 *
 * <p>Codes: {@code rbe-name-blank} (E), {@code rbe-legacy-entitlements-xml} (E: the shim refuses a policy
 * whose {@code DirXML-SPEntitlementsXML} is set — "unconverted policy"), {@code rbe-no-membership} (W:
 * neither a membership query nor a static member), {@code rbe-no-entitlement} (W: it grants nothing),
 * {@code rbe-unknown-entitlement} (W: a grant names an entitlement no driver of the set defines),
 * {@code rbe-no-service-driver} (W: policies, but no driver runs the Entitlements Service shim),
 * {@code rbe-no-priority} (E), {@code rbe-duplicate-priority} (E), {@code rbe-priorities-not-sequential} (E).
 *
 * <p>The priority rules are the shim's ({@code Directory.checkPriorities}, read 2026-10-09): every policy
 * needs exactly one entry in the container's {@code DirXML-SPPriority}, and the levels must run 0, 1, 2 …
 * with no gap, or the driver refuses to start ("Entitlement Policy priorities are non-sequential").
 */
public final class RbeCheck implements Check {
    @Override
    public String name() {
        return "rbe-policies";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        if (ds.rbePolicies.isEmpty()) {
            return;
        }
        if (ds.entitlementServiceDrivers().isEmpty()) {
            r.add(Finding.warning("rbe-no-service-driver", "rbe-policies", "the set has entitlement policies but no driver runs the Entitlements Service shim (" + EntitlementPolicy.SERVICE_SHIM_CLASS + "); nothing will grant them"));
        }
        java.util.Map<Integer, String> seen = new java.util.HashMap<>();
        for (EntitlementPolicy p : ds.rbePolicies) {
            String path = "rbe-policies/" + p.name;
            if (p.name == null || p.name.isBlank()) {
                r.add(Finding.error("rbe-name-blank", path, "entitlement policy has a blank name"));
            }
            if ("true".equals(p.meta.get("legacy-entitlements-xml"))) {
                r.add(Finding.error("rbe-legacy-entitlements-xml", path, "policy still carries DirXML-SPEntitlementsXML (pre-3.5 shape); the Entitlements Service driver refuses it as an unconverted policy"));
            }
            boolean query = p.memberQuery != null && !p.memberQuery.isBlank();
            if (!query && p.members.isEmpty()) {
                r.add(Finding.warning("rbe-no-membership", path, "policy has neither a membership query nor a static member: it applies to no one"));
            }
            if (p.entitlementRefs.isEmpty()) {
                r.add(Finding.warning("rbe-no-entitlement", path, "policy grants no entitlement"));
            }
            for (String dn : p.entitlementDns()) {
                if (!defined(ds, dn)) {
                    r.add(Finding.warning("rbe-unknown-entitlement", path, "policy grants '" + dn + "', which no driver of the set defines"));
                }
            }
            if (p.priority == null) {
                r.add(Finding.error("rbe-no-priority", path, "the policy container does not order this policy (no DirXML-SPPriority entry); the Entitlements Service driver refuses to start"));
            } else {
                String other = seen.put(p.priority, p.name);
                if (other != null) {
                    r.add(Finding.error("rbe-duplicate-priority", path, "priority " + p.priority + " is also held by '" + other + "'; the Entitlements Service driver refuses to start"));
                }
            }
        }
        java.util.List<Integer> levels = new java.util.ArrayList<>(seen.keySet());
        java.util.Collections.sort(levels);
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i) != i) {
                r.add(Finding.error("rbe-priorities-not-sequential", "rbe-policies", "policy priorities are " + levels + "; they must run 0, 1, 2 … with no gap, or the Entitlements Service driver refuses to start"));
                break;
            }
        }
    }

    /** Whether {@code cn=<entitlement>,cn=<driver>,…} names an entitlement a driver of the set defines (by the two leaf names). */
    private static boolean defined(DriverSet ds, String dn) {
        String[] parts = dn.split(",", 3);
        if (parts.length < 2) {
            return false;
        }
        String ent = leaf(parts[0]);
        String drv = leaf(parts[1]);
        for (Driver d : ds.drivers) {
            if (!d.name.toLowerCase(Locale.ROOT).equals(drv)) {
                continue;
            }
            for (Entitlement e : d.entitlements) {
                if (e.name.toLowerCase(Locale.ROOT).equals(ent)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String leaf(String rdn) {
        return rdn.replaceFirst("^[^=]+=", "").trim().toLowerCase(Locale.ROOT);
    }
}
