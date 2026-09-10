package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.edit.Operation;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code package.adopt}: derive the installed-package records
 * ({@code package.installed.<SHORT>} in the driver / driver-set manifest) from
 * the objects' package stamps — for trees imported from a vault or a project,
 * which carry the stamps but no record. Idempotent; never touches content.
 */
public final class PackageAdopt implements Operation {

    private final String driverName;   // null = every driver and the Library
    private final Catalog catalog;     // optional: resolves project-style ids to SHORT names

    public PackageAdopt(String driverName, Catalog catalog) {
        this.driverName = driverName;
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "package.adopt";
    }

    @Override
    public void apply(DriverSet ds, Transaction tx) throws Refusal {
        int written = 0;
        if (driverName == null) {
            written += adopt(ds.meta, ds.library.artifacts(), null, tx);
        }
        for (Driver d : ds.drivers) {
            if (driverName != null && !driverName.equals(d.name)) {
                continue;
            }
            written += adopt(d.meta, d.artifacts(), d, tx);
        }
        if (driverName != null && ds.driver(driverName) == null) {
            throw new Refusal("no driver '" + driverName + "' in the tree");
        }
        tx.note(written + " installed-package record(s) written");
    }

    private int adopt(Map<String, String> meta, List<Artifact> artifacts, Driver d, Transaction tx) {
        Map<String, String> records = new TreeMap<>();
        for (Artifact a : artifacts) {
            String guid = a.meta.get(PackageInstall.META_GUID);
            if (guid != null) {
                PackageStatus.Installed i = PackageStatus.parse(guid);
                records.putIfAbsent(i.shortName, guid);
            } else if (a.meta.get(PackageInstall.META_PACKAGE_ID) != null && catalog != null) {
                String id = a.meta.get(PackageInstall.META_PACKAGE_ID);
                String shortName = catalog.idIndex().get(id);
                Catalog.PackageEntry e = shortName == null ? null : catalog.get(shortName);
                if (e != null) {
                    String version = a.meta.getOrDefault("package-version", e.newestVersion());
                    records.putIfAbsent(shortName, id + ";" + e.symbolicName + ";" + version + ";" + e.displayName + ";" + shortName);
                    tx.note("'" + a.path() + "': package version taken from the catalog (" + version + ") — the project records only the id");
                }
            }
        }
        String own = meta.get(PackageInstall.META_GUID);
        if (own != null) {
            PackageStatus.Installed i = PackageStatus.parse(own);
            records.put(i.shortName, own + ";base");
        }
        int written = 0;
        for (Map.Entry<String, String> r : records.entrySet()) {
            String key = PackageInstall.META_INSTALLED_PREFIX + r.getKey();
            if (!r.getValue().equals(meta.get(key))) {
                meta.put(key, r.getValue());
                written++;
                tx.note((d == null ? "library" : d.name) + ": " + key + " = " + r.getValue());
            }
        }
        return written;
    }
}
