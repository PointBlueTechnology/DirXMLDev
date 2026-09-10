package com.pointblue.dirxml.dev.packages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * {@code package.site}: render the catalog as an Eclipse update site Designer
 * can read (docs/spikes/package-format.md): {@code site.xml} listing one
 * feature per package version, {@code features/<SHORT>.feature_<ver>.jar}
 * (a {@code feature.xml} naming the plugin), {@code plugins/<SHORT>_<ver>.jar}
 * (the package jar, copied), {@code deprecations/deprecated.properties}.
 */
public final class SiteWriter {

    private SiteWriter() {
    }

    /** Writes the site; returns the feature entries written. */
    public static List<String> write(Catalog catalog, Path out, String description) throws IOException {
        Files.createDirectories(out.resolve("features"));
        Files.createDirectories(out.resolve("plugins"));
        Files.createDirectories(out.resolve("deprecations"));
        StringBuilder site = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><site>\n\t<description>")
            .append(escape(description)).append("</description>\n");
        List<String> written = new ArrayList<>();
        for (Catalog.PackageEntry e : catalog.packages().values()) {
            for (Map.Entry<String, Catalog.VersionEntry> v : e.versions.entrySet()) {
                String ver = v.getKey();
                Path jar = catalog.dir.resolve("jars").resolve(e.shortName).resolve(e.shortName + "_" + ver + ".jar");
                if (!Files.exists(jar)) {
                    continue;
                }
                Files.copy(jar, out.resolve("plugins").resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                PackageJar p = PackageJar.read(jar);
                String featureName = e.shortName + ".feature_" + ver + ".jar";
                Manifest mf = new Manifest();
                mf.getMainAttributes().putValue("Manifest-Version", "1.0");
                mf.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
                mf.getMainAttributes().putValue("Bundle-Name", e.displayName == null ? e.shortName : e.displayName);
                mf.getMainAttributes().putValue("Bundle-Version", ver);
                mf.getMainAttributes().putValue("Bundle-Vendor", p.manifestAttr("Bundle-Vendor") == null ? "" : p.manifestAttr("Bundle-Vendor"));
                mf.getMainAttributes().putValue("Bundle-SymbolicName", e.symbolicName + "; singleton:=true");
                mf.getMainAttributes().putValue("Require-Bundle", "com.novell.idm.packagemanager");
                mf.getMainAttributes().putValue("Internal-Version", p.manifestAttr("Internal-Version") == null ? "1" : p.manifestAttr("Internal-Version"));
                String featureXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<feature\n    id=\"" + e.shortName + ".feature\"\n    label=\""
                    + escape(e.displayName == null ? e.shortName : e.displayName) + "\"\n    version=\"" + ver + "\"\n    provider-name=\""
                    + escape(p.manifestAttr("Bundle-Vendor") == null ? "" : p.manifestAttr("Bundle-Vendor")) + "\">\n\n    <description>\n        "
                    + escape(descriptionOf(p)) + "\n    </description>\n    <plugin\n        id=\"" + e.shortName + "\"\n        download-size=\"0\"\n"
                    + "        install-size=\"0\"\n        version=\"" + ver + "\"\n        fragment=\"true\"\n        unpack=\"false\"/>\n</feature>\n";
                try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(out.resolve("features").resolve(featureName)), mf)) {
                    jo.putNextEntry(new JarEntry("feature.xml"));
                    jo.write(featureXml.getBytes(StandardCharsets.UTF_8));
                    jo.closeEntry();
                }
                site.append("\t<feature id=\"").append(e.shortName).append(".feature\" url=\"features/").append(featureName)
                    .append("\" version=\"").append(ver).append("\"/>\n");
                written.add(e.shortName + "_" + ver);
            }
        }
        site.append("</site>\n");
        Files.writeString(out.resolve("site.xml"), site.toString(), StandardCharsets.UTF_8);
        Path dep = out.resolve("deprecations").resolve("deprecated.properties");
        if (!Files.exists(dep)) {
            Files.writeString(dep, "# SHORT_version=reason\n", StandardCharsets.UTF_8);
        }
        return written;
    }

    private static String descriptionOf(PackageJar p) {
        org.w3c.dom.Element d = PackageChecksum.child(p.pkg, "description");
        return d == null ? "" : PromptEngine.text(d);
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
