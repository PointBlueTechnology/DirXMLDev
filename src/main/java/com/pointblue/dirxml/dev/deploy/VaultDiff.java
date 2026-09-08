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
        JndiLdapSearch.Config c = new JndiLdapSearch.Config();
        c.url = env.url;
        c.bindDn = env.bindDn;
        c.bindPassword = env.password;
        c.trustAllCerts = env.trustAll;
        return LdifReader.readLive(c, env.driverSetDn);
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
