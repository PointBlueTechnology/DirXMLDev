package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Library artifacts that live outside the driver set's own Library (#111). A driver set can use
 * Libraries in other containers too; the tree holds their objects in its one {@code library/},
 * marked with the Library's DN ({@link DuplicateArtifacts#LIBRARY_DN}), as read-only references:
 * links to them resolve and carry their real DN, compare and deploy leave them alone, and an edit
 * that would change one is refused. They are changed where they live, in Designer or that Library.
 */
public final class Libraries {

    private Libraries() {
    }

    /** The driver set's own Library, where a deploy writes the tree's Library. */
    public static String ownDn(DriverSet ds) {
        return ds.dn == null ? null : "cn=Library," + ds.dn;
    }

    /** The DN of the other Library {@code a} lives in, or null when it's the driver set's own (or not a Library artifact). */
    public static String outside(DriverSet ds, Artifact a) {
        if (a == null || a.scope != Scope.LIBRARY) {
            return null;
        }
        String dn = a.meta.get(DuplicateArtifacts.LIBRARY_DN);
        if (dn == null || dn.isBlank()) {
            return null;
        }
        String own = ownDn(ds);
        return own != null && own.equalsIgnoreCase(dn) ? null : dn;
    }

    /** Every Library artifact that lives outside the driver set's own Library. */
    public static List<Artifact> outside(DriverSet ds) {
        List<Artifact> out = new ArrayList<>();
        for (Artifact a : ds.library.artifacts()) {
            if (outside(ds, a) != null) {
                out.add(a);
            }
        }
        return out;
    }

    // ---- one folder per Library (docs/library-containers.md) ----

    /**
     * Gives every artifact from another Library or container its alias ({@link Artifact#library}), and
     * {@code ds.libraries} an entry per alias. {@code extraDns} are containers that only links reach
     * (their objects aren't in the source), so a link keeps its DN. An alias already in
     * {@code ds.libraries} is kept. A new one is the container's name, plus the nearest parent names
     * when another container has the same name ({@code Library@services}), and {@code ~2}... last.
     */
    public static void assignAliases(DriverSet ds, java.util.Collection<String> extraDns) {
        java.util.Map<String, String> byNorm = new java.util.LinkedHashMap<>();   // norm(dn) → dn as read
        for (Artifact a : ds.library.artifacts()) {
            String dn = outside(ds, a);
            if (dn != null) {
                byNorm.putIfAbsent(norm(dn), dn);
            }
        }
        String own = ownDn(ds);
        for (String dn : extraDns) {
            if (dn != null && !dn.isBlank() && (own == null || !norm(dn).equals(norm(own))) && (ds.dn == null || !norm(dn).equals(norm(ds.dn)))) {
                byNorm.putIfAbsent(norm(dn), dn);
            }
        }
        java.util.Map<String, String> aliasByNorm = new java.util.HashMap<>();
        java.util.Set<String> taken = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (java.util.Map.Entry<String, String> e : ds.libraries.entrySet()) {
            aliasByNorm.put(norm(e.getValue()), e.getKey());
            taken.add(e.getKey());
        }
        List<String> fresh = new ArrayList<>();
        for (String n : byNorm.keySet()) {
            if (!aliasByNorm.containsKey(n)) {
                fresh.add(n);
            }
        }
        java.util.Map<String, List<String>> all = new java.util.HashMap<>();   // every DN in play, for the names that collide
        for (java.util.Map.Entry<String, String> e : byNorm.entrySet()) {
            all.put(e.getKey(), values(e.getValue()));
        }
        for (java.util.Map.Entry<String, String> e : ds.libraries.entrySet()) {
            all.putIfAbsent(norm(e.getValue()), values(e.getValue()));
        }
        for (String n : fresh) {
            String alias = alias(n, all);
            String candidate = alias;
            for (int i = 2; taken.contains(candidate); i++) {
                candidate = alias + "~" + i;
            }
            taken.add(candidate);
            aliasByNorm.put(n, candidate);
            ds.libraries.put(candidate, byNorm.get(n));
        }
        for (Artifact a : ds.library.artifacts()) {
            String dn = outside(ds, a);
            if (dn != null && a.library == null) {
                a.library = aliasByNorm.get(norm(dn));
            }
        }
    }

    /**
     * Gives {@code ds}'s other Libraries the aliases {@code tree} already uses for the same DNs, so a read written
     * over an existing tree ({@code import-live}, a capture) keeps its folders: an alias depends on which
     * containers a read sees ({@code Library} alone, {@code Library@services} beside another {@code Library}).
     * The artifacts' aliases and the drivers' links into {@code libraries/} follow. A DN the tree doesn't know
     * keeps its alias, with {@code ~2}... when the tree's aliases took it.
     */
    public static void adoptAliases(DriverSet ds, DriverSet tree) {
        if (tree == null || tree.libraries.isEmpty() || ds.libraries.isEmpty()) {
            return;
        }
        java.util.Map<String, String> treeAlias = new java.util.HashMap<>();   // norm(dn) → the tree's alias
        for (java.util.Map.Entry<String, String> e : tree.libraries.entrySet()) {
            treeAlias.putIfAbsent(norm(e.getValue()), e.getKey());
        }
        java.util.Map<String, String> rename = new java.util.HashMap<>();   // alias → new alias
        java.util.Map<String, String> next = new java.util.TreeMap<>();
        java.util.Set<String> taken = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (java.util.Map.Entry<String, String> e : ds.libraries.entrySet()) {
            String want = treeAlias.get(norm(e.getValue()));
            if (want != null && taken.add(want)) {
                next.put(want, e.getValue());
                rename.put(e.getKey(), want);
            }
        }
        taken.addAll(tree.libraries.keySet());   // a new DN never takes a folder the tree has for another
        for (java.util.Map.Entry<String, String> e : ds.libraries.entrySet()) {
            if (rename.containsKey(e.getKey())) {
                continue;
            }
            String candidate = e.getKey();
            for (int i = 2; taken.contains(candidate); i++) {
                candidate = e.getKey() + "~" + i;
            }
            taken.add(candidate);
            next.put(candidate, e.getValue());
            rename.put(e.getKey(), candidate);
        }
        rename.entrySet().removeIf(e -> e.getKey().equals(e.getValue()));
        if (rename.isEmpty()) {
            return;
        }
        for (Artifact a : ds.library.artifacts()) {
            if (a.library != null && rename.containsKey(a.library)) {
                a.library = rename.get(a.library);
            }
        }
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                if (l.ref == null || !l.ref.startsWith("libraries/")) {
                    continue;
                }
                int slash = l.ref.indexOf('/', "libraries/".length());
                String alias = slash < 0 ? null : l.ref.substring("libraries/".length(), slash);
                if (alias != null && rename.containsKey(alias)) {
                    l.ref = Artifact.libraryPath(rename.get(alias), l.ref.substring(slash + 1));
                }
            }
        }
        ds.libraries.clear();
        ds.libraries.putAll(next);
    }

    /** The alias of the container at {@code dn}, or null when the set has none for it. */
    public static String aliasOf(DriverSet ds, String dn) {
        if (dn == null) {
            return null;
        }
        String n = norm(dn);
        for (java.util.Map.Entry<String, String> e : ds.libraries.entrySet()) {
            if (norm(e.getValue()).equals(n)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The container's name, then its nearest parents' names as far as another container in {@code all} shares them. */
    private static String alias(String n, java.util.Map<String, List<String>> all) {
        List<String> mine = all.get(n);
        int k = 0;
        while (true) {
            String candidate = candidate(mine, k);
            boolean clash = false;
            for (java.util.Map.Entry<String, List<String>> e : all.entrySet()) {
                if (!e.getKey().equals(n) && candidate(e.getValue(), k).equalsIgnoreCase(candidate)) {
                    clash = true;
                    break;
                }
            }
            if (!clash || k + 1 >= mine.size()) {
                return candidate;
            }
            k++;
        }
    }

    private static String candidate(List<String> values, int k) {
        StringBuilder b = new StringBuilder(values.isEmpty() ? "library" : values.get(0));
        if (k > 0 && values.size() > 1) {
            b.append('@').append(String.join(".", values.subList(1, Math.min(values.size(), k + 1))));
        }
        return b.toString().replace('/', '_');
    }

    /** The RDN values of a DN, child first, as written ({@code cn=Library,o=services} → [Library, services]). */
    private static List<String> values(String dn) {
        List<String> out = new ArrayList<>();
        for (String c : dn.trim().split("(?<!\\\\),")) {
            int eq = c.indexOf('=');
            out.add((eq < 0 ? c : c.substring(eq + 1)).trim().replace("\\,", ","));
        }
        return out;
    }

    /** A DN as the directory compares it: components trimmed, case ignored. */
    public static String normDn(String dn) {
        return norm(dn);
    }

    static String norm(String dn) {
        List<String> out = new ArrayList<>();
        for (String c : dn.trim().split("(?<!\\\\),")) {
            int eq = c.indexOf('=');
            out.add(eq < 0 ? c.trim() : c.substring(0, eq).trim() + "=" + c.substring(eq + 1).trim());
        }
        return String.join(",", out).toLowerCase();
    }

    /** Why an edit can't change {@code a}. */
    public static String readOnly(DriverSet ds, Artifact a) {
        return a.path() + " is read-only here: it lives in " + outside(ds, a) + ", outside the driver set's Library ("
            + ownDn(ds) + "). Change it in Designer, or in that Library";
    }
}
