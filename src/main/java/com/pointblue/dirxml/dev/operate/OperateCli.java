package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.deploy.AgentWriteGate;
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
        if ((cmd.equals("driver.log-level") || cmd.equals("driver.health") || cmd.equals("vault.email-server")) && argv.length >= 2 && !argv[1].startsWith("--")) {
            sub = argv[1];   // optional: "set" / "clear"; without it the command shows
            optStart = 2;
        }
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
        if (cmd.equals("driver.trace") && "view".equals(sub)) {
            return ViewerCli.view(opts, json);   // the viewer opens its own connection; a file needs none
        }
        String envName = first(opts, "env");
        if (envName == null) {
            System.err.println("--env <name> is required (environments.properties; see docs/vault-deploy.md)");
            return 2;
        }
        if (mutatesVault(cmd, sub)) {
            String why = AgentWriteGate.refusal(envName, true, first(opts, "confirm"), System.getenv(AgentWriteGate.ENV));
            if (why != null) {
                Operate.Result refused = Operate.Result.refused(why);
                System.out.print(json ? refused.json() + "\n" : refused.text());
                return 1;
            }
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
                    String direction = opts.containsKey("direction") ? first(opts, "direction") : (opts.containsKey("xds") ? "app" : null);
                    if (driver == null || direction == null || !List.of("app", "vault").contains(direction)
                        || ("app".equals(direction) && first(opts, "xds") == null)) {
                        System.err.println("usage: driver.migrate --env E --driver D --direction app --xds <file> --yes");
                        System.err.println("       driver.migrate --env E --driver D --direction vault --base DN --filter F --class C [--max N] [--dry-run] --yes");
                        return 2;
                    }
                    if ("vault".equals(direction)) {
                        int max = opts.containsKey("max") ? Integer.parseInt(first(opts, "max")) : 500;
                        result = Operate.migrateIntoApp(engine, env, driver, first(opts, "base"), first(opts, "filter"), first(opts, "class"), max, opts.containsKey("dry-run"), yes, confirm, tree);
                        break;
                    }
                    byte[] xds = Files.readAllBytes(Paths.get(first(opts, "xds")));
                    result = Operate.migrate(engine, env, driver, xds, yes, confirm, tree);
                    break;
                }

                case "vault.email-server": {
                    if ("set".equals(sub)) {
                        Map<String, String> values = new LinkedHashMap<>();
                        for (String[] a : Operate.EMAIL_SERVER_ATTRS) {
                            if (opts.containsKey(a[0])) {
                                values.put(a[0], first(opts, a[0]));
                            }
                        }
                        char[] password = null;
                        if (opts.containsKey("stdin")) {
                            String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
                            password = line == null ? null : line.toCharArray();
                        } else if (opts.containsKey("password-key")) {
                            Secrets secrets = env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile);
                            password = secrets.get(first(opts, "password-key"));
                            if (password == null) {
                                System.err.println("no secret '" + first(opts, "password-key") + "' in the environment's secrets file");
                                return 2;
                            }
                        }
                        result = Operate.emailServerSet(engine, env, values, password, yes, confirm, tree);
                    } else {
                        result = Operate.emailServerShow(engine, env);
                    }
                    break;
                }

                case "driver.log-level": {
                    if ("set".equals(sub)) {
                        Integer level = null;
                        if (opts.containsKey("level")) {
                            level = Operate.logLevelValue(first(opts, "level"));
                            if (level < 0) {
                                System.err.println("--level is errors, errors-and-warnings, last-log-time, off or specific-events (or the number)");
                                return 2;
                            }
                        }
                        int[] events = null;
                        if (opts.containsKey("events")) {
                            String[] parts = first(opts, "events").split("[,\\s]+");
                            events = new int[parts.length];
                            for (int i = 0; i < parts.length; i++) {
                                events[i] = Integer.parseInt(parts[i].trim());
                            }
                        }
                        Integer limit = opts.containsKey("limit") ? Integer.valueOf(first(opts, "limit")) : null;
                        Integer type = opts.containsKey("events-type") ? Integer.valueOf(first(opts, "events-type")) : null;
                        result = Operate.logLevelSet(engine, env, driver, level, events, limit, type, opts.containsKey("inherit"), yes, confirm, tree);
                    } else {
                        result = Operate.logLevelShow(engine, env, driver);
                    }
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
                    String kind = opts.containsKey("kind") ? first(opts, "kind") : "named";
                    if ("list".equals(sub)) {
                        result = Operate.secretsList(engine, env, driver);
                    } else if ("set".equals(sub)) {
                        if ("named".equals(kind) && name == null) {
                            System.err.println("usage: driver.secrets set --env E --driver D [--kind named|shim-auth|remote-loader|key|keystore] [--name X] [--stdin]");
                            return 2;
                        }
                        Secrets secrets = env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile);
                        result = Operate.secretsSet(engine, env, driver, kind, name, secrets, opts.containsKey("stdin"), yes, confirm, tree);
                    } else if ("remove".equals(sub)) {
                        if ("named".equals(kind) && name == null) {
                            System.err.println("usage: driver.secrets remove --env E --driver D [--kind named|shim-auth|remote-loader|key|keystore] [--name X]");
                            return 2;
                        }
                        result = Operate.secretsRemove(engine, env, driver, kind, name, yes, confirm, tree);
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
                        System.err.println("usage: driver.trace show|set|reset|tail|view --env E --driver D [--level N] [--file F] [--lines N] [--grep RE] [--since MIN] [--follow] [--ldap [--seconds N] [--engine]]   (view: the desktop viewer, or view --file F)");
                        return 2;
                    }
                    break;
                }

                case "driver.start-option": {
                    String option = first(opts, "option");
                    if (driver == null || option == null) {
                        System.err.println("usage: driver.start-option --env E --driver D --option auto|manual|disabled [--yes] [--confirm E] [--json]");
                        return 2;
                    }
                    result = Operate.startOption(engine, env, driver, option, yes, confirm, tree);
                    break;
                }

                case "driver.associations": {
                    if (driver == null) {
                        System.err.println("usage: driver.associations --env E --driver D [--state processed|disabled|pending|manual|migrate] [--base DN] [--limit N] [--json]");
                        return 2;
                    }
                    int limit = opts.containsKey("limit") ? Integer.parseInt(first(opts, "limit")) : 50;
                    result = Operate.driverAssociations(engine, env, driver, first(opts, "state"), first(opts, "base"), limit);
                    break;
                }

                case "driver.password-sync": {
                    if (driver == null) {
                        System.err.println("usage: driver.password-sync --env E --driver D [--json]");
                        return 2;
                    }
                    result = Operate.passwordSync(engine, env, driver);
                    break;
                }

                case "job.list":
                    result = Operate.jobList(engine, env, driver);
                    break;

                case "rbe.list":
                    result = Operate.rbeList(engine, env);
                    break;

                case "rbe.members": {
                    String policy = first(opts, "policy");
                    if (policy == null) {
                        System.err.println("usage: rbe.members --env E --policy P [--json]");
                        return 2;
                    }
                    result = Operate.rbeMembers(engine, env, policy);
                    break;
                }

                case "job.status": {
                    String job = first(opts, "job");
                    if (job == null) {
                        System.err.println("usage: job.status --env E --job J [--driver D] [--json]");
                        return 2;
                    }
                    result = Operate.jobStatus(engine, env, job, driver);
                    break;
                }

                case "job.start":
                case "job.abort": {
                    String job = first(opts, "job");
                    if (job == null) {
                        System.err.println("usage: " + cmd + " --env E --job J [--driver D] [--yes] [--confirm E] [--json]");
                        return 2;
                    }
                    result = Operate.jobAction(engine, env, job, driver, cmd.substring("job.".length()), yes, confirm, tree);
                    break;
                }

                case "object.inspect": {
                    String dn = first(opts, "dn");
                    if (dn == null) {
                        System.err.println("usage: object.inspect --env E --dn <object DN> [--json]");
                        return 2;
                    }
                    result = Operate.inspectObject(engine, env, dn);
                    break;
                }

                case "driver.query": {
                    if (driver == null) {
                        System.err.println("usage: driver.query --env E --driver D [--class C] [--scope subtree|subordinates|entry] [--dn DN] [--association A] [--search name=value…] [--read-attr A…|none] [--yes] [--confirm E] [--json]");
                        return 2;
                    }
                    Operate.Query q = new Operate.Query();
                    q.className = first(opts, "class");
                    if (opts.containsKey("scope")) {
                        q.scope = first(opts, "scope");
                        if (!List.of("subtree", "subordinates", "entry").contains(q.scope)) {
                            System.err.println("--scope is subtree, subordinates or entry");
                            return 2;
                        }
                    }
                    q.destDn = first(opts, "dn");
                    q.association = first(opts, "association");
                    q.searchAttrs.addAll(opts.getOrDefault("search", List.of()));
                    q.readAttrs.addAll(opts.getOrDefault("read-attr", List.of()));
                    result = Operate.driverQuery(engine, env, driver, q, yes, confirm, tree);
                    break;
                }

                case "driver.health": {
                    if (driver == null) {
                        System.err.println("usage: driver.health [clear] --env E --driver D [--yes] [--confirm E] [--json]");
                        return 2;
                    }
                    result = "clear".equals(sub) ? Operate.driverHealthClear(engine, env, driver, yes, confirm, tree) : Operate.driverHealth(engine, env, driver);
                    break;
                }

                case "driver.submit": {
                    String xdsFile = first(opts, "xds");
                    String mode = opts.containsKey("mode") ? first(opts, "mode") : "command";
                    if (driver == null || xdsFile == null || !List.of("command", "event", "queue").contains(mode)) {
                        System.err.println("usage: driver.submit --env E --driver D --xds <file> [--mode command|event|queue] --yes [--confirm E] [--tree DIR] [--json]");
                        return 2;
                    }
                    String gate = Operate.gate(env, Operate.OpClass.HEAVY, yes, confirm);
                    if (gate != null) {
                        System.err.println("REFUSED — " + gate);
                        return 1;
                    }
                    String xds = java.nio.file.Files.readString(Paths.get(xdsFile), java.nio.charset.StandardCharsets.UTF_8);
                    if (!mode.equals("command")) {
                        // event: the publisher channel of a running driver; queue: into the subscriber cache, even of a stopped driver
                        String driverDn = com.pointblue.dirxml.dev.deploy.VaultMapping.driverDn(env.driverSetDn, driver);
                        byte[] bytes = xds.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        String answer = null;
                        String error = null;
                        try {
                            if (mode.equals("event")) {
                                answer = vault.submitEvent(driverDn, bytes);
                            } else {
                                vault.queueEvent(driverDn, bytes);
                            }
                        } catch (RuntimeException e) {
                            error = e.getMessage();
                        }
                        com.pointblue.dirxml.dev.deploy.DeployLog.Record rec = com.pointblue.dirxml.dev.deploy.DeployLog.record(env.name, "operate");
                        rec.outcome = error == null ? "ok" : "failed";
                        rec.detail = "driver.submit '" + driver + "': " + (mode.equals("event") ? "SubmitEvent" : "QueueEvent") + " from " + xdsFile + (error != null ? " — " + error : "");
                        com.pointblue.dirxml.dev.deploy.DeployLog.append(tree, rec);
                        if (json) {
                            System.out.println("{\"ok\":" + (error == null) + ",\"mode\":\"" + mode + "\"" + (error != null ? ",\"error\":" + Operate.q(error) : "") + (answer != null ? ",\"result\":" + Operate.q(answer) : "") + "}");
                        } else {
                            System.out.print(error == null ? (mode.equals("event") ? "event submitted (" + bytes.length + " bytes)\n" + (answer == null || answer.isBlank() ? "" : answer + "\n") : "event queued into the cache (" + bytes.length + " bytes)\n") : "FAILED   " + error + "\n");
                        }
                        return error == null ? 0 : 1;
                    }
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
        if (opts.containsKey("ldap") || env.sshHost == null) {
            return traceOverLdap(env, driver, opts, json);
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

    /**
     * {@code driver.trace tail --ldap}: the driver's lines out of the engine's DirXML debug events over
     * the environment's own LDAPS connection ({@link LdapTrace}) — the default when the environment names
     * no {@code sshHost}. {@code --follow} streams until Ctrl-C; otherwise {@code --seconds N} (default 30)
     * collects and prints. {@code --grep} filters, {@code --engine} adds the engine-level channel;
     * {@code --lines} and {@code --since} do not apply to a live stream.
     */
    private static int traceOverLdap(Environments.Environment env, String driver, Map<String, List<String>> opts, boolean json) throws Exception {
        String grep = first(opts, "grep");
        boolean engineToo = opts.containsKey("engine");
        long seconds = opts.containsKey("seconds") ? Long.parseLong(first(opts, "seconds")) : 30;
        if (opts.containsKey("follow")) {
            System.err.println("streaming '" + driver + "' trace over LDAP from " + env.url + " until Ctrl-C");
            long n = LdapTrace.stream(env, driver, grep, engineToo, 0, System.out::println);
            System.err.println(n + " line(s)");
            return 0;
        }
        List<String> out = LdapTrace.collect(env, driver, grep, engineToo, seconds);
        if (json) {
            StringBuilder sb = new StringBuilder("{\"source\":\"ldap\",\"seconds\":" + seconds + ",\"lines\":[");
            for (int i = 0; i < out.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(com.pointblue.dirxml.dev.deploy.DeployLog.q(out.get(i)));
            }
            System.out.println(sb.append("]}"));
        } else {
            for (String line : out) {
                System.out.println(line);
            }
            System.err.println(out.size() + " line(s) in " + seconds + "s over LDAP" + (out.isEmpty()
                ? " — a driver at trace level 0 emits only its log events; driver.trace set --level 3 shows the rest" : ""));
        }
        return 0;
    }

    /** Operate commands that change the vault. Read-only status, cache view, secrets list, and trace show/tail do not. */
    static boolean mutatesVault(String cmd, String sub) {
        return AgentWriteGate.mutatesVault(cmd, sub);
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
        System.err.println("  driver.trace show|set|reset|tail|view --env E --driver D [--level N] [--file F] [--lines N] [--grep RE] [--since MIN] [--follow] [--ldap [--seconds N] [--engine]]   (view: the desktop viewer, or view --file F)");
        System.err.println("  vault.email-server [set] --env E [--host H] [--port N] [--from A] [--user U] [--tls true|false] [--timeout N] [--protocol P] [--auth M] [--password-key K|--stdin]   the notification collection's SMTP settings; set writes them");
        System.err.println("  driver.migrate --env E --driver D --direction vault --base DN --filter F --class C [--max N] [--dry-run] --yes   send vault objects into the application (one <sync> per object through the running driver)");
        System.err.println("  driver.log-level [set] --env E [--driver D] [--level errors|errors-and-warnings|last-log-time|off|specific-events] [--events id,…] [--limit N] [--events-type N] [--inherit]   the log level of a driver or the driver set; set writes it live, --inherit makes a driver use the set's");
        System.err.println("  driver.secrets set|remove --env E --driver D [--kind named|shim-auth|remote-loader|key|keystore] [--name X] [--stdin]   one secret live; key and keystore are the Remote Loader's mutual-authentication passwords");
        System.err.println("  driver.query --env E --driver D [--class C] [--scope subtree|subordinates|entry] [--dn DN] [--association A] [--search name=value…] [--read-attr A…|none]   ask the connected system through the running driver (the engine's query verb); the <instance>s it answers");
        System.err.println("  driver.health [clear] --env E --driver D [--yes]   the Driver Health job's last state per server, the health configuration on the driver, the set's health jobs; clear removes the recorded status");
        System.err.println("  driver.submit --env E --driver D --xds <file> [--mode command|event|queue] --yes [--tree DIR]   SubmitCommand (subscriber), SubmitEvent (publisher) or QueueEvent (into the cache); with --tree, the simulator canary");
        System.err.println("  driver.start-option --env E --driver D --option auto|manual|disabled [--yes] [--confirm E]   the start option, live");
        System.err.println("  driver.associations --env E --driver D [--state processed|disabled|pending|manual|migrate] [--base DN] [--limit N] [--json]   the objects associated with the driver");
        System.err.println("  driver.password-sync --env E --driver D [--json]   the driver set's sync timeout and the driver's password settings, live");
        System.err.println("  object.inspect --env E --dn <object DN> [--json]   an object's classes, its associations across drivers, its password-sync status");
        System.err.println("  rbe.list --env E [--json]                          the entitlement policies of the driver set: priority, membership, grants, member count");
        System.err.println("  rbe.members --env E --policy P [--json]            the members the directory computes for one policy (static and dynamic)");
        System.err.println("  job.list --env E [--driver D] [--json]             every job of the driver set (or one driver) with the engine's state");
        System.err.println("  job.status --env E --job J [--driver D] [--json]   one job: class, servers, running, configuration, scheduled, next run");
        System.err.println("  job.start|abort --env E --job J [--driver D] [--yes] [--confirm E]   StartJob / AbortJob, live");
        System.err.println("  engine.version --env E");
        System.err.println("  engine.stats --env E [--driver D…] [--json]");
    }
}
