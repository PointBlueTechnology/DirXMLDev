package com.pointblue.dirxml.dev.ascode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pointblue.dirxml.dev.model.AppObject;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.source.AppConfigLdifReaderTest;
import com.pointblue.dirxml.dev.source.LdifReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** AppConfig objects as code: one ds-object file per object under {@code provisioning/objects/}, manifest entries, round trip. */
public class AppConfigAsCodeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void layoutAndRoundTrip() throws Exception {
        DriverSet ds = LdifReader.fromEntries(AppConfigLdifReaderTest.entries(), "synthetic");
        Path out = tmp.newFolder("tree").toPath();
        AsCodeWriter.write(ds, out);
        Path prov = out.resolve("drivers/UA/provisioning");
        assertTrue(Files.exists(prov.resolve("objects/DirectoryModel/EntityDefs/user.xml")));
        assertTrue(Files.exists(prov.resolve("objects/DirectoryModel.xml")));
        assertTrue(Files.exists(prov.resolve("objects/RoleConfig/RoleDefs/Level20/System/provManager.xml")));
        String userFile = Files.readString(prov.resolve("objects/DirectoryModel/EntityDefs/user.xml"), StandardCharsets.UTF_8);
        assertTrue(userFile.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ds-object ds-object-class=\"srvprvEntity\" ds-object-name=\"user\">"));
        assertTrue("XML attribute written as XML, not escaped text", userFile.contains("<entity-definition>"));
        assertTrue(!userFile.contains("&lt;entity-definition"));
        String manifest = Files.readString(prov.resolve("provisioning.xml"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("<object kind=\"entity\" path=\"DirectoryModel/EntityDefs/user\" file=\"objects/DirectoryModel/EntityDefs/user.xml\">"));
        assertTrue(manifest.contains("<meta key=\"dirxml-pkgguid\">PKGID;com.x.y;1.0.0</meta>"));

        DriverSet back = AsCodeReader.read(out);
        Provisioning p = back.driver("UA").provisioning;
        assertEquals(ds.driver("UA").provisioning.objects.size(), p.objects.size());
        for (AppObject a : ds.driver("UA").provisioning.objects) {
            AppObject b = p.object(a.path());
            assertNotNull(a.path(), b);
            assertEquals(a.path(), a.classes, b.classes);
            assertEquals(a.path(), a.attrs, b.attrs);
            assertEquals(a.path(), a.meta, b.meta);
        }
        Path again = tmp.newFolder("again").toPath();
        AsCodeWriter.write(back, again);
        assertEquals(Files.readString(prov.resolve("provisioning.xml")), Files.readString(again.resolve("drivers/UA/provisioning/provisioning.xml")));
        assertEquals(userFile, Files.readString(again.resolve("drivers/UA/provisioning/objects/DirectoryModel/EntityDefs/user.xml")));
    }

    @Test
    public void dsObjectFormatIsDesignersShape() {
        AppObject o = AppObject.ofPath("AppDefs/locale-configuration");
        o.classes.addAll(List.of("Top", "srvprvWebAppConfig"));
        o.put("description", List.of("Locale <config> & more"));
        o.put("XmlData", List.of("<configuration><locale code=\"en\"/></configuration>"));
        String xml = DsObjectXml.write(o);
        assertTrue(xml.contains("<ds-attribute ds-attr-name=\"description\">\n      <ds-value>Locale &lt;config&gt; &amp; more</ds-value>"));
        assertTrue(xml.contains("<ds-attribute ds-attr-name=\"XmlData\">\n      <ds-value>\n<configuration>"));
        AppObject back = DsObjectXml.read(xml, o.segments);
        assertEquals(o.classes, back.classes);
        assertEquals("Locale <config> & more", back.first("description"));
        assertEquals(DsObjectXml.asXml("<configuration><locale code=\"en\"/></configuration>"), back.first("XmlData"));
        assertEquals(AppObject.Kind.WEB_APP_CONFIG, back.kind());
    }
}
