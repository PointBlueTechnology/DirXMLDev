package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProjectWriter#create} — {@code export-project --new}, milestones N1+N2 of
 * {@code docs/designer-new-project.md}: a whole Designer project written from a tree.
 *
 * <p>The synthetic fixture is a small Designer project laid out the way a real one is
 * (a driver set with a Library, a packaged subscriber policy with its package baseline,
 * a filter, an entitlement, an AppConfig with one form and one bound PRD, and a
 * dangling {@code 0.ECMAScriptResource_} reference placeholder). It is read into a
 * tree, the tree is written into a brand-new project, and that project is read back:
 * the two models must be equal as-code, modulo the minted Designer ids (the same
 * "modulo minted ids" comparison {@code ProjectWriterTest} uses).
 *
 * <p>The guarded test does the same on the real {@code tree-test11pf} (19 drivers, 4
 * packaged drivers, 11 forms, 39 PRDs) when that tree is on the machine.
 */
public class NewProjectWriterTest {

    private static final String E2E_TREE = "/Users/jcombs/IdeaProjects/DirXMLDev-e2e/tree-test11pf";
    private static final String COMPOSER_SHIM = "com.novell.idm.driver.ComposerDriverShim";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private int seq;

    // ================================================================================
    // fixture: a small but realistically shaped Designer project
    // ================================================================================

    private static final String PACKAGE_ID = "B5PAGQ5E_201005261601510810";
    private static final String PKG_ASSOC_ID = "6KWCCG8H_201409191603360834";

    private static final String PACKAGED_POLICY_XML =
        "<policy><rule><description>packaged</description><conditions/><actions><do-veto/></actions></rule></policy>";

    private static final String ENTITLEMENT_XML =
        "<entitlement conflict-resolution=\"union\" description=\"Group membership\" display-name=\"Group\">"
        + "<values multi-valued=\"true\"><value>cn=all,o=data</value></values></entitlement>";

    private static final String FILTER_XML =
        "<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
        + "<filter-attr attr-name=\"CN\" merge-authority=\"default\" publisher=\"sync\" subscriber=\"sync\"/>"
        + "</filter-class></filter>";

    private static final String ECMA_TEXT = "function hello() { return 'hi'; }\n";

    private static final String STOCK_FORM_JSON =
        "{\"components\":["
        + "{\"key\":\"title\",\"type\":\"title\",\"input\":false},"
        + "{\"key\":\"reason\",\"type\":\"textfield\",\"input\":true},"
        + "{\"key\":\"submit\",\"type\":\"button\",\"input\":true}"
        + "],\"title\":\"Stock Form\",\"display\":\"form\",\"inlinescripts\":\"\",\"localization\":{},\"externalScripts\":[]}";

    private static final String STOCK_PRD_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<prov-req-defn status=\"Active\" flow-strategy=\"SingleFlow\" grant=\"true\" revoke=\"false\" "
        + "prov-category=\"systemTemplates\" prov-id=\"Stock Prd\">"
        + "<display-name xml:lang=\"en\">Stock Prd</display-name>"
        + "<description xml:lang=\"en\">Stock Prd</description>"
        + "<xml-data/>"
        + "<provision-request formSrc=\"1\" version=\"3.6.1\">"
        + "<form-binding form-id=\"Stock Form\"><content>"
        + "<field data-type=\"string\" name=\"title\"><control control-type=\"title\"/></field>"
        + "<field data-type=\"string\" name=\"reason\"><control control-type=\"textfield\"/></field>"
        + "<field data-type=\"button\" name=\"submit\"><control control-type=\"button\"/></field>"
        + "</content></form-binding>"
        + "<request-data-items>"
        + "<data-item data-type=\"string\" name=\"reason\" target=\"flowdata.Start/Stock Form/reason\" target-type=\"single-value\"/>"
        + "</request-data-items></provision-request>"
        + "<process formSrc=\"1\" id=\"cn=Stock Prd,cn=RequestDefs,cn=AppConfig,cn=UA,cn=driverset1,o=system\" version=\"4.5.0\">"
        + "<start-activity activity-id=\"Start\"><display-name xml:lang=\"en\">Start</display-name></start-activity>"
        + "<finish-activity activity-id=\"Finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"Start\" target=\"Finish\" type=\"forward\"/>"
        + "</process></prov-req-defn>";

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String cobject(String name, String type, String attrsXml, String relationsXml) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<com.novell.designer.model:CObject xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
            + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\" name=\"" + name + "\" type=\"" + type + "\">"
            + attrsXml + relationsXml
            + "</com.novell.designer.model:CObject>";
    }

    private static String cstring(String attrName, String value) {
        return "<attributes xsi:type=\"com.novell.designer.model:CString\" attrName=\"" + attrName + "\" value=\"" + value + "\"/>";
    }

    private static String heavy(String attrName) {
        return "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"" + attrName + "\"/>";
    }

    private static String rel(String name, String type, String key) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + key + "\"/>";
    }

    private static String containerDigest(String cn, String type, String guid, String display, String version) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<container cn=\"cn=" + cn + "\" protected=\"false\" readonly=\"false\" type=\"" + type + "\""
            + (version == null ? "" : " version=\"" + version + "\"") + " visible=\"true\">"
            + "<guid>" + guid + "</guid><display xml:lang=\"en\">" + display + "</display><modstamp>0</modstamp></container>";
    }

    /** A Designer project with one driver, one packaged policy, one entitlement, and an AppConfig. */
    private Path buildSourceProject() throws IOException {
        Path root = tmp.newFolder("source" + (seq++)).toPath();
        write(root.resolve("Model/DS1.DriverSet_"),
            cobject("driverset1", "DriverSet", cstring("DSetContext", "o=system"),
                rel("Idm:Drivers", "Child", "DRV1.Driver_") + rel("Idm:Libraries", "Child", "LIB1.Library_")));
        write(root.resolve("Model/DS1/LIB1.Library_"),
            cobject("Library", "Library", cstring("LibraryContext", "cn=driverset1,o=system"),
                rel("Idm:Resources", "Child", "ECMA1.ECMAScriptResource_")));
        write(root.resolve("Model/DS1/ECMA1.ECMAScriptResource_"),
            cobject("SharedFunctions", "ECMAScriptResource",
                heavy("contents") + cstring("DirXML-ContentType", "application/ecmascript"), ""));
        write(root.resolve("Model/DS1/ECMA1_contents.xml"), ECMA_TEXT);

        write(root.resolve("Model/DS1/DRV1.Driver_"),
            cobject("UA", "NProv Driver 4.8.0",
                cstring("DirXML-JavaModule", COMPOSER_SHIM) + cstring("DirXML-ShimAuthID", "cn=uaadmin,ou=sa,o=data"),
                rel("Idm:Subscriber", "Child", "SUB1.Subscriber_")
                    + rel("Idm:Filter", "Child", "FLT1.Filter_")
                    + rel("Idm:Entitlements", "Child", "ENT1.Entitlement_")
                    + rel("Idm:ExtensionFunctions", "Reference", "0.ECMAScriptResource_")));
        write(root.resolve("Model/DS1/DRV1/FLT1.Filter_"), cobject("UA Filter", "Filter", heavy("contents"), ""));
        write(root.resolve("Model/DS1/DRV1/FLT1_contents.xml"), FILTER_XML);
        write(root.resolve("Model/DS1/DRV1/SUB1.Subscriber_"),
            cobject("Subscriber", "Subscriber", "",
                rel("Idm:Policies", "Child", "POL1.ScriptPolicy_") + rel("Idm:EventPolicies", "Reference", "POL1.ScriptPolicy_")));
        write(root.resolve("Model/DS1/DRV1/SUB1/POL1.ScriptPolicy_"),
            cobject("NOVLUABASE-sub-etp-Packaged", "ScriptPolicy",
                heavy("contents") + heavy("initial_state")
                    + cstring("Idm:PackageAssocGuid", PKG_ASSOC_ID)
                    + cstring("Idm:PackageGuid", PACKAGE_ID)
                    + "<attributes xsi:type=\"com.novell.designer.model:CLong\" attrName=\"Idm:ContentChecksum\" value=\"1988768136\"/>",
                ""));
        write(root.resolve("Model/DS1/DRV1/SUB1/POL1_contents.xml"), PACKAGED_POLICY_XML);
        write(root.resolve("Model/DS1/DRV1/SUB1/POL1_initial_state.xml"), PACKAGED_POLICY_XML);
        write(root.resolve("Model/DS1/DRV1/ENT1.Entitlement_"),
            cobject("Group", "Entitlement",
                heavy("contents") + cstring("Idm:PackageGuid", PACKAGE_ID), ""));
        write(root.resolve("Model/DS1/DRV1/ENT1_contents.xml"), ENTITLEMENT_XML);
        // Designer's placeholder for a reference the project doesn't hold (test11pf's 0./1.)
        write(root.resolve("Model/0.ECMAScriptResource_"),
            cobject("cn=NOVLLIBAJC-JS,cn=Library,cn=driverset1,o=system", "Ref", "", ""));

        write(root.resolve("Model/APPG1.Application_"),
            cobject("UA App", "NProv", "", rel("Idm:Drivers", "Reference", "DRV1.Driver_")));

        Path appDir = root.resolve("Model/Provisioning/AppConfig");
        write(appDir.resolve("AppConfig.digest"), containerDigest("AppConfig", "srvprvAppConfig", "APPG1", "UA", "4.8"));
        write(appDir.resolve("WorkflowForms/WorkflowForms.digest"),
            containerDigest("WorkflowForms", "srvprvJSONForms", "WFRMS001", "Workflow Forms", null));
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/WorkflowRequestForms.digest"),
            containerDigest("WorkflowRequestForms", "srvprvJSONRequestForms", "WFREQ001", "Request Forms", null));
        write(appDir.resolve("RequestDefs/RequestDefs.digest"),
            containerDigest("RequestDefs", "srvprvRequestDefs", "RQDEF001", "Provisioning Request Definitions", null));
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/Stock Form.formRequest"), STOCK_FORM_JSON);
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/Stock Form.digest"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<item cn=\"cn=Stock Form\" filename=\"Stock Form.formRequest\" "
                + "hasContainment=\"false\" modstamp=\"0\" protected=\"true\" readonly=\"true\" "
                + "type=\"srvprvJSONRequestForm\" visible=\"true\"><guid>STOCKFRM</guid>"
                + "<package-id>" + PACKAGE_ID + "</package-id></item>");
        write(appDir.resolve("RequestDefs/Stock Prd.prd"), STOCK_PRD_XML);
        write(appDir.resolve("RequestDefs/Stock Prd.digest"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<item cn=\"cn=Stock Prd\" filename=\"Stock Prd.prd\" "
                + "hasContainment=\"false\" modstamp=\"0\" protected=\"false\" readonly=\"false\" "
                + "type=\"srvprvRequest\" visible=\"true\"><guid>STOCKPRD</guid>"
                + "<display xml:lang=\"en\">Stock Prd</display><descr xml:lang=\"en\">Stock Prd</descr>"
                + "<keys><key>systemTemplates</key></keys></item>");
        return root;
    }

    /** The source project as a tree — the "from" side of every {@code --new} run below. */
    private Path syntheticTree() throws IOException {
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(ProjectReader.read(buildSourceProject()), tree);
        return tree;
    }

    // ---- comparison ------------------------------------------------------------------

    /** Every as-code file of a model, minus reader bookkeeping meta (which carries minted ids). */
    private static String asCode(DriverSet ds) throws IOException {
        Path dir = Files.createTempDirectory("idm-new-ascode");
        AsCodeWriter.write(ds, dir);
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                sb.append("== ").append(dir.relativize(p)).append('\n');
                for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n")) {
                    if (line.contains("<meta key=\"")) {
                        continue;
                    }
                    sb.append(line).append('\n');
                }
            }
        }
        // stripping the meta lines can leave "<form …>\n</form>" where the other side wrote
        // "<form …/>" — collapse every now-empty element so only real differences remain
        return sb.toString().replaceAll("(<([a-zA-Z-]+) [^>]*)>\\n\\s*</\\2>", "$1/>");
    }

    private static Path findFile(Path root, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no file ending in " + suffix + " under " + root));
        }
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ================================================================================
    // (a) the round trip
    // ================================================================================

    @Test
    public void newProjectRoundTripsTheWholeTree() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("ClientProject");

        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), false);
        assertNull(r.text(), r.refusal);
        assertTrue(r.text(), r.ok);
        assertTrue(r.deletedFiles.isEmpty());

        assertEquals(asCode(AsCodeReader.read(tree)), asCode(ProjectReader.read(project)));

        // and the parts the model comparison can't see:
        DriverSet reread = ProjectReader.read(project);
        Driver ua = reread.driver("UA");
        assertNotNull(ua);
        assertEquals(COMPOSER_SHIM, ua.shimClass);
        Provisioning prov = ua.provisioning;
        assertNotNull("the new project's AppConfig must tie back to its driver", prov);
        assertNotNull(prov.form(Form.Kind.REQUEST, "Stock Form"));
        Prd prd = prov.prd("Stock Prd");
        assertNotNull(prd);
        assertEquals("Stock Form", prd.bindings().get(0).formId);
        assertEquals(1, ua.entitlements.size());
        assertEquals("Group", ua.entitlements.get(0).name);
    }

    @Test
    public void packagedItemsCarryTheirPackageAttributesAndABaseline() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("Packaged");
        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), false);
        assertNull(r.text(), r.refusal);

        Path policyFile = findFile(project, ".ScriptPolicy_");
        String policyId = policyFile.getFileName().toString().replace(".ScriptPolicy_", "");
        String policyMeta = read(policyFile);
        assertTrue(policyMeta, policyMeta.contains("attrName=\"Idm:PackageGuid\" value=\"" + PACKAGE_ID + "\""));
        assertTrue(policyMeta, policyMeta.contains("attrName=\"Idm:PackageAssocGuid\" value=\"" + PKG_ASSOC_ID + "\""));
        assertTrue(policyMeta, policyMeta.contains("attrName=\"Idm:ContentChecksum\" value=\"1988768136\""));
        assertTrue(policyMeta, policyMeta.contains("attrName=\"initial_state\""));
        // not customized in the tree, so the baseline is the item's own content, byte for byte
        String contents = read(policyFile.resolveSibling(policyId + "_contents.xml"));
        assertTrue(contents, contents.contains("<description>packaged</description>"));
        assertEquals(contents, read(policyFile.resolveSibling(policyId + "_initial_state.xml")));

        // the entitlement is packaged too, and gets the same treatment
        String entitlementMeta = read(findFile(project, ".Entitlement_"));
        assertTrue(entitlementMeta, entitlementMeta.contains("attrName=\"Idm:PackageGuid\""));

        // N3's hand-off: the result names the packages, and writes no IdmPackage_ objects
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains(PACKAGE_ID) && n.contains("N3")));
        assertTrue(r.createdFiles.toString(), r.createdFiles.stream().noneMatch(f -> f.endsWith(".IdmPackage_")));

        // a protected, packaged form keeps its protection (and its package id) in the new digest
        String formDigest = read(project.resolve(
            "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/Stock Form.digest"));
        assertTrue(formDigest, formDigest.contains("protected=\"true\""));
        assertTrue(formDigest, formDigest.contains("readonly=\"true\""));
        assertTrue(formDigest, formDigest.contains("<package-id>" + PACKAGE_ID + "</package-id>"));
    }

    @Test
    public void everyDriverGetsAnApplicationAndAModelerNode() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("Modeler");
        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), false);
        assertNull(r.text(), r.refusal);

        Path appFile = findFile(project, ".Application_");
        String app = read(appFile);
        assertTrue(app, app.contains("name=\"UA\""));
        assertTrue("the User Application driver's app is typed NProv: " + app, app.contains("type=\"NProv\""));
        String appId = appFile.getFileName().toString().replace(".Application_", "");

        String driver = read(findFile(project, ".Driver_"));
        assertTrue(driver, driver.contains("attrName=\"IdmParameter:AppIDCreatedDuringImport\" value=\"" + appId + "\""));
        assertTrue(driver, driver.contains("key=\"#" + appId + ".Application_\" name=\"Idm:Application\" type=\"BackReference\""));
        assertTrue("the driver keeps the type the tree recorded: " + driver, driver.contains("type=\"NProv Driver 4.8.0\""));

        String domain = read(findFile(project, ".Domain_"));
        assertTrue(domain, domain.contains("key=\"#" + appId + ".Application_\""));

        String nodes = read(findFile(project, ".ModelerNodes_"));
        assertTrue(nodes, nodes.contains("objectURI=\"#" + appId + ".Application_\""));
        assertTrue(nodes, nodes.contains(".IdentityVault_\""));
        assertTrue(nodes, nodes.contains("<sourcePorts name=\"source-center\"/>"));
        assertTrue(nodes, nodes.contains("<targetPorts name=\"target-center\"/>"));
        assertTrue("the diagram points back at the project file: " + nodes,
            nodes.contains("<project href=\"../../Modeler.proj#/\"/>"));

        // .provisioning ties the AppConfig folder to that same Application_
        String provisioning = read(project.resolve("Model/Provisioning/.provisioning"));
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n    <application folder=\"AppConfig\" guid=\""
            + appId + "\"/></root>\n", provisioning);
        assertTrue(Files.exists(project.resolve("Model/Provisioning/AppConfig/.appconfig")));
        String appConfig = read(project.resolve("Model/Provisioning/AppConfig/.appconfig"));
        assertTrue(appConfig, appConfig.contains("ds-object-class=\"srvprvAppConfig\""));
        assertTrue("the version comes from the tree's AppConfig digest: " + appConfig.substring(0, 300),
            appConfig.contains("<![CDATA[4.8]]>"));
        for (String container : List.of("RequestDefs", "WorkFlowDefs", "ResourceDefs", "ServiceDefs", "DirectoryModel",
            "AppDefs", "TeamDefs", "RoleConfig", "AuthTypes", "UIConfig")) {
            assertTrue(container + " missing from .appconfig", appConfig.contains("ds-object-name=\"" + container + "\""));
        }
        for (String digest : List.of("AppConfig/AppConfig.digest",
            "AppConfig/WorkflowForms/WorkflowForms.digest",
            "AppConfig/WorkflowForms/WorkflowRequestForms/WorkflowRequestForms.digest",
            "AppConfig/WorkflowForms/WorkflowApprovalForms/WorkflowApprovalForms.digest",
            "AppConfig/WorkflowForms/WorkflowTemplateForms/WorkflowTemplateForms.digest",
            "AppConfig/RequestDefs/RequestDefs.digest")) {
            assertTrue(digest + " missing", Files.exists(project.resolve("Model/Provisioning").resolve(digest)));
        }
    }

    @Test
    public void vaultCarriesTheGivenDetailsAndNeverAPassword() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("Vaulted");
        ProjectWriter.Result r = ProjectWriter.create(tree, project,
            new NewProject("IDM_TEST_TREE", "vault.example.test", "cn=admin,ou=sa,o=system",
                "idm-engine", "ou=servers,o=system"), false);
        assertNull(r.text(), r.refusal);

        String vault = read(findFile(project, ".IdentityVault_"));
        assertTrue(vault, vault.contains("name=\"IDM_TEST_TREE\""));
        assertTrue(vault, vault.contains("attrName=\"IdentityVaultHost\" value=\"vault.example.test\""));
        assertTrue(vault, vault.contains("attrName=\"IdentityVaultUsername\" value=\"cn=admin,ou=sa,o=system\""));
        assertTrue(vault, vault.contains("attrName=\"IdentityVaultSavePassword\" value=\"false\""));
        assertFalse("a vault password must never be written: " + vault, vault.contains("IdentityVaultPassword"));

        String server = read(findFile(project, ".Server_"));
        assertTrue(server, server.contains("name=\"idm-engine\""));
        assertTrue(server, server.contains("attrName=\"ServerContext\" value=\"ou=servers,o=system\""));
    }

    @Test
    public void withoutFlagsNoHostNoUserNoServer() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("Bare");
        assertNull(ProjectWriter.create(tree, project, NewProject.defaults(), false).refusal);

        String vault = read(findFile(project, ".IdentityVault_"));
        assertFalse(vault, vault.contains("IdentityVaultHost"));
        assertFalse(vault, vault.contains("IdentityVaultUsername"));
        assertFalse(vault, vault.contains("IdentityVaultPassword"));
        assertTrue(vault, vault.contains("attrName=\"IdentityVaultSavePassword\" value=\"false\""));
        try (Stream<Path> s = Files.walk(project)) {
            assertTrue("no Server_ without --server",
                s.noneMatch(p -> p.getFileName().toString().endsWith(".Server_")));
        }
    }

    // ================================================================================
    // (b) the descriptor coupling (spike 6a/6b)
    // ================================================================================

    @Test
    public void allThreeDescriptorsCarryTheFolderName() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("Acme-IDM");
        assertNull(ProjectWriter.create(tree, project, NewProject.defaults(), false).refusal);

        String eclipse = read(project.resolve(".project"));
        assertTrue(eclipse, eclipse.contains("<name>Acme-IDM</name>"));
        assertTrue(eclipse, eclipse.contains("<nature>com.novell.idm.DesignerProjectNature</nature>"));

        String proj = read(project.resolve("Acme-IDM.proj"));
        assertTrue(proj, proj.contains("name=\"Acme-IDM\""));
        assertTrue(proj, proj.contains("cprojectURI=\"Acme-IDM/Acme-IDM.cproj\""));
        assertTrue(proj, proj.contains("<adapterProject href=\"Acme-IDM.cproj#/\"/>"));
        assertTrue(proj, proj.contains("packageLinkagesMigrated=\"true\""));
        assertTrue(proj, proj.contains("productID=\"4.7\""));
        assertTrue(proj, proj.contains("version=\"4.7\""));

        String cproj = read(project.resolve("Acme-IDM.cproj"));
        assertTrue(cproj, cproj.contains("name=\"Acme-IDM\""));
        assertTrue(cproj, cproj.contains("key=\"#IdentityManager.CRoot_\""));
        assertTrue(cproj, cproj.contains("key=\"#Project.CRoot_\""));
        assertTrue(cproj, cproj.contains("key=\"#EdirOrphan.CRoot_\""));

        // the .proj's guid is minted, and its domain/modelerNodes hrefs resolve to real files
        Matcher guid = Pattern.compile("guid=\"([0-9A-Z]{8})\"").matcher(proj);
        assertTrue(proj, guid.find());
        Matcher domain = Pattern.compile("domainURI=\"IdentityManager/([0-9A-Z]{8})\\.domain\"").matcher(proj);
        assertTrue(proj, domain.find());
        assertTrue(Files.exists(project.resolve("Model/IdentityManager/" + domain.group(1) + ".Domain_")));
        Matcher nodes = Pattern.compile("<modelerNodes href=\"([^\"#]+)#/\"/>").matcher(proj);
        assertTrue(proj, nodes.find());
        assertTrue(nodes.group(1), Files.exists(project.resolve(nodes.group(1))));

        // and the package catalog is there with Designer's six stock categories
        String catalog = read(findFile(project, ".IdmCatalog_"));
        assertEquals(6, catalog.split("Idm:Categories", -1).length - 1);
        try (Stream<Path> s = Files.walk(project)) {
            assertEquals(6, s.filter(p -> p.getFileName().toString().endsWith(".IdmCategory_")).count());
        }
    }

    // ================================================================================
    // (c) refusals and dry run
    // ================================================================================

    @Test
    public void refusesANonEmptyDirectory() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("occupied" + (seq++)).toPath();
        write(project.resolve("Model/Something.txt"), "already here");

        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), false);
        assertFalse(r.ok);
        assertNotNull(r.refusal);
        assertTrue(r.refusal, r.refusal.contains("not empty"));
        assertTrue(r.text(), r.text().startsWith("REFUSED"));
        assertEquals("already here", read(project.resolve("Model/Something.txt")));
    }

    @Test
    public void anEmptyDirectoryIsFine() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("empty" + (seq++)).toPath();
        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), false);
        assertNull(r.text(), r.refusal);
        assertTrue(Files.exists(project.resolve(".project")));
    }

    @Test
    public void dryRunWritesNothingButReportsEverything() throws IOException {
        Path tree = syntheticTree();
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("DryRun");
        ProjectWriter.Result r = ProjectWriter.create(tree, project, NewProject.defaults(), true);
        assertTrue(r.text(), r.ok);
        assertFalse(r.createdFiles.isEmpty());
        assertTrue(r.createdFiles.contains(".project"));
        assertTrue(r.createdFiles.contains("DryRun.proj"));
        assertFalse("--dry-run must not create the project directory", Files.exists(project));
    }

    // ================================================================================
    // (d) guarded: the real tree
    // ================================================================================

    @Test
    public void realTreeRoundTripsThroughANewProject() throws IOException {
        Path tree = Paths.get(E2E_TREE);
        Assume.assumeTrue(Files.isDirectory(tree));
        Path project = tmp.newFolder("out" + (seq++)).toPath().resolve("test11new");

        ProjectWriter.Result r = ProjectWriter.create(tree, project,
            new NewProject("IDM_TEST_TREE", null, null, "idm-test", "ou=servers,o=system"), false);
        assertNull(r.text(), r.refusal);
        assertTrue(r.text(), r.ok);

        DriverSet treeDs = AsCodeReader.read(tree);
        DriverSet reread = ProjectReader.read(project);
        assertEquals(treeDs.drivers.size(), reread.drivers.size());
        assertEquals(asCode(treeDs), asCode(reread));

        // every driver has its Application_, and the UA driver's AppConfig came across whole
        try (Stream<Path> s = Files.walk(project)) {
            assertEquals(treeDs.drivers.size(),
                s.filter(p -> p.getFileName().toString().endsWith(".Application_")).count());
        }
        Provisioning treeProv = treeDs.driver("User Application Driver").provisioning;
        Provisioning prov = reread.driver("User Application Driver").provisioning;
        assertNotNull(prov);
        assertEquals(treeProv.forms.size(), prov.forms.size());
        assertEquals(treeProv.prds.size(), prov.prds.size());
        assertTrue("the real tree should carry a dozen forms and dozens of PRDs",
            prov.forms.size() >= 10 && prov.prds.size() >= 30);
    }
}
