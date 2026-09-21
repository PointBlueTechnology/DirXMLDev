package com.pointblue.dirxml.dev.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the tree knows about the {@code cn=AppConfig} subtree beyond forms and PRDs
 * ({@code docs/appconfig.md} §1–§2): which classes are which kind of object, which
 * containers hold the Identity Applications' runtime records (never read, never
 * written), which attributes are operational (read for the record, never compared or
 * deployed), which attribute values are XML, and the schema spelling of the attribute
 * names (LDIF and live reads lower-case them).
 */
public final class AppConfigPolicy {

    private AppConfigPolicy() {
    }

    /** Structural class → kind. Every other class is {@link AppObject.Kind#OTHER}, unless it is a known container. */
    private static final Map<String, AppObject.Kind> KINDS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Classes that are containers of AppConfig (their own attributes matter; they are design objects too). */
    private static final Set<String> CONTAINER_CLASSES = caseInsensitive(
        "srvprvAppConfig", "srvprvDirectoryModel", "srvprvEntityDefs", "srvprvChoiceDefs", "srvprvRelationshipDefs",
        "srvprvQueryDefs", "srvprvRequestDefs", "srvprvJSONForms", "srvprvTeamDefs", "srvprvServiceDefs",
        "srvprvResourceDefs", "srvprvWorkflowDefs", "srvprvProxyDefs", "srvprvDelegateeDefs", "srvprvDelegationDefs",
        "srvprvAppDefs", "nrfConfig", "nrfRoleDefs", "nrfResourceDefs", "nrfReportDefs", "nrfSODDefs", "nrfAttestations",
        "nrfRequests", "nrfResourceRequests", "nrfResourceAssociations", "nrfCPRSRequests", "nrfUIConfig", "nrfNavItems",
        "nrfAuthTypes");
    /** Containers (paths under AppConfig) whose children are the applications' runtime records. */
    public static final Set<String> RUNTIME_CONTAINERS = Set.of(
        "RoleConfig/Requests", "RoleConfig/ResourceRequests", "RoleConfig/ResourceAssociations", "RoleConfig/CprsRequests");
    /** Classes of runtime records, wherever they sit. */
    private static final Set<String> RUNTIME_CLASSES = caseInsensitive(
        "nrfRequest", "nrfResourceRequest", "nrfResourceAssociation", "nrfCPRSRequest", "nrfRoleRequest");
    /** Attributes the applications or eDirectory own: kept in the read, never compared or deployed. */
    private static final Set<String> OPERATIONAL_ATTRS = caseInsensitive(
        "equivalentToMe", "DirXML-Associations", "GUID", "revision", "modifiersName", "creatorsName",
        "createTimestamp", "modifyTimestamp", "structuralObjectClass", "subordinateCount", "entryFlags",
        "localEntryID", "federationBoundary", "subschemaSubentry", "entryDN", "ACL-Read", "nrfLastUpdated", "srvprvModified");
    /** Boolean-syntax attributes: the vault spells them {@code TRUE}/{@code FALSE}; Designer's files say {@code true}/{@code false}. */
    private static final Set<String> BOOLEAN_ATTRS = caseInsensitive("nrfAttestationDefault", "nrfDefault", "nrfVisible",
        "nrfActive", "nrfAllowMulti", "nrfAllowAprOveride", "nrfRevokeApprovalRequired", "nrfIsExpirationRequired");

    /** A value as the vault holds it: booleans upper-cased; everything else as given. */
    public static String normalizeValue(String attr, String value) {
        if (value != null && BOOLEAN_ATTRS.contains(attr)) {
            String t = value.trim();
            if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                return t.toUpperCase(Locale.ROOT);
            }
        }
        return value;
    }

    /** True for the {@code lang~text|…} localized attributes, whose segment order carries no meaning. */
    public static boolean isLocalizedAttribute(String attr) {
        return attr.endsWith("LocalizedNames") || attr.endsWith("LocalizedDescrs");
    }

    /** A localized string with its segments sorted by language, for comparison. */
    public static String sortedLocalized(String value) {
        if (value == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("\\|")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        parts.sort(null);
        return String.join("|", parts);
    }

    /** Meta key: attributes a source cannot carry ({@code a,b,c}); neither side of a diff has an opinion on them. */
    public static final String ABSENT_ATTRS_META = "source.absent-attrs";

    /** True when {@code meta} says the source could not carry {@code attr}. */
    public static boolean isAbsent(Map<String, String> meta, String attr) {
        String v = meta.get(ABSENT_ATTRS_META);
        if (v == null) {
            return false;
        }
        for (String a : v.split(",")) {
            if (a.trim().equalsIgnoreCase(attr)) {
                return true;
            }
        }
        return false;
    }
    /** Attributes whose value is an XML document. */
    private static final Set<String> XML_ATTRS = caseInsensitive("XmlData", "nrfResourceParms", "nrfEntitlementConfigDefault");
    /** Attributes that are not the object's design content in any source (identity, stamps carried in meta). */
    private static final Set<String> NOT_CONTENT = caseInsensitive("cn", "objectClass", "DirXML-pkgGUID",
        "DirXML-pkgAssociationId", "DirXML-pkgChecksum", "DirXML-pkgLinkages", "DirXML-pkgInitialState", "DirXML-pkgExtensions");
    /** Schema spelling of every attribute seen on AppConfig objects (idm254, ig4, the UA base package). */
    private static final Map<String, String> CANONICAL = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static {
        kind("srvprvEntity", AppObject.Kind.ENTITY);
        kind("srvprvChoice", AppObject.Kind.CHOICE);
        kind("srvprvRelationship", AppObject.Kind.RELATIONSHIP);
        kind("srvprvDirectoryModelConfig", AppObject.Kind.DM_CONFIG);
        kind("nrfRole", AppObject.Kind.ROLE);
        kind("nrfResource", AppObject.Kind.RESOURCE);
        kind("nrfAttestation", AppObject.Kind.ATTESTATION);
        kind("nrfSOD", AppObject.Kind.SOD);
        kind("nrfConfiguration", AppObject.Kind.ROLE_CONFIG);
        kind("nrfReport", AppObject.Kind.REPORT);
        kind("nrfNavItem", AppObject.Kind.NAV_ITEM);
        kind("nrfAuthType", AppObject.Kind.AUTH_TYPE);
        kind("srvprvWebAppConfig", AppObject.Kind.WEB_APP_CONFIG);
        kind("srvprvRequest", AppObject.Kind.PRD);
        kind("srvprvJSONForm", AppObject.Kind.FORM);
        for (String c : CONTAINER_CLASSES) {
            KINDS.put(c, AppObject.Kind.CONTAINER);
        }
        for (String a : new String[] {
            "cn", "objectClass", "description", "ACL", "XmlData", "Version",
            "srvprvEntityType", "srvprvJSONData", "srvprvPlugins", "srvprvModified", "srvprvDefaultTheme",
            "srvprvLocalizedNames", "srvprvLocalizedDescrs", "srvprvStatus", "srvprvFlowStrategy", "srvprvGrant",
            "srvprvRevoke", "srvprvCategoryKey", "srvprvProcessType", "srvprvRequestXML", "srvprvProcessXML",
            "srvprvEntitlementRef", "srvprvWorkflowData",
            "nrfLocalizedNames", "nrfLocalizedDescrs", "nrfNavItemId", "nrfNavItemType", "nrfDefault", "nrfVisible",
            "nrfAccessAttribute", "nrfAuthContObjClass", "nrfAuthObjectClass", "nrfAuthTypeId",
            "nrfRoleCategoryKey", "nrfStatus", "nrfRoleLevel", "nrfResourceParms", "nrfCategoryKey",
            "nrfAllowAprOveride", "nrfActive", "nrfAllowMulti", "nrfRequestDef", "nrfAttestationType",
            "nrfAttestationDefault", "nrfReportLocaleDefault", "nrfReportLocales", "nrfRoleLevels",
            "nrfEntitlementConfigDefault", "nrfStdSODRequestDef", "nrfResourceRevokeRequestDef",
            "nrfResourceGrantRequestDef", "nrfResourceRequestContainer", "nrfStdRequestDef", "nrfResourcesContainer",
            "nrfReportContainer", "nrfRolesContainer", "nrfRequestContainer", "nrfSODContainer", "nrfUADContainer",
            "nrfPCRSRequestContainer", "nrfRemovalGracePeriod", "nrfEntitlementRef", "nrfApprover", "nrfQuorum",
            "nrfRevokeApprovalRequired", "nrfOwners", "nrfChildRoles", "nrfParentRoles", "nrfSODRoles",
            "equivalentToMe", "DirXML-Associations", "DirXML-pkgGUID", "DirXML-pkgAssociationId",
            "DirXML-pkgChecksum", "DirXML-pkgLinkages", "DirXML-pkgInitialState", "DirXML-pkgExtensions"}) {
            CANONICAL.put(a, a);
        }
    }

    private static void kind(String cls, AppObject.Kind k) {
        KINDS.put(cls, k);
    }

    private static Set<String> caseInsensitive(String... names) {
        Set<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(List.of(names));
        return s;
    }

    public static AppObject.Kind kindOf(String structuralClass) {
        if (structuralClass == null) {
            return AppObject.Kind.OTHER;
        }
        AppObject.Kind k = KINDS.get(structuralClass);
        return k == null ? AppObject.Kind.OTHER : k;
    }

    /** The one class in {@code classes} that names a kind; else the first that is not {@code Top} or an auxiliary. */
    public static String structuralClass(List<String> classes) {
        for (String c : classes) {
            if (KINDS.containsKey(c)) {
                return c;
            }
        }
        for (String c : classes) {
            if (!c.equalsIgnoreCase("Top") && !isAuxiliary(c)) {
                return c;
            }
        }
        return classes.isEmpty() ? null : classes.get(0);
    }

    public static boolean isAuxiliary(String cls) {
        return cls.equalsIgnoreCase("DirXML-PkgItemAux") || cls.toLowerCase(Locale.ROOT).endsWith("aux");
    }

    public static boolean isContainerClass(String cls) {
        return cls != null && CONTAINER_CLASSES.contains(cls);
    }

    public static boolean isRuntimeClass(String cls) {
        return cls != null && RUNTIME_CLASSES.contains(cls);
    }

    /** True when {@code path} is inside one of the runtime containers (a child, not the container itself). */
    public static boolean isRuntimePath(String path) {
        for (String c : RUNTIME_CONTAINERS) {
            if (path.regionMatches(true, 0, c + "/", 0, c.length() + 1)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOperational(String attr) {
        return OPERATIONAL_ATTRS.contains(attr);
    }

    public static boolean isXmlAttribute(String attr) {
        return XML_ATTRS.contains(attr);
    }

    /** Identity and package stamps: carried in {@code meta}, never as content. */
    public static boolean isNotContent(String attr) {
        return NOT_CONTENT.contains(attr);
    }

    /** The schema's spelling of an attribute name, when known; else the name as given. */
    public static String canonicalAttribute(String attr) {
        String c = CANONICAL.get(attr);
        return c == null ? attr : c;
    }

    /** {@code en~Name|de~Name|…} → language → text (insertion order of the source). */
    public static Map<String, String> localized(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split("\\|")) {
            int t = part.indexOf('~');
            if (t > 0) {
                out.put(part.substring(0, t), part.substring(t + 1));
            }
        }
        return out;
    }

    /** True when every non-empty segment of a localized string is {@code lang~text}. */
    public static boolean isWellFormedLocalized(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (String part : value.split("\\|")) {
            if (part.isEmpty()) {
                continue;
            }
            int t = part.indexOf('~');
            if (t <= 0 || t > 12) {
                return false;
            }
        }
        return true;
    }
}
