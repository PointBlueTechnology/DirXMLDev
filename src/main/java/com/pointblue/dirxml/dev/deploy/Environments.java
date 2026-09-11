package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Vault targets, from a local, gitignored {@code environments.properties}
 * (the path in {@code IDM_ENVIRONMENTS}, else the working directory, else
 * {@code ~/.idm/environments.properties}):
 * <pre>
 *   stg.url=ldaps://idm-stg:636
 *   stg.bindDn=cn=idm-deploy,ou=sa,o=system
 *   stg.password=…              # or stg.passwordEnv=IDM_STG_PASSWORD
 *   stg.driverSet=cn=driverset1,o=system
 *   stg.tier=stg                # dev | stg | prd
 *   stg.secrets=secrets-stg.properties   # optional; see Secrets
 *   prd.requires=stg            # optional: a green STG deploy of the same commit first
 *   stg.sshHost=idm-stg          # optional: the engine host, for driver.trace tail (key-based ssh)
 *   stg.sshUser=root
 * </pre>
 */
public final class Environments {

    public enum Tier { DEV, STG, PRD }

    public static final class Environment {
        public final String name;
        public final String url;
        public final String bindDn;
        public final String password;
        public final String driverSetDn;
        public final Tier tier;
        public final String requires;     // an environment name, or null
        public final Path secretsFile;    // may be null
        public final boolean trustAll;
        public final String sshHost;      // may be null: no trace tail
        public final String sshUser;

        Environment(String name, String url, String bindDn, String password, String driverSetDn,
                    Tier tier, String requires, Path secretsFile, boolean trustAll, String sshHost, String sshUser) {
            this.name = name;
            this.url = url;
            this.bindDn = bindDn;
            this.password = password;
            this.driverSetDn = driverSetDn;
            this.tier = tier;
            this.requires = requires;
            this.secretsFile = secretsFile;
            this.trustAll = trustAll;
            this.sshHost = sshHost;
            this.sshUser = sshUser;
        }

        public Vault.Config vaultConfig() {
            Vault.Config c = new Vault.Config();
            c.url = url;
            c.bindDn = bindDn;
            c.password = password;
            c.trustAll = trustAll;
            return c;
        }

        @Override
        public String toString() {
            return name + " (" + tier.name().toLowerCase() + ", " + url + ", " + driverSetDn + ")";
        }
    }

    private final Properties props;
    private final Path file;

    private Environments(Properties props, Path file) {
        this.props = props;
        this.file = file;
    }

    /** Where the environments file is looked for, in order. */
    public static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String env = System.getenv("IDM_ENVIRONMENTS");
        if (env != null && !env.isBlank()) {
            out.add(Paths.get(env));
        }
        out.add(Paths.get("environments.properties"));
        out.add(Paths.get(System.getProperty("user.home"), ".idm", "environments.properties"));
        return out;
    }

    public static Environments load() throws IOException {
        for (Path p : candidates()) {
            if (Files.isRegularFile(p)) {
                return load(p);
            }
        }
        throw new IOException("no environments file: looked for " + candidates()
            + " (set IDM_ENVIRONMENTS or create environments.properties; see docs/vault-deploy.md)");
    }

    public static Environments load(Path file) throws IOException {
        // the same unescaped key=value format as the secrets file (DNs and passwords keep their backslashes)
        return new Environments(Secrets.parse(Files.readString(file, StandardCharsets.UTF_8)), file);
    }

    public Path file() {
        return file;
    }

    public List<String> names() {
        TreeSet<String> names = new TreeSet<>();
        for (String k : props.stringPropertyNames()) {
            int dot = k.indexOf('.');
            if (dot > 0) {
                names.add(k.substring(0, dot));
            }
        }
        return new ArrayList<>(names);
    }

    public Environment get(String name) throws IOException {
        String url = req(name, "url");
        String bindDn = req(name, "bindDn");
        String password = props.getProperty(name + ".password");
        String passwordEnv = props.getProperty(name + ".passwordEnv");
        if ((password == null || password.isBlank()) && passwordEnv != null && !passwordEnv.isBlank()) {
            password = System.getenv(passwordEnv);
            if (password == null) {
                throw new IOException("environment '" + name + "': " + name + ".passwordEnv names " + passwordEnv + ", which is not set");
            }
        }
        if (password == null || password.isBlank()) {
            throw new IOException("environment '" + name + "': set " + name + ".password or " + name + ".passwordEnv");
        }
        String driverSet = req(name, "driverSet");
        String tierS = props.getProperty(name + ".tier", "dev").trim().toUpperCase();
        Tier tier;
        try {
            tier = Tier.valueOf(tierS);
        } catch (IllegalArgumentException e) {
            throw new IOException("environment '" + name + "': tier must be dev|stg|prd, not '" + tierS + "'");
        }
        String requires = props.getProperty(name + ".requires");
        String secrets = props.getProperty(name + ".secrets");
        Path secretsFile = secrets == null || secrets.isBlank() ? null
            : (file == null ? Paths.get(secrets) : file.toAbsolutePath().getParent().resolve(secrets));
        boolean trustAll = !"false".equals(props.getProperty(name + ".trustAll"));
        String sshHost = props.getProperty(name + ".sshHost");
        String sshUser = props.getProperty(name + ".sshUser");
        return new Environment(name, url, bindDn, password, driverSet, tier,
            requires == null || requires.isBlank() ? null : requires.trim(), secretsFile, trustAll,
            sshHost == null || sshHost.isBlank() ? null : sshHost.trim(),
            sshUser == null || sshUser.isBlank() ? null : sshUser.trim());
    }

    /** Any other {@code <env>.<key>} value (e.g. {@code formsUrl}, {@code k8sHost}), trimmed; null when absent or blank. */
    public String property(String name, String key) {
        String v = props.getProperty(name + "." + key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private String req(String name, String key) throws IOException {
        String v = props.getProperty(name + "." + key);
        if (v == null || v.isBlank()) {
            if (!names().contains(name)) {
                throw new IOException("no environment '" + name + "' in " + file + "; environments: " + names());
            }
            throw new IOException("environment '" + name + "': missing " + name + "." + key);
        }
        return v.trim();
    }
}
