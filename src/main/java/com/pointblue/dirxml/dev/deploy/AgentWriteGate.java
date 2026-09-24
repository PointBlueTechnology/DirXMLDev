package com.pointblue.dirxml.dev.deploy;

/**
 * CLI gate for vault mutations ({@code vault.deploy --yes}/{@code --step},
 * {@code vault.rollback --yes}, {@code vault.import-clone --yes}, and the
 * operate commands that change a driver).
 *
 * <p>Allowed when {@code IDM_AGENT_ALLOW_WRITE=1} or when {@code --confirm}
 * equals the environment name (the confirmation a production deploy already
 * requires). {@code --dry-run} and read-only commands skip the gate. The CLI
 * checks this before it resolves a secret or opens LDAP, so a refused write
 * never contacts the vault. In-process tests of {@link Deployer} and
 * {@code Operate} keep the tier gates they already have.
 */
public final class AgentWriteGate {

    public static final String ENV = "IDM_AGENT_ALLOW_WRITE";

    private AgentWriteGate() {
    }

    /**
     * Null when the call may proceed. {@code allowWrite} is the value of
     * {@link #ENV} ({@code null} when unset); tests pass it directly.
     */
    public static String refusal(String envName, boolean mutating, String confirm, String allowWrite) {
        if (!mutating) {
            return null;
        }
        if ("1".equals(allowWrite)) {
            return null;
        }
        if (envName != null && envName.equals(confirm)) {
            return null;
        }
        String target = envName == null || envName.isBlank() ? "<env>" : envName;
        return "refusing vault write to '" + target + "': set " + ENV + "=1 or pass --confirm " + target;
    }
}
