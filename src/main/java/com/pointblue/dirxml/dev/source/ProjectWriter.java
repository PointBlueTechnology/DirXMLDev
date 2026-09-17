package com.pointblue.dirxml.dev.source;

import com.pointblue.dirxml.dev.ascode.AsCodeReader;
import com.pointblue.dirxml.dev.deploy.ModelDiff;
import com.pointblue.dirxml.dev.deploy.VaultMapping;
import com.pointblue.dirxml.dev.edit.Packages;
import com.pointblue.dirxml.dev.model.Artifact;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Entitlement;
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
 *   <li>Driver removal, and — on the {@link #update} path only — packaged-driver
 *       creation and a whole new AppConfig; all refuse (the last, only for its own
 *       driver). {@link #create} lifts the last two (see below).</li>
 *   <li>A form/PRD {@code FORM_CHANGED}/{@code PRD_CHANGED} whose only difference
 *       is package stamps (content identical) is noted rather than rewritten —
 *       regenerating the digest from the model would drop
 *       {@code protected}/{@code readonly}/{@code dirguid}/{@code dirrev}, which
 *       this writer never reads back off an existing digest.</li>
 *   <li>A PRD whose Designer digest {@code cn} disagrees with its filename (a
 *       documented but unobserved-in-the-wild Designer quirk) is addressed by
 *       filename, not by the digest's {@code cn}.</li>
 * </ul>
 *
 * <h2>A brand-new project ({@link #create}, milestones N1+N2)</h2>
 * <p>{@code export-project --new} writes a whole Designer project from a tree:
 * {@link ProjectSkeleton} lays out the empty project (descriptors, roots, domain,
 * vault, driver set, library, catalog — N1), then the very same change loop runs
 * against it, where every diff is an "added". Three refusals are lifted on this
 * path only, exactly as {@code docs/designer-new-project.md} §3.2 describes:
 * <ul>
 *   <li><b>a packaged driver</b> — its items are written with the package
 *       attributes Designer reads back ({@code Idm:PackageGuid} /
 *       {@code Idm:PackageAssocGuid} / {@code Idm:ContentChecksum}, derived from
 *       the tree's own stamps by {@link ExportWriter#fromVaultStamps}) and an
 *       {@code <id>_initial_state.xml} baseline. The {@code IdmPackage_} objects
 *       and {@code Idm:InstalledPackages} relations are milestone N3; until then
 *       the result lists the packages the tree names;</li>
 *   <li><b>a driver's {@code Application_}</b> — one per driver in the domain
 *       ({@code NProv} for the User Application driver), with the driver's
 *       {@code Idm:Application} back-reference,
 *       {@code IdmParameter:AppIDCreatedDuringImport} and a modeler node;</li>
 *   <li><b>a missing AppConfig</b> — {@code Model/Provisioning/.provisioning},
 *       {@code AppConfig/.appconfig} from a bundled template and the container
 *       digests, after which forms, PRDs and entitlements go through the existing
 *       provisioning paths.</li>
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
    /** A Designer reference placeholder's id, as it survives into a tree's link refs ({@code library/0}). */
    private static final Pattern STUB_ID = Pattern.compile("[0-9A-Z]{1,8}");
    private static final String APPCONFIG_TEMPLATE = "/designer/appconfig-template.xml";
    /** {@code ProjectReader} records the AppConfig container digest's version here. */
    static final String APPCONFIG_VERSION_META = "project.appconfig-version";
    private static final String DEFAULT_APPCONFIG_VERSION = "4.8";

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

        Ctx ctx = new Ctx(tree, projectDir, project, treeDs, dryRun, result);
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
                    result.refusal = "driver '" + d.name + "' carries package metadata — an existing project needs "
                        + "Designer's IdmPackage installation records, which only Designer produces; deploy it "
                        + "to the vault and use Designer's \"Import from the Identity Vault\", or write a whole new "
                        + "project with --new (which carries the package attributes and baselines); nothing was written";
                    return result;
                }
            }
        }

        ctx.detectRenames(diff);
        applyChanges(ctx, diff, treeDs, project, result);

        ctx.flush();
        result.ok = true;
        return result;
    }

    /**
     * Milestone N1+N2 of {@code docs/designer-new-project.md}: writes a whole new Designer
     * project for {@code tree} into {@code projectDir} (which must not exist, or be empty —
     * its basename becomes the project name). Everything is built in a staging directory
     * first and copied into place at the end, so a {@code dryRun} genuinely writes nothing
     * while still reporting every file it would have produced.
     */
    public static Result create(Path tree, Path projectDir, NewProject opts, boolean dryRun) throws IOException {
        Result result = new Result();
        Path name = projectDir.getFileName();
        if (name == null || name.toString().isBlank()) {
            result.refusal = "cannot tell the project name from '" + projectDir + "' — the directory's basename is "
                + "the project name and is written into .project, <name>.proj and <name>.cproj";
            return result;
        }
        if (Files.exists(projectDir)) {
            if (!Files.isDirectory(projectDir)) {
                result.refusal = "'" + projectDir + "' exists and is not a directory; nothing was written";
                return result;
            }
            List<String> existing = new ArrayList<>();
            try (Stream<Path> s = Files.list(projectDir)) {
                s.limit(5).forEach(p -> existing.add(p.getFileName().toString()));
            }
            if (!existing.isEmpty()) {
                result.refusal = "'" + projectDir + "' is not empty (" + String.join(", ", existing)
                    + "…) — --new never writes into an existing project; point it at a new directory, or drop "
                    + "--new to update the project that is there; nothing was written";
                return result;
            }
        }

        DriverSet treeDs = AsCodeReader.read(tree);
        Path staging = Files.createTempDirectory("idm-new-project");
        try {
            Path work = staging.resolve(name.toString());
            Files.createDirectories(work);
            ProjectSkeleton.Plan plan = ProjectSkeleton.write(work, name.toString(), treeDs, opts, result);

            DriverSet project = ProjectReader.read(work);
            ModelDiff diff = ModelDiff.of(project, treeDs);
            Ctx ctx = new Ctx(tree, work, project, treeDs, false, result);
            ctx.plan = plan;
            ctx.opts = opts;
            ctx.designer = opts.designerRoot != null
                ? DesignerInstall.at(opts.designerRoot, "explicit")
                : DesignerInstall.resolve();
            ctx.scan();
            ctx.usedIds.addAll(plan.usedIds);
            if (ctx.serverId == null) {
                ctx.serverId = plan.serverToken;
            }
            ctx.createDanglingRefStubs();
            applyChanges(ctx, diff, treeDs, project, result);
            ctx.finishNewProject();
            ctx.writeProjectCatalog();
            if (result.refusal != null) {
                // the staging tree is thrown away in the finally block: nothing reached projectDir
                return result;
            }
            ctx.flush();
            ctx.reportNewProjectGaps();

            if (!dryRun) {
                copyTree(work, projectDir);
            }
            result.ok = true;
            return result;
        } finally {
            deleteTree(staging);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.forEach(all::add);
        }
        Collections.reverse(all);
        for (Path p : all) {
            Files.deleteIfExists(p);
        }
    }

    private static void applyChanges(Ctx ctx, ModelDiff diff, DriverSet treeDs, DriverSet project, Result result)
        throws IOException {
        // two passes: linkage last, so a link to an artifact this same run creates (a Library ECMAScript
        // resource a driver's ecmascript set names, on a fresh project) finds its minted id
        for (ModelDiff.Change c : diff.changes()) {
            if (c.kind != ModelDiff.Kind.DRIVER_LINKAGE && c.kind != ModelDiff.Kind.DRIVERSET_LINKAGE) {
                applyChange(ctx, c, treeDs, project, result);
            }
        }
        for (ModelDiff.Change c : diff.changes()) {
            if (c.kind == ModelDiff.Kind.DRIVER_LINKAGE || c.kind == ModelDiff.Kind.DRIVERSET_LINKAGE) {
                applyChange(ctx, c, treeDs, project, result);
            }
        }
        for (Driver d : ctx.deferredLinkage) {
            for (PolicySet set : PolicySet.values()) {
                if (!d.links(set).isEmpty()) {
                    ctx.applyDriverLinkage(d, set);
                }
            }
        }
        ctx.deferredLinkage.clear();
    }

    private static void applyChange(Ctx ctx, ModelDiff.Change c, DriverSet treeDs, DriverSet project, Result result)
        throws IOException {
        {
            if (ctx.consumed.contains(c)) {
                return;
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
                    // a brand-new project has no ambiguity to resolve: the tree's own
                    // driverset.linkage.* meta says which library GCV objects the driver set
                    // owns, and finishNewProject() writes those relations in that order.
                    if (!ctx.creating()) {
                        result.notes.add("driver-set GCV linkage changed ('" + c.what + "') but reordering the driver "
                            + "set's own Idm:GlobalConfigs relations is not implemented (ownership between the driver "
                            + "set and its Library object is ambiguous in the model); left unchanged");
                    }
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
                case ENTITLEMENT_ADDED: {
                    Entitlement e = resolveEntitlementFromPath(treeDs, c.path);
                    Driver td = treeDs.driver(c.driver);
                    if (e != null && td != null) {
                        ctx.applyEntitlementAdded(td, e);
                    }
                    break;
                }
                case ENTITLEMENT_REMOVED: {
                    Entitlement e = resolveEntitlementFromPath(project, c.path);
                    if (e != null) {
                        ctx.applyEntitlementRemoved(c.driver, e);
                    }
                    break;
                }
                case ENTITLEMENT_CHANGED: {
                    Entitlement e = resolveEntitlementFromPath(treeDs, c.path);
                    Driver td = treeDs.driver(c.driver);
                    if (e != null && td != null) {
                        ctx.applyEntitlementChanged(td, e, c.what);
                    }
                    break;
                }
                default:
                    break;
            }
        }
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

    /**
     * The CObject type suffix for an artifact. Derived from its kind, except for a resource
     * whose content type is none of the three Designer models explicitly (an
     * {@code EntitlementConfiguration}, say), which gets the type the tree recorded when it
     * came out of a project and {@code IDMResource} — Designer's generic resource type —
     * otherwise.
     */
    private static String typeSuffixFor(Artifact a) {
        if (a instanceof Policy) {
            switch (((Policy) a).policyKind()) {
                case DIRXML_SCRIPT: return "ScriptPolicy";
                case XSLT: return "StylesheetPolicy";
                case SCHEMA_MAP: return "MappingPolicy";
                default: return recordedType(a);
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
        String recorded = recordedType(a);
        return recorded != null ? recorded : "IDMResource";
    }

    private static String recordedType(Artifact a) {
        String t = a.meta.get("designer.type");
        return (t == null || t.isEmpty()) ? null : t;
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

    /** A fresh Designer id (8 characters from {@code [0-9A-Z]}), added to {@code used} so it is never reused. */
    static String mintId(Set<String> used) {
        while (true) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(ID_ALPHABET[RNG.nextInt(ID_ALPHABET.length)]);
            }
            String id = sb.toString();
            if (used.add(id)) {
                return id;
            }
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

    /** {@code drivers/<d>/entitlements/<name>} -&gt; (d, name). */
    private static ProvPath parseEntitlementPath(String path) {
        String rest = path.substring("drivers/".length());
        int i = rest.indexOf("/entitlements/");
        String driver = rest.substring(0, i);
        return new ProvPath(driver, null, rest.substring(i + "/entitlements/".length()));
    }

    private static Entitlement resolveEntitlementFromPath(DriverSet ds, String path) {
        ProvPath pp = parseEntitlementPath(path);
        Driver d = ds.driver(pp.driver);
        return d == null ? null : d.entitlement(pp.name);
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

    /**
     * A digest item's {@code protected}/{@code readonly} flag, kept from the tree when it
     * records one (a packaged, vendor-protected form must not become editable on the way into
     * a project) and {@code false} otherwise — what a hand-authored item gets.
     */
    private static String flag(Map<String, String> meta, String name) {
        return "true".equals(provPkgMeta(meta, name)) ? "true" : "false";
    }

    /** {@code <dirguid>}/{@code <dirrev>}: the vault identity of an item the tree read out of a project. */
    private static void appendDirStamps(StringBuilder sb, Map<String, String> meta) {
        String dirguid = provPkgMeta(meta, "dirguid");
        String dirrev = provPkgMeta(meta, "dirrev");
        if (dirguid != null) {
            sb.append("<dirguid>").append(CObjectXml.esc(dirguid)).append("</dirguid>");
        }
        if (dirrev != null) {
            sb.append("<dirrev>").append(CObjectXml.esc(dirrev)).append("</dirrev>");
        }
    }

    /**
     * An artifact's package stamp in Designer/export form: the tree's own attribute when it
     * came from a project or an export, otherwise the vault's {@code dirxml-pkg*} stamps
     * mapped through {@link ExportWriter#fromVaultStamps} — the one mapping both writers share.
     */
    static String packageStamp(Map<String, String> meta, String key) {
        String v = meta.get(key);
        return v != null ? v : ExportWriter.fromVaultStamps(meta, key);
    }

    /** {@code edit.Packages#isPackaged}'s test, over any meta map (entitlements aren't {@link Artifact}s). */
    private static boolean isPackagedMeta(Map<String, String> meta) {
        for (String k : meta.keySet()) {
            String lk = k.toLowerCase();
            if (lk.equals("package-id") || lk.equals("pkg-assoc-id") || lk.startsWith("dirxml-pkg")) {
                return true;
            }
        }
        return false;
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
        appendRelationKey(doc, name, type, "#" + id + "." + typeSuffix + "_");
    }

    private static void appendRelationKey(Document doc, String name, String type, String key) {
        Element rel = doc.createElement("relations");
        rel.setAttribute("name", name);
        rel.setAttribute("type", type);
        rel.setAttribute("key", key);
        doc.getDocumentElement().appendChild(rel);
    }

    // ------------------------------------------------------------------
    // Ctx: everything the writer needs to know about the project on disk
    // ------------------------------------------------------------------

    private static final class Ctx {
        final Path treeDir;
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
        /** Entitlements aren't {@link Artifact}s, so they get their own id-by-path map. */
        final Map<String, String> entitlementIdByPath = new LinkedHashMap<>();

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

        // ---- brand-new project only (null on the update path) ----
        /** The skeleton's minted ids; non-null exactly when this is a {@code --new} run. */
        ProjectSkeleton.Plan plan;
        /** {@code --new}'s options (vault/server details, {@code --catalog}, the Designer install). */
        NewProject opts;
        /** Where driver icons come from; {@link DesignerInstall#none()} when there is no install. */
        DesignerInstall designer = DesignerInstall.none();
        /** Set once a driver icon could not be written, so the note is said once, not per driver. */
        private boolean notedMissingIcons;
        /** driver name -&gt; its {@code Application_} id, in the order the drivers were written. */
        final Map<String, String> applicationIdByDriver = new LinkedHashMap<>();
        /** {@code {folder, Application_ id}} per AppConfig, for {@code .provisioning}. */
        final List<String[]> appConfigFolders = new ArrayList<>();
        /** Library GCV objects the driver set (not the Library) owns, in the tree's recorded order. */
        final List<String> driverSetGcvNames = new ArrayList<>();
        /** name -&gt; relation key, filled as those objects are written; emitted in order at the end. */
        final Map<String, String> driverSetGcvKeys = new LinkedHashMap<>();
        /** Every library-scope GCV object's relation key, for the driver set's Idm:ConfigExtensions. */
        final List<String> configExtensionKeys = new ArrayList<>();
        /** Drivers added this run whose linkage is written after every artifact exists. */
        final List<Driver> deferredLinkage = new ArrayList<>();
        Ctx(Path treeDir, Path projectDir, DriverSet project, DriverSet treeDs, boolean dryRun, Result result) {
            this.treeDir = treeDir;
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
            for (Driver d : project.drivers) {
                for (Entitlement ent : d.entitlements) {
                    String id = ent.meta.get("designer.id");
                    if (id != null) {
                        entitlementIdByPath.put(ModelDiff.entitlementPath(d, ent), id);
                    }
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
            if (creating()) {
                driverSetGcvNames.addAll(driverSetOwnedGcvNames(treeDs));
            }
        }

        boolean creating() {
            return plan != null;
        }

        /**
         * The library GCV objects the driver set itself owns, in order — read back out of the
         * tree's {@code driverset.linkage.<n>} meta ({@code cn=<name>,cn=Library,<dsDn>#<order>#14},
         * the shape {@code ProjectReader} and {@code LdifReader} both record).
         */
        private static List<String> driverSetOwnedGcvNames(DriverSet ds) {
            Map<Integer, String> byOrder = new java.util.TreeMap<>();
            for (Map.Entry<String, String> e : ds.meta.entrySet()) {
                if (!e.getKey().startsWith("driverset.linkage.")) {
                    continue;
                }
                String[] parts = e.getValue().split("#");
                if (parts.length < 3 || !String.valueOf(PolicySet.GCV.id).equals(parts[2].trim())) {
                    continue;
                }
                String dn = parts[0];
                if (!dn.startsWith("cn=")) {
                    continue;
                }
                int comma = dn.indexOf(',');
                String name = comma > 0 ? dn.substring(3, comma) : dn.substring(3);
                int order;
                try {
                    order = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException nfe) {
                    order = byOrder.size();
                }
                byOrder.put(order, name);
            }
            return new ArrayList<>(byOrder.values());
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
            return ProjectWriter.mintId(usedIds);
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
                        + "</associatedAttrSets>" + packageAttrsXml(b.meta), "");
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
                attrsXml.append(packageAttrsXml(b.meta));
                String metaXml = CObjectXml.cobject(b.name, typeSuffix, attrsXml.toString(), "");
                writeFile(metaFile, metaXml, true);
                Path contentsFile = dir.resolve(newId + "_contents.xml");
                String content = contentStringFor(b);
                writeFile(contentsFile, content == null ? "" : content, true);
            }
            writeInitialState(b, newId, dir);

            String key = "#" + newId + "." + typeSuffix + "_";
            if (creating() && "GlobalConfig".equals(typeSuffix) && b.scope == Scope.LIBRARY) {
                // A library-scope GCV object is owned either by the Library or by the driver set
                // itself; the tree's driverset.linkage.* meta is the only record of which, and it
                // is ordered — so those relations are emitted together, in order, at the end.
                configExtensionKeys.add(key);
                if (driverSetGcvNames.contains(b.name)) {
                    driverSetGcvKeys.put(b.name, key);
                    return;
                }
            }
            String ownerId = ownerContainerId(b.scope, b.driver);
            Document ownerDoc = docFor(ownerId);
            appendRelation(ownerDoc, childRelationNameFor(b), "Child", newId, typeSuffix);
        }

        /**
         * The package attributes Designer reads back off a packaged item
         * ({@code ProjectReader#copyPackageMeta}), plus the {@code initial_state} heavy-data
         * marker that says an {@code <id>_initial_state.xml} baseline sits beside it. Values
         * come from the tree's own meta, or from its vault stamps through the very same
         * mapping the export writer uses ({@link ExportWriter#fromVaultStamps}), so a project
         * and an export carry identical package associations.
         */
        private String packageAttrsXml(Map<String, String> meta) {
            if (!isPackagedMeta(meta)) {
                return "";
            }
            String pkgId = packageStamp(meta, "package-id");
            String assocId = packageStamp(meta, "pkg-assoc-id");
            String checksum = packageStamp(meta, "checksum");
            String directive = meta.get("directive-checksum");
            StringBuilder sb = new StringBuilder(
                "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"initial_state\"/>");
            if (assocId != null) {
                sb.append(CObjectXml.attr("Idm:PackageAssocGuid", assocId, "CString"));
            }
            if (pkgId != null) {
                sb.append(CObjectXml.attr("Idm:PackageGuid", pkgId, "CString"));
            }
            if (checksum != null) {
                sb.append(CObjectXml.attr("Idm:ContentChecksum", checksum, "CLong"));
            }
            if (directive != null) {
                sb.append(CObjectXml.attr("Idm:DirectiveChecksum", directive, "CLong"));
            }
            return sb.toString();
        }

        /**
         * A packaged item's {@code <id>_initial_state.xml}: the package's own content — which
         * for a customized item is the {@code .package-baseline/} copy the tree kept
         * ({@code edit.Packages}), and for an untouched one is the item's content itself.
         * Designer's "modified" test is exactly this baseline differing from the content.
         */
        private void writeInitialState(Artifact a, String id, Path dir) throws IOException {
            if (!isPackagedMeta(a.meta)) {
                return;
            }
            String content = null;
            if (Packages.isCustomized(a)) {
                content = Packages.baseline(treeDir, a);
                if (content == null) {
                    result.notes.add("packaged artifact '" + a.path() + "' is marked customized but the tree has no "
                        + ".package-baseline copy; its _initial_state.xml was written from the current content, so "
                        + "Designer will not show it as modified");
                }
            }
            if (content == null) {
                content = contentStringFor(a);
            }
            if (content != null) {
                writeFile(dir.resolve(id + "_initial_state.xml"), content, true);
            }
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

        // ---- entitlements (Idm:Entitlements; docs/entitlements.md) ----
        // Same CObject + contents shape as a driver-scope artifact (applyArtifactAdded/Removed/Changed
        // above), owned by the driver's own CObject directly (ownerDir/ownerContainerId with Scope.DRIVER)
        // rather than a Policy/Resource bucket — an entitlement has no scope of its own and keeps the
        // driver's Idm:Entitlements relation in step the same way Idm:Policies/Idm:Resources are kept.

        void applyEntitlementAdded(Driver treeDriver, Entitlement e) throws IOException {
            String newId = mintId();
            Path dir = ownerDir(Scope.DRIVER, treeDriver.name);
            Path metaFile = dir.resolve(newId + ".Entitlement_");
            metaFileById.put(newId, metaFile);
            typeById.put(newId, "Entitlement");
            String path = ModelDiff.entitlementPath(treeDriver, e);
            entitlementIdByPath.put(path, newId);
            result.mintedIds.put(path, newId);

            String attrsXml = "<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"contents\"/>"
                + packageAttrsXml(e.meta);
            String metaXml = CObjectXml.cobject(e.name, "Entitlement", attrsXml, "");
            writeFile(metaFile, metaXml, true);
            Path contentsFile = dir.resolve(newId + "_contents.xml");
            String content = e.definition == null ? "" : CanonicalXml.serialize(e.definition);
            writeFile(contentsFile, content, true);
            if (isPackagedMeta(e.meta)) {
                // an entitlement has no .package-baseline copy of its own (edit.Packages keeps
                // those for artifacts only), so its baseline is its current definition
                writeFile(dir.resolve(newId + "_initial_state.xml"), content, true);
            }

            String ownerId = ownerContainerId(Scope.DRIVER, treeDriver.name);
            Document ownerDoc = docFor(ownerId);
            appendRelation(ownerDoc, "Idm:Entitlements", "Child", newId, "Entitlement");
        }

        void applyEntitlementRemoved(String driverName, Entitlement e) throws IOException {
            String id = e.meta.get("designer.id");
            if (id == null) {
                result.notes.add("cannot remove entitlement '" + driverName + "/" + e.name + "': no designer id on record");
                return;
            }
            deleteFile(metaFileById.get(id));
            deleteFile(contentsFileById.get(id));
            deleteFile(initialStateFileById.get(id));

            String ownerId = ownerContainerId(Scope.DRIVER, driverName);
            String key = "#" + id + ".Entitlement_";
            if (ownerId != null && metaFileById.containsKey(ownerId)) {
                Document ownerDoc = docFor(ownerId);
                removeRelationsWithKey(ownerDoc.getDocumentElement(), key);
            }
            entitlementIdByPath.remove("drivers/" + driverName + "/entitlements/" + e.name);
        }

        void applyEntitlementChanged(Driver treeDriver, Entitlement newE, String what) throws IOException {
            String path = ModelDiff.entitlementPath(treeDriver, newE);
            String id = entitlementIdByPath.get(path);
            if (id == null) {
                result.notes.add("cannot update entitlement '" + path + "': no designer id on record");
                return;
            }
            if ("package-stamps".equals(what)) {
                result.notes.add("entitlement '" + newE.name + "' package stamps changed but its content did not; "
                    + "not rewritten (see ProjectWriter's class doc)");
                return;
            }
            String content = newE.definition == null ? "" : CanonicalXml.serialize(newE.definition);
            Path contentsFile = contentsFileById.get(id);
            boolean created = contentsFile == null;
            if (contentsFile == null) {
                contentsFile = metaFileById.get(id).getParent().resolve(id + "_contents.xml");
            }
            writeFile(contentsFile, content, created);
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
                .append("\" hasContainment=\"false\" modstamp=\"0\" protected=\"")
                .append(flag(f.meta, "protected")).append("\" readonly=\"").append(flag(f.meta, "readonly"))
                .append("\" type=\"").append(f.kind.digestType).append("\" visible=\"true\">");
            sb.append("<guid>").append(CObjectXml.esc(guid)).append("</guid>");
            appendDirStamps(sb, f.meta);
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
                .append("\" hasContainment=\"false\" modstamp=\"0\" protected=\"").append(flag(p.meta, "protected"))
                .append("\" readonly=\"").append(flag(p.meta, "readonly"))
                .append("\" type=\"srvprvRequest\" visible=\"true\">");
            sb.append("<guid>").append(CObjectXml.esc(guid)).append("</guid>");
            appendDirStamps(sb, p.meta);
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
                        // stock template PRDs all bind the same absent "approval_form"; say it once
                        String note = "PRD '" + p.name + "' binds form '" + b.formId
                            + "' which does not resolve to a form on driver '" + treeDriver.name
                            + "'; no digest-dependency recorded for it";
                        if (!result.notes.contains(note)) {
                            result.notes.add(note);
                        }
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
            // the tree records the driver's own Designer type when it came from a project
            // ("NProv Driver 4.8.0", "Active Directory 4.1.0.0", …) — much better than a guess
            String typeAttr = treeDriver.meta.get("designer.driver-type");
            if (typeAttr == null || typeAttr.isEmpty()) {
                typeAttr = findDriverTypeForShim(treeDriver.shimClass);
            }

            String applicationType = ApplicationType.of(treeDriver);

            StringBuilder attrs = new StringBuilder();
            Element configValues = treeDriver.config.get(Driver.CONFIG_VALUES);
            if (configValues != null) {
                attrs.append("<associatedAttrSets objectURI=\"")
                    .append(CObjectXml.esc(attrSetObjectUri == null ? "" : attrSetObjectUri)).append("\">")
                    .append("<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" attrName=\"DirXML-ConfigValues\"/>")
                    .append("</associatedAttrSets>");
            }
            if (writeDriverIcon(applicationType, driverId, dsChildrenDir)) {
                attrs.append("<attributes xsi:type=\"com.novell.designer.model:CHeavyData\" "
                    + "attrName=\"icon\" extension=\"gif\"/>");
            }
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
            String driverVersion = treeDriver.meta.get("version");
            if (driverVersion != null && !driverVersion.isEmpty()) {
                attrs.append(CObjectXml.attr("DirXML-DriverVersion", driverVersion, "CString"));
            }
            String applicationId = creating() ? mintId() : null;
            if (applicationId != null) {
                attrs.append(CObjectXml.attr("IdmParameter:AppIDCreatedDuringImport", applicationId, "CString"));
            }

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

            if (configValues != null) {
                Path valuesFile = dsChildrenDir.resolve(driverId + "_" + serverId + "_DirXML-ConfigValues.xml");
                configValueFilesById.computeIfAbsent(driverId, k -> new ArrayList<>()).add(valuesFile);
                writeFile(valuesFile, CanonicalXml.serialize(configValues), true);
            }

            if (dsId != null) {
                Document dsDoc = docFor(dsId);
                appendRelation(dsDoc, "Idm:Drivers", "Child", driverId, "Driver");
            }

            if (applicationId != null) {
                applyApplicationAdded(treeDriver, driverId, driverDoc, applicationId, applicationType);
            } else {
                result.notes.add("driver '" + treeDriver.name + "' added; Designer's verdict on new drivers is "
                    + "pending (spike 6a) — whether this is enough for Designer to accept the project is not yet known");
            }

            for (Artifact a : treeDriver.artifacts()) {
                applyArtifactAdded(a);
            }
            // linkage last (see applyChanges): a link to a Library artifact this same run creates — an
            // ecmascript set naming NOVLLIBAJC-JS on a fresh project — has no id until the Library is written
            deferredLinkage.add(treeDriver);
            for (Entitlement e : treeDriver.entitlements) {
                applyEntitlementAdded(treeDriver, e);
            }
            if (treeDriver.provisioning != null && applicationId != null) {
                createAppConfig(treeDriver, applicationId);
                for (Form f : treeDriver.provisioning.forms) {
                    applyFormAdded(treeDriver, f);
                }
                for (Prd p : treeDriver.provisioning.prds) {
                    applyPrdAdded(treeDriver, p);
                }
            } else if (treeDriver.provisioning != null) {
                requireProvisioningDir(treeDriver.name);
            }
        }

        // ---- brand-new project: Application_, AppConfig, dangling-ref stubs, finalizing ----

        /**
         * The driver's {@code Application_} in the domain — the object the modeler draws, the
         * one {@code ProjectReader#attachProvisioning} resolves an AppConfig through, and the
         * one Designer's own vault import mints (hence
         * {@code IdmParameter:AppIDCreatedDuringImport} on the driver).
         */
        private void applyApplicationAdded(Driver treeDriver, String driverId, Document driverDoc,
                                           String applicationId, String type) throws IOException {
            Path domainDir = metaFileById.get(plan.domainId).getParent().resolve(plan.domainId);
            Path appFile = domainDir.resolve(applicationId + ".Application_");
            metaFileById.put(applicationId, appFile);
            typeById.put(applicationId, "Application");
            writeFile(appFile, CObjectXml.cobject(treeDriver.name, type,
                CObjectXml.attr("IdmParameter:Modeler.NameInited", "true", "CString"),
                "<relations name=\"Idm:Drivers\" type=\"Reference\" key=\"#" + driverId + ".Driver_\"/>"), true);
            appendRelation(driverDoc, "Idm:Application", "BackReference", applicationId, "Application");
            appendRelation(docFor(plan.domainId), "Idm:DomainItems", "Child", applicationId, "Application");
            applicationIdByDriver.put(treeDriver.name, applicationId);
            result.mintedIds.put("drivers/" + treeDriver.name + " (Application_)", applicationId);
        }

        /**
         * {@code <driverId>_icon.gif} beside the {@code Driver_}, copied out of a Designer
         * install exactly as Designer's own vault importer does
         * ({@code com.novell.core_<ver>} &rarr; {@code icons/iManager/<ApplicationType>.gif}, falling back to
         * {@code GenericApp.gif}) — without it Designer draws no icon for the driver, which is
         * what the first Designer check found. Returns true when one was written, so the
         * caller adds the matching {@code CHeavyData} attribute; says so once when no install
         * was found. An icon is never written into a tree, only into a project.
         */
        private boolean writeDriverIcon(String applicationType, String driverId, Path dsChildrenDir)
            throws IOException {
            if (!creating()) {
                // an existing project already has Designer's icons for the drivers it holds; a
                // driver this run adds gets one the next time Designer draws it
                if (!notedMissingIcons) {
                    notedMissingIcons = true;
                    result.notes.add("the added driver has no <id>_icon.gif; Designer draws its own, or "
                        + "write the project fresh with --new (which copies the icon from a Designer install)");
                }
                return false;
            }
            Path icon = designer.icon(applicationType);
            if (icon == null) {
                if (!notedMissingIcons) {
                    notedMissingIcons = true;
                    result.notes.add("no driver icons were written: " + designer.describe()
                        + " — Designer draws its own icon once it has one, or set IDM_DESIGNER "
                        + "(or -Ddesigner=<installRoot>) and run again");
                }
                return false;
            }
            Path out = dsChildrenDir.resolve(driverId + "_icon.gif");
            Files.createDirectories(out.getParent());
            Files.copy(icon, out);
            result.createdFiles.add(relative(out));
            return true;
        }

        /**
         * A whole {@code Model/Provisioning/<folder>}: the {@code .appconfig} ds-object skeleton
         * from the bundled template and the six container digests
         * {@code ProjectReader#attachProvisioning} and the provisioning writer expect
         * (AppConfig, WorkflowForms + its three form containers, RequestDefs). The AppConfig
         * digest's {@code guid} is the driver's {@code Application_} id — that is the tie the
         * reader follows back to the driver.
         */
        private void createAppConfig(Driver treeDriver, String applicationId) throws IOException {
            String folder = appConfigFolders.isEmpty() ? "AppConfig" : "AppConfig" + appConfigFolders.size();
            Path dir = projectDir.resolve("Model").resolve("Provisioning").resolve(folder);
            String version = treeDriver.provisioning.meta.get(APPCONFIG_VERSION_META);
            if (version == null || version.isBlank()) {
                version = DEFAULT_APPCONFIG_VERSION;
            }
            writeFile(dir.resolve(".appconfig"), appConfigTemplate(version), true);
            // the container's cn is always the vault's (cn=AppConfig); only the folder is numbered
            writeFile(dir.resolve(folder + ".digest"),
                containerDigest("AppConfig", "srvprvAppConfig", applicationId, treeDriver.name, version), true);
            Path formsDir = dir.resolve("WorkflowForms");
            writeFile(formsDir.resolve("WorkflowForms.digest"),
                containerDigest("WorkflowForms", "srvprvJSONForms", mintId(), "Workflow Forms", null), true);
            for (Form.Kind kind : Form.Kind.values()) {
                writeFile(formsDir.resolve(kind.container).resolve(kind.container + ".digest"),
                    containerDigest(kind.container, kind.digestType + "s", mintId(), formsContainerDisplay(kind), null), true);
            }
            writeFile(dir.resolve("RequestDefs").resolve("RequestDefs.digest"),
                containerDigest("RequestDefs", "srvprvRequestDefs", mintId(),
                    "Provisioning Request Definitions", null), true);
            provisioningDirByDriver.put(treeDriver.name, dir);
            appConfigFolders.add(new String[] {folder, applicationId});
        }

        private static String formsContainerDisplay(Form.Kind kind) {
            switch (kind) {
                case REQUEST: return "Request Forms";
                case APPROVAL: return "Approval Forms";
                case TEMPLATE: return "Template Forms";
                default: throw new IllegalStateException("unknown form kind " + kind);
            }
        }

        private static String containerDigest(String cn, String type, String guid, String display, String version) {
            return DIGEST_DECL + "\n<container cn=\"cn=" + CObjectXml.esc(cn) + "\" protected=\"false\" readonly=\"false\""
                + " type=\"" + CObjectXml.esc(type) + "\""
                + (version == null ? "" : " version=\"" + CObjectXml.esc(version) + "\"")
                + " visible=\"true\"><guid>" + CObjectXml.esc(guid) + "</guid>"
                + "<display xml:lang=\"en\">" + CObjectXml.esc(display) + "</display>"
                + "<modstamp>0</modstamp></container>\n";
        }

        private static String appConfigTemplate(String version) throws IOException {
            try (java.io.InputStream in = ProjectWriter.class.getResourceAsStream(APPCONFIG_TEMPLATE)) {
                if (in == null) {
                    throw new IOException("bundled resource " + APPCONFIG_TEMPLATE + " is missing from the jar");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@VERSION@", version);
            }
        }

        /**
         * Designer's own placeholder for a relation target the project doesn't hold — a
         * {@code type="Ref"} CObject whose id is reused project-wide (the {@code
         * 0.ECMAScriptResource_} / {@code 1.ECMAScriptResource_} of {@code test11pf}). A tree
         * read from such a project records those links as {@code library/<id>}, so recreating
         * the stubs is what keeps a driver's {@code Idm:ExtensionFunctions} list intact
         * through the round trip instead of silently losing entries.
         */
        void createDanglingRefStubs() throws IOException {
            Path orphanDir = projectDir.resolve("Model").resolve("EdirOrphan");
            for (Driver d : treeDs.drivers) {
                for (PolicyLink l : d.links) {
                    if (treeDs.resolve(l.ref) != null || idByPath.containsKey(l.ref)) {
                        continue;
                    }
                    int slash = l.ref.lastIndexOf('/');
                    String stubId = slash < 0 ? l.ref : l.ref.substring(slash + 1);
                    if (!STUB_ID.matcher(stubId).matches()) {
                        result.notes.add("linkage ref '" + l.ref + "' in driver '" + d.name + "' set '" + l.set.key
                            + "' resolves to nothing in the tree and does not look like a Designer reference "
                            + "placeholder; left out of the relation list");
                        continue;
                    }
                    String typeSuffix = l.set == PolicySet.ECMASCRIPT ? "ECMAScriptResource"
                        : l.set == PolicySet.GCV ? "GlobalConfig" : "ScriptPolicy";
                    if (!metaFileById.containsKey(stubId)) {
                        Path f = orphanDir.resolve(stubId + "." + typeSuffix + "_");
                        metaFileById.put(stubId, f);
                        typeById.put(stubId, typeSuffix);
                        writeFile(f, CObjectXml.cobject(stubName(l.ref, d), "Ref", "", ""), true);
                    }
                    idByPath.put(l.ref, stubId);
                    typeByPath.put(l.ref, typeById.get(stubId));
                }
            }
        }

        /** The DN Designer would have put on a {@code Ref} stub; the tree no longer records the real one. */
        private String stubName(String ref, Driver owner) {
            String dsDn = (treeDs.dn != null && !treeDs.dn.isEmpty()) ? treeDs.dn : "cn=" + treeDs.name + ",o=system";
            int slash = ref.lastIndexOf('/');
            String leaf = slash < 0 ? ref : ref.substring(slash + 1);
            if (ref.startsWith("library/")) {
                return "cn=" + leaf + ",cn=Library," + dsDn;
            }
            return "cn=" + leaf + ",cn=" + owner.name + "," + dsDn;
        }

        /**
         * The writes that need every {@code Application_} to exist: the driver set's ordered
         * GCV relations, the modeler diagram and {@code Model/Provisioning/.provisioning}.
         */
        void finishNewProject() throws IOException {
            if (dsId != null && (!driverSetGcvKeys.isEmpty() || !configExtensionKeys.isEmpty())) {
                Document dsDoc = docFor(dsId);
                for (String name : driverSetGcvNames) {
                    String key = driverSetGcvKeys.remove(name);
                    if (key != null) {
                        appendRelationKey(dsDoc, "Idm:GlobalConfigs", "Child", key);
                    }
                }
                for (String key : driverSetGcvKeys.values()) {
                    appendRelationKey(dsDoc, "Idm:GlobalConfigs", "Child", key);
                }
                for (String key : configExtensionKeys) {
                    appendRelationKey(dsDoc, "Idm:ConfigExtensions", "Reference", key);
                }
            }
            writeFile(projectDir.resolve("Model").resolve("IdentityManager")
                    .resolve(plan.modelerNodesId + ".ModelerNodes_"),
                ProjectSkeleton.modelerNodes(plan, new ArrayList<>(applicationIdByDriver.values())), true);
            if (!appConfigFolders.isEmpty()) {
                writeFile(projectDir.resolve("Model").resolve("Provisioning").resolve(".provisioning"),
                    ProjectSkeleton.provisioningIndex(appConfigFolders), true);
            }
        }

        /**
         * Milestone N3: the project's own package catalog
         * ({@link ProjectCatalogWriter}) plus the {@code Idm:InstalledPackages} relations from
         * the driver, driver set and vault. Without {@code --catalog} nothing is written and
         * the pre-N3 note names the packages Designer will therefore not associate.
         */
        void writeProjectCatalog() throws IOException {
            Map<String, ProjectCatalogWriter.Need> needs = ProjectCatalogWriter.needs(treeDs);
            if (opts == null || opts.catalogDir == null) {
                if (!needs.isEmpty()) {
                    result.notes.add("no IdmPackage_ objects and no Idm:InstalledPackages relations were written "
                        + "(no --catalog): Designer will show these " + needs.size() + " package(s)' items as plain, "
                        + "and its driver-set Packages page reports \"invalid values\", until the catalog is written "
                        + "or the packages are imported in Designer — "
                        + needs.values().stream().map(ProjectCatalogWriter.Need::describe)
                            .collect(java.util.stream.Collectors.joining(", ")));
                }
                return;
            }
            ProjectCatalogWriter.Result cat =
                ProjectCatalogWriter.write(needs, opts.catalogDir, plan.catalogId, plan.categoryIdByName, host());
            if (cat.refusal != null) {
                result.refusal = cat.refusal;
                return;
            }
            if (cat.packages > 0) {
                result.notes.add("project package catalog: " + cat.packages + " package(s) with " + cat.items
                    + " item(s) written from " + opts.catalogDir
                    + ", with their Idm:InstalledPackages relations");
            }
        }

        /** The project-side callbacks {@link ProjectCatalogWriter} writes through. */
        private ProjectCatalogWriter.Host host() {
            return new ProjectCatalogWriter.Host() {
                @Override
                public String mintId() {
                    return Ctx.this.mintId();
                }

                @Override
                public void write(Path file, String content) throws IOException {
                    writeFile(file, content, true);
                    // a catalog CObject can gain relations after it is written (a category folder
                    // collects its packages) — register it so docFor()/flush() can find it
                    String fn = file.getFileName().toString();
                    int dot = fn.lastIndexOf('.');
                    if (dot > 0 && fn.endsWith("_")) {
                        String id = fn.substring(0, dot);
                        metaFileById.put(id, file);
                        typeById.put(id, fn.substring(dot + 1, fn.length() - 1));
                        newlyCreatedIds.add(id);
                    }
                }

                @Override
                public void writeBytes(Path file, byte[] content) throws IOException {
                    if (!dryRun) {
                        Files.createDirectories(file.getParent());
                        Files.write(file, content);
                    }
                    result.createdFiles.add(relative(file));
                }

                @Override
                public void note(String note) {
                    result.notes.add(note);
                }

                @Override
                public Path childrenDir(String id) {
                    return metaFileById.get(id).getParent().resolve(id);
                }

                @Override
                public void addRelation(String ownerId, String relationName, String type, String targetId,
                                        String targetType) {
                    appendRelation(docFor(ownerId), relationName, type, targetId, targetType);
                    markDirty(ownerId);
                }

                @Override
                public List<String[]> installTargets(int packageType, Set<String> driverNames) {
                    List<String[]> out = new ArrayList<>();
                    if (packageType == 3) {
                        if (dsId != null) {
                            out.add(new String[] {dsId, "DriverSet", "Idm:InstalledPackageDSetRefs"});
                        }
                    } else if (packageType == 4) {
                        out.add(new String[] {plan.vaultId, "IdentityVault", "Idm:InstalledPackageIVRefs"});
                    } else {
                        for (String name : driverNames) {
                            String id = driverId(name);
                            if (id != null) {
                                out.add(new String[] {id, "Driver", "Idm:InstalledPackageDriverRefs"});
                            }
                        }
                    }
                    return out;
                }
            };
        }

        /**
         * What a brand-new project cannot carry because the tree never recorded it. The driver
         * set is the one that matters: {@code test11pf}'s {@code DriverSet_} also holds
         * {@code DSetCreatePartition}, {@code DirXML-LogEvents}, {@code DirXML-JavaDebugPort},
         * {@code DirXML-JavaTraceFile}, {@code DirXML-LogLimit}, {@code DirXML-TraceSizeLimit},
         * {@code DirXML-XSLTraceLevel}, {@code JavaEnvParameters} and {@code NamedPasswords},
         * and a tree's {@code driverset.xml} records only its config values (checked on
         * {@code tree-test11pf}, {@code tree-7c}, {@code tree-ig4} and {@code tree-idm254}:
         * none of them carries a single driver-set setting). They are left absent rather than
         * invented — Designer fills its own defaults.
         */
        void reportNewProjectGaps() {
            result.notes.add("the driver set was written with the name, DSetContext and config values the tree "
                + "records; DSetCreatePartition, DirXML-LogEvents, DirXML-JavaDebugPort, DirXML-JavaTraceFile, "
                + "DirXML-LogLimit, DirXML-TraceSizeLimit, DirXML-XSLTraceLevel, JavaEnvParameters and "
                + "NamedPasswords are left absent because no tree records them (set them in Designer)");
        }
    }
}
