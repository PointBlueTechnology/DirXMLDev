package com.pointblue.dirxml.dev.forms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Finds and checks the vendor form builder (OpenText's Electron "FormBuilder", shipped as
 * Designer plugins) and builds the exact command line Designer itself uses.
 *
 * <p>Designer's {@code FormCreateWizard} launches
 * {@code <exe> --filepath=<file> --locale=<lang> [--service=<ServiceRegistry.json>]} on every
 * platform (plus {@code --no-sandbox} on Linux); the file is read on start and written back in
 * place on save. Only the executable's location differs per OS:
 * <ul>
 *   <li>macOS: {@code plugins/com.mf.mac.cocoa.formbuilder_<ver>/lib/FormBuilder.app/Contents/MacOS/FormBuilder}</li>
 *   <li>Windows: {@code plugins/com.mf.win.win32.formbuilder_<ver>/lib/FormBuilder.exe}</li>
 *   <li>Linux: {@code plugins/com.mf.linux.gtk.formbuilder_<ver>/lib/formbuilder}</li>
 * </ul>
 * Resolution order: an explicit path ({@code IDM_FORMBUILDER} env, or the {@code formbuilder}
 * property) → the newest matching plugin under the known Designer install roots. The locator
 * never changes anything on disk; {@link Status#fixes} lists the one-time commands a person
 * runs (Gatekeeper quarantine on macOS, execute bits on Linux).
 */
public final class FormBuilderLocator {

    /** Which OS family we are resolving for (kept explicit so tests can cover all three). */
    public enum Os {
        MAC("com.mf.mac.cocoa.formbuilder", "FormBuilder.app/Contents/MacOS/FormBuilder"),
        WINDOWS("com.mf.win.win32.formbuilder", "FormBuilder.exe"),
        LINUX("com.mf.linux.gtk.formbuilder", "formbuilder");

        public final String pluginId;
        public final String exeInLib;

        Os(String pluginId, String exeInLib) {
            this.pluginId = pluginId;
            this.exeInLib = exeInLib;
        }

        public static Os current() {
            String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (n.contains("mac") || n.contains("darwin")) {
                return MAC;
            }
            if (n.contains("win")) {
                return WINDOWS;
            }
            return LINUX;
        }
    }

    /** The outcome of a resolution: where the builder is and whether it will launch. */
    public static final class Status {
        public final Os os;
        /** How the path was chosen: {@code env}, {@code property}, {@code designer:<root>} or {@code none}. */
        public final String source;
        public final Path executable;      // null when nothing was found
        public final boolean exists;
        public final boolean executable_;  // the file's execute bit (always true on Windows when it exists)
        public final boolean quarantined;  // macOS: com.apple.quarantine present on the .app
        public final List<String> fixes;   // one-time commands for a person to run; empty when ready

        Status(Os os, String source, Path executable, boolean exists, boolean canExecute, boolean quarantined, List<String> fixes) {
            this.os = os;
            this.source = source;
            this.executable = executable;
            this.exists = exists;
            this.executable_ = canExecute;
            this.quarantined = quarantined;
            this.fixes = Collections.unmodifiableList(fixes);
        }

        public boolean ready() {
            return exists && executable_ && !quarantined;
        }

        /** The app bundle (macOS) or the lib directory (others) the executable belongs to. */
        public Path home() {
            if (executable == null) {
                return null;
            }
            return os == Os.MAC ? appBundle(executable) : executable.getParent();
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (executable == null) {
                sb.append("form builder: not found (set IDM_FORMBUILDER or formbuilder=<path to ")
                  .append(os.exeInLib).append("> — it ships with Designer 4.8+ as plugin ")
                  .append(os.pluginId).append(")");
                return sb.toString();
            }
            sb.append("form builder: ").append(executable).append(" (").append(source).append(")");
            if (!exists) {
                sb.append("\n  MISSING");
            } else {
                if (!executable_) {
                    sb.append("\n  not executable");
                }
                if (quarantined) {
                    sb.append("\n  quarantined by Gatekeeper (macOS will say it cannot be verified)");
                }
            }
            if (!fixes.isEmpty()) {
                sb.append("\n  run once, by hand:");
                for (String f : fixes) {
                    sb.append("\n    ").append(f);
                }
            } else if (exists) {
                sb.append("\n  ready");
            }
            return sb.toString();
        }
    }

    private FormBuilderLocator() {
    }

    /** Resolve for the current OS using the process environment and the given properties. */
    public static Status resolve(Map<String, String> properties) {
        return resolve(Os.current(), System.getenv("IDM_FORMBUILDER"),
            properties == null ? null : properties.get("formbuilder"), defaultRoots(Os.current()));
    }

    /**
     * Resolve with everything explicit (what the tests use).
     *
     * @param envPath   the {@code IDM_FORMBUILDER} value or null
     * @param propPath  the {@code formbuilder} property or null
     * @param roots     Designer install roots to search (each is expected to contain {@code plugins/})
     */
    public static Status resolve(Os os, String envPath, String propPath, List<Path> roots) {
        Path exe = null;
        String source = "none";
        if (envPath != null && !envPath.isBlank()) {
            exe = normalize(os, Paths.get(envPath));
            source = "env IDM_FORMBUILDER";
        } else if (propPath != null && !propPath.isBlank()) {
            exe = normalize(os, Paths.get(propPath));
            source = "property formbuilder";
        } else {
            for (Path root : roots) {
                Path found = newestPlugin(root.resolve("plugins"), os.pluginId);
                if (found != null) {
                    exe = found.resolve("lib").resolve(os.exeInLib);
                    source = "designer " + root;
                    break;
                }
            }
        }
        return check(os, source, exe);
    }

    /** Inspect a candidate executable without touching it. */
    public static Status check(Os os, String source, Path exe) {
        if (exe == null) {
            return new Status(os, source, null, false, false, false, Collections.emptyList());
        }
        boolean exists = Files.isRegularFile(exe);
        boolean canExecute = exists && (os == Os.WINDOWS || Files.isExecutable(exe));
        boolean quarantined = false;
        if (exists && os == Os.MAC) {
            Path app = appBundle(exe);
            quarantined = hasQuarantine(app != null ? app : exe);
        }
        List<String> fixes = new ArrayList<>();
        if (exists) {
            if (os == Os.MAC) {
                Path app = appBundle(exe);
                String target = quote(app != null ? app : exe);
                if (quarantined) {
                    fixes.add("xattr -dr com.apple.quarantine " + target);
                }
                if (!canExecute) {
                    fixes.add("chmod -R a+x " + target);
                }
            } else if (os == Os.LINUX && !canExecute) {
                fixes.add("chmod -R a+x " + quote(exe.getParent()));
            }
        }
        return new Status(os, source, exe, exists, canExecute, quarantined, fixes);
    }

    /**
     * The command line, exactly as Designer builds it: executable, {@code --filepath=}, {@code --locale=},
     * optional {@code --service=}, and {@code --no-sandbox} on Linux.
     */
    public static List<String> command(Status status, Path formFile, String locale, Path serviceRegistry) {
        Objects.requireNonNull(status.executable, "no form builder executable");
        List<String> cmd = new ArrayList<>();
        cmd.add(status.executable.toString());
        cmd.add("--filepath=" + formFile.toAbsolutePath());
        cmd.add("--locale=" + (locale == null || locale.isBlank() ? "en_US" : locale));
        if (serviceRegistry != null) {
            cmd.add("--service=" + serviceRegistry.toAbsolutePath());
        }
        if (status.os == Os.LINUX) {
            cmd.add("--no-sandbox");
        }
        return cmd;
    }

    /** Designer install roots we look under when nothing is configured. */
    public static List<Path> defaultRoots(Os os) {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        switch (os) {
            case MAC:
                roots.add(Paths.get("/Applications/Designer"));
                roots.add(Paths.get(home, "Applications", "Designer"));
                break;
            case WINDOWS:
                roots.add(Paths.get("C:\\netiq\\idm\\apps\\Designer"));
                roots.add(Paths.get(home, "designer"));
                roots.add(Paths.get("C:\\Program Files\\NetIQ\\Designer"));
                break;
            default:
                roots.add(Paths.get("/opt/netiq/idm/apps/Designer"));
                roots.add(Paths.get(home, "designer"));
                roots.add(Paths.get("/opt/designer"));
        }
        return roots;
    }

    // ---- helpers ----

    /** Accept the .app bundle, the plugin dir or its lib dir as a configured path and return the executable. */
    static Path normalize(Os os, Path p) {
        if (Files.isRegularFile(p)) {
            return p;
        }
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        if (os == Os.MAC && name.endsWith(".app")) {
            return p.resolve("Contents").resolve("MacOS").resolve("FormBuilder");
        }
        if (name.equals("lib")) {
            return p.resolve(os.exeInLib);
        }
        if (Files.isDirectory(p.resolve("lib"))) {
            return p.resolve("lib").resolve(os.exeInLib);
        }
        return p.resolve(os.exeInLib);
    }

    /** The newest {@code <pluginId>_<version>} directory under {@code plugins}, or null. */
    static Path newestPlugin(Path plugins, String pluginId) {
        if (!Files.isDirectory(plugins)) {
            return null;
        }
        try (Stream<Path> s = Files.list(plugins)) {
            return s.filter(Files::isDirectory)
                .filter(d -> d.getFileName().toString().startsWith(pluginId + "_"))
                .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** The enclosing {@code *.app} directory of a macOS executable, or null. */
    static Path appBundle(Path exe) {
        for (Path p = exe; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().endsWith(".app")) {
                return p;
            }
        }
        return null;
    }

    /** True when macOS's quarantine attribute is set on the path (read via {@code xattr}; false if unavailable). */
    static boolean hasQuarantine(Path p) {
        try {
            Process proc = new ProcessBuilder("xattr", "-p", "com.apple.quarantine", p.toString())
                .redirectErrorStream(true).start();
            byte[] out = proc.getInputStream().readAllBytes();
            int rc = proc.waitFor();
            return rc == 0 && out.length > 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String quote(Path p) {
        String s = p.toString();
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }
}
