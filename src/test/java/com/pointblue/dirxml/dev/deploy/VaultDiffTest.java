package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.sim.LdifDriverSource;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** The live read ({@code import-live}, the deploy's own read of the vault) through a fake vault. */
public class VaultDiffTest {

    private static final String DS = "cn=dvs,o=system";   // matches VaultMappingTest's fixture DNs

    static FakeVault seedVault(DriverSet ds) {
        FakeVault vault = new FakeVault();
        for (LdifDriverSource.Entry e : VaultMappingTest.entries(ds)) {
            Vault.Entry ve = new Vault.Entry(e.dn);
            for (String name : e.attributeNames()) {
                List<byte[]> bytes = new ArrayList<>();
                for (String v : e.all(name)) {
                    bytes.add(v.getBytes(StandardCharsets.UTF_8));
                }
                ve.attrs.put(name, bytes);
            }
            vault.seed(ve);
        }
        return vault;
    }

    /**
     * The driver's {@code DirXML-DriverImage} comes back as the icon, byte for byte, with the
     * format read from the bytes — and never as text among the driver's meta.
     */
    @Test
    public void driverImageBecomesTheIcon() {
        byte[] gif = com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.TINY_GIF;
        FakeVault vault = seedVault(VaultMappingTest.model());
        vault.read("cn=AD," + DS).attrs.put(VaultMapping.DRIVER_IMAGE, List.of(gif));

        DriverSet live = VaultDiff.fromVault(vault, DS);
        Driver ad = live.driver("AD");
        assertArrayEquals(gif, ad.icon);
        assertEquals("gif", ad.iconExtension);
        assertFalse(ad.meta.keySet().toString(), ad.meta.containsKey("dirxml-driverimage"));
        for (Driver d : live.drivers) {
            if (!d.name.equals("AD")) {
                assertNull(d.name, d.icon);
            }
        }
    }

    /** A vault with the icon and a tree with the same bytes: no difference to deploy. */
    @Test
    public void sameIconInVaultAndTreeIsNoChange() {
        byte[] gif = com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.TINY_GIF;
        FakeVault vault = seedVault(VaultMappingTest.model());
        vault.read("cn=AD," + DS).attrs.put(VaultMapping.DRIVER_IMAGE, List.of(gif));
        DriverSet tree = VaultMappingTest.model();
        tree.driver("AD").icon = gif;
        tree.driver("AD").iconExtension = "gif";

        ModelDiff diff = ModelDiff.of(VaultDiff.fromVault(vault, DS), tree);
        org.junit.Assert.assertTrue(diff.text(), diff.isEmpty());
    }

    /**
     * One value the server can't return (eDirectory -325) fails the whole subtree search: the read
     * goes object by object instead, leaves that one attribute out, and says which it was.
     */
    @Test
    public void aValueTheServerCantReturnIsLeftOutAndNamed() {
        FakeVault vault = seedVault(VaultMappingTest.model());
        String ad = "cn=AD," + DS;
        vault.read(ad).attrs.put("badAttr", List.of("x".getBytes(StandardCharsets.UTF_8)));
        vault.unreadable.put(ad.toLowerCase(), "badAttr");

        List<String> notes = new ArrayList<>();
        DriverSet live = VaultDiff.fromVault(vault, DS, notes::add);
        assertEquals(notes.toString(), 1, notes.size());
        org.junit.Assert.assertTrue(notes.get(0), notes.get(0).startsWith(ad + ": the server can't return attribute badAttr"));
        ModelDiff diff = ModelDiff.of(live, VaultMappingTest.model());
        org.junit.Assert.assertTrue(diff.text(), diff.isEmpty());
    }

    /** The driver set object itself holding such a value: read the same way. */
    @Test
    public void theDriverSetItselfCanHoldIt() {
        FakeVault vault = seedVault(VaultMappingTest.model());
        vault.unreadable.put(DS, "badAttr");
        vault.byDn.get(DS).attrs.put("badAttr", List.of("x".getBytes(StandardCharsets.UTF_8)));

        List<String> notes = new ArrayList<>();
        DriverSet live = VaultDiff.fromVault(vault, DS, notes::add);
        assertEquals(notes.toString(), 1, notes.size());
        org.junit.Assert.assertTrue(diffOf(live).isEmpty());
    }

    private static ModelDiff diffOf(DriverSet live) {
        return ModelDiff.of(live, VaultMappingTest.model());
    }
}
