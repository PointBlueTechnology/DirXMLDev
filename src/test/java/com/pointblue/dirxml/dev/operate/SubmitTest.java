package com.pointblue.dirxml.dev.operate;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SubmitTest {

    @Test
    public void extractsTheDocumentSubmittedToTheShim() {
        List<String> trace = List.of(
            "[09/09/26 08:59:28.400]:QT ST:Applying policy: sub-ctp.",
            "[09/09/26 08:59:28.430]:QT ST:Submitting document to subscriber shim:",
            "[09/09/26 08:59:28.430]:QT ST:",
            "<nds dtdversion=\"4.0\">",
            "  <input>",
            "    <modify class-name=\"User\" event-id=\"e1\"/>",
            "  </input>",
            "</nds>",
            "[09/09/26 08:59:28.431]:QT ST:QTestSubscriptionShim: execute",
            "<other/>");
        String doc = Submit.shimReceived(trace);
        assertTrue(doc, doc.startsWith("<nds dtdversion=\"4.0\">") && doc.endsWith("</nds>"));
        assertTrue(doc.contains("event-id=\"e1\""));
        assertNull(Submit.shimReceived(List.of("[09/09/26 08:59:28.431]:QT ST:nothing here")));
    }

    @Test
    public void simulatorRunsTheCommandFromATree() throws IOException {
        Path tree = Files.createTempDirectory("idm-submit-tree");
        DriverSet ds = ValidatorTest.clean();
        AsCodeWriter.write(ds, tree);
        String xds = "<nds dtdversion=\"4.0\"><input><modify class-name=\"User\" src-dn=\"\\\\dvs\\\\u\" event-id=\"e1\">"
            + "<association>a</association><modify-attr attr-name=\"Surname\"><add-value><value>S</value></add-value></modify-attr>"
            + "</modify></input></nds>";
        String out = Submit.simulate(tree, "AD", xds);
        assertTrue(out, out.contains("<modify") && out.contains("Surname"));
        try {
            Submit.simulate(tree, "Nope", xds);
        } catch (IOException e) {
            assertEquals("the tree has no driver 'Nope'", e.getMessage());
        }
    }
}
