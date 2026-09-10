package com.pointblue.dirxml.dev.packages;

import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The one place this codebase is allowed to touch the network: fetching a
 * small real package from nu.novell.com. Guarded on {@code -Dpackages.liveSite=true}
 * — never run by a plain {@code mvn test}.
 */
public class UpdateSiteLiveTest {

    @Test
    public void fetchOneSmallPackageFromTheRealUpdateSite() throws Exception {
        Assume.assumeTrue("true".equals(System.getProperty("packages.liveSite")));
        Path work = Files.createTempDirectory("pkg-live-fetch");
        Catalog catalog = Catalog.open(work.resolve("catalog"));

        UpdateSite.FetchResult r = UpdateSite.fetch(catalog, Catalog.DEFAULT_SITE_2, List.of("NOVLLBACKB"), false, false);
        assertTrue("error: " + r.error, r.error == null);
        assertFalse("refused: " + r.refused, !r.refused.isEmpty());
        assertFalse("nothing added", r.added.isEmpty());

        Catalog.PackageEntry e = catalog.get("NOVLLBACKB");
        assertTrue("indexed", e != null);
        String newest = e.newestVersion();
        assertTrue(Files.exists(catalog.dir.resolve("jars/NOVLLBACKB/NOVLLBACKB_" + newest + ".jar")));
    }
}
