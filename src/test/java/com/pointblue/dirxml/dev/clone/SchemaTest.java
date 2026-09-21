package com.pointblue.dirxml.dev.clone;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SchemaTest {

    static final List<String> ATTRS = List.of(
        "( 2.5.4.3 NAME 'cn' SUP name )",
        "( 2.5.4.4 NAME 'sn' SUP name )",
        "( 2.5.4.41 NAME 'name' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Name' )",
        "( 2.5.4.31 NAME 'member' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Member' )",
        "( 2.16.840.1.113719.1.14.4.1.44 NAME 'DirXML-ServerList' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 )",
        "( 2.16.840.1.113719.1.14.4.1.45 NAME 'DirXML-Policies' SYNTAX 2.16.840.1.113719.1.1.5.1.25 )",
        "( 2.16.840.1.113719.1.14.4.1.46 NAME 'DirXML-Associations' SYNTAX 2.16.840.1.113719.1.1.5.1.15{64512} USAGE directoryOperation X-NDS_OPERATIONAL '1' )",
        "( 2.16.840.1.113719.1.1.4.1.2 NAME 'ACL' SYNTAX 2.16.840.1.113719.1.1.5.1.17 X-NDS_NAME 'ACL' )",
        "( 2.16.840.1.113719.1.1.4.1.501 NAME 'GUID' SYNTAX 1.3.6.1.4.1.1466.115.121.1.40{16} SINGLE-VALUE NO-USER-MODIFICATION )",
        "( 2.16.840.1.113719.1.1.4.1.35 NAME 'Back Link' SYNTAX 2.16.840.1.113719.1.1.5.1.23 USAGE directoryOperation )",
        "( 2.16.840.1.113719.1.14.4.1.6 NAME 'XmlData' SYNTAX 1.3.6.1.4.1.1466.115.121.1.5 SINGLE-VALUE )",
        "( 2.16.840.1.113719.1.14.4.1.9 NAME ( 'DirXML-DriverStartOption' 'dirxmlStart' ) SYNTAX 1.3.6.1.4.1.1466.115.121.1.27 X-NDS_NEVER_SYNC '1' )",
        "( 2.16.840.1.113719.1.14.4.1.40 NAME 'DirXML-ShimAuthPassword' SYNTAX 1.3.6.1.4.1.1466.115.121.1.40 SINGLE-VALUE X-NDS_NEVER_SYNC '1' )",
        "( 2.16.840.1.113719.1.14.4.1.10 NAME 'DirXML-ShimConfigInfo' SYNTAX 1.3.6.1.4.1.1466.115.121.1.5 SINGLE-VALUE X-NDS_NEVER_SYNC '1' )",
        "( 2.16.840.1.113719.1.14.4.1.11 NAME 'DirXML-State' SYNTAX 1.3.6.1.4.1.1466.115.121.1.27 SINGLE-VALUE X-NDS_NEVER_SYNC '1' )",
        "( 2.16.840.1.113719.1.14.4.1.43 NAME 'DirXML-DriverImage' SYNTAX 1.3.6.1.4.1.1466.115.121.1.5 SINGLE-VALUE )",
        "( 2.16.840.1.113719.1.1.4.1.4 NAME 'securityEquals' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Security Equals' )",
        "( 2.16.840.1.113719.1.1.4.1.5 NAME 'equivalentToMe' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Equivalent To Me' )",
        "( 2.16.840.1.113719.1.39.43.4.5 NAME 'nspmPasswordPolicyDN' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 SINGLE-VALUE )",
        "( 2.5.4.42 NAME 'givenName' SUP name )",
        "( 0.9.2342.19200300.100.1.3 NAME 'mail' SYNTAX 1.3.6.1.4.1.1466.115.121.1.26 )",
        "( 2.16.840.1.113719.1.1.4.1.1 NAME 'fullName' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Full Name' )",
        "( 2.16.840.1.113730.3.1.241 NAME 'displayName' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )",
        "( 2.5.4.35 NAME 'userPassword' SYNTAX 1.3.6.1.4.1.1466.115.121.1.40 )",
        "( 2.16.840.1.113719.1.1.4.1.290 NAME 'loginDisabled' SYNTAX 1.3.6.1.4.1.1466.115.121.1.7 SINGLE-VALUE )",
        "( 0.9.2342.19200300.100.1.10 NAME 'manager' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 )");

    static final List<String> CLASSES = List.of(
        "( 2.5.6.0 NAME 'Top' STRUCTURAL MUST objectClass MAY ( cn $ ACL $ GUID $ securityEquals $ equivalentToMe $ nspmPasswordPolicyDN ) X-NDS_NONREMOVABLE '1' )",
        "( 2.5.6.4 NAME 'Organization' SUP Top STRUCTURAL MUST o X-NDS_NAMING 'o' )",
        "( 2.5.6.5 NAME 'organizationalUnit' SUP Top STRUCTURAL MUST ou )",
        "( 2.5.6.9 NAME 'groupOfNames' SUP Top STRUCTURAL MUST cn MAY ( member ) )",
        "( 2.5.6.6 NAME 'Person' SUP Top STRUCTURAL MUST ( cn $ sn ) )",
        "( 2.16.840.1.113730.3.2.2 NAME 'inetOrgPerson' SUP Person STRUCTURAL MAY ( givenName $ mail $ fullName $ displayName $ userPassword $ loginDisabled $ manager $ DirXML-Associations ) )",
        "( 2.16.840.1.113719.1.1.6.1.9 NAME 'ncpServer' SUP Top STRUCTURAL MUST cn )",
        "( 2.16.840.1.113719.1.14.6.1.1 NAME 'DirXML-DriverSet' SUP Top STRUCTURAL MUST cn MAY ( DirXML-ServerList $ DirXML-Policies ) )",
        "( 2.16.840.1.113719.1.14.6.1.2 NAME 'DirXML-Driver' SUP Top STRUCTURAL MUST cn MAY ( DirXML-DriverStartOption $ DirXML-ShimAuthPassword $ DirXML-Policies $ DirXML-DriverImage $ DirXML-ShimConfigInfo $ DirXML-State ) X-NDS_CONTAINMENT 'DirXML-DriverSet' )",
        "( 2.16.840.1.113719.1.14.6.1.20 NAME 'DirXML-Job' SUP Top STRUCTURAL MUST ( cn $ DirXML-ServerList ) )",
        "( 2.16.840.1.113719.1.14.6.1.3 NAME 'DirXML-Rule' SUP Top STRUCTURAL MUST cn MAY XmlData )",
        "( 2.16.840.1.113719.1.1.6.1.11 NAME 'Partition' AUXILIARY )",
        "( 2.16.840.1.113719.1.39.42.6.1 NAME 'sASSecurity' SUP Top STRUCTURAL MUST cn )",
        "( 2.16.840.1.113719.1.39.43.6.1 NAME 'nspmPasswordPolicyContainer' SUP Top STRUCTURAL MUST cn )",
        "( 2.16.840.1.113719.1.39.43.6.2 NAME 'nspmPasswordPolicy' SUP Top STRUCTURAL MUST cn )",
        "( 2.16.840.1.113719.1.39.42.6.7 NAME 'nDSPKISDKeyAccessPartition' SUP Top STRUCTURAL MUST cn )",
        "( 2.16.840.1.113719.1.135.6.1 NAME 'rbsCollection2' SUP Top STRUCTURAL MUST cn )",
        "( 1.2.3.4.5 NAME 'customBase' SUP Top STRUCTURAL MUST cn )",
        "( 1.2.3.4.6 NAME 'customLeaf' SUP customBase STRUCTURAL MAY member )");

    static Schema sample() {
        return Schema.of(ATTRS, CLASSES);
    }

    @Test
    public void parsesNamesSupMustMaySyntaxAndFlags() {
        Schema s = sample();
        assertEquals("cn", s.attribute("cn").name());
        assertEquals("name", s.attribute("cn").sup);
        assertEquals("1.3.6.1.4.1.1466.115.121.1.15", s.syntaxOf("cn"));            // through SUP
        assertEquals("1.3.6.1.4.1.1466.115.121.1.40", s.attribute("GUID").syntax);    // {16} stripped
        assertTrue(s.attribute("GUID").noUserModification);
        assertTrue(s.attribute("Back Link").operational);
        assertEquals(List.of("DirXML-DriverStartOption", "dirxmlStart"), s.attribute("dirxmlStart").names);
        Schema.Def job = s.objectClass("DirXML-Job");
        assertEquals(List.of("Top"), job.sups);
        assertTrue(job.must.contains("DirXML-ServerList"));
        assertTrue(s.objectClass("DirXML-Driver").may.contains("DirXML-Policies"));
        assertEquals(26, s.attributes().size());
        assertTrue(s.neverSync("DirXML-ShimConfigInfo"));
        assertFalse(s.neverSync("cn"));
        assertEquals(List.of("DirXML-DriverStartOption", "DirXML-ShimAuthPassword", "DirXML-ShimConfigInfo", "DirXML-State"), s.neverSyncAttributes());
        assertEquals(19, s.classes().size());
    }

    @Test
    public void classifiesAttributesForTheClone() {
        Schema s = sample();
        assertTrue(s.carriesDn("member"));
        assertTrue(s.carriesDn("DirXML-Policies"));            // Typed Name
        assertTrue(s.carriesDn("DirXML-Associations"));        // Path
        assertFalse(s.carriesDn("cn"));
        assertTrue(s.isAcl("ACL"));
        assertFalse(s.carriesDn("ACL"));
        assertTrue(s.isOperational("GUID"));
        assertTrue(s.isOperational("Back Link"));
        assertFalse(s.isOperational("member"));
        assertFalse("USAGE directoryOperation alone is not server-owned", s.isOperational("DirXML-Associations"));
        assertTrue(s.attribute("DirXML-Associations").operational);
        assertTrue(s.isBinary("XmlData"));
        assertTrue(s.isBinary("DirXML-ShimAuthPassword"));
        assertFalse(s.isBinary("cn"));
        assertTrue(s.binaryAttributes().contains("DirXML-DriverImage"));
        assertTrue(s.mustHave("DirXML-Job", "DirXML-ServerList"));
        assertFalse(s.mustHave("DirXML-DriverSet", "DirXML-ServerList"));
        assertTrue(s.mustHave("inetOrgPerson", "sn"));         // through SUP Person
        assertNull(s.syntaxOf("nosuch"));
    }

    @Test
    public void deltaOrdersSuperiorsFirstAndReportsConflicts() {
        Schema source = sample();
        Schema target = Schema.of(
            List.of("( 2.5.4.41 NAME 'name' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 X-NDS_NAME 'Name' )",
                "( 2.5.4.31 NAME 'member' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Member' X-NDS_UPPER_BOUND '-1' )"),
            List.of("( 2.5.6.0 NAME 'Top' STRUCTURAL MUST objectClass MAY ( cn $ ACL $ GUID $ securityEquals $ equivalentToMe $ nspmPasswordPolicyDN ) X-NDS_NONREMOVABLE '1' )"));
        Schema.Delta d = Schema.delta(source, target);
        assertEquals(24, d.attributes.size());                  // 26 - name - member
        assertEquals(18, d.classes.size());                     // 19 - Top
        assertTrue(d.conflicts.toString(), d.conflicts.isEmpty());   // an extra X-NDS bound on the target is rendering, not meaning
        // customBase before customLeaf; both after Top-derived ones is irrelevant, but the SUP order holds
        int base = -1;
        int leaf = -1;
        for (int i = 0; i < d.classes.size(); i++) {
            if (d.classes.get(i).name().equals("customBase")) {
                base = i;
            }
            if (d.classes.get(i).name().equals("customLeaf")) {
                leaf = i;
            }
        }
        assertTrue(base >= 0 && leaf > base);
        // Person before inetOrgPerson
        int person = -1;
        int inet = -1;
        for (int i = 0; i < d.classes.size(); i++) {
            if (d.classes.get(i).name().equals("Person")) {
                person = i;
            }
            if (d.classes.get(i).name().equals("inetOrgPerson")) {
                inet = i;
            }
        }
        assertTrue(inet > person);
        assertTrue(Schema.delta(source, source).isEmpty());
    }

    /** eDirectory re-renders every definition it stores; only a change of meaning is a conflict. */
    @Test
    public void renderingDifferencesAreNotConflicts() {
        Schema source = Schema.of(List.of(
            "( 2.5.4.31 NAME 'member' SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 X-NDS_NAME 'Member' )",
            "( 1.2.3.4.1 NAME 'photo' SYNTAX 1.3.6.1.4.1.1466.115.121.1.40 )",
            "( 1.2.3.4.2 NAME 'flag' SYNTAX 1.3.6.1.4.1.1466.115.121.1.7 SINGLE-VALUE )"),
            List.of("( 2.5.6.9 NAME 'groupOfNames' SUP Top STRUCTURAL MUST cn MAY ( member $ photo ) X-NDS_NONREMOVABLE '1' )"));
        Schema target = Schema.of(List.of(
            "( 2.5.4.31 NAME 'member'  SYNTAX 1.3.6.1.4.1.1466.115.121.1.12{64512} X-NDS_NAME 'Member' X-NDS_UPPER_BOUND '-1' X-NDS_NAME_VALUE_ACCESS '1' )",
            "( 1.2.3.4.1 NAME 'photo' SYNTAX 1.3.6.1.4.1.1466.115.121.1.5 )",
            "( 1.2.3.4.2 NAME 'flag' SYNTAX 1.3.6.1.4.1.1466.115.121.1.7 )"),
            List.of("( 2.5.6.9 NAME 'groupOfNames' SUP Top STRUCTURAL MUST cn MAY ( photo $ member ) )"));
        Schema.Delta d = Schema.delta(source, target);
        assertTrue(d.isEmpty());
        assertEquals(List.of("flag", "photo"), List.copyOf(new java.util.TreeSet<>(d.conflicts.keySet())));   // syntax changed, single-value changed
    }
}
