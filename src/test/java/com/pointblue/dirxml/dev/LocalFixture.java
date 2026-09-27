package com.pointblue.dirxml.dev;

import java.nio.file.Path;

/**
 * Where the tests that need a machine-local fixture look for it: a Designer project, an export,
 * an e2e tree, Designer's package catalog. None of these is committed. Each has a system property
 * and an environment variable that point elsewhere ({@code -Ddirxml.fixture.test11=/path} or
 * {@code DIRXML_FIXTURE_TEST11=/path}; the property wins); with neither set, a path under the
 * user's home (or Designer's install location) is used. A test skips when the file is absent.
 * See docs/install.md §2.4.
 */
public final class LocalFixture {

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    private LocalFixture() {
    }

    /** The small Designer project most project tests read and copy. */
    public static Path test11() {
        return resolve("dirxml.fixture.test11", "DIRXML_FIXTURE_TEST11", HOME.resolve("designer_workspace/test11"));
    }

    /** The read-only Designer project with provisioning content. */
    public static Path test11pf() {
        return resolve("dirxml.fixture.test11pf", "DIRXML_FIXTURE_TEST11PF", HOME.resolve("designer_workspace/test11pf"));
    }

    /** A Designer project with PRDs and forms. */
    public static Path amica() {
        return resolve("dirxml.fixture.amica", "DIRXML_FIXTURE_AMICA", HOME.resolve("tmp/AMICA-PRD-20260627"));
    }

    /** The e2e fixtures directory ({@code DirXMLDev-e2e}: trees, LDIFs, the catalog). */
    public static Path e2eDir() {
        return resolve("dirxml.fixture.e2e.dir", "DIRXML_FIXTURE_E2E_DIR", HOME.resolve("IdeaProjects/DirXMLDev-e2e"));
    }

    /** A file under {@link #e2eDir()}. */
    public static Path e2e(String relative) {
        return e2eDir().resolve(relative);
    }

    public static Path e2eTree() {
        return resolve("dirxml.fixture.e2e.tree", "DIRXML_FIXTURE_E2E_TREE", e2eDir().resolve("tree-test11pf"));
    }

    public static Path e2e7c() {
        return resolve("dirxml.fixture.e2e.7c", "DIRXML_FIXTURE_E2E_7C", e2eDir().resolve("tree-7c"));
    }

    public static Path e2eCatalog() {
        return resolve("dirxml.fixture.e2e.catalog", "DIRXML_FIXTURE_E2E_CATALOG", e2eDir().resolve("catalog"));
    }

    /** A driver-set export with a Library, linked policies and an RLand driver. */
    public static Path rfiExport() {
        return resolve("dirxml.fixture.rfi", "DIRXML_FIXTURE_RFI", HOME.resolve("tmp/RFI-DriverSet.xml"));
    }

    /** A large single-driver export. */
    public static Path jfwExport() {
        return resolve("dirxml.fixture.jfw", "DIRXML_FIXTURE_JFW", HOME.resolve("IdeaProjects/DirXMLSimulator/JFW-DEV-UKG.xml"));
    }

    /** An AD driver export with entitlements. */
    public static Path adDriverExport() {
        return resolve("dirxml.fixture.ad", "DIRXML_FIXTURE_AD", HOME.resolve("Downloads/Active Directory Driver.xml"));
    }

    /** Designer's package catalog (its Eclipse plugins directory). */
    public static Path designerPlugins() {
        return resolve("dirxml.fixture.designer.plugins", "DIRXML_FIXTURE_DESIGNER_PLUGINS", Path.of("/Applications/Designer/packages/eclipse/plugins"));
    }

    static Path resolve(String property, String environment, Path fallback) {
        return choose(System.getProperty(property), System.getenv(environment), fallback);
    }

    static Path choose(String propertyValue, String envValue, Path fallback) {
        if (present(propertyValue)) {
            return Path.of(propertyValue.trim());
        }
        if (present(envValue)) {
            return Path.of(envValue.trim());
        }
        return fallback;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
