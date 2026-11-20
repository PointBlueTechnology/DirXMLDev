package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import org.junit.Test;

public class CompletionCliTest {

    @Test
    public void generatedBashScriptUsesCurrentCommandsAndOptionsWithoutCheckoutMetadata() throws Exception {
        String script = CompletionCli.generate(Path.of(".").toAbsolutePath());

        assertTrue(script.contains("    'completion'\n"));
        assertTrue(script.contains("    'vault.deploy'\n"));
        assertTrue(script.contains("--delete-all"));
        // in the parser's spec, not in the usage text
        assertTrue(script.contains("--trace-size-limit"));
        // an edit operation's options come from the Registry
        assertTrue(script.contains("--content-file"));
        assertTrue(script.contains("'clear view'"));
        assertFalse(script.contains("docs/commands.md"));
        assertFalse(script.contains("BEGIN IDM OPTIONS\n# END IDM OPTIONS"));
    }
}
