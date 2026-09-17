package com.pointblue.dirxml.dev.source;

/**
 * The optional details {@code export-project --new} writes into a fresh Designer
 * project's {@code IdentityVault_} and {@code Server_} objects. Every field is
 * optional (the design note's §5.3 decision: optional flags, default none —
 * Designer asks for whatever is missing on its first connect).
 *
 * <p><b>No password is ever carried here.</b> The vault object is always written
 * with {@code IdentityVaultSavePassword=false} and no {@code IdentityVaultPassword}
 * attribute; there is deliberately no field for one.
 */
public final class NewProject {

    /** The {@code IdentityVault_} object's name (the eDirectory tree name), or null for the default. */
    public final String vaultName;
    /** {@code IdentityVaultHost}, or null to leave it for Designer to ask. */
    public final String vaultHost;
    /** {@code IdentityVaultUsername} (a DN), or null. */
    public final String vaultUser;
    /** The engine server's name; null writes no {@code Server_} at all. */
    public final String serverName;
    /** The engine server's {@code ServerContext} (the container DN it lives in), or null. */
    public final String serverContext;

    public NewProject(String vaultName, String vaultHost, String vaultUser, String serverName, String serverContext) {
        this.vaultName = blankToNull(vaultName);
        this.vaultHost = blankToNull(vaultHost);
        this.vaultUser = blankToNull(vaultUser);
        this.serverName = blankToNull(serverName);
        this.serverContext = blankToNull(serverContext);
    }

    /** Nothing given: Designer asks for host, user and password on its first connect. */
    public static NewProject defaults() {
        return new NewProject(null, null, null, null, null);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
