package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSync;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Form operations (Track P). A form is not an {@link com.pointblue.dirxml.dev.model.Artifact}, so
 * the package bookkeeping (baseline + customized mark) is done here with the same conventions:
 * baseline file {@code .package-baseline/drivers/<d>/provisioning/forms/<kind>/<file>} holding the
 * pre-edit document, {@code package.customized=true} in the form's meta. PRDs the form is bound to
 * are re-synced ({@link BindingSync}) and, when packaged, marked customized with their
 * {@code definition.xml}/{@code request.xml} baselined.
 */
public final class FormOps {

    private FormOps() {
    }

    /** A form and the driver that owns it. */
    public static final class Found {
        public final Driver driver;
        public final Form form;

        Found(Driver driver, Form form) {
            this.driver = driver;
            this.form = form;
        }
    }

    /** Accepts a bare name, {@code kind/name} or {@code driver/kind/name}; null when absent or ambiguous across drivers. */
    public static Found find(DriverSet ds, String ref, String driverFlag) {
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
        Found hit = null;
        for (Driver d : ds.drivers) {
            if (driverName != null && !driverName.equals(d.name)) {
                continue;
            }
            if (d.provisioning == null) {
                continue;
            }
            Form f = kind != null ? d.provisioning.form(kind, name) : d.provisioning.formByName(name);
            if (f != null) {
                if (hit != null) {
                    return null;   // ambiguous: say which driver
                }
                hit = new Found(d, f);
            }
        }
        return hit;
    }

    /** The tree-relative path used in results and baselines: {@code drivers/<d>/provisioning/forms/<kind>/<name>}. */
    public static String path(Driver d, Form f) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/provisioning/forms/" + f.kind.dir + "/" + AsCodeWriter.fileSafe(f.name);
    }

    public static String prdPath(Driver d, Prd p) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/provisioning/prds/" + AsCodeWriter.fileSafe(p.name);
    }

    static boolean isPackaged(Map<String, String> meta) {
        String g = meta.get("dirxml-pkgguid");
        return g != null && !g.isBlank();
    }

    // ---- set-content / sync -------------------------------------------------------

    /**
     * Replace a form's document (or, with {@code json == null}, re-normalize the one in the tree —
     * what {@code form.sync} does after the vendor builder saved in place), then sync every PRD
     * binding that references the form.
     */
    public static final class SetContent implements Operation {
        private final String driver;
        private final String ref;
        private final String json;     // null = keep the model's document (sync only)

        public SetContent(String driver, String ref, String json) {
            this.driver = driver;
            this.ref = ref;
            this.json = json;
        }

        @Override
        public String name() {
            return json == null ? "form.sync" : "form.set-content";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            if (ref == null || ref.isBlank()) {
                throw new Refusal("a form name is required");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Refusal("form '" + ref + "' not found" + (driver == null ? " (or found on several drivers — say --driver)" : " on driver '" + driver + "'"));
            }
            Form form = found.form;
            String text = json != null ? json : form.json;
            Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (RuntimeException e) {
                throw new Refusal("the form document is not valid JSON: " + e.getMessage());
            }
            if (!(parsed instanceof Map) || !(((Map<?, ?>) parsed).get("components") instanceof List)) {
                throw new Refusal("the form document must be a JSON object with a \"components\" array");
            }
            String pretty = Json.pretty(parsed);
            String path = path(found.driver, form);
            boolean changed = !pretty.equals(form.json);
            if (changed) {
                customizeForm(tx, found.driver, form);
                form.json = pretty;
                tx.touched(path);
            } else {
                tx.note("form '" + form.name + "': document unchanged");
            }
            // bindings
            for (Prd prd : found.driver.provisioning.prds) {
                if (!BindingSync.binds(prd, form.name)) {
                    continue;
                }
                String before = prdFingerprint(prd);
                List<BindingSync.Change> changes = BindingSync.sync(prd, form);
                if (!changes.isEmpty() && !before.equals(prdFingerprint(prd))) {
                    customizePrd(tx, found.driver, prd, before);
                    tx.touched(prdPath(found.driver, prd));
                    for (BindingSync.Change c : changes) {
                        tx.note(c.toString());
                    }
                } else if (!changes.isEmpty()) {
                    for (BindingSync.Change c : changes) {
                        tx.note(c.toString());
                    }
                }
            }
        }
    }

    /** Baseline + customized mark for a packaged form on its first edit. */
    static void customizeForm(Transaction tx, Driver d, Form f) {
        if (!isPackaged(f.meta)) {
            return;
        }
        boolean newly = !"true".equals(f.meta.get(Packages.CUSTOMIZED_KEY));
        Path baseline = tx.tree().resolve(".package-baseline").resolve(path(d, f) + ".form.json");
        if (!java.nio.file.Files.exists(baseline)) {
            tx.pendingBaseline(baseline, f.json);
        }
        f.meta.put(Packages.CUSTOMIZED_KEY, "true");
        if (newly) {
            tx.customizedNow(path(d, f));
        }
    }

    /** Baseline (definition.xml + request.xml as they were) + customized mark for a packaged PRD. */
    static void customizePrd(Transaction tx, Driver d, Prd p, String beforeFingerprint) {
        if (!isPackaged(p.meta)) {
            return;
        }
        boolean newly = !"true".equals(p.meta.get(Packages.CUSTOMIZED_KEY));
        Path dir = tx.tree().resolve(".package-baseline").resolve(prdPath(d, p));
        if (!java.nio.file.Files.exists(dir)) {
            // the fingerprint is definition + "\n----\n" + request, canonical, captured before the sync
            String[] parts = beforeFingerprint.split("\n----\n", -1);
            tx.pendingBaseline(dir.resolve("definition.xml"), parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) {
                tx.pendingBaseline(dir.resolve("request.xml"), parts[1]);
            }
        }
        p.meta.put(Packages.CUSTOMIZED_KEY, "true");
        if (newly) {
            tx.customizedNow(prdPath(d, p));
        }
    }

    static String prdFingerprint(Prd p) {
        String def = p.definition == null ? "" : CanonicalXml.serialize(p.definition);
        String req = p.request == null ? "" : CanonicalXml.serialize(p.request);
        return def + "\n----\n" + req;
    }
}
