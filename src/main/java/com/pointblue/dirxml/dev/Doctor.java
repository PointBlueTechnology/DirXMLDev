package com.pointblue.dirxml.dev;

import com.pointblue.dirxml.dev.deploy.Environments;
import com.pointblue.dirxml.dev.deploy.Vault;
import com.pointblue.dirxml.dev.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * {@code idm doctor [--json] [--env NAME]} — preconditions for an agent or an
 * operator. Reports JDK 21 (the JVM the launcher selected), the DirXML
 * Simulator jar at the version this project pins, proprietary {@code lib/*.jar}
 * files, and — when an environments file is present — each environment's name,
 * tier, and whether url / bind / password / driver set are configured.
 * Passwords, bind DNs, and URLs are never printed. {@code --env} is the only
 * way a live LDAPS bind is attempted.
 */
public final class Doctor {

    /** Matches {@code pom.xml} {@code simulator.version} and the {@code bin/idm} default. */
    public static final String PROJECT_SIM_VERSION = "1.5.2";

    /**
     * The proprietary NetIQ/OpenText jars {@code pom.xml} puts on the system classpath.
     * {@code bin/require-engine.sh} and the {@code doctor} shell fallback list the same names.
     */
    public static final List<String> REQUIRED_JARS = List.of(
        "dirxml.jar",
        "dirxml_misc.jar",
        "nxsl.jar",
        "xp.jar",
        "CommonDriverShim.jar",
        "jclient.jar",
        "dhutil.jar",
        "XDS.jar",
        "js.jar",
        "ldap.jar");

    /** Classes the simulator's own doctor loads, plus the Novell LDAP client this repo binds with. */
    static final List<String> ENGINE_CLASSES = List.of(
        "com.novell.nds.dirxml.engine.rules.DirXMLScriptProcessor",
        "com.novell.nds.dirxml.engine.gcv.GCDefinitions",
        "com.novell.nds.dirxml.driver.XmlDocument",
        "com.novell.xml.dom.DocumentImpl",
        "novell.jclient.JCContext",
        "com.novell.ldap.LDAPConnection");

    private static final String SIM_CLASS = "com.pointblue.dirxml.sim.BatchRunner";

    /** Opens one LDAPS session. Tests substitute a fake; the default binds with {@link Vault#connect}. */
    public interface Probe {
        void connect(Environments.Environment env) throws Exception;
    }

    /** Inputs. Tests fill these; {@link #fromSystem} reads the launcher's system properties. */
    public static final class Request {
        public Path home = Path.of(System.getProperty("user.dir"));
        public String javaVersion = "";
        public String javaHome = "";
        /** Selected simulator version ({@code IDM_SIM_VERSION}, else {@link #PROJECT_SIM_VERSION}). */
        public String simVersion = PROJECT_SIM_VERSION;
        /** Null: the launcher's default path under {@code ~/.m2}. */
        public Path simJar;
        /** When true (the launcher sets {@code -Didm.launcher=1}), also load engine and simulator classes. */
        public boolean checkEngineClasses;
        /** Null uses this class's loader. Tests pass an empty loader so the check does not see Maven's classpath. */
        public ClassLoader classLoader;
        /** When non-null, this file only — missing means "not configured". */
        public Path environmentsFile;
        /** When false and {@link #environmentsFile} is null, do not search {@link Environments#candidates()}. */
        public boolean searchEnvironments = true;
        /** Null skips the LDAPS probe. */
        public String probeEnv;
        /** Null uses {@link Vault#connect}. */
        public Probe probe;

        public Path simJar() {
            if (simJar != null) {
                return simJar;
            }
            String ver = simVersion == null || simVersion.isBlank() ? PROJECT_SIM_VERSION : simVersion;
            return Path.of(System.getProperty("user.home"), ".m2", "repository",
                "com", "pointblue", "dirxml", "dirxml-simulator", ver,
                "dirxml-simulator-" + ver + ".jar");
        }

        public Path libDir() {
            return home.resolve("lib");
        }
    }

    public static final class Report {
        public final boolean ok;
        final List<Check> checks;

        Report(boolean ok, List<Check> checks) {
            this.ok = ok;
            this.checks = checks;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("DirXML Dev — doctor\n");
            for (Check c : checks) {
                sb.append("  ").append(c.line).append('\n');
                for (String note : c.notes) {
                    sb.append("    ").append(note).append('\n');
                }
            }
            sb.append(ok ? "DOCTOR: OK\n" : "DOCTOR: PROBLEMS FOUND\n");
            return sb.toString();
        }

        public String json() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("ok", ok);
            List<Object> list = new ArrayList<>();
            for (Check c : checks) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", c.name);
                o.put("ok", c.ok);
                o.putAll(c.fields);
                list.add(o);
            }
            root.put("checks", list);
            return Json.compact(root);
        }

        Check check(String name) {
            for (Check c : checks) {
                if (c.name.equals(name)) {
                    return c;
                }
            }
            return null;
        }
    }

    static final class Check {
        final String name;
        final boolean ok;
        final String line;
        final List<String> notes;
        final Map<String, Object> fields;

        Check(String name, boolean ok, String line, List<String> notes, Map<String, Object> fields) {
            this.name = name;
            this.ok = ok;
            this.line = line;
            this.notes = notes;
            this.fields = fields;
        }
    }

    private Doctor() {
    }

    public static int run(String[] args) throws Exception {
        boolean json = false;
        String env = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--json")) {
                json = true;
            } else if (args[i].equals("--env") && i + 1 < args.length) {
                env = args[++i];
            } else {
                System.err.println("usage: doctor [--json] [--env NAME]");
                return 2;
            }
        }
        Report r = check(fromSystem(env));
        System.out.print(json ? r.json() + "\n" : r.text());
        return r.ok ? 0 : 1;
    }

    /** Launcher contract: {@code -Didm.home}, {@code -Didm.sim.version}, {@code -Didm.sim.jar}, {@code -Didm.launcher=1}. */
    public static Request fromSystem(String probeEnv) {
        Request r = new Request();
        r.home = Path.of(System.getProperty("idm.home", System.getProperty("user.dir")));
        r.javaVersion = System.getProperty("java.version", "");
        r.javaHome = System.getProperty("java.home", "");
        String sim = System.getProperty("idm.sim.version");
        r.simVersion = sim == null || sim.isBlank() ? PROJECT_SIM_VERSION : sim.trim();
        String jar = System.getProperty("idm.sim.jar");
        if (jar != null && !jar.isBlank()) {
            r.simJar = Path.of(jar);
        }
        r.checkEngineClasses = "1".equals(System.getProperty("idm.launcher"));
        r.probeEnv = probeEnv;
        return r;
    }

    public static Report check(Request req) {
        List<Check> checks = new ArrayList<>();
        checks.add(jdk(req));
        checks.add(simulator(req));
        checks.add(lib(req));
        Environments loaded = null;
        Path envFile = null;
        try {
            envFile = resolveEnvironments(req);
            if (envFile != null) {
                loaded = Environments.load(envFile);
            }
        } catch (IOException e) {
            checks.add(environmentsUnreadable(e.getMessage()));
            checks.add(ldapsSkippedBecause(req, "environments file could not be read"));
            return finish(checks);
        }
        checks.add(environments(loaded, envFile));
        checks.add(ldaps(req, loaded));
        return finish(checks);
    }

    private static Report finish(List<Check> checks) {
        boolean ok = true;
        for (Check c : checks) {
            ok &= c.ok;
        }
        return new Report(ok, checks);
    }

    static boolean isJdk21(String version) {
        return version != null && (version.equals("21") || version.startsWith("21."));
    }

    private static Check jdk(Request req) {
        String version = req.javaVersion == null ? "" : req.javaVersion;
        String home = req.javaHome == null ? "" : req.javaHome;
        boolean ok = isJdk21(version);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", version);
        fields.put("home", home);
        if (ok) {
            fields.put("detail", "JDK 21 selected");
            return new Check("jdk", true, "jdk: OK  " + version + "  (" + home + ")", List.of(), fields);
        }
        String detail = "DirXMLDev and the simulator require JDK 21 (the 4.10.1 engine jars are Java 21 bytecode). "
            + "Set IDM_JAVA_HOME to a JDK 21 home. The launcher also accepts SIM_JAVA_HOME, "
            + "/usr/libexec/java_home -v 21, a JAVA_HOME that is 21, /usr/lib/jvm/*21*, and java on PATH when that java is 21.";
        fields.put("detail", detail);
        String shown = version.isBlank() ? "(none)" : version;
        String at = home.isBlank() ? "" : " at " + home;
        return new Check("jdk", false, "jdk: FAIL  " + shown + at, List.of(detail), fields);
    }

    private static Check simulator(Request req) {
        String version = req.simVersion == null || req.simVersion.isBlank() ? PROJECT_SIM_VERSION : req.simVersion;
        Path jar = req.simJar();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("expected", version);
        fields.put("projectPin", PROJECT_SIM_VERSION);
        fields.put("path", jar.toString());
        List<String> notes = new ArrayList<>();
        if (!version.equals(PROJECT_SIM_VERSION)) {
            notes.add("IDM_SIM_VERSION selects " + version + "; this project pins " + PROJECT_SIM_VERSION + ".");
        }
        if (!Files.isRegularFile(jar)) {
            String detail = "Install the DirXML Simulator and run 'mvn install' in that repo so "
                + jar + " exists. The launcher reads IDM_SIM_VERSION (default " + PROJECT_SIM_VERSION
                + ", the version in pom.xml). See docs/install.md section 2.";
            notes.add(detail);
            fields.put("detail", detail);
            return new Check("simulator", false, "simulator: FAIL  " + version + " missing", notes, fields);
        }
        String embedded;
        try {
            embedded = embeddedVersion(jar);
        } catch (IOException e) {
            String detail = jar + " is not a readable dirxml-simulator jar (" + e.getMessage()
                + "). Reinstall with 'mvn install' in the DirXMLSimulator repo.";
            notes.add(detail);
            fields.put("detail", detail);
            return new Check("simulator", false, "simulator: FAIL  " + version + " unreadable", notes, fields);
        }
        fields.put("jarVersion", embedded == null ? "" : embedded);
        if (embedded == null) {
            String detail = "No META-INF/maven/com.pointblue.dirxml/dirxml-simulator/pom.properties in " + jar
                + ". Expected a jar produced by 'mvn install' of dirxml-simulator " + version + ".";
            notes.add(detail);
            fields.put("detail", detail);
            return new Check("simulator", false, "simulator: FAIL  " + version + " has no simulator version", notes, fields);
        }
        if (!embedded.equals(version)) {
            String detail = "Jar version is " + embedded + " but the launcher selected " + version
                + " (project pin " + PROJECT_SIM_VERSION + "). Set IDM_SIM_VERSION to " + embedded
                + " or install dirxml-simulator-" + version + ".jar.";
            notes.add(detail);
            fields.put("detail", detail);
            return new Check("simulator", false, "simulator: FAIL  jar " + embedded + ", selected " + version, notes, fields);
        }
        if (req.checkEngineClasses && !classLoads(req, SIM_CLASS)) {
            String detail = "The jar is on disk but " + SIM_CLASS + " did not load. Run doctor through bin/idm "
                + "so the simulator jar is on the classpath.";
            notes.add(detail);
            fields.put("detail", detail);
            return new Check("simulator", false, "simulator: FAIL  " + version + " not on the classpath", notes, fields);
        }
        fields.put("detail", "dirxml-simulator " + version);
        return new Check("simulator", true, "simulator: OK  " + version + "  (" + jar + ")", notes, fields);
    }

    private static Check lib(Request req) {
        Path dir = req.libDir();
        List<String> missing = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> links = new ArrayList<>();
        boolean directoryLink = Files.isSymbolicLink(dir);
        if (!directoryLink) {
            for (String name : REQUIRED_JARS) {
                Path jar = dir.resolve(name);
                if (Files.isSymbolicLink(jar)) {
                    links.add(name);
                } else if (Files.isRegularFile(jar)) {
                    present.add(name);
                } else {
                    missing.add(name);
                }
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("directory", dir.toString());
        fields.put("present", new ArrayList<Object>(present));
        fields.put("missing", new ArrayList<Object>(missing));
        fields.put("symlinks", new ArrayList<Object>(links));
        if (directoryLink || !links.isEmpty() || !missing.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            if (directoryLink) {
                detail.append("lib is a directory symlink. ");
            }
            if (!links.isEmpty()) {
                detail.append("Symlinked jars: ").append(String.join(", ", links)).append(". ");
            }
            if (!missing.isEmpty()) {
                detail.append("Missing ").append(String.join(", ", missing)).append(". ");
            }
            detail.append("Maven's requireFilesExist check compares each path with its canonical path and ")
                .append("reports a directory symlink of lib/, or a symlink of a jar, as missing. ")
                .append("Copy the proprietary NetIQ/OpenText IDM jars into a real ").append(dir)
                .append(" directory. They come from an IDM engine ")
                .append("(/opt/novell/eDirectory/lib/dirxml/classes/) or a Designer install, and they are gitignored. ")
                .append("See docs/install.md section 2.");
            String text = detail.toString();
            fields.put("detail", text);
            String summary = directoryLink || !links.isEmpty()
                ? "lib: FAIL  symlink is not a Maven file"
                : "lib: FAIL  missing " + String.join(", ", missing);
            return new Check("lib", false, summary, List.of(text), fields);
        }
        if (req.checkEngineClasses) {
            List<String> unloaded = new ArrayList<>();
            for (String c : ENGINE_CLASSES) {
                if (!classLoads(req, c)) {
                    unloaded.add(c);
                }
            }
            if (!unloaded.isEmpty()) {
                String detail = "The jar files are present but these classes did not load: "
                    + String.join(", ", unloaded) + ". bin/idm puts lib/*.jar on the classpath; "
                    + "the jars may be the wrong build (this project expects the 4.10.1 engine set).";
                fields.put("detail", detail);
                fields.put("unloaded", new ArrayList<Object>(unloaded));
                return new Check("lib", false, "lib: FAIL  classes did not load", List.of(detail), fields);
            }
        }
        fields.put("detail", present.size() + " jars in " + dir);
        return new Check("lib", true, "lib: OK  " + present.size() + " jars", List.of(), fields);
    }

    private static Path resolveEnvironments(Request req) {
        if (req.environmentsFile != null) {
            return Files.isRegularFile(req.environmentsFile) ? req.environmentsFile : null;
        }
        if (!req.searchEnvironments) {
            return null;
        }
        for (Path p : Environments.candidates()) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static Check environments(Environments loaded, Path file) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (loaded == null) {
            String detail = "No environments file (optional). Set IDM_ENVIRONMENTS or create environments.properties "
                + "(or ~/.idm/environments.properties). See docs/vault-deploy.md.";
            fields.put("configured", false);
            fields.put("detail", detail);
            fields.put("environments", List.of());
            return new Check("environments", true, "environments: OK  none configured (optional)", List.of(detail), fields);
        }
        List<Environments.Described> described = loaded.describe();
        fields.put("configured", true);
        fields.put("file", file.toString());
        List<Object> envs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (Environments.Described d : described) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", d.name);
            e.put("tier", d.tier);
            e.put("tierRecognized", d.tierRecognized);
            e.put("url", d.urlPresent);
            e.put("bindDn", d.bindDnPresent);
            e.put("password", d.passwordConfigured);
            e.put("driverSet", d.driverSetPresent);
            envs.add(e);
            notes.add(d.name + "  tier=" + d.tier + (d.tierRecognized ? "" : " (want dev|stg|prd)")
                + "  url=" + yn(d.urlPresent) + "  bind=" + yn(d.bindDnPresent)
                + "  password=" + yn(d.passwordConfigured) + "  driverSet=" + yn(d.driverSetPresent));
        }
        fields.put("environments", envs);
        fields.put("detail", described.size() + " environment(s) in " + file);
        return new Check("environments", true,
            "environments: OK  " + described.size() + " in " + file, notes, fields);
    }

    private static Check environmentsUnreadable(String message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("configured", false);
        fields.put("detail", message == null ? "environments file could not be read" : message);
        fields.put("environments", List.of());
        return new Check("environments", false, "environments: FAIL  " + fields.get("detail"), List.of(), fields);
    }

    private static Check ldaps(Request req, Environments loaded) {
        if (req.probeEnv == null || req.probeEnv.isBlank()) {
            return ldapsNotRequested();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requested", true);
        fields.put("env", req.probeEnv);
        if (loaded == null) {
            String detail = "No environments file, so --env " + req.probeEnv + " cannot be probed. "
                + "Set IDM_ENVIRONMENTS or create environments.properties.";
            fields.put("detail", detail);
            return new Check("ldaps", false, "ldaps: FAIL  " + req.probeEnv + " (no environments file)", List.of(detail), fields);
        }
        Environments.Environment env;
        try {
            env = loaded.get(req.probeEnv);
        } catch (IOException e) {
            String detail = e.getMessage() == null ? "environment could not be loaded" : e.getMessage();
            fields.put("detail", detail);
            return new Check("ldaps", false, "ldaps: FAIL  " + req.probeEnv, List.of(detail), fields);
        }
        String host = endpoint(env.url);
        fields.put("host", host);
        Probe probe = req.probe == null ? DEFAULT_PROBE : req.probe;
        try {
            probe.connect(env);
        } catch (Exception e) {
            String detail = failureDetail(env, e);
            fields.put("detail", detail);
            return new Check("ldaps", false, "ldaps: FAIL  " + req.probeEnv + "  " + host, List.of(detail), fields);
        }
        fields.put("detail", "connected");
        return new Check("ldaps", true, "ldaps: OK  " + req.probeEnv + " connected (" + host + ")", List.of(), fields);
    }

    private static Check ldapsNotRequested() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requested", false);
        fields.put("detail", "Pass --env <name> to open one LDAPS connection.");
        return new Check("ldaps", true, "ldaps: OK  not requested (pass --env <name> to probe)", List.of(), fields);
    }

    private static Check ldapsSkippedBecause(Request req, String why) {
        if (req.probeEnv == null || req.probeEnv.isBlank()) {
            return ldapsNotRequested();
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requested", true);
        fields.put("env", req.probeEnv);
        fields.put("detail", why);
        return new Check("ldaps", false, "ldaps: FAIL  " + why, List.of(why), fields);
    }

    private static final Probe DEFAULT_PROBE = env -> {
        try (Vault vault = Vault.connect(env.vaultConfig())) {
            // A successful bind is the probe. The connection is closed here.
        }
    };

    /** Host:port only. Userinfo in the URL is dropped so a credential there is never printed. */
    static String endpoint(String url) {
        if (url == null || url.isBlank()) {
            return "(no url)";
        }
        String rest = url.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        return rest.isBlank() ? "(unparsed url)" : rest;
    }

    static String failureDetail(Environments.Environment env, Throwable error) {
        String msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        msg = redact(msg, env.password);
        msg = redact(msg, env.url);
        msg = redact(msg, env.bindDn);
        return "LDAPS connect to " + endpoint(env.url) + " failed: " + msg;
    }

    static String redact(String message, String secret) {
        if (message == null) {
            return "";
        }
        if (secret == null || secret.isEmpty()) {
            return message;
        }
        return message.replace(secret, "***");
    }

    private static String embeddedVersion(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("META-INF/maven/com.pointblue.dirxml/dirxml-simulator/pom.properties");
            if (entry == null) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream in = jf.getInputStream(entry)) {
                props.load(in);
            }
            String v = props.getProperty("version");
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    private static boolean classLoads(Request req, String name) {
        ClassLoader loader = req.classLoader == null ? Doctor.class.getClassLoader() : req.classLoader;
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String yn(boolean v) {
        return v ? "yes" : "no";
    }
}
