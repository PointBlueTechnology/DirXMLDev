package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.json.Json;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** {@code query fishbone --json}: the extension's payload, from the model, files named as the writer names them. */
public class FishboneTest {

    /** The extension's own smoke tree (AD Driver), kept in the repo. */
    private static final Path SAMPLE = Paths.get("extensions/dirxmldev-visual/sample-tree");

    @SuppressWarnings("unchecked")
    @Test
    public void sampleTreeFishboneHasEveryBoneAndRealFiles() throws Exception {
        DriverSet ds = AsCodeReader.read(SAMPLE);
        Driver ad = ds.driver("AD Driver");
        Map<String, Object> m = Fishbone.model(ds, SAMPLE, ad);
        assertEquals(SAMPLE.toAbsolutePath().normalize().toString(), m.get("treeRoot"));
        assertEquals("driverset1", ((Map<String, Object>) m.get("driverSet")).get("name"));
        assertEquals("drivers/AD Driver", ((Map<String, Object>) m.get("driver")).get("dir"));
        List<Object> pub = (List<Object>) m.get("publisher");
        List<Object> sub = (List<Object>) m.get("subscriber");
        assertEquals(5, pub.size());
        assertEquals(5, sub.size());
        assertEquals(3, ((List<Object>) m.get("spine")).size());
        assertEquals(4, ((List<Object>) m.get("resources")).size());
        assertEquals("Event Transformation", ((Map<String, Object>) sub.get(0)).get("label"));
        assertEquals("bone:subscriber-event", ((Map<String, Object>) sub.get(0)).get("id"));
        assertEquals(4, ((Map<String, Object>) sub.get(0)).get("setId"));
        int policies = 0;
        for (String section : List.of("publisher", "subscriber", "spine", "resources")) {
            for (Object bo : (List<Object>) m.get(section)) {
                for (Object po : (List<Object>) ((Map<String, Object>) bo).get("policies")) {
                    Map<String, Object> p = (Map<String, Object>) po;
                    policies++;
                    assertFalse(p.toString(), (Boolean) p.get("unresolved"));
                    assertNotNull(p.toString(), p.get("file"));
                    assertTrue(p.get("file").toString(), Files.isRegularFile(SAMPLE.resolve(p.get("file").toString())));
                    assertTrue(p.get("id").toString().startsWith("policy:"));
                }
            }
        }
        assertTrue("the sample links policies", policies > 5);
        Map<String, Object> filter = (Map<String, Object>) m.get("filter");
        assertEquals("config:driver-filter", filter.get("id"));
        assertTrue(Files.isRegularFile(SAMPLE.resolve(filter.get("file").toString())));
        // the Startup bone carries the AD entitlement startup policy
        Map<String, Object> startup = (Map<String, Object>) ((List<Object>) m.get("resources")).get(2);
        assertEquals("startup", startup.get("key"));
        assertFalse(((List<Object>) startup.get("policies")).isEmpty());
        // round trip through JSON keeps the shape
        Map<String, Object> back = Json.asMap(Json.parse(Json.pretty(m)));
        assertEquals(m.keySet(), back.keySet());
        assertTrue(Fishbone.text(m).contains("Schema Mapping (schema-mapping)"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void unresolvedLinkIsMarkedAndFilesFollowTheWriter() throws Exception {
        DriverSet ds = AsCodeReader.read(SAMPLE);
        Driver ad = ds.driver("AD Driver");
        ad.links.add(new PolicyLink(PolicySet.SUB_EVENT, "drivers/AD Driver/subscriber/Nope", 99));
        Map<String, Object> m = Fishbone.model(ds, SAMPLE, ad);
        List<Object> sub = (List<Object>) ((Map<String, Object>) ((List<Object>) m.get("subscriber")).get(0)).get("policies");
        Map<String, Object> last = (Map<String, Object>) sub.get(sub.size() - 1);
        assertEquals("Nope", last.get("name"));
        assertEquals("unresolved", last.get("kind"));
        assertEquals(Boolean.TRUE, last.get("unresolved"));
        assertFalse(last.containsKey("file"));
        Map<String, String> files = AsCodeWriter.files(ds);
        for (Map.Entry<String, String> e : files.entrySet()) {
            assertTrue(e.toString(), Files.isRegularFile(SAMPLE.resolve(e.getValue())));
        }
        Map<String, Object> drivers = Fishbone.drivers(ds, SAMPLE);
        assertEquals("AD Driver", ((Map<String, Object>) ((List<Object>) drivers.get("drivers")).get(0)).get("name"));
    }
}
