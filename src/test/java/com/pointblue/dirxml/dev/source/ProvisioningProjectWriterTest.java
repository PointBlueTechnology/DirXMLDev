package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.FormOps;
import com.pointblue.dirxml.dev.edit.Result;
import com.pointblue.dirxml.dev.edit.Transaction;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ProjectWriter} carrying a driver's provisioning objects (JSON forms + PRDs)
 * into an existing Designer project (Track P step 6). Two fixtures:
 *
 * <ul>
 *   <li>a hand-built synthetic project skeleton (this class), laid out the way
 *       Designer lays one out (AppConfig/container digests, one stock form, one
 *       bound template PRD) — exercises form add/remove and PRD add against a
 *       minimal project without any real Designer install; and</li>
 *   <li>a copy of {@code ~/designer_workspace/test11} (guarded: skipped, not
 *       failed, when absent), exercising the exact typed ops an agent would run
 *       ({@code form.add}, {@code prd.add}) against the real 11-form/39-PRD
 *       project and asserting exactly which files the writer touched.</li>
 * </ul>
 */
public class ProvisioningProjectWriterTest {

    private static final String TEST11 = "/Users/jcombs/designer_workspace/test11";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private int seq;

    // ================================================================================
    // synthetic project skeleton
    // ================================================================================

    private static final String COMPOSER_SHIM = "com.novell.idm.driver.ComposerDriverShim";

    private static final String STOCK_FORM_JSON =
        "{\"components\":["
        + "{\"key\":\"title\",\"type\":\"title\",\"input\":false},"
        + "{\"key\":\"reason\",\"type\":\"textfield\",\"input\":true},"
        + "{\"key\":\"submit\",\"type\":\"button\",\"input\":true}"
        + "],\"title\":\"Stock Form\",\"display\":\"form\",\"inlinescripts\":\"\",\"localization\":{},\"externalScripts\":[]}";

    private static final String EXTRA_FORM_JSON =
        "{\"components\":[{\"key\":\"title\",\"type\":\"title\",\"input\":false}],\"title\":\"Extra Form\","
        + "\"display\":\"form\",\"inlinescripts\":\"\",\"localization\":{},\"externalScripts\":[]}";

    private static final String STOCK_PRD_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<prov-req-defn status=\"Template\" flow-strategy=\"SingleFlow\" grant=\"true\" revoke=\"false\" "
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
        + "<user-activity activity-id=\"Activity\" approver-type=\"group-approver\">"
        + "<display-name xml:lang=\"en\">Activity</display-name><addressee>recipient</addressee></user-activity>"
        + "<finish-activity activity-id=\"Finish\"><display-name xml:lang=\"en\">Finish</display-name></finish-activity>"
        + "<link source=\"Start\" target=\"Activity\" type=\"forward\"/>"
        + "<link source=\"Activity\" target=\"Finish\" type=\"approved\"/>"
        + "<link source=\"Activity\" target=\"Finish\" type=\"denied\"/>"
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

    private static String rel(String name, String type, String key) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + key + "\"/>";
    }

    private static String containerDigest(String cn, String type, String guid, String display) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<container cn=\"cn=" + cn + "\" protected=\"false\" readonly=\"false\" type=\"" + type + "\" visible=\"true\">"
            + "<guid>" + guid + "</guid><display xml:lang=\"en\">" + display + "</display><modstamp>0</modstamp></container>";
    }

    private static String formItemDigest(String cn, String filename, String type, String guid) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<item cn=\"cn=" + cn + "\" filename=\"" + filename + "\" hasContainment=\"false\" modstamp=\"0\" "
            + "protected=\"false\" readonly=\"false\" type=\"" + type + "\" visible=\"true\"><guid>" + guid + "</guid></item>";
    }

    private static String prdItemDigest(String cn, String guid) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<item cn=\"cn=" + cn + "\" filename=\"" + cn + ".prd\" hasContainment=\"false\" modstamp=\"0\" "
            + "protected=\"false\" readonly=\"false\" type=\"srvprvRequest\" visible=\"true\"><guid>" + guid + "</guid>"
            + "<display xml:lang=\"en\">" + cn + "</display><descr xml:lang=\"en\">" + cn + "</descr>"
            + "<keys><key>systemTemplates</key></keys>"
            + "<digest-dependency digest-managed=\"false\" force-deploy=\"true\" new-digest-dependency=\"false\" "
            + "object-cn=\"WorkflowForms/Stock Form\" object-mapkey=\"WorkflowForms/WorkflowRequestForms+Stock Form\" "
            + "object-type=\"srvprvJSONRequestForm\" type=\"srvprvJSONRequestForm\"/></item>";
    }

    /** A minimal project skeleton laid out like Designer's: one driver, one AppConfig, one stock form/PRD pair
     *  bound together, and one unbound extra form (so a plain {@code form.delete} has something to remove). */
    private Path buildSkeleton() throws IOException {
        return buildSkeleton(COMPOSER_SHIM);
    }

    /** {@code shimClass} != Composer defeats {@code ProjectReader}'s sole-Composer-driver fallback, so a test
     *  can observe what happens when the AppConfig-&gt;driver tie genuinely cannot be resolved. */
    private Path buildSkeleton(String shimClass) throws IOException {
        Path root = tmp.newFolder("skeleton" + (seq++)).toPath();
        write(root.resolve("Model/DS1.DriverSet_"),
            cobject("driverset1", "DriverSet", cstring("DSetContext", "o=system"), rel("Idm:Drivers", "Reference", "DRV1.Driver_")));
        write(root.resolve("Model/DRV1.Driver_"),
            cobject("UA", "NProv Driver 4.8.0", cstring("DirXML-JavaModule", shimClass), ""));
        write(root.resolve("Model/APPG1.Application_"),
            cobject("UA App", "NProv", "", rel("Idm:Drivers", "Reference", "DRV1.Driver_")));

        Path appDir = root.resolve("Model/Provisioning/AppConfig");
        write(appDir.resolve("AppConfig.digest"), containerDigest("AppConfig", "srvprvAppConfig", "APPG1", "UA"));
        write(appDir.resolve("WorkflowForms/WorkflowForms.digest"),
            containerDigest("WorkflowForms", "srvprvJSONForms", "WFRMS001", "Workflow Forms"));
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/WorkflowRequestForms.digest"),
            containerDigest("WorkflowRequestForms", "srvprvJSONRequestForms", "WFREQ001", "Request Forms"));
        write(appDir.resolve("RequestDefs/RequestDefs.digest"),
            containerDigest("RequestDefs", "srvprvRequestDefs", "RQDEF001", "Provisioning Request Definitions"));

        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/Stock Form.formRequest"), STOCK_FORM_JSON);
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/Stock Form.digest"),
            formItemDigest("Stock Form", "Stock Form.formRequest", "srvprvJSONRequestForm", "STOCKFRM"));
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/Extra Form.formRequest"), EXTRA_FORM_JSON);
        write(appDir.resolve("WorkflowForms/WorkflowRequestForms/Extra Form.digest"),
            formItemDigest("Extra Form", "Extra Form.formRequest", "srvprvJSONRequestForm", "EXTRAFRM"));

        write(appDir.resolve("RequestDefs/Stock Prd.prd"), STOCK_PRD_XML);
        write(appDir.resolve("RequestDefs/Stock Prd.digest"), prdItemDigest("Stock Prd", "STOCKPRD"));
        return root;
    }

    private static Map<String, String> hashAll(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                out.put(root.relativize(p).toString().replace('\\', '/'), sha256(Files.readAllBytes(p)));
            }
        }
        return out;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertOnlyChanged(Map<String, String> before, Map<String, String> after,
                                           Set<String> allowedChangedOrNew, Set<String> allowedDeleted) {
        Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        for (String k : keys) {
            boolean inBefore = before.containsKey(k);
            boolean inAfter = after.containsKey(k);
            if (inBefore && inAfter) {
                if (!before.get(k).equals(after.get(k))) {
                    assertTrue("unexpected change to untouched file: " + k, allowedChangedOrNew.contains(k));
                }
            } else if (inBefore) {
                assertTrue("unexpected deletion: " + k, allowedDeleted.contains(k));
            } else {
                assertTrue("unexpected creation: " + k, allowedChangedOrNew.contains(k));
            }
        }
    }

    private static void run(Path tree, com.pointblue.dirxml.dev.edit.Operation op) throws IOException {
        Result r = Transaction.open(tree).run(op, false, false);
        assertTrue(r.text(), r.ok() && r.written);
    }

    @Test
    public void addFormAddPrdRemoveUnboundForm() throws IOException {
        Path project = buildSkeleton();
        DriverSet initial = ProjectReader.read(project);
        assertNotNull(initial.driver("UA").provisioning);
        assertEquals(2, initial.driver("UA").provisioning.forms.size());
        assertEquals(1, initial.driver("UA").provisioning.prds.size());

        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(initial, tree);

        run(tree, new FormOps.Add("UA", "request", "New Form", "Stock Form", null));
        run(tree, new FormOps.Delete("UA", "Extra Form"));
        run(tree, new FormOps.PrdAdd("UA", "New Prd", "Stock Prd", "New Form", null, null, null, false));

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertNull(r.refusal);

        Path reqFormsDir = project.resolve("Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms");
        Path reqDefsDir = project.resolve("Model/Provisioning/AppConfig/RequestDefs");
        String newForm = "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/New Form.formRequest";
        String newFormDigest = "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/New Form.digest";
        String newPrd = "Model/Provisioning/AppConfig/RequestDefs/New Prd.prd";
        String newPrdDigest = "Model/Provisioning/AppConfig/RequestDefs/New Prd.digest";
        String extraForm = "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/Extra Form.formRequest";
        String extraFormDigest = "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/Extra Form.digest";

        assertEquals(new HashSet<>(List.of(newForm, newFormDigest, newPrd, newPrdDigest)), new HashSet<>(r.createdFiles));
        assertEquals(new HashSet<>(List.of(extraForm, extraFormDigest)), new HashSet<>(r.deletedFiles));
        assertTrue(r.changedFiles.toString(), r.changedFiles.isEmpty());

        Set<String> allowedNew = new HashSet<>(r.createdFiles);
        Set<String> allowedDeleted = new HashSet<>(r.deletedFiles);
        assertOnlyChanged(before, hashAll(project), allowedNew, allowedDeleted);

        // the stock form + PRD (untouched) are byte-identical
        assertEquals(STOCK_FORM_JSON, Files.readString(reqFormsDir.resolve("Stock Form.formRequest")));
        assertEquals(STOCK_PRD_XML, Files.readString(reqDefsDir.resolve("Stock Prd.prd")));

        // new form file: compact JSON, byte-identical to what the vendor builder would produce
        String newFormBytes = Files.readString(reqFormsDir.resolve("New Form.formRequest"));
        assertFalse(newFormBytes, newFormBytes.contains("\n"));
        Map<String, Object> expectedForm = Json.asMap(Json.parse(STOCK_FORM_JSON));
        expectedForm.put("title", "New Form");
        assertEquals(expectedForm, Json.parse(newFormBytes));

        // new form digest: minted guid, no dirguid/dirrev/package elements
        String digestXml = Files.readString(reqFormsDir.resolve("New Form.digest"));
        assertTrue(digestXml, digestXml.contains("cn=\"cn=New Form\""));
        assertTrue(digestXml, digestXml.contains("type=\"srvprvJSONRequestForm\""));
        assertFalse(digestXml, digestXml.contains("dirguid"));
        assertFalse(digestXml, digestXml.contains("package-id"));

        // new PRD digest: display/descr/keys copied from properties, one digest-dependency on "New Form"
        String prdDigestXml = Files.readString(reqDefsDir.resolve("New Prd.digest"));
        assertTrue(prdDigestXml, prdDigestXml.contains("<key>systemTemplates</key>"));
        assertTrue(prdDigestXml, prdDigestXml.contains("object-cn=\"WorkflowForms/New Form\""));
        assertTrue(prdDigestXml, prdDigestXml.contains("object-mapkey=\"WorkflowForms/WorkflowRequestForms+New Form\""));
        assertTrue(prdDigestXml, prdDigestXml.contains("object-type=\"srvprvJSONRequestForm\""));

        // new PRD document: provision-request re-inserted right before <process>
        String newPrdXml = Files.readString(reqDefsDir.resolve("New Prd.prd"));
        assertTrue(newPrdXml, newPrdXml.indexOf("</provision-request>") < newPrdXml.indexOf("<process"));
        assertTrue(newPrdXml, newPrdXml.contains("form-id=\"New Form\""));

        // reader parity: re-reading the project now matches the tree
        DriverSet reread = ProjectReader.read(project);
        Provisioning rereadProv = reread.driver("UA").provisioning;
        assertEquals(2, rereadProv.forms.size());
        assertNotNull(rereadProv.form(Form.Kind.REQUEST, "New Form"));
        assertNull(rereadProv.form(Form.Kind.REQUEST, "Extra Form"));
        assertEquals(2, rereadProv.prds.size());
        Prd rereadNewPrd = rereadProv.prd("New Prd");
        assertNotNull(rereadNewPrd);
        assertEquals("New Form", rereadNewPrd.bindings().get(0).formId);
        assertEquals("Active", rereadNewPrd.property("status"));

        DriverSet treeAfter = AsCodeReader.read(tree);
        Provisioning treeProv = treeAfter.driver("UA").provisioning;
        assertEquals(treeProv.forms.size(), rereadProv.forms.size());
        for (Form f : treeProv.forms) {
            Form rf = rereadProv.form(f.kind, f.name);
            assertNotNull("missing form " + f.name, rf);
            assertEquals(Json.parse(f.json), Json.parse(rf.json));
        }
        assertEquals(treeProv.prds.size(), rereadProv.prds.size());
        for (Prd p : treeProv.prds) {
            Prd rp = rereadProv.prd(p.name);
            assertNotNull("missing prd " + p.name, rp);
            assertEquals(canon(p.definition), canon(rp.definition));
            assertEquals(canon(p.request), canon(rp.request));
            assertEquals(canon(p.process), canon(rp.process));
        }
    }

    private static String canon(org.w3c.dom.Element e) {
        return e == null ? null : CanonicalXml.serialize(e);
    }

    @Test
    public void noChangeTouchesNothing() throws IOException {
        Path project = buildSkeleton();
        DriverSet initial = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(initial, tree);

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.changedFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());
        assertEquals(before, hashAll(project));
    }

    @Test
    public void dryRunWritesNothing() throws IOException {
        Path project = buildSkeleton();
        DriverSet initial = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(initial, tree);
        run(tree, new FormOps.Add("UA", "request", "New Form", "Stock Form", null));

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, true);
        assertTrue(r.text(), r.ok);
        assertFalse(r.createdFiles.isEmpty());
        assertEquals(before, hashAll(project));
    }

    @Test
    public void missingAppConfigRefusesJustThatDriversProvisioning() throws IOException {
        // a non-Composer shim defeats ProjectReader's sole-Composer-driver fallback, so once the
        // Application_ tie is gone the AppConfig genuinely cannot be resolved to "UA" any more
        // (matching testc7's real "provisioning.unresolved.*" case) — the driver's provisioning must
        // then be refused on its own, without failing the rest of the update.
        Path project = buildSkeleton("com.example.idm.SomeOtherShim");
        DriverSet initial = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(initial, tree);

        Files.delete(project.resolve("Model/APPG1.Application_"));
        assertNull(ProjectReader.read(project).driver("UA").provisioning);
        run(tree, new FormOps.Add("UA", "request", "New Form", "Stock Form", null));

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);   // not a whole-update refusal
        assertTrue(r.notes.toString(), r.notes.stream().anyMatch(n -> n.contains("UA") && n.contains("AppConfig")));
        assertTrue(r.createdFiles.toString(), r.createdFiles.isEmpty());
    }

    // ================================================================================
    // guarded: a copy of the real test11 project
    // ================================================================================

    private Path copyProject() throws IOException {
        return copyDirectory(Paths.get(TEST11), tmp.newFolder("project" + (seq++)).toPath());
    }

    private static Path copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        return dst;
    }

    private Path buildTree(Path project) throws IOException {
        DriverSet ds = ProjectReader.read(project);
        Path tree = tmp.newFolder("tree" + (seq++)).toPath();
        AsCodeWriter.write(ds, tree);
        return tree;
    }

    @Test
    public void test11NoChangeTouchesNothing() throws IOException {
        Assume.assumeTrue(Files.isDirectory(Paths.get(TEST11)));
        Path project = copyProject();
        Path tree = buildTree(project);
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertTrue(r.createdFiles.isEmpty());
        assertTrue(r.changedFiles.isEmpty());
        assertTrue(r.deletedFiles.isEmpty());
        assertEquals(before, hashAll(project));
    }

    @Test
    public void test11AddFormAndPrdTouchesOnlyFourFiles() throws IOException {
        Assume.assumeTrue(Files.isDirectory(Paths.get(TEST11)));
        Path project = copyProject();
        Path tree = buildTree(project);

        run(tree, new FormOps.Add("User Application Driver", "request", "DirXMLDev Writer Test", "Request Form", null));
        // --force: NoApproval (like every stock template) still carries the unresolved
        // "{enter Entitlement DN here}" placeholder FlowCheck now flags on an Active PRD
        // (docs/workflows.md &sect;2 — filling it in is Track W's W2, not built yet).
        Result forced = Transaction.open(tree).run(
            new FormOps.PrdAdd("User Application Driver", "DirXMLDev Writer PRD", "NoApproval", "DirXMLDev Writer Test", null, null, null, false),
            false, true);
        assertTrue(forced.text(), forced.ok() && forced.written);

        Map<String, String> before = hashAll(project);
        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertNull(r.refusal);

        String newForm = "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/DirXMLDev Writer Test.formRequest";
        String newFormDigest = "Model/Provisioning/AppConfig/WorkflowForms/WorkflowRequestForms/DirXMLDev Writer Test.digest";
        String newPrd = "Model/Provisioning/AppConfig/RequestDefs/DirXMLDev Writer PRD.prd";
        String newPrdDigest = "Model/Provisioning/AppConfig/RequestDefs/DirXMLDev Writer PRD.digest";

        assertEquals(new HashSet<>(List.of(newForm, newFormDigest, newPrd, newPrdDigest)), new HashSet<>(r.createdFiles));
        assertTrue(r.changedFiles.toString(), r.changedFiles.isEmpty());
        assertTrue(r.deletedFiles.toString(), r.deletedFiles.isEmpty());

        Set<String> allowed = new HashSet<>(r.createdFiles);
        assertOnlyChanged(before, hashAll(project), allowed, Set.of());

        DriverSet reread = ProjectReader.read(project);
        Provisioning prov = reread.driver("User Application Driver").provisioning;
        assertEquals(12, prov.forms.size());
        assertEquals(40, prov.prds.size());
        Prd created = prov.prd("DirXMLDev Writer PRD");
        assertNotNull(created);
        assertEquals("DirXMLDev Writer Test", created.bindings().get(0).formId);
    }

    @Test
    public void test11ImportThenNoOpUpdateTouchesNothing() throws IOException {
        Assume.assumeTrue(Files.isDirectory(Paths.get(TEST11)));
        Path project = copyProject();
        Path tree = buildTree(project);
        Map<String, String> before = hashAll(project);

        ProjectWriter.Result r = ProjectWriter.update(tree, project, false);
        assertTrue(r.text(), r.ok);
        assertEquals(before, hashAll(project));
    }
}
