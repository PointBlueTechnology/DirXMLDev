package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.DuplicateArtifacts;

/**
 * A manifest that lists two artifacts at one path: a tree imported before the readers kept one
 * ({@link DuplicateArtifacts}). The reader loads one so the other checks run; this says which,
 * and how to make the tree hold one.
 *
 * <p>Also names the Library artifacts that live in another Library, read-only here (#111).
 *
 * <p>Codes: {@code duplicate-artifact} (E), {@code library-other} (I).
 */
public final class DuplicateArtifactCheck implements Check {

    @Override
    public String name() {
        return "duplicates";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (DuplicateArtifacts.Dropped d : ds.duplicates) {
            r.add(Finding.error("duplicate-artifact", d.path, "the tree lists " + d.note
                + ". Import the project again with this version, which keeps one; or delete the other's"
                + " <artifact> entry and file from the manifest's folder"));
        }
        java.util.Map<String, java.util.List<String>> byLibrary = new java.util.TreeMap<>();
        java.util.Map<String, String> where = new java.util.HashMap<>();   // DN → the folder it's in
        for (com.pointblue.dirxml.dev.model.Artifact a : com.pointblue.dirxml.dev.model.Libraries.outside(ds)) {
            String dn = com.pointblue.dirxml.dev.model.Libraries.outside(ds, a);
            byLibrary.computeIfAbsent(dn, k -> new java.util.ArrayList<>()).add(a.name);
            // its own folder (docs/library-containers.md); library/ in a tree from before 0.77.0
            where.putIfAbsent(dn, a.library != null ? "libraries/" + a.library : "library");
        }
        for (java.util.Map.Entry<String, java.util.List<String>> e : byLibrary.entrySet()) {
            r.add(Finding.info("library-other", where.get(e.getKey()), e.getValue().size() + " Library object(s) live in " + e.getKey()
                + ", outside the driver set's Library: read-only here, never compared or deployed; links to them keep their DN ("
                + String.join(", ", e.getValue()) + ")"));
        }
    }
}
