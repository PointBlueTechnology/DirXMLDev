package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The as-code tree layout for provisioning (docs/forms.md §3): forms under
 * {@code provisioning/forms/<kind>}, one directory per PRD, a
 * {@code provisioning.xml} manifest, and — per {@link AsCodeWriter}'s
 * {@code writeProvisioning} javadoc — {@code process.xml} written only when a
 * PRD's process isn't already the node kept inside its {@code definition.xml}.
 */
public class ProvisioningAsCodeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    private static final String REQ_FORM_JSON = "{\"title\":\"Req\",\"components\":[{\"key\":\"a\",\"type\":\"textfield\"}]}";
    private static final String APPR_FORM_JSON = "{\"title\":\"Appr\",\"components\":[]}";

    /** Two drivers: "UA" has provisioning (two PRDs — one with an embedded process,
     *  one with a standalone process element); "NoProv" has none. */
    private static DriverSet sample() {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";

        Driver ua = new Driver("UA");
        ua.dn = "cn=UA,cn=driverset1,o=system";
        Provisioning p = new Provisioning();
        p.dn = "cn=AppConfig," + ua.dn;
        p.meta.put("provisioning.other-objects", "3");

        Form req = new Form(Form.Kind.REQUEST, "Req Form", REQ_FORM_JSON);
        req.meta.put("dn", "cn=Req Form,...");
        p.forms.add(req);
        Form appr = new Form(Form.Kind.APPROVAL, "Appr Form", APPR_FORM_JSON);
        p.forms.add(appr);

        Prd embedded = new Prd("PrdWithEmbeddedProcess");
        embedded.definition = xml("<prov-req-defn status=\"Active\"><process id=\"p1\"><activities/></process></prov-req-defn>");
        embedded.process = com.pointblue.dirxml.sim.Xds.childrenByName(embedded.definition, "process").get(0);
        embedded.request = xml("<provision-request formSrc=\"1\"><form-binding form-id=\"Req Form\"><content/></form-binding></provision-request>");
        embedded.properties.put("status", java.util.List.of("Active"));
        embedded.properties.put("category-key", java.util.List.of("accounts", "roles"));
        p.prds.add(embedded);

        Prd separate = new Prd("PrdWithSeparateProcess");
        separate.definition = xml("<prov-req-defn status=\"Active\"/>");
        separate.process = xml("<process id=\"p2\"><activities/></process>");
        p.prds.add(separate);

        ua.provisioning = p;
        ds.drivers.add(ua);

        Driver noProv = new Driver("NoProv");
        noProv.dn = "cn=NoProv,cn=driverset1,o=system";
        ds.drivers.add(noProv);

        return ds;
    }

    @Test
    public void layoutMatchesSpec() throws Exception {
        Path a = tmp.newFolder("a").toPath();
        AsCodeWriter.write(sample(), a);

        Path prov = a.resolve("drivers/UA/provisioning");
        assertTrue(Files.exists(prov.resolve("provisioning.xml")));
        assertTrue(Files.exists(prov.resolve("forms/request/Req Form.form.json")));
        assertTrue(Files.exists(prov.resolve("forms/approval/Appr Form.form.json")));
        assertTrue(Files.exists(prov.resolve("prds/PrdWithEmbeddedProcess/definition.xml")));
        assertTrue(Files.exists(prov.resolve("prds/PrdWithEmbeddedProcess/request.xml")));
        assertFalse("process embedded in definition -> no separate process.xml",
            Files.exists(prov.resolve("prds/PrdWithEmbeddedProcess/process.xml")));
        assertTrue(Files.exists(prov.resolve("prds/PrdWithSeparateProcess/definition.xml")));
        assertFalse(Files.exists(prov.resolve("prds/PrdWithSeparateProcess/request.xml")));
        assertTrue("standalone process -> its own file",
            Files.exists(prov.resolve("prds/PrdWithSeparateProcess/process.xml")));

        String manifest = Files.readString(prov.resolve("provisioning.xml"));
        assertTrue(manifest.contains("<provisioning dn=\"cn=AppConfig,cn=UA,cn=driverset1,o=system\">"));
        assertTrue(manifest.contains("kind=\"request\""));
        assertTrue(manifest.contains("kind=\"approval\""));
        assertTrue(manifest.contains("<property key=\"category-key\">accounts</property>"));
        assertTrue(manifest.contains("<property key=\"category-key\">roles</property>"));

        // form files are pretty-printed for humans/git, not the compact wire form
        String formFile = Files.readString(prov.resolve("forms/request/Req Form.form.json"));
        assertTrue(formFile.contains("\n"));
        assertTrue(formFile.contains("  \""));
    }

    @Test
    public void driverWithoutProvisioningWritesNoProvisioningDir() throws Exception {
        Path a = tmp.newFolder("a").toPath();
        AsCodeWriter.write(sample(), a);
        assertFalse(Files.exists(a.resolve("drivers/NoProv/provisioning")));
    }

    @Test
    public void writeIsIdempotentAndReadPreservesEverything() throws Exception {
        DriverSet ds = sample();
        Path a = tmp.newFolder("a").toPath();
        Path b = tmp.newFolder("b").toPath();

        AsCodeWriter.write(ds, a);
        DriverSet back = AsCodeReader.read(a);
        AsCodeWriter.write(back, b);

        assertEquals(AsCodeRoundTripTest.snapshot(a), AsCodeRoundTripTest.snapshot(b));

        Driver ua = back.driver("UA");
        assertNotNull(ua.provisioning);
        assertEquals(2, ua.provisioning.forms.size());
        assertEquals(2, ua.provisioning.prds.size());
        assertEquals("3", ua.provisioning.meta.get("provisioning.other-objects"));
        assertNull(back.driver("NoProv").provisioning);

        Prd embedded = ua.provisioning.prd("PrdWithEmbeddedProcess");
        assertNotNull(embedded.process);
        boolean sameNode = false;
        org.w3c.dom.NodeList kids = embedded.definition.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) == embedded.process) {
                sameNode = true;
            }
        }
        assertTrue("process re-derived as the definition's own child on read-back", sameNode);
        assertEquals(java.util.List.of("accounts", "roles"), embedded.properties.get("category-key"));

        Prd separate = ua.provisioning.prd("PrdWithSeparateProcess");
        assertNotNull(separate.process);
        assertEquals("process", separate.process.getTagName());
        assertNull(separate.request);

        // form JSON: semantically equal (parsed trees), even though bytes differ (compact
        // source vs. the tree's pretty-printed copy)
        Form reqBack = ua.provisioning.form(Form.Kind.REQUEST, "Req Form");
        assertEquals(Json.parse(REQ_FORM_JSON), Json.parse(reqBack.json));
        Form apprBack = ua.provisioning.form(Form.Kind.APPROVAL, "Appr Form");
        assertEquals(Json.parse(APPR_FORM_JSON), Json.parse(apprBack.json));
    }

    @Test
    public void rewritingAnUnchangedTreeChangesNoFile() throws Exception {
        Path a = tmp.newFolder("a").toPath();
        DriverSet ds = sample();
        AsCodeWriter.write(ds, a);
        Map<String, String> before = AsCodeRoundTripTest.snapshot(a);

        DriverSet reread = AsCodeReader.read(a);
        AsCodeWriter.write(reread, a);
        Map<String, String> after = AsCodeRoundTripTest.snapshot(a);

        assertEquals(before, after);
    }
}
