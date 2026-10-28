package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertEquals;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.deploy.DeployCli;
import com.pointblue.dirxml.dev.edit.ReadCli;

import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** A value outside the listed ones is a usage error, and a missing driver a refusal (#106, #107). */
public class CliValuesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String tree() throws Exception {
        Path dir = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(SyntheticDriverSet.model(), dir);
        return dir.toString();
    }

    @Test
    public void anUnlistedSecretsModeIsAUsageError() throws Exception {
        // refused before the environment is loaded: "al" used to deploy as if it were "none"
        assertEquals(2, DeployCli.run(new String[] {"vault.deploy", "tree", "--env", "test", "--secrets", "al"}));
    }

    @Test
    public void theChainChannelIsSubOrPub() throws Exception {
        String t = tree();
        assertEquals(2, ReadCli.query(new String[] {"query", t, "chain", SyntheticDriverSet.DRIVER, "sideways"}));
        assertEquals(0, ReadCli.query(new String[] {"query", t, "chain", SyntheticDriverSet.DRIVER, "publisher"}));
        assertEquals(0, ReadCli.query(new String[] {"query", t, "chain", SyntheticDriverSet.DRIVER, "sub"}));
    }

    @Test
    public void anUnknownDriverIsARefusalEverywhere() throws Exception {
        String t = tree();
        assertEquals(1, ReadCli.prdList(new String[] {"prd.list", t, "--driver", "Nope"}));
        assertEquals(1, ReadCli.formList(new String[] {"form.list", t, "--driver", "Nope"}));
        assertEquals(1, ReadCli.query(new String[] {"query", t, "schema", "Nope"}));
        assertEquals(1, ReadCli.query(new String[] {"query", t, "gcvs", "Nope"}));
    }
}
