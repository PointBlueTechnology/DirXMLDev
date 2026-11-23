package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.DuplicateArtifacts;
import com.pointblue.dirxml.dev.model.Libraries;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertSame;

/** Another Library's table of a name the driver set's own Library also has: the own one wins (docs/library-containers.md). */
public class ModelLibrariesTest {

    @Test
    public void theDriverSetsOwnTableWinsAName() {
        DriverSet ds = new DriverSet("DS");
        ds.dn = "cn=DS,o=system";
        Resource theirs = new Resource("CodeMap", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        theirs.meta.put(DuplicateArtifacts.LIBRARY_DN, "cn=Library,o=services");
        Resource own = new Resource("CodeMap", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        Resource onlyTheirs = new Resource("Other", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        onlyTheirs.meta.put(DuplicateArtifacts.LIBRARY_DN, "cn=Library,o=services");
        ds.library.resources.add(theirs);   // read first, as a reader may
        ds.library.resources.add(own);
        ds.library.resources.add(onlyTheirs);
        Libraries.assignAliases(ds, List.of());
        assertSame(own, Model.mappingTables(ds, null).get("CodeMap"));
        assertSame(onlyTheirs, Model.mappingTables(ds, null).get("Other"));
    }
}
