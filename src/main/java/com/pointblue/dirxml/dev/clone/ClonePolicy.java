package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Vault;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a clone carries and what it never does (docs/vault-clone.md §2, §5). Three kinds of
 * rule: <b>subtrees</b> that stay at the source (servers, certificate and key material,
 * login methods, iManager RBS); <b>classes</b> that are dropped from an entry's
 * {@code objectClass} because a client cannot create them ({@code Partition});
 * <b>attributes</b> that are left out — server-computed, eDirectory-maintained inverse
 * links, passwords and keys, driver run-time state — plus the one value the clone changes:
 * every driver's start option becomes <em>manual</em>.
 */
public final class ClonePolicy {

    private ClonePolicy() {
    }

    /** Structural classes whose entries (and subtrees) are never cloned: tree-bound, server-bound, cryptographic. */
    public static final Set<String> NEVER_CLONE_CLASSES = ci(
        "ncpServer", "Server", "ldapServer", "ldapGroup", "snmpGroup", "sASService", "httpServer",
        "nDSPKICertificateAuthority", "nDSPKIKeyMaterial", "ndspkiContainer", "nDSPKITrustedRoot",
        "nDSPKITrustedRootObject", "nDSPKISDKeyAccessPartition", "nDSPKISDKeyList", "sASLoginMethodContainer",
        "sasPostLoginMethodContainer", "sASLoginPolicy", "mASVSecurityPolicy", "sssServerPolicies",
        "sssServerPolicyOverride", "NCP Server", "Volume", "Queue", "Directory Map", "Print Server", "Printer",
        "nrfIdentityVaultConfig", "sasLoginMethod", "sasPostLoginMethod");

    /** Class prefixes never cloned unless asked (iManager Role Based Services). */
    public static final String RBS_PREFIX = "rbs";

    /** Auxiliary classes dropped from {@code objectClass}: only the server can bestow them. */
    public static final Set<String> DROP_CLASSES = ci("Partition", "nDSPKIRepository");

    /** Identity data: users, groups, aliases — cloned only with {@code --data}. */
    public static final Set<String> DATA_CLASSES = ci("Person", "inetOrgPerson", "organizationalPerson", "User",
        "groupOfNames", "Group", "dynamicGroup", "dynamicGroupAux", "aliasObject", "Alias", "organizationalRole",
        "Template", "Profile");

    /** Attributes the server computes or a client may not write, beyond what the schema flags say. */
    public static final Set<String> EXCLUDED_ATTRS = ci(
        "GUID", "createTimestamp", "modifyTimestamp", "creatorsName", "modifiersName", "revision", "localEntryID",
        "entryFlags", "subordinateCount", "structuralObjectClass", "objectVersion", "Obituary", "Back Link",
        "Reference", "Used By", "Bindery Property", "Bindery Object Restriction", "Bindery Type", "Cross Certificate Pair",
        "networkAddress", "lastLoginTime", "loginTime", "loginIntruderAddress", "loginIntruderAttempts", "loginIntruderResetTime",
        "Detect Intruder", "Intruder Attempt Reset Interval", "Intruder Lockout Reset Interval", "Lockout After Detection",
        "Login Intruder Limit", "loginDisabled", "passwordExpirationTime", "pwdChangedTime", "pwdAccountLockedTime",
        "nspmPasswordHistory", "nspmDistributionPassword", "userPassword", "nspmPasswordKey", "sasNDSPasswordWindow",
        "publicKey", "privateKey", "sASSecretStore", "sasLoginSecret", "sasLoginSecretKey", "sasEncryptionType",
        "sasNMASProductOptions", "ndspkiKeyMaterialDN", "ndspkiTreeCADN", "ndspkiTrustedRootCertificate",
        "DirXML-ShimAuthPassword", "DirXML-NamedPasswords", "DirXML-EncryptionKeys", "DirXML-ServerKeys",
        "DirXML-ServerGUID", "DirXML-MutualAuthPassword", "DirXML-State", "DirXML-StatusLog", "DirXML-LastLogTime",
        "DirXML-Act", "DirXML-Act1", "DirXML-Act2", "DirXML-Act3", "DirXML-Command", "DirXML-Response",
        "DirXML-ApplicationSchema", "DirXML-CacheLocation", "DirXML-ShimConfigInfo-lastRun",
        "sssActiveServerList", "Convergence", "lowConvergenceSyncInterval", "highConvergenceSyncInterval",
        "replica", "Partition Control", "Partition Creation Time", "Partition Status", "Replica Up To",
        "Synchronized Up To", "Purge Vector", "Local Received Up To", "Transitive Vector", "authorityRevocationList");

    /** Driver run-time state: out unless {@code --keep-driver-state}. */
    public static final Set<String> DRIVER_STATE_ATTRS = ci("DirXML-DriverStorage", "DirXML-PersistentData", "DirXML-PasswordSyncStatus");

    /** eDirectory-maintained inverses: the forward side is written, these follow. */
    public static final Set<String> INVERSE_ATTRS = ci("groupMembership", "equivalentToMe", "directReports", "higherPrivileges",
        "nrfMemberOf", "profileMembership", "membersOfTemplate", "Reference", "Back Link");

    /** Identity data attributes never cloned on a config clone ({@code DirXML-Associations} is data). */
    public static final Set<String> DATA_ATTRS = ci("DirXML-Associations");

    /** Attributes reported as "secrets the target must be given" when the source carries them. */
    public static final Set<String> SECRET_ATTRS = ci("DirXML-ShimAuthPassword", "DirXML-NamedPasswords", "DirXML-MutualAuthPassword");

    public static final String START_OPTION_ATTR = "DirXML-DriverStartOption";
    public static final String SERVER_LIST_ATTR = "DirXML-ServerList";
    public static final String MANUAL_START = Integer.toString(Vault.START_MANUAL);

    /** {@code cn=Security}: only these children are cloned (the rest is tree-bound). */
    public static final Set<String> SECURITY_CHILDREN = ci("Password Policies", "Default Notification Collection");

    /** The container the servers live in; never cloned. */
    public static boolean isServerSubtree(String dn) {
        String l = dn.toLowerCase(Locale.ROOT);
        return l.startsWith("ou=servers,") || l.equals("ou=servers");
    }

    /** True when the entry is one of the never-cloned families (by class, RBS, or the servers container). */
    public static boolean neverClone(Vault.Entry e, boolean withRbs) {
        if (isServerSubtree(e.dn)) {
            return true;
        }
        for (String oc : e.objectClasses()) {
            if (NEVER_CLONE_CLASSES.contains(oc)) {
                return true;
            }
            if (!withRbs && oc.toLowerCase(Locale.ROOT).startsWith(RBS_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isData(Vault.Entry e) {
        for (String oc : e.objectClasses()) {
            if (DATA_CLASSES.contains(oc)) {
                return true;
            }
        }
        return false;
    }

    /** Under {@code cn=Security}, only the listed children (and their subtrees) are cloned. */
    public static boolean isSecurityInternal(String dn) {
        String l = dn.toLowerCase(Locale.ROOT);
        if (!l.endsWith("cn=security")) {
            return false;
        }
        if (l.equals("cn=security")) {
            return false;
        }
        // the child directly under cn=Security decides
        String[] parts = dn.split("(?<!\\\\),");
        String child = parts[parts.length - 2].trim();
        int eq = child.indexOf('=');
        String value = eq < 0 ? child : child.substring(eq + 1);
        return !SECURITY_CHILDREN.contains(value);
    }

    public static boolean excludedAttribute(String attr, boolean keepDriverState) {
        return EXCLUDED_ATTRS.contains(attr) || INVERSE_ATTRS.contains(attr) || DATA_ATTRS.contains(attr)
            || (!keepDriverState && DRIVER_STATE_ATTRS.contains(attr));
    }

    private static Set<String> ci(String... names) {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.addAll(List.of(names));
        return s;
    }
}
