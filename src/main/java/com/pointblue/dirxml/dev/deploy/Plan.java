package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Scope;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deploy plan: the ordered steps that turn a {@link ModelDiff} into vault
 * writes and restarts (docs/vault-deploy.md, "The plan and the deploy"). Each
 * step is one LDAP or extended operation; steps belong to a <em>change</em> (an
 * artifact, a driver's linkage, a config blob …) so {@code --step} can confirm
 * and verify change by change.
 *
 * <p>Order: driver containers for new drivers → Library objects → driver-scope
 * objects → channel objects → driver attributes and linkage → driver-set
 * attributes and linkage → deletes (after the linkage that referenced them is
 * gone) → secrets → restarts. A driver the tree lacks is never deleted (reported
 * as a note). A new driver is created stopped with start option manual.
 */
public final class Plan {

    public enum Op { ADD, MODIFY, DELETE, SET_SECRET, RESTART, START_OPTION, AUX_CLASS, ENSURE_CONTAINER }

    /** One operation. {@code values} is what to write (null for DELETE/RESTART; secrets carry only the key). */
    public static final class Step {
        public final Op op;
        public final String dn;
        public final String attr;             // for MODIFY; the secret key for SET_SECRET
        public final List<String> objectClasses;   // for ADD
        public final Map<String, List<byte[]>> values;   // ADD: all attrs; MODIFY: {attr: values}
        public final String description;
        public final String change;           // the change this step belongs to (group key)
        public final String driver;           // affected driver name, or null

        Step(Op op, String dn, String attr, List<String> objectClasses, Map<String, List<byte[]>> values,
             String description, String change, String driver) {
            this.op = op;
            this.dn = dn;
            this.attr = attr;
            this.objectClasses = objectClasses;
            this.values = values;
            this.description = description;
            this.change = change;
            this.driver = driver;
        }

        @Override
        public String toString() {
            return String.format("%-12s %s", op.name().toLowerCase(), description);
        }
    }

    public final List<Step> steps = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    public final List<String> missingSecrets = new ArrayList<>();
    public final Set<String> restart = new LinkedHashSet<>();       // driver names that will be restarted
    public final Set<String> touchedDns = new LinkedHashSet<>();    // for the snapshot
    public final Set<String> newDrivers = new LinkedHashSet<>();

    /** Top, DirXML-Driver, plus the package aux classes the stamps need (as Designer's drivers carry them). */
    static List<String> driverClasses(Map<String, List<byte[]>> stamps) {
        List<String> c = new ArrayList<>(List.of("Top", "DirXML-Driver"));
        if (stamps.containsKey(VaultMapping.PKG_EXTENSIONS)) {
            c.add(VaultMapping.PKG_TARGET_AUX);
        }
        if (stamps.containsKey(VaultMapping.PKG_GUID)) {
            c.add(VaultMapping.PKG_ITEM_AUX);   // the driver's own DirXML-pkgGUID lives in the item aux class
        }
        return c;
    }

    private Plan() {
    }

    /**
     * Build the plan for a diff (from = vault, to = tree).
     *
     * @param secretsMode {@code none} (secrets untouched except on new drivers), {@code missing}, or {@code all}
     * @param liveNamedPasswords per driver name, the named passwords already in the vault (for {@code missing})
     */
    public static Plan of(ModelDiff diff, DriverSet to, String dsDn, Secrets secrets, String secretsMode,
                          Map<String, List<String>> liveNamedPasswords, boolean restartRunning) {
        return of(diff, to, dsDn, secrets, secretsMode, liveNamedPasswords, restartRunning, null);
    }

    /** @param tree the tree (for package baselines → {@code DirXML-pkgInitialState}); null when not available */
    public static Plan of(ModelDiff diff, DriverSet to, String dsDn, Secrets secrets, String secretsMode,
                          Map<String, List<String>> liveNamedPasswords, boolean restartRunning, java.nio.file.Path tree) {
        Plan p = new Plan();
        List<Step> containers = new ArrayList<>();
        List<Step> library = new ArrayList<>();
        List<Step> driverScope = new ArrayList<>();
        List<Step> channel = new ArrayList<>();
        List<Step> driverAttrs = new ArrayList<>();
        List<Step> driverSet = new ArrayList<>();
        List<Step> deletes = new ArrayList<>();
        List<Step> secretSteps = new ArrayList<>();
        List<Step> provisioning = new ArrayList<>();
        Set<String> ensuredContainers = new LinkedHashSet<>();
        Set<String> driversNeedingLinkage = new LinkedHashSet<>();

        for (ModelDiff.Change c : diff.changes()) {
            if (c.kind.isProvisioning()) {
                provisioningSteps(p, c, to, dsDn, tree, provisioning, deletes, ensuredContainers);
                continue;
            }
            switch (c.kind) {
                case ARTIFACT_ADDED:
                case ARTIFACT_CHANGED: {
                    Artifact a = to.resolve(c.path);
                    if (a == null) {
                        p.notes.add("cannot resolve " + c.path + " in the tree; skipped");
                        break;
                    }
                    String dn = VaultMapping.artifactDn(dsDn, a);
                    p.touchedDns.add(dn);
                    Map<String, List<byte[]>> attrs = "package-stamps".equals(c.what)
                        ? new LinkedHashMap<>() : VaultMapping.attributes(a);
                    Map<String, List<byte[]>> stamps = VaultMapping.packageAttributes(tree, a);
                    if (!stamps.isEmpty()) {
                        attrs.putAll(stamps);   // an installed object: Designer's stamps, the installed checksum included
                    } else if (Packages.isPackaged(a) && Packages.isCustomized(a)) {
                        byte[] content = VaultMapping.contentBytes(a);
                        if (content != null) {
                            attrs.put(VaultMapping.PKG_CHECKSUM, Vault.value(VaultMapping.customizedChecksum(content)));
                        }
                    }
                    List<Step> bucket = a.scope == Scope.LIBRARY ? library : a.scope == Scope.DRIVER ? driverScope : channel;
                    if (c.kind == ModelDiff.Kind.ARTIFACT_ADDED) {
                        List<String> classes = stamps.isEmpty() ? List.of("Top", VaultMapping.objectClass(a))
                            : List.of("Top", VaultMapping.objectClass(a), VaultMapping.PKG_ITEM_AUX);
                        bucket.add(new Step(Op.ADD, dn, null, classes, attrs,
                            dn + "  " + VaultMapping.objectClass(a) + " (" + size(attrs) + ")", c.path, a.driver));
                    } else {
                        if (!stamps.isEmpty()) {
                            bucket.add(new Step(Op.AUX_CLASS, dn, null, List.of(VaultMapping.PKG_ITEM_AUX), null,
                                dn + "  objectClass += " + VaultMapping.PKG_ITEM_AUX, c.path, a.driver));
                        }
                        for (Map.Entry<String, List<byte[]>> e : attrs.entrySet()) {
                            bucket.add(new Step(Op.MODIFY, dn, e.getKey(), null, Map.of(e.getKey(), e.getValue()),
                                dn + "  " + e.getKey() + " (" + size(Map.of(e.getKey(), e.getValue())) + ")", c.path, a.driver));
                        }
                    }
                    break;
                }
                case ARTIFACT_KIND_CHANGED: {
                    Artifact a = to.resolve(c.path);
                    if (a == null) {
                        break;
                    }
                    String dn = VaultMapping.artifactDn(dsDn, a);
                    p.touchedDns.add(dn);
                    deletes.add(new Step(Op.DELETE, dn, null, null, null, dn + "  (object class changes: delete, then add)", c.path, a.driver));
                    Map<String, List<byte[]>> attrs = VaultMapping.attributes(a);
                    // the add must follow the delete: queue it in the deletes bucket right after
                    deletes.add(new Step(Op.ADD, dn, null, List.of("Top", VaultMapping.objectClass(a)), attrs,
                        dn + "  " + VaultMapping.objectClass(a) + " (" + size(attrs) + ")", c.path, a.driver));
                    if (a.driver != null) {
                        driversNeedingLinkage.add(a.driver);
                    }
                    break;
                }
                case ARTIFACT_REMOVED: {
                    String dn = VaultMapping.pathDn(dsDn, c.path);
                    p.touchedDns.add(dn);
                    deletes.add(new Step(Op.DELETE, dn, null, null, null, dn, c.path, c.driver));
                    break;
                }
                case DRIVER_SETTING: {
                    Driver d = to.driver(c.driver);
                    String dn = VaultMapping.driverDn(dsDn, c.driver);
                    p.touchedDns.add(dn);
                    String attr = c.what.equals("shim-class") ? VaultMapping.JAVA_MODULE
                        : c.what.equals("shim-auth-server") ? VaultMapping.SHIM_AUTH_SERVER : VaultMapping.SHIM_AUTH_ID;
                    String v = c.what.equals("shim-class") ? d.shimClass : c.what.equals("shim-auth-server") ? d.shimAuthServer : d.shimAuthId;
                    List<byte[]> values = v == null || v.isBlank() ? Collections.emptyList() : Vault.value(v);
                    driverAttrs.add(new Step(Op.MODIFY, dn, attr, null, Map.of(attr, values),
                        dn + "  " + attr + (values.isEmpty() ? " (remove)" : " = " + v), c.path + "#" + c.what, c.driver));
                    break;
                }
                case DRIVER_CONFIG: {
                    Driver d = to.driver(c.driver);
                    String dn = VaultMapping.driverDn(dsDn, c.driver);
                    p.touchedDns.add(dn);
                    String attr = VaultMapping.driverConfigAttribute(c.what);
                    org.w3c.dom.Element e = d.config.get(c.what);
                    List<byte[]> values = e == null ? Collections.emptyList()
                        : List.of(com.pointblue.dirxml.dev.xml.CanonicalXml.serialize(e).getBytes(StandardCharsets.UTF_8));
                    driverAttrs.add(new Step(Op.MODIFY, dn, attr, null, Map.of(attr, values),
                        dn + "  " + attr + (values.isEmpty() ? " (remove)" : " (" + size(Map.of(attr, values)) + ")"), c.path + "#" + c.what, c.driver));
                    break;
                }
                case DRIVER_LINKAGE:
                    driversNeedingLinkage.add(c.driver);
                    break;
                case DRIVER_STAMPS: {
                    Driver d = to.driver(c.driver);
                    String dn = VaultMapping.driverDn(dsDn, c.driver);
                    p.touchedDns.add(dn);
                    Map<String, List<byte[]>> dstamps = VaultMapping.driverPackageAttributes(d);
                    List<String> aux = driverClasses(dstamps).subList(2, driverClasses(dstamps).size());
                    if (!aux.isEmpty()) {
                        driverAttrs.add(new Step(Op.AUX_CLASS, dn, null, aux, null, dn + "  objectClass += " + String.join(", ", aux), c.path + "#stamps", c.driver));
                    }
                    for (String attr : List.of(VaultMapping.PKG_GUID, VaultMapping.PKG_EXTENSIONS)) {
                        List<byte[]> values = dstamps.getOrDefault(attr, Collections.emptyList());
                        driverAttrs.add(new Step(Op.MODIFY, dn, attr, null, Map.of(attr, values),
                            dn + "  " + attr + (values.isEmpty() ? " (remove)" : " (" + size(Map.of(attr, values)) + ")"), c.path + "#stamps", c.driver));
                    }
                    break;
                }
                case DRIVER_ADDED: {
                    Driver d = to.driver(c.driver);
                    String dn = VaultMapping.driverDn(dsDn, c.driver);
                    p.newDrivers.add(c.driver);
                    p.touchedDns.add(dn);
                    Map<String, List<byte[]>> attrs = VaultMapping.driverAttributes(d);
                    Map<String, List<byte[]>> dstamps = VaultMapping.driverPackageAttributes(d);
                    attrs.putAll(dstamps);
                    containers.add(new Step(Op.ADD, dn, null, driverClasses(dstamps), attrs,
                        dn + "  DirXML-Driver (new driver, created stopped)", c.path, c.driver));
                    for (Scope s : new Scope[] {Scope.SUBSCRIBER, Scope.PUBLISHER}) {
                        String cdn = VaultMapping.channelDn(dsDn, c.driver, s);
                        p.touchedDns.add(cdn);
                        containers.add(new Step(Op.ADD, cdn, null,
                            List.of("Top", s == Scope.SUBSCRIBER ? "DirXML-Subscriber" : "DirXML-Publisher"), Vault.attrs(),
                            cdn, c.path, c.driver));
                    }
                    for (Artifact a : d.artifacts()) {
                        String adn = VaultMapping.artifactDn(dsDn, a);
                        p.touchedDns.add(adn);
                        Map<String, List<byte[]>> aa = VaultMapping.attributes(a);
                        Map<String, List<byte[]>> astamps = VaultMapping.packageAttributes(tree, a);
                        aa.putAll(astamps);
                        (a.scope == Scope.DRIVER ? driverScope : channel).add(new Step(Op.ADD, adn, null,
                            astamps.isEmpty() ? List.of("Top", VaultMapping.objectClass(a)) : List.of("Top", VaultMapping.objectClass(a), VaultMapping.PKG_ITEM_AUX), aa,
                            adn + "  " + VaultMapping.objectClass(a) + " (" + size(aa) + ")", c.path, c.driver));
                    }
                    driversNeedingLinkage.add(c.driver);
                    driverAttrs.add(new Step(Op.START_OPTION, dn, null, null, null, dn + "  start option = manual", c.path, c.driver));
                    // secrets a new driver needs
                    for (SecretInventory.Need need : SecretInventory.forDriver(to, d)) {
                        if (secrets.has(need.key)) {
                            secretSteps.add(new Step(Op.SET_SECRET, dn, need.key, null, null,
                                "set secret " + need.key + " (" + need.kind + ")", c.path, c.driver));
                        } else {
                            p.missingSecrets.add(need.key + " — " + need.because);
                        }
                    }
                    break;
                }
                case DRIVER_REMOVED:
                    p.notes.add("driver '" + c.driver + "' exists in the vault but not in the tree — never deleted by deploy "
                        + "(use --delete-driver to remove it explicitly)");
                    break;
                case DRIVERSET_GCVS: {
                    p.touchedDns.add(dsDn);
                    Map<String, List<byte[]>> attrs = VaultMapping.driverSetAttributes(to);
                    List<byte[]> values = attrs.getOrDefault(VaultMapping.CONFIG_VALUES, Collections.emptyList());
                    driverSet.add(new Step(Op.MODIFY, dsDn, VaultMapping.CONFIG_VALUES, null, Map.of(VaultMapping.CONFIG_VALUES, values),
                        dsDn + "  " + VaultMapping.CONFIG_VALUES + " (driver-set GCVs)", "driverset#gcvs", null));
                    break;
                }
                case DRIVERSET_LINKAGE: {
                    p.touchedDns.add(dsDn);
                    List<byte[]> values = new ArrayList<>();
                    for (String v : VaultMapping.driverSetPoliciesValues(dsDn, to)) {
                        values.add(v.getBytes(StandardCharsets.UTF_8));
                    }
                    driverSet.add(new Step(Op.MODIFY, dsDn, VaultMapping.POLICIES, null, Map.of(VaultMapping.POLICIES, values),
                        dsDn + "  " + VaultMapping.POLICIES + " (" + values.size() + " value(s))", "driverset#linkage", null));
                    break;
                }
                default:
                    break;
            }
        }

        // linkage: the whole DirXML-Policies of every driver whose chains changed (dedupe)
        for (String name : driversNeedingLinkage) {
            Driver d = to.driver(name);
            if (d == null) {
                continue;
            }
            String dn = VaultMapping.driverDn(dsDn, name);
            p.touchedDns.add(dn);
            List<byte[]> values = new ArrayList<>();
            for (String v : VaultMapping.policiesValues(dsDn, d)) {
                values.add(v.getBytes(StandardCharsets.UTF_8));
            }
            driverAttrs.add(new Step(Op.MODIFY, dn, VaultMapping.POLICIES, null, Map.of(VaultMapping.POLICIES, values),
                dn + "  " + VaultMapping.POLICIES + " (" + values.size() + " value(s))", "drivers/" + name + "#linkage", name));
        }

        // forced / missing secrets on existing drivers
        if (!"none".equals(secretsMode)) {
            for (Driver d : to.drivers) {
                if (p.newDrivers.contains(d.name)) {
                    continue;
                }
                String dn = VaultMapping.driverDn(dsDn, d.name);
                for (SecretInventory.Need need : SecretInventory.forDriver(to, d)) {
                    boolean want = "all".equals(secretsMode);
                    if ("missing".equals(secretsMode) && need.kind.equals("named")) {
                        String name = need.key.substring(need.key.indexOf(".named.") + 7);
                        List<String> have = liveNamedPasswords == null ? null : liveNamedPasswords.get(d.name);
                        want = have != null && !have.contains(name);
                    }
                    if (!want) {
                        continue;
                    }
                    if (secrets.has(need.key)) {
                        p.touchedDns.add(dn);
                        secretSteps.add(new Step(Op.SET_SECRET, dn, need.key, null, null,
                            "set secret " + need.key + " (" + need.kind + ")", "secrets#" + d.name, d.name));
                    } else {
                        p.missingSecrets.add(need.key + " — " + need.because);
                    }
                }
            }
        }

        p.steps.addAll(containers);
        p.steps.addAll(library);
        p.steps.addAll(driverScope);
        p.steps.addAll(channel);
        p.steps.addAll(provisioning);
        p.steps.addAll(driverAttrs);
        p.steps.addAll(driverSet);
        p.steps.addAll(deletes);
        p.steps.addAll(secretSteps);
        if (restartRunning) {
            Set<String> affected = new LinkedHashSet<>(diff.affectedDrivers());
            // a driver-set GCV object (linked from the driver set, not from drivers) is in
            // every driver's scope: a change to one restarts them all
            Set<String> dsGcvObjects = new LinkedHashSet<>();
            for (String v : VaultMapping.driverSetPoliciesValues(dsDn, to)) {
                String dn = v.substring(0, v.indexOf('#'));
                dsGcvObjects.add(dn);
            }
            for (ModelDiff.Change c : diff.changes()) {
                if ((c.kind == ModelDiff.Kind.ARTIFACT_CHANGED || c.kind == ModelDiff.Kind.ARTIFACT_ADDED
                    || c.kind == ModelDiff.Kind.ARTIFACT_REMOVED) && c.path.startsWith("library/")
                    && dsGcvObjects.contains(VaultMapping.pathDn(dsDn, c.path))) {
                    for (Driver d : to.drivers) {
                        affected.add(d.name);
                    }
                }
            }
            for (String name : affected) {
                if (to.driver(name) != null && !p.newDrivers.contains(name)) {
                    p.restart.add(name);
                }
            }
        }
        return p;
    }

    /**
     * Steps for a provisioning change (JSON form or PRD under the driver's {@code cn=AppConfig}):
     * containers ensured, the object added/modified attribute by attribute, deletes queued last.
     * Stamps as for artifacts; a customized packaged object gets a content-derived
     * {@code DirXML-pkgChecksum} so Designer's modified test trips.
     */
    private static void provisioningSteps(Plan p, ModelDiff.Change c, DriverSet to, String dsDn, java.nio.file.Path tree,
                                          List<Step> bucket, List<Step> deletes, Set<String> ensured) {
        String driver = c.driver;
        Driver d = to.driver(driver);
        switch (c.kind) {
            case FORM_REMOVED:
            case PRD_REMOVED: {
                String dn = VaultMapping.provisioningPathDn(dsDn, c.path);
                p.touchedDns.add(dn);
                deletes.add(new Step(Op.DELETE, dn, null, null, null, dn, c.path, driver));
                return;
            }
            default:
                break;
        }
        if (d == null || d.provisioning == null) {
            p.notes.add("cannot resolve " + c.path + " in the tree; skipped");
            return;
        }
        boolean stampsOnly = "package-stamps".equals(c.what);
        boolean added = c.kind == ModelDiff.Kind.FORM_ADDED || c.kind == ModelDiff.Kind.PRD_ADDED;
        String dn;
        String oc;
        Map<String, List<byte[]>> attrs;
        Map<String, List<byte[]>> stamps;
        byte[] content;
        if (c.kind == ModelDiff.Kind.FORM_ADDED || c.kind == ModelDiff.Kind.FORM_CHANGED) {
            String tail = c.path.substring(c.path.indexOf("/provisioning/forms/") + "/provisioning/forms/".length());
            String[] parts = tail.split("/", 2);
            com.pointblue.dirxml.dev.model.Form f = d.provisioning.form(com.pointblue.dirxml.dev.model.Form.Kind.byDir(parts[0]), parts[1]);
            if (f == null) {
                p.notes.add("cannot resolve " + c.path + " in the tree; skipped");
                return;
            }
            dn = VaultMapping.formDn(dsDn, driver, f);
            oc = VaultMapping.OC_JSON_FORM;
            content = VaultMapping.formBytes(f);
            attrs = stampsOnly ? new LinkedHashMap<>() : VaultMapping.formAttributes(f);
            String baseline = readBaseline(tree, com.pointblue.dirxml.dev.edit.FormOps.path(d, f) + ".form.json");
            if (baseline != null) {
                try {
                    baseline = com.pointblue.dirxml.dev.json.Json.compact(com.pointblue.dirxml.dev.json.Json.parse(baseline));
                } catch (RuntimeException e) {
                    // keep as is
                }
            }
            stamps = VaultMapping.provisioningPackageAttributes(f.meta, baseline);
            if (!stamps.isEmpty() && "true".equals(f.meta.get(Packages.CUSTOMIZED_KEY))) {
                stamps.put(VaultMapping.PKG_CHECKSUM, Vault.value(VaultMapping.customizedChecksum(content)));
            }
            if (added) {
                ensureContainer(bucket, ensured, VaultMapping.workflowFormsDn(dsDn, driver), VaultMapping.OC_JSON_FORMS, c, driver);
                ensureContainer(bucket, ensured, VaultMapping.formContainerDn(dsDn, driver, f.kind), VaultMapping.OC_JSON_FORMS, c, driver);
            }
        } else {
            String name = c.path.substring(c.path.indexOf("/provisioning/prds/") + "/provisioning/prds/".length());
            com.pointblue.dirxml.dev.model.Prd prd = d.provisioning.prd(name);
            if (prd == null) {
                p.notes.add("cannot resolve " + c.path + " in the tree; skipped");
                return;
            }
            dn = VaultMapping.prdDn(dsDn, driver, prd);
            oc = VaultMapping.OC_REQUEST;
            attrs = stampsOnly ? new LinkedHashMap<>() : VaultMapping.prdAttributes(prd);
            List<byte[]> xml = attrs.get(VaultMapping.XML_DATA);
            content = xml == null || xml.isEmpty() ? new byte[0] : xml.get(0);
            String baseline = readBaseline(tree, com.pointblue.dirxml.dev.edit.FormOps.prdPath(d, prd) + "/definition.xml");
            stamps = VaultMapping.provisioningPackageAttributes(prd.meta, baseline);
            if (!stamps.isEmpty() && "true".equals(prd.meta.get(Packages.CUSTOMIZED_KEY))) {
                stamps.put(VaultMapping.PKG_CHECKSUM, Vault.value(VaultMapping.customizedChecksum(content)));
            }
            if (added) {
                ensureContainer(bucket, ensured, VaultMapping.requestDefsDn(dsDn, driver), VaultMapping.OC_REQUEST_DEFS, c, driver);
            }
        }
        p.touchedDns.add(dn);
        attrs.putAll(stamps);
        if (added) {
            List<String> classes = stamps.isEmpty() ? List.of("Top", oc) : List.of("Top", oc, VaultMapping.PKG_ITEM_AUX);
            bucket.add(new Step(Op.ADD, dn, null, classes, attrs, dn + "  " + oc + " (" + size(attrs) + ")", c.path, driver));
        } else {
            if (!stamps.isEmpty()) {
                bucket.add(new Step(Op.AUX_CLASS, dn, null, List.of(VaultMapping.PKG_ITEM_AUX), null,
                    dn + "  objectClass += " + VaultMapping.PKG_ITEM_AUX, c.path, driver));
            }
            for (Map.Entry<String, List<byte[]>> e : attrs.entrySet()) {
                bucket.add(new Step(Op.MODIFY, dn, e.getKey(), null, Map.of(e.getKey(), e.getValue()),
                    dn + "  " + e.getKey() + " (" + size(Map.of(e.getKey(), e.getValue())) + ")", c.path, driver));
            }
        }
    }

    private static void ensureContainer(List<Step> bucket, Set<String> ensured, String dn, String oc, ModelDiff.Change c, String driver) {
        if (ensured.add(dn)) {
            bucket.add(new Step(Op.ENSURE_CONTAINER, dn, null, List.of("Top", oc), Vault.attrs(),
                dn + "  " + oc + " (created if absent)", c.path, driver));
        }
    }

    private static String readBaseline(java.nio.file.Path tree, String relative) {
        if (tree == null) {
            return null;
        }
        java.nio.file.Path f = tree.resolve(".package-baseline").resolve(relative);
        try {
            return java.nio.file.Files.isRegularFile(f) ? java.nio.file.Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** The distinct changes, in step order (for {@code --step}). */
    public List<String> changes() {
        List<String> out = new ArrayList<>();
        for (Step s : steps) {
            if (!out.contains(s.change)) {
                out.add(s.change);
            }
        }
        return out;
    }

    public List<Step> stepsOf(String change) {
        List<Step> out = new ArrayList<>();
        for (Step s : steps) {
            if (s.change.equals(change)) {
                out.add(s);
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return steps.isEmpty() && restart.isEmpty();
    }

    public String text(String env, String dsDn) {
        StringBuilder sb = new StringBuilder();
        sb.append("plan for ").append(env).append(" (").append(dsDn).append("): ")
            .append(steps.size()).append(" step(s), ").append(restart.size()).append(" restart(s)\n");
        int n = 0;
        for (Step s : steps) {
            sb.append(String.format("  %3d. %s%n", ++n, s));
        }
        for (String d : restart) {
            sb.append(String.format("  %3d. %-12s %s  (after the writes, if the driver is running; a stopped driver loads them when started)%n", ++n, "restart", d));
        }
        for (String d : newDrivers) {
            sb.append("  new driver '").append(d).append("' is created stopped; start it explicitly when its secrets are in place\n");
        }
        for (String m : missingSecrets) {
            sb.append("  MISSING SECRET: ").append(m).append('\n');
        }
        for (String note : notes) {
            sb.append("  note: ").append(note).append('\n');
        }
        return sb.toString();
    }

    public String json() {
        StringBuilder sb = new StringBuilder("{\"steps\":[");
        boolean first = true;
        for (Step s : steps) {
            sb.append(first ? "" : ",").append("{\"op\":").append(DeployLog.q(s.op.name().toLowerCase()))
                .append(",\"dn\":").append(DeployLog.q(s.dn))
                .append(",\"attr\":").append(s.attr == null ? "null" : DeployLog.q(s.attr))
                .append(",\"change\":").append(DeployLog.q(s.change))
                .append(",\"driver\":").append(s.driver == null ? "null" : DeployLog.q(s.driver))
                .append(",\"description\":").append(DeployLog.q(s.description)).append('}');
            first = false;
        }
        sb.append("],\"restart\":[");
        first = true;
        for (String d : restart) {
            sb.append(first ? "" : ",").append(DeployLog.q(d));
            first = false;
        }
        sb.append("],\"newDrivers\":[");
        first = true;
        for (String d : newDrivers) {
            sb.append(first ? "" : ",").append(DeployLog.q(d));
            first = false;
        }
        sb.append("],\"missingSecrets\":[");
        first = true;
        for (String m : missingSecrets) {
            sb.append(first ? "" : ",").append(DeployLog.q(m));
            first = false;
        }
        sb.append("],\"notes\":[");
        first = true;
        for (String m : notes) {
            sb.append(first ? "" : ",").append(DeployLog.q(m));
            first = false;
        }
        return sb.append("]}").toString();
    }

    private static String size(Map<String, List<byte[]>> attrs) {
        long n = 0;
        for (List<byte[]> vs : attrs.values()) {
            for (byte[] v : vs) {
                n += v.length;
            }
        }
        return n < 1024 ? n + " B" : String.format("%.1f KB", n / 1024.0);
    }

    static Map<String, List<byte[]>> single(String attr, List<byte[]> values) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        m.put(attr, values);
        return m;
    }
}
