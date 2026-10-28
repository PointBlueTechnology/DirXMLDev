package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.Args;
import com.pointblue.dirxml.dev.model.AppObject;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code idm package.<cmd> …} — the catalog commands (docs/packages.md §3.1):
 * <pre>
 *   package.fetch   --catalog DIR [--site NAME|URL] [--short SHORT[_ver]…] [--all-versions] [--dry-run] [--json]
 *   package.import  --catalog DIR &lt;jar|dir&gt; [--json]
 *   package.list    --catalog DIR [--driver-type ID] [--type 2|3|4] [--base] [--json]
 *   package.show    --catalog DIR SHORT[_ver] [--json]
 *   package.diff    --catalog DIR SHORT_v1 SHORT_v2 [--json]
 *   package.resolve --catalog DIR --base SHORT[_ver] [--feature SHORT…] [--driver-set-has SHORT_ver…] [--vault-has SHORT_ver…] [--json]
 * </pre>
 * Exit codes: 0 ok, 1 refusal, 2 usage, 3 error (thrown exceptions propagate to the caller, {@code Cli} maps those to 3).
 */
public final class PackageCli {

    private PackageCli() {
    }

    /** What each catalog command takes (docs/commands.md): its flags, its options, and how many positionals. */
    static final Map<String, Args.Spec> SPECS = Map.ofEntries(
        Map.entry("package.fetch", Args.Spec.of("all-versions dry-run", "catalog site short")),
        Map.entry("package.import", Args.Spec.of("", "catalog")),
        Map.entry("package.list", Args.Spec.of("base", "catalog driver-type type")),
        Map.entry("package.show", Args.Spec.of("", "catalog")),
        Map.entry("package.diff", Args.Spec.of("", "catalog")),
        Map.entry("package.resolve", Args.Spec.of("", "catalog base feature driver-set-has vault-has")),
        Map.entry("package.build", Args.Spec.of("base",
            "catalog short name driver vendor version description readme include new-version-of depends customized gcvs supported-driver")),
        Map.entry("package.site", Args.Spec.of("", "catalog out description")));

    /** The positionals each command takes, and the usage line that names them. */
    private static final Map<String, Integer> POSITIONALS = Map.of(
        "package.import", 1, "package.show", 1, "package.diff", 2, "package.build", 1);
    private static final Map<String, String> USAGE = Map.ofEntries(
        Map.entry("package.fetch", "usage: package.fetch --catalog DIR --short SHORT[_ver]… [--site NAME|URL] [--all-versions] [--dry-run] [--json]"),
        Map.entry("package.import", "usage: package.import --catalog DIR <jar|dir>"),
        Map.entry("package.list", "usage: package.list --catalog DIR [--driver-type ID] [--type 2|3|4] [--base] [--json]"),
        Map.entry("package.show", "usage: package.show --catalog DIR SHORT[_ver]"),
        Map.entry("package.diff", "usage: package.diff --catalog DIR SHORT_v1 SHORT_v2"),
        Map.entry("package.resolve", "usage: package.resolve --catalog DIR --base SHORT[_ver] [--feature SHORT…] [--driver-set-has SHORT_ver…] [--vault-has SHORT_ver…]"),
        Map.entry("package.build", "usage: package.build --catalog DIR <tree> --short SHORT --name \"Display Name\" [--driver D] [--vendor V] [--version M.m.r]"
            + " [--description …] [--readme FILE] [--include path]… [--new-version-of SHORT_ver|jar] [--depends SHORT[_ver]]… [--base] [--customized keep] [--gcvs referenced|all|none] [--supported-driver ID]… [--json]"),
        Map.entry("package.site", "usage: package.site --catalog DIR --out DIR [--description …] [--json]"));

    static final List<String> GCVS = List.of("referenced", "all", "none");
    static final List<String> CUSTOMIZED = List.of("refuse", "keep");

    public static int run(String[] argv) throws IOException, InterruptedException {
        String cmd = argv[0];
        if (cmd.equals("package.status")) {
            return PackageStatus.cli(argv);
        }
        Args.Spec spec = SPECS.get(cmd);
        if (spec == null) {
            System.err.println("unknown command " + cmd);
            return 2;
        }
        Args a;
        try {
            a = Args.parse(cmd, argv, 1, spec);
            // Everything the command line alone decides is checked here, before the catalog
            // directory is opened (opening creates it).
            if (a.first("catalog") == null) {
                throw new Args.UsageError("--catalog <dir> is required");
            }
            int want = POSITIONALS.getOrDefault(cmd, 0);
            if (a.positional.size() != want) {
                throw new Args.UsageError((a.positional.size() > want ? "unexpected argument '" + a.positional.get(want) + "'\n" : "") + USAGE.get(cmd));
            }
            switch (cmd) {
                case "package.list" -> a.intOpt("type", 0);
                case "package.resolve" -> require(a, "base", cmd);
                case "package.site" -> require(a, "out", cmd);
                case "package.build" -> {
                    require(a, "short", cmd);
                    require(a, "name", cmd);
                    a.oneOf("gcvs", "referenced", GCVS);
                    a.oneOf("customized", "refuse", CUSTOMIZED);
                }
                default -> {
                }
            }
        } catch (Args.UsageError e) {
            System.err.println(e.getMessage());
            return 2;
        }
        boolean json = a.has("json");
        List<String> pos = a.positional;
        Catalog catalog = Catalog.open(Paths.get(a.first("catalog")));

        try {
            switch (cmd) {
                case "package.fetch":
                    return fetch(catalog, a, json);
                case "package.import":
                    return doImport(catalog, pos, json);
                case "package.list":
                    return list(catalog, a, json);
                case "package.show":
                    return show(catalog, pos, json);
                case "package.diff":
                    return diff(catalog, pos, json);
                case "package.resolve":
                    return resolve(catalog, a, json);
                case "package.build":
                    return build(catalog, pos, a, json);
                case "package.site":
                    return site(catalog, a, json);
                default:
                    System.err.println("unknown command " + cmd);
                    return 2;
            }
        } catch (Args.UsageError e) {
            // checked above; here only for the methods' signatures
            System.err.println(e.getMessage());
            return 2;
        }
    }

    private static void require(Args a, String option, String cmd) throws Args.UsageError {
        if (a.first(option) == null) {
            throw new Args.UsageError("--" + option + " is required\n" + USAGE.get(cmd));
        }
    }

    // ---- package.fetch ----

    private static int fetch(Catalog catalog, Args opts, boolean json) throws IOException, InterruptedException {
        String siteSpec = opts.first("site");
        String siteUrl = siteSpec;
        if (siteSpec == null) {
            siteUrl = Catalog.DEFAULT_SITE_2;
        } else if (!siteSpec.startsWith("http")) {
            Map<String, String> sites = catalog.sites();
            siteUrl = sites.get(siteSpec);
            if (siteUrl == null) {
                System.err.println("unknown site '" + siteSpec + "' (see sites.properties)");
                return 2;
            }
        }
        List<String> shorts = opts.all("short");
        boolean allVersions = opts.has("all-versions");
        boolean dryRun = opts.has("dry-run");
        if (shorts.isEmpty()) {
            System.err.println("refused: --short SHORT[_ver] is required (fetching the whole site is not supported)");
            return 1;
        }
        UpdateSite.FetchResult r = UpdateSite.fetch(catalog, siteUrl, shorts, allVersions, dryRun);
        StringBuilder sb = new StringBuilder();
        for (String a : r.added) {
            sb.append("added    ").append(a).append('\n');
        }
        for (String s : r.skipped) {
            sb.append("skipped  ").append(s).append('\n');
        }
        for (String f : r.refused) {
            sb.append("REFUSED  ").append(f).append('\n');
        }
        if (r.error != null) {
            sb.append("ERROR: ").append(r.error).append('\n');
        }
        System.out.print(json ? fetchJson(r) : sb.toString());
        return r.error != null ? 1 : (r.refused.isEmpty() ? 0 : 1);
    }

    private static String fetchJson(UpdateSite.FetchResult r) {
        StringBuilder sb = new StringBuilder("{\"added\":").append(jsonArr(r.added));
        sb.append(",\"skipped\":").append(jsonArr(r.skipped));
        sb.append(",\"refused\":").append(jsonArr(r.refused));
        sb.append(",\"error\":").append(r.error == null ? "null" : q(r.error));
        return sb.append("}\n").toString();
    }

    // ---- package.import ----

    private static int doImport(Catalog catalog, List<String> pos, boolean json) throws IOException {
        Path src = Paths.get(pos.get(0));
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(src)) {
            try (var s = Files.list(src)) {
                jars.addAll(s.filter(p -> p.toString().endsWith(".jar")).sorted().toList());
            }
        } else {
            jars.add(src);
        }
        List<Catalog.AddResult> results = new ArrayList<>();
        int refusals = 0;
        for (Path jar : jars) {
            Catalog.AddResult r = catalog.add(jar, "import:" + jar);
            results.add(r);
            if (!r.ok()) {
                refusals++;
            }
        }
        if (json) {
            List<String> items = new ArrayList<>();
            for (Catalog.AddResult r : results) {
                items.add("{\"jar\":" + q(r.shortName == null ? "" : r.shortName) + ",\"version\":" + q(r.version == null ? "" : r.version)
                    + ",\"added\":" + r.added + ",\"refusal\":" + (r.refusal == null ? "null" : q(r.refusal)) + "}");
            }
            System.out.println("{\"results\":[" + String.join(",", items) + "]}");
        } else {
            for (Catalog.AddResult r : results) {
                if (!r.ok()) {
                    System.out.println("REFUSED  " + r.refusal);
                } else if (r.added) {
                    System.out.println("added    " + r.shortName + "_" + r.version);
                } else {
                    System.out.println("unchanged " + r.shortName + "_" + r.version);
                }
            }
        }
        return refusals > 0 ? 1 : 0;
    }

    // ---- package.list ----

    private static int list(Catalog catalog, Args opts, boolean json) throws Args.UsageError {
        Integer typeFilter = opts.first("type") == null ? null : opts.intOpt("type", 0);
        boolean baseOnly = opts.has("base");
        String driverType = opts.first("driver-type");
        List<Catalog.PackageEntry> matched = new ArrayList<>();
        for (Catalog.PackageEntry e : catalog.packages().values()) {
            if (typeFilter != null && e.type != typeFilter) {
                continue;
            }
            if (baseOnly && !e.base) {
                continue;
            }
            if (driverType != null && !supportsDriver(e, driverType)) {
                continue;
            }
            matched.add(e);
        }
        matched.sort((a, b) -> a.shortName.compareTo(b.shortName));
        if (json) {
            List<String> items = new ArrayList<>();
            for (Catalog.PackageEntry e : matched) {
                items.add("{\"short\":" + q(e.shortName) + ",\"id\":" + q(e.id) + ",\"type\":" + e.type
                    + ",\"base\":" + e.base + ",\"versions\":" + jsonArr(new ArrayList<>(e.versions.keySet())) + "}");
            }
            System.out.println("{\"packages\":[" + String.join(",", items) + "]}");
        } else {
            for (Catalog.PackageEntry e : matched) {
                System.out.printf("%-16s type=%d%s  %s%n", e.shortName, e.type, e.base ? " base" : "",
                    String.join(", ", e.versions.keySet()));
            }
            System.out.println(matched.size() + " package(s)");
        }
        return 0;
    }

    private static boolean supportsDriver(Catalog.PackageEntry e, String driverType) {
        for (Catalog.VersionEntry ve : e.versions.values()) {
            for (Dependency.SupportedDriver sd : ve.supportedDrivers) {
                if (driverType.equals(sd.id) || driverType.equals(sd.driverId)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- package.show ----

    private static int show(Catalog catalog, List<String> pos, boolean json) throws IOException {
        String[] sv = Resolver.splitSpec(pos.get(0));
        Catalog.PackageEntry e = catalog.get(sv[0]);
        if (e == null) {
            System.err.println("no such package in the catalog: " + sv[0]);
            return 1;
        }
        String version = sv[1] != null ? sv[1] : e.newestVersion();
        if (!e.versions.containsKey(version)) {
            System.err.println(sv[0] + " has no version " + version + " in the catalog");
            return 1;
        }
        Path jarPath = catalog.dir.resolve("jars").resolve(sv[0]).resolve(sv[0] + "_" + version + ".jar");
        PackageJar p = PackageJar.read(jarPath);
        Catalog.VersionEntry ve = e.versions.get(version);
        Map<String, String> idToShort = catalog.idIndex();

        List<String> objectLines = new ArrayList<>();
        List<String> promptLines = new ArrayList<>();
        List<PackageJar.Item> sorted = new ArrayList<>(p.items);
        sorted.sort(Comparator.<PackageJar.Item, Integer>comparing(it -> it.folderId).thenComparing(it -> it.name));
        for (PackageJar.Item it : sorted) {
            String placement = "";
            String weight = "";
            if (it.directive != null) {
                Element root = NxslCanonical.parse(it.directive).getDocumentElement();
                Element pl = PackageChecksum.child(root, "placement");
                if (pl != null) {
                    placement = pl.getAttribute("location");
                    if (!pl.getAttribute("context").isEmpty()) {
                        placement = pl.getAttribute("context") + "/" + placement;
                    }
                }
                Element linkage = PackageChecksum.child(root, "policy-linkage");
                Element ps = linkage == null ? null : PackageChecksum.child(linkage, "policy-set");
                if (ps != null) {
                    String order = ps.getAttribute("order");
                    weight = "Weight".equals(order) ? ps.getAttribute("value") : order;
                }
            }
            String line = String.format("  [%d-%s] %-22s %-28s placement=%-24s weight=%s",
                it.folderId, it.folderName, it.objectClass, it.name, placement, weight);
            boolean isPrompt = it.contentType != null && it.contentType.toLowerCase().contains("pkg-prompt");
            (isPrompt ? promptLines : objectLines).add(line);
        }

        if (json) {
            System.out.println(showJson(e, version, ve, idToShort, sorted, p.provisioningObjects.size()));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(e.shortName).append(" ").append(version).append('\n');
        sb.append("  id: ").append(e.id).append("  symbolic-name: ").append(e.symbolicName).append('\n');
        sb.append("  type: ").append(e.type).append(typeLabel(e.type)).append("  base: ").append(e.base).append('\n');
        sb.append("  versions in catalog: ").append(String.join(", ", e.versions.keySet())).append('\n');
        sb.append("  supported drivers: ").append(ve.supportedDrivers.isEmpty() ? "(any)" :
            ve.supportedDrivers.stream().map(d -> d.displayName + " [" + d.id + "]").collect(Collectors.joining(", "))).append('\n');
        sb.append("  mandatory features:\n");
        for (Dependency.Feature f : ve.features) {
            if (f.mandatory) {
                sb.append("    - ").append(f.displayName).append(featureShort(f, idToShort)).append('\n');
            }
        }
        sb.append("  optional features:\n");
        for (Dependency.Feature f : ve.features) {
            if (!f.mandatory) {
                sb.append("    - ").append(f.group != null ? "[" + f.group + "] " : "").append(f.displayName)
                    .append(featureShort(f, idToShort)).append('\n');
            }
        }
        sb.append("  dependencies:\n");
        for (Dependency d : ve.dependencies) {
            String depShort = idToShort.get(d.packageId);
            sb.append("    - ").append(d.name).append(" [").append(d.packageId).append(depShort != null ? " = " + depShort : "")
                .append("] type=").append(d.type).append(" ").append(d.constraintText()).append('\n');
        }
        sb.append("  prompts:\n");
        for (String l : promptLines) {
            sb.append(l).append('\n');
        }
        sb.append("  objects:\n");
        for (String l : objectLines) {
            sb.append(l).append('\n');
        }
        if (!p.provisioningObjects.isEmpty()) {
            Map<String, Integer> byKind = new java.util.TreeMap<>();
            for (AppObject o : p.provisioningObjects) {
                byKind.merge(o.kind().key, 1, Integer::sum);
            }
            sb.append("  provisioning (AppConfig) objects: ").append(p.provisioningObjects.size()).append("  ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> k : byKind.entrySet()) {
                parts.add(k.getValue() + " " + k.getKey());
            }
            sb.append(String.join(", ", parts)).append("   (objects/<n>-Provisioning/ in the catalog)\n");
        }
        System.out.print(sb);
        return 0;
    }

    private static String featureShort(Dependency.Feature f, Map<String, String> idToShort) {
        String s = idToShort.get(f.packageId);
        return " [" + f.packageId + (s != null ? " = " + s : "") + "]";
    }

    private static String typeLabel(int type) {
        return switch (type) {
            case 2 -> " (driver)";
            case 3 -> " (driver set)";
            case 4 -> " (identity vault)";
            default -> "";
        };
    }

    private static String showJson(Catalog.PackageEntry e, String version, Catalog.VersionEntry ve,
                                    Map<String, String> idToShort, List<PackageJar.Item> items, int provisioningObjectCount) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"short\":").append(q(e.shortName)).append(",\"version\":").append(q(version));
        sb.append(",\"id\":").append(q(e.id)).append(",\"symbolicName\":").append(q(e.symbolicName));
        sb.append(",\"type\":").append(e.type).append(",\"base\":").append(e.base);
        sb.append(",\"versions\":").append(jsonArr(new ArrayList<>(e.versions.keySet())));
        List<String> sds = new ArrayList<>();
        for (Dependency.SupportedDriver d : ve.supportedDrivers) {
            sds.add("{\"displayName\":" + q(d.displayName) + ",\"driverId\":" + q(d.driverId) + ",\"id\":" + q(d.id) + "}");
        }
        sb.append(",\"supportedDrivers\":[").append(String.join(",", sds)).append("]");
        List<String> mand = new ArrayList<>();
        List<String> opt = new ArrayList<>();
        for (Dependency.Feature f : ve.features) {
            String j = "{\"packageId\":" + q(f.packageId) + ",\"short\":" + q(idToShort.get(f.packageId))
                + ",\"displayName\":" + q(f.displayName) + ",\"group\":" + (f.group == null ? "null" : q(f.group)) + "}";
            (f.mandatory ? mand : opt).add(j);
        }
        sb.append(",\"featuresMandatory\":[").append(String.join(",", mand)).append("]");
        sb.append(",\"featuresOptional\":[").append(String.join(",", opt)).append("]");
        List<String> deps = new ArrayList<>();
        for (Dependency d : ve.dependencies) {
            deps.add("{\"name\":" + q(d.name) + ",\"packageId\":" + q(d.packageId) + ",\"short\":" + q(idToShort.get(d.packageId))
                + ",\"type\":" + d.type + ",\"constraint\":" + q(d.constraintText()) + "}");
        }
        sb.append(",\"dependencies\":[").append(String.join(",", deps)).append("]");
        List<String> objs = new ArrayList<>();
        for (PackageJar.Item it : items) {
            objs.add("{\"folderId\":" + it.folderId + ",\"folderName\":" + q(it.folderName) + ",\"class\":" + q(it.objectClass)
                + ",\"name\":" + q(it.name) + "}");
        }
        sb.append(",\"objects\":[").append(String.join(",", objs)).append("]");
        sb.append(",\"provisioningObjects\":").append(provisioningObjectCount);
        return sb.append('}').toString();
    }

    // ---- package.diff ----

    private static int diff(Catalog catalog, List<String> pos, boolean json) throws IOException {
        PackageJar p1 = readCataloged(catalog, pos.get(0));
        PackageJar p2 = readCataloged(catalog, pos.get(1));
        if (p1 == null || p2 == null) {
            System.err.println("not in the catalog: " + (p1 == null ? pos.get(0) : pos.get(1)));
            return 1;
        }
        PackageDiff d = PackageDiff.of(p1, p2);
        System.out.print(json ? d.json() + "\n" : d.text());
        return d.isEmpty() ? 0 : 1;
    }

    private static PackageJar readCataloged(Catalog catalog, String spec) throws IOException {
        String[] sv = Resolver.splitSpec(spec);
        Catalog.PackageEntry e = catalog.get(sv[0]);
        if (e == null) {
            return null;
        }
        String version = sv[1] != null ? sv[1] : e.newestVersion();
        if (version == null || !e.versions.containsKey(version)) {
            return null;
        }
        Path jarPath = catalog.dir.resolve("jars").resolve(sv[0]).resolve(sv[0] + "_" + version + ".jar");
        if (!Files.exists(jarPath)) {
            return null;
        }
        return PackageJar.read(jarPath);
    }

    // ---- package.resolve ----

    private static int resolve(Catalog catalog, Args opts, boolean json) {
        String base = opts.first("base");
        List<String> features = opts.all("feature");
        Set<String> driverSetHas = new LinkedHashSet<>(opts.all("driver-set-has"));
        Set<String> vaultHas = new LinkedHashSet<>(opts.all("vault-has"));
        Resolver.Result r = Resolver.resolve(catalog, base, features, driverSetHas, vaultHas);
        if (json) {
            System.out.println(resolveJson(r));
        } else {
            if (!r.ok()) {
                System.out.println("REFUSED — " + r.refusal);
                return 1;
            }
            System.out.println("install (in order):");
            for (Resolver.Chosen c : r.install) {
                System.out.println("  " + c.shortName + "_" + c.version + "  — " + c.reason);
            }
            if (!r.missing.isEmpty()) {
                System.out.println("missing (not installed by this resolve):");
                for (Resolver.Missing m : r.missing) {
                    System.out.println("  " + m);
                }
            }
        }
        return r.ok() ? 0 : 1;
    }

    private static String resolveJson(Resolver.Result r) {
        StringBuilder sb = new StringBuilder("{\"refusal\":").append(r.refusal == null ? "null" : q(r.refusal));
        List<String> install = new ArrayList<>();
        for (Resolver.Chosen c : r.install) {
            install.add("{\"short\":" + q(c.shortName) + ",\"version\":" + q(c.version) + ",\"reason\":" + q(c.reason) + "}");
        }
        sb.append(",\"install\":[").append(String.join(",", install)).append("]");
        List<String> missing = new ArrayList<>();
        for (Resolver.Missing m : r.missing) {
            missing.add("{\"name\":" + q(m.name) + ",\"packageId\":" + q(m.packageId) + ",\"constraint\":" + q(m.constraint)
                + ",\"neededBy\":" + q(m.neededBy) + "}");
        }
        sb.append(",\"missing\":[").append(String.join(",", missing)).append("]}");
        return sb.toString();
    }

    // ---- shared helpers ----

    // ---- package.build ----

    private static int build(Catalog catalog, List<String> pos, Args opts, boolean json) throws IOException, Args.UsageError {
        PackageBuilder.Options o = new PackageBuilder.Options();
        o.tree = Paths.get(pos.get(0));
        o.driver = opts.first("driver");
        o.shortName = opts.first("short");
        o.name = opts.first("name");
        if (opts.first("vendor") != null) {
            o.vendor = opts.first("vendor");
        }
        if (opts.first("version") != null) {
            o.version = opts.first("version");
        }
        if (opts.first("description") != null) {
            o.description = opts.first("description");
        }
        if (opts.first("readme") != null) {
            o.readme = java.nio.file.Files.readString(Paths.get(opts.first("readme")));
        }
        o.include.addAll(opts.all("include"));
        o.depends.addAll(opts.all("depends"));
        o.base = opts.has("base");
        o.customized = opts.oneOf("customized", o.customized, CUSTOMIZED);
        o.gcvs = opts.oneOf("gcvs", o.gcvs, GCVS);
        o.supportedDrivers.addAll(opts.all("supported-driver"));
        o.catalog = catalog;
        if (opts.first("new-version-of") != null) {
            String spec = opts.first("new-version-of");
            o.newVersionOf = spec.endsWith(".jar") ? Paths.get(spec) : PackageInstall.jarOf(null, catalog.dir.toString(), spec);
        }
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("pkgbuild");
        PackageBuilder.Result r = PackageBuilder.build(o, tmp);
        if (r.ok) {
            Catalog.AddResult a = catalog.add(r.jar, "built");
            if (!a.ok()) {
                r.ok = false;
                r.refusal = "built, but the catalog refused it: " + a.refusal;
            } else {
                r.jar = catalog.dir.resolve("jars").resolve(a.shortName).resolve(a.shortName + "_" + a.version + ".jar");
                r.notes.add("added to the catalog as " + a.shortName + "_" + a.version + " (sha256 " + a.sha256.substring(0, 12) + "…)");
            }
        }
        System.out.print(json ? r.json() + "\n" : r.text());
        return r.ok ? 0 : 1;
    }

    // ---- package.site ----

    private static int site(Catalog catalog, Args opts, boolean json) throws IOException {
        String out = opts.first("out");
        String description = opts.first("description") == null ? "Packages built with DirXMLDev" : opts.first("description");
        List<String> written = SiteWriter.write(catalog, Paths.get(out), description);
        if (json) {
            System.out.println("{\"out\":" + q(out) + ",\"features\":" + jsonArr(written) + "}");
        } else {
            System.out.println("package.site: wrote " + out + " (" + written.size() + " feature(s))");
            for (String w : written) {
                System.out.println("  " + w);
            }
        }
        return 0;
    }

    private static String jsonArr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(q(items.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        return com.pointblue.dirxml.dev.deploy.DeployLog.q(s);
    }
}
