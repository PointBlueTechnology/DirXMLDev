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

    // ---- a folder per Library (docs/library-containers.md) ----

    private static Resource from(String name, String dn) {
        Resource r = js(name, "function " + name.replace('-', '_') + "(){}");
        r.meta.put(DuplicateArtifacts.LIBRARY_DN, dn);
        return r;
    }

    /** Each container's name, plus the nearest parents' names only where another container shares it. */
    @Test
    public void aliasesNameEachLibrary() {
        DriverSet ds = new DriverSet("DS");
        ds.dn = "cn=DS,o=system";
        ds.library.resources.add(from("a", "cn=Library,o=services"));
        ds.library.resources.add(from("b", "cn=Library,ou=idm,o=services"));
        ds.library.resources.add(from("c", "cn=customizing,o=services"));
        ds.library.resources.add(from("d", "cn=customizing,ou=services,o=global"));
        ds.library.resources.add(from("e", "cn=SKyPRO,cn=DS,o=system"));
        ds.library.resources.add(from("f", "CN=SKyPRO, cn=DS, o=system"));   // the same container, written otherwise
        Libraries.assignAliases(ds, List.of("cn=OnlyLinked,o=elsewhere", "cn=Library,cn=DS,o=system"));
        assertEquals(java.util.Map.of(
            "Library@services", "cn=Library,o=services",
            "Library@idm", "cn=Library,ou=idm,o=services",
            "customizing@services", "cn=customizing,o=services",
            "customizing@services.global", "cn=customizing,ou=services,o=global",
            "SKyPRO", "cn=SKyPRO,cn=DS,o=system",
            "OnlyLinked", "cn=OnlyLinked,o=elsewhere"), ds.libraries);
        assertEquals("libraries/SKyPRO/f", ds.library.resources.get(5).path());
        assertEquals("libraries/Library@idm/b", ds.library.resources.get(1).path());

        // an alias already given is kept when another container joins
        ds.library.resources.add(from("g", "cn=SKyPRO,o=other"));
        Libraries.assignAliases(ds, List.of());
        assertEquals("cn=SKyPRO,cn=DS,o=system", ds.libraries.get("SKyPRO"));
        assertEquals("libraries/SKyPRO@other/g", ds.library.resources.get(6).path());
    }

    /** The tree on disk: a folder per Library with its DN; objects without per-object DNs; read back the same. */
    @Test
    public void foldersRoundTripThroughTheTree() throws Exception {
        DriverSet ds = set();
        ds.library.resources.add(from("es-shared", "cn=Library,o=services"));   // same name, another Library
        Libraries.assignAliases(ds, List.of("cn=Gone,o=services"));            // a Library only a link reaches
        Driver loop = ds.drivers.get(0);
        loop.links.clear();
        loop.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "library/es-own", 0));
        loop.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "libraries/Library@shared/es-shared", 1));
        loop.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "libraries/Library@services/es-shared", 2));
        loop.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "libraries/Gone/es-missing", 3));
        assertEquals(java.util.Set.of("Library@shared", "Library@services", "Gone"), ds.libraries.keySet());

        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, dir);
        String manifest = java.nio.file.Files.readString(dir.resolve("libraries/Library@services/library.xml"));
        assertTrue(manifest, manifest.contains("dn=\"cn=Library,o=services\""));
        assertFalse("the DN is the folder's, not each object's", manifest.contains("library.dn"));
        assertTrue(java.nio.file.Files.exists(dir.resolve("libraries/Gone/library.xml")));

        DriverSet back = com.pointblue.dirxml.dev.ascode.AsCodeReader.read(dir);
        assertEquals(ds.libraries, back.libraries);
        assertEquals(ds.index().keySet(), back.index().keySet());
        Artifact services = back.resolve("libraries/Library@services/es-shared");
        assertEquals("cn=Library,o=services", services.meta.get(DuplicateArtifacts.LIBRARY_DN));
        assertEquals("Library@services", services.library);
        assertEquals(List.of("libraries/Gone/es-missing"), back.unresolvedLinks().stream().map(l -> l.ref).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of(
                "cn=es-own,cn=Library,cn=DS,o=system#0#3",
                "cn=es-shared," + SHARED + "#1#3",
                "cn=es-shared,cn=Library,o=services#2#3",
                "cn=es-missing,cn=Gone,o=services#3#3"),
            VaultMapping.policiesValues(back.dn, back.drivers.get(0), back));

        // another read of the vault that named the folders otherwise is the same linkage
        DriverSet renamed = com.pointblue.dirxml.dev.ascode.AsCodeReader.read(dir);
        renamed.libraries.clear();
        renamed.libraries.put("Shared", SHARED);
        renamed.libraries.put("Services", "cn=Library,o=services");
        renamed.libraries.put("Gone", "cn=Gone,o=services");
        for (Artifact a : renamed.library.artifacts()) {
            if ("Library@shared".equals(a.library)) {
                a.library = "Shared";
            } else if ("Library@services".equals(a.library)) {
                a.library = "Services";
            }
        }
        for (PolicyLink l : renamed.drivers.get(0).links) {
            l.ref = l.ref.replace("Library@shared", "Shared").replace("Library@services", "Services");
        }
        assertTrue(ModelDiff.of(back, renamed).changes().toString(), ModelDiff.of(back, renamed).changes().isEmpty());
    }
}
