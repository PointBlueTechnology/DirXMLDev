package com.pointblue.dirxml.dev.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AppObjectTest {

    @Test
    public void pathNameParentAndKind() {
        AppObject o = AppObject.ofPath("RoleConfig/RoleDefs/Level20/System/provManager");
        o.classes.addAll(List.of("DirXML-PkgItemAux", "Top", "nrfRole"));
        assertEquals("provManager", o.name());
        assertEquals("RoleConfig/RoleDefs/Level20/System", o.parentPath());
        assertEquals("nrfRole", o.structuralClass());
        assertEquals(AppObject.Kind.ROLE, o.kind());
        assertFalse(o.isContainer());
    }

    @Test
    public void containersAndUnknownClasses() {
        AppObject c = AppObject.ofPath("DirectoryModel");
        c.classes.addAll(List.of("Top", "srvprvDirectoryModel"));
        assertTrue(c.isContainer());
        assertEquals("", c.parentPath());
        AppObject x = AppObject.ofPath("Something/odd");
        x.classes.addAll(List.of("Top", "someVendorClass"));
        assertEquals(AppObject.Kind.OTHER, x.kind());
        assertEquals("someVendorClass", x.structuralClass());
    }

    @Test
    public void attributeNamesAreCanonicalAndCaseInsensitive() {
        AppObject o = AppObject.ofPath("UIConfig/NavItems/x");
        o.put("nrflocalizednames", List.of("de~Zugriff|en~Access Report"));
        assertEquals("de~Zugriff|en~Access Report", o.first("NRFLOCALIZEDNAMES"));
        assertTrue(o.attrs.containsKey("nrfLocalizedNames"));
        assertEquals("Access Report", o.displayName());
        assertNull(o.first("missing"));
    }

    @Test
    public void policyTables() {
        assertTrue(AppConfigPolicy.isRuntimePath("RoleConfig/Requests/20240101-abc"));
        assertFalse(AppConfigPolicy.isRuntimePath("RoleConfig/Requests"));
        assertTrue(AppConfigPolicy.isRuntimeClass("nrfRequest"));
        assertTrue(AppConfigPolicy.isOperational("equivalentToMe"));
        assertTrue(AppConfigPolicy.isOperational("DirXML-Associations"));
        assertFalse(AppConfigPolicy.isOperational("ACL"));
        assertTrue(AppConfigPolicy.isXmlAttribute("xmldata"));
        assertTrue(AppConfigPolicy.isNotContent("DirXML-pkgGUID"));
        assertEquals("nrfResourceParms", AppConfigPolicy.canonicalAttribute("nrfresourceparms"));
        assertEquals("customThing", AppConfigPolicy.canonicalAttribute("customThing"));
        Map<String, String> l = AppConfigPolicy.localized("en~Name|fr~Nom");
        assertEquals("Nom", l.get("fr"));
        assertTrue(AppConfigPolicy.isWellFormedLocalized("en~A|de~B"));
        assertFalse(AppConfigPolicy.isWellFormedLocalized("just text"));
    }
}
