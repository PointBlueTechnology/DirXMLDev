package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.pointblue.dirxml.dev.operate.TraceViewer;
import java.util.List;
import org.junit.Test;

/** The viewer's connect arguments from an environment: URL, bind DN, search base above the driver set, the driver; never the password. */
public class TraceViewerArgsTest {

    @Test
    public void connectArgsFromAnEnvironment() {
        Environments.Environment env = new Environments.Environment("lab", "ldaps://vault:636", "cn=admin,o=system", "pw",
            "cn=driverset1,o=system", Environments.Tier.DEV, null, null, true, null, null);
        List<String> connect = TraceViewer.connectArgs(env, "AD Driver");
        assertEquals(List.of("--connect", "ldaps://vault:636", "--bind-dn", "cn=admin,o=system", "--password-stdin",
            "--search-base", "o=system", "--driver", "AD Driver"), connect);
        assertFalse("the password is never an argument", String.join(" ", connect).contains("pw"));
        assertEquals(5, TraceViewer.connectArgs(env, null).size() - 2);
    }
}
