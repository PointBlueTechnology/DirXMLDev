package com.pointblue.dirxml.dev.deploy;

import java.util.List;
import java.util.Map;

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

    /** A vault CLI command that will change the vault. {@code --dry-run} is never a write. */
    public static boolean writesVault(String cmd, Map<String, List<String>> opts) {
        boolean yes = opts.containsKey("yes");
        boolean dryRun = opts.containsKey("dry-run");
        switch (cmd) {
            case "vault.deploy":
                return (yes || opts.containsKey("step")) && !dryRun;
            case "vault.rollback":
            case "vault.import-clone":
                return yes;
            default:
                return false;
        }
    }

    /** Operate commands that change the vault. Status, cache view, secrets list, and trace show/tail do not. */
    public static boolean mutatesVault(String cmd, String sub) {
        switch (cmd) {
            case "driver.start":
            case "driver.stop":
            case "driver.restart":
            case "driver.migrate":
            case "driver.resync":
            case "driver.submit":
                return true;
            case "driver.cache":
                return "clear".equals(sub);
            case "driver.secrets":
                return "set".equals(sub) || "remove".equals(sub);
            case "driver.trace":
                return "set".equals(sub) || "reset".equals(sub);
            default:
                return false;
        }
    }
}
