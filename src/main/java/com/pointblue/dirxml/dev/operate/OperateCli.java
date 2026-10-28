package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.Args;
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
import java.util.Set;

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

    /**
     * What each command takes (docs/commands.md), keyed by the command or, for a command with verbs,
     * "command verb"; a command whose verb is optional has its show form under the command alone.
     * Each entry is { flags, options with a value }.
     */
    private static final Map<String, String[]> TAKES = Map.ofEntries(
        Map.entry("driverset.status", takes("", "env")),
        Map.entry("driverset.activation", takes("", "env")),
        Map.entry("driverset.activation apply", takes("yes", "env file confirm tree")),
        Map.entry("driverset.servers", takes("", "env")),
        Map.entry("driverset.servers add", takes("yes", "env server confirm tree")),
        Map.entry("driverset.servers remove", takes("yes", "env server confirm tree")),
        Map.entry("engine.metrics", takes("", "env base")),
        Map.entry("engine.version", takes("", "env")),
        Map.entry("engine.stats", takes("", "env driver")),
        Map.entry("driver.rights", takes("", "env driver dn attr")),
        Map.entry("workorder.list", takes("", "env base limit")),
        Map.entry("driver.status", takes("", "env driver tree")),
        Map.entry("driver.start", takes("yes", "env driver wait confirm tree")),
        Map.entry("driver.stop", takes("yes", "env driver wait confirm tree")),
        Map.entry("driver.restart", takes("yes", "env driver wait confirm tree")),
        Map.entry("driver.cache view", takes("", "env driver count out")),
        Map.entry("driver.cache clear", takes("yes", "env driver confirm tree")),
        Map.entry("driver.migrate", takes("yes dry-run", "env driver direction xds base filter class max confirm tree")),
        Map.entry("vault.email-server", takes("", "env")),
        Map.entry("vault.email-server set", takes("yes stdin",
            "env host port from user tls timeout protocol auth password-key confirm tree")),
        Map.entry("driver.log-level", takes("", "env driver")),
        Map.entry("driver.log-level set", takes("yes inherit", "env driver level events limit events-type confirm tree")),
        Map.entry("driver.resync", takes("yes", "env driver since confirm tree")),
        Map.entry("driver.secrets list", takes("", "env driver")),
        Map.entry("driver.secrets set", takes("yes stdin", "env driver kind name confirm tree")),
        Map.entry("driver.secrets remove", takes("yes", "env driver kind name confirm tree")),
        Map.entry("driver.trace show", takes("", "env driver")),
        Map.entry("driver.trace set", takes("yes", "env driver level file confirm tree")),
        Map.entry("driver.trace reset", takes("yes", "env driver confirm tree")),
        Map.entry("driver.trace tail", takes("follow ldap engine", "env driver lines since seconds grep")),
        Map.entry("driver.trace view", takes("", "env driver file")),
        Map.entry("trace.view", takes("ldap engine vetoed",
            "file env driver lines since seconds trace-driver channel status text last transaction")),
        Map.entry("driver.start-option", takes("yes", "env driver option confirm tree")),
        Map.entry("driver.associations", takes("", "env driver state base limit")),
        Map.entry("driver.password-sync", takes("", "env driver")),
        Map.entry("job.list", takes("", "env driver")),
        Map.entry("job.status", takes("", "env job driver")),
        Map.entry("job.start", takes("yes", "env job driver confirm tree")),
        Map.entry("job.abort", takes("yes", "env job driver confirm tree")),
        Map.entry("rbe.list", takes("", "env")),
        Map.entry("rbe.members", takes("", "env policy")),
        Map.entry("object.inspect", takes("", "env dn")),
        Map.entry("driver.query", takes("yes", "env driver class scope dn association search read-attr confirm tree")),
        Map.entry("driver.health", takes("", "env driver")),
        Map.entry("driver.health clear", takes("yes", "env driver confirm tree")),
        Map.entry("driver.submit", takes("yes", "env driver xds mode confirm tree")));

    /** The verbs of the commands that have them (the second word). */
    static final Map<String, List<String>> VERBS = Map.of(
        "driver.cache", List.of("view", "clear"),
        "driver.secrets", List.of("list", "set", "remove"),
        "driver.trace", List.of("show", "set", "reset", "tail", "view"),
        "driver.log-level", List.of("set"),
        "driver.health", List.of("clear"),
        "vault.email-server", List.of("set"),
        "driverset.activation", List.of("apply"),
        "driverset.servers", List.of("add", "remove"));

    /** The commands that can't go without a verb; the others show without one. */
    private static final Set<String> VERB_REQUIRED = Set.of("driver.cache", "driver.secrets", "driver.trace");

    /** The options whose value is a whole number, by the same keys as {@link #TAKES}. */
    private static final Map<String, String> NUMBERS = Map.ofEntries(
        Map.entry("workorder.list", "limit"),
        Map.entry("driver.start", "wait"),
        Map.entry("driver.stop", "wait"),
        Map.entry("driver.restart", "wait"),
        Map.entry("driver.cache view", "count"),
        Map.entry("driver.migrate", "max"),
        Map.entry("driver.log-level set", "limit events-type"),
        Map.entry("driver.trace set", "level"),
        Map.entry("driver.trace tail", "lines since seconds"),
        Map.entry("trace.view", "lines since seconds last transaction"),
        Map.entry("driver.associations", "limit"));

    private static String[] takes(String flags, String options) {
        return new String[] { flags, options };
    }

    /** A command line read against what its command (and verb) takes. */
    static final class Parsed {
        final String cmd;
        /** The verb, or null. */
        final String sub;
        final Args args;

        Parsed(String cmd, String sub, Args args) {
            this.cmd = cmd;
            this.sub = sub;
            this.args = args;
        }
    }

    /**
     * Reads {@code argv} against what {@code argv[0]} takes: the verb is the one positional word (it may
     * come after flags), and an unknown verb, a missing required verb or any other positional word is a
     * usage error. Null for a command this CLI doesn't run.
     */
    static Parsed parse(String[] argv) throws Args.UsageError {
        String cmd = argv[0];
        List<String> verbs = VERBS.get(cmd);
        if (verbs == null) {
            String[] t = TAKES.get(cmd);
            if (t == null) {
                return null;
            }
            Args a = Args.parse(cmd, argv, 1, Args.Spec.of(t[0], t[1]));
            if (!a.positional.isEmpty()) {
                throw new Args.UsageError(cmd + " takes no positional argument, not '" + a.positional.get(0) + "'");
            }
            return new Parsed(cmd, null, a);
        }
        // first what any of its verbs takes, to find the verb; then what that verb takes
        StringBuilder flags = new StringBuilder();
        StringBuilder options = new StringBuilder();
        for (String key : keysOf(cmd, verbs)) {
            String[] t = TAKES.get(key);
            flags.append(' ').append(t[0]);
            options.append(' ').append(t[1]);
        }
        Args any = Args.parse(cmd, argv, 1, Args.Spec.of(flags.toString(), options.toString()));
        String verbList = String.join("|", verbs);
        if (any.positional.size() > 1) {
            throw new Args.UsageError(cmd + " takes one verb (" + verbList + "), not '" + String.join(" ", any.positional) + "'");
        }
        String sub = any.positional.isEmpty() ? null : any.positional.get(0);
        if (sub == null && VERB_REQUIRED.contains(cmd)) {
            throw new Args.UsageError("usage: " + cmd + " " + verbList + " --env <name> …");
        }
        if (sub != null && !verbs.contains(sub)) {
            throw new Args.UsageError(cmd + " has no verb '" + sub + "'; it takes " + verbList
                + (VERB_REQUIRED.contains(cmd) ? "" : ", or none to show"));
        }
        String key = sub == null ? cmd : cmd + " " + sub;
        String[] t = TAKES.get(key);
        return new Parsed(cmd, sub, Args.parse(key, argv, 1, Args.Spec.of(t[0], t[1])));
    }

    private static List<String> keysOf(String cmd, List<String> verbs) {
        List<String> keys = new ArrayList<>();
        if (!VERB_REQUIRED.contains(cmd)) {
            keys.add(cmd);
        }
        for (String v : verbs) {
            keys.add(cmd + " " + v);
        }
        return keys;
    }

    /**
     * The values a command line can be refused for without the vault: whole numbers, listed values,
     * an instant. Run before the environment is loaded, so a bad value never opens a connection.
     */
    static void check(Parsed p) throws Args.UsageError {
        Args a = p.args;
        String key = p.sub == null ? p.cmd : p.cmd + " " + p.sub;
        String numbers = NUMBERS.get(key);
        if (numbers != null) {
            for (String n : numbers.split(" ")) {
                if (n.equals("seconds")) {
                    a.longOpt(n, 0);
                } else {
                    a.intOpt(n, 0);
                }
            }
        }
        switch (key) {
            case "driver.migrate": {
                String direction = a.oneOf("direction", a.has("xds") ? "app" : null, List.of("app", "vault"));
                if (a.first("driver") == null || direction == null || ("app".equals(direction) && a.first("xds") == null)) {
                    throw new Args.UsageError("usage: driver.migrate --env E --driver D --direction app --xds <file> --yes\n"
                        + "       driver.migrate --env E --driver D --direction vault --base DN --filter F --class C [--max N] [--dry-run] --yes");
                }
                if ("app".equals(direction) && a.has("dry-run")) {
                    throw new Args.UsageError("driver.migrate --dry-run goes with --direction vault; --direction app has no dry run");
                }
                break;
            }
            case "driver.resync": {
                String since = a.first("since");
                if (since != null) {
                    try {
                        Instant.parse(since);
                    } catch (java.time.format.DateTimeParseException e) {
                        throw new Args.UsageError("--since needs an ISO-8601 instant such as 2026-09-01T00:00:00Z, not '" + since + "'");
                    }
                }
                break;
            }
            case "driver.log-level set": {
                if (a.has("level") && Operate.logLevelValue(a.first("level")) < 0) {
                    throw new Args.UsageError("--level is errors, errors-and-warnings, last-log-time, off or specific-events (or the number)");
                }
                if (a.has("events")) {
                    events(a.first("events"));
                }
                break;
            }
            case "driver.query":
                a.oneOf("scope", null, List.of("subtree", "subordinates", "entry"));
                break;
            case "driver.submit":
                a.oneOf("mode", "command", List.of("command", "event", "queue"));
                break;
            case "trace.view":
                com.pointblue.dirxml.dev.trace.TraceViewCli.check(a);
                break;
            default:
                break;
        }
    }

    /** {@code --events}: event ids, comma- or space-separated. */
    private static int[] events(String value) throws Args.UsageError {
        String[] parts = value.trim().split("[,\\s]+");
        int[] events = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                events[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new Args.UsageError("--events needs event ids (whole numbers), not '" + parts[i] + "'");
            }
        }
        return events;
    }

    public static int run(String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
            return 2;
        }
        Parsed p;
        try {
            p = parse(argv);
            if (p == null) {
                usage();
                return 2;
            }
            check(p);
        } catch (Args.UsageError e) {
            System.err.println(e.getMessage());
            return 2;
        }
        String cmd = p.cmd;
        String sub = p.sub;
        Args a = p.args;
        Map<String, List<String>> opts = a.opts;

        boolean json = opts.containsKey("json");
        if (cmd.equals("driver.trace") && "view".equals(sub)) {
            return ViewerCli.view(opts, json);   // the viewer opens its own connection; a file needs none
        }
        if (cmd.equals("trace.view") && opts.containsKey("file")) {
            return com.pointblue.dirxml.dev.trace.TraceViewCli.file(opts, json);   // a file needs no environment
        }
        String envName = first(opts, "env");
        if (envName == null) {
            System.err.println("--env <name> is required (environments.properties; see docs/vault-deploy.md)");
            return 2;
        }
        if (AgentWriteGate.mutatesVault(cmd, sub, opts.containsKey("dry-run"))) {
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

                case "driverset.activation": {
                    if ("apply".equals(sub)) {
                        String file = first(opts, "file");
                        if (file == null) {
                            System.err.println("usage: driverset.activation apply --env E --file <credential.xml> --yes [--confirm E]");
                            return 2;
                        }
                        result = Operate.driversetActivationApply(engine, env, Files.readAllBytes(Paths.get(file)), file, yes, confirm, tree);
                    } else {
                        result = Operate.driversetActivation(engine, env);
                    }
                    break;
                }

                case "driverset.servers": {
                    if ("add".equals(sub) || "remove".equals(sub)) {
                        String server = first(opts, "server");
                        if (server == null) {
                            System.err.println("usage: driverset.servers add|remove --env E --server <server DN> --yes [--confirm E]");
                            return 2;
                        }
                        String url = com.pointblue.dirxml.dev.deploy.Servers.urlOf(env, vault, server);
                        result = Operate.driversetServerSet(env, server, url, "remove".equals(sub), yes, confirm, tree);
                    } else {
                        result = Operate.driversetServers(engine, env);
                    }
                    break;
                }

                case "engine.metrics":
                    result = Operate.engineMetrics(engine, env, first(opts, "base"));
                    break;

                case "driver.rights": {
                    if (driver == null) {
                        System.err.println("usage: driver.rights --env E --driver D --dn <object> [--attr A…] [--json]");
                        return 2;
                    }
                    result = Operate.driverRights(engine, env, driver, first(opts, "dn"), opts.getOrDefault("attr", List.of()));
                    break;
                }

                case "workorder.list":
                    result = Operate.workOrders(engine, env, first(opts, "base"), a.intOpt("limit", 200));
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
                    int wait = a.intOpt("wait", 180);
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
                        int count = a.intOpt("count", 100);
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
                    String direction = a.oneOf("direction", a.has("xds") ? "app" : null, List.of("app", "vault"));   // checked before the environment
                    if ("vault".equals(direction)) {
                        int max = a.intOpt("max", 500);
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
                        for (String[] attr : Operate.EMAIL_SERVER_ATTRS) {
                            if (opts.containsKey(attr[0])) {
                                values.put(attr[0], first(opts, attr[0]));
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
                            level = Operate.logLevelValue(first(opts, "level"));   // a known level: checked before the environment
                        }
                        int[] events = opts.containsKey("events") ? events(first(opts, "events")) : null;
                        Integer limit = a.has("limit") ? Integer.valueOf(a.intOpt("limit", 0)) : null;
                        Integer type = a.has("events-type") ? Integer.valueOf(a.intOpt("events-type", 0)) : null;
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
                        Integer level = a.has("level") ? Integer.valueOf(a.intOpt("level", 0)) : null;
                        String file = first(opts, "file");
                        result = Operate.traceSet(engine, env, driver, level, file, yes, confirm, tree);
                    } else if ("reset".equals(sub)) {
                        result = Operate.traceReset(engine, env, driver, yes, confirm, tree);
                    } else if ("tail".equals(sub)) {
                        return traceTail(engine, env, driver, a, json);
                    } else {
                        System.err.println("usage: driver.trace show|set|reset|tail|view --env E --driver D [--level N] [--file F] [--lines N] [--grep RE] [--since MIN] [--follow] [--ldap [--seconds N] [--engine]]   (view: the desktop viewer, or view --file F)");
                        return 2;
                    }
                    break;
                }

                case "trace.view": {
                    if (driver == null) {
                        System.err.println(com.pointblue.dirxml.dev.trace.TraceViewCli.USAGE);
                        return 2;
                    }
                    List<String> lines = new ArrayList<>();
                    String source = fetchTrace(engine, env, driver, a, lines);
                    if (source == null) {
                        return 1;
                    }
                    return com.pointblue.dirxml.dev.trace.TraceViewCli.print(source, lines, opts, json, first(opts, "trace-driver"));
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
                    int limit = a.intOpt("limit", 50);
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
                        q.scope = first(opts, "scope");   // one of subtree, subordinates, entry: checked before the environment
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
                    if (driver == null || xdsFile == null) {   // --mode is checked before the environment
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
                    Submit.Outcome o;
                    try {
                        o = Submit.run(vault, env, driver,
                            com.pointblue.dirxml.dev.deploy.VaultMapping.driverDn(env.driverSetDn, driver), xds, simTree);
                    } catch (Submit.NotRunning e) {
                        System.err.println("REFUSED — " + e.getMessage());
                        return 1;
                    }
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
                                 Args opts, boolean json) throws Exception {
        if (opts.has("ldap") || env.sshHost == null) {
            return traceOverLdap(env, driver, opts, json);
        }
        com.pointblue.dirxml.dev.deploy.Vault.Entry d = engine.read(
            com.pointblue.dirxml.dev.deploy.VaultMapping.driverDn(env.driverSetDn, driver));
        String file = d == null ? null : d.string(com.pointblue.dirxml.dev.deploy.Vault.TRACE_FILE);
        if (file == null || file.isBlank()) {
            System.err.println("driver '" + driver + "' has no DirXML-TraceFile; set one with driver.trace set --file");
            return 1;
        }
        int lines = opts.intOpt("lines", 50);
        String grep = opts.first("grep");
        TraceTail tail = new TraceTail(env.sshUser, env.sshHost);
        if (opts.has("follow")) {
            Process p = tail.follow(file, lines, grep, System.out::println);
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            p.waitFor();
            return 0;
        }
        List<String> out = opts.has("since")
            ? tail.since(file, opts.intOpt("since", 0), grep)
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
     * The driver's trace lines for {@code trace.view}, bounded as {@code driver.trace tail} bounds them:
     * over SSH the last {@code --lines} (default 2000) or {@code --since MIN} of its DirXML-TraceFile;
     * over LDAP ({@code --ldap}, or no {@code sshHost}) {@code --seconds} (default 30) of live events.
     * Returns a description of the source, or null (having said why) when there is none.
     */
    private static String fetchTrace(Operate.Engine engine, Environments.Environment env, String driver,
                                     Args opts, List<String> into) throws Exception {
        if (opts.has("ldap") || env.sshHost == null) {
            long seconds = opts.longOpt("seconds", 30);
            into.addAll(LdapTrace.collect(env, driver, null, opts.has("engine"), seconds));
            return "env " + env.name + " driver " + driver + " (" + seconds + "s over LDAP)";
        }
        com.pointblue.dirxml.dev.deploy.Vault.Entry d = engine.read(
            com.pointblue.dirxml.dev.deploy.VaultMapping.driverDn(env.driverSetDn, driver));
        String file = d == null ? null : d.string(com.pointblue.dirxml.dev.deploy.Vault.TRACE_FILE);
        if (file == null || file.isBlank()) {
            System.err.println("driver '" + driver + "' has no DirXML-TraceFile; set one with driver.trace set --file, or use --ldap");
            return null;
        }
        TraceTail tail = new TraceTail(env.sshUser, env.sshHost);
        if (opts.has("since")) {
            into.addAll(tail.since(file, opts.intOpt("since", 0), null));
            return "env " + env.name + " driver " + driver + " " + file + " (last " + opts.first("since") + " min)";
        }
        int lines = opts.intOpt("lines", 2000);
        into.addAll(tail.tail(file, lines, null));
        return "env " + env.name + " driver " + driver + " " + file + " (last " + lines + " lines)";
    }

    /**
     * {@code driver.trace tail --ldap}: the driver's lines out of the engine's DirXML debug events over
     * the environment's own LDAPS connection ({@link LdapTrace}) — the default when the environment names
     * no {@code sshHost}. {@code --follow} streams until Ctrl-C; otherwise {@code --seconds N} (default 30)
     * collects and prints. {@code --grep} filters, {@code --engine} adds the engine-level channel;
     * {@code --lines} and {@code --since} do not apply to a live stream.
     */
    private static int traceOverLdap(Environments.Environment env, String driver, Args opts, boolean json) throws Exception {
        String grep = opts.first("grep");
        boolean engineToo = opts.has("engine");
        long seconds = opts.longOpt("seconds", 30);
        if (opts.has("follow")) {
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
        System.err.println("  driverset.activation [apply --file <credential.xml> --yes] --env E   the driver set's activation as the engine reports it; apply writes a vendor credential");
        System.err.println("  driverset.servers [add|remove --server DN --yes] --env E   the servers of the set and what this connection's server runs; add/remove associate a server (SetDriverSet / ClearDriverSet, at that server)");
        System.err.println("  engine.metrics --env E [--base DN] [--json]        users, users with an association, enabled ones (the licensing count), and each driver's associations by state");
        System.err.println("  driver.rights --env E --driver D --dn <object> [--attr A…]   eDirectory's effective rights of the driver object on an object: entry, all attributes, named attributes");
        System.err.println("  workorder.list --env E [--base DN] [--limit N]    the Work Order driver's DirXML-WorkOrder objects: status, due date, description");
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
