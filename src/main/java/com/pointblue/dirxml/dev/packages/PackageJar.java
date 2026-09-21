package com.pointblue.dirxml.dev.packages;

import org.w3c.dom.Document;
import com.pointblue.dirxml.dev.model.AppObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * A Designer package jar, read: the manifest, the {@code <package>} element of
 * {@code package_import.xml}, and every {@code ds-object} with its decoded
 * installation directive and stored checksums. Content is kept as the DOM
 * element (XML) or text (non-XML resources) exactly as the jar has it.
 */
public final class PackageJar {

    public static final class Item {
        public int folderId;
        public String folderName;
        public String objectClass;
        public String name;
        public String contentType;       // DirXML-Resource only
        public String packageGuid;       // idm-packageguid
        public String assocId;           // idm-packageassocguid
        public String directive;         // decoded idm-installationdirective
        public String storedContentChecksum;
        public String storedDirectiveChecksum;
        public Element content;          // XmlData / DirXML-Data element, or null
        public String text;              // XmlData text when it has no element child
        public Element dsObject;
    }

    public Path path;
    public Manifest manifest;
    public String shortName;
    public String symbolicName;
    public String version;
    public int type;
    public boolean basePackage;
    public Element pkg;                  // <package …>
    public Document doc;
    public String directive;             // package installation-directive, as serialized by Designer
    public Map<Integer, String> folderNames = new LinkedHashMap<>();
    /** {@code <properties lang="xx">} bundles: language → key/value (Designer's localization source). */
    public Map<String, Map<String, String>> properties = new LinkedHashMap<>();
    public Map<Integer, String> folderProvisioningData = new LinkedHashMap<>();
    public List<Item> items = new ArrayList<>();
    /**
     * The package's AppConfig (User Application packages): the decoded {@code <provisioning>} document
     * a Provisioning folder carries under {@code children/provisioning} as base64 — one ds-object tree
     * rooted at {@code srvprvAppConfig}, the same shape Designer's {@code .appconfig} has. Null when the
     * package has none. {@link #provisioningObjects} is it flattened, paths below AppConfig.
     */
    public Element provisioning;
    public List<AppObject> provisioningObjects = new ArrayList<>();

    public static PackageJar read(Path jar) throws IOException {
        PackageJar p = new PackageJar();
        p.path = jar;
        try (JarFile jf = new JarFile(jar.toFile())) {
            p.manifest = jf.getManifest();
            ZipEntry e = jf.getEntry("package_import.xml");
            if (e == null) {
                throw new IOException(jar + ": no package_import.xml");
            }
            String xml;
            try (InputStream in = jf.getInputStream(e)) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.doc = NxslCanonical.parse(xml);
        }
        p.pkg = p.doc.getDocumentElement();
        p.symbolicName = p.pkg.getAttribute("symbolic-name");
        p.version = p.pkg.getAttribute("version");
        p.type = parseInt(p.pkg.getAttribute("type"));
        p.basePackage = "true".equals(p.pkg.getAttribute("base-package"));
        Element sn = PackageChecksum.child(p.pkg, "idm-shortname");
        p.shortName = sn == null ? null : text(sn);
        Element idir = PackageChecksum.child(p.pkg, "idm-installationdirective");
        Element dirRoot = idir == null ? null : PackageChecksum.child(idir, "installation-directive");
        if (dirRoot != null) {
            Document dd = com.novell.xml.dom.DocumentFactory.newDocument();
            dd.appendChild(dd.importNode(dirRoot, true));
            p.directive = NxslCanonical.serialize(dd);
        }
        for (Element props : PackageChecksum.children(p.pkg, "properties")) {
            String lang = props.getAttribute("lang");
            String b64 = text(props).replaceAll("\\s", "");
            if (lang.isEmpty() || b64.isEmpty()) {
                continue;
            }
            try {
                java.util.Properties pr = new java.util.Properties();
                pr.load(new java.io.StringReader(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)));
                Map<String, String> m = new LinkedHashMap<>();
                for (String k : pr.stringPropertyNames()) {
                    m.put(k, pr.getProperty(k));
                }
                p.properties.put(lang, m);
            } catch (Exception e) {
                // an unreadable bundle: the default texts apply
            }
        }
        for (Element folder : PackageChecksum.children(p.pkg, "package-folder")) {
            int fid = parseInt(folder.getAttribute("id"));
            p.folderNames.put(fid, folder.getAttribute("name"));
            Element prov = PackageChecksum.child(folder, "provisioning-data");
            if (prov != null) {
                p.folderProvisioningData.put(fid, text(prov));
            }
            Element ch = PackageChecksum.child(folder, "children");
            Element provBlob = ch == null ? null : PackageChecksum.child(ch, "provisioning");
            if (provBlob != null) {
                String b64 = text(provBlob);
                if (b64 != null && !b64.isBlank()) {
                    String xml = new String(Base64.getDecoder().decode(b64.replaceAll("\\s", "")), StandardCharsets.UTF_8);
                    // Designer's folder checksum covers the decoded document (proved against NOVLUABASE 4.10.1's
                    // stored package checksum: decoded matches, the base64 text and "absent" do not)
                    p.folderProvisioningData.put(fid, xml);
                    try {
                        Element root = NxslCanonical.parse(xml).getDocumentElement();
                        Element appConfig = root.getNodeName().equals("ds-object") ? root : PackageChecksum.child(root, "ds-object");
                        if (appConfig != null) {
                            p.provisioning = appConfig;
                            flattenProvisioning(appConfig, new ArrayList<>(), p.provisioningObjects);
                        }
                    } catch (RuntimeException e) {
                        // an undecodable provisioning blob: the package still loads; nothing to unpack
                    }
                }
            }
            for (Element obj : PackageChecksum.children(ch, "ds-object")) {
                Item it = new Item();
                it.folderId = fid;
                it.folderName = folder.getAttribute("name");
                it.dsObject = obj;
                it.objectClass = obj.getAttribute("ds-object-class");
                it.name = obj.getAttribute("ds-object-name");
                String ct = obj.getAttribute("DirXML-ContentType");
                it.contentType = ct.isEmpty() ? null : ct;
                it.packageGuid = dsAttrText(obj, "idm-packageguid");
                it.assocId = dsAttrText(obj, "idm-packageassocguid");
                it.storedContentChecksum = dsAttrText(obj, "idm-contentchecksum");
                it.storedDirectiveChecksum = dsAttrText(obj, "idm-directivechecksum");
                String b64 = dsAttrText(obj, "idm-installationdirective");
                if (b64 != null) {
                    it.directive = new String(Base64.getDecoder().decode(b64.replaceAll("\\s", "")), StandardCharsets.UTF_8);
                }
                it.content = dsAttrElement(obj, "XmlData");
                if (it.content == null) {
                    it.content = dsAttrElement(obj, "DirXML-Data");
                }
                if (it.content == null) {
                    it.text = dsAttrText(obj, "XmlData");
                }
                p.items.add(it);
            }
        }
        return p;
    }

    /** The localization bundle for a language ({@code en} first, then the language's base, else empty). */
    public Map<String, String> propertiesFor(String lang) {
        Map<String, String> m = properties.get(lang);
        if (m == null && lang != null && lang.contains("_")) {
            m = properties.get(lang.substring(0, lang.indexOf('_')));
        }
        return m == null ? Map.of() : m;
    }

    public String manifestAttr(String name) {
        return manifest == null ? null : manifest.getMainAttributes().getValue(name);
    }

    static String dsAttrText(Element dsObject, String attrName) {
        Element attrs = PackageChecksum.child(dsObject, "ds-attributes");
        for (Element a : PackageChecksum.children(attrs, "ds-attribute")) {
            if (attrName.equalsIgnoreCase(a.getAttribute("ds-attr-name"))) {
                Element v = PackageChecksum.child(a, "ds-value");
                if (v == null) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                for (Node c = v.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                        sb.append(c.getNodeValue());
                    }
                }
                return sb.toString();
            }
        }
        return null;
    }

    static Element dsAttrElement(Element dsObject, String attrName) {
        Element attrs = PackageChecksum.child(dsObject, "ds-attributes");
        for (Element a : PackageChecksum.children(attrs, "ds-attribute")) {
            if (attrName.equalsIgnoreCase(a.getAttribute("ds-attr-name"))) {
                Element v = PackageChecksum.child(a, "ds-value");
                if (v == null) {
                    return null;
                }
                for (Node c = v.getFirstChild(); c != null; c = c.getNextSibling()) {
                    if (c.getNodeType() == Node.ELEMENT_NODE) {
                        return (Element) c;
                    }
                }
            }
        }
        return null;
    }

    private static String text(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(c.getNodeValue());
            }
        }
        return sb.toString();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Every ds-object below the AppConfig root as an {@link AppObject} whose path is its ds-object-name
     * chain; the root itself is skipped (it is the container the vault already has). Designer's stamps
     * on the element ({@code package-id}, {@code pkg-assoc-id}, {@code checksum}, {@code guid}) go to meta.
     */
    static void flattenProvisioning(Element dsObject, List<String> parentSegments, List<AppObject> out) {
        for (Element child : PackageChecksum.children(dsObject, "ds-object")) {
            List<String> segments = new ArrayList<>(parentSegments);
            segments.add(child.getAttribute("ds-object-name"));
            AppObject o = com.pointblue.dirxml.dev.ascode.DsObjectXml.read(child, segments);
            for (String stamp : new String[] {"package-id", "pkg-assoc-id", "checksum", "guid"}) {
                String v = child.getAttribute(stamp);
                if (!v.isEmpty()) {
                    o.meta.put(stamp.equals("guid") ? "designer.guid" : stamp, v);
                }
            }
            o.meta.put("objectClass", child.getAttribute("ds-object-class"));
            out.add(o);
            flattenProvisioning(child, segments, out);
        }
    }
}
