package com.pointblue.dirxml.dev.forms;

import com.pointblue.dirxml.dev.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure functions over a parsed form document (a {@code Map<String,Object>} from
 * {@link Json#parse}) for the typed field operations (Track P step P2b): find a
 * component anywhere in the tree, insert/remove/move it, set a dotted property
 * path, and load the captured builder-default templates
 * ({@code resources/forms/components/&lt;type&gt;.json}, see {@code docs/forms.md} §2).
 * No I/O beyond the template resources, no model dependency — {@code edit.FormOps}
 * wraps these as transactions and does validation / binding-sync / package
 * bookkeeping.
 *
 * <p>Component nesting mirrors {@link com.pointblue.dirxml.dev.model.FormDocument}:
 * a component's own {@code components} array (panel/tabs/container/datagrid all
 * nest this way) and a {@code columns} component's per-column {@code components}
 * arrays. This is the same nesting the 22 captured templates and every real form
 * seen so far actually use.
 */
public final class FormEditor {

    private FormEditor() {
    }

    /** A component in the tree, together with the list it lives in and its index there. */
    public static final class Located {
        public final Map<String, Object> component;
        public final List<Object> holder;
        public final int index;

        Located(Map<String, Object> component, List<Object> holder, int index) {
            this.component = component;
            this.holder = holder;
            this.index = index;
        }
    }

    /** Every component anywhere in the document, document order. */
    public static List<Located> all(Map<String, Object> root) {
        List<Located> out = new ArrayList<>();
        walk(listOf(root, "components"), out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void walk(List<Object> comps, List<Located> out) {
        for (int i = 0; i < comps.size(); i++) {
            Map<String, Object> c = Json.asMap(comps.get(i));
            out.add(new Located(c, comps, i));
            Object nested = c.get("components");
            if (nested instanceof List) {
                walk((List<Object>) nested, out);
            }
            Object columns = c.get("columns");
            if (columns instanceof List) {
                for (Object colObj : (List<Object>) columns) {
                    Object colComps = Json.asMap(colObj).get("components");
                    if (colComps instanceof List) {
                        walk((List<Object>) colComps, out);
                    }
                }
            }
        }
    }

    /** The first component with this key anywhere in the document (this nesting only), or null. */
    public static Located find(Map<String, Object> root, String key) {
        for (Located l : all(root)) {
            if (key.equals(Json.asString(l.component.get("key")))) {
                return l;
            }
        }
        return null;
    }

    /**
     * Every key that appears on more than one JSON object with a string {@code key}
     * and {@code type} <em>anywhere</em> in the document — the same walk
     * {@link BindingSync#items} uses (Designer's {@code ParseJSON}), except this one
     * counts instead of keeping only the first. Designer's keys are global, so this
     * is the authority for a duplicate-key refusal, not {@link #all}.
     */
    public static Map<String, Integer> globalKeyCounts(Object node) {
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

    // ---- placement ----------------------------------------------------------------

    /** Where to insert or move a component: relative to a sibling key, first, last (default), in a named container. */
    public static final class Placement {
        public final String after;
        public final String before;
        public final boolean first;
        public final String in;    // container/panel/column key, or null for the top-level components

        private Placement(String after, String before, boolean first, String in) {
            this.after = after;
            this.before = before;
            this.first = first;
            this.in = in;
        }

        public static Placement after(String key, String in) {
            return new Placement(key, null, false, in);
        }

        public static Placement before(String key, String in) {
            return new Placement(null, key, false, in);
        }

        public static Placement first(String in) {
            return new Placement(null, null, true, in);
        }

        public static Placement last(String in) {
            return new Placement(null, null, false, in);
        }
    }

    /**
     * The list a placement targets: the top-level {@code components}, or a
     * container/panel's own {@code components} (created if absent), or — when the
     * named component is a {@code columns} component — its first column's
     * {@code components} (creating the column too, if the {@code columns} array is
     * empty).
     */
    public static List<Object> target(Map<String, Object> root, String containerKey) {
        if (containerKey == null) {
            return listOf(root, "components");
        }
        Located c = find(root, containerKey);
        if (c == null) {
            throw new IllegalArgumentException("no component with key '" + containerKey + "'");
        }
        if ("columns".equals(Json.asString(c.component.get("type")))) {
            List<Object> columns = listOf(c.component, "columns");
            if (columns.isEmpty()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("components", new ArrayList<>());
                columns.add(col);
            }
            return listOf(Json.asMap(columns.get(0)), "components");
        }
        return listOf(c.component, "components");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) {
            v = new ArrayList<>();
            m.put(key, v);
        }
        return (List<Object>) v;
    }

    /** Insert {@code component} into {@code list} at the position {@code placement} describes (null = last). */
    public static void insertAt(List<Object> list, Map<String, Object> component, Placement placement) {
        list.add(indexFor(list, placement), component);
    }

    private static int indexFor(List<Object> list, Placement placement) {
        if (placement == null) {
            return list.size();
        }
        if (placement.first) {
            return 0;
        }
        if (placement.after != null) {
            int i = indexOfKey(list, placement.after);
            if (i < 0) {
                throw new IllegalArgumentException("no component with key '" + placement.after + "' to place after");
            }
            return i + 1;
        }
        if (placement.before != null) {
            int i = indexOfKey(list, placement.before);
            if (i < 0) {
                throw new IllegalArgumentException("no component with key '" + placement.before + "' to place before");
            }
            return i;
        }
        return list.size();
    }

    private static int indexOfKey(List<Object> list, String key) {
        for (int i = 0; i < list.size(); i++) {
            if (key.equals(Json.asString(Json.asMap(list.get(i)).get("key")))) {
                return i;
            }
        }
        return -1;
    }

    /** Remove the component with this key (this nesting only); true if it was found and removed. */
    public static boolean remove(Map<String, Object> root, String key) {
        Located l = find(root, key);
        if (l == null) {
            return false;
        }
        l.holder.remove(l.index);
        return true;
    }

    /** Move the component with this key to a new position, possibly reparenting it. */
    public static void move(Map<String, Object> root, String key, Placement placement) {
        Located l = find(root, key);
        if (l == null) {
            throw new IllegalArgumentException("no component with key '" + key + "'");
        }
        if (placement != null && key.equals(placement.in)) {
            throw new IllegalArgumentException("cannot move '" + key + "' into itself");
        }
        List<Object> dest = target(root, placement == null ? null : placement.in);
        if (dest == l.holder) {
            int destIdx = indexFor(dest, placement);
            l.holder.remove(l.index);
            if (destIdx > l.index) {
                destIdx--;
            }
            dest.add(destIdx, l.component);
            return;
        }
        l.holder.remove(l.index);
        insertAt(dest, l.component, placement);
    }

    // ---- properties -----------------------------------------------------------------

    /** Set a dotted property path ({@code validate.maxLength}) on a component, creating intermediate objects as needed. */
    @SuppressWarnings("unchecked")
    public static void setProperty(Map<String, Object> component, String dottedPath, Object value) {
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> cur = component;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], value);
    }

    // ---- component templates ----------------------------------------------------------

    /** The captured builder default for a component type ({@code resources/forms/components/<type>.json}), or null if none was captured. */
    public static Map<String, Object> template(String type) {
        String res = "/forms/components/" + type + ".json";
        try (InputStream in = FormEditor.class.getResourceAsStream(res)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return deepCopyMap(Json.asMap(Json.parse(text)));
        } catch (IOException e) {
            throw new RuntimeException("cannot read " + res, e);
        }
    }

    /** A minimal component shape (the IDM 4.10.1 stock forms are documents built entirely of these — see docs/spikes/json-forms-format.md §6a). */
    public static Map<String, Object> minimal(String type, String key, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label == null ? "" : label);
        m.put("key", key);
        m.put("type", type);
        m.put("input", true);
        return m;
    }

    // ---- deep copy / deep merge -------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            return deepCopyMap((Map<String, Object>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                out.add(deepCopy(o));
            }
            return out;
        }
        return v;
    }

    public static Map<String, Object> deepCopyMap(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(e.getKey(), deepCopy(e.getValue()));
        }
        return out;
    }

    /** Merge {@code overlay} onto {@code base} in place: object values merge key-by-key (recursively); anything else (including arrays) replaces. */
    @SuppressWarnings("unchecked")
    public static void deepMergeInto(Map<String, Object> base, Map<String, Object> overlay) {
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            Object ov = e.getValue();
            Object bv = base.get(e.getKey());
            if (ov instanceof Map && bv instanceof Map) {
                deepMergeInto((Map<String, Object>) bv, (Map<String, Object>) ov);
            } else {
                base.put(e.getKey(), deepCopy(ov));
            }
        }
    }
}
