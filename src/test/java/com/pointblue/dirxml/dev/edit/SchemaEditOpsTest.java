package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.clone.Schema;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.VaultSchema;
import com.pointblue.dirxml.dev.validate.Validator;
import com.pointblue.dirxml.dev.validate.ValidatorTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** Custom schema in the tree: defined, changed, removed, and what the validator says about names the schema lacks. */
public class SchemaEditOpsTest {

    private Path tree;

    @Before
    public void writeTree() throws IOException {
        tree = Files.createTempDirectory("idm-schema");
        DriverSet ds = ValidatorTest.clean();
        ds.schema = VaultSchema.fromLdap(Schema.of(List.of(
            "( 2.5.4.41 NAME 'name' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Name' )",
            "( 2.5.4.3 NAME 'cn' SUP name X-NDS_NAME 'CN' )",
            "( 2.5.4.4 NAME 'sn' SUP name X-NDS_NAME 'Surname' )",
            "( 2.5.4.42 NAME 'givenName' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Given Name' )"),
            List.of(
            "( 2.5.6.0 NAME 'Top' ABSTRACT MUST objectClass )",
            "( 2.16.840.1.113719.1.1.6.1.5 NAME 'inetOrgPerson' SUP Top STRUCTURAL MUST ( cn $ sn ) MAY givenName X-NDS_NAME 'User' )")), "test");
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws IOException {
        return Transaction.open(tree).run(op, false, false);
    }

    @Test
    public void customAttributesAndClassesAreDefinedChangedAndRemoved() throws IOException {
        assertTrue(run(new SchemaEditOps.AddAttribute("PB Cost Center", "pbCostCenter", null, "1.3.6.1.4.1.99999.1.1", true)).ok());
        VaultSchema s = AsCodeReader.read(tree).schema;
        VaultSchema.AttrDef a = s.attribute("pbCostCenter");
        assertNotNull(a);
        assertTrue(a.custom);
        assertTrue(a.single);
        assertEquals("1.3.6.1.4.1.1466.115.121.1.15", a.syntax);
        assertTrue(run(new SchemaEditOps.AddAttribute("PB Cost Center", null, null, null, false)).refusal.contains("exists already"));
        // a class naming it, under User
        assertTrue(run(new SchemaEditOps.AddClass("pbContractor", null, "auxiliary", "1.3.6.1.4.1.99999.2.1", "Top", null, "PB Cost Center, Given Name", null, null)).ok());
        s = AsCodeReader.read(tree).schema;
        VaultSchema.ClassDef c = s.classDef("pbContractor");
        assertEquals("auxiliary", c.kind);
        assertEquals(List.of("PB Cost Center", "Given Name"), c.optional);
        assertEquals("( 1.3.6.1.4.1.99999.2.1 NAME 'pbContractor' SUP Top AUXILIARY MAY ( pbCostCenter $ givenName ) )", s.ldapDefinition(c));
        assertTrue(run(new SchemaEditOps.AddClass("x", null, "structural", null, "Nope", null, null, null, null)).refusal.contains("superclass"));
        assertTrue(run(new SchemaEditOps.AddClass("x", null, "structural", null, null, "nope", null, null, null)).refusal.contains("not in the schema"));
        // change: only custom ones
        assertTrue(run(new SchemaEditOps.Set("pbCostCenter", null, null, "1.3.6.1.4.1.99999.1.2", false, null, null, null, null, null, null)).ok());
        a = AsCodeReader.read(tree).schema.attribute("pbCostCenter");
        assertEquals("1.3.6.1.4.1.99999.1.2", a.oid);
        assertFalse(a.single);
        assertTrue(run(new SchemaEditOps.Set("Surname", null, null, "1.2.3", null, null, null, null, null, null, null)).refusal.contains("the vault's"));
        // remove: refuses while named, then goes; never the vault's
        assertTrue(run(new SchemaEditOps.Remove("pbCostCenter")).refusal.contains("named by class pbContractor"));
        assertTrue(run(new SchemaEditOps.Remove("pbContractor")).ok());
        assertTrue(run(new SchemaEditOps.Remove("pbCostCenter")).ok());
        assertNull(AsCodeReader.read(tree).schema.attribute("pbCostCenter"));
        assertTrue(run(new SchemaEditOps.Remove("User")).refusal.contains("the vault's"));
        assertTrue(run(new SchemaEditOps.Remove("nope")).refusal.contains("no attribute or class"));
    }

    @Test
    public void theRegistryKnowsTheOperations() throws Exception {
        assertNotNull(Registry.get("schema.add-attribute"));
        assertNotNull(Registry.get("schema.add-class"));
        assertNotNull(Registry.get("schema.set"));
        assertNotNull(Registry.get("schema.remove"));
        Result r = run(Registry.get("schema.add-attribute").create(java.util.Map.of("name", "pbX", "single", "true")));
        assertTrue(r.text(), r.ok());
        assertTrue(AsCodeReader.read(tree).schema.attribute("pbX").single);
    }

    @Test
    public void withoutASchemaTheOpsRefuseAndTheCheckIsSilent() throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        ds.schema = null;
        AsCodeWriter.write(ds, tree);
        assertTrue(run(new SchemaEditOps.AddAttribute("x", null, null, null, false)).refusal.contains("no schema"));
        assertTrue(Validator.standard().validate(AsCodeReader.read(tree)).withCode("schema-unknown-class").isEmpty());
    }

    @Test
    public void theValidatorWarnsAboutNamesTheSchemaLacks() throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        ds.driver("AD").config.put(com.pointblue.dirxml.dev.model.Driver.DRIVER_FILTER, ValidatorTest.xml(
            "<filter><filter-class class-name=\"Widget\" publisher=\"sync\" subscriber=\"sync\"><filter-attr attr-name=\"Surname\"/></filter-class>"
            + "<filter-class class-name=\"User\" publisher=\"sync\" subscriber=\"sync\"><filter-attr attr-name=\"Surname\"/><filter-attr attr-name=\"zzz\"/><filter-attr attr-name=\"Name\"/></filter-class></filter>"));
        com.pointblue.dirxml.dev.validate.Report report = Validator.standard().validate(ds);
        assertEquals(1, report.withCode("schema-unknown-class").size());
        assertTrue(report.withCode("schema-unknown-class").get(0).message.contains("Widget"));
        assertEquals(1, report.withCode("schema-unknown-attr").size());
        assertTrue(report.withCode("schema-unknown-attr").get(0).message.contains("zzz"));
        // Name is an attribute, but not of User (nor its superclasses)
        assertEquals(1, report.withCode("schema-attr-not-of-class").size());
        assertEquals("WARNING", report.withCode("schema-unknown-class").get(0).severity.name());
    }
}
