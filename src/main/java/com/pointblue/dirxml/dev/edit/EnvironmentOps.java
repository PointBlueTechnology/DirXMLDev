package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.EnvironmentList;
import com.pointblue.dirxml.dev.model.Job;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code environments.set}: which environments get an object and which it is planned for
 * ({@code docs/existing-environments.md}). An object is named by its tree path: {@code drivers/<d>},
 * {@code drivers/<d>/jobs/<n>}, {@code jobs/<n>}, {@code rbe-policies/<n>}, {@code templates/<n>}; several at once,
 * one per line, so that a run of test objects one environment holds is marked in one change.
 */
public final class EnvironmentOps {

    private EnvironmentOps() {
    }

    public static final class Set implements Operation {
        private final List<String> paths = new ArrayList<>();
        private final String environments;
        private final String planned;

        /** {@code environments}/{@code planned}: comma-separated; null leaves that list alone, "" empties it. */
        public Set(String paths, String environments, String planned) {
            if (paths != null) {
                for (String p : paths.split("\\r?\\n")) {
                    if (!p.isBlank()) {
                        this.paths.add(p.trim());
                    }
                }
            }
            this.environments = environments;
            this.planned = planned;
        }

        @Override
        public String name() {
            return "environments.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            if (paths.isEmpty()) {
                throw new Refusal("name at least one object (--paths, one tree path per line)");
            }
            if (environments == null && planned == null) {
                throw new Refusal("give --environments, --planned, or both");
            }
            List<String> unknown = new ArrayList<>();
            for (String p : paths) {
                Lists l = lists(ds, p);
                if (l == null) {
                    unknown.add(p);
                    continue;
                }
                if (environments != null) {
                    l.environments.clear();
                    l.environments.addAll(EnvironmentList.parse(environments));
                }
                if (planned != null) {
                    l.planned.clear();
                    l.planned.addAll(EnvironmentList.parse(planned));
                }
            }
            if (!unknown.isEmpty()) {
                throw new Refusal("not in the tree: " + String.join(", ", unknown)
                    + " (drivers/<d>, drivers/<d>/jobs/<n>, jobs/<n>, rbe-policies/<n>, templates/<n>)");
            }
        }
    }

    private record Lists(List<String> environments, List<String> planned) {
    }

    private static Lists lists(DriverSet ds, String path) {
        String[] p = path.split("/", -1);
        if (p.length == 2 && p[0].equals("drivers")) {
            Driver d = ds.driver(p[1]);
            return d == null ? null : new Lists(d.environments, d.planned);
        }
        if (p.length == 4 && p[0].equals("drivers") && p[2].equals("jobs")) {
            Driver d = ds.driver(p[1]);
            Job j = d == null ? null : d.jobs.stream().filter(x -> x.name.equals(p[3])).findFirst().orElse(null);
            return j == null ? null : new Lists(j.environments, j.planned);
        }
        if (p.length == 2 && p[0].equals("jobs")) {
            return ds.jobs.stream().filter(x -> x.name.equals(p[1])).findFirst().map(j -> new Lists(j.environments, j.planned)).orElse(null);
        }
        if (p.length == 2 && p[0].equals("rbe-policies")) {
            return ds.rbePolicies.stream().filter(x -> x.name.equals(p[1])).findFirst().map(x -> new Lists(x.environments, x.planned)).orElse(null);
        }
        if (p.length == 2 && p[0].equals("templates")) {
            return ds.templates.stream().filter(x -> x.name.equals(p[1])).findFirst().map(x -> new Lists(x.environments, x.planned)).orElse(null);
        }
        return null;
    }
}
