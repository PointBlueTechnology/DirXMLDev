package com.pointblue.dirxml.dev.forms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.edit.FormOpsTest;
import com.pointblue.dirxml.dev.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** form.edit end to end with a shell script standing in for the vendor builder. */
public class FormEditCliTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String oldProp;

    @After
    public void restore() {
        if (oldProp == null) {
            System.clearProperty("formbuilder");
        } else {
            System.setProperty("formbuilder", oldProp);
        }
    }

    /** The fake builder writes FORM_V2 (compact, as the real one does) to the --filepath file and exits. */
    private void fakeBuilder(String body) throws Exception {
        Assume.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        Path lib = tmp.newFolder("lib").toPath();
        Path exe = lib.resolve(FormBuilderLocator.Os.current().exeInLib);
        Files.createDirectories(exe.getParent());
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n");
        exe.toFile().setExecutable(true, false);
        oldProp = System.getProperty("formbuilder");
        System.setProperty("formbuilder", exe.toString());
    }

    @Test
    public void editSavesThroughTheTransactionAndSyncsBindings() throws Exception {
        Path v2 = tmp.newFile("v2.json").toPath();
        Files.writeString(v2, BindingSyncTest.FORM_V2);
        fakeBuilder("for a in \"$@\"; do case \"$a\" in --filepath=*) f=\"${a#--filepath=}\";; esac; done\n"
            + "case \"$f\" in *.form) ;; *) echo \"unexpected file $f\" >&2; exit 3;; esac\n"
            + "cp \"" + v2 + "\" \"$f\"; exit 0");
        Path tree = FormOpsTest.tree(tmp, false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out));
        int rc;
        try {
            rc = FormEditCli.run(new String[] {"form.edit", tree.toString(), "Req", "--json"});
        } finally {
            System.setOut(old);
        }
        assertEquals(out.toString(), 0, rc);
        assertTrue(out.toString(), out.toString().contains("\"operation\":\"form.set-content\""));
        assertTrue(out.toString(), out.toString().contains("bound field 'justification'"));

        String stored = Files.readString(tree.resolve("drivers/UA/provisioning/forms/request/Req.form.json"));
        assertEquals(Json.parse(BindingSyncTest.FORM_V2), Json.parse(stored));
        assertTrue(stored.startsWith("{\n  \"components\""));
        assertEquals(5, AsCodeReader.read(tree).drivers.get(0).provisioning.prd("P").bindings().get(0).fields.size());
    }

    @Test
    public void closingWithoutSavingChangesNothing() throws Exception {
        fakeBuilder("exit 0");
        Path tree = FormOpsTest.tree(tmp, false);
        String before = Files.readString(tree.resolve("drivers/UA/provisioning/forms/request/Req.form.json"));
        assertEquals(0, FormEditCli.run(new String[] {"form.edit", tree.toString(), "Req"}));
        assertEquals(before, Files.readString(tree.resolve("drivers/UA/provisioning/forms/request/Req.form.json")));
    }

    @Test
    public void checkReportsTheBuilderAndUnknownFormsFail() throws Exception {
        fakeBuilder("exit 0");
        Path tree = FormOpsTest.tree(tmp, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(out));
        try {
            assertEquals(0, FormEditCli.run(new String[] {"form.edit", tree.toString(), "--check"}));
        } finally {
            System.setOut(old);
        }
        assertTrue(out.toString(), out.toString().contains("ready"));
        assertEquals(1, FormEditCli.run(new String[] {"form.edit", tree.toString(), "Nope"}));
    }

    @Test
    public void noWaitLaunchesOnTheTreeFile() throws Exception {
        fakeBuilder("for a in \"$@\"; do case \"$a\" in --filepath=*) f=\"${a#--filepath=}\";; esac; done\n"
            + "case \"$f\" in */drivers/UA/provisioning/forms/request/Req.form.json) exit 0;; *) exit 3;; esac");
        Path tree = FormOpsTest.tree(tmp, false);
        assertEquals(0, FormEditCli.run(new String[] {"form.edit", tree.toString(), "Req", "--no-wait"}));
    }
}
