package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one registry of edit operations: name, argument spec, description, and a
 * factory from parsed arguments. The CLI dispatches from it; any other surface
 * (an MCP adapter, if ever) would too. Arguments arrive as {@code --key value}
 * pairs; a repeatable argument (e.g. {@code --order}) is joined with {@code \n}.
 */
public final class Registry {

    /** One argument of an operation. */
    public static final class Arg {
        public final String name;
        public final boolean required;
        public final String help;

        Arg(String name, boolean required, String help) {
            this.name = name;
            this.required = required;
            this.help = help;
        }
    }

    /** The factory for one operation. */
    public interface Factory {
        Operation create(Map<String, String> args) throws IOException, IllegalArgumentException;
    }

    public static final class Spec {
        public final String name;
        public final String help;
        public final List<Arg> args;
        final Factory factory;

        Spec(String name, String help, List<Arg> args, Factory factory) {
            this.name = name;
            this.help = help;
            this.args = args;
            this.factory = factory;
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private Registry() {
    }

    public static Spec get(String name) {
        return SPECS.get(name);
    }

    public static List<Spec> all() {
        return new ArrayList<>(SPECS.values());
    }

    private static void register(String name, String help, Factory f, Arg... args) {
        SPECS.put(name, new Spec(name, help, Arrays.asList(args), f));
    }

    private static Arg req(String name, String help) {
        return new Arg(name, true, help);
    }

    private static Arg opt(String name, String help) {
        return new Arg(name, false, help);
    }

    static {
        Arg driver = opt("driver", "driver name (omit for the Library)");
        Arg scope = opt("scope", "driver|subscriber|publisher|library (default: driver, or library when no --driver)");
        Arg content = opt("content-file", "file holding the content (XML, or JS for ecmascript); default: a skeleton");
        Arg link = opt("link", "also link it: a policy-set key (input, output, subscriber-command, publisher-event, …)");
        Arg at = opt("at", "where in the set: first|last|after:<path>|before:<path>|<order> (default last)");
        Arg linkDriver = opt("link-driver", "for a Library artifact with --link: the driver whose set links it");

        register("policy.add", "create a policy (DirXML Script, XSLT or schema map) and optionally link it",
            a -> new ArtifactOps.Add(scopeOf(a), a.get("driver"), a.get("name"),
                a.getOrDefault("kind", "policy"), contentOf(a), setOf(a.get("link")),
                a.containsKey("at") ? ArtifactOps.Position.parse(a.get("at")) : null, a.get("link-driver")),
            req("name", "policy name"), driver, scope, opt("kind", "policy|xslt|schema-map (default policy)"),
            content, link, at, linkDriver);

        register("resource.add", "create a mapping table, ECMAScript or GCV-definition resource and optionally link it",
            a -> new ArtifactOps.Add(scopeOf(a), a.get("driver"), a.get("name"),
                a.getOrDefault("kind", "mapping-table"), contentOf(a), setOf(a.get("link")),
                a.containsKey("at") ? ArtifactOps.Position.parse(a.get("at")) : null, a.get("link-driver")),
            req("name", "resource name"), driver, scope, opt("kind", "mapping-table|ecmascript|gcv (default mapping-table)"),
            content, link, at, linkDriver);

        register("artifact.set-content", "replace an artifact's content",
            a -> new ArtifactOps.SetContent(a.get("path"), contentOf(a)),
            req("path", "artifact path (library/X, drivers/D/X, drivers/D/subscriber/X)"),
            req("content-file", "file holding the new content"));

        register("artifact.rename", "rename an artifact and rewrite every reference to it",
            a -> new ArtifactOps.Rename(a.get("path"), a.get("name")),
            req("path", "artifact path"), req("name", "the new name"));

        register("artifact.delete", "delete an artifact; refuses while anything references it",
            a -> new ArtifactOps.Delete(a.get("path"), a.containsKey("unlink")),
            req("path", "artifact path"), opt("unlink", "remove its policy-set links first (flag)"));

        register("policy.link", "link an artifact into a driver's policy set",
            a -> new ArtifactOps.Link(a.get("path"), a.get("driver"), setOf(a.get("set")),
                a.containsKey("at") ? ArtifactOps.Position.parse(a.get("at")) : null),
            req("path", "artifact path"), req("driver", "driver name"), req("set", "policy-set key"), at);

        register("policy.unlink", "remove an artifact from a driver's policy set",
            a -> new ArtifactOps.Unlink(a.get("path"), a.get("driver"), setOf(a.get("set"))),
            req("path", "artifact path"), req("driver", "driver name"), req("set", "policy-set key"));

        register("policy.reorder", "set the full order of a driver's policy set",
            a -> new ArtifactOps.Reorder(a.get("driver"), setOf(a.get("set")),
                Arrays.asList(a.getOrDefault("order", "").split("\n"))),
            req("driver", "driver name"), req("set", "policy-set key"),
            req("order", "the members in order (repeat --order <path> for each)"));
    }

    private static Scope scopeOf(Map<String, String> a) {
        String s = a.get("scope");
        if (s == null || s.isBlank()) {
            return a.get("driver") == null || a.get("driver").isBlank() ? Scope.LIBRARY : Scope.DRIVER;
        }
        return Scope.byKey(s);
    }

    private static PolicySet setOf(String key) {
        return key == null || key.isBlank() ? null : PolicySet.byKey(key);
    }

    private static String contentOf(Map<String, String> a) throws IOException {
        String f = a.get("content-file");
        return f == null || f.isBlank() ? null : Files.readString(Paths.get(f), StandardCharsets.UTF_8);
    }

    /** Check required args are present; returns the problem or null. */
    public static String missing(Spec spec, Map<String, String> args) {
        List<String> m = new ArrayList<>();
        for (Arg arg : spec.args) {
            if (arg.required && (args.get(arg.name) == null || args.get(arg.name).isBlank())) {
                m.add("--" + arg.name);
            }
        }
        return m.isEmpty() ? null : "missing " + String.join(", ", m);
    }

    public static String usage(Spec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(spec.name).append(" <tree>");
        for (Arg a : spec.args) {
            sb.append(a.required ? " --" + a.name + " <v>" : " [--" + a.name + " <v>]");
        }
        sb.append(" [--dry-run] [--force] [--json]\n      ").append(spec.help).append('\n');
        for (Arg a : spec.args) {
            sb.append(String.format("      --%-14s %s%n", a.name, a.help));
        }
        return sb.toString();
    }
}
