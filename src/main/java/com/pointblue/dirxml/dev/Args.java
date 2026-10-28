package com.pointblue.dirxml.dev;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A command line read against what the command takes (#104, #105): its flags (present or absent)
 * and its options (each with a value). A flag never takes the word after it, so a positional
 * argument after {@code --json} stays positional; {@code --name=value} is split; an option the
 * command doesn't take, a value given to a flag and an option without its value are usage errors
 * (exit 2) that name the problem. {@code --json} is a flag of every command.
 */
public final class Args {

    /** A command line the command can't take: the message says what, the CLI exits 2. */
    public static final class UsageError extends Exception {
        public UsageError(String message) {
            super(message);
        }
    }

    /** What one command takes: its flags and its options with a value. */
    public static final class Spec {
        final Set<String> flags = new LinkedHashSet<>();
        final Set<String> options = new LinkedHashSet<>();

        /** {@code flags} and {@code options} as space-separated names without dashes. */
        public static Spec of(String flags, String options) {
            Spec s = new Spec();
            s.flags.add("json");
            for (String f : words(flags)) {
                s.flags.add(f);
            }
            for (String o : words(options)) {
                if (s.flags.contains(o)) {
                    throw new IllegalArgumentException("'" + o + "' is both a flag and an option");
                }
                s.options.add(o);
            }
            return s;
        }

        /** This spec and another's, for a command whose verbs share options. */
        public Spec and(String flags, String options) {
            Spec s = of(String.join(" ", this.flags) + " " + flags, String.join(" ", this.options) + " " + options);
            return s;
        }

        private static List<String> words(String s) {
            List<String> out = new ArrayList<>();
            if (s != null) {
                for (String w : s.trim().split("\\s+")) {
                    if (!w.isEmpty()) {
                        out.add(w);
                    }
                }
            }
            return out;
        }
    }

    public final List<String> positional = new ArrayList<>();
    /** Each flag or option given, in order; a flag maps to an empty value, a repeated option to each value. */
    public final Map<String, List<String>> opts = new LinkedHashMap<>();

    private Args() {
    }

    /** Reads {@code argv} from {@code from} on against {@code spec}. */
    public static Args parse(String command, String[] argv, int from, Spec spec) throws UsageError {
        Args a = new Args();
        for (int i = from; i < argv.length; i++) {
            String w = argv[i];
            if (!w.startsWith("--") || w.equals("--")) {
                a.positional.add(w);
                continue;
            }
            String name = w.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (spec.flags.contains(name)) {
                if (value != null) {
                    throw new UsageError("--" + name + " takes no value (" + command + ")");
                }
                a.opts.computeIfAbsent(name, k -> new ArrayList<>()).add("");
            } else if (spec.options.contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                        throw new UsageError("--" + name + " needs a value (" + command + ")");
                    }
                    value = argv[++i];
                }
                a.opts.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            } else {
                throw new UsageError(command + " has no option --" + name + "; it takes " + known(spec));
            }
        }
        return a;
    }

    private static String known(Spec spec) {
        List<String> all = new ArrayList<>();
        for (String o : spec.options) {
            all.add("--" + o);
        }
        for (String f : spec.flags) {
            all.add("--" + f);
        }
        return all.isEmpty() ? "none" : String.join(" ", all);
    }

    public boolean has(String name) {
        return opts.containsKey(name);
    }

    /** The first value of an option, or null. */
    public String first(String name) {
        List<String> v = opts.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    public List<String> all(String name) {
        return opts.getOrDefault(name, List.of());
    }

    /** An option's whole number, or {@code dflt} when it isn't given; anything else is a usage error. */
    public int intOpt(String name, int dflt) throws UsageError {
        String v = first(name);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new UsageError("--" + name + " needs a whole number, not '" + v + "'");
        }
    }

    public long longOpt(String name, long dflt) throws UsageError {
        String v = first(name);
        if (v == null) {
            return dflt;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new UsageError("--" + name + " needs a whole number, not '" + v + "'");
        }
    }

    /** One of {@code allowed}, or {@code dflt} when it isn't given; anything else is a usage error. */
    public String oneOf(String name, String dflt, Collection<String> allowed) throws UsageError {
        String v = first(name);
        if (v == null) {
            return dflt;
        }
        if (!allowed.contains(v)) {
            throw new UsageError("--" + name + " is " + String.join(", ", allowed) + ", not '" + v + "'");
        }
        return v;
    }
}
