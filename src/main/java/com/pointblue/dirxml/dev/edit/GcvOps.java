package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.GcvReferences;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GCV operations. {@code gcv.set} changes a GCV's value wherever the driver's
 * scope defines it — the driver's own config-values first, then a GCV-definition
 * resource linked in its set 14, then the driver set's config-values, then the
 * driver set's GCV objects — the engine's precedence, so the value the driver
 * sees is the one changed. With {@code --define type=… --display-name=…} an
 * absent GCV is created in the driver's (or driver set's) config-values (the
 * engine rejects a definition without a {@code display-name}). {@code gcv.delete}
 * refuses while any policy in the driver's reach references the name (as a
 * token or as {@code ~name~}) — the engine wouldn't start the driver.
 */
public final class GcvOps {

    private GcvOps() {
    }

    /** Where a GCV is defined: the definition element and the artifact (resource) or driver/driver-set that holds it. */
    public static final class Home {
        public final Element definition;
        public final Artifact resource;   // null when in a config-values blob
        final String owner;        // "drivers/D", "driverset", or the resource path

        Home(Element definition, Artifact resource, String owner) {
            this.definition = definition;
            this.resource = resource;
            this.owner = owner;
        }
    }

    public static Home find(DriverSet ds, Driver d, String name, Map<String, Artifact> index) {
        if (d != null) {
            Element def = definition(d.config.get(Driver.CONFIG_VALUES), name);
            if (def != null) {
                return new Home(def, null, "drivers/" + d.name);
            }
            for (PolicyLink l : d.links(PolicySet.GCV)) {
                Artifact a = index.get(l.ref);
                if (a instanceof Resource) {
                    def = definition(((Resource) a).content, name);
                    if (def != null) {
                        return new Home(def, a, a.path());
                    }
                }
            }
        }
        Element def = definition(ds.configValues, name);
        if (def != null) {
            return new Home(def, null, "driverset");
        }
        for (Map.Entry<String, String> m : ds.meta.entrySet()) {
            if (!m.getKey().startsWith("driverset.linkage.")) {
                continue;
            }
            String leaf = Refs.leafOfLinkage(m.getValue());
            for (Resource r : ds.library.resources) {
                if (r.isGcvDef() && r.name.equals(leaf)) {
                    def = definition(r.content, name);
                    if (def != null) {
                        return new Home(def, r, r.path());
                    }
                }
            }
        }
        return null;
    }

    public static Element definition(Element configValues, String name) {
        if (configValues == null) {
            return null;
        }
        for (Element def : Xds.descendantsByName(configValues, "definition")) {
            if (name.equals(def.getAttribute("name"))) {
                return def;
            }
        }
        return null;
    }

    // ---- set ------------------------------------------------------------------

    public static final class Set implements Operation {
        private final String driver;     // null: the driver set
        private final String name;
        private final String value;
        private final String defineType;        // non-null: create when absent
        private final String defineDisplayName;

        public Set(String driver, String name, String value, String defineType, String defineDisplayName) {
            this.driver = driver;
            this.name = name;
            this.value = value;
            this.defineType = defineType;
            this.defineDisplayName = defineDisplayName;
        }

        @Override
        public String name() {
            return "gcv.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            if (name == null || name.isBlank()) {
                throw new Refusal("a GCV name is required");
            }
            if (value == null) {
                throw new Refusal("a value is required");
            }
            Driver d = driver == null || driver.isBlank() ? null : ArtifactOps.driverOrRefuse(ds, driver);
            Home home = find(ds, d, name, ds.index());
            if (home == null) {
                if (defineType == null) {
                    throw new Refusal("GCV '" + name + "' is not defined in " + (d == null ? "the driver set" : "driver '" + d.name + "'")
                        + " or anything it links; add --define <type> --display-name <text> to create it");
                }
                Element cv = d == null ? ds.configValues : d.config.get(Driver.CONFIG_VALUES);
                if (cv == null) {
                    cv = CanonicalXml.parse("<configuration-values><definitions/></configuration-values>").getDocumentElement();
                    if (d == null) {
                        ds.configValues = cv;
                    } else {
                        d.config.put(Driver.CONFIG_VALUES, cv);
                    }
                }
                List<Element> defs = Xds.childrenByName(cv, "definitions");
                Element definitions = defs.isEmpty() ? (Element) cv.appendChild(cv.getOwnerDocument().createElementNS(null, "definitions")) : defs.get(0);
                Element def = cv.getOwnerDocument().createElementNS(null, "definition");
                def.setAttribute("display-name", defineDisplayName == null || defineDisplayName.isBlank() ? name : defineDisplayName);
                def.setAttribute("name", name);
                def.setAttribute("type", defineType);
                Element v = cv.getOwnerDocument().createElementNS(null, "value");
                v.setTextContent(value);
                def.appendChild(v);
                definitions.appendChild(def);
                return;
            }
            if (home.resource != null) {
                tx.touch(home.resource);
            }
            Element def = home.definition;
            List<Element> values = Xds.childrenByName(def, "value");
            if (values.isEmpty()) {
                Element v = def.getOwnerDocument().createElementNS(null, "value");
                v.setTextContent(value);
                def.appendChild(v);
            } else {
                values.get(0).setTextContent(value);
                for (int i = 1; i < values.size(); i++) {
                    def.removeChild(values.get(i));   // a scalar has one value
                }
            }
        }
    }

    // ---- delete ---------------------------------------------------------------

    private static final Pattern TILDE = Pattern.compile("~([A-Za-z0-9_.\\-]+)~");

    public static final class Delete implements Operation {
        private final String driver;
        private final String name;

        public Delete(String driver, String name) {
            this.driver = driver;
            this.name = name;
        }

        @Override
        public String name() {
            return "gcv.delete";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
            Driver d = driver == null || driver.isBlank() ? null : ArtifactOps.driverOrRefuse(ds, driver);
            Home home = find(ds, d, name, ds.index());
            if (home == null) {
                throw new Refusal("GCV '" + name + "' is not defined in " + (d == null ? "the driver set" : "driver '" + d.name + "'"));
            }
            List<Use> readers = usage(ds, d, name, home);
            if (!readers.isEmpty()) {
                List<String> where = new ArrayList<>();
                for (Use u : readers) {
                    where.add(u.path);
                }
                throw new Refusal("GCV '" + name + "' is read by " + readers.size() + " place" + (readers.size() == 1 ? "" : "s")
                    + " — " + String.join(", ", where));
            }
            if (home.resource != null) {
                tx.touch(home.resource);
            }
            Node parent = home.definition.getParentNode();
            parent.removeChild(home.definition);
        }
    }

    // ---- where a GCV is read ----------------------------------------------------

    /** One place that reads a GCV: a policy (its artifact path) or a driver's configuration document ({@code drivers/<name>/<kind>}). */
    public static final class Use {
        /** "policy", or the configuration kind (shim-config-info, engine-control-values, driver-filter, …). */
        public final String kind;
        public final String path;
        /** The driver whose configuration document it is, else null. */
        public final String driver;

        Use(String kind, String path, String driver) {
            this.kind = kind;
            this.path = path;
            this.driver = driver;
        }
    }

    /**
     * Every place in reach of {@code name}'s definition that reads it: policies (the GCV tokens
     * and {@code ~name~} in XPath or text) and the driver's configuration documents ({@code ~name~}
     * in shim parameters, engine controls, filter). Empty when the GCV is not defined in the scope.
     */
    public static List<Use> usage(DriverSet ds, Driver d, String name) {
        Home home = find(ds, d, name, ds.index());
        return home == null ? List.of() : usage(ds, d, name, home);
    }

    private static List<Use> usage(DriverSet ds, Driver d, String name, Home home) {
        List<Use> out = new ArrayList<>();
        List<Policy> inReach = new ArrayList<>(ds.library.policies);
        List<Driver> drivers = new ArrayList<>();
        if (d != null && home.owner.startsWith("drivers/")) {
            drivers.add(d);
        } else {
            drivers.addAll(ds.drivers);
        }
        for (Driver x : drivers) {
            inReach.addAll(Model.policies(x));
        }
        for (Policy p : inReach) {
            if (p.content == null) {
                continue;
            }
            if (GcvReferences.referenced(p.content).contains(name) || mentions(CanonicalXml.serialize(p.content), name)) {
                out.add(new Use("policy", p.path(), p.driver));
            }
        }
        for (Driver x : drivers) {
            for (Map.Entry<String, Element> c : x.config.entrySet()) {
                if (Driver.CONFIG_VALUES.equals(c.getKey()) || c.getValue() == null) {
                    continue;   // the definitions themselves
                }
                if (mentions(CanonicalXml.serialize(c.getValue()), name)) {
                    out.add(new Use(c.getKey(), "drivers/" + x.name + "/" + c.getKey(), x.name));
                }
            }
        }
        return out;
    }

    private static boolean mentions(String text, String name) {
        Matcher m = TILDE.matcher(text);
        while (m.find()) {
            if (m.group(1).equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** The driver's / driver set's policies (delegates to the validator's model helper via a local copy). */
    static final class Model {
        private Model() {
        }

        static List<Policy> policies(Driver d) {
            List<Policy> out = new ArrayList<>(d.policies);
            out.addAll(d.subscriber.policies);
            out.addAll(d.publisher.policies);
            return out;
        }
    }
}
