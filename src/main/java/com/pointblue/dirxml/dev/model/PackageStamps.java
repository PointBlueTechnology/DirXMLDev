package com.pointblue.dirxml.dev.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The package stamps an object carries, read from either vocabulary a tree may hold.
 *
 * <p><b>The tree's vocabulary is the vault's</b> — the attribute names of {@code DirXML-PkgItemAux}
 * / {@code DirXML-PkgTargetAux}, lower-cased as the live and LDIF readers record them:
 * {@code dirxml-pkgguid} (the record {@code id;symbolicName;version;name;SHORT}),
 * {@code dirxml-pkgassociationid}, {@code dirxml-pkgchecksum}, {@code dirxml-pkglinkages},
 * {@code dirxml-pkgextensions} on a driver, and {@code package.installed.<SHORT>} (the same record,
 * {@code ;base} appended for the base package) on a driver or the driver set. The package
 * installer, the project reader and the export reader all write it, and the deploy mapping and
 * the diff read it.
 *
 * <p><b>The older vocabulary</b> — {@code package-id} (the id alone), {@code package-version},
 * {@code pkg-assoc-id}, {@code checksum}, and the provisioning digests' {@code project.package-id}
 * / {@code project.pkg-assoc-id} / {@code project.pkg-checksum} — is what trees written before
 * 2026-09-18 by {@code import-project} and {@code export} carry. Every reader here accepts it,
 * so those trees keep diffing and deploying; nothing writes it any more.
 *
 * <p>A record may be <em>partial</em>: a Designer export names only the id (and a version on
 * the driver), and Designer itself writes three fields ({@code id;symbolicName;version}) on
 * provisioning items. {@link #sameGuid} compares field by field where both sides know the field,
 * and an {@link Index} built from the models at hand completes a partial record from the fullest
 * one that names the same package — what the deploy writes to the vault.
 */
public final class PackageStamps {

    public static final String GUID = "dirxml-pkgguid";
    public static final String ASSOC = "dirxml-pkgassociationid";
    public static final String CHECKSUM = "dirxml-pkgchecksum";
    public static final String LINKAGES = "dirxml-pkglinkages";
    public static final String EXTENSIONS = "dirxml-pkgextensions";
    public static final String INSTALLED_PREFIX = "package.installed.";

    private static final String OLD_ID = "package-id";
    private static final String OLD_VERSION = "package-version";
    private static final String OLD_ASSOC = "pkg-assoc-id";
    private static final String OLD_CHECKSUM = "checksum";
    private static final String DIGEST_ID = "project.package-id";
    private static final String DIGEST_ASSOC = "project.pkg-assoc-id";
    private static final String DIGEST_CHECKSUM = "project.pkg-checksum";

    private PackageStamps() {
    }

    /**
     * One package record: {@code id;symbolicName;version;name;SHORT[;base]}, any field but the id
     * may be blank. Designer's own placeholders — {@code unknown} for the symbolic name and
     * {@code 0.0.0} for the version, which it writes on a provisioning item whose package it could
     * not resolve at deploy time — count as blank: they say nothing about the package.
     */
    public static final class Guid {
        public final String id;
        public final String symbolicName;
        public final String version;
        public final String name;
        public final String shortName;
        public final boolean base;

        public Guid(String id, String symbolicName, String version, String name, String shortName, boolean base) {
            this.id = blankToNull(id);
            this.symbolicName = "unknown".equalsIgnoreCase(blankToNull(symbolicName)) ? null : blankToNull(symbolicName);
            this.version = "0.0.0".equals(blankToNull(version)) ? null : blankToNull(version);
            this.name = blankToNull(name);
            this.shortName = blankToNull(shortName);
            this.base = base;
        }

        /** Parses a record; null for a null or blank string. Trailing {@code ;base} marks the base package. */
        public static Guid parse(String record) {
            if (record == null || record.isBlank()) {
                return null;
            }
            String[] f = record.trim().split(";", -1);
            boolean base = f.length > 5 && "base".equalsIgnoreCase(f[5].trim());
            return new Guid(f[0], at(f, 1), at(f, 2), at(f, 3), at(f, 4), base);
        }

        private static String at(String[] f, int i) {
            return i < f.length ? f[i].trim() : null;
        }

        /** The five-field record with trailing blank fields dropped ({@code id}, {@code id;;1.0}, {@code id;sym;ver;name;SHORT}). */
        public String format() {
            String[] f = {id, symbolicName, version, name, shortName};
            int last = 0;
            for (int i = 0; i < f.length; i++) {
                if (f[i] != null) {
                    last = i;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= last; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(f[i] == null ? "" : f[i]);
            }
            return sb.toString();
        }

        /** The record as {@code package.installed.<SHORT>} holds it: five fields, {@code ;base} for the base package. */
        public String formatInstalled() {
            return (id == null ? "" : id) + ";" + (symbolicName == null ? "" : symbolicName) + ";" + (version == null ? "" : version)
                + ";" + (name == null ? "" : name) + ";" + (shortName == null ? "" : shortName) + (base ? ";base" : "");
        }

        public boolean isComplete() {
            return id != null && symbolicName != null && version != null && shortName != null;
        }

        /** This record with every blank field taken from {@code other} (same id), or this when there is nothing to take. */
        public Guid completedBy(Guid other) {
            if (other == null || id == null || !id.equals(other.id)) {
                return this;
            }
            return new Guid(id, symbolicName != null ? symbolicName : other.symbolicName,
                version != null ? version : other.version, name != null ? name : other.name,
                shortName != null ? shortName : other.shortName, base || other.base);
        }

        /** How many fields are known — the fuller of two records for the same package wins in an {@link Index}. */
        int known() {
            int n = 0;
            for (String s : new String[] {id, symbolicName, version, name, shortName}) {
                if (s != null) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public String toString() {
            return format();
        }
    }

    /**
     * Designer's symbolic name for a package: {@code com.} + the vendor name stripped of everything
     * but letters and digits, lower-cased + {@code .} + the short name lower-cased ({@code NetIQ Corporation}
     * / {@code NOVLUABASE} → {@code com.netiqcorporation.novluabase}; {@code Novell, Inc.} →
     * {@code com.novellinc.…}). {@code pkgvendor} when the vendor name has no such characters.
     * (Decompiled {@code IdmPackageImpl.getSymbolicName}, Designer 4.8.7.)
     */
    public static String symbolicName(String vendorName, String shortName) {
        String v = vendorName == null ? "" : vendorName.replaceAll("[^a-zA-Z0-9]", "");
        if (v.isEmpty()) {
            return "pkgvendor";
        }
        return "com." + v.toLowerCase() + "." + (shortName == null ? "" : shortName.toLowerCase());
    }

    /** The object's package record from whichever vocabulary its meta speaks, or null when it carries none. */
    public static Guid guid(Map<String, String> meta) {
        String record = get(meta, GUID);
        if (record != null) {
            return Guid.parse(record);
        }
        String id = get(meta, OLD_ID);
        if (id != null) {
            return new Guid(id, null, get(meta, OLD_VERSION), null, null, false);
        }
        id = get(meta, DIGEST_ID);
        return id == null ? null : new Guid(id, null, null, null, null, false);
    }

    /** The package id (the record's first field), or null. */
    public static String packageId(Map<String, String> meta) {
        Guid g = guid(meta);
        return g == null ? null : g.id;
    }

    /** The package version the record names, or null. */
    public static String version(Map<String, String> meta) {
        Guid g = guid(meta);
        return g == null ? null : g.version;
    }

    public static String assocId(Map<String, String> meta) {
        return first(meta, ASSOC, OLD_ASSOC, DIGEST_ASSOC);
    }

    public static String checksum(Map<String, String> meta) {
        return first(meta, CHECKSUM, OLD_CHECKSUM, DIGEST_CHECKSUM);
    }

    public static String linkages(Map<String, String> meta) {
        return get(meta, LINKAGES);
    }

    /** True when the meta names a package in any vocabulary. */
    public static boolean isPackaged(Map<String, String> meta) {
        return guid(meta) != null || assocId(meta) != null;
    }

    /**
     * Whether two records name the same package: same id, and every field both sides know is
     * equal (case-sensitive). A side that knows only the id agrees with any fuller record of it.
     */
    public static boolean sameGuid(String a, String b) {
        return sameGuid(Guid.parse(a), Guid.parse(b));
    }

    public static boolean sameGuid(Guid a, Guid b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.id, b.id)
            && agree(a.symbolicName, b.symbolicName) && agree(a.version, b.version)
            && agree(a.name, b.name) && agree(a.shortName, b.shortName);
    }

    private static boolean agree(String x, String y) {
        return x == null || y == null || x.equals(y);
    }

    /**
     * The stamps as the vault holds them — {@link #GUID} (completed through {@code index}),
     * {@link #ASSOC}, {@link #CHECKSUM} and {@link #LINKAGES}, each only when the meta has it —
     * from either vocabulary. Empty for an unpackaged object.
     */
    public static Map<String, String> vaultStamps(Map<String, String> meta, Index index) {
        Map<String, String> out = new LinkedHashMap<>();
        Guid g = guid(meta);
        if (g != null) {
            out.put(GUID, (index == null ? g : index.complete(g)).format());
        }
        putIfNotNull(out, ASSOC, assocId(meta));
        putIfNotNull(out, CHECKSUM, checksum(meta));
        putIfNotNull(out, LINKAGES, linkages(meta));
        return out;
    }

    /**
     * Every package the given models name, by id, each as the fullest record seen: the drivers'
     * and driver set's {@code package.installed.*} records and own guids, and every artifact's,
     * form's, PRD's and entitlement's stamp. Completes a partial record (an export's id-only one,
     * an old project tree's) from what the other side of a diff — typically the vault — knows.
     */
    public static final class Index {
        public static final Index EMPTY = new Index();

        private final Map<String, Guid> byId = new LinkedHashMap<>();

        public static Index of(DriverSet... models) {
            Index idx = new Index();
            for (DriverSet ds : models) {
                if (ds == null) {
                    continue;
                }
                idx.addMeta(ds.meta);
                for (Artifact a : ds.library.artifacts()) {
                    idx.addMeta(a.meta);
                }
                for (Driver d : ds.drivers) {
                    idx.addMeta(d.meta);
                    for (Artifact a : d.artifacts()) {
                        idx.addMeta(a.meta);
                    }
                    for (Entitlement e : d.entitlements) {
                        idx.addMeta(e.meta);
                    }
                    if (d.provisioning != null) {
                        for (Form f : d.provisioning.forms) {
                            idx.addMeta(f.meta);
                        }
                        for (Prd p : d.provisioning.prds) {
                            idx.addMeta(p.meta);
                        }
                    }
                }
            }
            return idx;
        }

        /** Adds one record (any vocabulary); the fuller of two records for the same id is kept. */
        public void add(Guid g) {
            if (g == null || g.id == null) {
                return;
            }
            Guid have = byId.get(g.id);
            if (have == null) {
                byId.put(g.id, g);
            } else {
                byId.put(g.id, have.known() >= g.known() ? have.completedBy(g) : g.completedBy(have));
            }
        }

        private void addMeta(Map<String, String> meta) {
            if (meta == null) {
                return;
            }
            add(guid(meta));
            for (Map.Entry<String, String> e : meta.entrySet()) {
                if (e.getKey().startsWith(INSTALLED_PREFIX)) {
                    add(Guid.parse(e.getValue()));
                }
            }
        }

        public Guid get(String id) {
            return id == null ? null : byId.get(id);
        }

        /** {@code g} with its blank fields filled from the index's record of the same package. */
        public Guid complete(Guid g) {
            return g == null ? null : g.completedBy(byId.get(g.id));
        }

        public int size() {
            return byId.size();
        }
    }

    // ---- small helpers ----

    private static String get(Map<String, String> meta, String key) {
        if (meta == null) {
            return null;
        }
        return blankToNull(meta.get(key));
    }

    private static String first(Map<String, String> meta, String... keys) {
        for (String k : keys) {
            String v = get(meta, k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static void putIfNotNull(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
