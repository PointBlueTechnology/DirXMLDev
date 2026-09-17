package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.FormDocument;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Guarded tests against real Designer/vault data (skipped, not failed, when the
 * input isn't present on this machine): the {@code test11} Designer workspace,
 * the test vault's User Application driver as an LDIF subtree dump (two IDM
 * versions), and the whole-tree import -&gt; as-code round trip.
 */
public class ProvisioningGuardedTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final Path TEST11 = Path.of(System.getProperty("user.home"), "designer_workspace", "test11");
    private static final Path UA_LDIF = Path.of(System.getProperty("user.home"),
        "IdeaProjects", "DirXMLDev-e2e", "ua-driver.ldif");
    private static final Path UA_LDIF_IDM254 = Path.of(System.getProperty("user.home"),
        "IdeaProjects", "DirXMLDev-e2e", "ua-driver-idm254.ldif");

    private static final String DRIVER_NAME = "User Application Driver";

    // ---- test11 (Designer project) --------------------------------------------------

    @Test
    public void test11HasElevenFormsAndThirtyNinePrds() {
        assumeTrue("needs " + TEST11, Files.isDirectory(TEST11));
        Provisioning p = provisioningOf(ProjectReader.read(TEST11));
        assertEquals(11, p.forms.size());
        assertEquals(39, p.prds.size());

        int jsonForms = 0;
        for (Prd prd : p.prds) {
            if (prd.isJsonForms()) {
                jsonForms++;
            }
        }
        assertEquals(17, jsonForms);

        Prd ticket = p.prd("HelpdeskTicket");
        assertNotNull(ticket);
        List<Prd.FormBinding> bindings = ticket.bindings();
        assertEquals(2, bindings.size());
        assertEquals("Help-desk Request Form", bindings.get(0).formId);
        assertEquals(null, bindings.get(0).activityId);
        assertEquals("Help-desk Approval Form", bindings.get(1).formId);
        assertEquals("Activity", bindings.get(1).activityId);
    }

    // ---- ua-driver.ldif (test vault, IDM 4.8.7) --------------------------------------

    @Test
    public void uaDriverLdifHasElevenFormsAndThirtyNinePrds() throws IOException {
        assumeTrue("needs " + UA_LDIF, Files.exists(UA_LDIF));
        assertEquals(272, countLdifEntries(UA_LDIF));

        Provisioning p = provisioningOf(LdifReader.read(UA_LDIF));
        assertEquals(11, p.forms.size());
        assertEquals(39, p.prds.size());
        assertNotNull(p.prd("HelpdeskTicket").bindings());
    }

    // ---- ua-driver-idm254.ldif (a second vault, IDM 4.10.1; minimal stock forms) -----

    @Test
    public void uaDriverIdm254LdifHasElevenFormsAndThirtyNinePrds() throws IOException {
        assumeTrue("needs " + UA_LDIF_IDM254, Files.exists(UA_LDIF_IDM254));
        assertEquals(279, countLdifEntries(UA_LDIF_IDM254));

        DriverSet ds = LdifReader.read(UA_LDIF_IDM254);
        Provisioning p = provisioningOf(ds);
        assertEquals(11, p.forms.size());
        assertEquals(39, p.prds.size());

        // stock forms here are minimal documents (no builder default keys) — the
        // reader/FormDocument must not assume any key beyond what's actually there.
        Form f = p.formByName("Help-desk Request Form");
        assertNotNull(f);
        FormDocument doc = f.document();
        assertEquals("HelpDesk Ticket", doc.title());
        assertTrue(doc.components().size() > 0);

        Prd ticket = p.prd("HelpdeskTicket");
        assertEquals(2, ticket.bindings().size());
    }

    // ---- cross-source: project vs. vault agree on the same objects ------------------

    @Test
    public void helpDeskRequestFormIsSemanticallyEqualAcrossSources() {
        assumeTrue("needs " + TEST11 + " and " + UA_LDIF, Files.isDirectory(TEST11) && Files.exists(UA_LDIF));
        Form fromProject = provisioningOf(ProjectReader.read(TEST11)).formByName("Help-desk Request Form");
        Form fromLdif = provisioningOf(LdifReader.read(UA_LDIF)).formByName("Help-desk Request Form");
        assertNotNull(fromProject);
        assertNotNull(fromLdif);
        // the project's .formRequest file is documented as byte-identical to the
        // vault's srvprvJSONData; confirmed here rather than assumed.
        assertEquals("project .formRequest file vs. vault srvprvJSONData", fromProject.json, fromLdif.json);
        assertEquals(Json.parse(fromProject.json), Json.parse(fromLdif.json));
    }

    /**
     * The PRD's three parts, cross-checked between the project and the vault after
     * {@link CanonicalXml} serialization. {@code definition} and {@code process} are
     * asserted equal (verified so on the test vault). {@code request} is reported,
     * not asserted: the project's {@code .prd} wraps its data items in a
     * {@code <request-data-items>} element that the vault's {@code srvprvRequestXML}
     * does not — a real structural difference between the two formats, not just
     * whitespace or a trailing comment, and out of scope to reconcile in this step
     * (see the final report, finding (d)/(e)).
     */
    @Test
    public void helpdeskTicketPrdPartsAgreeOrDifferenceIsReported() {
        assumeTrue("needs " + TEST11 + " and " + UA_LDIF, Files.isDirectory(TEST11) && Files.exists(UA_LDIF));
        Prd fromProject = provisioningOf(ProjectReader.read(TEST11)).prd("HelpdeskTicket");
        Prd fromLdif = provisioningOf(LdifReader.read(UA_LDIF)).prd("HelpdeskTicket");

        String defP = CanonicalXml.serialize(fromProject.definition);
        String defL = CanonicalXml.serialize(fromLdif.definition);
        assertEquals("definition differs between project and vault", defP, defL);

        String procP = CanonicalXml.serialize(fromProject.process);
        String procL = CanonicalXml.serialize(fromLdif.process);
        assertEquals("process differs between project and vault", procP, procL);

        String reqP = CanonicalXml.serialize(fromProject.request);
        String reqL = CanonicalXml.serialize(fromLdif.request);
        if (!reqP.equals(reqL)) {
            System.out.println("NOTE: HelpdeskTicket <provision-request> differs between project and vault "
                + "(project.length=" + reqP.length() + ", vault.length=" + reqL.length()
                + ") — the project wraps data items in <request-data-items>, the vault does not; see report.");
        } else {
            System.out.println("HelpdeskTicket <provision-request> is byte-identical (after canonicalization) across sources.");
        }
    }

    // ---- whole tree: import -> as-code -> read -> write again is byte-identical -----

    @Test
    public void wholeTreeImportProjectRoundTripsByteIdentical() throws Exception {
        assumeTrue("needs " + TEST11, Files.isDirectory(TEST11));
        Path a = tmp.newFolder("project-a").toPath();
        Path b = tmp.newFolder("project-b").toPath();
        AsCodeWriter.write(ProjectReader.read(TEST11), a);
        AsCodeWriter.write(AsCodeReader.read(a), b);
        assertEquals(snapshot(a), snapshot(b));
    }

    @Test
    public void wholeTreeImportLdifRoundTripsByteIdentical() throws Exception {
        assumeTrue("needs " + UA_LDIF, Files.exists(UA_LDIF));
        Path a = tmp.newFolder("ldif-a").toPath();
        Path b = tmp.newFolder("ldif-b").toPath();
        AsCodeWriter.write(LdifReader.read(UA_LDIF), a);
        AsCodeWriter.write(AsCodeReader.read(a), b);
        assertEquals(snapshot(a), snapshot(b));
    }

    // ---- helpers ----------------------------------------------------------------------

    private static Provisioning provisioningOf(DriverSet ds) {
        Driver d = ds.driver(DRIVER_NAME);
        assertNotNull("no driver '" + DRIVER_NAME + "' in " + ds, d);
        assertNotNull("driver has no provisioning", d.provisioning);
        return d.provisioning;
    }

    private static int countLdifEntries(Path ldif) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(ldif, java.nio.charset.StandardCharsets.UTF_8)) {
            if (line.startsWith("dn: ")) {
                n++;
            }
        }
        return n;
    }

    /** relative path -> file bytes, binary-safe (a tree can hold a driver icon). */
    private static Map<String, String> snapshot(Path root) throws IOException {
        return com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.snapshot(root);
    }
}
