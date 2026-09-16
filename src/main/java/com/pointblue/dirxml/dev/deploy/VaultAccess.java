package com.pointblue.dirxml.dev.deploy;

import java.util.List;
import java.util.Map;

/**
 * Everything {@link Deployer}, {@link Plan} (for {@code --delete-driver}),
 * {@link Snapshot} and {@link VaultDiff#fromVault} need from a live vault.
 * {@link Vault} is {@code final} and wraps a real LDAP connection, so this
 * small seam — the same pattern as {@code Operate.Engine} in the operate
 * package — lets a test supply an in-memory fake instead of a live vault.
 * {@link Vault} implements this directly; nothing about its public API
 * changes.
 */
interface VaultAccess extends AutoCloseable {

    Vault.Entry read(String dn);

    boolean exists(String dn);

    /** {@code Vault.search}: e.g. a subtree search for {@code --delete-driver}. */
    List<Vault.Entry> search(String base, String filter, int scope);

    void add(String dn, List<String> objectClasses, Map<String, List<byte[]>> attrs);

    void addObjectClasses(String dn, List<String> classes);

    void replace(String dn, String attr, List<byte[]> values);

    void delete(String dn);

    int driverState(String driverDn);

    int driverStartOption(String driverDn);

    void restartDriver(String driverDn);

    void setDriverStartOption(String driverDn, int option);

    String waitForState(String driverDn, int wanted, int seconds);

    List<String> namedPasswords(String dn);

    void setNamedPassword(String dn, String name, String displayName, char[] value);

    @Override
    void close();
}
