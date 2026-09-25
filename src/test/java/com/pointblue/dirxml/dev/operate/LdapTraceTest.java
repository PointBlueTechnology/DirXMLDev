package com.pointblue.dirxml.dev.operate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.novell.ldap.events.edir.EdirEventConstant;
import com.pointblue.dirxml.sim.EdirTraceStream;
import org.junit.Test;

/** One driver's lines out of the whole engine's stream, continuation lines following their driver. */
public class LdapTraceTest {

    private static final int DRV = EdirEventConstant.EVT_DB_DIRXML_DRIVERS;

    private static EdirTraceStream.Line line(String raw) {
        return EdirTraceStream.parse(DRV, 7, raw, 0L);
    }

    @Test
    public void keepsOneDriverAndItsContinuations() {
        LdapTrace t = new LdapTrace("User Application Driver", null);
        assertNull(t.accept(line("%10CRole and Resource driver ST: DirXML Log Event")));
        String first = t.accept(line("%10CUser Application Driver PT: javax.net.ssl.SSLPeerUnverifiedException: peer not authenticated"));
        assertTrue(first, first.endsWith("] PT: javax.net.ssl.SSLPeerUnverifiedException: peer not authenticated"));
        String cont = t.accept(line("%12C\tat sun.security.ssl.SSLSessionImpl.getPeerCertificates(Unknown Source)"));
        assertTrue(cont, cont.endsWith("] \tat sun.security.ssl.SSLSessionImpl.getPeerCertificates(Unknown Source)"));
        assertNull("a continuation after another driver's line belongs to that driver",
            new LdapTrace("User Application Driver", null).accept(line("%12C\tat x")));
        assertNull(t.accept(line("%10CData Collection Service Driver ST: something")));
        assertNull("the continuation now follows the DCS line", t.accept(line("%12C\tat y")));
    }

    @Test
    public void everyDriverKeepsThePrefixAndGrepFilters() {
        LdapTrace all = new LdapTrace(null, "Log Event");
        String s = all.accept(line("%10CRole and Resource driver ST: DirXML Log Event"));
        assertTrue(s, s.endsWith("] Role and Resource driver ST: DirXML Log Event"));
        assertNull(all.accept(line("%10CRole and Resource driver ST: Updating DirXML-DriverStorage")));
        assertEquals("[HH:mm:ss.mmm] is 15 characters, then the driver", 15, s.indexOf("Role"));
    }
}
