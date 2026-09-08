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
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The operation core against a real on-disk tree: every operation on the
 * synthetic driver set from {@link ValidatorTest}, the refusals, the write/sync
 * semantics (only changed files, deletions, dry run), and package baselines.
 */
public class ArtifactOpsTest {

    private Path tree;

    @Before
    public void writeTree() throws IOException {
        tree = Files.createTempDirectory("idm-ops");
        DriverSet ds = ValidatorTest.clean();
        // a library policy with an include target, a driver Map token on the library table
        ds.library.policies.add(new Policy("lib-shared", Scope.LIBRARY, null, ValidatorTest.xml(
            "<policy><rule><description>shared</description><conditions/><actions/></rule></policy>")));
        Driver ad = ds.driver("AD");
        ad.subscriber.policies.add(new Policy("sub-incl", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><include name=\"..\\..\\Library\\lib-shared\"/></policy>")));
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "drivers/AD/subscriber/sub-incl", 0));
        ad.subscriber.policies.add(new Policy("sub-map", Scope.SUBSCRIBER, "AD", ValidatorTest.xml(
            "<policy><rule><description>m</description><conditions/><actions><do-set-local-variable name=\"x\">"
                + "<arg-string><token-map dest=\"dn\" src=\"code\" table=\"..\\..\\Library\\CodeMap\"><token-text>1</token-text>"
                + "</token-map></arg-string></do-set-local-variable></actions></rule></policy>")));
        ad.links.add(new PolicyLink(PolicySet.SUB_MATCH, "drivers/AD/subscriber/sub-map", 0));
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws IOException {
        return Transaction.open(tree).run(op, false, false);
    }

    private DriverSet reload() throws IOException {
        return AsCodeReader.read(tree);
    }

    // ---- add ----

    @Test
    public void addPolicyLinkedAfter() throws IOException {
        Result r = run(new ArtifactOps.Add(Scope.SUBSCRIBER, "AD", "sub-ctp-New", "policy", null,
            PolicySet.SUB_COMMAND, ArtifactOps.Position.after("drivers/AD/subscriber/sub-ctp"), null));
        assertTrue(r.text(), r.ok() && r.written);
        assertTrue(r.changedFiles.contains("drivers/AD/subscriber/sub-ctp-New.policy.xml"));
        assertTrue(r.changedFiles.contains("drivers/AD/driver.xml"));
        assertEquals(2, r.changedFiles.size());
        DriverSet ds = reload();
        List<PolicyLink> cmd = ds.driver("AD").links(PolicySet.SUB_COMMAND);
        assertEquals(Arrays.asList("drivers/AD/subscriber/sub-ctp", "drivers/AD/subscriber/sub-ctp-New"),
            cmd.stream().map(l -> l.ref).toList());
        assertEquals(Arrays.asList(0, 1), cmd.stream().map(l -> l.order).toList());
        assertTrue(r.report.ok());
    }

    @Test
    public void addRefusesDuplicateAndBadContent() throws IOException {
        Result dup = run(new ArtifactOps.Add(Scope.SUBSCRIBER, "AD", "sub-ctp", "policy", null, null, null, null));
        assertFalse(dup.ok());
        assertTrue(dup.refusal, dup.refusal.contains("already exists"));
        Result bad = run(new ArtifactOps.Add(Scope.DRIVER, "AD", "broken", "policy",
            "<policy><rule><description>r</description><conditions/><actions><do-frobnicate/></actions></rule></policy>",
            PolicySet.SUB_EVENT, null, null));
        assertFalse(bad.ok());
        assertEquals(1, bad.newErrors.size());
        assertEquals("compile-error", bad.newErrors.get(0).code);
        assertNull(reload().resolve("drivers/AD/broken"));   // nothing written
    }

    @Test
    public void addLibraryArtifactNeedsLinkDriver() throws IOException {
        Result r = run(new ArtifactOps.Add(Scope.LIBRARY, null, "lib-new", "policy", null, PolicySet.SUB_EVENT, null, null));
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("link-driver"));
        Result ok = run(new ArtifactOps.Add(Scope.LIBRARY, null, "lib-new", "policy", null, PolicySet.SUB_EVENT, null, "AD"));
        assertTrue(ok.text(), ok.ok());
        assertEquals("library/lib-new", reload().driver("AD").links(PolicySet.SUB_EVENT).get(1).ref);
    }

    @Test
    public void addMappingTableSkeleton() throws IOException {
        Result r = run(new ArtifactOps.Add(Scope.DRIVER, "AD", "NewMap", "mapping-table", null, null, null, null));
        assertTrue(r.text(), r.ok());
        Resource t = (Resource) reload().resolve("drivers/AD/NewMap");
        assertTrue(t.isMappingTable());
        assertEquals("mapping-table", t.content.getNodeName());
    }

    // ---- set-content ----

    @Test
    public void setContentRefusesUncompilable() throws IOException {
        Result r = run(new ArtifactOps.SetContent("drivers/AD/subscriber/sub-ctp",
            "<policy><rule><description>r</description><conditions><and><if-xpath op=\"true\">@@@(</if-xpath></and></conditions><actions/></rule></policy>"));
        assertFalse(r.ok());
        assertEquals("compile-error", r.newErrors.get(0).code);
        Result forced = Transaction.open(tree).run(new ArtifactOps.SetContent("drivers/AD/subscriber/sub-ctp",
            "<policy><rule><description>r</description><conditions><and><if-xpath op=\"true\">@@@(</if-xpath></and></conditions><actions/></rule></policy>"),
            false, true);
        assertTrue(forced.ok() && forced.written);
        assertEquals(1, forced.newErrors.size());
    }

    // ---- rename ----

    @Test
    public void renameRewritesLinksIncludesAndMapTokens() throws IOException {
        Result r = run(new ArtifactOps.Rename("library/lib-shared", "lib-renamed"));
        assertTrue(r.text(), r.ok());
        assertEquals("library/lib-renamed", r.renamed.get("library/lib-shared"));
        assertTrue(r.deletedFiles.contains("library/lib-shared.policy.xml"));
        DriverSet ds = reload();
        assertNull(ds.resolve("library/lib-shared"));
        assertNotNull(ds.resolve("library/lib-renamed"));
        Policy incl = (Policy) ds.resolve("drivers/AD/subscriber/sub-incl");
        assertTrue(com.pointblue.dirxml.dev.xml.CanonicalXml.serialize(incl.content).contains("Library\\lib-renamed"));

        Result t = run(new ArtifactOps.Rename("library/CodeMap", "CodeMap2"));
        assertTrue(t.text(), t.ok());
        ds = reload();
        Policy map = (Policy) ds.resolve("drivers/AD/subscriber/sub-map");
        assertTrue(com.pointblue.dirxml.dev.xml.CanonicalXml.serialize(map.content).contains("Library\\CodeMap2"));
        assertTrue(t.report.ok());

        Result l = run(new ArtifactOps.Rename("drivers/AD/subscriber/sub-ctp", "sub-ctp-Renamed"));
        assertTrue(l.text(), l.ok());
        assertEquals("drivers/AD/subscriber/sub-ctp-Renamed", reload().driver("AD").links(PolicySet.SUB_COMMAND).get(0).ref);
    }

    @Test
    public void renameCarriesPreExistingErrors() throws IOException {
        // a policy that already fails compile: renaming it must not count as a new error
        Transaction.open(tree).run(new ArtifactOps.SetContent("drivers/AD/subscriber/sub-ctp",
            "<policy><rule><description>r</description><conditions/><actions><do-frobnicate/></actions></rule></policy>"), false, true);
        Result r = run(new ArtifactOps.Rename("drivers/AD/subscriber/sub-ctp", "sub-ctp-Bad"));
        assertTrue(r.text(), r.ok());
        assertTrue(r.newErrors.isEmpty());
        assertFalse(r.report.ok());   // still broken, just not newly
    }

    // ---- delete ----

    @Test
    public void deleteRefusesWhileReferenced() throws IOException {
        Result r = run(new ArtifactOps.Delete("drivers/AD/subscriber/sub-ctp", false));
        assertFalse(r.ok());
        assertTrue(r.refusal, r.refusal.contains("link from drivers/AD"));
        Result u = run(new ArtifactOps.Delete("drivers/AD/subscriber/sub-ctp", true));
        assertTrue(u.text(), u.ok());
        assertTrue(u.deletedFiles.contains("drivers/AD/subscriber/sub-ctp.policy.xml"));
        assertNull(reload().resolve("drivers/AD/subscriber/sub-ctp"));
        assertTrue(reload().driver("AD").links(PolicySet.SUB_COMMAND).isEmpty());
        // an include is content, never removed silently
        Result i = run(new ArtifactOps.Delete("library/lib-shared", true));
        assertFalse(i.ok());
        assertTrue(i.refusal, i.refusal.contains("include from drivers/AD/subscriber/sub-incl"));
    }

    // ---- link / unlink / reorder ----

    @Test
    public void linkUnlinkReorder() throws IOException {
        Result l = run(new ArtifactOps.Link("library/lib-shared", "AD", PolicySet.SUB_COMMAND, ArtifactOps.Position.first()));
        assertTrue(l.text(), l.ok());
        assertEquals(Arrays.asList("library/lib-shared", "drivers/AD/subscriber/sub-ctp"),
            reload().driver("AD").links(PolicySet.SUB_COMMAND).stream().map(x -> x.ref).toList());
        Result again = run(new ArtifactOps.Link("library/lib-shared", "AD", PolicySet.SUB_COMMAND, null));
        assertFalse(again.ok());
        Result wrongKind = run(new ArtifactOps.Link("library/CodeMap", "AD", PolicySet.SUB_COMMAND, null));
        assertFalse(wrongKind.ok());
        assertEquals("link-kind", wrongKind.newErrors.get(0).code);

        Result ro = run(new ArtifactOps.Reorder("AD", PolicySet.SUB_COMMAND,
            Arrays.asList("drivers/AD/subscriber/sub-ctp", "library/lib-shared")));
        assertTrue(ro.text(), ro.ok());
        assertEquals("drivers/AD/subscriber/sub-ctp", reload().driver("AD").links(PolicySet.SUB_COMMAND).get(0).ref);
        Result partial = run(new ArtifactOps.Reorder("AD", PolicySet.SUB_COMMAND, List.of("library/lib-shared")));
        assertFalse(partial.ok());

        Result u = run(new ArtifactOps.Unlink("library/lib-shared", "AD", PolicySet.SUB_COMMAND));
        assertTrue(u.ok());
        assertEquals(1, reload().driver("AD").links(PolicySet.SUB_COMMAND).size());
        assertEquals(0, reload().driver("AD").links(PolicySet.SUB_COMMAND).get(0).order);
    }

    // ---- transaction semantics ----

    @Test
    public void dryRunWritesNothing() throws IOException {
        Result r = Transaction.open(tree).run(new ArtifactOps.Delete("drivers/AD/subscriber/sub-ctp", true), true, false);
        assertTrue(r.ok() && r.dryRun && !r.written);
        assertFalse(r.deletedFiles.isEmpty());
        assertNotNull(reload().resolve("drivers/AD/subscriber/sub-ctp"));
    }

    @Test
    public void unmanagedFilesSurvive() throws IOException {
        Files.writeString(tree.resolve("README.md"), "mine", StandardCharsets.UTF_8);
        Files.createDirectories(tree.resolve("cases/one"));
        Files.writeString(tree.resolve("cases/one/input.xds"), "<nds/>", StandardCharsets.UTF_8);
        Result r = run(new ArtifactOps.Unlink("drivers/AD/subscriber/sub-map", "AD", PolicySet.SUB_MATCH));
        assertTrue(r.ok());
        assertEquals(List.of("drivers/AD/driver.xml"), r.changedFiles);
        assertTrue(Files.exists(tree.resolve("README.md")));
        assertTrue(Files.exists(tree.resolve("cases/one/input.xds")));
    }

    // ---- packages ----

    @Test
    public void firstEditOfPackagedArtifactKeepsBaseline() throws IOException {
        DriverSet ds = reload();
        Policy p = (Policy) ds.resolve("drivers/AD/subscriber/sub-ctp");
        p.meta.put("package-id", "NOVLADBASE");
        p.meta.put("pkg-assoc-id", "abc");
        AsCodeWriter.write(ds, tree);
        String original = Files.readString(tree.resolve("drivers/AD/subscriber/sub-ctp.policy.xml"), StandardCharsets.UTF_8);

        Result r = run(new ArtifactOps.SetContent("drivers/AD/subscriber/sub-ctp",
            "<policy><rule><description>custom</description><conditions/><actions/></rule></policy>"));
        assertTrue(r.text(), r.ok());
        assertEquals(List.of("drivers/AD/subscriber/sub-ctp"), r.customized);
        Path baseline = tree.resolve(".package-baseline/drivers/AD/subscriber/sub-ctp.policy.xml");
        assertTrue(Files.exists(baseline));
        assertEquals(original, Files.readString(baseline, StandardCharsets.UTF_8));
        Policy after = (Policy) reload().resolve("drivers/AD/subscriber/sub-ctp");
        assertTrue(Packages.isCustomized(after));

        // a second edit: already customized, baseline untouched
        Result r2 = run(new ArtifactOps.SetContent("drivers/AD/subscriber/sub-ctp",
            "<policy><rule><description>custom2</description><conditions/><actions/></rule></policy>"));
        assertTrue(r2.customized.isEmpty());
        assertEquals(original, Files.readString(baseline, StandardCharsets.UTF_8));
        // unpackaged artifacts never get a baseline
        Result r3 = run(new ArtifactOps.SetContent("drivers/AD/subscriber/sub-map",
            "<policy><rule><description>x</description><conditions/><actions/></rule></policy>"));
        assertTrue(r3.customized.isEmpty());
        assertFalse(Files.exists(tree.resolve(".package-baseline/drivers/AD/subscriber/sub-map.policy.xml")));
    }

    // ---- refs ----

    @Test
    public void refsIndex() throws IOException {
        DriverSet ds = reload();
        List<Refs.Ref> lib = Refs.to(ds, "library/lib-shared");
        assertEquals(1, lib.size());
        assertEquals("include", lib.get(0).kind);
        List<Refs.Ref> table = Refs.to(ds, "library/CodeMap");
        assertEquals(1, table.size());
        assertEquals("map", table.get(0).kind);
        List<Refs.Ref> pol = Refs.to(ds, "drivers/AD/subscriber/sub-ctp");
        assertEquals(1, pol.size());
        assertEquals("link", pol.get(0).kind);
        assertTrue(Refs.to(ds, "drivers/AD/smp").get(0).detail.contains("schema-mapping"));
    }
}
