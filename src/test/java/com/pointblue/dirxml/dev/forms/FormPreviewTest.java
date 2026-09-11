package com.pointblue.dirxml.dev.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.edit.FormOpsTest;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.Form;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FormPreviewTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void pageIsSelfContainedAndCarriesTheDocument() throws Exception {
        Driver d = new Driver("UA");
        Form f = new Form(Form.Kind.REQUEST, "My <Form>", BindingSyncTest.FORM_V2);
        String html = FormPreview.html(d, f, "en");
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("My &lt;Form&gt;"));                       // escaped title
        assertTrue(html.contains("Formio.createForm"));
        assertTrue(html.contains("stubOf(TextFieldComponent, 'dn_display'"));   // our stubs
        assertTrue(html.contains("\"key\":\"justification\""));               // the document inline
        assertTrue(html.contains("not the vendor renderer"));
        assertTrue(html.contains("<code>groups</code>"));
        assertFalse("no external script tags", html.contains("<script src="));
        assertFalse("no external stylesheets", html.substring(0, html.indexOf("<body")).contains("<link "));
        assertTrue(html.length() > 1_000_000);                              // the vendored renderer is inline
        // the vendored bundle must not close our inline script early
        String js = FormPreview.resource("formio.full.min.js");
        assertFalse(js.contains("</script>"));
    }

    @Test
    public void cliWritesThePage() throws Exception {
        Path tree = FormOpsTest.tree(tmp, false);
        Path out = tmp.getRoot().toPath().resolve("req.html");
        int rc = FormPreview.run(new String[] {"form.preview", tree.toString(), "Req", "--out", out.toString()});
        assertEquals(0, rc);
        assertTrue(Files.exists(out));
        assertTrue(Files.readString(out).contains("\"key\":\"recipient\""));
        assertEquals(1, FormPreview.run(new String[] {"form.preview", tree.toString(), "Nope", "--out", out.toString()}));
    }
}
