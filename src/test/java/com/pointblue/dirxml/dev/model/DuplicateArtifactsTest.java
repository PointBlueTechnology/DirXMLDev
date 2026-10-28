package com.pointblue.dirxml.dev.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.validate.Finding;
import com.pointblue.dirxml.dev.validate.Report;
import com.pointblue.dirxml.dev.validate.Validator;

import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Two artifacts at one path: one is kept, the read goes on, and the person is told (DirXMLDevWeb #174). */
public class DuplicateArtifactsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Resource js(String name, String text, String packageRecord) {
        Resource r = new Resource(name, Scope.LIBRARY, null, Resource.ECMASCRIPT);
        r.text = text;
        if (packageRecord != null) {
            r.meta.put(PackageStamps.GUID, packageRecord);
        }
        return r;
    }

    private static DriverSet set(Resource... resources) {
        DriverSet ds = new DriverSet("DS");
        ds.dn = "cn=DS,o=system";
        for (Resource r : resources) {
            ds.library.resources.add(r);
        }
        return ds;
    }

    @Test
    public void sameCopiesKeepOne() {
        DriverSet ds = set(js("NOVLLIBAJC-JS", "function a(){}", null), js("NOVLLIBAJC-JS", "function a(){}", null));
        List<DuplicateArtifacts.Dropped> d = DuplicateArtifacts.drop(ds);
        assertEquals(1, ds.library.resources.size());
        assertEquals(1, d.size());
        assertEquals("library/NOVLLIBAJC-JS", d.get(0).path);
        assertTrue(d.get(0).note, d.get(0).note.contains("they are the same, so kept one"));
        assertTrue(d.get(0).advice, d.get(0).advice.contains("Designer"));
    }

    @Test
    public void differentCopiesKeepTheNewestPackageVersion() {
        Resource old = js("NOVLLIBAJC-JS", "function old(){}", "G1;com.netiq.novllibajc;2.9.1.20130701170318;AJC;NOVLLIBAJC");
        Resource now = js("NOVLLIBAJC-JS", "function now(){}", "G1;com.netiq.novllibajc;2.10.0.20190806142553;AJC;NOVLLIBAJC");
        DriverSet ds = set(old, now);
        List<DuplicateArtifacts.Dropped> d = DuplicateArtifacts.drop(ds);
        assertEquals(List.of(now), ds.library.resources);
        assertTrue(d.get(0).note, d.get(0).note.contains("kept the resource (from package NOVLLIBAJC 2.10.0.20190806142553)"));
        assertTrue(d.get(0).note, d.get(0).note.contains("left out the resource (from package NOVLLIBAJC 2.9.1.20130701170318)"));
    }

    @Test
    public void differentCopiesWithoutPackagesKeepTheFirst() {
        Resource first = js("x", "1", null);
        DriverSet ds = set(first, js("x", "2", null));
        DuplicateArtifacts.drop(ds);
        assertEquals(List.of(first), ds.library.resources);
    }

    @Test
    public void uniquePathsAreLeftAlone() {
        DriverSet ds = set(js("a", "1", null), js("b", "1", null));
        assertTrue(DuplicateArtifacts.drop(ds).isEmpty());
        assertEquals(2, ds.library.resources.size());
    }

    @Test
    public void versionsCompareNumberByNumber() {
        assertTrue(DuplicateArtifacts.compareVersions("2.10.0", "2.9.1") > 0);
        assertTrue(DuplicateArtifacts.compareVersions("2.2.2.20190806142553", "2.2.2.20170104125336") > 0);
        assertEquals(0, DuplicateArtifacts.compareVersions("1.0", "1.0.0"));
    }

    /** A tree written before the readers kept one: it loads, and validation names the duplicate instead of every check failing. */
    @Test
    public void aTreeThatListsTwoLoadsAndValidationSaysSo() throws Exception {
        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(set(js("NOVLLIBAJC-JS", "function a(){}", null), js("NOVLLIBAJC-JS", "function b(){}", null)), dir);

        Report r = Validator.standard().validate(dir);

        boolean named = false;
        for (Finding f : r.findings()) {
            assertFalse(f.toString(), f.code.equals("check-failed") || f.code.equals("ascode-unreadable"));
            named |= f.code.equals("duplicate-artifact") && f.path.equals("library/NOVLLIBAJC-JS");
        }
        assertTrue(r.findings().toString(), named);
    }
}
