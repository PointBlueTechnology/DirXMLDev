package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The checks owned here (links, compile, gcv, mapping-tables) and the
 * Validator's well-formedness pre-pass: one clean driver set that must produce
 * no error, then one case per code. Fixtures are synthetic driver sets built in
 * code; the compile cases go through the engine's real compilers.
 */
public class ValidatorTest {

    private static final String RULE_OK =
        "<policy><rule><description>r1</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-text>1</token-text></arg-string>"
            + "</do-set-local-variable></actions></rule></policy>";

    private static final String SCHEMA_MAP =
        "<attr-name-map><class-name><app-name>user</app-name><nds-name>User</nds-name></class-name></attr-name-map>";

    private static final String FILTER =
        "<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
            + "<filter-attr attr-name=\"Surname\" publisher=\"sync\" subscriber=\"sync\"/></filter-class></filter>";

    private static final String GCVS =
        "<configuration-values><definitions>"
            + "<definition display-name=\"Users\" name=\"drv.users\" type=\"string\"><value>users</value></definition>"
            + "</definitions></configuration-values>";

    private static final String TABLE =
        "<mapping-table><col-def name=\"code\" type=\"nocase\"/><col-def name=\"dn\" type=\"nocase\"/>"
            + "<row><col>1</col><col>ou=a</col></row></mapping-table>";

    static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /** A driver set with one well-formed driver: schema map, filter, GCVs, a table, two linked policies. */
    static DriverSet clean() {
        DriverSet ds = new DriverSet("dvs");
        ds.dn = "cn=dvs,o=system";
        Resource table = new Resource("CodeMap", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        table.content = xml(TABLE);
        ds.library.resources.add(table);

        Driver d = new Driver("AD");
        d.dn = "cn=AD,cn=dvs,o=system";
        d.shimClass = "com.example.Shim";
        d.config.put(Driver.SHIM_CONFIG_INFO, xml("<shim-config-info/>"));
        d.config.put(Driver.DRIVER_FILTER, xml(FILTER));
        d.config.put(Driver.CONFIG_VALUES, xml(GCVS));
        d.policies.add(new Policy("smp", Scope.DRIVER, "AD", xml(SCHEMA_MAP)));
        d.subscriber.policies.add(new Policy("sub-ctp", Scope.SUBSCRIBER, "AD", xml(RULE_OK)));
        d.links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/AD/smp", 0));
        d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-ctp", 0));
        ds.drivers.add(d);
        return ds;
    }

    private static Validator owned() {
        return new Validator(Arrays.asList(new LinkCheck(), new CompileCheck(), new GcvCheck(), new MappingTableCheck()));
    }

    private static Driver ad(DriverSet ds) {
        return ds.driver("AD");
    }

    private static void addSub(DriverSet ds, String name, String policyXml) {
        Driver d = ad(ds);
        d.subscriber.policies.add(new Policy(name, Scope.SUBSCRIBER, "AD", xml(policyXml)));
        d.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/" + name, d.links.size()));
    }

    @Test
    public void cleanDriverSetHasNoErrors() {
        Report r = owned().validate(clean());
        assertTrue(r.text(), r.ok());
        assertEquals(r.text(), 0, r.count(Finding.Severity.WARNING));
    }

    // ---- links ----

    @Test
    public void linkUnresolved() {
        DriverSet ds = clean();
        ad(ds).links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/nope", 0));
        Report r = owned().validate(ds);
        assertEquals(1, r.withCode("link-unresolved").size());
        assertTrue(r.withCode("link-unresolved").get(0).message.contains("library/nope"));
    }

    @Test
    public void linkKind() {
        DriverSet ds = clean();
        // a DirXML Script policy in the ECMAScript set, a resource in a command set
        ad(ds).links.add(new PolicyLink(PolicySet.ECMASCRIPT, "drivers/AD/subscriber/sub-ctp", 0));
        ad(ds).links.add(new PolicyLink(PolicySet.PUB_COMMAND, "library/CodeMap", 0));
        Report r = owned().validate(ds);
        assertEquals(r.text(), 2, r.withCode("link-kind").size());
    }

    @Test
    public void schemaMappingSetAcceptsDirXmlScript() {
        DriverSet ds = clean();
        ad(ds).policies.add(new Policy("smp-skip", Scope.DRIVER, "AD", xml(RULE_OK)));
        ad(ds).links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/AD/smp-skip", 1));
        assertTrue(owned().validate(ds).withCode("link-kind").isEmpty());
    }

    @Test
    public void linkDuplicateOrderAndChannelMismatch() {
        DriverSet ds = clean();
        addSub(ds, "sub-ctp2", RULE_OK);
        ad(ds).links.get(2).order = 0;    // same order as sub-ctp
        ad(ds).links.add(new PolicyLink(PolicySet.PUB_COMMAND, "drivers/AD/subscriber/sub-ctp", 0));
        Report r = owned().validate(ds);
        assertEquals(1, r.withCode("link-duplicate-order").size());
        assertEquals(1, r.withCode("link-channel-mismatch").size());
    }

    @Test
    public void unlinkedPolicyAndDriverWithoutShim() {
        DriverSet ds = clean();
        ad(ds).policies.add(new Policy("orphan", Scope.DRIVER, "AD", xml(RULE_OK)));
        ad(ds).shimClass = null;
        Report r = owned().validate(ds);
        assertEquals("drivers/AD/orphan", r.withCode("policy-unlinked").get(0).path);
        assertEquals(1, r.withCode("driver-no-shim").size());
    }

    // ---- compile ----

    @Test
    public void compileErrorQuotesTheEngine() {
        DriverSet ds = clean();
        addSub(ds, "bad", "<policy><rule><description>r</description><conditions/><actions><do-frobnicate/></actions></rule></policy>");
        Report r = owned().validate(ds);
        List<Finding> f = r.withCode("compile-error");
        assertEquals(1, f.size());
        assertEquals("drivers/AD/subscriber/bad", f.get(0).path);
        assertTrue(f.get(0).message, f.get(0).message.contains("do-frobnicate"));
    }

    @Test
    public void xsltErrorCarriesParserDetail() {
        DriverSet ds = clean();
        addSub(ds, "badxsl", "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
            + "<xsl:template match=\"/\"><xsl:value-of select=\"@@@(\"/></xsl:template></xsl:stylesheet>");
        Finding f = owned().validate(ds).withCode("compile-error").get(0);
        assertTrue(f.message, f.message.contains("-9014"));
        assertTrue(String.valueOf(f.detail), f.detail != null && f.detail.contains("node-test was expected"));
    }

    @Test
    public void tildeGcvIsSubstitutedBeforeCompile() {
        DirXmlScriptWithTilde(true);
    }

    @Test
    public void undefinedTildeGcvFailsCompile() {
        DirXmlScriptWithTilde(false);
    }

    private static void DirXmlScriptWithTilde(boolean defined) {
        DriverSet ds = clean();
        String name = defined ? "drv.users" : "drv.nope";
        addSub(ds, "tilde", "<policy><rule><description>r</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-xpath expression=\"./x/~" + name + "~\"/></arg-string>"
            + "</do-set-local-variable></actions></rule></policy>");
        Report r = owned().validate(ds);
        if (defined) {
            assertTrue(r.text(), r.withCode("compile-error").isEmpty());
        } else {
            Finding f = r.withCode("compile-error").get(0);
            assertTrue(f.message, f.message.contains("drv.nope") && f.message.contains("not defined"));
        }
    }

    @Test
    public void mapTokenCompilesAgainstRegisteredTable() {
        DriverSet ds = clean();
        addSub(ds, "map", "<policy><rule><description>r</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string>"
            + "<token-map dest=\"dn\" src=\"code\" table=\"..\\..\\Library\\CodeMap\"><token-text>1</token-text></token-map>"
            + "</arg-string></do-set-local-variable></actions></rule></policy>");
        Report r = owned().validate(ds);
        assertTrue(r.text(), r.ok());
    }

    @Test
    public void includeResolvesLibraryPolicy() {
        DriverSet ds = clean();
        ds.library.policies.add(new Policy("lib-shared", Scope.LIBRARY, null, xml(RULE_OK)));
        addSub(ds, "incl", "<policy><include name=\"..\\..\\Library\\lib-shared\"/></policy>");
        Report r = owned().validate(ds);
        assertTrue(r.text(), r.withCode("compile-error").isEmpty());
    }

    @Test
    public void libraryPolicyCompilesInLinkingDriversContext() {
        DriverSet ds = clean();
        // the library policy needs the driver's GCV; it's fine as loaded by AD
        ds.library.policies.add(new Policy("lib-tilde", Scope.LIBRARY, null, xml(
            "<policy><rule><description>r</description><conditions/><actions>"
                + "<do-set-local-variable name=\"x\"><arg-string><token-xpath expression=\"./x/~drv.users~\"/></arg-string>"
                + "</do-set-local-variable></actions></rule></policy>")));
        ad(ds).links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-tilde", 0));
        assertTrue(owned().validate(ds).withCode("compile-error").isEmpty());
        // linked by a second driver without that GCV: fails as loaded by it
        Driver other = new Driver("Other");
        other.shimClass = "x";
        other.config.put(Driver.SHIM_CONFIG_INFO, xml("<shim-config-info/>"));
        other.config.put(Driver.DRIVER_FILTER, xml(FILTER));
        other.links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-tilde", 0));
        ds.drivers.add(other);
        Finding f = owned().validate(ds).withCode("compile-error").get(0);
        assertEquals("library/lib-tilde", f.path);
        assertTrue(f.message, f.message.contains("as loaded by driver 'Other'"));
    }

    // ---- gcv ----

    @Test
    public void gcvUndefinedToken() {
        DriverSet ds = clean();
        addSub(ds, "gcv", "<policy><rule><description>r</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-global-variable name=\"drv.missing\"/>"
            + "<token-global-variable name=\"drv.users\"/><token-global-variable name=\"dirxml.auto.driverdn\"/>"
            + "</arg-string></do-set-local-variable></actions></rule></policy>");
        List<Finding> f = owned().validate(ds).withCode("gcv-undefined");
        assertEquals(1, f.size());
        assertTrue(f.get(0).message, f.get(0).message.endsWith("drv.missing"));
    }

    @Test
    public void gcvDefinedByDriverSetResource() {
        DriverSet ds = clean();
        Resource gc = new Resource("SetGCVs", Scope.LIBRARY, null, Resource.GCV_DEF);
        gc.content = xml("<configuration-values><definitions><definition display-name=\"s\" name=\"set.value\" type=\"string\"><value>1</value></definition></definitions></configuration-values>");
        ds.library.resources.add(gc);
        ds.meta.put("driverset.linkage.0", "cn=SetGCVs,cn=Library,cn=dvs,o=system#0#14");
        addSub(ds, "gcv", "<policy><rule><description>r</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-global-variable name=\"set.value\"/>"
            + "<token-xpath expression=\"~set.value~\"/></arg-string></do-set-local-variable></actions></rule></policy>");
        Report r = owned().validate(ds);
        assertTrue(r.text(), r.ok());
    }

    // ---- mapping tables ----

    @Test
    public void mappingTableMissingAndColumn() {
        DriverSet ds = clean();
        addSub(ds, "maps", "<policy><rule><description>r</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string>"
            + "<token-map dest=\"dn\" src=\"code\" table=\"..\\..\\Library\\NoSuch\"><token-text>1</token-text></token-map>"
            + "<token-map dest=\"nope\" src=\"code\" table=\"..\\..\\Library\\CodeMap\"><token-text>1</token-text></token-map>"
            + "<token-map dest=\"dn\" src=\"code\" table=\"$tableDn$\"><token-text>1</token-text></token-map>"
            + "</arg-string></do-set-local-variable></actions></rule></policy>");
        Report r = owned().validate(ds);
        assertEquals(1, r.withCode("mapping-table-missing").size());
        assertEquals(1, r.withCode("mapping-table-column").size());
        assertEquals(1, r.withCode("mapping-table-dynamic").size());
        // the engine's compile fails on the missing table too
        assertFalse(r.withCode("compile-error").isEmpty());
    }

    @Test
    public void raggedRowsWarnAndBadRootMismatches() {
        DriverSet ds = clean();
        Resource ragged = new Resource("Ragged", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        ragged.content = xml("<mapping-table><col-def name=\"a\" type=\"nocase\"/><col-def name=\"b\" type=\"nocase\"/><row><col>1</col></row></mapping-table>");
        Resource wrong = new Resource("Wrong", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        wrong.content = xml("<entitlement-configuration/>");
        ds.library.resources.add(ragged);
        ds.library.resources.add(wrong);
        Report r = owned().validate(ds);
        assertEquals(1, r.withCode("mapping-table-ragged-row").size());
        assertEquals(1, r.withCode("resource-content-mismatch").size());
        assertTrue(r.ok());
    }

    // ---- files ----

    @Test
    public void malformedFileIsReportedNotThrown() throws Exception {
        Path dir = Files.createTempDirectory("idm-validate");
        AsCodeWriter.write(clean(), dir);
        Path bad = dir.resolve("drivers/AD/subscriber/sub-ctp.policy.xml");
        Files.writeString(bad, "<policy><rule></policy>", StandardCharsets.UTF_8);
        Report r = Validator.standard().validate(dir);
        assertEquals(1, r.withCode("xml-not-well-formed").size());
        assertTrue(r.withCode("xml-not-well-formed").get(0).path.endsWith("sub-ctp.policy.xml"));
        assertFalse(r.ok());
    }

    @Test
    public void cleanTreeValidatesFromDisk() throws Exception {
        Path dir = Files.createTempDirectory("idm-validate");
        AsCodeWriter.write(clean(), dir);
        Report r = Validator.standard().validate(dir);
        assertTrue(r.text(), r.ok());
        assertTrue(r.json().startsWith("{\"ok\":true,"));
    }
}
