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
    public final Library library = new Library();
    public final List<Driver> drivers = new ArrayList<>();
    /** The servers that serve this set ({@code DirXML-ServerList}), the connection's own included; empty when unknown. */
    public final List<String> servers = new ArrayList<>();
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
}
