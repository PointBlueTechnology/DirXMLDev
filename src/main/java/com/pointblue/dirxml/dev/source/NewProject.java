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
    /**
     * The git package catalog ({@code --catalog}) whose jars fill the project's own package
     * catalog (milestone N3). Null keeps the pre-N3 behaviour: no {@code IdmPackage_}
     * objects and a note naming the packages Designer will therefore not associate.
     */
    public final java.nio.file.Path catalogDir;
    /**
     * A Designer install to take driver icons from, overriding the {@code IDM_DESIGNER}
     * environment variable / {@code designer} system property / default roots that
     * {@link DesignerInstall#resolve()} uses. Null means "resolve it".
     */
    public final java.nio.file.Path designerRoot;

    public NewProject(String vaultName, String vaultHost, String vaultUser, String serverName, String serverContext) {
        this(vaultName, vaultHost, vaultUser, serverName, serverContext, null, null);
    }

    public NewProject(String vaultName, String vaultHost, String vaultUser, String serverName, String serverContext,
                      java.nio.file.Path catalogDir, java.nio.file.Path designerRoot) {
        this.vaultName = blankToNull(vaultName);
        this.vaultHost = blankToNull(vaultHost);
        this.vaultUser = blankToNull(vaultUser);
        this.serverName = blankToNull(serverName);
        this.serverContext = blankToNull(serverContext);
        this.catalogDir = catalogDir;
        this.designerRoot = designerRoot;
    }

    /** Nothing given: Designer asks for host, user and password on its first connect. */
    public static NewProject defaults() {
        return new NewProject(null, null, null, null, null);
    }

    /** The same, plus the package catalog to write the project's own catalog from. */
    public NewProject withCatalog(java.nio.file.Path dir) {
        return new NewProject(vaultName, vaultHost, vaultUser, serverName, serverContext, dir, designerRoot);
    }

    /** The same, plus an explicit Designer install to copy driver icons from. */
    public NewProject withDesignerRoot(java.nio.file.Path root) {
        return new NewProject(vaultName, vaultHost, vaultUser, serverName, serverContext, catalogDir, root);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
