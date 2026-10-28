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

    /** Why an edit can't change {@code a}. */
    public static String readOnly(DriverSet ds, Artifact a) {
        return a.path() + " is read-only here: it lives in " + outside(ds, a) + ", outside the driver set's Library ("
            + ownDn(ds) + "). Change it in Designer, or in that Library";
    }
}
