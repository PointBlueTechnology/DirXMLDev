package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSync;
import com.pointblue.dirxml.dev.forms.FormEditor;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (ref == null || ref.isBlank()) {
                throw new Operation.Refusal("a form name is required");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal("form '" + ref + "' not found" + (driver == null ? " (or found on several drivers — say --driver)" : " on driver '" + driver + "'"));
            }
            Form form = found.form;
            String text = json != null ? json : form.json;
            Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (RuntimeException e) {
                throw new Operation.Refusal("the form document is not valid JSON: " + e.getMessage());
            }
            if (!(parsed instanceof Map) || !(((Map<?, ?>) parsed).get("components") instanceof List)) {
                throw new Operation.Refusal("the form document must be a JSON object with a \"components\" array");
            }
            syncDocument(tx, found, Json.pretty(parsed));
        }
    }

    // ---- shared tail: store a document + sync every PRD binding that references it -----

    /**
     * What every form-changing operation ends with (see class doc): store the
     * (already pretty-printed) document if it changed, then re-sync every PRD
     * binding that references the form, baselining/marking customized packaged
     * forms and PRDs on their first edit.
     */
    static void syncDocument(Transaction tx, Found found, String pretty) throws IOException {
        Form form = found.form;
        String path = path(found.driver, form);
        boolean changed = !pretty.equals(form.json);
        if (changed) {
            customizeForm(tx, found.driver, form);
            form.json = pretty;
            tx.touched(path);
        } else {
            tx.note("form '" + form.name + "': document unchanged");
        }
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

    /** Same as {@link #syncDocument(Transaction, Found, String)}, pretty-printing the parsed root first. */
    static void applyDocument(Transaction tx, Found found, Map<String, Object> root) throws IOException {
        syncDocument(tx, found, Json.pretty(root));
    }

    private static String notFoundMessage(String ref, String driver) {
        return "form '" + ref + "' not found" + (driver == null ? " (or found on several drivers — say --driver)" : " on driver '" + driver + "'");
    }

    private static FormEditor.Placement placementOf(String after, String before, boolean first, String in) {
        if (after != null) {
            return FormEditor.Placement.after(after, in);
        }
        if (before != null) {
            return FormEditor.Placement.before(before, in);
        }
        if (first) {
            return FormEditor.Placement.first(in);
        }
        return FormEditor.Placement.last(in);
    }

    // ---- form.add -------------------------------------------------------------------

    /** Create a new form: blank, or a copy of another form's document (with a new title). */
    public static final class Add implements Operation {
        private final String driver;
        private final Form.Kind kind;
        private final String name;
        private final String from;
        private final String title;

        public Add(String driver, String kind, String name, String from, String title) {
            this.driver = driver;
            this.kind = kind == null ? null : Form.Kind.byDir(kind);
            this.name = name;
            this.from = (from == null || from.isBlank()) ? null : from;
            this.title = (title == null || title.isBlank()) ? null : title;
        }

        @Override
        public String name() {
            return "form.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (kind == null) {
                throw new Operation.Refusal("--kind must be request, approval or template");
            }
            if (name == null || name.isBlank()) {
                throw new Operation.Refusal("a form name is required");
            }
            Driver d = driverFor(ds, driver);
            if (d.provisioning.form(kind, name) != null) {
                throw new Operation.Refusal("a " + kind.dir + " form named '" + name + "' already exists on driver '" + d.name + "'");
            }
            String docTitle = title != null ? title : name;
            String json;
            if (from != null) {
                Found src = find(ds, from, driver);
                if (src == null) {
                    throw new Operation.Refusal(notFoundMessage(from, driver));
                }
                Map<String, Object> m;
                try {
                    m = Json.asMap(Json.parse(src.form.json));
                } catch (RuntimeException e) {
                    throw new Operation.Refusal("source form '" + from + "' is not valid JSON: " + e.getMessage());
                }
                m.put("title", docTitle);
                json = Json.pretty(m);
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("components", new ArrayList<>());
                m.put("page", Json.Num.of("0"));
                m.put("title", docTitle);
                m.put("display", kind == Form.Kind.TEMPLATE ? "workflowWizard" : "form");
                m.put("inlinescripts", "");
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("en", new LinkedHashMap<>());
                m.put("localization", loc);
                m.put("externalScripts", new ArrayList<>());
                json = Json.pretty(m);
            }
            Form form = new Form(kind, name, json);
            d.provisioning.forms.add(form);
            tx.touched(path(d, form));
            tx.note("created " + kind.dir + " form '" + name + "'" + (from != null ? " (from '" + from + "')" : ""));
        }
    }

    // ---- form.field.add ---------------------------------------------------------------

    /** Add a new component: the captured builder template for its type (or a minimal shape), placed and flagged as asked. */
    public static final class FieldAdd implements Operation {
        private final String driver;
        private final String ref;
        private final String key;
        private final String type;
        private final String label;
        private final boolean required;
        private final boolean hidden;
        private final boolean multiple;
        private final boolean minimal;
        private final String after;
        private final String before;
        private final boolean first;
        private final String in;
        private final String extraJson;

        public FieldAdd(String driver, String ref, String key, String type, String label,
                         boolean required, boolean hidden, boolean multiple, boolean minimal,
                         String after, String before, boolean first, String in, String extraJson) {
            this.driver = driver;
            this.ref = ref;
            this.key = key;
            this.type = type;
            this.label = (label == null || label.isBlank()) ? null : label;
            this.required = required;
            this.hidden = hidden;
            this.multiple = multiple;
            this.minimal = minimal;
            this.after = (after == null || after.isBlank()) ? null : after;
            this.before = (before == null || before.isBlank()) ? null : before;
            this.first = first;
            this.in = (in == null || in.isBlank()) ? null : in;
            this.extraJson = extraJson;
        }

        @Override
        public String name() {
            return "form.field.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (key == null || key.isBlank()) {
                throw new Operation.Refusal("--key is required");
            }
            if (type == null || type.isBlank()) {
                throw new Operation.Refusal("--type is required");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Map<String, Object> root = Json.asMap(Json.parse(found.form.json));
            if (FormEditor.globalKeyCounts(root).containsKey(key)) {
                throw new Operation.Refusal("form '" + found.form.name + "' already has a component with key '" + key + "'");
            }
            Map<String, Object> comp;
            if (minimal) {
                comp = FormEditor.minimal(type, key, label);
            } else {
                Map<String, Object> tmpl = FormEditor.template(type);
                if (tmpl != null) {
                    comp = tmpl;
                    comp.remove("id");
                    comp.put("key", key);
                    if (label != null) {
                        comp.put("label", label);
                    }
                } else {
                    comp = FormEditor.minimal(type, key, label);
                }
            }
            if (required) {
                FormEditor.setProperty(comp, "validate.required", Boolean.TRUE);
            }
            if (hidden) {
                comp.put("hidden", Boolean.TRUE);
            }
            if (multiple) {
                comp.put("multiple", Boolean.TRUE);
            }
            if (extraJson != null && !extraJson.isBlank()) {
                Object extra;
                try {
                    extra = Json.parse(extraJson);
                } catch (RuntimeException e) {
                    throw new Operation.Refusal("--json is not valid JSON: " + e.getMessage());
                }
                FormEditor.deepMergeInto(comp, Json.asMap(extra));
            }
            try {
                List<Object> targetList = FormEditor.target(root, in);
                FormEditor.insertAt(targetList, comp, placementOf(after, before, first, in));
            } catch (IllegalArgumentException e) {
                throw new Operation.Refusal(e.getMessage());
            }
            applyDocument(tx, found, root);
            tx.note("form '" + found.form.name + "': added " + type + " field '" + key + "'");
        }
    }

    // ---- form.field.set ---------------------------------------------------------------

    /** Change properties of an existing component. */
    public static final class FieldSet implements Operation {
        private final String driver;
        private final String ref;
        private final String key;
        private final String label;
        private final Boolean required;
        private final Boolean hidden;
        private final Boolean multiple;
        private final String type;
        private final List<String> props;

        public FieldSet(String driver, String ref, String key, String label, Boolean required, Boolean hidden,
                         Boolean multiple, String type, List<String> props) {
            this.driver = driver;
            this.ref = ref;
            this.key = key;
            this.label = label;
            this.required = required;
            this.hidden = hidden;
            this.multiple = multiple;
            this.type = (type == null || type.isBlank()) ? null : type;
            this.props = props;
        }

        @Override
        public String name() {
            return "form.field.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (key == null || key.isBlank()) {
                throw new Operation.Refusal("--key is required");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Map<String, Object> root = Json.asMap(Json.parse(found.form.json));
            FormEditor.Located loc = FormEditor.find(root, key);
            if (loc == null) {
                throw new Operation.Refusal("form '" + found.form.name + "' has no component with key '" + key + "'");
            }
            if (label != null) {
                loc.component.put("label", label);
            }
            if (required != null) {
                FormEditor.setProperty(loc.component, "validate.required", required);
            }
            if (hidden != null) {
                loc.component.put("hidden", hidden);
            }
            if (multiple != null) {
                loc.component.put("multiple", multiple);
            }
            if (type != null) {
                loc.component.put("type", type);
            }
            if (props != null) {
                for (String pair : props) {
                    if (pair.isBlank()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        throw new Operation.Refusal("--prop must be path=value: '" + pair + "'");
                    }
                    String propPath = pair.substring(0, eq);
                    String raw = pair.substring(eq + 1);
                    Object value;
                    try {
                        value = Json.parse(raw);
                    } catch (RuntimeException e) {
                        value = raw;
                    }
                    FormEditor.setProperty(loc.component, propPath, value);
                }
            }
            applyDocument(tx, found, root);
            tx.note("form '" + found.form.name + "': updated field '" + key + "'");
        }
    }

    // ---- form.field.remove --------------------------------------------------------------

    /** Remove a component; refuses while a PRD data item maps it unless {@code --force}. */
    public static final class FieldRemove implements Operation {
        private final String driver;
        private final String ref;
        private final String key;

        public FieldRemove(String driver, String ref, String key) {
            this.driver = driver;
            this.ref = ref;
            this.key = key;
        }

        @Override
        public String name() {
            return "form.field.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (key == null || key.isBlank()) {
                throw new Operation.Refusal("--key is required");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Map<String, Object> root = Json.asMap(Json.parse(found.form.json));
            if (FormEditor.find(root, key) == null) {
                throw new Operation.Refusal("form '" + found.form.name + "' has no component with key '" + key + "'");
            }
            if (!tx.force()) {
                for (Prd prd : found.driver.provisioning.prds) {
                    if (BindingSync.binds(prd, found.form.name) && isMapped(prd, key)) {
                        throw new Operation.Refusal("field '" + key + "' is mapped by prd '" + prd.name
                            + "'; unmap it first (prd.map --unmap) or use --force");
                    }
                }
            }
            FormEditor.remove(root, key);
            applyDocument(tx, found, root);
            tx.note("form '" + found.form.name + "': removed field '" + key + "'");
        }
    }

    private static boolean isMapped(Prd prd, String key) {
        if (prd.request != null) {
            for (Element di : Xds.descendantsByName(prd.request, "data-item")) {
                if (key.equals(di.getAttribute("name"))) {
                    return true;
                }
            }
        }
        if (prd.process != null) {
            for (Element di : Xds.descendantsByName(prd.process, "data-item")) {
                if (key.equals(di.getAttribute("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- form.field.move ----------------------------------------------------------------

    /** Reorder or reparent a component. */
    public static final class FieldMove implements Operation {
        private final String driver;
        private final String ref;
        private final String key;
        private final String after;
        private final String before;
        private final boolean first;
        private final boolean last;
        private final String in;

        public FieldMove(String driver, String ref, String key, String after, String before,
                          boolean first, boolean last, String in) {
            this.driver = driver;
            this.ref = ref;
            this.key = key;
            this.after = (after == null || after.isBlank()) ? null : after;
            this.before = (before == null || before.isBlank()) ? null : before;
            this.first = first;
            this.last = last;
            this.in = (in == null || in.isBlank()) ? null : in;
        }

        @Override
        public String name() {
            return "form.field.move";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (key == null || key.isBlank()) {
                throw new Operation.Refusal("--key is required");
            }
            if (after == null && before == null && !first && !last) {
                throw new Operation.Refusal("give --after, --before, --first or --last");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Map<String, Object> root = Json.asMap(Json.parse(found.form.json));
            try {
                FormEditor.move(root, key, placementOf(after, before, first, in));
            } catch (IllegalArgumentException e) {
                throw new Operation.Refusal(e.getMessage());
            }
            applyDocument(tx, found, root);
            tx.note("form '" + found.form.name + "': moved field '" + key + "'");
        }
    }

    // ---- form.set -------------------------------------------------------------------------

    /** Change a form's top-level properties: title, display mode, the inline script, or an external script. */
    public static final class SetForm implements Operation {
        private final String driver;
        private final String ref;
        private final String title;
        private final String display;
        private final String inlineScript;   // already-read file content, or null
        private final String externalScript; // a URL, or null
        private final boolean removeExternal;

        public SetForm(String driver, String ref, String title, String display, String inlineScript,
                        String externalScript, boolean removeExternal) {
            this.driver = driver;
            this.ref = ref;
            this.title = (title == null || title.isBlank()) ? null : title;
            this.display = (display == null || display.isBlank()) ? null : display;
            this.inlineScript = inlineScript;
            this.externalScript = (externalScript == null || externalScript.isBlank()) ? null : externalScript;
            this.removeExternal = removeExternal;
        }

        @Override
        public String name() {
            return "form.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (title == null && display == null && inlineScript == null && externalScript == null) {
                throw new Operation.Refusal("give --title, --display, --inline-script or --external-script");
            }
            if (display != null && !display.equals("form") && !display.equals("workflowWizard")) {
                throw new Operation.Refusal("--display must be form or workflowWizard");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Map<String, Object> root = Json.asMap(Json.parse(found.form.json));
            if (title != null) {
                root.put("title", title);
            }
            if (display != null) {
                root.put("display", display);
            }
            if (inlineScript != null) {
                root.put("inlinescripts", inlineScript);
            }
            if (externalScript != null) {
                List<Object> list = Json.asList(root.get("externalScripts"));
                root.put("externalScripts", list);
                if (removeExternal) {
                    list.removeIf(o -> externalScript.equals(Json.asString(o)));
                } else if (!list.contains(externalScript)) {
                    list.add(externalScript);
                }
            }
            applyDocument(tx, found, root);
            tx.note("form '" + found.form.name + "': updated");
        }
    }

    // ---- form.localize ----------------------------------------------------------------------

    /** Set explicit localized strings, or top up every declared language with English/source text for anything missing. */
    public static final class Localize implements Operation {
        private final String driver;
        private final String ref;
        private final String lang;
        private final List<String> setPairs;
        private final boolean sync;

        public Localize(String driver, String ref, String lang, List<String> setPairs, boolean sync) {
            this.driver = driver;
            this.ref = ref;
            this.lang = lang;
            this.setPairs = setPairs;
            this.sync = sync;
        }

        @Override
        public String name() {
            return "form.localize";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (lang == null || lang.isBlank()) {
                throw new Operation.Refusal("--lang is required");
            }
            boolean hasSet = setPairs != null && !setPairs.isEmpty() && setPairs.stream().anyMatch(s -> !s.isBlank());
            if (!hasSet && !sync) {
                throw new Operation.Refusal("give --set or --sync");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Map<String, Object> root = Json.asMap(Json.parse(found.form.json));
            Map<String, Object> localization = Json.asMap(root.get("localization"));
            root.put("localization", localization);
            Map<String, Object> langMap = Json.asMap(localization.get(lang));
            localization.put(lang, langMap);

            if (hasSet) {
                for (String pair : setPairs) {
                    if (pair.isBlank()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        throw new Operation.Refusal("--set must be Label=Value: '" + pair + "'");
                    }
                    langMap.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            if (sync) {
                // top up every declared language with the union of what the components declare and
                // what any language already carries (e.g. stock forms' extra en-only titles/buttons/
                // validation messages), so a synced form never trips validate's form-localization-missing
                Set<String> keys = new LinkedHashSet<>();
                collectLocalizableText(root, keys);
                for (Object v : localization.values()) {
                    keys.addAll(Json.asMap(v).keySet());
                }
                Map<String, Object> enMap = Json.asMap(localization.get("en"));
                for (Map.Entry<String, Object> e : localization.entrySet()) {
                    Map<String, Object> lm = Json.asMap(e.getValue());
                    localization.put(e.getKey(), lm);
                    int added = 0;
                    for (String k : keys) {
                        if (!lm.containsKey(k)) {
                            Object value = langMap.containsKey(k) ? langMap.get(k)
                                : enMap.containsKey(k) ? enMap.get(k) : k;
                            lm.put(k, value);
                            added++;
                        }
                    }
                    if (added > 0) {
                        tx.note("form '" + found.form.name + "': localization '" + e.getKey() + "': added " + added
                            + " entr" + (added == 1 ? "y" : "ies"));
                    }
                }
            }
            applyDocument(tx, found, root);
        }
    }

    private static void collectLocalizableText(Map<String, Object> root, Set<String> out) {
        for (FormEditor.Located l : FormEditor.all(root)) {
            addIfText(l.component.get("label"), out);
            addIfText(l.component.get("placeholder"), out);
            addIfText(l.component.get("tooltip"), out);
            Object data = l.component.get("data");
            if (data instanceof Map) {
                Object values = ((Map<?, ?>) data).get("values");
                if (values instanceof List) {
                    for (Object v : (List<?>) values) {
                        if (v instanceof Map) {
                            addIfText(((Map<?, ?>) v).get("label"), out);
                        }
                    }
                }
            }
        }
    }

    private static void addIfText(Object v, Set<String> out) {
        if (v instanceof String && !((String) v).isBlank()) {
            out.add((String) v);
        }
    }

    // ---- form.rename ------------------------------------------------------------------------

    /** Rename a form and rewrite every PRD reference to it: form-id attributes and flowdata prefixes. */
    public static final class Rename implements Operation {
        private final String driver;
        private final String ref;
        private final String to;

        public Rename(String driver, String ref, String to) {
            this.driver = driver;
            this.ref = ref;
            this.to = to;
        }

        @Override
        public String name() {
            return "form.rename";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (to == null || to.isBlank()) {
                throw new Operation.Refusal("--to is required");
            }
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            Driver d = found.driver;
            Form oldForm = found.form;
            if (to.equals(oldForm.name)) {
                tx.note("form '" + oldForm.name + "': name unchanged");
                return;
            }
            if (d.provisioning.form(oldForm.kind, to) != null) {
                throw new Operation.Refusal("a " + oldForm.kind.dir + " form named '" + to + "' already exists on driver '" + d.name + "'");
            }
            String oldPath = path(d, oldForm);
            Form renamed = new Form(oldForm.kind, to, oldForm.json);
            renamed.meta.putAll(oldForm.meta);
            int idx = d.provisioning.forms.indexOf(oldForm);
            d.provisioning.forms.set(idx, renamed);
            String newPath = path(d, renamed);
            customizeForm(tx, d, renamed);
            tx.touched(newPath);
            tx.renamed(oldPath, newPath);
            tx.note("renamed form '" + oldForm.name + "' -> '" + to + "'");

            String oldSeg = oldForm.name.replace(' ', '_');
            String newSeg = to.replace(' ', '_');
            for (Prd prd : d.provisioning.prds) {
                if (!BindingSync.binds(prd, oldForm.name)) {
                    continue;
                }
                String before = prdFingerprint(prd);
                boolean changed = rewriteFormIdAttrs(prd.request, oldForm.name, to);
                changed |= rewriteFormIdAttrs(prd.process, oldForm.name, to);
                changed |= rewriteFlowdataPrefixes(prd.request, oldSeg, newSeg);
                changed |= rewriteFlowdataPrefixes(prd.process, oldSeg, newSeg);
                if (changed) {
                    customizePrd(tx, d, prd, before);
                    tx.touched(prdPath(d, prd));
                    tx.note("prd '" + prd.name + "': rewrote references to renamed form '" + oldForm.name + "' -> '" + to + "'");
                }
            }
        }
    }

    private static boolean rewriteFormIdAttrs(Element root, String oldName, String newName) {
        if (root == null) {
            return false;
        }
        boolean[] changed = {false};
        rewriteFormId(root, oldName, newName, changed);
        return changed[0];
    }

    private static void rewriteFormId(Element e, String oldName, String newName, boolean[] changed) {
        if (oldName.equals(e.getAttribute("form-id"))) {
            e.setAttribute("form-id", newName);
            changed[0] = true;
        }
        for (Element c : Xds.childElements(e)) {
            rewriteFormId(c, oldName, newName, changed);
        }
    }

    /** Rewrites {@code flowdata.<activity>/<oldSeg>/} (and {@code flowdata.get('<activity>/<oldSeg>/…')}) to the new segment, in every attribute value under {@code root}. */
    private static boolean rewriteFlowdataPrefixes(Element root, String oldSeg, String newSeg) {
        if (root == null || oldSeg.equals(newSeg)) {
            return false;
        }
        Pattern p = Pattern.compile("(flowdata(?:\\.get\\('|\\.))([^/'\"]+)/" + Pattern.quote(oldSeg) + "/");
        boolean[] changed = {false};
        rewriteAttrs(root, p, newSeg, changed);
        return changed[0];
    }

    private static void rewriteAttrs(Element e, Pattern p, String newSeg, boolean[] changed) {
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String v = a.getValue();
            Matcher m = p.matcher(v);
            if (m.find()) {
                String nv = m.replaceAll("$1$2/" + Matcher.quoteReplacement(newSeg) + "/");
                if (!nv.equals(v)) {
                    a.setValue(nv);
                    changed[0] = true;
                }
            }
        }
        for (Element c : Xds.childElements(e)) {
            rewriteAttrs(c, p, newSeg, changed);
        }
    }

    // ---- form.delete ------------------------------------------------------------------------

    /** Delete a form; refuses while any PRD binds it (never overridden by {@code --force}); a packaged form needs {@code --force}. */
    public static final class Delete implements Operation {
        private final String driver;
        private final String ref;

        public Delete(String driver, String ref) {
            this.driver = driver;
            this.ref = ref;
        }

        @Override
        public String name() {
            return "form.delete";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Found found = find(ds, ref, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(ref, driver));
            }
            for (Prd prd : found.driver.provisioning.prds) {
                if (BindingSync.binds(prd, found.form.name)) {
                    throw new Operation.Refusal("form '" + found.form.name + "' is bound by prd '" + prd.name
                        + "'; form.delete never removes bindings — unmap or rebind it first (--force does not override this)");
                }
            }
            if (isPackaged(found.form.meta) && !tx.force()) {
                throw new Operation.Refusal("form '" + found.form.name + "' is a packaged (stock) form; pass --force to delete it anyway");
            }
            String path = path(found.driver, found.form);
            found.driver.provisioning.forms.remove(found.form);
            tx.touched(path);
            tx.note("deleted " + found.form.kind.dir + " form '" + found.form.name + "'");
        }
    }

    // ---- prd.map ----------------------------------------------------------------------------

    /** A PRD and the driver that owns it. */
    public static final class FoundPrd {
        public final Driver driver;
        public final Prd prd;

        FoundPrd(Driver driver, Prd prd) {
            this.driver = driver;
            this.prd = prd;
        }
    }

    /** Finds a PRD by name, optionally restricted to one driver; null when absent or ambiguous across drivers. */
    public static FoundPrd findPrd(DriverSet ds, String name, String driverFlag) {
        FoundPrd hit = null;
        for (Driver d : ds.drivers) {
            if (driverFlag != null && !driverFlag.equals(d.name)) {
                continue;
            }
            if (d.provisioning == null) {
                continue;
            }
            Prd p = d.provisioning.prd(name);
            if (p != null) {
                if (hit != null) {
                    return null;
                }
                hit = new FoundPrd(d, p);
            }
        }
        return hit;
    }

    private static String prdNotFoundMessage(String ref, String driver) {
        return "prd '" + ref + "' not found" + (driver == null ? " (or found on several drivers — say --driver)" : " on driver '" + driver + "'");
    }

    /** Add, replace, or (with {@code --unmap}) remove a field's data-item mapping. */
    public static final class PrdMap implements Operation {
        private final String driver;
        private final String prdRef;
        private final String field;
        private final String activity;
        private final String target;
        private final String source;
        private final boolean unmap;

        public PrdMap(String driver, String prdRef, String field, String activity, String target, String source, boolean unmap) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.field = field;
            this.activity = (activity == null || activity.isBlank()) ? null : activity;
            this.target = (target == null || target.isBlank()) ? null : target;
            this.source = (source == null || source.isBlank()) ? null : source;
            this.unmap = unmap;
        }

        @Override
        public String name() {
            return "prd.map";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (field == null || field.isBlank()) {
                throw new Operation.Refusal("--field is required");
            }
            FoundPrd found = findPrd(ds, prdRef, driver);
            if (found == null) {
                throw new Operation.Refusal(prdNotFoundMessage(prdRef, driver));
            }
            Driver d = found.driver;
            Prd prd = found.prd;
            String before = prdFingerprint(prd);
            boolean changed = activity == null
                ? mapRequestField(d, prd, field, target, unmap)
                : mapActivityField(d, prd, field, activity, source, unmap);
            if (changed) {
                customizePrd(tx, d, prd, before);
                tx.touched(prdPath(d, prd));
                tx.note("prd '" + prd.name + "': " + (unmap ? "unmapped" : "mapped") + " field '" + field + "'"
                    + (activity == null ? " (request form)" : " (activity '" + activity + "')"));
            } else {
                tx.note("prd '" + prd.name + "': no change");
            }
        }
    }

    private static boolean mapRequestField(Driver d, Prd prd, String field, String targetOverride, boolean unmap) throws Operation.Refusal {
        if (prd.request == null) {
            throw new Operation.Refusal("prd '" + prd.name + "' has no request-form binding");
        }
        Element fb = firstChild(prd.request, "form-binding");
        if (fb == null) {
            throw new Operation.Refusal("prd '" + prd.name + "' has no request-form binding");
        }
        String formId = fb.getAttribute("form-id");
        Form form = d.provisioning.formByName(formId);
        if (form == null) {
            throw new Operation.Refusal("request form '" + formId + "' not found on driver '" + d.name + "'");
        }
        BindingSync.Item item = itemByKey(form.json, field);
        if (item == null || !item.bindable()) {
            throw new Operation.Refusal("field '" + field + "' is not bound/bindable in form '" + formId + "'");
        }
        Element holder = firstChild(prd.request, "request-data-items");
        Element scope = holder != null ? holder : prd.request;
        if (unmap) {
            if (!removeDataItem(scope, field)) {
                throw new Operation.Refusal("field '" + field + "' is not mapped in prd '" + prd.name + "'");
            }
            return true;
        }
        String tgt = targetOverride != null ? targetOverride
            : "flowdata." + BindingSync.startActivityId(prd) + "/" + formIdSegment(formId) + "/" + field;
        setDataItem(scope, field, item.dataType(), "target", tgt, targetType(item));
        return true;
    }

    private static boolean mapActivityField(Driver d, Prd prd, String field, String activity, String sourceOverride, boolean unmap) throws Operation.Refusal {
        if (prd.process == null) {
            throw new Operation.Refusal("prd '" + prd.name + "' has no process");
        }
        Prd.FormBinding actBinding = null;
        for (Prd.FormBinding b : prd.bindings()) {
            if (activity.equals(b.activityId)) {
                actBinding = b;
                break;
            }
        }
        if (actBinding == null) {
            throw new Operation.Refusal("activity '" + activity + "' has no form binding in prd '" + prd.name + "'");
        }
        Form form = d.provisioning.formByName(actBinding.formId);
        if (form == null) {
            throw new Operation.Refusal("form '" + actBinding.formId + "' (bound to activity '" + activity + "') not found on driver '" + d.name + "'");
        }
        BindingSync.Item item = itemByKey(form.json, field);
        if (item == null || !item.bindable()) {
            throw new Operation.Refusal("field '" + field + "' is not bound/bindable in form '" + actBinding.formId + "'");
        }
        Element holder = null;
        for (Element di : Xds.childrenByName(prd.process, "data-items")) {
            if (activity.equals(di.getAttribute("activity-id"))) {
                holder = di;
                break;
            }
        }
        if (unmap) {
            if (holder == null || !removeDataItem(holder, field)) {
                throw new Operation.Refusal("field '" + field + "' is not mapped for activity '" + activity + "' in prd '" + prd.name + "'");
            }
            return true;
        }
        if (holder == null) {
            holder = prd.process.getOwnerDocument().createElementNS(null, "data-items");
            holder.setAttribute("activity-id", activity);
            prd.process.appendChild(holder);
        }
        String src = sourceOverride;
        if (src == null) {
            Prd.FormBinding reqBinding = null;
            for (Prd.FormBinding b : prd.bindings()) {
                if (b.activityId == null) {
                    reqBinding = b;
                    break;
                }
            }
            Form reqForm = reqBinding == null ? null : d.provisioning.formByName(reqBinding.formId);
            BindingSync.Item reqItem = reqForm == null ? null : itemByKey(reqForm.json, field);
            if (reqBinding == null || reqItem == null) {
                throw new Operation.Refusal("give --source; the request form has no field '" + field + "'");
            }
            src = "flowdata.get('" + BindingSync.startActivityId(prd) + "/" + formIdSegment(reqBinding.formId) + "/" + field + "')";
        }
        setDataItem(holder, field, item.dataType(), "source", src, targetType(item));
        return true;
    }

    private static String targetType(BindingSync.Item item) {
        return item.multiple ? "multi-value-list" : "single-value";
    }

    private static BindingSync.Item itemByKey(String formJson, String key) {
        if (formJson == null) {
            return null;
        }
        for (BindingSync.Item it : BindingSync.items(formJson)) {
            if (it.key.equals(key)) {
                return it;
            }
        }
        return null;
    }

    private static String formIdSegment(String formId) {
        return formId.replace(' ', '_');
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> c = Xds.childrenByName(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static boolean removeDataItem(Element holder, String name) {
        for (Element di : Xds.childrenByName(holder, "data-item")) {
            if (name.equals(di.getAttribute("name"))) {
                holder.removeChild(di);
                return true;
            }
        }
        return false;
    }

    private static void setDataItem(Element holder, String name, String dataType, String attr, String value, String targetType) {
        Element di = null;
        for (Element e : Xds.childrenByName(holder, "data-item")) {
            if (name.equals(e.getAttribute("name"))) {
                di = e;
                break;
            }
        }
        if (di == null) {
            di = holder.getOwnerDocument().createElementNS(null, "data-item");
            di.setAttribute("name", name);
            holder.appendChild(di);
        }
        di.setAttribute("data-type", dataType);
        di.setAttribute(attr, value);
        di.setAttribute("target-type", targetType);
    }

    // ---- prd.add ------------------------------------------------------------------------------

    /** Copy a template PRD (status {@code Template}) into a new Active PRD bound to the given forms. */
    public static final class PrdAdd implements Operation {
        private final String driver;
        private final String name;
        private final String fromTemplate;
        private final String requestForm;
        private final String approvalForm;
        private final String category;
        private final String displayName;
        private final boolean mapAll;

        public PrdAdd(String driver, String name, String fromTemplate, String requestForm, String approvalForm,
                       String category, String displayName, boolean mapAll) {
            this.driver = driver;
            this.name = name;
            this.fromTemplate = fromTemplate;
            this.requestForm = requestForm;
            this.approvalForm = (approvalForm == null || approvalForm.isBlank()) ? null : approvalForm;
            this.category = (category == null || category.isBlank()) ? null : category;
            this.displayName = (displayName == null || displayName.isBlank()) ? null : displayName;
            this.mapAll = mapAll;
        }

        @Override
        public String name() {
            return "prd.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (name == null || name.isBlank()) {
                throw new Operation.Refusal("--name is required");
            }
            if (fromTemplate == null || fromTemplate.isBlank()) {
                throw new Operation.Refusal("--from-template is required");
            }
            if (requestForm == null || requestForm.isBlank()) {
                throw new Operation.Refusal("--request-form is required");
            }
            if (displayName != null && displayName.indexOf('~') <= 0) {
                throw new Operation.Refusal("--display-name must be lang~Text");
            }
            Driver d = driverFor(ds, driver);
            if (d.provisioning.prd(name) != null) {
                throw new Operation.Refusal("a prd named '" + name + "' already exists on driver '" + d.name + "'");
            }
            Prd tmpl = d.provisioning.prd(fromTemplate);
            if (tmpl == null) {
                throw new Operation.Refusal("template prd '" + fromTemplate + "' not found on driver '" + d.name + "'");
            }
            if (!tmpl.isJsonForms()) {
                throw new Operation.Refusal("template prd '" + fromTemplate + "' is a classic (XForms) PRD; prd.add only supports JSON-forms templates");
            }
            Found reqFound = find(ds, requestForm, driver);
            if (reqFound == null) {
                throw new Operation.Refusal(notFoundMessage(requestForm, driver));
            }
            Found apprFound = null;
            if (approvalForm != null) {
                apprFound = find(ds, approvalForm, driver);
                if (apprFound == null) {
                    throw new Operation.Refusal(notFoundMessage(approvalForm, driver));
                }
            }

            Prd created = clonePrd(tmpl, name);
            created.properties.put("status", new ArrayList<>(List.of("Active")));
            String cat = category != null ? category : tmpl.property("category-key");
            if (cat != null) {
                created.properties.put("category-key", new ArrayList<>(List.of(cat)));
                if (created.definition != null) {
                    created.definition.setAttribute("prov-category", cat);
                }
            }
            if (created.definition != null) {
                created.definition.setAttribute("status", "Active");
                created.definition.setAttribute("prov-id", name);
            }
            rewriteAttrValue(created.definition, "prov-id", tmpl.name, name);
            rewriteAttrValue(created.process, "prov-id", tmpl.name, name);
            rewriteProcessId(created.process, tmpl.name, name);
            renameDisplayNames(created.definition, name, displayName);
            renameDisplayNames(created.process, name, displayName);
            String names = joinLangText(created.definition, "display-name");
            if (names != null) {
                created.properties.put("localized-names", new ArrayList<>(List.of(names)));
            }

            Element reqBinding = firstChild(created.request, "form-binding");
            if (reqBinding != null) {
                reqBinding.setAttribute("form-id", reqFound.form.name);
            }
            if (created.request != null) {
                BindingSync.sync(created, reqFound.form);
            }
            String activityId = null;
            if (apprFound != null && created.process != null) {
                List<Element> userActivities = Xds.descendantsByName(created.process, "user-activity");
                if (!userActivities.isEmpty()) {
                    activityId = userActivities.get(0).getAttribute("activity-id");
                    for (Element fb : Xds.descendantsByName(created.process, "form-binding")) {
                        if (activityId.equals(fb.getAttribute("activity-id"))) {
                            fb.setAttribute("form-id", apprFound.form.name);
                            break;
                        }
                    }
                    BindingSync.sync(created, apprFound.form);
                }
            }

            int mapped = 0;
            List<String> skippedActivityFields = new ArrayList<>();
            if (mapAll) {
                if (created.request != null) {
                    for (BindingSync.Item item : BindingSync.items(reqFound.form.json)) {
                        if (item.bindable()) {
                            mapRequestField(d, created, item.key, null, false);
                            mapped++;
                        }
                    }
                }
                if (apprFound != null && activityId != null) {
                    for (BindingSync.Item item : BindingSync.items(apprFound.form.json)) {
                        if (!item.bindable()) {
                            continue;
                        }
                        try {
                            mapActivityField(d, created, item.key, activityId, null, false);
                            mapped++;
                        } catch (Operation.Refusal refusal) {
                            skippedActivityFields.add(item.key);
                        }
                    }
                }
            }

            d.provisioning.prds.add(created);
            tx.touched(prdPath(d, created));
            tx.note("created prd '" + name + "' from template '" + fromTemplate + "' (request form '" + reqFound.form.name + "'"
                + (apprFound != null ? ", approval form '" + apprFound.form.name + "'" : "") + ")"
                + (mapAll ? "; mapped " + mapped + " field" + (mapped == 1 ? "" : "s") : ""));
            if (!skippedActivityFields.isEmpty()) {
                tx.note("prd '" + name + "': skipped approval field" + (skippedActivityFields.size() == 1 ? "" : "s")
                    + " not present on the request form: " + String.join(", ", skippedActivityFields));
            }
        }
    }

    // ---- prd.delete ---------------------------------------------------------------------------

    /**
     * Delete a PRD (the mirror of {@link Delete form.delete}); refuses while another PRD's process
     * still starts it via a {@code start-correlated-flow-activity} (never overridden by
     * {@code --force}); a packaged (stock) PRD needs {@code --force}. The forms it bound are not
     * touched — a following {@code form.delete} on one of them now succeeds.
     */
    public static final class PrdDelete implements Operation {
        private final String driver;
        private final String prdRef;

        public PrdDelete(String driver, String prdRef) {
            this.driver = driver;
            this.prdRef = prdRef;
        }

        @Override
        public String name() {
            return "prd.delete";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FoundPrd found = findPrd(ds, prdRef, driver);
            if (found == null) {
                throw new Operation.Refusal(prdNotFoundMessage(prdRef, driver));
            }
            Driver d = found.driver;
            Prd prd = found.prd;
            for (Prd referencer : d.provisioning.prds) {
                if (referencer == prd) {
                    continue;
                }
                String activityId = correlatedFlowReference(referencer, prd.name);
                if (activityId != null) {
                    throw new Operation.Refusal("prd '" + prd.name + "' is referenced by prd '" + referencer.name
                        + "'s start-correlated-flow-activity '" + activityId
                        + "'; prd.delete never removes references (--force does not override this)");
                }
            }
            if (isPackaged(prd.meta) && !tx.force()) {
                throw new Operation.Refusal("prd '" + prd.name + "' is a packaged (stock) prd; pass --force to delete it anyway");
            }
            List<String> boundForms = new ArrayList<>();
            for (Prd.FormBinding b : prd.bindings()) {
                if (b.formId != null && !boundForms.contains(b.formId)) {
                    boundForms.add(b.formId);
                }
            }
            String path = prdPath(d, prd);
            d.provisioning.prds.remove(prd);
            tx.touched(path);
            tx.note("deleted prd '" + prd.name + "'");
            if (!boundForms.isEmpty()) {
                tx.note("forms it bound remain: " + boundForms);
            }
        }
    }

    /**
     * The {@code activity-id} of {@code referencer}'s {@code start-correlated-flow-activity} whose
     * {@code processId} names {@code targetName} — by bare name or by a DN whose first RDN is
     * {@code cn=<targetName>} — or null if none does.
     */
    private static String correlatedFlowReference(Prd referencer, String targetName) {
        if (referencer.process == null) {
            return null;
        }
        String dnPrefix = "cn=" + targetName + ",";
        for (Element sc : com.pointblue.dirxml.sim.Xds.descendantsByName(referencer.process, "start-correlated-flow-activity")) {
            String pid = sc.getAttribute("processId");
            if (targetName.equals(pid) || pid.startsWith(dnPrefix)) {
                return sc.getAttribute("activity-id");
            }
        }
        return null;
    }

    private static Prd clonePrd(Prd src, String newName) {
        Prd out = new Prd(newName);
        Element defClone = src.definition == null ? null : cloneElement(src.definition);
        out.definition = defClone;
        Element procClone;
        if (src.process != null && isChildOf(src.process, src.definition)) {
            List<Element> procs = defClone == null ? List.of() : Xds.childrenByName(defClone, "process");
            procClone = procs.isEmpty() ? null : procs.get(0);
        } else {
            procClone = src.process == null ? null : cloneElement(src.process);
        }
        out.process = procClone;
        out.request = src.request == null ? null : cloneElement(src.request);
        for (Map.Entry<String, List<String>> e : src.properties.entrySet()) {
            out.properties.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        // src.meta intentionally not copied — a new PRD is not a packaged object.
        return out;
    }

    private static boolean isChildOf(Element child, Element parent) {
        if (parent == null) {
            return false;
        }
        for (Element e : Xds.childrenByName(parent, child.getNodeName())) {
            if (e == child) {
                return true;
            }
        }
        return false;
    }

    private static Element cloneElement(Element e) {
        return CanonicalXml.parse(CanonicalXml.serialize(e)).getDocumentElement();
    }

    private static boolean rewriteAttrValue(Element root, String attrName, String oldValue, String newValue) {
        if (root == null) {
            return false;
        }
        boolean[] changed = {false};
        rewriteAttrValue(root, attrName, oldValue, newValue, changed);
        return changed[0];
    }

    private static void rewriteAttrValue(Element e, String attrName, String oldValue, String newValue, boolean[] changed) {
        if (e.hasAttribute(attrName) && oldValue.equals(e.getAttribute(attrName))) {
            e.setAttribute(attrName, newValue);
            changed[0] = true;
        }
        for (Element c : Xds.childElements(e)) {
            rewriteAttrValue(c, attrName, oldValue, newValue, changed);
        }
    }

    /** {@code <process id="cn=<old>,…">} — rewrite the leading {@code cn=} segment only. */
    private static void rewriteProcessId(Element process, String oldName, String newName) {
        if (process == null || !process.hasAttribute("id")) {
            return;
        }
        String id = process.getAttribute("id");
        String oldPrefix = "cn=" + oldName + ",";
        if (id.startsWith(oldPrefix)) {
            process.setAttribute("id", "cn=" + newName + "," + id.substring(oldPrefix.length()));
        }
    }

    /** Every {@code <display-name>} gets the new technical name; {@code displayNameOverride} ("lang~Text") overrides one language. */
    private static void renameDisplayNames(Element root, String newName, String displayNameOverride) {
        if (root == null) {
            return;
        }
        for (Element dn : Xds.childrenByName(root, "display-name")) {
            setText(dn, newName);
        }
        if (displayNameOverride != null) {
            int tilde = displayNameOverride.indexOf('~');
            String lang = displayNameOverride.substring(0, tilde);
            String text = displayNameOverride.substring(tilde + 1);
            Element target = null;
            for (Element dn : Xds.childrenByName(root, "display-name")) {
                if (lang.equals(xmlLang(dn))) {
                    target = dn;
                    break;
                }
            }
            if (target == null) {
                target = root.getOwnerDocument().createElementNS(null, "display-name");
                target.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:lang", lang);
                root.appendChild(target);
            }
            setText(target, text);
        }
    }

    private static void setText(Element e, String text) {
        while (e.getFirstChild() != null) {
            e.removeChild(e.getFirstChild());
        }
        e.appendChild(e.getOwnerDocument().createTextNode(text));
    }

    private static String xmlLang(Element el) {
        String v = el.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
        return v == null || v.isEmpty() ? null : v;
    }

    /** {@code lang~text|lang~text|…} over every direct {@code <childName xml:lang>} child, document order (matches {@code ProjectReader}'s format). */
    private static String joinLangText(Element root, String childName) {
        if (root == null) {
            return null;
        }
        List<Element> els = Xds.childrenByName(root, childName);
        if (els.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Element el : els) {
            String lang = xmlLang(el);
            String text = Xds.text(el);
            if (lang == null || text == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(lang).append('~').append(text);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---- driver resolution --------------------------------------------------------------------

    /** The one driver with provisioning, or the named one; refuses when ambiguous or absent. */
    private static Driver driverFor(DriverSet ds, String driverFlag) throws Operation.Refusal {
        if (driverFlag != null && !driverFlag.isBlank()) {
            Driver d = ds.driver(driverFlag);
            if (d == null) {
                throw new Operation.Refusal("no driver '" + driverFlag + "'");
            }
            if (d.provisioning == null) {
                throw new Operation.Refusal("driver '" + driverFlag + "' has no provisioning (AppConfig)");
            }
            return d;
        }
        Driver hit = null;
        for (Driver d : ds.drivers) {
            if (d.provisioning != null) {
                if (hit != null) {
                    throw new Operation.Refusal("more than one driver has provisioning (AppConfig) — say --driver");
                }
                hit = d;
            }
        }
        if (hit == null) {
            throw new Operation.Refusal("no driver has provisioning (AppConfig); it must exist on the driver first");
        }
        return hit;
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
