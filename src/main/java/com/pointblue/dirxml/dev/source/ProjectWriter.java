package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.deploy.VaultMapping;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Form;
import com.pointblue.dirxml.dev.model.Policy;
import com.pointblue.dirxml.dev.model.PolicyLink;
import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.model.Resource;
import com.pointblue.dirxml.dev.model.Scope;
import com.pointblue.dirxml.dev.packages.InstalledChecksum;
import com.pointblue.dirxml.dev.xml.CanonicalXml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Updates an existing Designer project on disk to match a tree — the writer half of
 * the round trip {@code ProjectReader} reads. See {@code docs/designer-roundtrip.md},
 * section 3, for the scope, the change table this class implements, and what is
 * deliberately left out (packaged-driver creation, driver removal, provisioning
 * objects, {@code _initial_state.xml} maintenance for untouched objects).
 *
 * <p>{@link #update} reads the project ({@link ProjectReader}) and the tree
 * ({@code AsCodeReader}), computes {@link ModelDiff#of} (project = "from", tree =
 * "to"), and edits <b>only</b> the files the diff calls for: CObject metadata files
 * ({@code <ID>.<Type>_}) are parsed, mutated at the DOM level and re-serialized by
 * {@link CObjectXml} (Designer's own on-disk shape as closely as a generic DOM edit
 * can reproduce it — see that class's doc for the two known formatting differences);
 * content files ({@code <ID>_contents.xml}, GCV values files) are rewritten as
 * canonical XML via {@link CanonicalXml}, the same as everywhere else in this
 * project. Ids for new objects are minted in Designer's form (8 characters from
 * {@code [0-9A-Z]}, unique across the project).
 *
 * <h2>Provisioning (JSON forms + PRDs)</h2>
 * <p>For each driver whose {@code AppConfig} the project reader could tie to it
 * (see {@code ProjectReader#attachProvisioning}), the writer also carries
 * {@code FORM_*}/{@code PRD_*} changes ({@link ModelDiff}) into
 * {@code Model/Provisioning/<AppConfig dir>/}: a form is its document, compact
 * ({@link VaultMapping#formBytes}, byte-identical to what the vendor builder and
 * the vault hold) plus a minted {@code <name>.digest} item (no
 * {@code dirguid}/{@code dirrev}/package elements unless the tree carries package
 * meta); a PRD is the union {@code <name>.prd} ({@code <provision-request>}
 * re-inserted right before {@code <process>}, matching Designer's own layout)
 * plus a {@code <name>.digest} with localized display/descr, category key and one
 * {@code <digest-dependency>} per form binding. The container digests
 * ({@code WorkflowForms.digest}, {@code WorkflowRequestForms.digest},
 * {@code RequestDefs.digest}, …) carry only the container's own description on
 * every project sampled ({@code docs/spikes/json-forms-format.md} §4) — nothing
 * to add there when a form/PRD is added or removed. A driver whose project has
 * no AppConfig refuses only its own provisioning changes (a note, not a whole-
 * update refusal) — creating a new AppConfig is out of scope.
 *
 * <h2>What this class does not implement</h2>
 * <ul>
 *   <li>{@code ARTIFACT_KIND_CHANGED} — noted and left alone (not required by the
 *       design's scope; a kind change is rare and ambiguous to translate into a
 *       CObject type change without more study of what Designer accepts).</li>
 *   <li>{@code DRIVERSET_LINKAGE} — the driver set's own GCV objects can be owned
 *       either by the {@code Library_} object or by the driver set object itself
 *       (both surface as {@code library/*} paths in the model, and the model does
 *       not record which); reordering is noted rather than attempted so an
 *       ambiguous case is never guessed at.</li>
 *   <li>A brand-new project (no existing Designer origin), packaged-driver
 *       creation, and a whole new AppConfig are out of scope per the design
 *       note; all refuse (the last, only for its own driver).</li>
 *   <li>A form/PRD {@code FORM_CHANGED}/{@code PRD_CHANGED} whose only difference
 *       is package stamps (content identical) is noted rather than rewritten —
 *       regenerating the digest from the model would drop
 *       {@code protected}/{@code readonly}/{@code dirguid}/{@code dirrev}, which
 *       this writer never reads back off an existing digest.</li>
 *   <li>A PRD whose Designer digest {@code cn} disagrees with its filename (a
 *       documented but unobserved-in-the-wild Designer quirk) is addressed by
 *       filename, not by the digest's {@code cn}.</li>
 * </ul>
 */
public final class ProjectWriter {

    private static final Pattern ID_IN_FILENAME = Pattern.compile("^([0-9A-Z]{8})[._]");
    private static final Pattern CONFIG_VALUES_FILE =
        Pattern.compile("^([0-9A-Za-z]+)_([0-9A-Za-z]+)_DirXML-ConfigValues\\.xml$");
    private static final char[] ID_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern DIGEST_GUID = Pattern.compile("<guid>([0-9A-Z]{8})</guid>");
    private static final String DIGEST_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    private ProjectWriter() {
    }

    /** Updates {@code projectDir} to match {@code tree}; writes nothing if {@code dryRun}. */
    public static Result update(Path tree, Path projectDir, boolean dryRun) throws IOException {
        Result result = new Result();
        DriverSet project = ProjectReader.read(projectDir);
        DriverSet treeDs = AsCodeReader.read(tree);
        ModelDiff diff = ModelDiff.of(project, treeDs);
        if (diff.isEmpty()) {
            result.ok = true;
            return result;
        }

        Ctx ctx = new Ctx(projectDir, project, treeDs, dryRun, result);
        ctx.scan();

        for (ModelDiff.Change c : diff.changes()) {
            if (c.kind == ModelDiff.Kind.DRIVER_REMOVED) {
                result.refusal = "driver '" + c.driver + "' was removed from the tree — a driver is removed "
                    + "from a project deliberately, in Designer; nothing was written";
                return result;
            }
        }
        for (ModelDiff.Change c : diff.changes()) {
            if (c.kind == ModelDiff.Kind.DRIVER_ADDED) {
                Driver d = treeDs.driver(c.driver);
                if (ctx.hasPackageMeta(d)) {
                    result.refusal = "driver '" + d.name + "' carries package metadata — the project needs "
                        + "Designer's IdmPackage installation records, which only Designer produces; deploy it "
                        + "to the vault and use Designer's \"Import from the Identity Vault\" instead; nothing was written";
                    return result;
                }
            }
        }

        ctx.detectRenames(diff);

        for (ModelDiff.Change c : diff.changes()) {
            if (ctx.consumed.contains(c)) {
                continue;
            }
            switch (c.kind) {
                case DRIVER_ADDED:
                    ctx.applyDriverAdd(treeDs.driver(c.driver));
                    break;
                case DRIVER_REMOVED:
                    break; // unreachable: refused above
                case ARTIFACT_ADDED:
                    ctx.applyArtifactAdded(treeDs.resolve(c.path));
                    break;
                case ARTIFACT_REMOVED:
                    ctx.applyArtifactRemoved(project.resolve(c.path));
                    break;
                case ARTIFACT_CHANGED:
                    ctx.applyArtifactChanged(project.resolve(c.path), treeDs.resolve(c.path));
                    break;
                case ARTIFACT_KIND_CHANGED:
                    result.notes.add("kind-changed artifact '" + c.path + "' is not supported by this writer; left unchanged");
                    break;
                case DRIVER_SETTING:
                    ctx.applyDriverSetting(c.driver, c.what, treeDs.driver(c.driver));
                    break;
                case DRIVER_CONFIG:
                    ctx.applyDriverConfig(c.driver, c.what, treeDs.driver(c.driver));
                    break;
                case DRIVER_LINKAGE:
                    ctx.applyDriverLinkage(treeDs.driver(c.driver), PolicySet.byKey(c.what));
                    break;
                case DRIVERSET_GCVS:
                    ctx.applyDriverSetGcvs();
                    break;
                case DRIVERSET_LINKAGE:
                    result.notes.add("driver-set GCV linkage changed ('" + c.what + "') but reordering the driver "
                        + "set's own Idm:GlobalConfigs relations is not implemented (ownership between the driver "
                        + "set and its Library object is ambiguous in the model); left unchanged");
                    break;
                case FORM_ADDED: {
                    Form f = resolveFormFromPath(treeDs, c.path);
                    Driver td = treeDs.driver(c.driver);
                    if (f != null && td != null) {
                        ctx.applyFormAdded(td, f);
                    }
                    break;
                }
                case FORM_REMOVED: {
                    Form f = resolveFormFromPath(project, c.path);
                    if (f != null) {
                        ctx.applyFormRemoved(c.driver, f);
                    }
                    break;
                }
                case FORM_CHANGED: {
                    Form f = resolveFormFromPath(treeDs, c.path);
                    if (f != null) {
                        ctx.applyFormChanged(c.driver, f, c.what);
                    }
                    break;
                }
                case PRD_ADDED: {
                    Prd p = resolvePrdFromPath(treeDs, c.path);
                    Driver td = treeDs.driver(c.driver);
                    if (p != null && td != null) {
                        ctx.applyPrdAdded(td, p);
                    }
                    break;
                }
                case PRD_REMOVED: {
                    Prd p = resolvePrdFromPath(project, c.path);
                    if (p != null) {
                        ctx.applyPrdRemoved(c.driver, p);
                    }
                    break;
                }
                case PRD_CHANGED: {
                    Prd p = resolvePrdFromPath(treeDs, c.path);
                    Driver td = treeDs.driver(c.driver);
                    if (p != null && td != null) {
                        ctx.applyPrdChanged(td, p, c.what);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        ctx.flush();
        result.ok = true;
        return result;
    }

    // ------------------------------------------------------------------
    // Result
    // ------------------------------------------------------------------

    /** House style ({@code deploy.Deployer.Result}): what happened, or why it refused. */
    public static final class Result {
        public boolean ok;
        public String refusal;
        public final List<String> changedFiles = new ArrayList<>();
        public final List<String> deletedFiles = new ArrayList<>();
        public final List<String> createdFiles = new ArrayList<>();
        public final Map<String, String> mintedIds = new LinkedHashMap<>();
        public final List<String> notes = new ArrayList<>();

        public String text() {
            StringBuilder sb = new StringBuilder();
            if (refusal != null) {
                sb.append("REFUSED — ").append(refusal).append('\n');
                return sb.toString();
            }
            if (changedFiles.isEmpty() && createdFiles.isEmpty() && deletedFiles.isEmpty()) {
                sb.append("no differences — nothing written\n");
            }
            for (String f : createdFiles) {
                sb.append("  created  ").append(f).append('\n');
            }
            for (String f : changedFiles) {
                sb.append("  changed  ").append(f).append('\n');
            }
            for (String f : deletedFiles) {
                sb.append("  deleted  ").append(f).append('\n');
            }
            for (Map.Entry<String, String> e : mintedIds.entrySet()) {
                sb.append("  minted   ").append(e.getValue()).append(" for ").append(e.getKey()).append('\n');
            }
            for (String n : notes) {
                sb.append("  note     ").append(n).append('\n');
            }
            sb.append(ok ? "OK" : "FAILED").append('\n');
            return sb.toString();
        }

        public String json() {
            StringBuilder sb = new StringBuilder("{\"ok\":").append(ok);
            sb.append(",\"refusal\":").append(refusal == null ? "null" : q(refusal));
            sb.append(",\"createdFiles\":").append(arr(createdFiles));
            sb.append(",\"changedFiles\":").append(arr(changedFiles));
            sb.append(",\"deletedFiles\":").append(arr(deletedFiles));
            sb.append(",\"mintedIds\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : mintedIds.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(q(e.getKey())).append(':').append(q(e.getValue()));
            }
            sb.append("},\"notes\":").append(arr(notes));
            return sb.append('}').toString();
        }

        private static String arr(List<String> items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(q(items.get(i)));
            }
            return sb.append(']').toString();
        }

        private static String q(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.append('"').toString();
        }
    }

    // ------------------------------------------------------------------
    // shared content helper
    // ------------------------------------------------------------------

    private static String contentStringFor(Artifact a) {
        if (a instanceof Policy) {
            Element el = ((Policy) a).content;
            return el == null ? null : CanonicalXml.serialize(el);
        }
        Resource r = (Resource) a;
        if (r.isText()) {
            return r.text == null ? "" : r.text;
        }
        return r.content == null ? null : CanonicalXml.serialize(r.content);
    }

    private static String typeSuffixFor(Artifact a) {
        if (a instanceof Policy) {
            switch (((Policy) a).policyKind()) {
                case DIRXML_SCRIPT: return "ScriptPolicy";
                case XSLT: return "StylesheetPolicy";
                case SCHEMA_MAP: return "MappingPolicy";
                default: return null;
            }
        }
        Resource r = (Resource) a;
        if (r.isGcvDef()) {
            return "GlobalConfig";
        }
        if (r.isMappingTable()) {
            return "MappingTableResource";
        }
        if (r.isEcmaScript()) {
            return "ECMAScriptResource";
        }
        return null;
    }

    private static String childRelationNameFor(Artifact a) {
        if (a instanceof Policy) {
            return "Idm:Policies";
        }
        Resource r = (Resource) a;
        return r.isGcvDef() ? "Idm:GlobalConfigs" : "Idm:Resources";
    }

    private static String relationNameForSet(PolicySet set) {
        switch (set) {
            case SCHEMA_MAPPING: return "Idm:MappingPolicies";
            case INPUT: return "Idm:InputPolicies";
            case OUTPUT: return "Idm:OutputPolicies";
            case ECMASCRIPT: return "Idm:ExtensionFunctions";
            case GCV: return "Idm:GlobalConfigs";
            case SUB_EVENT: case PUB_EVENT: return "Idm:EventPolicies";
            case SUB_MATCH: case PUB_MATCH: return "Idm:MatchingPolicies";
            case SUB_CREATE: case PUB_CREATE: return "Idm:CreatePolicies";
            case SUB_COMMAND: case PUB_COMMAND: return "Idm:CommandPolicies";
            case SUB_PLACEMENT: case PUB_PLACEMENT: return "Idm:PlacementPolicies";
            default: throw new IllegalStateException("unknown policy set " + set);
        }
    }

    private static boolean isDriverLevel(PolicySet set) {
        return set == PolicySet.SCHEMA_MAPPING || set == PolicySet.INPUT || set == PolicySet.OUTPUT
            || set == PolicySet.ECMASCRIPT || set == PolicySet.GCV;
    }

    // ------------------------------------------------------------------
    // provisioning (JSON forms + PRDs) path resolution
    // ------------------------------------------------------------------

    /** {@code ModelDiff.formPath}/{@code prdPath}, split back into (driver, kind, name). */
    private static final class ProvPath {
        final String driver;
        final Form.Kind kind;
        final String name;

        ProvPath(String driver, Form.Kind kind, String name) {
            this.driver = driver;
            this.kind = kind;
            this.name = name;
        }
    }

    /** {@code drivers/<d>/provisioning/forms/<kindDir>/<name>} -&gt; (d, kind, name). */
    private static ProvPath parseFormPath(String path) {
        String rest = path.substring("drivers/".length());
        int i = rest.indexOf("/provisioning/forms/");
        String driver = rest.substring(0, i);
        String tail = rest.substring(i + "/provisioning/forms/".length());
        int slash = tail.indexOf('/');
        return new ProvPath(driver, Form.Kind.byDir(tail.substring(0, slash)), tail.substring(slash + 1));
    }

    /** {@code drivers/<d>/provisioning/prds/<name>} -&gt; (d, null, name). */
    private static ProvPath parsePrdPath(String path) {
        String rest = path.substring("drivers/".length());
        int i = rest.indexOf("/provisioning/prds/");
        String driver = rest.substring(0, i);
        return new ProvPath(driver, null, rest.substring(i + "/provisioning/prds/".length()));
    }

    private static Form resolveFormFromPath(DriverSet ds, String path) {
        ProvPath pp = parseFormPath(path);
        Driver d = ds.driver(pp.driver);
        return (d == null || d.provisioning == null) ? null : d.provisioning.form(pp.kind, pp.name);
    }

    private static Prd resolvePrdFromPath(DriverSet ds, String path) {
        ProvPath pp = parsePrdPath(path);
        Driver d = ds.driver(pp.driver);
        return (d == null || d.provisioning == null) ? null : d.provisioning.prd(pp.name);
    }

    private static String formExt(Form.Kind kind) {
        switch (kind) {
            case REQUEST: return "formRequest";
            case APPROVAL: return "formApproval";
            case TEMPLATE: return "formTemplate";
            default: throw new IllegalStateException("unknown form kind " + kind);
        }
    }

    /** {@code lang~text|lang~text|…} (the shape {@code ProjectReader}/{@code LdifReader} store) -&gt; [lang,text] pairs, in order. */
    private static List<String[]> langPairs(List<String> propertyValue) {
        List<String[]> out = new ArrayList<>();
        if (propertyValue == null || propertyValue.isEmpty() || propertyValue.get(0) == null) {
            return out;
        }
        for (String part : propertyValue.get(0).split("\\|")) {
            int tilde = part.indexOf('~');
            if (tilde > 0) {
                out.add(new String[] {part.substring(0, tilde), part.substring(tilde + 1)});
            }
        }
        return out;
    }

    /** A package stamp copied from provisioning meta the way {@code ProjectReader} maps a digest item's stamps back. */
    private static String provPkgMeta(Map<String, String> meta, String suffix) {
        return meta.get("project." + suffix);
    }

    // ------------------------------------------------------------------
    // low-level CObject DOM helpers
    // ------------------------------------------------------------------

    private static List<String> relationKeys(Element meta, String relationName) {
        List<String> keys = new ArrayList<>();
        if (meta == null) {
            return keys;
        }
        for (Node n = meta.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && "relations".equals(n.getNodeName())) {
                Element rel = (Element) n;
                if (relationName.equals(rel.getAttribute("name")) && !"BackReference".equals(rel.getAttribute("type"))) {
                    String key = rel.getAttribute("key");
                    if (key != null && !key.isEmpty()) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    private static String idOf(String key) {
        String s = key.startsWith("#") ? key.substring(1) : key;
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static List<Element> childrenNamed(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static List<Element> descendantsByName(Node ctx, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = ctx.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                if (name.equals(n.getNodeName())) {
                    out.add((Element) n);
                }
                out.addAll(descendantsByName(n, name));
            }
        }
        return out;
    }

    private static Element findAttributeElement(Element root, String attrName) {
        for (Element e : descendantsByName(root, "attributes")) {
            if (attrName.equals(e.getAttribute("attrName"))) {
                return e;
            }
        }
        return null;
    }

    private static boolean removeRelationsWithKey(Element root, String key) {
        List<Element> toRemove = new ArrayList<>();
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.ELEMENT_NODE && "relations".equals(n.getNodeName())) {
                Element e = (Element) n;
                if (key.equals(e.getAttribute("key"))) {
                    toRemove.add(e);
                }
            }
        }
        for (Element e : toRemove) {
            root.removeChild(e);
        }
        return !toRemove.isEmpty();
    }

    private static void appendRelation(Document doc, String name, String type, String id, String typeSuffix) {
        Element rel = doc.createElement("relations");
        rel.setAttribute("name", name);
        rel.setAttribute("type", type);
        rel.setAttribute("key", "#" + id + "." + typeSuffix + "_");
        doc.getDocumentElement().appendChild(rel);
    }

    // ------------------------------------------------------------------
    // Ctx: everything the writer needs to know about the project on disk
    // ------------------------------------------------------------------

    private static final class Ctx {
        final Path projectDir;
        final DriverSet project;
        final DriverSet treeDs;
        final boolean dryRun;
        final Result result;

        final Map<String, Path> metaFileById = new LinkedHashMap<>();
        final Map<String, String> typeById = new LinkedHashMap<>();
        final Map<String, Path> contentsFileById = new LinkedHashMap<>();
        final Map<String, Path> initialStateFileById = new LinkedHashMap<>();
        final Map<String, List<Path>> configValueFilesById = new LinkedHashMap<>();
        final Set<String> usedIds = new HashSet<>();

        final Map<String, String> idByPath = new LinkedHashMap<>();
        final Map<String, String> typeByPath = new LinkedHashMap<>();

        final Map<String, Document> allDocs = new LinkedHashMap<>();
        final Set<String> dirtyIds = new LinkedHashSet<>();
        final Set<String> newlyCreatedIds = new HashSet<>();

        final Map<String, String> driverIdOverride = new LinkedHashMap<>();
        final Map<String, String> channelIdCache = new LinkedHashMap<>();

        final Set<ModelDiff.Change> consumed = Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        /** driver name -&gt; {@code Model/Provisioning/<AppConfig dir>} on disk, only for drivers the project ties an AppConfig to. */
        final Map<String, Path> provisioningDirByDriver = new LinkedHashMap<>();
        final Set<String> notedMissingProvisioning = new HashSet<>();

        String dsId;
        String libraryId;
        String serverId;
        String attrSetObjectUri;

        Ctx(Path projectDir, DriverSet project, DriverSet treeDs, boolean dryRun, Result result) {
            this.projectDir = projectDir;
            this.project = project;
            this.treeDs = treeDs;
            this.dryRun = dryRun;
            this.result = result;
            for (Map.Entry<String, Artifact> e : project.index().entrySet()) {
                Artifact a = e.getValue();
                String id = a.meta.get("designer.id");
                String type = a.meta.get("designer.type");
                if (id != null) {
                    idByPath.put(e.getKey(), id);
                    typeByPath.put(e.getKey(), type);
                }
            }
        }

        // ---- one-time project scan ----

        void scan() throws IOException {
            dsId = project.meta.get("designer.id");
            try (Stream<Path> walk = Files.walk(projectDir)) {
                for (Path f : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String fn = f.getFileName().toString();
                    Matcher idm = ID_IN_FILENAME.matcher(fn);
                    if (idm.find()) {
                        usedIds.add(idm.group(1));
                    }
                    Matcher cvm = CONFIG_VALUES_FILE.matcher(fn);
                    if (fn.endsWith("_contents.xml")) {
                        contentsFileById.put(fn.substring(0, fn.length() - "_contents.xml".length()), f);
                    } else if (fn.endsWith("_initial_state.xml")) {
                        initialStateFileById.put(fn.substring(0, fn.length() - "_initial_state.xml".length()), f);
                    } else if (cvm.matches()) {
                        configValueFilesById.computeIfAbsent(cvm.group(1), k -> new ArrayList<>()).add(f);
                        if (serverId == null) {
                            serverId = cvm.group(2);
                        }
                    } else if (fn.endsWith(".digest")) {
                        // a form/PRD digest's own <guid> isn't in its filename (forms/PRDs are named by cn,
                        // not by a minted id) — collect it anyway so a freshly minted id never collides.
                        try {
                            Matcher gm = DIGEST_GUID.matcher(Files.readString(f, StandardCharsets.UTF_8));
                            while (gm.find()) {
                                usedIds.add(gm.group(1));
                            }
                        } catch (IOException e) {
                            // not fatal: mintId() re-rolls on any collision it does detect
                        }
                    } else {
                        int dot = fn.lastIndexOf('.');
                        if (dot > 0 && fn.endsWith("_")) {
                            String id = fn.substring(0, dot);
                            String type = fn.substring(dot + 1, fn.length() - 1);
                            metaFileById.putIfAbsent(id, f);
                            typeById.putIfAbsent(id, type);
                        }
                    }
                }
            }
            if (dsId != null && metaFileById.containsKey(dsId)) {
                Element dsRoot = parsed(dsId).getDocumentElement();
                for (Element e : childrenNamed(dsRoot, "associatedAttrSets")) {
                    String uri = e.getAttribute("objectURI");
                    if (uri != null && !uri.isEmpty()) {
                        attrSetObjectUri = uri;
                        break;
                    }
                }
            }
            Path provisioningRoot = projectDir.resolve("Model").resolve("Provisioning");
            for (Driver d : project.drivers) {
                if (d.provisioning == null) {
                    continue;
                }
                String dirName = d.provisioning.meta.get("designer.dir");
                if (dirName != null) {
                    provisioningDirByDriver.put(d.name, provisioningRoot.resolve(dirName));
                }
            }
        }

        /** The driver's {@code Model/Provisioning/<AppConfig dir>} on disk, or null when the project has none. */
        Path provisioningDir(String driverName) {
            return provisioningDirByDriver.get(driverName);
        }

        /**
         * True (and the directory exists) when the driver has an AppConfig the project reader could tie to it;
         * otherwise records a note (once per driver) and returns false — the writer refuses only that driver's
         * provisioning changes, per {@code docs/designer-roundtrip.md}'s "AppConfig creation is out of scope".
         */
        boolean requireProvisioningDir(String driverName) {
            if (provisioningDir(driverName) != null) {
                return true;
            }
            if (notedMissingProvisioning.add(driverName)) {
                result.notes.add("driver '" + driverName + "' has no AppConfig in the project (or the project "
                    + "reader couldn't tie one to it); its provisioning (forms/PRDs) changes were not written — "
                    + "creating a whole AppConfig is out of scope");
            }
            return false;
        }

        boolean hasPackageMeta(Driver d) {
            if (d.meta.containsKey("package-id") || d.meta.containsKey("pkg-assoc-id")) {
                return true;
            }
            for (String k : d.meta.keySet()) {
                if (k.toLowerCase().startsWith("dirxml-pkg")) {
                    return true;
                }
            }
            for (Artifact a : d.artifacts()) {
                if (Packages.isPackaged(a)) {
                    return true;
                }
            }
            return false;
        }

        // ---- id / directory resolution ----

        String mintId() {
            while (true) {
                StringBuilder sb = new StringBuilder(8);
                for (int i = 0; i < 8; i++) {
                    sb.append(ID_ALPHABET[RNG.nextInt(ID_ALPHABET.length)]);
                }
                String id = sb.toString();
                if (usedIds.add(id)) {
                    return id;
                }
            }
        }

        String libraryId() {
            if (libraryId == null && dsId != null) {
                Element dsRoot = parsed(dsId).getDocumentElement();
                List<String> keys = relationKeys(dsRoot, "Idm:Libraries");
                libraryId = keys.isEmpty() ? null : idOf(keys.get(0));
            }
            return libraryId;
        }

        String driverId(String name) {
            String o = driverIdOverride.get(name);
            if (o != null) {
                return o;
            }
            Driver d = project.driver(name);
            return d == null ? null : d.meta.get("designer.id");
        }

        String channelId(String driverName, Scope scope) {
            String key = driverName + "|" + scope;
            String cached = channelIdCache.get(key);
            if (cached != null) {
                return cached;
            }
            String driverId = driverId(driverName);
            if (driverId == null || !metaFileById.containsKey(driverId)) {
                return null;
            }
            Element driverRoot = parsed(driverId).getDocumentElement();
            String relName = scope == Scope.SUBSCRIBER ? "Idm:Subscriber" : "Idm:Publisher";
            List<String> keys = relationKeys(driverRoot, relName);
            String id = keys.isEmpty() ? null : idOf(keys.get(0));
            if (id != null) {
                channelIdCache.put(key, id);
            }
            return id;
        }

        String ownerContainerId(Scope scope, String driverName) {
            switch (scope) {
                case LIBRARY: return libraryId();
                case DRIVER: return driverId(driverName);
                case SUBSCRIBER: return channelId(driverName, Scope.SUBSCRIBER);
                case PUBLISHER: return channelId(driverName, Scope.PUBLISHER);
                default: throw new IllegalStateException();
            }
        }

        Path ownerDir(Scope scope, String driverName) {
            String ownerId = ownerContainerId(scope, driverName);
            Path ownerMeta = metaFileById.get(ownerId);
            if (ownerMeta == null) {
                throw new IllegalStateException("no CObject file on record for owner id " + ownerId
                    + " (scope " + scope + ", driver " + driverName + ")");
            }
            Path base = ownerMeta.getParent();
            if (scope == Scope.SUBSCRIBER || scope == Scope.PUBLISHER) {
                return base.resolve(ownerId);
            }
            return base;
        }

        Path dsChildrenDir() {
            Path dsMeta = metaFileById.get(dsId);
            return dsMeta.getParent().resolve(dsId);
        }

        String findDriverTypeForShim(String shimClass) {
            if (shimClass != null) {
                for (Driver d : project.drivers) {
                    if (shimClass.equals(d.shimClass)) {
                        String did = d.meta.get("designer.id");
                        if (did != null && metaFileById.containsKey(did)) {
                            String t = parsed(did).getDocumentElement().getAttribute("type");
                            if (t != null && !t.isEmpty()) {
                                return t;
                            }
                        }
                    }
                }
            }
            return "[ANY]";
        }

        // ---- doc cache ----

        Document parsed(String id) {
            return allDocs.computeIfAbsent(id, k -> {
                Path p = metaFileById.get(id);
                try {
                    return CanonicalXml.parse(Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        Document docFor(String id) {
            Document d = parsed(id);
            dirtyIds.add(id);
            return d;
        }

        void markDirty(String id) {
            dirtyIds.add(id);
        }

        void setStringAttribute(String id, String attrName, String value, String xsiType) {
            Document doc = docFor(id);
            Element root = doc.getDocumentElement();
            Element existing = findAttributeElement(root, attrName);
            if (value == null) {
                if (existing != null) {
                    existing.getParentNode().removeChild(existing);
                }
                return;
            }
            if (existing != null) {
                existing.setAttribute("value", value);
                return;
            }
            Element el = doc.createElement("attributes");
            el.setAttribute("xsi:type", "com.novell.designer.model:" + xsiType);
            el.setAttribute("attrName", attrName);
            el.setAttribute("value", value);
            Node firstRelation = null;
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() == Node.ELEMENT_NODE && "relations".equals(n.getNodeName())) {
                    firstRelation = n;
                    break;
                }
            }
            if (firstRelation != null) {
                root.insertBefore(el, firstRelation);
            } else {
                root.appendChild(el);
            }
        }

        // ---- file IO bookkeeping ----

        String relative(Path p) {
            return projectDir.relativize(p).toString().replace('\\', '/');
        }

        void writeFile(Path p, String content, boolean created) throws IOException {
            if (!dryRun) {
                Files.createDirectories(p.getParent());
                Files.write(p, content.getBytes(StandardCharsets.UTF_8));
            }
            (created ? result.createdFiles : result.changedFiles).add(relative(p));
        }

        void deleteFile(Path p) throws IOException {
            if (p == null || !Files.exists(p)) {
                return;
            }
            if (!dryRun) {
                Files.delete(p);
            }
            result.deletedFiles.add(relative(p));
        }

        void flush() throws IOException {
            for (String id : dirtyIds) {
                Path p = metaFileById.get(id);
                Document doc = allDocs.get(id);
                String xml = CObjectXml.serialize(doc.getDocumentElement());
                if (!dryRun) {
                    Files.createDirectories(p.getParent());
                    Files.write(p, xml.getBytes(StandardCharsets.UTF_8));
                }
                String rel = relative(p);
                if (newlyCreatedIds.contains(id)) {
                    if (!result.createdFiles.contains(rel)) {
                        result.createdFiles.add(rel);
                    }
                } else {
                    result.changedFiles.add(rel);
                }
            }
        }

        // ---- rename detection ----

        void detectRenames(ModelDiff diff) {
            List<ModelDiff.Change> removed = new ArrayList<>();
            List<ModelDiff.Change> added = new ArrayList<>();
            for (ModelDiff.Change c : diff.changes()) {
                if (c.kind == ModelDiff.Kind.ARTIFACT_REMOVED) {
                    removed.add(c);
                } else if (c.kind == ModelDiff.Kind.ARTIFACT_ADDED) {
                    added.add(c);
                }
            }
            for (ModelDiff.Change rc : removed) {
                Artifact oldA = project.resolve(rc.path);
                if (oldA == null) {
                    continue;
                }
                String oldContent = contentStringFor(oldA);
                for (ModelDiff.Change ac : added) {
                    if (consumed.contains(ac)) {
                        continue;
                    }
                    Artifact newA = treeDs.resolve(ac.path);
                    if (newA == null || !sameOwner(oldA, newA) || (oldA instanceof Policy) != (newA instanceof Policy)) {
                        continue;
                    }
                    if (oldA instanceof Policy) {
                        if (((Policy) oldA).policyKind() != ((Policy) newA).policyKind()) {
                            continue;
                        }
                    } else if (!Objects.equals(((Resource) oldA).contentType, ((Resource) newA).contentType)) {
                        continue;
                    }
                    if (!Objects.equals(oldContent, contentStringFor(newA))) {
                        continue;
                    }
                    String id = oldA.meta.get("designer.id");
                    String type = oldA.meta.get("designer.type");
                    if (id == null) {
                        continue;
                    }
                    Document doc = docFor(id);
                    doc.getDocumentElement().setAttribute("name", newA.name);
                    idByPath.remove(rc.path);
                    typeByPath.remove(rc.path);
                    idByPath.put(ac.path, id);
                    typeByPath.put(ac.path, type);
                    consumed.add(rc);
                    consumed.add(ac);
                    result.notes.add("renamed " + rc.path + " -> " + ac.path + " (kept id " + id + ")");
                    break;
                }
            }
        }

        private static boolean sameOwner(Artifact a, Artifact b) {
            return a.scope == b.scope && Objects.equals(a.driver, b.driver);
        }

        // ---- artifact add / remove / change ----

        void applyArtifactAdded(Artifact b) throws IOException {
            String typeSuffix = typeSuffixFor(b);
            if (typeSuffix == null) {
                result.notes.add("artifact '" + b.path() + "' has a kind this writer doesn't create; not written");
                return;
            }
            String newId = mintId();
            Path dir = ownerDir(b.scope, b.driver);
            Path metaFile = dir.resolve(newId + "." + typeSuffix + "_");
            metaFileById.put(newId, metaFile);
            typeById.put(newId, typeSuffix);
            idByPath.put(b.path(), newId);
            typeByPath.put(b.path(), typeSuffix);
            result.mintedIds.put(b.path(), newId);

            if ("GlobalConfig".equals(typeSuffix)) {
                Resource r = (Resource) b;
                String uri = attrSetObjectUri != null ? attrSetObjectUri : "";
                String metaXml = CObjectXml.cobject(b.name, typeSuffix,
                    "<associatedAttrSets objectURI=\"" + CObjectXml.esc(uri) + "\">"
                        + "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"DirXML-ConfigValues\"/>"
                        + "</associatedAttrSets>", "");
                writeFile(metaFile, metaXml, true);
                Path valuesFile = dir.resolve(newId + "_" + serverId + "_DirXML-ConfigValues.xml");
                writeFile(valuesFile, CanonicalXml.serialize(r.content), true);
            } else {
                StringBuilder attrsXml = new StringBuilder(
                    "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"contents\"/>");
                if (b instanceof Resource) {
                    Resource r = (Resource) b;
                    attrsXml.append(CObjectXml.attr("DirXML-ContentType", r.contentType, "CString"));
                }
                String metaXml = CObjectXml.cobject(b.name, typeSuffix, attrsXml.toString(), "");
                writeFile(metaFile, metaXml, true);
                Path contentsFile = dir.resolve(newId + "_contents.xml");
                String content = contentStringFor(b);
                writeFile(contentsFile, content == null ? "" : content, true);
            }

            String ownerId = ownerContainerId(b.scope, b.driver);
            Document ownerDoc = docFor(ownerId);
            appendRelation(ownerDoc, childRelationNameFor(b), "Child", newId, typeSuffix);
        }

        void applyArtifactRemoved(Artifact a) throws IOException {
            if (a == null) {
                return;
            }
            String id = a.meta.get("designer.id");
            String type = a.meta.get("designer.type");
            if (id == null) {
                result.notes.add("cannot remove '" + a.path() + "': no designer id on record");
                return;
            }
            deleteFile(metaFileById.get(id));
            deleteFile(contentsFileById.get(id));
            deleteFile(initialStateFileById.get(id));
            for (Path f : configValueFilesById.getOrDefault(id, List.of())) {
                deleteFile(f);
            }

            String ownerId = ownerContainerId(a.scope, a.driver);
            String key = "#" + id + "." + type + "_";
            if (ownerId != null && metaFileById.containsKey(ownerId)) {
                Document ownerDoc = docFor(ownerId);
                removeRelationsWithKey(ownerDoc.getDocumentElement(), key);
            }

            String keyToken = "key=\"" + key + "\"";
            for (Map.Entry<String, Path> e : new ArrayList<>(metaFileById.entrySet())) {
                String candidateId = e.getKey();
                if (candidateId.equals(id) || candidateId.equals(ownerId)) {
                    continue;
                }
                Path p = e.getValue();
                if (!Files.exists(p)) {
                    continue;
                }
                // ISO-8859-1 for this plain substring pre-filter: it never throws on any byte sequence, and
                // the ASCII key token we're looking for round-trips identically regardless of the file's
                // real encoding, so a byte-exact read is not needed here.
                String text = dirtyIds.contains(candidateId)
                    ? CObjectXml.serialize(allDocs.get(candidateId).getDocumentElement())
                    : new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                if (text.contains(keyToken)) {
                    Document doc = docFor(candidateId);
                    removeRelationsWithKey(doc.getDocumentElement(), key);
                }
            }

            idByPath.remove(a.path());
            typeByPath.remove(a.path());
        }

        void applyArtifactChanged(Artifact oldA, Artifact newA) throws IOException {
            String id = oldA.meta.get("designer.id");
            if (id == null) {
                result.notes.add("cannot update '" + oldA.path() + "': no designer id on record");
                return;
            }
            if (newA instanceof Resource && ((Resource) newA).isGcvDef()) {
                Resource r = (Resource) newA;
                String content = CanonicalXml.serialize(r.content);
                List<Path> files = configValueFilesById.get(id);
                if (files == null || files.isEmpty()) {
                    Path dir = metaFileById.get(id).getParent();
                    writeFile(dir.resolve(id + "_" + serverId + "_DirXML-ConfigValues.xml"), content, true);
                } else {
                    for (Path f : files) {
                        writeFile(f, content, false);
                    }
                }
            } else {
                String content = contentStringFor(newA);
                Path contentsFile = contentsFileById.get(id);
                boolean created = contentsFile == null;
                if (contentsFile == null) {
                    contentsFile = metaFileById.get(id).getParent().resolve(id + "_contents.xml");
                }
                writeFile(contentsFile, content == null ? "" : content, created);
                if (newA instanceof Resource) {
                    Resource ra = (Resource) oldA;
                    Resource rb = (Resource) newA;
                    if (!Objects.equals(ra.contentType, rb.contentType)) {
                        setStringAttribute(id, "DirXML-ContentType", rb.contentType, "CString");
                    }
                }
            }
            if (Packages.isPackaged(newA) && Packages.isCustomized(newA)) {
                // Designer's own installed-content recipe (the same one the vault stamp and
                // package.status use), so the project and the vault carry the same number.
                Driver owner = newA.driver == null ? null : treeDs.driver(newA.driver);
                String checksum = Long.toString(InstalledChecksum.of(treeDs, owner, newA));
                setStringAttribute(id, "Idm:ContentChecksum", checksum, "CLong");
            }
        }

        // ---- provisioning: forms ----

        void applyFormAdded(Driver treeDriver, Form f) throws IOException {
            if (!requireProvisioningDir(treeDriver.name)) {
                return;
            }
            Path containerDir = provisioningDir(treeDriver.name).resolve("WorkflowForms").resolve(f.kind.container);
            Files.createDirectories(containerDir);
            String guid = mintId();
            String pkgId = provPkgMeta(f.meta, "package-id");
            String pkgAssocId = provPkgMeta(f.meta, "pkg-assoc-id");
            String pkgChecksum = provPkgMeta(f.meta, "pkg-checksum");
            writeFile(containerDir.resolve(f.name + "." + formExt(f.kind)),
                new String(VaultMapping.formBytes(f), StandardCharsets.UTF_8), true);
            writeFile(containerDir.resolve(f.name + ".digest"), formDigestXml(f, guid, pkgId, pkgAssocId, pkgChecksum), true);
            result.mintedIds.put(ModelDiff.formPath(treeDriver, f), guid);
        }

        void applyFormRemoved(String driverName, Form f) throws IOException {
            if (!requireProvisioningDir(driverName)) {
                return;
            }
            Path containerDir = provisioningDir(driverName).resolve("WorkflowForms").resolve(f.kind.container);
            deleteFile(containerDir.resolve(f.name + "." + formExt(f.kind)));
            deleteFile(containerDir.resolve(f.name + ".digest"));
        }

        void applyFormChanged(String driverName, Form newForm, String what) throws IOException {
            if (!requireProvisioningDir(driverName)) {
                return;
            }
            if ("package-stamps".equals(what)) {
                result.notes.add("form '" + newForm.name + "' package stamps changed but its content did not; "
                    + "not rewritten (see ProjectWriter's class doc)");
                return;
            }
            Path containerDir = provisioningDir(driverName).resolve("WorkflowForms").resolve(newForm.kind.container);
            writeFile(containerDir.resolve(newForm.name + "." + formExt(newForm.kind)),
                new String(VaultMapping.formBytes(newForm), StandardCharsets.UTF_8), false);
        }

        /** Designer's item digest for a form ({@code docs/spikes/json-forms-format.md} §4). */
        private String formDigestXml(Form f, String guid, String pkgId, String pkgAssocId, String pkgChecksum) {
            StringBuilder sb = new StringBuilder();
            sb.append("<item cn=\"cn=").append(CObjectXml.esc(f.name)).append("\" filename=\"")
                .append(CObjectXml.esc(f.name)).append('.').append(formExt(f.kind))
                .append("\" hasContainment=\"false\" modstamp=\"0\" protected=\"false\" readonly=\"false\" type=\"")
                .append(f.kind.digestType).append("\" visible=\"true\">");
            sb.append("<guid>").append(CObjectXml.esc(guid)).append("</guid>");
            if (pkgId != null) {
                sb.append("<package-id>").append(CObjectXml.esc(pkgId)).append("</package-id>");
            }
            if (pkgAssocId != null) {
                sb.append("<pkg-assoc-id>").append(CObjectXml.esc(pkgAssocId)).append("</pkg-assoc-id>");
            }
            if (pkgChecksum != null) {
                sb.append("<pkg-checksum>").append(CObjectXml.esc(pkgChecksum)).append("</pkg-checksum>");
            }
            sb.append("</item>");
            return DIGEST_DECL + "\n" + sb + "\n";
        }

        // ---- provisioning: PRDs ----

        void applyPrdAdded(Driver treeDriver, Prd p) throws IOException {
            if (!requireProvisioningDir(treeDriver.name)) {
                return;
            }
            Path reqDefsDir = provisioningDir(treeDriver.name).resolve("RequestDefs");
            Files.createDirectories(reqDefsDir);
            String guid = mintId();
            String pkgId = provPkgMeta(p.meta, "package-id");
            String pkgAssocId = provPkgMeta(p.meta, "pkg-assoc-id");
            String pkgChecksum = provPkgMeta(p.meta, "pkg-checksum");
            writeFile(reqDefsDir.resolve(p.name + ".prd"), prdDocumentXml(p), true);
            writeFile(reqDefsDir.resolve(p.name + ".digest"),
                prdDigestXml(treeDriver, p, guid, pkgId, pkgAssocId, pkgChecksum), true);
            result.mintedIds.put(ModelDiff.prdPath(treeDriver, p), guid);
        }

        void applyPrdRemoved(String driverName, Prd p) throws IOException {
            if (!requireProvisioningDir(driverName)) {
                return;
            }
            Path reqDefsDir = provisioningDir(driverName).resolve("RequestDefs");
            deleteFile(reqDefsDir.resolve(p.name + ".prd"));
            deleteFile(reqDefsDir.resolve(p.name + ".digest"));
        }

        void applyPrdChanged(Driver treeDriver, Prd newPrd, String what) throws IOException {
            if (!requireProvisioningDir(treeDriver.name)) {
                return;
            }
            if ("package-stamps".equals(what)) {
                result.notes.add("PRD '" + newPrd.name + "' package stamps changed but its content did not; "
                    + "not rewritten (see ProjectWriter's class doc)");
                return;
            }
            Path reqDefsDir = provisioningDir(treeDriver.name).resolve("RequestDefs");
            writeFile(reqDefsDir.resolve(newPrd.name + ".prd"), prdDocumentXml(newPrd), false);
        }

        /**
         * The union {@code .prd} document: {@link Prd#definition} (which already carries {@link Prd#process}
         * as a child in the common case) with {@link Prd#request} re-inserted right before {@code <process>}
         * (test11's own layout — {@code docs/spikes/json-forms-format.md} §3), or appended when there is no
         * {@code <process>}. Builds on a fresh, cloned document so the model's shared nodes are never mutated.
         */
        private static String prdDocumentXml(Prd p) {
            if (p.definition == null) {
                throw new IllegalStateException("prd '" + p.name + "' has no definition");
            }
            Document doc = CanonicalXml.parse(CanonicalXml.serialize(p.definition));
            Element root = doc.getDocumentElement();
            if (p.process != null && !isChildOf(p.process, p.definition)) {
                Element existingProcess = firstChildNamed(root, "process");
                Node importedProcess = doc.importNode(p.process, true);
                if (existingProcess != null) {
                    root.replaceChild(importedProcess, existingProcess);
                } else {
                    root.appendChild(importedProcess);
                }
            }
            if (p.request != null) {
                Node importedRequest = doc.importNode(p.request, true);
                Element processChild = firstChildNamed(root, "process");
                if (processChild != null) {
                    root.insertBefore(importedRequest, processChild);
                } else {
                    root.appendChild(importedRequest);
                }
            }
            return CanonicalXml.serialize(root);
        }

        private static boolean isChildOf(Element child, Element parent) {
            if (parent == null || child == null) {
                return false;
            }
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n == child) {
                    return true;
                }
            }
            return false;
        }

        private static Element firstChildNamed(Element parent, String name) {
            for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                    return (Element) n;
                }
            }
            return null;
        }

        /**
         * Designer's item digest for a PRD: localized {@code display}/{@code descr}, the category key,
         * one {@code digest-dependency} per form binding (request first, then approval bindings in document
         * order — {@link Prd#bindings()}), and package elements when packaged
         * ({@code docs/spikes/json-forms-format.md} §4; attribute set copied from test11's PRD digests).
         */
        private String prdDigestXml(Driver treeDriver, Prd p, String guid, String pkgId, String pkgAssocId, String pkgChecksum) {
            StringBuilder sb = new StringBuilder();
            sb.append("<item cn=\"cn=").append(CObjectXml.esc(p.name)).append("\" filename=\"")
                .append(CObjectXml.esc(p.name)).append(".prd")
                .append("\" hasContainment=\"false\" modstamp=\"0\" protected=\"false\" readonly=\"false\" type=\"srvprvRequest\" visible=\"true\">");
            sb.append("<guid>").append(CObjectXml.esc(guid)).append("</guid>");
            for (String[] d : langPairs(p.properties.get("localized-names"))) {
                sb.append("<display xml:lang=\"").append(CObjectXml.esc(d[0])).append("\">")
                    .append(CObjectXml.esc(d[1])).append("</display>");
            }
            for (String[] d : langPairs(p.properties.get("localized-descrs"))) {
                sb.append("<descr xml:lang=\"").append(CObjectXml.esc(d[0])).append("\">")
                    .append(CObjectXml.esc(d[1])).append("</descr>");
            }
            String cat = p.property("category-key");
            if (cat != null && !cat.isBlank()) {
                sb.append("<keys><key>").append(CObjectXml.esc(cat)).append("</key></keys>");
            }
            if (treeDriver.provisioning != null) {
                for (Prd.FormBinding b : p.bindings()) {
                    Form f = treeDriver.provisioning.formByName(b.formId);
                    if (f == null) {
                        result.notes.add("PRD '" + p.name + "' binds form '" + b.formId
                            + "' which does not resolve to a form on driver '" + treeDriver.name
                            + "'; no digest-dependency recorded for it");
                        continue;
                    }
                    sb.append("<digest-dependency digest-managed=\"false\" force-deploy=\"true\" "
                        + "new-digest-dependency=\"false\" object-cn=\"WorkflowForms/").append(CObjectXml.esc(f.name))
                        .append("\" object-mapkey=\"WorkflowForms/").append(CObjectXml.esc(f.kind.container))
                        .append('+').append(CObjectXml.esc(f.name))
                        .append("\" object-type=\"").append(f.kind.digestType)
                        .append("\" type=\"").append(f.kind.digestType).append("\"/>");
                }
            }
            if (pkgId != null) {
                sb.append("<package-id>").append(CObjectXml.esc(pkgId)).append("</package-id>");
            }
            if (pkgAssocId != null) {
                sb.append("<pkg-assoc-id>").append(CObjectXml.esc(pkgAssocId)).append("</pkg-assoc-id>");
            }
            if (pkgChecksum != null) {
                sb.append("<pkg-checksum>").append(CObjectXml.esc(pkgChecksum)).append("</pkg-checksum>");
            }
            sb.append("</item>");
            return DIGEST_DECL + "\n" + sb + "\n";
        }

        // ---- driver settings / config / linkage ----

        void applyDriverSetting(String driverName, String what, Driver treeDriver) {
            String driverId = driverId(driverName);
            if (driverId == null) {
                return;
            }
            String attrName;
            String value;
            switch (what) {
                case "shim-class": attrName = "DirXML-JavaModule"; value = treeDriver.shimClass; break;
                case "shim-auth-server": attrName = "DirXML-ShimAuthServer"; value = treeDriver.shimAuthServer; break;
                case "shim-auth-id": attrName = "DirXML-ShimAuthID"; value = treeDriver.shimAuthId; break;
                default: return;
            }
            setStringAttribute(driverId, attrName, value, "CString");
        }

        void applyDriverConfig(String driverName, String key, Driver treeDriver) throws IOException {
            String driverId = driverId(driverName);
            if (driverId == null) {
                return;
            }
            if (Driver.SHIM_CONFIG_INFO.equals(key)) {
                Element el = treeDriver.config.get(Driver.SHIM_CONFIG_INFO);
                setStringAttribute(driverId, "DirXML-ShimConfigInfo", el == null ? null : CanonicalXml.serialize(el), "CString");
            } else if (Driver.ENGINE_CONTROL_VALUES.equals(key)) {
                Element el = treeDriver.config.get(Driver.ENGINE_CONTROL_VALUES);
                setStringAttribute(driverId, "DirXML-EngineControlValues", el == null ? null : CanonicalXml.serialize(el), "CString");
            } else if (Driver.DRIVER_FILTER.equals(key)) {
                Element el = treeDriver.config.get(Driver.DRIVER_FILTER);
                List<String> filterKeys = relationKeys(parsed(driverId).getDocumentElement(), "Idm:Filter");
                if (filterKeys.isEmpty()) {
                    result.notes.add("driver '" + driverName + "' has no Filter_ object on record; filter change skipped");
                    return;
                }
                String filterId = idOf(filterKeys.get(0));
                Path f = contentsFileById.get(filterId);
                if (f == null) {
                    f = metaFileById.get(filterId).getParent().resolve(filterId + "_contents.xml");
                }
                writeFile(f, CanonicalXml.serialize(el != null ? el : CanonicalXml.parse("<filter/>").getDocumentElement()), false);
            } else if (Driver.CONFIG_VALUES.equals(key)) {
                Element el = treeDriver.config.get(Driver.CONFIG_VALUES);
                List<Path> files = configValueFilesById.get(driverId);
                if (files == null || files.isEmpty()) {
                    if (el != null) {
                        Path dir = metaFileById.get(driverId).getParent();
                        writeFile(dir.resolve(driverId + "_" + serverId + "_DirXML-ConfigValues.xml"),
                            CanonicalXml.serialize(el), true);
                    }
                } else {
                    for (Path f : files) {
                        if (el == null) {
                            deleteFile(f);
                        } else {
                            writeFile(f, CanonicalXml.serialize(el), false);
                        }
                    }
                }
            }
        }

        void applyDriverLinkage(Driver treeDriver, PolicySet set) {
            String ownerId = isDriverLevel(set) ? driverId(treeDriver.name)
                : channelId(treeDriver.name, set.isSubscriber() ? Scope.SUBSCRIBER : Scope.PUBLISHER);
            if (ownerId == null) {
                result.notes.add("driver '" + treeDriver.name + "' has no owner on record for linkage set '"
                    + set.key + "'; skipped");
                return;
            }
            Document doc = parsed(ownerId);
            Element root = doc.getDocumentElement();
            String relName = relationNameForSet(set);
            String relType = set == PolicySet.GCV ? "Child" : "Reference";

            List<Element> matches = new ArrayList<>();
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() == Node.ELEMENT_NODE && "relations".equals(n.getNodeName())) {
                    Element e = (Element) n;
                    if (relName.equals(e.getAttribute("name"))) {
                        matches.add(e);
                    }
                }
            }
            List<String> existingKeys = new ArrayList<>();
            for (Element e : matches) {
                existingKeys.add(e.getAttribute("key"));
            }
            List<String> newKeys = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            for (PolicyLink l : treeDriver.links(set)) {
                String id = idByPath.get(l.ref);
                String type = typeByPath.get(l.ref);
                if (id == null || type == null) {
                    unresolved.add(l.ref);
                    continue;
                }
                newKeys.add("#" + id + "." + type + "_");
            }
            // a rename resolves to the same ids in the same order (relations reference ids, not names) —
            // nothing actually needs rewriting, so leave the file untouched.
            if (existingKeys.equals(newKeys)) {
                return;
            }
            markDirty(ownerId);
            for (String ref : unresolved) {
                result.notes.add("linkage ref '" + ref + "' in driver '" + treeDriver.name + "' set '"
                    + set.key + "' does not resolve to a project artifact; left out of the relation list");
            }
            Node anchor = matches.isEmpty() ? null : matches.get(matches.size() - 1).getNextSibling();
            for (Element e : matches) {
                root.removeChild(e);
            }
            for (String key : newKeys) {
                Element rel = doc.createElement("relations");
                rel.setAttribute("name", relName);
                rel.setAttribute("type", relType);
                rel.setAttribute("key", key);
                if (anchor != null) {
                    root.insertBefore(rel, anchor);
                } else {
                    root.appendChild(rel);
                }
            }
        }

        void applyDriverSetGcvs() throws IOException {
            Element el = treeDs.configValues;
            List<Path> files = dsId == null ? null : configValueFilesById.get(dsId);
            if (files == null || files.isEmpty()) {
                if (el != null && dsId != null) {
                    Path dir = metaFileById.get(dsId).getParent();
                    writeFile(dir.resolve(dsId + "_" + serverId + "_DirXML-ConfigValues.xml"),
                        CanonicalXml.serialize(el), true);
                }
            } else {
                for (Path f : files) {
                    if (el == null) {
                        deleteFile(f);
                    } else {
                        writeFile(f, CanonicalXml.serialize(el), false);
                    }
                }
            }
        }

        // ---- driver add (non-packaged only) ----

        void applyDriverAdd(Driver treeDriver) throws IOException {
            String driverId = mintId();
            Path dsChildrenDir = dsChildrenDir();
            Path driverDir = dsChildrenDir.resolve(driverId);
            Path driverMetaFile = dsChildrenDir.resolve(driverId + ".Driver_");
            String typeAttr = findDriverTypeForShim(treeDriver.shimClass);

            StringBuilder attrs = new StringBuilder();
            if (treeDriver.shimClass != null) {
                attrs.append(CObjectXml.attr("DirXML-JavaModule", treeDriver.shimClass, "CString"));
            }
            if (treeDriver.shimAuthServer != null) {
                attrs.append(CObjectXml.attr("DirXML-ShimAuthServer", treeDriver.shimAuthServer, "CString"));
            }
            if (treeDriver.shimAuthId != null) {
                attrs.append(CObjectXml.attr("DirXML-ShimAuthID", treeDriver.shimAuthId, "CString"));
            }
            Element sci = treeDriver.config.get(Driver.SHIM_CONFIG_INFO);
            if (sci != null) {
                attrs.append(CObjectXml.attr("DirXML-ShimConfigInfo", CanonicalXml.serialize(sci), "CString"));
            }
            Element ecv = treeDriver.config.get(Driver.ENGINE_CONTROL_VALUES);
            if (ecv != null) {
                attrs.append(CObjectXml.attr("DirXML-EngineControlValues", CanonicalXml.serialize(ecv), "CString"));
            }
            attrs.append(CObjectXml.attr("DirXML-DriverStartOption", "1", "CInteger"));

            String metaXml = CObjectXml.cobject(treeDriver.name, typeAttr, attrs.toString(), "");
            Document driverDoc = CanonicalXml.parse(metaXml);
            metaFileById.put(driverId, driverMetaFile);
            typeById.put(driverId, "Driver");
            allDocs.put(driverId, driverDoc);
            newlyCreatedIds.add(driverId);
            dirtyIds.add(driverId);
            driverIdOverride.put(treeDriver.name, driverId);

            String filterId = mintId();
            Path filterMetaFile = driverDir.resolve(filterId + ".Filter_");
            metaFileById.put(filterId, filterMetaFile);
            typeById.put(filterId, "Filter");
            newlyCreatedIds.add(filterId);
            writeFile(filterMetaFile,
                CObjectXml.cobject("Filter", "Filter",
                    "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"contents\"/>", ""),
                true);
            Element filterEl = treeDriver.config.get(Driver.DRIVER_FILTER);
            Path filterContents = driverDir.resolve(filterId + "_contents.xml");
            contentsFileById.put(filterId, filterContents);
            writeFile(filterContents,
                CanonicalXml.serialize(filterEl != null ? filterEl : CanonicalXml.parse("<filter/>").getDocumentElement()),
                true);

            String subId = mintId();
            Path subMetaFile = driverDir.resolve(subId + ".Subscriber_");
            metaFileById.put(subId, subMetaFile);
            typeById.put(subId, "Subscriber");
            newlyCreatedIds.add(subId);
            writeFile(subMetaFile, CObjectXml.cobject("Subscriber", "Subscriber", "", ""), true);
            channelIdCache.put(treeDriver.name + "|" + Scope.SUBSCRIBER, subId);

            String pubId = mintId();
            Path pubMetaFile = driverDir.resolve(pubId + ".Publisher_");
            metaFileById.put(pubId, pubMetaFile);
            typeById.put(pubId, "Publisher");
            newlyCreatedIds.add(pubId);
            writeFile(pubMetaFile, CObjectXml.cobject("Publisher", "Publisher", "", ""), true);
            channelIdCache.put(treeDriver.name + "|" + Scope.PUBLISHER, pubId);

            appendRelation(driverDoc, "Idm:Filter", "Child", filterId, "Filter");
            appendRelation(driverDoc, "Idm:Subscriber", "Child", subId, "Subscriber");
            appendRelation(driverDoc, "Idm:Publisher", "Child", pubId, "Publisher");

            if (dsId != null) {
                Document dsDoc = docFor(dsId);
                appendRelation(dsDoc, "Idm:Drivers", "Child", driverId, "Driver");
            }

            result.notes.add("driver '" + treeDriver.name + "' added; Designer's verdict on new drivers is "
                + "pending (spike 6a) — whether this is enough for Designer to accept the project is not yet known");

            for (Artifact a : treeDriver.artifacts()) {
                applyArtifactAdded(a);
            }
            for (PolicySet set : PolicySet.values()) {
                if (!treeDriver.links(set).isEmpty()) {
                    applyDriverLinkage(treeDriver, set);
                }
            }
        }
    }
}
