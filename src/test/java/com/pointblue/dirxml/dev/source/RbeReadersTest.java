package com.pointblue.dirxml.dev.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.EntitlementPolicy;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.LdifDriverSource.Entry;

/** Role-based entitlement policies (DirXML-SharedProfile) through the LDIF reader, the tree, the export and validate. */
public class RbeReadersTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String DS = "cn=driverset1,o=system";
    private static final String SET = "cn=Entitlement Policies," + DS;
    private static final String POL = "cn=Contractors," + SET;
    private static final String ENT = "cn=UserAccount,cn=AD," + DS;
    private static final String CRITERIA = "<selection-criterion><group><group-op>and</group-op><row><attribute>employeeType</attribute><operation>AS.Op.isEqual</operation><value>contractor</value><row-op>and</row-op></row></group></selection-criterion>";
    private static final String DISPLAY = "<Drivers><Driver><name>AD</name><value>cn=AD," + DS + "</value><description/><Entitlement><name>UserAccount</name><value>" + ENT + "</value><description/><type>NoValues</type><EntitlementMulti>false</EntitlementMulti><EntitlementPackedValues>PP</EntitlementPackedValues><EntitlementDN>" + ENT + "</EntitlementDN><hasInterpretiveVariables>false</hasInterpretiveVariables><conflict-resolution>priority</conflict-resolution></Entitlement></Driver></Drivers>";

    private static Entry entry(String dn, String oc, String... kv) {
        Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
        attrs.put("objectclass", List.of("Top", oc));
        for (int i = 0; i < kv.length; i += 2) {
            attrs.computeIfAbsent(kv[i].toLowerCase(), k -> new java.util.ArrayList<>()).add(kv[i + 1]);
        }
        return new Entry(dn, attrs);
    }

    private static List<Entry> entries() {
        return List.of(
            entry(DS, "DirXML-DriverSet"),
            entry("cn=AD," + DS, "DirXML-Driver", "DirXML-JavaModule", "com.novell.nds.dirxml.driver.ad.ADDriverShim"),
            entry(ENT, "DirXML-Entitlement", "XmlData", "<entitlement conflict-resolution=\"priority\" display-name=\"UserAccount\"><values multi-valued=\"false\"/></entitlement>"),
            entry("cn=Entitlements Service," + DS, "DirXML-Driver", "DirXML-JavaModule", EntitlementPolicy.SERVICE_SHIM_CLASS),
            entry(SET, "DirXML-SharedProfileSet", "DirXML-SPPriority", POL + "#0#0"),
            entry(POL, "DirXML-SharedProfile", "Description", "contractors get an AD account",
                "memberQuery", "ldap:///ou=users,o=data??sub?(employeeType=contractor)?x-sparse",
                "dgIdentity", "cn=admin,ou=sa,o=system",
                "DirXML-SPFilterXML", CRITERIA,
                "Member", "cn=bob,ou=users,o=data",
                "excludedMember", "cn=eve,ou=users,o=data",
                "DirXML-EntitlementRef", ENT + "#0#<ref/>",
                "DirXML-SPDisplayEntitlements", DISPLAY));
    }

    @Test
    public void ldifPoliciesLandOnTheSetAndRoundTripTheTree() throws Exception {
        DriverSet ds = LdifReader.fromEntries(entries(), "test", Map.of());
        assertEquals(1, ds.rbePolicies.size());
        EntitlementPolicy p = ds.rbePolicies.get(0);
        assertEquals("Contractors", p.name);
        assertEquals(Integer.valueOf(0), p.priority);
        assertEquals("contractors get an AD account", p.description);
        assertEquals("ldap:///ou=users,o=data??sub?(employeeType=contractor)?x-sparse", p.memberQuery);
        assertEquals("cn=admin,ou=sa,o=system", p.identity);
        assertEquals(List.of("cn=bob,ou=users,o=data"), p.members);
        assertEquals(List.of("cn=eve,ou=users,o=data"), p.excludedMembers);
        assertEquals(List.of(ENT), p.entitlementDns());
        assertNotNull(p.criteria);
        assertNotNull(p.displayEntitlements);
        assertEquals("Entitlement Policies", ds.rbeContainerName());
        assertEquals(1, ds.entitlementServiceDrivers().size());

        Path t = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, t);
        assertTrue(Files.exists(t.resolve("rbe-policies/Contractors.xml")));
        String file = Files.readString(t.resolve("rbe-policies/Contractors.xml"));
        assertTrue(file, file.contains("<member-query>ldap:///ou=users,o=data??sub?(employeeType=contractor)?x-sparse</member-query>"));
        assertTrue(file, file.contains("priority=\"0\""));
        DriverSet back = AsCodeReader.read(t);
        EntitlementPolicy q = back.rbePolicy("contractors");
        assertNotNull(q);
        assertEquals(p.memberQuery, q.memberQuery);
        assertEquals(p.identity, q.identity);
        assertEquals(p.members, q.members);
        assertEquals(p.excludedMembers, q.excludedMembers);
        assertEquals(p.entitlementRefs, q.entitlementRefs);
        assertEquals(p.priority, q.priority);
        assertEquals(CanonicalXml.serialize(p.criteria), CanonicalXml.serialize(q.criteria));
        assertEquals(CanonicalXml.serialize(p.displayEntitlements), CanonicalXml.serialize(q.displayEntitlements));
        assertEquals(POL, q.meta.get("dn"));
    }

    @Test
    public void exportRoundTripsDesignersShape() throws Exception {
        DriverSet ds = LdifReader.fromEntries(entries(), "test", Map.of());
        String xml = ExportWriter.toXml(ds);
        assertTrue(xml, xml.contains("<rbe-policies>"));
        assertTrue(xml, xml.contains("ds-object-class=\"DirXML-SharedProfileSet\""));
        assertTrue(xml, xml.contains("<typed-name-level>"));
        assertTrue(xml, xml.contains("base64-encoded=\"true\""));
        DriverSet back = ExportReader.read(CanonicalXml.parse(xml).getDocumentElement(), "x");
        assertEquals(1, back.rbePolicies.size());
        EntitlementPolicy q = back.rbePolicies.get(0);
        EntitlementPolicy p = ds.rbePolicies.get(0);
        assertEquals(p.name, q.name);
        assertEquals(p.priority, q.priority);
        assertEquals(p.memberQuery, q.memberQuery);
        assertEquals(p.members, q.members);
        assertEquals(p.entitlementRefs, q.entitlementRefs);
        assertEquals(CanonicalXml.serialize(p.criteria), CanonicalXml.serialize(q.criteria));
        assertEquals(CanonicalXml.serialize(p.displayEntitlements), CanonicalXml.serialize(q.displayEntitlements));
    }

    @Test
    public void exportWithoutRefsDerivesThemFromTheDisplayDocumentAsDesignerDoes() throws Exception {
        String xml = "<driver-set-configuration name=\"driverset1\"><children><rbe-policies>"
            + "<ds-object ds-object-class=\"DirXML-SharedProfileSet\" ds-object-name=\"Entitlement Policies\"><ds-attributes/>"
            + "<ds-object ds-object-class=\"DirXML-SharedProfile\" ds-object-name=\"Groups\"><ds-attributes>"
            + "<ds-attribute ds-attr-name=\"DirXML-SPDisplayEntitlements\"><ds-value base64-encoded=\"true\">"
            + java.util.Base64.getEncoder().encodeToString(("<Drivers><Driver><name>AD</name><value>x</value><description/><Entitlement><name>Group</name><value>v</value><description/><type>InputValues</type><EntitlementMulti>true</EntitlementMulti><EntitlementPackedValues>PP</EntitlementPackedValues><EntitlementDN>cn=Group,cn=AD," + DS + "</EntitlementDN><hasInterpretiveVariables>false</hasInterpretiveVariables><InputValues><value>g1</value><value>g2</value></InputValues><conflict-resolution>union</conflict-resolution></Entitlement></Driver></Drivers>").getBytes("UTF-8"))
            + "</ds-value></ds-attribute></ds-attributes></ds-object></ds-object></rbe-policies></children></driver-set-configuration>";
        DriverSet ds = ExportReader.read(CanonicalXml.parse(xml).getDocumentElement(), "x");
        assertEquals(1, ds.rbePolicies.size());
        EntitlementPolicy p = ds.rbePolicies.get(0);
        assertEquals(List.of("cn=Group,cn=AD," + DS + "#0#<ref><param>g1</param></ref>", "cn=Group,cn=AD," + DS + "#0#<ref><param>g2</param></ref>"), p.entitlementRefs);
        assertNull(p.priority);
    }

    @Test
    public void validateFlagsTheUsualMistakes() throws Exception {
        DriverSet ds = LdifReader.fromEntries(entries(), "test", Map.of());
        EntitlementPolicy p = ds.rbePolicies.get(0);
        p.memberQuery = null;
        p.members.clear();
        p.entitlementRefs.clear();
        p.entitlementRefs.add("cn=Nope,cn=AD," + DS + "#0#<ref/>");
        EntitlementPolicy dup = new EntitlementPolicy("Dup");
        dup.priority = 0;
        dup.memberQuery = "ldap:///o=data??sub?(cn=*)?x-sparse";
        dup.meta.put("legacy-entitlements-xml", "true");
        ds.rbePolicies.add(dup);
        EntitlementPolicy gap = new EntitlementPolicy("Gap");
        gap.priority = 2;
        gap.memberQuery = dup.memberQuery;
        ds.rbePolicies.add(gap);
        ds.drivers.removeIf(d -> EntitlementPolicy.SERVICE_SHIM_CLASS.equals(d.shimClass));
        Report r = Validator.standard().validate(ds);
        assertTrue(codes(r).contains("rbe-no-membership"));
        assertTrue(codes(r).contains("rbe-unknown-entitlement"));
        assertTrue(codes(r).contains("rbe-no-service-driver"));
        assertTrue(codes(r).contains("rbe-duplicate-priority"));
        assertTrue(codes(r).contains("rbe-legacy-entitlements-xml"));
        assertTrue(codes(r).contains("rbe-no-entitlement"));
        assertTrue(codes(r).contains("rbe-priorities-not-sequential"));   // levels 0 and 2: a gap
    }

    private static List<String> codes(Report r) {
        List<String> out = new java.util.ArrayList<>();
        for (Finding f : r.findings()) {
            out.add(f.code);
        }
        return out;
    }
}
