package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Designer project -&gt; {@link Provisioning}. A project ties an AppConfig folder
 * to its driver indirectly (see {@link ProjectReader}'s {@code attachProvisioning}
 * javadoc): the digest's {@code <guid>} is also the id of an {@code .Application_}
 * object elsewhere in the project, and that object's {@code Idm:Drivers} reference
 * names the real driver.
 */
public class ProvisioningProjectReaderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String COMPOSER_SHIM = "com.novell.idm.driver.ComposerDriverShim";
    private static final String FORM_JSON = "{\"title\":\"My Form\",\"components\":[]}";
    private static final String PRD_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<prov-req-defn status=\"Active\" flow-strategy=\"SingleFlow\" grant=\"true\" revoke=\"false\" "
        + "prov-category=\"accounts\" prov-id=\"MyPrd\">"
        + "<display-name xml:lang=\"en\">My PRD</display-name>"
        + "<description xml:lang=\"en\">A test PRD</description>"
        + "<provision-request formSrc=\"1\" version=\"3.6.1\">"
        + "<form-binding form-id=\"My Form\"><content>"
        + "<field data-type=\"string\" name=\"a\"><control control-type=\"textfield\"/></field>"
        + "</content></form-binding></provision-request>"
        + "<process id=\"p1\" process-type=\"Normal\"><activities/></process>"
        + "</prov-req-defn>";

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

    private static String rel(String name, String type, String key) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + key + "\"/>";
    }

    /** Lays out the driver/driver-set objects, an AppConfig with a form + PRD, and (if {@code tieAppConfig})
     *  the {@code .Application_} object that ties the AppConfig folder to the driver. */
    private Path buildProject(String driverName, boolean tieAppConfig, String appConfigDirName) throws IOException {
        Path root = tmp.newFolder(driverName.replace(' ', '_') + "_" + appConfigDirName).toPath();
        write(root.resolve("Model/DS1.DriverSet_"),
            cobject("driverset1", "DriverSet", cstring("DSetContext", "o=system"), rel("Idm:Drivers", "Reference", "DRV1.Driver_")));
        write(root.resolve("Model/DRV1.Driver_"),
            cobject(driverName, "NProv Driver 4.8.0", cstring("DirXML-JavaModule", COMPOSER_SHIM), ""));
        if (tieAppConfig) {
            write(root.resolve("Model/APPG1.Application_"),
                cobject(driverName + " App", "NProv", "", rel("Idm:Drivers", "Reference", "DRV1.Driver_")));
        }

        Path appDir = root.resolve("Model/Provisioning/" + appConfigDirName);
        write(appDir.resolve(appConfigDirName + ".digest"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<container cn=\"cn=AppConfig\" protected=\"false\" readonly=\"false\" type=\"srvprvAppConfig\" version=\"4.8\" visible=\"true\">"
            + "<guid>APPG1</guid><display xml:lang=\"en\">" + driverName + "</display><modstamp>0</modstamp></container>");

        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/My Form.formRequest"), FORM_JSON);
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/My Form.digest"),
            "<item cn=\"cn=My Form\" filename=\"My Form.formRequest\" hasContainment=\"false\" modstamp=\"0\" "
            + "protected=\"false\" readonly=\"false\" type=\"srvprvJSONRequestForm\" visible=\"true\"><guid>FORMG1</guid></item>");

        write(appDir.resolve("RequestDefs/MyPrd.prd"), PRD_XML);
        write(appDir.resolve("RequestDefs/MyPrd.digest"),
            "<item cn=\"cn=MyPrd\" filename=\"MyPrd.prd\" hasContainment=\"false\" modstamp=\"0\" "
            + "protected=\"true\" readonly=\"true\" type=\"srvprvRequest\" visible=\"true\"><guid>PRDG1</guid>"
            + "<package-id>PKG1</package-id></item>");
        return root;
    }

    @Test
    public void appConfigResolvesToItsDriverViaApplicationGuid() throws Exception {
        Path root = buildProject("UA", true, "AppConfig");
        DriverSet ds = ProjectReader.read(root);
        Driver ua = ds.driver("UA");
        assertNotNull(ua);
        assertNotNull(ua.provisioning);
        assertEquals("cn=AppConfig," + ua.dn, ua.provisioning.dn);

        Provisioning p = ua.provisioning;
        assertEquals(1, p.forms.size());
        Form f = p.form(Form.Kind.REQUEST, "My Form");
        assertNotNull(f);
        assertEquals(FORM_JSON, f.json);
        assertEquals("FORMG1", f.meta.get("project.guid"));

        assertEquals(1, p.prds.size());
        Prd prd = p.prd("MyPrd");
        assertNotNull(prd);
        assertTrue(prd.isJsonForms());
        assertEquals("Active", prd.property("status"));
        assertEquals("SingleFlow", prd.property("flow-strategy"));
        assertEquals("TRUE", prd.property("grant")); // the vault's spelling; the .prd itself says true
        assertEquals("FALSE", prd.property("revoke"));
        assertEquals("accounts", prd.property("category-key"));
        assertEquals("en~My PRD", prd.property("localized-names"));
        assertEquals("en~A test PRD", prd.property("localized-descrs"));
        assertEquals("A test PRD", prd.property("description"));
        assertEquals("Normal", prd.property("process-type"));
        assertEquals("PKG1", prd.meta.get("project.package-id"));
        assertEquals("true", prd.meta.get("project.protected"));
        assertEquals(1, prd.bindings().size());
        assertEquals("My Form", prd.bindings().get(0).formId);
    }

    @Test
    public void fallsBackToSoleComposerDriverWhenGuidDoesNotResolve() throws Exception {
        Path root = buildProject("UA", false, "AppConfig");
        DriverSet ds = ProjectReader.read(root);
        assertNotNull(ds.driver("UA").provisioning);
        assertEquals(1, ds.driver("UA").provisioning.forms.size());
    }

    @Test
    public void unresolvableAppConfigIsNotedInMetaNotCrashed() throws Exception {
        // two AppConfig-shaped folders under one project, neither tied by guid, and no
        // sole Composer driver to fall back to unambiguously -> both must be reported,
        // never silently attached to the wrong driver (mirrors ~/designer_workspace/testc7).
        Path root = tmp.newFolder("ambiguous").toPath();
        write(root.resolve("Model/DS1.DriverSet_"),
            cobject("driverset1", "DriverSet", cstring("DSetContext", "o=system"),
                rel("Idm:Drivers", "Reference", "DRV1.Driver_") + rel("Idm:Drivers", "Reference", "DRV2.Driver_")));
        write(root.resolve("Model/DRV1.Driver_"),
            cobject("UA", "NProv Driver 4.8.0", cstring("DirXML-JavaModule", COMPOSER_SHIM), ""));
        write(root.resolve("Model/DRV2.Driver_"),
            cobject("UA2", "NProv Driver 4.8.0", cstring("DirXML-JavaModule", COMPOSER_SHIM), ""));

        Path appDir = root.resolve("Model/Provisioning/AppConfig1");
        write(appDir.resolve("AppConfig1.digest"),
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<container cn=\"cn=AppConfig\" protected=\"false\" readonly=\"false\" type=\"srvprvAppConfig\" version=\"4.8\" visible=\"true\">"
            + "<guid>NOMATCH</guid><display xml:lang=\"en\">Orphan</display><modstamp>0</modstamp></container>");

        DriverSet ds = ProjectReader.read(root);
        assertNull(ds.driver("UA").provisioning);
        assertNull(ds.driver("UA2").provisioning);
        assertTrue(ds.meta.containsKey("provisioning.unresolved.AppConfig1"));
    }
}
