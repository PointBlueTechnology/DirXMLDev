package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.sim.DesignerProject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Designer project (on-disk CObject metadata + {@code _contents.xml}) -&gt; model.
 * The synthetic fixture below mimics the shape learned from a hand-built workspace
 * ({@code ~/designer_workspace/test11}) and a 50-driver production project — see
 * {@link ProjectReader}'s class doc for the exact type suffixes and relation names.
 */
public class ProjectReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ---- synthetic project fixture ------------------------------------------------

    private static String esc(String xml) {
        return xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String cobject(String name, String type, String attrsXml, String relationsXml) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<com.novell.designer.model:CObject xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\" name=\"" + name + "\" type=\"" + type + "\">"
            + attrsXml + relationsXml
            + "</com.novell.designer.model:CObject>";
    }

    private static String cstring(String attrName, String value) {
        return "<attributes xsi:type=\"com.novell.designer.model:CString\" attrName=\"" + attrName + "\" value=\"" + esc(value) + "\"/>";
    }

    private static String rel(String name, String type, String key) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + key + "\"/>";
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    /**
     * Builds a small project under {@code root/Model/EdirOrphan}:
     * DriverSet "DS1" (context o=system) with Library "Library" (a shared policy,
     * a mapping table, an ECMAScript resource) and one driver "AD" (filter, shim
     * attrs + config blobs, a driver-scope GCV definition, a schema map, an input
     * and output transform, subscriber policies including one bound to the shared
     * library policy, and a dangling command-policy reference). A second, driverless
     * DriverSet "DS2" sits alongside it to exercise multi-driverset selection.
     */
    private Path buildProject() throws IOException {
        Path root = tmp.getRoot().toPath();
        Path orphan = root.resolve("Model/EdirOrphan");

        // ---- DS2: empty sibling driver set (no Idm:Drivers) ----
        write(orphan.resolve("DS2ID.DriverSet_"), cobject("DS2", "DriverSet",
            cstring("DSetContext", "o=system"), ""));

        // ---- DS1 ----
        Path ds1 = orphan.resolve("DS1ID");

        // library: shared policy + mapping table + ecmascript
        write(orphan.resolve("LIB1ID.Library_"), cobject("Library", "Library", "",
            rel("Idm:Policies", "Child", "LIBPOLID.ScriptPolicy_")
            + rel("Idm:Resources", "Child", "LIBMAPID.MappingTableResource_")
            + rel("Idm:Resources", "Child", "LIBJSID.ECMAScriptResource_")));
        write(ds1.resolve("LIBPOLID.ScriptPolicy_"), cobject("lib-common-event", "ScriptPolicy", "", ""));
        write(ds1.resolve("LIBPOLID_contents.xml"),
            "<policy><rule><description>shared</description><conditions/><actions/></rule></policy>");
        write(ds1.resolve("LIBMAPID.MappingTableResource_"), cobject("LocCodeMap", "MappingTableResource",
            cstring("DirXML-ContentType", Resource.MAPPING_TABLE), ""));
        write(ds1.resolve("LIBMAPID_contents.xml"), "<mapping-table><col-def name=\"a\"/></mapping-table>");
        write(ds1.resolve("LIBJSID.ECMAScriptResource_"), cobject("es-misc", "ECMAScriptResource",
            cstring("DirXML-ContentType", "text/ecmascript;charset=UTF-8"), ""));
        write(ds1.resolve("LIBJSID_contents.xml"), "function f(){}");

        // driver-scope: schema map, input/output transform, GCV definition, filter
        write(ds1.resolve("SCHID.MappingPolicy_"), cobject("sch-EmployeeMap", "MappingPolicy", "", ""));
        write(ds1.resolve("SCHID_contents.xml"),
            "<attr-name-map><class-name><nds-name>User</nds-name><app-name>User</app-name></class-name></attr-name-map>");
        write(ds1.resolve("INID.ScriptPolicy_"), cobject("drv-itp_Xform", "ScriptPolicy", "", ""));
        write(ds1.resolve("INID_contents.xml"), "<policy><rule><conditions/><actions/></rule></policy>");
        write(ds1.resolve("OUTID.ScriptPolicy_"), cobject("drv-otp_Xform", "ScriptPolicy", "", ""));
        write(ds1.resolve("OUTID_contents.xml"), "<policy><rule><conditions/><actions/></rule></policy>");
        write(ds1.resolve("FILT1.Filter_"), cobject("Filter", "Filter", "", ""));
        write(ds1.resolve("FILT1_contents.xml"),
            "<filter><filter-class class-name=\"User\" publisher=\"ignore\" subscriber=\"sync\"/></filter>");
        write(ds1.resolve("GCVID.GlobalConfig_"), cobject("JFW-AD_GCVs", "GlobalConfig",
            cstring("Idm:PackageGuid", "PKG-1") + cstring("Idm:PackageAssocGuid", "PKGASSOC-1")
            + cstring("Idm:InstalledLinkages", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><policy-linkage>\n\t<policy-set Driver=\"DRV1ID\" name=\"gcv\" order=\"Weight\" package-id=\"PKG-1\" value=\"120\"/>\n</policy-linkage>")
            + "<attributes xsi:type=\"com.novell.designer.model:CLong\" attrName=\"Idm:ContentChecksum\" value=\"12345\"/>", ""));
        write(ds1.resolve("GCVID_SRV1_DirXML-ConfigValues.xml"),
            "<?xml version=\"1.0\"?><configuration-values><definitions>"
            + "<definition name=\"drv.x\" display-name=\"x\" type=\"string\"><value>1</value></definition>"
            + "</definitions></configuration-values>");

        // driver's own resolved GCVs (config-values), distinct from the GCV definition above
        write(ds1.resolve("DRV1ID_SRV1_DirXML-ConfigValues.xml"),
            "<?xml version=\"1.0\"?><configuration-values><definitions>"
            + "<definition name=\"idv.dit.data.users\" type=\"dn\"><value>data\\users</value></definition>"
            + "</definitions></configuration-values>");

        // subscriber: driver-owned event/matching policies, one shared library event
        // policy in the same set, and a dangling command-policy reference
        write(ds1.resolve("SUB1.Subscriber_"), cobject("Subscriber", "Subscriber", "",
            rel("Idm:Policies", "Child", "SUBEVID.ScriptPolicy_")
            + rel("Idm:Policies", "Child", "SUBMATCHID.ScriptPolicy_")
            + rel("Idm:EventPolicies", "Reference", "SUBEVID.ScriptPolicy_")
            + rel("Idm:EventPolicies", "Reference", "LIBPOLID.ScriptPolicy_")
            + rel("Idm:MatchingPolicies", "Reference", "SUBMATCHID.ScriptPolicy_")
            + rel("Idm:CommandPolicies", "Reference", "MISSINGID.ScriptPolicy_")));
        write(ds1.resolve("SUB1/SUBEVID.ScriptPolicy_"), cobject("sub-etp_Scoping", "ScriptPolicy", "", ""));
        write(ds1.resolve("SUB1/SUBEVID_contents.xml"),
            "<policy><rule><conditions/><actions><do-veto/></actions></rule></policy>");
        write(ds1.resolve("SUB1/SUBMATCHID.ScriptPolicy_"), cobject("sub-mp_Matching", "ScriptPolicy", "", ""));
        write(ds1.resolve("SUB1/SUBMATCHID_contents.xml"), "<policy><rule><conditions/><actions/></rule></policy>");

        // publisher: one XSLT event policy
        write(ds1.resolve("PUB1.Publisher_"), cobject("Publisher", "Publisher", "",
            rel("Idm:Policies", "Child", "PUBEVID.StylesheetPolicy_")
            + rel("Idm:EventPolicies", "Reference", "PUBEVID.StylesheetPolicy_")));
        write(ds1.resolve("PUB1/PUBEVID.StylesheetPolicy_"), cobject("pub-etp_Xslt", "StylesheetPolicy", "", ""));
        write(ds1.resolve("PUB1/PUBEVID_contents.xml"),
            "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\"/>");

        // the driver itself
        String shimConfigInfo = "<?xml version=\"1.0\"?><driver-config name=\"AD\"><driver-options>"
            + "<configuration-values><definitions/></configuration-values></driver-options></driver-config>";
        String engineControlValues = "<?xml version=\"1.0\"?><configuration-values><definitions>"
            + "<definition name=\"dirxml.engine.retry-interval\" type=\"integer\"><value>30</value></definition>"
            + "</definitions></configuration-values>";
        String driverAttrs = cstring("DirXML-JavaModule", "com.novell.nds.dirxml.driver.ad.ADDriverShim")
            + cstring("DirXML-ShimAuthServer", "ad.example.com")
            + cstring("DirXML-ShimAuthID", "svc")
            + cstring("DirXML-ShimConfigInfo", shimConfigInfo)
            + cstring("DirXML-EngineControlValues", engineControlValues)
            + "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"DirXML-ConfigValues\"/>"
            + cstring("DirXML-DriverVersion", "1.0.0")
            + cstring("Idm:PackageGuid", "PKG-DRV")
            + cstring("Idm:PackageAssocGuid", "PKGASSOC-DRV")
            + "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"icon\" extension=\"gif\"/>";
        String driverRelations = rel("Idm:Filter", "Child", "FILT1.Filter_")
            + rel("Idm:Subscriber", "Child", "SUB1.Subscriber_")
            + rel("Idm:Publisher", "Child", "PUB1.Publisher_")
            + rel("Idm:Policies", "Child", "SCHID.MappingPolicy_")
            + rel("Idm:Policies", "Child", "INID.ScriptPolicy_")
            + rel("Idm:Policies", "Child", "OUTID.ScriptPolicy_")
            + rel("Idm:MappingPolicies", "Reference", "SCHID.MappingPolicy_")
            + rel("Idm:InputPolicies", "Reference", "INID.ScriptPolicy_")
            + rel("Idm:OutputPolicies", "Reference", "OUTID.ScriptPolicy_")
            + rel("Idm:GlobalConfigs", "Child", "GCVID.GlobalConfig_")
            + rel("Idm:ExtensionFunctions", "Reference", "LIBJSID.ECMAScriptResource_");
        driverRelations += rel("Idm:InstalledPackages", "Reference", "PKGDRVID.IdmPackage_")
            + rel("Idm:InstalledPackages", "Reference", "PKG1ID.IdmPackage_");
        write(orphan.resolve("DRV1ID.Driver_"), cobject("AD", "AD-Driver", driverAttrs, driverRelations));
        // the project's package catalog: the two packages the driver has installed (the symbolic
        // name is not stored — Designer derives it from the vendor and short names)
        Path catalog = root.resolve("Model/Project/PROJID/CATID/Directory/FOLDID");
        write(catalog.resolve("PKGDRVID.IdmPackage_"), cobject("Driver Base", "IdmPackage",
            cstring("Idm:PackageGuid", "PKG-DRV") + cstring("Idm:PackageVersion", "1.0.0")
            + cstring("Idm:shortName", "NOVLDRV") + cstring("Idm:vendorName", "Novell, Inc.")
            + "<attributes xsi:type=\"com.novell.designer.model:CBoolean\" attrName=\"Idm:BasePackage\" value=\"true\"/>", ""));
        write(catalog.resolve("PKG1ID.IdmPackage_"), cobject("GCV Package", "IdmPackage",
            cstring("Idm:PackageGuid", "PKG-1") + cstring("Idm:PackageVersion", "2.0.0")
            + cstring("Idm:shortName", "NOVLGCV") + cstring("Idm:vendorName", "NetIQ Corporation"), ""));
        // the driver's icon: the heavy-data attribute above plus these bytes beside the Driver_
        writeBytes(orphan.resolve("DRV1ID_icon.gif"), AsCodeRoundTripTest.TINY_GIF);

        // DS1 itself, after its children so paths above already exist
        write(orphan.resolve("DS1ID.DriverSet_"), cobject("DS1", "DriverSet",
            cstring("DSetContext", "o=system"),
            rel("Idm:Libraries", "Child", "LIB1ID.Library_")
            + rel("Idm:Drivers", "Child", "DRV1ID.Driver_")));

        return root;
    }

    // ---- structure ------------------------------------------------------------------

    @Test
    public void picksTheDriverSetWithDriversAndNotesTheOthers() throws IOException {
        Path root = buildProject();
        DriverSet ds = ProjectReader.read(root);
        assertEquals("DS1", ds.name);
        assertEquals(1, ds.drivers.size());
        assertEquals("DS2", ds.meta.get("project.other-driversets"));
        assertEquals("cn=DS1,o=system", ds.dn);
        assertEquals("true", ds.meta.get("dn.synthesized"));
        assertEquals(root.toString(), ds.meta.get("source.file"));
    }

    @Test
    public void secondOverloadPicksDriverSetByName() throws IOException {
        Path root = buildProject();
        DriverSet ds2 = ProjectReader.read(root, "DS2");
        assertEquals("DS2", ds2.name);
        assertEquals(0, ds2.drivers.size());
        assertEquals("DS1", ds2.meta.get("project.other-driversets"));
    }

    @Test
    public void libraryHoldsSharedPolicyMappingTableAndEcmaScript() throws IOException {
        DriverSet ds = ProjectReader.read(buildProject());
        assertEquals(1, ds.library.policies.size());
        Policy shared = ds.library.policies.get(0);
        assertEquals("lib-common-event", shared.name);
        assertEquals(Policy.Kind.DIRXML_SCRIPT, shared.policyKind());
        assertEquals("library/lib-common-event", shared.path());
        assertEquals("LIBPOLID", shared.meta.get("designer.id"));
        assertEquals("ScriptPolicy", shared.meta.get("designer.type"));

        assertEquals(2, ds.library.resources.size());
        Resource table = (Resource) ds.resolve("library/LocCodeMap");
        assertNotNull(table);
        assertTrue(table.isMappingTable());
        assertEquals("mapping-table", table.content.getLocalName());
        assertNull(table.text);

        Resource js = (Resource) ds.resolve("library/es-misc");
        assertNotNull(js);
        assertTrue(js.isEcmaScript());
        assertEquals("function f(){}", js.text);
        assertNull(js.content);
    }

    @Test
    public void driverCarriesShimAndConfigBlobsAndPackageMeta() throws IOException {
        Driver ad = ProjectReader.read(buildProject()).driver("AD");
        assertNotNull(ad);
        assertEquals("com.novell.nds.dirxml.driver.ad.ADDriverShim", ad.shimClass);
        assertEquals("ad.example.com", ad.shimAuthServer);
        assertEquals("svc", ad.shimAuthId);
        assertEquals("cn=AD,cn=DS1,o=system", ad.dn);
        assertEquals("true", ad.meta.get("dn.synthesized"));
        assertEquals("DRV1ID", ad.meta.get("designer.id"));
        assertEquals("Driver", ad.meta.get("designer.type"));
        assertEquals("AD-Driver", ad.meta.get("designer.driver-type"));
        assertEquals("1.0.0", ad.meta.get("version"));
        // package stamps in the tree's (the vault's) vocabulary: the full record Designer would
        // deploy, composed from the project's IdmPackage_ objects; the installed packages as the
        // package installer records them, the base one also as the driver's own record
        assertEquals("PKG-DRV;com.novellinc.novldrv;1.0.0;Driver Base;NOVLDRV", ad.meta.get("dirxml-pkgguid"));
        assertEquals("PKGASSOC-DRV", ad.meta.get("dirxml-pkgassociationid"));
        assertEquals("PKG-DRV;com.novellinc.novldrv;1.0.0;Driver Base;NOVLDRV;base", ad.meta.get("package.installed.NOVLDRV"));
        assertEquals("PKG-1;com.netiqcorporation.novlgcv;2.0.0;GCV Package;NOVLGCV", ad.meta.get("package.installed.NOVLGCV"));
        assertEquals("2", ad.meta.get("packages.count"));
        assertNull(ad.meta.get("package-id"));
        assertNull(ad.meta.get("pkg-assoc-id"));

        assertTrue(ad.config.containsKey(Driver.SHIM_CONFIG_INFO));
        assertEquals("driver-config", ad.config.get(Driver.SHIM_CONFIG_INFO).getLocalName());

        assertTrue(ad.config.containsKey(Driver.ENGINE_CONTROL_VALUES));
        assertEquals("configuration-values", ad.config.get(Driver.ENGINE_CONTROL_VALUES).getLocalName());

        assertTrue(ad.config.containsKey(Driver.CONFIG_VALUES));
        assertEquals("configuration-values", ad.config.get(Driver.CONFIG_VALUES).getLocalName());
        assertTrue(com.pointblue.dirxml.sim.Xds.serializeElement(ad.config.get(Driver.CONFIG_VALUES))
            .contains("idv.dit.data.users"));

        assertTrue(ad.config.containsKey(Driver.DRIVER_FILTER));
        assertEquals("filter", ad.config.get(Driver.DRIVER_FILTER).getLocalName());
    }

    @Test
    public void driverScopeArtifactsAndGcvDefinitionAreModeled() throws IOException {
        DriverSet ds = ProjectReader.read(buildProject());
        Driver ad = ds.driver("AD");
        assertEquals(3, ad.policies.size());
        assertEquals(1, ad.resources.size());

        Resource gcv = (Resource) ds.resolve("drivers/AD/JFW-AD_GCVs");
        assertNotNull(gcv);
        assertTrue(gcv.isGcvDef());
        assertNotNull(gcv.content);
        assertEquals("configuration-values", gcv.content.getLocalName());
        assertEquals("PKG-1;com.netiqcorporation.novlgcv;2.0.0;GCV Package;NOVLGCV", gcv.meta.get("dirxml-pkgguid"));
        assertEquals("PKGASSOC-1", gcv.meta.get("dirxml-pkgassociationid"));
        assertEquals("12345", gcv.meta.get("dirxml-pkgchecksum"));
        assertNull(gcv.meta.get("checksum"));
        // the package's record of the link, exactly as the vault holds it in DirXML-pkgLinkages
        assertTrue(gcv.meta.get("dirxml-pkglinkages"), gcv.meta.get("dirxml-pkglinkages")
            .contains("<policy-set Driver=\"DRV1ID\" name=\"gcv\" order=\"Weight\" package-id=\"PKG-1\" value=\"120\"/>"));
    }

    @Test
    public void channelsHoldOwnedPolicies() throws IOException {
        Driver ad = ProjectReader.read(buildProject()).driver("AD");
        assertEquals(2, ad.subscriber.policies.size());
        assertEquals(1, ad.publisher.policies.size());
        assertEquals(Policy.Kind.XSLT, ad.publisher.policies.get(0).policyKind());
        assertEquals("drivers/AD/publisher/pub-etp_Xslt", ad.publisher.policies.get(0).path());
    }

    @Test
    public void linkageSpansAtLeastFiveSetsIncludingALibraryPolicyAndReportsTheDanglingOne() throws IOException {
        DriverSet ds = ProjectReader.read(buildProject());
        Driver ad = ds.driver("AD");

        long distinctSets = ad.links.stream().map(l -> l.set).distinct().count();
        assertTrue("expected >=5 distinct policy sets, got " + distinctSets, distinctSets >= 5);

        assertEquals("drivers/AD/sch-EmployeeMap", ad.links(PolicySet.SCHEMA_MAPPING).get(0).ref);
        assertEquals("drivers/AD/drv-itp_Xform", ad.links(PolicySet.INPUT).get(0).ref);
        assertEquals("drivers/AD/drv-otp_Xform", ad.links(PolicySet.OUTPUT).get(0).ref);
        assertEquals("drivers/AD/JFW-AD_GCVs", ad.links(PolicySet.GCV).get(0).ref);
        assertEquals("library/es-misc", ad.links(PolicySet.ECMASCRIPT).get(0).ref);

        List<PolicyLink> subEvent = ad.links(PolicySet.SUB_EVENT);
        assertEquals(2, subEvent.size());
        assertEquals("drivers/AD/subscriber/sub-etp_Scoping", subEvent.get(0).ref);
        assertEquals("library/lib-common-event", subEvent.get(1).ref);   // the shared library policy

        assertEquals("drivers/AD/subscriber/sub-mp_Matching", ad.links(PolicySet.SUB_MATCH).get(0).ref);
        assertEquals("drivers/AD/publisher/pub-etp_Xslt", ad.links(PolicySet.PUB_EVENT).get(0).ref);

        List<PolicyLink> unresolved = ds.unresolvedLinks();
        assertEquals(1, unresolved.size());
        assertEquals(PolicySet.SUB_COMMAND, unresolved.get(0).set);
        assertEquals("drivers/AD/subscriber/MISSINGID", unresolved.get(0).ref);

        assertFalse(ds.index().isEmpty());   // no duplicate-path IllegalStateException
        assertEquals(10, ds.index().size());
    }

    // ---- driver icon -----------------------------------------------------------------

    @Test
    public void theDriverIconIsReadFromTheHeavyDataAttributeAndItsSiblingFile() throws IOException {
        Driver d = ProjectReader.read(buildProject()).driver("AD");
        assertArrayEquals(AsCodeRoundTripTest.TINY_GIF, d.icon);
        assertEquals("gif", d.iconExtension);
    }

    /** No {@code icon} attribute on the CObject means no icon, even if a stray file is there. */
    @Test
    public void withoutTheAttributeThereIsNoIcon() throws IOException {
        Path root = buildProject();
        Path meta = root.resolve("Model/EdirOrphan/DRV1ID.Driver_");
        write(meta, Files.readString(meta, StandardCharsets.UTF_8)
            .replace("<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"icon\" extension=\"gif\"/>", ""));
        assertNull(ProjectReader.read(root).driver("AD").icon);
    }

    /** The attribute's {@code extension} is authoritative — Designer writes {@code png} for some drivers. */
    @Test
    public void thePngExtensionIsKeptAsDesignerWroteIt() throws IOException {
        Path root = buildProject();
        Path meta = root.resolve("Model/EdirOrphan/DRV1ID.Driver_");
        write(meta, Files.readString(meta, StandardCharsets.UTF_8)
            .replace("attrName=\"icon\" extension=\"gif\"", "attrName=\"icon\" extension=\"png\""));
        Files.move(root.resolve("Model/EdirOrphan/DRV1ID_icon.gif"),
            root.resolve("Model/EdirOrphan/DRV1ID_icon.png"));
        Driver d = ProjectReader.read(root).driver("AD");
        assertEquals("png", d.iconExtension);
        assertArrayEquals(AsCodeRoundTripTest.TINY_GIF, d.icon);
    }

    // ---- real projects (guarded) ----------------------------------------------------

    @Test
    public void realTest11WorkspaceWhenPresent() {
        Path project = Path.of(System.getProperty("user.home"), "designer_workspace", "test11");
        assumeTrue("needs the local test11 Designer workspace", Files.isDirectory(project));

        DriverSet ds = ProjectReader.read(project);
        assertTrue("expected >=1 driver, got " + ds.drivers.size(), ds.drivers.size() >= 1);
        assertFalse(ds.index().isEmpty());   // no duplicate-path IllegalStateException

        List<String> mappingTableNames = ds.drivers.stream()
            .flatMap(d -> d.resources.stream())
            .filter(Resource::isMappingTable)
            .map(r -> r.name)
            .collect(Collectors.toList());
        assertTrue("expected NOVLMSGWMSYS-GCV_TO_IDM_TYPE among " + mappingTableNames,
            mappingTableNames.contains("NOVLMSGWMSYS-GCV_TO_IDM_TYPE"));

        List<PolicyLink> unresolved = ds.unresolvedLinks();
        System.out.println("test11: " + ds + "; unresolved links: " + unresolved.size());
        if (!unresolved.isEmpty()) {
            System.out.println("  sample: " + unresolved.get(0));
        }
    }

    /**
     * {@code test11pf} is Designer's own import of the test vault. Several of its drivers carry
     * a <b>custom</b> icon — one a user set in Designer, which no application-type or
     * driver-type lookup can reproduce — and {@code EventLogger} is one of them. The reader must
     * hand back exactly the bytes of the file beside the {@code Driver_}, and the tree must
     * carry them through unchanged.
     */
    @Test
    public void realTest11pfCarriesEventLoggersCustomIconThroughTheTree() throws IOException {
        Path project = Path.of(System.getProperty("user.home"), "designer_workspace", "test11pf");
        assumeTrue("needs the local test11pf Designer workspace", Files.isDirectory(project));

        Driver d = ProjectReader.read(project).driver("EventLogger");
        assertNotNull("test11pf should hold an EventLogger driver", d);
        assertNotNull("EventLogger's icon is a custom one; the reader must carry it", d.icon);
        assertEquals("gif", d.iconExtension);

        Path onDisk = project.resolve("Model/EdirOrphan/ZEZTZUKV")
            .resolve(d.meta.get("designer.id") + "_icon." + d.iconExtension);
        assertTrue("no icon file at " + onDisk, Files.isRegularFile(onDisk));
        assertArrayEquals(Files.readAllBytes(onDisk), d.icon);

        // and through a tree: byte for byte, both ways
        Path tree = tmp.newFolder("test11pf-tree").toPath();
        com.pointblue.dirxml.dev.ascode.AsCodeWriter.write(ProjectReader.read(project), tree);
        assertArrayEquals(d.icon,
            com.pointblue.dirxml.dev.ascode.AsCodeReader.read(tree).driver("EventLogger").icon);
    }

    @Test
    public void realAmicaPrdProjectWhenPresentMatchesSimulatorDriverNames() {
        Path project = Path.of("/private/tmp/claude-501/-Users-jcombs-Dev-DirXML-Engine-Analysis",
            "34814343-5cce-492a-8498-a04381e36292", "scratchpad", "amica-prd", "AMICA-PRD-20260627");
        assumeTrue("needs the unzipped Amica PRD project", Files.isDirectory(project));

        DriverSet ds = ProjectReader.read(project);
        assertTrue("expected >=1 driver, got " + ds.drivers.size(), ds.drivers.size() >= 1);
        assertFalse(ds.index().isEmpty());   // no duplicate-path IllegalStateException

        long mappingTables = ds.index().values().stream()
            .filter(a -> a instanceof Resource && ((Resource) a).isMappingTable())
            .count();
        assertTrue("expected >=1 mapping-table resource, got " + mappingTables, mappingTables >= 1);

        List<PolicyLink> unresolved = ds.unresolvedLinks();
        System.out.println("Amica PRD: " + ds + "; unresolved links: " + unresolved.size());
        if (!unresolved.isEmpty()) {
            System.out.println("  sample: " + unresolved.get(0));
        }

        Set<String> fromReader = new HashSet<>();
        for (Driver d : ds.drivers) {
            fromReader.add(d.name);
        }
        Set<String> fromSimulator = new HashSet<>(DesignerProject.load(project).driverNames());
        assertEquals(fromSimulator, fromReader);
    }
}
