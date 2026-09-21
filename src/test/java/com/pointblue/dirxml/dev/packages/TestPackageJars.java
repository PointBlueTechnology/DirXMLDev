package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds tiny, valid Designer package jars for tests: MANIFEST + plugin.xml +
 * a {@code package_import.xml} whose stored checksums are computed with
 * {@link PackageChecksum} so {@link ChecksumAudit} passes cleanly (folder 1
 * only, so the package-level {@code checksum} attribute is also exact — no
 * reliance on the "known mismatch" carve-outs).
 */
public final class TestPackageJars {

    private TestPackageJars() {
    }

    public static final class Obj {
        final String objectClass;
        final String name;
        final String xmlContent;     // XmlData content, e.g. "<policy>...</policy>"
        final String directiveXml;   // the item's raw stored installation-directive string
        final String assocId;
        final String guid;
        Long forcedContentChecksum;  // when set, store this instead of the real recomputed value (for negative tests)

        public Obj(String objectClass, String name, String xmlContent, String directiveXml, String assocId, String guid) {
            this.objectClass = objectClass;
            this.name = name;
            this.xmlContent = xmlContent;
            this.directiveXml = directiveXml;
            this.assocId = assocId;
            this.guid = guid;
        }
    }

    public static final class Spec {
        public String id;
        public String shortName;
        public String symbolicName;
        public String displayName = "Test Package";
        public String version;
        public int type = 2;
        public boolean basePackage;
        /** {@code package/@category} — one of Designer's six stock ones, or anything else. */
        public String category = "Test";
        /** {@code package/@category-folder}, the {@code IdmCategoryFolder_} the package is filed in. */
        public String categoryFolder = "Test";
        // embedded inline in package_import.xml (not a separately-decoded document, unlike an item's directive) — no XML declaration
        public String installDirectiveXml = "<installation-directive><ds-attributes/></installation-directive>";
        public List<Obj> objects = new ArrayList<>();
        /** A {@code <provisioning>} ds-object document (the AppConfig tree) to carry base64 in a Provisioning folder (id 5), or null. */
        public String provisioningXml;
    }

    /** Builds the jar bytes and writes them under {@code dir}, returning the path. */
    public static Path build(Path dir, Spec s) throws IOException {
        Map<String, String> assocToChecksum = new LinkedHashMap<>();
        StringBuilder children = new StringBuilder();
        for (Obj o : s.objects) {
            Element contentEl = NxslCanonical.parse(o.xmlContent).getDocumentElement();
            long realContentChecksum = PackageChecksum.content(o.objectClass, o.name, contentEl, null, null, List.of());
            long storedContentChecksum = o.forcedContentChecksum != null ? o.forcedContentChecksum : realContentChecksum;
            long directiveChecksum = PackageChecksum.directive(o.directiveXml);
            String directiveB64 = Base64.getEncoder().encodeToString(o.directiveXml.getBytes(StandardCharsets.UTF_8));
            assocToChecksum.put(o.assocId, Long.toString(storedContentChecksum));
            children.append("<ds-object ds-object-class=\"").append(o.objectClass).append("\" ds-object-name=\"")
                .append(o.name).append("\">")
                .append("<ds-attributes>")
                .append(attr("idm-packageguid", o.guid))
                .append(attr("idm-packageassocguid", o.assocId))
                .append(attr("idm-contentchecksum", Long.toString(storedContentChecksum)))
                .append(attr("idm-directivechecksum", Long.toString(directiveChecksum)))
                .append(attr("idm-installationdirective", directiveB64))
                .append("<ds-attribute ds-attr-name=\"XmlData\"><ds-value>").append(o.xmlContent).append("</ds-value></ds-attribute>")
                .append("</ds-attributes></ds-object>");
        }
        long folderChecksum = PackageChecksum.folder(assocToChecksum, null);
        Map<Integer, Long> folderChecksums = new LinkedHashMap<>();
        folderChecksums.put(1, folderChecksum);
        String provisioningFolder = "";
        if (s.provisioningXml != null) {
            folderChecksums.put(5, PackageChecksum.folder(Map.of(), s.provisioningXml));
            provisioningFolder = "<package-folder id=\"5\" name=\"Provisioning\"><description></description><children><provisioning>"
                + Base64.getEncoder().encodeToString(s.provisioningXml.getBytes(StandardCharsets.UTF_8))
                + "</provisioning></children></package-folder>";
        }
        long pkgChecksum = PackageChecksum.pkg(folderChecksums);
        long dirChecksum = PackageChecksum.directive(NxslCanonical.canonical(s.installDirectiveXml));
        String readmeB64 = Base64.getEncoder().encodeToString("a test package".getBytes(StandardCharsets.UTF_8));

        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><package base-package=\"" + s.basePackage
            + "\" category=\"" + s.category + "\" category-folder=\"" + s.categoryFolder + "\" checksum=\"" + pkgChecksum + "\" directive-checksum=\""
            + dirChecksum + "\" id=\"" + s.id + "\" name=\"" + s.displayName + "\" symbolic-name=\"" + s.symbolicName
            + "\" type=\"" + s.type + "\" version=\"" + s.version + "\">"
            + "<description></description><category-description></category-description><category-folder-description></category-folder-description>"
            + "<idm-shortname>" + s.shortName + "</idm-shortname>"
            + "<idm-installationdirective>" + s.installDirectiveXml + "</idm-installationdirective>"
            + "<readme>" + readmeB64 + "</readme>"
            + "<package-folder id=\"1\" name=\"Policies\"><description></description><children>" + children + "</children></package-folder>"
            + provisioningFolder
            + "</package>";

        Path jar = dir.resolve(s.shortName + "_" + s.version + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            out.write(("Manifest-Version: 1.0\r\nShort-Name: " + s.shortName + "\r\nType: " + s.type
                + "\r\nBase-Package: " + s.basePackage + "\r\nBundle-SymbolicName: " + s.symbolicName
                + "\r\nBundle-Version: " + s.version + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("plugin.xml"));
            out.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plugin><extension point=\"com.novell.idm.packagemanager.packageregistration\">"
                + "<packageregistration><package id=\"" + s.id + "\" version=\"" + s.version + "\"/>"
                + "<display name=\"" + s.shortName + "_" + s.version + "\"/><symbolic name=\"" + s.symbolicName + "\"/>"
                + "</packageregistration></extension></plugin>").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("package_import.xml"));
            out.write(xml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String attr(String name, String value) {
        return "<ds-attribute ds-attr-name=\"" + name + "\"><ds-value>" + value + "</ds-value></ds-attribute>";
    }
}
