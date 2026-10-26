package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.json.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code tree.compare} and {@code promote.*} (docs/promotion.md). Exit 0 when done (or, for {@code tree.compare},
 * when nothing is open: every difference known or unchanged since the record), 1 when refused (or differences are
 * open), 2 on a usage error.
 */
public final class PromoteCli {

    private PromoteCli() {
    }

    public static boolean handles(String command) {
        return command.equals("tree.compare") || command.startsWith("promote.");
    }

    static final String USAGE =
        "usage: tree.compare <target tree> --with <source tree> [--name <pair>] [--json]\n"
        + "       promote.plan  <target tree> --from <source tree> [--name <pair>] [--object <path>]… [--value <key>]… [--pending] [--contents <file.json>] [--json]\n"
        + "       promote.apply <target tree> --from <source tree> [--name <pair>] [--object <path>]… [--value <key>]… [--pending] [--contents <file.json>] [--force] [--by <who>] [--json]\n"
        + "       promote.pair  <target tree> --from <source tree> [--name <pair>] [--dry-run] [--force] [--by <who>] [--json]\n"
        + "       promote.accept <target tree> --from <source tree> [--name <pair>] --object <path or key> [--decision intended|later] [--note <text>] [--by <who>] [--dry-run] [--json]\n"
        + "       promote.forget <target tree> (--from <source tree> | --name <pair>) --object <path or key> [--dry-run] [--json]\n";

    public static int run(String[] argv) throws Exception {
        String cmd = argv[0];
        Path target = null;
        Path source = null;
        String name = null;
        boolean json = false;
        boolean dryRun = false;
        boolean force = false;
        String by = null;
        String decision = null;
        String note = null;
        Promote.Selection sel = new Promote.Selection();
        for (int i = 1; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--json": json = true; continue;
                case "--dry-run": dryRun = true; continue;
                case "--force": force = true; continue;
                case "--pending": sel.pending = true; continue;
                default:
            }
            if (!a.startsWith("--")) {
                if (target != null) {
                    return usage("unexpected argument '" + a + "'");
                }
                target = Paths.get(a);
                continue;
            }
            if (i + 1 >= argv.length) {
                return usage(a + " needs a value");
            }
            String v = argv[++i];
            switch (a) {
                case "--with", "--from" -> source = Paths.get(v);
                case "--name" -> name = v;
                case "--object" -> sel.objects.add(v);
                case "--value" -> sel.values.add(v);
                case "--by" -> by = v;
                case "--decision" -> decision = v;
                case "--note" -> note = v;
                case "--contents" -> {
                    for (Map.Entry<String, Object> e : Json.asMap(Json.parse(Files.readString(Paths.get(v), StandardCharsets.UTF_8))).entrySet()) {
                        sel.contents.put(e.getKey(), Json.asString(e.getValue()));
                    }
                }
                default -> {
                    return usage("unknown option " + a);
                }
            }
        }
        if (target == null) {
            return usage("the target tree is required");
        }
        if (source == null && !cmd.equals("promote.forget")) {
            return usage(cmd.equals("tree.compare") ? "--with <source tree> is required" : "--from <source tree> is required");
        }
        if (name == null) {
            if (source == null) {
                return usage("--name <pair> or --from <source tree> is required");
            }
            name = Pair.nameOf(source);
        }
        if (!Pair.validName(name)) {
            return usage("a pair's name is letters, digits, '.', '_' and '-' (got '" + name + "'): give one with --name");
        }
        switch (cmd) {
            case "tree.compare": {
                ProjectCompare c = ProjectCompare.of(target, source, name);
                System.out.print(json ? c.json() + "\n" : c.text());
                return c.hasOpen() ? 1 : 0;
            }
            case "promote.plan":
            case "promote.apply": {
                Promote.Result r = Promote.run(target, source, name, sel, cmd.equals("promote.plan") || dryRun, force, by);
                return print(r, json);
            }
            case "promote.pair":
                return print(Promote.pair(target, source, name, dryRun, force, by), json);
            case "promote.accept":
            case "promote.forget": {
                List<String> objects = new ArrayList<>(sel.objects);
                if (objects.size() != 1) {
                    return usage("--object <path or key> names the one difference");
                }
                Promote.Result r = cmd.equals("promote.accept")
                    ? Promote.accept(target, source, name, objects.get(0), decision, note, by, dryRun)
                    : Promote.forget(target, name, objects.get(0), dryRun);
                return print(r, json);
            }
            default:
                return usage("unknown command " + cmd);
        }
    }

    private static int print(Promote.Result r, boolean json) {
        System.out.print(json ? r.json() + "\n" : r.text());
        return r.ok() ? 0 : 1;
    }

    private static int usage(String problem) {
        System.err.println(problem);
        System.err.print(USAGE);
        return 2;
    }
}
