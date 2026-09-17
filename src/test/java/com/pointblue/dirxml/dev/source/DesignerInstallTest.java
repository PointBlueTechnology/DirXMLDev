package com.pointblue.dirxml.dev.source;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** The icon lookup order: application type, driver type, base package's driver type; preferred plugins before the core. */
public class DesignerInstallTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path fakeInstall() throws IOException {
        Path root = tmp.newFolder("Designer").toPath();
        Path core = root.resolve("plugins/com.novell.core_4.0.0.1/icons/iManager");
        Path pal = root.resolve("plugins/com.novell.prov.pal.integration_4.0.0.1/icons/iManager");
        Files.createDirectories(core);
        Files.createDirectories(pal);
        Files.writeString(core.resolve("GenericApp.gif"), "generic");
        Files.writeString(core.resolve("SCIM.gif"), "scim");
        Files.writeString(core.resolve("AD.gif"), "ad");
        Files.writeString(core.resolve("NProv.gif"), "nprov-core");
        Files.writeString(pal.resolve("NProv.gif"), "nprov-pal");
        return root;
    }

    @Test
    public void preferredPluginWinsThenCore() throws IOException {
        DesignerInstall d = DesignerInstall.at(fakeInstall(), "test");
        assertEquals("nprov-pal", Files.readString(d.icon("NProv", "NProv Driver 4.8.0", null)));
        assertEquals("scim", Files.readString(d.icon("GenericApp", "SCIM-Driver", null)));
    }

    @Test
    public void basePackageDriverTypeGivesACustomShimItsIcon() throws IOException {
        DesignerInstall d = DesignerInstall.at(fakeInstall(), "test");
        // a custom SCIM-based shim: type [ANY], application GenericApp, base package NETQSCIMBASE → SCIM-Driver
        assertEquals("scim", Files.readString(d.icon("GenericApp", "[ANY]", "SCIM-Driver")));
        // a Remote Loader AD driver: the AD-Driver type's short name is the iManager file name
        assertEquals("ad", Files.readString(d.icon("ActiveDirectory", "AD-Driver", null)));
        // nothing known: the catch-all
        assertEquals("generic", Files.readString(d.icon("GenericApp", "[ANY]", null)));
    }

    @Test
    public void noInstallNoIcon() {
        assertNull(DesignerInstall.none().icon("NProv", "NProv Driver 4.8.0", null));
    }
}
