package com.pointblue.dirxml.dev.model;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The root of the model: a driver set with its GCVs, Library, and drivers. Also the
 * reference resolver — every {@link PolicyLink#ref} is an artifact path resolvable
 * here, which is what makes edits reference-aware.
 */
public final class DriverSet {

    public final String name;
    public String dn;
    /** Raw driver-set {@code <configuration-values>} (GCVs), or null. */
    public Element configValues;
    /** The vault's schema as the tree keeps it ({@code schema/vault.xml}), or null until read. */
    public VaultSchema schema;
    public final Library library = new Library();
    public final List<Driver> drivers = new ArrayList<>();
    /** The servers that serve this set ({@code DirXML-ServerList}), the connection's own included; empty when unknown. */
    public final List<String> servers = new ArrayList<>();
    /** The set's own jobs ({@code DirXML-Job} objects directly under it, e.g. a statistics job). */
    public final List<Job> jobs = new ArrayList<>();
    /**
     * The role-based entitlement policies of the set's Entitlements Service driver ({@code DirXML-SharedProfile}
     * objects in the set's one {@code DirXML-SharedProfileSet} container, docs/console-gaps.md §9). The
     * container's name is {@link #rbeContainerName()}.
     */
    public final List<EntitlementPolicy> rbePolicies = new ArrayList<>();
    /**
     * The vault's e-mail notification templates ({@code notfMergeTemplate} objects of the notification
     * collection, docs/console-gaps.md §12): not the driver set's, but kept with the tree. The collection's
     * DN is {@link #templatesCollectionDn()}.
     */
    public final List<NotificationTemplate> templates = new ArrayList<>();
    /** Meta key holding the notification collection's DN when it is not the default. */
    public static final String TEMPLATES_COLLECTION_META = "templates.collection";
    /** Meta key holding the policy container's name when it is not the default. */
    public static final String RBE_CONTAINER_META = "rbe.container";
    /** iManager's and Designer's name for the policy container. */
    public static final String DEFAULT_RBE_CONTAINER = "Entitlement Policies";
    /** Per environment, the values that differ from the tree's base ({@code overrides/<env>.properties}; see {@code deploy.Overrides}). */
    public final java.util.Map<String, java.util.Map<String, String>> overrides = new java.util.TreeMap<>();
    public final Map<String, String> meta = new LinkedHashMap<>();

    public DriverSet(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public Driver driver(String name) {
        for (Driver d : drivers) {
            if (d.name.equals(name)) {
                return d;
            }
        }
        return null;
    }

    /** All artifacts in the set, by path (insertion order: library, then each driver). */
    public Map<String, Artifact> index() {
        Map<String, Artifact> idx = new LinkedHashMap<>();
        for (Artifact a : library.artifacts()) {
            put(idx, a);
        }
        for (Driver d : drivers) {
            for (Artifact a : d.artifacts()) {
                put(idx, a);
            }
        }
        return idx;
    }

    private static void put(Map<String, Artifact> idx, Artifact a) {
        Artifact prev = idx.put(a.path(), a);
        if (prev != null) {
            throw new IllegalStateException("duplicate artifact path: " + a.path());
        }
    }

    /** The artifact a link refers to, or null if unresolved. */
    public Artifact resolve(String ref) {
        return index().get(ref);
    }

    /** Links whose ref resolves to nothing — a broken chain (e.g. a Library policy not exported). */
    public List<PolicyLink> unresolvedLinks() {
        Map<String, Artifact> idx = index();
        List<PolicyLink> out = new ArrayList<>();
        for (Driver d : drivers) {
            for (PolicyLink l : d.links) {
                if (!idx.containsKey(l.ref)) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "driverset " + name + " (" + drivers.size() + " drivers, "
            + library.artifacts().size() + " library artifacts)";
    }

    /** The name of the {@code DirXML-SharedProfileSet} container the policies live in. */
    public String rbeContainerName() {
        String n = meta.get(RBE_CONTAINER_META);
        return n == null || n.isBlank() ? DEFAULT_RBE_CONTAINER : n;
    }

    /** The DN of the notification collection the templates live in. */
    public String templatesCollectionDn() {
        String n = meta.get(TEMPLATES_COLLECTION_META);
        return n == null || n.isBlank() ? NotificationTemplate.DEFAULT_COLLECTION_DN : n;
    }

    /** A template by name (case-insensitive), or null. */
    public NotificationTemplate template(String name) {
        for (NotificationTemplate t : templates) {
            if (t.name.equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    /** A policy by name (case-insensitive), or null. */
    public EntitlementPolicy rbePolicy(String name) {
        for (EntitlementPolicy p : rbePolicies) {
            if (p.name.equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    /** The drivers running the Entitlements Service shim: the ones the policies belong to (normally one). */
    public List<Driver> entitlementServiceDrivers() {
        List<Driver> out = new ArrayList<>();
        for (Driver d : drivers) {
            if (EntitlementPolicy.SERVICE_SHIM_CLASS.equals(d.shimClass)) {
                out.add(d);
            }
        }
        return out;
    }
}
