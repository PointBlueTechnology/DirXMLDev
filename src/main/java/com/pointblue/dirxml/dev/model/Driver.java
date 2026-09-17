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
    /** This driver's {@code cn=AppConfig} subtree (forms, PRDs); null if it has none. */
    public Provisioning provisioning;
    /**
     * The driver's icon, as opaque bytes, or null when it has none. Designer lets a driver
     * carry a custom icon that no type lookup can produce (test11pf: EventLogger, AcctExpNotif,
     * Beeline, CyberArk), and it lives only in a Designer project — the vault has no icon
     * attribute, so an export, an LDIF and a live read never produce one. See
     * {@code docs/designer-new-project.md} §7.2c.
     */
    public byte[] icon;
    /**
     * The image format of {@link #icon}, as Designer's {@code CHeavyData extension} names it —
     * {@code "gif"} on nearly every driver, {@code "png"} on some. Kept exactly as Designer
     * wrote it; null when there is no icon.
     */
    public String iconExtension;
    /** {@code DirXML-Entitlement} objects hanging directly off this driver, in read order. */
    public final List<Entitlement> entitlements = new ArrayList<>();

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

    /** The entitlement of this name, or null. */
    public Entitlement entitlement(String name) {
        for (Entitlement e : entitlements) {
            if (e.name.equals(name)) {
                return e;
            }
        }
        return null;
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
