package com.pointblue.dirxml.dev.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Vault targets, from a local, gitignored {@code environments.properties}
 * (the path in {@code IDM_ENVIRONMENTS}, else the working directory, else
 * {@code ~/.idm/environments.properties}):
 * <pre>
 *   stg.url=ldaps://idm-stg:636
 *   stg.bindDn=cn=idm-deploy,ou=sa,o=system
 *   stg.password=…              # or stg.passwordEnv=VAR | stg.passwordCommand=cmd | stg.passwordKeychain=service[/account]
 *                               # (see SecretSource; prefer an indirect form for stg/prd)
 *   stg.driverSet=cn=driverset1,o=system
 *   stg.tier=stg                # dev | stg | prd
 *   stg.secrets=secrets-stg.properties   # optional; see Secrets
 *   prd.requires=stg            # optional: a green STG deploy of the same commit first
 *   stg.sshHost=idm-stg          # optional: the engine host, for driver.trace tail (key-based ssh)
 *   stg.eventsUrl=jdbc:postgresql://db:5432/idmEvent   # optional: the Event Logger's store (docs/event-store.md)
 *   stg.eventsUser=eventlogger_reader                  #   read-only account; eventsPassword[Env|Command|Keychain]=
 *   stg.eventsTable=public.dxmlevent                   #   default; eventsPseudonymise=true masks people in output
 *   stg.eventsTree=TREE                                #   the tree name DNs in the store start with (default: from the vault)
 *   stg.sshUser=root
 * </pre>
 * A hosted server keeps two files (DirXMLDevWeb's docs/multi-user.md): the project's
 * <em>definitions</em> (every key above except the secret ones) and one person's
 * <em>credentials</em> (their {@code bindDn}, {@code password…}, {@code trustAll},
 * {@code eventsPassword…}, or whole environments of their own). {@link #load(Path, Path)} merges
 * them, the person's keys over the definitions', and refuses a definitions file that holds a
 * secret key.
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
        /** The Event Logger's store, or null when the environment has none (docs/event-store.md). */
        public EventsConfig events;

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

    /** Names the definitions file holds, when two files were merged; null for one file. */
    private final Set<String> defined;
    /** The directory a relative {@code <env>.secrets} resolves against. */
    private final Path secretsDir;

    private Environments(Properties props, Path file) {
        this(props, file, null, file == null ? null : file.toAbsolutePath().getParent());
    }

    private Environments(Properties props, Path file, Set<String> defined, Path secretsDir) {
        this.props = props;
        this.file = file;
        this.defined = defined;
        this.secretsDir = secretsDir;
    }

    /** A key that holds, or points at, a secret: {@code <env>.password}, {@code .eventsPassword}, {@code .appsPassword}, each with its {@code Env|Command|Keychain} form. */
    private static final Pattern SECRET_KEY = Pattern.compile("[^.]+\\.[A-Za-z0-9_]*[pP]assword(Env|Command|Keychain)?");

    /** The secret keys a file holds, sorted; empty when it holds none or does not exist. */
    public static List<String> secretKeys(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        for (String k : Secrets.parse(Files.readString(file, StandardCharsets.UTF_8)).stringPropertyNames()) {
            if (SECRET_KEY.matcher(k).matches()) {
                out.add(k);
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * The definitions file merged with one person's credentials file: a key in the credentials
     * file replaces the definition's; a name only in the credentials file is that person's own.
     * The definitions file must hold no secret key ({@link #secretKeys}); the credentials file
     * may be absent. A relative {@code <env>.secrets} resolves beside the definitions file.
     */
    public static Environments load(Path definitions, Path credentials) throws IOException {
        List<String> secret = secretKeys(definitions);
        if (!secret.isEmpty()) {
            throw new IOException("the environment definitions " + definitions + " hold secret keys that belong in a person's credentials file: " + String.join(", ", secret));
        }
        Properties defs = Files.isRegularFile(definitions) ? Secrets.parse(Files.readString(definitions, StandardCharsets.UTF_8)) : new Properties();
        Properties merged = new Properties();
        merged.putAll(defs);
        if (credentials != null && Files.isRegularFile(credentials)) {
            SecretSource.warnIfShared(credentials);
            merged.putAll(Secrets.parse(Files.readString(credentials, StandardCharsets.UTF_8)));
        }
        Set<String> defined = new TreeSet<>();
        for (String k : defs.stringPropertyNames()) {
            int dot = k.indexOf('.');
            if (dot > 0) {
                defined.add(k.substring(0, dot));
            }
        }
        return new Environments(merged, credentials == null ? definitions : credentials, defined, definitions.toAbsolutePath().getParent());
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
        SecretSource.warnIfShared(file);
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

    /**
     * One environment as {@code doctor} reports it: names, tier, and whether the
     * connection fields are configured. The password is never read or copied —
     * {@link #passwordConfigured} is only "a literal or an indirect form is present".
     */
    public static final class Described {
        public final String name;
        public final String tier;
        public final boolean tierRecognized;
        public final boolean urlPresent;
        public final boolean bindDnPresent;
        public final boolean passwordConfigured;
        public final boolean driverSetPresent;
        /** Named only in the person's credentials file (two-file form); false for one file. */
        public final boolean own;

        Described(String name, String tier, boolean tierRecognized, boolean urlPresent,
                  boolean bindDnPresent, boolean passwordConfigured, boolean driverSetPresent) {
            this(name, tier, tierRecognized, urlPresent, bindDnPresent, passwordConfigured, driverSetPresent, false);
        }

        Described(String name, String tier, boolean tierRecognized, boolean urlPresent,
                  boolean bindDnPresent, boolean passwordConfigured, boolean driverSetPresent, boolean own) {
            this.own = own;
            this.name = name;
            this.tier = tier;
            this.tierRecognized = tierRecognized;
            this.urlPresent = urlPresent;
            this.bindDnPresent = bindDnPresent;
            this.passwordConfigured = passwordConfigured;
            this.driverSetPresent = driverSetPresent;
        }
    }

    /** Where an environment's event store is and how to read it; the reader account only ever selects. */
    public static final class EventsConfig {
        public final String url;
        public final String user;
        public final String password;
        public final String table;
        public final boolean pseudonymise;
        public final String tree;   // may be null: taken from the vault

        public EventsConfig(String url, String user, String password, String table, boolean pseudonymise, String tree) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = table;
            this.pseudonymise = pseudonymise;
            this.tree = tree;
        }
    }

    /** Every environment in the file, without resolving secrets. */
    public List<Described> describe() {
        List<Described> out = new ArrayList<>();
        for (String name : names()) {
            String tierRaw = props.getProperty(name + ".tier");
            String tier = tierRaw == null || tierRaw.isBlank() ? "dev" : tierRaw.trim().toLowerCase();
            boolean recognized = tier.equals("dev") || tier.equals("stg") || tier.equals("prd");
            out.add(new Described(name, tier, recognized,
                present(name, "url"), present(name, "bindDn"),
                SecretSource.has(props, name + ".password"), present(name, "driverSet"),
                defined != null && !defined.contains(name)));
        }
        return out;
    }

    private boolean present(String name, String key) {
        String v = props.getProperty(name + "." + key);
        return v != null && !v.isBlank();
    }

    public Environment get(String name) throws IOException {
        String url = req(name, "url");
        String bindDn = req(name, "bindDn");
        char[] pw = SecretSource.resolve(props, name + ".password", "environment '" + name + "'");
        if (pw == null || pw.length == 0) {
            throw new IOException("environment '" + name + "': set " + name + ".password, .passwordEnv, .passwordCommand or .passwordKeychain");
        }
        String password = new String(pw);
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
            : (secretsDir == null ? Paths.get(secrets) : secretsDir.resolve(secrets));
        boolean trustAll = "true".equals(props.getProperty(name + ".trustAll"));   // opt in; TLS is verified otherwise
        String sshHost = props.getProperty(name + ".sshHost");
        String sshUser = props.getProperty(name + ".sshUser");
        Environment env = new Environment(name, url, bindDn, password, driverSet, tier,
            requires == null || requires.isBlank() ? null : requires.trim(), secretsFile, trustAll,
            sshHost == null || sshHost.isBlank() ? null : sshHost.trim(),
            sshUser == null || sshUser.isBlank() ? null : sshUser.trim());
        env.events = eventsOf(name);
        return env;
    }

    /** The event store settings, or null without {@code <name>.eventsUrl}. */
    private EventsConfig eventsOf(String name) throws IOException {
        String url = props.getProperty(name + ".eventsUrl");
        if (url == null || url.isBlank()) {
            return null;
        }
        url = url.trim();
        if (!url.startsWith("jdbc:")) {
            url = "jdbc:postgresql://" + url;
        }
        String user = props.getProperty(name + ".eventsUser", "eventlogger_reader").trim();
        char[] pw = SecretSource.has(props, name + ".eventsPassword") ? SecretSource.resolve(props, name + ".eventsPassword", "environment '" + name + "' event store") : null;
        String table = props.getProperty(name + ".eventsTable", "public.dxmlevent").trim();
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            throw new IOException("environment '" + name + "': eventsTable must be a plain [schema.]table name");
        }
        boolean pseud = "true".equals(props.getProperty(name + ".eventsPseudonymise", "").trim());
        String tree = props.getProperty(name + ".eventsTree");
        return new EventsConfig(url, user, pw == null ? "" : new String(pw), table, pseud, tree == null || tree.isBlank() ? null : tree.trim());
    }

    /**
     * A credential other than the bind password ({@code <env>.<key>}, e.g. {@code appsPassword}),
     * in any of the {@link SecretSource} forms; null when not configured.
     */
    public char[] secret(String name, String key) throws IOException {
        return SecretSource.resolve(props, name + "." + key, "environment '" + name + "'");
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
