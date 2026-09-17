package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.forms.FormBuilderLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A Designer installation on this machine, found the way {@code form.edit} finds the
 * form builder ({@link FormBuilderLocator}): an explicit path first
 * ({@code IDM_DESIGNER} in the environment, or the {@code designer} system property),
 * then the platform's default install roots.
 *
 * <p>The one thing {@code export-project --new} takes out of an install is a driver
 * icon, and only for a driver the <b>tree</b> carries no icon of its own (see
 * {@code docs/designer-new-project.md} §7.2c — a tree read from a Designer project keeps
 * that project's icon, custom or not, and that one always wins). Designer's own vault
 * importer copies
 * {@code plugins/com.novell.core_<ver>/icons/iManager/<ApplicationType>.gif} to
 * {@code <driverId>_icon.gif} beside the {@code Driver_} CObject (verified byte for
 * byte against {@code ~/designer_workspace/test11pf}: its
 * {@code 1K4NAIPS_icon.gif} is {@code NrfApp.gif} and its {@code 83OISG7Y_icon.gif}
 * is {@code ActiveDirectory.gif}). Nothing else is ever read or copied out of the
 * install, and an icon taken from an install is never written back into a tree.
 */
public final class DesignerInstall {

    /** Where the icons live inside the install, relative to {@code plugins/}. */
    private static final String CORE_PLUGIN = "com.novell.core_";
    private static final String ICON_DIR = "icons/iManager";
    /** Designer's own catch-all icon, used when an application type has none. */
    public static final String FALLBACK_ICON = "GenericApp";

    /** The install root ({@code …/Designer}), or null when none was found. */
    public final Path root;
    /** How it was chosen: {@code env IDM_DESIGNER}, {@code property designer}, {@code default} or {@code none}. */
    public final String source;
    /** {@code <root>/plugins/com.novell.core_<ver>/icons/iManager}, or null. */
    public final Path iconDir;
    /** Plugins whose {@code icons/iManager} Designer consults before the core's (the provisioning plugin's NProv.gif is the one test11pf carries). */
    private static final String[] PREFERRED_PLUGINS = {"com.novell.prov.pal.integration_"};
    /** Icon directories in lookup order: the preferred plugins' first, the core's last. */
    private final java.util.List<Path> iconDirs;

    private DesignerInstall(Path root, String source, Path iconDir) {
        this(root, source, iconDir, iconDir == null ? java.util.List.of() : java.util.List.of(iconDir));
    }

    private DesignerInstall(Path root, String source, Path iconDir, java.util.List<Path> iconDirs) {
        this.root = root;
        this.source = source;
        this.iconDir = iconDir;
        this.iconDirs = iconDirs;
    }

    /** Nothing found — {@link #icon} always returns null. */
    public static DesignerInstall none() {
        return new DesignerInstall(null, "none", null);
    }

    /** Resolve from the environment, the {@code designer} system property, then the default roots. */
    public static DesignerInstall resolve() {
        String env = System.getenv("IDM_DESIGNER");
        if (env != null && !env.isBlank()) {
            return at(Paths.get(env.trim()), "env IDM_DESIGNER");
        }
        String prop = System.getProperty("designer");
        if (prop != null && !prop.isBlank()) {
            return at(Paths.get(prop.trim()), "property designer");
        }
        for (Path r : FormBuilderLocator.defaultRoots(FormBuilderLocator.Os.current())) {
            DesignerInstall d = at(r, "default");
            if (d.iconDir != null) {
                return d;
            }
        }
        return none();
    }

    /** Resolve against one explicit root (what the tests and {@code --designer} use). */
    public static DesignerInstall at(Path root, String source) {
        if (root == null || !Files.isDirectory(root)) {
            return none();
        }
        Path plugins = root.resolve("plugins");
        Path core = newestCorePlugin(plugins);
        Path icons = core == null ? null : core.resolve(ICON_DIR);
        if (icons == null || !Files.isDirectory(icons)) {
            return new DesignerInstall(root, source, null);
        }
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        for (String prefix : PREFERRED_PLUGINS) {
            Path p = newestPlugin(plugins, prefix);
            if (p != null && Files.isDirectory(p.resolve(ICON_DIR))) {
                dirs.add(p.resolve(ICON_DIR));
            }
        }
        dirs.add(icons);
        return new DesignerInstall(root, source, icons, dirs);
    }

    /** The lexically newest {@code plugins/<prefix>*} directory, or null. */
    private static Path newestPlugin(Path plugins, String prefix) {
        if (!Files.isDirectory(plugins)) {
            return null;
        }
        Path best = null;
        try (java.util.stream.Stream<Path> s = Files.list(plugins)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith(prefix) && Files.isDirectory(p)
                    && (best == null || n.compareTo(best.getFileName().toString()) > 0)) {
                    best = p;
                }
            }
        } catch (java.io.IOException e) {
            return null;
        }
        return best;
    }

    /** True when an icon could actually be copied from here. */
    public boolean hasIcons() {
        return iconDir != null;
    }

    /**
     * The icon file for an application type, falling back to {@code GenericApp.gif}
     * (Designer's own catch-all — {@code MultiDomainActiveDirectory}, for one, has no
     * icon of its own). Null when no install was found or neither file exists.
     */
    public Path icon(String applicationType) {
        return icon(applicationType, null);
    }

    /**
     * The same, also trying the Designer driver type's short name ({@code AD-Driver} → {@code AD.gif},
     * {@code SCIM-Driver} → {@code SCIM.gif}) — the names the iManager icon set uses for drivers
     * whose application type has no icon of its own.
     */
    public Path icon(String applicationType, String driverType) {
        return icon(applicationType, driverType, null);
    }

    /**
     * The same, with a third name to try: the driver type the driver's <b>base package</b>
     * declares — what gives a custom SCIM-based shim typed {@code [ANY]} the SCIM icon, as
     * Designer's own importer does (test11pf: Beeline, CyberArk).
     */
    public Path icon(String applicationType, String driverType, String basePackageDriverType) {
        if (iconDir == null) {
            return null;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        if (applicationType != null && !applicationType.isBlank() && !FALLBACK_ICON.equals(applicationType)) {
            names.add(applicationType);
        }
        for (String t : new String[] {driverType, basePackageDriverType}) {
            if (t != null && !t.isBlank() && !"[ANY]".equals(t)) {
                names.add(t.endsWith("-Driver") ? t.substring(0, t.length() - 7) : t);
                String app = ApplicationType.forDriverType(t);
                if (app != null && !FALLBACK_ICON.equals(app)) {
                    names.add(app);
                }
            }
        }
        for (String n : names) {
            for (Path dir : iconDirs) {
                Path p = dir.resolve(n + ".gif");
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        Path generic = iconDir.resolve(FALLBACK_ICON + ".gif");
        return Files.isRegularFile(generic) ? generic : null;
    }

    public String describe() {
        if (iconDir == null) {
            return root == null
                ? "no Designer install found (set IDM_DESIGNER or -Ddesigner=<installRoot>)"
                : root + ": no " + CORE_PLUGIN + "* plugin with " + ICON_DIR;
        }
        return root + " (" + source + ")";
    }

    /** The newest {@code com.novell.core_<version>} plugin directory under {@code plugins/}. */
    private static Path newestCorePlugin(Path plugins) {
        if (!Files.isDirectory(plugins)) {
            return null;
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> s = Files.list(plugins)) {
            s.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith(CORE_PLUGIN))
                .forEach(found::add);
        } catch (IOException e) {
            return null;
        }
        return found.stream().max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
    }
}
