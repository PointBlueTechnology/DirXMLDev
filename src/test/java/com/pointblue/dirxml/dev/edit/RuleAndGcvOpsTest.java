package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuleAndGcvOpsTest {

    private static final String THREE_RULES =
        "<policy>"
            + "<rule><description>one</description><conditions/><actions/></rule>"
            + "<rule><description>two</description><conditions/><actions/></rule>"
            + "<rule><description>three</description><conditions/><actions/></rule>"
            + "</policy>";

    private Path tree;

    @Before
    public void writeTree() throws IOException {
        tree = Files.createTempDirectory("idm-rules");
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");
        ad.subscriber.policies.add(new Policy("sub-rules", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(THREE_RULES)));
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "drivers/AD/subscriber/sub-rules", 0));
        // a set-14 GCV resource and a driver-set GCV object
        Resource pkg = new Resource("PKG-GCVs", Scope.DRIVER, "AD", Resource.GCV_DEF);
        pkg.content = ValidatorTest.xml("<configuration-values><definitions>"
            + "<definition display-name=\"Realm\" name=\"drv.realm\" type=\"string\"><value>old</value></definition>"
            + "</definitions></configuration-values>");
        pkg.meta.put("package-id", "PKG");
        ad.resources.add(pkg);
        ad.links.add(new PolicyLink(PolicySet.GCV, "drivers/AD/PKG-GCVs", 0));
        Resource setGcv = new Resource("SetGCVs", Scope.LIBRARY, null, Resource.GCV_DEF);
        setGcv.content = ValidatorTest.xml("<configuration-values><definitions>"
            + "<definition display-name=\"Tree\" name=\"set.tree\" type=\"string\"><value>t1</value></definition>"
            + "</definitions></configuration-values>");
        ds.library.resources.add(setGcv);
        ds.meta.put("driverset.linkage.0", "cn=SetGCVs,cn=Library,cn=dvs,o=system#0#14");
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws IOException {
        return Transaction.open(tree).run(op, false, false);
    }

    private List<String> rules() throws IOException {
        Policy p = (Policy) AsCodeReader.read(tree).resolve("drivers/AD/subscriber/sub-rules");
        return RuleOps.describe(Xds.childrenByName(p.content, "rule"));
    }

    private String gcv(String driver, String name) throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        GcvOps.Home h = GcvOps.find(ds, driver == null ? null : ds.driver(driver), name, ds.index());
        if (h == null) {
            return null;
        }
        List<Element> v = Xds.childrenByName(h.definition, "value");
        return h.owner + "=" + (v.isEmpty() ? "" : Xds.text(v.get(0)));
    }

    // ---- rules ----

    @Test
    public void addDeleteMove() throws IOException {
        Result add = run(new RuleOps.Add("drivers/AD/subscriber/sub-rules",
            "<rule><description>new</description><conditions/><actions/></rule>", "after:one"));
        assertTrue(add.text(), add.ok());
        assertEquals(List.of("#1 one", "#2 new", "#3 two", "#4 three"), rules());
        Result mv = run(new RuleOps.Move("drivers/AD/subscriber/sub-rules", "new", "last"));
        assertTrue(mv.ok());
        assertEquals(List.of("#1 one", "#2 two", "#3 three", "#4 new"), rules());
        Result del = run(new RuleOps.Delete("drivers/AD/subscriber/sub-rules", "#2"));
        assertTrue(del.ok());
        assertEquals(List.of("#1 one", "#2 three", "#3 new"), rules());
        Result first = run(new RuleOps.Move("drivers/AD/subscriber/sub-rules", "new", "first"));
        assertTrue(first.ok());
        assertEquals(List.of("#1 new", "#2 one", "#3 three"), rules());
    }

    @Test
    public void refusals() throws IOException {
        assertTrue(run(new RuleOps.Delete("drivers/AD/subscriber/sub-rules", "nope")).refusal.contains("no rule 'nope'"));
        assertTrue(run(new RuleOps.Delete("drivers/AD/subscriber/sub-rules", "#9")).refusal.contains("out of range"));
        assertTrue(run(new RuleOps.Add("drivers/AD/smp", "<rule/>", null)).refusal.contains("not a DirXML Script"));
        assertTrue(run(new RuleOps.Add("drivers/AD/subscriber/sub-rules", "<actions/>", null)).refusal.contains("must be <rule>"));
        // a rule the engine rejects is refused by validation, not written
        Result bad = run(new RuleOps.Add("drivers/AD/subscriber/sub-rules",
            "<rule><description>bad</description><conditions/><actions><do-frobnicate/></actions></rule>", null));
        assertFalse(bad.ok());
        assertEquals("compile-error", bad.newErrors.get(0).code);
        assertEquals(3, rules().size());
        // duplicate descriptions need #n
        run(new RuleOps.Add("drivers/AD/subscriber/sub-rules",
            "<rule><description>one</description><conditions/><actions/></rule>", null));
        assertTrue(run(new RuleOps.Delete("drivers/AD/subscriber/sub-rules", "one")).refusal.contains("use #n"));
    }

    @Test
    public void disableEnable() throws IOException {
        Result d = run(new RuleOps.SetDisabled("drivers/AD/subscriber/sub-rules", "two", true));
        assertTrue(d.text(), d.ok());
        Policy p = (Policy) AsCodeReader.read(tree).resolve("drivers/AD/subscriber/sub-rules");
        assertEquals("true", Xds.childrenByName(p.content, "rule").get(1).getAttribute("disabled"));
        Result e = run(new RuleOps.SetDisabled("drivers/AD/subscriber/sub-rules", "two", false));
        assertTrue(e.ok());
        p = (Policy) AsCodeReader.read(tree).resolve("drivers/AD/subscriber/sub-rules");
        assertFalse(Xds.childrenByName(p.content, "rule").get(1).hasAttribute("disabled"));
    }

    // ---- GCVs ----

    @Test
    public void setFollowsEnginePrecedence() throws IOException {
        assertTrue(run(new GcvOps.Set("AD", "drv.users", "people", null, null)).ok());
        assertEquals("drivers/AD=people", gcv("AD", "drv.users"));
        // defined in the linked (packaged) GCV resource: changed there, baseline kept
        Result r = run(new GcvOps.Set("AD", "drv.realm", "new", null, null));
        assertTrue(r.text(), r.ok());
        assertEquals("drivers/AD/PKG-GCVs=new", gcv("AD", "drv.realm"));
        assertEquals(List.of("drivers/AD/PKG-GCVs"), r.customized);
        assertTrue(Files.exists(tree.resolve(".package-baseline/drivers/AD/PKG-GCVs.gcv.xml")));
        // defined at driver-set level through a GCV object
        assertTrue(run(new GcvOps.Set("AD", "set.tree", "t2", null, null)).ok());
        assertEquals("library/SetGCVs=t2", gcv("AD", "set.tree"));
        assertEquals("library/SetGCVs=t2", gcv(null, "set.tree"));
    }

    @Test
    public void setCreatesOnlyWithDefine() throws IOException {
        Result r = run(new GcvOps.Set("AD", "drv.new", "x", null, null));
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("--define"));
        Result c = run(new GcvOps.Set("AD", "drv.new", "x", "string", "New thing"));
        assertTrue(c.text(), c.ok());
        assertEquals("drivers/AD=x", gcv("AD", "drv.new"));
        assertTrue(c.report.ok());
        // and at driver-set level
        Result s = run(new GcvOps.Set(null, "set.new", "y", "boolean", null));
        assertTrue(s.text(), s.ok());
        assertEquals("driverset=y", gcv(null, "set.new"));
        String cv = Files.readString(tree.resolve("config-values.xml"));
        assertTrue(cv, cv.contains("display-name=\"set.new\"") && cv.contains("type=\"boolean\""));
    }

    @Test
    public void deleteRefusesWhileRead() throws IOException {
        // sub-ctp reads nothing; add a policy that reads drv.users as a token and set.tree as ~tilde~
        DriverSet ds = AsCodeReader.read(tree);
        Driver ad = ds.driver("AD");
        ad.subscriber.policies.add(new Policy("sub-reads", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><rule><description>r</description><conditions/><actions><do-set-local-variable name=\"x\">"
                + "<arg-string><token-global-variable name=\"drv.users\"/><token-xpath expression=\"'~set.tree~'\"/></arg-string>"
                + "</do-set-local-variable></actions></rule></policy>")));
        ad.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-reads", 1));
        AsCodeWriter.write(ds, tree);

        Result a = run(new GcvOps.Delete("AD", "drv.users"));
        assertFalse(a.ok());
        assertTrue(a.refusal, a.refusal.contains("sub-reads"));
        Result b = run(new GcvOps.Delete("AD", "set.tree"));
        assertFalse(b.ok());
        assertTrue(b.refusal, b.refusal.contains("sub-reads"));
        Result c = run(new GcvOps.Delete("AD", "drv.realm"));
        assertTrue(c.text(), c.ok());
        assertEquals(null, gcv("AD", "drv.realm"));
        assertTrue(run(new GcvOps.Delete("AD", "drv.realm")).refusal.contains("not defined"));
    }

    @Test
    public void cliRoundTrip() throws Exception {
        Path ruleFile = Files.createTempFile("rule", ".xml");
        Files.writeString(ruleFile, "<rule><description>cli</description><conditions/><actions/></rule>");
        int code = EditCli.run(new String[] {"rule.add", tree.toString(), "--path", "drivers/AD/subscriber/sub-rules",
            "--content-file", ruleFile.toString(), "--at", "first", "--json"});
        assertEquals(0, code);
        assertEquals("#1 cli", rules().get(0));
        assertEquals(1, EditCli.run(new String[] {"rule.delete", tree.toString(), "--path", "drivers/AD/subscriber/sub-rules", "--rule", "nope"}));
        assertEquals(2, EditCli.run(new String[] {"rule.delete", tree.toString(), "--path", "drivers/AD/subscriber/sub-rules"}));
        assertEquals(CanonicalXml.serialize(ValidatorTest.xml("<a/>")).length() > 0, true);
    }
}
