package com.pointblue.dirxml.dev.deploy;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shared-file warning only where its advice works (DirXMLDevWeb #177): a folder that ignores
 * chmod (a Windows folder mounted into a container) gets no warning. A local disk keeps chmod, so
 * these check that the warning stays there and the probe leaves nothing behind.
 */
public class SecretSourcePermissionsTest {

    @Test
    public void aLocalFolderKeepsPermissionsAndTheProbeIsRemoved() throws IOException {
        Path dir = Files.createTempDirectory("idm-perm");
        assertTrue(SecretSource.keepsPermissions(dir));
        try (Stream<Path> left = Files.list(dir)) {
            assertEquals("the scratch file is removed", 0, left.count());
        }
    }

    @Test
    public void aFolderThatCantBeTriedKeepsTheWarning() {
        assertTrue(SecretSource.keepsPermissions(Path.of("/no/such/folder/for/idm")));
    }
}
