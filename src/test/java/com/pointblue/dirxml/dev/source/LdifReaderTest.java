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
}
