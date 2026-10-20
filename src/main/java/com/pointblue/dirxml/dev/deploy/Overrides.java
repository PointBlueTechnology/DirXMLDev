package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.edit.GcvOps;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.xml.Dom;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Values that differ per stage — the AD driver's domain name, a URL, a container DN — kept
 * beside the tree in {@code overrides/<env>.properties}, one file per environment, one line per
 * value:
 * <pre>
 *   drivers/AD Driver.gcv.drv.domain.dns.name = corp.example.com
 *   drivers/AD Driver.shim.pub-heartbeat-interval = 5
 *   drivers/AD Driver.shim-auth-server = REMOTE(hostname=rl.corp.example port=8090 kmo=idm)dc.corp.example
 *   drivers/AD Driver.shim-auth-id = CORP\\svc-idm
 *   drivers/AD Driver.ecv.dirxml.engine.retry-interval = 60
 *   driverset.gcv.company.name = ACME
 * </pre>
 * The tree's own files carry the base value (what a fresh vault or a lab gets); a deploy to
 * {@code --env X} writes the base with X's overrides applied, a diff compares against that, and
 * an {@code import-live --env X} folds the other way: a value X's file covers updates that file
 * when the vault differs and leaves the base alone, so stages stay independent through round
 * trips ({@code docs/vault-deploy.md}, "Values that differ per stage"). Secrets are not
 * overrides: they stay in the environment's secrets file.
 *
 * <p>Keys: {@code drivers/<driver>.gcv.<name>} — a GCV the driver's scope defines (its own
 * config-values, a GCV resource it links, the driver set's); {@code drivers/<driver>.shim.<name>}
 * — a parameter of the driver's shim configuration; {@code driverset.gcv.<name>} — a driver-set
 * GCV. The file is read with the key up to the first {@code =}, so a driver name may contain
 * spaces; {@code #} starts a comment.
 */
public final class Overrides {

    public static final String DIR = "overrides";
    /** {@code drivers/<driver>.name = <name>}: the name this environment's vault gives the driver, when it differs from the tree's. */
    public static final String NAME = "name";
    public static final String EXT = ".properties";

    private Overrides() {
    }

    // ---- the files ----

    /** Every {@code overrides/<env>.properties} under {@code tree}, by environment name, in file order. */
    public static Map<String, Map<String, String>> read(Path tree) throws IOException {
        Map<String, Map<String, String>> out = new TreeMap<>();
        Path dir = tree.resolve(DIR);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(EXT)).sorted()::iterator) {
                String env = p.getFileName().toString();
                env = env.substring(0, env.length() - EXT.length());
                out.put(env, parse(Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    /** Write one file per environment; an environment with no lines gets no file. */
    public static void write(Path tree, Map<String, Map<String, String>> overrides) throws IOException {
        if (overrides.isEmpty()) {
            return;
        }
        Path dir = tree.resolve(DIR);
        Files.createDirectories(dir);
        for (Map.Entry<String, Map<String, String>> e : overrides.entrySet()) {
            if (!e.getValue().isEmpty()) {
                Files.writeString(dir.resolve(e.getKey() + EXT), format(e.getKey(), e.getValue()), StandardCharsets.UTF_8);
            }
        }
    }

    /** {@code key = value} lines; the key runs to the first {@code =}; blank lines and {@code #} comments are skipped. Malformed lines are kept under the key {@code "?<line-number>"} so a check can report them. */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (String raw : text.split("\n")) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                out.put("?" + n, line);
                continue;
            }
            out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    public static String format(String env, Map<String, String> values) {
        StringBuilder sb = new StringBuilder("# Values for environment '" + env + "' that differ from the tree's base values.\n"
            + "# key = value; keys: drivers/<driver>.gcv.<name>, .shim.<name>, .ecv.<name>, .shim-auth-server, .shim-auth-id; driverset.gcv.<name>\n");
        for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
            if (!e.getKey().startsWith("?")) {
                sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    // ---- resolving a key against the model ----

    /**
     * What a key names: the {@code <definition>} element holding the value (a GCV, a shim parameter,
     * an engine control value), or a driver's own setting ({@code shim-auth-server},
     * {@code shim-auth-id}); else nothing, with a reason.
     */
    public static final class Target {
        public final Element definition;
        public final Driver driver;
        public final String setting;
        public final String problem;

        Target(Element definition, String problem) {
            this(definition, null, null, problem);
        }

        static Target ofSetting(Driver driver, String setting) {
            return new Target(null, driver, setting, null);
        }

        private Target(Element definition, Driver driver, String setting, String problem) {
            this.definition = definition;
            this.driver = driver;
            this.setting = setting;
            this.problem = problem;
        }

        public boolean resolved() {
            return problem == null;
        }

        /** The value the model holds for the key, or null when it holds none. */
        public String value() {
            if (definition != null) {
                return valueOf(definition);
            }
            if (driver == null) {
                return null;
            }
            if (NAME.equals(setting)) {
                return driver.name;
            }
            return Driver.SHIM_AUTH_SERVER.equals(setting) ? driver.shimAuthServer : driver.shimAuthId;
        }

        public void set(String value) {
            if (definition != null) {
                setValue(definition, value);
            } else if (driver != null && !NAME.equals(setting)) {   // a name is applied by renaming, never set
                if (Driver.SHIM_AUTH_SERVER.equals(setting)) {
                    driver.shimAuthServer = value;
                } else {
                    driver.shimAuthId = value;
                }
            }
        }
    }

    public static Target resolve(DriverSet ds, String key) {
        if (key.startsWith("?")) {
            return new Target(null, "not a 'key = value' line");
        }
        if (ServerMap.isKey(key)) {
            boolean prefer = key.endsWith(ServerMap.PREFER);
            String server = key.substring(ServerMap.PREFIX.length(), key.length() - (prefer ? ServerMap.PREFER.length() : 0));
            if (prefer) {
                return new Target(null, null, ServerMap.PREFIX, null);   // names this environment's server; checked against the map by the setup
            }
            return ds.servers.stream().anyMatch(s -> s.equalsIgnoreCase(server)) ? new Target(null, null, ServerMap.PREFIX, null)
                : new Target(null, "the tree names no server '" + server + "' (driverset.xml)");
        }
        if (key.startsWith("driverset.gcv.")) {
            String name = key.substring("driverset.gcv.".length());
            GcvOps.Home home = GcvOps.find(ds, null, name, ds.index());
            return home == null ? new Target(null, "the driver set defines no GCV '" + name + "'") : new Target(home.definition, null);
        }
        if (!key.startsWith("drivers/")) {
            return new Target(null, "a key starts with drivers/<driver>.gcv., drivers/<driver>.shim., drivers/<driver>.ecv., "
                + "drivers/<driver>." + Driver.SHIM_AUTH_SERVER + ", drivers/<driver>." + Driver.SHIM_AUTH_ID + " or driverset.gcv.");
        }
        String rest = key.substring("drivers/".length());
        // the name this environment's vault gives the driver: applied by renames(), after the values
        if (isName(key)) {
            String driverName = rest.substring(0, rest.length() - NAME.length() - 1);
            Driver d = ds.driver(driverName);
            return d == null ? new Target(null, "no driver '" + driverName + "' in the tree") : Target.ofSetting(d, NAME);
        }
        // the driver's own settings: drivers/<driver>.shim-auth-server, drivers/<driver>.shim-auth-id
        for (String setting : List.of(Driver.SHIM_AUTH_SERVER, Driver.SHIM_AUTH_ID)) {
            if (rest.endsWith("." + setting)) {
                String driverName = rest.substring(0, rest.length() - setting.length() - 1);
                Driver d = ds.driver(driverName);
                return d == null ? new Target(null, "no driver '" + driverName + "' in the tree") : Target.ofSetting(d, setting);
            }
        }
        // a definition under one of the driver's config blobs: drivers/<driver>.<kind>.<name>
        int cut = -1;
        String kind = null;
        for (String k : List.of("gcv", "shim", "ecv")) {
            int i = rest.indexOf("." + k + ".");
            if (i > 0 && (cut < 0 || i < cut)) {
                cut = i;
                kind = k;
            }
        }
        if (cut < 0) {
            return new Target(null, "a driver key is drivers/<driver>.gcv.<name>, .shim.<name>, .ecv.<name>, ."
                + Driver.SHIM_AUTH_SERVER + " or ." + Driver.SHIM_AUTH_ID);
        }
        String driverName = rest.substring(0, cut);
        Driver d = ds.driver(driverName);
        if (d == null) {
            return new Target(null, "no driver '" + driverName + "' in the tree");
        }
        String name = rest.substring(cut + kind.length() + 2);
        switch (kind) {
            case "gcv": {
                GcvOps.Home home = GcvOps.find(ds, d, name, ds.index());
                return home == null ? new Target(null, "driver '" + d.name + "' and what it links define no GCV '" + name + "'") : new Target(home.definition, null);
            }
            case "shim": {
                Element def = GcvOps.definition(d.config.get(Driver.SHIM_CONFIG_INFO), name);
                return def == null ? new Target(null, "driver '" + d.name + "' has no shim parameter '" + name + "'") : new Target(def, null);
            }
            default: {
                Element def = GcvOps.definition(d.config.get(Driver.ENGINE_CONTROL_VALUES), name);
                return def == null ? new Target(null, "driver '" + d.name + "' has no engine control value '" + name + "'") : new Target(def, null);
            }
        }
    }

    /** The scalar value a definition holds ({@code <value>} text), or null when it has none. */
    public static String valueOf(Element definition) {
        List<Element> values = Dom.childrenByName(definition, "value");
        return values.isEmpty() ? null : text(values.get(0));
    }

    private static String text(Element e) {
        StringBuilder sb = new StringBuilder();
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == org.w3c.dom.Node.TEXT_NODE || n.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static void setValue(Element definition, String value) {
        List<Element> values = Dom.childrenByName(definition, "value");
        if (values.isEmpty()) {
            Element v = definition.getOwnerDocument().createElementNS(null, "value");
            v.setTextContent(value);
            definition.appendChild(v);
            return;
        }
        Element v = values.get(0);
        while (v.getFirstChild() != null) {
            v.removeChild(v.getFirstChild());
        }
        v.appendChild(v.getOwnerDocument().createTextNode(value));
        for (int i = 1; i < values.size(); i++) {
            definition.removeChild(values.get(i));
        }
    }

    // ---- apply / fold back ----

    /** What {@link #apply} did: the keys written and the ones it could not place. */
    public static final class Applied {
        public final String env;
        public final List<String> applied = new ArrayList<>();
        public final List<String> problems = new ArrayList<>();
        /** Drivers the tree doesn't deploy to this environment ({@link DriverEnvironments}), left out of the model. */
        public final List<String> notDeployed = new ArrayList<>();
        /** Entitlement policies, templates and jobs the tree doesn't deploy to this environment, by tree path. */
        public final List<String> notDeployedObjects = new ArrayList<>();
        /** The tree's name to this environment's, for drivers its vault names differently. */
        public final Map<String, String> renamed = new java.util.LinkedHashMap<>();
        /** The tree's servers to this environment's ({@link ServerMap}); empty for the base environment. */
        public final Map<String, String> serverMap = new java.util.LinkedHashMap<>();

        /** Names a scoped request gave (the tree's) as this environment's model now has them. */
        public List<String> vaultNames(java.util.Collection<String> treeNames) {
            if (treeNames == null) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (String n : treeNames) {
                out.add(renamed.getOrDefault(n, n));
            }
            return out;
        }

        /** The refusal for a scoped request naming drivers this environment doesn't get, or null. */
        public String refusalFor(java.util.Collection<String> treeNames) {
            if (treeNames == null) {
                return null;
            }
            List<String> named = new ArrayList<>();
            for (String n : treeNames) {
                if (notDeployed.contains(n)) {
                    named.add("'" + n + "'");
                }
            }
            return named.isEmpty() ? null : String.join(", ", named) + (named.size() == 1 ? " is" : " are") + " not deployed to '" + env
                + "' (driver.xml environments). Add " + env + " to " + (named.size() == 1 ? "its" : "their") + " environments to deploy " + (named.size() == 1 ? "it" : "them") + " there.";
        }

        Applied(String env) {
            this.env = env;
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            if (!applied.isEmpty() || !problems.isEmpty()) {
                parts.add("overrides for '" + env + "': " + applied.size() + " value(s) applied from " + DIR + "/" + env + EXT
                    + (problems.isEmpty() ? "" : "; not applied: " + String.join("; ", problems)));
            }
            if (!notDeployed.isEmpty()) {
                parts.add("not deployed to '" + env + "' (driver.xml environments): " + String.join(", ", notDeployed));
            }
            if (!notDeployedObjects.isEmpty()) {
                parts.add(notDeployedObjects.size() + " other object(s) not deployed to '" + env + "' (environments)");
            }
            return parts.isEmpty() ? null : String.join("; ", parts);
        }
    }

    /**
     * Put environment {@code env}'s values into the model, in place, and take out the drivers {@code env}
     * doesn't get ({@link DriverEnvironments}): the model is a throwaway read of the tree that a diff or a
     * deploy compares and writes, never written back as the tree. An environment without a file applies
     * no values.
     */
    public static Applied apply(DriverSet ds, String env) {
        Applied a = applyValues(ds, env);
        Map<String, String> values = ds.overrides.get(env);
        // the drivers this environment doesn't get leave the model after the values, so a value set for one is still placed
        a.notDeployed.addAll(DriverEnvironments.filter(ds, env));
        a.notDeployedObjects.addAll(DriverEnvironments.filterObjects(ds, env));
        // and the names this environment's vault uses come last: every key above names the tree's driver
        for (Map.Entry<String, String> r : renames(values).entrySet()) {
            if (a.notDeployed.contains(r.getKey())) {
                continue;
            }
            if (ds.renameDriver(r.getKey(), r.getValue())) {
                a.renamed.put(r.getKey(), r.getValue());
                a.applied.add("drivers/" + r.getKey() + "." + NAME);
            } else if (!r.getKey().equals(r.getValue())) {
                a.problems.add("drivers/" + r.getKey() + "." + NAME + " (" + (ds.driver(r.getKey()) == null
                    ? "no driver '" + r.getKey() + "' in the tree" : "the tree already has a driver '" + r.getValue() + "'") + ")");
            }
        }
        // the tree's servers as this environment's (several onto one where it has fewer)
        a.serverMap.putAll(ServerMap.of(values));
        a.problems.addAll(ServerMap.apply(ds, values));
        return a;
    }

    /**
     * Environment {@code env}'s values alone, every driver kept under the tree's name: for the simulator,
     * whose cases name the tree's drivers.
     */
    public static Applied applyValues(DriverSet ds, String env) {
        Applied a = new Applied(env);
        Map<String, String> values = ds.overrides.get(env);
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (isName(e.getKey()) || ServerMap.isKey(e.getKey())) {
                    continue;
                }
                Target t = resolve(ds, e.getKey());
                if (!t.resolved()) {
                    a.problems.add(e.getKey() + " (" + t.problem + ")");
                    continue;
                }
                t.set(e.getValue());
                a.applied.add(e.getKey());
            }
        }
        return a;
    }

    /** True for {@code drivers/<driver>.name}. */
    public static boolean isName(String key) {
        if (!key.startsWith("drivers/") || !key.endsWith("." + NAME) || key.length() <= "drivers/".length() + NAME.length() + 1) {
            return false;
        }
        // drivers/AD.gcv.drv.domain.dns.name is a GCV called drv.domain.dns.name, not a name
        String driver = key.substring("drivers/".length(), key.length() - NAME.length() - 1);
        return !driver.contains(".gcv.") && !driver.contains(".shim.") && !driver.contains(".ecv.");
    }

    /** An environment file's {@code drivers/<driver>.name} lines: the tree's name to the name in that vault, in file order. */
    public static Map<String, String> renames(Map<String, String> values) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (isName(e.getKey()) && !e.getValue().isBlank()) {
                    out.put(e.getKey().substring("drivers/".length(), e.getKey().length() - NAME.length() - 1), e.getValue().trim());
                }
            }
        }
        return out;
    }

    /**
     * A model read from {@code env}'s vault, its drivers given the tree's names ({@code drivers/<tree>.name =
     * <vault>} read backwards), in place, so it compares with or folds into the tree. Returns notes.
     */
    public static List<String> toTreeNames(DriverSet live, Map<String, String> values) {
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, String> r : renames(values).entrySet()) {
            if (live.driver(r.getValue()) == null) {
                continue;
            }
            if (!live.renameDriver(r.getValue(), r.getKey())) {
                notes.add("'" + r.getValue() + "' was not given the tree's name '" + r.getKey() + "': the vault has a driver of that name too");
            }
        }
        return notes;
    }

    /**
     * {@code import-live --env X}: {@code live} was read from X's vault. For every key in X's
     * file, the vault's value goes into the file (when it differs) and the base's value goes back
     * into {@code live}, so the tree's files keep the base and X's file keeps X. {@code base} is
     * the tree as it is on disk (null on a first import, when there is nothing to fold to and the
     * file is simply refreshed). Returns what changed, for the import's report.
     */
    public static List<String> foldBack(DriverSet live, String env, DriverSet base) {
        List<String> notes = new ArrayList<>();
        Map<String, String> values = live.overrides.get(env);
        if (values == null) {
            return notes;
        }
        for (Map.Entry<String, String> e : new ArrayList<>(values.entrySet())) {
            if (isName(e.getKey()) || ServerMap.isKey(e.getKey())) {
                continue;   // a name or a server stays as the file has it: the import already used it
            }
            Target t = resolve(live, e.getKey());
            if (!t.resolved()) {
                notes.add(e.getKey() + ": " + t.problem + " (left as recorded)");
                continue;
            }
            String vaultValue = t.value();
            if (vaultValue != null && !vaultValue.equals(e.getValue())) {
                values.put(e.getKey(), vaultValue);
                notes.add(e.getKey() + ": " + DIR + "/" + env + EXT + " updated from the vault");
            }
            if (base != null) {
                Target b = resolve(base, e.getKey());
                if (b.resolved() && b.value() != null) {
                    t.set(b.value());
                }
            }
        }
        return notes;
    }

    /** The artifacts (GCV resources) a key resolves into, for callers that must mark them touched. */
    public static Artifact resourceOf(DriverSet ds, String key) {
        if (!key.contains(".gcv.")) {
            return null;
        }
        String name = key.substring(key.indexOf(".gcv.") + ".gcv.".length());
        Driver d = key.startsWith("drivers/") ? ds.driver(key.substring("drivers/".length(), key.indexOf(".gcv."))) : null;
        GcvOps.Home home = GcvOps.find(ds, d, name, ds.index());
        return home == null ? null : home.resource;
    }

    // ---- shim auth ids the secrets file supplies (docs/vault-deploy.md, "Secrets") ----

    /**
     * Put every {@code <driver>.shim-auth-id} the environment's secrets file holds into the model
     * (the driver's {@code shimAuthId}, flagged secret), for a diff or a deploy: it wins over the
     * tree's value and over an override. Returns the drivers touched.
     */
    public static List<String> applySecretShimAuthIds(DriverSet ds, Secrets secrets) throws IOException {
        List<String> out = new ArrayList<>();
        for (Driver d : ds.drivers) {
            String key = Secrets.shimAuthId(d.name);
            if (secrets.has(key)) {
                char[] v = secrets.get(key);
                if (v != null) {
                    d.shimAuthId = new String(v);
                    d.shimAuthIdSecret = true;
                    java.util.Arrays.fill(v, '\0');
                    out.add(d.name);
                }
            }
        }
        return out;
    }

    /** {@code import-live}: drop the shim auth id of every driver the secrets file supplies one for, so the tree never carries it. Returns the drivers touched. */
    public static List<String> stripSecretShimAuthIds(DriverSet ds, Secrets secrets) {
        List<String> out = new ArrayList<>();
        for (Driver d : ds.drivers) {
            if (secrets.has(Secrets.shimAuthId(d.name))) {
                d.shimAuthId = null;
                d.shimAuthIdSecret = false;
                out.add(d.name);
            }
        }
        return out;
    }
}
