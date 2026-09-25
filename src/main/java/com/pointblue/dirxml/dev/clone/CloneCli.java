package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Vault;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import javax.naming.directory.SearchControls;

/**
 * <pre>
 *   vault.export-clone --env <src> --out <dir> [--rbs] [--keep-driver-state] [--data <container>[,…]] [--pseudonymise]
 *   vault.import-clone --env <lab> --from <dir> [--server <dn>] [--map <srcDn>=<dstDn> …]
 *                      [--driver-server <driver>=<srcServerDn> …] [--user-password <secretKey>]
 *                      [--replace] [--replace-driverset] [--yes] [--json]
 * </pre>
 * Other servers of a tree (source or lab) are reached with the environment's credentials at
 * the URL the tree describes, or at {@code <env>.servers=<serverDn>=<url>;…} when it does not.
 * Two steps on purpose: the bundle is the handoff, and the two may run on different
 * workstations (docs/vault-clone.md §3).
 */
public final class CloneCli {

    private CloneCli() {
    }

    public static int run(String cmd, Environments.Environment env, Map<String, List<String>> opts, boolean json) throws Exception {
        switch (cmd) {
            case "vault.export-clone": {
                String out = first(opts, "out");
                if (out == null) {
                    System.err.println("usage: vault.export-clone --env <name> --out <dir> [--rbs] [--keep-driver-state]");
                    return 2;
                }
                Vault.Config c = env.vaultConfig();
                Schema schema;
                try (Vault v = Vault.connect(c)) {
                    schema = CloneExporter.readSchema(v);
                }
                c.binaryAttrs = schema.binaryAttributes();     // every octet-string attribute of the source, read as bytes
                CloneExporter.Options o = new CloneExporter.Options();
                o.withRbs = opts.containsKey("rbs");
                o.keepDriverState = opts.containsKey("keep-driver-state");
                o.pseudonymise = opts.containsKey("pseudonymise") || opts.containsKey("pseudonymize");
                List<String> data = new java.util.ArrayList<>();
                for (String d : opts.getOrDefault("data", List.of())) {
                    for (String part : d.split(",")) {
                        if (!part.isBlank()) {
                            data.add(part.trim());
                        }
                    }
                }
                o.dataContainers = data;
                o.sourceName = env.name + " (" + env.url + ")";
                o.serverUrls.putAll(serverUrls(env));
                final Vault.Config cfg = c;
                o.connector = url -> Vault.connect(cfg.withUrl(url));
                CloneExporter.Report r;
                try (Vault v = Vault.connect(c)) {
                    r = new CloneExporter(v, o).run();
                }
                r.bundle.write(Paths.get(out));
                System.out.print(r.text());
                System.out.println("wrote " + out);
                return 0;
            }
            case "vault.import-clone": {
                String from = first(opts, "from");
                if (from == null) {
                    System.err.println("usage: vault.import-clone --env <name> --from <dir> [--server <dn>] [--map src=dst …] [--replace] [--replace-driverset] [--yes] [--json]");
                    return 2;
                }
                if (env.tier == Environments.Tier.PRD) {
                    System.err.println("REFUSED  import-clone never targets a prd-tier environment (" + env.name + ")");
                    return 1;
                }
                CloneBundle bundle = CloneBundle.read(Paths.get(from));
                Vault.Config c = env.vaultConfig();
                c.binaryAttrs = bundle.schema().binaryAttributes();
                CloneImporter.Options o = new CloneImporter.Options();
                o.envName = env.name;
                o.dryRun = !opts.containsKey("yes");
                o.replace = opts.containsKey("replace");
                o.replaceDriverSet = opts.containsKey("replace-driverset");
                o.logDir = Paths.get(from).resolve("imports");
                for (String m : opts.getOrDefault("map", List.of())) {
                    int eq = m.indexOf('=');
                    if (eq <= 0) {
                        System.err.println("--map wants <sourceDn>=<targetDn>: " + m);
                        return 2;
                    }
                    o.dnMap.put(m.substring(0, eq).trim(), m.substring(eq + 1).trim());
                }
                for (String m : opts.getOrDefault("driver-server", List.of())) {
                    int eq = m.indexOf('=');
                    if (eq <= 0) {
                        System.err.println("--driver-server wants <driver>=<sourceServerDn>: " + m);
                        return 2;
                    }
                    o.driverServer.put(m.substring(0, eq).trim(), m.substring(eq + 1).trim());
                }
                o.targetServerUrls.putAll(serverUrls(env));
                final Vault.Config cfg = c;
                o.targetConnector = url -> Vault.connect(cfg.withUrl(url));
                o.serverDn = first(opts, "server");
                String pwKey = first(opts, "user-password");
                if (pwKey != null) {
                    if (env.secretsFile == null) {
                        System.err.println("--user-password needs the environment's secrets file (" + env.name + ".secrets=…)");
                        return 2;
                    }
                    o.userPassword = com.pointblue.dirxml.dev.deploy.Secrets.load(env.secretsFile).get(pwKey);
                }
                CloneImporter.Result r;
                try (Vault v = Vault.connect(c)) {
                    if (o.serverDn == null) {
                        List<Vault.Entry> servers = v.search("", "(objectClass=ncpServer)", SearchControls.SUBTREE_SCOPE);
                        if (servers.size() == 1) {
                            o.serverDn = servers.get(0).dn;
                        } else if (servers.size() > 1) {
                            System.err.println("the target has " + servers.size() + " servers; say which one with --server <dn>");
                            return 2;
                        }
                    }
                    r = new CloneImporter(bundle, v, o).run();
                }
                System.out.print(json ? r.json() + "\n" : r.text());
                return r.ok ? 0 : 1;
            }
            default:
                return 2;
        }
    }

    /** {@code <env>.servers=<serverDn>=<url>;<serverDn>=<url>} — the tree's other servers, when it cannot describe them. */
    static Map<String, String> serverUrls(Environments.Environment env) throws Exception {
        return com.pointblue.dirxml.dev.deploy.Servers.urls(env);
    }

    private static String first(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    static Path path(String s) {
        return Paths.get(s);
    }
}
