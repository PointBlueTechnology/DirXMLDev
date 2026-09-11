package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.forms.FormEditor;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.dev.source.ProjectReader;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Guarded tests against real Designer/vault data (skipped, not failed, when the input isn't
 * present on this machine) for Track P step P2b: the {@code test11} Designer workspace and the
 * test vault's User Application driver as two LDIF dumps (IDM 4.8.7 and 4.10.1) — see
 * {@code com.pointblue.dirxml.dev.source.ProvisioningGuardedTest} for the read-only P1 coverage
 * of the same three sources.
 */
public class FormOpsGuardedTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Path TEST11 = Path.of(System.getProperty("user.home"), "designer_workspace", "test11");
    private static final Path UA_LDIF = Path.of(System.getProperty("user.home"), "IdeaProjects", "DirXMLDev-e2e", "ua-driver.ldif");
    private static final Path UA_LDIF_IDM254 = Path.of(System.getProperty("user.home"), "IdeaProjects", "DirXMLDev-e2e", "ua-driver-idm254.ldif");

    private static void assertNoFormCheckErrors(DriverSet ds, String label) {
        Report r = Validator.standard().validate(ds);
        List<Finding> bad = r.of(Finding.Severity.ERROR).stream()
            .filter(f -> f.code.startsWith("form-") || f.code.startsWith("prd-"))
            .toList();
        assertTrue(label + ": unexpected FormCheck error(s):\n" + bad.stream().map(Finding::toString).reduce("", (a, b) -> a + "\n" + b),
            bad.isEmpty());
    }

    @Test
    public void test11ValidatesWithZeroFormCheckErrors() {
        assumeTrue("needs " + TEST11, Files.isDirectory(TEST11));
        assertNoFormCheckErrors(ProjectReader.read(TEST11), "test11");
    }

    @Test
    public void uaDriverLdif487ValidatesWithZeroFormCheckErrors() throws Exception {
        assumeTrue("needs " + UA_LDIF, Files.exists(UA_LDIF));
        assertNoFormCheckErrors(LdifReader.read(UA_LDIF), "ua-driver.ldif (4.8.7)");
    }

    @Test
    public void uaDriverLdifIdm254ValidatesWithZeroFormCheckErrors() throws Exception {
        assumeTrue("needs " + UA_LDIF_IDM254, Files.exists(UA_LDIF_IDM254));
        assertNoFormCheckErrors(LdifReader.read(UA_LDIF_IDM254), "ua-driver-idm254.ldif (4.10.1)");
    }

    /**
     * {@code form.field.add} + {@code prd.map} on a copy of the real "Help-desk Request Form",
     * bound by the real "HelpdeskTicket" PRD: {@code prd.show} reflects the new mapping, and the
     * new field's JSON round-trips through {@link Json#compact} unchanged.
     */
    @Test
    public void fieldAddAndPrdMapOnTheRealHelpDeskFormAndTicket() throws Exception {
        assumeTrue("needs " + UA_LDIF, Files.exists(UA_LDIF));
        Path tree = tmp.newFolder("real-ua").toPath();
        AsCodeWriter.write(LdifReader.read(UA_LDIF), tree);

        Result added = Transaction.open(tree).run(
            new FormOps.FieldAdd(null, "request/Help-desk Request Form", "agentAddedField", "textfield", "Agent Added Field",
                false, false, false, false, null, null, false, null, null),
            false, false);
        assertTrue(added.text(), added.ok());

        Result mapped = Transaction.open(tree).run(
            new FormOps.PrdMap(null, "HelpdeskTicket", "agentAddedField", null, null, null, false),
            false, false);
        assertTrue(mapped.text(), mapped.ok());

        // prd.show reflects the new mapping
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            int rc = ReadCli.prdShow(new String[]{"prd.show", tree.toString(), "HelpdeskTicket"});
            assertEquals(0, rc);
        } finally {
            System.setOut(old);
        }
        String shown = out.toString(StandardCharsets.UTF_8);
        assertTrue(shown, shown.contains("agentAddedField"));

        // the new field's JSON round-trips through Json.compact unchanged
        DriverSet again = AsCodeReader.read(tree);
        Form form = again.drivers.get(0).provisioning.formByName("Help-desk Request Form");
        Map<String, Object> root = Json.asMap(Json.parse(form.json));
        FormEditor.Located loc = FormEditor.find(root, "agentAddedField");
        assertTrue("new field not found in the stored form", loc != null);
        String compact = Json.compact(loc.component);
        assertEquals(loc.component, Json.asMap(Json.parse(compact)));
        assertEquals(compact, Json.compact(Json.parse(compact)));
    }
}
