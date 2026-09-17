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
 * icon: Designer's own vault importer copies
 * {@code plugins/com.novell.core_<ver>/icons/iManager/<ApplicationType>.gif} to
 * {@code <driverId>_icon.gif} beside the {@code Driver_} CObject (verified byte for
 * byte against {@code ~/designer_workspace/test11pf}: its
 * {@code 1K4NAIPS_icon.gif} is {@code NrfApp.gif} and its {@code 83OISG7Y_icon.gif}
 * is {@code ActiveDirectory.gif}). Nothing else is ever read or copied out of the
 * install, and an icon is never committed to a tree.
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

    private DesignerInstall(Path root, String source, Path iconDir) {
        this.root = root;
        this.source = source;
        this.iconDir = iconDir;
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
        return new DesignerInstall(root, source, icons);
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
        if (iconDir == null) {
            return null;
        }
        if (applicationType != null && !applicationType.isBlank()) {
            Path p = iconDir.resolve(applicationType + ".gif");
            if (Files.isRegularFile(p)) {
                return p;
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
