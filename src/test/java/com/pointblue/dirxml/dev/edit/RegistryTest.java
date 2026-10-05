package com.pointblue.dirxml.dev.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** The catalog a client outside this package (the CLI, a server, a UI) builds operations from. */
public class RegistryTest {

    @Test
    public void everySpecHasANameHelpAndArgs() {
        assertTrue(Registry.all().size() >= 60);
        for (Registry.Spec s : Registry.all()) {
            assertNotNull(s.name);
            assertNotNull(s.name, s.help);
            assertNotNull(s.name, s.args);
            assertEquals(s, Registry.get(s.name));
        }
    }

    @Test
    public void aSpecBuildsItsOperationFromArguments() throws Exception {
        Registry.Spec spec = Registry.get("artifact.rename");
        assertNotNull(spec);
        assertNotNull("missing arguments are named first", Registry.missing(spec, Map.of()));
        Map<String, String> args = Map.of("path", "library/x", "name", "y");
        assertNull(Registry.missing(spec, args));
        Operation op = spec.create(args);
        assertNotNull(op);
        assertEquals("artifact.rename", op.name());
    }
}
