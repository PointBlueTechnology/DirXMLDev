package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The known-state gate checks out the last deployed commit with git and tar as argument lists, from a SHA only. */
public class DeployerCheckoutTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String git(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git", "-C", repo.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assumeTrue("git available: " + out, p.waitFor() == 0);
        return out.trim();
    }

    @Test
    public void checksOutASha_andRefusesAnythingElse() throws Exception {
        Path repo = tmp.newFolder("tree").toPath();
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@example");
        git(repo, "config", "user.name", "t");
        Files.writeString(repo.resolve("driverset.xml"), "<driverset name=\"x\"/>", StandardCharsets.UTF_8);
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "one");
        String sha = git(repo, "rev-parse", "HEAD");

        Path out = Deployer.checkoutAt(repo, sha);
        assertTrue(out != null && Files.isRegularFile(out.resolve("driverset.xml")));
        Deployer.deleteRecursively(out);

        assertNull("not a SHA: never handed to git", Deployer.checkoutAt(repo, "HEAD"));
        assertNull("not a SHA: never handed to git", Deployer.checkoutAt(repo, sha.substring(0, 12) + "; echo injected"));
        assertNull("an unknown SHA", Deployer.checkoutAt(repo, "0123456789abcdef0123456789abcdef01234567"));
    }
}
