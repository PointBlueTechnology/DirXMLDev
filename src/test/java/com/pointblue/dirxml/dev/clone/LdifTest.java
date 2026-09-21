package com.pointblue.dirxml.dev.clone;

import com.pointblue.dirxml.dev.deploy.Ldif;
import com.pointblue.dirxml.dev.deploy.Vault;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LdifTest {

    @Test
    public void contentRecordsRoundTripWithBinaryAndLongValues() {
        Vault.Entry e = new Vault.Entry("cn=x,o=system");
        e.attrs.put("objectClass", List.of("Top".getBytes(StandardCharsets.UTF_8), "DirXML-Rule".getBytes(StandardCharsets.UTF_8)));
        byte[] gif = new byte[] {'G', 'I', 'F', '8', '9', 'a', 0, (byte) 0xFF, 1};
        e.attrs.put("DirXML-DriverImage", List.of(gif));
        String longText = "x".repeat(300);
        e.attrs.put("description", List.of(longText.getBytes(StandardCharsets.UTF_8), " leading space".getBytes(StandardCharsets.UTF_8)));
        String text = "version: 1\n# a comment\n\n" + Ldif.entry(e) + "\n";
        assertTrue(text, text.contains("DirXML-DriverImage:: "));
        assertTrue(text, text.contains("description:: IGxlYWRpbmcgc3BhY2U="));
        List<Ldif.Record> records = Ldif.parse(text);
        assertEquals(1, records.size());
        Ldif.Record r = records.get(0);
        assertNull(r.changeType);
        assertEquals("cn=x,o=system", r.dn);
        assertArrayEquals(gif, r.entry.attrs.get("DirXML-DriverImage").get(0));
        assertEquals(longText, new String(r.entry.attrs.get("description").get(0), StandardCharsets.UTF_8));
        assertEquals(" leading space", new String(r.entry.attrs.get("description").get(1), StandardCharsets.UTF_8));
        assertEquals(List.of("Top", "DirXML-Rule"), r.entry.objectClasses());
    }

    @Test
    public void modifyRecordsRoundTrip() {
        Map<String, List<byte[]>> attrs = Map.of("member", List.of("cn=a,o=x".getBytes(StandardCharsets.UTF_8), "cn=b,o=x".getBytes(StandardCharsets.UTF_8)));
        String text = Ldif.modify("cn=g,o=x", "add", attrs) + "\n";
        assertTrue(text, text.contains("changetype: modify\nadd: member\nmember: cn=a,o=x\nmember: cn=b,o=x\n-"));
        Ldif.Record r = Ldif.parse(text).get(0);
        assertEquals("modify", r.changeType);
        assertEquals(1, r.ops.size());
        assertEquals("add", r.ops.get(0).op);
        assertEquals("member", r.ops.get(0).attr);
        assertEquals(2, Ldif.adds(r).get("member").size());
    }
}
