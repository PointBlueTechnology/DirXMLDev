package com.pointblue.dirxml.dev.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.forms.FormBuilderLocator.Os;
import com.pointblue.dirxml.dev.forms.FormBuilderLocator.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Uses a shell script standing in for the builder: it rewrites the --filepath file like a save would. */
public class FormBuilderRunnerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Status fakeBuilder(String body) throws IOException {
        Assume.assumeTrue("needs /bin/sh", Files.isExecutable(Path.of("/bin/sh")));
        Path exe = tmp.newFolder("lib").toPath().resolve("formbuilder");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n");
        exe.toFile().setExecutable(true, false);
        return FormBuilderLocator.check(Os.LINUX, "test", exe);
    }

    @Test
    public void waitsAndDetectsASave() throws Exception {
        // the fake "builder" writes a compact document to the file named by --filepath
        Status b = fakeBuilder("for a in \"$@\"; do case \"$a\" in --filepath=*) f=\"${a#--filepath=}\";; esac; done\n"
            + "printf '{\"components\":[],\"title\":\"Saved\"}' > \"$f\"; exit 0");
        Path form = tmp.newFile("x.form.json").toPath();
        Files.writeString(form, "{\n  \"components\": [],\n  \"title\": \"Before\"\n}\n");

        FormBuilderRunner.Outcome o = FormBuilderRunner.run(b, form, "en_US", null, true);
        assertEquals(Integer.valueOf(0), o.exitCode);
        assertTrue(o.changed());
        assertEquals("{\"components\":[],\"title\":\"Saved\"}", o.afterText());
        assertTrue(o.command.get(1).startsWith("--filepath="));
        assertEquals("--no-sandbox", o.command.get(o.command.size() - 1));
    }

    @Test
    public void quitWithoutSaveIsUnchanged() throws Exception {
        Status b = fakeBuilder("exit 0");
        Path form = tmp.newFile("y.form.json").toPath();
        Files.writeString(form, "{}");
        FormBuilderRunner.Outcome o = FormBuilderRunner.run(b, form, null, null, true);
        assertFalse(o.changed());
        assertEquals("{}", o.afterText());
    }

    @Test
    public void noWaitReturnsImmediately() throws Exception {
        Status b = fakeBuilder("sleep 2; exit 0");
        Path form = tmp.newFile("z.form.json").toPath();
        Files.writeString(form, "{}");
        long t0 = System.currentTimeMillis();
        FormBuilderRunner.Outcome o = FormBuilderRunner.run(b, form, null, null, false);
        assertTrue(System.currentTimeMillis() - t0 < 1500);
        assertNull(o.exitCode);
        assertNull(o.after);
        assertTrue(o.pid > 0);
    }

    @Test
    public void refusesABuilderThatIsNotReady() throws Exception {
        Path exe = tmp.newFolder("lib2").toPath().resolve("formbuilder");
        Files.writeString(exe, "#!/bin/sh\nexit 0\n"); // no execute bit
        Status b = FormBuilderLocator.check(Os.LINUX, "test", exe);
        Path form = tmp.newFile("w.form.json").toPath();
        Files.writeString(form, "{}");
        try {
            FormBuilderRunner.run(b, form, null, null, true);
            throw new AssertionError("expected refusal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("chmod -R a+x"));
        }
    }

    @Test
    public void serviceRegistryNormalizesTheUrl() {
        String s = FormBuilderRunner.serviceRegistry("https://apps.example.com:8543/");
        assertTrue(s, s.contains("\"FormsBackendUrl\": \"https://apps.example.com:8543/WFHandler\""));
        assertEquals(s, FormBuilderRunner.serviceRegistry("https://apps.example.com:8543/WFHandler"));
        assertEquals(StandardCharsets.UTF_8, StandardCharsets.UTF_8);
    }
}
