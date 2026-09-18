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
    /** The driver's icon (single-valued octet string) — what Designer deploys and iManager shows. */
    public static final String DRIVER_IMAGE = "DirXML-DriverImage";
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
        if (d.icon != null && d.icon.length > 0) {
            m.put(DRIVER_IMAGE, List.of(d.icon));
        }
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

    // ---- provisioning (JSON forms + PRDs under a driver's cn=AppConfig) ----

    public static final String JSON_DATA = "srvprvJSONData";
    public static final String REQUEST_XML = "srvprvRequestXML";
    public static final String PROCESS_XML = "srvprvProcessXML";
    public static final String OC_JSON_FORM = "srvprvJSONForm";
    public static final String OC_JSON_FORMS = "srvprvJSONForms";
    public static final String OC_REQUEST = "srvprvRequest";
    public static final String OC_REQUEST_DEFS = "srvprvRequestDefs";

    /** PRD property key (as the model stores it) → vault attribute. */
    public static final Map<String, String> PRD_PROPERTY_ATTRS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", "srvprvStatus");
        m.put("flow-strategy", "srvprvFlowStrategy");
        m.put("grant", "srvprvGrant");
        m.put("revoke", "srvprvRevoke");
        m.put("category-key", "srvprvCategoryKey");
        m.put("localized-names", "srvprvLocalizedNames");
        m.put("localized-descrs", "srvprvLocalizedDescrs");
        m.put("process-type", "srvprvProcessType");
        m.put("entitlement-ref", "srvprvEntitlementRef");
        m.put("workflow-data", "srvprvWorkflowData");
        m.put("description", "description");
        PRD_PROPERTY_ATTRS = java.util.Collections.unmodifiableMap(m);
    }

    public static String appConfigDn(String dsDn, String driver) {
        return "cn=AppConfig," + driverDn(dsDn, driver);
    }

    public static String workflowFormsDn(String dsDn, String driver) {
        return "cn=WorkflowForms," + appConfigDn(dsDn, driver);
    }

    public static String formContainerDn(String dsDn, String driver, com.pointblue.dirxml.dev.model.Form.Kind kind) {
        return "cn=" + kind.container + "," + workflowFormsDn(dsDn, driver);
    }

    public static String formDn(String dsDn, String driver, com.pointblue.dirxml.dev.model.Form f) {
        return "cn=" + escapeRdn(f.name) + "," + formContainerDn(dsDn, driver, f.kind);
    }

    public static String requestDefsDn(String dsDn, String driver) {
        return "cn=RequestDefs," + appConfigDn(dsDn, driver);
    }

    public static String prdDn(String dsDn, String driver, com.pointblue.dirxml.dev.model.Prd p) {
        return "cn=" + escapeRdn(p.name) + "," + requestDefsDn(dsDn, driver);
    }

    /** The DN for a provisioning diff path ({@code drivers/<d>/provisioning/forms/<kind>/<name>} or {@code …/prds/<name>}). */
    public static String provisioningPathDn(String dsDn, String path) {
        String rest = path.substring("drivers/".length());
        int i = rest.indexOf("/provisioning/");
        String driver = rest.substring(0, i);
        String tail = rest.substring(i + "/provisioning/".length());
        if (tail.startsWith("forms/")) {
            String[] parts = tail.substring("forms/".length()).split("/", 2);
            com.pointblue.dirxml.dev.model.Form.Kind kind = com.pointblue.dirxml.dev.model.Form.Kind.byDir(parts[0]);
            return "cn=" + escapeRdn(parts[1]) + "," + formContainerDn(dsDn, driver, kind);
        }
        if (tail.startsWith("prds/")) {
            return "cn=" + escapeRdn(tail.substring("prds/".length())) + "," + requestDefsDn(dsDn, driver);
        }
        throw new IllegalArgumentException("not a provisioning path: " + path);
    }

    /** The bytes the vault holds for a form: the document compact, exactly as the vendor builder saves it. */
    public static byte[] formBytes(com.pointblue.dirxml.dev.model.Form f) {
        String compact;
        try {
            compact = com.pointblue.dirxml.dev.json.Json.compact(com.pointblue.dirxml.dev.json.Json.parse(f.json));
        } catch (RuntimeException e) {
            compact = f.json;
        }
        return compact.getBytes(StandardCharsets.UTF_8);
    }

    /** Every attribute an add of a form writes. */
    public static Map<String, List<byte[]>> formAttributes(com.pointblue.dirxml.dev.model.Form f) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        m.put(JSON_DATA, List.of(formBytes(f)));
        return m;
    }

    /**
     * Every attribute an add of a PRD writes: {@code XmlData} (the definition, which carries the process),
     * {@code srvprvRequestXML}, {@code srvprvProcessXML} (the vault keeps a copy), and the plain properties.
     */
    public static Map<String, List<byte[]>> prdAttributes(com.pointblue.dirxml.dev.model.Prd p) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        if (p.definition != null) {
            m.put(XML_DATA, List.of(xmlBytes(p.definition)));
        }
        if (p.request != null) {
            m.put(REQUEST_XML, List.of(xmlBytes(p.request)));
        }
        if (p.process != null) {
            m.put(PROCESS_XML, List.of(xmlBytes(p.process)));
        }
        for (Map.Entry<String, String> e : PRD_PROPERTY_ATTRS.entrySet()) {
            List<String> vals = p.properties.get(e.getKey());
            if (vals != null && !vals.isEmpty()) {
                List<byte[]> bytes = new ArrayList<>();
                for (String v : vals) {
                    bytes.add(v.getBytes(StandardCharsets.UTF_8));
                }
                m.put(e.getValue(), bytes);
            }
        }
        return m;
    }

    private static byte[] xmlBytes(org.w3c.dom.Element e) {
        return CanonicalXml.serialize(e).getBytes(StandardCharsets.UTF_8);   // canonical form carries the declaration
    }

    /** Package stamps of a form or PRD from its meta (same keys as artifacts) plus an optional baseline as the initial state. */
    public static Map<String, List<byte[]>> provisioningPackageAttributes(Map<String, String> meta, String baseline) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        put(m, PKG_GUID, meta.get("dirxml-pkgguid"));
        put(m, PKG_ASSOC, meta.get("dirxml-pkgassociationid"));
        put(m, PKG_CHECKSUM, meta.get("dirxml-pkgchecksum"));
        put(m, PKG_LINKAGES, meta.get("dirxml-pkglinkages"));
        if (!m.isEmpty() && baseline != null) {
            m.put(PKG_INITIAL_STATE, List.of(baseline.getBytes(StandardCharsets.UTF_8)));
        }
        return m;
    }

    // ---- entitlements (DirXML-Entitlement objects hanging directly off a driver) ----

    public static final String OC_ENTITLEMENT = "DirXML-Entitlement";

    /** {@code cn=<name>,cn=<driver>,<driver set>} — a plain child of the driver object. */
    public static String entitlementDn(String dsDn, String driver, com.pointblue.dirxml.dev.model.Entitlement e) {
        return "cn=" + escapeRdn(e.name) + "," + driverDn(dsDn, driver);
    }

    /** The DN for an entitlement diff path ({@code drivers/<d>/entitlements/<name>}). */
    public static String entitlementPathDn(String dsDn, String path) {
        String rest = path.substring("drivers/".length());
        int i = rest.indexOf("/entitlements/");
        String driver = rest.substring(0, i);
        String name = rest.substring(i + "/entitlements/".length());
        return "cn=" + escapeRdn(name) + "," + driverDn(dsDn, driver);
    }

    /** Every attribute an add of an entitlement writes: {@code XmlData} (the whole {@code <entitlement>} document). */
    public static Map<String, List<byte[]>> entitlementAttributes(com.pointblue.dirxml.dev.model.Entitlement e) {
        Map<String, List<byte[]>> m = new LinkedHashMap<>();
        if (e.definition != null) {
            m.put(XML_DATA, List.of(xmlBytes(e.definition)));
        }
        return m;
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
