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
import com.pointblue.dirxml.sim.Xds;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Mapping tables as CSV (import, export) and the drivers that use a table (Refs.withDrivers). */
public class MappingTableCsvTest {

    private Path tree;

    @Before
    public void writeTree() throws IOException {
        tree = Files.createTempDirectory("idm-csv");
        DriverSet ds = ValidatorTest.clean();
        // CodeMap: code (nocase), dn (nocase); one row 1 → ou=a. Make code numeric to see a type kept.
        Element t = ((Resource) ds.resolve("library/CodeMap")).content;
        Xds.childrenByName(t, "col-def").get(0).setAttribute("type", "numeric");
        AsCodeWriter.write(ds, tree);
    }

    private Result run(Operation op) throws IOException {
        return Transaction.open(tree).run(op, false, false);
    }

    private Element table() throws IOException {
        return ((Resource) AsCodeReader.read(tree).resolve("library/CodeMap")).content;
    }

    private static List<String> rows(Element t) {
        return Xds.childrenByName(t, "row").stream()
            .map(r -> Xds.childrenByName(r, "col").stream().map(Xds::text).collect(Collectors.joining("|")))
            .collect(Collectors.toList());
    }

    @Test
    public void parsesRfc4180() {
        assertEquals(List.of(List.of("a", "b"), List.of("1", "x,y"), List.of("2", "say \"hi\"\nthere")),
            MappingTableCsv.parse("﻿a,b\r\n1,\"x,y\"\r\n\r\n2,\"say \"\"hi\"\"\nthere\"\n"));
        assertEquals("no trailing line end", List.of(List.of("a"), List.of("")), MappingTableCsv.parse("a\n\"\""));
        for (String bad : new String[] {"a,\"b\n", "a,b\"c\n", "a,\"b\"c\n"}) {
            try {
                MappingTableCsv.parse(bad);
                fail("parsed " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("line "));
            }
        }
    }

    @Test
    public void writesAndReadsBack() throws IOException {
        assertTrue(run(new ConfigOps.TableSetRow("library/CodeMap", null, java.util.Map.of("code", "2", "dn", "ou=b,o=x"))).ok());
        String csv = MappingTableCsv.write(table());
        assertEquals("code,dn\r\n1,ou=a\r\n2,\"ou=b,o=x\"\r\n", csv);
        Result r = run(new MappingTableCsv.Import("library/CodeMap", csv, false));
        assertTrue(r.text(), r.ok());
        assertEquals(List.of("1|ou=a", "2|ou=b,o=x"), rows(table()));
    }

    @Test
    public void importReplacesTheTableKeepingColumnTypes() throws IOException {
        Result r = run(new MappingTableCsv.Import("library/CodeMap", "code,dn,region\n7,ou=g,west\n8,ou=h,\n", false));
        assertTrue(r.text(), r.ok());
        Element t = table();
        assertEquals(List.of("code", "dn", "region"), ConfigOps.columns(t));
        assertEquals("numeric", Xds.childrenByName(t, "col-def").get(0).getAttribute("type"));
        assertEquals("nocase", Xds.childrenByName(t, "col-def").get(2).getAttribute("type"));
        assertEquals("the old row is gone", List.of("7|ou=g|west", "8|ou=h|"), rows(t));

        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "code\n9\n", false)).ok());
        assertEquals("a column the CSV leaves out goes", List.of("code"), ConfigOps.columns(table()));
    }

    @Test
    public void mergeAddsAndUpdatesByKey() throws IOException {
        Result r = run(new MappingTableCsv.Import("library/CodeMap", "code,region\n1,east\n3,north\n", true));
        assertTrue(r.text(), r.ok());
        Element t = table();
        assertEquals(List.of("code", "dn", "region"), ConfigOps.columns(t));
        assertEquals(List.of("1|ou=a|east", "3||north"), rows(t));
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "dn,code\nou=z,1\n", true)).refusal.contains("key column first"));
    }

    @Test
    public void refusesWhatWouldBeAmbiguous() throws IOException {
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "code,dn\n1,a\n1,b\n", false)).refusal.contains("appears twice"));
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "code,dn\n,a\n", false)).refusal.contains("no key"));
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "code,dn\n1,a,extra\n", false)).refusal.contains("3 fields"));
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "code,code\n1,a\n", false)).refusal.contains("twice"));
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "", false)).refusal.contains("empty"));
        assertTrue(run(new MappingTableCsv.Import("library/CodeMap", "code,\"dn\n", false)).refusal.startsWith("not CSV"));
        assertEquals("nothing changed", List.of("1|ou=a"), rows(table()));
    }

    @Test
    public void aTableNamesTheDriversThatRunAPolicyReadingIt() throws IOException {
        DriverSet ds = AsCodeReader.read(tree);
        Policy lib = new Policy("lib-Map", Scope.LIBRARY, null, ValidatorTest.xml("<policy><rule><description>r</description><conditions/><actions>"
            + "<do-set-local-variable name=\"x\"><arg-string><token-map table=\"..\\Library\\CodeMap\" source=\"code\" destination=\"dn\">"
            + "<token-text>1</token-text></token-map></arg-string></do-set-local-variable></actions></rule></policy>"));
        ds.library.policies.add(lib);
        Driver ad = ds.driver("AD");
        ad.links.add(new PolicyLink(PolicySet.SUB_COMMAND, "library/lib-Map", 0));
        List<Refs.Ref> refs = Refs.withDrivers(ds, "library/CodeMap");
        List<String> kinds = refs.stream().map(r -> r.kind + " " + r.from).collect(Collectors.toList());
        assertTrue(kinds.toString(), kinds.contains("map library/lib-Map"));
        assertTrue(kinds.toString(), kinds.contains("driver drivers/AD"));
        assertEquals("rename and delete still see the policy only", 1, Refs.to(ds, "library/CodeMap").size());
    }
}
