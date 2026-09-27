package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.model.DriverSet;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code overrides/<env>.properties}: every line is {@code key = value}, every key names a GCV,
 * shim parameter, engine control value or driver setting the tree defines, and a key one environment sets is set for every other
 * environment that has a file — "must differ per stage" usually means "must be set per stage".
 */
public final class OverridesCheck implements Check {

    @Override
    public String name() {
        return "overrides";
    }

    @Override
    public void run(DriverSet ds, Report report) {
        Set<String> allKeys = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> env : ds.overrides.entrySet()) {
            String path = Overrides.DIR + "/" + env.getKey() + Overrides.EXT;
            for (Map.Entry<String, String> e : env.getValue().entrySet()) {
                if (e.getKey().startsWith("?")) {
                    report.add(Finding.error("override-malformed", path, "line " + e.getKey().substring(1) + " is not 'key = value': " + e.getValue()));
                    continue;
                }
                allKeys.add(e.getKey());
                Overrides.Target t = Overrides.resolve(ds, e.getKey());
                if (!t.resolved()) {
                    report.add(Finding.error("override-unknown", path, e.getKey() + ": " + t.problem));
                }
            }
        }
        if (ds.overrides.size() > 1) {
            for (Map.Entry<String, Map<String, String>> env : ds.overrides.entrySet()) {
                for (String key : allKeys) {
                    if (!env.getValue().containsKey(key)) {
                        report.add(Finding.warning("override-missing-env", Overrides.DIR + "/" + env.getKey() + Overrides.EXT,
                            key + " is set for another environment but not for '" + env.getKey() + "', which gets the tree's base value"));
                    }
                }
            }
        }
    }
}
