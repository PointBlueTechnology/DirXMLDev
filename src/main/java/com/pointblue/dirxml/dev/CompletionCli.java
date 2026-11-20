package com.pointblue.dirxml.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.pointblue.dirxml.dev.deploy.DeployCli;
import com.pointblue.dirxml.dev.edit.ReadCli;
import com.pointblue.dirxml.dev.edit.Registry;
import com.pointblue.dirxml.dev.events.EventsCli;
import com.pointblue.dirxml.dev.operate.OperateCli;
import com.pointblue.dirxml.dev.operate.ViewerCli;
import com.pointblue.dirxml.dev.packages.PackageCli;
import com.pointblue.dirxml.dev.promote.PromoteCli;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates standalone shell-completion scripts from the CLI's current usage. */
public final class CompletionCli {
    private static final String COMMANDS_START = "# BEGIN IDM COMMANDS";
    private static final String COMMANDS_END = "# END IDM COMMANDS";
    private static final String OPTIONS_START = "# BEGIN IDM OPTIONS";
    private static final String OPTIONS_END = "# END IDM OPTIONS";
    private static final String SUBCOMMANDS_START = "# BEGIN IDM SUBCOMMANDS";
    private static final String SUBCOMMANDS_END = "# END IDM SUBCOMMANDS";
    private static final Pattern SYNOPSIS = Pattern.compile(
        "^  ([a-z][a-z0-9.-]*(?:\\|[a-z][a-z0-9.-]*)*)(?:\\s+(.*))?$");
    private static final Pattern OPTION = Pattern.compile("--[a-z][a-z0-9-]*");
    private static final Pattern QUERY_VERB = Pattern.compile("(?:^|\\|)\\s*([a-z][a-z0-9-]*)\\b");
    private static final Pattern COMMAND_NAME = Pattern.compile("[a-z][a-z0-9.-]*");
    private static final Set<String> QUERY_VALUES = Set.of("sub", "pub");

    private CompletionCli() {
    }

    static int run(String[] args) throws IOException {
        if (args.length != 2 || !args[1].equals("bash")) {
            System.err.println("usage: completion bash");
            return 2;
        }
        Path home = Path.of(System.getProperty("idm.home", ".")).toAbsolutePath().normalize();
        System.out.print(generate(home));
        return 0;
    }

    static String generate(Path home) throws IOException {
        String template = Files.readString(home.resolve("completions/idm.bash"), StandardCharsets.UTF_8);
        CompletionData data = parse(Cli.usageText());
        overlayStructuredSpecs(data);
        template = replaceBlock(template, COMMANDS_START, COMMANDS_END, renderCommands(data));
        template = replaceBlock(template, OPTIONS_START, OPTIONS_END, renderOptions(data));
        return replaceBlock(template, SUBCOMMANDS_START, SUBCOMMANDS_END, renderSubcommands(data));
    }

    private static CompletionData parse(String usage) {
        CompletionData data = new CompletionData();
        List<String> currentCommands = List.of();
        for (String line : usage.split("\\R")) {
            Matcher synopsis = SYNOPSIS.matcher(line);
            if (synopsis.matches()) {
                currentCommands = expandCommands(synopsis.group(1));
                for (String command : currentCommands) {
                    data.commands.add(command);
                    data.options.computeIfAbsent(command, ignored -> new TreeSet<>());
                }
                addOptions(data, currentCommands, line);
                addSubcommands(data, currentCommands, synopsis.group(2));
            } else if (!currentCommands.isEmpty()
                && line.stripLeading().startsWith("[")
                && line.contains("--")) {
                addOptions(data, currentCommands, line);
            } else {
                currentCommands = List.of();
            }
        }
        return data;
    }

    /**
     * The parsers' own specs are what each command really accepts, so they replace whatever the usage text
     * suggested. The usage text stays only for commands whose spec is not exposed (and for command names).
     */
    private static void overlayStructuredSpecs(CompletionData data) {
        List<Map<String, Args.Spec>> sources = List.of(Cli.SPECS, DeployCli.SPECS, ReadCli.SPECS, PackageCli.SPECS,
            PromoteCli.SPECS, ViewerCli.SPECS, EventsCli.SPECS);
        for (Map<String, Args.Spec> source : sources) {
            source.forEach((command, spec) -> setOptions(data, command, spec.flags(), spec.options()));
        }
        for (Args.Spec spec : EventsCli.QUERY_SPECS.values()) {
            addAll(data, "query", spec.flags(), spec.options());
        }

        for (Registry.Spec operation : Registry.all()) {
            TreeSet<String> names = new TreeSet<>(List.of("dry-run", "force", "json"));
            operation.args.forEach(arg -> names.add(arg.name));
            data.commands.add(operation.name);
            data.options.put(operation.name, prefixed(names));
        }

        // an operate command's verbs take different options; completing before the verb offers them all
        Map<String, TreeSet<String>> operate = new TreeMap<>();
        OperateCli.TAKES.forEach((key, takes) -> {
            String command = key.split(" ", 2)[0];
            TreeSet<String> names = operate.computeIfAbsent(command, ignored -> new TreeSet<>());
            names.addAll(prefixed(words(takes[0])));
            names.addAll(prefixed(words(takes[1])));
            names.add("--json");
        });
        operate.forEach((command, names) -> {
            data.commands.add(command);
            data.options.put(command, names);
        });
        OperateCli.VERBS.forEach((command, verbs) -> {
            data.subcommands.put(command, new TreeSet<>(verbs));
            data.subcommandWordIndex.put(command, 2);
        });
    }

    private static void setOptions(CompletionData data, String command, Set<String> flags, Set<String> options) {
        data.commands.add(command);
        data.options.put(command, new TreeSet<>());
        addAll(data, command, flags, options);
    }

    private static void addAll(CompletionData data, String command, Set<String> flags, Set<String> options) {
        TreeSet<String> names = data.options.computeIfAbsent(command, ignored -> new TreeSet<>());
        names.addAll(prefixed(flags));
        names.addAll(prefixed(options));
    }

    private static TreeSet<String> prefixed(Iterable<String> names) {
        TreeSet<String> out = new TreeSet<>();
        for (String name : names) {
            out.add("--" + name);
        }
        return out;
    }

    private static List<String> words(String names) {
        return names.isBlank() ? List.of() : List.of(names.trim().split("\\s+"));
    }

    private static List<String> expandCommands(String names) {
        List<String> expanded = new ArrayList<>();
        String prefix = "";
        for (String part : names.split("\\|")) {
            if (part.contains(".")) {
                prefix = part.substring(0, part.lastIndexOf('.') + 1);
                expanded.add(part);
            } else {
                expanded.add(prefix + part);
            }
        }
        return expanded;
    }

    private static void addOptions(CompletionData data, List<String> commands, String syntax) {
        Matcher options = OPTION.matcher(syntax);
        while (options.find()) {
            String option = options.group();
            for (String command : commands) {
                data.options.computeIfAbsent(command, ignored -> new TreeSet<>()).add(option);
            }
        }
    }

    private static void addSubcommands(CompletionData data, List<String> commands, String remainder) {
        if (remainder == null) {
            return;
        }
        for (String command : commands) {
            String syntax = remainder.stripLeading();
            if (command.equals("query")) {
                Matcher firstArgument = Pattern.compile("^<[^>]+>\\s+").matcher(syntax);
                if (!firstArgument.find()) {
                    continue;
                }
                Matcher verbs = QUERY_VERB.matcher(syntax.substring(firstArgument.end()));
                while (verbs.find()) {
                    String verb = verbs.group(1);
                    if (!QUERY_VALUES.contains(verb)) {
                        data.subcommands.computeIfAbsent(command, ignored -> new TreeSet<>()).add(verb);
                        data.subcommandWordIndex.put(command, 3);
                    }
                }
                continue;
            }

            if (syntax.startsWith("[")) {
                int close = syntax.indexOf(']');
                if (close > 1 && !syntax.substring(1, close).startsWith("--")) {
                    addSubcommandAlternatives(data, command, syntax.substring(1, close));
                }
            } else if (!syntax.startsWith("<") && !syntax.startsWith("--")) {
                String first = syntax.split("\\s+", 2)[0];
                addSubcommandAlternatives(data, command, first);
            }
        }
    }

    private static void addSubcommandAlternatives(CompletionData data, String command, String alternatives) {
        for (String subcommand : alternatives.split("\\|")) {
            if (COMMAND_NAME.matcher(subcommand).matches()) {
                data.subcommands.computeIfAbsent(command, ignored -> new TreeSet<>()).add(subcommand);
                data.subcommandWordIndex.putIfAbsent(command, 2);
            }
        }
    }

    private static String renderCommands(CompletionData data) throws IOException {
        if (data.commands.isEmpty()) {
            throw new IOException("no commands found in CLI usage");
        }
        StringBuilder script = new StringBuilder("_idm_completion_commands=(\n");
        for (String command : data.commands) {
            script.append("    '").append(command).append("'\n");
        }
        return script.append(")\n").toString();
    }

    private static String renderOptions(CompletionData data) {
        StringBuilder script = new StringBuilder();
        for (Map.Entry<String, TreeSet<String>> entry : data.options.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            script.append("        ").append(entry.getKey()).append(")\n")
                .append("            printf '%s\\n' '")
                .append(String.join(" ", entry.getValue()))
                .append("' ;;\n");
        }
        return script.toString();
    }

    private static String renderSubcommands(CompletionData data) {
        StringBuilder script = new StringBuilder();
        for (Map.Entry<String, TreeSet<String>> entry : data.subcommands.entrySet()) {
            script.append("        ").append(entry.getKey()).append(")\n")
                .append("            (( COMP_CWORD == ")
                .append(data.subcommandWordIndex.get(entry.getKey()))
                .append(" )) || return 1\n")
                .append("            printf '%s\\n' '")
                .append(String.join(" ", entry.getValue()))
                .append("' ;;\n");
        }
        return script.toString();
    }

    private static String replaceBlock(String template, String startMarker, String endMarker, String contents)
        throws IOException {
        int start = template.indexOf(startMarker);
        int end = template.indexOf(endMarker);
        if (start < 0 || end < start || template.indexOf(startMarker, start + startMarker.length()) >= 0) {
            throw new IOException("completion template has missing or duplicate markers: " + startMarker);
        }
        return template.substring(0, start)
            + startMarker + "\n"
            + contents
            + endMarker
            + template.substring(end + endMarker.length());
    }

    private static final class CompletionData {
        final TreeSet<String> commands = new TreeSet<>();
        final Map<String, TreeSet<String>> options = new TreeMap<>();
        final Map<String, TreeSet<String>> subcommands = new TreeMap<>();
        final Map<String, Integer> subcommandWordIndex = new TreeMap<>();
    }
}
