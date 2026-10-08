package com.pointblue.dirxml.dev.deploy;

import com.pointblue.dirxml.dev.model.VaultSchema;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Two schemas side by side: what counts as a difference, and what does not. */
public class SchemaDiffTest {

    private static VaultSchema.AttrDef attr(String name, String ldap, String syntax, boolean single) {
        VaultSchema.AttrDef a = new VaultSchema.AttrDef(name, ldap);
        a.syntax = syntax;
        a.single = single;
        return a;
    }

    private static VaultSchema.ClassDef cls(String name, String ldap, String... optional) {
        VaultSchema.ClassDef c = new VaultSchema.ClassDef(name, ldap);
        c.superclasses.add("Top");
        c.optional.addAll(List.of(optional));
        return c;
    }

    @Test
    public void onlyRealDifferencesCount() {
        VaultSchema model = new VaultSchema();
        model.add(attr("Given Name", "givenName", "1.3.6.1.4.1.1466.115.121.1.15", false));
        model.add(attr("PB Cost Center", "pbCostCenter", "1.3.6.1.4.1.1466.115.121.1.15", true));
        model.attribute("PB Cost Center").custom = true;
        model.add(attr("Title", "title", "1.3.6.1.4.1.1466.115.121.1.15", false));
        model.add(cls("User", "inetOrgPerson", "Given Name", "Title"));
        model.add(cls("pbContractor", "pbContractor", "PB Cost Center"));
        model.classDef("pbContractor").custom = true;

        VaultSchema live = new VaultSchema();
        live.add(attr("given name", "givenName", "1.3.6.1.4.1.1466.115.121.1.15", false));   // case differs: same thing
        live.add(attr("Title", "title", "1.3.6.1.4.1.1466.115.121.1.15", true));             // single-valued there
        live.add(attr("Surname", "sn", "1.3.6.1.4.1.1466.115.121.1.15", false));             // only in the vault
        live.add(cls("User", "inetOrgPerson", "Title", "Given Name", "Surname"));             // order differs, one more

        SchemaDiff.Result r = SchemaDiff.of(model, live, "model", "lab");
        assertFalse(r.inSync());
        assertEquals(2, r.attributesCompared);
        assertEquals(1, r.classesCompared);
        Map<String, Object> m = r.toMap();
        @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) m.get("counts");
        assertEquals(Integer.valueOf(1), counts.get("attribute:left-only"));
        assertEquals(Integer.valueOf(1), counts.get("attribute:right-only"));
        assertEquals(Integer.valueOf(1), counts.get("attribute:differs"));
        assertEquals(Integer.valueOf(1), counts.get("class:left-only"));
        assertEquals(Integer.valueOf(1), counts.get("class:differs"));
        SchemaDiff.Change costCenter = r.changes.stream().filter(c -> c.name.equals("PB Cost Center")).findFirst().orElseThrow();
        assertTrue("a custom definition not yet pushed says so", costCenter.custom && costCenter.where.equals("left-only"));
        SchemaDiff.Change title = r.changes.stream().filter(c -> c.name.equals("Title")).findFirst().orElseThrow();
        assertEquals(List.of("single-valued: false → true"), title.fields);
        SchemaDiff.Change user = r.changes.stream().filter(c -> c.name.equals("User")).findFirst().orElseThrow();
        assertEquals(List.of("optional: right has Surname"), user.fields);
        assertTrue(r.text().contains("< attribute PB Cost Center (custom, not pushed)"));
        assertTrue(r.text().contains("> attribute Surname"));
    }

    @Test
    public void identicalSchemasAreInSyncAndAMissingOidOnOneSideIsNotADifference() {
        VaultSchema a = new VaultSchema();
        a.add(attr("Title", "title", "1.3.6.1.4.1.1466.115.121.1.15", false));
        VaultSchema b = new VaultSchema();
        b.add(attr("Title", "title", "1.3.6.1.4.1.1466.115.121.1.15", false));
        b.attribute("Title").oid = "2.5.4.12";
        a.attribute("Title").oid = null;
        SchemaDiff.Result r = SchemaDiff.of(a, b, "stg", "prd");
        assertEquals("oid: — → 2.5.4.12", r.changes.get(0).fields.get(0));
        b.attribute("Title").oid = null;
        assertTrue(SchemaDiff.of(a, b, "stg", "prd").inSync());
    }
}
