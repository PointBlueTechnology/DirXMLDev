package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The model → vault mapping (docs/vault-deploy.md, "Artifact ↔ vault mapping"):
 * an artifact's DN, object class and content attribute; a driver's attributes;
 * the {@code DirXML-Policies} values for a driver's (or the driver set's)
 * linkage. Pure functions; the inverse of what {@code LdifReader.readLive}
 * reads. The deployer and the diff both go through here so that "what the
 * tree would write" has exactly one definition.
 */
public final class VaultMapping {

    public static final String XML_DATA = "XmlData";
    public static final String DATA = "DirXML-Data";
    public static final String CONTENT_TYPE = "DirXML-ContentType";
    public static final String CONFIG_VALUES = "DirXML-ConfigValues";
    public static final String POLICIES = "DirXML-Policies";
    public static final String JAVA_MODULE = "DirXML-JavaModule";
    public static final String SHIM_AUTH_SERVER = "DirXML-ShimAuthServer";
    public static final String SHIM_AUTH_ID = "DirXML-ShimAuthID";
    public static final String SHIM_AUTH_PASSWORD = "DirXML-ShimAuthPassword";
    public static final String SHIM_CONFIG_INFO = "DirXML-ShimConfigInfo";
    public static final String DRIVER_FILTER = "DirXML-DriverFilter";
    public static final String ENGINE_CONTROL_VALUES = "DirXML-EngineControlValues";
    public static final String PKG_CHECKSUM = "DirXML-pkgChecksum";
    public static final String PKG_GUID = "DirXML-pkgGUID";
    public static final String PKG_ASSOC = "DirXML-pkgAssociationId";
    public static final String PKG_LINKAGES = "DirXML-pkgLinkages";
    public static final String PKG_INITIAL_STATE = "DirXML-pkgInitialState";
    public static final String PKG_EXTENSIONS = "DirXML-pkgExtensions";
    public static final String PKG_ITEM_AUX = "DirXML-PkgItemAux";
    public static final String PKG_TARGET_AUX = "DirXML-PkgTargetAux";
    /** Artifact meta keys (the live/LDIF readers' lowercase attribute names) that are package stamps. */
    public static final List<String> STAMP_KEYS = List.of("dirxml-pkgguid", "dirxml-pkgassociationid", "dirxml-pkgchecksum", "dirxml-pkglinkages");

    private VaultMapping() {
    }

    // ---- DNs ----

    /** RFC 4514 escaping of a DN attribute value. */
    public static String escapeRdn(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (",+\"\\<>;=".indexOf(c) >= 0 || (c == '#' && i == 0) || (c == ' ' && (i == 0 || i == v.length() - 1))) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String libraryDn(String dsDn) {
        return "cn=Library," + dsDn;
    }

    public static String driverDn(String dsDn, String driver) {
        return "cn=" + escapeRdn(driver) + "," + dsDn;
    }

    public static String channelDn(String dsDn, String driver, Scope scope) {
        return "cn=" + (scope == Scope.SUBSCRIBER ? "Subscriber" : "Publisher") + "," + driverDn(dsDn, driver);
    }

    /** The DN of an artifact given its path's scope, driver and name. */
    public static String artifactDn(String dsDn, Scope scope, String driver, String name) {
        switch (scope) {
            case LIBRARY:    return "cn=" + escapeRdn(name) + "," + libraryDn(dsDn);
            case DRIVER:     return "cn=" + escapeRdn(name) + "," + driverDn(dsDn, driver);
            case SUBSCRIBER:
            case PUBLISHER:  return "cn=" + escapeRdn(name) + "," + channelDn(dsDn, driver, scope);
            default: throw new IllegalStateException();
        }
    }

    public static String artifactDn(String dsDn, Artifact a) {
        return artifactDn(dsDn, a.scope, a.driver, a.name);
    }

    /** The DN for an artifact path ({@code library/X}, {@code drivers/D/subscriber/X} …). */
    public static String pathDn(String dsDn, String path) {
        String[] parts = path.split("/");
        if (parts.length == 2 && parts[0].equals("library")) {
            return artifactDn(dsDn, Scope.LIBRARY, null, parts[1]);
        }
        if (parts.length == 3 && parts[0].equals("drivers")) {
            return artifactDn(dsDn, Scope.DRIVER, parts[1], parts[2]);
        }
        if (parts.length == 4 && parts[0].equals("drivers")) {
            Scope s = parts[2].equals("subscriber") ? Scope.SUBSCRIBER : parts[2].equals("publisher") ? Scope.PUBLISHER : null;
            if (s != null) {
                return artifactDn(dsDn, s, parts[1], parts[3]);
            }
        }
        throw new IllegalArgumentException("not an artifact path: " + path);
    }

    // ---- objects ----

    /** The object class an artifact is stored as. */
    public static String objectClass(Artifact a) {
        if (a instanceof Policy) {
            return ((Policy) a).policyKind() == Policy.Kind.XSLT ? "DirXML-StyleSheet" : "DirXML-Rule";
        }
        return ((Resource) a).isGcvDef() ? "DirXML-GlobalConfigDef" : "DirXML-Resource";
    }

    /** The attribute an artifact's content lives in. */
    public static String contentAttribute(Artifact a) {
        if (a instanceof Policy) {
            return XML_DATA;
        }
        return ((Resource) a).isGcvDef() ? CONFIG_VALUES : DATA;
    }

    /** The bytes the vault should hold for an artifact's content (canonical XML, or the text). */
    public static byte[] contentBytes(Artifact a) {
        if (a instanceof Policy) {
            Policy p = (Policy) a;
            return p.content == null ? null : CanonicalXml.serialize(p.content).getBytes(StandardCharsets.UTF_8);
        }
        Resource r = (Resource) a;
        if (r.isText()) {
            return r.text.getBytes(StandardCharsets.UTF_8);
        }
        return r.content == null ? null : CanonicalXml.serialize(r.content).getBytes(StandardCharsets.UTF_8);
    }

    /** Every attribute an {@code add} of this artifact writes (content, content type). */
    public static Map<String, List<byte[]>> attributes(Artifact a) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        byte[] content = contentBytes(a);
        if (content != null) {
            m.put(contentAttribute(a), List.of(content));
        }
        if (a instanceof Resource && !((Resource) a).isGcvDef()) {
            String ct = ((Resource) a).contentType;
            if (ct != null && !ct.isBlank()) {
                m.put(CONTENT_TYPE, Vault.value(ct));
            }
        }
        return m;
    }

    /**
     * The package stamps of an installed artifact, as the vault stores them ({@code DirXML-PkgItemAux}):
     * GUID record, association id, installed checksum and linkage record from meta; the initial state from the
     * tree's package baseline. Empty for a non-packaged artifact.
     */
    public static Map<String, List<byte[]>> packageAttributes(java.nio.file.Path tree, Artifact a) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        put(m, PKG_GUID, a.meta.get("dirxml-pkgguid"));
        put(m, PKG_ASSOC, a.meta.get("dirxml-pkgassociationid"));
        put(m, PKG_CHECKSUM, a.meta.get("dirxml-pkgchecksum"));
        put(m, PKG_LINKAGES, a.meta.get("dirxml-pkglinkages"));
        if (!m.isEmpty() && tree != null) {
            try {
                String baseline = com.pointblue.dirxml.dev.edit.Packages.baseline(tree, a);
                if (baseline != null) {
                    m.put(PKG_INITIAL_STATE, List.of(baseline.getBytes(StandardCharsets.UTF_8)));
                }
            } catch (java.io.IOException e) {
                // no initial state then
            }
        }
        return m;
    }

    /** True if the artifact carries package stamps (so its object needs {@code DirXML-PkgItemAux}). */
    public static boolean isStamped(Artifact a) {
        return a.meta.get("dirxml-pkgguid") != null || a.meta.get("dirxml-pkgassociationid") != null;
    }

    /** The driver's package stamps ({@code DirXML-PkgTargetAux}): base package record and filter-extension cache. */
    public static Map<String, List<byte[]>> driverPackageAttributes(Driver d) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        put(m, PKG_GUID, d.meta.get("dirxml-pkgguid"));
        put(m, PKG_EXTENSIONS, d.meta.get("dirxml-pkgextensions"));
        return m;
    }

    /**
     * The checksum written to a customized packaged object so the vault's pair
     * differs from the package baseline (spike 4: the server won't do it).
     * A positive int derived from the content; stable for the same content.
     */
    public static String customizedChecksum(byte[] content) {
        CRC32 crc = new CRC32();
        crc.update(content);
        long v = crc.getValue() & 0x7fffffffL;
        return Long.toString(v == 0 ? 1 : v);
    }

    // ---- drivers ----

    /** A driver's scalar attributes and config blobs as the vault stores them (nulls omitted). */
    public static Map<String, List<byte[]>> driverAttributes(Driver d) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        put(m, JAVA_MODULE, d.shimClass);
        put(m, SHIM_AUTH_SERVER, d.shimAuthServer);
        put(m, SHIM_AUTH_ID, d.shimAuthId);
        blob(m, SHIM_CONFIG_INFO, d.config.get(Driver.SHIM_CONFIG_INFO));
        blob(m, CONFIG_VALUES, d.config.get(Driver.CONFIG_VALUES));
        blob(m, DRIVER_FILTER, d.config.get(Driver.DRIVER_FILTER));
        blob(m, ENGINE_CONTROL_VALUES, d.config.get(Driver.ENGINE_CONTROL_VALUES));
        return m;
    }

    /** The driver set's own attributes (its GCVs). */
    public static Map<String, List<byte[]>> driverSetAttributes(DriverSet ds) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        blob(m, CONFIG_VALUES, ds.configValues);
        return m;
    }

    /** The vault attribute a driver config key maps to. */
    public static String driverConfigAttribute(String configKey) {
        switch (configKey) {
            case Driver.SHIM_CONFIG_INFO: return SHIM_CONFIG_INFO;
            case Driver.CONFIG_VALUES: return CONFIG_VALUES;
            case Driver.DRIVER_FILTER: return DRIVER_FILTER;
            case Driver.ENGINE_CONTROL_VALUES: return ENGINE_CONTROL_VALUES;
            default: throw new IllegalArgumentException("unknown driver config key " + configKey);
        }
    }

    private static void put(Map<String, List<byte[]>> m, String attr, String v) {
        if (v != null && !v.isBlank()) {
            m.put(attr, Vault.value(v));
        }
    }

    private static void blob(Map<String, List<byte[]>> m, String attr, org.w3c.dom.Element e) {
        if (e != null) {
            m.put(attr, List.of(CanonicalXml.serialize(e).getBytes(StandardCharsets.UTF_8)));
        }
    }

    // ---- linkage ----

    /** A driver's {@code DirXML-Policies} values, in set then order. */
    public static List<String> policiesValues(String dsDn, Driver d) {
        List<String> out = new ArrayList<>();
        for (PolicySet set : PolicySet.values()) {
            for (PolicyLink l : d.links(set)) {
                out.add(pathDn(dsDn, l.ref) + "#" + l.order + "#" + set.id);
            }
        }
        return out;
    }

    /** The driver set's own {@code DirXML-Policies} values (its GCV objects) from the linkage meta. */
    public static List<String> driverSetPoliciesValues(String dsDn, DriverSet ds) {
        List<String> out = new ArrayList<>();
        int n = 0;
        while (ds.meta.containsKey("driverset.linkage." + n)) {
            String v = ds.meta.get("driverset.linkage." + n++);
            int hash = v.indexOf('#');
            String dn = hash >= 0 ? v.substring(0, hash) : v;
            String rest = hash >= 0 ? v.substring(hash) : "#0#14";
            // the meta may carry a synthesized or foreign DN; re-anchor its leaf under this driver set's Library
            String leaf = dn.contains("=") ? dn.split("(?<!\\\\),")[0].substring(dn.indexOf('=') + 1).trim()
                : dn.substring(Math.max(dn.lastIndexOf('\\'), dn.lastIndexOf('/')) + 1);
            out.add(artifactDn(dsDn, Scope.LIBRARY, null, leaf) + rest);
        }
        return out;
    }

    /** The DNs of every driver whose linkage references an artifact path. */
    public static List<String> linkingDrivers(DriverSet ds, String path) {
        List<String> out = new ArrayList<>();
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                if (l.ref.equals(path)) {
                    out.add(d.name);
                    break;
                }
            }
        }
        return out;
    }
}
