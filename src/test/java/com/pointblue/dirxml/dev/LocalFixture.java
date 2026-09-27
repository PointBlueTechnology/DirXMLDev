package com.pointblue.dirxml.dev;

import java.nio.file.Path;

/**
 * Paths of Designer projects and exports that live on a developer's machine and
 * are never committed. A system property wins, then the matching environment
 * variable, then the historical default — so a checkout on the machine that
 * already has the fixture still runs with no extra configuration, and any other
 * machine can point the same tests at its own copy.
 *
 * <p>Callers skip with {@link org.junit.Assume#assumeTrue(boolean)} when the path
 * is absent. They do not fail.
 *
 * <table>
 *   <caption>Overrides</caption>
 *   <tr><th>Fixture</th><th>System property</th><th>Environment variable</th></tr>
 *   <tr><td>test11 Designer project</td><td>{@code dirxml.fixture.test11}</td><td>{@code DIRXML_FIXTURE_TEST11}</td></tr>
 *   <tr><td>Amica PRD project</td><td>{@code dirxml.fixture.amica}</td><td>{@code DIRXML_FIXTURE_AMICA}</td></tr>
 *   <tr><td>e2e {@code tree-test11pf}</td><td>{@code dirxml.fixture.e2e.tree}</td><td>{@code DIRXML_FIXTURE_E2E_TREE}</td></tr>
 *   <tr><td>e2e {@code tree-7c}</td><td>{@code dirxml.fixture.e2e.7c}</td><td>{@code DIRXML_FIXTURE_E2E_7C}</td></tr>
 *   <tr><td>e2e package catalog</td><td>{@code dirxml.fixture.e2e.catalog}</td><td>{@code DIRXML_FIXTURE_E2E_CATALOG}</td></tr>
 *   <tr><td>RFI driver-set export</td><td>{@code dirxml.fixture.rfi}</td><td>{@code DIRXML_FIXTURE_RFI}</td></tr>
 * </table>
 */
public final class LocalFixture {

    private static final String TEST11 = "/Users/jcombs/designer_workspace/test11";
    private static final String AMICA =
        "/private/tmp/claude-501/-Users-jcombs-Dev-DirXML-Engine-Analysis/34814343-5cce-492a-8498-a04381e36292"
            + "/scratchpad/amica-prd/AMICA-PRD-20260627";
    private static final String E2E_TREE = "/Users/jcombs/IdeaProjects/DirXMLDev-e2e/tree-test11pf";
    private static final String E2E_7C = "/Users/jcombs/IdeaProjects/DirXMLDev-e2e/tree-7c";
    private static final String E2E_CATALOG = "/Users/jcombs/IdeaProjects/DirXMLDev-e2e/catalog";
    private static final String RFI = "/Users/jcombs/tmp/RFI-DriverSet.xml";

    private LocalFixture() {
    }

    /** The hand-built {@code test11} Designer project. */
    public static Path test11() {
        return resolve("dirxml.fixture.test11", "DIRXML_FIXTURE_TEST11", TEST11);
    }

    /** The unzipped Amica PRD Designer project. */
    public static Path amica() {
        return resolve("dirxml.fixture.amica", "DIRXML_FIXTURE_AMICA", AMICA);
    }

    /** IDM-as-code tree exported from {@code test11pf}, used by {@code export-project --new} tests. */
    public static Path e2eTree() {
        return resolve("dirxml.fixture.e2e.tree", "DIRXML_FIXTURE_E2E_TREE", E2E_TREE);
    }

    /** IDM-as-code tree whose packages the e2e catalog holds ({@code tree-7c}). */
    public static Path e2e7c() {
        return resolve("dirxml.fixture.e2e.7c", "DIRXML_FIXTURE_E2E_7C", E2E_7C);
    }

    /** Package catalog beside the e2e trees. */
    public static Path e2eCatalog() {
        return resolve("dirxml.fixture.e2e.catalog", "DIRXML_FIXTURE_E2E_CATALOG", E2E_CATALOG);
    }

    /** A real driver-set export ({@code RFI-DriverSet.xml}). */
    public static Path rfiExport() {
        return resolve("dirxml.fixture.rfi", "DIRXML_FIXTURE_RFI", RFI);
    }

    static Path resolve(String property, String environment, String fallback) {
        return choose(System.getProperty(property), System.getenv(environment), fallback);
    }

    /** Property, then environment, then fallback. Blank values are treated as unset. */
    static Path choose(String propertyValue, String envValue, String fallback) {
        if (present(propertyValue)) {
            return Path.of(propertyValue.trim());
        }
        if (present(envValue)) {
            return Path.of(envValue.trim());
        }
        return Path.of(fallback);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
