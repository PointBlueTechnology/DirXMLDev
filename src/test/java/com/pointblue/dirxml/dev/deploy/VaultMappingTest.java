package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.source.LdifReader;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.LdifDriverSource;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The model → vault mapping is the inverse of the live reader: a model written
 * through {@link VaultMapping} as LDAP entries and read back with
 * {@link LdifReader#fromEntries} must be the same model (byte-equal as-code).
 */
public class VaultMappingTest {

    private static final String DS = "cn=dvs,o=system";

    @Test
    public void dnsAndClasses() {
        assertEquals("cn=X,cn=Library," + DS, VaultMapping.pathDn(DS, "library/X"));
        assertEquals("cn=X,cn=AD,cn=dvs,o=system", VaultMapping.pathDn(DS, "drivers/AD/X"));
        assertEquals("cn=X,cn=Subscriber,cn=AD,cn=dvs,o=system", VaultMapping.pathDn(DS, "drivers/AD/subscriber/X"));
        assertEquals("cn=a\\,b\\+c,cn=Publisher,cn=D\\=1,cn=dvs,o=system", VaultMapping.pathDn(DS, "drivers/D=1/publisher/a,b+c"));
        Policy script = new Policy("p", Scope.DRIVER, "AD", ValidatorTest.xml("<policy/>"));
        Policy xslt = new Policy("x", Scope.DRIVER, "AD", ValidatorTest.xml(
            "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\"/>"));
        Resource table = new Resource("t", Scope.LIBRARY, null, Resource.MAPPING_TABLE);
        Resource gcv = new Resource("g", Scope.LIBRARY, null, Resource.GCV_DEF);
        assertEquals("DirXML-Rule", VaultMapping.objectClass(script));
        assertEquals("DirXML-StyleSheet", VaultMapping.objectClass(xslt));
        assertEquals("DirXML-Resource", VaultMapping.objectClass(table));
        assertEquals("DirXML-GlobalConfigDef", VaultMapping.objectClass(gcv));
        assertEquals("XmlData", VaultMapping.contentAttribute(script));
        assertEquals("DirXML-Data", VaultMapping.contentAttribute(table));
        assertEquals("DirXML-ConfigValues", VaultMapping.contentAttribute(gcv));
        String c1 = VaultMapping.customizedChecksum("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(c1, VaultMapping.customizedChecksum("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(c1, VaultMapping.customizedChecksum("abd".getBytes(StandardCharsets.UTF_8)));
        assertTrue(Long.parseLong(c1) > 0);
    }

    @Test
    public void linkageValues() {
        DriverSet ds = model();
        Driver ad = ds.driver("AD");
        List<String> v = VaultMapping.policiesValues(DS, ad);
        assertEquals(List.of(
            "cn=smp,cn=AD,cn=dvs,o=system#0#0",
            "cn=lib-shared,cn=Library,cn=dvs,o=system#0#4",
            "cn=sub-ctp,cn=Subscriber,cn=AD,cn=dvs,o=system#0#10",
            "cn=PKG-GCVs,cn=AD,cn=dvs,o=system#0#14"), v);
        assertEquals(List.of("cn=SetGCVs,cn=Library,cn=dvs,o=system#0#14"), VaultMapping.driverSetPoliciesValues(DS, ds));
        assertEquals(List.of("AD"), VaultMapping.linkingDrivers(ds, "library/lib-shared"));
    }

    @Test
    public void roundTripThroughTheLiveReader() throws IOException {
        DriverSet ds = model();
        List<LdifDriverSource.Entry> entries = entries(ds);
        DriverSet back = LdifReader.fromEntries(entries, "mapping-test");
        assertEquals(asCode(ds), asCode(back));
    }

    // ---- fixture: the model and its vault entries as the mapping defines them ----

    static DriverSet model() {
        DriverSet ds = ValidatorTest.clean();
        ds.library.policies.add(new Policy("lib-shared", Scope.LIBRARY, null, ValidatorTest.xml(
            "<policy><rule><description>shared</description><conditions/><actions/></rule></policy>")));
        Resource setGcv = new Resource("SetGCVs", Scope.LIBRARY, null, Resource.GCV_DEF);
        setGcv.content = ValidatorTest.xml("<configuration-values><definitions><definition display-name=\"t\" name=\"set.tree\" type=\"string\"><value>t1</value></definition></definitions></configuration-values>");
        ds.library.resources.add(setGcv);
        ds.meta.put("driverset.linkage.0", "cn=SetGCVs,cn=Library,cn=dvs,o=system#0#14");
        Driver ad = ds.driver("AD");
        Resource pkg = new Resource("PKG-GCVs", Scope.DRIVER, "AD", Resource.GCV_DEF);
        pkg.content = ValidatorTest.xml("<configuration-values><definitions/></configuration-values>");
        ad.resources.add(pkg);
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "library/lib-shared", 0));
        ad.links.add(new PolicyLink(PolicySet.GCV, "drivers/AD/PKG-GCVs", 0));
        ad.shimAuthServer = "ldap://app";
        ad.shimAuthId = "svc";
        return ds;
    }

    static List<LdifDriverSource.Entry> entries(DriverSet ds) {
        List<LdifDriverSource.Entry> out = new ArrayList<>();
        Map<String, List<String>> dsAttrs = strings(VaultMapping.driverSetAttributes(ds));
        dsAttrs.put("objectClass", List.of("Top", "DirXML-DriverSet"));
        dsAttrs.put("DirXML-Policies", VaultMapping.driverSetPoliciesValues(DS, ds));
        out.add(new LdifDriverSource.Entry(DS, lower(dsAttrs)));
        out.add(new LdifDriverSource.Entry(VaultMapping.libraryDn(DS), Map.of("objectclass", List.of("Top", "DirXML-Library"))));
        for (Artifact a : ds.library.artifacts()) {
            out.add(artifact(a));
        }
        for (Driver d : ds.drivers) {
            Map<String, List<String>> da = strings(VaultMapping.driverAttributes(d));
            da.put("objectClass", List.of("Top", "DirXML-Driver"));
            da.put("DirXML-Policies", VaultMapping.policiesValues(DS, d));
            out.add(new LdifDriverSource.Entry(VaultMapping.driverDn(DS, d.name), lower(da)));
            out.add(new LdifDriverSource.Entry(VaultMapping.channelDn(DS, d.name, Scope.SUBSCRIBER), Map.of("objectclass", List.of("Top", "DirXML-Subscriber"))));
            out.add(new LdifDriverSource.Entry(VaultMapping.channelDn(DS, d.name, Scope.PUBLISHER), Map.of("objectclass", List.of("Top", "DirXML-Publisher"))));
            for (Artifact a : d.artifacts()) {
                out.add(artifact(a));
            }
        }
        return out;
    }

    private static LdifDriverSource.Entry artifact(Artifact a) {
        Map<String, List<String>> attrs = strings(VaultMapping.attributes(a));
        attrs.put("objectClass", List.of("Top", VaultMapping.objectClass(a)));
        return new LdifDriverSource.Entry(VaultMapping.artifactDn(DS, a), lower(attrs));
    }

    private static Map<String, List<String>> lower(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.toLowerCase(), v));
        return out;
    }

    private static Map<String, List<String>> strings(Map<String, List<byte[]>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<byte[]>> e : in.entrySet()) {
            List<String> vs = new ArrayList<>();
            for (byte[] b : e.getValue()) {
                vs.add(new String(b, StandardCharsets.UTF_8));
            }
            out.put(e.getKey(), vs);
        }
        return out;
    }

    /** Every as-code file of a model, minus reader bookkeeping meta, as one string. */
    static String asCode(DriverSet ds) throws IOException {
        Path dir = Files.createTempDirectory("idm-map");
        AsCodeWriter.write(ds, dir);
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                sb.append("== ").append(dir.relativize(p)).append('\n');
                for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n")) {
                    if (line.contains("<meta key=\"")) {
                        continue;   // the live reader records dn / objectClass / content-type meta
                    }
                    sb.append(line).append('\n');
                }
            }
        }
        // an artifact element left with no meta children is written self-closed
        return sb.toString().replaceAll("(<artifact [^>]*)>\\n\\s*</artifact>", "$1/>");
    }

    /** A new driver is created with its icon — DirXML-DriverImage, the bytes as they are. */
    @Test
    public void driverAttributesCarryTheIcon() {
        Driver d = model().driver("AD");
        org.junit.Assert.assertFalse(VaultMapping.driverAttributes(d).containsKey(VaultMapping.DRIVER_IMAGE));
        d.icon = com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.TINY_GIF;
        d.iconExtension = "gif";
        Map<String, List<byte[]>> m = VaultMapping.driverAttributes(d);
        org.junit.Assert.assertArrayEquals(com.pointblue.dirxml.dev.ascode.AsCodeRoundTripTest.TINY_GIF,
            m.get(VaultMapping.DRIVER_IMAGE).get(0));
    }
}
