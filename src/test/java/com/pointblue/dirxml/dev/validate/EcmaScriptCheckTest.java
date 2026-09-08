package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Test;
import org.w3c.dom.Element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One clean driver set (no findings), then one case per {@link EcmaScriptCheck} code.
 */
public class EcmaScriptCheckTest {

    private static final String RULE_USES_FOO =
        "<policy><rule><description>r1</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-xpath expression=\"es:foo(.)\"/></arg-string>"
            + "</do-set-local-variable></actions></rule></policy>";

    private static final String RULE_USES_BAR =
        "<policy><rule><description>r1</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-xpath expression=\"es:bar(.)\"/></arg-string>"
            + "</do-set-local-variable></actions></rule></policy>";

    private static final String RULE_NO_ES =
        "<policy><rule><description>r1</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-text>1</token-text></arg-string>"
            + "</do-set-local-variable></actions></rule></policy>";

    static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /** A driver with an ECMAScript resource in set 3 defining foo(), and a policy calling es:foo(.). */
    static DriverSet clean() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("AD");
        d.shimClass = "com.example.Shim";
        Resource es = new Resource("Lib", Scope.DRIVER, "AD", Resource.ECMASCRIPT);
        es.text = "function foo(a) { return a; }";
        d.resources.add(es);
        d.subscriber.policies.add(new Policy("sub-ctp", Scope.SUBSCRIBER, "AD", xml(RULE_USES_FOO)));
        d.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "drivers/AD/Lib", 0));
        d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-ctp", 0));
        ds.drivers.add(d);
        return ds;
    }

    private static Report run(DriverSet ds) {
        Report r = new Report();
        new EcmaScriptCheck().run(ds, r);
        return r;
    }

    @Test
    public void cleanDriverSetHasNoFindings() {
        Report r = run(clean());
        assertTrue(r.text(), r.findings().isEmpty());
    }

    @Test
    public void ecmascriptEmpty() {
        DriverSet ds = clean();
        Resource blank = new Resource("Blank", Scope.LIBRARY, null, Resource.ECMASCRIPT);
        blank.text = "   ";
        ds.library.resources.add(blank);
        Report r = run(ds);
        assertEquals(1, r.withCode("ecmascript-empty").size());
        assertEquals("library/Blank", r.withCode("ecmascript-empty").get(0).path);
    }

    @Test
    public void ecmascriptSyntax() {
        DriverSet ds = clean();
        ds.driver("AD").resources.get(0).text = "function foo(a { return a; }";
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("ecmascript-syntax").size());
        Finding f = r.withCode("ecmascript-syntax").get(0);
        assertEquals("drivers/AD/Lib", f.path);
        assertTrue(f.message, f.message.toLowerCase().contains("line"));
    }

    @Test
    public void ecmascriptFunctionUndefined() {
        DriverSet ds = clean();
        ds.driver("AD").subscriber.policies.add(new Policy("sub-ctp2", Scope.SUBSCRIBER, "AD", xml(RULE_USES_BAR)));
        ds.driver("AD").links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-ctp2", 1));
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("ecmascript-function-undefined").size());
        Finding f = r.withCode("ecmascript-function-undefined").get(0);
        assertEquals("drivers/AD/subscriber/sub-ctp2", f.path);
        assertTrue(f.message, f.message.contains("es:bar"));
        assertTrue(f.message, f.message.contains("foo"));
    }

    @Test
    public void ecmascriptFunctionUndefinedNoResourcesLinked() {
        DriverSet ds = new DriverSet("dvs");
        Driver d = new Driver("AD");
        d.shimClass = "com.example.Shim";
        d.subscriber.policies.add(new Policy("sub-ctp", Scope.SUBSCRIBER, "AD", xml(RULE_USES_FOO)));
        d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-ctp", 0));
        ds.drivers.add(d);
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("ecmascript-function-undefined").size());
        assertTrue(r.withCode("ecmascript-function-undefined").get(0).message.contains("no ECMAScript resources are linked"));
    }

    @Test
    public void ecmascriptFunctionUndefinedLibraryPolicyAttributesDriver() {
        DriverSet ds = clean();
        Policy libPolicy = new Policy("lib-ctp", Scope.LIBRARY, null, xml(RULE_USES_BAR));
        ds.library.policies.add(libPolicy);
        ds.driver("AD").links.add(new PolicyLink(PolicySet.SUB_COMMAND, "library/lib-ctp", 1));
        Report r = run(ds);
        assertEquals(r.text(), 1, r.withCode("ecmascript-function-undefined").size());
        Finding f = r.withCode("ecmascript-function-undefined").get(0);
        assertEquals("library/lib-ctp", f.path);
        assertTrue(f.message, f.message.contains("as loaded by driver 'AD'"));
    }

    @Test
    public void ecmascriptUnlinked() {
        DriverSet ds = clean();
        Resource orphan = new Resource("Orphan", Scope.DRIVER, "AD", Resource.ECMASCRIPT);
        orphan.text = "function unused() {}";
        ds.driver("AD").resources.add(orphan);
        Report r = run(ds);
        assertEquals(1, r.withCode("ecmascript-unlinked").size());
        assertEquals("drivers/AD/Orphan", r.withCode("ecmascript-unlinked").get(0).path);
    }

    @Test
    public void libraryEcmaScriptResourceNeverReportedUnlinked() {
        DriverSet ds = clean();
        Resource libEs = new Resource("LibEs", Scope.LIBRARY, null, Resource.ECMASCRIPT);
        libEs.text = "function unused() {}";
        ds.library.resources.add(libEs);
        Report r = run(ds);
        assertTrue(r.withCode("ecmascript-unlinked").isEmpty());
    }

    @Test
    public void definesFunctionViaVarAssignment() {
        DriverSet ds = clean();
        ds.driver("AD").resources.get(0).text = "var foo = function(a) { return a; };";
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("ecmascript-function-undefined").isEmpty());
    }

    @Test
    public void ruleWithNoEsCallsIsClean() {
        DriverSet ds = clean();
        ds.driver("AD").subscriber.policies.clear();
        ds.driver("AD").subscriber.policies.add(new Policy("sub-ctp", Scope.SUBSCRIBER, "AD", xml(RULE_NO_ES)));
        Report r = run(ds);
        assertTrue(r.text(), r.withCode("ecmascript-function-undefined").isEmpty());
    }
}
