package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.PackageStamps;
import com.pointblue.dirxml.dev.model.Prd;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code package.strip <tree> --driver D [--library]}: make a driver fully custom — remove every
 * package stamp from the driver and everything under it, so nothing in the tree, the vault or a
 * written Designer project says a package ever installed it.
 *
 * <p>What goes, on the driver and on each of its artifacts, entitlements, forms, PRDs and
 * AppConfig objects: the {@code dirxml-pkg*} metas (the vault's vocabulary: GUID record,
 * association id, checksum, linkage record, the driver's filter-extension cache), a project's or
 * export's {@code package-id} / {@code pkg-assoc-id} / {@code project.*} equivalents, the
 * driver's installed-package records ({@code package.installed.*}), the {@code package.customized}
 * mark with its recorded baseline checksum, and the {@code .package-baseline/} copies. The content
 * itself — policies, GCVs, the filter, entitlements, forms — is untouched; a customized object keeps
 * its customized content, which is now simply its content.
 *
 * <p>The driver is then marked {@link #STRIPPED_KEY} so a deploy knows the missing stamps are a
 * request to remove the vault's (a project or an export never carries some of them, and their
 * absence is otherwise never read as one — see {@code ModelDiff.stampLines}): the deploy deletes
 * the five {@code DirXML-pkg*} attributes and the {@code DirXML-PkgItemAux} class from every object,
 * and the driver's own record, extension cache and both package aux classes. The mark is cleared
 * by the next {@code package.install} on the driver.
 *
 * <p>Library items are shared by every driver in the set, so they stay stamped unless
 * {@code --library} says to strip them (and the driver set's own records) too.
 */
public final class PackageStrip implements Operation {

    /** Driver / driver-set meta: {@code true} once stripped, until a package is installed again. */
    public static final String STRIPPED_KEY = "package.stripped";

    private final String driverName;
    private final boolean library;

    public PackageStrip(String driverName, boolean library) {
        this.driverName = driverName == null || driverName.isBlank() ? null : driverName.trim();
        this.library = library;
    }

    @Override
    public String name() {
        return "package.strip";
    }

    @Override
    public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
        if (driverName == null && !library) {
            throw new Operation.Refusal("--driver D (the driver to strip) or --library (the Library and the driver set's records) is required");
        }
        Path baseRoot = tx.tree().resolve(Packages.BASELINE_DIR);
        int total = 0;
        if (driverName != null) {
            Driver d = ds.driver(driverName);
            if (d == null) {
                throw new Operation.Refusal("no driver '" + driverName + "' in the tree");
            }
            int n = stripDriver(ds, d, tx, baseRoot);
            if (n == 0) {
                throw new Operation.Refusal("'" + d.name + "' carries no package stamps; nothing to strip");
            }
            total += n;
        }
        if (library) {
            int n = stripLibrary(ds, tx);
            if (n == 0 && driverName == null) {
                throw new Operation.Refusal("the Library and the driver set carry no package stamps; nothing to strip");
            }
            total += n;
        }
        tx.note("stripped package stamps from " + total + " object(s); the tree now describes a fully custom "
            + (driverName != null ? "driver" : "Library") + " and the next deploy removes the vault's stamps too");
    }

    private int stripDriver(DriverSet ds, Driver d, Transaction tx, Path baseRoot) throws IOException {
        int n = 0;
        List<String> stripped = new ArrayList<>();
        if (strip(d.meta)) {
            n++;
            stripped.add("the driver's own record");
        }
        for (Artifact a : d.artifacts()) {
            if (strip(a.meta)) {
                n++;
                tx.dropBaseline(a);
                tx.touched(a.path());
            }
        }
        for (Entitlement e : d.entitlements) {
            if (strip(e.meta)) {
                n++;
                String p = EntitlementOps.path(d, e);
                tx.dropBaseline(baseRoot.resolve(p + ".xml"));
                tx.touched(p);
            }
        }
        if (d.provisioning != null) {
            for (Form f : d.provisioning.forms) {
                if (strip(f.meta)) {
                    n++;
                    String p = FormOps.path(d, f);
                    tx.dropBaseline(baseRoot.resolve(p + ".form.json"));
                    tx.touched(p);
                }
            }
            for (Prd prd : d.provisioning.prds) {
                if (strip(prd.meta)) {
                    n++;
                    String p = FormOps.prdPath(d, prd);
                    tx.dropBaseline(baseRoot.resolve(p).resolve("definition.xml"));
                    tx.dropBaseline(baseRoot.resolve(p).resolve("request.xml"));
                    tx.touched(p);
                }
            }
            for (AppObject o : d.provisioning.objects) {
                if (strip(o.meta)) {
                    n++;
                    String p = AppConfigOps.path(d, o);
                    tx.dropBaseline(baseRoot.resolve(p + ".xml"));
                    tx.touched(p);
                }
            }
        }
        if (n > 0) {
            d.meta.put(STRIPPED_KEY, "true");
            tx.touched("drivers/" + d.name);
            tx.note("'" + d.name + "': " + n + " object(s) stripped" + (stripped.isEmpty() ? "" : " (" + String.join(", ", stripped) + " included)"));
        }
        return n;
    }

    private int stripLibrary(DriverSet ds, Transaction tx) throws IOException {
        int n = 0;
        if (strip(ds.meta)) {
            n++;
        }
        for (Artifact a : ds.library.artifacts()) {
            if (strip(a.meta)) {
                n++;
                tx.dropBaseline(a);
                tx.touched(a.path());
            }
        }
        if (n > 0) {
            ds.meta.put(STRIPPED_KEY, "true");
            tx.note("Library and driver set: " + n + " object(s) stripped");
        }
        return n;
    }

    /**
     * Removes every package-related key from a meta map: {@code dirxml-pkg*}, {@code package-id},
     * {@code pkg-assoc-id}, {@code project.package-id} / {@code project.pkg-assoc-id} /
     * {@code project.pkg-checksum}, {@code package.installed.*}, {@code package.customized},
     * {@code package.baseline-checksum}. True when anything was removed.
     */
    public static boolean strip(Map<String, String> meta) {
        List<String> drop = new ArrayList<>();
        for (String k : meta.keySet()) {
            if (isPackageKey(k)) {
                drop.add(k);
            }
        }
        for (String k : drop) {
            meta.remove(k);
        }
        return !drop.isEmpty();
    }

    static boolean isPackageKey(String key) {
        String k = key.toLowerCase();
        return k.startsWith("dirxml-pkg")
            || k.equals("package-id") || k.equals("pkg-assoc-id")
            || k.equals("project.package-id") || k.equals("project.pkg-assoc-id") || k.equals("project.pkg-checksum")
            || k.startsWith(PackageStamps.INSTALLED_PREFIX)
            || k.equals(Packages.CUSTOMIZED_KEY) || k.equals(PackageRevert.BASELINE_CHECKSUM_KEY);
    }

    /** True when {@code driver}'s (or, for null, the driver set's) meta says it was stripped. */
    public static boolean isStripped(DriverSet ds, String driver) {
        if (driver == null) {
            return "true".equals(ds.meta.get(STRIPPED_KEY));
        }
        Driver d = ds.driver(driver);
        return d != null && "true".equals(d.meta.get(STRIPPED_KEY));
    }
}
