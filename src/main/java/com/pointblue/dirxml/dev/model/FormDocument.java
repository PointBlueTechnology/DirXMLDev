package com.pointblue.dirxml.dev.model;

import com.pointblue.dirxml.dev.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@link Form} document (Form.io + NetIQ extensions — see
 * {@code docs/spikes/json-forms-format.md} §2): title/display, a recursive walk
 * of the component tree, declared localization languages, and script presence.
 * Read-only; editing forms is Track P2b, not this step.
 */
public final class FormDocument {

    private final Map<String, Object> root;

    private FormDocument(Map<String, Object> root) {
        this.root = root;
    }

    public static FormDocument parse(String json) {
        return new FormDocument(Json.asMap(Json.parse(json)));
    }

    public String title() {
        return Json.asString(root.get("title"));
    }

    /** {@code "form"} or {@code "workflowWizard"} (the Create Workflow Form). */
    public String display() {
        return Json.asString(root.get("display"));
    }

    public boolean hasInlineScripts() {
        String s = Json.asString(root.get("inlinescripts"));
        return s != null && !s.isBlank();
    }

    public List<String> externalScripts() {
        List<String> out = new ArrayList<>();
        for (Object o : Json.asList(root.get("externalScripts"))) {
            out.add(Json.asString(o));
        }
        return out;
    }

    /** Declared languages (keys of the top-level {@code localization} map). */
    public List<String> languages() {
        return new ArrayList<>(Json.asMap(root.get("localization")).keySet());
    }

    /**
     * Every component, recursive, document order. Walks a component's own
     * nested {@code components} array directly (panel/tabs/container/datagrid
     * all nest this way) and a {@code columns} component's per-column
     * {@code components} arrays; a path like {@code components[2].columns[0].components[1]}
     * identifies each one uniquely within the document.
     */
    public List<Component> components() {
        List<Component> out = new ArrayList<>();
        walk(Json.asList(root.get("components")), "components", out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(List<Object> comps, String path, List<Component> out) {
        for (int i = 0; i < comps.size(); i++) {
            Map<String, Object> c = Json.asMap(comps.get(i));
            String p = path + "[" + i + "]";
            boolean required = false;
            Object validate = c.get("validate");
            if (validate instanceof Map) {
                required = Json.asBool(((Map<String, Object>) validate).get("required"));
            }
            Object conditional = c.get("conditional");
            String conditionalSummary = null;
            if (conditional instanceof Map) {
                Map<String, Object> cond = (Map<String, Object>) conditional;
                String when = Json.asString(cond.get("when"));
                String show = Json.asString(cond.get("show"));
                if (when != null && !when.isEmpty()) {
                    conditionalSummary = "when " + when + " eq " + Json.asString(cond.get("eq")) + " show=" + show;
                }
            }
            out.add(new Component(p, Json.asString(c.get("key")), Json.asString(c.get("type")),
                Json.asString(c.get("label")), Json.asBool(c.get("input")), required,
                Json.asBool(c.get("hidden")), conditionalSummary));

            Object nested = c.get("components");
            if (nested instanceof List) {
                walk((List<Object>) nested, p + ".components", out);
            }
            Object columns = c.get("columns");
            if (columns instanceof List) {
                List<Object> cols = (List<Object>) columns;
                for (int j = 0; j < cols.size(); j++) {
                    Object colComps = Json.asMap(cols.get(j)).get("components");
                    if (colComps instanceof List) {
                        walk((List<Object>) colComps, p + ".columns[" + j + "].components", out);
                    }
                }
            }
        }
    }

    /** One component in the tree; see {@link #components()}. */
    public static final class Component {
        public final String path;
        public final String key;
        public final String type;
        public final String label;
        public final boolean input;
        public final boolean required;
        public final boolean hidden;
        /** A short human summary of a non-trivial {@code conditional}, or null. */
        public final String conditional;

        Component(String path, String key, String type, String label, boolean input, boolean required,
                  boolean hidden, String conditional) {
            this.path = path;
            this.key = key;
            this.type = type;
            this.label = label;
            this.input = input;
            this.required = required;
            this.hidden = hidden;
            this.conditional = conditional;
        }

        @Override
        public String toString() {
            return path + " " + type + " key=" + key;
        }
    }
}
