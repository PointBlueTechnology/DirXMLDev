package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * {@code vault.deploy} and {@code vault.rollback} — the flow in
 * docs/vault-deploy.md: validate → diff → plan → gate → snapshot → write
 * (all at once, or change by change with a confirmation and a verification
 * each) → restart → verify → audit. Nothing is written before the plan is
 * printed; nothing is written to a production tier from an unknown state.
 */
public final class Deployer {

    /** {@code --delete-all} kinds: the three typed ones plus every AppConfig object kind in the plural (roles, entities, nav-items …). */
    static final List<String> VALID_DELETE_ALL_KINDS = validDeleteAllKinds();

    private static List<String> validDeleteAllKinds() {
        List<String> out = new ArrayList<>(List.of("entitlements", "jobs", "rbe-policies", "templates", "forms", "prds"));
        for (com.pointblue.dirxml.dev.model.AppObject.Kind k : com.pointblue.dirxml.dev.model.AppObject.Kind.values()) {
            if (k != com.pointblue.dirxml.dev.model.AppObject.Kind.PRD && k != com.pointblue.dirxml.dev.model.AppObject.Kind.FORM) {
                out.add(k.plural());
            }
        }
        return out;
    }

    public static final class Options {
        public Path tree;
        public Environments.Environment env;
        public List<String> drivers = List.of();
        public boolean dryRun;
        public boolean yes;
        public boolean step;
        public String confirm;
        public boolean restart = true;
        public String secretsMode = "none";
        public boolean allowMissingSecrets;
        public boolean captureDrift;
        /**
         * {@code --base-branch}: a production deploy comes from this branch only (the web page sets the project's base,
         * usually main), so what reaches production is what was merged and reviewed. Null: any branch.
         */
        public String baseBranch;
        /**
         * Capture only (the web page's "Capture the vault's state"): read the vault, and when it differs from the last
         * deploy on record — or nothing is on record — record its state on an {@code as-found/<env>/<time>} branch, as
         * {@link #captureDrift} does; when it matches, say so. Writes nothing to the vault, needs no confirmation.
         */
        public boolean captureOnly;
        public boolean json;
        public List<String> deleteDrivers = List.of();
        /** {@code --delete-all} kinds ({@code entitlements}, {@code forms}, {@code prds}), repeatable. */
        public List<String> deleteAllKinds = List.of();
        public int restartWaitSeconds = 180;
        /**
         * Hears each step as it happens, one line each ("snapshot: …", "write 3/9: modify cn=…", "restarting AD: waiting
         * for Running, up to 180 s"), for a caller that shows a deploy's progress (the web page). Never null; must not throw.
         */
        public java.util.function.Consumer<String> progress = line -> { };
    }

    public static final class Result {
        public boolean ok;
        public String refusal;
        /**
         * Which production check stopped this run (with {@link #refusal}), or what the plan's preflight found (with
         * {@link #gate}): confirm, not-in-git, uncommitted, branch, requires, unknown-state, drift; after a capture,
         * captured or verified. Null when nothing stood in the way.
         */
        public String gateKind;
        /** A dry run against production: what the production checks would refuse, so the plan can say it before a deploy is tried. */
        public String gate;
        /** The as-found branch a capture made, or null. */
        public String capturedBranch;
        public String planText = "";
        public String diffText = "";
        public String snapshot;
        public final List<String> done = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public String failure;
        public final List<String> restarted = new ArrayList<>();
        public final List<String> secretsSet = new ArrayList<>();
        public String verifyText;
        public boolean verified;

        public String text() {
            StringBuilder sb = new StringBuilder();
            if (!diffText.isBlank()) {
                sb.append(diffText);
            }
            if (!planText.isBlank()) {
                sb.append(planText);
            }
            if (refusal != null) {
                sb.append("REFUSED — ").append(refusal).append('\n');
                return sb.toString();
            }
            if (snapshot != null) {
                sb.append("snapshot: ").append(snapshot).append('\n');
            }
            for (String d : done) {
                sb.append("  done     ").append(d).append('\n');
            }
            for (String s : skipped) {
                sb.append("  skipped  ").append(s).append('\n');
            }
            if (failure != null) {
                sb.append("  FAILED   ").append(failure).append('\n');
            }
            for (String r : restarted) {
                sb.append("  restarted ").append(r).append('\n');
            }
            for (String s : secretsSet) {
                sb.append("  secret   ").append(s).append('\n');
            }
            if (verifyText != null) {
                sb.append("verify: ").append(verified ? "vault matches the tree" : "MISMATCH").append('\n').append(verifyText);
            }
            sb.append(ok ? "OK" : "FAILED").append('\n');
            return sb.toString();
        }

        public String json() {
            StringBuilder sb = new StringBuilder("{\"ok\":").append(ok);
            sb.append(",\"refusal\":").append(refusal == null ? "null" : DeployLog.q(refusal));
            sb.append(",\"snapshot\":").append(snapshot == null ? "null" : DeployLog.q(snapshot));
            sb.append(",\"done\":").append(arr(done)).append(",\"skipped\":").append(arr(skipped));
            sb.append(",\"failure\":").append(failure == null ? "null" : DeployLog.q(failure));
            sb.append(",\"restarted\":").append(arr(restarted)).append(",\"secretsSet\":").append(arr(secretsSet));
            sb.append(",\"verified\":").append(verified);
            sb.append(",\"verify\":").append(verifyText == null ? "null" : DeployLog.q(verifyText));
            sb.append(",\"plan\":").append(DeployLog.q(planText)).append(",\"diff\":").append(DeployLog.q(diffText));
            sb.append(",\"gateKind\":").append(gateKind == null ? "null" : DeployLog.q(gateKind));
            sb.append(",\"gate\":").append(gate == null ? "null" : DeployLog.q(gate));
            sb.append(",\"capturedBranch\":").append(capturedBranch == null ? "null" : DeployLog.q(capturedBranch));
            return sb.append('}').toString();
        }

        private static String arr(List<String> items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(DeployLog.q(items.get(i)));
            }
            return sb.append(']').toString();
        }
    }

    private final Options o;
    private final VaultAccess testVault;   // non-null only from the package-private test constructor
    /** Test seam: the other servers of the set, by DN (their own fakes). Real runs connect through {@link Servers}. */
    Map<String, VaultAccess> testServers = Map.of();
    private final Map<String, VaultAccess> serverConnections = new LinkedHashMap<>();

    /** The connection for a step: the environment's own, or the named server's (opened once, closed with the run). */
    /**
     * The primary connection this run opens and closes — or, under test, the test's own vault
     * behind a proxy whose {@code close()} does nothing: the test opened it and inspects it after
     * the run, and a fake that has been closed refuses every call, as the real one does.
     */
    private VaultAccess openPrimary(Environments.Environment env) {
        if (testVault == null) {
            return Vault.connect(env.vaultConfig());
        }
        return (VaultAccess) java.lang.reflect.Proxy.newProxyInstance(VaultAccess.class.getClassLoader(),
            new Class<?>[] {VaultAccess.class}, (proxy, m, args) -> {
                if (m.getName().equals("close") && m.getParameterCount() == 0) {
                    return null;
                }
                try {
                    return m.invoke(testVault, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    private VaultAccess vaultFor(VaultAccess primary, Environments.Environment env, String serverDn) {
        if (serverDn == null) {
            return primary;
        }
        VaultAccess v = serverConnections.get(serverDn);
        if (v == null) {
            v = testServers.get(serverDn);
            if (v == null && testVault == null) {
                String url = Servers.urlOf(env, primary, serverDn);
                if (url == null) {
                    throw new VaultAccessException("no LDAP URL for server " + serverDn + " (give " + env.name + ".servers=" + serverDn + "=ldaps://host:port)");
                }
                v = Vault.connect(env.vaultConfig().withUrl(url));
            }
            if (v == null) {
                throw new VaultAccessException("no connection for server " + serverDn);
            }
            serverConnections.put(serverDn, v);
        }
        return v;
    }

    /** A server connection could not be made; reported as a failure of the run. */
    static final class VaultAccessException extends RuntimeException {
        VaultAccessException(String m) {
            super(m);
        }
    }

    private void closeServerConnections() {
        for (VaultAccess v : serverConnections.values()) {
            if (!testServers.containsValue(v)) {
                try {
                    v.close();
                } catch (RuntimeException ignore) {
                    // best effort
                }
            }
        }
        serverConnections.clear();
    }

    public Deployer(Options o) {
        this(o, null);
    }

    /**
     * Test seam: run against {@code testVault} (an in-memory fake) instead of a real, connected
     * {@link Vault} — {@link Vault} is {@code final}, so this is how {@code DeployerTest} exercises
     * the whole {@link #run()} flow (snapshot, writes, restarts, verify, audit) without a live vault.
     */
    Deployer(Options o, VaultAccess testVault) {
        this.o = o;
        this.testVault = testVault;
    }

    public Result run() throws IOException {
        Result r = new Result();
        Environments.Environment env = o.env;
        String dsDn = env.driverSetDn;

        // 1. the tree must validate clean — no way to skip. A deploy scoped to drivers is held to what it
        //    deploys (ValidationScope): an error in a driver it leaves alone is listed, not a refusal.
        o.progress.accept("validating the tree");
        Report validation = Validator.standard().validate(o.tree);
        String validationNote = "";
        if (!validation.ok()) {
            ValidationScope.Split split = o.drivers.isEmpty() ? null : ValidationScope.split(validation, o.tree, o.drivers);
            if (split == null || !split.blocking.isEmpty()) {
                r.refusal = "the tree does not validate: " + (split == null ? validation.summary()
                    : split.blocking.size() + " error(s) in what this deploy writes:\n" + ValidationScope.list(split.blocking)) + " — fix it first (idm validate)";
                return r;
            }
            validationNote = split.note();
        }
        for (String k : o.deleteAllKinds) {
            if (!VALID_DELETE_ALL_KINDS.contains(k)) {
                r.refusal = "--delete-all " + k + ": unknown kind (" + String.join(", ", VALID_DELETE_ALL_KINDS) + ")";
                return r;
            }
        }
        DriverSet to = AsCodeReader.read(o.tree);
        Overrides.Applied overrides = Overrides.apply(to, env.name);
        String overrideNote = overrides.summary() == null ? "" : overrides.summary() + "\n";
        String notDeployedHere = overrides.refusalFor(o.drivers);
        if (notDeployedHere != null) {
            r.refusal = notDeployedHere;
            return r;
        }
        // the drivers asked for, as this environment's vault names them (the request uses the tree's names)
        List<String> scope = overrides.vaultNames(o.drivers);
        String treeCommit = DeployLog.treeCommit(o.tree);
        Secrets secrets = (env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile)).withNames(overrides.renamed);
        Overrides.applySecretShimAuthIds(to, secrets);   // ids the secrets file supplies win over the tree's (never shown)

        o.progress.accept("reading the vault");
        try (VaultAccess vault = openPrimary(env)) {
            // 2. diff and plan — the other servers' own driver settings included, read through their connections
            DriverSet from = VaultDiff.fromVault(vault, dsDn);
            Servers.readOverrides(from, dsDn, vault, s -> vaultFor(vault, env, s), n -> r.skipped.add("note: " + n));
            if (!overrides.serverMap.isEmpty()) {
                Servers.foldPrimary(to, Servers.primary(vault));   // a mapped server that is this environment's primary holds the base values
            }
            String unknown = VaultDiff.unknownDrivers(from, to, scope);
            if (unknown != null) {
                r.refusal = unknown;
                closeServerConnections();
                return r;
            }
            if (o.captureOnly) {
                capture(r, env, from);
                closeServerConnections();
                return r;
            }
            ModelDiff diff = VaultDiff.of(from, to, scope);
            r.diffText = diff.text();
            Map<String, List<String>> liveNamed = new LinkedHashMap<>();
            if ("missing".equals(o.secretsMode)) {
                for (var d : to.drivers) {
                    if (scope.isEmpty() || scope.contains(d.name)) {
                        liveNamed.put(d.name, vault.namedPasswords(VaultMapping.driverDn(dsDn, d.name)));
                    }
                }
            }
            Plan plan = Plan.of(diff, to, dsDn, secrets, o.secretsMode, liveNamed, o.restart, o.tree, o.deleteDrivers, vault, o.deleteAllKinds);
            r.planText = validationNote + overrideNote + plan.text(env.name, dsDn);
            o.progress.accept("planned: " + plan.steps.size() + " write(s), " + (plan.restart.size() + plan.serverRestarts.size()) + " restart(s)");
            if (!plan.deleteDriverRefusals.isEmpty()) {
                r.refusal = String.join("; ", plan.deleteDriverRefusals);
                return r;
            }
            if (plan.isEmpty()) {
                r.ok = true;
                r.planText += "nothing to deploy — the vault matches the tree\n";
                return r;
            }
            if (!plan.missingSecrets.isEmpty() && !o.allowMissingSecrets) {
                r.refusal = plan.missingSecrets.size() + " required secret(s) not provided (see MISSING SECRET above); "
                    + "add them to the environment's secrets file, or --allow-missing-secrets to create the driver without them";
                return r;
            }
            if (o.dryRun) {
                r.ok = true;
                r.planText += "(dry run — nothing written)\n";
                if (env.tier == Environments.Tier.PRD) {
                    // the production checks a deploy would make, so the plan says what stands in the way first
                    r.gate = gate(env, treeCommit, from, vault, true);
                    r.gateKind = r.gate == null ? null : gateKind;
                }
                return r;
            }

            // 3. the gate
            String gate = gate(env, treeCommit, from, vault, false);
            if (gate != null) {
                r.refusal = gate;
                r.gateKind = gateKind;
                r.capturedBranch = capturedBranch;
                return r;
            }
            if (!o.yes && !o.step) {
                r.refusal = "add --yes (deploy the whole plan after a snapshot) or --step (confirm and verify each change)";
                return r;
            }
            if (o.step && o.json) {
                r.refusal = "--step is interactive; it cannot be combined with --json";
                return r;
            }

            // 4. snapshot
            List<String> driverDns = new ArrayList<>();
            for (String name : diff.affectedDrivers()) {
                if (to.driver(name) != null) {
                    driverDns.add(VaultMapping.driverDn(dsDn, name));
                }
            }
            o.progress.accept("taking a snapshot of what the plan changes");
            Snapshot snap = Snapshot.capture(vault, env.name, dsDn, plan.touchedDns, driverDns, treeCommit,
                "deploy", r.planText, List.of(to.templatesCollectionDn()));
            Path snapDir = o.tree.resolve("deploy-snapshots").resolve(env.name);
            Path snapFile = snap.write(snapDir);
            r.snapshot = o.tree.toAbsolutePath().relativize(snapFile.toAbsolutePath()).toString().replace('\\', '/');
            o.progress.accept("snapshot: " + r.snapshot);
            // the other servers' own copies of what the plan writes there: one snapshot per server
            // (vault.rollback --server <dn> restores it through that server's connection)
            Map<String, Set<String>> perServerDns = new LinkedHashMap<>();
            for (Plan.Step s : plan.steps) {
                if (s.server != null) {
                    perServerDns.computeIfAbsent(s.server, k -> new LinkedHashSet<>()).add(s.dn);
                }
            }
            for (Map.Entry<String, Set<String>> e : perServerDns.entrySet()) {
                try {
                    Snapshot ss = Snapshot.capture(vaultFor(vault, env, e.getKey()), env.name, dsDn, e.getValue(), List.of(), treeCommit,
                        "deploy@" + e.getKey(), r.planText);
                    Path f = ss.write(snapDir.resolve(Servers.dirName(e.getKey())));
                    r.skipped.add("snapshot for " + e.getKey() + ": " + o.tree.toAbsolutePath().relativize(f.toAbsolutePath()).toString().replace('\\', '/'));
                } catch (RuntimeException ex) {
                    r.refusal = "cannot snapshot server " + e.getKey() + ": " + ex.getMessage();
                    closeServerConnections();
                    return r;
                }
            }

            // 5. writes
            DeployLog.Record log = DeployLog.record(env.name, "deploy");
            log.treeCommit = treeCommit;
            log.snapshot = r.snapshot;
            log.changes = plan.changes().size();
            boolean stopped = false;
            try {
                if (o.step) {
                    stopped = !stepThrough(vault, env, plan, to, dsDn, secrets, r);
                } else {
                    int i = 0;
                    for (Plan.Step s : plan.steps) {
                        o.progress.accept("write " + (++i) + "/" + plan.steps.size() + ": " + s.op.name().toLowerCase(java.util.Locale.ROOT) + " " + s.dn
                            + (s.server == null ? "" : " @ " + s.server));
                        execute(vaultFor(vault, env, s.server), s, secrets, r);
                    }
                }
            } catch (RuntimeException | IOException e) {
                r.failure = e.getMessage();
            }

            // 6. restarts
            if (r.failure == null && !stopped) {
                for (String name : plan.restart) {
                    String dn = VaultMapping.driverDn(dsDn, name);
                    try {
                        int st = vault.driverState(dn);
                        if (st == Vault.STATE_RUNNING || st == Vault.STATE_STARTING) {
                            o.progress.accept("restarting " + name + ": waiting for Running, up to " + o.restartWaitSeconds + " s");
                            vault.restartDriver(dn);
                            String seen = vault.waitForState(dn, Vault.STATE_RUNNING, o.restartWaitSeconds);
                            r.restarted.add(name + " (" + seen + ")");
                            o.progress.accept(name + ": " + seen);
                        } else {
                            r.skipped.add("restart " + name + " — driver is " + Vault.stateName(st) + "; it will load the new configuration when started");
                        }
                    } catch (RuntimeException e) {
                        r.failure = "restart " + name + ": " + e.getMessage();
                        break;
                    }
                }
            }

            // 6b. restarts on the other servers, where a driver's own settings changed there
            if (r.failure == null && !stopped) {
                for (Map.Entry<String, Set<String>> e : plan.serverRestarts.entrySet()) {
                    String dn = VaultMapping.driverDn(dsDn, e.getKey());
                    for (String server : e.getValue()) {
                        try {
                            VaultAccess sv = vaultFor(vault, env, server);
                            int st = sv.driverState(dn);
                            if (st == Vault.STATE_RUNNING || st == Vault.STATE_STARTING) {
                                o.progress.accept("restarting " + e.getKey() + " @ " + server + ": waiting for Running, up to " + o.restartWaitSeconds + " s");
                                sv.restartDriver(dn);
                                String seen = sv.waitForState(dn, Vault.STATE_RUNNING, o.restartWaitSeconds);
                                r.restarted.add(e.getKey() + " @ " + server + " (" + seen + ")");
                                o.progress.accept(e.getKey() + " @ " + server + ": " + seen);
                            } else {
                                r.skipped.add("restart " + e.getKey() + " @ " + server + " — driver is " + Vault.stateName(st) + " there");
                            }
                        } catch (RuntimeException ex) {
                            r.failure = "restart " + e.getKey() + " @ " + server + ": " + ex.getMessage();
                            break;
                        }
                    }
                }
            }

            // 7. verify — the other servers' own settings re-read too
            o.progress.accept("verifying: reading the vault again");
            DriverSet after = VaultDiff.fromVault(vault, dsDn);
            Servers.readOverrides(after, dsDn, vault, s -> vaultFor(vault, env, s), n -> r.skipped.add("note: " + n));
            ModelDiff verify = VaultDiff.of(after, to, scope.isEmpty() ? diff.affectedDrivers() : scope);
            r.verified = verify.isEmpty();
            r.verifyText = verify.isEmpty() ? "" : verify.text();
            // affectedDrivers() never includes a deleted driver (never restarted), so its removal
            // needs its own check: the driver DN must no longer exist
            for (String name : plan.driversDeleted) {
                String dn = VaultMapping.driverDn(dsDn, name);
                if (vault.exists(dn)) {
                    r.verified = false;
                    r.verifyText = (r.verifyText == null ? "" : r.verifyText) + "driver '" + name + "' (" + dn + ") still exists after --delete-driver\n";
                }
            }
            r.ok = r.failure == null && r.verified;
            log.restarted.addAll(r.restarted);
            log.secretsSet.addAll(r.secretsSet);
            log.outcome = r.ok ? "ok" : (r.done.isEmpty() ? "failed" : "partial");
            log.detail = r.failure != null ? r.failure : (r.verified ? (stopped ? "stopped by user after " + r.done.size() + " step(s)" : "verified") : "verify mismatch:\n" + r.verifyText);
            if (stopped && r.verified) {
                log.outcome = "partial";
            }
            if (r.ok && !plan.driversDeleted.isEmpty()) {
                List<String> parts = new ArrayList<>();
                for (String name : plan.driversDeleted) {
                    parts.add(name + " (" + plan.deletedObjectCounts.getOrDefault(name, 0) + " object(s))");
                }
                String delMsg = "deleted driver(s): " + String.join(", ", parts);
                log.detail = (log.detail == null || log.detail.isBlank()) ? delMsg : log.detail + "; " + delMsg;
            }
            if (!plan.deleteAllKinds.isEmpty()) {
                String daMsg = "--delete-all: " + String.join(", ", plan.deleteAllKinds);
                log.detail = (log.detail == null || log.detail.isBlank()) ? daMsg : log.detail + "; " + daMsg;
            }
            DeployLog.append(o.tree, log);
            o.progress.accept(r.ok ? "done: verified" : "done: " + log.outcome + (r.failure != null ? " — " + r.failure : r.verified ? "" : " — the vault differs from the tree"));
            return r;
        } finally {
            closeServerConnections();
        }
    }

    /** A tree read for comparison with an environment's vault: the base with that environment's overrides and secret shim auth ids applied. */
    private static DriverSet readWithOverrides(Path tree, Environments.Environment env) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        Overrides.Applied applied = Overrides.apply(ds, env.name);
        Overrides.applySecretShimAuthIds(ds, (env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile)).withNames(applied.renamed));
        return ds;
    }

    /** Execute one step; records it in the result; throws on failure. */
    private void execute(VaultAccess vault, Plan.Step s, Secrets secrets, Result r) throws IOException {
        switch (s.op) {
            case ADD:
                vault.add(s.dn, s.objectClasses, s.values);
                break;
            case MODIFY:
                vault.replace(s.dn, s.attr, s.values.get(s.attr));
                break;
            case DELETE:
                vault.delete(s.dn);
                break;
            case DELETE_SUBTREE: {
                // deepest first (s.subtreeDns is already sorted that way at plan time): a mid-way
                // failure reports exactly which objects were deleted and which remain
                List<String> dns = s.subtreeDns;
                for (int i = 0; i < dns.size(); i++) {
                    String dn = dns.get(i);
                    try {
                        vault.delete(dn);
                    } catch (RuntimeException e) {
                        List<String> deleted = dns.subList(0, i);
                        List<String> remaining = dns.subList(i, dns.size());
                        for (String d : deleted) {
                            r.done.add("delete " + d);
                        }
                        throw new IOException("delete driver subtree " + s.dn + ": failed deleting " + dn + " ("
                            + deleted.size() + " of " + dns.size() + " deleted; " + remaining.size()
                            + " remaining: " + String.join(", ", remaining) + ")", e);
                    }
                }
                for (String dn : dns) {
                    r.done.add("delete " + dn);
                }
                break;
            }
            case AUX_CLASS:
                vault.addObjectClasses(s.dn, s.objectClasses);
                break;
            case DROP_AUX_CLASS:
                vault.removeObjectClasses(s.dn, s.objectClasses);
                break;
            case ENSURE_CONTAINER:
                if (!vault.exists(s.dn)) {
                    vault.add(s.dn, s.objectClasses, s.values);
                }
                break;
            case START_OPTION:
                vault.setDriverStartOption(s.dn, Vault.START_MANUAL);
                break;
            case NOTIFY_JOB:
                vault.notifyJobUpdate(s.dn);
                break;
            case SET_SECRET: {
                String key = s.attr;
                char[] value = secrets.get(key);
                if (value == null) {
                    r.skipped.add(s.description + " — not provided");
                    return;
                }
                try {
                    if (key.endsWith("." + Secrets.SHIM_AUTH)) {
                        vault.replace(s.dn, VaultMapping.SHIM_AUTH_PASSWORD, List.of(new String(value).getBytes(StandardCharsets.UTF_8)));
                    } else if (key.contains(".named.")) {
                        String name = key.substring(key.indexOf(".named.") + 7);
                        vault.setNamedPassword(s.dn, name, name, value.clone());
                    } else if (key.endsWith("." + Secrets.REMOTE_LOADER)) {
                        vault.setRemoteLoaderPassword(s.dn, value.clone());
                    } else if (key.endsWith("." + Secrets.KEY)) {
                        vault.setMutualAuthKeyPassword(s.dn, value.clone());
                    } else if (key.endsWith("." + Secrets.KEYSTORE)) {
                        vault.setMutualAuthKeystorePassword(s.dn, value.clone());
                    } else {
                        r.skipped.add(s.description + " — unknown secret kind");
                        return;
                    }
                } finally {
                    Arrays.fill(value, '\0');
                }
                r.secretsSet.add(key);
                break;
            }
            default:
                break;
        }
        r.done.add(s.description);
    }

    /** {@code --step}: per change — show, ask, write, verify that object. Returns false if the user quit. */
    private boolean stepThrough(VaultAccess vault, Environments.Environment env, Plan plan, DriverSet to, String dsDn, Secrets secrets, Result r) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String change : plan.changes()) {
            List<Plan.Step> steps = plan.stepsOf(change);
            System.out.println("\nchange: " + change);
            for (Plan.Step s : steps) {
                System.out.println("    " + s);
            }
            System.out.print("apply? [y/n/q] ");
            System.out.flush();
            String line = in.readLine();
            String a = line == null ? "q" : line.trim().toLowerCase();
            if (a.startsWith("q")) {
                System.out.println("stopped; " + r.done.size() + " step(s) written so far");
                return false;
            }
            if (!a.startsWith("y")) {
                for (Plan.Step s : steps) {
                    r.skipped.add(s.description + " — declined");
                }
                continue;
            }
            for (Plan.Step s : steps) {
                execute(vaultFor(vault, env, s.server), s, secrets, r);
            }
            // verify this change's objects immediately
            for (Plan.Step s : steps) {
                if (s.op == Plan.Op.ADD || s.op == Plan.Op.MODIFY) {
                    Vault.Entry e = vault.read(s.dn);
                    String problem = e == null ? "object missing after write" : mismatch(e, s);
                    if (problem != null) {
                        throw new IOException("verify " + s.dn + ": " + problem);
                    }
                } else if (s.op == Plan.Op.DROP_AUX_CLASS) {
                    Vault.Entry e = vault.read(s.dn);
                    for (String oc : s.objectClasses) {
                        if (e != null && e.hasClass(oc)) {
                            throw new IOException("verify " + s.dn + ": still carries " + oc);
                        }
                    }
                } else if (s.op == Plan.Op.DELETE && vault.exists(s.dn)) {
                    throw new IOException("verify " + s.dn + ": still exists after delete");
                } else if (s.op == Plan.Op.DELETE_SUBTREE) {
                    for (String dn : s.subtreeDns) {
                        if (vault.exists(dn)) {
                            throw new IOException("verify " + dn + ": still exists after delete driver subtree");
                        }
                    }
                }
            }
            System.out.println("    verified");
        }
        return true;
    }

    /** Compare what a step wrote with what the vault holds now; null when equal. */
    private static String mismatch(Vault.Entry e, Plan.Step s) {
        for (Map.Entry<String, List<byte[]>> a : s.values.entrySet()) {
            if (a.getKey().equalsIgnoreCase(VaultMapping.SHIM_AUTH_PASSWORD)) {
                continue;
            }
            List<byte[]> live = e.attrs.get(a.getKey());
            if (a.getValue().isEmpty()) {
                if (live != null && !live.isEmpty()) {
                    return a.getKey() + " should be absent";
                }
                continue;
            }
            if (live == null || live.size() != a.getValue().size()) {
                return a.getKey() + " has " + (live == null ? 0 : live.size()) + " value(s), expected " + a.getValue().size();
            }
            for (int i = 0; i < live.size(); i++) {
                if (!Arrays.equals(live.get(i), a.getValue().get(i))) {
                    if (live.size() > 1) {
                        // multi-valued: order isn't guaranteed by LDAP; compare as sets
                        List<String> x = strings(live);
                        List<String> y = strings(a.getValue());
                        if (x.containsAll(y) && y.containsAll(x)) {
                            break;
                        }
                    }
                    return a.getKey() + " differs from what was written";
                }
            }
        }
        return null;
    }

    private static List<String> strings(List<byte[]> v) {
        List<String> out = new ArrayList<>();
        for (byte[] b : v) {
            out.add(new String(b, StandardCharsets.UTF_8));
        }
        return out;
    }

    // ---- the gate ----

    /** What the last {@link #gate} found ({@link Result#gateKind}); set with each refusal it returns. */
    private String gateKind;
    /** The as-found branch the last capture made, or null (an empty capture leaves none). */
    private String capturedBranch;

    /**
     * Null when the deploy may proceed; else the reason it may not. {@code preflight}: a dry run asking what a deploy
     * would meet — no confirmation needed, and an unknown or drifted state is reported, never captured.
     */
    private String gate(Environments.Environment env, String treeCommit, DriverSet live, VaultAccess vault, boolean preflight) throws IOException {
        gateKind = null;
        if (env.tier != Environments.Tier.PRD) {
            return null;
        }
        if (!preflight && (o.confirm == null || !o.confirm.equals(env.name))) {
            gateKind = "confirm";
            return "a production deploy needs --confirm " + env.name;
        }
        if (treeCommit == null) {
            gateKind = "not-in-git";
            return "a production deploy needs the tree in git (no commit found for " + o.tree + ")";
        }
        if (DeployLog.treeDirty(o.tree)) {
            gateKind = "uncommitted";
            return "the tree has uncommitted changes; commit them first so the deploy records what was deployed";
        }
        if (o.baseBranch != null) {
            String on = DeployLog.treeBranch(o.tree);
            if (!o.baseBranch.equals(on)) {
                gateKind = "branch";
                return "a production deploy comes from " + o.baseBranch + "; this tree is on " + (on == null ? "no branch" : on)
                    + " — merge it into " + o.baseBranch + " and deploy from there";
            }
        }
        if (env.requires != null && !DeployLog.hasOkDeploy(o.tree, env.requires, treeCommit)) {
            gateKind = "requires";
            return "environment '" + env.name + "' requires a green deploy of this commit (" + treeCommit.substring(0, 12)
                + "), or of one with the same content, to '" + env.requires + "' first (deploy-log/" + env.requires + ".jsonl has none)";
        }
        String known = knownState(env, live);
        if (known == null) {
            return null;
        }
        boolean unknownState = DeployLog.lastOk(o.tree, env.name) == null;
        gateKind = unknownState ? "unknown-state" : "drift";
        if (preflight) {
            return known;
        }
        String why = driftRefusal(env, live, lastDrift, known);
        if (o.captureDrift) {
            gateKind = capturedBranch != null ? "captured" : "verified";
        }
        return why;
    }

    private ModelDiff lastDrift;

    /**
     * Null when the vault matches the last deploy to {@code env} on record; else why its state isn't known: nothing on
     * record, or how it differs ({@link #lastDrift} holds the difference). Reads the last deployed commit's tree.
     */
    private String knownState(Environments.Environment env, DriverSet live) throws IOException {
        lastDrift = null;
        DeployLog.Record last = DeployLog.lastOk(o.tree, env.name);
        if (last == null || last.treeCommit == null) {
            return "no deploy to '" + env.name + "' is on record, so its current state is unknown";
        }
        Path known = checkout(last.treeCommit);
        if (known == null) {
            return "cannot check out the last deployed commit " + last.treeCommit.substring(0, 12) + " from the tree's git history";
        }
        try {
            // an icon set in Designer since the last deploy is not drift worth refusing over: the
            // deploy neither restarts for it nor clobbers policy because of it
            ModelDiff drift = ModelDiff.of(readWithOverrides(known, env), live);
            if (!drift.isEmptyButForIcons()) {
                lastDrift = drift;
                return "the vault differs from the last deploy on record (" + last.treeCommit.substring(0, 12) + ")";
            }
        } finally {
            deleteRecursively(known);
        }
        return null;
    }

    /** {@link Options#captureOnly}: the vault's state recorded when it isn't the known one; nothing written to the vault. */
    private void capture(Result r, Environments.Environment env, DriverSet live) throws IOException {
        if (DeployLog.treeCommit(o.tree) == null) {
            r.refusal = "the tree is not in git: a capture records the vault's state as a commit";
            r.gateKind = "not-in-git";
            return;
        }
        String known = knownState(env, live);
        r.ok = true;
        if (known == null) {
            r.gateKind = "known";
            r.planText = "the vault matches the last deploy to '" + env.name + "' on record: its state is known, nothing to capture\n";
            return;
        }
        boolean was = o.captureDrift;
        o.captureDrift = true;
        try {
            r.planText = driftRefusal(env, live, lastDrift, known) + "\n";
        } finally {
            o.captureDrift = was;
        }
        r.capturedBranch = capturedBranch;
        r.gateKind = capturedBranch != null ? "captured" : "verified";
    }

    private String driftRefusal(Environments.Environment env, DriverSet live, ModelDiff drift, String why) throws IOException {
        StringBuilder sb = new StringBuilder(why);
        if (drift != null) {
            sb.append(":\n").append(drift.text());
        }
        capturedBranch = null;
        if (!o.captureDrift) {
            sb.append("\nrun again with --capture-drift to record the vault's current state in the repo as its own commit, then deploy on top of it");
            return sb.toString();
        }
        // capture: the live state as its own commit on a branch based on the last deployed
        // commit — or, when nothing was ever deployed here, on the tree's first commit (the
        // import) — so the intended change can be rebased on top of what production holds
        String ts = Instant.now().toString().replace(':', '-');
        String branch = "as-found/" + env.name + "/" + ts;
        Path wt = Files.createTempDirectory("idm-as-found");
        Files.delete(wt);
        DeployLog.Record last = DeployLog.lastOk(o.tree, env.name);
        String base = last != null && last.treeCommit != null ? last.treeCommit : rootCommit();
        run("git", "-C", o.tree.toAbsolutePath().toString(), "worktree", "add", "-b", branch, wt.toString(), base);
        // the worktree is the whole repository: the tree is where it sits in it (tree/ in a page's project)
        Path wtTree = wt.resolve(treePrefix());
        try {
            // does the vault differ from the base commit? decided on bytes through the
            // model, never through git's view (which may normalize line endings)
            boolean differs;
            try {
                differs = !ModelDiff.of(readWithOverrides(wtTree, env), live).isEmptyButForIcons();
            } catch (RuntimeException | IOException e) {
                differs = true;
            }
            // exactly the live state: clear the managed paths first so nothing stale survives,
            // and make sure git stores the bytes as they are
            Files.createDirectories(wtTree);
            for (String managed : AsCodeWriter.MANAGED_PATHS) {
                deleteRecursively(wtTree.resolve(managed));
            }
            DeployLog.writeAttributes(wtTree);
            AsCodeWriter.write(live, wtTree);
            run("git", "-C", wtTree.toString(), "add", "-A", "--", ".");
            String commit;
            if (differs) {
                List<String> commitCmd = new ArrayList<>(List.of("git", "-C", wt.toString()));
                commitCmd.addAll(identityArgs(gitConfig(wt, "user.email")));
                commitCmd.addAll(List.of("commit", "-q", "-m", env.name + " as found " + ts));
                run(commitCmd.toArray(new String[0]));
                commit = DeployLog.treeCommit(wt);
            } else {
                commit = DeployLog.treeCommit(wt);   // the base itself: the vault matches it
            }
            DeployLog.Record rec = DeployLog.record(env.name, "deploy");
            rec.treeCommit = commit;
            rec.outcome = "ok";
            rec.changes = 0;
            rec.detail = differs ? "state captured from the vault (--capture-drift), not deployed"
                : "vault verified to match this commit (--capture-drift), nothing deployed";
            DeployLog.append(o.tree, rec);
            if (differs) {
                capturedBranch = branch;
                sb.append("\ncaptured the vault's state as branch ").append(branch).append(" (commit ")
                    .append(commit == null ? "?" : commit.substring(0, 12))
                    .append(") and recorded it as the known state; put your change on top of it — `git rebase ")
                    .append(branch).append("` — then deploy again");
            } else {
                sb.append("\nthe vault matches commit ").append(commit == null ? "?" : commit.substring(0, 12))
                    .append(" exactly; recorded it as the known state — commit deploy-log/ and deploy again");
            }
        } finally {
            run("git", "-C", o.tree.toAbsolutePath().toString(), "worktree", "remove", "--force", wt.toString());
        }
        try {
            // an empty capture leaves no branch behind
            Process p = new ProcessBuilder("git", "-C", o.tree.toAbsolutePath().toString(), "diff", "--quiet", base, branch)
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) {
                run("git", "-C", o.tree.toAbsolutePath().toString(), "branch", "-D", branch);
                capturedBranch = null;
            }
        } catch (Exception ignored) {
            // the branch stays; harmless
        }
        return sb.toString();
    }

    /**
     * The identity the as-found commit needs when git has none: a container (the web image) has no
     * {@code user.email} anywhere, and git then refuses to commit ("unable to auto-detect email address"),
     * which stopped a capture before it recorded the vault's state. A configured identity is left alone.
     */
    static List<String> identityArgs(String configuredEmail) {
        if (configuredEmail != null && !configuredEmail.isBlank()) {
            return List.of();
        }
        return List.of("-c", "user.name=DirXMLDev", "-c", "user.email=dirxmldev@localhost");
    }

    /** {@code git config <key>} in {@code dir} (repository, global and system settings), or null when unset. */
    static String gitConfig(Path dir, String key) {
        try {
            Process p = new ProcessBuilder("git", "-C", dir.toAbsolutePath().toString(), "config", "--get", key)
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The tree's first commit (the import), or HEAD when it can't be found. */
    /** Where the tree sits in its repository ({@code git rev-parse --show-prefix}): "" at the top, else e.g. "tree/". */
    private String treePrefix() {
        try {
            Process p = new ProcessBuilder("git", "-C", o.tree.toAbsolutePath().toString(), "rev-parse", "--show-prefix")
                .redirectErrorStream(true).start();
            String out;
            try (java.io.InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            }
            return p.waitFor() == 0 ? out : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String rootCommit() {
        try {
            Process p = new ProcessBuilder("git", "-C", o.tree.toAbsolutePath().toString(), "rev-list", "--max-parents=0", "HEAD")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            String first = out.split("\n")[0].strip();
            return p.waitFor() == 0 && first.matches("[0-9a-f]{40}") ? first : "HEAD";
        } catch (Exception e) {
            return "HEAD";
        }
    }

    /**
     * A temp checkout of the tree at a commit, or null. The commit comes from the deploy log, so it
     * is accepted only as a full SHA; git and tar are run as argument lists, never through a shell.
     */
    private Path checkout(String commit) {
        return checkoutAt(o.tree, commit);
    }

    static Path checkoutAt(Path tree, String commit) {
        if (commit == null || !commit.matches("[0-9a-f]{40}")) {
            return null;
        }
        Path dir = null;
        Path tar = null;
        try {
            dir = Files.createTempDirectory("idm-known");
            tar = Files.createTempFile("idm-known", ".tar");
            run("git", "-C", tree.toAbsolutePath().toString(), "archive", "--format=tar", "-o", tar.toString(), commit);
            run("tar", "-x", "-f", tar.toString(), "-C", dir.toString());
            return dir;
        } catch (Exception e) {
            if (dir != null) {
                deleteRecursively(dir);
            }
            return null;
        } finally {
            if (tar != null) {
                try {
                    Files.deleteIfExists(tar);
                } catch (IOException ignore) {
                    // best effort
                }
            }
        }
    }

    private static void run(String... cmd) throws IOException {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IOException(String.join(" ", cmd) + ": " + out.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    static void deleteRecursively(Path dir) {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // temp dir
        }
    }

    // ---- rollback ----

    public static Result rollback(Path tree, Environments.Environment env, Path snapshotFile, boolean yes) throws IOException {
        return rollback(tree, env, snapshotFile, yes, null);
    }

    /** {@code serverDn} non-null: restore through that server's own connection (a per-server snapshot). */
    public static Result rollback(Path tree, Environments.Environment env, Path snapshotFile, boolean yes, String serverDn) throws IOException {
        return rollback(tree, env, snapshotFile, yes, serverDn, line -> { });
    }

    /** As above; {@code progress} hears each step as it happens (see {@link Options#progress}). */
    public static Result rollback(Path tree, Environments.Environment env, Path snapshotFile, boolean yes, String serverDn,
                                  java.util.function.Consumer<String> progress) throws IOException {
        Result r = new Result();
        Snapshot snap = Snapshot.read(snapshotFile);
        r.planText = "rollback to " + snapshotFile + ":\n" + snap.text();
        if (env.tier == Environments.Tier.PRD && !yes) {
            r.refusal = "add --yes to roll production back to this snapshot";
            return r;
        }
        if (!yes) {
            r.refusal = "add --yes to restore the snapshot";
            return r;
        }
        Vault.Config cfg = env.vaultConfig();
        if (serverDn != null) {
            try (Vault primary = Vault.connect(env.vaultConfig())) {
                String url = Servers.urlOf(env, primary, serverDn);
                if (url == null) {
                    r.refusal = "no LDAP URL for server " + serverDn + " (give " + env.name + ".servers=" + serverDn + "=ldaps://host:port)";
                    return r;
                }
                cfg = cfg.withUrl(url);
            }
        }
        try (Vault vault = Vault.connect(cfg)) {
            // a rollback is itself reversible: snapshot the current state of the same objects first
            List<String> dns = new ArrayList<>();
            for (Snapshot.CapturedEntry e : snap.entries) {
                dns.add(e.dn);
            }
            List<String> driverDns = new ArrayList<>();
            for (Snapshot.DriverStateInfo d : snap.drivers) {
                driverDns.add(d.dn);
            }
            // a snapshot may hold the vault's notification templates (outside the driver set): their parents are roots too
            List<String> extraRoots = new ArrayList<>();
            for (String dn : dns) {
                String d = dn.toLowerCase(java.util.Locale.ROOT);
                if (!d.equals(env.driverSetDn.toLowerCase(java.util.Locale.ROOT)) && !d.endsWith("," + env.driverSetDn.toLowerCase(java.util.Locale.ROOT)) && dn.indexOf(',') > 0) {
                    extraRoots.add(dn.substring(dn.indexOf(',') + 1));
                }
            }
            progress.accept("taking a snapshot of the current state");
            Snapshot before = Snapshot.capture(vault, env.name, env.driverSetDn, dns, driverDns,
                DeployLog.treeCommit(tree), "before-rollback", null, extraRoots);
            Path snapFile = before.write(tree.resolve("deploy-snapshots").resolve(env.name));
            r.snapshot = tree.toAbsolutePath().relativize(snapFile.toAbsolutePath()).toString().replace('\\', '/');
            progress.accept("snapshot: " + r.snapshot);
            progress.accept("restoring " + snap.entries.size() + " object(s) and the drivers' states");
            Snapshot.RestoreResult rr = snap.restore(vault);
            r.done.addAll(rr.actions);
            if (!rr.ok()) {
                List<String> f = new ArrayList<>();
                rr.failures.forEach((dn, why) -> f.add(dn + ": " + why));
                r.failure = String.join("; ", f);
            }
            progress.accept("verifying: comparing the vault with the snapshot");
            List<String> diffs = snap.differences(vault);
            r.verified = diffs.isEmpty();
            r.verifyText = diffs.isEmpty() ? "" : String.join("\n", diffs) + "\n";
            r.ok = r.failure == null && r.verified;
            DeployLog.Record log = DeployLog.record(env.name, "rollback");
            log.treeCommit = DeployLog.treeCommit(tree);
            log.snapshot = r.snapshot;
            log.outcome = r.ok ? "ok" : "failed";
            log.detail = "restored " + snapshotFile + (r.failure == null ? "" : "; " + r.failure);
            DeployLog.append(tree, log);
            progress.accept(r.ok ? "done: verified" : "done: failed" + (r.failure != null ? " — " + r.failure : " — the vault differs from the snapshot"));
        }
        return r;
    }
}
