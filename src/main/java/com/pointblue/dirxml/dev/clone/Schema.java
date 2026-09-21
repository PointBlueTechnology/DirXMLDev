package com.pointblue.dirxml.dev.clone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An LDAP subschema as eDirectory publishes it on {@code cn=schema}: every
 * {@code attributeTypes} and {@code objectClasses} definition, parsed just far enough for
 * a clone — names, OID, {@code SUP}, {@code MUST}/{@code MAY}, {@code SYNTAX}, the
 * operational flags — and kept verbatim, because the definition is what gets written to
 * the target's {@code cn=schema} (NetIQ's {@code X-NDS_*} flags included).
 *
 * <p>Syntaxes that carry a DN, which decide what a clone holds back until every object
 * exists (docs/vault-clone.md §4): DN ({@code 1.3.6.1.4.1.1466.115.121.1.12}), NetIQ Path
 * ({@code 2.16.840.1.113719.1.1.5.1.15} — {@code DirXML-Associations},
 * {@code DirXML-EntitlementRef}, {@code nrfAssignedRoles}: {@code dn#n#value}), Typed Name
 * ({@code …5.1.25} — {@code DirXML-Policies}: {@code dn#n#n}) and Object ACL
 * ({@code …5.1.17} — {@code ACL}: {@code attr#dn#rights}, written last of all).
 */
public final class Schema {

    public static final String SYNTAX_DN = "1.3.6.1.4.1.1466.115.121.1.12";
    public static final String SYNTAX_PATH = "2.16.840.1.113719.1.1.5.1.15";
    public static final String SYNTAX_TYPED_NAME = "2.16.840.1.113719.1.1.5.1.25";
    public static final String SYNTAX_OBJECT_ACL = "2.16.840.1.113719.1.1.5.1.17";
    public static final String SYNTAX_BACK_LINK = "2.16.840.1.113719.1.1.5.1.23";
    /** Syntaxes whose values eDirectory hands over as bytes, not text. */
    private static final Set<String> BINARY_SYNTAXES = Set.of(
        "1.3.6.1.4.1.1466.115.121.1.40",   // octet string
        "1.3.6.1.4.1.1466.115.121.1.5",    // binary
        "1.3.6.1.4.1.1466.115.121.1.8",    // certificate
        "1.3.6.1.4.1.1466.115.121.1.9",    // certificate list
        "1.3.6.1.4.1.1466.115.121.1.10",   // certificate pair
        "1.3.6.1.4.1.1466.115.121.1.28",   // JPEG
        "2.16.840.1.113719.1.1.5.1.0",     // NetIQ stream
        "2.16.840.1.113719.1.1.5.1.12");   // NetIQ net address (networkAddress: type#bytes)

    /** One definition, attribute type or object class. */
    public static final class Def {
        public final boolean attribute;
        public final String oid;
        public final List<String> names;
        public final String sup;              // first SUP for attributes; classes may have several: see sups
        public final List<String> sups;
        public final Set<String> must;
        public final Set<String> may;
        public final String syntax;           // attributes: the OID without {len}
        public final boolean noUserModification;
        public final boolean operational;     // USAGE directoryOperation / dSAOperation / distributedOperation
        public final boolean singleValue;
        /** {@code X-NDS_NEVER_SYNC}: the value never replicates — each server holds its own (IDM's server-specific driver settings). */
        public final boolean neverSync;
        public final String raw;

        Def(boolean attribute, String raw) {
            this.attribute = attribute;
            this.raw = raw.trim();
            this.oid = firstToken(this.raw);
            this.names = names(this.raw);
            this.sups = list(this.raw, "SUP");
            this.sup = sups.isEmpty() ? null : sups.get(0);
            this.must = new LinkedHashSet<>(list(this.raw, "MUST"));
            this.may = new LinkedHashSet<>(list(this.raw, "MAY"));
            String syn = single(this.raw, "SYNTAX");
            this.syntax = syn == null ? null : syn.replaceAll("\\{\\d+}$", "");
            this.noUserModification = this.raw.contains(" NO-USER-MODIFICATION");
            this.operational = this.raw.matches("(?s).* USAGE (directoryOperation|dSAOperation|distributedOperation)\\b.*");
            this.singleValue = this.raw.contains(" SINGLE-VALUE");
            this.neverSync = this.raw.contains("X-NDS_NEVER_SYNC '1'");
        }

        public String name() {
            return names.isEmpty() ? oid : names.get(0);
        }

        /**
         * What the definition means, independent of how eDirectory rendered it: the OID, the
         * names, SUP, the syntax without its length bound, the flags that change behaviour
         * (SINGLE-VALUE, NO-USER-MODIFICATION, USAGE, the class kind), MUST and MAY as sets.
         * {@code X-NDS_*} rendering flags, bounds and spacing do not count.
         */
        public String normalized() {
            StringBuilder sb = new StringBuilder(oid);
            sb.append(" names=").append(new TreeSet<>(lower(names)));
            sb.append(" sup=").append(new TreeSet<>(lower(sups)));
            if (attribute) {
                sb.append(" syntax=").append(syntax);
                sb.append(singleValue ? " single" : "").append(noUserModification ? " no-user-mod" : "").append(operational ? " operational" : "");
            } else {
                sb.append(raw.contains(" ABSTRACT") ? " abstract" : raw.contains(" AUXILIARY") ? " auxiliary" : " structural");
                sb.append(" must=").append(new TreeSet<>(lower(must)));
                sb.append(" may=").append(new TreeSet<>(lower(may)));
            }
            return sb.toString();
        }

        private static List<String> lower(java.util.Collection<String> in) {
            List<String> out = new ArrayList<>();
            for (String s : in) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
            return out;
        }

        @Override
        public String toString() {
            return (attribute ? "attributeType " : "objectClass ") + name();
        }
    }

    private final Map<String, Def> attributes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, Def> classes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<Def> attributeOrder = new ArrayList<>();
    private final List<Def> classOrder = new ArrayList<>();

    public static Schema of(List<String> attributeTypes, List<String> objectClasses) {
        Schema s = new Schema();
        for (String raw : attributeTypes) {
            Def d = new Def(true, raw);
            s.attributeOrder.add(d);
            for (String n : d.names) {
                s.attributes.putIfAbsent(n, d);
            }
            s.attributes.putIfAbsent(d.oid, d);
        }
        for (String raw : objectClasses) {
            Def d = new Def(false, raw);
            s.classOrder.add(d);
            for (String n : d.names) {
                s.classes.putIfAbsent(n, d);
            }
            s.classes.putIfAbsent(d.oid, d);
        }
        return s;
    }

    public Def attribute(String name) {
        return attributes.get(name);
    }

    public Def objectClass(String name) {
        return classes.get(name);
    }

    public List<Def> attributes() {
        return Collections.unmodifiableList(attributeOrder);
    }

    public List<Def> classes() {
        return Collections.unmodifiableList(classOrder);
    }

    /** The attribute's syntax OID, following {@code SUP} chains, or null when unknown. */
    public String syntaxOf(String attr) {
        Def d = attributes.get(attr);
        for (int guard = 0; d != null && guard < 10; guard++) {
            if (d.syntax != null) {
                return d.syntax;
            }
            d = d.sup == null ? null : attributes.get(d.sup);
        }
        return null;
    }

    /** True when the attribute's values name other objects (DN, Path, Typed Name) — held back until they exist. */
    public boolean carriesDn(String attr) {
        String s = syntaxOf(attr);
        return SYNTAX_DN.equals(s) || SYNTAX_PATH.equals(s) || SYNTAX_TYPED_NAME.equals(s);
    }

    public boolean isAcl(String attr) {
        return SYNTAX_OBJECT_ACL.equals(syntaxOf(attr));
    }

    /**
     * True when the server would refuse a client writing this attribute: {@code NO-USER-MODIFICATION},
     * or a back link. {@code USAGE directoryOperation} alone does not count — NetIQ marks
     * client-written attributes that way too ({@code DirXML-Associations}, for one).
     */
    public boolean isOperational(String attr) {
        Def d = attributes.get(attr);
        return d != null && (d.noUserModification || SYNTAX_BACK_LINK.equals(d.syntax));
    }

    /** True for a server-specific attribute: never replicated, read from — and written to — one server at a time. */
    public boolean neverSync(String attr) {
        Def d = attributes.get(attr);
        return d != null && d.neverSync;
    }

    public List<String> neverSyncAttributes() {
        List<String> out = new ArrayList<>();
        for (Def d : attributeOrder) {
            if (d.neverSync) {
                out.add(d.name());
            }
        }
        return out;
    }

    public boolean isBinary(String attr) {
        String s = syntaxOf(attr);
        return s != null && BINARY_SYNTAXES.contains(s);
    }

    /** Every attribute name whose syntax is binary — what a reading connection must be told. */
    public List<String> binaryAttributes() {
        List<String> out = new ArrayList<>();
        for (Def d : attributeOrder) {
            if (isBinary(d.name())) {
                out.add(d.name());
            }
        }
        return out;
    }

    /** Whether the class (or any of its superclasses) requires the attribute. */
    public boolean mustHave(String objectClass, String attr) {
        Def c = classes.get(objectClass);
        Set<String> seen = new LinkedHashSet<>();
        List<Def> todo = new ArrayList<>();
        if (c != null) {
            todo.add(c);
        }
        while (!todo.isEmpty()) {
            Def d = todo.remove(0);
            if (!seen.add(d.name())) {
                continue;
            }
            for (String m : d.must) {
                if (m.equalsIgnoreCase(attr)) {
                    return true;
                }
            }
            for (String s : d.sups) {
                Def sd = classes.get(s);
                if (sd != null) {
                    todo.add(sd);
                }
            }
        }
        return false;
    }

    /** Every attribute the classes (and their superclasses) allow, MUST and MAY; empty when none of the classes is known. */
    public Set<String> allowedAttributes(List<String> objectClasses) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Def> todo = new ArrayList<>();
        for (String c : objectClasses) {
            Def d = classes.get(c);
            if (d != null) {
                todo.add(d);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        while (!todo.isEmpty()) {
            Def d = todo.remove(0);
            if (!seen.add(d.name().toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.addAll(d.must);
            out.addAll(d.may);
            for (String s : d.sups) {
                Def sd = classes.get(s);
                if (sd != null) {
                    todo.add(sd);
                }
            }
        }
        return out;
    }

    /** NetIQ's "Unknown" syntax: eDirectory publishes it but silently refuses to create an attribute with it over LDAP. */
    public static final String SYNTAX_NDS_UNKNOWN = "2.16.840.1.113719.1.1.5.1.0";
    public static final String SYNTAX_OCTET_STRING = "1.3.6.1.4.1.1466.115.121.1.40";

    /** The class definition with the named attributes removed from MUST / MAY (the target could not define them). */
    public static String withoutAttributes(String raw, Set<String> drop) {
        String out = raw;
        for (String key : new String[] {"MUST", "MAY"}) {
            Matcher m = Pattern.compile("\\s" + key + "\\s+(\\(([^)]*)\\)|'([^']*)'|([^\\s)]+))").matcher(out);
            if (!m.find()) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            String body = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            for (String p : body.split("\\$")) {
                String t = p.trim().replace("'", "");
                if (!t.isEmpty() && !drop.contains(t)) {
                    kept.add(t);
                }
            }
            String replacement = kept.isEmpty() ? "" : " " + key + " ( " + String.join(" $ ", kept) + " )";
            out = out.substring(0, m.start()) + replacement + out.substring(m.end());
        }
        return out;
    }

    // ---- the delta a clone writes -------------------------------------------------------

    /** What the target lacks, in an order eDirectory accepts, and what it defines differently. */
    public static final class Delta {
        public final List<Def> attributes = new ArrayList<>();
        public final List<Def> classes = new ArrayList<>();
        /** name -> (source definition, target definition) for definitions present on both but different. */
        public final Map<String, Def[]> conflicts = new LinkedHashMap<>();

        public boolean isEmpty() {
            return attributes.isEmpty() && classes.isEmpty();
        }
    }

    /**
     * Definitions of {@code source} the {@code target} does not have: attributes first (a
     * {@code SUP}'d attribute after its superior), then classes in superclass order. A
     * definition present on both with a different normalized text is a conflict, reported and
     * never rewritten — the target's definition wins.
     */
    public static Delta delta(Schema source, Schema target) {
        Delta d = new Delta();
        Set<String> have = new LinkedHashSet<>();
        for (Def t : target.attributeOrder) {
            have.add(t.name().toLowerCase(Locale.ROOT));
            for (String n : t.names) {
                have.add(n.toLowerCase(Locale.ROOT));
            }
        }
        List<Def> missing = new ArrayList<>();
        for (Def s : source.attributeOrder) {
            if (!have.contains(s.name().toLowerCase(Locale.ROOT))) {
                missing.add(s);
            } else {
                Def t = target.attribute(s.name());
                if (t != null && !t.normalized().equals(s.normalized())) {
                    d.conflicts.put(s.name(), new Def[] {s, t});
                }
            }
        }
        d.attributes.addAll(topo(missing, source.attributes, have));

        Set<String> haveClasses = new LinkedHashSet<>();
        for (Def t : target.classOrder) {
            for (String n : t.names) {
                haveClasses.add(n.toLowerCase(Locale.ROOT));
            }
        }
        List<Def> missingClasses = new ArrayList<>();
        for (Def s : source.classOrder) {
            if (!haveClasses.contains(s.name().toLowerCase(Locale.ROOT))) {
                missingClasses.add(s);
            } else {
                Def t = target.objectClass(s.name());
                if (t != null && !t.normalized().equals(s.normalized())) {
                    d.conflicts.put(s.name(), new Def[] {s, t});
                }
            }
        }
        d.classes.addAll(topo(missingClasses, source.classes, haveClasses));
        return d;
    }

    /** {@code defs} ordered so every SUP that is itself in {@code defs} comes first (a SUP the target has already needs no order). */
    private static List<Def> topo(List<Def> defs, Map<String, Def> byName, Set<String> targetHas) {
        Map<String, Def> pending = new LinkedHashMap<>();
        for (Def d : defs) {
            pending.put(d.name().toLowerCase(Locale.ROOT), d);
        }
        List<Def> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (Def d : defs) {
            visit(d, pending, byName, done, out, 0);
        }
        return out;
    }

    private static void visit(Def d, Map<String, Def> pending, Map<String, Def> byName, Set<String> done, List<Def> out, int depth) {
        String key = d.name().toLowerCase(Locale.ROOT);
        if (done.contains(key) || depth > 50) {
            return;
        }
        for (String s : d.sups) {
            Def sd = byName.get(s);
            if (sd != null && pending.containsKey(sd.name().toLowerCase(Locale.ROOT))) {
                visit(sd, pending, byName, done, out, depth + 1);
            }
        }
        if (done.add(key)) {
            out.add(d);
        }
    }

    // ---- parsing helpers -----------------------------------------------------------------

    private static final Pattern NAME = Pattern.compile("\\bNAME\\s+(?:'([^']*)'|\\(([^)]*)\\))");

    private static String firstToken(String raw) {
        String s = raw.startsWith("(") ? raw.substring(1).trim() : raw;
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    private static List<String> names(String raw) {
        List<String> out = new ArrayList<>();
        Matcher m = NAME.matcher(raw);
        if (m.find()) {
            if (m.group(1) != null) {
                out.add(m.group(1));
            } else {
                Matcher q = Pattern.compile("'([^']*)'").matcher(m.group(2));
                while (q.find()) {
                    out.add(q.group(1));
                }
            }
        }
        return out;
    }

    /** {@code KEY name}, {@code KEY 'name'} or {@code KEY ( a $ b )} → the names. */
    private static List<String> list(String raw, String key) {
        Matcher m = Pattern.compile("\\s" + key + "\\s+(?:\\(([^)]*)\\)|'([^']*)'|([^\\s)]+))").matcher(raw);
        List<String> out = new ArrayList<>();
        if (!m.find()) {
            return out;
        }
        if (m.group(1) != null) {
            for (String p : m.group(1).split("\\$")) {
                String t = p.trim().replace("'", "");
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        } else if (m.group(2) != null) {
            out.add(m.group(2));
        } else {
            out.add(m.group(3));
        }
        return out;
    }

    private static String single(String raw, String key) {
        Matcher m = Pattern.compile("\\s" + key + "\\s+'?([^\\s')]+)'?").matcher(raw);
        return m.find() ? m.group(1) : null;
    }
}
