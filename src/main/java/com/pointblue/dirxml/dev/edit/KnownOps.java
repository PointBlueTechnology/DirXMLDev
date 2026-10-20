package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.KnownDifference;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code known.accept} / {@code known.forget}: a difference between two environments accepted as it is, or no longer
 * ({@code overrides/<env>.known.json}; docs/existing-environments.md, D1).
 */
public final class KnownOps {

    private KnownOps() {
    }

    public static final class Accept implements Operation {
        private final String env;
        private final String with;
        private final String path;
        private final String fingerprint;
        private final String decision;
        private final String summary;
        private final String note;
        private final String by;

        public Accept(String env, String with, String path, String fingerprint, String decision, String summary, String note, String by) {
            this.env = env;
            this.with = with;
            this.path = path;
            this.fingerprint = fingerprint;
            this.decision = decision == null ? "intended" : decision;
            this.summary = summary;
            this.note = note;
            this.by = by;
        }

        @Override
        public String name() {
            return "known.accept";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            if (env == null || with == null || path == null || fingerprint == null) {
                throw new Refusal("--env, --with, --path and --fingerprint are required (a comparison gives the fingerprint)");
            }
            if (!decision.equals("intended") && !decision.equals("later")) {
                throw new Refusal("--decision is intended (different on purpose) or later (to decide)");
            }
            List<KnownDifference> list = ds.known.computeIfAbsent(env, k -> new ArrayList<>());
            list.removeIf(k -> k.path.equals(path) && with.equalsIgnoreCase(k.with));
            list.add(new KnownDifference(with, path, fingerprint, decision, summary == null ? path : summary, note, by == null ? "" : by,
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()));
        }
    }

    public static final class Forget implements Operation {
        private final String env;
        private final String with;
        private final String path;

        public Forget(String env, String with, String path) {
            this.env = env;
            this.with = with;
            this.path = path;
        }

        @Override
        public String name() {
            return "known.forget";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            List<KnownDifference> list = ds.known.get(env);
            if (list == null || !list.removeIf(k -> k.path.equals(path) && (with == null || with.equalsIgnoreCase(k.with)))) {
                throw new Refusal("no known difference '" + path + "' recorded for " + env);
            }
        }
    }
}
