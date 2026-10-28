package com.pointblue.dirxml.dev.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.deploy.VaultMapping;
import com.pointblue.dirxml.dev.edit.ArtifactOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Validator;

import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A Library object from another Library: a read-only reference (#111). */
public class LibrariesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    static final String SHARED = "cn=Library,ou=shared,o=system";

    private static Resource js(String name, String text) {
        Resource r = new Resource(name, Scope.LIBRARY, null, Resource.ECMASCRIPT);
        r.text = text;
        return r;
    }

    /** cn=DS,o=system with its own es-own and a reference to es-shared, which lives in ou=shared; Loop links both. */
    private static DriverSet set() {
        DriverSet ds = new DriverSet("DS");
        ds.dn = "cn=DS,o=system";
        ds.library.resources.add(js("es-own", "function own(){}"));
        Resource shared = js("es-shared", "function shared(){}");
        shared.meta.put(DuplicateArtifacts.LIBRARY_DN, SHARED);
        ds.library.resources.add(shared);
        Driver d = new Driver("Loop");
        d.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "library/es-own", 0));
        d.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "library/es-shared", 1));
        ds.drivers.add(d);
        return ds;
    }

    @Test
    public void onlyAnotherLibrarysObjectIsOutside() {
        DriverSet ds = set();
        assertEquals(SHARED, Libraries.outside(ds, ds.library.resources.get(1)));
        assertNull(Libraries.outside(ds, ds.library.resources.get(0)));
        // a tree imported by 0.57.0 recorded the driver set's own Library too: still its own
        ds.library.resources.get(0).meta.put(DuplicateArtifacts.LIBRARY_DN, "cn=Library,cn=DS,o=system");
        assertNull(Libraries.outside(ds, ds.library.resources.get(0)));
    }

    /** Deployed to another driver set (QA), the own object follows that driver set; the shared one keeps its DN. */
    @Test
    public void linksCarryTheRealDn() {
        DriverSet ds = set();
        List<String> values = VaultMapping.policiesValues("cn=QA,o=qa", ds.drivers.get(0), ds);
        assertEquals(List.of("cn=es-own,cn=Library,cn=QA,o=qa#0#3", "cn=es-shared," + SHARED + "#1#3"), values);
    }

    /** The vault's driver set doesn't hold es-shared (it lives elsewhere): no change, so a deploy never creates a copy. */
    @Test
    public void compareAndDeployLeaveItAlone() {
        DriverSet tree = set();
        DriverSet vault = set();
        vault.library.resources.remove(1);
        vault.library.resources.get(0).text = "function own(){ return 1; }";
        ModelDiff diff = ModelDiff.of(vault, tree);
        assertEquals(1, diff.changes().size());
        assertEquals("library/es-own", diff.changes().get(0).path);
    }

    @Test
    public void anEditThatWouldChangeItIsRefused() throws Exception {
        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(set(), dir);

        Result shared = Transaction.open(dir).run(new ArtifactOps.SetContent("library/es-shared", "function changed(){}"), true, true);
        assertTrue(shared.refusal, shared.refusal != null && shared.refusal.contains("read-only here: it lives in " + SHARED));

        Result own = Transaction.open(dir).run(new ArtifactOps.SetContent("library/es-own", "function changed(){}"), true, false);
        assertNull(own.refusal);
    }

    @Test
    public void validationSaysWhereTheyLive() throws Exception {
        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(set(), dir);
        boolean said = false;
        for (Finding f : Validator.standard().validate(dir).findings()) {
            said |= f.code.equals("library-other") && f.message.contains(SHARED) && f.message.contains("es-shared");
            assertFalse(f.toString(), f.code.equals("check-failed"));
        }
        assertTrue(said);
    }
}
