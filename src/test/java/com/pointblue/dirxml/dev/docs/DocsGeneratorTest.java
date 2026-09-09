package com.pointblue.dirxml.dev.docs;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.edit.Packages;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The {@code docs} package: {@link DocsGenerator} against a synthetic driver set
 * (built on {@link ValidatorTest#clean()}), {@link ConditionSummary} in
 * isolation, and — guarded on a local file — against a real export.
 */
public class DocsGeneratorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /**
     * {@link ValidatorTest#clean()} plus: a rule with conditions, a disabled
     * rule, an XSLT policy, a Library policy linked, a mapping table used by a
     * token-map, a password-ref GCV, packaged+customized meta, and a second,
     * otherwise-empty driver (to exercise {@code --driver} filtering).
     */
    private static DriverSet buildDs() {
        DriverSet ds = ValidatorTest.clean();
        Driver ad = ds.driver("AD");

        Policy libPolicy = new Policy("lib-CommonRule", Scope.LIBRARY, null, xml(
            "<policy><rule><description>lib rule</description><conditions/><actions><do-veto/></actions></rule></policy>"));
        ds.library.policies.add(libPolicy);
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-CommonRule", ad.links.size()));

        Policy condRule = new Policy("sub-condrule", Scope.SUBSCRIBER, "AD", xml(
            "<policy>"
                + "<rule><description>cond rule</description>"
                + "<conditions><and><if-class-name op=\"equal\">User</if-class-name>"
                + "<if-op-attr name=\"Title\" op=\"changing\"/></and></conditions>"
                + "<actions><do-trace-message><arg-string><token-text>hi</token-text></arg-string></do-trace-message>"
                + "<token-map table=\"CodeMap\"><arg-value><token-attr name=\"Code\"/></arg-value></token-map>"
                + "</actions></rule>"
                + "<rule disabled=\"true\"><description>disabled rule</description><conditions/>"
                + "<actions><do-veto/></actions></rule>"
                + "</policy>"));
        ad.subscriber.policies.add(condRule);
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "drivers/AD/subscriber/sub-condrule", ad.links.size()));

        Policy xslt = new Policy("pub-xform", Scope.PUBLISHER, "AD", xml(
            "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
                + "<xsl:template match=\"/\"><out/></xsl:template></xsl:stylesheet>"));
        ad.publisher.policies.add(xslt);
        ad.links.add(new PolicyLink(PolicySet.PUB_EVENT, "drivers/AD/publisher/pub-xform", ad.links.size()));

        ad.config.put(Driver.CONFIG_VALUES, xml(
            "<configuration-values><definitions>"
                + "<definition display-name=\"Users\" name=\"drv.users\" type=\"string\"><value>users</value></definition>"
                + "<definition display-name=\"Password\" name=\"drv.secret\" type=\"password-ref\"><value>svc-pw</value></definition>"
                + "</definitions></configuration-values>"));

        Policy smp = ad.policies.get(0); // "smp" from ValidatorTest.clean()
        smp.meta.put("package-id", "PKG-1");
        smp.meta.put("package-version", "1.0.0");
        smp.meta.put(Packages.CUSTOMIZED_KEY, "true");

        Driver ldap = new Driver("LDAP");
        ldap.dn = "cn=LDAP,cn=dvs,o=system";
        ldap.shimClass = "com.example.LdapShim";
        ds.drivers.add(ldap);

        return ds;
    }

    private Path writeTree(DriverSet ds) throws IOException {
        Path tree = tmp.newFolder("tree-" + System.nanoTime()).toPath();
        AsCodeWriter.write(ds, tree);
        return tree;
    }

    // ------------------------------------------------------------------
    // generation against the synthetic set
    // ------------------------------------------------------------------

    @Test
    public void generatesExpectedFilesAndContent() throws Exception {
        Path tree = writeTree(buildDs());
        Path out = tmp.newFolder("out1").toPath();
        DocsGenerator.generate(tree, out, null, null, "md");

        assertTrue(Files.exists(out.resolve("README.md")));
        assertTrue(Files.exists(out.resolve("drivers").resolve("AD.md")));
        assertTrue(Files.exists(out.resolve("drivers").resolve("LDAP.md")));
        assertTrue(Files.exists(out.resolve("library.md")));
        assertFalse(Files.exists(out.resolve("changes.md")));

        String readme = Files.readString(out.resolve("README.md"));
        assertTrue(readme, readme.contains("AD"));
        assertTrue(readme, readme.contains("LDAP"));
        assertTrue(readme, readme.contains("drivers/AD.md"));

        String driverPage = Files.readString(out.resolve("drivers").resolve("AD.md"));
        // filter table: class row bold, attribute row present
        assertTrue(driverPage, driverPage.contains("**User**"));
        assertTrue(driverPage, driverPage.contains("Surname"));
        // chain in execution order: subscriber-event before subscriber-command
        int eventIdx = driverPage.indexOf("### subscriber-event");
        int commandIdx = driverPage.indexOf("### subscriber-command");
        assertTrue(driverPage, eventIdx >= 0 && commandIdx >= 0 && eventIdx < commandIdx);
        // rule line with condition summary and first action
        assertTrue(driverPage, driverPage.contains("**cond rule** — if class User and Title changing → do-trace-message"));
        assertTrue(driverPage, driverPage.contains("**disabled rule**") && driverPage.contains("(disabled)"));
        // masked password: the GCV table shows the value as "(password)", never the target name
        assertTrue(driverPage, driverPage.contains("| drv.secret | (password) |"));
        // mapping table used, rendered as a table with its row values
        assertTrue(driverPage, driverPage.contains("CodeMap"));
        assertTrue(driverPage, driverPage.contains("ou=a"));
        // library policy linked
        assertTrue(driverPage, driverPage.contains("lib-CommonRule"));

        String library = Files.readString(out.resolve("library.md"));
        assertTrue(library, library.contains("CodeMap"));
        assertTrue(library, library.contains("ou=a"));

        String ldapPage = Files.readString(out.resolve("drivers").resolve("LDAP.md"));
        assertTrue(ldapPage.contains("com.example.LdapShim"));
    }

    @Test
    public void generationIsDeterministic() throws Exception {
        DriverSet ds = buildDs();
        Path tree = writeTree(ds);
        Path out1 = tmp.newFolder("outA").toPath();
        Path out2 = tmp.newFolder("outB").toPath();
        DocsGenerator.generate(tree, out1, null, null, "md");
        DocsGenerator.generate(tree, out2, null, null, "md");

        for (String rel : List.of("README.md", "library.md", "drivers/AD.md", "drivers/LDAP.md")) {
            byte[] a = Files.readAllBytes(out1.resolve(rel));
            byte[] b = Files.readAllBytes(out2.resolve(rel));
            assertTrue(rel, Arrays.equals(a, b));
        }
    }

    @Test
    public void driverFilterLimitsDriverPages() throws Exception {
        Path tree = writeTree(buildDs());
        Path out = tmp.newFolder("out-filtered").toPath();
        DocsGenerator.generate(tree, out, List.of("AD"), null, "md");

        assertTrue(Files.exists(out.resolve("drivers").resolve("AD.md")));
        assertFalse(Files.exists(out.resolve("drivers").resolve("LDAP.md")));
        // README still lists both drivers
        String readme = Files.readString(out.resolve("README.md"));
        assertTrue(readme.contains("LDAP"));
    }

    @Test
    public void htmlFormatProducesIndexWithHeadings() throws Exception {
        Path tree = writeTree(buildDs());
        Path out = tmp.newFolder("out-html").toPath();
        DocsGenerator.generate(tree, out, null, null, "html");

        Path index = out.resolve("index.html");
        assertTrue(Files.exists(index));
        String html = Files.readString(index);
        assertTrue(html.contains("<h1"));
        assertTrue(html.contains("<h2"));
        assertTrue(html.contains("dvs"));
        assertTrue(html.contains("cond rule"));
        assertTrue(html.contains("(password)"));
    }

    // ------------------------------------------------------------------
    // changes.md
    // ------------------------------------------------------------------

    @Test
    public void changesMdReportsAChangeSinceACommit() throws Exception {
        Path tree = tmp.newFolder("changes-tree").toPath();
        DriverSet ds = ValidatorTest.clean();
        AsCodeWriter.write(ds, tree);
        git(tree, "init", "-q");
        git(tree, "add", "-A");
        git(tree, "-c", "user.email=test@test.local", "-c", "user.name=Test", "commit", "-q", "-m", "initial");
        String firstCommit = gitOut(tree, "rev-parse", "HEAD").trim();

        // change a policy's content and rewrite
        Driver ad = ds.driver("AD");
        ad.subscriber.policies.get(0).content = xml(
            "<policy><rule><description>r1 changed</description><conditions/><actions>"
                + "<do-veto/></actions></rule></policy>");
        AsCodeWriter.write(ds, tree);
        git(tree, "add", "-A");
        git(tree, "-c", "user.email=test@test.local", "-c", "user.name=Test", "commit", "-q", "-m", "change rule");

        Path out = tmp.newFolder("changes-out").toPath();
        DocsGenerator.generate(tree, out, null, firstCommit, "md");

        assertTrue(Files.exists(out.resolve("changes.md")));
        String changes = Files.readString(out.resolve("changes.md"));
        assertTrue(changes, changes.contains(firstCommit));
        assertTrue(changes, changes.contains("sub-ctp"));
        assertTrue(changes, changes.contains("AD"));
        assertTrue(changes, changes.contains("```diff"));
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> full = new java.util.ArrayList<>();
        full.add("git");
        full.add("-C");
        full.add(dir.toAbsolutePath().toString());
        full.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + outText);
        }
    }

    private static String gitOut(Path dir, String... args) throws IOException, InterruptedException {
        List<String> full = new java.util.ArrayList<>();
        full.add("git");
        full.add("-C");
        full.add(dir.toAbsolutePath().toString());
        full.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(full).redirectErrorStream(true).start();
        String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + outText);
        }
        return outText;
    }

    // ------------------------------------------------------------------
    // condition summary unit cases
    // ------------------------------------------------------------------

    @Test
    public void conditionSummaryGrammar() {
        assertSummary("always", "<conditions/>");
        assertSummary("class User",
            "<conditions><and><if-class-name op=\"equal\">User</if-class-name></and></conditions>");
        assertSummary("not class User",
            "<conditions><and><if-class-name op=\"not-equal\">User</if-class-name></and></conditions>");
        assertSummary("Title changing",
            "<conditions><and><if-op-attr name=\"Title\" op=\"changing\"/></and></conditions>");
        assertSummary("X = v",
            "<conditions><and><if-op-attr name=\"X\" op=\"equal\">v</if-op-attr></and></conditions>");
        assertSummary("op modify",
            "<conditions><and><if-operation op=\"equal\">modify</if-operation></and></conditions>");
        assertSummary("gcv g = v",
            "<conditions><and><if-global-variable name=\"g\" op=\"equal\">v</if-global-variable></and></conditions>");
        assertSummary("var x = v",
            "<conditions><and><if-local-variable name=\"x\" op=\"equal\">v</if-local-variable></and></conditions>");
        assertSummary("xpath(true())",
            "<conditions><and><if-xpath op=\"true\">true()</if-xpath></and></conditions>");
        assertSummary("class User and Title changing",
            "<conditions><and><if-class-name op=\"equal\">User</if-class-name>"
                + "<if-op-attr name=\"Title\" op=\"changing\"/></and></conditions>");
        assertSummary("class User and (Title changing or Surname changing)",
            "<conditions><and><if-class-name op=\"equal\">User</if-class-name>"
                + "<or><if-op-attr name=\"Title\" op=\"changing\"/><if-op-attr name=\"Surname\" op=\"changing\"/></or>"
                + "</and></conditions>");
        assertSummary("frobnicate",
            "<conditions><and><if-frobnicate/></and></conditions>");

        // xpath abbreviation to 40 chars
        String longExpr = "a".repeat(60);
        Element c = xml("<conditions><and><if-xpath op=\"true\">" + longExpr + "</if-xpath></and></conditions>");
        String summary = ConditionSummary.summarize(c);
        assertTrue(summary, summary.startsWith("xpath("));
        assertTrue(summary, summary.length() < ("xpath(" + longExpr + ")").length());
    }

    private static void assertSummary(String expected, String conditionsXml) {
        Element conditions = xml(conditionsXml);
        assertEquals(expected, ConditionSummary.summarize(conditions));
    }

    // ------------------------------------------------------------------
    // guarded real-data test
    // ------------------------------------------------------------------

    @Test
    public void realRfiDriverSetGeneratesDocs() throws Exception {
        Path f = Path.of(System.getProperty("user.home"), "tmp", "RFI-DriverSet.xml");
        assumeTrue("needs the local RFI-DriverSet.xml export", Files.exists(f));

        DriverSet ds = ExportReader.read(f);
        Path tree = tmp.newFolder("rfi-tree").toPath();
        AsCodeWriter.write(ds, tree);
        Path out = tmp.newFolder("rfi-out").toPath();
        DocsGenerator.generate(tree, out, null, null, "md");

        long driverPages;
        try (var s = Files.list(out.resolve("drivers"))) {
            driverPages = s.filter(p -> p.toString().endsWith(".md")).count();
        }
        assertEquals(19, driverPages);

        String library = Files.readString(out.resolve("library.md"));
        assertTrue(library.contains("LocCodeMap"));
    }
}
