package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.DuplicateArtifacts;

/**
 * A manifest that lists two artifacts at one path: a tree imported before the readers kept one
 * ({@link DuplicateArtifacts}). The reader loads one so the other checks run; this says which,
 * and how to make the tree hold one.
 *
 * <p>Code: {@code duplicate-artifact} (E).
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
    }
}
