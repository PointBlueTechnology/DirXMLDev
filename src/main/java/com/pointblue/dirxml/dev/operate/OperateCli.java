package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Secrets;
import com.pointblue.dirxml.dev.deploy.Vault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code idm driver.<cmd> …} / {@code idm driverset.<cmd> …} / {@code idm engine.<cmd> …}
 * (docs/operate.md):
 * <pre>
 *   driverset.status            --env E [--json]
 *   driver.status               --env E --driver D [--json] [--tree DIR]
 *   driver.start|stop|restart   --env E --driver D [--wait N] [--yes] [--confirm E] [--tree DIR]
 *   driver.cache view           --env E --driver D [--count N] [--out DIR] [--json]
 *   driver.cache clear          --env E --driver D --yes [--confirm E] [--tree DIR]
 *   driver.migrate              --env E --driver D --xds FILE --yes [--confirm E] [--tree DIR]
 *   driver.resync               --env E --driver D [--since ISO] --yes [--confirm E] [--tree DIR]
 *   driver.secrets list|set|remove --env E --driver D [--name X] [--stdin] [--yes] [--confirm E] [--tree DIR]
 *   driver.trace show|set|reset --env E --driver D [--level N] [--file F] [--yes] [--confirm E] [--tree DIR]
 *   engine.version              --env E
 *   engine.stats                --env E [--driver D…] [--json]
 * </pre>
 * Not here: {@code driver.trace tail} and {@code driver.submit} (built separately).
 */
public final class OperateCli {

    private OperateCli() {
    }

    public static int run(String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            return 2;
        }
        String cmd = argv[0];
        String sub = null;
        int optStart = 1;
        if (cmd.equals("driver.cache") || cmd.equals("driver.secrets") || cmd.equals("driver.trace")) {
            if (argv.length < 2 || argv[1].startsWith("--")) {
                System.err.println("usage: " + cmd + " <subcommand> --env <name> …");
                return 2;
            }
            sub = argv[1];
            optStart = 2;
        }

        Map<String, List<String>> opts = new LinkedHashMap<>();
        for (int i = optStart; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = "";
                if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    value = argv[++i];
                }
                opts.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }

        boolean json = opts.containsKey("json");
        String envName = first(opts, "env");
        if (envName == null) {
            System.err.println("--env <name> is required (environments.properties; see docs/vault-deploy.md)");
            return 2;
        }
        Environments.Environment env = Environments.load().get(envName);
        Path tree = Paths.get(opts.containsKey("tree") ? first(opts, "tree") : ".");
        boolean yes = opts.containsKey("yes");
        String confirm = first(opts, "confirm");
        String driver = first(opts, "driver");
        List<String> drivers = opts.getOrDefault("driver", List.of());

        try (Vault vault = Vault.connect(env.vaultConfig())) {
            Operate.Engine engine = Operate.vaultEngine(vault);
            Operate.Result result;

            switch (cmd) {
                case "driverset.status":
                    result = Operate.driversetStatus(engine, env);
                    break;

                case "driver.status": {
                    if (driver == null) {
                        System.err.println("usage: driver.status --env E --driver D [--json] [--tree DIR]");
                        return 2;
                    }
                    result = Operate.driverStatus(engine, env, driver, tree);
                    break;
                }

                case "driver.start":
                case "driver.stop":
                case "driver.restart": {
                    if (driver == null) {
                        System.err.println("usage: " + cmd + " --env E --driver D [--wait N] [--yes] [--confirm E]");
                        return 2;
                    }
                    int wait = opts.containsKey("wait") ? Integer.parseInt(first(opts, "wait")) : 180;
                    String action = cmd.substring("driver.".length());
                    result = Operate.lifecycle(engine, env, driver, action, wait, yes, confirm, tree);
                    break;
                }

                case "driver.cache": {
                    if (driver == null) {
                        System.err.println("usage: driver.cache view|clear --env E --driver D …");
                        return 2;
                    }
                    if ("view".equals(sub)) {
                        int count = opts.containsKey("count") ? Integer.parseInt(first(opts, "count")) : 100;
                        Path out = opts.containsKey("out") ? Paths.get(first(opts, "out")) : null;
                        result = Operate.cacheView(engine, env, driver, count, out);
                    } else if ("clear".equals(sub)) {
                        result = Operate.cacheClear(engine, env, driver, yes, confirm, tree);
                    } else {
                        System.err.println("usage: driver.cache view|clear --env E --driver D …");
                        return 2;
                    }
                    break;
                }

                case "driver.migrate": {
                    if (driver == null || first(opts, "xds") == null) {
                        System.err.println("usage: driver.migrate --env E --driver D --xds <file> --yes");
                        return 2;
                    }
                    byte[] xds = Files.readAllBytes(Paths.get(first(opts, "xds")));
                    result = Operate.migrate(engine, env, driver, xds, yes, confirm, tree);
                    break;
                }

                case "driver.resync": {
                    if (driver == null) {
                        System.err.println("usage: driver.resync --env E --driver D [--since <ISO-8601 instant>] --yes");
                        return 2;
                    }
                    Long since = opts.containsKey("since") ? Instant.parse(first(opts, "since")).toEpochMilli() : null;
                    result = Operate.resync(engine, env, driver, since, yes, confirm, tree);
                    break;
                }

                case "driver.secrets": {
                    if (driver == null) {
                        System.err.println("usage: driver.secrets list|set|remove --env E --driver D [--name X]");
                        return 2;
                    }
                    String name = first(opts, "name");
                    if ("list".equals(sub)) {
                        result = Operate.secretsList(engine, env, driver);
                    } else if ("set".equals(sub)) {
                        if (name == null) {
                            System.err.println("usage: driver.secrets set --env E --driver D --name X [--stdin]");
                            return 2;
                        }
                        Secrets secrets = env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile);
                        result = Operate.secretsSet(engine, env, driver, name, secrets, opts.containsKey("stdin"), yes, confirm, tree);
                    } else if ("remove".equals(sub)) {
                        if (name == null) {
                            System.err.println("usage: driver.secrets remove --env E --driver D --name X");
                            return 2;
                        }
                        result = Operate.secretsRemove(engine, env, driver, name, yes, confirm, tree);
                    } else {
                        System.err.println("usage: driver.secrets list|set|remove --env E --driver D [--name X]");
                        return 2;
                    }
                    break;
                }

                case "driver.trace": {
                    if (driver == null) {
                        System.err.println("usage: driver.trace show|set|reset --env E --driver D [--level N] [--file F]");
                        return 2;
                    }
                    if ("show".equals(sub)) {
                        result = Operate.traceShow(engine, env, driver);
                    } else if ("set".equals(sub)) {
                        Integer level = opts.containsKey("level") ? Integer.valueOf(first(opts, "level")) : null;
                        String file = first(opts, "file");
                        result = Operate.traceSet(engine, env, driver, level, file, yes, confirm, tree);
                    } else if ("reset".equals(sub)) {
                        result = Operate.traceReset(engine, env, driver, yes, confirm, tree);
                    } else if ("tail".equals(sub)) {
                        return traceTail(engine, env, driver, opts, json);
                    } else {
                        System.err.println("usage: driver.trace show|set|reset|tail --env E --driver D [--level N] [--file F] [--lines N] [--grep RE] [--since MIN] [--follow]");
                        return 2;
                    }
                    break;
                }

                case "driver.submit": {
                    String xdsFile = first(opts, "xds");
                    if (driver == null || xdsFile == null) {
                        System.err.println("usage: driver.submit --env E --driver D --xds <file> --yes [--confirm E] [--tree DIR] [--json]");
                        return 2;
                    }
                    String gate = Operate.gate(env, Operate.OpClass.HEAVY, yes, confirm);
                    if (gate != null) {
                        System.err.println("REFUSED — " + gate);
                        return 1;
                    }
                    String xds = java.nio.file.Files.readString(Paths.get(xdsFile), java.nio.charset.StandardCharsets.UTF_8);
                    Path simTree = opts.containsKey("tree") ? tree : null;
                    Submit.Outcome o = Submit.run(vault, env, driver,
                        com.pointblue.dirxml.dev.deploy.VaultMapping.driverDn(env.driverSetDn, driver), xds, simTree);
                    com.pointblue.dirxml.dev.deploy.DeployLog.Record rec = com.pointblue.dirxml.dev.deploy.DeployLog.record(env.name, "operate");
                    rec.outcome = "ok";
                    rec.detail = "driver.submit '" + driver + "': SubmitCommand from " + xdsFile
                        + (o.matches == null ? "" : o.matches ? " — canary MATCH" : " — canary MISMATCH");
                    com.pointblue.dirxml.dev.deploy.DeployLog.append(tree, rec);
                    System.out.print(o.text());
                    return o.matches != null && !o.matches ? 1 : 0;
                }

                case "engine.version":
                    result = Operate.engineVersion(engine);
                    break;

                case "engine.stats":
                    result = Operate.engineStats(engine, env, drivers);
                    break;

                default:
                    usage();
                    return 2;
            }

            System.out.print(json ? result.json() + "\n" : result.text());
            return result.ok ? 0 : 1;
        }
    }

    /** {@code driver.trace tail}: the driver's trace file on the engine host over SSH. */
    private static int traceTail(Operate.Engine engine, Environments.Environment env, String driver,
                                 Map<String, List<String>> opts, boolean json) throws Exception {
        if (env.sshHost == null) {
            System.err.println("environment '" + env.name + "' has no sshHost; add " + env.name + ".sshHost / .sshUser to tail traces");
            return 2;
        }
        com.pointblue.dirxml.dev.deploy.Vault.Entry d = engine.read(
            com.pointblue.dirxml.dev.deploy.VaultMapping.driverDn(env.driverSetDn, driver));
        String file = d == null ? null : d.string(com.pointblue.dirxml.dev.deploy.Vault.TRACE_FILE);
        if (file == null || file.isBlank()) {
            System.err.println("driver '" + driver + "' has no DirXML-TraceFile; set one with driver.trace set --file");
            return 1;
        }
        int lines = opts.containsKey("lines") ? Integer.parseInt(first(opts, "lines")) : 50;
        String grep = first(opts, "grep");
        TraceTail tail = new TraceTail(env.sshUser, env.sshHost);
        if (opts.containsKey("follow")) {
            Process p = tail.follow(file, lines, grep, System.out::println);
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            p.waitFor();
            return 0;
        }
        List<String> out = opts.containsKey("since")
            ? tail.since(file, Integer.parseInt(first(opts, "since")), grep)
            : tail.tail(file, lines, grep);
        if (json) {
            StringBuilder sb = new StringBuilder("{\"file\":\"" + file.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"lines\":[");
            for (int i = 0; i < out.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(com.pointblue.dirxml.dev.deploy.DeployLog.q(out.get(i)));
            }
            System.out.println(sb.append("]}"));
        } else {
            for (String line : out) {
                System.out.println(line);
            }
        }
        return 0;
    }

    private static String first(Map<String, List<String>> opts, String key) {
        List<String> v = opts.get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    private static void usage() {
        System.err.println("usage:");
        System.err.println("  driverset.status --env E [--json]");
        System.err.println("  driver.status --env E --driver D [--json] [--tree DIR]");
        System.err.println("  driver.start|stop|restart --env E --driver D [--wait N] [--yes] [--confirm E]");
        System.err.println("  driver.cache view --env E --driver D [--count N] [--out DIR] [--json]");
        System.err.println("  driver.cache clear --env E --driver D --yes [--confirm E]");
        System.err.println("  driver.migrate --env E --driver D --xds FILE --yes [--confirm E]");
        System.err.println("  driver.resync --env E --driver D [--since ISO] --yes [--confirm E]");
        System.err.println("  driver.secrets list|set|remove --env E --driver D [--name X] [--stdin]");
        System.err.println("  driver.trace show|set|reset|tail --env E --driver D [--level N] [--file F] [--lines N] [--grep RE] [--since MIN] [--follow]");
        System.err.println("  driver.submit --env E --driver D --xds <file> --yes [--tree DIR]   SubmitCommand; with --tree, the simulator canary");
        System.err.println("  engine.version --env E");
        System.err.println("  engine.stats --env E [--driver D…] [--json]");
    }
}
