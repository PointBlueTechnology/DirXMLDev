package com.pointblue.dirxml.dev.model;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A driver: identity, shim, raw config blobs (kept losslessly), the artifacts it
 * owns at driver scope and in its two channels, and its policy-set linkage.
 */
public final class Driver {

    /** Keys of {@link #config}: the raw XML config blobs a driver carries. */
    public static final String SHIM_CONFIG_INFO = "shim-config-info";
    public static final String CONFIG_VALUES = "config-values";
    public static final String DRIVER_FILTER = "driver-filter";
    public static final String ENGINE_CONTROL_VALUES = "engine-control-values";

    public final String name;
    public String dn;
    public String shimClass;
    public String shimAuthServer;
    public String shimAuthId;
    /** Raw config blobs by kind (see constants). */
    public final Map<String, Element> config = new LinkedHashMap<>();
    public final List<Policy> policies = new ArrayList<>();      // driver scope
    public final List<Resource> resources = new ArrayList<>();   // driver scope
    public final Channel subscriber = new Channel(Scope.SUBSCRIBER);
    public final Channel publisher = new Channel(Scope.PUBLISHER);
    /** Ordered policy-set linkage (all sets). */
    public final List<PolicyLink> links = new ArrayList<>();
    public final Map<String, String> meta = new LinkedHashMap<>();

    public Driver(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** Links of one set, in order. */
    public List<PolicyLink> links(PolicySet set) {
        List<PolicyLink> out = new ArrayList<>();
        for (PolicyLink l : links) {
            if (l.set == set) {
                out.add(l);
            }
        }
        out.sort((a, b) -> Integer.compare(a.order, b.order));
        return out;
    }

    /** Every artifact this driver owns (driver scope + both channels). */
    public List<Artifact> artifacts() {
        List<Artifact> all = new ArrayList<>();
        all.addAll(policies);
        all.addAll(resources);
        all.addAll(subscriber.policies);
        all.addAll(publisher.policies);
        return all;
    }

    @Override
    public String toString() {
        return "driver " + name;
    }
}
