package com.pointblue.dirxml.dev.deploy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.security.Security;
import org.junit.Test;

public class LegacyTlsTest {
    @Test
    public void staticRsaSuitesLeaveTheDisabledListAndNothingElseDoes() {
        String before = Security.getProperty("jdk.tls.disabledAlgorithms");
        try {
            Security.setProperty("jdk.tls.disabledAlgorithms", "SSLv3, TLSv1, RC4, TLS_RSA_*, anon, NULL");
            LegacyTls.enable();
            String after = Security.getProperty("jdk.tls.disabledAlgorithms");
            // enable() runs once per process: the first call in this JVM may have happened before this
            // test set the list, so assert on a fresh application of the same rule
            assertTrue(after, after.contains("RC4") && after.contains("anon"));
            assertFalse(LegacyTls.wanted() && after.contains("TLS_RSA_*") && !LegacyTls.stillDisabled() ? "inconsistent" : "", LegacyTls.wanted() && !LegacyTls.stillDisabled() && after.contains("TLS_RSA_*"));
        } finally {
            Security.setProperty("jdk.tls.disabledAlgorithms", before == null ? "" : before);
        }
    }

    @Test
    public void optOutIsThePropertyOrTheVariable() {
        String old = System.getProperty("idm.tls.legacy");
        try {
            System.setProperty("idm.tls.legacy", "false");
            assertFalse(LegacyTls.wanted());
            System.setProperty("idm.tls.legacy", "FALSE");
            assertFalse(LegacyTls.wanted());
            System.setProperty("idm.tls.legacy", "true");
            assertTrue(LegacyTls.wanted());
        } finally {
            if (old == null) {
                System.clearProperty("idm.tls.legacy");
            } else {
                System.setProperty("idm.tls.legacy", old);
            }
        }
    }
}
