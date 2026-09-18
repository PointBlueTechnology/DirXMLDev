package com.pointblue.dirxml.dev.model;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The one reader of package stamps: either vocabulary in, the vault's out. */
public class PackageStampsTest {

    private static final String FULL = "B5PAGQ5E_201005261601510810;com.netiqcorporation.novluabase;4.10.1.20250606222933;User Application Base;NOVLUABASE";

    @Test
    public void recordsParseAndFormatLosslessly() {
        PackageStamps.Guid g = PackageStamps.Guid.parse(FULL);
        assertEquals("B5PAGQ5E_201005261601510810", g.id);
        assertEquals("com.netiqcorporation.novluabase", g.symbolicName);
        assertEquals("4.10.1.20250606222933", g.version);
        assertEquals("User Application Base", g.name);
        assertEquals("NOVLUABASE", g.shortName);
        assertFalse(g.base);
        assertTrue(g.isComplete());
        assertEquals(FULL, g.format());
        assertEquals(FULL + ";base", PackageStamps.Guid.parse(FULL + ";base").formatInstalled());
        assertTrue(PackageStamps.Guid.parse(FULL + ";base").base);

        // Designer's three-field record on provisioning items, an export's id-and-version, an id alone
        assertEquals("X;com.v.s;1.0", PackageStamps.Guid.parse("X;com.v.s;1.0").format());
        assertEquals("X;;1.0", new PackageStamps.Guid("X", null, "1.0", null, null, false).format());
        assertEquals("X", new PackageStamps.Guid("X", "", "", "", "", false).format());
        assertNull(PackageStamps.Guid.parse(" "));

        // Designer's placeholders on a PRD it could not resolve: they say nothing about the package
        PackageStamps.Guid placeholder = PackageStamps.Guid.parse("A6YBB9HO_201907161500230437;unknown;0.0.0");
        assertNull(placeholder.symbolicName);
        assertNull(placeholder.version);
        assertEquals("A6YBB9HO_201907161500230437", placeholder.format());
        assertTrue(PackageStamps.sameGuid("A6YBB9HO_201907161500230437;unknown;0.0.0",
            "A6YBB9HO_201907161500230437;com.netiqcorporation.novlwfwizard;1.1.0.20200316113624"));
    }

    @Test
    public void symbolicNameFollowsDesignersRule() {
        assertEquals("com.netiqcorporation.novluabase", PackageStamps.symbolicName("NetIQ Corporation", "NOVLUABASE"));
        assertEquals("com.novellinc.novledirbase", PackageStamps.symbolicName("Novell, Inc.", "NOVLEDIRBASE"));
        assertEquals("com.microfocus.mfigasgmtcol", PackageStamps.symbolicName("Micro Focus", "MFIGASGMTCOL"));
        assertEquals("pkgvendor", PackageStamps.symbolicName(" - ", "X"));
    }

    @Test
    public void readsEitherVocabulary() {
        Map<String, String> vault = new LinkedHashMap<>();
        vault.put("dirxml-pkgguid", FULL);
        vault.put("dirxml-pkgassociationid", "A1");
        vault.put("dirxml-pkgchecksum", "42");
        vault.put("dirxml-pkglinkages", "<policy-linkage/>");
        assertEquals("B5PAGQ5E_201005261601510810", PackageStamps.packageId(vault));
        assertEquals("4.10.1.20250606222933", PackageStamps.version(vault));
        assertEquals("A1", PackageStamps.assocId(vault));
        assertEquals("42", PackageStamps.checksum(vault));
        assertEquals("<policy-linkage/>", PackageStamps.linkages(vault));

        Map<String, String> old = new LinkedHashMap<>();
        old.put("package-id", "B5PAGQ5E_201005261601510810");
        old.put("package-version", "4.10.1.20250606222933");
        old.put("pkg-assoc-id", "A1");
        old.put("checksum", "42");
        assertEquals("B5PAGQ5E_201005261601510810;;4.10.1.20250606222933", PackageStamps.guid(old).format());
        assertEquals("A1", PackageStamps.assocId(old));
        assertEquals("42", PackageStamps.checksum(old));
        assertNull(PackageStamps.linkages(old));

        Map<String, String> digest = new LinkedHashMap<>();
        digest.put("project.package-id", "P");
        digest.put("project.pkg-assoc-id", "A2");
        digest.put("project.pkg-checksum", "7");
        assertEquals("P", PackageStamps.packageId(digest));
        assertEquals("A2", PackageStamps.assocId(digest));
        assertEquals("7", PackageStamps.checksum(digest));

        assertTrue(PackageStamps.isPackaged(old));
        assertFalse(PackageStamps.isPackaged(Map.of("designer.id", "X")));
        assertNull(PackageStamps.guid(Map.of()));
    }

    @Test
    public void sameGuidComparesOnlyTheFieldsBothSidesKnow() {
        assertTrue(PackageStamps.sameGuid(FULL, "B5PAGQ5E_201005261601510810"));
        assertTrue(PackageStamps.sameGuid(FULL, "B5PAGQ5E_201005261601510810;;4.10.1.20250606222933"));
        assertTrue(PackageStamps.sameGuid("B5PAGQ5E_201005261601510810;com.netiqcorporation.novluabase;4.10.1.20250606222933", FULL));
        assertFalse(PackageStamps.sameGuid(FULL, "B5PAGQ5E_201005261601510810;;9.9.9"));
        assertFalse(PackageStamps.sameGuid(FULL, "OTHER"));
        assertFalse(PackageStamps.sameGuid(FULL, (String) null));
        assertTrue(PackageStamps.sameGuid((String) null, null));
    }

    @Test
    public void indexKeepsTheFullestRecordAndCompletesPartialOnes() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("AD");
        d.meta.put("package.installed.NOVLUABASE", FULL + ";base");
        d.meta.put("dirxml-pkgguid", "B5PAGQ5E_201005261601510810");
        ds.drivers.add(d);
        Policy p = new Policy("x", Scope.DRIVER, "AD", null);
        p.meta.put("package-id", "OTHER");
        d.policies.add(p);

        PackageStamps.Index idx = PackageStamps.Index.of(ds);
        assertEquals(2, idx.size());
        assertEquals(FULL, idx.get("B5PAGQ5E_201005261601510810").format());
        assertTrue(idx.get("B5PAGQ5E_201005261601510810").base);
        assertEquals("B5PAGQ5E_201005261601510810;;1.2", idx.complete(PackageStamps.Guid.parse("B5PAGQ5E_201005261601510810;;1.2")).format()
            .replace("com.netiqcorporation.novluabase", "").replace("4.10.1.20250606222933", "1.2").replace(";User Application Base;NOVLUABASE", ""));
        PackageStamps.Guid completed = idx.complete(new PackageStamps.Guid("B5PAGQ5E_201005261601510810", null, null, null, null, false));
        assertEquals(FULL, completed.format());
        assertEquals("OTHER", idx.complete(PackageStamps.Guid.parse("OTHER")).format());

        Map<String, String> old = Map.of("package-id", "B5PAGQ5E_201005261601510810", "pkg-assoc-id", "A1", "checksum", "42");
        Map<String, String> stamps = PackageStamps.vaultStamps(old, idx);
        assertEquals(FULL, stamps.get(PackageStamps.GUID));
        assertEquals("A1", stamps.get(PackageStamps.ASSOC));
        assertEquals("42", stamps.get(PackageStamps.CHECKSUM));
        assertFalse(stamps.containsKey(PackageStamps.LINKAGES));
        assertTrue(PackageStamps.vaultStamps(Map.of(), idx).isEmpty());
    }
}
