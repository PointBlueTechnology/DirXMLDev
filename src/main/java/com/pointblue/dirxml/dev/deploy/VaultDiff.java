package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.sim.JndiLdapSearch;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code vault.diff}: the vault's driver set read live into a model, diffed
 * against the tree (from = vault, to = tree). Optionally limited to some
 * drivers (plus the Library and driver set, which they depend on).
 */
public final class VaultDiff {

    private VaultDiff() {
    }

    /** Read the environment's driver set live into a model. */
    public static DriverSet readLive(Environments.Environment env) {
        return readLive(env, System.err::println);
    }

    /**
     * The driver set through the environment's connection, plus every other server's own
     * driver settings where they differ ({@link Servers#readOverrides}); {@code notes} hears about
     * servers that could not be read.
     */
    public static DriverSet readLive(Environments.Environment env, java.util.function.Consumer<String> notes) {
        try (Vault v = Vault.connect(env.vaultConfig())) {
            DriverSet ds = fromVault(v, env.driverSetDn, env.url + "/" + env.driverSetDn);
            Servers.readOverrides(ds, env.driverSetDn, v, serverDn -> {
                String url = Servers.urlOf(env, v, serverDn);
                return url == null ? null : Vault.connect(env.vaultConfig().withUrl(url));
            }, notes);
            return ds;
        }
    }

    /** Every attribute of the driver-set subtree (package stamps included), through our own connection. */
    public static DriverSet readLive(Vault.Config config, String driverSetDn) {
        try (Vault v = Vault.connect(config)) {
            return fromVault(v, driverSetDn, config.url + "/" + driverSetDn);
        }
    }

    /**
     * The same read, through an already-connected {@link VaultAccess} — real or, in tests, a
     * fake — so {@link Deployer} reads the vault it is about to write to instead of opening a
     * second connection.
     */
    static DriverSet fromVault(VaultAccess v, String driverSetDn) {
        return fromVault(v, driverSetDn, driverSetDn);
    }

    private static DriverSet fromVault(VaultAccess v, String driverSetDn, String sourceName) {
        List<com.pointblue.dirxml.sim.LdifDriverSource.Entry> entries = new java.util.ArrayList<>();
        Vault.Entry root = v.read(driverSetDn);
        if (root == null) {
            throw new IllegalArgumentException("driver set " + driverSetDn + " not found");
        }
        entries.add(toSourceEntry(root));
        // the one binary attribute the model carries: kept as bytes beside the text entries
        // (a source entry holds text only — see LdifReader.fromEntries)
        java.util.Map<String, byte[]> driverImages = new java.util.HashMap<>();
        for (Vault.Entry e : v.search(driverSetDn, "(objectClass=*)", javax.naming.directory.SearchControls.SUBTREE_SCOPE)) {
            if (!e.dn.equalsIgnoreCase(root.dn)) {
                entries.add(toSourceEntry(e));
            }
            byte[] image = e.hasClass("DirXML-Driver") ? e.bytes(VaultMapping.DRIVER_IMAGE) : null;
            if (image != null && image.length > 0) {
                driverImages.put(e.dn.toLowerCase(), image);
            }
        }
        return LdifReader.fromEntries(entries, sourceName, driverImages);
    }

    static com.pointblue.dirxml.sim.LdifDriverSource.Entry toSourceEntry(Vault.Entry e) {
        java.util.Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<byte[]>> a : e.attrs.entrySet()) {
            if (a.getKey().equalsIgnoreCase(VaultMapping.DRIVER_IMAGE)) {
                continue;   // binary — carried separately, never as (mangled) text
            }
            List<String> vals = new java.util.ArrayList<>();
            for (byte[] b : a.getValue()) {
                vals.add(new String(b, java.nio.charset.StandardCharsets.UTF_8));
            }
            attrs.put(a.getKey().toLowerCase(), vals);
        }
        return new com.pointblue.dirxml.sim.LdifDriverSource.Entry(e.dn, attrs);
    }

    /** The diff of the live vault against the tree. */
    public static ModelDiff of(Path tree, Environments.Environment env, Collection<String> onlyDrivers) throws IOException {
        DriverSet to = AsCodeReader.read(tree);
        DriverSet from = readLive(env);
        return of(from, to, onlyDrivers);
    }

    /** {@link ModelDiff#of} with both models narrowed to {@code onlyDrivers} (empty = all). */
    public static ModelDiff of(DriverSet from, DriverSet to, Collection<String> onlyDrivers) {
        if (onlyDrivers == null || onlyDrivers.isEmpty()) {
            return ModelDiff.of(from, to);
        }
        return ModelDiff.of(narrow(from, onlyDrivers), narrow(to, onlyDrivers));
    }

    /** A shallow copy of the driver set with only the named drivers (Library and driver set kept). */
    static DriverSet narrow(DriverSet ds, Collection<String> drivers) {
        DriverSet n = new DriverSet(ds.name);
        n.dn = ds.dn;
        n.configValues = ds.configValues;
        n.meta.putAll(ds.meta);
        n.library.policies.addAll(ds.library.policies);
        n.library.resources.addAll(ds.library.resources);
        List<String> missing = new ArrayList<>();
        for (String name : drivers) {
            Driver d = ds.driver(name);
            if (d != null) {
                n.drivers.add(d);
            } else {
                missing.add(name);
            }
        }
        return n;
    }
}
