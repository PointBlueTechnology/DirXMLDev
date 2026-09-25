package com.pointblue.dirxml.dev;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DoctorTest {

    private static final String PASSWORD = "s3cret-DOCTOR-TOKEN";
    private static final String BIND = "cn=bind-secret,ou=sa,o=system";
    private static final String URL = "ldaps://idm-stg.example:636";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void jdk21SimulatorAndJarsPassWithoutPrintingSecrets() throws Exception {
        Doctor.Request req = ready();
        Path env = writeEnvironments(""
            + "stg.url=" + URL + "\n"
            + "stg.bindDn=" + BIND + "\n"
            + "stg.password=" + PASSWORD + "\n"
            + "stg.driverSet=cn=driverset1,o=system\n"
            + "stg.tier=stg\n"
            + "lab.url=ldaps://lab.example:636\n"
            + "lab.bindDn=cn=lab,o=system\n"
            + "lab.passwordEnv=IDM_DOCTOR_NO_SUCH_VAR\n"
            + "lab.driverSet=cn=driverset1,o=system\n"
            + "lab.tier=qa\n");
        req.environmentsFile = env;

        Doctor.Report report = Doctor.check(req);
        String text = report.text();
        String json = report.json();
        assertTrue(text, report.ok);
        assertTrue(text, text.contains("jdk: OK  21.0.10"));
        assertTrue(text, text.contains("simulator: OK  " + Doctor.PROJECT_SIM_VERSION));
        assertTrue(text, text.contains("lib: OK  " + Doctor.REQUIRED_JARS.size() + " jars"));
        assertTrue(text, text.contains("stg  tier=stg  url=yes  bind=yes  password=yes  driverSet=yes"));
        assertTrue(text, text.contains("lab  tier=qa (want dev|stg|prd)  url=yes  bind=yes  password=yes  driverSet=yes"));
        assertTrue(text, text.contains("ldaps: OK  not requested"));
        assertTrue(text, text.contains("DOCTOR: OK"));
        assertFalse(text, text.contains(PASSWORD));
        assertFalse(json, json.contains(PASSWORD));
        assertFalse(text, text.contains(URL));
        assertFalse(text, text.contains(BIND));
        assertFalse(text, text.contains("IDM_DOCTOR_NO_SUCH_VAR"));
        assertTrue(json, json.contains("\"password\":true"));
        assertTrue(json, json.contains("\"url\":true"));
        assertTrue(json, json.contains("\"bindDn\":true"));
        assertTrue(json, json.contains("\"name\":\"stg\""));
        assertTrue(json, json.contains("\"tier\":\"qa\""));
        assertTrue(json, json.contains("\"tierRecognized\":false"));
    }

    @Test
    public void missingJarsAndSimulatorAreActionable() throws Exception {
        Doctor.Request req = base();
        req.simJar = tmp.newFolder("m2").toPath().resolve("missing.jar");
        Doctor.Report report = Doctor.check(req);
        String text = report.text();
        assertFalse(report.ok);
        assertTrue(text, text.contains("simulator: FAIL"));
        assertTrue(text, text.contains("mvn install"));
        assertTrue(text, text.contains("docs/install.md"));
        for (String jar : List.of("dirxml.jar", "ldap.jar", "nxsl.jar")) {
            assertTrue(text, text.contains(jar));
        }
        assertTrue(text, text.contains("DOCTOR: PROBLEMS FOUND"));
        assertTrue(report.json(), report.json().contains("\"ok\":false"));
    }

    @Test
    public void directorySymlinkIsNotAMavenLib() throws Exception {
        Doctor.Request req = ready();
        Path real = req.home.resolve("lib");
        Path moved = req.home.resolve("jars-real");
        Files.move(real, moved);
        Files.createSymbolicLink(real, moved);
        Doctor.Report report = Doctor.check(req);
        assertFalse(report.ok);
        assertTrue(report.text(), report.text().contains("directory symlink"));
        assertTrue(report.text(), report.text().contains("requireFilesExist"));
    }

    @Test
    public void perJarSymlinkIsNotAMavenLib() throws Exception {
        Doctor.Request req = ready();
        Path jar = req.home.resolve("lib").resolve("dirxml.jar");
        Path moved = req.home.resolve("dirxml-real.jar");
        Files.move(jar, moved);
        Files.createSymbolicLink(jar, moved);
        Doctor.Report report = Doctor.check(req);
        assertFalse(report.ok);
        assertTrue(report.text(), report.text().contains("dirxml.jar"));
        assertTrue(report.text(), report.text().contains("Symlinked jars"));
    }

    @Test
    public void jarVersionMustMatchTheSelectedSimulator() throws Exception {
        Doctor.Request req = base();
        req.simJar = simJar("1.5.1");
        writeJars(req.home);
        Doctor.Report mismatch = Doctor.check(req);
        assertFalse(mismatch.ok);
        assertTrue(mismatch.text(), mismatch.text().contains("jar 1.5.1"));
        assertTrue(mismatch.text(), mismatch.text().contains("selected " + Doctor.PROJECT_SIM_VERSION));

        Path bare = tmp.newFile("bare.jar").toPath();
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(bare), manifest())) {
            jos.putNextEntry(new JarEntry("readme.txt"));
            jos.write("no pom".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        req.simJar = bare;
        Doctor.Report noPom = Doctor.check(req);
        assertFalse(noPom.ok);
        assertTrue(noPom.text(), noPom.text().contains("pom.properties"));
    }

    @Test
    public void jdkOtherThan21Fails() throws Exception {
        assertFalse(Doctor.isJdk21("17.0.9"));
        assertFalse(Doctor.isJdk21("211"));
        assertTrue(Doctor.isJdk21("21"));
        assertTrue(Doctor.isJdk21("21.0.10"));

        Doctor.Request req = ready();
        req.javaVersion = "17.0.9";
        req.javaHome = "/usr/lib/jvm/java-17";
        Doctor.Report report = Doctor.check(req);
        assertFalse(report.ok);
        assertTrue(report.text(), report.text().contains("jdk: FAIL  17.0.9 at /usr/lib/jvm/java-17"));
        assertTrue(report.text(), report.text().contains("IDM_JAVA_HOME"));
        assertFalse(report.check("jdk").ok);
    }

    @Test
    public void absentEnvironmentsFileIsOptional() throws Exception {
        Doctor.Request req = ready();
        req.environmentsFile = tmp.getRoot().toPath().resolve("no-such.properties");
        Doctor.Report report = Doctor.check(req);
        assertTrue(report.text(), report.ok);
        assertTrue(report.text(), report.text().contains("none configured"));
        assertEquals(Boolean.FALSE, report.check("environments").fields.get("configured"));
    }

    @Test
    public void ldapsProbeRedactsPasswordUrlAndBindDn() throws Exception {
        Doctor.Request req = ready();
        req.environmentsFile = writeEnvironments(""
            + "stg.url=" + URL + "\n"
            + "stg.bindDn=" + BIND + "\n"
            + "stg.password=" + PASSWORD + "\n"
            + "stg.driverSet=cn=driverset1,o=system\n"
            + "stg.tier=stg\n");
        req.probeEnv = "stg";
        req.probe = env -> {
            throw new IOException("bind failed password=" + env.password + " url=" + env.url + " dn=" + env.bindDn);
        };

        Doctor.Report report = Doctor.check(req);
        String text = report.text();
        String json = report.json();
        assertFalse(report.ok);
        assertFalse(text, text.contains(PASSWORD));
        assertFalse(json, json.contains(PASSWORD));
        assertFalse(text, text.contains(URL));
        assertFalse(json, json.contains(URL));
        assertFalse(text, text.contains(BIND));
        assertFalse(json, json.contains(BIND));
        assertTrue(text, text.contains("idm-stg.example:636"));
        assertTrue(text, text.contains("***"));
        assertTrue(json, json.contains("\"requested\":true"));
        assertTrue(json, json.contains("\"host\":\"idm-stg.example:636\""));
    }

    @Test
    public void ldapsProbeSuccessNamesTheHostOnly() throws Exception {
        Doctor.Request req = ready();
        req.environmentsFile = writeEnvironments(""
            + "stg.url=ldaps://user:" + PASSWORD + "@idm-stg.example:636\n"
            + "stg.bindDn=" + BIND + "\n"
            + "stg.password=" + PASSWORD + "\n"
            + "stg.driverSet=cn=driverset1,o=system\n"
            + "stg.tier=dev\n");
        req.probeEnv = "stg";
        req.probe = env -> {
            assertEquals(PASSWORD, env.password);
        };
        Doctor.Report report = Doctor.check(req);
        assertTrue(report.text(), report.ok);
        assertTrue(report.text(), report.text().contains("ldaps: OK  stg connected (idm-stg.example:636)"));
        assertFalse(report.text(), report.text().contains(PASSWORD));
        assertFalse(report.json(), report.json().contains(PASSWORD));
        assertFalse(report.text(), report.text().contains("user:"));
        assertEquals("idm-stg.example:636", Doctor.endpoint("ldaps://user:" + PASSWORD + "@idm-stg.example:636/dc=x"));
    }

    @Test
    public void unknownEnvProbeFailsWithoutResolvingAnotherPassword() throws Exception {
        Doctor.Request req = ready();
        req.environmentsFile = writeEnvironments(""
            + "stg.url=" + URL + "\n"
            + "stg.bindDn=" + BIND + "\n"
            + "stg.password=" + PASSWORD + "\n"
            + "stg.driverSet=cn=driverset1,o=system\n");
        req.probeEnv = "missing";
        req.probe = env -> {
            throw new AssertionError("probe must not run");
        };
        Doctor.Report report = Doctor.check(req);
        assertFalse(report.ok);
        assertTrue(report.text(), report.text().contains("no environment 'missing'"));
        assertFalse(report.text(), report.text().contains(PASSWORD));
    }

    @Test
    public void launcherClassCheckFailsWhenJarsAreEmpty() throws Exception {
        Doctor.Request req = ready();
        req.checkEngineClasses = true;
        req.classLoader = new ClassLoader(null) { };
        Doctor.Report report = Doctor.check(req);
        assertFalse(report.ok);
        assertTrue(report.text(), report.text().contains("did not load"));
        assertTrue(report.text(), report.text().contains("com.pointblue.dirxml.sim.BatchRunner"));
    }

    @Test
    public void usageAndPinsMatchTheProject() throws Exception {
        assertEquals(2, Doctor.run(new String[] {"doctor", "--bogus"}));

        Path root = repoRoot();
        String pom = Files.readString(root.resolve("pom.xml"));
        assertTrue(pom.contains("<simulator.version>" + Doctor.PROJECT_SIM_VERSION + "</simulator.version>"));
        String jarsLine = "REQUIRED_JARS: " + String.join(" ", Doctor.REQUIRED_JARS);
        String require = Files.readString(root.resolve("bin/require-engine.sh"));
        assertTrue(require, require.contains(jarsLine));
        assertTrue(require, require.contains("IDM_SIM_VERSION:-" + Doctor.PROJECT_SIM_VERSION));
        String idm = Files.readString(root.resolve("bin/idm"));
        assertTrue(idm, idm.contains("IDM_SIM_VERSION:-" + Doctor.PROJECT_SIM_VERSION));
        for (String jar : Doctor.REQUIRED_JARS) {
            assertTrue(idm, idm.contains(jar));
        }
        String cmd = Files.readString(root.resolve("bin/idm.cmd"));
        assertTrue(cmd, cmd.contains("SIM_VER=1.6.0") || cmd.contains("set \"SIM_VER=1.6.0\"") || cmd.contains("%SIM_VER%"));
        assertTrue(cmd, cmd.contains("if \"%SIM_VER%\"==\"\" set \"SIM_VER=1.6.0\""));
    }

    @Test
    public void endpointDropsUserinfo() {
        assertEquals("idm.example:636", Doctor.endpoint("ldaps://cn=admin:pw@idm.example:636"));
        assertEquals("(no url)", Doctor.endpoint("  "));
    }

    private Doctor.Request ready() throws Exception {
        Doctor.Request req = base();
        writeJars(req.home);
        req.simJar = simJar(Doctor.PROJECT_SIM_VERSION);
        return req;
    }

    private Doctor.Request base() {
        Doctor.Request req = new Doctor.Request();
        req.home = tmp.getRoot().toPath();
        req.javaVersion = "21.0.10";
        req.javaHome = "/usr/lib/jvm/java-21";
        req.simVersion = Doctor.PROJECT_SIM_VERSION;
        req.searchEnvironments = false;
        req.checkEngineClasses = false;
        return req;
    }

    private void writeJars(Path home) throws IOException {
        Path lib = home.resolve("lib");
        Files.createDirectories(lib);
        for (String name : Doctor.REQUIRED_JARS) {
            Files.write(lib.resolve(name), new byte[] {0});
        }
    }

    private Path simJar(String version) throws IOException {
        Path jar = tmp.getRoot().toPath().resolve("dirxml-simulator-" + version + ".jar");
        String body = "version=" + version + "\ngroupId=com.pointblue.dirxml\nartifactId=dirxml-simulator\n";
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), manifest())) {
            JarEntry entry = new JarEntry("META-INF/maven/com.pointblue.dirxml/dirxml-simulator/pom.properties");
            jos.putNextEntry(entry);
            jos.write(body.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

    private static Manifest manifest() {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return mf;
    }

    private Path writeEnvironments(String body) throws IOException {
        Path f = tmp.newFile("environments.properties").toPath();
        Files.writeString(f, body, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(f, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows has no POSIX mode bits; the warning is irrelevant there.
        }
        return f;
    }

    private static Path repoRoot() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.readString(pom).contains("<artifactId>dirxml-dev</artifactId>")) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IOException("dirxml-dev pom.xml not found from " + System.getProperty("user.dir"));
    }
}
