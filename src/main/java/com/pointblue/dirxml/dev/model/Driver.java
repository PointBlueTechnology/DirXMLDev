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
    /** Raw config blobs by kind (see constants) — the primary server's, or the only server's. */
    public final Map<String, Element> config = new LinkedHashMap<>();
    /**
     * Per other server of the driver set, the never-sync config blobs that differ from
     * {@link #config} there (server DN → kind → blob; a null blob = that server holds none).
     * Empty on a single-server set and wherever a server holds what the primary holds.
     * See {@code deploy.Servers}.
     */
    public final Map<String, Map<String, Element>> serverConfig = new LinkedHashMap<>();
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
     * The driver's icon, as opaque bytes, or null when it has none. It is the vault's
     * {@code DirXML-DriverImage} (a single-valued octet string on {@code DirXML-Driver}):
     * Designer writes the driver's custom icon there on deploy — or its stock icon for the
     * driver type when the driver has no custom one — and reads it back on import, so a live
     * read, an LDIF and a Designer export all carry it, iManager displays it, and a Designer
     * project stores the same bytes as the driver's {@code icon} heavy-data attribute. See
     * {@code docs/designer-new-project.md} §7.2e.
     */
    public byte[] icon;
    /**
     * The image format of {@link #icon}: the {@code extension} Designer wrote on the heavy-data
     * attribute when the icon came from a project ({@code "gif"} on nearly every driver,
     * {@code "png"} on some), or {@link #iconExtensionOf} from the bytes' magic number when it
     * came from the vault, an LDIF or an export. Null when there is no icon.
     */
    public String iconExtension;

    /**
     * The image format the bytes' magic number says — {@code gif}, {@code png}, {@code jpg},
     * {@code bmp} — or {@code bin} when it is none of those (never null for non-empty bytes).
     */
    public static String iconExtensionOf(byte[] b) {
        if (b == null || b.length < 4) {
            return "bin";
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "gif";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (b[0] == 'B' && b[1] == 'M') {
            return "bmp";
        }
        return "bin";
    }
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
