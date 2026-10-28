package com.pointblue.dirxml.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.pointblue.dirxml.dev.deploy.DeployCli;

import java.util.List;
import org.junit.Test;

/** A command line read against what the command takes (#104, #105). */
public class ArgsTest {

    private static final Args.Spec SPEC = Args.Spec.of("yes dry-run", "env driver");

    private static String refused(String... argv) {
        try {
            Args.parse("cmd", argv, 0, SPEC);
        } catch (Args.UsageError e) {
            return e.getMessage();
        }
        fail("accepted " + List.of(argv));
        return null;
    }

    @Test
    public void aFlagNeverTakesTheNextWord() throws Exception {
        Args a = Args.parse("cmd", new String[] {"--json", "tree", "--yes", "more", "--env", "test"}, 0, SPEC);
        assertEquals(List.of("tree", "more"), a.positional);
        assertTrue(a.has("json") && a.has("yes"));
        assertEquals("test", a.first("env"));
    }

    @Test
    public void nameEqualsValueIsSplitAndOptionsRepeat() throws Exception {
        Args a = Args.parse("cmd", new String[] {"--env=test", "--driver", "A", "--driver=B"}, 0, SPEC);
        assertEquals("test", a.first("env"));
        assertEquals(List.of("A", "B"), a.all("driver"));
    }

    @Test
    public void whatTheCommandDoesNotTakeIsAUsageError() {
        assertTrue(refused("--envv", "test").contains("has no option --envv"));
        assertTrue(refused("--yes=true").contains("--yes takes no value"));
        assertTrue(refused("--env").contains("--env needs a value"));
        assertTrue(refused("--env", "--yes").contains("--env needs a value"));
    }

    @Test
    public void numbersAndListedValuesAreChecked() throws Exception {
        Args a = Args.parse("cmd", new String[] {"--env", "12", "--driver", "x"}, 0, SPEC);
        assertEquals(12, a.intOpt("env", 0));
        assertEquals(7, a.intOpt("missing", 7));
        try {
            a.intOpt("driver", 0);
            fail();
        } catch (Args.UsageError e) {
            assertTrue(e.getMessage().contains("--driver needs a whole number, not 'x'"));
        }
        try {
            a.oneOf("driver", "a", List.of("a", "b"));
            fail();
        } catch (Args.UsageError e) {
            assertTrue(e.getMessage().contains("--driver is a, b, not 'x'"));
        }
    }

    /** #104's example: the tree after --json used to become --json's value. */
    @Test
    public void vaultDiffKeepsTheTreeAfterJsonAndRefusesATypo() throws Exception {
        assertEquals(2, DeployCli.run(new String[] {"vault.diff", "--json", "tree", "--envv", "test"}));
        assertEquals(2, DeployCli.run(new String[] {"vault.deploy", "tree", "--env", "test", "--yes=1"}));
    }
}
