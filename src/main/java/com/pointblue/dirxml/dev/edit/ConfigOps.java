package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Driver configuration operations: the filter, the schema map, the driver's own
 * settings, and mapping-table rows.
 *
 * <ul>
 *   <li>{@link FilterSetClass} / {@link FilterSetAttr} / {@link FilterRemoveClass}
 *       / {@link FilterRemoveAttr} — the driver filter, with the enumerations the
 *       validator checks ({@code sync|ignore|notify|reset}, merge authority
 *       {@code default|edir|app|none});</li>
 *   <li>{@link SchemaMapSet} / {@link SchemaMapRemove} — the driver's
 *       {@code <attr-name-map>} (class mapping, per-class attribute mapping, or a
 *       top-level attribute mapping that applies to every class);</li>
 *   <li>{@link DriverSet_} — {@code driver.set}: {@code shim-class},
 *       {@code shim-auth-server}, {@code shim-auth-id}, a shim parameter
 *       ({@code param:<name>} in shim-config-info) or an engine control value
 *       ({@code engine:<name>});</li>
 *   <li>{@link TableSetRow} / {@link TableDeleteRow} / {@link TableAddColumn} —
 *       mapping-table rows keyed by the first column (or {@code --key-column}).</li>
 * </ul>
 */
public final class ConfigOps {

    private ConfigOps() {
    }

    private static final List<String> SYNC = List.of("sync", "ignore", "notify", "reset");
    private static final List<String> MERGE = List.of("default", "edir", "app", "none");

    // ---- filter ---------------------------------------------------------------

    static Element filterOrRefuse(Driver d) throws Operation.Refusal {
        Element f = d.config.get(Driver.DRIVER_FILTER);
        if (f == null) {
            throw new Operation.Refusal("driver '" + d.name + "' has no filter");
        }
        return f;
    }

    static Element filterClass(Element filter, String className) {
        for (Element c : Xds.childrenByName(filter, "filter-class")) {
            if (className.equals(c.getAttribute("class-name"))) {
                return c;
            }
        }
        return null;
    }

    static Element filterAttr(Element cls, String attrName) {
        for (Element a : Xds.childrenByName(cls, "filter-attr")) {
            if (attrName.equals(a.getAttribute("attr-name"))) {
                return a;
            }
        }
        return null;
    }

    private static void setEnum(Element el, String attr, String value, List<String> allowed) throws Operation.Refusal {
        if (value == null) {
            return;
        }
        if (!allowed.contains(value)) {
            throw new Operation.Refusal(attr + " must be one of " + String.join("|", allowed) + ", not '" + value + "'");
        }
        el.setAttribute(attr, value);
    }

    private static void setBool(Element el, String attr, String value) throws Operation.Refusal {
        if (value == null) {
            return;
        }
        if (!value.equals("true") && !value.equals("false")) {
            throw new Operation.Refusal(attr + " must be true|false, not '" + value + "'");
        }
        el.setAttribute(attr, value);
    }

    /** {@code filter.set-class}: add or update a class (creates the filter if the driver has none). */
    public static final class FilterSetClass implements Operation {
        private final String driver;
        private final String className;
        private final Map<String, String> attrs;   // publisher, subscriber, publisher-create-homedir, publisher-track-template-member

        public FilterSetClass(String driver, String className, Map<String, String> attrs) {
            this.driver = driver;
            this.className = className;
            this.attrs = attrs;
        }

        @Override
        public String name() {
            return "filter.set-class";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            if (className == null || className.isBlank()) {
                throw new Refusal("a class name is required");
            }
            Element filter = d.config.get(Driver.DRIVER_FILTER);
            if (filter == null) {
                filter = CanonicalXml.parse("<filter/>").getDocumentElement();
                d.config.put(Driver.DRIVER_FILTER, filter);
            }
            Element cls = filterClass(filter, className);
            if (cls == null) {
                cls = filter.getOwnerDocument().createElementNS(null, "filter-class");
                cls.setAttribute("class-name", className);
                cls.setAttribute("publisher", "sync");
                cls.setAttribute("subscriber", "sync");
                filter.appendChild(cls);
            }
            setEnum(cls, "publisher", attrs.get("publisher"), SYNC);
            setEnum(cls, "subscriber", attrs.get("subscriber"), SYNC);
            setBool(cls, "publisher-create-homedir", attrs.get("publisher-create-homedir"));
            setBool(cls, "publisher-track-template-member", attrs.get("publisher-track-template-member"));
        }
    }

    /** {@code filter.set-attr}: add or update an attribute of a class. */
    public static final class FilterSetAttr implements Operation {
        private final String driver;
        private final String className;
        private final String attrName;
        private final Map<String, String> attrs;   // publisher, subscriber, merge-authority, *-optimize-modify

        public FilterSetAttr(String driver, String className, String attrName, Map<String, String> attrs) {
            this.driver = driver;
            this.className = className;
            this.attrName = attrName;
            this.attrs = attrs;
        }

        @Override
        public String name() {
            return "filter.set-attr";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            Element filter = filterOrRefuse(d);
            Element cls = filterClass(filter, className);
            if (cls == null) {
                throw new Refusal("class '" + className + "' is not in the filter (filter.set-class first)");
            }
            if (attrName == null || attrName.isBlank()) {
                throw new Refusal("an attribute name is required");
            }
            Element at = filterAttr(cls, attrName);
            if (at == null) {
                at = filter.getOwnerDocument().createElementNS(null, "filter-attr");
                at.setAttribute("attr-name", attrName);
                at.setAttribute("merge-authority", "default");
                at.setAttribute("publisher", "sync");
                at.setAttribute("publisher-optimize-modify", "true");
                at.setAttribute("subscriber", "sync");
                cls.appendChild(at);
            }
            setEnum(at, "publisher", attrs.get("publisher"), SYNC);
            setEnum(at, "subscriber", attrs.get("subscriber"), SYNC);
            setEnum(at, "merge-authority", attrs.get("merge-authority"), MERGE);
            setBool(at, "publisher-optimize-modify", attrs.get("publisher-optimize-modify"));
            setBool(at, "subscriber-optimize-modify", attrs.get("subscriber-optimize-modify"));
        }
    }

    public static final class FilterRemoveClass implements Operation {
        private final String driver;
        private final String className;

        public FilterRemoveClass(String driver, String className) {
            this.driver = driver;
            this.className = className;
        }

        @Override
        public String name() {
            return "filter.remove-class";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            Element filter = filterOrRefuse(d);
            Element cls = filterClass(filter, className);
            if (cls == null) {
                throw new Refusal("class '" + className + "' is not in the filter");
            }
            filter.removeChild(cls);
        }
    }

    public static final class FilterRemoveAttr implements Operation {
        private final String driver;
        private final String className;
        private final String attrName;

        public FilterRemoveAttr(String driver, String className, String attrName) {
            this.driver = driver;
            this.className = className;
            this.attrName = attrName;
        }

        @Override
        public String name() {
            return "filter.remove-attr";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            Element cls = filterClass(filterOrRefuse(d), className);
            Element at = cls == null ? null : filterAttr(cls, attrName);
            if (at == null) {
                throw new Refusal("attribute '" + attrName + "' of class '" + className + "' is not in the filter");
            }
            cls.removeChild(at);
        }
    }

    // ---- schema map -----------------------------------------------------------

    /** The driver's schema-map policy (linked in set 0), or refuse. */
    static Policy schemaMap(DriverSet ds, Driver d) throws Operation.Refusal {
        Map<String, Artifact> index = ds.index();
        for (PolicyLink l : d.links(PolicySet.SCHEMA_MAPPING)) {
            Artifact a = index.get(l.ref);
            if (a instanceof Policy && ((Policy) a).policyKind() == Policy.Kind.SCHEMA_MAP) {
                return (Policy) a;
            }
        }
        for (Policy p : d.policies) {
            if (p.policyKind() == Policy.Kind.SCHEMA_MAP) {
                return p;
            }
        }
        throw new Operation.Refusal("driver '" + d.name + "' has no schema-map policy (policy.add --kind schema-map --link schema-mapping first)");
    }

    static Element entryByNds(Element parent, String element, String ndsName) {
        for (Element e : Xds.childrenByName(parent, element)) {
            List<Element> n = Xds.childrenByName(e, "nds-name");
            if (!n.isEmpty() && ndsName.equals(Xds.text(n.get(0)).trim())) {
                return e;
            }
        }
        return null;
    }

    static Element newEntry(Document doc, String element, String app, String nds) {
        Element e = doc.createElementNS(null, element);
        Element a = doc.createElementNS(null, "app-name");
        a.setTextContent(app);
        Element n = doc.createElementNS(null, "nds-name");
        n.setTextContent(nds);
        e.appendChild(a);
        e.appendChild(n);
        return e;
    }

    static void setAppName(Element entry, String app) {
        List<Element> a = Xds.childrenByName(entry, "app-name");
        if (a.isEmpty()) {
            Element n = entry.getOwnerDocument().createElementNS(null, "app-name");
            n.setTextContent(app);
            entry.insertBefore(n, entry.getFirstChild());
        } else {
            a.get(0).setTextContent(app);
        }
    }

    /**
     * {@code schema-map.set}: {@code --nds-class C --app-class c} maps a class;
     * add {@code --nds-attr A --app-attr a} to map an attribute within that class;
     * {@code --nds-attr/--app-attr} alone maps an attribute for every class.
     */
    public static final class SchemaMapSet implements Operation {
        private final String driver;
        private final String ndsClass;
        private final String appClass;
        private final String ndsAttr;
        private final String appAttr;

        public SchemaMapSet(String driver, String ndsClass, String appClass, String ndsAttr, String appAttr) {
            this.driver = driver;
            this.ndsClass = ndsClass;
            this.appClass = appClass;
            this.ndsAttr = ndsAttr;
            this.appAttr = appAttr;
        }

        @Override
        public String name() {
            return "schema-map.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            Policy smp = schemaMap(ds, d);
            Element root = smp.content;
            Document doc = root.getOwnerDocument();
            boolean attr = ndsAttr != null && !ndsAttr.isBlank();
            if (attr && (appAttr == null || appAttr.isBlank())) {
                throw new Refusal("--app-attr is required with --nds-attr");
            }
            tx.touch(smp);
            if (ndsClass != null && !ndsClass.isBlank()) {
                Element cls = entryByNds(root, "class-name", ndsClass);
                if (cls == null) {
                    if (appClass == null || appClass.isBlank()) {
                        throw new Refusal("class '" + ndsClass + "' is not mapped; give --app-class to map it");
                    }
                    cls = newEntry(doc, "class-name", appClass, ndsClass);
                    root.appendChild(cls);
                } else if (appClass != null && !appClass.isBlank()) {
                    setAppName(cls, appClass);
                }
                if (attr) {
                    Element a = entryByNds(cls, "attr-name", ndsAttr);
                    if (a == null) {
                        cls.appendChild(newEntry(doc, "attr-name", appAttr, ndsAttr));
                    } else {
                        setAppName(a, appAttr);
                    }
                }
            } else if (attr) {
                Element a = entryByNds(root, "attr-name", ndsAttr);
                if (a == null) {
                    root.appendChild(newEntry(doc, "attr-name", appAttr, ndsAttr));
                } else {
                    setAppName(a, appAttr);
                }
            } else {
                throw new Refusal("give --nds-class (with --app-class) and/or --nds-attr --app-attr");
            }
        }
    }

    public static final class SchemaMapRemove implements Operation {
        private final String driver;
        private final String ndsClass;
        private final String ndsAttr;

        public SchemaMapRemove(String driver, String ndsClass, String ndsAttr) {
            this.driver = driver;
            this.ndsClass = ndsClass;
            this.ndsAttr = ndsAttr;
        }

        @Override
        public String name() {
            return "schema-map.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            Policy smp = schemaMap(ds, d);
            Element root = smp.content;
            boolean attr = ndsAttr != null && !ndsAttr.isBlank();
            Element parent = root;
            if (ndsClass != null && !ndsClass.isBlank()) {
                Element cls = entryByNds(root, "class-name", ndsClass);
                if (cls == null) {
                    throw new Refusal("class '" + ndsClass + "' is not in the schema map");
                }
                if (!attr) {
                    tx.touch(smp);
                    root.removeChild(cls);
                    return;
                }
                parent = cls;
            } else if (!attr) {
                throw new Refusal("give --nds-class and/or --nds-attr");
            }
            Element a = entryByNds(parent, "attr-name", ndsAttr);
            if (a == null) {
                throw new Refusal("attribute '" + ndsAttr + "' is not mapped" + (parent == root ? " at the top level" : " in class '" + ndsClass + "'"));
            }
            tx.touch(smp);
            parent.removeChild(a);
        }
    }

    // ---- driver.set -----------------------------------------------------------

    /** {@code driver.set --key shim-class|shim-auth-server|shim-auth-id|param:<name>|engine:<name> --value v}. */
    public static final class DriverSet_ implements Operation {
        private final String driver;
        private final String key;
        private final String value;

        public DriverSet_(String driver, String key, String value) {
            this.driver = driver;
            this.key = key;
            this.value = value;
        }

        @Override
        public String name() {
            return "driver.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = ArtifactOps.driverOrRefuse(ds, driver);
            if (key == null || key.isBlank()) {
                throw new Refusal("a key is required");
            }
            if (value == null) {
                throw new Refusal("a value is required");
            }
            switch (key) {
                case "shim-class": d.shimClass = value; return;
                case "shim-auth-server": d.shimAuthServer = value; return;
                case "shim-auth-id": d.shimAuthId = value; return;
                default:
            }
            if (key.startsWith("param:") || key.startsWith("engine:")) {
                boolean param = key.startsWith("param:");
                String name = key.substring(key.indexOf(':') + 1);
                Element blob = d.config.get(param ? Driver.SHIM_CONFIG_INFO : Driver.ENGINE_CONTROL_VALUES);
                if (blob == null) {
                    throw new Refusal("driver '" + d.name + "' has no " + (param ? "shim-config-info" : "engine-control-values"));
                }
                Element def = GcvOps.definition(blob, name);
                if (def == null) {
                    throw new Refusal("no " + (param ? "shim parameter" : "engine control value") + " '" + name + "' in driver '" + d.name + "'; defined: " + names(blob));
                }
                List<Element> values = Xds.childrenByName(def, "value");
                if (values.isEmpty()) {
                    Element v = def.getOwnerDocument().createElementNS(null, "value");
                    v.setTextContent(value);
                    def.appendChild(v);
                } else {
                    values.get(0).setTextContent(value);
                }
                return;
            }
            throw new Refusal("key must be shim-class|shim-auth-server|shim-auth-id|param:<name>|engine:<name>, not '" + key + "'");
        }

        private static List<String> names(Element blob) {
            List<String> out = new ArrayList<>();
            for (Element def : Xds.descendantsByName(blob, "definition")) {
                out.add(def.getAttribute("name"));
            }
            return out;
        }
    }

    // ---- mapping tables -------------------------------------------------------

    static Resource tableOrRefuse(DriverSet ds, String path) throws Operation.Refusal {
        Artifact a = ArtifactOps.artifactOrRefuse(ds, path);
        if (!(a instanceof Resource) || !((Resource) a).isMappingTable() || ((Resource) a).content == null) {
            throw new Operation.Refusal("'" + path + "' is not a mapping table");
        }
        return (Resource) a;
    }

    static List<String> columns(Element table) {
        List<String> cols = new ArrayList<>();
        for (Element c : Xds.childrenByName(table, "col-def")) {
            cols.add(c.getAttribute("name"));
        }
        return cols;
    }

    static Element rowByKey(Element table, int keyIndex, String keyValue) {
        for (Element row : Xds.childrenByName(table, "row")) {
            List<Element> cols = Xds.childrenByName(row, "col");
            if (cols.size() > keyIndex && keyValue.equals(Xds.text(cols.get(keyIndex)).trim())) {
                return row;
            }
        }
        return null;
    }

    /** {@code mapping-table.set-row}: values by column name; the key column (default: the first) finds an existing row to update. */
    public static final class TableSetRow implements Operation {
        private final String path;
        private final String keyColumn;               // null: first column
        private final Map<String, String> values;     // column -> value

        public TableSetRow(String path, String keyColumn, Map<String, String> values) {
            this.path = path;
            this.keyColumn = keyColumn;
            this.values = values;
        }

        @Override
        public String name() {
            return "mapping-table.set-row";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Resource t = tableOrRefuse(ds, path);
            Element table = t.content;
            List<String> cols = columns(table);
            String key = keyColumn == null || keyColumn.isBlank() ? cols.get(0) : keyColumn;
            int keyIndex = cols.indexOf(key);
            if (keyIndex < 0) {
                throw new Refusal("no column '" + key + "'; columns: " + cols);
            }
            for (String c : values.keySet()) {
                if (!cols.contains(c)) {
                    throw new Refusal("no column '" + c + "'; columns: " + cols);
                }
            }
            String keyValue = values.get(key);
            if (keyValue == null) {
                throw new Refusal("a value for the key column '" + key + "' is required");
            }
            tx.touch(t);
            Element row = rowByKey(table, keyIndex, keyValue);
            Document doc = table.getOwnerDocument();
            if (row == null) {
                row = doc.createElementNS(null, "row");
                for (String c : cols) {
                    Element col = doc.createElementNS(null, "col");
                    col.setTextContent(values.getOrDefault(c, ""));
                    row.appendChild(col);
                }
                table.appendChild(row);
                return;
            }
            List<Element> rowCols = Xds.childrenByName(row, "col");
            while (rowCols.size() < cols.size()) {   // pad a ragged row
                Element col = doc.createElementNS(null, "col");
                row.appendChild(col);
                rowCols = Xds.childrenByName(row, "col");
            }
            for (Map.Entry<String, String> v : values.entrySet()) {
                rowCols.get(cols.indexOf(v.getKey())).setTextContent(v.getValue());
            }
        }
    }

    public static final class TableDeleteRow implements Operation {
        private final String path;
        private final String keyColumn;
        private final String keyValue;

        public TableDeleteRow(String path, String keyColumn, String keyValue) {
            this.path = path;
            this.keyColumn = keyColumn;
            this.keyValue = keyValue;
        }

        @Override
        public String name() {
            return "mapping-table.delete-row";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Resource t = tableOrRefuse(ds, path);
            List<String> cols = columns(t.content);
            String key = keyColumn == null || keyColumn.isBlank() ? cols.get(0) : keyColumn;
            int keyIndex = cols.indexOf(key);
            if (keyIndex < 0) {
                throw new Refusal("no column '" + key + "'; columns: " + cols);
            }
            Element row = keyValue == null ? null : rowByKey(t.content, keyIndex, keyValue);
            if (row == null) {
                throw new Refusal("no row with " + key + " = '" + keyValue + "'");
            }
            tx.touch(t);
            t.content.removeChild(row);
        }
    }

    public static final class TableAddColumn implements Operation {
        private final String path;
        private final String column;
        private final String type;   // nocase | case | numeric

        public TableAddColumn(String path, String column, String type) {
            this.path = path;
            this.column = column;
            this.type = type;
        }

        @Override
        public String name() {
            return "mapping-table.add-column";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Resource t = tableOrRefuse(ds, path);
            if (column == null || column.isBlank()) {
                throw new Refusal("a column name is required");
            }
            List<String> cols = columns(t.content);
            if (cols.contains(column)) {
                throw new Refusal("column '" + column + "' already exists");
            }
            String ty = type == null || type.isBlank() ? "nocase" : type;
            if (!List.of("nocase", "case", "numeric").contains(ty)) {
                throw new Refusal("type must be nocase|case|numeric");
            }
            tx.touch(t);
            Document doc = t.content.getOwnerDocument();
            Element def = doc.createElementNS(null, "col-def");
            def.setAttribute("name", column);
            def.setAttribute("type", ty);
            List<Element> defs = Xds.childrenByName(t.content, "col-def");
            Element last = defs.get(defs.size() - 1);
            t.content.insertBefore(def, last.getNextSibling());
            for (Element row : Xds.childrenByName(t.content, "row")) {
                row.appendChild(doc.createElementNS(null, "col"));
            }
        }
    }

    /** Parse repeated {@code --col name=value} arguments. */
    static Map<String, String> columnValues(String joined) throws IllegalArgumentException {
        Map<String, String> out = new LinkedHashMap<>();
        if (joined == null || joined.isBlank()) {
            return out;
        }
        for (String pair : joined.split("\n")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--col expects name=value, not '" + pair + "'");
            }
            out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1));
        }
        return out;
    }
}
