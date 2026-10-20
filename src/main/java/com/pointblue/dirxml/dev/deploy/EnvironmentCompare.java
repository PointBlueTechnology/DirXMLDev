package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Two environments' vaults side by side ({@code docs/vault-deploy.md}, "Comparing two environments"):
 * which drivers only one holds, which values differ, and what else differs once the values are set
 * aside. With the tree, both vaults' drivers take the tree's names ({@code drivers/<d>.name}), and a
 * difference the tree already accounts for is marked expected: a driver the tree doesn't deploy to the
 * other environment, a value one of the two environments' {@code overrides/<env>.properties} sets.
 *
 * <ul>
 *   <li><b>values</b>: every scalar value an override can carry ({@code drivers/<d>.gcv.<n>} for the
 *       driver's own GCVs, {@code .shim.<n>}, {@code .ecv.<n>}, {@code .shim-auth-server},
 *       {@code .shim-auth-id}, {@code driverset.gcv.<n>}) that both hold and that differs, keyed as the
 *       overrides file keys it;</li>
 *   <li><b>drift</b>: the diff of the first against the second after the first takes the second's value
 *       for each of those, so what remains is what a value can't explain: policies, filters, linkage,
 *       definitions one side lacks, GCV resources several drivers share.</li>
 * </ul>
 */
public final class EnvironmentCompare {

    /**
     * Something one environment's vault holds and the other's doesn't: a driver ({@code driver} set, {@code path}
     * {@code drivers/<d>}), or a role-based entitlement policy, template or job ({@code path} {@code rbe-policies/<n>},
     * {@code templates/<n>}, {@code jobs/<n>}, {@code drivers/<d>/jobs/<n>}).
     */
    public static final class OnlyIn {
        public final String driver;
        public final String path;
        public final String kind;
        public final String env;
        /** The tree doesn't deploy it to the other environment, for good or for now. */
        public final boolean expected;
        /** The tree means it to reach the other environment later ({@code planned}): waiting for promotion. */
        public final boolean planned;
        /** The tree has nothing of this name. */
        public final boolean notInTree;

        OnlyIn(String driver, String path, String kind, String env, boolean expected, boolean planned, boolean notInTree) {
            this.driver = driver;
            this.path = path;
            this.kind = kind;
            this.env = env;
            this.expected = expected;
            this.planned = planned;
            this.notInTree = notInTree;
        }
    }

    /** One value both hold, differently. */
    public static final class Value {
        /** The tree's name of the driver; null for the driver set's own GCVs. */
        public final String driver;
        public final String key;
        public final String a;
        public final String b;
        /** One of the two environments' overrides files sets this key. */
        public final boolean expected;

        Value(String driver, String key, String a, String b, boolean expected) {
            this.driver = driver;
            this.key = key;
            this.a = a;
            this.b = b;
            this.expected = expected;
        }
    }

    public final String envA;
    public final String envB;
    public final List<OnlyIn> onlyIn = new ArrayList<>();
    public final List<Value> values = new ArrayList<>();
    public ModelDiff drift;
    /** For each change of {@link #drift}, in order, its likely cause ({@link #cause}): the analysis a person starts from. */
    public final List<String> causes = new ArrayList<>();
    /** Changes whose every changed line differs only in letter case (a DN's {@code OU=services} against {@code OU=SERVICES}): eDirectory reads them as the same. Kept apart from the drift. */
    public final List<ModelDiff.Change> caseOnly = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    private EnvironmentCompare(String envA, String envB) {
        this.envA = envA;
        this.envB = envB;
    }

    /**
     * Compares {@code a} (read from {@code envA}'s vault) with {@code b} (from {@code envB}'s); both are
     * changed in place (names, values). {@code tree} may be null: then names are compared as they are and
     * nothing is marked expected.
     */
    public static EnvironmentCompare of(DriverSet a, String envA, DriverSet b, String envB, DriverSet tree) {
        EnvironmentCompare c = new EnvironmentCompare(envA, envB);
        Map<String, String> filesA = tree == null ? Map.of() : Objects.requireNonNullElse(tree.overrides.get(envA), Map.of());
        Map<String, String> filesB = tree == null ? Map.of() : Objects.requireNonNullElse(tree.overrides.get(envB), Map.of());
        c.notes.addAll(Overrides.toTreeNames(a, filesA));
        c.notes.addAll(Overrides.toTreeNames(b, filesB));
        // each side's servers as the tree names them, so per-server settings and jobs line up
        c.notes.addAll(ServerMap.toTree(a, filesA, tree));
        c.notes.addAll(ServerMap.toTree(b, filesB, tree));

        for (Driver d : a.drivers) {
            if (b.driver(d.name) == null) {
                c.onlyIn.add(onlyIn(d.name, envA, envB, tree));
            }
        }
        for (Driver d : b.drivers) {
            if (a.driver(d.name) == null) {
                c.onlyIn.add(onlyIn(d.name, envB, envA, tree));
            }
        }

        Map<String, String> va = values(a);
        Map<String, String> vb = values(b);
        for (Map.Entry<String, String> e : va.entrySet()) {
            if (!vb.containsKey(e.getKey()) || Objects.equals(e.getValue(), vb.get(e.getKey()))) {
                continue;
            }
            String key = e.getKey();
            c.values.add(new Value(driverOf(key), key, e.getValue(), vb.get(key), filesA.containsKey(key) || filesB.containsKey(key)));
            Overrides.Target t = Overrides.resolve(a, key);
            if (t.resolved() && vb.get(key) != null) {
                t.set(vb.get(key));
            }
        }

        // the drivers only one side holds are listed above; what's left of the rest is drift, but for differences of letter case
        ModelDiff rest = ModelDiff.of(a, b).only(ch -> ch.kind != ModelDiff.Kind.DRIVER_ADDED && ch.kind != ModelDiff.Kind.DRIVER_REMOVED
            && (ch.driver == null || (a.driver(ch.driver) != null && b.driver(ch.driver) != null)));
        List<ModelDiff.Change> oneSided = new ArrayList<>();
        for (ModelDiff.Change ch : rest.changes()) {
            boolean setObject = ch.kind.isRbe() || ch.kind.isTemplate() || ch.kind.isJob();
            boolean added = ch.kind.name().endsWith("_ADDED");
            boolean removed = ch.kind.name().endsWith("_REMOVED");
            if (setObject && (added || removed)) {
                // from = a, to = b: "removed" is a's only, "added" b's only
                c.onlyIn.add(objectOnlyIn(ch, removed ? envA : envB, removed ? envB : envA, tree));
                oneSided.add(ch);
            } else if (onlyLetterCase(ch.detail)) {
                c.caseOnly.add(ch);
            }
        }
        c.drift = rest.only(ch -> !c.caseOnly.contains(ch) && !oneSided.contains(ch));
        // words that mark environment-specific text: the environments' names and their servers' names
        java.util.Set<String> envWords = new java.util.HashSet<>();
        envWords.add(envA.toLowerCase(java.util.Locale.ROOT));
        envWords.add(envB.toLowerCase(java.util.Locale.ROOT));
        for (DriverSet side : List.of(a, b)) {
            for (String server : side.servers) {
                String rdn = server.split(",")[0];
                envWords.add(rdn.substring(rdn.indexOf('=') + 1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (ModelDiff.Change ch : c.drift.changes()) {
            c.causes.add(cause(ch, envA, envB, envWords));
        }
        return c;
    }

    /**
     * A change's likely cause, from the change alone: one side only ("only in A"/"only in B"), lines only one side has,
     * package versions, cosmetic (descriptions, comments, display names), environment-specific text (a name of either
     * environment or of one of their servers on a changed line), or "both changed" (a person reviews it).
     */
    static String cause(ModelDiff.Change ch, String envA, String envB, java.util.Set<String> envWords) {
        String k = ch.kind.name();
        if (k.endsWith("_REMOVED")) {
            return "only in " + envA;
        }
        if (k.endsWith("_ADDED")) {
            return "only in " + envB;
        }
        if (ch.kind == ModelDiff.Kind.DRIVER_STAMPS) {
            return "package versions differ";
        }
        if (ch.detail == null) {
            return "both changed";
        }
        List<String> fromA = new ArrayList<>();
        List<String> fromB = new ArrayList<>();
        for (String line : ch.detail.split("\n")) {
            String t = line.stripLeading();
            if (t.startsWith("- ")) {
                fromA.add(t.substring(2));
            } else if (t.startsWith("+ ")) {
                fromB.add(t.substring(2));
            }
        }
        if (fromA.isEmpty() && fromB.isEmpty()) {
            return "both changed";
        }
        if (fromA.isEmpty()) {
            return "lines only " + envB + " has";
        }
        if (fromB.isEmpty()) {
            return "lines only " + envA + " has";
        }
        List<String> all = new ArrayList<>(fromA);
        all.addAll(fromB);
        if (all.stream().allMatch(l -> l.contains("<description") || l.contains("</description>") || l.trim().startsWith("<!--")
            || l.trim().isEmpty() || l.contains("display-name="))) {
            return "cosmetic";
        }
        for (String l : all) {
            String low = l.toLowerCase(java.util.Locale.ROOT);
            for (String w : envWords) {
                if (w.length() >= 2 && java.util.regex.Pattern.compile("(^|[^a-z0-9])" + java.util.regex.Pattern.quote(w) + "([^a-z0-9]|$)").matcher(low).find()) {
                    return "environment-specific text";
                }
            }
        }
        return "both changed";
    }

    /** True when a change's detail has removed and added lines and they are the same lines but for letter case. */
    static boolean onlyLetterCase(String detail) {
        if (detail == null) {
            return false;
        }
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String line : detail.split("\n")) {
            String t = line.stripLeading();
            if (t.startsWith("- ")) {
                removed.add(t.substring(2).trim().toLowerCase(java.util.Locale.ROOT));
            } else if (t.startsWith("+ ")) {
                added.add(t.substring(2).trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return !removed.isEmpty() && removed.equals(added);
    }

    private static OnlyIn onlyIn(String name, String in, String other, DriverSet tree) {
        Driver t = tree == null ? null : tree.driver(name);
        return new OnlyIn(name, "drivers/" + name, "driver", in, t != null && !t.deploysTo(other),
            t != null && com.pointblue.dirxml.dev.model.EnvironmentList.includes(t.planned, other) && !t.planned.isEmpty(), tree != null && t == null);
    }

    /** A one-sided policy, template or job: its tree object's lists, by path. */
    private static OnlyIn objectOnlyIn(ModelDiff.Change ch, String in, String other, DriverSet tree) {
        List<String> envs = null;
        List<String> planned = null;
        if (tree != null) {
            Object o = treeObject(tree, ch.path);
            if (o instanceof com.pointblue.dirxml.dev.model.EntitlementPolicy p) {
                envs = p.environments;
                planned = p.planned;
            } else if (o instanceof com.pointblue.dirxml.dev.model.NotificationTemplate t) {
                envs = t.environments;
                planned = t.planned;
            } else if (o instanceof com.pointblue.dirxml.dev.model.Job j) {
                envs = j.environments;
                planned = j.planned;
            }
        }
        String kind = ch.kind.isRbe() ? "entitlement policy" : ch.kind.isTemplate() ? "template" : "job";
        boolean expected = envs != null && !com.pointblue.dirxml.dev.model.EnvironmentList.includes(envs, other);
        boolean isPlanned = planned != null && !planned.isEmpty() && com.pointblue.dirxml.dev.model.EnvironmentList.includes(planned, other);
        return new OnlyIn(ch.driver, ch.path, kind, in, expected, isPlanned, tree != null && envs == null);
    }

    private static Object treeObject(DriverSet tree, String path) {
        String[] p = path.split("/");
        if (p.length == 2 && p[0].equals("rbe-policies")) {
            return tree.rbePolicies.stream().filter(x -> x.name.equals(p[1])).findFirst().orElse(null);
        }
        if (p.length == 2 && p[0].equals("templates")) {
            return tree.templates.stream().filter(x -> x.name.equals(p[1])).findFirst().orElse(null);
        }
        if (p.length == 2 && p[0].equals("jobs")) {
            return tree.jobs.stream().filter(x -> x.name.equals(p[1])).findFirst().orElse(null);
        }
        if (p.length == 4 && p[0].equals("drivers") && p[2].equals("jobs")) {
            Driver d = tree.driver(p[1]);
            return d == null ? null : d.jobs.stream().filter(x -> x.name.equals(p[3])).findFirst().orElse(null);
        }
        return null;
    }

    private static String driverOf(String key) {
        if (!key.startsWith("drivers/")) {
            return null;
        }
        String rest = key.substring("drivers/".length());
        for (String marker : List.of(".gcv.", ".shim.", ".ecv.", "." + Driver.SHIM_AUTH_SERVER, "." + Driver.SHIM_AUTH_ID)) {
            int i = rest.indexOf(marker);
            if (i > 0) {
                return rest.substring(0, i);
            }
        }
        return rest;
    }

    /** Every scalar value an override can carry, keyed as an overrides file keys it, for the drivers of {@code ds} and the set. */
    static Map<String, String> values(DriverSet ds) {
        Map<String, String> out = new LinkedHashMap<>();
        definitions(ds.configValues, "driverset.gcv.", out);
        for (Driver d : ds.drivers) {
            String p = "drivers/" + d.name + ".";
            definitions(d.config.get(Driver.CONFIG_VALUES), p + "gcv.", out);
            definitions(d.config.get(Driver.SHIM_CONFIG_INFO), p + "shim.", out);
            definitions(d.config.get(Driver.ENGINE_CONTROL_VALUES), p + "ecv.", out);
            out.put(p + Driver.SHIM_AUTH_SERVER, d.shimAuthServer);
            out.put(p + Driver.SHIM_AUTH_ID, d.shimAuthId);
        }
        return out;
    }

    private static void definitions(Element blob, String prefix, Map<String, String> out) {
        if (blob == null) {
            return;
        }
        for (Element def : Xds.descendantsByName(blob, "definition")) {
            String name = def.getAttribute("name");
            if (!name.isEmpty()) {
                out.putIfAbsent(prefix + name, Overrides.valueOf(def));
            }
        }
    }

    public boolean isEmpty() {
        return onlyIn.isEmpty() && values.isEmpty() && (drift == null || drift.isEmpty()) && caseOnly.isEmpty();
    }

    /** For people: drivers in one only, values, then the rest. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        sb.append(envA).append(" compared with ").append(envB).append('\n');
        for (String n : notes) {
            sb.append("note: ").append(n).append('\n');
        }
        if (isEmpty()) {
            return sb.append("no differences\n").toString();
        }
        for (OnlyIn o : onlyIn) {
            String other = o.env.equals(envA) ? envB : envA;
            sb.append("  only in ").append(o.env).append(": ").append(o.kind).append(' ').append(o.path)
              .append(o.planned ? "  (planned for " + other + ", not promoted yet)" : o.expected ? "  (expected: the tree doesn't deploy it to " + other + ")" : "")
              .append(o.notInTree ? "  (not in the tree)" : "").append('\n');
        }
        for (Value v : values) {
            sb.append("  ").append(v.key).append(": ").append(envA).append('=').append(v.a == null ? "(none)" : v.a)
              .append("  ").append(envB).append('=').append(v.b == null ? "(none)" : v.b)
              .append(v.expected ? "  (expected: an override)" : "").append('\n');
        }
        if (drift != null && !drift.isEmpty()) {
            sb.append("other differences, which no value explains:\n").append(drift.text());
        }
        if (!caseOnly.isEmpty()) {
            sb.append(caseOnly.size()).append(" more differ only in letter case (eDirectory reads those DNs as the same)\n");
        }
        return sb.toString();
    }

    public String json() {
        StringBuilder sb = new StringBuilder("{\"a\":").append(q(envA)).append(",\"b\":").append(q(envB)).append(",\"onlyIn\":[");
        for (int i = 0; i < onlyIn.size(); i++) {
            OnlyIn o = onlyIn.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"driver\":").append(q(o.driver)).append(",\"path\":").append(q(o.path)).append(",\"kind\":").append(q(o.kind))
              .append(",\"env\":").append(q(o.env)).append(",\"expected\":").append(o.expected).append(",\"planned\":").append(o.planned)
              .append(",\"notInTree\":").append(o.notInTree).append('}');
        }
        sb.append("],\"values\":[");
        for (int i = 0; i < values.size(); i++) {
            Value v = values.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"driver\":").append(q(v.driver)).append(",\"key\":").append(q(v.key))
              .append(",\"a\":").append(q(v.a)).append(",\"b\":").append(q(v.b)).append(",\"expected\":").append(v.expected).append('}');
        }
        sb.append("],\"drift\":").append(drift == null ? "null" : drift.json()).append(",\"causes\":[");
        for (int i = 0; i < causes.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(causes.get(i)));
        }
        sb.append("],\"caseOnly\":[");
        for (int i = 0; i < caseOnly.size(); i++) {
            ModelDiff.Change ch = caseOnly.get(i);
            sb.append(i == 0 ? "" : ",").append("{\"driver\":").append(q(ch.driver)).append(",\"path\":").append(q(ch.path)).append(",\"summary\":").append(q(ch.summary)).append('}');
        }
        sb.append("],\"notes\":[");
        for (int i = 0; i < notes.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(notes.get(i)));
        }
        return sb.append("]}").toString();
    }

    private static String q(String s) {
        return ModelDiff.q(s);
    }
}
