package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.packages.Catalog;
import com.pointblue.dirxml.dev.packages.NxslCanonical;
import com.pointblue.dirxml.dev.packages.PackageInstall;
import com.pointblue.dirxml.dev.packages.PackageJar;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Milestone N3 of {@code docs/designer-new-project.md}: the project's own package
 * catalog, written from the git package catalog's jars.
 *
 * <p>A Designer project keeps every package it uses <i>inside itself</i>
 * ({@code Model/Project/<P>/<C>.IdmCatalog_}) — nothing in a project points at the
 * workstation catalog. The layout and every attribute below are read off
 * {@code ~/designer_workspace/test11pf} and matched against the same package versions'
 * jars; {@code docs/spikes/designer-project-catalog.md} is the reading. In short it is a
 * one-to-one rendering of the jar's {@code package_import.xml}, which
 * {@link PackageJar} already parses:
 *
 * <pre>
 *   &lt;Cat&gt;.IdmCategory_                    one of the six stock categories, by package/@category
 *     &lt;F&gt;.IdmCategoryFolder_              by package/@category-folder, Idm:Packages Child per package
 *       &lt;K&gt;.IdmPackage_                   the package attributes (only non-empty fields)
 *       &lt;K&gt;_license.xml / _readme.txt     the jar's base64, decoded verbatim
 *       &lt;K&gt;_change.log                    Designer's own import log (three lines)
 *       &lt;K&gt;_&lt;lang&gt;.properties            one per &lt;properties lang=…&gt; bundle
 *       &lt;K&gt;/&lt;F&gt;.IdmPackageFolder_         all nine, always, even when empty
 *         &lt;K&gt;/&lt;F&gt;/&lt;I&gt;.&lt;Type&gt;_            one CObject per ds-object
 *         &lt;K&gt;/&lt;F&gt;/&lt;I&gt;_contents.xml       iff the jar item carries content
 * </pre>
 *
 * <p>The {@code Idm:InstalledPackages} / back-reference relations are written by
 * {@link ProjectWriter}, which owns the driver, driver-set and vault documents; this
 * class hands it {@link Result#packageObjectId} and {@link Result#packageType}.
 */
final class ProjectCatalogWriter {

    /** {@code <lang>} bundles and the two text blobs are heavy data beside the package object. */
    private static final DateTimeFormatter CHANGE_LOG_STAMP =
        DateTimeFormatter.ofPattern("M/d/yy h:mm a", Locale.US);

    /** Designer's nine package folders, in id order (the names its own importer writes). */
    private static final String[] FOLDER_NAMES = {
        "Policies", "Resources", "Jobs", "Entitlements", "Provisioning",
        "Files", "Notification Templates", "ID Policies", "Global Configurations",
    };

    /** The item classes we can render; anything else refuses rather than guessing a CObject type. */
    private static final String CLASS_RULE = "DirXML-Rule";
    private static final String CLASS_RESOURCE = "DirXML-Resource";
    private static final String CLASS_GCV_DEF = "DirXML-GlobalConfigDef";

    private ProjectCatalogWriter() {
    }

    // ------------------------------------------------------------------
    // what the tree asks for
    // ------------------------------------------------------------------

    /** One package a tree's stamps name, with whatever the stamps say about it. */
    static final class Need {
        final String id;
        String version;          // only a vault-read tree records one (dirxml-pkgguid field 2)
        String shortName;        // …field 4
        String displayName;      // …field 3
        final Set<String> driverNames = new LinkedHashSet<>();

        Need(String id) {
            this.id = id;
        }

        /**
         * What a refusal lists: {@code NOVLEDIRBASE 2.1.2.20190219130306
         * [H32H32B6_201007011046370279]}, or just the id when the tree is a project read
         * (which records nothing but the package id on its items).
         */
        String describe() {
            String label = shortName != null ? shortName : displayName;
            if (label == null && version == null) {
                return id;
            }
            return (label == null ? "" : label + " ") + (version == null ? "" : version + " ") + "[" + id + "]";
        }
    }

    /**
     * Every distinct package the tree's stamps name, in a stable order. Sources, all of
     * which the readers record: a driver's own {@code dirxml-pkgguid} (its base packages)
     * and its {@code package.installed.<SHORT>} meta
     * ({@link PackageInstall#META_INSTALLED_PREFIX}), every packaged artifact's stamp
     * (library artifacts included — those are the driver-set-level packages), and the
     * package id a form, PRD or entitlement carries. A project-read tree records only the
     * id ({@code package-id} / {@code project.package-id}); a vault-read one records the
     * whole five-field record, version and short name included.
     */
    static Map<String, Need> needs(DriverSet tree) {
        Map<String, Need> out = new LinkedHashMap<>();
        addAll(out, tree.meta, null);
        for (Artifact a : tree.library.artifacts()) {
            addAll(out, a.meta, null);
        }
        for (Driver d : tree.drivers) {
            addAll(out, d.meta, d.name);
            for (Artifact a : d.artifacts()) {
                addAll(out, a.meta, d.name);
            }
            for (Entitlement e : d.entitlements) {
                addAll(out, e.meta, d.name);
            }
            if (d.provisioning != null) {
                for (Form f : d.provisioning.forms) {
                    addAll(out, f.meta, d.name);
                }
                for (Prd p : d.provisioning.prds) {
                    addAll(out, p.meta, d.name);
                }
            }
        }
        Map<String, Need> sorted = new LinkedHashMap<>();
        out.values().stream()
            .sorted(Comparator.comparing((Need n) -> n.shortName == null ? n.id : n.shortName).thenComparing(n -> n.id))
            .forEach(n -> sorted.put(n.id, n));
        return sorted;
    }

    private static void addAll(Map<String, Need> out, Map<String, String> meta, String driverName) {
        addRecord(out, meta.get("dirxml-pkgguid"), driverName);
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (e.getKey().startsWith(PackageInstall.META_INSTALLED_PREFIX)) {
                addRecord(out, e.getValue(), driverName);
            }
        }
        addId(out, meta.get("package-id"), driverName);
        addId(out, meta.get("project.package-id"), driverName);
    }

    /** {@code <id>;<symbolic-name>;<version>;<display name>;<SHORT>[;base]} — the vault's own record. */
    private static void addRecord(Map<String, Need> out, String record, String driverName) {
        if (record == null || record.isBlank()) {
            return;
        }
        String[] f = record.split(";", -1);
        Need n = addId(out, f[0], driverName);
        if (n == null) {
            return;
        }
        if (n.version == null && f.length > 2 && !f[2].isBlank()) {
            n.version = f[2].trim();
        }
        if (n.displayName == null && f.length > 3 && !f[3].isBlank()) {
            n.displayName = f[3].trim();
        }
        if (n.shortName == null && f.length > 4 && !f[4].isBlank()) {
            n.shortName = f[4].trim();
        }
    }

    private static Need addId(Map<String, Need> out, String id, String driverName) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Need n = out.computeIfAbsent(id.trim(), Need::new);
        if (driverName != null) {
            n.driverNames.add(driverName);
        }
        return n;
    }

    // ------------------------------------------------------------------
    // writing
    // ------------------------------------------------------------------

    /** What the project side has to provide: minted ids, file writes and notes. */
    interface Host {
        String mintId();

        /** Writes a project file and records it as created. */
        void write(Path file, String content) throws IOException;

        /** Writes a project file from raw bytes (the heavy-data blobs) and records it as created. */
        void writeBytes(Path file, byte[] content) throws IOException;

        void note(String note);

        /** The directory of an existing CObject's children ({@code <dir>/<id>/}). */
        Path childrenDir(String id);

        /** Adds a relation to an existing CObject and marks it dirty. */
        void addRelation(String ownerId, String relationName, String type, String targetId, String targetType);

        /**
         * The objects that install a package of this type, as
         * {@code {cobjectId, cobjectTypeSuffix, backReferenceRelationName}}: the named drivers
         * for a type-2 package, the driver set for type 3, the vault for type 4 (the levels
         * {@code test11pf} hangs them off).
         */
        List<String[]> installTargets(int packageType, Set<String> driverNames);
    }

    static final class Result {
        String refusal;
        /** package id → the {@code IdmPackage_} CObject id in this project. */
        final Map<String, String> packageObjectId = new LinkedHashMap<>();
        /** package id → the jar's {@code @type} (2 driver, 3 driver set, 4 vault). */
        final Map<String, Integer> packageType = new LinkedHashMap<>();
        /** package id → the driver names whose stamps named it (type 2 only). */
        final Map<String, Set<String>> installingDrivers = new LinkedHashMap<>();
        int packages;
        int items;
    }

    /**
     * Resolves every {@link Need} against the git catalog and writes it into the project,
     * or refuses with the list of packages the catalog does not hold. Nothing is written
     * when a package is missing — the whole {@code --new} run is refused by the caller.
     *
     * @param catalogDir      the git package catalog ({@code --catalog})
     * @param categoryIdByName the {@code IdmCategory_} objects the skeleton already wrote
     * @param catalogId       the {@code IdmCatalog_} the categories hang off
     */
    static Result write(Map<String, Need> needs, Path catalogDir, String catalogId,
                        Map<String, String> categoryIdByName, Host host) throws IOException {
        Result result = new Result();
        if (needs.isEmpty()) {
            return result;
        }
        Catalog catalog = Catalog.open(catalogDir);
        Map<String, String> byId = catalog.idIndex();

        Map<String, Path> jars = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Need n : needs.values()) {
            String shortName = byId.get(n.id);
            Catalog.PackageEntry entry = shortName == null ? null : catalog.get(shortName);
            if (entry == null) {
                missing.add(n.describe());
                continue;
            }
            if (n.shortName == null) {
                n.shortName = entry.shortName;
            }
            if (n.displayName == null) {
                n.displayName = entry.displayName;
            }
            // the tree records a version only when it was read from a vault; otherwise the
            // catalog's newest version of that package id is the only candidate there is
            String version = n.version != null ? n.version : entry.newestVersion();
            Path jar = version == null ? null : catalog.jar(entry.shortName, version);
            if (jar == null || !Files.isRegularFile(jar)) {
                missing.add(n.describe());
                continue;
            }
            if (n.version == null) {
                n.version = version;
            }
            jars.put(n.id, jar);
        }
        if (!missing.isEmpty()) {
            result.refusal = "the package catalog " + catalogDir + " does not hold " + missing.size()
                + " of the " + needs.size() + " package(s) this tree's stamps name; fetch or build them "
                + "(package.fetch / package.build) and run again — nothing was written:\n  "
                + String.join("\n  ", missing);
            return result;
        }

        // categories/folders are created on demand and reused across packages
        Map<String, String> categoryIds = new LinkedHashMap<>(categoryIdByName);
        Map<String, String> folderIdByCategoryAndName = new LinkedHashMap<>();

        for (Need n : needs.values()) {
            PackageJar jar = PackageJar.read(jars.get(n.id));
            String category = attr(jar.pkg, "category");
            if (category.isEmpty()) {
                category = "Common";
                host.note("package '" + n.describe() + "' declares no category; filed under Common");
            }
            String categoryId = categoryIds.get(category);
            if (categoryId == null) {
                categoryId = host.mintId();
                categoryIds.put(category, categoryId);
                Path catalogDirOnDisk = host.childrenDir(catalogId);
                host.write(catalogDirOnDisk.resolve(categoryId + ".IdmCategory_"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                        + "<com.novell.designer.model:CObject "
                        + "xmlns:com.novell.designer.model=\"http://com.novell.designer.model\" name=\""
                        + CObjectXml.esc(category) + "\"/>\n");
                host.addRelation(catalogId, "Idm:Categories", "Child", categoryId, "IdmCategory");
                host.note("package '" + n.describe() + "' is in category '" + category + "', which is not one of "
                    + "Designer's six stock catalog categories; a new IdmCategory_ was created for it");
            }
            String folderName = attr(jar.pkg, "category-folder");
            if (folderName.isEmpty()) {
                folderName = category;
            }
            String folderKey = categoryId + "/" + folderName;
            String folderId = folderIdByCategoryAndName.get(folderKey);
            Path categoryDir = host.childrenDir(categoryId);
            if (folderId == null) {
                folderId = host.mintId();
                folderIdByCategoryAndName.put(folderKey, folderId);
                host.write(categoryDir.resolve(folderId + ".IdmCategoryFolder_"),
                    CObjectXml.cobjectNoType(folderName, ""));
                host.addRelation(categoryId, "Idm:CategoryFolders", "Child", folderId, "IdmCategoryFolder");
            }

            String refusal = writePackage(n, jar, categoryDir.resolve(folderId), folderId, host, result);
            if (refusal != null) {
                result.refusal = refusal;
                return result;
            }
        }
        return result;
    }

    /** One {@code IdmPackage_} with its heavy data, nine folders and every item. */
    private static String writePackage(Need need, PackageJar jar, Path folderDir, String categoryFolderId,
                                       Host host, Result result) throws IOException {
        String pkgId = host.mintId();
        Path pkgDir = folderDir.resolve(pkgId);
        String name = attr(jar.pkg, "name");

        // ---- heavy data (verbatim bytes out of the jar, plus Designer's own import log) ----
        StringBuilder attrs = new StringBuilder();
        byte[] license = decoded(child(jar.pkg, "license"));
        if (license != null) {
            attrs.append(heavy("license", null));
            host.writeBytes(folderDir.resolve(pkgId + "_license.xml"), license);
        }
        attrs.append(heavy("change", "log"));
        host.writeBytes(folderDir.resolve(pkgId + "_change.log"), changeLog(name, jar.version));
        byte[] readme = decoded(child(jar.pkg, "readme"));
        if (readme != null) {
            attrs.append(heavy("readme", "txt"));
            host.writeBytes(folderDir.resolve(pkgId + "_readme.txt"), readme);
        }
        for (Element props : children(jar.pkg, "properties")) {
            String lang = props.getAttribute("lang");
            byte[] bytes = decoded(props);
            if (lang.isEmpty() || bytes == null) {
                continue;
            }
            attrs.append(heavy(lang, "properties"));
            host.writeBytes(folderDir.resolve(pkgId + "_" + lang + ".properties"), bytes);
        }

        // ---- the attribute table (docs/spikes/designer-project-catalog.md) ----
        attrs.append(CObjectXml.attr("Idm:PackageImported", "true", "CBoolean"));
        attrs.append(CObjectXml.attr("Idm:PackageGuid", attr(jar.pkg, "id"), "CString"));
        attrs.append(CObjectXml.attr("Idm:PackageVersion", jar.version, "CString"));
        attrs.append(CObjectXml.attr("Idm:PackageType", String.valueOf(jar.type), "CInteger"));
        attrs.append(CObjectXml.attr("Idm:BasePackage", String.valueOf(jar.basePackage), "CBoolean"));
        appendIfSet(attrs, "Idm:Released", text(jar.pkg, "idm-released"), "CBoolean");
        appendIfSet(attrs, "Idm:ContentChecksum", attr(jar.pkg, "checksum"), "CLong");
        appendIfSet(attrs, "Idm:DirectiveChecksum", attr(jar.pkg, "directive-checksum"), "CLong");
        appendIfSet(attrs, "Idm:description", text(jar.pkg, "description"), "CString");
        appendIfSet(attrs, "Idm:shortName", text(jar.pkg, "idm-shortname"), "CString");
        appendIfSet(attrs, "Idm:BuildTime", text(jar.pkg, "idm-buildtime"), "CString");
        appendIfSet(attrs, "Idm:BuildHost", text(jar.pkg, "idm-buildhost"), "CString");
        appendIfSet(attrs, "Idm:BuildUser", text(jar.pkg, "idm-builduser"), "CString");
        appendIfSet(attrs, "Idm:Protected", text(jar.pkg, "idm-protected"), "CBoolean");
        appendIfSet(attrs, "Idm:newVersion", text(jar.pkg, "idm-newversion"), "CBoolean");
        appendIfSet(attrs, "Idm:CreationTime", text(jar.pkg, "idm-creationtime"), "CString");
        appendIfSet(attrs, "Idm:vendorName", text(jar.pkg, "idm-vendorname"), "CString");
        appendIfSet(attrs, "Idm:vendorAddress", text(jar.pkg, "idm-vendoraddress"), "CString");
        appendIfSet(attrs, "Idm:vendorURL", text(jar.pkg, "idm-vendorurl"), "CString");
        appendIfSet(attrs, "Idm:vendorEmail", text(jar.pkg, "idm-vendoremail"), "CString");
        appendIfSet(attrs, "Idm:contactName", text(jar.pkg, "idm-contactname"), "CString");
        appendIfSet(attrs, "Idm:contactEmail", text(jar.pkg, "idm-contactemail"), "CString");
        appendIfSet(attrs, "Idm:minIdmVersion", text(jar.pkg, "idm-minidmversion"), "CString");
        appendIfSet(attrs, "Idm:maxIdmVersion", text(jar.pkg, "idm-maxidmversion"), "CString");
        appendIfSet(attrs, "Idm:minAppVersion", text(jar.pkg, "idm-minappversion"), "CString");
        appendIfSet(attrs, "Idm:maxAppVersion", text(jar.pkg, "idm-maxappversion"), "CString");
        appendIfSet(attrs, "Idm:InternalVersion", text(jar.pkg, "idm-internalversion"), "CLong");
        appendIfSet(attrs, "Idm:InstallationDirective", jar.directive, "CString");

        // ---- who installed it: the back-references, before the folder relations (test11pf's order) ----
        StringBuilder relations = new StringBuilder();
        List<String[]> targets = host.installTargets(jar.type, need.driverNames);
        for (String[] t : targets) {
            relations.append(relation(t[2], "BackReference", t[0], t[1]));
        }

        // ---- the nine folders and their items ----
        Map<Integer, List<PackageJar.Item>> itemsByFolder = new TreeMap<>();
        for (PackageJar.Item it : jar.items) {
            itemsByFolder.computeIfAbsent(it.folderId, k -> new ArrayList<>()).add(it);
        }
        for (int fid = 1; fid <= FOLDER_NAMES.length; fid++) {
            String folderObjectId = host.mintId();
            String folderName = jar.folderNames.getOrDefault(fid, FOLDER_NAMES[fid - 1]);
            if (folderName == null || folderName.isBlank()) {
                folderName = FOLDER_NAMES[fid - 1];
            }
            StringBuilder itemRelations = new StringBuilder();
            for (PackageJar.Item it : itemsByFolder.getOrDefault(fid, List.of())) {
                String type = cobjectTypeFor(it);
                if (type == null) {
                    return "package '" + need.describe() + "' carries an item of class '" + it.objectClass
                        + "' ('" + it.name + "', folder " + fid + " " + folderName + ") that this writer does not "
                        + "model as a Designer CObject; the project catalog would have to guess its type, so "
                        + "nothing was written — read a project holding that class first "
                        + "(docs/spikes/designer-project-catalog.md)";
                }
                String itemId = host.mintId();
                writeItem(it, type, itemId, pkgDir.resolve(folderObjectId), host);
                itemRelations.append(relation(relationNameFor(type), "Child", itemId, type));
                result.items++;
            }
            host.write(pkgDir.resolve(folderObjectId + ".IdmPackageFolder_"),
                CObjectXml.cobjectNoType(folderName,
                    CObjectXml.attr("Idm:FolderId", String.valueOf(fid), "CInteger") + itemRelations));
            relations.append(relation("Idm:PackageFolders", "Child", folderObjectId, "IdmPackageFolder"));
        }

        host.write(folderDir.resolve(pkgId + ".IdmPackage_"),
            CObjectXml.cobjectNoType(name, attrs + relations.toString()));
        host.addRelation(categoryFolderId, "Idm:Packages", "Child", pkgId, "IdmPackage");
        for (String[] t : targets) {
            host.addRelation(t[0], "Idm:InstalledPackages", "Reference", pkgId, "IdmPackage");
        }

        result.packageObjectId.put(need.id, pkgId);
        result.packageType.put(need.id, jar.type);
        result.installingDrivers.put(need.id, need.driverNames);
        result.packages++;
        return null;
    }

    /** One package item as a CObject, with the jar's stamps verbatim and its content beside it. */
    private static void writeItem(PackageJar.Item it, String type, String itemId, Path dir, Host host)
        throws IOException {
        StringBuilder attrs = new StringBuilder();
        String contents = contentsOf(it);
        if (contents != null) {
            attrs.append(heavy("contents", null));
        }
        if (it.contentType != null) {
            attrs.append(CObjectXml.attr("DirXML-ContentType", it.contentType, "CString"));
        }
        // PARAM:<x> on the jar's ds-object is IdmParameter:<x> on the CObject (test11pf's
        // NOVLEDIRBASE-ConnectionPrompts carries IdmParameter:initialSettingsPrompt)
        for (Element a : children(child(it.dsObject, "ds-attributes"), "ds-attribute")) {
            String an = a.getAttribute("ds-attr-name");
            if (an != null && an.startsWith("PARAM:")) {
                attrs.append(CObjectXml.attr("IdmParameter:" + an.substring("PARAM:".length()),
                    dsValue(a), "CString"));
            }
        }
        appendIfSet(attrs, "Idm:PkgPromptType", dsAttr(it.dsObject, "idm-pkgprompttype"), "CInteger");
        appendIfSet(attrs, "Idm:InstallationDirective", it.directive, "CString");
        appendIfSet(attrs, "Idm:DirectiveChecksum", it.storedDirectiveChecksum, "CLong");
        appendIfSet(attrs, "Idm:PackageAssocGuid", it.assocId, "CString");
        appendIfSet(attrs, "Idm:PackageGuid", it.packageGuid, "CString");
        appendIfSet(attrs, "Idm:ContentChecksum", it.storedContentChecksum, "CLong");

        host.write(dir.resolve(itemId + "." + type + "_"), CObjectXml.cobjectNoType(it.name, attrs.toString()));
        if (contents != null) {
            host.write(dir.resolve(itemId + "_contents.xml"), contents);
        }
    }

    /**
     * The item's {@code <I>_contents.xml}, or null when the jar carries none. The spike's
     * rule is "a contents file when the item's content element is non-null", for every
     * class (a prompt with no {@code XmlData} element — {@code NOVLEDIRBASE-UpgradeSettings}
     * — has neither the attribute nor the file). A text-only {@code XmlData} (an
     * ECMAScript resource, say) is written as it stands; no such item was observed in
     * {@code test11pf}, so that half of the rule is inference, not observation.
     */
    private static String contentsOf(PackageJar.Item it) {
        if (it.content != null) {
            return NxslCanonical.canonical(it.content);
        }
        if (it.text != null && !it.text.isBlank()) {
            return it.text;
        }
        return null;
    }

    /**
     * The CObject type for a package item, or null when the class is one this writer does
     * not model (which refuses the run rather than inventing a type). A {@code DirXML-Rule}
     * is typed by its content, exactly as {@code ProjectReader} derives it back.
     */
    private static String cobjectTypeFor(PackageJar.Item it) {
        if (CLASS_RULE.equals(it.objectClass)) {
            switch (Policy.Kind.of(it.content)) {
                case XSLT: return "StylesheetPolicy";
                case SCHEMA_MAP: return "MappingPolicy";
                default: return "ScriptPolicy";
            }
        }
        if (CLASS_RESOURCE.equals(it.objectClass)) {
            return "IDMResource";
        }
        if (CLASS_GCV_DEF.equals(it.objectClass)) {
            return "GlobalConfig";
        }
        return null;
    }

    /** The {@code IdmPackageFolder_}'s child relation name, by item type (test11pf's three). */
    private static String relationNameFor(String type) {
        if ("GlobalConfig".equals(type)) {
            return "Idm:GlobalConfigs";
        }
        if ("IDMResource".equals(type)) {
            return "Idm:Resources";
        }
        return "Idm:Policies";
    }

    /**
     * The three lines Designer's own package importer writes into {@code <K>_change.log}
     * (the jar carries no change log: it is the project's local history of that package,
     * and an import is its first three entries — verified on every package in test11pf).
     */
    private static byte[] changeLog(String name, String version) {
        String stamp = LocalDateTime.now().format(CHANGE_LOG_STAMP);
        String s = stamp + "    Setting package imported flag to 'null'\n"
            + stamp + "    Changed installation directive for '" + name + "' (version='" + version + "').\n"
            + stamp + "    Changed license for package '" + name + "' (version='" + version + "').\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ---- small DOM / XML helpers (the jar's DOM is plain w3c, no namespaces) ----

    private static String heavy(String attrName, String extension) {
        return "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\""
            + CObjectXml.esc(attrName) + "\""
            + (extension == null ? "" : " extension=\"" + CObjectXml.esc(extension) + "\"") + "/>";
    }

    private static String relation(String name, String type, String id, String typeSuffix) {
        return "<relations name=\"" + name + "\" type=\"" + type + "\" key=\"#" + id + "." + typeSuffix + "_\"/>";
    }

    private static void appendIfSet(StringBuilder sb, String attrName, String value, String xsiType) {
        if (value != null && !value.isEmpty()) {
            sb.append(CObjectXml.attr(attrName, value, xsiType));
        }
    }

    private static String attr(Element e, String name) {
        return e == null ? "" : e.getAttribute(name);
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String text(Element parent, String childName) {
        Element c = child(parent, childName);
        return c == null ? null : textOf(c).trim();
    }

    private static String textOf(Element e) {
        StringBuilder sb = new StringBuilder();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString();
    }

    /** A base64 blob out of the jar, decoded to the bytes Designer writes verbatim; null when empty. */
    private static byte[] decoded(Element e) {
        if (e == null) {
            return null;
        }
        String b64 = textOf(e).replaceAll("\\s", "");
        if (b64.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            return bytes.length == 0 ? null : bytes;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String dsAttr(Element dsObject, String attrName) {
        for (Element a : children(child(dsObject, "ds-attributes"), "ds-attribute")) {
            if (attrName.equalsIgnoreCase(a.getAttribute("ds-attr-name"))) {
                return dsValue(a);
            }
        }
        return null;
    }

    private static String dsValue(Element dsAttribute) {
        Element v = child(dsAttribute, "ds-value");
        return v == null ? null : textOf(v);
    }
}
