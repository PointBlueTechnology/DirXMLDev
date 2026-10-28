package com.pointblue.dirxml.dev.promote;

import com.pointblue.dirxml.dev.Args;
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
        + "       promote.apply <target tree> --from <source tree> [--name <pair>] [--object <path>]… [--value <key>]… [--pending] [--contents <file.json>] [--force] [--by <who>] [--dry-run] [--json]\n"
        + "       promote.map   <target tree> --from <source tree> [--name <pair>]   (prints a proposed map; writes nothing)\n"
        + "       promote.pair  <target tree> --from <source tree> [--name <pair>] [--propose-map] [--pending <path or key>]… [--accept <path or key>]… [--dry-run] [--force] [--by <who>] [--json]\n"
        + "       promote.accept <target tree> --from <source tree> [--name <pair>] --object <path or key> [--decision intended|later] [--note <text>] [--by <who>] [--dry-run] [--json]\n"
        + "       promote.forget <target tree> (--from <source tree> | --name <pair>) --object <path or key> [--dry-run] [--json]\n"
        + "  each takes [--cases <target's cases>] [--source-cases <source's cases>]; by default <tree>/cases or cases/ beside a tree/ directory\n";

    /**
     * What each command takes (docs/commands.md); {@code --with} and {@code --from} are one option, and every
     * command takes the two sides' cases. {@code --pending} is a flag of promote.plan/apply and an option
     * (a difference to leave pending) of promote.pair.
     */
    static final Map<String, Args.Spec> SPECS = Map.ofEntries(
        Map.entry("tree.compare", Args.Spec.of("", "with from name cases source-cases")),
        Map.entry("promote.plan", Args.Spec.of("pending", "with from name object value contents cases source-cases")),
        Map.entry("promote.apply", Args.Spec.of("pending force dry-run", "with from name object value contents by cases source-cases")),
        Map.entry("promote.map", Args.Spec.of("", "with from name cases source-cases")),
        Map.entry("promote.pair", Args.Spec.of("propose-map dry-run force", "with from name pending accept by cases source-cases")),
        Map.entry("promote.accept", Args.Spec.of("dry-run", "with from name object decision note by cases source-cases")),
        Map.entry("promote.forget", Args.Spec.of("dry-run", "with from name object cases source-cases")));

    public static int run(String[] argv) throws Exception {
        String cmd = argv[0];
        Args.Spec spec = SPECS.get(cmd);
        if (spec == null) {
            return usage("unknown command " + cmd);
        }
        Args a;
        try {
            a = Args.parse(cmd, argv, 1, spec);
        } catch (Args.UsageError e) {
            return usage(e.getMessage());
        }
        if (a.positional.size() > 1) {
            return usage("unexpected argument '" + a.positional.get(1) + "'");
        }
        Path target = a.positional.isEmpty() ? null : Paths.get(a.positional.get(0));
        String src = a.first("from") != null ? a.first("from") : a.first("with");
        Path source = src == null ? null : Paths.get(src);
        String name = a.first("name");
        boolean json = a.has("json");
        boolean dryRun = a.has("dry-run");
        boolean force = a.has("force");
        String by = a.first("by");
        String decision = a.first("decision");
        String note = a.first("note");
        Promote.Selection sel = new Promote.Selection();
        Promote.Pairing pairing = new Promote.Pairing();
        sel.objects.addAll(a.all("object"));
        sel.values.addAll(a.all("value"));
        if (cmd.equals("promote.pair")) {
            pairing.pending.addAll(a.all("pending"));
        } else {
            sel.pending = a.has("pending");
        }
        for (String v : a.all("accept")) {
            pairing.known.put(v, new String[] {"intended", null});
        }
        boolean proposeMap = a.has("propose-map");
        Path cases = a.first("cases") == null ? null : Paths.get(a.first("cases"));
        Path sourceCases = a.first("source-cases") == null ? null : Paths.get(a.first("source-cases"));
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
        for (String v : a.all("contents")) {
            for (Map.Entry<String, Object> e : Json.asMap(Json.parse(Files.readString(Paths.get(v), StandardCharsets.UTF_8))).entrySet()) {
                sel.contents.put(e.getKey(), Json.asString(e.getValue()));
            }
        }
        Projects projects = Projects.of(target, source, name);
        if (source != null) {
            Path tc = cases != null ? cases : Projects.casesOf(target);
            Path sc = sourceCases != null ? sourceCases : Projects.casesOf(source);
            if (tc != null && sc != null) {
                projects = projects.withCases(tc, sc);
            }
        }
        switch (cmd) {
            case "promote.map": {
                System.out.print(Pair.formatMap(ProjectCompare.proposedMap(projects)));
                return 0;
            }
            case "tree.compare": {
                ProjectCompare c = ProjectCompare.of(projects);
                System.out.print(json ? c.json() + "\n" : c.text());
                return c.hasOpen() ? 1 : 0;
            }
            case "promote.plan":
            case "promote.apply": {
                Promote.Result r = Promote.run(projects, sel, cmd.equals("promote.plan") || dryRun, force, by);
                return print(r, json);
            }
            case "promote.pair":
                if (proposeMap) {
                    pairing.map = ProjectCompare.proposedMap(projects);
                }
                return print(Promote.pair(projects, pairing, dryRun, force, by), json);
            case "promote.accept":
            case "promote.forget": {
                List<String> objects = new ArrayList<>(sel.objects);
                if (objects.size() != 1) {
                    return usage("--object <path or key> names the one difference");
                }
                Map<String, String[]> one = new java.util.LinkedHashMap<>();
                one.put(objects.get(0), new String[] {decision, note});
                Promote.Result r = cmd.equals("promote.accept")
                    ? Promote.accept(projects, one, by, dryRun)
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
