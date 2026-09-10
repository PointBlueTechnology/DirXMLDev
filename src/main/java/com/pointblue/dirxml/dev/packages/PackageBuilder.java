package com.pointblue.dirxml.dev.packages;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.deploy.DeployLog;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * {@code package.build}: a package jar from a tree's content — the non-packaged
 * artifacts of a driver (a type-2 feature package) or of the Library (a type-3
 * driver-set package), each with a directive that reproduces its placement and
 * its position in the sets (docs/packages.md §3.5). Written exactly as
 * Designer writes a package (docs/spikes/designer-package-layer.md §6): ids
 * {@code XXXXXXXX_yyyyMMddHHmmssSSSS}, version {@code M.m.r.yyyyMMddHHmmss},
 * symbolic name {@code com.<vendor>.<short>}, MANIFEST / plugin.xml /
 * package_import.xml with the stored checksums computed by {@link PackageChecksum}.
 * Customized packaged objects are not included (they belong to their package).
 */
public final class PackageBuilder {

    public static final class Options {
        public Path tree;
        public String driver;             // null → the Library (type 3)
        public String shortName;
        public String name;
        public String vendor = "pointbluetech";
        public String version = "1.0.0";  // M.m.r; the build id is appended
        public String description = "";
        public String readme = "";
        public String category = "Custom";
        public String categoryFolder = "Custom";
        public List<String> include = new ArrayList<>();      // artifact paths; empty = every non-packaged artifact of the target
        public Path newVersionOf;                              // a previous version's jar: reuse its package id and association ids
        public List<String> depends = new ArrayList<>();       // "SHORT_ver" or "SHORT" of installed packages to declare (type from the tree)
        public boolean base;                                   // build a base package (default: a feature package)
        public String customized = "refuse";                   // refuse | keep
        public String gcvs = "referenced";                     // referenced | all | none: driver-level GCV definitions to ship as a GCV object
        public List<String> supportedDrivers = new ArrayList<>();   // driver type ids (e.g. EDIR-Driver); default: the target's base package's list, else the driver's type
        public Catalog catalog;                                // optional: where the base package's jar is looked up for the supported-driver list
    }

    public static final class Result {
        public boolean ok = true;
        public String refusal;
        public Path jar;
        public String packageId;
        public String version;
        public final List<String> objects = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();

        public String text() {
            StringBuilder sb = new StringBuilder("package.build: ");
            if (refusal != null) {
                return sb.append("REFUSED — ").append(refusal).append('\n').toString();
            }
            sb.append("wrote ").append(jar).append(" (id ").append(packageId).append(", version ").append(version).append(")\n");
            for (String o : objects) {
                sb.append("  object   ").append(o).append('\n');
            }
            for (String n : notes) {
                sb.append("  note     ").append(n).append('\n');
            }
            return sb.toString();
        }

        public String json() {
            StringBuilder sb = new StringBuilder("{\"ok\":").append(refusal == null);
            if (refusal != null) {
                sb.append(",\"refusal\":").append(DeployLog.q(refusal));
            }
            sb.append(",\"jar\":").append(jar == null ? "null" : DeployLog.q(jar.toString()));
            sb.append(",\"packageId\":").append(packageId == null ? "null" : DeployLog.q(packageId));
            sb.append(",\"version\":").append(version == null ? "null" : DeployLog.q(version));
            sb.append(",\"objects\":[");
            for (int i = 0; i < objects.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(DeployLog.q(objects.get(i)));
            }
            sb.append("],\"notes\":[");
            for (int i = 0; i < notes.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(DeployLog.q(notes.get(i)));
            }
            return sb.append("]}").toString();
        }
    }

    private PackageBuilder() {
    }

    public static Result build(Options o, Path outDir) throws IOException {
        Result r = new Result();
        DriverSet ds = AsCodeReader.read(o.tree);
        Driver d = null;
        List<Artifact> pool;
        if (o.driver != null) {
            d = ds.driver(o.driver);
            if (d == null) {
                return refuse(r, "no driver '" + o.driver + "' in the tree");
            }
            pool = d.artifacts();
        } else {
            pool = ds.library.artifacts();
        }
        if (o.shortName == null || !o.shortName.matches("[A-Z0-9]{4,12}")) {
            return refuse(r, "--short must be 4–12 uppercase letters/digits (Designer's convention, e.g. PBTCUSTAD)");
        }
        // 1. what goes in
        List<Artifact> chosen = new ArrayList<>();
        List<String> customizedVendor = new ArrayList<>();
        for (Artifact a : pool) {
            boolean wanted = o.include.isEmpty() || o.include.contains(a.path());
            if (!wanted) {
                continue;
            }
            if (Packages.isPackaged(a)) {
                if (Packages.isCustomized(a) || o.include.contains(a.path())) {
                    customizedVendor.add(a.path());
                }
                continue;
            }
            chosen.add(a);
        }
        if (!customizedVendor.isEmpty()) {
            if ("keep".equals(o.customized)) {
                r.notes.add("left as customizations of their own packages (not in this package): " + customizedVendor);
            } else {
                return refuse(r, "these are packaged objects (customized or explicitly included) — an object belongs to one package; "
                    + "--customized keep leaves them as customizations: " + customizedVendor);
            }
        }
        // the driver's own (non-packaged) GCV definitions the content reads travel as a GCV object, Designer's way
        Resource gcvObject = d == null || "none".equals(o.gcvs) ? null : gcvObject(d, chosen, o, r);
        if (gcvObject != null) {
            chosen.add(gcvObject);
        }
        if (chosen.isEmpty()) {
            return refuse(r, "nothing to package: no non-packaged artifact on " + (d == null ? "the Library" : "'" + d.name + "'"));
        }
        // 2. ids
        PackageJar prev = o.newVersionOf == null ? null : PackageJar.read(o.newVersionOf);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSS"));
        String buildTime = stamp.substring(0, 14);
        String packageId = prev != null ? prev.pkg.getAttribute("id") : PackageInstall.mintId() + "_" + stamp;
        String version = o.version + "." + buildTime;
        String symbolic = "com." + o.vendor.replaceAll("[^a-zA-Z0-9]", "").toLowerCase() + "." + o.shortName.toLowerCase();
        Map<String, String> prevAssoc = new LinkedHashMap<>();
        if (prev != null) {
            for (PackageJar.Item it : prev.items) {
                prevAssoc.put(it.objectClass + "|" + it.name, it.assocId);
            }
            r.notes.add("new version of " + prev.shortName + "_" + prev.version + ": package id and association ids reused");
        }
        int type = d == null ? 3 : 2;
        // 3. objects
        Document doc = CanonicalXml.parse("<package/>");
        Element pkg = doc.getDocumentElement();
        Map<Integer, List<Element>> folders = new TreeMap<>();
        Map<Integer, Map<String, String>> folderChecksums = new TreeMap<>();
        for (Artifact a : chosen) {
            String cls = a instanceof Policy
                ? (((Policy) a).policyKind() == Policy.Kind.XSLT ? PackageChecksum.STYLESHEET : PackageChecksum.RULE)
                : (((Resource) a).isGcvDef() ? PackageChecksum.GCV_DEF : PackageChecksum.RESOURCE);
            int folder = a instanceof Policy ? 1 : ((Resource) a).isGcvDef() ? 9 : 2;
            String assoc = prevAssoc.getOrDefault(cls + "|" + a.name, PackageInstall.mintId() + "_" + stamp);
            Element dir = directiveFor(ds, d, a, r);
            String directive = NxslCanonical.serialize(NxslCanonical.parse(CanonicalXml.serialize(dir)));
            long dirCrc = PackageChecksum.directive(directive);
            long contentCrc;
            Element content = null;
            String text = null;
            if (a instanceof Policy) {
                content = ((Policy) a).content;
                contentCrc = PackageChecksum.content(cls, a.name, PackageInstall.toNxsl(content), null, null, List.of());
            } else {
                Resource res = (Resource) a;
                if (res.isGcvDef()) {
                    contentCrc = PackageChecksum.gcv(a.name, PackageInstall.toNxsl(res.content), List.of());
                } else if (res.isText()) {
                    text = res.text;
                    contentCrc = PackageChecksum.content(cls, a.name, null, text, res.contentType, List.of());
                } else {
                    content = res.content;
                    contentCrc = PackageChecksum.content(cls, a.name, PackageInstall.toNxsl(content), null, res.contentType, List.of());
                }
            }
            Element obj = doc.createElementNS(null, "ds-object");
            if (a instanceof Resource && !((Resource) a).isGcvDef()) {
                obj.setAttribute("DirXML-ContentType", ((Resource) a).contentType);
            }
            obj.setAttribute("ds-object-class", cls);
            obj.setAttribute("ds-object-name", a.name);
            Element attrs = doc.createElementNS(null, "ds-attributes");
            obj.appendChild(attrs);
            if (!(a instanceof Resource && ((Resource) a).isGcvDef())) {
                Element x = dsAttr(doc, attrs, "XmlData");
                Element v = PromptEngine.child(x, "ds-value");
                if (content != null) {
                    v.appendChild(doc.importNode(content, true));
                } else if (text != null) {
                    v.appendChild(doc.createTextNode(text));
                }
            }
            textAttr(doc, attrs, "idm-installationdirective", Base64.getEncoder().encodeToString(directive.getBytes(StandardCharsets.UTF_8)));
            textAttr(doc, attrs, "idm-directivechecksum", "" + dirCrc);
            textAttr(doc, attrs, "idm-packageassocguid", assoc);
            textAttr(doc, attrs, "idm-packageguid", packageId);
            textAttr(doc, attrs, "idm-contentchecksum", "" + contentCrc);
            folders.computeIfAbsent(folder, k -> new ArrayList<>()).add(obj);
            folderChecksums.computeIfAbsent(folder, k -> new LinkedHashMap<>()).put(assoc, "" + contentCrc);
            r.objects.add(a.path() + " → " + cls + " (assoc " + assoc + ")");
        }
        // 4. the package element
        Map<Integer, Long> fcs = new TreeMap<>();
        String[] folderNames = {"", "Policies", "Resources", "Jobs", "Entitlements", "Provisioning", "Files", "Notification Templates", "ID Policies", "Global Configurations"};
        for (int i = 1; i <= 9; i++) {
            fcs.put(i, PackageChecksum.folder(folderChecksums.getOrDefault(i, Map.of()), null));
        }
        Element pkgDir = packageDirective(doc, ds, d, o, r);
        String pkgDirective = NxslCanonical.serialize(NxslCanonical.parse(CanonicalXml.serialize(pkgDir)));
        pkg.setAttribute("base-package", "" + o.base);
        pkg.setAttribute("category", o.category);
        pkg.setAttribute("category-folder", o.categoryFolder);
        pkg.setAttribute("checksum", "" + PackageChecksum.pkg(fcs));
        pkg.setAttribute("directive-checksum", "" + PackageChecksum.directive(pkgDirective));
        pkg.setAttribute("id", packageId);
        pkg.setAttribute("name", o.name);
        pkg.setAttribute("symbolic-name", symbolic);
        pkg.setAttribute("type", "" + type);
        pkg.setAttribute("version", version);
        text(doc, pkg, "description", o.description);
        text(doc, pkg, "category-description", "");
        text(doc, pkg, "category-folder-description", "");
        text(doc, pkg, "idm-shortname", o.shortName);
        text(doc, pkg, "idm-buildtime", buildTime);
        text(doc, pkg, "idm-buildhost", hostname());
        text(doc, pkg, "idm-builduser", System.getProperty("user.name", ""));
        text(doc, pkg, "idm-released", "true");
        text(doc, pkg, "idm-protected", "false");
        text(doc, pkg, "idm-newversion", prev != null ? "true" : "false");
        text(doc, pkg, "idm-creationtime", prev != null ? textOf(prev.pkg, "idm-creationtime", stamp) : stamp);
        text(doc, pkg, "idm-vendorname", o.vendor);
        text(doc, pkg, "idm-vendoraddress", "");
        text(doc, pkg, "idm-vendorurl", "");
        text(doc, pkg, "idm-vendoremail", "");
        text(doc, pkg, "idm-contactname", "");
        text(doc, pkg, "idm-contactemail", "");
        text(doc, pkg, "idm-internalversion", prev != null ? "" + (parseInt(textOf(prev.pkg, "idm-internalversion", "0")) + 1) : "1");
        text(doc, pkg, "idm-minidmversion", "4.0.0");
        text(doc, pkg, "idm-maxidmversion", "");
        text(doc, pkg, "idm-minappversion", "");
        text(doc, pkg, "idm-maxappversion", "");
        text(doc, pkg, "license", Base64.getEncoder().encodeToString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".getBytes(StandardCharsets.UTF_8)));
        text(doc, pkg, "readme", Base64.getEncoder().encodeToString(o.readme.getBytes(StandardCharsets.UTF_8)));
        Element idir = doc.createElementNS(null, "idm-installationdirective");
        idir.appendChild(doc.importNode(pkgDir, true));
        pkg.appendChild(idir);
        for (int i = 1; i <= 9; i++) {
            Element f = doc.createElementNS(null, "package-folder");
            f.setAttribute("id", "" + i);
            f.setAttribute("name", folderNames[i]);
            text(doc, f, "description", "");
            Element children = doc.createElementNS(null, "children");
            for (Element obj : folders.getOrDefault(i, List.of())) {
                children.appendChild(obj);
            }
            f.appendChild(children);
            pkg.appendChild(f);
        }
        String packageXml = NxslCanonical.serialize(NxslCanonical.parse(CanonicalXml.serialize(pkg)));
        // 5. the jar
        Files.createDirectories(outDir);
        Path jar = outDir.resolve(o.shortName + "_" + version + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        mf.getMainAttributes().putValue("Bundle-Name", o.name);
        mf.getMainAttributes().putValue("Bundle-Version", version);
        mf.getMainAttributes().putValue("Bundle-Vendor", o.vendor);
        mf.getMainAttributes().putValue("Bundle-SymbolicName", symbolic + "; singleton:=true");
        mf.getMainAttributes().putValue("Require-Bundle", "com.novell.idm.packagemanager");
        mf.getMainAttributes().putValue("Internal-Version", textOf(pkg, "idm-internalversion", "1"));
        mf.getMainAttributes().putValue("Short-Name", o.shortName);
        mf.getMainAttributes().putValue("Type", "" + type);
        mf.getMainAttributes().putValue("Base-Package", "" + o.base);
        mf.getMainAttributes().putValue("Dependencies", b64(serializeChild(pkgDir, "dependencies", "<dependencies/>")));
        mf.getMainAttributes().putValue("Supported-Drivers", b64(serializeChild(pkgDir, "supported-drivers", "<supported-drivers/>")));
        mf.getMainAttributes().putValue("Features", b64("<?xml version=\"1.0\" encoding=\"UTF-8\"?><features><mandatory/><optional/></features>"));
        String pluginXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?eclipse version=\"3.2\"?>\n<plugin>\n"
            + "    <extension point=\"com.novell.idm.packagemanager.packageregistration\">\n        <packageregistration>\n"
            + "\t\t       <package id=\"" + packageId + "\" version=\"" + version + "\"/>\n"
            + "            <display name=\"" + o.shortName + "_" + version + "\"/>\n"
            + "            <symbolic name=\"" + symbolic + "\"/>\n        </packageregistration>\n    </extension>\n</plugin>";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            out.putNextEntry(new JarEntry("plugin.xml"));
            out.write(pluginXml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("package_import.xml"));
            out.write(packageXml.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        // 6. self-check: every stored checksum must recompute the way Designer will check it on import
        ChecksumAudit audit = ChecksumAudit.of(PackageJar.read(jar));
        if (!audit.allMatch()) {
            Files.deleteIfExists(jar);
            return refuse(r, "internal error: the built jar does not verify: " + audit.mismatches());
        }
        r.jar = jar;
        r.packageId = packageId;
        r.version = version;
        return r;
    }

    private static final java.util.regex.Pattern TILDE = java.util.regex.Pattern.compile("~([A-Za-z0-9_.\\-]+)~");

    /**
     * A {@code <SHORT>-GCVs} object holding the driver-level definitions the chosen content references
     * ({@code ~name~}, {@code if-global-variable}, {@code token-global-variable}), values included as defaults;
     * {@code all} ships every definition of the driver's own config values. Null when there is nothing to ship.
     */
    static Resource gcvObject(Driver d, List<Artifact> chosen, Options o, Result r) {
        Element cv = d.config.get(Driver.CONFIG_VALUES);
        if (cv == null) {
            return null;
        }
        java.util.Set<String> wanted = new java.util.TreeSet<>();
        if ("all".equals(o.gcvs)) {
            for (Element def : PromptEngine.definitions(cv)) {
                wanted.add(def.getAttribute("name"));
            }
        } else {
            for (Artifact a : chosen) {
                String xml = a instanceof Policy ? CanonicalXml.serialize(((Policy) a).content)
                    : ((Resource) a).isText() ? ((Resource) a).text : ((Resource) a).content == null ? "" : CanonicalXml.serialize(((Resource) a).content);
                java.util.regex.Matcher m = TILDE.matcher(xml);
                while (m.find()) {
                    wanted.add(m.group(1));
                }
                java.util.regex.Matcher g = java.util.regex.Pattern.compile("<(?:if|token)-global-variable[^>]*\\sname=\"([^\"]+)\"").matcher(xml);
                while (g.find()) {
                    wanted.add(g.group(1));
                }
            }
        }
        Document doc = CanonicalXml.parse("<configuration-values><definitions/></configuration-values>");
        Element defs = doc.getDocumentElement().getFirstChild() instanceof Element
            ? (Element) doc.getDocumentElement().getFirstChild() : PromptEngine.child(doc.getDocumentElement(), "definitions");
        int n = 0;
        List<String> shipped = new ArrayList<>();
        for (Element def : PromptEngine.definitions(cv)) {
            String name = def.getAttribute("name");
            if (wanted.contains(name)) {
                defs.appendChild(doc.importNode(def, true));
                shipped.add(name);
                n++;
            }
        }
        wanted.removeAll(shipped);
        if (!wanted.isEmpty()) {
            r.notes.add("GCVs the content references but the driver's own config values do not define (they must come from an installed package or the driver set): " + wanted);
        }
        if (n == 0) {
            return null;
        }
        Element dn = PromptEngine.child(cv, "definitions");
        if (dn != null && !dn.getAttribute("display-name").isEmpty()) {
            defs.setAttribute("display-name", dn.getAttribute("display-name"));
        } else {
            defs.setAttribute("display-name", o.name);
        }
        Resource res = new Resource(o.shortName + "-GCVs", Scope.DRIVER, d.name, Resource.GCV_DEF);
        res.content = doc.getDocumentElement();
        r.notes.add("GCV object " + res.name + " ships " + n + " driver-level definition(s) with their current values as defaults: " + shipped);
        return res;
    }

    /** The item's directive: placement from its scope, linkage from the driver's sets with a weight that lands it where it is. */
    static Element directiveFor(DriverSet ds, Driver d, Artifact a, Result r) {
        Document doc = CanonicalXml.parse("<installation-directive/>");
        Element root = doc.getDocumentElement();
        boolean gcv = a instanceof Resource && ((Resource) a).isGcvDef();
        if (gcv) {
            root.setAttribute("mode", "all");
        }
        Element placement = doc.createElementNS(null, "placement");
        if (a.scope == Scope.LIBRARY) {
            placement.setAttribute("context", "driver-set");
            placement.setAttribute("location", "library");
            placement.setAttribute("name", "Library");
        } else if (a.scope == Scope.SUBSCRIBER || a.scope == Scope.PUBLISHER) {
            placement.setAttribute("location", a.scope == Scope.SUBSCRIBER ? "subscriber" : "publisher");
        } else {
            placement.setAttribute("location", "default");
        }
        root.appendChild(placement);
        root.appendChild(doc.createElementNS(null, "ds-attributes"));
        if (gcv) {
            root.appendChild(doc.importNode(((Resource) a).content, true));
        }
        List<Driver> linking = d != null ? List.of(d) : ds.drivers;
        Element linkage = doc.createElementNS(null, "policy-linkage");
        for (Driver drv : linking) {
            for (PolicySet set : PolicySet.values()) {
                List<PolicyLink> links = drv.links(set);
                for (int i = 0; i < links.size(); i++) {
                    if (!links.get(i).ref.equals(a.path())) {
                        continue;
                    }
                    long prevW = -1;
                    for (int j = i - 1; j >= 0 && prevW < 0; j--) {
                        prevW = stampedWeight(ds, links.get(j), set);
                    }
                    long nextW = Long.MAX_VALUE;
                    for (int j = i + 1; j < links.size() && nextW == Long.MAX_VALUE; j++) {
                        long w = stampedWeight(ds, links.get(j), set);
                        if (w >= 0) {
                            nextW = w;
                        }
                    }
                    long weight = prevW >= 0 ? prevW : (nextW == Long.MAX_VALUE ? 500 : Math.max(0, nextW - 1));
                    if (prevW >= 0 && nextW != Long.MAX_VALUE && nextW <= prevW) {
                        r.notes.add("'" + a.path() + "' in " + set.key + ": neighbours have weights " + prevW + " and " + nextW
                            + " — a fresh install lands it after the first, the order among equals is install order");
                    }
                    Element ps = doc.createElementNS(null, "policy-set");
                    if (set.isSubscriber() || set.isPublisher()) {
                        ps.setAttribute("channel", set.isSubscriber() ? "subscriber" : "publisher");
                    }
                    ps.setAttribute("name", PackageInstall.DESIGNER_SET_NAMES.get(set));
                    ps.setAttribute("order", "Weight");
                    ps.setAttribute("value", "" + weight);
                    linkage.appendChild(ps);
                }
            }
        }
        if (gcv && linkage.getFirstChild() == null) {
            Element ps = doc.createElementNS(null, "policy-set");
            ps.setAttribute("name", "gcv");
            ps.setAttribute("order", "Weight");
            ps.setAttribute("value", "500");
            linkage.appendChild(ps);
        }
        if (linkage.getFirstChild() != null || !(a instanceof Resource) || ((Resource) a).isEcmaScript() || gcv) {
            root.appendChild(linkage);
        }
        return root;
    }

    static long stampedWeight(DriverSet ds, PolicyLink l, PolicySet set) {
        Artifact a = ds.resolve(l.ref);
        if (a == null || a.meta.get(PackageInstall.META_LINKAGES) == null) {
            return -1;
        }
        try {
            Element root = CanonicalXml.parse(a.meta.get(PackageInstall.META_LINKAGES)).getDocumentElement();
            for (Element ps : PromptEngine.children(root, "policy-set")) {
                if (PackageInstall.policySet(ps.getAttribute("name"), ps.getAttribute("channel")) == set) {
                    return PackageInstall.parseLong(ps.getAttribute("value"), -1);
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }

    /** {@code <installation-directive><ds-attributes/><dependencies>…</dependencies><supported-drivers/>…}: dependencies from the target's installed packages named in --depends (plus the base package, when the target has one). */
    static Element packageDirective(Document doc, DriverSet ds, Driver d, Options o, Result r) {
        Document dd = CanonicalXml.parse("<installation-directive/>");
        Element root = dd.getDocumentElement();
        root.appendChild(dd.createElementNS(null, "ds-attributes"));
        Element deps = dd.createElementNS(null, "dependencies");
        Map<String, String> installed = new TreeMap<>();
        Map<String, String> meta = d != null ? d.meta : ds.meta;
        for (Map.Entry<String, String> m : meta.entrySet()) {
            if (m.getKey().startsWith(PackageInstall.META_INSTALLED_PREFIX)) {
                installed.put(m.getKey().substring(PackageInstall.META_INSTALLED_PREFIX.length()), m.getValue());
            }
        }
        List<String> wanted = new ArrayList<>(o.depends);
        for (Map.Entry<String, String> e : installed.entrySet()) {
            if (e.getValue().endsWith(";base") && d != null && !o.base) {
                wanted.add(e.getKey());
            }
        }
        for (String w : wanted) {
            String shortName = w.contains("_") ? w.substring(0, w.indexOf('_')) : w;
            String rec = installed.get(shortName);
            if (rec == null) {
                r.notes.add("--depends " + w + ": not installed on the target; declared with its name only");
                Element dep = dd.createElementNS(null, "dependency");
                dep.setAttribute("name", shortName);
                dep.setAttribute("package-id", "");
                dep.setAttribute("type", "2");
                deps.appendChild(dep);
                continue;
            }
            PackageStatus.Installed i = PackageStatus.parse(rec);
            Element dep = dd.createElementNS(null, "dependency");
            dep.setAttribute("name", i.name == null ? shortName : i.name);
            dep.setAttribute("package-id", i.id);
            dep.setAttribute("type", d != null ? "2" : "3");
            if (i.version != null) {
                Element min = dd.createElementNS(null, "min-version");
                min.setAttribute("value", i.version);
                dep.appendChild(min);
            }
            deps.appendChild(dep);
        }
        root.appendChild(deps);
        root.appendChild(supportedDrivers(dd, d, o, installed, r));
        return root;
    }

    /**
     * {@code <supported-drivers>}: Designer offers a driver package for install only on drivers of a type the
     * package names. Explicit {@code --supported-driver} ids win; else the list is copied from the target's base
     * package jar (catalog); else derived from the driver's type meta ({@code designer.driver-type}, e.g. EDIR-Driver).
     */
    static Element supportedDrivers(Document dd, Driver d, Options o, Map<String, String> installed, Result r) {
        Element sd = dd.createElementNS(null, "supported-drivers");
        if (d == null) {
            return sd;   // a driver-set package is not driver-specific
        }
        if (!o.supportedDrivers.isEmpty()) {
            for (String id : o.supportedDrivers) {
                definition(dd, sd, "Driver for " + id.replace("-Driver", ""), id, id.replace("-Driver", ""));
            }
            return sd;
        }
        for (Map.Entry<String, String> e : installed.entrySet()) {
            if (!e.getValue().endsWith(";base") || o.catalog == null) {
                continue;
            }
            PackageStatus.Installed base = PackageStatus.parse(e.getValue());
            try {
                Path jar = PackageInstall.jarOf(null, o.catalog.dir.toString(), base.shortName + "_" + base.version);
                String b64 = PackageJar.read(jar).manifestAttr("Supported-Drivers");
                if (b64 != null) {
                    String xml = new String(Base64.getMimeDecoder().decode(b64), StandardCharsets.UTF_8);
                    Element from = CanonicalXml.parse(xml).getDocumentElement();
                    for (Element def : PromptEngine.children(from, "definition")) {
                        sd.appendChild(dd.importNode(def, true));
                    }
                    r.notes.add("supported drivers copied from the base package " + base.shortName + ": " + names(sd));
                    return sd;
                }
            } catch (Exception ex) {
                r.notes.add("base package " + base.shortName + "_" + base.version + " not in the catalog; supported drivers derived from the driver's type");
            }
        }
        String type = d.meta.get("designer.driver-type");
        if (type != null && !type.isBlank()) {
            definition(dd, sd, "Driver for " + type.replace("-Driver", ""), type, type.replace("-Driver", ""));
            r.notes.add("supported drivers from the driver's type: " + type);
        } else {
            r.notes.add("no supported driver type known (no base package in the catalog, no driver type in the tree): "
                + "Designer will not offer this package on any driver — give --supported-driver <id>, e.g. EDIR-Driver, AD-Driver");
        }
        return sd;
    }

    private static void definition(Document dd, Element sd, String displayName, String driverId, String id) {
        Element def = dd.createElementNS(null, "definition");
        def.setAttribute("display-name", displayName);
        def.setAttribute("driver-id", driverId);
        def.setAttribute("id", id);
        sd.appendChild(def);
    }

    private static String names(Element sd) {
        List<String> out = new ArrayList<>();
        for (Element def : PromptEngine.children(sd, "definition")) {
            out.add(def.getAttribute("driver-id"));
        }
        return String.join(", ", out);
    }

    private static Result refuse(Result r, String why) {
        r.ok = false;
        r.refusal = why;
        return r;
    }

    private static Element dsAttr(Document doc, Element attrs, String name) {
        Element a = doc.createElementNS(null, "ds-attribute");
        a.setAttribute("ds-attr-name", name);
        a.appendChild(doc.createElementNS(null, "ds-value"));
        attrs.appendChild(a);
        return a;
    }

    private static void textAttr(Document doc, Element attrs, String name, String value) {
        Element a = dsAttr(doc, attrs, name);
        PromptEngine.child(a, "ds-value").appendChild(doc.createTextNode(value));
    }

    private static void text(Document doc, Element parent, String name, String value) {
        Element e = doc.createElementNS(null, name);
        if (value != null && !value.isEmpty()) {
            e.appendChild(doc.createTextNode(value));
        }
        parent.appendChild(e);
    }

    private static String textOf(Element parent, String name, String dflt) {
        Element e = PackageChecksum.child(parent, name);
        if (e == null) {
            return dflt;
        }
        String t = PromptEngine.text(e);
        return t.isEmpty() ? dflt : t;
    }

    private static String serializeChild(Element directive, String name, String dflt) {
        Element c = PromptEngine.child(directive, name);
        if (c == null) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + dflt;
        }
        return NxslCanonical.serialize(NxslCanonical.parse(CanonicalXml.serialize(c)));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
