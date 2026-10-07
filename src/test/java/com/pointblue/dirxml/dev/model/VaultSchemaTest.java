package com.pointblue.dirxml.dev.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.clone.Schema;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** The vault schema in the tree: from the subschema, from Designer, round-tripped, queried, rendered back as LDAP. */
public class VaultSchemaTest {

    private static final List<String> ATTRS = List.of(
        "( 2.5.4.41 NAME 'name' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Name' )",
        "( 2.5.4.3 NAME 'cn' SUP name X-NDS_NAME 'CN' )",
        "( 2.5.4.4 NAME 'sn' SUP name X-NDS_NAME 'Surname' )",
        "( 2.5.4.42 NAME 'givenName' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Given Name' )",
        "( 2.16.840.1.113719.1.1.4.1.501 NAME 'GUID' SYNTAX 1.3.6.1.4.1.1466.115.121.1.40{16} SINGLE-VALUE NO-USER-MODIFICATION )",
        "( 2.5.4.31 NAME 'member' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Member' )");
    private static final List<String> CLASSES = List.of(
        "( 2.5.6.0 NAME 'Top' ABSTRACT MUST objectClass MAY ( cn $ GUID ) X-NDS_NAME 'Top' )",
        "( 2.5.6.6 NAME 'Person' SUP Top STRUCTURAL MUST ( cn $ sn ) MAY givenName X-NDS_NAME 'Person' X-NDS_NAMING 'cn' X-NDS_CONTAINMENT ( 'Organization' 'Organizational Unit' ) )",
        "( 2.16.840.1.113719.1.1.6.1.5 NAME 'inetOrgPerson' SUP Person STRUCTURAL MAY member X-NDS_NAME 'User' X-NDS_NAMING 'cn' )");

    @Test
    public void fromTheSubschemaWithNdsNamesAndInheritance() {
        VaultSchema v = VaultSchema.fromLdap(Schema.of(ATTRS, CLASSES), "ldaps://lab:636");
        assertEquals("ldaps://lab:636", v.meta.get("source"));
        VaultSchema.AttrDef given = v.attribute("givenName");
        assertNotNull(given);
        assertEquals("Given Name", given.name);
        assertEquals("givenName", given.ldap);
        assertEquals("2.5.4.42", given.oid);
        assertEquals("1.3.6.1.4.1.1466.115.121.1.15", given.syntax);
        assertTrue(v.attribute("GUID").single);
        assertTrue(v.attribute("GUID").noUserModification);
        // SUP-inherited syntax
        assertEquals("1.3.6.1.4.1.1466.115.121.1.15", v.attribute("Surname").syntax);
        VaultSchema.ClassDef user = v.classDef("inetOrgPerson");
        assertEquals("User", user.name);
        assertEquals(List.of("Person"), user.superclasses);
        assertEquals("structural", user.kind);
        assertEquals("abstract", v.classDef("Top").kind);
        // lists are NDS names, and attributesOf walks the superclasses, mandatory first
        assertEquals(List.of("CN", "Surname"), v.classDef("Person").mandatory);
        assertEquals(List.of("Organization", "Organizational Unit"), v.classDef("Person").containment);
        assertEquals(List.of("cn"), v.classDef("Person").naming);
        assertEquals(List.of("CN", "Surname", "objectClass", "Member", "Given Name", "GUID"), v.attributesOf("User"));
        assertTrue(v.customAttributes().isEmpty());
    }

    @Test
    public void fromDesignerSchemaXml() {
        String xml = "<schema user-mod=\"1\">"
            + "<attr name=\"Given Name\" ldap=\"givenName\" aid=\"2.5.4.42\" syn=\"ci-string\"/>"
            + "<attr name=\"pbEmployeeType\" ldap=\"pbEmployeeType\" syn=\"ci-string\" sngl=\"1\" user-mod=\"1\"/>"
            + "<class name=\"User\" ldap=\"inetOrgPerson\" aid=\"2.16.840.1.113719.1.1.6.1.5\" eff=\"1\"><sup>Organizational Person</sup><ctn>Organization, Organizational Unit</ctn><nmng>CN</nmng><mand>CN, Surname</mand><opt>Given Name, pbEmployeeType</opt></class>"
            + "</schema>";
        VaultSchema v = VaultSchema.fromDesigner(CanonicalXml.parse(xml).getDocumentElement(), "test11pf");
        assertEquals(2, v.attributes.size());
        assertTrue(v.attribute("pbEmployeeType").custom);
        assertTrue(v.attribute("pbEmployeeType").single);
        assertFalse(v.attribute("givenName").custom);
        assertEquals("ci-string", v.attribute("Given Name").syntax);
        VaultSchema.ClassDef user = v.classDef("User");
        assertEquals("inetOrgPerson", user.ldap);
        assertEquals(List.of("Organizational Person"), user.superclasses);
        assertEquals(List.of("CN", "Surname"), user.mandatory);
        assertEquals(List.of("Given Name", "pbEmployeeType"), user.optional);
        assertEquals(List.of("CN"), user.naming);
    }

    @Test
    public void roundTripsThroughTheTree() throws Exception {
        VaultSchema v = VaultSchema.fromLdap(Schema.of(ATTRS, CLASSES), "ldaps://lab:636");
        VaultSchema.AttrDef custom = new VaultSchema.AttrDef("pbCostCenter", "pbCostCenter");
        custom.syntax = "1.3.6.1.4.1.1466.115.121.1.15";
        custom.oid = "1.3.6.1.4.1.99999.1.1";
        custom.custom = true;
        v.add(custom);
        DriverSet ds = new DriverSet("set");
        ds.schema = v;
        Path dir = Files.createTempDirectory("schema-tree");
        AsCodeWriter.write(ds, dir);
        assertTrue(Files.isRegularFile(dir.resolve("schema/vault.xml")));
        assertTrue(Files.readString(dir.resolve("driverset.xml")).contains("schema/vault.xml"));
        VaultSchema back = AsCodeReader.read(dir).schema;
        assertNotNull(back);
        assertEquals(7, back.attributes.size());
        assertEquals(3, back.classes.size());
        assertEquals("Given Name", back.attribute("givenName").name);
        assertTrue(back.attribute("GUID").single);
        assertTrue(back.attribute("pbCostCenter").custom);
        assertEquals(List.of("CN", "Surname"), back.classDef("Person").mandatory);
        assertEquals(List.of("Organization", "Organizational Unit"), back.classDef("Person").containment);
        assertEquals("ldaps://lab:636", back.meta.get("source"));
        assertEquals(1, back.customAttributes().size());
        // a tree without a schema reads back without one
        DriverSet plain = new DriverSet("plain");
        Path dir2 = Files.createTempDirectory("schema-tree2");
        AsCodeWriter.write(plain, dir2);
        assertNull(AsCodeReader.read(dir2).schema);
    }

    @Test
    public void rendersCustomDefinitionsAsLdap() {
        VaultSchema v = VaultSchema.fromLdap(Schema.of(ATTRS, CLASSES), null);
        VaultSchema.AttrDef a = new VaultSchema.AttrDef("PB Cost Center", "pbCostCenter");
        a.oid = "1.3.6.1.4.1.99999.1.1";
        a.syntax = "1.3.6.1.4.1.1466.115.121.1.15";
        a.single = true;
        a.custom = true;
        v.add(a);
        assertEquals("( 1.3.6.1.4.1.99999.1.1 NAME 'pbCostCenter' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE X-NDS_NAME 'PB Cost Center' )", v.ldapDefinition(a));
        VaultSchema.ClassDef c = new VaultSchema.ClassDef("pbContractor", "pbContractor");
        c.oid = "1.3.6.1.4.1.99999.2.1";
        c.kind = "auxiliary";
        c.superclasses.add("Top");
        c.optional.add("PB Cost Center");
        c.optional.add("Given Name");
        c.custom = true;
        v.add(c);
        assertEquals("( 1.3.6.1.4.1.99999.2.1 NAME 'pbContractor' SUP Top AUXILIARY MAY ( pbCostCenter $ givenName ) )", v.ldapDefinition(c));
        assertTrue(v.remove("pbContractor"));
        assertNull(v.classDef("pbContractor"));
        assertFalse(v.remove("nope"));
    }
}
