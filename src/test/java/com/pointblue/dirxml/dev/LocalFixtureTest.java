package com.pointblue.dirxml.dev;

import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * The override rules in {@link LocalFixture}. These do not need a Designer
 * project or the engine jars, so the portable CI profile runs them.
 */
public class LocalFixtureTest {

    @Test
    public void propertyWinsOverEnvironmentAndFallback() {
        assertEquals(Path.of("/from/property"),
            LocalFixture.choose(" /from/property ", "/from/env", "/fallback"));
    }

    @Test
    public void environmentWinsWhenThePropertyIsBlank() {
        assertEquals(Path.of("/from/env"), LocalFixture.choose(null, "/from/env", "/fallback"));
        assertEquals(Path.of("/from/env"), LocalFixture.choose("   ", " /from/env ", "/fallback"));
    }

    @Test
    public void fallbackWhenBothAreUnset() {
        assertEquals(Path.of("/Users/jcombs/designer_workspace/test11"),
            LocalFixture.choose(null, null, "/Users/jcombs/designer_workspace/test11"));
        assertEquals(Path.of("/fallback"), LocalFixture.choose("", "   ", "/fallback"));
    }

    @Test
    public void aSystemPropertyOverridesTheHistoricalDefault() {
        String key = "dirxml.fixture.test11";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "/tmp/dirxml-fixture-test11");
            assertEquals(Path.of("/tmp/dirxml-fixture-test11"), LocalFixture.test11());
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    public void defaultsStayOnTheHistoricalPathsWhenNothingIsSet() {
        assumeTrue(System.getProperty("dirxml.fixture.test11") == null
            && System.getenv("DIRXML_FIXTURE_TEST11") == null);
        assumeTrue(System.getProperty("dirxml.fixture.amica") == null
            && System.getenv("DIRXML_FIXTURE_AMICA") == null);
        assumeTrue(System.getProperty("dirxml.fixture.e2e.tree") == null
            && System.getenv("DIRXML_FIXTURE_E2E_TREE") == null);
        assumeTrue(System.getProperty("dirxml.fixture.e2e.7c") == null
            && System.getenv("DIRXML_FIXTURE_E2E_7C") == null);
        assumeTrue(System.getProperty("dirxml.fixture.e2e.catalog") == null
            && System.getenv("DIRXML_FIXTURE_E2E_CATALOG") == null);
        assumeTrue(System.getProperty("dirxml.fixture.rfi") == null
            && System.getenv("DIRXML_FIXTURE_RFI") == null);

        assertEquals(Path.of("/Users/jcombs/designer_workspace/test11"), LocalFixture.test11());
        assertEquals(Path.of("/private/tmp/claude-501/-Users-jcombs-Dev-DirXML-Engine-Analysis"
            + "/34814343-5cce-492a-8498-a04381e36292/scratchpad/amica-prd/AMICA-PRD-20260627"),
            LocalFixture.amica());
        assertEquals(Path.of("/Users/jcombs/IdeaProjects/DirXMLDev-e2e/tree-test11pf"), LocalFixture.e2eTree());
        assertEquals(Path.of("/Users/jcombs/IdeaProjects/DirXMLDev-e2e/tree-7c"), LocalFixture.e2e7c());
        assertEquals(Path.of("/Users/jcombs/IdeaProjects/DirXMLDev-e2e/catalog"), LocalFixture.e2eCatalog());
        assertEquals(Path.of("/Users/jcombs/tmp/RFI-DriverSet.xml"), LocalFixture.rfiExport());
    }
}
