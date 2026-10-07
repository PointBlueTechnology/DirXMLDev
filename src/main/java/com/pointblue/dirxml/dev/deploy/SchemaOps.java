package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.clone.Schema;
import com.pointblue.dirxml.dev.edit.Operation;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.VaultSchema;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * The schema between the tree and a vault — only when asked, never as a side effect:
 * <ul>
 *   <li>{@link #refreshVault}: {@code cn=schema} into {@code schema/vault.xml}; the tree's own
 *       (custom) definitions are kept, dropping the mark on any the vault now has.</li>
 *   <li>{@link #refreshApp}: the application schema the shim reports, into the driver's
 *       {@code app-schema.xml} ({@code DriverGetSchema}: the engine asks the shim).</li>
 *   <li>{@link #deploySchema}: the tree's custom attributes and classes the vault lacks, written
 *       as LDAP definitions to {@code cn=schema} — attributes first, then classes. Nothing is ever
 *       removed from a vault's schema.</li>
 * </ul>
 */
public final class SchemaOps {

    public static final class Result {
        public boolean ok = true;
        public String refusal;
        public final List<String> done = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();
        public final Map<String, Object> summary = new LinkedHashMap<>();

        public String text() {
            StringBuilder sb = new StringBuilder();
            if (refusal != null) {
                sb.append("REFUSED — ").append(refusal).append('\n');
            }
            for (Map.Entry<String, Object> e : summary.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            for (String s : done) {
                sb.append("  + ").append(s).append('\n');
            }
            for (String s : skipped) {
                sb.append("  = ").append(s).append('\n');
            }
            for (String s : failures) {
                sb.append("  ! ").append(s).append('\n');
            }
            return sb.toString();
        }

        public String json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ok);
            m.put("refusal", refusal);
            m.putAll(summary);
            m.put("done", done);
            m.put("skipped", skipped);
            m.put("failures", failures);
            return com.pointblue.dirxml.dev.json.Json.pretty(m);
        }
    }

    private SchemaOps() {
    }

    /** Read {@code cn=schema} of {@code env} into the tree's {@code schema/vault.xml}, keeping the tree's custom definitions. */
    public static Result refreshVault(Path tree, Environments.Environment env) throws IOException {
        Result r = new Result();
        Schema live;
        try (Vault v = Vault.connect(env.vaultConfig())) {
            Vault.Entry e = v.read("cn=schema", "attributeTypes", "objectClasses");
            if (e == null) {
                r.ok = false;
                r.refusal = "cn=schema is not readable on " + env.url;
                return r;
            }
            live = Schema.of(e.strings("attributeTypes"), e.strings("objectClasses"));
        }
        VaultSchema fresh = VaultSchema.fromLdap(live, env.url);
        DriverSet ds = AsCodeReader.read(tree);
        int kept = 0;
        if (ds.schema != null) {
            for (VaultSchema.AttrDef a : ds.schema.customAttributes()) {
                if (fresh.attribute(a.name) == null && fresh.attribute(a.ldap) == null) {
                    fresh.add(a);
                    kept++;
                }
            }
            for (VaultSchema.ClassDef c : ds.schema.customClasses()) {
                if (fresh.classDef(c.name) == null && fresh.classDef(c.ldap) == null) {
                    fresh.add(c);
                    kept++;
                }
            }
        }
        com.pointblue.dirxml.dev.edit.Result tr = Transaction.open(tree).run(new SetSchema(fresh), false, false);
        r.ok = tr.ok();
        r.refusal = tr.refusal;
        r.summary.put("source", env.url);
        r.summary.put("attributes", fresh.attributes.size());
        r.summary.put("classes", fresh.classes.size());
        r.summary.put("customKept", kept);
        r.summary.put("file", "schema/vault.xml");
        return r;
    }

    /** Ask the engine for {@code driver}'s application schema and keep it in the tree as {@code drivers/<d>/app-schema.xml}. */
    public static Result refreshApp(Path tree, Environments.Environment env, String driver) throws IOException {
        Result r = new Result();
        DriverSet ds = AsCodeReader.read(tree);
        Driver d = ds.driver(driver);
        if (d == null) {
            r.ok = false;
            r.refusal = "no driver '" + driver + "' in the tree";
            return r;
        }
        String dn = VaultMapping.driverDn(env.driverSetDn, driver);
        String xml;
        String how;
        try (Vault v = Vault.connect(env.vaultConfig())) {
            // the engine asks the shim only of a stopped driver (it starts the shim for the question), as iManager does
            String state = Vault.stateName(v.driverState(dn));
            if ("running".equals(state) || "starting".equals(state)) {
                r.ok = false;
                r.refusal = "driver '" + driver + "' is " + state + ": the engine asks the shim for its schema only while the driver is stopped (driver.stop first)";
                return r;
            }
            String before = v.applicationSchema(dn);
            String after;
            try {
                after = v.driverGetSchema(dn);
                how = "asked the shim";
            } catch (RuntimeException e) {
                after = before;
                how = "the engine refused to ask the shim (" + e.getMessage() + "); the last schema it stored";
            }
            xml = after;
            r.summary.put("how", how + (after.equals(before) && !before.isEmpty() ? " (unchanged since the last refresh)" : ""));
        }
        if (xml == null || xml.isBlank()) {
            r.ok = false;
            r.refusal = "no application schema for " + dn + ": the engine stored none (can the driver start and the shim connect? the engine writes DirXML-ApplicationSchema after asking the shim)";
            return r;
        }
        Element root;
        try {
            root = CanonicalXml.parse(xml).getDocumentElement();
        } catch (RuntimeException e) {
            r.ok = false;
            r.refusal = "the engine's reply is not XML: " + e.getMessage();
            return r;
        }
        Element schemaDef = find(root, "schema-def");
        if (schemaDef == null) {
            // a <status> instead: the shim refused or the driver could not run
            Element status = find(root, "status");
            r.ok = false;
            r.refusal = status != null ? "the engine reported: " + status.getAttribute("level") + " " + status.getTextContent().trim()
                : "no <schema-def> in the engine's reply";
            return r;
        }
        com.pointblue.dirxml.dev.edit.Result tr = Transaction.open(tree).run(new SetAppSchema(driver, schemaDef), false, false);
        r.ok = tr.ok();
        r.refusal = tr.refusal;
        int classes = 0;
        int attrs = 0;
        for (Element c : children(schemaDef, "class-def")) {
            classes++;
            attrs += children(c, "attr-def").size();
        }
        r.summary.put("driver", driver);
        r.summary.put("classes", classes);
        r.summary.put("attributes", attrs);
        r.summary.put("file", "drivers/" + com.pointblue.dirxml.dev.ascode.AsCodeWriter.fileSafe(driver) + "/" + Driver.APP_SCHEMA + ".xml");
        return r;
    }

    /**
     * Write the tree's custom attributes and classes the vault lacks to {@code cn=schema}. A plan
     * without {@code yes}; a production environment needs {@code confirm} = its name as every vault
     * write does. Attributes first (a class may name them), classes in superclass order.
     */
    public static Result deploySchema(Path tree, Environments.Environment env, boolean yes, String confirm) throws IOException {
        Result r = new Result();
        DriverSet ds = AsCodeReader.read(tree);
        if (ds.schema == null) {
            r.ok = false;
            r.refusal = "the tree has no schema (vault.schema --env " + env.name + " first)";
            return r;
        }
        List<VaultSchema.AttrDef> attrs = ds.schema.customAttributes();
        List<VaultSchema.ClassDef> classes = ds.schema.customClasses();
        if (attrs.isEmpty() && classes.isEmpty()) {
            r.summary.put("custom", 0);
            r.skipped.add("the tree defines no custom schema");
            return r;
        }
        for (VaultSchema.AttrDef a : attrs) {
            if (a.oid == null || a.oid.isBlank()) {
                r.ok = false;
                r.refusal = "custom attribute '" + a.name + "' has no OID; eDirectory needs one over LDAP (schema.set-attribute --name … --oid …)";
                return r;
            }
        }
        for (VaultSchema.ClassDef c : classes) {
            if (c.oid == null || c.oid.isBlank()) {
                r.ok = false;
                r.refusal = "custom class '" + c.name + "' has no OID; eDirectory needs one over LDAP (schema.set-class --name … --oid …)";
                return r;
            }
        }
        if (env.tier == Environments.Tier.PRD && (confirm == null || !confirm.equals(env.name))) {
            r.ok = false;
            r.refusal = "a production schema change needs --confirm " + env.name;
            return r;
        }
        try (Vault v = Vault.connect(env.vaultConfig())) {
            Vault.Entry e = v.read("cn=schema", "attributeTypes", "objectClasses");
            Schema live = e == null ? Schema.of(List.of(), List.of()) : Schema.of(e.strings("attributeTypes"), e.strings("objectClasses"));
            List<String[]> plan = new ArrayList<>();   // kind, name, definition
            for (VaultSchema.AttrDef a : attrs) {
                if (live.attribute(a.ldap) != null || live.attribute(a.name) != null) {
                    r.skipped.add("attribute " + a.name + " (the vault has it)");
                } else {
                    plan.add(new String[] {"attributeTypes", a.name, ds.schema.ldapDefinition(a)});
                }
            }
            for (VaultSchema.ClassDef c : ordered(classes, ds.schema)) {
                if (live.objectClass(c.ldap) != null || live.objectClass(c.name) != null) {
                    r.skipped.add("class " + c.name + " (the vault has it)");
                } else {
                    plan.add(new String[] {"objectClasses", c.name, ds.schema.ldapDefinition(c)});
                }
            }
            r.summary.put("environment", env.name);
            r.summary.put("toAdd", plan.size());
            if (!yes) {
                for (String[] p : plan) {
                    r.done.add("(plan) " + p[0] + " " + p[1] + ": " + p[2]);
                }
                r.summary.put("dryRun", true);
                return r;
            }
            for (String[] p : plan) {
                try {
                    v.addValues("cn=schema", p[0], List.of(p[2].getBytes(StandardCharsets.UTF_8)));
                    r.done.add(p[0] + " " + p[1]);
                } catch (RuntimeException ex) {
                    r.failures.add(p[0] + " " + p[1] + ": " + ex.getMessage());
                    r.ok = false;
                }
            }
        }
        return r;
    }

    /** Superclasses before the classes that name them (custom ones only; the vault's are there already). */
    private static List<VaultSchema.ClassDef> ordered(List<VaultSchema.ClassDef> classes, VaultSchema schema) {
        List<VaultSchema.ClassDef> out = new ArrayList<>();
        List<VaultSchema.ClassDef> pending = new ArrayList<>(classes);
        for (int round = 0; round < 20 && !pending.isEmpty(); round++) {
            List<VaultSchema.ClassDef> next = new ArrayList<>();
            for (VaultSchema.ClassDef c : pending) {
                boolean ready = true;
                for (String s : c.superclasses) {
                    VaultSchema.ClassDef sup = schema.classDef(s);
                    if (sup != null && sup.custom && !out.contains(sup)) {
                        ready = false;
                    }
                }
                (ready ? out : next).add(c);
            }
            pending = next;
        }
        out.addAll(pending);
        return out;
    }

    /** The tree-side half of a refresh: replace the schema. */
    public static final class SetSchema implements Operation {
        private final VaultSchema schema;

        public SetSchema(VaultSchema schema) {
            this.schema = schema;
        }

        @Override
        public String name() {
            return "schema.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) {
            ds.schema = schema;
            tx.note("vault schema: " + schema.attributes.size() + " attributes, " + schema.classes.size() + " classes from " + schema.meta.get("source"));
        }
    }

    /** The tree-side half of an application-schema refresh. */
    public static final class SetAppSchema implements Operation {
        private final String driver;
        private final Element schemaDef;

        public SetAppSchema(String driver, Element schemaDef) {
            this.driver = driver;
            this.schemaDef = schemaDef;
        }

        @Override
        public String name() {
            return "driver.app-schema.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            Driver d = ds.driver(driver);
            if (d == null) {
                throw new Refusal("no driver '" + driver + "'");
            }
            d.config.put(Driver.APP_SCHEMA, (Element) CanonicalXml.parse(CanonicalXml.serialize(schemaDef)).getDocumentElement());
            tx.note("application schema of " + driver + " from the shim");
        }
    }

    public static Element find(Element root, String name) {
        if (name.equals(root.getNodeName())) {
            return root;
        }
        for (org.w3c.dom.Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                Element f = find((Element) n, name);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    public static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }
}
