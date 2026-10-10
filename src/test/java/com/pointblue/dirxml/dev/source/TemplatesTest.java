package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.deploy.Plan;
import com.pointblue.dirxml.dev.deploy.Secrets;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.NotificationTemplate;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Validator;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;

/** Notification templates (notfMergeTemplate) through the LDIF reader, the tree, the diff and the plan (docs/console-gaps.md §12). */
public class TemplatesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String COLL = NotificationTemplate.DEFAULT_COLLECTION_DN;
    private static final String BODY = "<html xmlns:form=\"http://www.novell.com/dirxml/workflow/form\"><form:token-descriptions><form:token-description description=\"User full name\" item-name=\"UserFullName\"/></form:token-descriptions><body>Dear $UserFullName$</body></html>";

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", List.of("Top", oc));
        for (int i = 0; i < kv.length; i += 2) {
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    private static List<Entry> entries() {
        return List.of(
            entry(DS, "DirXML-DriverSet"),
            entry("cn=AD," + DS, "DirXML-Driver", "DirXML-JavaModule", "x.Shim"),
            entry("cn=Forgot Password," + COLL, "notfMergeTemplate", "notfMergeTemplateSubject", "Your password request", "notfMergeTemplateData", BODY, "DirXML-pkgGUID", "{G1}"),
            entry("cn=Custom Welcome," + COLL, "notfMergeTemplate", "notfMergeTemplateSubject", "Welcome", "notfMergeTemplateData", BODY));
    }

    @Test
    public void templatesReadFromEntriesRoundTripTheTree() throws Exception {
        DriverSet ds = LdifReader.fromEntries(entries(), "test", Map.of());
        assertEquals(2, ds.templates.size());
        NotificationTemplate t = ds.template("forgot password");
        assertEquals("Your password request", t.subject);
        assertEquals("{G1}", t.meta.get("dirxml-pkgguid"));
        assertEquals(COLL, ds.templatesCollectionDn());
        Path tree = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, tree);
        assertTrue(Files.exists(tree.resolve("templates/Forgot Password.xml")));
        assertTrue(Files.exists(tree.resolve("templates/Custom Welcome.xml")));
        DriverSet back = AsCodeReader.read(tree);
        assertEquals(2, back.templates.size());
        assertEquals("Your password request", back.template("Forgot Password").subject);
        assertEquals(CanonicalXml.serialize(t.data), CanonicalXml.serialize(back.template("Forgot Password").data));
        assertEquals("{G1}", back.template("Forgot Password").meta.get("dirxml-pkgguid"));
        assertEquals(0, ModelDiff.of(ds, back).changes().size());
        assertTrue(Validator.standard().validate(back).findings().stream().noneMatch(f -> f.code.startsWith("template-")));
    }

    @Test
    public void diffAndPlanAddChangeAndGuardTheRemoval() throws Exception {
        DriverSet from = LdifReader.fromEntries(entries(), "test", Map.of());
        DriverSet to = LdifReader.fromEntries(entries(), "test", Map.of());
        to.template("Custom Welcome").subject = "Welcome aboard";
        to.templates.removeIf(x -> x.name.equals("Forgot Password"));
        NotificationTemplate added = new NotificationTemplate("Scratch", CanonicalXml.parse(BODY).getDocumentElement());
        added.subject = "Scratch";
        to.templates.add(added);
        ModelDiff diff = ModelDiff.of(from, to);
        List<ModelDiff.Kind> kinds = diff.changes().stream().map(c -> c.kind).toList();
        assertTrue(kinds.toString(), kinds.contains(ModelDiff.Kind.TEMPLATE_ADDED) && kinds.contains(ModelDiff.Kind.TEMPLATE_CHANGED) && kinds.contains(ModelDiff.Kind.TEMPLATE_REMOVED));
        assertTrue(diff.affectedDrivers().isEmpty());   // no driver restarts for a template
        Plan p = Plan.of(diff, to, DS, Secrets.none(), "none", null, true);
        List<String> ops = new java.util.ArrayList<>();
        for (Plan.Step s : p.steps) {
            ops.add(s.op + " " + s.dn + (s.attr == null ? "" : " " + s.attr));
        }
        assertTrue(ops.toString(), ops.contains("ENSURE_CONTAINER " + COLL));
        assertTrue(ops.toString(), ops.contains("ADD cn=Scratch," + COLL));
        assertTrue(ops.toString(), ops.contains("MODIFY cn=Custom Welcome," + COLL + " notfMergeTemplateSubject"));
        assertTrue(ops.toString(), ops.contains("DELETE cn=Forgot Password," + COLL));   // the tree still has templates: not guarded
        DriverSet none = LdifReader.fromEntries(entries(), "test", Map.of());
        none.templates.clear();
        ModelDiff emptied = ModelDiff.of(from, none);
        Plan guarded = Plan.of(emptied, none, DS, Secrets.none(), "none", null, true);
        assertTrue(guarded.notes.toString(), guarded.notes.stream().anyMatch(n -> n.contains("no templates")));
        assertTrue(guarded.steps.stream().noneMatch(s -> s.op == Plan.Op.DELETE));
    }

    @Test
    public void validateFlagsAMissingBodyAndSubject() throws Exception {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = DS;
        ds.templates.add(new NotificationTemplate("Empty", null));
        List<String> codes = Validator.standard().validate(ds).findings().stream().map(f -> f.code).toList();
        assertTrue(codes.toString(), codes.contains("template-no-data") && codes.contains("template-no-subject"));
    }
}
