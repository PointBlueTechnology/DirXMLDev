package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.FormDocument;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The read commands an agent uses to orient itself in a tree before editing:
 * <pre>
 *   show <tree> <path>                       an artifact's content
 *   query <tree> artifacts [driver]           every artifact (of a driver)
 *   query <tree> chain <driver> sub|pub       the channel's policy chain in execution order
 *   query <tree> gcvs <driver>                GCVs in the driver's scope, with where each is defined
 *   query <tree> tables <driver>              mapping tables in the driver's reach
 *   package.diff <tree> <path>                a customized packaged artifact vs its baseline
 *   form.list <tree> [--driver D]             every JSON form (kind, name, title, #fields, packaged mark)
 *   form.show <tree> <name-or-path> [--driver D] [--json]   a form's outline + which PRDs bind it
 *   prd.list <tree>                           every PRD (status, category, json-forms/classic, bound forms)
 *   prd.show <tree> <name> [--driver D] [--json]   a PRD's properties, bindings, activities
 * </pre>
 */
public final class ReadCli {

    private ReadCli() {
    }

    /** The engine's stage order per channel, as the simulator assembles it. */
    static final List<PolicySet> SUB_CHAIN = Arrays.asList(PolicySet.SUB_EVENT, PolicySet.SUB_MATCH,
        PolicySet.SUB_CREATE, PolicySet.SUB_PLACEMENT, PolicySet.SUB_COMMAND, PolicySet.SCHEMA_MAPPING, PolicySet.OUTPUT);
    static final List<PolicySet> PUB_CHAIN = Arrays.asList(PolicySet.INPUT, PolicySet.SCHEMA_MAPPING,
        PolicySet.PUB_EVENT, PolicySet.PUB_MATCH, PolicySet.PUB_CREATE, PolicySet.PUB_PLACEMENT, PolicySet.PUB_COMMAND);

    public static int show(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("usage: show <tree> <artifactPath>");
            return 2;
        }
        DriverSet ds = AsCodeReader.read(Paths.get(argv[1]));
        Artifact a = ds.resolve(argv[2]);
        if (a == null) {
            System.err.println("no artifact at '" + argv[2] + "'");
            return 1;
        }
        String content = Packages.currentContent(a);
        System.out.print(content == null ? "(no content)\n" : content.endsWith("\n") ? content : content + "\n");
        return 0;
    }

    public static int query(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("usage: query <tree> artifacts [driver] | chain <driver> sub|pub | gcvs <driver> | tables <driver>");
            return 2;
        }
        DriverSet ds = AsCodeReader.read(Paths.get(argv[1]));
        String what = argv[2];
        String driver = argv.length > 3 ? argv[3] : null;
        switch (what) {
            case "artifacts":
                return artifacts(ds, driver);
            case "chain":
                if (driver == null || argv.length < 5) {
                    System.err.println("usage: query <tree> chain <driver> sub|pub");
                    return 2;
                }
                return chain(ds, driver, argv[4]);
            case "gcvs":
                return gcvs(ds, driver);
            case "tables":
                return tables(ds, driver);
            default:
                System.err.println("unknown query '" + what + "'");
                return 2;
        }
    }

    private static Driver driver(DriverSet ds, String name) {
        Driver d = name == null ? null : ds.driver(name);
        if (d == null) {
            List<String> names = new ArrayList<>();
            for (Driver x : ds.drivers) {
                names.add(x.name);
            }
            System.err.println("no driver '" + name + "'; drivers: " + names);
        }
        return d;
    }

    private static int artifacts(DriverSet ds, String driverName) {
        List<Artifact> list;
        if (driverName == null) {
            list = new ArrayList<>(ds.index().values());
        } else {
            Driver d = driver(ds, driverName);
            if (d == null) {
                return 1;
            }
            list = d.artifacts();
        }
        for (Artifact a : list) {
            String kind = a instanceof Policy ? ((Policy) a).policyKind().name().toLowerCase().replace('_', '-')
                : ((Resource) a).isMappingTable() ? "mapping-table" : ((Resource) a).isEcmaScript() ? "ecmascript"
                : ((Resource) a).isGcvDef() ? "gcv-def" : "resource";
            String pkg = Packages.isPackaged(a) ? (Packages.isCustomized(a) ? "  [packaged, customized]" : "  [packaged]") : "";
            System.out.printf("%-14s %s%s%n", kind, a.path(), pkg);
        }
        return 0;
    }

    private static int chain(DriverSet ds, String driverName, String channel) {
        Driver d = driver(ds, driverName);
        if (d == null) {
            return 1;
        }
        boolean sub = channel.startsWith("sub");
        Map<String, Artifact> index = ds.index();
        System.out.println((sub ? "subscriber" : "publisher") + " chain of " + d.name + " (execution order):");
        for (PolicySet set : sub ? SUB_CHAIN : PUB_CHAIN) {
            List<PolicyLink> links = d.links(set);
            if (links.isEmpty()) {
                continue;
            }
            System.out.println("  " + set.key);
            for (PolicyLink l : links) {
                Artifact a = index.get(l.ref);
                String kind = a == null ? "UNRESOLVED" : a instanceof Policy ? ((Policy) a).policyKind().name().toLowerCase() : a.kind();
                System.out.printf("    %2d  %-14s %s%n", l.order, kind, l.ref);
            }
        }
        Set<PolicySet> shown = new LinkedHashSet<>(sub ? SUB_CHAIN : PUB_CHAIN);
        for (PolicySet set : PolicySet.values()) {
            if (!shown.contains(set) && !d.links(set).isEmpty()
                && (set == PolicySet.ECMASCRIPT || set == PolicySet.GCV
                    || set == PolicySet.STARTUP || set == PolicySet.SHUTDOWN)) {
                System.out.println("  " + set.key + " (resources)");
                for (PolicyLink l : d.links(set)) {
                    System.out.printf("    %2d  %s%n", l.order, l.ref);
                }
            }
        }
        return 0;
    }

    private static int gcvs(DriverSet ds, String driverName) {
        Driver d = driverName == null ? null : driver(ds, driverName);
        if (driverName != null && d == null) {
            return 1;
        }
        // in the engine's precedence: first definition wins
        Map<String, String[]> seen = new LinkedHashMap<>();
        Map<String, Artifact> index = ds.index();
        if (d != null) {
            collect(seen, d.config.get(Driver.CONFIG_VALUES), "drivers/" + d.name + " (config-values)");
            for (PolicyLink l : d.links(PolicySet.GCV)) {
                Artifact a = index.get(l.ref);
                if (a instanceof Resource) {
                    collect(seen, ((Resource) a).content, a.path());
                }
            }
        }
        collect(seen, ds.configValues, "driverset (config-values)");
        for (Map.Entry<String, String> m : ds.meta.entrySet()) {
            if (m.getKey().startsWith("driverset.linkage.")) {
                String leaf = Refs.leafOfLinkage(m.getValue());
                for (Resource r : ds.library.resources) {
                    if (r.isGcvDef() && r.name.equals(leaf)) {
                        collect(seen, r.content, r.path());
                    }
                }
            }
        }
        for (Map.Entry<String, String[]> e : seen.entrySet()) {
            System.out.printf("%-45s = %-30s (%s)%n", e.getKey(), abbreviate(e.getValue()[0]), e.getValue()[1]);
        }
        System.out.println(seen.size() + " GCV(s)" + (d == null ? " at driver-set scope" : " in scope for " + d.name));
        return 0;
    }

    private static void collect(Map<String, String[]> seen, Element cv, String where) {
        if (cv == null) {
            return;
        }
        for (Element def : Xds.descendantsByName(cv, "definition")) {
            String name = def.getAttribute("name");
            if (name.isBlank() || seen.containsKey(name)) {
                continue;
            }
            List<Element> v = Xds.childrenByName(def, "value");
            String value = v.isEmpty() ? "" : Xds.text(v.get(0));
            if ("password-ref".equals(def.getAttribute("type"))) {
                value = "(password)";
            }
            seen.put(name, new String[] {value, where});
        }
    }

    private static String abbreviate(String s) {
        String t = s.replace("\n", " ").trim();
        return t.length() > 30 ? t.substring(0, 27) + "…" : t;
    }

    private static int tables(DriverSet ds, String driverName) {
        Driver d = driverName == null ? null : driver(ds, driverName);
        if (driverName != null && d == null) {
            return 1;
        }
        List<Resource> all = new ArrayList<>(ds.library.resources);
        if (d != null) {
            all.addAll(d.resources);
        }
        int n = 0;
        for (Resource r : all) {
            if (!r.isMappingTable()) {
                continue;
            }
            n++;
            List<String> cols = new ArrayList<>();
            int rows = 0;
            if (r.content != null) {
                for (Element c : Xds.childrenByName(r.content, "col-def")) {
                    cols.add(c.getAttribute("name"));
                }
                rows = Xds.childrenByName(r.content, "row").size();
            }
            System.out.printf("%-50s %3d row(s)  columns: %s%n", r.path(), rows, String.join(", ", cols));
        }
        System.out.println(n + " mapping table(s) in reach");
        return 0;
    }

    public static int packageDiff(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("usage: package.diff <tree> <artifactPath>");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        DriverSet ds = AsCodeReader.read(tree);
        Artifact a = ds.resolve(argv[2]);
        if (a == null) {
            System.err.println("no artifact at '" + argv[2] + "'");
            return 1;
        }
        if (!Packages.isPackaged(a)) {
            System.out.println(a.path() + " is not package-managed");
            return 0;
        }
        String baseline = Packages.baseline(tree, a);
        if (!Packages.isCustomized(a) || baseline == null) {
            System.out.println(a.path() + " is packaged and not customized" + (baseline == null ? "" : " (baseline present)"));
            return 0;
        }
        String current = Packages.currentContent(a);
        List<String> b = Arrays.asList(baseline.split("\n"));
        List<String> c = current == null ? List.of("") : Arrays.asList(current.split("\n"));
        System.out.println("--- " + a.path() + " (package baseline)");
        System.out.println("+++ " + a.path() + " (customized)");
        for (String line : diff(b, c)) {
            System.out.println(line);
        }
        return 0;
    }

    /** A minimal LCS line diff — enough to read a customization. */
    static List<String> diff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add("  " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add("- " + a.get(i++));
            } else {
                out.add("+ " + b.get(j++));
            }
        }
        while (i < n) {
            out.add("- " + a.get(i++));
        }
        while (j < m) {
            out.add("+ " + b.get(j++));
        }
        return out;
    }

    static String serialize(Element e) {
        return CanonicalXml.serialize(e);
    }

    static boolean isChannel(Scope s) {
        return s == Scope.SUBSCRIBER || s == Scope.PUBLISHER;
    }

    // ---- provisioning: forms and PRDs ------------------------------------------------

    public static int formList(String[] argv) throws Exception {
        if (argv.length < 2) {
            System.err.println("usage: form.list <tree> [--driver D]");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        String driverName = flag(argv, "--driver");
        DriverSet ds = AsCodeReader.read(tree);
        List<Driver> drivers = driversOf(ds, driverName);
        if (driverName != null && drivers.isEmpty()) {
            System.err.println("no driver '" + driverName + "'");
            return 1;
        }
        int n = 0;
        for (Driver d : drivers) {
            if (d.provisioning == null) {
                continue;
            }
            for (Form f : d.provisioning.forms) {
                n++;
                FormDocument doc = safeDocument(f);
                int fields = doc == null ? -1 : countInputFields(doc);
                String title = doc == null ? "(unparsable)" : doc.title();
                System.out.printf("%-8s %-40s %-30s %3s field(s)%s%n", f.kind.dir, d.name + "/" + f.name,
                    title == null ? "" : title, fields < 0 ? "?" : String.valueOf(fields), formPkgMark(f.meta));
            }
        }
        System.out.println(n + " form(s)");
        return 0;
    }

    public static int formShow(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("usage: form.show <tree> <name-or-path> [--driver D] [--json]");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        String ref = argv[2];
        String driverName = flag(argv, "--driver");
        boolean json = hasFlag(argv, "--json");
        DriverSet ds = AsCodeReader.read(tree);

        Found found = findForm(ds, ref, driverName);
        if (found == null) {
            System.err.println("no form matching '" + ref + "'" + (driverName == null ? "" : " on driver '" + driverName + "'"));
            return 1;
        }
        Form f = found.form;
        FormDocument doc = safeDocument(f);
        if (json) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("driver", found.driver.name);
            out.put("kind", f.kind.dir);
            out.put("name", f.name);
            if (doc != null) {
                out.put("title", doc.title());
                out.put("display", doc.display());
                out.put("languages", doc.languages());
                out.put("hasInlineScripts", doc.hasInlineScripts());
                out.put("externalScripts", doc.externalScripts());
                List<Object> comps = new ArrayList<>();
                for (FormDocument.Component c : doc.components()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("path", c.path);
                    cm.put("key", c.key);
                    cm.put("type", c.type);
                    cm.put("label", c.label);
                    cm.put("input", c.input);
                    cm.put("required", c.required);
                    cm.put("hidden", c.hidden);
                    if (c.conditional != null) {
                        cm.put("conditional", c.conditional);
                    }
                    comps.add(cm);
                }
                out.put("components", comps);
            }
            List<Object> boundBy = new ArrayList<>();
            for (Bound b : formBindings(ds, found.driver, f.name)) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("prd", b.prd.name);
                bm.put("activityId", b.binding.activityId);
                bm.put("fields", fieldNames(b.binding));
                boundBy.add(bm);
            }
            out.put("boundBy", boundBy);
            System.out.println(Json.pretty(out));
            return 0;
        }

        System.out.println(found.driver.name + "/" + f.kind.dir + "/" + f.name + formPkgMark(f.meta));
        if (doc == null) {
            System.out.println("  (document did not parse as JSON)");
        } else {
            System.out.println("  title:   " + doc.title());
            System.out.println("  display: " + doc.display());
            System.out.println("  languages: " + String.join(", ", doc.languages()));
            System.out.println("  inline scripts: " + (doc.hasInlineScripts() ? "yes" : "no"));
            System.out.println("  external scripts: " + doc.externalScripts());
            System.out.println("  fields:");
            for (FormDocument.Component c : doc.components()) {
                if (!c.input) {
                    continue;
                }
                System.out.printf("    %-40s %-14s label=%-20s required=%-5s hidden=%-5s%s%n",
                    c.key, c.type, c.label == null ? "" : c.label, c.required, c.hidden,
                    c.conditional == null ? "" : "  conditional: " + c.conditional);
            }
        }
        List<Bound> boundBy = formBindings(ds, found.driver, f.name);
        System.out.println("  bound by " + boundBy.size() + " PRD binding(s):");
        for (Bound b : boundBy) {
            System.out.println("    " + b.prd.name + (b.binding.activityId == null ? " (request)" : " (activity " + b.binding.activityId + ")")
                + "  fields: " + fieldNames(b.binding));
        }
        return 0;
    }

    public static int prdList(String[] argv) throws Exception {
        if (argv.length < 2) {
            System.err.println("usage: prd.list <tree> [--driver D]");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        String driverName = flag(argv, "--driver");
        DriverSet ds = AsCodeReader.read(tree);
        List<Driver> drivers = driversOf(ds, driverName);
        int n = 0;
        for (Driver d : drivers) {
            if (d.provisioning == null) {
                continue;
            }
            for (Prd prd : d.provisioning.prds) {
                n++;
                List<String> forms = new ArrayList<>();
                for (Prd.FormBinding b : prd.bindings()) {
                    forms.add(b.formId);
                }
                System.out.printf("%-40s status=%-10s category=%-14s %-11s bound: %s%n",
                    d.name + "/" + prd.name, str(prd.property("status")), str(prd.property("category-key")),
                    prd.isJsonForms() ? "json-forms" : "classic", forms);
            }
        }
        System.out.println(n + " PRD(s)");
        return 0;
    }

    public static int prdShow(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("usage: prd.show <tree> <name> [--driver D] [--json]");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        String name = argv[2];
        String driverName = flag(argv, "--driver");
        boolean json = hasFlag(argv, "--json");
        DriverSet ds = AsCodeReader.read(tree);

        Driver owner = null;
        Prd prd = null;
        for (Driver d : driversOf(ds, driverName)) {
            if (d.provisioning == null) {
                continue;
            }
            Prd p = d.provisioning.prd(name);
            if (p != null) {
                owner = d;
                prd = p;
                break;
            }
        }
        if (prd == null) {
            System.err.println("no PRD '" + name + "'" + (driverName == null ? "" : " on driver '" + driverName + "'"));
            return 1;
        }
        List<String> activities = activityIds(prd.process);
        if (json) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("driver", owner.name);
            out.put("name", prd.name);
            out.put("isJsonForms", prd.isJsonForms());
            Map<String, Object> props = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : prd.properties.entrySet()) {
                props.put(e.getKey(), e.getValue());
            }
            out.put("properties", props);
            List<Object> bindings = new ArrayList<>();
            for (Prd.FormBinding b : prd.bindings()) {
                Map<String, Object> bm = new LinkedHashMap<>();
                bm.put("activityId", b.activityId);
                bm.put("formId", b.formId);
                bm.put("fields", fieldNames(b));
                bindings.add(bm);
            }
            out.put("bindings", bindings);
            out.put("activities", activities);
            System.out.println(Json.pretty(out));
            return 0;
        }

        System.out.println(owner.name + "/" + prd.name + (prd.isJsonForms() ? "  [json-forms]" : "  [classic]"));
        System.out.println("  properties:");
        for (Map.Entry<String, List<String>> e : prd.properties.entrySet()) {
            System.out.println("    " + e.getKey() + " = " + e.getValue());
        }
        System.out.println("  bindings:");
        for (Prd.FormBinding b : prd.bindings()) {
            System.out.println("    " + (b.activityId == null ? "request" : "activity " + b.activityId)
                + " -> " + b.formId + "  fields: " + fieldNames(b));
        }
        System.out.println("  activities: " + activities);
        return 0;
    }

    // ---- entitlements (docs/entitlements.md) ---------------------------------------

    public static int entitlementList(String[] argv) throws Exception {
        if (argv.length < 2) {
            System.err.println("usage: entitlement.list <tree> [--driver D]");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        String driverName = flag(argv, "--driver");
        DriverSet ds = AsCodeReader.read(tree);
        List<Driver> drivers = driversOf(ds, driverName);
        if (driverName != null && drivers.isEmpty()) {
            System.err.println("no driver '" + driverName + "'");
            return 1;
        }
        int n = 0;
        for (Driver d : drivers) {
            for (com.pointblue.dirxml.dev.model.Entitlement e : d.entitlements) {
                n++;
                System.out.printf("%-40s conflict=%-9s multi-valued=%-5s display-name=%-20s%s%n",
                    d.name + "/" + e.name, str(e.conflictResolution()), str(e.multiValued()),
                    str(e.displayName()), pkgMark(e.meta));
            }
        }
        System.out.println(n + " entitlement(s)");
        return 0;
    }

    public static int entitlementShow(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("usage: entitlement.show <tree> --driver D --name N [--json]");
            return 2;
        }
        Path tree = Paths.get(argv[1]);
        String driverName = flag(argv, "--driver");
        String name = flag(argv, "--name");
        boolean json = hasFlag(argv, "--json");
        if (name == null || name.isBlank()) {
            System.err.println("usage: entitlement.show <tree> --driver D --name N [--json]");
            return 2;
        }
        DriverSet ds = AsCodeReader.read(tree);
        com.pointblue.dirxml.dev.edit.EntitlementOps.Found found = com.pointblue.dirxml.dev.edit.EntitlementOps.find(ds, name, driverName);
        if (found == null) {
            System.err.println("no entitlement '" + name + "'" + (driverName == null ? "" : " on driver '" + driverName + "'"));
            return 1;
        }
        com.pointblue.dirxml.dev.model.Entitlement e = found.entitlement;
        List<String> refs = com.pointblue.dirxml.dev.edit.EntitlementOps.referencingPrds(ds, found.driver, e);
        if (json) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("driver", found.driver.name);
            out.put("name", e.name);
            out.put("displayName", e.displayName());
            out.put("description", e.description());
            out.put("conflictResolution", e.conflictResolution());
            out.put("multiValued", e.multiValued());
            out.put("document", e.definition == null ? null : serialize(e.definition));
            out.put("packaged", e.meta.containsKey("dirxml-pkgguid"));
            out.put("customized", "true".equals(e.meta.get(Packages.CUSTOMIZED_KEY)));
            out.put("referencedByPrds", refs);
            System.out.println(Json.pretty(out));
            return 0;
        }
        System.out.println(found.driver.name + "/" + e.name + pkgMark(e.meta));
        System.out.println("  display-name:       " + str(e.displayName()));
        System.out.println("  description:        " + str(e.description()));
        System.out.println("  conflict-resolution: " + str(e.conflictResolution()));
        System.out.println("  multi-valued:       " + str(e.multiValued()));
        System.out.println("  referenced by " + refs.size() + " PRD provision activity(ies): " + refs);
        if (e.definition != null) {
            System.out.println("  document:");
            System.out.println("    " + serialize(e.definition).replace("\n", "\n    "));
        }
        return 0;
    }

    private static String pkgMark(Map<String, String> meta) {
        boolean packaged = meta.containsKey("dirxml-pkgguid");
        boolean customized = "true".equals(meta.get(Packages.CUSTOMIZED_KEY));
        return packaged ? (customized ? "  [packaged, customized]" : "  [packaged]") : "";
    }

    // ---- provisioning helpers -----------------------------------------------------

    private static final class Found {
        final Driver driver;
        final Form form;
        Found(Driver driver, Form form) {
            this.driver = driver;
            this.form = form;
        }
    }

    private static final class Bound {
        final Prd prd;
        final Prd.FormBinding binding;
        Bound(Prd prd, Prd.FormBinding binding) {
            this.prd = prd;
            this.binding = binding;
        }
    }

    /** Accepts a bare name, {@code kind/name}, or {@code driver/kind/name}. */
    private static Found findForm(DriverSet ds, String ref, String driverFlag) {
        String[] parts = ref.split("/", -1);
        String driverName = driverFlag;
        Form.Kind kind = null;
        String name;
        if (parts.length == 3) {
            driverName = parts[0];
            kind = Form.Kind.byDir(parts[1]);
            name = parts[2];
        } else if (parts.length == 2) {
            kind = Form.Kind.byDir(parts[0]);
            name = parts[1];
        } else {
            name = ref;
        }
        for (Driver d : driversOf(ds, driverName)) {
            if (d.provisioning == null) {
                continue;
            }
            Form f = kind != null ? d.provisioning.form(kind, name) : d.provisioning.formByName(name);
            if (f != null) {
                return new Found(d, f);
            }
        }
        return null;
    }

    /** Every PRD binding (on any driver in scope) that references {@code formName}. */
    private static List<Bound> formBindings(DriverSet ds, Driver formDriver, String formName) {
        List<Bound> out = new ArrayList<>();
        Provisioning p = formDriver.provisioning;
        if (p == null) {
            return out;
        }
        for (Prd prd : p.prds) {
            for (Prd.FormBinding b : prd.bindings()) {
                if (formName.equals(b.formId)) {
                    out.add(new Bound(prd, b));
                }
            }
        }
        return out;
    }

    private static List<String> fieldNames(Prd.FormBinding b) {
        List<String> out = new ArrayList<>();
        for (Prd.Field f : b.fields) {
            out.add(f.name);
        }
        return out;
    }

    /** Descendant elements under a PRD's {@code <process>} that carry a non-empty {@code id} — its activities. */
    private static List<String> activityIds(Element process) {
        List<String> out = new ArrayList<>();
        if (process == null) {
            return out;
        }
        collectIds(process, out);
        return out;
    }

    private static void collectIds(Element el, List<String> out) {
        // workflow activities carry activity-id (start-activity, user-activity,
        // finish-activity, …); the process root itself uses plain id — never counted.
        String id = el.getAttribute("activity-id");
        String ln = el.getLocalName() != null ? el.getLocalName() : el.getNodeName();
        if (!id.isEmpty()) {
            out.add(ln + " id=" + id);
        }
        for (Element c : Xds.childElements(el)) {
            collectIds(c, out);
        }
    }

    private static FormDocument safeDocument(Form f) {
        try {
            return f.document();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int countInputFields(FormDocument doc) {
        int n = 0;
        for (FormDocument.Component c : doc.components()) {
            if (c.input) {
                n++;
            }
        }
        return n;
    }

    private static String formPkgMark(Map<String, String> meta) {
        boolean packaged = meta.containsKey("dirxml-pkgguid") || meta.containsKey("project.package-id");
        return packaged ? "  [packaged]" : "";
    }

    private static List<Driver> driversOf(DriverSet ds, String driverName) {
        if (driverName == null) {
            return ds.drivers;
        }
        Driver d = ds.driver(driverName);
        return d == null ? List.of() : List.of(d);
    }

    private static String flag(String[] argv, String name) {
        for (int i = 0; i < argv.length - 1; i++) {
            if (argv[i].equals(name)) {
                return argv[i + 1];
            }
        }
        return null;
    }

    private static boolean hasFlag(String[] argv, String name) {
        for (String a : argv) {
            if (a.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
