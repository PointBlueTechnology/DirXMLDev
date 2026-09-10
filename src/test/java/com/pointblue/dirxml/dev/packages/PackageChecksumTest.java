package com.pointblue.dirxml.dev.packages;

import org.junit.Assume;
import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The checksum recipes against Designer's own numbers. The catalog tests are
 * guarded on a local Designer install; the primitive tests always run.
 */
public class PackageChecksumTest {

    static final Path CATALOG = Paths.get("/Applications/Designer/packages/eclipse/plugins");

    @Test
    public void crcIsCrc32OfConcatenatedUtf8() {
        // CRC32("ab") == CRC32 over update("a"), update("b"); null/empty parts contribute nothing
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update("ab".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(c.getValue(), PackageChecksum.crc("a", null, "", "b"));
    }

    @Test
    public void canonicalXmlIsDeclarationPlusTabIndentedNoTrailingNewline() {
        String canon = NxslCanonical.canonical("<a>\n  <b x=\"1\"/>\n  <c>t</c>\n</a>");
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a>\n\t<b x=\"1\"/>\n\t<c>t</c>\n</a>", canon);
    }

    @Test
    public void emptyXmlResourceHashesNamePlusDeclarationPlusContentType() {
        // MFAZUREBASE-UpgradeSettings: an XML prompt resource with no content (verified against the jar: 2163953968)
        assertEquals(2163953968L, PackageChecksum.content(PackageChecksum.RESOURCE, "MFAZUREBASE-UpgradeSettings",
            null, null, "application/vnd.novell.dirxml.pkg-prompt+xml", List.of()));
    }

    @Test
    public void packageChecksumCoversFoldersOneToEight() {
        Map<Integer, Long> folders = Map.of(1, 11L, 2, 22L, 9, 99L);
        assertEquals(PackageChecksum.crc("11", "22", "0", "0", "0", "0", "0", "0"), PackageChecksum.pkg(folders));
        assertEquals(0L, PackageChecksum.folder(Map.of(), null));
        // sorted by association id, stored decimal checksums
        assertEquals(PackageChecksum.crc("5", "7"), PackageChecksum.folder(Map.of("B_2", "7", "A_1", "5"), null));
    }

    @Test
    public void installedPolicyChecksumFoldsInLinkedSetNames() {
        // a policy's installed checksum = catalog checksum input + the names of the sets it is linked into
        Element policy = NxslCanonical.parse("<policy><rule><description>r</description><conditions/><actions/></rule></policy>").getDocumentElement();
        long catalog = PackageChecksum.content(PackageChecksum.RULE, "p", policy, null, null, List.of());
        long installed = PackageChecksum.content(PackageChecksum.RULE, "p", policy, null, null, List.of("input"));
        assertEquals(PackageChecksum.crc("p", NxslCanonical.canonical(policy), "input"), installed);
        assertTrue(catalog != installed);
    }

    @Test
    public void catalogJarsRecomputeExactly() throws Exception {
        Assume.assumeTrue(Files.isDirectory(CATALOG));
        // one jar per verified object kind: policies+resources+GCV (eDir default config), templates (notification
        // templates), entitlements (AD entitlements), prompts with empty content (Azure base), ID policies
        for (String jar : List.of("NOVLEDIRDCFG_2.1.0.20120831225140.jar", "NOVLPROVNOTF_2.2.0.20190914160915.jar",
            "NOVLADENTEX_2.5.7.20190610155012.jar", "MFAZUREBASE_1.0.8.20240826105220.jar", "NOVLIDPROVB_1.0.0.jar")) {
            Path p = CATALOG.resolve(jar);
            Assume.assumeTrue(Files.exists(p));
            ChecksumAudit a = ChecksumAudit.of(PackageJar.read(p));
            List<ChecksumAudit.Line> bad = a.mismatches().stream()
                .filter(l -> !"DirXML-Job".equals(l.objectClass))   // jobs: recipe not yet verified
                .toList();
            assertTrue(jar + ": " + bad, bad.isEmpty());
        }
    }

    @Test
    public void wholeCatalogPoliciesAndDirectivesRecompute() throws Exception {
        Assume.assumeTrue(Files.isDirectory(CATALOG) && "true".equals(System.getProperty("checksum.wholeCatalog")));
        int mismatches = 0;
        try (Stream<Path> s = Files.list(CATALOG)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".jar")).toList()) {
                for (ChecksumAudit.Line l : ChecksumAudit.of(PackageJar.read(p)).mismatches()) {
                    if (!"DirXML-Job".equals(l.objectClass) && !"package".equals(l.kind)) {
                        mismatches++;
                    }
                }
            }
        }
        assertEquals(0, mismatches);
    }
}
