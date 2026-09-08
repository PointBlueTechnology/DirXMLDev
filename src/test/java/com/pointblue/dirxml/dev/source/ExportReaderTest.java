package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.Xds;
import org.junit.Test;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Designer "Export to Configuration File" → model: both root forms, rule/stylesheet
 * wrappers, resources, config blobs, and policy-linkage resolution (including
 * unresolved targets and unknown set ids).
 */
public class ExportReaderTest {

    // ---- single-driver form ---------------------------------------------------

    private static final String SINGLE_DRIVER_XML =
        "<driver-configuration dn=\"cn=UKG,cn=driverset1,ou=idm,o=system\" "
        + "driver-set-dn=\"cn=driverset1,ou=idm,o=system\" name=\"UKG\">"
        + "  <attributes>"
        + "    <driver-filter-xml><filter><filter-class class-name=\"User\"/></filter></driver-filter-xml>"
        + "    <java-module value=\"com.pointbluetech.idm.ukg.driv.UKGDriverShim\"/>"
        + "    <policy-linkage>"
        + "      <linkage-item dn=\"cn=sch_Employee Map,cn=UKG,cn=driverset1,ou=idm,o=system\" order=\"0\" policy-set=\"0\"/>"
        + "      <linkage-item dn=\"cn=sub-etp_Scoping,cn=Subscriber,cn=UKG,cn=driverset1,ou=idm,o=system\" order=\"0\" policy-set=\"4\"/>"
        + "      <linkage-item dn=\"cn=pub-otp_Xform,cn=Publisher,cn=UKG,cn=driverset1,ou=idm,o=system\" order=\"0\" policy-set=\"5\"/>"
        + "      <linkage-item dn=\"cn=sub-ctp_Xslt,cn=Subscriber,cn=UKG,cn=driverset1,ou=idm,o=system\" order=\"1\" policy-set=\"10\"/>"
        + "      <linkage-item dn=\"cn=JFW-UKG_GCVs,cn=UKG,cn=driverset1,ou=idm,o=system\" order=\"0\" policy-set=\"14\"/>"
        + "      <linkage-item dn=\"cn=weird,cn=UKG,cn=driverset1,ou=idm,o=system\" order=\"0\" policy-set=\"99\"/>"
        + "    </policy-linkage>"
        + "    <shim-auth-id value=\"IDVAULTAPI\"/>"
        + "    <shim-auth-server value=\"https://rental2.ultipro.com\"/>"
        + "    <shim-config-info-xml><driver-config name=\"REST Driver\">"
        + "      <driver-options><configuration-values><definitions/></configuration-values></driver-options>"
        + "    </driver-config></shim-config-info-xml>"
        + "    <global-engine-values><configuration-values><definitions>"
        + "      <definition name=\"dirxml.engine.retry-interval\" type=\"integer\"><value>30</value></definition>"
        + "    </definitions></configuration-values></global-engine-values>"
        + "    <global-config-values><configuration-values><definitions/></configuration-values></global-config-values>"
        + "  </attributes>"
        + "  <children>"
        + "    <publisher name=\"Publisher\"><children>"
        + "      <rule name=\"pub-otp_Xform\"><policy><rule><conditions/><actions/></rule></policy></rule>"
        + "    </children></publisher>"
        + "    <subscriber name=\"Subscriber\"><children>"
        + "      <rule name=\"sub-etp_Scoping\"><policy><rule><conditions/><actions><do-veto/></actions></rule></policy></rule>"
        + "      <stylesheet name=\"sub-ctp_Xslt\"><xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\"/></stylesheet>"
        + "    </children></subscriber>"
        + "    <rule name=\"sch_Employee Map\"><attr-name-map/></rule>"
        + "    <global-config-def name=\"JFW-UKG_GCVs\" package-id=\"PKG-1\"><attributes/>"
        + "<configuration-values><definitions><definition name=\"drv.x\" display-name=\"x\" type=\"string\"><value>1</value></definition></definitions></configuration-values>"
        + "</global-config-def>"
        + "  </children>"
        + "  <global-config-values><configuration-values><definitions display-name=\"Common Settings\">"
        + "    <definition name=\"idv.dit.data.users\" type=\"dn\"><value>data\\users</value></definition>"
        + "  </definitions></configuration-values></global-config-values>"
        + "</driver-configuration>";

    private static DriverSet singleDriverSample() {
        Element root = Xds.parse(SINGLE_DRIVER_XML).getDocumentElement();
        return ExportReader.read(root, "UKG.xml");
    }

    @Test
    public void singleDriverFormSynthesizesDriverSetFromDriverSetDn() {
        DriverSet ds = singleDriverSample();
        assertEquals("driverset1", ds.name);
        assertEquals("cn=driverset1,ou=idm,o=system", ds.dn);
        assertEquals("UKG.xml", ds.meta.get("source.file"));
        assertEquals(1, ds.drivers.size());
        // the ancestor driver set's GCVs, included at export root level for context
        assertNotNull(ds.configValues);
        assertTrue(Xds.serializeElement(ds.configValues).contains("idv.dit.data.users"));
    }

    @Test
    public void singleDriverFormCapturesShimAndConfigBlobs() {
        Driver ukg = singleDriverSample().driver("UKG");
        assertNotNull(ukg);
        assertEquals("com.pointbluetech.idm.ukg.driv.UKGDriverShim", ukg.shimClass);
        assertEquals("https://rental2.ultipro.com", ukg.shimAuthServer);
        assertEquals("IDVAULTAPI", ukg.shimAuthId);

        assertTrue(ukg.config.containsKey(Driver.DRIVER_FILTER));
        assertEquals("filter", ukg.config.get(Driver.DRIVER_FILTER).getLocalName());

        assertTrue(ukg.config.containsKey(Driver.CONFIG_VALUES));
        assertEquals("configuration-values", ukg.config.get(Driver.CONFIG_VALUES).getLocalName());

        assertTrue(ukg.config.containsKey(Driver.ENGINE_CONTROL_VALUES));
        assertTrue(Xds.serializeElement(ukg.config.get(Driver.ENGINE_CONTROL_VALUES))
            .contains("dirxml.engine.retry-interval"));

        assertTrue(ukg.config.containsKey(Driver.SHIM_CONFIG_INFO));
        assertEquals("driver-config", ukg.config.get(Driver.SHIM_CONFIG_INFO).getLocalName());
    }

    @Test
    public void singleDriverFormPlacesPoliciesByChannelAndKind() {
        Driver ukg = singleDriverSample().driver("UKG");
        assertEquals(1, ukg.policies.size());
        assertEquals("sch_Employee Map", ukg.policies.get(0).name);
        assertEquals(Policy.Kind.SCHEMA_MAP, ukg.policies.get(0).policyKind());
        assertEquals(Scope.DRIVER, ukg.policies.get(0).scope);
        assertEquals("drivers/UKG/sch_Employee Map", ukg.policies.get(0).path());

        assertEquals(1, ukg.publisher.policies.size());
        assertEquals(Policy.Kind.DIRXML_SCRIPT, ukg.publisher.policies.get(0).policyKind());
        assertEquals("drivers/UKG/publisher/pub-otp_Xform", ukg.publisher.policies.get(0).path());

        assertEquals(2, ukg.subscriber.policies.size());
        Policy scoping = ukg.subscriber.policies.get(0);
        Policy xslt = ukg.subscriber.policies.get(1);
        assertEquals(Policy.Kind.DIRXML_SCRIPT, scoping.policyKind());
        assertEquals(Policy.Kind.XSLT, xslt.policyKind());
        assertEquals("drivers/UKG/subscriber/sub-ctp_Xslt", xslt.path());
    }

    @Test
    public void singleDriverFormResolvesLinkageAndReportsUnresolvedAndUnknown() {
        DriverSet ds = singleDriverSample();
        Driver ukg = ds.driver("UKG");

        // 6 linkage-items in the export; one (set 99) is an unknown policy-set id.
        assertEquals(5, ukg.links.size());
        assertTrue(ukg.meta.containsKey("linkage.unknown.0"));
        assertTrue(ukg.meta.get("linkage.unknown.0").contains("cn=weird"));

        assertEquals("drivers/UKG/sch_Employee Map", ukg.links(PolicySet.SCHEMA_MAPPING).get(0).ref);
        assertEquals("drivers/UKG/subscriber/sub-etp_Scoping", ukg.links(PolicySet.SUB_EVENT).get(0).ref);
        assertEquals("drivers/UKG/publisher/pub-otp_Xform", ukg.links(PolicySet.PUB_EVENT).get(0).ref);
        assertEquals("drivers/UKG/subscriber/sub-ctp_Xslt", ukg.links(PolicySet.SUB_COMMAND).get(0).ref);

        // the GCV-set link points at a <global-config-def>, which is modeled as a
        // GCV-definition resource so the link resolves and the content is kept.
        List<PolicyLink> unresolved = ds.unresolvedLinks();
        assertTrue("expected no unresolved links, got " + unresolved, unresolved.isEmpty());
        assertEquals("drivers/UKG/JFW-UKG_GCVs", ukg.links(PolicySet.GCV).get(0).ref);
        com.pointblue.dirxml.dev.model.Resource gcv =
            (com.pointblue.dirxml.dev.model.Resource) ds.resolve("drivers/UKG/JFW-UKG_GCVs");
        assertNotNull(gcv);
        assertTrue(gcv.isGcvDef());
        assertEquals("configuration-values", gcv.content.getLocalName() != null
            ? gcv.content.getLocalName() : gcv.content.getNodeName());

        assertFalse(ds.index().isEmpty());
    }

    // ---- driver-set form --------------------------------------------------------

    private static final String DRIVER_SET_XML =
        "<driver-set-configuration dn=\"cn=DS1,o=system\" name=\"DS1\">"
        + "  <driver-set-attributes>"
        + "    <global-config-values><configuration-values><definitions>"
        + "      <definition name=\"drvset.notif.email\" type=\"string\"><value>ops@example.com</value></definition>"
        + "    </definitions></configuration-values></global-config-values>"
        + "  </driver-set-attributes>"
        + "  <children>"
        + "    <policy-library base-dn=\"cn=Library,cn=DS1,o=system\" name=\"Library\">"
        + "      <rule name=\"lib-common-event\"><policy><rule><conditions/><actions/></rule></policy></rule>"
        + "      <resource content-type=\"application/vnd.novell.dirxml.mapping-table+xml\" name=\"LocCodeMap\">"
        + "        <content contains=\"xml\"><mapping-table><col-def name=\"a\"/></mapping-table></content>"
        + "      </resource>"
        + "      <resource content-type=\"text/ecmascript;charset=UTF-8\" name=\"es-misc\">"
        + "        <content contains=\"text\">function f(){}</content>"
        + "      </resource>"
        + "    </policy-library>"
        + "  </children>"
        + "  <driver-configuration dn=\"cn=AD,cn=DS1,o=system\" name=\"AD\">"
        + "    <attributes>"
        + "      <java-module value=\"com.novell.nds.dirxml.driver.ad.ADDriverShim\"/>"
        + "      <policy-linkage>"
        + "        <linkage-item dn=\"cn=lib-common-event,cn=Library,cn=DS1,o=system\" order=\"0\" policy-set=\"4\"/>"
        + "        <linkage-item dn=\"cn=lib-not-exported,cn=Library,cn=DS1,o=system\" order=\"1\" policy-set=\"4\"/>"
        + "      </policy-linkage>"
        + "    </attributes>"
        + "    <children>"
        + "      <subscriber name=\"Subscriber\"><children/></subscriber>"
        + "      <publisher name=\"Publisher\"><children/></publisher>"
        + "    </children>"
        + "  </driver-configuration>"
        + "</driver-set-configuration>";

    private static DriverSet driverSetSample() {
        Element root = Xds.parse(DRIVER_SET_XML).getDocumentElement();
        return ExportReader.read(root, "DS1.xml");
    }

    @Test
    public void driverSetFormReadsNameDnAndConfigValues() {
        DriverSet ds = driverSetSample();
        assertEquals("DS1", ds.name);
        assertEquals("cn=DS1,o=system", ds.dn);
        assertNotNull(ds.configValues);
        assertTrue(Xds.serializeElement(ds.configValues).contains("drvset.notif.email"));
        assertEquals(1, ds.drivers.size());
    }

    @Test
    public void driverSetFormReadsLibraryPoliciesAndResources() {
        DriverSet ds = driverSetSample();
        assertEquals(1, ds.library.policies.size());
        assertEquals("lib-common-event", ds.library.policies.get(0).name);
        assertEquals(Scope.LIBRARY, ds.library.policies.get(0).scope);
        assertEquals("library/lib-common-event", ds.library.policies.get(0).path());

        assertEquals(2, ds.library.resources.size());
        Resource table = (Resource) ds.resolve("library/LocCodeMap");
        assertNotNull(table);
        assertTrue(table.isMappingTable());
        assertNotNull(table.content);
        assertEquals("mapping-table", table.content.getLocalName());
        assertNull(table.text);

        Resource js = (Resource) ds.resolve("library/es-misc");
        assertNotNull(js);
        assertTrue(js.isEcmaScript());
        assertEquals("function f(){}", js.text);
        assertNull(js.content);
    }

    @Test
    public void driverSetFormResolvesLibraryLinkageAndReportsMissingLibraryTarget() {
        DriverSet ds = driverSetSample();
        Driver ad = ds.driver("AD");
        assertNotNull(ad);
        assertEquals(2, ad.links.size());
        List<PolicyLink> subEvent = ad.links(PolicySet.SUB_EVENT);
        assertEquals("library/lib-common-event", subEvent.get(0).ref);
        assertEquals("library/lib-not-exported", subEvent.get(1).ref);

        List<PolicyLink> unresolved = ds.unresolvedLinks();
        assertEquals(1, unresolved.size());
        assertEquals("library/lib-not-exported", unresolved.get(0).ref);

        assertFalse(ds.index().isEmpty());
    }

    // ---- resolveRef unit coverage -----------------------------------------------

    @Test
    public void resolveRefByDnSecondComponent() {
        assertEquals("library/x", ExportReader.resolveRef("cn=x,cn=Library,cn=DS1,o=system", "AD", "Library"));
        assertEquals("drivers/AD/publisher/x",
            ExportReader.resolveRef("cn=x,cn=Publisher,cn=AD,cn=DS1,o=system", "AD", "Library"));
        assertEquals("drivers/AD/subscriber/x",
            ExportReader.resolveRef("cn=x,cn=Subscriber,cn=AD,cn=DS1,o=system", "AD", "Library"));
        assertEquals("drivers/AD/x", ExportReader.resolveRef("cn=x,cn=AD,cn=DS1,o=system", "AD", "Library"));
    }

    // ---- real files (guarded) ----------------------------------------------------

    @Test
    public void realJfwSingleDriverExportWhenPresent() {
        Path f = Path.of(System.getProperty("user.home"), "IdeaProjects", "DirXMLSimulator", "JFW-DEV-UKG.xml");
        assumeTrue("needs the local JFW-DEV-UKG.xml export", Files.exists(f));

        DriverSet ds = ExportReader.read(f);
        assertEquals(1, ds.drivers.size());
        Driver ukg = ds.drivers.get(0);
        assertFalse("expected subscriber policies", ukg.subscriber.policies.isEmpty());
        assertFalse("expected publisher policies", ukg.publisher.policies.isEmpty());
        long distinctSets = ukg.links.stream().map(l -> l.set).distinct().count();
        assertTrue("expected >=6 distinct policy sets, got " + distinctSets, distinctSets >= 6);
        assertFalse(ds.index().isEmpty()); // no duplicate-path IllegalStateException

        List<PolicyLink> unresolved = ds.unresolvedLinks();
        System.out.println("JFW export: " + ds + "; unresolved links: " + unresolved);
    }

    @Test
    public void realRfiDriverSetExportWhenPresent() {
        Path f = Path.of(System.getProperty("user.home"), "tmp", "RFI-DriverSet.xml");
        assumeTrue("needs the local RFI-DriverSet.xml export", Files.exists(f));

        DriverSet ds = ExportReader.read(f);
        assertTrue("expected at least 1 driver, got " + ds.drivers.size(), ds.drivers.size() >= 1);

        long mappingTables = ds.library.resources.stream().filter(Resource::isMappingTable).count();
        assertTrue("expected >=4 mapping-table resources, got " + mappingTables, mappingTables >= 4);
        List<String> resourceNames = ds.library.resources.stream().map(r -> r.name).collect(Collectors.toList());
        assertTrue("expected DeptCodeMap among " + resourceNames, resourceNames.contains("DeptCodeMap"));
        assertTrue("expected LocCodeMap among " + resourceNames, resourceNames.contains("LocCodeMap"));
        long ecma = ds.library.resources.stream().filter(Resource::isEcmaScript).count();
        assertTrue("expected >=1 ecmascript resource, got " + ecma, ecma >= 1);

        assertFalse(ds.index().isEmpty()); // no duplicate-path IllegalStateException

        List<PolicyLink> unresolved = ds.unresolvedLinks();
        System.out.println("RFI export: " + ds + "; unresolved links: " + unresolved.size());
        for (PolicyLink l : unresolved) {
            System.out.println("  unresolved: " + l);
        }
        int totalLinks = ds.drivers.stream().mapToInt(d -> d.links.size()).sum();
        assertTrue("unresolved link count (" + unresolved.size() + ") should be small relative to total (" + totalLinks + ")",
            unresolved.size() < totalLinks);
    }
}
