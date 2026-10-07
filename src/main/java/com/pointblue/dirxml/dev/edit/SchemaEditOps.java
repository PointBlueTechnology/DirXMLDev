package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.VaultSchema;
import java.util.ArrayList;
import java.util.List;

/**
 * Schema defined in the tree, as Designer lets you define it in the project: custom attributes and
 * classes in {@code schema/vault.xml}, pushed to a vault by {@code vault.deploy-schema}. The vault's
 * own definitions are not edited here (a refresh would undo it); only custom ones change or go.
 */
public final class SchemaEditOps {

    private SchemaEditOps() {
    }

    static VaultSchema schemaOrRefuse(DriverSet ds) throws Operation.Refusal {
        if (ds.schema == null) {
            throw new Operation.Refusal("the tree has no schema yet: vault.schema --env <name> reads the vault's, or import a Designer project");
        }
        return ds.schema;
    }

    static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) {
            for (String p : s.split(",")) {
                if (!p.trim().isEmpty()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    /** {@code schema.add-attribute}: a custom attribute. */
    public static final class AddAttribute implements Operation {
        private final String name;
        private final String ldap;
        private final String syntax;
        private final String oid;
        private final boolean single;

        public AddAttribute(String name, String ldap, String syntax, String oid, boolean single) {
            this.name = name;
            this.ldap = ldap;
            this.syntax = syntax;
            this.oid = oid;
            this.single = single;
        }

        @Override
        public String name() {
            return "schema.add-attribute";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            VaultSchema s = schemaOrRefuse(ds);
            if (name == null || name.isBlank()) {
                throw new Refusal("a name is required");
            }
            if (s.attribute(name) != null || (ldap != null && s.attribute(ldap) != null)) {
                throw new Refusal("attribute '" + name + "' exists already");
            }
            VaultSchema.AttrDef a = new VaultSchema.AttrDef(name.trim(), ldap == null || ldap.isBlank() ? name.trim() : ldap.trim());
            a.syntax = syntax == null || syntax.isBlank() ? "1.3.6.1.4.1.1466.115.121.1.15" : syntax.trim();
            a.oid = oid == null || oid.isBlank() ? null : oid.trim();
            a.single = single;
            a.custom = true;
            s.add(a);
            tx.note("schema: custom attribute " + a.name + " (" + a.ldap + ", " + a.syntax + (single ? ", single" : "") + ")");
        }
    }

    /** {@code schema.add-class}: a custom class. */
    public static final class AddClass implements Operation {
        private final String name;
        private final String ldap;
        private final String kind;
        private final String oid;
        private final List<String> superclasses;
        private final List<String> mandatory;
        private final List<String> optional;
        private final List<String> containment;
        private final List<String> naming;

        public AddClass(String name, String ldap, String kind, String oid, String superclasses, String mandatory, String optional, String containment, String naming) {
            this.name = name;
            this.ldap = ldap;
            this.kind = kind;
            this.oid = oid;
            this.superclasses = csv(superclasses);
            this.mandatory = csv(mandatory);
            this.optional = csv(optional);
            this.containment = csv(containment);
            this.naming = csv(naming);
        }

        @Override
        public String name() {
            return "schema.add-class";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            VaultSchema s = schemaOrRefuse(ds);
            if (name == null || name.isBlank()) {
                throw new Refusal("a name is required");
            }
            if (s.classDef(name) != null || (ldap != null && s.classDef(ldap) != null)) {
                throw new Refusal("class '" + name + "' exists already");
            }
            VaultSchema.ClassDef c = new VaultSchema.ClassDef(name.trim(), ldap == null || ldap.isBlank() ? name.trim() : ldap.trim());
            c.kind = kind == null || kind.isBlank() ? "structural" : kind.trim().toLowerCase(java.util.Locale.ROOT);
            if (!List.of("structural", "auxiliary", "abstract").contains(c.kind)) {
                throw new Refusal("kind must be structural, auxiliary or abstract, not '" + kind + "'");
            }
            c.oid = oid == null || oid.isBlank() ? null : oid.trim();
            for (String sup : superclasses.isEmpty() ? List.of("Top") : superclasses) {
                if (s.classDef(sup) == null) {
                    throw new Refusal("superclass '" + sup + "' is not in the schema");
                }
                c.superclasses.add(s.classDef(sup).name);
            }
            for (String a : mandatory) {
                c.mandatory.add(attrName(s, a));
            }
            for (String a : optional) {
                c.optional.add(attrName(s, a));
            }
            c.containment.addAll(containment);
            c.naming.addAll(naming);
            c.custom = true;
            s.add(c);
            tx.note("schema: custom class " + c.name + " (" + c.kind + ", sup " + String.join(", ", c.superclasses) + ")");
        }
    }

    static String attrName(VaultSchema s, String a) throws Operation.Refusal {
        VaultSchema.AttrDef def = s.attribute(a);
        if (def == null) {
            throw new Operation.Refusal("attribute '" + a + "' is not in the schema (schema.add-attribute first)");
        }
        return def.name;
    }

    /** {@code schema.set-attribute} / {@code schema.set-class}: change a custom definition. */
    public static final class Set implements Operation {
        private final String name;
        private final String ldap;
        private final String syntax;
        private final String oid;
        private final Boolean single;
        private final String kind;
        private final String superclasses;
        private final String mandatory;
        private final String optional;
        private final String containment;
        private final String naming;

        public Set(String name, String ldap, String syntax, String oid, Boolean single, String kind, String superclasses, String mandatory, String optional, String containment, String naming) {
            this.name = name;
            this.ldap = ldap;
            this.syntax = syntax;
            this.oid = oid;
            this.single = single;
            this.kind = kind;
            this.superclasses = superclasses;
            this.mandatory = mandatory;
            this.optional = optional;
            this.containment = containment;
            this.naming = naming;
        }

        @Override
        public String name() {
            return "schema.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            VaultSchema s = schemaOrRefuse(ds);
            VaultSchema.AttrDef a = s.attribute(name);
            VaultSchema.ClassDef c = a == null ? s.classDef(name) : null;
            if (a == null && c == null) {
                throw new Refusal("no attribute or class '" + name + "' in the schema");
            }
            if (a != null ? !a.custom : !c.custom) {
                throw new Refusal("'" + name + "' is the vault's; only definitions the tree made (custom) change here");
            }
            if (a != null) {
                if (ldap != null && !ldap.isBlank()) {
                    a.ldap = ldap.trim();
                }
                if (syntax != null && !syntax.isBlank()) {
                    a.syntax = syntax.trim();
                }
                if (oid != null) {
                    a.oid = oid.isBlank() ? null : oid.trim();
                }
                if (single != null) {
                    a.single = single;
                }
                tx.note("schema: attribute " + a.name + " changed");
                return;
            }
            if (ldap != null && !ldap.isBlank()) {
                c.ldap = ldap.trim();
            }
            if (oid != null) {
                c.oid = oid.isBlank() ? null : oid.trim();
            }
            if (kind != null && !kind.isBlank()) {
                c.kind = kind.trim().toLowerCase(java.util.Locale.ROOT);
            }
            if (superclasses != null) {
                c.superclasses.clear();
                for (String sup : csv(superclasses)) {
                    if (s.classDef(sup) == null) {
                        throw new Refusal("superclass '" + sup + "' is not in the schema");
                    }
                    c.superclasses.add(s.classDef(sup).name);
                }
            }
            if (mandatory != null) {
                c.mandatory.clear();
                for (String x : csv(mandatory)) {
                    c.mandatory.add(attrName(s, x));
                }
            }
            if (optional != null) {
                c.optional.clear();
                for (String x : csv(optional)) {
                    c.optional.add(attrName(s, x));
                }
            }
            if (containment != null) {
                c.containment.clear();
                c.containment.addAll(csv(containment));
            }
            if (naming != null) {
                c.naming.clear();
                c.naming.addAll(csv(naming));
            }
            tx.note("schema: class " + c.name + " changed");
        }
    }

    /** {@code schema.remove}: a custom definition leaves the tree (the vault keeps whatever was pushed). */
    public static final class Remove implements Operation {
        private final String name;

        public Remove(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return "schema.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Refusal {
            VaultSchema s = schemaOrRefuse(ds);
            VaultSchema.AttrDef a = s.attribute(name);
            VaultSchema.ClassDef c = a == null ? s.classDef(name) : null;
            if (a == null && c == null) {
                throw new Refusal("no attribute or class '" + name + "' in the schema");
            }
            if (a != null ? !a.custom : !c.custom) {
                throw new Refusal("'" + name + "' is the vault's; it is not removed from the tree (and never from the vault)");
            }
            if (a != null) {
                for (VaultSchema.ClassDef k : s.classes) {
                    if (k.mandatory.contains(a.name) || k.optional.contains(a.name)) {
                        throw new Refusal("attribute '" + a.name + "' is named by class " + k.name);
                    }
                }
            } else {
                for (VaultSchema.ClassDef k : s.classes) {
                    if (k.superclasses.contains(c.name)) {
                        throw new Refusal("class '" + c.name + "' is a superclass of " + k.name);
                    }
                }
            }
            s.remove(a != null ? a.name : c.name);
            tx.note("schema: custom " + (a != null ? "attribute " + a.name : "class " + c.name) + " removed from the tree");
        }
    }
}
