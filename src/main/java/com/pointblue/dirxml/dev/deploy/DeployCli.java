package com.pointblue.dirxml.dev.deploy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code idm vault.<cmd> …}:
 * <pre>
 *   vault.diff     <tree> --env <name> [--driver D…] [--json]
 *   vault.deploy   <tree> --env <name> [--driver D…] [--dry-run | --yes | --step] [--confirm <name>]
 *                  [--no-restart] [--secrets none|missing|all] [--allow-missing-secrets] [--capture-drift] [--json]
 *                  [--delete-driver D…] [--delete-all entitlements|forms|prds|roles|entities|… …]
 *   vault.verify   <tree> --env <name> [--driver D…] [--json]
 *   vault.rollback --env <name> --snapshot <file> [--yes] [--json]
 *   vault.secrets  --env <name> --driver D --set <key> …
 * </pre>
 */
public final class DeployCli {

    private DeployCli() {
    }

    public static int run(String[] argv) throws Exception {
        String cmd = argv[0];
        Map<String, List<String>> opts = new LinkedHashMap<>();
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = "";
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    value = argv[++i];
                }
                opts.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            } else {
                pos.add(a);
            }
        }
        boolean json = opts.containsKey("json");
        String envName = first(opts, "env");
        if (envName == null) {
            System.err.println("--env <name> is required (environments.properties; see docs/vault-deploy.md)");
            return 2;
        }
        if (writesVault(cmd, opts)) {
            String why = AgentWriteGate.refusal(envName, true, first(opts, "confirm"), System.getenv(AgentWriteGate.ENV));
            if (why != null) {
                Deployer.Result refused = new Deployer.Result();
                refused.refusal = why;
                System.out.print(json ? refused.json() + "\n" : refused.text());
                return 1;
            }
        }
        Environments.Environment env = Environments.load().get(envName);
        List<String> drivers = opts.getOrDefault("driver", List.of());

        switch (cmd) {
            case "vault.diff":
            case "vault.verify": {
                if (pos.isEmpty()) {
                    System.err.println("usage: " + cmd + " <tree> --env <name> [--driver D…] [--json]");
                    return 2;
                }
                ModelDiff diff = VaultDiff.of(Paths.get(pos.get(0)), env, drivers);
                System.out.print(json ? diff.json() + "\n" : diff.text());
                return diff.isEmpty() ? 0 : 1;
            }
            case "vault.deploy": {
                if (pos.isEmpty()) {
                    System.err.println("usage: vault.deploy <tree> --env <name> [--driver D…] [--dry-run|--yes|--step] …");
                    return 2;
                }
                Deployer.Options o = new Deployer.Options();
                o.tree = Paths.get(pos.get(0));
                o.env = env;
                o.drivers = drivers;
                o.dryRun = opts.containsKey("dry-run");
                o.yes = opts.containsKey("yes");
                o.step = opts.containsKey("step");
                o.confirm = first(opts, "confirm");
                o.restart = !opts.containsKey("no-restart");
                o.secretsMode = opts.containsKey("secrets") ? first(opts, "secrets") : "none";
                o.allowMissingSecrets = opts.containsKey("allow-missing-secrets");
                o.captureDrift = opts.containsKey("capture-drift");
                o.json = json;
                o.deleteDrivers = opts.getOrDefault("delete-driver", List.of());
                o.deleteAllKinds = opts.getOrDefault("delete-all", List.of());
                Deployer.Result r = new Deployer(o).run();
                System.out.print(json ? r.json() + "\n" : r.text());
                return r.ok ? 0 : 1;
            }
            case "vault.rollback": {
                String snap = first(opts, "snapshot");
                if (snap == null) {
                    System.err.println("usage: vault.rollback --env <name> --snapshot <file.ldif> [--yes] [--json]");
                    return 2;
                }
                Path tree = pos.isEmpty() ? Paths.get(".") : Paths.get(pos.get(0));
                Deployer.Result r = Deployer.rollback(tree, env, Paths.get(snap), opts.containsKey("yes"));
                System.out.print(json ? r.json() + "\n" : r.text());
                return r.ok ? 0 : 1;
            }
            case "vault.export-clone":
            case "vault.import-clone":
                return com.pointblue.dirxml.dev.clone.CloneCli.run(cmd, env, opts, json);
            default:
                System.err.println("unknown command " + cmd);
                return 2;
        }
    }

    /** A command that will change the vault. {@code --dry-run} is never a write. */
    static boolean writesVault(String cmd, Map<String, List<String>> opts) {
        return AgentWriteGate.writesVault(cmd, opts);
    }

    private static String first(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }
}
