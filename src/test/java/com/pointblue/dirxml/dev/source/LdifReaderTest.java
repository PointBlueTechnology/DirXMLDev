package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Directory entries (LDIF / live) → model: placement by DN, attributes, linkage. */
public class LdifReaderTest {

    private static final String DS = "cn=driverset1,o=system";

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", List.of("Top", oc));
        for (int i = 0; i < kv.length; i += 2) {
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    private static DriverSet sample() {
        String drv = "cn=AD,cn=driverset1,o=system";
        String lib = "cn=lib-common,cn=Library," + DS;
        String subPol = "cn=sub-etp_Scope,cn=Subscriber," + drv;
        String pubPol = "cn=pub-otp,cn=Publisher," + drv;
        String schema = "cn=sch_Map," + drv;
        String missing = "cn=lib-not-exported,cn=Library," + DS;
        List<Entry> entries = List.of(
            entry(DS, "DirXML-DriverSet", "DirXML-ConfigValues",
                "<configuration-values><definitions><definition name=\"x\" display-name=\"x\" type=\"string\"><value>1</value></definition></definitions></configuration-values>"),
            entry(lib, "DirXML-Rule", "XmlData", "<policy><rule><description>shared</description><conditions/><actions/></rule></policy>"),
            entry("cn=LocCodeMap,cn=Library," + DS, "DirXML-Resource",
                "DirXML-ContentType", Resource.MAPPING_TABLE,
                "DirXML-Data", "<?xml version=\"1.0\"?><mapping-table><col-def name=\"a\"/><row><col>1</col></row></mapping-table>"),
            entry("cn=es-misc,cn=Library," + DS, "DirXML-Resource",
                "DirXML-ContentType", "text/ecmascript;charset=UTF-8", "DirXML-Data", "function f(){}"),
            entry(drv, "DirXML-Driver",
                "DirXML-JavaModule", "com.novell.nds.dirxml.driver.ad.ADDriverShim",
                "DirXML-ShimAuthServer", "ad.example.com", "DirXML-ShimAuthID", "svc",
                "DirXML-TraceSizeLimit", "200000",
                "DirXML-DriverFilter", "<filter/>",
                "DirXML-pkgGUID", "PKG-1",
                "DirXML-Policies", schema + "#0#0",
                "DirXML-Policies", subPol + "#0#4",
                "DirXML-Policies", lib + "#1#4",
                "DirXML-Policies", pubPol + "#0#2",
                "DirXML-Policies", missing + "#2#4",
                "DirXML-Policies", "cn=NOVLADDCFG-GCVs," + drv + "#0#14",
                "DirXML-Policies", "cn=NOVLADENTEX-Startup-InitEntitlementConfigurationResource," + drv + "#0#15",
                "DirXML-Policies", "cn=drv-shutdown," + drv + "#0#16"),
            entry("cn=NOVLADDCFG-GCVs," + drv, "DirXML-GlobalConfigDef",
                "DirXML-ConfigValues", "<?xml version=\"1.0\"?><configuration-values><definitions/></configuration-values>",
                "DirXML-pkgChecksum", "4234957497"),
            entry("cn=Subscriber," + drv, "DirXML-Subscriber"),
            entry("cn=Publisher," + drv, "DirXML-Publisher"),
            entry(schema, "DirXML-Rule", "XmlData", "<attr-name-map/>"),
            entry(subPol, "DirXML-Rule", "XmlData", "<policy><rule><conditions/><actions><do-veto/></actions></rule></policy>"),
            entry(pubPol, "DirXML-StyleSheet", "XmlData",
                "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\"/>"),
            entry("cn=NOVLADENTEX-Startup-InitEntitlementConfigurationResource," + drv, "DirXML-Rule",
                "XmlData", "<policy><rule><description>init entitlements</description><conditions/><actions/></rule></policy>"),
            entry("cn=drv-shutdown," + drv, "DirXML-Rule",
                "XmlData", "<policy><rule><description>shutdown</description><conditions/><actions/></rule></policy>"));
        return LdifReader.fromEntries(entries, "synthetic.ldif");
    }

    @Test
    public void placesArtifactsByDnStructure() {
        DriverSet ds = sample();
        assertEquals("driverset1", ds.name);
        assertEquals(DS, ds.dn);
        assertNotNull(ds.configValues);
        assertEquals(1, ds.library.policies.size());
        assertEquals(2, ds.library.resources.size());

        Driver ad = ds.driver("AD");
        assertNotNull(ad);
        assertEquals("com.novell.nds.dirxml.driver.ad.ADDriverShim", ad.shimClass);
        assertEquals("svc", ad.shimAuthId);
        assertEquals("200000", ad.traceSizeLimit);
        assertTrue(ad.config.containsKey(Driver.DRIVER_FILTER));
        assertEquals("PKG-1", ad.meta.get("dirxml-pkgguid"));
        assertEquals(3, ad.policies.size());                 // sch_Map + startup + shutdown at driver scope
        assertEquals(Policy.Kind.SCHEMA_MAP, ad.policies.get(0).policyKind());
        assertEquals(1, ad.subscriber.policies.size());
        assertEquals(1, ad.publisher.policies.size());
        assertEquals(Policy.Kind.XSLT, ad.publisher.policies.get(0).policyKind());
        assertEquals("drivers/AD/subscriber/sub-etp_Scope", ad.subscriber.policies.get(0).path());
    }

    @Test
    public void resourcesTypedByContent() {
        DriverSet ds = sample();
        Resource table = (Resource) ds.resolve("library/LocCodeMap");
        assertTrue(table.isMappingTable());
        assertNotNull(table.content);
        assertNull(table.text);
        Resource js = (Resource) ds.resolve("library/es-misc");
        assertTrue(js.isEcmaScript());
        assertEquals("function f(){}", js.text);
    }

    @Test
    public void linkageMapsToPathsAndReportsMissingTargets() {
        DriverSet ds = sample();
        Driver ad = ds.driver("AD");
        assertEquals(8, ad.links.size());
        // a DirXML-GlobalConfigDef becomes a GCV-def resource so the set-14 link resolves
        assertEquals("drivers/AD/NOVLADDCFG-GCVs", ad.links(PolicySet.GCV).get(0).ref);
        Resource gcv = (Resource) ds.resolve("drivers/AD/NOVLADDCFG-GCVs");
        assertNotNull(gcv);
        assertTrue(gcv.isGcvDef());
        assertNotNull(gcv.content);
        assertEquals("4234957497", gcv.meta.get("dirxml-pkgchecksum"));
        List<PolicyLink> sub = ad.links(PolicySet.SUB_EVENT);
        assertEquals("drivers/AD/subscriber/sub-etp_Scope", sub.get(0).ref);
        assertEquals("library/lib-common", sub.get(1).ref);
        assertEquals("library/lib-not-exported", sub.get(2).ref);
        assertEquals("drivers/AD/sch_Map", ad.links(PolicySet.SCHEMA_MAPPING).get(0).ref);
        assertEquals("drivers/AD/publisher/pub-otp", ad.links(PolicySet.OUTPUT).get(0).ref);
        assertEquals("drivers/AD/NOVLADENTEX-Startup-InitEntitlementConfigurationResource",
            ad.links(PolicySet.STARTUP).get(0).ref);
        assertEquals("drivers/AD/drv-shutdown", ad.links(PolicySet.SHUTDOWN).get(0).ref);
        assertTrue(ad.meta.keySet().stream().noneMatch(k -> k.startsWith("linkage.unknown.")));

        List<PolicyLink> broken = ds.unresolvedLinks();
        assertEquals(1, broken.size());
        assertEquals("library/lib-not-exported", broken.get(0).ref);
    }

    @Test
    public void dnHelpers() {
        assertEquals("a,b", LdifReader.rdn("cn=a\\,b,o=x"));
        assertEquals("o=x", LdifReader.parentDn("cn=a,o=x"));
        assertEquals(Scope.PUBLISHER, LdifReader.place("cn=p,cn=Publisher,cn=D," + DS, DS).scope);
        assertEquals("D", LdifReader.place("cn=p,cn=Publisher,cn=D," + DS, DS).driver);
        assertEquals(Scope.LIBRARY, LdifReader.place("cn=p,cn=Library," + DS, DS).scope);
        assertEquals(Scope.DRIVER, LdifReader.place("cn=p,cn=D," + DS, DS).scope);
    }

    @Test
    public void realVaultLdifWhenPresent() {
        Path ldif = Path.of(System.getProperty("user.home"), "tmp", "IDM_IG4_TREE_subtree.ldif");
        assumeTrue("needs the local IG4 vault LDIF", Files.exists(ldif));
        DriverSet ds = LdifReader.read(ldif);
        assertTrue("expected many drivers, got " + ds.drivers.size(), ds.drivers.size() >= 10);
        assertFalse(ds.index().isEmpty());
        System.out.println("IG4 LDIF: " + ds + "; unresolved links: " + ds.unresolvedLinks().size());
    }

    /**
     * An LDIF holds the icon base64-encoded (folded across lines); the simulator's reader would
     * decode it as UTF-8 text, so the bytes are read again from the file — exactly.
     */
    @Test
    public void driverImageIsReadFromTheLdifAsBytes() throws Exception {
        byte[] gif = com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.TINY_GIF;
        String b64 = java.util.Base64.getEncoder().encodeToString(gif);
        String folded = b64.substring(0, 20) + "\n " + b64.substring(20);
        String ldif = "version: 1\n\n"
            + "dn: " + DS + "\nobjectClass: Top\nobjectClass: DirXML-DriverSet\ncn: driverset1\n\n"
            + "dn: cn=AD," + DS + "\nobjectClass: Top\nobjectClass: DirXML-Driver\ncn: AD\n"
            + "DirXML-JavaModule: com.example.Shim\nDirXML-DriverImage:: " + folded + "\n\n"
            + "dn: cn=Plain," + DS + "\nobjectClass: Top\nobjectClass: DirXML-Driver\ncn: Plain\n"
            + "DirXML-JavaModule: com.example.Shim\n";
        Path f = Files.createTempFile("icon", ".ldif");
        Files.writeString(f, ldif, java.nio.charset.StandardCharsets.UTF_8);
        try {
            DriverSet ds = LdifReader.read(f);
            Driver ad = ds.driver("AD");
            assertArrayEquals(gif, ad.icon);
            assertEquals("gif", ad.iconExtension);
            assertNull(ds.driver("Plain").icon);
            assertFalse("never as (mangled) text in meta", ad.meta.containsKey("dirxml-driverimage"));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /** A file without a driver set says what it did hold, and why that is not enough. */
    @Test
    public void missingDriverSetIsExplained() {
        List<Entry> noClasses = List.of(
            new Entry("cn=driverset1,o=system", java.util.Map.of("dirxml-configvalues", List.of("<configuration-values/>"))),
            new Entry("cn=AD,cn=driverset1,o=system", java.util.Map.of("dirxml-shimconfiginfo", List.of("<driver-config/>"))));
        try {
            LdifReader.fromEntries(noClasses, "attrs-only.ldif");
            org.junit.Assert.fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            String m = e.getMessage();
            org.junit.Assert.assertTrue(m, m.contains("2 entries, none with an objectClass attribute"));
            org.junit.Assert.assertTrue(m, m.contains("DirXML-ConfigValues") || m.contains("dirxml-configvalues"));
            org.junit.Assert.assertTrue(m, m.contains("objectClass included"));
        }
        List<Entry> wrongBase = List.of(
            entry("cn=jsmith,ou=users,o=data", "inetOrgPerson", "cn", "jsmith"),
            entry("ou=users,o=data", "organizationalUnit", "ou", "users"));
        try {
            LdifReader.fromEntries(wrongBase, "people.ldif");
            org.junit.Assert.fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            String m = e.getMessage();
            org.junit.Assert.assertTrue(m, m.contains("classes seen: inetOrgPerson (1), organizationalUnit (1)"));
            org.junit.Assert.assertTrue(m, m.contains("driver set's DN downwards"));
        }
    }

    /**
     * #133: a whole vault's LDIF — two driver sets, each with a Library and a driver called AD, and a
     * Library outside both — no longer fails on {@code duplicate artifact path}. Names are unique only in
     * their container: the tree takes its own set's objects by DN, and from other Libraries only what it can use.
     */
    private static List<Entry> vault() {
        String ds2 = "cn=DriverSet-Other,ou=services,o=identity";
        String ds1 = "cn=DriverSet-Identity,ou=services,o=identity";
        String global = "cn=Library,o=services";
        String rule = "<policy><rule><description>%s</description><conditions/><actions/></rule></policy>";
        return List.of(
            entry(ds1, "DirXML-DriverSet"),
            entry(ds2, "DirXML-DriverSet"),
            entry("cn=Library," + ds1, "DirXML-Library"),
            entry("cn=Library," + ds2, "DirXML-Library"),
            entry(global, "DirXML-Library"),
            // the same name in this set's Library, the shared Library and the other set's Library
            entry("cn=pub-ets-ps-Relationships,cn=Library," + ds1, "DirXML-Rule", "XmlData", String.format(rule, "own")),
            entry("cn=pub-ets-ps-Relationships," + global, "DirXML-Rule", "XmlData", String.format(rule, "shared")),
            entry("cn=pub-ets-ps-Relationships,cn=Library," + ds2, "DirXML-Rule", "XmlData", String.format(rule, "other set")),
            entry("cn=shared-only," + global, "DirXML-Rule", "XmlData", String.format(rule, "shared only")),
            entry("cn=other-linked,cn=Library," + ds2, "DirXML-Rule", "XmlData", String.format(rule, "other, linked")),
            entry("cn=other-unlinked,cn=Library," + ds2, "DirXML-Rule", "XmlData", String.format(rule, "other, not linked")),
            // a second Library inside the set (klasen's cn=SKyPRO), and a policy in a container that is no driver
            entry("cn=SKyPRO," + ds1, "DirXML-Library"),
            entry("cn=SPACDMCS-EcmaScript,cn=SKyPRO," + ds1, "DirXML-Resource",
                "DirXML-ContentType", "text/ecmascript", "DirXML-Data", "function f(){}"),
            entry("cn=pub-ets-ps-Relationships,cn=SKyPRO," + ds1, "DirXML-Rule", "XmlData", String.format(rule, "second Library")),
            entry("cn=pub,cn=AD," + ds1, "organizationalUnit"),
            entry("cn=pub-etp-ValidityPeriodLog,cn=pub,cn=AD," + ds1, "DirXML-Rule", "XmlData", String.format(rule, "odd container")),
            // a driver of one name in each set, each with a publisher policy of one name
            entry("cn=AD," + ds1, "DirXML-Driver",
                "DirXML-Policies", "cn=pub-etp,cn=Publisher,cn=AD," + ds1 + "#0#2",
                "DirXML-Policies", "cn=pub-ets-ps-Relationships,cn=Library," + ds1 + "#1#2",
                "DirXML-Policies", "cn=shared-only," + global + "#2#2",
                "DirXML-Policies", "cn=other-linked,cn=Library," + ds2 + "#3#2",
                "DirXML-Policies", "cn=SPACDMCS-EcmaScript,cn=SKyPRO," + ds1 + "#3#11",
                "DirXML-Policies", "cn=pub-etp-ValidityPeriodLog,cn=pub,cn=AD," + ds1 + "#4#2",
                "DirXML-Policies", "cn=pub-ets-ps-Relationships,cn=SKyPRO," + ds1 + "#5#2"),
            entry("cn=Publisher,cn=AD," + ds1, "DirXML-Publisher"),
            entry("cn=pub-etp,cn=Publisher,cn=AD," + ds1, "DirXML-Rule", "XmlData", String.format(rule, "own AD")),
            entry("cn=Entitlement1,cn=AD," + ds1, "DirXML-Entitlement", "XmlData", "<entitlement/>"),
            entry("cn=AD," + ds2, "DirXML-Driver",
                "DirXML-Policies", "cn=pub-etp,cn=Publisher,cn=AD," + ds2 + "#0#2"),
            entry("cn=Publisher,cn=AD," + ds2, "DirXML-Publisher"),
            entry("cn=pub-etp,cn=Publisher,cn=AD," + ds2, "DirXML-Rule", "XmlData", String.format(rule, "other AD")),
            entry("cn=Entitlement1,cn=AD," + ds2, "DirXML-Entitlement", "XmlData", "<entitlement/>"),
            entry("cn=Job1,cn=AD," + ds2, "DirXML-Job", "XmlData", "<job/>"));
    }

    @Test
    public void aWholeVaultLdifNeedsTheDriverSetNamed() {
        try {
            LdifReader.fromEntries(vault(), "vault.ldif");
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("holds 2 driver sets: cn=DriverSet-Identity,ou=services,o=identity, cn=DriverSet-Other"));
            assertTrue(e.getMessage(), e.getMessage().contains("--driver-set <dn>"));
        }
        try {
            LdifReader.fromEntries(vault(), "vault.ldif", Map.of(), "cn=Nope,o=identity");
            fail("expected a refusal");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("has no driver set cn=Nope,o=identity"));
        }
    }

    @Test
    public void rdnsAreUniqueOnlyInTheirContainer() {
        // spaces after the commas and another case: the same DN to the directory
        DriverSet ds = LdifReader.fromEntries(vault(), "vault.ldif", Map.of(), "CN=DriverSet-Identity, ou=services, o=identity");
        assertEquals("cn=DriverSet-Identity,ou=services,o=identity", ds.dn);
        ds.index();   // threw "duplicate artifact path: library/pub-ets-ps-Relationships"

        // one driver: this set's AD, with its own policy and entitlement, not the other set's
        assertEquals(1, ds.drivers.size());
        Driver ad = ds.driver("AD");
        assertEquals(1, ad.publisher.policies.size());
        assertTrue(CanonicalXmlText.of(ad.publisher.policies.get(0)).contains("own AD"));
        assertEquals(1, ad.entitlements.size());
        assertTrue(ad.jobs.isEmpty());
        assertTrue(ds.jobs.isEmpty());

        // the Library: its own copy of the shared name, kept; the shared Library's own object and what
        // the set links from the other set's Library, as read-only references; nothing else of the other set
        Policy own = (Policy) ds.resolve("library/pub-ets-ps-Relationships");
        assertTrue(CanonicalXmlText.of(own).contains("own"));
        assertNull(own.meta.get(com.pointblue.dirxml.dev.model.DuplicateArtifacts.LIBRARY_DN));
        assertEquals(1, ds.duplicates.size());
        String note = ds.duplicates.get(0).note;
        assertTrue(note, note.contains("3 objects in different Libraries are named library/pub-ets-ps-Relationships"));
        assertTrue(note, note.contains("in cn=Library,o=services") && note.contains("in cn=SKyPRO,cn=DriverSet-Identity"));
        // the tree's limit, not IDM's; and the copies differ, so the drivers linking the one left out are named
        assertTrue(note, note.contains("objects in different Libraries are named") && note.contains("IDM itself links from any number of Libraries"));
        String advice = ds.duplicates.get(0).advice;
        assertTrue(advice, advice.contains("content DIFFERS"));
        assertTrue(advice, advice.contains("Drivers that link it: AD"));

        Policy shared = (Policy) ds.resolve("library/shared-only");
        assertEquals("cn=Library,o=services", shared.meta.get(com.pointblue.dirxml.dev.model.DuplicateArtifacts.LIBRARY_DN));
        assertNotNull(com.pointblue.dirxml.dev.model.Libraries.outside(ds, shared));
        Policy linked = (Policy) ds.resolve("library/other-linked");
        assertEquals("cn=Library,cn=DriverSet-Other,ou=services,o=identity",
            linked.meta.get(com.pointblue.dirxml.dev.model.DuplicateArtifacts.LIBRARY_DN));
        assertNull(ds.resolve("library/other-unlinked"));

        // the second Library inside the set, and the odd container: in library/, read-only, keeping their DNs
        Resource script = (Resource) ds.resolve("library/SPACDMCS-EcmaScript");
        assertEquals("cn=SKyPRO,cn=DriverSet-Identity,ou=services,o=identity",
            script.meta.get(com.pointblue.dirxml.dev.model.DuplicateArtifacts.LIBRARY_DN));
        Policy odd = (Policy) ds.resolve("library/pub-etp-ValidityPeriodLog");
        assertEquals("cn=pub,cn=AD,cn=DriverSet-Identity,ou=services,o=identity",
            odd.meta.get(com.pointblue.dirxml.dev.model.DuplicateArtifacts.LIBRARY_DN));
        assertEquals(4, ds.library.policies.size());
        assertEquals(1, ds.library.resources.size());

        // every link resolves (#133: drivers/SKyPRO/SPACDMCS-EcmaScript did not), by the DN it names
        assertTrue(ds.unresolvedLinks().toString(), ds.unresolvedLinks().isEmpty());
        assertEquals(7, ad.links.size());
        PolicyLink toScript = ad.links.stream().filter(l -> l.ref.equals("library/SPACDMCS-EcmaScript")).findFirst().orElseThrow();
        assertEquals("cn=SPACDMCS-EcmaScript,cn=SKyPRO,cn=DriverSet-Identity,ou=services,o=identity", toScript.sourceDn);
        // and a deploy writes each link with the DN it was read from
        List<String> values = com.pointblue.dirxml.dev.deploy.VaultMapping.policiesValues(ds.dn, ad, ds);
        assertTrue(values.toString(), values.contains("cn=SPACDMCS-EcmaScript,cn=SKyPRO,cn=DriverSet-Identity,ou=services,o=identity#3#11"));
        assertTrue(values.toString(), values.contains("cn=pub-etp-ValidityPeriodLog,cn=pub,cn=AD,cn=DriverSet-Identity,ou=services,o=identity#4#2"));
        assertTrue(values.toString(), values.contains("cn=shared-only,cn=Library,o=services#2#2"));

        // and the other set reads the same way, with its own AD
        DriverSet other = LdifReader.fromEntries(vault(), "vault.ldif", Map.of(), "cn=DriverSet-Other,ou=services,o=identity");
        other.index();
        assertTrue(CanonicalXmlText.of(other.driver("AD").publisher.policies.get(0)).contains("other AD"));
        assertEquals(1, other.driver("AD").jobs.size());
        // its own Library's objects are its own, linked or not
        assertNotNull(other.resolve("library/other-unlinked"));
        assertNull(other.resolve("library/other-unlinked").meta.get(com.pointblue.dirxml.dev.model.DuplicateArtifacts.LIBRARY_DN));
    }

    /** A policy's XML as text, for asserting which copy was kept. */
    private static final class CanonicalXmlText {
        static String of(Policy p) {
            return com.pointblue.dirxml.dev.xml.CanonicalXml.serialize(p.content);
        }
    }

    /**
     * #133: channels are found by object class, not by name. Two drivers whose publisher channel is {@code cn=pub}
     * each keep their own {@code pub-ets-ps-Relationships}; the channel's name is kept for deploy.
     */
    @Test
    public void channelsAreFoundByObjectClass() {
        String set = "cn=DriverSet-Identity,ou=services,o=identity";
        String rule = "<policy><rule><description>%s</description><conditions/><actions/></rule></policy>";
        List<Entry> entries = new java.util.ArrayList<>();
        entries.add(entry(set, "DirXML-DriverSet"));
        for (String drv : new String[] {"SAP-HR-XXX", "SAP-HR-ZZZ"}) {
            String d = "cn=" + drv + "," + set;
            entries.add(entry(d, "DirXML-Driver",
                "DirXML-Policies", "cn=pub-ets-ps-Relationships,cn=pub," + d + "#0#2",
                "DirXML-Policies", "cn=sub-etp,cn=sub," + d + "#0#3"));
            entries.add(entry("cn=pub," + d, "DirXML-Publisher"));
            entries.add(entry("cn=sub," + d, "DirXML-Subscriber"));
            entries.add(entry("cn=pub-ets-ps-Relationships,cn=pub," + d, "DirXML-Rule", "XmlData", String.format(rule, drv)));
            entries.add(entry("cn=sub-etp,cn=sub," + d, "DirXML-Rule", "XmlData", String.format(rule, drv + " sub")));
        }
        DriverSet ds = LdifReader.fromEntries(entries, "vault.ldif");
        ds.index();
        assertTrue(ds.duplicates.toString(), ds.duplicates.isEmpty());
        assertTrue(ds.library.artifacts().isEmpty());
        assertTrue(ds.unresolvedLinks().toString(), ds.unresolvedLinks().isEmpty());
        for (String drv : new String[] {"SAP-HR-XXX", "SAP-HR-ZZZ"}) {
            Driver d = ds.driver(drv);
            assertEquals("pub", d.meta.get(com.pointblue.dirxml.dev.deploy.VaultMapping.CHANNEL_PUBLISHER_META));
            assertEquals("sub", d.meta.get(com.pointblue.dirxml.dev.deploy.VaultMapping.CHANNEL_SUBSCRIBER_META));
            Policy pub = (Policy) ds.resolve("drivers/" + drv + "/publisher/pub-ets-ps-Relationships");
            assertTrue(CanonicalXmlText.of(pub).contains(drv));
            assertNotNull(ds.resolve("drivers/" + drv + "/subscriber/sub-etp"));
            // a deploy writes them where they are
            String dn = "cn=" + drv + "," + set;
            assertEquals("cn=pub-ets-ps-Relationships,cn=pub," + dn,
                com.pointblue.dirxml.dev.deploy.VaultMapping.artifactDn(set, pub, ds));
            List<String> values = com.pointblue.dirxml.dev.deploy.VaultMapping.policiesValues(set, d, ds);
            assertTrue(values.toString(), values.contains("cn=pub-ets-ps-Relationships,cn=pub," + dn + "#0#2"));
            assertTrue(values.toString(), values.contains("cn=sub-etp,cn=sub," + dn + "#0#3"));
            assertEquals("cn=sub," + dn, com.pointblue.dirxml.dev.deploy.VaultMapping.channelDn(set, drv, Scope.SUBSCRIBER, ds));
        }
        // a driver read without names keeps the defaults
        assertEquals("cn=Publisher,cn=New," + set, com.pointblue.dirxml.dev.deploy.VaultMapping.channelDn(set, "New", Scope.PUBLISHER, ds));
    }
}
