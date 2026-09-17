package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.ModelDiff.Change;
import com.pointblue.dirxml.dev.deploy.ModelDiff.Kind;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.source.ExportReader;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * One test per {@link Kind}, the identical-models case, the linkage order-normalization
 * rule, {@link ModelDiff#affectedDrivers()} for a Library change and a driver-set GCV
 * change, and a guarded check against the local RFI export.
 */
public class ModelDiffTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String RULE1 =
        "<policy><rule><description>r1</description><conditions/><actions/></rule></policy>";
    private static final String RULE2 =
        "<policy><rule><description>r2</description><conditions/><actions/></rule></policy>";
    private static final String XSLT =
        "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
            + "<xsl:template match=\"/\"/></xsl:stylesheet>";
    private static final String FILTER2 =
        "<filter><filter-class class-name=\"Group\" publisher=\"sync\" subscriber=\"sync\"/></filter>";
    private static final String GCVS2 =
        "<configuration-values><definitions>"
            + "<definition display-name=\"Other\" name=\"drv.other\" type=\"string\"><value>x</value></definition>"
            + "</definitions></configuration-values>";

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /** A deep, independent copy of {@code ds} via the as-code round trip. */
    private DriverSet copy(DriverSet ds) throws IOException {
        Path dir = tmp.newFolder().toPath();
        AsCodeWriter.write(ds, dir);
        return AsCodeReader.read(dir);
    }

    private static Policy findPolicy(DriverSet ds, String path) {
        return (Policy) ds.index().get(path);
    }

    private static Resource findResource(DriverSet ds, String path) {
        return (Resource) ds.index().get(path);
    }

    private static List<Change> of(ModelDiff diff, Kind kind) {
        List<Change> out = new ArrayList<>();
        for (Change c : diff.changes()) {
            if (c.kind == kind) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- baseline ----

    @Test
    public void identicalModelsAreEmpty() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        ModelDiff diff = ModelDiff.of(from, to);
        assertTrue(diff.text(), diff.isEmpty());
        assertTrue(diff.changes().isEmpty());
        assertTrue(diff.affectedDrivers().isEmpty());
        assertEquals("no differences\n", diff.text());
    }

    // ---- ARTIFACT_ADDED / ARTIFACT_REMOVED ----

    @Test
    public void artifactAdded() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.driver("AD").subscriber.policies.add(new Policy("sub-new", Scope.SUBSCRIBER, "AD", xml(RULE1)));

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> added = of(diff, Kind.ARTIFACT_ADDED);
        assertEquals(diff.text(), 1, added.size());
        assertEquals("drivers/AD/subscriber/sub-new", added.get(0).path);
        assertEquals("AD", added.get(0).driver);
        assertTrue(added.get(0).summary.startsWith("+"));
    }

    @Test
    public void artifactRemoved() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.driver("AD").subscriber.policies.removeIf(p -> p.name.equals("sub-ctp"));
        to.driver("AD").links.removeIf(l -> l.ref.equals("drivers/AD/subscriber/sub-ctp"));

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> removed = of(diff, Kind.ARTIFACT_REMOVED);
        assertEquals(diff.text(), 1, removed.size());
        assertEquals("drivers/AD/subscriber/sub-ctp", removed.get(0).path);
        assertTrue(removed.get(0).summary.startsWith("-"));
    }

    // ---- ARTIFACT_CHANGED ----

    @Test
    public void artifactChangedPolicyContent() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        findPolicy(to, "drivers/AD/subscriber/sub-ctp").content = xml(RULE2);

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> changed = of(diff, Kind.ARTIFACT_CHANGED);
        assertEquals(diff.text(), 1, changed.size());
        assertEquals("drivers/AD/subscriber/sub-ctp", changed.get(0).path);
        assertNotNull(changed.get(0).detail);
        assertTrue(changed.get(0).detail.contains("r1"));
        assertTrue(changed.get(0).detail.contains("r2"));
    }

    @Test
    public void artifactChangedResourceContentType() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        findResource(to, "library/CodeMap").contentType = "application/x-other";

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> changed = of(diff, Kind.ARTIFACT_CHANGED);
        assertEquals(diff.text(), 1, changed.size());
        assertEquals("library/CodeMap", changed.get(0).path);
        assertNull(changed.get(0).driver);
        assertTrue(changed.get(0).detail, changed.get(0).detail.contains("content-type"));
    }

    // ---- ARTIFACT_KIND_CHANGED ----

    @Test
    public void artifactKindChangedPolicyKind() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        findPolicy(to, "drivers/AD/subscriber/sub-ctp").content = xml(XSLT);

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> kindChanged = of(diff, Kind.ARTIFACT_KIND_CHANGED);
        assertEquals(diff.text(), 1, kindChanged.size());
        assertEquals("drivers/AD/subscriber/sub-ctp", kindChanged.get(0).path);
        assertTrue(of(diff, Kind.ARTIFACT_CHANGED).isEmpty());
    }

    @Test
    public void artifactKindChangedPolicyToResource() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        Driver ad = to.driver("AD");
        ad.policies.removeIf(p -> p.name.equals("smp"));
        Resource asResource = new Resource("smp", Scope.DRIVER, "AD", Resource.MAPPING_TABLE);
        ad.resources.add(asResource);

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> kindChanged = of(diff, Kind.ARTIFACT_KIND_CHANGED);
        assertEquals(diff.text(), 1, kindChanged.size());
        assertEquals("drivers/AD/smp", kindChanged.get(0).path);
    }

    // ---- DRIVER_ICON ----

    private static final byte[] ICON_A = com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.TINY_GIF;
    private static final byte[] ICON_B = "GIF89a-different".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Icons are compared only when both sides can hold one — {@code of(from, to)} never reports them. */
    @Test
    public void iconsAreNotComparedAgainstAVaultSideModel() throws IOException {
        DriverSet from = ValidatorTest.clean();            // a vault/export model: no icon anywhere
        DriverSet to = copy(from);
        to.driver("AD").icon = ICON_A;
        to.driver("AD").iconExtension = "gif";

        assertTrue(ModelDiff.of(from, to).text(), ModelDiff.of(from, to).isEmpty());
        assertEquals(1, of(ModelDiff.of(from, to, true), Kind.DRIVER_ICON).size());
    }

    @Test
    public void iconAddedChangedAndRemoved() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.driver("AD").icon = ICON_A;
        to.driver("AD").iconExtension = "gif";

        Change added = of(ModelDiff.of(from, to, true), Kind.DRIVER_ICON).get(0);
        assertEquals("AD", added.driver);
        assertEquals("drivers/AD", added.path);
        assertEquals("icon", added.what);
        assertTrue(added.summary, added.summary.startsWith("+ icon added (" + ICON_A.length + " bytes, gif)"));
        assertNull("never the bytes", added.detail);

        from.driver("AD").icon = ICON_B;
        from.driver("AD").iconExtension = "gif";
        Change changed = of(ModelDiff.of(from, to, true), Kind.DRIVER_ICON).get(0);
        assertTrue(changed.summary, changed.summary.startsWith("~ icon changed ("));
        assertFalse(changed.summary, changed.summary.contains("GIF89a"));

        to.driver("AD").icon = null;
        to.driver("AD").iconExtension = null;
        Change removed = of(ModelDiff.of(from, to, true), Kind.DRIVER_ICON).get(0);
        assertTrue(removed.summary, removed.summary.startsWith("- icon removed ("));
    }

    /** Same bytes, different format: still a change (the file's name changes). */
    @Test
    public void iconFormatAloneIsAChange() throws IOException {
        DriverSet from = ValidatorTest.clean();
        from.driver("AD").icon = ICON_A;
        from.driver("AD").iconExtension = "gif";
        DriverSet to = copy(from);
        to.driver("AD").iconExtension = "png";
        assertEquals(1, of(ModelDiff.of(from, to, true), Kind.DRIVER_ICON).size());
    }

    /** An icon change never restarts the driver — nothing in the vault changed. */
    @Test
    public void anIconChangeAffectsNoDriver() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.driver("AD").icon = ICON_A;
        to.driver("AD").iconExtension = "gif";
        ModelDiff diff = ModelDiff.of(from, to, true);
        assertFalse(diff.isEmpty());
        assertTrue(Kind.DRIVER_ICON.noRestart());
        assertEquals(List.of(), diff.affectedDrivers());
    }

    // ---- DRIVER_SETTING ----

    @Test
    public void driverSetting() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.driver("AD").shimClass = "com.example.NewShim";
        to.driver("AD").shimAuthServer = "server.example.com";

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> settings = of(diff, Kind.DRIVER_SETTING);
        assertEquals(diff.text(), 2, settings.size());
        Set<String> whats = new TreeSet<>();
        for (Change c : settings) {
            whats.add(c.what);
            assertEquals("AD", c.driver);
        }
        assertEquals(new TreeSet<>(Arrays.asList("shim-class", "shim-auth-server")), whats);
    }

    // ---- DRIVER_CONFIG ----

    @Test
    public void driverConfigAddedChangedRemoved() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        Driver ad = to.driver("AD");
        ad.config.put(Driver.DRIVER_FILTER, xml(FILTER2));                 // changed
        ad.config.put(Driver.ENGINE_CONTROL_VALUES, xml("<engine-values/>")); // added
        ad.config.remove(Driver.SHIM_CONFIG_INFO);                          // removed

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> config = of(diff, Kind.DRIVER_CONFIG);
        assertEquals(diff.text(), 3, config.size());
        Set<String> whats = new TreeSet<>();
        for (Change c : config) {
            whats.add(c.what);
            assertEquals("AD", c.driver);
        }
        assertEquals(new TreeSet<>(Arrays.asList(
            Driver.DRIVER_FILTER, Driver.ENGINE_CONTROL_VALUES, Driver.SHIM_CONFIG_INFO)), whats);
    }

    // ---- DRIVER_LINKAGE + order normalization ----

    @Test
    public void linkageSameRefsDifferentOrderNumbersIsNotAChange() throws IOException {
        DriverSet from = ValidatorTest.clean();
        from.driver("AD").subscriber.policies.add(new Policy("sub-ctp2", Scope.SUBSCRIBER, "AD", xml(RULE1)));
        from.driver("AD").links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-ctp2", 1));
        DriverSet to = copy(from);

        // renumber (0,1) -> (0,2) on `to`: same ref sequence, different literal order values
        for (PolicyLink l : to.driver("AD").links) {
            if (l.set == PolicySet.SUB_COMMAND && l.ref.endsWith("sub-ctp2")) {
                l.order = 2;
            }
        }

        ModelDiff diff = ModelDiff.of(from, to);
        assertTrue(diff.text(), of(diff, Kind.DRIVER_LINKAGE).isEmpty());
        assertTrue(diff.isEmpty());
    }

    @Test
    public void linkageReorderIsAChange() throws IOException {
        DriverSet from = ValidatorTest.clean();
        from.driver("AD").subscriber.policies.add(new Policy("sub-ctp2", Scope.SUBSCRIBER, "AD", xml(RULE1)));
        from.driver("AD").links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-ctp2", 1));
        DriverSet to = copy(from);

        // swap the relative order: sub-ctp2 now runs before sub-ctp
        for (PolicyLink l : to.driver("AD").links) {
            if (l.set != PolicySet.SUB_COMMAND) {
                continue;
            }
            l.order = l.ref.endsWith("sub-ctp2") ? 0 : 1;
        }

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> linkage = of(diff, Kind.DRIVER_LINKAGE);
        assertEquals(diff.text(), 1, linkage.size());
        assertEquals("subscriber-command", linkage.get(0).what);
        assertEquals("AD", linkage.get(0).driver);
    }

    @Test
    public void linkageMembershipChangeIsAChange() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.driver("AD").subscriber.policies.add(new Policy("sub-new", Scope.SUBSCRIBER, "AD", xml(RULE1)));
        to.driver("AD").links.add(new PolicyLink(PolicySet.SUB_COMMAND, "drivers/AD/subscriber/sub-new", 1));

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> linkage = of(diff, Kind.DRIVER_LINKAGE);
        assertEquals(diff.text(), 1, linkage.size());
        assertEquals("subscriber-command", linkage.get(0).what);
    }

    // ---- DRIVER_ADDED / DRIVER_REMOVED ----

    @Test
    public void driverAdded() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        Driver added = new Driver("New");
        added.shimClass = "com.example.NewShim";
        added.policies.add(new Policy("smp2", Scope.DRIVER, "New", xml(RULE1)));
        added.links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/New/smp2", 0));
        to.drivers.add(added);

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> driverAdded = of(diff, Kind.DRIVER_ADDED);
        assertEquals(diff.text(), 1, driverAdded.size());
        assertEquals("New", driverAdded.get(0).driver);
        assertEquals("drivers/New", driverAdded.get(0).path);
        assertNotNull(driverAdded.get(0).detail);
        assertTrue(driverAdded.get(0).detail.contains("drivers/New/smp2"));
        // the new driver's own artifact is folded into DRIVER_ADDED, not reported separately
        for (Change c : of(diff, Kind.ARTIFACT_ADDED)) {
            assertFalse(c.path.startsWith("drivers/New/"));
        }
    }

    @Test
    public void driverRemoved() throws IOException {
        DriverSet from = ValidatorTest.clean();
        Driver old = new Driver("Old");
        old.shimClass = "com.example.OldShim";
        old.policies.add(new Policy("smp2", Scope.DRIVER, "Old", xml(RULE1)));
        from.drivers.add(old);
        DriverSet to = copy(from);
        to.drivers.removeIf(d -> d.name.equals("Old"));

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> driverRemoved = of(diff, Kind.DRIVER_REMOVED);
        assertEquals(diff.text(), 1, driverRemoved.size());
        assertEquals("Old", driverRemoved.get(0).driver);
        assertTrue(driverRemoved.get(0).summary.contains("never deleted by deploy"));
        for (Change c : of(diff, Kind.ARTIFACT_REMOVED)) {
            assertFalse(c.path.startsWith("drivers/Old/"));
        }
    }

    // ---- DRIVERSET_GCVS ----

    @Test
    public void driverSetGcvsChanged() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        to.configValues = xml(GCVS2);

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> gcvs = of(diff, Kind.DRIVERSET_GCVS);
        assertEquals(diff.text(), 1, gcvs.size());
        assertNull(gcvs.get(0).driver);
        assertEquals("driverset", gcvs.get(0).path);
    }

    // ---- DRIVERSET_LINKAGE ----

    @Test
    public void driverSetLinkageChanged() throws IOException {
        DriverSet from = ValidatorTest.clean();
        from.meta.put("driverset.linkage.0", "cn=SetGCVs,cn=Library,cn=dvs,o=system#0#14");
        DriverSet to = copy(from);
        to.meta.put("driverset.linkage.1", "cn=OtherGCVs,cn=Library,cn=dvs,o=system#1#14");

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> linkage = of(diff, Kind.DRIVERSET_LINKAGE);
        assertEquals(diff.text(), 1, linkage.size());
        assertEquals("gcv", linkage.get(0).what);
        assertNull(linkage.get(0).driver);
    }

    // ---- affectedDrivers() ----

    @Test
    public void affectedDriversForLibraryChangeIsOnlyLinkingDrivers() throws IOException {
        DriverSet from = ValidatorTest.clean();
        from.library.policies.add(new Policy("lib-shared", Scope.LIBRARY, null, xml(RULE1)));
        from.driver("AD").links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-shared", 5));
        Driver other = new Driver("Other");
        other.shimClass = "com.example.OtherShim";
        from.drivers.add(other);

        DriverSet to = copy(from);
        findPolicy(to, "library/lib-shared").content = xml(RULE2);

        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(diff.text(), Arrays.asList("AD"), diff.affectedDrivers());
    }

    @Test
    public void affectedDriversForDriverSetGcvChangeIsAllDrivers() throws IOException {
        DriverSet from = ValidatorTest.clean();
        Driver other = new Driver("Other");
        other.shimClass = "com.example.OtherShim";
        from.drivers.add(other);
        DriverSet to = copy(from);
        to.configValues = xml(GCVS2);

        ModelDiff diff = ModelDiff.of(from, to);
        assertEquals(new TreeSet<>(Arrays.asList("AD", "Other")), new TreeSet<>(diff.affectedDrivers()));
    }

    // ---- JSON smoke ----

    @Test
    public void jsonRendersEmptyAndNonEmpty() throws IOException {
        DriverSet from = ValidatorTest.clean();
        DriverSet to = copy(from);
        assertTrue(ModelDiff.of(from, to).json().startsWith("{\"empty\":true,"));

        to.driver("AD").shimClass = "changed";
        String json = ModelDiff.of(from, to).json();
        assertTrue(json, json.startsWith("{\"empty\":false,"));
        assertTrue(json, json.contains("\"kind\":\"DRIVER_SETTING\""));
        assertTrue(json, json.contains("\"affectedDrivers\":[\"AD\"]"));
    }

    // ---- real data (guarded) ----

    private static Path rfiExport() {
        return Path.of(System.getProperty("user.home"), "tmp", "RFI-DriverSet.xml");
    }

    @Test
    public void realDataDiffAgainstItselfIsEmpty() {
        Path rfi = rfiExport();
        assumeTrue("needs the local RFI-DriverSet.xml export", Files.exists(rfi));

        DriverSet a = ExportReader.read(rfi);
        DriverSet b = ExportReader.read(rfi);
        ModelDiff diff = ModelDiff.of(a, b);
        assertTrue(diff.text(), diff.isEmpty());
    }

    @Test
    public void realDataDetectsOneLibraryPolicyChange() {
        Path rfi = rfiExport();
        assumeTrue("needs the local RFI-DriverSet.xml export", Files.exists(rfi));

        DriverSet from = ExportReader.read(rfi);
        DriverSet to = ExportReader.read(rfi);

        // pick a Library policy that at least one driver actually links, so affectedDrivers() is non-trivial
        Policy target = null;
        for (Policy p : from.library.policies) {
            if (linkingDrivers(from, p.path()).isEmpty()) {
                continue;
            }
            target = p;
            break;
        }
        assumeTrue("needs a linked Library policy in RFI-DriverSet.xml", target != null);

        Policy targetInTo = findPolicy(to, target.path());
        targetInTo.content = modified(targetInTo.content);

        ModelDiff diff = ModelDiff.of(from, to);
        List<Change> changed = of(diff, Kind.ARTIFACT_CHANGED);
        assertEquals(diff.text(), 1, changed.size());
        assertEquals(target.path(), changed.get(0).path);

        List<String> affected = diff.affectedDrivers();
        assertFalse(diff.text(), affected.isEmpty());
        assertEquals(linkingDrivers(from, target.path()), new TreeSet<>(affected));
    }

    private static Set<String> linkingDrivers(DriverSet ds, String path) {
        Set<String> out = new TreeSet<>();
        for (Driver d : ds.drivers) {
            for (PolicyLink l : d.links) {
                if (l.ref.equals(path)) {
                    out.add(d.name);
                }
            }
        }
        return out;
    }

    /** Re-parses {@code content}'s canonical XML with an extra comment inserted before the closing tag. */
    private static Element modified(Element content) {
        String xmlText = CanonicalXml.serialize(content);
        String tag = content.getTagName();
        String closeTag = "</" + tag + ">";
        int idx = xmlText.lastIndexOf(closeTag);
        String modifiedXml;
        if (idx >= 0) {
            modifiedXml = xmlText.substring(0, idx) + "<!--model-diff-test-modification-->\n" + xmlText.substring(idx);
        } else {
            // self-closing root: open it up with the comment as its only child
            modifiedXml = xmlText.replaceFirst("/>\\s*$", ">") + "<!--model-diff-test-modification--></" + tag + ">\n";
        }
        return CanonicalXml.parse(modifiedXml).getDocumentElement();
    }
}
