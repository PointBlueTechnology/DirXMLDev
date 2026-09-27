package com.pointblue.dirxml.dev;

import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The override rules in {@link LocalFixture}. These do not need a Designer
 * project or the engine jars, so the portable CI profile runs them.
 */
public class LocalFixtureTest {

    @Test
    public void propertyWinsOverEnvironmentAndFallback() {
        assertEquals(Path.of("/from/property"),
            LocalFixture.choose(" /from/property ", "/from/env", Path.of("/fallback")));
    }

    @Test
    public void environmentWinsWhenThePropertyIsBlank() {
        assertEquals(Path.of("/from/env"), LocalFixture.choose(null, "/from/env", Path.of("/fallback")));
        assertEquals(Path.of("/from/env"), LocalFixture.choose("   ", " /from/env ", Path.of("/fallback")));
    }

    @Test
    public void fallbackWhenBothAreUnset() {
        assertEquals(Path.of("/fallback"), LocalFixture.choose(null, null, Path.of("/fallback")));
        assertEquals(Path.of("/fallback"), LocalFixture.choose("", "   ", Path.of("/fallback")));
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
    public void defaultsLiveUnderTheUserHomeNotOneDevelopersDisk() {
        assumeTrue(System.getProperty("dirxml.fixture.test11") == null && System.getenv("DIRXML_FIXTURE_TEST11") == null);
        assumeTrue(System.getProperty("dirxml.fixture.rfi") == null && System.getenv("DIRXML_FIXTURE_RFI") == null);
        Path home = Path.of(System.getProperty("user.home"));
        assertTrue(LocalFixture.test11().toString(), LocalFixture.test11().startsWith(home));
        assertTrue(LocalFixture.rfiExport().toString(), LocalFixture.rfiExport().startsWith(home));
        assertTrue(LocalFixture.e2e("ua-driver.ldif").toString(), LocalFixture.e2e("ua-driver.ldif").endsWith("ua-driver.ldif"));
    }
}
