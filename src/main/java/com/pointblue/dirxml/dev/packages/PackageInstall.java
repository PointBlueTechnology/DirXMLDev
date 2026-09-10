package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.edit.Operation;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code package.install}: install one package jar onto a driver (type 2) or the
 * Library (type 3) of a tree, reproducing Designer's install
 * (docs/packages.md §3.2, docs/spikes/designer-package-layer.md §2):
 * prompts → driver {@code ds-attributes} (set, engine controls merged) → objects
 * at their placement with the package stamps → linkage by Designer's weight rule
 * → filter-extension merge → installed checksum → initial state as the package
 * baseline → the installed-package record in the driver's manifest.
 * Object classes the model doesn't carry (jobs, entitlements, ID policies,
 * templates) are reported, not installed.
 */
public final class PackageInstall implements Operation {

    // ---- meta keys (vault attribute names, lowercase, as the live/LDIF readers record them; plus Designer's two) ----
    public static final String META_GUID = "dirxml-pkgguid";
    public static final String META_ASSOC = "dirxml-pkgassociationid";
    public static final String META_CHECKSUM = "dirxml-pkgchecksum";
    public static final String META_LINKAGES = "dirxml-pkglinkages";
    public static final String META_EXTENSIONS = "dirxml-pkgextensions";
    public static final String META_PACKAGE_ID = "package-id";
    public static final String META_PKG_ASSOC = "pkg-assoc-id";
    public static final String META_INSTALLED_PREFIX = "package.installed.";
    public static final String META_NAMED_PASSWORDS = "package.named-passwords";
    public static final String META_DESIGNER_ID = "designer.id";
    public static final String META_START_OPTION = "dirxml-driverstartoption";

    public static final String FILTER_EXT = "application/vnd.novell.dirxml.filter-ext+xml";
    public static final String PKG_PROMPT = "application/vnd.novell.dirxml.pkg-prompt+xml";

    private final List<Path> jars;
    private final String driverName;      // null → Library (type 3)
    private final Map<String, String> answers;
    private final boolean propertyWizard; // true when installing onto an existing driver (Designer's PIW), false for a new one

    public PackageInstall(List<Path> jars, String driverName, Map<String, String> answers, boolean propertyWizard) {
        this.jars = jars;
        this.driverName = driverName;
        this.answers = answers == null ? Map.of() : answers;
        this.propertyWizard = propertyWizard;
    }

    @Override
    public String name() {
        return "package.install";
    }

    @Override
    public void apply(DriverSet ds, Transaction tx) throws Refusal, IOException {
        bind();
        if (jars == null || jars.isEmpty()) {
            throw new Refusal("nothing to install");
        }
        // Designer installs the resolved set together and validates afterwards; so do we: one transaction, in order
        for (Path jar : jars) {
            installOne(ds, tx, jar);
        }
    }

    private void installOne(DriverSet ds, Transaction tx, Path jar) throws Refusal, IOException {
        PackageJar p;
        try {
            p = PackageJar.read(jar);
        } catch (IOException e) {
            throw new Refusal("cannot read package " + jar + ": " + e.getMessage());
        }
        Driver d = null;
        if (p.type == 2) {
            if (driverName == null) {
                throw new Refusal(p.shortName + " is a driver package (type 2); give --driver");
            }
            d = ds.driver(driverName);
            if (d == null) {
                throw new Refusal("no driver '" + driverName + "' in the tree");
            }
        } else if (p.type == 3) {
            if (driverName != null) {
                throw new Refusal(p.shortName + " is a driver-set package (type 3); it installs into the Library, not a driver");
            }
        } else {
            throw new Refusal(p.shortName + " is a type " + p.type + " package; only driver (2) and driver-set (3) packages install into a tree");
        }
        String guid = guid(p);
        if (d != null) {
            if (d.meta.containsKey(META_INSTALLED_PREFIX + p.shortName)) {
                throw new Refusal(p.shortName + " is already installed on '" + d.name + "' ("
                    + d.meta.get(META_INSTALLED_PREFIX + p.shortName) + "); use package.upgrade");
            }
            if (p.basePackage) {
                for (Map.Entry<String, String> m : d.meta.entrySet()) {
                    if (m.getKey().startsWith(META_INSTALLED_PREFIX) && m.getValue().endsWith(";base")) {
                        throw new Refusal("'" + d.name + "' already has a base package (" + m.getValue() + "); one base package per driver");
                    }
                }
            }
            supportedShim(p, d, tx);
        } else if (ds.meta.containsKey(META_INSTALLED_PREFIX + p.shortName)) {
            throw new Refusal(p.shortName + " is already installed on the driver set (" + ds.meta.get(META_INSTALLED_PREFIX + p.shortName) + ")");
        }
        new Install(ds, d, p, guid, tx).run();
    }

    /** The shim class a base package's driver attributes name ({@code java-module} / {@code native-module}), or null. */
    public static String shimClassOf(Path jar) {
        try {
            PackageJar p = PackageJar.read(jar);
            Element dir = CanonicalXml.parse(p.directive).getDocumentElement();
            Element attrs = PromptEngine.child(dir, "ds-attributes");
            if (attrs == null) {
                return null;
            }
            for (Element a : PromptEngine.children(attrs, "ds-attribute")) {
                String n = a.getAttribute("ds-attr-name");
                if (n.equals("java-module") || n.equals("native-module")) {
                    Element v = PromptEngine.child(a, "ds-value");
                    return v == null ? null : PromptEngine.text(v).trim();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The 5-field record the vault keeps: {@code id;symbolicName;version;name;shortName}. */
    public static String guid(PackageJar p) {
        String name = p.pkg.getAttribute("name");
        String s = p.pkg.getAttribute("id") + ";" + p.symbolicName + ";" + p.version;
        if (name != null && !name.isEmpty()) {
            s += ";" + name + ";" + (p.shortName == null ? "" : p.shortName);
        }
        return s;
    }

    private static void supportedShim(PackageJar p, Driver d, Transaction tx) {
        String sd = p.manifestAttr("Supported-Drivers");
        if (sd == null || d.shimClass == null) {
            return;
        }
        // informational only: the manifest lists driver ids, not shim classes; the vault/Designer check is by type
        tx.note("supported drivers (from the package): " + supportedDriverIds(sd));
    }

    static String supportedDriverIds(String base64) {
        try {
            String xml = new String(java.util.Base64.getMimeDecoder().decode(base64), StandardCharsets.UTF_8);
            List<String> ids = new ArrayList<>();
            for (Element def : PromptEngine.children(CanonicalXml.parse(xml).getDocumentElement(), "definition")) {
                ids.add(def.getAttribute("driver-id"));
            }
            return String.join(", ", ids);
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** One install, Designer's order of operations. */
    static final class Install {
        final DriverSet ds;
        final Driver d;                    // null for a Library install
        final PackageJar p;
        final String guid;
        final Transaction tx;
        final Map<String, PackageJar.Item> byAssoc = new LinkedHashMap<>();
        final Map<String, Element> directives = new LinkedHashMap<>();   // assoc id → (prompt-transformed) directive
        final Map<String, Element> contents = new LinkedHashMap<>();     // assoc id → (prompt-transformed) content
        final Map<String, Artifact> created = new LinkedHashMap<>();     // assoc id → artifact
        final List<String> namedPasswords = new ArrayList<>();

        Install(DriverSet ds, Driver d, PackageJar p, String guid, Transaction tx) {
            this.ds = ds;
            this.d = d;
            this.p = p;
            this.guid = guid;
            this.tx = tx;
        }

        void run() throws Refusal, IOException {
            for (PackageJar.Item it : p.items) {
                if (it.assocId != null) {
                    byAssoc.put(it.assocId, it);
                    directives.put(it.assocId, CanonicalXml.parse(it.directive).getDocumentElement());
                    if (it.content != null) {
                        contents.put(it.assocId, CanonicalXml.parse(NxslCanonical.serialize(it.content)).getDocumentElement());
                    }
                }
            }
            Element packageDirective = CanonicalXml.parse(p.directive).getDocumentElement();
            // 1. prompts
            prompts(packageDirective);
            // 2. driver attributes from the package directive
            if (d != null) {
                driverAttributes(PromptEngine.child(packageDirective, "ds-attributes"));
            }
            // 3. objects (op 1) — everything but filter extensions and prompts
            for (PackageJar.Item it : p.items) {
                if (it.assocId == null) {
                    continue;
                }
                if (PackageChecksum.RESOURCE.equals(it.objectClass) && it.contentType != null) {
                    if (it.contentType.startsWith(PKG_PROMPT)) {
                        continue;
                    }
                    if (it.contentType.startsWith(FILTER_EXT)) {
                        continue;   // merged in step 5
                    }
                }
                create(it);
            }
            // 4. linkage (op 4: First/Last/Weight; op 11: Before/After), initial state, installed checksum
            for (Map.Entry<String, Artifact> e : created.entrySet()) {
                link(e.getKey(), e.getValue(), false);
            }
            for (Map.Entry<String, Artifact> e : created.entrySet()) {
                link(e.getKey(), e.getValue(), true);
            }
            for (Map.Entry<String, Artifact> e : created.entrySet()) {
                stamp(e.getKey(), e.getValue());
            }
            // 4b. package-level linkage: this package links items (its own or a dependency's) by association id
            packageLinkage(packageDirective);
            // 5. filter extensions
            if (d != null) {
                for (PackageJar.Item it : p.items) {
                    if (PackageChecksum.RESOURCE.equals(it.objectClass) && it.contentType != null
                        && it.contentType.startsWith(FILTER_EXT) && it.assocId != null) {
                        filterExtension(it);
                    }
                }
            }
            // 6. the installed-package record
            Map<String, String> meta = d != null ? d.meta : ds.meta;
            meta.put(META_INSTALLED_PREFIX + p.shortName, guid + (p.basePackage ? ";base" : ""));
            if (d != null && p.basePackage) {
                d.meta.put(META_GUID, guid);
            }
            if (!namedPasswords.isEmpty() && d != null) {
                List<String> all = new ArrayList<>();
                String prev = d.meta.get(META_NAMED_PASSWORDS);
                if (prev != null && !prev.isBlank()) {
                    all.addAll(List.of(prev.split(",")));
                }
                for (String n : namedPasswords) {
                    if (!all.contains(n)) {
                        all.add(n);
                    }
                }
                d.meta.put(META_NAMED_PASSWORDS, String.join(",", all));
                tx.note("named passwords this package expects (provide them in the environment's secrets file): " + String.join(", ", namedPasswords));
            }
        }

        // ---- prompts ----

        void prompts(Element packageDirective) throws Refusal {
            List<PromptEngine.Prompt> prompts = new ArrayList<>();
            for (PackageJar.Item it : p.items) {
                if (PackageChecksum.RESOURCE.equals(it.objectClass) && it.contentType != null && it.contentType.startsWith(PKG_PROMPT)) {
                    if (it.directive == null) {
                        continue;
                    }
                    prompts.add(PromptEngine.read(it));
                }
            }
            prompts.sort((a, b) -> Integer.compare(a.order, b.order));
            Element curDoc = currentAttributes();
            Element npDoc = CanonicalXml.parse("<named-passwords/>").getDocumentElement();
            List<String> unanswered = new ArrayList<>();
            for (PromptEngine.Prompt pr : prompts) {
                PromptEngine.answer(pr, answersFor(pr), curDoc, npDoc, propertyWizardFlag());
                for (String u : pr.unanswered) {
                    unanswered.add(pr.name + ": " + u);
                }
                namedPasswords.addAll(pr.passwordRefs);
                List<String> targets = pr.targetAssocIds.isEmpty() ? List.of(p.pkg.getAttribute("id")) : pr.targetAssocIds;
                for (String t : targets) {
                    if (t.equals(p.pkg.getAttribute("id"))) {
                        Element out = PromptEngine.applyToTarget(pr, packageDirective, curDoc, npDoc, packageDirective, propertyWizardFlag());
                        if (out != null && "installation-directive".equals(out.getNodeName())) {
                            replaceChildren(packageDirective, out);
                        }
                        continue;
                    }
                    Element dir = directives.get(t);
                    if (dir == null) {
                        tx.note("prompt '" + pr.name + "' targets " + t + ", which this package does not contain; ignored");
                        continue;
                    }
                    Element out = PromptEngine.applyToTarget(pr, dir, curDoc, npDoc, dir, propertyWizardFlag());
                    if (out != null && "installation-directive".equals(out.getNodeName())) {
                        directives.put(t, out);
                    }
                    PackageJar.Item target = byAssoc.get(t);
                    Element content = contents.get(t);
                    if (content != null && target != null && !PackageChecksum.GCV_DEF.equals(target.objectClass)) {
                        Element c2 = PromptEngine.applyToTarget(pr, content, curDoc, npDoc, directives.get(t), propertyWizardFlag());
                        if (c2 != null && c2.getNodeName().equals(content.getNodeName())) {
                            contents.put(t, c2);
                        }
                    }
                }
            }
            if (!unanswered.isEmpty()) {
                throw new Refusal("mandatory prompts without a value (give --answers; see package.prompts): " + unanswered);
            }
        }

        private Map<String, String> answersFor(PromptEngine.Prompt pr) {
            return answers();
        }

        private Map<String, String> answers() {
            return PackageInstall.this_answers.get();
        }

        private boolean propertyWizardFlag() {
            return PackageInstall.this_wizard.get();
        }

        /** The target's current attributes as Designer's {@code curDoc}: a {@code <ds-attributes>} document. */
        Element currentAttributes() {
            Document doc = CanonicalXml.parse("<ds-attributes/>");
            Element root = doc.getDocumentElement();
            if (d != null) {
                attr(root, "name", d.name);
                attr(root, "shim-auth-id", d.shimAuthId);
                attr(root, "shim-auth-server", d.shimAuthServer);
                attr(root, "java-module", d.shimClass);
                attr(root, "driver-start-option", d.meta.get(META_START_OPTION));
            }
            return root;
        }

        private static void attr(Element root, String name, String value) {
            if (value == null || value.isEmpty()) {
                return;
            }
            Element a = root.getOwnerDocument().createElementNS(null, "ds-attribute");
            a.setAttribute("ds-attr-name", name);
            Element v = root.getOwnerDocument().createElementNS(null, "ds-value");
            v.appendChild(root.getOwnerDocument().createTextNode(value));
            a.appendChild(v);
            root.appendChild(a);
        }

        // ---- driver attributes (Designer's DriverImpl.setAttributesFromXml) ----

        void driverAttributes(Element dsAttributes) throws Refusal {
            if (dsAttributes == null) {
                return;
            }
            List<String> skipped = new ArrayList<>();
            for (Element a : PromptEngine.children(dsAttributes, "ds-attribute")) {
                String name = a.getAttribute("ds-attr-name");
                Element v = PromptEngine.child(a, "ds-value");
                Element el = v == null ? null : PromptEngine.firstElement(v);
                String text = v == null ? null : PromptEngine.text(v);
                switch (name) {
                    case "name":
                        break;   // the driver already has its name
                    case "java-module":
                        if (text != null && !text.isBlank()) {
                            d.shimClass = text.trim();
                        }
                        break;
                    case "native-module":
                        if (text != null && !text.isBlank()) {
                            d.shimClass = text.trim();
                        }
                        break;
                    case "shim-auth-id":
                        d.shimAuthId = text == null || text.isBlank() ? null : text.trim();
                        break;
                    case "shim-auth-server":
                        d.shimAuthServer = text == null || text.isBlank() ? null : text.trim();
                        break;
                    case "shim-auth-password":
                        namedPasswords.add("shim-auth-password");
                        break;
                    case "shim-config-info-xml":
                        if (el != null) {
                            d.config.put(Driver.SHIM_CONFIG_INFO, copy(el));
                        }
                        break;
                    case "configuration-values":
                    case "global-config-values":
                        if (el != null) {
                            d.config.put(Driver.CONFIG_VALUES, copy(el));
                        }
                        break;
                    case "driver-filter-xml":
                        if (el != null) {
                            d.config.put(Driver.DRIVER_FILTER, copy(el));
                        }
                        break;
                    case "global-engine-values":
                        if (el != null) {
                            d.config.put(Driver.ENGINE_CONTROL_VALUES, mergeGcvDocuments(d.config.get(Driver.ENGINE_CONTROL_VALUES), el));
                        }
                        break;
                    case "driver-start-option":
                        if (text != null && !text.isBlank()) {
                            d.meta.put(META_START_OPTION, text.trim());
                        }
                        break;
                    case "trace-level":
                        putMeta("dirxml-tracelevel", text);
                        break;
                    case "trace-file":
                        putMeta("dirxml-tracefile", text);
                        break;
                    case "named-password":
                        for (Element dv : PromptEngine.children(a, "ds-value")) {
                            String n = dv.getAttribute("name");
                            if (!n.isEmpty()) {
                                namedPasswords.add(n);
                            }
                        }
                        break;
                    default:
                        skipped.add(name);
                }
            }
            if (!skipped.isEmpty()) {
                tx.note("driver attributes the model does not carry, not applied: " + String.join(", ", skipped));
            }
        }

        private void putMeta(String key, String text) {
            if (text != null && !text.isBlank()) {
                d.meta.put(key, text.trim());
            }
        }

        /** Designer's engine-control merge: existing definitions of the same name keep their value; new ones are added. */
        static Element mergeGcvDocuments(Element existing, Element incoming) {
            Element in = copy(incoming);
            if (existing == null) {
                return in;
            }
            Map<String, Element> have = new LinkedHashMap<>();
            for (Element def : PromptEngine.definitions(existing)) {
                have.put(def.getAttribute("name"), def);
            }
            for (Element def : PromptEngine.definitions(in)) {
                Element old = have.get(def.getAttribute("name"));
                if (old != null && old.getAttribute("type").equals(def.getAttribute("type"))) {
                    Element ov = PromptEngine.child(old, "value");
                    if (ov != null) {
                        PromptEngine.setValue(def, PromptEngine.text(ov));
                    }
                }
            }
            return in;
        }

        // ---- objects ----

        void create(PackageJar.Item it) throws Refusal {
            Element dir = directives.get(it.assocId);
            Element placement = PromptEngine.child(dir, "placement");
            String location = placement == null ? "" : placement.getAttribute("location");
            String context = placement == null ? "" : placement.getAttribute("context");
            Scope scope;
            Driver owner = d;
            if ("library".equalsIgnoreCase(location) || d == null) {
                scope = Scope.LIBRARY;
                owner = null;
            } else if ("subscriber".equalsIgnoreCase(location)) {
                scope = Scope.SUBSCRIBER;
            } else if ("publisher".equalsIgnoreCase(location)) {
                scope = Scope.PUBLISHER;
            } else if ("driver-set".equalsIgnoreCase(location) || "identity-vault".equalsIgnoreCase(context)) {
                scope = Scope.LIBRARY;   // the tree keeps driver-set-level objects in the Library
                owner = null;
                tx.note("'" + it.name + "' is placed at the driver set; the tree keeps it in the Library");
            } else {
                scope = Scope.DRIVER;
            }
            String driverName = owner == null ? null : owner.name;
            Artifact existing = ds.resolve(Artifact.path(scope, driverName, it.name));
            if (existing != null) {
                String otherPkg = existing.meta.get(META_PACKAGE_ID);
                if (otherPkg != null && !otherPkg.equals(p.pkg.getAttribute("id"))) {
                    throw new Refusal("'" + existing.path() + "' exists and belongs to another package (" + otherPkg + ")");
                }
                throw new Refusal("'" + existing.path() + "' already exists; Designer would take it over as a customized package item — remove or rename it first");
            }
            Element content = contents.get(it.assocId);
            Artifact a;
            switch (it.objectClass) {
                case PackageChecksum.RULE:
                case PackageChecksum.STYLESHEET: {
                    Policy pol = new Policy(it.name, scope, driverName, content == null ? null : copy(content));
                    a = pol;
                    break;
                }
                case PackageChecksum.RESOURCE: {
                    Resource r = new Resource(it.name, scope, driverName, it.contentType);
                    if (content != null && PackageChecksum.isXmlContentType(it.contentType)) {
                        r.content = copy(content);
                    } else if (it.text != null) {
                        r.text = it.text;
                    } else if (content != null) {
                        r.content = copy(content);
                    }
                    a = r;
                    break;
                }
                case PackageChecksum.GCV_DEF: {
                    Resource r = new Resource(it.name, scope, driverName, Resource.GCV_DEF);
                    Element cv = PromptEngine.child(dir, "configuration-values");
                    r.content = cv == null ? CanonicalXml.parse("<configuration-values><definitions/></configuration-values>").getDocumentElement() : copy(cv);
                    a = r;
                    break;
                }
                default:
                    tx.note("not installed: " + it.objectClass + " '" + it.name + "' (not in the model yet)");
                    return;
            }
            a.meta.put(META_PACKAGE_ID, p.pkg.getAttribute("id"));
            a.meta.put(META_PKG_ASSOC, it.assocId);
            a.meta.put(META_GUID, guid);
            a.meta.put(META_ASSOC, it.assocId);
            if (scope == Scope.LIBRARY) {
                if (a instanceof Policy) {
                    ds.library.policies.add((Policy) a);
                } else {
                    ds.library.resources.add((Resource) a);
                }
            } else if (scope == Scope.DRIVER) {
                if (a instanceof Policy) {
                    d.policies.add((Policy) a);
                } else {
                    d.resources.add((Resource) a);
                }
            } else {
                (scope == Scope.SUBSCRIBER ? d.subscriber : d.publisher).policies.add((Policy) a);
            }
            created.put(it.assocId, a);
        }

        // ---- linkage ----

        /** {@code <policy-linkage><policy-set name channel order value order-id/>…} → links on the driver. */
        void link(String assocId, Artifact a, boolean relativePass) throws Refusal {
            Element dir = directives.get(assocId);
            Element linkage = PromptEngine.child(dir, "policy-linkage");
            if (linkage == null) {
                return;
            }
            for (Element ps : PromptEngine.children(linkage, "policy-set")) {
                String order = ps.getAttribute("order");
                boolean relative = order.equalsIgnoreCase("Before") || order.equalsIgnoreCase("After");
                if (relative != relativePass) {
                    continue;
                }
                linkOne(a, ps);
            }
        }

        /** One linkage entry applied to one artifact (Designer's linkPackagePolicy / linkPackageGlobalConfig / linkPackageECMAScript). */
        void linkOne(Artifact a, Element ps) throws Refusal {
            String order = ps.getAttribute("order");
            boolean relative = order.equalsIgnoreCase("Before") || order.equalsIgnoreCase("After");
            PolicySet set = policySet(ps.getAttribute("name"), ps.getAttribute("channel"));
            if (set == null) {
                tx.note("'" + a.name + "': linkage into set '" + ps.getAttribute("name") + "' (" + ps.getAttribute("channel")
                    + ") is not in the model; not linked");
                return;
            }
            Driver target = d;
            if (target == null) {
                if (set == PolicySet.GCV) {
                    linkDriverSet(a, ps);
                    return;
                }
                tx.note("'" + a.name + "': a Library object cannot be linked without a driver; not linked");
                return;
            }
            long weight;
            int at;
            List<PolicyLink> current = target.links(set);
            for (PolicyLink l : current) {
                if (l.ref.equals(a.path())) {
                    return;   // already in the set (an item directive and a package directive may both name it)
                }
            }
            if (order.equalsIgnoreCase("First")) {
                at = 0;
                weight = 0;
            } else if (order.equalsIgnoreCase("Weight")) {
                weight = parseLong(ps.getAttribute("value"), Long.MAX_VALUE);
                at = current.size();
                for (int i = 0; i < current.size(); i++) {
                    if (weightOf(current.get(i), set) > weight) {
                        at = i;
                        break;
                    }
                }
            } else if (relative) {
                String anchorAssoc = ps.getAttribute("order-id");
                int i = indexOfAssoc(current, anchorAssoc);
                if (i < 0) {
                    at = order.equalsIgnoreCase("After") ? current.size() : 0;
                    weight = order.equalsIgnoreCase("After") ? Long.MAX_VALUE : 0;
                } else {
                    at = order.equalsIgnoreCase("After") ? i + 1 : i;
                    weight = weightOf(current.get(i), set);
                }
            } else {   // Last or unknown
                at = current.size();
                weight = Long.MAX_VALUE;
            }
            target.links.removeAll(current);
            List<String> refs = new ArrayList<>();
            for (PolicyLink l : current) {
                refs.add(l.ref);
            }
            refs.add(at, a.path());
            for (int i = 0; i < refs.size(); i++) {
                target.links.add(new PolicyLink(set, refs.get(i), i));
            }
            recordLinkage(a, ps, weight, target);
        }

        /** {@code <policy-linkage>} on the package directive: entries carry {@code pkg-assoc-id} of the item to link. */
        void packageLinkage(Element packageDirective) throws Refusal {
            Element linkage = PromptEngine.child(packageDirective, "policy-linkage");
            if (linkage == null || d == null) {
                return;
            }
            List<Artifact> relinked = new ArrayList<>();
            for (Element ps : PromptEngine.children(linkage, "policy-set")) {
                String assoc = ps.getAttribute("pkg-assoc-id");
                Artifact a = byAssocOnDriver(assoc);
                if (a == null) {
                    tx.note("package linkage: no installed object with association id " + assoc + " (package "
                        + ps.getAttribute("package-id") + ") on '" + d.name + "'; not linked — install that package first");
                    continue;
                }
                if (com.pointblue.dirxml.dev.edit.Packages.isCustomized(a)) {
                    tx.note("package linkage: '" + a.path() + "' is customized; its linkage is left alone");
                    continue;
                }
                linkOne(a, ps);
                relinked.add(a);
            }
            for (Artifact a : relinked) {
                a.meta.put(META_CHECKSUM, "" + installedChecksum(a));
                tx.installed(a);
            }
        }

        Artifact byAssocOnDriver(String assoc) {
            for (Artifact a : d.artifacts()) {
                if (assoc.equals(a.meta.get(META_PKG_ASSOC)) || assoc.equals(a.meta.get(META_ASSOC))) {
                    return a;
                }
            }
            return null;
        }

        private void linkDriverSet(Artifact a, Element ps) {
            int n = 0;
            for (String k : ds.meta.keySet()) {
                if (k.startsWith("driverset.linkage.")) {
                    n++;
                }
            }
            String dn = ds.dn == null ? a.name : "cn=" + a.name + ",cn=Library," + ds.dn;
            ds.meta.put("driverset.linkage." + n, dn + "#" + n + "#" + PolicySet.GCV.id);
            recordLinkage(a, ps, parseLong(ps.getAttribute("value"), Long.MAX_VALUE), null);
        }

        /** Appends Designer's record of the link to the artifact's linkages meta (the vault's DirXML-pkgLinkages). */
        private void recordLinkage(Artifact a, Element ps, long weight, Driver target) {
            Map<String, String> attrs = new TreeMap<>();
            if (target != null) {
                attrs.put("Driver", designerId(target));
            }
            if (!ps.getAttribute("channel").isEmpty()) {
                attrs.put("channel", ps.getAttribute("channel"));
            }
            attrs.put("name", ps.getAttribute("name"));
            attrs.put("order", "Weight");
            attrs.put("package-id", p.pkg.getAttribute("id"));
            attrs.put("value", "" + weight);
            StringBuilder entry = new StringBuilder("\t<policy-set");
            for (Map.Entry<String, String> e : attrs.entrySet()) {
                entry.append(' ').append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
            }
            entry.append("/>");
            String prev = a.meta.get(META_LINKAGES);
            String body;
            if (prev == null || !prev.contains("<policy-linkage>")) {
                body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><policy-linkage>\n" + entry + "\n</policy-linkage>";
            } else {
                body = prev.replace("\n</policy-linkage>", "\n" + entry + "\n</policy-linkage>");
            }
            a.meta.put(META_LINKAGES, body);
        }

        private String designerId(Driver target) {
            String id = target.meta.get(META_DESIGNER_ID);
            if (id == null || id.isEmpty()) {
                id = mintId();
                target.meta.put(META_DESIGNER_ID, id);
            }
            return id;
        }

        /** The weight Designer would see for an existing link: its recorded linkage entry, else −1. */
        long weightOf(PolicyLink l, PolicySet set) {
            Artifact a = ds.resolve(l.ref);
            if (a == null) {
                return -1;
            }
            String rec = a.meta.get(META_LINKAGES);
            if (rec == null) {
                return -1;
            }
            try {
                Element root = CanonicalXml.parse(rec).getDocumentElement();
                for (Element ps : PromptEngine.children(root, "policy-set")) {
                    PolicySet s = policySet(ps.getAttribute("name"), ps.getAttribute("channel"));
                    if (s == set) {
                        return parseLong(ps.getAttribute("value"), -1);
                    }
                }
            } catch (Exception e) {
                return -1;
            }
            return -1;
        }

        int indexOfAssoc(List<PolicyLink> current, String assoc) {
            for (int i = 0; i < current.size(); i++) {
                Artifact a = ds.resolve(current.get(i).ref);
                if (a != null && assoc.equals(a.meta.get(META_PKG_ASSOC))) {
                    return i;
                }
            }
            return -1;
        }

        // ---- stamps: initial state, installed checksum ----

        void stamp(String assocId, Artifact a) throws IOException {
            String content = com.pointblue.dirxml.dev.edit.Packages.currentContent(a);
            if (content != null) {
                tx.baseline(a, content);
            }
            a.meta.put(META_CHECKSUM, "" + installedChecksum(a));
            tx.installed(a);
        }

        /** Designer's installed content checksum: content + the names of the sets the object is linked into. */
        long installedChecksum(Artifact a) {
            return InstalledChecksum.of(ds, d, a);
        }

        // ---- filter extensions ----

        void filterExtension(PackageJar.Item it) {
            Element pkgFilter = contents.get(it.assocId);
            if (pkgFilter == null) {
                return;
            }
            Element filter = d.config.get(Driver.DRIVER_FILTER);
            if (filter == null) {
                filter = CanonicalXml.parse("<filter/>").getDocumentElement();
                d.config.put(Driver.DRIVER_FILTER, filter);
            }
            Element cache = d.meta.containsKey(META_EXTENSIONS)
                ? CanonicalXml.parse(d.meta.get(META_EXTENSIONS)).getDocumentElement()
                : CanonicalXml.parse("<filter/>").getDocumentElement();
            for (Element pc : PromptEngine.children(pkgFilter, "filter-class")) {
                String cn = pc.getAttribute("class-name");
                Element dc = byAttr(filter, "filter-class", "class-name", cn);
                Element cc = byAttr(cache, "filter-class", "class-name", cn);
                if (cc == null) {
                    cc = cache.getOwnerDocument().createElementNS(null, "filter-class");
                    cc.setAttribute("class-name", cn);
                    cache.appendChild(cc);
                    if (dc != null) {
                        cc.setAttribute("existing", "true");
                        copyAttrs(dc, cc, CLASS_ATTRS);
                    }
                }
                if (dc == null) {
                    dc = (Element) filter.getOwnerDocument().importNode(pc, true);
                    filter.appendChild(dc);
                } else {
                    mergeByPrecedence(pc, dc, CLASS_ATTRS);
                    for (Element pa : PromptEngine.children(pc, "filter-attr")) {
                        String an = pa.getAttribute("attr-name");
                        Element da = byAttr(dc, "filter-attr", "attr-name", an);
                        Element ca = byAttr(cc, "filter-attr", "attr-name", an);
                        if (ca == null) {
                            ca = cache.getOwnerDocument().createElementNS(null, "filter-attr");
                            ca.setAttribute("attr-name", an);
                            cc.appendChild(ca);
                            if (da != null) {
                                ca.setAttribute("existing", "true");
                                copyAttrs(da, ca, ATTR_ATTRS);
                            }
                        }
                        if (da == null) {
                            dc.appendChild(dc.getOwnerDocument().importNode(pa, true));
                        } else {
                            mergeByPrecedence(pa, da, ATTR_ATTRS);
                        }
                        ownerMark(ca, it.assocId);
                    }
                }
                ownerMark(cc, it.assocId);
                if (dc.getParentNode() == filter && byAttr(cache, "filter-class", "class-name", cn) == cc) {
                    for (Element pa : PromptEngine.children(pc, "filter-attr")) {
                        String an = pa.getAttribute("attr-name");
                        if (byAttr(cc, "filter-attr", "attr-name", an) == null) {
                            Element ca = cache.getOwnerDocument().createElementNS(null, "filter-attr");
                            ca.setAttribute("attr-name", an);
                            cc.appendChild(ca);
                            ownerMark(ca, it.assocId);
                        }
                    }
                }
            }
            d.meta.put(META_EXTENSIONS, CanonicalXml.serialize(cache));
        }

        private void ownerMark(Element cacheEl, String assocId) {
            for (Element o : PromptEngine.children(cacheEl, "package")) {
                if (o.getAttribute("pkg-assoc-id").equals(assocId)) {
                    return;
                }
            }
            Element o = cacheEl.getOwnerDocument().createElementNS(null, "package");
            o.setAttribute("package-id", p.pkg.getAttribute("id"));
            o.setAttribute("pkg-assoc-id", assocId);
            cacheEl.insertBefore(o, cacheEl.getFirstChild());
        }
    }

    // ---- statics shared by the install ----

    static final List<String> CLASS_ATTRS = List.of("publisher", "subscriber", "publisher-create-homedir", "publisher-track-template-member");
    static final List<String> ATTR_ATTRS = List.of("publisher", "subscriber", "merge-authority", "publisher-optimize-modify", "priority-sync");

    static int precedence(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        switch (v) {
            case "sync": case "app": return 4;
            case "notify": case "edir": return 3;
            case "ignore": case "default": case "true": return 2;
            case "reset": case "none": case "false": return 1;
            default: return 0;
        }
    }

    static void mergeByPrecedence(Element from, Element into, List<String> attrs) {
        for (String n : attrs) {
            String v = from.getAttribute(n);
            if (v.isEmpty()) {
                continue;
            }
            if (precedence(v) > precedence(into.getAttribute(n))) {
                into.setAttribute(n, v);
            }
        }
    }

    static void copyAttrs(Element from, Element into, List<String> attrs) {
        for (String n : attrs) {
            if (!from.getAttribute(n).isEmpty()) {
                into.setAttribute(n, from.getAttribute(n));
            }
        }
    }

    static Element byAttr(Element parent, String el, String attr, String value) {
        for (Element c : PromptEngine.children(parent, el)) {
            if (c.getAttribute(attr).equals(value)) {
                return c;
            }
        }
        return null;
    }

    /** Designer's policy-set names → the model's sets. */
    static PolicySet policySet(String name, String channel) {
        String n = name == null ? "" : name.trim();
        if (n.startsWith("subscriber ")) {
            channel = "subscriber";
            n = n.substring("subscriber ".length());
        } else if (n.startsWith("publisher ")) {
            channel = "publisher";
            n = n.substring("publisher ".length());
        }
        boolean pub = "publisher".equalsIgnoreCase(channel);
        switch (n.toLowerCase()) {
            case "input": return PolicySet.INPUT;
            case "output": return PolicySet.OUTPUT;
            case "schema": case "schema-mapping": return PolicySet.SCHEMA_MAPPING;
            case "gcv": return PolicySet.GCV;
            case "ecma-script": case "ecmascript": return PolicySet.ECMASCRIPT;
            case "event": return pub ? PolicySet.PUB_EVENT : PolicySet.SUB_EVENT;
            case "matching": return pub ? PolicySet.PUB_MATCH : PolicySet.SUB_MATCH;
            case "creation": case "create": return pub ? PolicySet.PUB_CREATE : PolicySet.SUB_CREATE;
            case "placement": return pub ? PolicySet.PUB_PLACEMENT : PolicySet.SUB_PLACEMENT;
            case "command": return pub ? PolicySet.PUB_COMMAND : PolicySet.SUB_COMMAND;
            default: return null;
        }
    }

    /** Designer's {@code addPolicySetRefs} enumeration order (i = 0..; the model's subset). */
    static final List<PolicySet> ADD_POLICY_SET_REFS_ORDER = List.of(
        PolicySet.INPUT, PolicySet.SCHEMA_MAPPING, PolicySet.PUB_EVENT, PolicySet.PUB_MATCH, PolicySet.PUB_CREATE,
        PolicySet.PUB_PLACEMENT, PolicySet.PUB_COMMAND, PolicySet.OUTPUT, PolicySet.SUB_COMMAND, PolicySet.SUB_PLACEMENT,
        PolicySet.SUB_CREATE, PolicySet.SUB_MATCH, PolicySet.SUB_EVENT, PolicySet.GCV, PolicySet.ECMASCRIPT);

    static final Map<PolicySet, String> DESIGNER_SET_NAMES = Map.ofEntries(
        Map.entry(PolicySet.INPUT, "input"), Map.entry(PolicySet.SCHEMA_MAPPING, "schema"),
        Map.entry(PolicySet.PUB_EVENT, "event"), Map.entry(PolicySet.PUB_MATCH, "matching"),
        Map.entry(PolicySet.PUB_CREATE, "creation"), Map.entry(PolicySet.PUB_PLACEMENT, "placement"),
        Map.entry(PolicySet.PUB_COMMAND, "command"), Map.entry(PolicySet.OUTPUT, "output"),
        Map.entry(PolicySet.SUB_COMMAND, "command"), Map.entry(PolicySet.SUB_PLACEMENT, "placement"),
        Map.entry(PolicySet.SUB_CREATE, "creation"), Map.entry(PolicySet.SUB_MATCH, "matching"),
        Map.entry(PolicySet.SUB_EVENT, "event"), Map.entry(PolicySet.GCV, "gcv"), Map.entry(PolicySet.ECMASCRIPT, "ecma-script"));

    static Element copy(Element e) {
        return CanonicalXml.parse(CanonicalXml.serialize(e)).getDocumentElement();
    }

    /** A JDK-DOM element re-parsed by the engine's parser (what the checksum canonicalizer expects). */
    static Element toNxsl(Element e) {
        return e == null ? null : NxslCanonical.parse(CanonicalXml.serialize(e)).getDocumentElement();
    }

    static void replaceChildren(Element target, Element source) {
        while (target.getFirstChild() != null) {
            target.removeChild(target.getFirstChild());
        }
        for (Node c = source.getFirstChild(); c != null; c = c.getNextSibling()) {
            target.appendChild(target.getOwnerDocument().importNode(c, true));
        }
        org.w3c.dom.NamedNodeMap attrs = source.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            target.setAttribute(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
        }
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    static long parseLong(String s, long dflt) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static final java.security.SecureRandom RND = new java.security.SecureRandom();

    /** An 8-character [0-9A-Z] id, Designer's shape. */
    public static String mintId() {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(RND.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // The inner class reaches the operation's options through these (it is static so the linkage helpers can be tested alone).
    private static final ThreadLocal<Map<String, String>> this_answers = ThreadLocal.withInitial(Map::of);
    private static final ThreadLocal<Boolean> this_wizard = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Reads a {@code name=value} answers file (blank lines and {@code #} comments ignored; no escapes). */
    public static Map<String, String> readAnswers(Path file) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        if (file == null) {
            return m;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int i = t.indexOf('=');
            if (i > 0) {
                m.put(t.substring(0, i).strip(), t.substring(i + 1).strip());
            }
        }
        return m;
    }

    /**
     * The jar to install: {@code --jar}, or {@code --catalog DIR --package SHORT[_ver]} resolved against the
     * catalog's {@code jars/<SHORT>/<SHORT>_<ver>.jar} layout (newest version when none is given).
     */
    public static List<Path> jarsOf(String jars, String catalog, String pkgs) {
        List<Path> out = new ArrayList<>();
        if (jars != null && !jars.isBlank()) {
            for (String j : jars.split(",")) {
                if (!j.isBlank()) {
                    out.add(Path.of(j.strip()));
                }
            }
        }
        if (pkgs != null && !pkgs.isBlank()) {
            for (String p : pkgs.split(",")) {
                if (!p.isBlank()) {
                    out.add(jarOf(null, catalog, p.strip()));
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("give --jar <file[,file…]>, or --catalog <dir> and --package SHORT[_version][,…]");
        }
        return out;
    }

    public static Path jarOf(String jar, String catalog, String pkg) {
        if (jar != null && !jar.isBlank()) {
            return Path.of(jar);
        }
        if (catalog == null || catalog.isBlank() || pkg == null || pkg.isBlank()) {
            throw new IllegalArgumentException("give --jar <file>, or --catalog <dir> and --package SHORT[_version]");
        }
        String shortName = pkg;
        String version = null;
        int i = pkg.indexOf('_');
        if (i > 0) {
            shortName = pkg.substring(0, i);
            version = pkg.substring(i + 1);
        }
        Path dir = Path.of(catalog).resolve("jars").resolve(shortName);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("no package " + shortName + " in catalog " + catalog + " (package.fetch it first)");
        }
        if (version != null) {
            Path f = dir.resolve(shortName + "_" + version + ".jar");
            if (!Files.exists(f)) {
                throw new IllegalArgumentException("no " + f.getFileName() + " in the catalog");
            }
            return f;
        }
        final String sn = shortName;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            final String prefix = sn + "_";
            return s.filter(f -> f.getFileName().toString().startsWith(prefix) && f.toString().endsWith(".jar"))
                .max((a, b) -> compareVersions(versionOf(a, prefix), versionOf(b, prefix)))
                .orElseThrow(() -> new IllegalArgumentException("no versions of " + sn + " in the catalog"));
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static String versionOf(Path f, String prefix) {
        String n = f.getFileName().toString();
        return n.substring(prefix.length(), n.length() - 4);
    }

    /** {@code M.m.r[.build]} compared numerically, missing parts = 0. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 4; i++) {
            long p = i < x.length ? parseLong(x[i], 0) : 0;
            long q = i < y.length ? parseLong(y[i], 0) : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    /** Sets the per-run options the inner install reads; called by {@link #apply}. */
    private void bind() {
        this_answers.set(answers);
        this_wizard.set(propertyWizard);
    }
}
