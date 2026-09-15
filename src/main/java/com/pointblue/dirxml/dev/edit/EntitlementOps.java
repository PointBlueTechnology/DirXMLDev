package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.flow.Flow;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Entitlement operations (Track W step W4b — see {@code docs/entitlements.md} §2). An
 * entitlement is not an {@link com.pointblue.dirxml.dev.model.Artifact}, so package
 * bookkeeping (baseline + customized mark) is done here with the same conventions as
 * {@link FormOps}: baseline file
 * {@code .package-baseline/drivers/<d>/entitlements/<file>.xml} holding the pre-edit
 * document, {@code package.customized=true} in the entitlement's meta.
 */
public final class EntitlementOps {

    private EntitlementOps() {
    }

    /** The tree-relative path used in results and baselines: {@code drivers/<d>/entitlements/<name>}. */
    public static String path(Driver d, Entitlement e) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/entitlements/" + AsCodeWriter.fileSafe(e.name);
    }

    static boolean isPackaged(Map<String, String> meta) {
        String g = meta.get("dirxml-pkgguid");
        return g != null && !g.isBlank();
    }

    private static Driver driver(DriverSet ds, String name) throws Operation.Refusal {
        if (name == null || name.isBlank()) {
            throw new Operation.Refusal("--driver is required");
        }
        Driver d = ds.driver(name);
        if (d == null) {
            throw new Operation.Refusal("no driver '" + name + "'");
        }
        return d;
    }

    /** Builds {@code <entitlement>} from the individual flags (no {@code --definition-file}). */
    private static Element buildDefinition(String displayName, String description, boolean multiValued,
                                           String conflict, List<String> values) throws Operation.Refusal {
        String cr = (conflict == null || conflict.isBlank()) ? "priority" : conflict;
        if (!cr.equals("union") && !cr.equals("priority")) {
            throw new Operation.Refusal("--conflict must be union or priority");
        }
        Document doc = com.novell.xml.dom.DocumentFactory.newDocument();
        Element root = doc.createElementNS(null, "entitlement");
        root.setAttribute("conflict-resolution", cr);
        root.setAttribute("description", description == null ? "" : description);
        root.setAttribute("display-name", displayName == null ? "" : displayName);
        doc.appendChild(root);
        Element valuesEl = doc.createElementNS(null, "values");
        valuesEl.setAttribute("multi-valued", Boolean.toString(multiValued));
        if (values != null) {
            for (String v : values) {
                if (v.isBlank()) {
                    continue;
                }
                Element ve = doc.createElementNS(null, "value");
                ve.appendChild(doc.createTextNode(v));
                valuesEl.appendChild(ve);
            }
        }
        root.appendChild(valuesEl);
        return root;
    }

    private static Element parseDefinitionFile(String file) throws Operation.Refusal {
        try {
            String xml = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            Element root = CanonicalXml.parse(xml).getDocumentElement();
            if (!"entitlement".equals(root.getLocalName() != null ? root.getLocalName() : root.getNodeName())) {
                throw new Operation.Refusal("--definition-file must hold an <entitlement> document (root was <" + root.getNodeName() + ">)");
            }
            return root;
        } catch (IOException e) {
            throw new Operation.Refusal("cannot read --definition-file '" + file + "': " + e.getMessage());
        } catch (RuntimeException e) {
            throw new Operation.Refusal("--definition-file is not well-formed XML: " + e.getMessage());
        }
    }

    private static List<String> splitValues(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String v : csv.split(",")) {
            if (!v.isBlank()) {
                out.add(v.trim());
            }
        }
        return out;
    }

    // ---- entitlement.add ----------------------------------------------------------------

    public static final class Add implements Operation {
        private final String driver;
        private final String name;
        private final String displayName;
        private final String description;
        private final boolean multiValued;
        private final String conflict;
        private final String valuesCsv;
        private final String definitionFile;

        public Add(String driver, String name, String displayName, String description, boolean multiValued,
                   String conflict, String valuesCsv, String definitionFile) {
            this.driver = driver;
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.multiValued = multiValued;
            this.conflict = conflict;
            this.valuesCsv = valuesCsv;
            this.definitionFile = (definitionFile == null || definitionFile.isBlank()) ? null : definitionFile;
        }

        @Override
        public String name() {
            return "entitlement.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (name == null || name.isBlank()) {
                throw new Operation.Refusal("--name is required");
            }
            Driver d = driver(ds, driver);
            if (d.entitlement(name) != null) {
                throw new Operation.Refusal("an entitlement named '" + name + "' already exists on driver '" + d.name + "'");
            }
            Element def = definitionFile != null ? parseDefinitionFile(definitionFile)
                : buildDefinition(displayName, description, multiValued, conflict, splitValues(valuesCsv));
            Entitlement e = new Entitlement(name, CanonicalXml.normalize(def));
            d.entitlements.add(e);
            tx.touched(path(d, e));
            tx.note("created entitlement '" + name + "' on driver '" + d.name + "'");
        }
    }

    // ---- entitlement.set ----------------------------------------------------------------

    public static final class Set implements Operation {
        private final String driver;
        private final String name;
        private final String displayName;
        private final String description;
        private final Boolean multiValued;
        private final String conflict;
        private final String valuesCsv;
        private final String definitionFile;

        public Set(String driver, String name, String displayName, String description, Boolean multiValued,
                   String conflict, String valuesCsv, String definitionFile) {
            this.driver = driver;
            this.name = name;
            this.displayName = displayName;
            this.description = description;
            this.multiValued = multiValued;
            this.conflict = conflict;
            this.valuesCsv = valuesCsv;
            this.definitionFile = (definitionFile == null || definitionFile.isBlank()) ? null : definitionFile;
        }

        @Override
        public String name() {
            return "entitlement.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Found found = find(ds, name, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(name, driver));
            }
            Entitlement e = found.entitlement;
            Element newDef;
            if (definitionFile != null) {
                newDef = parseDefinitionFile(definitionFile);
            } else {
                Element root = e.definition != null ? (Element) e.definition.cloneNode(true) : buildDefinition(null, null, false, null, null);
                if (displayName != null) {
                    root.setAttribute("display-name", displayName);
                }
                if (description != null) {
                    root.setAttribute("description", description);
                }
                if (conflict != null) {
                    if (!conflict.equals("union") && !conflict.equals("priority")) {
                        throw new Operation.Refusal("--conflict must be union or priority");
                    }
                    root.setAttribute("conflict-resolution", conflict);
                }
                Element valuesEl = firstChild(root, "values");
                if (multiValued != null || valuesCsv != null) {
                    if (valuesEl == null) {
                        valuesEl = root.getOwnerDocument().createElementNS(null, "values");
                        root.appendChild(valuesEl);
                    }
                    if (multiValued != null) {
                        valuesEl.setAttribute("multi-valued", Boolean.toString(multiValued));
                    }
                    if (valuesCsv != null) {
                        for (Element old : childElements(valuesEl, "value")) {
                            valuesEl.removeChild(old);
                        }
                        for (String v : splitValues(valuesCsv)) {
                            Element ve = root.getOwnerDocument().createElementNS(null, "value");
                            ve.appendChild(root.getOwnerDocument().createTextNode(v));
                            valuesEl.appendChild(ve);
                        }
                    }
                }
                newDef = root;
            }
            newDef = CanonicalXml.normalize(newDef);
            String oldXml = e.definition == null ? null : CanonicalXml.serialize(e.definition);
            String newXml = CanonicalXml.serialize(newDef);
            if (java.util.Objects.equals(oldXml, newXml)) {
                tx.note("entitlement '" + e.name + "': document unchanged");
                return;
            }
            customizeEntitlement(tx, found.driver, e);
            e.definition = newDef;
            tx.touched(path(found.driver, e));
            tx.note("entitlement '" + e.name + "': updated");
        }
    }

    // ---- entitlement.remove ---------------------------------------------------------------

    /** Refuses while any PRD's provision activity's {@code DirXML-Entitlement-DN} literal names it; {@code --force} never overrides this. */
    public static final class Remove implements Operation {
        private final String driver;
        private final String name;

        public Remove(String driver, String name) {
            this.driver = driver;
            this.name = name;
        }

        @Override
        public String name() {
            return "entitlement.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            Found found = find(ds, name, driver);
            if (found == null) {
                throw new Operation.Refusal(notFoundMessage(name, driver));
            }
            for (String ref : referencingPrds(ds, found.driver, found.entitlement)) {
                throw new Operation.Refusal("entitlement '" + found.entitlement.name + "' is named by " + ref
                    + "'s provision activity (DirXML-Entitlement-DN); remove or repoint the activity first "
                    + "(--force does not override this)");
            }
            String p = path(found.driver, found.entitlement);
            found.driver.entitlements.remove(found.entitlement);
            tx.touched(p);
            tx.note("removed entitlement '" + found.entitlement.name + "' from driver '" + found.driver.name + "'");
        }
    }

    /** Every {@code driver/prd} that names this entitlement in a provision activity's {@code DirXML-Entitlement-DN}. */
    static List<String> referencingPrds(DriverSet ds, Driver owner, Entitlement e) {
        List<String> out = new ArrayList<>();
        for (Driver d : ds.drivers) {
            if (d.provisioning == null) {
                continue;
            }
            for (Prd prd : d.provisioning.prds) {
                Flow flow = Flow.of(prd);
                if (flow == null) {
                    continue;
                }
                for (Flow.Activity a : flow.activities) {
                    if (a.kind != Flow.Kind.PROVISION) {
                        continue;
                    }
                    for (Flow.DataItem di : flow.dataItemsByActivity.getOrDefault(a.id, List.of())) {
                        if (!"DirXML-Entitlement-DN".equals(di.name)) {
                            continue;
                        }
                        String dn = unquoteLiteral(di.source);
                        if (dn == null) {
                            continue;
                        }
                        String[] rdns = firstTwoRdnValues(dn);
                        if (rdns != null && e.name.equalsIgnoreCase(rdns[0]) && owner.name.equalsIgnoreCase(rdns[1])) {
                            out.add(d.name + "/" + prd.name);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Reverses {@code FlowOps.quoteLiteral}: a single-quoted literal -&gt; its value; null otherwise. */
    static String unquoteLiteral(String source) {
        if (source == null) {
            return null;
        }
        String s = source.strip();
        if (s.length() < 2 || s.charAt(0) != '\'' || s.charAt(s.length() - 1) != '\'') {
            return null;
        }
        String inner = s.substring(1, s.length() - 1);
        return inner.replace("\\'", "'").replace("\\\\", "\\");
    }

    /** The first two RDN values of a DN ({@code [leaf, parent]}), naively split on unescaped commas; null if fewer than two components. */
    static String[] firstTwoRdnValues(String dn) {
        List<String> comps = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\' && i + 1 < dn.length()) {
                cur.append(c).append(dn.charAt(++i));
            } else if (c == ',') {
                comps.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            comps.add(cur.toString().trim());
        }
        if (comps.size() < 2) {
            return null;
        }
        return new String[] {rdnValue(comps.get(0)), rdnValue(comps.get(1))};
    }

    private static String rdnValue(String comp) {
        int eq = comp.indexOf('=');
        String v = eq >= 0 ? comp.substring(eq + 1) : comp;
        return v.replace("\\,", ",").replace("\\\\", "\\").trim();
    }

    // ---- lookup ---------------------------------------------------------------------------

    /** An entitlement and the driver that owns it. */
    public static final class Found {
        public final Driver driver;
        public final Entitlement entitlement;

        Found(Driver driver, Entitlement entitlement) {
            this.driver = driver;
            this.entitlement = entitlement;
        }
    }

    /** Finds an entitlement by name, optionally restricted to one driver; null when absent or ambiguous across drivers. */
    public static Found find(DriverSet ds, String name, String driverFlag) {
        Found hit = null;
        for (Driver d : ds.drivers) {
            if (driverFlag != null && !driverFlag.isBlank() && !driverFlag.equals(d.name)) {
                continue;
            }
            Entitlement e = d.entitlement(name);
            if (e != null) {
                if (hit != null) {
                    return null;   // ambiguous: say which driver
                }
                hit = new Found(d, e);
            }
        }
        return hit;
    }

    private static String notFoundMessage(String ref, String driver) {
        return "entitlement '" + ref + "' not found" + (driver == null || driver.isBlank()
            ? " (or found on several drivers — say --driver)" : " on driver '" + driver + "'");
    }

    private static Element firstChild(Element parent, String localName) {
        for (Element c : childElements(parent, localName)) {
            return c;
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        org.w3c.dom.NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node k = kids.item(i);
            if (k.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                String ln = k.getLocalName() != null ? k.getLocalName() : k.getNodeName();
                if (localName.equals(ln)) {
                    out.add((Element) k);
                }
            }
        }
        return out;
    }

    /** Baseline + customized mark for a packaged entitlement on its first edit. */
    static void customizeEntitlement(Transaction tx, Driver d, Entitlement e) {
        if (!isPackaged(e.meta)) {
            return;
        }
        boolean newly = !"true".equals(e.meta.get(Packages.CUSTOMIZED_KEY));
        Path baseline = tx.tree().resolve(".package-baseline").resolve(path(d, e) + ".xml");
        if (!Files.exists(baseline) && e.definition != null) {
            tx.pendingBaseline(baseline, CanonicalXml.serialize(e.definition));
        }
        e.meta.put(Packages.CUSTOMIZED_KEY, "true");
        if (newly) {
            tx.customizedNow(path(d, e));
        }
    }
}
