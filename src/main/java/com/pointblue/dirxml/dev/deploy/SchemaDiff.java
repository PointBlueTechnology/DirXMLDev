package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.clone.Schema;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.VaultSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Two schemas side by side, nothing written (docs/schema.md): the tree's {@code schema/vault.xml}
 * against an environment's live {@code cn=schema}, or two environments' live schemas. Definitions
 * match by NDS name, else by LDAP name, case-insensitively; a definition in one side only, or in
 * both with a different syntax, flag, superclass or attribute list, is a change.
 */
public final class SchemaDiff {

    /** One difference. */
    public static final class Change {
        public final String kind;      // attribute | class
        public final String name;
        public final String where;     // left-only | right-only | differs
        public final List<String> fields = new ArrayList<>();   // for differs: "syntax: a → b"
        public boolean custom;         // defined in the tree, not yet pushed (left = model only)

        Change(String kind, String name, String where) {
            this.kind = kind;
            this.name = name;
            this.where = where;
        }
    }

    public static final class Result {
        public final String left;
        public final String right;
        public final List<Change> changes = new ArrayList<>();
        public int attributesCompared;
        public int classesCompared;

        Result(String left, String right) {
            this.left = left;
            this.right = right;
        }

        public boolean inSync() {
            return changes.isEmpty();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("left", left);
            m.put("right", right);
            m.put("inSync", inSync());
            m.put("attributesCompared", attributesCompared);
            m.put("classesCompared", classesCompared);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Change c : changes) {
                counts.merge(c.kind + ":" + c.where, 1, Integer::sum);
            }
            m.put("counts", counts);
            List<Object> list = new ArrayList<>();
            for (Change c : changes) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("kind", c.kind);
                cm.put("name", c.name);
                cm.put("where", c.where);
                cm.put("fields", c.fields);
                cm.put("custom", c.custom);
                list.add(cm);
            }
            m.put("changes", list);
            return m;
        }

        public String json() {
            return Json.pretty(toMap());
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(left).append(" vs ").append(right).append(": ")
              .append(inSync() ? "in sync" : changes.size() + " difference(s)")
              .append(" (").append(attributesCompared).append(" attributes, ").append(classesCompared).append(" classes compared)\n");
            for (Change c : changes) {
                String mark = c.where.equals("left-only") ? "<" : c.where.equals("right-only") ? ">" : "~";
                sb.append("  ").append(mark).append(' ').append(c.kind).append(' ').append(c.name);
                if (c.custom) {
                    sb.append(" (custom, not pushed)");
                }
                sb.append('\n');
                for (String f : c.fields) {
                    sb.append("      ").append(f).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private SchemaDiff() {
    }

    /** The environment's live schema, read from {@code cn=schema}. */
    public static VaultSchema live(Environments.Environment env) throws IOException {
        try (Vault v = Vault.connect(env.vaultConfig())) {
            Vault.Entry e = v.read("cn=schema", "attributeTypes", "objectClasses");
            if (e == null) {
                throw new IOException("cn=schema is not readable on " + env.url);
            }
            return VaultSchema.fromLdap(Schema.of(e.strings("attributeTypes"), e.strings("objectClasses")), env.url);
        }
    }

    /** The tree's {@code schema/vault.xml} (left) against the environment's live schema (right). */
    public static Result modelVsLive(Path tree, Environments.Environment env) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        if (ds.schema == null) {
            throw new IOException("the tree has no schema/vault.xml yet: run vault.schema --env " + env.name + " first");
        }
        return of(ds.schema, live(env), "model", env.name);
    }

    /** Two environments' live schemas. */
    public static Result liveVsLive(Environments.Environment a, Environments.Environment b) throws IOException {
        return of(live(a), live(b), a.name, b.name);
    }

    public static Result of(VaultSchema left, VaultSchema right, String leftName, String rightName) {
        Result r = new Result(leftName, rightName);
        Map<String, VaultSchema.AttrDef> ra = new LinkedHashMap<>();
        for (VaultSchema.AttrDef a : right.attributes) {
            ra.put(key(a.name), a);
            if (a.ldap != null) {
                ra.putIfAbsent(key(a.ldap), a);
            }
        }
        java.util.Set<VaultSchema.AttrDef> matched = new java.util.HashSet<>();
        for (VaultSchema.AttrDef a : left.attributes) {
            VaultSchema.AttrDef b = ra.get(key(a.name));
            if (b == null && a.ldap != null) {
                b = ra.get(key(a.ldap));
            }
            if (b == null) {
                Change c = new Change("attribute", a.name, "left-only");
                c.custom = a.custom;
                r.changes.add(c);
                continue;
            }
            matched.add(b);
            r.attributesCompared++;
            List<String> fields = new ArrayList<>();
            field(fields, "ldap", a.ldap, b.ldap);
            field(fields, "oid", a.oid, b.oid);
            field(fields, "syntax", a.syntax, b.syntax);
            field(fields, "single-valued", a.single, b.single);
            field(fields, "no-user-modification", a.noUserModification, b.noUserModification);
            if (!fields.isEmpty()) {
                Change c = new Change("attribute", a.name, "differs");
                c.fields.addAll(fields);
                r.changes.add(c);
            }
        }
        for (VaultSchema.AttrDef b : right.attributes) {
            if (!matched.contains(b)) {
                r.changes.add(new Change("attribute", b.name, "right-only"));
            }
        }
        Map<String, VaultSchema.ClassDef> rc = new LinkedHashMap<>();
        for (VaultSchema.ClassDef c : right.classes) {
            rc.put(key(c.name), c);
            if (c.ldap != null) {
                rc.putIfAbsent(key(c.ldap), c);
            }
        }
        java.util.Set<VaultSchema.ClassDef> matchedC = new java.util.HashSet<>();
        for (VaultSchema.ClassDef a : left.classes) {
            VaultSchema.ClassDef b = rc.get(key(a.name));
            if (b == null && a.ldap != null) {
                b = rc.get(key(a.ldap));
            }
            if (b == null) {
                Change c = new Change("class", a.name, "left-only");
                c.custom = a.custom;
                r.changes.add(c);
                continue;
            }
            matchedC.add(b);
            r.classesCompared++;
            List<String> fields = new ArrayList<>();
            field(fields, "ldap", a.ldap, b.ldap);
            field(fields, "oid", a.oid, b.oid);
            field(fields, "kind", a.kind, b.kind);
            field(fields, "container", a.container, b.container);
            set(fields, "superclasses", a.superclasses, b.superclasses);
            set(fields, "mandatory", a.mandatory, b.mandatory);
            set(fields, "optional", a.optional, b.optional);
            set(fields, "containment", a.containment, b.containment);
            set(fields, "naming", a.naming, b.naming);
            if (!fields.isEmpty()) {
                Change c = new Change("class", a.name, "differs");
                c.fields.addAll(fields);
                r.changes.add(c);
            }
        }
        for (VaultSchema.ClassDef b : right.classes) {
            if (!matchedC.contains(b)) {
                r.changes.add(new Change("class", b.name, "right-only"));
            }
        }
        return r;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static void field(List<String> out, String what, Object a, Object b) {
        String sa = a == null ? "" : String.valueOf(a).trim();
        String sb = b == null ? "" : String.valueOf(b).trim();
        if (sa.isEmpty() && sb.isEmpty()) {
            return;   // neither side says: nothing to compare (Designer's copy may lack an OID)
        }
        if (!Objects.equals(sa, sb)) {
            out.add(what + ": " + (sa.isEmpty() ? "—" : sa) + " → " + (sb.isEmpty() ? "—" : sb));
        }
    }

    /** Lists compared as sets of names, case-insensitively; the names only one side has are shown. */
    private static void set(List<String> out, String what, List<String> a, List<String> b) {
        TreeSet<String> sa = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        TreeSet<String> sb = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sa.addAll(a);
        sb.addAll(b);
        List<String> onlyA = new ArrayList<>();
        List<String> onlyB = new ArrayList<>();
        for (String s : sa) {
            if (!sb.contains(s)) {
                onlyA.add(s);
            }
        }
        for (String s : sb) {
            if (!sa.contains(s)) {
                onlyB.add(s);
            }
        }
        if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
            out.add(what + ": " + (onlyA.isEmpty() ? "" : "left has " + String.join(", ", onlyA)) + (onlyA.isEmpty() || onlyB.isEmpty() ? "" : "; ") + (onlyB.isEmpty() ? "" : "right has " + String.join(", ", onlyB)));
        }
    }
}
