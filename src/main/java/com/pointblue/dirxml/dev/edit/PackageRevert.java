package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.ascode.DsObjectXml;
import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.PackageStamps;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.packages.Catalog;
import com.pointblue.dirxml.dev.packages.InstalledChecksum;
import com.pointblue.dirxml.dev.packages.PackageJar;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code package.revert <tree> --path P [--catalog DIR]}: put a customized packaged object back to
 * its package baseline — the content the tree kept in {@code .package-baseline/} at the first
 * edit — drop the {@code package.customized} mark, delete the baseline, and restore the package's
 * checksum so Designer stops showing the object as modified. Works for every kind that can be
 * customized: a policy or resource artifact, a JSON form, a PRD, an entitlement, an AppConfig
 * object.
 *
 * <p>The package's checksum comes back from, in order: the {@code package.baseline-checksum}
 * the tree recorded at the first customization (every customize helper writes it), for an
 * artifact Designer's own installed-content recipe recomputed over the restored content, or the
 * package item's stored checksum in the {@code --catalog}; failing all three the content-derived
 * one stays, with a note.
 */
public final class PackageRevert implements Operation {

    /** Meta key: the package's checksum as it was before the first customization. */
    public static final String BASELINE_CHECKSUM_KEY = "package.baseline-checksum";

    private final String path;
    private final Catalog catalog;

    public PackageRevert(String path, Catalog catalog) {
        this.path = path == null ? null : path.trim();
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "package.revert";
    }

    @Override
    public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
        if (path == null || path.isBlank()) {
            throw new Operation.Refusal("--path is required");
        }
        Path baseRoot = tx.tree().resolve(Packages.BASELINE_DIR);
        int prov = path.indexOf("/provisioning/");
        int ent = path.indexOf("/entitlements/");
        if (path.startsWith("drivers/") && prov >= 0) {
            String driverSafe = path.substring("drivers/".length(), prov);
            String tail = path.substring(prov + "/provisioning/".length());
            Driver d = driverBySafeName(ds, driverSafe);
            if (d == null || d.provisioning == null) {
                throw new Operation.Refusal("no driver with provisioning at '" + path + "'");
            }
            if (tail.startsWith("forms/")) {
                revertForm(ds, tx, d, tail.substring("forms/".length()), baseRoot);
            } else if (tail.startsWith("prds/")) {
                revertPrd(ds, tx, d, tail.substring("prds/".length()), baseRoot);
            } else if (tail.startsWith("objects/")) {
                revertObject(ds, tx, d, tail.substring("objects/".length()), baseRoot);
            } else {
                throw new Operation.Refusal("'" + path + "' is not a form, PRD or AppConfig object path");
            }
            return;
        }
        if (path.startsWith("drivers/") && ent >= 0) {
            Driver d = driverBySafeName(ds, path.substring("drivers/".length(), ent));
            if (d == null) {
                throw new Operation.Refusal("no driver at '" + path + "'");
            }
            revertEntitlement(ds, tx, d, path.substring(ent + "/entitlements/".length()), baseRoot);
            return;
        }
        Artifact a = ds.resolve(path);
        if (a == null) {
            throw new Operation.Refusal("no artifact, form, PRD, entitlement or AppConfig object at '" + path + "'");
        }
        revertArtifact(ds, tx, a);
    }

    private static Driver driverBySafeName(DriverSet ds, String safe) {
        for (Driver d : ds.drivers) {
            if (AsCodeWriter.fileSafe(d.name).equals(safe) || d.name.equals(safe)) {
                return d;
            }
        }
        return null;
    }

    private static void requireCustomized(Map<String, String> meta, String what) throws Operation.Refusal {
        if (!PackageStamps.isPackaged(meta)) {
            throw new Operation.Refusal(what + " is not a packaged object; nothing to revert to");
        }
        if (!"true".equals(meta.get(Packages.CUSTOMIZED_KEY))) {
            throw new Operation.Refusal(what + " is not marked customized; it already is what its package installed");
        }
    }

    private static String readBaseline(Path file, String what) throws Operation.Refusal, IOException {
        if (!Files.isRegularFile(file)) {
            throw new Operation.Refusal(what + " has no package baseline in the tree (" + file.getFileName()
                + "); the tree never kept its installed content, so there is nothing to revert to");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    // ---- kinds ----

    private void revertArtifact(DriverSet ds, Transaction tx, Artifact a) throws Operation.Refusal, IOException {
        requireCustomized(a.meta, a.path());
        Path baseline = Packages.baselineFile(tx.tree(), a);
        String content = readBaseline(baseline, a.path());
        if (a instanceof Policy) {
            ((Policy) a).content = CanonicalXml.parse(content).getDocumentElement();
        } else {
            Resource r = (Resource) a;
            if (r.isText()) {
                r.text = content;
            } else {
                r.content = CanonicalXml.parse(content).getDocumentElement();
            }
        }
        a.meta.remove(Packages.CUSTOMIZED_KEY);
        String recorded = a.meta.remove(BASELINE_CHECKSUM_KEY);
        if (a.meta.get(PackageStamps.CHECKSUM) != null) {
            // Designer's installed-content recipe over the restored content is the package's checksum
            Driver owner = a.driver == null ? null : ds.driver(a.driver);
            a.meta.put(PackageStamps.CHECKSUM, Long.toString(InstalledChecksum.of(ds, owner, a)));
        } else if (recorded != null) {
            a.meta.put(PackageStamps.CHECKSUM, recorded);
        }
        tx.dropBaseline(a);
        tx.touched(a.path());
        tx.note("reverted " + a.path() + " to its package baseline; customized mark dropped");
    }

    private void revertForm(DriverSet ds, Transaction tx, Driver d, String tail, Path baseRoot) throws Operation.Refusal, IOException {
        String[] parts = tail.split("/", 2);
        Form.Kind kind = parts.length == 2 ? Form.Kind.byDir(parts[0]) : null;
        Form f = null;
        for (Form x : d.provisioning.forms) {
            if (kind != null && x.kind == kind && AsCodeWriter.fileSafe(x.name).equals(parts[1])) {
                f = x;
            }
        }
        if (f == null) {
            throw new Operation.Refusal("no form at '" + path + "'");
        }
        String p = FormOps.path(d, f);
        requireCustomized(f.meta, p);
        Path baseline = baseRoot.resolve(p + ".form.json");
        f.json = readBaseline(baseline, p);
        finish(tx, f.meta, p, baseline, PackageStamps.assocId(f.meta));
    }

    private void revertPrd(DriverSet ds, Transaction tx, Driver d, String name, Path baseRoot) throws Operation.Refusal, IOException {
        Prd prd = null;
        for (Prd x : d.provisioning.prds) {
            if (AsCodeWriter.fileSafe(x.name).equals(name)) {
                prd = x;
            }
        }
        if (prd == null) {
            throw new Operation.Refusal("no PRD at '" + path + "'");
        }
        String p = FormOps.prdPath(d, prd);
        requireCustomized(prd.meta, p);
        Path dir = baseRoot.resolve(p);
        String def = readBaseline(dir.resolve("definition.xml"), p);
        prd.definition = CanonicalXml.parse(def).getDocumentElement();
        List<Element> procs = Xds.childrenByName(prd.definition, "process");
        prd.process = procs.isEmpty() ? null : procs.get(0);
        Path req = dir.resolve("request.xml");
        prd.request = Files.isRegularFile(req) ? CanonicalXml.parse(Files.readString(req, StandardCharsets.UTF_8)).getDocumentElement() : null;
        prd.meta.remove(Packages.CUSTOMIZED_KEY);
        restoreChecksum(tx, prd.meta, p, PackageStamps.assocId(prd.meta));
        tx.dropBaseline(dir.resolve("definition.xml"));
        tx.dropBaseline(req);
        tx.touched(p);
        tx.note("reverted " + p + " to its package baseline; customized mark dropped");
    }

    private void revertEntitlement(DriverSet ds, Transaction tx, Driver d, String name, Path baseRoot) throws Operation.Refusal, IOException {
        Entitlement e = null;
        for (Entitlement x : d.entitlements) {
            if (AsCodeWriter.fileSafe(x.name).equals(name)) {
                e = x;
            }
        }
        if (e == null) {
            throw new Operation.Refusal("no entitlement at '" + path + "'");
        }
        String p = EntitlementOps.path(d, e);
        requireCustomized(e.meta, p);
        Path baseline = baseRoot.resolve(p + ".xml");
        e.definition = CanonicalXml.parse(readBaseline(baseline, p)).getDocumentElement();
        finish(tx, e.meta, p, baseline, PackageStamps.assocId(e.meta));
    }

    private void revertObject(DriverSet ds, Transaction tx, Driver d, String objectPath, Path baseRoot) throws Operation.Refusal, IOException {
        AppObject o = d.provisioning.object(objectPath);
        if (o == null) {
            throw new Operation.Refusal("no AppConfig object at '" + path + "'");
        }
        String p = AppConfigOps.path(d, o);
        requireCustomized(o.meta, p);
        Path baseline = baseRoot.resolve(p + ".xml");
        AppObject was = DsObjectXml.read(readBaseline(baseline, p), o.segments);
        o.classes.clear();
        o.classes.addAll(was.classes);
        o.attrs.clear();
        for (Map.Entry<String, List<String>> e : was.attrs.entrySet()) {
            o.put(e.getKey(), e.getValue());
        }
        finish(tx, o.meta, p, baseline, PackageStamps.assocId(o.meta));
    }

    private void finish(Transaction tx, Map<String, String> meta, String p, Path baseline, String assocId) throws IOException {
        meta.remove(Packages.CUSTOMIZED_KEY);
        restoreChecksum(tx, meta, p, assocId);
        tx.dropBaseline(baseline);
        tx.touched(p);
        tx.note("reverted " + p + " to its package baseline; customized mark dropped");
    }

    /** The package's checksum: recorded at the first customization, else looked up in the catalog, else left as is with a note. */
    private void restoreChecksum(Transaction tx, Map<String, String> meta, String p, String assocId) throws IOException {
        String recorded = meta.remove(BASELINE_CHECKSUM_KEY);
        if (recorded != null) {
            meta.put(PackageStamps.CHECKSUM, recorded);
            return;
        }
        String fromCatalog = catalog == null ? null : catalogChecksum(meta, assocId);
        if (fromCatalog != null) {
            meta.put(PackageStamps.CHECKSUM, fromCatalog);
            tx.note(p + ": package checksum restored from the catalog");
            return;
        }
        tx.note(p + ": the package's checksum is not on record (customized before the tree kept it" + (catalog == null ? "; --catalog can look it up" : ", and the catalog has no such item")
            + "); the content-derived one stays, so Designer still shows the object modified until a package sync");
    }

    private String catalogChecksum(Map<String, String> meta, String assocId) throws IOException {
        PackageStamps.Guid g = PackageStamps.guid(meta);
        if (g == null || assocId == null) {
            return null;
        }
        String shortName = catalog.idIndex().get(g.id);
        if (shortName == null) {
            return null;
        }
        Catalog.PackageEntry e = catalog.get(shortName);
        String version = g.version != null && e.versions.containsKey(g.version) ? g.version : e.newestVersion();
        if (version == null) {
            return null;
        }
        Path jar = catalog.jar(shortName, version);
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        PackageJar pkg = PackageJar.read(jar);
        for (PackageJar.Item it : pkg.items) {
            if (assocId.equals(it.assocId) && it.storedContentChecksum != null) {
                return it.storedContentChecksum.trim();
            }
        }
        for (AppObject o : pkg.provisioningObjects) {
            if (assocId.equals(o.meta.get("pkg-assoc-id")) && o.meta.get("pkg-checksum") != null) {
                return o.meta.get("pkg-checksum");
            }
        }
        return null;
    }
}
