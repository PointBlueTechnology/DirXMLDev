package com.pointblue.dirxml.dev.validate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Scope;
import java.util.List;
import org.junit.Test;

/** Required DirXML-Script attributes: the ig4 policy Designer dropped, a token-map, a clean policy, a library policy. */
public class ScriptCheckTest {

    private static Report run(String... policies) {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("AD");
        d.shimClass = "com.example.Shim";
        int i = 0;
        for (String p : policies) {
            d.publisher.policies.add(new Policy("p" + (i++), Scope.PUBLISHER, "AD", ValidatorTest.xml(p)));
        }
        ds.drivers.add(d);
        Report r = new Report();
        new ScriptCheck().run(ds, r);
        return r;
    }

    private static final String CLEAN = "<policy><rule><description>set title</description><conditions><and>"
        + "<if-op-attr name=\"Title\" op=\"available\"/></and></conditions><actions>"
        + "<do-set-dest-attr-value name=\"title\"><arg-value type=\"string\"><token-op-attr name=\"Title\"/></arg-value></do-set-dest-attr-value>"
        + "<do-send-email-from-template notification-dn=\"\\\\T\\\\Security\\\\Default Notification Collection\" template-dn=\"\\\\T\\\\Security\\\\Default Notification Collection\\\\Expiry\">"
        + "<arg-string name=\"to\"><token-text>x@example</token-text></arg-string></do-send-email-from-template>"
        + "</actions></rule></policy>";

    @Test
    public void aCleanPolicyHasNoFindings() {
        assertTrue(run(CLEAN).text(), run(CLEAN).findings().isEmpty());
    }

    @Test
    public void theIg4SendEmailWithoutATemplateIsAWarning_namedByItsRule() {
        String bad = "<policy><rule><description>Send expiration email</description><conditions/><actions>"
            + "<do-send-email-from-template notification-dn=\"\\\\T\\\\Security\\\\Default Notification Collection\">"
            + "<arg-string name=\"to\"><token-local-variable name=\"pb-email\"/></arg-string>"
            + "</do-send-email-from-template></actions></rule></policy>";
        Report r = run(bad);
        List<Finding> f = r.withCode(ScriptCheck.CODE);
        assertEquals(r.text(), 1, f.size());
        assertEquals(Finding.Severity.WARNING, f.get(0).severity);
        assertEquals("drivers/AD/publisher/p0", f.get(0).path);
        assertTrue(f.get(0).message, f.get(0).message.contains("<do-send-email-from-template> lacks its required attribute 'template-dn' in rule 'Send expiration email'"));
    }

    @Test
    public void everyMissingAttributeIsItsOwnFinding_andNonScriptPoliciesAreSkipped() {
        String map = "<policy><rule><description>map</description><conditions/><actions><do-set-local-variable name=\"x\">"
            + "<arg-string><token-map table=\"..\\\\Titles\"><token-text>a</token-text></token-map></arg-string>"
            + "</do-set-local-variable></actions></rule></policy>";
        Report r = run(map, "<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name></class-name></attr-name-map>");
        List<Finding> f = r.withCode(ScriptCheck.CODE);
        assertEquals(r.text(), 2, f.size());
        assertTrue(f.get(0).message, f.get(0).message.contains("'src'"));
        assertTrue(f.get(1).message, f.get(1).message.contains("'dest'"));
    }

    @Test
    public void libraryPoliciesAreCheckedToo() {
        DriverSet ds = new DriverSet("dvs");
        ds.library.policies.add(new Policy("lib", Scope.LIBRARY, null,
            ValidatorTest.xml("<policy><rule><conditions/><actions><do-status><arg-string><token-text>x</token-text></arg-string></do-status></actions></rule></policy>")));
        Report r = new Report();
        new ScriptCheck().run(ds, r);
        assertEquals(r.text(), 1, r.withCode(ScriptCheck.CODE).size());
        assertTrue(r.text(), r.withCode(ScriptCheck.CODE).get(0).message.contains("<do-status> lacks its required attribute 'level'"));
    }
}
