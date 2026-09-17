package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Provisioning;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds the model from a NetIQ / OpenText IDM <b>Designer project</b> on disk (the
 * directory containing {@code .project}/{@code *.proj}/{@code Model/}).
 *
 * <p>Empirically (checked against a small hand-built workspace and a 50-driver
 * production project; documented further by the {@code dirxml-designer-workspace}
 * skill):
 * <pre>
 *   Every object is a "CObject" metadata file named {@code <ID>.<Type>_} (root
 *   {@code com.novell.designer.model:CObject name="…" type="…"}), holding:
 *     &lt;attributes xsi:type="…C&lt;Kind&gt;" attrName="…" value="…"/&gt;   scalar (possibly nested
 *                                                                    under &lt;associatedAttrSets&gt;
 *                                                                    for per-server values — a
 *                                                                    plain recursive descendant
 *                                                                    search finds either)
 *     &lt;relations name="Idm:…" type="Child|Reference|BackReference" key="#&lt;ID&gt;.&lt;Type&gt;_"/&gt;
 *                                                                    ordered; document order is
 *                                                                    execution order for policy sets
 *   Payload (when present) is the sibling {@code <ID>_contents.xml}; a GCV bundle's
 *   resolved values live in {@code <ID>_<ServerID>_DirXML-ConfigValues.xml} (the
 *   {@code <ID>_initial_state.xml} sibling is the package baseline — not read here).
 *
 *   Type suffixes observed: DriverSet, Driver, Library, Subscriber, Publisher, Filter,
 *   GlobalConfig, ScriptPolicy, StylesheetPolicy, MappingPolicy, MappingTableResource,
 *   ECMAScriptResource (also seen but not modeled here: Server, Application, Job,
 *   IdmPackage, Entitlement, NotfTemplate/NotfTemplateCollection, SchemaDef).
 *
 *   Relation names observed:
 *     DriverSet: Idm:Drivers, Idm:Libraries, Idm:GlobalConfigs (Child, owns a driver-set-
 *       or library-shared GCV bundle), Idm:ConfigExtensions (Reference — re-points at a
 *       GCV bundle a Library already owns; not walked separately, would only duplicate),
 *       Idm:InstalledPackages, Idm:Servers, Idm:Jobs (counted in meta only)
 *     Library: Idm:Policies (Child, shared policies), Idm:Resources (Child, mapping
 *       tables / ECMAScript), Idm:GlobalConfigs (Child)
 *     Driver: Idm:Subscriber / Idm:Publisher (Child, singleton), Idm:Filter (Child,
 *       singleton), Idm:Policies (Child — every driver-scope policy, whether or not
 *       bound to Input/Output/MappingPolicies below), Idm:Resources (Child — mapping
 *       tables/ECMAScript), Idm:GlobalConfigs (Child — driver-scope *package* GCV
 *       definitions, distinct from the driver's own resolved DirXML-ConfigValues),
 *       Idm:MappingPolicies / Idm:InputPolicies / Idm:OutputPolicies (Reference,
 *       ordered — schema map / input transform / output transform; a policy bound here
 *       can be a ScriptPolicy_ OR a MappingPolicy_, kind is derived from content),
 *       Idm:ExtensionFunctions (Reference, ordered — ECMAScript resources in scope;
 *       target can be library- or driver-owned, or an unmodeled system resource whose
 *       id has no metadata, e.g. "0.ECMAScriptResource_")
 *     Subscriber_/Publisher_: Idm:Policies (Child — every channel-scope policy),
 *       Idm:EventPolicies / Idm:MatchingPolicies / Idm:CreatePolicies /
 *       Idm:PlacementPolicies / Idm:CommandPolicies (Reference, ordered); also checked
 *       defensively (not observed in either sample project) — Idm:InputPolicies /
 *       Idm:OutputPolicies / Idm:SchemaMappingPolicies at channel scope, folded into the
 *       driver-scope INPUT/OUTPUT/SCHEMA_MAPPING sets since the model has no per-channel
 *       equivalent, noted in driver meta.
 * </pre>
 *
 * <p>Designer objects carry no {@code dn} — the CObject id is the only stable identity,
 * so every {@link Driver}/{@link Artifact} gets {@code meta["designer.id"]} (and
 * {@code designer.type}) for a future Designer-format writer to map back onto. A DN is
 * synthesized ({@code cn=<name>,<parent>}) from the driver set's {@code DSetContext}
 * attribute so the model's {@code dn} fields aren't left null; {@code meta["dn.synthesized"]}
 * flags it as not authoritative.
 */
public final class ProjectReader {

    private ProjectReader() {
    }

    /** Read the driver set that has drivers (or the first one, if none do). */
    public static DriverSet read(Path projectDir) {
        return read(projectDir, null);
    }

    /** Read a specific driver set by name (falls back to the "has drivers" rule if not found). */
    public static DriverSet read(Path projectDir, String driverSetName) {
        Index idx = Index.scan(projectDir);

        List<String> dsIds = new ArrayList<>();
        for (Map.Entry<String, String> e : idx.typeById.entrySet()) {
            if ("DriverSet".equals(e.getValue())) {
                dsIds.add(e.getKey());
            }
        }
        if (dsIds.isEmpty()) {
            throw new IllegalArgumentException("no .DriverSet_ object found under " + projectDir);
        }

        String targetId = null;
        Map<String, String> namesById = new LinkedHashMap<>();
        for (String id : dsIds) {
            Element m = idx.parseMeta(id);
            namesById.put(id, attr(m, "name", id));
        }
        if (driverSetName != null) {
            for (Map.Entry<String, String> e : namesById.entrySet()) {
                if (e.getValue().equals(driverSetName)) {
                    targetId = e.getKey();
                    break;
                }
            }
        }
        if (targetId == null) {
            for (String id : dsIds) {
                if (!relationKeys(idx.parseMeta(id), "Idm:Drivers").isEmpty()) {
                    targetId = id;
                    break;
                }
            }
        }
        if (targetId == null) {
            targetId = dsIds.get(0);
        }

        DriverSet ds = buildDriverSet(idx, targetId, projectDir);
        if (dsIds.size() > 1) {
            List<String> others = new ArrayList<>();
            for (String id : dsIds) {
                if (!id.equals(targetId)) {
                    others.add(namesById.get(id));
                }
            }
            ds.meta.put("project.other-driversets", String.join(",", others));
        }
        return ds;
    }

    // ---- driver set ------------------------------------------------------------

    private static DriverSet buildDriverSet(Index idx, String dsId, Path projectDir) {
        Element m = idx.parseMeta(dsId);
        String name = attr(m, "name", "driverset");
        DriverSet ds = new DriverSet(name);
        ds.meta.put("designer.id", dsId);
        ds.meta.put("designer.type", "DriverSet");
        ds.meta.put("source.file", projectDir.toString());

        String dn = m.getAttribute("dn");
        if (dn == null || dn.isEmpty()) {
            String context = attrValue(m, "DSetContext");
            if (context != null && !context.isEmpty()) {
                dn = "cn=" + name + "," + context;
                ds.meta.put("dn.synthesized", "true");
            } else {
                dn = null;
            }
        }
        ds.dn = dn;

        ds.configValues = idx.configValuesFor(dsId);

        List<String> pkgs = relationKeys(m, "Idm:InstalledPackages");
        if (!pkgs.isEmpty()) {
            ds.meta.put("packages.count", String.valueOf(pkgs.size()));
        }
        List<String> jobs = relationKeys(m, "Idm:Jobs");
        if (!jobs.isEmpty()) {
            ds.meta.put("jobs.count", String.valueOf(jobs.size()));
        }

        for (String libKey : relationKeys(m, "Idm:Libraries")) {
            readLibrary(idx, idOf(libKey), ds);
        }
        // Driver-set GCV objects: library-scope resources, linked from the driver set
        // itself (DirXML-Policies on the driver set, set 14) — recorded the way the
        // export reader records them so consumers can tell "linked" from "present".
        int gcN = 0;
        for (String gcKey : relationKeys(m, "Idm:GlobalConfigs")) {
            Resource r = gcvDefResource(idx, idOf(gcKey), Scope.LIBRARY, null);
            ds.library.resources.add(r);
            ds.meta.put("driverset.linkage." + gcN,
                "cn=" + r.name + ",cn=Library," + ds.dn + "#" + gcN + "#" + PolicySet.GCV.id);
            gcN++;
        }

        Map<String, Driver> driversByDesignerId = new LinkedHashMap<>();
        for (String drvKey : relationKeys(m, "Idm:Drivers")) {
            String drvId = idOf(drvKey);
            if (idx.metaById.containsKey(drvId)) {
                Driver d = readDriver(idx, drvId, ds);
                ds.drivers.add(d);
                driversByDesignerId.put(drvId, d);
            }
        }
        attachProvisioning(idx, ds, projectDir, driversByDesignerId);
        return ds;
    }

    // ---- provisioning (Model/Provisioning/<AppConfigDir>: JSON forms + PRDs) --------

    /**
     * Attaches each {@code Model/Provisioning/<AppConfigDir>} to its driver. A
     * project ties an AppConfig folder to its driver indirectly: the folder's
     * digest {@code <guid>} is also the id of an {@code .Application_} CObject
     * elsewhere in the project (the "NProv" modeler node for that app), and
     * <i>that</i> object holds an {@code Idm:Drivers} reference to the real
     * driver — checked against {@code test11} (one AppConfig, resolves) and
     * {@code testc7} (two AppConfigs; the second, "AppConfig1", doesn't resolve
     * this way and there is no second Composer-shimmed driver to fall back to
     * either — so it lands in {@code driverset.meta["provisioning.unresolved.*"]}
     * rather than crashing or guessing). When the guid doesn't resolve, we fall
     * back to the driver whose shim class is {@code com.novell.idm.driver.ComposerDriverShim},
     * but only if the project has exactly one such driver and it doesn't already
     * have provisioning attached (never overwrite an already-resolved one).
     */
    private static void attachProvisioning(Index idx, DriverSet ds, Path projectDir, Map<String, Driver> driversByDesignerId) {
        Path provRoot = projectDir.resolve("Model").resolve("Provisioning");
        if (!Files.isDirectory(provRoot)) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(provRoot)) {
            s.filter(Files::isDirectory).sorted().forEach(dirs::add);
        } catch (IOException e) {
            return;
        }
        for (Path dir : dirs) {
            String dirName = dir.getFileName().toString();
            Path digestFile = dir.resolve(dirName + ".digest");
            if (!Files.exists(digestFile)) {
                continue;
            }
            Element digest;
            try {
                digest = Xds.parseFile(digestFile).getDocumentElement();
            } catch (Exception e) {
                continue;
            }
            String guid = firstChildText(digest, "guid");
            String display = firstChildText(digest, "display");
            Driver target = guid == null ? null : resolveDriverByAppGuid(idx, guid, driversByDesignerId);
            if (target == null) {
                target = fallbackComposerDriver(ds);
            }
            if (target == null || target.provisioning != null) {
                ds.meta.put("provisioning.unresolved." + dirName,
                    (guid == null ? "" : "guid=" + guid) + (display == null ? "" : " display='" + display + "'"));
                continue;
            }
            Provisioning p = new Provisioning();
            p.dn = target.dn != null ? "cn=AppConfig," + target.dn : null;
            p.meta.put("designer.dir", dirName);
            // the AppConfig container digest is the only place the project records the
            // srvprvAppConfig version (4.8 in a 4.8.7 project); the writer needs it to mint
            // a .appconfig for a driver whose project has none yet.
            String appConfigVersion = digest.getAttribute("version");
            if (appConfigVersion != null && !appConfigVersion.isEmpty()) {
                p.meta.put("project.appconfig-version", appConfigVersion);
            }
            readFormsDir(dir.resolve("WorkflowForms"), p);
            readPrdsDir(dir.resolve("RequestDefs"), p);
            target.provisioning = p;
        }
    }

    private static Driver resolveDriverByAppGuid(Index idx, String guid, Map<String, Driver> byDesignerId) {
        Element app = idx.parseMeta(guid);
        if (app == null) {
            return null;
        }
        for (String key : relationKeys(app, "Idm:Drivers")) {
            Driver d = byDesignerId.get(idOf(key));
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** The project's sole {@code ComposerDriverShim} driver, if exactly one and unclaimed; null otherwise. */
    private static Driver fallbackComposerDriver(DriverSet ds) {
        Driver only = null;
        for (Driver d : ds.drivers) {
            if ("com.novell.idm.driver.ComposerDriverShim".equals(d.shimClass)) {
                if (only != null) {
                    return null;
                }
                only = d;
            }
        }
        return only;
    }

    private static void readFormsDir(Path workflowFormsDir, Provisioning p) {
        if (!Files.isDirectory(workflowFormsDir)) {
            return;
        }
        for (Form.Kind kind : Form.Kind.values()) {
            Path dir = workflowFormsDir.resolve(kind.container);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            String ext = "." + (kind == Form.Kind.REQUEST ? "formRequest" : kind == Form.Kind.APPROVAL ? "formApproval" : "formTemplate");
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(f -> f.getFileName().toString().endsWith(ext)).sorted().forEach(files::add);
            } catch (IOException e) {
                continue;
            }
            for (Path f : files) {
                String fn = f.getFileName().toString();
                String name = fn.substring(0, fn.length() - ext.length());
                String json;
                try {
                    json = Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                Form form = new Form(kind, name, json);
                Path digestFile = dir.resolve(name + ".digest");
                if (Files.exists(digestFile)) {
                    try {
                        readItemDigestMeta(Xds.parseFile(digestFile).getDocumentElement(), form.meta);
                    } catch (Exception e) {
                        // no digest meta; content already captured
                    }
                }
                p.forms.add(form);
            }
        }
    }

    private static void readPrdsDir(Path requestDefsDir, Provisioning p) {
        if (!Files.isDirectory(requestDefsDir)) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(requestDefsDir)) {
            s.filter(f -> f.getFileName().toString().endsWith(".prd")).sorted().forEach(files::add);
        } catch (IOException e) {
            return;
        }
        for (Path f : files) {
            try {
                p.prds.add(readPrdFile(f, requestDefsDir));
            } catch (Exception e) {
                // an unreadable PRD is skipped rather than failing the whole project
            }
        }
    }

    /**
     * The {@code .prd} file is the union document ({@code docs/spikes/json-forms-format.md}
     * §3): {@code <prov-req-defn>} containing {@code <provision-request>} and
     * {@code <process>} inline. Split it: {@link Prd#request} is the removed
     * {@code <provision-request>} child; {@link Prd#process}, like the vault, stays
     * a child of {@link Prd#definition} too (see {@link Prd} class doc).
     */
    private static Prd readPrdFile(Path prdFile, Path dir) throws IOException {
        // Xds/XmlDocument (the engine's own DOM) does not normalize \r\n line endings
        // in text content the way a conformant parser does; CanonicalXml.normalize
        // re-parses through our own pipeline so a PRD's content is already in the
        // shape the as-code tree's own reader would produce (see its javadoc) —
        // otherwise a script with CRLF line endings (seen on the test vault) would
        // make the very first write differ from every write after a tree round trip.
        Element root = CanonicalXml.normalize(Xds.parseFile(prdFile).getDocumentElement());
        String fileName = prdFile.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".prd".length());

        Element request = null;
        List<Element> provReq = Xds.childrenByName(root, "provision-request");
        if (!provReq.isEmpty()) {
            request = provReq.get(0);
            root.removeChild(request);
        }
        Element process = null;
        List<Element> procs = Xds.childrenByName(root, "process");
        if (!procs.isEmpty()) {
            process = procs.get(0);
        }

        Path digestFile = dir.resolve(name + ".digest");
        Element digest = null;
        if (Files.exists(digestFile)) {
            try {
                digest = Xds.parseFile(digestFile).getDocumentElement();
                String cn = digest.getAttribute("cn");
                if (cn.startsWith("cn=")) {
                    name = cn.substring(3);
                }
            } catch (Exception e) {
                // keep the filename-derived name
            }
        }

        Prd prd = new Prd(name);
        prd.definition = root;
        prd.request = request;
        prd.process = process;

        putProjectProp(prd, "status", root.getAttribute("status"));
        putProjectProp(prd, "flow-strategy", root.getAttribute("flow-strategy"));
        putProjectProp(prd, "grant", root.getAttribute("grant"));
        putProjectProp(prd, "revoke", root.getAttribute("revoke"));
        putProjectProp(prd, "category-key", root.getAttribute("prov-category"));
        if (process != null) {
            putProjectProp(prd, "process-type", process.getAttribute("process-type"));
        }
        // vault shape: one value, "lang~text" pairs joined by "|" (verified against
        // the test vault's srvprvLocalizedNames/Descrs) — built here the same way so
        // the project and the vault compare equal.
        String names = joinLangText(root, "display-name");
        if (names != null) {
            prd.properties.put("localized-names", new ArrayList<>(List.of(names)));
        }
        String descrs = joinLangText(root, "description");
        if (descrs != null) {
            prd.properties.put("localized-descrs", new ArrayList<>(List.of(descrs)));
        }
        String enDescr = langText(root, "description", "en");
        if (enDescr != null) {
            prd.properties.put("description", new ArrayList<>(List.of(enDescr)));
        }

        if (digest != null) {
            readItemDigestMeta(digest, prd.meta);
        }
        return prd;
    }

    private static void putProjectProp(Prd prd, String key, String value) {
        if (value != null && !value.isEmpty()) {
            prd.properties.put(key, new ArrayList<>(List.of(value)));
        }
    }

    /** {@code lang~text|lang~text|…} over every {@code <childName xml:lang>} child, document order. */
    private static String joinLangText(Element root, String childName) {
        List<Element> els = Xds.childrenByName(root, childName);
        if (els.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Element el : els) {
            String lang = xmlLang(el);
            String text = Xds.text(el);
            if (lang == null || text == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(lang).append('~').append(text);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String langText(Element root, String childName, String lang) {
        for (Element el : Xds.childrenByName(root, childName)) {
            if (lang.equals(xmlLang(el))) {
                return Xds.text(el);
            }
        }
        return null;
    }

    private static String xmlLang(Element el) {
        String v = el.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
        if (v != null && !v.isEmpty()) {
            return v;
        }
        v = el.getAttribute("xml:lang");
        return v.isEmpty() ? null : v;
    }

    private static String firstChildText(Element parent, String localName) {
        List<Element> c = Xds.childrenByName(parent, localName);
        return c.isEmpty() ? null : Xds.text(c.get(0));
    }

    /** Digest {@code <item .../>} attributes/children -&gt; {@code project.*} meta keys (docs/forms.md §3). */
    private static void readItemDigestMeta(Element digest, Map<String, String> meta) {
        putAttrMeta(digest, "protected", meta, "project.protected");
        putAttrMeta(digest, "readonly", meta, "project.readonly");
        putChildTextMeta(digest, "guid", meta, "project.guid");
        putChildTextMeta(digest, "dirguid", meta, "project.dirguid");
        putChildTextMeta(digest, "dirrev", meta, "project.dirrev");
        putChildTextMeta(digest, "package-id", meta, "project.package-id");
        putChildTextMeta(digest, "pkg-assoc-id", meta, "project.pkg-assoc-id");
        putChildTextMeta(digest, "pkg-checksum", meta, "project.pkg-checksum");
    }

    private static void putAttrMeta(Element el, String attrName, Map<String, String> meta, String key) {
        String v = el.getAttribute(attrName);
        if (v != null && !v.isEmpty()) {
            meta.put(key, v);
        }
    }

    private static void putChildTextMeta(Element el, String childName, Map<String, String> meta, String key) {
        String v = firstChildText(el, childName);
        if (v != null && !v.isEmpty()) {
            meta.put(key, v);
        }
    }

    private static void readLibrary(Index idx, String libId, DriverSet ds) {
        Element m = idx.parseMeta(libId);
        if (m == null) {
            return;
        }
        for (String key : relationKeys(m, "Idm:Policies")) {
            Policy p = readPolicy(idx, idOf(key), Scope.LIBRARY, null);
            if (p != null) {
                ds.library.policies.add(p);
            }
        }
        for (String key : relationKeys(m, "Idm:Resources")) {
            Resource r = readResource(idx, idOf(key), Scope.LIBRARY, null);
            if (r != null) {
                ds.library.resources.add(r);
            }
        }
        for (String key : relationKeys(m, "Idm:GlobalConfigs")) {
            ds.library.resources.add(gcvDefResource(idx, idOf(key), Scope.LIBRARY, null));
        }
    }

    // ---- driver ------------------------------------------------------------------

    private static Driver readDriver(Index idx, String drvId, DriverSet ds) {
        Element m = idx.parseMeta(drvId);
        String name = attr(m, "name", "driver");
        Driver d = new Driver(name);
        d.meta.put("designer.id", drvId);
        d.meta.put("designer.type", "Driver");
        String driverTypeAttr = m.getAttribute("type");
        if (driverTypeAttr != null && !driverTypeAttr.isEmpty()) {
            d.meta.put("designer.driver-type", driverTypeAttr);
        }

        String dn = m.getAttribute("dn");
        if (dn == null || dn.isEmpty()) {
            if (ds.dn != null) {
                dn = "cn=" + name + "," + ds.dn;
                d.meta.put("dn.synthesized", "true");
            }
        }
        d.dn = dn;

        d.shimClass = attrValue(m, "DirXML-JavaModule");
        d.shimAuthServer = attrValue(m, "DirXML-ShimAuthServer");
        d.shimAuthId = attrValue(m, "DirXML-ShimAuthID");

        String shimConfigInfo = attrValue(m, "DirXML-ShimConfigInfo");
        if (shimConfigInfo != null && !shimConfigInfo.isBlank()) {
            try {
                d.config.put(Driver.SHIM_CONFIG_INFO, Xds.parse(shimConfigInfo).getDocumentElement());
            } catch (Exception e) {
                d.meta.put("unparsed." + Driver.SHIM_CONFIG_INFO, truncate(shimConfigInfo));
            }
        }
        String ecv = attrValue(m, "DirXML-EngineControlValues");
        if (ecv != null && !ecv.isBlank()) {
            try {
                d.config.put(Driver.ENGINE_CONTROL_VALUES, Xds.parse(ecv).getDocumentElement());
            } catch (Exception e) {
                d.meta.put("unparsed." + Driver.ENGINE_CONTROL_VALUES, truncate(ecv));
            }
        }
        Element ownConfigValues = idx.configValuesFor(drvId);
        if (ownConfigValues != null) {
            d.config.put(Driver.CONFIG_VALUES, ownConfigValues);
        }

        List<String> filterKeys = relationKeys(m, "Idm:Filter");
        if (!filterKeys.isEmpty()) {
            Path c = idx.contentsById.get(idOf(filterKeys.get(0)));
            if (c != null) {
                Element filter = Xds.parseFile(c).getDocumentElement();
                if (filter != null) {
                    d.config.put(Driver.DRIVER_FILTER, filter);
                }
            }
        }

        copyPackageMeta(m, d.meta);
        String version = attrValue(m, "DirXML-DriverVersion");
        if (version != null) {
            d.meta.put("version", version);
        }
        List<String> pkgs = relationKeys(m, "Idm:InstalledPackages");
        if (!pkgs.isEmpty()) {
            d.meta.put("packages.count", String.valueOf(pkgs.size()));
        }
        List<String> ents = relationKeys(m, "Idm:Entitlements");
        if (!ents.isEmpty()) {
            d.meta.put("entitlements.count", String.valueOf(ents.size()));
        }
        for (String key : ents) {
            Entitlement e = readEntitlement(idx, idOf(key));
            if (e != null) {
                d.entitlements.add(e);
            }
        }

        // driver-scope policies: every owned policy, whether or not linked below
        for (String key : relationKeys(m, "Idm:Policies")) {
            Policy p = readPolicy(idx, idOf(key), Scope.DRIVER, d.name);
            if (p != null) {
                d.policies.add(p);
            }
        }
        // driver-scope resources (mapping tables, ECMAScript, …)
        for (String key : relationKeys(m, "Idm:Resources")) {
            Resource r = readResource(idx, idOf(key), Scope.DRIVER, d.name);
            if (r != null) {
                d.resources.add(r);
            }
        }
        // driver-scope package GCV definitions: real, linkable objects (policy set 14)
        int gcvOrder = 0;
        for (String key : relationKeys(m, "Idm:GlobalConfigs")) {
            Resource r = gcvDefResource(idx, idOf(key), Scope.DRIVER, d.name);
            d.resources.add(r);
            d.links.add(new PolicyLink(PolicySet.GCV, r.path(), gcvOrder++));
        }

        // driver-scope linkage
        addLinks(idx, d, m, "Idm:MappingPolicies", PolicySet.SCHEMA_MAPPING, Scope.DRIVER, d.name, 0);
        addLinks(idx, d, m, "Idm:InputPolicies", PolicySet.INPUT, Scope.DRIVER, d.name, 0);
        addLinks(idx, d, m, "Idm:OutputPolicies", PolicySet.OUTPUT, Scope.DRIVER, d.name, 0);
        addLinks(idx, d, m, "Idm:ExtensionFunctions", PolicySet.ECMASCRIPT, Scope.LIBRARY, null, 0);

        // channels
        List<String> subKeys = relationKeys(m, "Idm:Subscriber");
        if (!subKeys.isEmpty()) {
            readChannel(idx, idOf(subKeys.get(0)), d, Scope.SUBSCRIBER);
        }
        List<String> pubKeys = relationKeys(m, "Idm:Publisher");
        if (!pubKeys.isEmpty()) {
            readChannel(idx, idOf(pubKeys.get(0)), d, Scope.PUBLISHER);
        }

        return d;
    }

    private static void readChannel(Index idx, String chanId, Driver d, Scope scope) {
        Element m = idx.parseMeta(chanId);
        if (m == null) {
            return;
        }
        List<Policy> bucket = scope == Scope.SUBSCRIBER ? d.subscriber.policies : d.publisher.policies;
        for (String key : relationKeys(m, "Idm:Policies")) {
            Policy p = readPolicy(idx, idOf(key), scope, d.name);
            if (p != null) {
                bucket.add(p);
            }
        }
        boolean sub = scope == Scope.SUBSCRIBER;
        addLinks(idx, d, m, "Idm:EventPolicies", sub ? PolicySet.SUB_EVENT : PolicySet.PUB_EVENT, scope, d.name, 0);
        addLinks(idx, d, m, "Idm:MatchingPolicies", sub ? PolicySet.SUB_MATCH : PolicySet.PUB_MATCH, scope, d.name, 0);
        addLinks(idx, d, m, "Idm:CreatePolicies", sub ? PolicySet.SUB_CREATE : PolicySet.PUB_CREATE, scope, d.name, 0);
        addLinks(idx, d, m, "Idm:PlacementPolicies", sub ? PolicySet.SUB_PLACEMENT : PolicySet.PUB_PLACEMENT, scope, d.name, 0);
        addLinks(idx, d, m, "Idm:CommandPolicies", sub ? PolicySet.SUB_COMMAND : PolicySet.PUB_COMMAND, scope, d.name, 0);

        // Not observed in either sample project, but the skill documents these as legal
        // relations on a channel; the model has no per-channel equivalent, so fold them
        // into the driver-scope set and flag it happened.
        int extra = 0;
        for (String relName : new String[] {"Idm:InputPolicies", "Idm:OutputPolicies", "Idm:SchemaMappingPolicies", "Idm:TransformPolicies"}) {
            List<String> keys = relationKeys(m, relName);
            if (keys.isEmpty()) {
                continue;
            }
            PolicySet target = relName.equals("Idm:InputPolicies") ? PolicySet.INPUT
                : relName.equals("Idm:OutputPolicies") ? PolicySet.OUTPUT
                : relName.equals("Idm:SchemaMappingPolicies") ? PolicySet.SCHEMA_MAPPING
                : null;
            if (target == null) {
                d.meta.put("channel." + (sub ? "subscriber" : "publisher") + ".unmodeled." + (extra++), relName + " x" + keys.size());
                continue;
            }
            int order = d.links(target).size();
            for (String key : keys) {
                Policy p = readPolicy(idx, idOf(key), Scope.DRIVER, d.name);
                if (p != null) {
                    d.policies.add(p);
                    d.links.add(new PolicyLink(target, p.path(), order++));
                }
            }
            d.meta.put("channel." + (sub ? "subscriber" : "publisher") + ".folded." + relName, String.valueOf(keys.size()));
        }
    }

    /**
     * Ordered linkage from one relation name into {@code set}. {@code fallbackScope}/
     * {@code fallbackDriver} are used when the target id resolves to nothing already
     * modeled (e.g. an id outside the project, or a genuinely dangling reference) — the
     * link is still added with a best-guess path so {@link DriverSet#unresolvedLinks()}
     * can report it.
     */
    private static void addLinks(Index idx, Driver d, Element owner, String relationName, PolicySet set,
                                  Scope fallbackScope, String fallbackDriver, int startOrder) {
        int order = startOrder;
        for (String key : relationKeys(owner, relationName)) {
            String id = idOf(key);
            String ref = idx.artifactPath(id);
            if (ref == null) {
                Element target = idx.metaById.containsKey(id) ? idx.parseMeta(id) : null;
                String name = (target != null && !isRefStub(target)) ? attr(target, "name", id) : id;
                ref = Artifact.path(fallbackScope, fallbackDriver, name);
            }
            d.links.add(new PolicyLink(set, ref, order++));
        }
    }

    // ---- one policy / resource / gcv-def ------------------------------------------

    private static Policy readPolicy(Index idx, String id, Scope scope, String driverName) {
        Element m = idx.parseMeta(id);
        if (m == null || isRefStub(m)) {
            return null;
        }
        String name = attr(m, "name", null);
        if (name == null) {
            return null;
        }
        Element content = null;
        Path c = idx.contentsById.get(id);
        if (c != null) {
            try {
                content = Xds.parseFile(c).getDocumentElement();
            } catch (Exception e) {
                // leave content null; nothing else to preserve without a multi-KB meta blob
            }
        }
        Policy p = new Policy(name, scope, driverName, content);
        p.meta.put("designer.id", id);
        p.meta.put("designer.type", idx.typeById.getOrDefault(id, "Policy"));
        copyPackageMeta(m, p.meta);
        idx.register(id, scope, driverName, name);
        return p;
    }

    /**
     * A {@code .Entitlement_} CObject: {@code name="<cn>" type="Entitlement"}, content
     * the sibling {@code _contents.xml}'s whole {@code <entitlement>} document
     * (docs/entitlements.md §1.2). Not registered in the artifact registry — nothing
     * else references an entitlement by Designer id.
     */
    private static Entitlement readEntitlement(Index idx, String id) {
        Element m = idx.parseMeta(id);
        if (m == null || isRefStub(m)) {
            return null;
        }
        String name = attr(m, "name", null);
        if (name == null) {
            return null;
        }
        Element content = null;
        Path c = idx.contentsById.get(id);
        if (c != null) {
            try {
                content = Xds.parseFile(c).getDocumentElement();
            } catch (Exception e) {
                // leave content null
            }
        }
        Entitlement ent = new Entitlement(name, content);
        ent.meta.put("designer.id", id);
        ent.meta.put("designer.type", idx.typeById.getOrDefault(id, "Entitlement"));
        copyPackageMeta(m, ent.meta);
        return ent;
    }

    private static Resource readResource(Index idx, String id, Scope scope, String driverName) {
        Element m = idx.parseMeta(id);
        if (m == null || isRefStub(m)) {
            return null;
        }
        String name = attr(m, "name", "");
        String contentType = attrValue(m, "DirXML-ContentType");
        Resource r = new Resource(name, scope, driverName, contentType);
        Path c = idx.contentsById.get(id);
        if (c != null) {
            try {
                String raw = new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
                if (r.isEcmaScript() || !raw.stripLeading().startsWith("<")) {
                    r.text = raw;
                } else {
                    try {
                        r.content = Xds.parse(raw).getDocumentElement();
                    } catch (Exception e) {
                        r.text = raw;
                    }
                }
            } catch (IOException e) {
                // no content readable; still a valid (empty) resource record
            }
        }
        r.meta.put("designer.id", id);
        r.meta.put("designer.type", idx.typeById.getOrDefault(id, "Resource"));
        copyPackageMeta(m, r.meta);
        idx.register(id, scope, driverName, name);
        return r;
    }

    /**
     * A {@code .GlobalConfig_} object as a {@link Resource} of type {@link Resource#GCV_DEF}:
     * content is the resolved {@code <configuration-values>} from its
     * {@code <ID>_<ServerID>_DirXML-ConfigValues.xml}, the same shape the vault holds on a
     * {@code DirXML-GlobalConfigDef} object, so a link to it resolves and a deploy can write
     * it back verbatim.
     */
    private static Resource gcvDefResource(Index idx, String id, Scope scope, String driverName) {
        Element m = idx.parseMeta(id);
        if (m != null && isRefStub(m)) {
            m = null;
        }
        String name = m != null ? attr(m, "name", "gcv") : id;
        Resource r = new Resource(name, scope, driverName, Resource.GCV_DEF);
        r.content = idx.configValuesFor(id);
        r.meta.put("designer.id", id);
        r.meta.put("designer.type", "GlobalConfig");
        if (m != null) {
            copyPackageMeta(m, r.meta);
        }
        idx.register(id, scope, driverName, name);
        return r;
    }

    private static void copyPackageMeta(Element el, Map<String, String> meta) {
        putIfPresent(el, "Idm:PackageGuid", meta, "package-id");
        putIfPresent(el, "Idm:PackageAssocGuid", meta, "pkg-assoc-id");
        putIfPresent(el, "Idm:ContentChecksum", meta, "checksum");
        putIfPresent(el, "Idm:DirectiveChecksum", meta, "directive-checksum");
    }

    private static void putIfPresent(Element el, String attrName, Map<String, String> meta, String key) {
        String v = attrValue(el, attrName);
        if (v != null && !v.isEmpty()) {
            meta.put(key, v);
        }
    }

    // ---- CObject helpers -----------------------------------------------------------

    /**
     * A {@code type="Ref"} CObject is Designer's placeholder for a relation target it
     * couldn't resolve locally (e.g. a cross-driver-set or since-deleted reference) — its
     * {@code name} is the original DN, not a real artifact name, and the same id (seen in
     * the wild as {@code 0.ECMAScriptResource_}) is reused project-wide for every such
     * dangling target regardless of what each one actually pointed at. Never model it as
     * an artifact; let the link stay genuinely unresolved instead.
     */
    private static boolean isRefStub(Element m) {
        return "Ref".equals(m.getAttribute("type"));
    }

    private static String attr(Element el, String name, String dflt) {
        if (el == null) {
            return dflt;
        }
        String v = el.getAttribute(name);
        return v.isEmpty() ? dflt : v;
    }

    /**
     * First descendant {@code <attributes attrName=name value=…/>}, wherever it sits
     * (top-level or nested inside {@code <associatedAttrSets>} for a per-server value).
     * Returns null if absent or the value is empty (e.g. a {@code CHeavyData} placeholder
     * whose real content lives in a sibling file).
     */
    private static String attrValue(Element cobject, String attrName) {
        if (cobject == null) {
            return null;
        }
        for (Element a : descendantsByName(cobject, "attributes")) {
            if (attrName.equals(a.getAttribute("attrName"))) {
                String v = a.getAttribute("value");
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static List<Element> descendantsByName(Node ctx, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList kids = ctx.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE) {
                if (localName.equals(k.getLocalName()) || localName.equals(k.getNodeName())) {
                    out.add((Element) k);
                }
                out.addAll(descendantsByName(k, localName));
            }
        }
        return out;
    }

    /** Ordered relation keys of a given name, excluding BackReference (inverse pointers). */
    private static List<String> relationKeys(Element meta, String relationName) {
        List<String> keys = new ArrayList<>();
        if (meta == null) {
            return keys;
        }
        for (Element rel : Xds.childrenByName(meta, "relations")) {
            if (relationName.equals(rel.getAttribute("name")) && !"BackReference".equals(rel.getAttribute("type"))) {
                String key = rel.getAttribute("key");
                if (key != null && !key.isEmpty()) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /** {@code "#OES3U2HZ.ScriptPolicy_"} -&gt; {@code "OES3U2HZ"}. */
    private static String idOf(String key) {
        String s = key.startsWith("#") ? key.substring(1) : key;
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    // ---- project index ---------------------------------------------------------------

    /** One pass over the project directory: every object's metadata/content/GCV files, by id. */
    private static final class Index {
        final Map<String, Path> metaById = new LinkedHashMap<>();
        final Map<String, String> typeById = new LinkedHashMap<>();
        final Map<String, Path> contentsById = new LinkedHashMap<>();
        final List<Path> configValueFiles = new ArrayList<>();
        final Map<String, Element> metaCache = new LinkedHashMap<>();
        /** designer id -> (scope, driver, name), populated as artifacts are created, so
         *  Reference-relation linkage (which only carries an id) can resolve to a real path. */
        final Map<String, ArtifactRef> registry = new LinkedHashMap<>();

        static Index scan(Path projectDir) {
            Index idx = new Index();
            try (Stream<Path> walk = Files.walk(projectDir)) {
                walk.filter(Files::isRegularFile).forEach(f -> {
                    String fn = f.getFileName().toString();
                    if (fn.endsWith("_contents.xml")) {
                        idx.contentsById.put(fn.substring(0, fn.length() - "_contents.xml".length()), f);
                    } else if (fn.endsWith("_DirXML-ConfigValues.xml")) {
                        idx.configValueFiles.add(f);
                    } else if (fn.endsWith("_initial_state.xml") || fn.endsWith("_schema.xml")) {
                        // package baseline / eDir schema — not read by this reader
                    } else {
                        int dot = fn.lastIndexOf('.');
                        if (dot > 0 && fn.endsWith("_")) {
                            String id = fn.substring(0, dot);
                            String type = fn.substring(dot + 1, fn.length() - 1);
                            idx.metaById.putIfAbsent(id, f);
                            idx.typeById.putIfAbsent(id, type);
                        }
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to read Designer project " + projectDir + ": " + e, e);
            }
            return idx;
        }

        Element parseMeta(String id) {
            Path p = metaById.get(id);
            if (p == null) {
                return null;
            }
            return metaCache.computeIfAbsent(id, k -> Xds.parseFile(p).getDocumentElement());
        }

        /** The first {@code <id>_<serverId>_DirXML-ConfigValues.xml}, parsed, or null. */
        Element configValuesFor(String id) {
            String prefix = id + "_";
            for (Path f : configValueFiles) {
                if (f.getFileName().toString().startsWith(prefix)) {
                    try {
                        return Xds.parseFile(f).getDocumentElement();
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
            return null;
        }

        void register(String id, Scope scope, String driverName, String name) {
            registry.putIfAbsent(id, new ArtifactRef(scope, driverName, name));
        }

        /** The artifact path already recorded for this designer id, or null if unknown. */
        String artifactPath(String id) {
            ArtifactRef ref = registry.get(id);
            return ref == null ? null : Artifact.path(ref.scope, ref.driver, ref.name);
        }
    }

    /** (scope, driver, name) for a designer id already turned into a model {@link Artifact}. */
    private static final class ArtifactRef {
        final Scope scope;
        final String driver;
        final String name;
        ArtifactRef(Scope scope, String driver, String name) {
            this.scope = scope;
            this.driver = driver;
            this.name = name;
        }
    }
}
