package com.pointblue.dirxml.dev.forms;

import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Keeps a PRD's form bindings in step with a JSON form, the way Designer's PRD editor does when
 * a form is (re)selected or saved (decompiled {@code JSONFormControlPage}, {@code ParseJSON},
 * {@code Activity.synchronizeDataItemsWithJSONForm}), calibrated against the stock PRDs of IDM
 * 4.8.7 and 4.10.1 so that syncing an untouched stock form is a no-op:
 *
 * <ul>
 *   <li><b>Parsed items</b> = every JSON object anywhere in the document that has a string
 *       {@code key} and a string {@code type} (first occurrence of each wins), with its
 *       {@code multiple} flag.</li>
 *   <li><b>Request binding</b> ({@code <provision-request><form-binding><content>}): fields =
 *       the parsed items whose type is in Designer's {@code FormDataConfig.json} map
 *       (case-insensitive), except the key {@code apwaComment}; {@code data-type} from that
 *       map, {@code control-type} = the type. Fields already bound whose type is not in the map
 *       (buttons in the stock PRDs) are kept while the key still exists.</li>
 *   <li><b>Activity bindings</b> ({@code <process><form-binding activity-id form-id>}) are a
 *       reference only — Designer stores no field list there — so they are left alone.</li>
 *   <li><b>Data items</b> ({@code request-data-items} for the request form, {@code <data-items
 *       activity-id>} for an activity) are Designer's persisted <i>mappings</i>: an item exists
 *       only once someone mapped it, so the sync never adds one. It keeps every item whose name
 *       is still a bindable field of the form, refreshes its {@code target-type} from
 *       {@code multiple} and its {@code data-type} from the map, and removes items whose field
 *       vanished. Mapping a new field is an explicit operation ({@code prd.map}, Track P step
 *       P2b).</li>
 * </ul>
 * Everything is reported as {@link Change}s so the transaction can note them.
 */
public final class BindingSync {

    /** Designer's FormDataConfig.json (component type → PRD data type), upper-cased keys. */
    public static final Map<String, String> DATA_TYPES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        String[][] rows = {
            {"textfield", "string"}, {"textarea", "string"}, {"text", "string"}, {"number", "decimal"},
            {"checkbox", "boolean"}, {"time", "time"}, {"selectboxes", "jsonobject"}, {"select", "string"},
            {"radio", "string"}, {"tree", "string"}, {"email", "string"}, {"url", "string"},
            {"phoneNumber", "string"}, {"datetime", "date"}, {"day", "string"}, {"htmlelement", "string"},
            {"dynamic_entity", "dn"}, {"dnquery", "dn"}, {"title", "string"}, {"dn_display", "dn"},
            {"permission_requestDN", "dn"}, {"labelelement", "string"}, {"currency", "decimal"},
            {"tags", "string"}, {"hidden", "string"}, {"file", "string"}, {"signature", "string"},
            {"survey", "jsonobject"}, {"modaledit", "string"}, {"datagrid", "jsonobject"},
            {"editgrid", "jsonobject"}, {"container", "jsonobject"}, {"datamap", "jsonobject"},
        };
        for (String[] r : rows) {
            m.put(r[0].toUpperCase(Locale.ROOT), r[1]);
        }
        DATA_TYPES = java.util.Collections.unmodifiableMap(m);
    }

    /** What Designer's ParseJSON yields for one component. */
    public static final class Item {
        public final String key;
        public final String type;
        public final boolean multiple;

        Item(String key, String type, boolean multiple) {
            this.key = key;
            this.type = type;
            this.multiple = multiple;
        }

        /** The PRD data type for this component, or null when Designer would not bind it. */
        public String dataType() {
            return DATA_TYPES.get(type.toUpperCase(Locale.ROOT));
        }

        /** Bindable = has a data type and is not the reserved comment field. */
        public boolean bindable() {
            return dataType() != null && !"apwaComment".equals(key);
        }

        @Override
        public String toString() {
            return key + ":" + type + (multiple ? "[]" : "");
        }
    }

    /** One thing the sync did. */
    public static final class Change {
        public final String prd;
        public final String activity;   // null for the request binding
        public final String what;

        Change(String prd, String activity, String what) {
            this.prd = prd;
            this.activity = activity;
            this.what = what;
        }

        @Override
        public String toString() {
            return prd + (activity == null ? " request form" : " activity '" + activity + "'") + ": " + what;
        }
    }

    private BindingSync() {
    }

    /** Designer's ParseJSON: every object with a string key and type, document order, first wins per key. */
    public static List<Item> items(String json) {
        Map<String, Item> out = new LinkedHashMap<>();
        walk(Json.parse(json), out);
        return new ArrayList<>(out.values());
    }

    private static void walk(Object node, Map<String, Item> out) {
        if (node instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) node;
            Object k = m.get("key");
            Object t = m.get("type");
            if (k instanceof String && t instanceof String && !((String) k).isEmpty() && !((String) t).isEmpty()) {
                Object mult = m.get("multiple");
                out.putIfAbsent((String) k, new Item((String) k, (String) t, Boolean.TRUE.equals(mult)));
            }
            for (Object v : m.values()) {
                walk(v, out);
            }
        } else if (node instanceof List) {
            for (Object v : (List<?>) node) {
                walk(v, out);
            }
        }
    }

    /** Sync every binding of {@code prd} that references {@code form}; returns what changed. */
    public static List<Change> sync(Prd prd, Form form) {
        List<Change> changes = new ArrayList<>();
        List<Item> items = items(form.json);
        Map<String, Item> byKey = new LinkedHashMap<>();
        for (Item it : items) {
            byKey.put(it.key, it);
        }
        if (prd.request != null) {
            for (Element fb : children(prd.request, "form-binding")) {
                if (!form.name.equals(fb.getAttribute("form-id"))) {
                    continue;
                }
                Element content = firstChild(fb, "content");
                if (content == null) {
                    content = appendElement(fb, "content");
                }
                List<Element> fields = syncFields(content, items, changes, prd.name);
                Map<String, String> bound = new LinkedHashMap<>();
                for (Element f : fields) {
                    if (!"button".equals(controlType(f))) {
                        bound.put(f.getAttribute("name"), f.getAttribute("data-type"));
                    }
                }
                Element holder = firstChild(prd.request, "request-data-items");
                syncDataItems(holder != null ? holder : prd.request, bound, byKey, changes, prd.name, null);
            }
        }
        if (prd.process != null) {
            for (Element fb : children(prd.process, "form-binding")) {
                if (!form.name.equals(fb.getAttribute("form-id"))) {
                    continue;
                }
                String activity = fb.getAttribute("activity-id");
                // the binding is a reference; its persisted mappings are the activity's data items
                Map<String, String> bound = new LinkedHashMap<>();
                for (Item it : items) {
                    if (it.bindable() && !"button".equals(it.type)) {
                        bound.put(it.key, it.dataType());
                    }
                }
                for (Element di : children(prd.process, "data-items")) {
                    if (activity.equals(di.getAttribute("activity-id"))) {
                        syncDataItems(di, bound, byKey, changes, prd.name, activity);
                    }
                }
            }
        }
        return changes;
    }

    /** True when the PRD binds the form anywhere (request or an activity). */
    public static boolean binds(Prd prd, String formName) {
        for (Prd.FormBinding b : prd.bindings()) {
            if (formName.equals(b.formId)) {
                return true;
            }
        }
        return false;
    }

    // ---- request fields ----

    /** Rebuild {@code <content>}'s field list as Designer's form page does; returns the fields in order. */
    private static List<Element> syncFields(Element content, List<Item> items, List<Change> changes, String prd) {
        Map<String, Element> existing = new LinkedHashMap<>();
        for (Element f : children(content, "field")) {
            existing.put(f.getAttribute("name"), f);
        }
        Document doc = content.getOwnerDocument();
        List<Element> result = new ArrayList<>();
        for (Item it : items) {
            if ("apwaComment".equals(it.key)) {
                continue;
            }
            String dataType = it.dataType();
            Element old = existing.remove(it.key);
            if (dataType == null) {
                if (old != null) {
                    result.add(old);   // Designer once bound it (a button); keep it
                }
                continue;
            }
            Element f;
            if (old != null) {
                f = old;
                String oldCt = controlType(old);
                if (!it.type.equals(oldCt) || !dataType.equals(old.getAttribute("data-type"))) {
                    setControl(f, it.type);
                    f.setAttribute("data-type", dataType);
                    changes.add(new Change(prd, null, "field '" + it.key + "' is now " + it.type + "/" + dataType
                        + (oldCt == null ? "" : " (was " + oldCt + "/" + old.getAttribute("data-type") + ")")));
                }
            } else {
                f = doc.createElementNS(null, "field");
                f.setAttribute("data-type", dataType);
                f.setAttribute("name", it.key);
                setControl(f, it.type);
                changes.add(new Change(prd, null, "bound field '" + it.key + "' (" + it.type + "/" + dataType + ")"));
            }
            result.add(f);
        }
        for (Map.Entry<String, Element> gone : existing.entrySet()) {
            changes.add(new Change(prd, null, "unbound field '" + gone.getKey() + "' (no longer in the form)"));
        }
        boolean reorder = !sameOrder(children(content, "field"), result);
        if (reorder) {
            for (Element f : children(content, "field")) {
                content.removeChild(f);
            }
            for (Element f : result) {
                content.appendChild(f);
            }
        }
        return result;
    }

    private static boolean sameOrder(List<Element> a, List<Element> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static String controlType(Element field) {
        Element c = firstChild(field, "control");
        return c == null ? null : c.getAttribute("control-type");
    }

    private static void setControl(Element field, String type) {
        Element c = firstChild(field, "control");
        if (c == null) {
            c = appendElement(field, "control");
        }
        c.setAttribute("control-type", type);
    }

    // ---- data items (persisted mappings) ----

    /**
     * Keep the items whose field is still bound (refreshing data-type and target-type), drop the
     * rest, never add.
     *
     * @param bound bindable field name → data type
     */
    private static void syncDataItems(Element holder, Map<String, String> bound, Map<String, Item> byKey,
                                      List<Change> changes, String prd, String activity) {
        for (Element di : children(holder, "data-item")) {
            String name = di.getAttribute("name");
            String dataType = bound.get(name);
            if (dataType == null) {
                holder.removeChild(di);
                changes.add(new Change(prd, activity, "data item '" + name + "' removed (its field is gone)"));
                continue;
            }
            Item it = byKey.get(name);
            String targetType = it != null && it.multiple ? "multi-value-list" : "single-value";
            if (di.hasAttribute("target-type") && !targetType.equals(di.getAttribute("target-type"))) {
                di.setAttribute("target-type", targetType);
                changes.add(new Change(prd, activity, "data item '" + name + "' target-type → " + targetType));
            }
            if (!dataType.equals(di.getAttribute("data-type"))) {
                changes.add(new Change(prd, activity, "data item '" + name + "' data-type " + di.getAttribute("data-type") + " → " + dataType));
                di.setAttribute("data-type", dataType);
            }
        }
    }

    /** The start activity's id (target expressions use it); "Start" when not declared. */
    public static String startActivityId(Prd prd) {
        Element process = prd.process;
        if (process == null && prd.definition != null) {
            process = firstChild(prd.definition, "process");
        }
        if (process != null) {
            Element start = firstChild(process, "start-activity");
            if (start != null && start.hasAttribute("activity-id") && !start.getAttribute("activity-id").isEmpty()) {
                return start.getAttribute("activity-id");
            }
        }
        return "Start";
    }

    // ---- DOM helpers ----

    static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element firstChild(Element e, String name) {
        List<Element> c = children(e, name);
        return c.isEmpty() ? null : c.get(0);
    }

    private static Element appendElement(Element parent, String name) {
        Element e = parent.getOwnerDocument().createElementNS(null, name);
        parent.appendChild(e);
        return e;
    }
}
