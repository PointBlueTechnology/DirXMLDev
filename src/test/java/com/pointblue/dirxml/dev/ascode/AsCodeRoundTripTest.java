package com.pointblue.dirxml.dev.ascode;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * The as-code round-trip contract: write(model) is idempotent byte-for-byte and
 * read(write(model)) preserves every name, path, kind, link and content.
 */
public class AsCodeRoundTripTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Element xml(String s) {
        return CanonicalXml.parse(s).getDocumentElement();
    }

    /** A small but representative driver set. */
    static DriverSet sample() {
        DriverSet ds = new DriverSet("driverset1");
        ds.dn = "cn=driverset1,o=system";
        ds.meta.put("source.file", "sample.xml");
        ds.configValues = xml("<configuration-values><definitions>"
            + "<definition name=\"idv.dit.data\" display-name=\"Data\" type=\"string\"><value>data</value></definition>"
            + "</definitions></configuration-values>");

        // library: a shared policy with significant whitespace, a mapping table, an ECMAScript
        Policy shared = new Policy("lib-common-event", Scope.LIBRARY, null, xml(
            "<policy><rule><description>keep spaces</description><conditions/><actions>"
            + "<do-set-dest-attr-value name=\"Title\"><arg-value type=\"string\">"
            + "<token-text>  Sr.  Engineer </token-text></arg-value></do-set-dest-attr-value>"
            + "</actions></rule></policy>"));
        shared.meta.put("pkg.guid", "ABC-123");
        ds.library.policies.add(shared);
        Resource table = new Resource("LocCodeMap", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        table.content = xml("<mapping-table><col-def name=\"LocCode\" type=\"nocase\"/><col-def name=\"Domain\"/>"
            + "<row><col>1</col><col>OU=a,DC=x</col></row></mapping-table>");
        ds.library.resources.add(table);
        Resource js = new Resource("es-misc", Scope.LIBRARY, null, Resource.ECMASCRIPT);
        js.text = "function f(x) {\n  return x + 1;\n}\n";
        ds.library.resources.add(js);

        // a driver with an awkward name, config blobs, all scopes, links across sets
        Driver d = new Driver("AD: Prod/Users");
        d.dn = "cn=AD: Prod/Users,cn=driverset1,o=system";
        d.shimClass = "com.novell.nds.dirxml.driver.ad.ADDriverShim";
        d.shimAuthServer = "ad.example.com";
        d.shimAuthId = "svc-idm";
        d.meta.put("pkg.version", "4.1.2");
        d.config.put(Driver.DRIVER_FILTER, xml("<filter><filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\">"
            + "<filter-attr attr-name=\"Surname\" publisher=\"sync\" subscriber=\"sync\"/></filter-class></filter>"));
        d.config.put(Driver.CONFIG_VALUES, xml("<configuration-values><definitions>"
            + "<definition name=\"drv.name\" display-name=\"Name\" type=\"string\"><value>AD</value></definition>"
            + "</definitions></configuration-values>"));
        d.policies.add(new Policy("sch_Map", Scope.DRIVER, d.name, xml(
            "<attr-name-map><attribute><nds-name>Surname</nds-name><app-name>sn</app-name></attribute></attr-name-map>")));
        d.subscriber.policies.add(new Policy("sub-etp \"Scoping\"", Scope.SUBSCRIBER, d.name, xml(
            "<policy><rule><description>scope</description><conditions><and><if-class-name op=\"equal\">User</if-class-name></and></conditions>"
            + "<actions><do-veto/></actions></rule></policy>")));
        d.publisher.policies.add(new Policy("pub-otp_Transform", Scope.PUBLISHER, d.name, xml(
            "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
            + "<xsl:template match=\"/\"><xsl:copy-of select=\".\"/></xsl:template></xsl:stylesheet>")));
        d.links.add(new PolicyLink(PolicySet.SCHEMA_MAPPING, "drivers/AD: Prod/Users/sch_Map", 0));
        d.links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-common-event", 1));
        d.links.add(new PolicyLink(PolicySet.SUB_EVENT, "drivers/AD: Prod/Users/subscriber/sub-etp \"Scoping\"", 0));
        d.links.add(new PolicyLink(PolicySet.OUTPUT, "drivers/AD: Prod/Users/publisher/pub-otp_Transform", 0));
        d.links.add(new PolicyLink(PolicySet.ECMASCRIPT, "library/es-misc", 0));
        ds.drivers.add(d);
        return ds;
    }

    @Test
    public void writeIsIdempotentAndReadPreservesEverything() throws Exception {
        DriverSet ds = sample();
        Path a = tmp.newFolder("a").toPath();
        Path b = tmp.newFolder("b").toPath();

        AsCodeWriter.write(ds, a);
        DriverSet back = AsCodeReader.read(a);
        AsCodeWriter.write(back, b);

        // idempotent: the re-written tree is byte-identical
        assertEquals(snapshot(a), snapshot(b));

        // structure preserved
        assertEquals(ds.name, back.name);
        assertEquals(ds.dn, back.dn);
        assertEquals("sample.xml", back.meta.get("source.file"));
        assertNotNull(back.configValues);
        assertEquals(ds.index().keySet(), back.index().keySet());
        assertTrue(back.unresolvedLinks().isEmpty());

        Driver d = back.driver("AD: Prod/Users");
        assertNotNull(d);
        assertEquals("com.novell.nds.dirxml.driver.ad.ADDriverShim", d.shimClass);
        assertEquals("svc-idm", d.shimAuthId);
        assertEquals("4.1.2", d.meta.get("pkg.version"));
        assertTrue(d.config.containsKey(Driver.DRIVER_FILTER));
        assertTrue(d.config.containsKey(Driver.CONFIG_VALUES));
        assertEquals(1, d.policies.size());
        assertEquals(1, d.subscriber.policies.size());
        assertEquals(1, d.publisher.policies.size());
        assertEquals(Policy.Kind.SCHEMA_MAP, d.policies.get(0).policyKind());
        assertEquals(Policy.Kind.XSLT, d.publisher.policies.get(0).policyKind());

        // links: same count, same order within a set
        assertEquals(5, d.links.size());
        List<PolicyLink> sub = d.links(PolicySet.SUB_EVENT);
        assertEquals("drivers/AD: Prod/Users/subscriber/sub-etp \"Scoping\"", sub.get(0).ref);
        assertEquals("library/lib-common-event", sub.get(1).ref);

        // content: significant whitespace in token-text survives; resources typed correctly
        Policy shared = (Policy) back.resolve("library/lib-common-event");
        assertEquals("  Sr.  Engineer ", shared.content.getElementsByTagName("token-text").item(0).getTextContent());
        assertEquals("ABC-123", shared.meta.get("pkg.guid"));
        Resource table = (Resource) back.resolve("library/LocCodeMap");
        assertTrue(table.isMappingTable());
        assertEquals("mapping-table", table.content.getLocalName() != null ? table.content.getLocalName() : table.content.getNodeName());
        Resource js = (Resource) back.resolve("library/es-misc");
        assertTrue(js.isEcmaScript() && js.isText());
        assertEquals("function f(x) {\n  return x + 1;\n}\n", js.text);
    }

    @Test
    public void layoutMatchesTheSpec() throws Exception {
        Path a = tmp.newFolder("layout").toPath();
        AsCodeWriter.write(sample(), a);
        assertTrue(Files.exists(a.resolve("driverset.xml")));
        assertTrue(Files.exists(a.resolve("config-values.xml")));
        assertTrue(Files.exists(a.resolve("library/library.xml")));
        assertTrue(Files.exists(a.resolve("library/lib-common-event.policy.xml")));
        assertTrue(Files.exists(a.resolve("library/LocCodeMap.mapping-table.xml")));
        assertTrue(Files.exists(a.resolve("library/es-misc.js")));
        // unsafe characters are sanitized in file/dir names; the manifest keeps the real name
        Path drv = a.resolve("drivers/AD_ Prod_Users");
        assertTrue("driver dir sanitized: " + drv, Files.isDirectory(drv));
        assertTrue(Files.exists(drv.resolve("driver.xml")));
        assertTrue(Files.exists(drv.resolve("driver-filter.xml")));
        assertTrue(Files.exists(drv.resolve("config-values.xml")));
        assertTrue(Files.exists(drv.resolve("sch_Map.policy.xml")));
        assertTrue(Files.exists(drv.resolve("subscriber/sub-etp _Scoping_.policy.xml")));
        assertTrue(Files.exists(drv.resolve("publisher/pub-otp_Transform.policy.xml")));
        String manifest = Files.readString(drv.resolve("driver.xml"));
        assertTrue(manifest.contains("name=\"AD: Prod/Users\""));
        assertTrue(manifest.contains("<set key=\"subscriber-event\">"));
        // content files are pure content: a policy file starts with the policy, not a wrapper
        String pol = Files.readString(drv.resolve("sch_Map.policy.xml"));
        assertTrue(pol.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<attr-name-map"));
    }

    /** relative path -> file bytes (as string), for whole-tree comparison. */
    static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    out.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p));
                }
            }
        }
        return out;
    }
}
