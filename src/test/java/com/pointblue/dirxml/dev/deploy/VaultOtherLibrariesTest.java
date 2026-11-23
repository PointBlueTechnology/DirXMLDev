package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.DuplicateArtifacts;
import com.pointblue.dirxml.dev.model.Libraries;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.sim.LdifDriverSource;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * #115: a read of the vault takes in what the driver set links outside it — a Library elsewhere in the
 * tree whole, any other container only what is linked — as the LDIF and Designer imports do.
 */
public class VaultOtherLibrariesTest {

    private static final String DS = "cn=dvs,o=system";
    private static final String SHARED = "cn=Library,ou=shared,o=x";
    private static final String RULE = "<policy><rule><description>%s</description><conditions/><actions/></rule></policy>";

    private static Vault.Entry entry(String dn, String objectClass, String... attrs) {
        Vault.Entry e = new Vault.Entry(dn);
        e.attrs.put("objectClass", List.of("Top".getBytes(StandardCharsets.UTF_8), objectClass.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            List<byte[]> vals = new ArrayList<>(e.attrs.getOrDefault(attrs[i], List.of()));
            vals.add(attrs[i + 1].getBytes(StandardCharsets.UTF_8));
            e.attrs.put(attrs[i], vals);
        }
        return e;
    }

    private static void link(FakeVault vault, String driverDn, String value) {
        Vault.Entry d = vault.read(driverDn);
        List<byte[]> vals = new ArrayList<>(d.attrs.getOrDefault("DirXML-Policies", List.of()));
        vals.add(value.getBytes(StandardCharsets.UTF_8));
        d.attrs.put("DirXML-Policies", vals);
    }

    /** The lab vault of the fixture, plus a shared Library under another ou with a linked policy and an unlinked resource. */
    private static FakeVault vaultWithSharedLibrary() {
        FakeVault vault = VaultDiffTest.seedVault(VaultMappingTest.model());
        vault.seed(entry(SHARED, "DirXML-Library"));
        vault.seed(entry("cn=P," + SHARED, "DirXML-Rule", "XmlData", String.format(RULE, "shared P")));
        vault.seed(entry("cn=Helpers," + SHARED, "DirXML-Resource", "DirXML-ContentType", "text/ecmascript", "DirXML-Data", "function h(){}"));
        vault.seed(entry("cn=lib-shared," + SHARED, "DirXML-Rule", "XmlData", String.format(RULE, "a second lib-shared")));
        link(vault, "cn=AD," + DS, "cn=P," + SHARED + "#5#4");
        return vault;
    }

    @Test
    public void aLinkedLibraryOutsideTheSetIsReadWhole() {
        List<String> notes = new ArrayList<>();
        DriverSet live = VaultDiff.fromVault(vaultWithSharedLibrary(), DS, notes::add);
        assertTrue(notes.toString(), notes.isEmpty());

        assertEquals(Map.of("Library", SHARED), live.libraries);
        Artifact p = live.resolve("libraries/Library/P");
        assertNotNull(p);
        assertEquals(SHARED, p.meta.get(DuplicateArtifacts.LIBRARY_DN));
        assertNotNull("not linked, but in the Library", live.resolve("libraries/Library/Helpers"));
        // the same name as the set's own: its own path, nothing left out
        assertNotNull(live.resolve("libraries/Library/lib-shared"));
        assertNotNull(live.resolve("library/lib-shared"));
        assertTrue(live.duplicates.toString(), live.duplicates.isEmpty());

        assertTrue(live.unresolvedLinks().toString(), live.unresolvedLinks().isEmpty());
        PolicyLink toP = live.driver("AD").links.stream().filter(l -> l.ref.equals("libraries/Library/P")).findFirst().orElseThrow();
        assertEquals("cn=P," + SHARED, toP.sourceDn);
        // a deploy writes the link back with its DN, and the objects themselves are never compared
        List<String> values = VaultMapping.policiesValues(DS, live.driver("AD"), live);
        assertTrue(values.toString(), values.contains("cn=P," + SHARED + "#5#4"));
    }

    /** The vault read and an LDIF of the same objects give the same model: Compare sees no difference. */
    @Test
    public void theVaultReadMatchesTheLdifImport() {
        FakeVault vault = vaultWithSharedLibrary();
        DriverSet live = VaultDiff.fromVault(vault, DS, n -> { });
        List<LdifDriverSource.Entry> all = new ArrayList<>();
        for (Vault.Entry e : vault.search("", "(objectClass=*)", 2)) {
            all.add(VaultDiff.toSourceEntry(e));
        }
        DriverSet ldif = LdifReader.fromEntries(all, "vault.ldif");
        assertEquals(ldif.libraries, live.libraries);
        assertEquals(ldif.library.artifacts().size(), live.library.artifacts().size());
        ModelDiff diff = ModelDiff.of(live, ldif);
        assertTrue(diff.text(), diff.isEmpty());
    }

    /** An object directly in an ou: only it is read, never the ou's other entries. */
    @Test
    public void anotherContainerGivesOnlyWhatIsLinked() {
        FakeVault vault = VaultDiffTest.seedVault(VaultMappingTest.model());
        String ou = "ou=policies,o=x";
        vault.seed(entry(ou, "organizationalUnit"));
        vault.seed(entry("cn=Odd," + ou, "DirXML-Rule", "XmlData", String.format(RULE, "odd")));
        vault.seed(entry("cn=NotLinked," + ou, "DirXML-Rule", "XmlData", String.format(RULE, "not linked")));
        vault.seed(entry("cn=someone," + ou, "inetOrgPerson"));
        link(vault, "cn=AD," + DS, "cn=Odd," + ou + "#6#4");

        DriverSet live = VaultDiff.fromVault(vault, DS, n -> { });
        assertEquals(Map.of("policies", ou), live.libraries);
        assertNotNull(live.resolve("libraries/policies/Odd"));
        assertNull(live.resolve("libraries/policies/NotLinked"));
        assertTrue(live.unresolvedLinks().toString(), live.unresolvedLinks().isEmpty());
    }

    /** Another driver set's Library: what this set links, not the other set's own objects. */
    @Test
    public void anotherSetsLibraryGivesOnlyWhatIsLinked() {
        FakeVault vault = VaultDiffTest.seedVault(VaultMappingTest.model());
        String other = "cn=other,o=system";
        String lib = "cn=Library," + other;
        vault.seed(entry(other, "DirXML-DriverSet"));
        vault.seed(entry(lib, "DirXML-Library"));
        vault.seed(entry("cn=Linked," + lib, "DirXML-Rule", "XmlData", String.format(RULE, "linked")));
        vault.seed(entry("cn=Theirs," + lib, "DirXML-Rule", "XmlData", String.format(RULE, "theirs")));
        link(vault, "cn=AD," + DS, "cn=Linked," + lib + "#6#4");

        DriverSet live = VaultDiff.fromVault(vault, DS, n -> { });
        assertEquals(Map.of("Library", lib), live.libraries);
        assertNotNull(live.resolve("libraries/Library/Linked"));
        assertNull(live.resolve("libraries/Library/Theirs"));
    }

    /** A link to an object the vault doesn't have, or the account can't see: a note naming it, and the read goes on. */
    @Test
    public void aMissingObjectIsANote() {
        FakeVault vault = VaultDiffTest.seedVault(VaultMappingTest.model());
        link(vault, "cn=AD," + DS, "cn=Gone," + SHARED + "#5#4");

        List<String> notes = new ArrayList<>();
        DriverSet live = VaultDiff.fromVault(vault, DS, notes::add);
        assertTrue(notes.toString(), notes.stream().anyMatch(n -> n.startsWith("cn=Gone," + SHARED + ": a link names it")));
        assertEquals(1, live.unresolvedLinks().size());
        assertEquals(SHARED, live.libraries.get("Library"));
    }

    /** One the server refuses (no rights, a referral): a note too. */
    @Test
    public void anUnreadableObjectIsANote() {
        FakeVault vault = vaultWithSharedLibrary();
        vault.unreadable.put(("cn=P," + SHARED).toLowerCase(), "XmlData");
        vault.unreadable.put(SHARED.toLowerCase(), "objectClass");   // listing the Library fails as well
        List<String> notes = new ArrayList<>();
        DriverSet live = VaultDiff.fromVault(vault, DS, notes::add);
        // the -325 rule: the object read attribute by attribute, the value it can't return left out and named
        assertTrue(notes.toString(), notes.stream().anyMatch(n -> n.startsWith("cn=P," + SHARED + ": the server can't return attribute XmlData")));
        assertNotNull(live.resolve("libraries/Library/P"));
    }

    /**
     * Written over an existing tree, a read keeps the tree's folder for a Library it knows: the LDIF import
     * saw two {@code cn=Library} containers and called this one {@code Library@shared}, the vault read sees one.
     */
    @Test
    public void aReadKeepsTheTreesFolderNames() {
        DriverSet live = VaultDiff.fromVault(vaultWithSharedLibrary(), DS, n -> { });
        DriverSet tree = new DriverSet("dvs");
        tree.libraries.put("Library@shared", SHARED);
        tree.libraries.put("Library", "cn=Library,ou=elsewhere,o=x");

        Libraries.adoptAliases(live, tree);
        assertEquals(Map.of("Library@shared", SHARED), live.libraries);
        assertNotNull(live.resolve("libraries/Library@shared/P"));
        Driver ad = live.driver("AD");
        assertTrue(ad.links.toString(), ad.links.stream().anyMatch(l -> l.ref.equals("libraries/Library@shared/P")));
        assertTrue(live.unresolvedLinks().toString(), live.unresolvedLinks().isEmpty());
        assertTrue(VaultMapping.policiesValues(DS, ad, live).contains("cn=P," + SHARED + "#5#4"));

        // a DN the tree doesn't know never takes a folder the tree has for another
        DriverSet live2 = VaultDiff.fromVault(vaultWithSharedLibrary(), DS, n -> { });
        DriverSet tree2 = new DriverSet("dvs");
        tree2.libraries.put("Library", "cn=Library,ou=elsewhere,o=x");
        Libraries.adoptAliases(live2, tree2);
        assertEquals(Map.of("Library~2", SHARED), live2.libraries);
        assertNotNull(live2.resolve("libraries/Library~2/P"));
    }
}
