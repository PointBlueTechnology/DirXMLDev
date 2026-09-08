package com.pointblue.dirxml.dev.edit;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code idm <op> <tree> [--key value …] [--dry-run] [--force] [--json]} —
 * dispatches a registered operation through a {@link Transaction}. Exit 0 when
 * the operation did what was asked (or would, on a dry run), 1 when refused,
 * 2 on a usage error.
 */
public final class EditCli {

    private EditCli() {
    }

    public static boolean isOperation(String name) {
        return Registry.get(name) != null;
    }

    public static int run(String[] argv) throws Exception {
        Registry.Spec spec = Registry.get(argv[0]);
        if (argv.length < 2) {
            System.err.print(Registry.usage(spec));
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        Map<String, String> args = new LinkedHashMap<>();
        boolean dryRun = false;
        boolean force = false;
        boolean json = false;
        for (int i = 2; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--dry-run": dryRun = true; continue;
                case "--force": force = true; continue;
                case "--json": json = true; continue;
                default:
            }
            if (!a.startsWith("--")) {
                System.err.println("unexpected argument '" + a + "'");
                System.err.print(Registry.usage(spec));
                return 2;
            }
            String key = a.substring(2);
            String value = "";
            if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                value = argv[++i];
            }
            args.merge(key, value, (old, v) -> old + "\n" + v);   // repeatable args
        }
        String missing = Registry.missing(spec, args);
        if (missing != null) {
            System.err.println(missing);
            System.err.print(Registry.usage(spec));
            return 2;
        }
        Operation op;
        try {
            op = spec.factory.create(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        Result r = Transaction.open(tree).run(op, dryRun, force);
        System.out.print(json ? r.json() + "\n" : r.text());
        return r.ok() ? 0 : 1;
    }

    public static String usage() {
        StringBuilder sb = new StringBuilder();
        for (Registry.Spec s : Registry.all()) {
            sb.append(Registry.usage(s));
        }
        return sb.toString();
    }
}
