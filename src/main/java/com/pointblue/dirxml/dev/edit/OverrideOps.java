package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.deploy.Overrides;
import com.pointblue.dirxml.dev.model.DriverSet;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Values that differ per stage ({@code overrides/<env>.properties}, docs/getting-started.md
 * §3.1), as edit operations: set a key for an environment, or remove it. The key must name
 * something the tree defines ({@link Overrides#resolve}); the tree's base value is untouched.
 */
public final class OverrideOps {

    private OverrideOps() {
    }

    private static String envOrRefuse(String env) throws Operation.Refusal {
        if (env == null || !env.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new Operation.Refusal("--env names the environment (letters, digits, '.', '_', '-'): it is the file overrides/<env>.properties");
        }
        return env;
    }

    /** {@code override.set --env E --key K --value V}. */
    public static final class Set implements Operation {
        private final String env;
        private final String key;
        private final String value;

        public Set(String env, String key, String value) {
            this.env = env;
            this.key = key;
            this.value = value;
        }

        @Override
        public String name() {
            return "override.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            String e = envOrRefuse(env);
            if (key == null || key.isBlank()) {
                throw new Refusal("--key is required (drivers/<driver>.gcv.<name>, .shim.<name>, .ecv.<name>, .shim-auth-server, .shim-auth-id, or driverset.gcv.<name>)");
            }
            if (value == null) {
                throw new Refusal("--value is required");
            }
            Overrides.Target t = Overrides.resolve(ds, key.trim());
            if (!t.resolved()) {
                throw new Refusal(key.trim() + ": " + t.problem);
            }
            Map<String, String> values = ds.overrides.computeIfAbsent(e, k -> new LinkedHashMap<>());
            String old = values.put(key.trim(), value);
            String base = t.value();
            tx.note("override for '" + e + "': " + key.trim() + " = " + value
                + (old == null ? " (new" : " (was " + old) + "; the tree's base value is " + (base == null ? "unset" : base) + ")");
        }
    }

    /** {@code override.remove --env E --key K}: the environment goes back to the base value. */
    public static final class Remove implements Operation {
        private final String env;
        private final String key;

        public Remove(String env, String key) {
            this.env = env;
            this.key = key;
        }

        @Override
        public String name() {
            return "override.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            String e = envOrRefuse(env);
            Map<String, String> values = ds.overrides.get(e);
            if (values == null || key == null || !values.containsKey(key.trim())) {
                throw new Refusal("environment '" + e + "' has no override for " + key);
            }
            values.remove(key.trim());
            if (values.isEmpty()) {
                ds.overrides.remove(e);   // the file goes with its last key
            }
            tx.note("override removed: '" + e + "' takes the tree's base value for " + key.trim());
        }
    }
}
