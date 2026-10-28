package com.pointblue.dirxml.dev.model;

import com.pointblue.dirxml.dev.xml.CanonicalXml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two artifacts with one path: a Library or driver (or channel) holding two policies or resources
 * of the same name. An Identity Vault holds one object per name in a container, and the tree one
 * per path ({@link DriverSet#index()} refuses two), but a Designer project can hold two: a driver
 * set that uses Libraries in two containers, each with an object of that name (the tree has one
 * {@code library/}), an object left behind by a package upgrade, or one copied in by hand.
 * {@link #drop} keeps one of each and says which, so a read goes on and the person decides.
 *
 * <p>The one kept: the one in the driver set's own Library ({@code cn=Library,<driver set>}, where a
 * deploy writes the tree's Library), when the copies come from different Libraries; else the
 * first, when the copies are the same; else the one from the newest package version, when each
 * comes from a package; else the first.
 */
public final class DuplicateArtifacts {

    /** One path that had several artifacts: what was kept and what was left out, and what to do in Designer. */
    public static final class Dropped {
        public final String path;
        public final String note;
        public final String advice;

        Dropped(String path, String note, String advice) {
            this.path = path;
            this.note = note;
            this.advice = advice;
        }
    }

    /** Meta key: the DN of the Library an artifact was read from, when the driver set uses several. */
    public static final String LIBRARY_DN = "library.dn";

    private DuplicateArtifacts() {
    }

    /** Removes all but one artifact of each path from {@code ds}; one entry per path that had several. */
    public static List<Dropped> drop(DriverSet ds) {
        List<Dropped> out = new ArrayList<>();
        String ownLibrary = ds.dn == null ? null : "cn=Library," + ds.dn;
        drop(ds.library.policies, ds.library.resources, ownLibrary, out);
        for (Driver d : ds.drivers) {
            drop(d.policies, d.resources, null, out);
            drop(d.subscriber.policies, new ArrayList<>(), null, out);
            drop(d.publisher.policies, new ArrayList<>(), null, out);
        }
        return out;
    }

    private static void drop(List<Policy> policies, List<Resource> resources, String ownLibrary, List<Dropped> out) {
        Map<String, List<Artifact>> byPath = new LinkedHashMap<>();
        for (Artifact a : policies) {
            byPath.computeIfAbsent(a.path(), k -> new ArrayList<>()).add(a);
        }
        for (Artifact a : resources) {
            byPath.computeIfAbsent(a.path(), k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<Artifact>> e : byPath.entrySet()) {
            List<Artifact> same = e.getValue();
            if (same.size() < 2) {
                continue;
            }
            Artifact keep = keeper(same, ownLibrary);
            List<String> left = new ArrayList<>();
            for (Artifact a : same) {
                if (a != keep) {
                    policies.remove(a);
                    resources.remove(a);
                    left.add(describe(a, inLibraries(same) ? ownLibrary : null));
                }
            }
            String how = identical(same) && !inLibraries(same) ? "they are the same, so kept one"
                : "kept " + describe(keep, inLibraries(same) ? ownLibrary : null) + ", left out " + String.join(" and ", left);
            out.add(new Dropped(e.getKey(), same.size() + " objects are named " + e.getKey()
                + " (an Identity Vault holds one per name in a container): " + how,
                inLibraries(same)
                    ? "The tree has one Library, so the copy in the other Library isn't in it; that copy stays in the vault as it is"
                    : "Delete the one you don't want in Designer, then import again"));
        }
    }

    private static Artifact keeper(List<Artifact> same, String ownLibrary) {
        if (inLibraries(same)) {
            // the driver set's own Library's copy: no Library DN recorded (or, in a tree from 0.57.0, its own)
            for (Artifact a : same) {
                String dn = a.meta.get(LIBRARY_DN);
                if (dn == null || ownLibrary != null && ownLibrary.equalsIgnoreCase(dn)) {
                    return a;
                }
            }
        }
        if (identical(same)) {
            return same.get(0);
        }
        Artifact best = null;
        String bestVersion = null;
        for (Artifact a : same) {
            String v = version(a);
            if (v == null) {
                return same.get(0);
            }
            if (best == null || compareVersions(v, bestVersion) > 0) {
                best = a;
                bestVersion = v;
            }
        }
        return best;
    }

    /** Copies read from different Libraries: each is its own object in the vault, even when the same. */
    private static boolean inLibraries(List<Artifact> same) {
        for (Artifact a : same) {
            if (a.meta.containsKey(LIBRARY_DN)) {
                return true;
            }
        }
        return false;
    }

    private static boolean identical(List<Artifact> same) {
        String first = content(same.get(0));
        for (Artifact a : same) {
            if (!a.kind().equals(same.get(0).kind()) || !first.equals(content(a))) {
                return false;
            }
        }
        return true;
    }

    private static String content(Artifact a) {
        if (a instanceof Policy) {
            Policy p = (Policy) a;
            return p.content == null ? "" : CanonicalXml.serialize(p.content);
        }
        Resource r = (Resource) a;
        if (r.isText()) {
            return r.text == null ? "" : r.text;
        }
        return r.content == null ? "" : CanonicalXml.serialize(r.content);
    }

    private static String version(Artifact a) {
        PackageStamps.Guid g = PackageStamps.guid(a.meta);
        return g == null ? null : g.version;
    }

    /** What the person needs to find it in Designer: its package and version, its Designer id. */
    static String describe(Artifact a, String ownLibrary) {
        List<String> parts = new ArrayList<>();
        String library = a.meta.get(LIBRARY_DN);
        if (library == null) {
            library = ownLibrary;   // copies from several Libraries: one without a recorded DN is the driver set's own
        }
        if (library != null) {
            parts.add("in " + library);
        }
        PackageStamps.Guid g = PackageStamps.guid(a.meta);
        if (g != null && (g.shortName != null || g.version != null)) {
            List<String> pkg = new ArrayList<>();
            for (String s : new String[] {g.shortName, g.version}) {
                if (s != null) {
                    pkg.add(s);
                }
            }
            parts.add("from package " + String.join(" ", pkg));
        }
        String id = a.meta.get("designer.id");
        if (id != null) {
            parts.add("Designer object " + id);
        }
        return "the " + a.kind() + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
    }

    /** Dotted versions compared number by number ({@code 2.10.0} after {@code 2.9.1}); text compares as text. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c;
            if (p.matches("\\d{1,18}") && q.matches("\\d{1,18}")) {
                c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            } else {
                c = p.compareTo(q);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
