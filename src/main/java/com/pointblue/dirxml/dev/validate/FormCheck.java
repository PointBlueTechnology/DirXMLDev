package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.BindingSync;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provisioning forms and PRDs (Track P step P2b — see {@code docs/forms.md} §2). On by
 * default in {@link Validator#standard}.
 *
 * <p>Codes: {@code form-json-invalid} (E), {@code form-no-components} (E),
 * {@code form-duplicate-key} (E), {@code form-key-missing} (E), {@code form-unknown-type}
 * (W), {@code form-conditional-ref} (E), {@code form-script-syntax} (E),
 * {@code form-request-buttons} (W), {@code form-localization-missing} (I),
 * {@code prd-binding-stale} (E), {@code prd-binding-fields-drift} (W),
 * {@code prd-mapping-unbound} (E).
 */
public final class FormCheck implements Check {

    /** The 22 component templates captured from the vendor builder ({@code resources/forms/components/}). */
    private static final Set<String> COMPONENT_TEMPLATES = Set.of(
        "button", "checkbox", "column", "columns", "container", "dataItemMappingTextField", "data_item_mapping",
        "datagrid", "datetime", "dn_display", "dynamic_entity", "htmlelement", "labelelement", "panel",
        "permission_requestDN", "radio", "select", "tabs", "textarea", "textfield", "title", "tree");

    /** The standard Form.io types not otherwise covered (a captured template, or a {@link BindingSync#DATA_TYPES} key). */
    private static final Set<String> STANDARD_TYPES = Set.of(
        "button", "columns", "column", "panel", "well", "fieldset", "table", "tabs", "content", "htmlelement",
        "hidden", "container", "datagrid", "editgrid", "datamap", "tree", "day", "time", "address", "resource",
        "form", "unknown", "custom");

    private static final Pattern TEMPLATE_ONLY = Pattern.compile("^\\{\\{.*}}$", Pattern.DOTALL);

    @Override
    public String name() {
        return "forms";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (Driver d : ds.drivers) {
            if (d.provisioning == null) {
                continue;
            }
            for (Form f : d.provisioning.forms) {
                checkForm(d, f, r);
            }
            for (Prd prd : d.provisioning.prds) {
                checkPrd(d, prd, r);
            }
        }
    }

    // ---- forms --------------------------------------------------------------------------

    private static void checkForm(Driver d, Form f, Report r) {
        String path = formPath(d, f);
        Object parsed;
        try {
            parsed = Json.parse(f.json);
        } catch (RuntimeException e) {
            r.add(Finding.error("form-json-invalid", path, "form document is not valid JSON: " + e.getMessage()));
            return;
        }
        if (!(parsed instanceof Map)) {
            r.add(Finding.error("form-json-invalid", path, "form document must be a JSON object"));
            return;
        }
        Map<String, Object> root = Json.asMap(parsed);
        Object comps = root.get("components");
        // an empty list is a legitimate blank form (form.add's starting point); missing/wrong-typed is not
        if (!(comps instanceof List)) {
            r.add(Finding.error("form-no-components", path, "form has no \"components\" array"));
        }

        Map<String, Integer> counts = globalKeyCounts(root);
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                r.add(Finding.error("form-duplicate-key", path,
                    "key '" + e.getKey() + "' appears on " + e.getValue() + " components — Designer's keys are global"));
            }
        }

        boolean[] hasButton = {false};
        walk(root, obj -> {
            Object type = obj.get("type");
            if (!(type instanceof String) || ((String) type).isEmpty()) {
                return;
            }
            String t = (String) type;
            Object key = obj.get("key");
            if (!(key instanceof String) || ((String) key).isEmpty()) {
                r.add(Finding.error("form-key-missing", path, "a '" + t + "' component has no key"));
            }
            if (!isKnownType(t)) {
                r.add(Finding.warning("form-unknown-type", path,
                    "component type '" + t + "' is not one of the 22 captured templates, a BindingSync data type, or a standard Form.io type"));
            }
            if ("button".equals(t)) {
                hasButton[0] = true;
            }
        });
        if (f.kind == Form.Kind.REQUEST && !hasButton[0]) {
            r.add(Finding.warning("form-request-buttons", path, "request form has no button component"));
        }

        checkConditionalRefs(root, counts.keySet(), path, r);
        checkScripts(root, path, r);
        checkLocalization(root, path, r);
    }

    private static boolean isKnownType(String t) {
        if (COMPONENT_TEMPLATES.contains(t) || STANDARD_TYPES.contains(t)) {
            return true;
        }
        return BindingSync.DATA_TYPES.containsKey(t.toUpperCase(Locale.ROOT));
    }

    private static void checkConditionalRefs(Object node, Set<String> knownKeys, String path, Report r) {
        if (node instanceof Map) {
            Map<String, Object> m = Json.asMap(node);
            checkWhen(Json.asMap(m.get("conditional")).get("when"), knownKeys, path, r, "conditional.when");
            Object logic = m.get("logic");
            if (logic instanceof List) {
                for (Object item : (List<?>) logic) {
                    Map<String, Object> trigger = Json.asMap(Json.asMap(item).get("trigger"));
                    Map<String, Object> simple = Json.asMap(trigger.get("simple"));
                    checkWhen(simple.get("when"), knownKeys, path, r, "logic[].trigger.simple.when");
                }
            }
            for (Object v : m.values()) {
                checkConditionalRefs(v, knownKeys, path, r);
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                checkConditionalRefs(v, knownKeys, path, r);
            }
        }
    }

    private static void checkWhen(Object when, Set<String> knownKeys, String path, Report r, String where) {
        if (when instanceof String && !((String) when).isEmpty() && !knownKeys.contains(when)) {
            r.add(Finding.error("form-conditional-ref", path, where + " references unknown key '" + when + "'"));
        }
    }

    private static void checkScripts(Map<String, Object> root, String path, Report r) {
        checkScript(Json.asString(root.get("inlinescripts")), "inlinescripts", path, r);
        walk(root, obj -> {
            String key = Json.asString(obj.get("key"));
            checkScript(Json.asString(obj.get("customConditional")), "customConditional (key '" + key + "')", path, r);
            checkScript(Json.asString(obj.get("calculateValue")), "calculateValue (key '" + key + "')", path, r);
            checkScript(Json.asString(obj.get("customDefaultValue")), "customDefaultValue (key '" + key + "')", path, r);
            Map<String, Object> validate = Json.asMap(obj.get("validate"));
            checkScript(Json.asString(validate.get("custom")), "validate.custom (key '" + key + "')", path, r);
            Map<String, Object> data = Json.asMap(obj.get("data"));
            checkScript(Json.asString(data.get("custom")), "data.custom (key '" + key + "')", path, r);
            if ("button".equals(Json.asString(obj.get("type")))) {
                checkScript(Json.asString(obj.get("custom")), "button custom (key '" + key + "')", path, r);
            }
        });
    }

    private static void checkScript(String src, String where, String path, Report r) {
        if (src == null || src.isBlank()) {
            return;
        }
        if (TEMPLATE_ONLY.matcher(src.strip()).matches()) {
            return;   // a {{ }} template expression, not a script
        }
        String err = EcmaScriptCheck.compileError(src, where);
        if (err != null) {
            r.add(Finding.error("form-script-syntax", path, where + ": " + err));
        }
    }

    private static void checkLocalization(Map<String, Object> root, String path, Report r) {
        Object locObj = root.get("localization");
        if (!(locObj instanceof Map)) {
            return;
        }
        Map<String, Object> localization = Json.asMap(locObj);
        if (localization.size() < 2) {
            return;
        }
        Map<String, Set<String>> keysByLang = new LinkedHashMap<>();
        Set<String> union = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : localization.entrySet()) {
            Set<String> keys = Json.asMap(e.getValue()).keySet();
            keysByLang.put(e.getKey(), keys);
            union.addAll(keys);
        }
        for (Map.Entry<String, Set<String>> e : keysByLang.entrySet()) {
            Set<String> missing = new LinkedHashSet<>(union);
            missing.removeAll(e.getValue());
            if (!missing.isEmpty()) {
                r.add(Finding.info("form-localization-missing", path,
                    "language '" + e.getKey() + "' is missing " + missing.size() + " of " + union.size() + " declared entries",
                    String.join(", ", missing)));
            }
        }
    }

    /** Every key that appears on more than one JSON object with a string {@code key} and {@code type} (Designer's ParseJSON). */
    private static Map<String, Integer> globalKeyCounts(Object node) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        countKeys(node, counts);
        return counts;
    }

    private static void countKeys(Object node, Map<String, Integer> counts) {
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            Object k = m.get("key");
            Object t = m.get("type");
            if (k instanceof String && t instanceof String && !((String) k).isEmpty() && !((String) t).isEmpty()) {
                counts.merge((String) k, 1, Integer::sum);
            }
            for (Object v : m.values()) {
                countKeys(v, counts);
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                countKeys(v, counts);
            }
        }
    }

    private interface ObjVisitor {
        void visit(Map<String, Object> obj);
    }

    /** Visits every JSON object anywhere in the document, document order. */
    private static void walk(Object node, ObjVisitor v) {
        if (node instanceof Map) {
            Map<String, Object> m = Json.asMap(node);
            v.visit(m);
            for (Object val : m.values()) {
                walk(val, v);
            }
        } else if (node instanceof List) {
            for (Object val : (List<?>) node) {
                walk(val, v);
            }
        }
    }

    // ---- PRDs ---------------------------------------------------------------------------

    private static void checkPrd(Driver d, Prd prd, Report r) {
        String path = prdPath(d, prd);
        for (Prd.FormBinding b : prd.bindings()) {
            Form form = d.provisioning.formByName(b.formId);
            if (form == null) {
                r.add(Finding.error("prd-binding-stale", path,
                    (b.activityId == null ? "request" : "activity '" + b.activityId + "'")
                        + " form-binding names '" + b.formId + "', which does not exist"));
                continue;
            }
            if (b.activityId == null) {
                List<String> current = fieldNames(b);
                List<String> synced = simulateSyncFieldNames(prd, form);
                if (!current.equals(synced)) {
                    r.add(Finding.warning("prd-binding-fields-drift", path,
                        "request form-binding field list differs from what form.sync would produce (current " + current + ", synced " + synced + ")"));
                }
            }
            checkMappingsBound(prd, b, form, path, r);
        }
    }

    private static List<String> fieldNames(Prd.FormBinding b) {
        List<String> out = new ArrayList<>();
        for (Prd.Field f : b.fields) {
            out.add(f.name);
        }
        return out;
    }

    /** What {@code form.sync} would leave the request binding's field list as, computed on a throwaway clone. */
    private static List<String> simulateSyncFieldNames(Prd prd, Form form) {
        if (prd.request == null) {
            return List.of();
        }
        Prd shadow = new Prd(prd.name);
        shadow.request = cloneElement(prd.request);
        BindingSync.sync(shadow, form);
        for (Prd.FormBinding b : shadow.bindings()) {
            if (b.activityId == null && form.name.equals(b.formId)) {
                return fieldNames(b);
            }
        }
        return List.of();
    }

    private static void checkMappingsBound(Prd prd, Prd.FormBinding b, Form form, String path, Report r) {
        Set<String> bindable = new LinkedHashSet<>();
        for (BindingSync.Item it : BindingSync.items(form.json)) {
            if (it.bindable()) {
                bindable.add(it.key);
            }
        }
        Element holder;
        if (b.activityId == null) {
            if (prd.request == null) {
                return;
            }
            Element wrapper = firstChild(prd.request, "request-data-items");
            holder = wrapper != null ? wrapper : prd.request;
        } else {
            holder = null;
            if (prd.process != null) {
                for (Element di : Xds.childrenByName(prd.process, "data-items")) {
                    if (b.activityId.equals(di.getAttribute("activity-id"))) {
                        holder = di;
                        break;
                    }
                }
            }
            if (holder == null) {
                return;
            }
        }
        for (Element di : Xds.childrenByName(holder, "data-item")) {
            String name = di.getAttribute("name");
            if (!bindable.contains(name)) {
                r.add(Finding.error("prd-mapping-unbound", path,
                    (b.activityId == null ? "request" : "activity '" + b.activityId + "'")
                        + " data item '" + name + "' is not a bound field of form '" + form.name + "'"));
            }
        }
    }

    private static Element firstChild(Element parent, String name) {
        List<Element> c = Xds.childrenByName(parent, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static Element cloneElement(Element e) {
        return CanonicalXml.parse(CanonicalXml.serialize(e)).getDocumentElement();
    }

    // ---- paths (duplicated from edit.FormOps to avoid a validate -> edit dependency) ------

    private static String formPath(Driver d, Form f) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/provisioning/forms/" + f.kind.dir + "/" + AsCodeWriter.fileSafe(f.name);
    }

    private static String prdPath(Driver d, Prd p) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/provisioning/prds/" + AsCodeWriter.fileSafe(p.name);
    }
}
