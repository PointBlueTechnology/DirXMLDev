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

    private static FakeVault seedVault(DriverSet ds) {
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
}
