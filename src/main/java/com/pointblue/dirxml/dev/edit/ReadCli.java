package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
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
            if (!shown.contains(set) && !d.links(set).isEmpty() && (set == PolicySet.ECMASCRIPT || set == PolicySet.GCV)) {
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
}
