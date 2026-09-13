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

    private static java.nio.file.Path pathOrNull(String dir) {
        return dir == null || dir.isBlank() ? null : Paths.get(dir);
    }

    private static com.pointblue.dirxml.dev.packages.Catalog catalogOf(String dir) {
        try {
            return dir == null || dir.isBlank() ? null : com.pointblue.dirxml.dev.packages.Catalog.open(Paths.get(dir));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot open catalog " + dir + ": " + e.getMessage());
        }
    }

    private static java.util.Map<String, String> readAnswers(String file) {
        try {
            return com.pointblue.dirxml.dev.packages.PackageInstall.readAnswers(file == null || file.isBlank() ? null : Paths.get(file));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot read answers file " + file + ": " + e.getMessage());
        }
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

        register("form.set-content", "replace a JSON form's document (pretty-printed in the tree) and re-sync every PRD binding that references it",
            a -> new FormOps.SetContent(a.get("driver"), a.get("form"), contentOf(a)),
            req("form", "form name, kind/name (request|approval|template) or driver/kind/name"),
            req("content-file", "file holding the new form JSON (what the vendor builder saved)"),
            opt("driver", "the User Application driver (needed when several drivers have a form of that name)"));

        register("form.sync", "re-normalize a form already saved into the tree (e.g. by the vendor builder) and re-sync its PRD bindings",
            a -> new FormOps.SetContent(a.get("driver"), a.get("form"), null),
            req("form", "form name, kind/name or driver/kind/name"),
            opt("driver", "the User Application driver"));

        // provisioning: typed form/PRD operations (Track P step P2b)
        Arg formRef = req("form", "form name, kind/name (request|approval|template) or driver/kind/name");
        Arg formDriver = opt("driver", "the User Application driver (needed when several drivers have a form of that name)");
        register("form.add", "create a new form (blank, or --from another form's document)",
            a -> new FormOps.Add(a.get("driver"), a.get("kind"), a.get("name"), a.get("from"), a.get("title")),
            req("kind", "request|approval|template"), req("name", "the new form's name"),
            opt("from", "copy this form's document (new title)"), opt("title", "the new form's title (default: its name)"),
            driver);

        register("form.field.add", "add a new component to a form",
            a -> new FormOps.FieldAdd(a.get("driver"), a.get("form"), a.get("key"), a.get("type"), a.get("label"),
                a.containsKey("required"), a.containsKey("hidden"), a.containsKey("multiple"), a.containsKey("minimal"),
                a.get("after"), a.get("before"), a.containsKey("first"), a.get("in"), a.get("json")),
            formRef, req("key", "the new component's key"), req("type", "component type (a captured template name, or any Form.io type)"),
            opt("label", "label text"), opt("required", "flag: validate.required = true"), opt("hidden", "flag: hidden = true"),
            opt("multiple", "flag: multiple = true"), opt("minimal", "flag: force the minimal {label,key,type,input} shape"),
            opt("after", "place after this key"), opt("before", "place before this key"), opt("first", "flag: place first"),
            opt("in", "place inside this container/panel/columns component (its first column, if columns)"),
            opt("json", "extra properties as a JSON object, deep-merged in last"), formDriver);

        register("form.field.set", "change an existing component's properties",
            a -> new FormOps.FieldSet(a.get("driver"), a.get("form"), a.get("key"), a.get("label"),
                boolOrNull(a.get("required")), boolOrNull(a.get("hidden")), boolOrNull(a.get("multiple")), a.get("type"),
                repeatable(a.get("prop"))),
            formRef, req("key", "the component's key"), opt("label", "new label"),
            opt("required", "true|false"), opt("hidden", "true|false"), opt("multiple", "true|false"), opt("type", "new component type"),
            opt("prop", "dotted-path=JSON-value (repeat --prop for each, e.g. validate.maxLength=50)"), formDriver);

        register("form.field.remove", "remove a component; refuses while a PRD data item maps it unless --force",
            a -> new FormOps.FieldRemove(a.get("driver"), a.get("form"), a.get("key")),
            formRef, req("key", "the component's key"), formDriver);

        register("form.field.move", "reorder or reparent a component",
            a -> new FormOps.FieldMove(a.get("driver"), a.get("form"), a.get("key"), a.get("after"), a.get("before"),
                a.containsKey("first"), a.containsKey("last"), a.get("in")),
            formRef, req("key", "the component's key"), opt("after", "place after this key"), opt("before", "place before this key"),
            opt("first", "flag: place first"), opt("last", "flag: place last"),
            opt("in", "reparent inside this container/panel/columns component"), formDriver);

        register("form.set", "change a form's title, display mode, inline script (from a file) or an external script",
            a -> new FormOps.SetForm(a.get("driver"), a.get("form"), a.get("title"), a.get("display"),
                scriptFileOf(a.get("inline-script")), a.get("external-script"), a.containsKey("remove")),
            formRef, opt("title", "new title"), opt("display", "form|workflowWizard"),
            opt("inline-script", "a file holding the new inlinescripts text"),
            opt("external-script", "a script URL to add (or remove, with --remove)"),
            opt("remove", "flag: with --external-script, remove it instead of adding it"), formDriver);

        register("form.localize", "set explicit localized strings, or top up every declared language with missing entries",
            a -> new FormOps.Localize(a.get("driver"), a.get("form"), a.get("lang"), repeatable(a.get("set")), a.containsKey("sync")),
            formRef, req("lang", "language code, e.g. fr"),
            opt("set", "Label=Localized (repeat --set for each)"),
            opt("sync", "flag: add, to every declared language, an entry for every label/placeholder/tooltip/option/button text missing one"),
            formDriver);

        register("form.rename", "rename a form and rewrite every PRD reference to it (form-id attributes and flowdata prefixes)",
            a -> new FormOps.Rename(a.get("driver"), a.get("form"), a.get("to")),
            formRef, req("to", "the new name"), formDriver);

        register("form.delete", "delete a form; refuses while any PRD binds it (never overridden by --force); a packaged form needs --force",
            a -> new FormOps.Delete(a.get("driver"), a.get("form")),
            formRef, formDriver);

        register("prd.map", "add, replace or (--unmap) remove a field's data-item mapping",
            a -> new FormOps.PrdMap(a.get("driver"), a.get("prd"), a.get("field"), a.get("activity"),
                a.get("target"), a.get("source"), a.containsKey("unmap")),
            req("prd", "PRD name"), req("field", "the form field's key"),
            opt("activity", "map an approval activity's data item instead of the request form's"),
            opt("target", "override the default flowdata target (request form only)"),
            opt("source", "override the default flowdata.get(...) source (activity only; required if the request form has no same-named field)"),
            opt("unmap", "flag: remove the mapping instead"), driver);

        register("prd.add", "copy a template PRD (status Template) into a new Active PRD bound to the given forms",
            a -> new FormOps.PrdAdd(a.get("driver"), a.get("name"), a.get("from-template"), a.get("request-form"),
                a.get("approval-form"), a.get("category"), a.get("display-name"), a.containsKey("map-all")),
            req("name", "the new PRD's name"), req("from-template", "the template PRD's name (status Template, e.g. NoApproval)"),
            req("request-form", "the request form to bind"), opt("approval-form", "the approval form to bind to the first user-activity, if any"),
            opt("category", "prov-category (default: the template's)"), opt("display-name", "lang~Text override for one language (default: the new PRD's name, every language)"),
            opt("map-all", "flag: map every bindable field of the bound form(s) to flowdata with the default targets/sources, as prd.map would"),
            driver);

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

        // rules
        Arg rulePath = req("path", "path of a DirXML Script policy");
        Arg ruleId = req("rule", "the rule's <description>, or #n (1-based position)");
        Arg rulePos = opt("at", "first|last|after:<rule>|before:<rule> (default last)");
        register("rule.add", "insert a <rule> into a DirXML Script policy",
            a -> new RuleOps.Add(a.get("path"), contentOf(a), a.get("at")),
            rulePath, req("content-file", "file holding the <rule> XML"), rulePos);
        register("rule.delete", "remove a rule",
            a -> new RuleOps.Delete(a.get("path"), a.get("rule")), rulePath, ruleId);
        register("rule.move", "move a rule to another position",
            a -> new RuleOps.Move(a.get("path"), a.get("rule"), a.get("at")), rulePath, ruleId, rulePos);
        register("rule.disable", "set <rule disabled=\"true\"> — the engine skips it",
            a -> new RuleOps.SetDisabled(a.get("path"), a.get("rule"), true), rulePath, ruleId);
        register("rule.enable", "clear a rule's disabled flag",
            a -> new RuleOps.SetDisabled(a.get("path"), a.get("rule"), false), rulePath, ruleId);

        // GCVs
        register("gcv.set", "set a GCV's value where the driver's scope defines it (or create it with --define)",
            a -> new GcvOps.Set(a.get("driver"), a.get("name"), a.getOrDefault("value", ""),
                a.get("define"), a.get("display-name")),
            req("name", "GCV name"), req("value", "the value"), driver,
            opt("define", "create the GCV when absent: its type (string|boolean|integer|dn|enum|…)"),
            opt("display-name", "display name for --define (default: the name)"));
        register("gcv.delete", "remove a GCV definition; refuses while any policy reads it",
            a -> new GcvOps.Delete(a.get("driver"), a.get("name")),
            req("name", "GCV name"), driver);

        // filter
        Arg drv = req("driver", "driver name");
        Arg cls = req("class", "class name (NDS name)");
        Arg pub = opt("publisher", "sync|ignore|notify|reset");
        Arg sub = opt("subscriber", "sync|ignore|notify|reset");
        register("filter.set-class", "add a class to the driver filter, or change its channel settings",
            a -> new ConfigOps.FilterSetClass(a.get("driver"), a.get("class"), a),
            drv, cls, pub, sub, opt("publisher-create-homedir", "true|false"), opt("publisher-track-template-member", "true|false"));
        register("filter.set-attr", "add an attribute to a filter class, or change its settings",
            a -> new ConfigOps.FilterSetAttr(a.get("driver"), a.get("class"), a.get("attr"), a),
            drv, cls, req("attr", "attribute name (NDS name)"), pub, sub, opt("merge-authority", "default|edir|app|none"),
            opt("publisher-optimize-modify", "true|false"), opt("subscriber-optimize-modify", "true|false"));
        register("filter.remove-class", "remove a class (and its attributes) from the filter",
            a -> new ConfigOps.FilterRemoveClass(a.get("driver"), a.get("class")), drv, cls);
        register("filter.remove-attr", "remove an attribute from a filter class",
            a -> new ConfigOps.FilterRemoveAttr(a.get("driver"), a.get("class"), a.get("attr")),
            drv, cls, req("attr", "attribute name"));

        // schema map
        register("schema-map.set", "map a class (--nds-class/--app-class), an attribute within it (add --nds-attr/--app-attr), or a top-level attribute (--nds-attr/--app-attr alone)",
            a -> new ConfigOps.SchemaMapSet(a.get("driver"), a.get("nds-class"), a.get("app-class"), a.get("nds-attr"), a.get("app-attr")),
            drv, opt("nds-class", "eDirectory class name"), opt("app-class", "application class name"),
            opt("nds-attr", "eDirectory attribute name"), opt("app-attr", "application attribute name"));
        register("schema-map.remove", "remove a class mapping, an attribute mapping within a class, or a top-level attribute mapping",
            a -> new ConfigOps.SchemaMapRemove(a.get("driver"), a.get("nds-class"), a.get("nds-attr")),
            drv, opt("nds-class", "eDirectory class name"), opt("nds-attr", "eDirectory attribute name"));

        // driver settings
        register("driver.set", "set a driver setting: shim-class, shim-auth-server, shim-auth-id, param:<shim parameter>, engine:<engine control value>",
            a -> new ConfigOps.DriverSet_(a.get("driver"), a.get("key"), a.get("value")),
            drv, req("key", "shim-class|shim-auth-server|shim-auth-id|param:<name>|engine:<name>"), req("value", "the value"));

        // drivers
        register("package.install", "install a package jar onto a driver (type 2) or the Library (type 3), Designer's way",
            a -> new com.pointblue.dirxml.dev.packages.PackageInstall(
                com.pointblue.dirxml.dev.packages.PackageInstall.jarsOf(a.get("jar"), a.get("catalog"), a.get("package")),
                a.get("driver"), readAnswers(a.get("answers")), !"true".equals(a.get("new-driver"))),
            opt("jar", "the package jar(s), comma-separated, in install order (or give --catalog and --package)"),
            opt("catalog", "a package catalog directory (jars/<SHORT>/<SHORT>_<ver>.jar)"),
            opt("package", "SHORT[_version][,SHORT[_version]…] in the catalog (newest version when omitted); base first"),
            opt("driver", "the driver to install onto (omit for a driver-set package into the Library)"),
            opt("answers", "name=value file answering the package prompts (see package.prompts)"),
            opt("new-driver", "true when the driver was just created (prompts run in driver-creation mode)"));
        register("package.adopt", "write the installed-package records from the objects' package stamps (trees imported from a vault or a project)",
            a -> new com.pointblue.dirxml.dev.packages.PackageAdopt(a.get("driver"), catalogOf(a.get("catalog"))),
            opt("driver", "one driver (default: every driver and the Library)"),
            opt("catalog", "a package catalog, to resolve project-style package ids to names and versions"));
        register("package.uninstall", "remove an installed package; refuses while another installed package depends on it, or while a base package's features remain, unless --all",
            a -> new com.pointblue.dirxml.dev.packages.PackageUninstall(a.get("driver"), a.get("package"),
                a.containsKey("yes"), a.containsKey("all"), pathOrNull(a.get("catalog"))),
            opt("driver", "the driver the package is installed on (omit for a driver-set package in the Library)"),
            req("package", "the installed package's SHORT name"),
            opt("yes", "remove customized objects too (flag; without it they're refused, named)"),
            opt("all", "also remove packages that depend on this one, or (for a base package) its remaining features"),
            opt("catalog", "a package catalog, to read other installed packages' declared dependencies"));
        register("package.upgrade", "replace an installed package with another version (--downgrade for an older one; same mechanics)",
            a -> new com.pointblue.dirxml.dev.packages.PackageUpgrade(a.get("driver"),
                com.pointblue.dirxml.dev.packages.PackageInstall.jarOf(a.get("jar"), a.get("catalog"), a.get("package")),
                readAnswers(a.get("answers")), a.containsKey("yes"), a.containsKey("downgrade"), pathOrNull(a.get("catalog"))),
            opt("driver", "the driver the package is installed on (omit for a driver-set package in the Library)"),
            opt("jar", "the new version's package jar (or give --catalog and --package)"),
            opt("catalog", "a package catalog directory (jars/<SHORT>/<SHORT>_<ver>.jar)"),
            opt("package", "SHORT_version in the catalog, the version to move to"),
            opt("answers", "name=value file answering the package's prompts (existing driver values pre-fill them)"),
            opt("yes", "remove customized objects the new version dropped (flag; without it they're refused, named)"),
            opt("downgrade", "moving to an older version (flag; informational — the mechanics are identical)"));
        register("driver.add", "add a driver: from a driver export, as a copy of another driver, or blank",
            a -> new DriverOps.Add(a.get("name"),
                a.get("from-export") == null || a.get("from-export").isBlank() ? null : Paths.get(a.get("from-export")),
                a.get("source-driver") != null ? a.get("source-driver") : a.get("copy-of"),
                a.get("copy-of") != null, a.get("shim-class"), a.get("auth-server"), a.get("auth-id"))
                .withPackages(a.get("packages") == null && a.get("package") == null ? null
                    : com.pointblue.dirxml.dev.packages.PackageInstall.jarsOf(a.get("packages"), a.get("catalog"), a.get("package")),
                    readAnswers(a.get("answers"))),
            req("name", "the new driver's name"),
            opt("from-export", "a driver export (Designer, with referenced policies) to merge in as this driver"),
            opt("source-driver", "with a driver-set export: which driver to take"),
            opt("copy-of", "an existing driver to clone"),
            opt("packages", "package jars, comma-separated, base first: the driver is built from the base package and the set installed"),
            opt("catalog", "with --package: the package catalog directory"),
            opt("package", "with --catalog: SHORT[_ver][,…] to install, base first"),
            opt("answers", "name=value file answering the packages' prompts"),
            opt("shim-class", "for a blank driver: the shim class"),
            opt("auth-server", "for a blank driver: the authentication server/URL"),
            opt("auth-id", "for a blank driver: the authentication id"));

        // mapping tables
        Arg table = req("path", "mapping-table resource path");
        Arg keyCol = opt("key-column", "column that identifies the row (default: the first)");
        register("mapping-table.set-row", "add or update a row, keyed by --key-column (default: the first column)",
            a -> new ConfigOps.TableSetRow(a.get("path"), a.get("key-column"), ConfigOps.columnValues(a.get("col"))),
            table, req("col", "name=value (repeat --col for each column)"), keyCol);
        register("mapping-table.delete-row", "delete the row whose key column has --key",
            a -> new ConfigOps.TableDeleteRow(a.get("path"), a.get("key-column"), a.get("key")),
            table, req("key", "the key column's value"), keyCol);
        register("mapping-table.add-column", "add a column (empty in every existing row)",
            a -> new ConfigOps.TableAddColumn(a.get("path"), a.get("column"), a.get("type")),
            table, req("column", "column name"), opt("type", "nocase|case|numeric (default nocase)"));
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

    private static String scriptFileOf(String file) throws IOException {
        return file == null || file.isBlank() ? null : Files.readString(Paths.get(file), StandardCharsets.UTF_8);
    }

    /** {@code "true"}/{@code "false"} -> a boolean; anything else (including absent) -> null (unchanged). */
    private static Boolean boolOrNull(String v) {
        if (v == null) {
            return null;
        }
        if (v.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** A repeatable {@code --arg} (joined with {@code \n} by the CLI) split back into its values; empty list when absent. */
    private static List<String> repeatable(String joined) {
        if (joined == null || joined.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(joined.split("\n")));
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
