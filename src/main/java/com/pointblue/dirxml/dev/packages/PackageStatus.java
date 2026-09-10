package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.deploy.DeployLog;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code package.status}: per target (driver, or the Library), the packages
 * installed (the manifest record, cross-checked against the object stamps),
 * the customized objects (installed checksum ≠ recomputed), and — with a
 * catalog — whether each version is present and whether a newer one exists.
 * Read-only; the tree is the only input.
 */
public final class PackageStatus {

    /** One installed package on one target. */
    public static final class Installed {
        public String shortName;
        public String id;
        public String version;
        public String name;
        public boolean base;
        public boolean inManifest;        // driver meta package.installed.<SHORT>
        public int objects;               // stamped objects that name this package
        public String inCatalog;          // null = no catalog; "yes" | "no"
        public String newer;              // newest catalog version if newer, else null
    }

    public static final class Target {
        public String name;               // driver name or "library"
        public final List<Installed> packages = new ArrayList<>();
        public final List<String> customized = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
    }

    public final List<Target> targets = new ArrayList<>();

    public static PackageStatus of(Path tree, String onlyDriver, Catalog catalog) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        PackageStatus st = new PackageStatus();
        if (onlyDriver == null) {
            Target lib = target("library", ds.meta, ds.library.artifacts(), ds, null, catalog);
            if (!lib.packages.isEmpty() || !lib.customized.isEmpty()) {
                st.targets.add(lib);
            }
        }
        for (Driver d : ds.drivers) {
            if (onlyDriver != null && !onlyDriver.equals(d.name)) {
                continue;
            }
            st.targets.add(target(d.name, d.meta, d.artifacts(), ds, d, catalog));
        }
        return st;
    }

    static Target target(String name, Map<String, String> meta, List<Artifact> artifacts, DriverSet ds, Driver d, Catalog catalog) {
        Target t = new Target();
        t.name = name;
        Map<String, Installed> byShort = new TreeMap<>();
        // 1. the manifest record
        for (Map.Entry<String, String> m : meta.entrySet()) {
            if (m.getKey().startsWith(PackageInstall.META_INSTALLED_PREFIX)) {
                Installed i = parse(m.getValue());
                i.inManifest = true;
                byShort.put(i.shortName, i);
            }
        }
        // 2. the object stamps
        for (Artifact a : artifacts) {
            String guid = a.meta.get(PackageInstall.META_GUID);
            if (guid == null) {
                if (a.meta.get(PackageInstall.META_PACKAGE_ID) != null) {
                    // a project/export tree: id only — name it by id until adopted with a catalog
                    String id = a.meta.get(PackageInstall.META_PACKAGE_ID);
                    Installed i = byShort.computeIfAbsent(shortFor(id, catalog), k -> {
                        Installed n = new Installed();
                        n.shortName = k;
                        n.id = id;
                        return n;
                    });
                    i.objects++;
                }
                continue;
            }
            Installed i = parse(guid);
            Installed have = byShort.get(i.shortName);
            if (have == null) {
                byShort.put(i.shortName, i);
                have = i;
            }
            have.objects++;
            // 3. customized?
            String stamped = a.meta.get(PackageInstall.META_CHECKSUM);
            if (stamped != null) {
                long now = InstalledChecksum.of(ds, d, a);
                if (!stamped.trim().equals("" + now) || Packages.isCustomized(a)) {
                    t.customized.add(a.path());
                }
            } else if (Packages.isCustomized(a)) {
                t.customized.add(a.path());
            }
        }
        // the driver's own base record
        String own = meta.get(PackageInstall.META_GUID);
        if (own != null) {
            Installed i = parse(own);
            Installed have = byShort.get(i.shortName);
            if (have == null) {
                i.base = true;
                byShort.put(i.shortName, i);
            } else {
                have.base = true;
            }
        }
        for (Installed i : byShort.values()) {
            if (!i.inManifest && i.objects > 0) {
                t.notes.add(i.shortName + ": objects carry the package but the manifest has no record (package.adopt writes it)");
            }
            if (catalog != null && i.shortName != null) {
                Catalog.PackageEntry e = catalog.get(i.shortName);
                if (e == null || i.version == null || !e.versions.containsKey(i.version)) {
                    i.inCatalog = "no";
                } else {
                    i.inCatalog = "yes";
                }
                if (e != null) {
                    String newest = e.newestVersion();
                    if (newest != null && i.version != null && PackageInstall.compareVersions(newest, i.version) > 0) {
                        i.newer = newest;
                    }
                }
            }
            t.packages.add(i);
        }
        return t;
    }

    static String shortFor(String id, Catalog catalog) {
        if (catalog != null) {
            String s = catalog.idIndex().get(id);
            if (s != null) {
                return s;
            }
        }
        return "id:" + id;
    }

    /** {@code id;symbolicName;version;name;SHORT[;base]} → an Installed. */
    public static Installed parse(String record) {
        String[] f = record.split(";", -1);
        Installed i = new Installed();
        i.id = f.length > 0 ? f[0] : null;
        i.version = f.length > 2 ? f[2] : null;
        i.name = f.length > 3 ? f[3] : null;
        i.shortName = f.length > 4 && !f[4].isEmpty() ? f[4] : "id:" + i.id;
        i.base = f.length > 5 && "base".equals(f[5]);
        return i;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Target t : targets) {
            sb.append(t.name).append(":\n");
            if (t.packages.isEmpty()) {
                sb.append("  (no packages)\n");
            }
            for (Installed i : t.packages) {
                sb.append(String.format("  %-16s %-26s %s%s%s%s%n", i.shortName, i.version == null ? "?" : i.version,
                    i.base ? "base " : "", i.objects + " object(s)",
                    i.inCatalog == null ? "" : (", in catalog: " + i.inCatalog),
                    i.newer == null ? "" : (", newer available: " + i.newer)));
            }
            for (String c : t.customized) {
                sb.append("  customized ").append(c).append('\n');
            }
            for (String n : t.notes) {
                sb.append("  note ").append(n).append('\n');
            }
        }
        return sb.toString();
    }

    public String json() {
        StringBuilder sb = new StringBuilder("{\"targets\":[");
        boolean ft = true;
        for (Target t : targets) {
            sb.append(ft ? "" : ",").append("{\"name\":").append(DeployLog.q(t.name)).append(",\"packages\":[");
            ft = false;
            boolean fp = true;
            for (Installed i : t.packages) {
                sb.append(fp ? "" : ",").append("{\"short\":").append(DeployLog.q(i.shortName))
                    .append(",\"id\":").append(i.id == null ? "null" : DeployLog.q(i.id))
                    .append(",\"version\":").append(i.version == null ? "null" : DeployLog.q(i.version))
                    .append(",\"base\":").append(i.base).append(",\"inManifest\":").append(i.inManifest)
                    .append(",\"objects\":").append(i.objects)
                    .append(",\"inCatalog\":").append(i.inCatalog == null ? "null" : DeployLog.q(i.inCatalog))
                    .append(",\"newer\":").append(i.newer == null ? "null" : DeployLog.q(i.newer)).append('}');
                fp = false;
            }
            sb.append("],\"customized\":[");
            for (int k = 0; k < t.customized.size(); k++) {
                sb.append(k == 0 ? "" : ",").append(DeployLog.q(t.customized.get(k)));
            }
            sb.append("],\"notes\":[");
            for (int k = 0; k < t.notes.size(); k++) {
                sb.append(k == 0 ? "" : ",").append(DeployLog.q(t.notes.get(k)));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /** {@code package.status <tree> [--driver D] [--catalog DIR] [--json]} */
    public static int cli(String[] args) throws IOException {
        Path tree = null;
        String driver = null;
        Catalog catalog = null;
        boolean json = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--driver") && i + 1 < args.length) {
                driver = args[++i];
            } else if (args[i].equals("--catalog") && i + 1 < args.length) {
                catalog = Catalog.open(Paths.get(args[++i]));
            } else if (args[i].equals("--json")) {
                json = true;
            } else if (tree == null) {
                tree = Paths.get(args[i]);
            }
        }
        if (tree == null) {
            System.err.println("usage: package.status <tree> [--driver D] [--catalog DIR] [--json]");
            return 2;
        }
        PackageStatus st = of(tree, driver, catalog);
        System.out.print(json ? st.json() + "\n" : st.text());
        return 0;
    }
}
