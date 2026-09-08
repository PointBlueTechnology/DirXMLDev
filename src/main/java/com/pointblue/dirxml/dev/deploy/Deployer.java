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
        public boolean json;
        public List<String> deleteDrivers = List.of();
        public int restartWaitSeconds = 180;
    }

    public static final class Result {
        public boolean ok;
        public String refusal;
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

    public Deployer(Options o) {
        this.o = o;
    }

    public Result run() throws IOException {
        Result r = new Result();
        Environments.Environment env = o.env;
        String dsDn = env.driverSetDn;

        // 1. the tree must validate clean — no way to skip
        Report validation = Validator.standard().validate(o.tree);
        if (!validation.ok()) {
            r.refusal = "the tree does not validate: " + validation.summary() + " — fix it first (idm validate)";
            return r;
        }
        DriverSet to = AsCodeReader.read(o.tree);
        String treeCommit = DeployLog.treeCommit(o.tree);
        Secrets secrets = env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile);

        try (Vault vault = Vault.connect(env.vaultConfig())) {
            // 2. diff and plan
            DriverSet from = VaultDiff.readLive(env);
            ModelDiff diff = VaultDiff.of(from, to, o.drivers);
            r.diffText = diff.text();
            Map<String, List<String>> liveNamed = new LinkedHashMap<>();
            if ("missing".equals(o.secretsMode)) {
                for (var d : to.drivers) {
                    if (o.drivers.isEmpty() || o.drivers.contains(d.name)) {
                        liveNamed.put(d.name, vault.namedPasswords(VaultMapping.driverDn(dsDn, d.name)));
                    }
                }
            }
            Plan plan = Plan.of(diff, to, dsDn, secrets, o.secretsMode, liveNamed, o.restart);
            for (String del : o.deleteDrivers) {
                if (from.driver(del) != null && to.driver(del) == null) {
                    plan.notes.add("--delete-driver " + del + ": not implemented yet (delete the driver's subtree manually)");
                }
            }
            r.planText = plan.text(env.name, dsDn);
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
                return r;
            }

            // 3. the gate
            String gate = gate(env, treeCommit, from, vault);
            if (gate != null) {
                r.refusal = gate;
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
            Snapshot snap = Snapshot.capture(vault, env.name, dsDn, plan.touchedDns, driverDns, treeCommit,
                "deploy", r.planText);
            Path snapDir = o.tree.resolve("deploy-snapshots").resolve(env.name);
            Path snapFile = snap.write(snapDir);
            r.snapshot = o.tree.toAbsolutePath().relativize(snapFile.toAbsolutePath()).toString().replace('\\', '/');

            // 5. writes
            DeployLog.Record log = DeployLog.record(env.name, "deploy");
            log.treeCommit = treeCommit;
            log.snapshot = r.snapshot;
            log.changes = plan.changes().size();
            boolean stopped = false;
            try {
                if (o.step) {
                    stopped = !stepThrough(vault, plan, to, dsDn, secrets, r);
                } else {
                    for (Plan.Step s : plan.steps) {
                        execute(vault, s, secrets, r);
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
                            vault.restartDriver(dn);
                            String seen = vault.waitForState(dn, Vault.STATE_RUNNING, o.restartWaitSeconds);
                            r.restarted.add(name + " (" + seen + ")");
                        } else {
                            r.skipped.add("restart " + name + " — driver is " + Vault.stateName(st) + "; it will load the new configuration when started");
                        }
                    } catch (RuntimeException e) {
                        r.failure = "restart " + name + ": " + e.getMessage();
                        break;
                    }
                }
            }

            // 7. verify
            DriverSet after = VaultDiff.readLive(env);
            ModelDiff verify = VaultDiff.of(after, to, o.drivers.isEmpty() ? diff.affectedDrivers() : o.drivers);
            r.verified = verify.isEmpty();
            r.verifyText = verify.isEmpty() ? "" : verify.text();
            r.ok = r.failure == null && r.verified;
            log.restarted.addAll(r.restarted);
            log.secretsSet.addAll(r.secretsSet);
            log.outcome = r.ok ? "ok" : (r.done.isEmpty() ? "failed" : "partial");
            log.detail = r.failure != null ? r.failure : (r.verified ? (stopped ? "stopped by user after " + r.done.size() + " step(s)" : "verified") : "verify mismatch:\n" + r.verifyText);
            if (stopped && r.verified) {
                log.outcome = "partial";
            }
            DeployLog.append(o.tree, log);
            return r;
        }
    }

    /** Execute one step; records it in the result; throws on failure. */
    private void execute(Vault vault, Plan.Step s, Secrets secrets, Result r) throws IOException {
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
            case START_OPTION:
                vault.setDriverStartOption(s.dn, Vault.START_MANUAL);
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
                    } else {
                        r.skipped.add(s.description + " — Remote Loader passwords are not supported yet");
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
    private boolean stepThrough(Vault vault, Plan plan, DriverSet to, String dsDn, Secrets secrets, Result r) throws IOException {
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
                execute(vault, s, secrets, r);
            }
            // verify this change's objects immediately
            for (Plan.Step s : steps) {
                if (s.op == Plan.Op.ADD || s.op == Plan.Op.MODIFY) {
                    Vault.Entry e = vault.read(s.dn);
                    String problem = e == null ? "object missing after write" : mismatch(e, s);
                    if (problem != null) {
                        throw new IOException("verify " + s.dn + ": " + problem);
                    }
                } else if (s.op == Plan.Op.DELETE && vault.exists(s.dn)) {
                    throw new IOException("verify " + s.dn + ": still exists after delete");
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
                    if (a.getKey().equalsIgnoreCase(VaultMapping.POLICIES)) {
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

    /** Null when the deploy may proceed; else the reason it may not. */
    private String gate(Environments.Environment env, String treeCommit, DriverSet live, Vault vault) throws IOException {
        if (env.tier != Environments.Tier.PRD) {
            return null;
        }
        if (o.confirm == null || !o.confirm.equals(env.name)) {
            return "a production deploy needs --confirm " + env.name;
        }
        if (treeCommit == null) {
            return "a production deploy needs the tree in git (no commit found for " + o.tree + ")";
        }
        if (DeployLog.treeDirty(o.tree)) {
            return "the tree has uncommitted changes; commit them first so the deploy records what was deployed";
        }
        if (env.requires != null && !DeployLog.hasOkDeploy(o.tree, env.requires, treeCommit)) {
            return "environment '" + env.name + "' requires a green deploy of this commit (" + treeCommit.substring(0, 12)
                + ") to '" + env.requires + "' first (deploy-log/" + env.requires + ".jsonl has none)";
        }
        // known state: the vault must match what was last deployed here
        DeployLog.Record last = DeployLog.lastOk(o.tree, env.name);
        if (last == null || last.treeCommit == null) {
            return driftRefusal(env, live, null, "no deploy to '" + env.name + "' is on record, so its current state is unknown");
        }
        Path known = checkout(last.treeCommit);
        if (known == null) {
            return "cannot check out the last deployed commit " + last.treeCommit.substring(0, 12) + " from the tree's git history";
        }
        try {
            ModelDiff drift = ModelDiff.of(AsCodeReader.read(known), live);
            if (!drift.isEmpty()) {
                return driftRefusal(env, live, drift, "the vault differs from the last deploy on record (" + last.treeCommit.substring(0, 12) + ")");
            }
        } finally {
            deleteRecursively(known);
        }
        return null;
    }

    private String driftRefusal(Environments.Environment env, DriverSet live, ModelDiff drift, String why) throws IOException {
        StringBuilder sb = new StringBuilder(why);
        if (drift != null) {
            sb.append(":\n").append(drift.text());
        }
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
        try {
            // does the vault differ from the base commit? decided on bytes through the
            // model, never through git's view (which may normalize line endings)
            boolean differs;
            try {
                differs = !ModelDiff.of(AsCodeReader.read(wt), live).isEmpty();
            } catch (RuntimeException | IOException e) {
                differs = true;
            }
            // exactly the live state: clear the managed paths first so nothing stale survives,
            // and make sure git stores the bytes as they are
            for (String managed : new String[] {"driverset.xml", "config-values.xml", "library", "drivers"}) {
                deleteRecursively(wt.resolve(managed));
            }
            Path attrs = wt.resolve(".gitattributes");
            if (!Files.exists(attrs)) {
                Files.writeString(attrs, "# IDM-as-code: content is byte-exact vault data; never normalize line endings\n* -text\n");
            }
            AsCodeWriter.write(live, wt);
            run("git", "-C", wt.toString(), "add", "-A");
            String commit;
            if (differs) {
                run("git", "-C", wt.toString(), "commit", "-q", "-m", env.name + " as found " + ts);
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
            }
        } catch (Exception ignored) {
            // the branch stays; harmless
        }
        return sb.toString();
    }

    /** The tree's first commit (the import), or HEAD when it can't be found. */
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

    /** A temp checkout of the tree at a commit, or null. */
    private Path checkout(String commit) {
        try {
            Path dir = Files.createTempDirectory("idm-known");
            Process p = new ProcessBuilder("/bin/sh", "-c",
                "git -C '" + o.tree.toAbsolutePath() + "' archive --format=tar " + commit + " | tar -x -C '" + dir + "'")
                .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 ? dir : null;
        } catch (Exception e) {
            return null;
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

    private static void deleteRecursively(Path dir) {
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
        try (Vault vault = Vault.connect(env.vaultConfig())) {
            // a rollback is itself reversible: snapshot the current state of the same objects first
            List<String> dns = new ArrayList<>();
            for (Snapshot.CapturedEntry e : snap.entries) {
                dns.add(e.dn);
            }
            List<String> driverDns = new ArrayList<>();
            for (Snapshot.DriverStateInfo d : snap.drivers) {
                driverDns.add(d.dn);
            }
            Snapshot before = Snapshot.capture(vault, env.name, env.driverSetDn, dns, driverDns,
                DeployLog.treeCommit(tree), "before-rollback", null);
            Path snapFile = before.write(tree.resolve("deploy-snapshots").resolve(env.name));
            r.snapshot = tree.toAbsolutePath().relativize(snapFile.toAbsolutePath()).toString().replace('\\', '/');
            Snapshot.RestoreResult rr = snap.restore(vault);
            r.done.addAll(rr.actions);
            if (!rr.ok()) {
                List<String> f = new ArrayList<>();
                rr.failures.forEach((dn, why) -> f.add(dn + ": " + why));
                r.failure = String.join("; ", f);
            }
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
        }
        return r;
    }
}
