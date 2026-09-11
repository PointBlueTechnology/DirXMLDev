package com.pointblue.dirxml.dev.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.forms.FormBuilderLocator.Os;
import com.pointblue.dirxml.dev.forms.FormBuilderLocator.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FormBuilderLocatorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A fake Designer install with the platform's form builder plugin (two versions; the newest must win). */
    private Path designer(Os os, boolean executable) throws IOException {
        Path root = tmp.newFolder("designer-" + os.name().toLowerCase()).toPath();
        Path plugins = root.resolve("plugins");
        for (String ver : new String[] {"4.0.0.201910221727", "4.0.0.202507091433"}) {
            Path exe = plugins.resolve(os.pluginId + "_" + ver).resolve("lib").resolve(os.exeInLib);
            Files.createDirectories(exe.getParent());
            Files.write(exe, new byte[] {1});
            if (executable && os != Os.WINDOWS) {
                exe.toFile().setExecutable(true, false);
            }
        }
        Files.createDirectories(plugins.resolve("com.novell.prov.form_4.0.0.202412191437"));
        return root;
    }

    @Test
    public void findsNewestPluginUnderDesignerRootOnEachOs() throws IOException {
        for (Os os : Os.values()) {
            Path root = designer(os, true);
            Status s = FormBuilderLocator.resolve(os, null, null, Arrays.asList(tmp.getRoot().toPath().resolve("nope"), root));
            assertTrue(os + ": " + s.describe(), s.exists);
            assertTrue(os + ": " + s.describe(), s.ready());
            assertEquals("designer " + root, s.source);
            assertTrue(s.executable.toString().contains(os.pluginId + "_4.0.0.202507091433"));
            assertTrue(s.executable.endsWith(os.exeInLib));
            assertTrue(s.fixes.isEmpty());
        }
    }

    @Test
    public void nothingFoundIsReportedNotThrown() {
        Status s = FormBuilderLocator.resolve(Os.MAC, null, null, Collections.singletonList(tmp.getRoot().toPath().resolve("nope")));
        assertNull(s.executable);
        assertFalse(s.ready());
        assertTrue(s.describe().contains("not found"));
        assertTrue(s.describe().contains("IDM_FORMBUILDER"));
    }

    @Test
    public void explicitPathWinsAndAcceptsBundleLibOrPluginDir() throws IOException {
        Path root = designer(Os.MAC, true);
        Path plugin = root.resolve("plugins").resolve(Os.MAC.pluginId + "_4.0.0.202507091433");
        Path exe = plugin.resolve("lib").resolve(Os.MAC.exeInLib);
        Path app = plugin.resolve("lib").resolve("FormBuilder.app");

        assertEquals(exe, FormBuilderLocator.resolve(Os.MAC, exe.toString(), null, Collections.emptyList()).executable);
        assertEquals(exe, FormBuilderLocator.resolve(Os.MAC, app.toString(), null, Collections.emptyList()).executable);
        assertEquals(exe, FormBuilderLocator.resolve(Os.MAC, plugin.resolve("lib").toString(), null, Collections.emptyList()).executable);
        assertEquals(exe, FormBuilderLocator.resolve(Os.MAC, plugin.toString(), null, Collections.emptyList()).executable);
        assertEquals("env IDM_FORMBUILDER", FormBuilderLocator.resolve(Os.MAC, exe.toString(), "/elsewhere", Collections.emptyList()).source);
        assertEquals("property formbuilder", FormBuilderLocator.resolve(Os.MAC, null, exe.toString(), Collections.emptyList()).source);

        Path win = designer(Os.WINDOWS, true).resolve("plugins").resolve(Os.WINDOWS.pluginId + "_4.0.0.202507091433");
        assertEquals(win.resolve("lib").resolve("FormBuilder.exe"),
            FormBuilderLocator.resolve(Os.WINDOWS, win.toString(), null, Collections.emptyList()).executable);
    }

    @Test
    public void missingExecuteBitYieldsTheFixCommandOnLinuxAndMac() throws IOException {
        Path linux = designer(Os.LINUX, false);
        Status ls = FormBuilderLocator.resolve(Os.LINUX, null, null, Collections.singletonList(linux));
        assertTrue(ls.exists);
        assertFalse(ls.executable_);
        assertFalse(ls.ready());
        assertEquals(1, ls.fixes.size());
        assertTrue(ls.fixes.get(0), ls.fixes.get(0).startsWith("chmod -R a+x "));
        assertTrue(ls.fixes.get(0), ls.fixes.get(0).endsWith("lib"));

        Path mac = designer(Os.MAC, false);
        Status ms = FormBuilderLocator.resolve(Os.MAC, null, null, Collections.singletonList(mac));
        assertFalse(ms.ready());
        assertTrue(ms.fixes.toString(), ms.fixes.stream().anyMatch(f -> f.startsWith("chmod -R a+x ") && f.endsWith("FormBuilder.app")));
        assertTrue(ms.describe().contains("run once, by hand"));
    }

    @Test
    public void commandLineMatchesDesigners() throws IOException {
        Path form = tmp.newFile("Help-desk Request Form.formRequest").toPath();
        Path svc = tmp.newFile("ServiceRegistry.json").toPath();

        Status mac = FormBuilderLocator.resolve(Os.MAC, null, null, Collections.singletonList(designer(Os.MAC, true)));
        List<String> cmd = FormBuilderLocator.command(mac, form, "en_US", svc);
        assertEquals(mac.executable.toString(), cmd.get(0));
        assertEquals("--filepath=" + form.toAbsolutePath(), cmd.get(1));
        assertEquals("--locale=en_US", cmd.get(2));
        assertEquals("--service=" + svc.toAbsolutePath(), cmd.get(3));
        assertEquals(4, cmd.size());

        assertEquals(3, FormBuilderLocator.command(mac, form, null, null).size());
        assertEquals("--locale=en_US", FormBuilderLocator.command(mac, form, null, null).get(2));

        Status linux = FormBuilderLocator.resolve(Os.LINUX, null, null, Collections.singletonList(designer(Os.LINUX, true)));
        List<String> lcmd = FormBuilderLocator.command(linux, form, "de_DE", null);
        assertEquals("--no-sandbox", lcmd.get(lcmd.size() - 1));
        assertEquals("--locale=de_DE", lcmd.get(2));

        Status win = FormBuilderLocator.resolve(Os.WINDOWS, null, null, Collections.singletonList(designer(Os.WINDOWS, true)));
        assertFalse(FormBuilderLocator.command(win, form, null, null).contains("--no-sandbox"));
    }

    @Test
    public void currentOsIsOneOfThree() {
        assertTrue(Arrays.asList(Os.values()).contains(Os.current()));
    }
}
