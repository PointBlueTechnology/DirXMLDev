package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.edit.OperationsDoc;
import com.pointblue.dirxml.dev.edit.Registry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * docs/commands.md has an entry for every command {@code bin/idm} runs, the usage text names every
 * command the CLIs dispatch, the edit operations' part is what {@link OperationsDoc} writes from the
 * registry, and each operation's example names only its own arguments and every required one.
 * {@code -Dcommands.write=true} rewrites the generated part instead of failing on it.
 */
public class CommandsReferenceTest {

    private static final Path DOC = Path.of("docs/commands.md");

    /** The CLI classes whose {@code case "x.y":} labels are commands. */
    private static final List<String> DISPATCHERS = List.of(
        "operate/OperateCli.java", "deploy/DeployCli.java", "clone/CloneCli.java", "events/EventsCli.java",
        "promote/PromoteCli.java", "packages/PackageCli.java");

    @Test
    public void theGeneratedPartIsCurrent() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        int b = doc.indexOf(OperationsDoc.BEGIN);
        int e = doc.indexOf(OperationsDoc.END);
        assertTrue("docs/commands.md has no generated part between the BEGIN and END markers", b >= 0 && e > b);
        String now = doc.substring(b, e + OperationsDoc.END.length() + 1);
        String want = OperationsDoc.markdown();
        if (Boolean.getBoolean("commands.write")) {
            Files.writeString(DOC, doc.substring(0, b) + want + doc.substring(e + OperationsDoc.END.length() + 1), StandardCharsets.UTF_8);
            return;
        }
        assertEquals("docs/commands.md's edit operations are stale: mvn -o test -Dtest=CommandsReferenceTest -Dcommands.write=true", want, now);
    }

    @Test
    public void everyCommandHasAnEntry() throws IOException {
        Set<String> entries = headings(Files.readString(DOC, StandardCharsets.UTF_8));
        Set<String> commands = new TreeSet<>(usageCommands());
        for (Registry.Spec s : Registry.all()) {
            commands.add(s.name);
        }
        commands.addAll(dispatched());
        commands.removeAll(entries);
        assertTrue("commands without an entry in docs/commands.md: " + commands, commands.isEmpty());
    }

    @Test
    public void theUsageTextNamesEveryDispatchedCommand() throws IOException {
        Set<String> missing = new TreeSet<>(dispatched());
        missing.removeAll(usageCommands());
        assertTrue("commands the CLI runs but bin/idm's usage text doesn't list: " + missing, missing.isEmpty());
    }

    @Test
    public void everyOperationHasAnExampleThatFitsItsArguments() {
        List<String> problems = new ArrayList<>();
        for (Registry.Spec spec : Registry.all()) {
            String example = Registry.example(spec.name);
            if (example == null) {
                problems.add(spec.name + ": no example in examples.properties");
                continue;
            }
            List<String> words = words(example);
            Set<String> given = new HashSet<>();
            for (int i = 0; i < words.size(); i++) {
                String w = words.get(i);
                if (!w.startsWith("--")) {
                    problems.add(spec.name + ": '" + w + "' is not after a flag that takes a value");
                    continue;
                }
                String name = w.substring(2);
                given.add(name);
                if (name.equals("dry-run") || name.equals("force") || name.equals("json")) {
                    continue;
                }
                Registry.Arg arg = spec.args.stream().filter(a -> a.name.equals(name)).findFirst().orElse(null);
                if (arg == null) {
                    problems.add(spec.name + ": --" + name + " is not one of its arguments");
                } else if (!arg.flag) {
                    if (i + 1 >= words.size() || words.get(i + 1).startsWith("--")) {
                        problems.add(spec.name + ": --" + name + " needs a value");
                    } else {
                        i++;
                    }
                }
            }
            for (Registry.Arg a : spec.args) {
                if (a.required && !given.contains(a.name)) {
                    problems.add(spec.name + ": the example lacks the required --" + a.name);
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    /** The command names in the page's {@code ### } headings: every backticked word. */
    static Set<String> headings(String doc) {
        Set<String> names = new HashSet<>();
        Matcher m = Pattern.compile("(?m)^### (.*)$").matcher(doc);
        while (m.find()) {
            Matcher n = Pattern.compile("`([^`]+)`").matcher(m.group(1));
            while (n.find()) {
                names.add(n.group(1));
            }
        }
        return names;
    }

    /**
     * The commands the usage text names: the first word of each line indented by two spaces and of
     * each " | "-separated alternative on it, with {@code a.b|c} read as {@code a.b} and {@code a.c}.
     * An alternative that isn't at the line's start counts only when it is dotted ({@code query …
     * chain|gcvs} names verbs, not commands).
     */
    static Set<String> usageCommands() {
        Set<String> names = new TreeSet<>();
        for (String line : Cli.usageText().split("\n")) {
            if (!line.matches("  [a-z].*")) {
                continue;
            }
            String[] alternatives = line.trim().split(" \\| ");
            for (int i = 0; i < alternatives.length; i++) {
                String word = alternatives[i].trim().split("\\s+")[0];
                String[] parts = word.split("\\|");
                String first = parts[0];
                if (i > 0 && !first.contains(".")) {
                    continue;
                }
                names.add(first);
                String prefix = first.contains(".") ? first.substring(0, first.lastIndexOf('.') + 1) : "";
                for (int p = 1; p < parts.length; p++) {
                    if (parts[p].matches("[a-z][a-z0-9.-]*")) {
                        names.add(parts[p].contains(".") ? parts[p] : prefix + parts[p]);
                    }
                }
            }
        }
        return names;
    }

    /** The dotted {@code case "…":} labels of the CLI dispatchers: the commands they run. */
    static Set<String> dispatched() throws IOException {
        Set<String> names = new TreeSet<>();
        Pattern label = Pattern.compile("case \"([a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+)\":");
        for (String f : DISPATCHERS) {
            Matcher m = label.matcher(Files.readString(Path.of("src/main/java/com/pointblue/dirxml/dev", f), StandardCharsets.UTF_8));
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** The example's words, double quotes grouping as the shell does. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(s);
        while (m.find()) {
            out.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return out;
    }
}
