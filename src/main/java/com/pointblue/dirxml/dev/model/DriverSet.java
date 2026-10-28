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

    /** Not final: a promotion gives a source project's model the target's identity ({@code promote}). */
    public String name;
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
    /** What a read left out because another artifact had its path ({@link DuplicateArtifacts}); never written. */
    public final List<DuplicateArtifacts.Dropped> duplicates = new ArrayList<>();
    /** Differences accepted as they are, per environment ({@code overrides/<env>.known.json}). */
    public final Map<String, List<KnownDifference>> known = new java.util.TreeMap<>();

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
    /**
     * Gives driver {@code from} the name {@code to}, in place, with everything that names it by path: its
     * artifacts' owner, its DN's first RDN, and every link (any driver's) into {@code drivers/<from>/}. For a
     * throwaway model only, such as the tree as an environment that names the driver differently gets it
     * ({@code overrides/<env>.properties}: {@code drivers/<from>.name = <to>}). Text that names the driver
     * inside a policy or a GCV is not rewritten. False when there is no driver {@code from}, or one named
     * {@code to} already.
     */
    public boolean renameDriver(String from, String to) {
        Driver d = driver(from);
        if (d == null || from.equals(to) || driver(to) != null) {
            return false;
        }
        d.name = to;
        if (d.dn != null && d.dn.regionMatches(true, 0, "cn=", 0, 3)) {
            int comma = firstUnescapedComma(d.dn);
            d.dn = "cn=" + escapeRdnValue(to) + (comma < 0 ? "" : d.dn.substring(comma));
        }
        List<Artifact> owned = new ArrayList<>(d.policies);
        owned.addAll(d.resources);
        owned.addAll(d.subscriber.policies);
        owned.addAll(d.publisher.policies);
        for (Artifact a : owned) {
            a.driver = to;
        }
        String oldPrefix = "drivers/" + from + "/";
        String newPrefix = "drivers/" + to + "/";
        for (Driver any : drivers) {
            for (PolicyLink l : any.links) {
                if (l.ref.startsWith(oldPrefix)) {
                    l.ref = newPrefix + l.ref.substring(oldPrefix.length());
                }
            }
        }
        return true;
    }

    private static int firstUnescapedComma(String dn) {
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ',') {
                return i;
            }
        }
        return -1;
    }

    private static String escapeRdnValue(String v) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (",+\"\\<>;=".indexOf(c) >= 0 || (c == '#' && i == 0) || (c == ' ' && (i == 0 || i == v.length() - 1))) {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

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
