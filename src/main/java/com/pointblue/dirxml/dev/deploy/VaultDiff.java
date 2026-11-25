package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Libraries;
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
        java.util.List<Vault> opened = new java.util.ArrayList<>();
        try (Vault v = Vault.connect(env.vaultConfig())) {
            DriverSet ds = fromVault(v, env.driverSetDn, env.url + "/" + env.driverSetDn, notes);
            Servers.readOverrides(ds, env.driverSetDn, v, serverDn -> {
                String url = Servers.urlOf(env, v, serverDn);
                if (url == null) {
                    return null;
                }
                Vault other = Vault.connect(env.vaultConfig().withUrl(url));
                opened.add(other);
                return other;
            }, notes);
            return ds;
        } finally {
            for (Vault o : opened) {
                try {
                    o.close();
                } catch (RuntimeException ignore) {
                    // best effort
                }
            }
        }
    }

    /** Every attribute of the driver-set subtree (package stamps included), through our own connection. */
    public static DriverSet readLive(Vault.Config config, String driverSetDn) {
        try (Vault v = Vault.connect(config)) {
            return fromVault(v, driverSetDn, config.url + "/" + driverSetDn, System.err::println);
        }
    }

    /**
     * The same read, through an already-connected {@link VaultAccess} — real or, in tests, a
     * fake — so {@link Deployer} reads the vault it is about to write to instead of opening a
     * second connection.
     */
    static DriverSet fromVault(VaultAccess v, String driverSetDn) {
        return fromVault(v, driverSetDn, driverSetDn, System.err::println);
    }

    static DriverSet fromVault(VaultAccess v, String driverSetDn, java.util.function.Consumer<String> notes) {
        return fromVault(v, driverSetDn, driverSetDn, notes);
    }

    private static DriverSet fromVault(VaultAccess v, String driverSetDn, String sourceName, java.util.function.Consumer<String> notes) {
        List<com.pointblue.dirxml.sim.LdifDriverSource.Entry> entries = new java.util.ArrayList<>();
        Vault.Entry root = readTolerant(v, driverSetDn, notes);
        if (root == null) {
            throw new IllegalArgumentException("driver set " + driverSetDn + " not found");
        }
        entries.add(toSourceEntry(root));
        // the one binary attribute the model carries: kept as bytes beside the text entries
        // (a source entry holds text only — see LdifReader.fromEntries)
        java.util.Map<String, byte[]> driverImages = new java.util.HashMap<>();
        List<Vault.Entry> all;
        try {
            all = v.search(driverSetDn, "(objectClass=*)", javax.naming.directory.SearchControls.SUBTREE_SCOPE);
        } catch (RuntimeException e) {
            if (!Vault.invalidSyntax(e)) {
                throw e;
            }
            // one value the server can't return fails the whole search: read object by object instead
            all = new java.util.ArrayList<>();
            for (String dn : v.names(driverSetDn, "(objectClass=*)", javax.naming.directory.SearchControls.SUBTREE_SCOPE)) {
                Vault.Entry one = dn.equalsIgnoreCase(root.dn) ? root : readTolerant(v, dn, notes);
                if (one != null) {
                    all.add(one);
                }
            }
        }
        for (Vault.Entry e : all) {
            if (!e.dn.equalsIgnoreCase(root.dn)) {
                entries.add(toSourceEntry(e));
            }
            byte[] image = e.hasClass("DirXML-Driver") ? e.bytes(VaultMapping.DRIVER_IMAGE) : null;
            if (image != null && image.length > 0) {
                driverImages.put(e.dn.toLowerCase(), image);
            }
        }
        // what the set links outside it (#115): a Library elsewhere in the tree, or any other container
        entries.addAll(linkedOutside(v, driverSetDn, entries, notes));
        // the vault's notification templates live outside the driver set (cn=Security): read them too
        try {
            for (Vault.Entry c : v.search("cn=Security", "(objectClass=notfTemplateCollection)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE)) {
                for (Vault.Entry t : v.search(c.dn, "(objectClass=notfMergeTemplate)", javax.naming.directory.SearchControls.ONELEVEL_SCOPE)) {
                    entries.add(toSourceEntry(t));
                }
            }
        } catch (RuntimeException e) {
            // no cn=Security, or no rights to it: a tree without templates
        }
        return LdifReader.fromEntries(entries, sourceName, driverImages);
    }

    /** The classes of a Library's objects the tree models (a package's GCV definitions are resources). */
    private static final String ARTIFACT_FILTER = "(|(objectClass=DirXML-Rule)(objectClass=DirXML-StyleSheet)"
        + "(objectClass=DirXML-Resource)(objectClass=DirXML-GlobalConfigDef))";

    /**
     * The objects the driver set and its drivers link outside the set (#115), so a read of the vault gives
     * the tree the LDIF and Designer imports give: a Library elsewhere in the tree whole, with its container
     * entry; from another driver set's Library, or any other container, only what is linked. They become
     * read-only references in {@code libraries/<alias>/}. An object that can't be read is a note naming its
     * DN, not a failed read: its link stays unresolved.
     */
    static List<com.pointblue.dirxml.sim.LdifDriverSource.Entry> linkedOutside(VaultAccess v, String driverSetDn,
            List<com.pointblue.dirxml.sim.LdifDriverSource.Entry> read, java.util.function.Consumer<String> notes) {
        List<com.pointblue.dirxml.sim.LdifDriverSource.Entry> out = new ArrayList<>();
        java.util.Set<String> have = new java.util.HashSet<>();
        java.util.Map<String, List<String>> byContainer = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> containerDn = new java.util.HashMap<>();
        for (String target : LdifReader.linkedOutside(read, driverSetDn)) {
            String c = LdifReader.parentDn(target);
            String key = Libraries.normDn(c);
            containerDn.putIfAbsent(key, c);
            byContainer.computeIfAbsent(key, k -> new ArrayList<>()).add(target);
        }
        for (java.util.Map.Entry<String, List<String>> group : byContainer.entrySet()) {
            String c = containerDn.get(group.getKey());
            Vault.Entry container = readOrNote(v, c, notes);
            if (container != null && container.hasClass("DirXML-Library")) {
                out.add(toSourceEntry(container));
                Vault.Entry parent = readOrNote(v, LdifReader.parentDn(c), s -> { });
                if (parent == null || !parent.hasClass("DirXML-DriverSet")) {
                    // a Library of its own, outside every driver set: all of it, as the imports take it
                    for (Vault.Entry e : libraryObjects(v, c, notes)) {
                        if (have.add(Libraries.normDn(e.dn))) {
                            out.add(toSourceEntry(e));
                        }
                    }
                }
            }
            for (String target : group.getValue()) {
                if (have.contains(Libraries.normDn(target))) {
                    continue;
                }
                Vault.Entry e;
                try {
                    e = readTolerant(v, target, notes);
                } catch (RuntimeException ex) {
                    notes.accept(target + ": a link names it, but it can't be read (" + ex.getMessage() + ")."
                        + " The link stays as it is and reads as unresolved in the tree.");
                    continue;
                }
                if (e == null) {
                    notes.accept(target + ": a link names it, but the vault doesn't have it, or this account can't see it."
                        + " The link stays as it is and reads as unresolved in the tree.");
                    continue;
                }
                have.add(Libraries.normDn(target));
                out.add(toSourceEntry(e));
            }
        }
        return out;
    }

    /** A Library's policies and resources, one level down; object by object when one value can't be returned. */
    private static List<Vault.Entry> libraryObjects(VaultAccess v, String libraryDn, java.util.function.Consumer<String> notes) {
        List<Vault.Entry> found;
        try {
            found = v.search(libraryDn, ARTIFACT_FILTER, javax.naming.directory.SearchControls.ONELEVEL_SCOPE);
        } catch (RuntimeException e) {
            if (!Vault.invalidSyntax(e)) {
                notes.accept(libraryDn + ": its objects can't be listed (" + e.getMessage() + "); only those a link names were read");
                return List.of();
            }
            found = new ArrayList<>();
            for (String dn : v.names(libraryDn, ARTIFACT_FILTER, javax.naming.directory.SearchControls.ONELEVEL_SCOPE)) {
                Vault.Entry one = readOrNote(v, dn, notes);
                if (one != null) {
                    found.add(one);
                }
            }
        }
        List<Vault.Entry> out = new ArrayList<>();
        for (Vault.Entry e : found) {
            boolean artifact = e.hasClass("DirXML-Rule") || e.hasClass("DirXML-StyleSheet") || e.hasClass("DirXML-Resource")
                || e.hasClass("DirXML-GlobalConfigDef");
            if (artifact && LdifReader.sameDn(LdifReader.parentDn(e.dn), libraryDn)) {
                out.add(e);
            }
        }
        return out;
    }

    /** {@link #readTolerant}, with any other failure (no rights, a referral) a note and null. */
    private static Vault.Entry readOrNote(VaultAccess v, String dn, java.util.function.Consumer<String> notes) {
        try {
            return readTolerant(v, dn, notes);
        } catch (RuntimeException e) {
            notes.accept(dn + ": can't be read (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * The object, all its attributes; when the server can't return one of them (invalid attribute syntax,
     * -325), every other attribute, read one at a time, and a note naming the one left out.
     */
    static Vault.Entry readTolerant(VaultAccess v, String dn, java.util.function.Consumer<String> notes) {
        try {
            return v.read(dn);
        } catch (RuntimeException e) {
            if (!Vault.invalidSyntax(e)) {
                throw e;
            }
        }
        Vault.Entry out = new Vault.Entry(dn);
        for (String attr : v.attributeNames(dn)) {
            try {
                Vault.Entry one = v.read(dn, attr);
                if (one != null) {
                    out.attrs.putAll(one.attrs);
                }
            } catch (RuntimeException e) {
                if (!Vault.invalidSyntax(e)) {
                    throw e;
                }
                notes.accept(dn + ": the server can't return attribute " + attr + " (invalid attribute syntax, eDirectory -325), so it was"
                    + " left out of the comparison: a value there is damaged or doesn't fit the attribute's syntax. A deploy would write"
                    + " the tree's value of it, if the tree has one. Look at it with iManager or Designer, and fix or clear the value.");
            }
        }
        return out;
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

    /**
     * Two environments' vaults side by side ({@link EnvironmentCompare}): both read live, each with the other
     * servers' own settings, a shim auth id either's secrets file supplies kept out (a credential is never
     * shown), drivers given the tree's names. {@code tree} may be null.
     */
    public static EnvironmentCompare compareEnvironments(Environments.Environment a, Environments.Environment b, Path tree,
                                                         java.util.function.Consumer<String> notes) throws IOException {
        DriverSet base = tree == null ? null : AsCodeReader.read(tree);
        DriverSet liveA = readLive(a, n -> notes.accept(a.name + ": " + n));
        DriverSet liveB = readLive(b, n -> notes.accept(b.name + ": " + n));
        Overrides.stripSecretShimAuthIds(liveA, a.secretsFile == null ? Secrets.none() : Secrets.load(a.secretsFile));
        Overrides.stripSecretShimAuthIds(liveB, b.secretsFile == null ? Secrets.none() : Secrets.load(b.secretsFile));
        return EnvironmentCompare.of(liveA, a.name, liveB, b.name, base);
    }

    /** The diff of the live vault against the tree. */
    public static ModelDiff of(Path tree, Environments.Environment env, Collection<String> onlyDrivers) throws IOException {
        DriverSet to = AsCodeReader.read(tree);
        Overrides.Applied applied = Overrides.apply(to, env.name);
        if (applied.summary() != null) {
            System.err.println(applied.summary());
        }
        String refusal = applied.refusalFor(onlyDrivers);
        if (refusal != null) {
            throw new IllegalArgumentException(refusal);
        }
        Secrets secrets = (env.secretsFile == null ? Secrets.none() : Secrets.load(env.secretsFile)).withNames(applied.renamed);
        Overrides.applySecretShimAuthIds(to, secrets);
        DriverSet from = readLive(env);
        if (!applied.serverMap.isEmpty()) {
            Servers.foldPrimary(to, from.meta.get(Servers.PRIMARY_META));
        }
        return applied.leaveAlone(of(from, to, applied.vaultNames(onlyDrivers)));
    }

    /** {@link ModelDiff#of} with both models narrowed to {@code onlyDrivers} (empty = all). */
    public static ModelDiff of(DriverSet from, DriverSet to, Collection<String> onlyDrivers) {
        if (onlyDrivers == null || onlyDrivers.isEmpty()) {
            return ModelDiff.of(from, to);
        }
        String unknown = unknownDrivers(from, to, onlyDrivers);
        if (unknown != null) {
            throw new IllegalArgumentException(unknown);
        }
        return ModelDiff.of(narrow(from, onlyDrivers), narrow(to, onlyDrivers));
    }

    /** {@code --driver} names that neither side has (a typo would otherwise diff as "no differences"), as a message, or null. */
    public static String unknownDrivers(DriverSet from, DriverSet to, Collection<String> onlyDrivers) {
        List<String> missing = new ArrayList<>();
        for (String name : onlyDrivers) {
            if (from.driver(name) == null && to.driver(name) == null) {
                missing.add(name);
            }
        }
        return missing.isEmpty() ? null : "--driver: no driver named " + missing + " in the tree or the vault";
    }

    /** A shallow copy of the driver set with only the named drivers (Library and driver set kept). */
    static DriverSet narrow(DriverSet ds, Collection<String> drivers) {
        DriverSet n = new DriverSet(ds.name);
        n.dn = ds.dn;
        n.configValues = ds.configValues;
        n.meta.putAll(ds.meta);
        n.servers.addAll(ds.servers);
        n.overrides.putAll(ds.overrides);
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
        // the entitlement policies belong with the Entitlements Service driver: scoped to it, they travel too
        for (Driver d : n.drivers) {
            if (com.pointblue.dirxml.dev.model.EntitlementPolicy.SERVICE_SHIM_CLASS.equals(d.shimClass)) {
                n.rbePolicies.addAll(ds.rbePolicies);
                break;
            }
        }
        return n;
    }
}
