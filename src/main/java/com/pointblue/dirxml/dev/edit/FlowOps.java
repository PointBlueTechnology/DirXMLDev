package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.flow.Flow;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.dev.xml.CanonicalXml;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typed workflow operations on a PRD's {@code <process>} (Track W step W2; see
 * {@code docs/workflows.md} &sect;3 option B and &sect;4 W2). Every op resolves its PRD via
 * {@link FormOps#findPrd}, refuses ({@link Operation.Refusal}) for an unknown PRD, a PRD with no
 * {@code <process>}, an unknown/duplicate/invalid activity id, or a value outside its {@link Flow}
 * enum, then mutates the process DOM directly (the DOM stays the store, as in W1) and finishes with
 * {@link FormOps#customizePrd}, {@link #syncDefinition}, {@code tx.touched}, and one {@code tx.note}.
 *
 * <p>Element/attribute defaults for the approval, log and provision shapes are copied from three
 * stock template PRDs read at {@code DirXMLDev-e2e/tree-idm254/.../provisioning/prds/}
 * (never copied into this repo — see the class constants, each commented with its source template):
 * {@code TemplateSingleApproval_TD} (the {@code user-activity}'s {@code timeout}/{@code ontimeout},
 * its default {@code addressee}, its {@code notify} template/maps and its {@code retry}, and the
 * {@code log-activity}'s {@code author}), {@code NoApproval} (the {@code provision-activity}'s five
 * data items) and {@code HelpdeskTicket} (process-level attribute shapes referenced from
 * {@code flow.set}'s documentation only — no defaults are copied from it here).
 *
 * <p>Two deliberate departures from a literal stock copy, both because the source material has no
 * example to copy from: a {@code notification-activity}'s {@code notify} carries a smaller map set
 * than an approval's (dropping the two maps that call {@code <id>.getAddressee()} — a method a
 * plain notification's info object does not have), and a {@code condition-activity}/
 * {@code notification-activity}/{@code log-activity} shape is built from the engine grammar
 * (&sect;1.1) directly since none of the three source templates contains one.
 */
public final class FlowOps {

    private FlowOps() {
    }

    // ---- id validation ------------------------------------------------------------------------

    /** {@code activity-id} shape the engine accepts (docs/workflows.md &sect;1.1). */
    public static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");

    private static final Set<String> ADD_KINDS = Set.of(
        "approval", "condition", "log", "notification", "mapping", "provision");

    // ---- stock defaults (see class doc for source templates) ----------------------------------

    /** {@code user-activity/@timeout}, from {@code TemplateSingleApproval_TD}'s "approval" activity (8 days, ms). */
    public static final long APPROVAL_DEFAULT_TIMEOUT_MS = 691200000L;
    /** {@code user-activity/@ontimeout} default, from {@code TemplateSingleApproval_TD}. */
    public static final String APPROVAL_DEFAULT_ONTIMEOUT = "denied";
    /** {@code addressee} default, from {@code TemplateSingleApproval_TD}. */
    public static final String APPROVAL_DEFAULT_ADDRESSEE = "IDVault.get(recipient,'user','manager')";
    /** {@code notify/@template} on the approval activity, from {@code TemplateSingleApproval_TD}. */
    public static final String APPROVAL_NOTIFY_TEMPLATE = "cn=Provisioning Notification,cn=Default Notification Collection,cn=Security";
    /** {@code retry/@attempts}, from {@code TemplateSingleApproval_TD}. */
    public static final String RETRY_ATTEMPTS = "3";
    /** {@code retry/@interval} (2 days, ms), from {@code TemplateSingleApproval_TD}. */
    public static final String RETRY_INTERVAL_MS = "172800000";
    /** {@code log-activity/author} text, from {@code TemplateSingleApproval_TD}'s "log_approval" activity. */
    public static final String LOG_DEFAULT_AUTHOR = "initiator";

    /** The insertion index and reason a new activity's default outgoing link(s) target the finish activity when the operation gave none. */
    private static String isBlankNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    // =============================================================================================
    // shared plumbing
    // =============================================================================================

    /**
     * A PRD's {@link #process} may be a different DOM node than {@code definition}'s own
     * {@code <process>} child (see {@link Prd} class doc — a {@code process.xml} was read
     * separately). Every op must call this once, after mutating {@link Prd#process}, so the
     * {@code definition.xml}/{@code XmlData} copy the writer emits from {@code prd.definition}
     * stays byte-for-byte in step with the edited process.
     */
    static void syncDefinition(Prd prd) {
        if (prd.definition == null || prd.process == null) {
            return;
        }
        List<Element> procs = Xds.childrenByName(prd.definition, "process");
        Element defChild = procs.isEmpty() ? null : procs.get(0);
        if (defChild == prd.process) {
            return;
        }
        Element clone = cloneElement(prd.process);
        if (defChild != null) {
            prd.definition.replaceChild(clone, defChild);
        } else {
            prd.definition.appendChild(clone);
        }
    }

    private static Element cloneElement(Element e) {
        return CanonicalXml.parse(CanonicalXml.serialize(e)).getDocumentElement();
    }

    private static String prdNotFoundMessage(String ref, String driver) {
        return "prd '" + ref + "' not found" + (driver == null ? " (or found on several drivers — say --driver)" : " on driver '" + driver + "'");
    }

    /** Resolves {@code prdRef}, refusing when absent or the PRD has no process. */
    private static FormOps.FoundPrd resolveFlowPrd(DriverSet ds, String prdRef, String driver) throws Operation.Refusal {
        FormOps.FoundPrd found = FormOps.findPrd(ds, prdRef, driver);
        if (found == null) {
            throw new Operation.Refusal(prdNotFoundMessage(prdRef, driver));
        }
        if (found.prd.process == null) {
            throw new Operation.Refusal("prd '" + prdRef + "' has no <process> (classic PRD with no workflow)");
        }
        return found;
    }

    static void checkNewId(Flow flow, String id) throws Operation.Refusal {
        if (id == null || id.isBlank()) {
            throw new Operation.Refusal("--id is required");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new Operation.Refusal("--id '" + id + "' is not a valid activity id (must match [A-Za-z_][A-Za-z0-9_-]*)");
        }
        if (flow.byId(id) != null) {
            throw new Operation.Refusal("activity id '" + id + "' already exists in this process");
        }
    }

    static Flow.Activity requireActivity(Flow flow, String id, String label) throws Operation.Refusal {
        if (id == null || id.isBlank()) {
            throw new Operation.Refusal("--" + label + " is required");
        }
        Flow.Activity a = flow.byId(id);
        if (a == null) {
            throw new Operation.Refusal("activity '" + id + "' not found in this process");
        }
        return a;
    }

    private static void checkEnum(String value, Set<String> allowed, String label) throws Operation.Refusal {
        if (value != null && !allowed.contains(value)) {
            throw new Operation.Refusal("--" + label + " '" + value + "' is not one of " + allowed);
        }
    }

    // ---- element-order helpers (docs/workflows.md &sect;1.1) -------------------------------------

    /** Inserts a new activity element before {@code finish-activity} (activities come before it, links after). */
    public static void insertActivity(Element process, Element activity) {
        List<Element> finishes = Xds.childrenByName(process, "finish-activity");
        if (!finishes.isEmpty()) {
            process.insertBefore(activity, finishes.get(0));
        } else {
            process.appendChild(activity);
        }
    }

    /** Appends a new {@code <link>} after the last existing one (or at the end, if none). */
    public static void addLink(Element process, Element link) {
        List<Element> links = Xds.childrenByName(process, "link");
        if (links.isEmpty()) {
            process.appendChild(link);
            return;
        }
        Node after = links.get(links.size() - 1).getNextSibling();
        if (after != null) {
            process.insertBefore(link, after);
        } else {
            process.appendChild(link);
        }
    }

    /** Inserts a {@code <data-items>} (or other pre-start) block right before {@code start-activity}. */
    public static void insertBeforeStart(Element process, Element element) {
        List<Element> starts = Xds.childrenByName(process, "start-activity");
        if (!starts.isEmpty()) {
            process.insertBefore(element, starts.get(0));
        } else {
            process.appendChild(element);
        }
    }

    public static Element createLink(Document doc, String source, String target, String type) {
        Element e = doc.createElementNS(null, "link");
        e.setAttribute("source", source);
        e.setAttribute("target", target);
        e.setAttribute("type", type);
        return e;
    }

    /** The {@code <link>} DOM element matching this {@link Flow.Link} read view (first match, document order). */
    static Element findLinkElement(Element process, Flow.Link link) {
        for (Element e : Xds.childrenByName(process, "link")) {
            if (eq(e.getAttribute("source"), link.source) && eq(e.getAttribute("target"), link.target)
                && eq(e.getAttribute("type"), link.type)) {
                return e;
            }
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- display names --------------------------------------------------------------------------

    /** {@code lang~Text}, or plain text (= {@code en}). */
    static String[] parseName(String name) {
        int t = name.indexOf('~');
        if (t > 0) {
            return new String[] {name.substring(0, t), name.substring(t + 1)};
        }
        return new String[] {"en", name};
    }

    static Element createDisplayName(Document doc, String lang, String text) {
        Element dn = doc.createElementNS(null, "display-name");
        dn.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:lang", lang);
        dn.appendChild(doc.createTextNode(text));
        return dn;
    }

    /** Every created activity gets an {@code en} display-name (default a sensible label); {@code --name} in another language adds a second one. */
    static void addDisplayName(Document doc, Element activity, String nameArg, String defaultLabel) {
        String enText = defaultLabel;
        String[] extra = null;
        if (nameArg != null && !nameArg.isBlank()) {
            String[] parsed = parseName(nameArg);
            if ("en".equals(parsed[0])) {
                enText = parsed[1];
            } else {
                extra = parsed;
            }
        }
        activity.appendChild(createDisplayName(doc, "en", enText));
        if (extra != null) {
            activity.appendChild(createDisplayName(doc, extra[0], extra[1]));
        }
    }

    static String defaultLabel(String kind, String id) {
        String noun = switch (kind) {
            case "approval" -> "Approval";
            case "condition" -> "Condition";
            case "log" -> "Log";
            case "notification" -> "Notification";
            case "mapping" -> "Mapping";
            case "provision" -> "Provision";
            case "branch" -> "Branch";
            case "merge" -> "Merge";
            default -> kind;
        };
        return noun + " " + id;
    }

    // ---- ECMAScript literal helper ---------------------------------------------------------------

    /** Wraps a DN/value as a single-quoted ECMAScript string literal, escaping {@code \} and {@code '}. */
    public static String quoteLiteral(String value) {
        String escaped = value.replace("\\", "\\\\").replace("'", "\\'");
        return "'" + escaped + "'";
    }

    // ---- notify/retry shapes (see class doc for the source template) ----------------------------

    static Element createNotify(Document doc, String template, List<String[]> maps) {
        Element notify = doc.createElementNS(null, "notify");
        notify.setAttribute("template", template);
        for (String[] m : maps) {
            Element map = doc.createElementNS(null, "map");
            map.setAttribute("source", m[0]);
            map.setAttribute("target", m[1]);
            notify.appendChild(map);
        }
        return notify;
    }

    /** The approval activity's own {@code notify} maps, from {@code TemplateSingleApproval_TD} ({@code id} substituted for its own info-object name). */
    static List<String[]> approvalNotifyMaps(String id) {
        return List.of(
            new String[] {"_default_", "TO"},
            new String[] {"process.getName(java.util.Locale.getDefault())", "requestTitle"},
            new String[] {"IDVault.get(" + id + ".getAddressee(), 'user', 'FirstName')", "userFirstName"},
            new String[] {"IDVault.get(initiator, 'user', 'FirstName') + ' ' + IDVault.get(initiator, 'user', 'LastName')", "initiatorFullName"},
            new String[] {"IDVault.get(recipient, 'user', 'FirstName') + ' ' + IDVault.get(recipient, 'user', 'LastName')", "recipientFullName"});
    }

    /**
     * A notification activity's {@code notify} maps: the same shape minus the two maps that call
     * {@code <id>.getAddressee()} — a plain {@code notification-activity} has no such info-object
     * method (only a {@code user-activity}'s does). Not copied from a stock example (see class doc).
     */
    static List<String[]> notificationNotifyMaps() {
        return List.of(
            new String[] {"_default_", "TO"},
            new String[] {"process.getName(java.util.Locale.getDefault())", "requestTitle"},
            new String[] {"IDVault.get(initiator, 'user', 'FirstName') + ' ' + IDVault.get(initiator, 'user', 'LastName')", "initiatorFullName"},
            new String[] {"IDVault.get(recipient, 'user', 'FirstName') + ' ' + IDVault.get(recipient, 'user', 'LastName')", "recipientFullName"});
    }

    static Element createRetry(Document doc, String activityId) {
        Element retry = doc.createElementNS(null, "retry");
        retry.setAttribute("attempts", RETRY_ATTEMPTS);
        retry.setAttribute("interval", RETRY_INTERVAL_MS);
        Element addressee = doc.createElementNS(null, "addressee");
        addressee.appendChild(doc.createTextNode("IDVault.get(" + activityId + ".getAddressee(),'user','manager')"));
        retry.appendChild(addressee);
        return retry;
    }

    static Element createDataItem(Document doc, String name, String dataType, String source, String target, String targetType) {
        Element di = doc.createElementNS(null, "data-item");
        di.setAttribute("name", name);
        if (dataType != null) {
            di.setAttribute("data-type", dataType);
        }
        if (source != null) {
            di.setAttribute("source", source);
        }
        if (target != null) {
            di.setAttribute("target", target);
        }
        if (targetType != null) {
            di.setAttribute("target-type", targetType);
        }
        return di;
    }

    /** The {@code <data-items activity-id>} block for this activity (first match), or null. */
    static Element findDataItems(Element process, String activityId) {
        for (Element e : Xds.childrenByName(process, "data-items")) {
            if (activityId.equals(e.getAttribute("activity-id"))) {
                return e;
            }
        }
        return null;
    }

    // =============================================================================================
    // flow.activity.add
    // =============================================================================================

    public static final class ActivityAdd implements Operation {
        private final String driver;
        private final String prdRef;
        private final String kind;
        private final String id;
        private final String after;
        private final String via;
        private final String to;
        private final String onDenied;
        private final String onFalse;
        private final String nameArg;
        private final String addressee;
        private final String timeout;
        private final String ontimeout;
        private final String expression;
        private final String message;
        private final String template;
        private final String entitlementDn;
        private final String entitlementParam;

        public ActivityAdd(String driver, String prdRef, String kind, String id, String after, String via, String to,
                            String onDenied, String onFalse, String nameArg, String addressee, String timeout,
                            String ontimeout, String expression, String message, String template,
                            String entitlementDn, String entitlementParam) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.kind = kind;
            this.id = id;
            this.after = isBlankNull(after);
            this.via = isBlankNull(via);
            this.to = isBlankNull(to);
            this.onDenied = isBlankNull(onDenied);
            this.onFalse = isBlankNull(onFalse);
            this.nameArg = isBlankNull(nameArg);
            this.addressee = isBlankNull(addressee);
            this.timeout = isBlankNull(timeout);
            this.ontimeout = isBlankNull(ontimeout);
            this.expression = expression;
            this.message = isBlankNull(message);
            this.template = isBlankNull(template);
            this.entitlementDn = isBlankNull(entitlementDn);
            this.entitlementParam = entitlementParam;
        }

        @Override
        public String name() {
            return "flow.activity.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            checkNewId(flow, id);
            String k = requireAddKind(kind);
            if (after == null) {
                throw new Operation.Refusal("--after is required");
            }
            Flow.Activity afterAct = requireActivity(flow, after, "after");
            validateKindArgs(k);

            Flow.Activity finish = flow.finish();
            String finishId = finish == null ? null : finish.id;
            boolean branchCase = afterAct.kind == Flow.Kind.BRANCH;

            String primaryTarget;
            if (branchCase) {
                if (to == null) {
                    throw new Operation.Refusal("--to is required when --after names a branch activity");
                }
                Flow.Activity toAct = flow.byId(to);
                if (toAct == null) {
                    throw new Operation.Refusal("--to activity '" + to + "' not found");
                }
                if (!reachableOrMerge(flow, afterAct, toAct)) {
                    throw new Operation.Refusal("--to '" + to + "' must be branch '" + after + "'s merge activity, or reachable from it");
                }
                primaryTarget = to;
            } else {
                Flow.Link consumed = pickOutgoingLink(flow, afterAct, via);
                primaryTarget = consumed.target;
            }
            String denyTarget = k.equals("approval") ? firstNonNull(onDenied, finishId) : null;
            String falseTarget = k.equals("condition") ? firstNonNull(onFalse, finishId) : null;
            if (k.equals("approval")) {
                if (denyTarget == null) {
                    throw new Operation.Refusal("no finish activity to default --on-denied to; give --on-denied");
                }
                if (flow.byId(denyTarget) == null) {
                    throw new Operation.Refusal("--on-denied activity '" + denyTarget + "' not found");
                }
            }
            if (k.equals("condition")) {
                if (falseTarget == null) {
                    throw new Operation.Refusal("no finish activity to default --on-false to; give --on-false");
                }
                if (flow.byId(falseTarget) == null) {
                    throw new Operation.Refusal("--on-false activity '" + falseTarget + "' not found");
                }
            }

            String before = FormOps.prdFingerprint(prd);
            Document doc = prd.process.getOwnerDocument();
            Element newEl = createActivityElement(doc, k, id);
            addDisplayName(doc, newEl, nameArg, defaultLabel(k, id));
            insertActivity(prd.process, newEl);

            if (branchCase) {
                addLink(prd.process, createLink(doc, after, id, "forward"));
            } else {
                Flow.Link consumed = pickOutgoingLink(flow, afterAct, via);
                Element linkEl = findLinkElement(prd.process, consumed);
                if (linkEl != null) {
                    linkEl.setAttribute("target", id);
                }
            }
            addDefaultOutgoing(doc, prd.process, k, id, primaryTarget, denyTarget, falseTarget);
            addDataItemsForKind(doc, prd.process, k, id, entitlementDn, entitlementParam);

            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': added " + k + " activity '" + id + "' after '" + after + "'");
        }

        private void validateKindArgs(String k) throws Operation.Refusal {
            switch (k) {
                case "condition":
                    if (isBlankNull(expression) == null) {
                        throw new Operation.Refusal("--expression is required for a condition activity");
                    }
                    break;
                case "notification":
                    if (template == null) {
                        throw new Operation.Refusal("--template is required for a notification activity");
                    }
                    break;
                case "provision":
                    if (entitlementDn == null) {
                        throw new Operation.Refusal("--entitlement-dn is required for a provision activity");
                    }
                    break;
                default:
                    break;
            }
            if (ontimeout != null) {
                checkEnum(ontimeout, Flow.ONTIMEOUTS, "ontimeout");
            }
            if (timeout != null) {
                try {
                    Long.parseLong(timeout);
                } catch (NumberFormatException e) {
                    throw new Operation.Refusal("--timeout must be a number of milliseconds");
                }
            }
        }

        private Element createActivityElement(Document doc, String k, String activityId) {
            switch (k) {
                case "approval": {
                    Element e = doc.createElementNS(null, "user-activity");
                    e.setAttribute("activity-id", activityId);
                    e.setAttribute("timeout", timeout != null ? timeout : String.valueOf(APPROVAL_DEFAULT_TIMEOUT_MS));
                    e.setAttribute("ontimeout", ontimeout != null ? ontimeout : APPROVAL_DEFAULT_ONTIMEOUT);
                    Element ae = doc.createElementNS(null, "addressee");
                    ae.appendChild(doc.createTextNode(addressee != null ? addressee : APPROVAL_DEFAULT_ADDRESSEE));
                    e.appendChild(ae);
                    e.appendChild(createNotify(doc, APPROVAL_NOTIFY_TEMPLATE, approvalNotifyMaps(activityId)));
                    e.appendChild(createRetry(doc, activityId));
                    return e;
                }
                case "condition": {
                    Element e = doc.createElementNS(null, "condition-activity");
                    e.setAttribute("activity-id", activityId);
                    Element expr = doc.createElementNS(null, "expression");
                    expr.appendChild(doc.createTextNode(expression));
                    e.appendChild(expr);
                    return e;
                }
                case "log": {
                    Element e = doc.createElementNS(null, "log-activity");
                    e.setAttribute("activity-id", activityId);
                    e.setAttribute("audit", "false");
                    Element author = doc.createElementNS(null, "author");
                    author.appendChild(doc.createTextNode(LOG_DEFAULT_AUTHOR));
                    e.appendChild(author);
                    Element msg = doc.createElementNS(null, "message");
                    msg.appendChild(doc.createTextNode(message != null ? message : "'Activity " + activityId + "'"));
                    e.appendChild(msg);
                    return e;
                }
                case "notification": {
                    Element e = doc.createElementNS(null, "notification-activity");
                    e.setAttribute("activity-id", activityId);
                    e.appendChild(createNotify(doc, template, notificationNotifyMaps()));
                    return e;
                }
                case "mapping": {
                    Element e = doc.createElementNS(null, "mapping-activity");
                    e.setAttribute("activity-id", activityId);
                    return e;
                }
                case "provision": {
                    Element e = doc.createElementNS(null, "provision-activity");
                    e.setAttribute("activity-id", activityId);
                    e.setAttribute("category", "entitlement");
                    e.setAttribute("entity-type", "sys-entitlement-request");
                    e.setAttribute("operation", "grant");
                    return e;
                }
                default:
                    throw new IllegalStateException("unreachable kind " + k);
            }
        }
    }

    private static String requireAddKind(String kind) throws Operation.Refusal {
        if (kind == null || kind.isBlank()) {
            throw new Operation.Refusal("--kind is required");
        }
        if ("branch".equals(kind)) {
            throw new Operation.Refusal("--kind branch is not added with flow.activity.add; use flow.branch.add");
        }
        if (!ADD_KINDS.contains(kind)) {
            throw new Operation.Refusal("--kind must be one of " + ADD_KINDS + " (or branch, via flow.branch.add)");
        }
        return kind;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Flow.Link pickOutgoingLink(Flow flow, Flow.Activity act, String via) throws Operation.Refusal {
        List<Flow.Link> out = flow.outgoing(act.id);
        if (via != null) {
            for (Flow.Link l : out) {
                if (via.equals(l.type)) {
                    return l;
                }
            }
            throw new Operation.Refusal("activity '" + act.id + "' has no outgoing link of type '" + via + "'; has: " + typesOf(out));
        }
        if (out.size() == 1) {
            return out.get(0);
        }
        if (out.isEmpty()) {
            throw new Operation.Refusal("activity '" + act.id + "' has no outgoing link to insert after");
        }
        throw new Operation.Refusal("activity '" + act.id + "' has " + out.size() + " outgoing links (" + typesOf(out) + "); say --via");
    }

    private static String typesOf(List<Flow.Link> links) {
        List<String> types = new ArrayList<>();
        for (Flow.Link l : links) {
            types.add(l.type + "->" + l.target);
        }
        return types.toString();
    }

    private static boolean reachableOrMerge(Flow flow, Flow.Activity branch, Flow.Activity to) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind == Flow.Kind.MERGE && branch.id.equals(a.attr("branch-activity-id")) && a.id.equals(to.id)) {
                return true;
            }
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(branch.id);
        queue.add(branch.id);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Flow.Link l : flow.outgoing(cur)) {
                if (l.target == null) {
                    continue;
                }
                if (l.target.equals(to.id)) {
                    return true;
                }
                if (visited.add(l.target)) {
                    queue.add(l.target);
                }
            }
        }
        return false;
    }

    private static void addDefaultOutgoing(Document doc, Element process, String k, String id,
                                            String primaryTarget, String denyTarget, String falseTarget) {
        switch (k) {
            case "approval":
                addLink(process, createLink(doc, id, primaryTarget, "approved"));
                addLink(process, createLink(doc, id, denyTarget, "denied"));
                break;
            case "condition":
                addLink(process, createLink(doc, id, primaryTarget, "true"));
                addLink(process, createLink(doc, id, falseTarget, "false"));
                break;
            case "log":
            case "mapping":
            case "provision":
            case "notification":
            case "merge":
                addLink(process, createLink(doc, id, primaryTarget, "forward"));
                break;
            default:
                throw new IllegalStateException("unreachable kind " + k);
        }
    }

    /** {@code DirXML-Entitlement-DN}/{@code -Parameter}/{@code -Action}/{@code -MultiValueAllowed} data-type/source, from {@code NoApproval}'s "prov" activity. */
    private static void addDataItemsForKind(Document doc, Element process, String k, String id, String entitlementDn, String entitlementParam) {
        switch (k) {
            case "approval":
            case "mapping": {
                Element holder = doc.createElementNS(null, "data-items");
                holder.setAttribute("activity-id", id);
                insertBeforeStart(process, holder);
                break;
            }
            case "provision": {
                Element holder = doc.createElementNS(null, "data-items");
                holder.setAttribute("activity-id", id);
                holder.appendChild(createDataItem(doc, "dn", "string", "recipient", null, null));
                holder.appendChild(createDataItem(doc, "DirXML-Entitlement-DN", "string", quoteLiteral(entitlementDn), null, null));
                holder.appendChild(createDataItem(doc, "DirXML-Entitlement-Action", "string", "'1'", null, null));
                String param = entitlementParam != null ? entitlementParam : "";
                holder.appendChild(createDataItem(doc, "DirXML-Entitlement-Parameter", "string", quoteLiteral(param), null, null));
                holder.appendChild(createDataItem(doc, "DirXML-Entitlement-MultiValueAllowed", "boolean", "'true'", null, null));
                insertBeforeStart(process, holder);
                break;
            }
            default:
                break;
        }
    }

    // =============================================================================================
    // flow.activity.set
    // =============================================================================================

    public static final class ActivitySet implements Operation {
        private final String driver;
        private final String prdRef;
        private final String id;
        private final String nameArg;
        private final List<String> attrs;
        private final String addressee;
        private final String timeout;
        private final String ontimeout;
        private final String expression;
        private final String message;
        private final String template;
        private final String entitlementDn;
        private final String entitlementParam;
        private final String approverType;

        public ActivitySet(String driver, String prdRef, String id, String nameArg, List<String> attrs,
                            String addressee, String timeout, String ontimeout, String expression, String message,
                            String template, String entitlementDn, String entitlementParam, String approverType) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.id = id;
            this.nameArg = isBlankNull(nameArg);
            this.attrs = attrs;
            this.addressee = addressee;
            this.timeout = isBlankNull(timeout);
            this.ontimeout = isBlankNull(ontimeout);
            this.expression = expression;
            this.message = message;
            this.template = isBlankNull(template);
            this.entitlementDn = isBlankNull(entitlementDn);
            this.entitlementParam = entitlementParam;
            this.approverType = isBlankNull(approverType);
        }

        @Override
        public String name() {
            return "flow.activity.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            Flow.Activity act = requireActivity(flow, id, "id");

            boolean any = nameArg != null || (attrs != null && !attrs.isEmpty()) || addressee != null
                || timeout != null || ontimeout != null || expression != null || message != null
                || template != null || entitlementDn != null || entitlementParam != null || approverType != null;
            if (!any) {
                throw new Operation.Refusal("give at least one of --name, --attr, --addressee, --timeout, --ontimeout, "
                    + "--expression, --message, --template, --entitlement-dn, --entitlement-param, --approver-type");
            }
            if ((entitlementDn != null || entitlementParam != null) && act.kind != Flow.Kind.PROVISION) {
                throw new Operation.Refusal("--entitlement-dn/--entitlement-param only apply to a provision activity ('" + id + "' is a " + elementName(act) + ")");
            }
            if (ontimeout != null) {
                checkEnum(ontimeout, Flow.ONTIMEOUTS, "ontimeout");
            }
            if (approverType != null) {
                checkEnum(approverType, Flow.APPROVER_TYPES, "approver-type");
            }
            if (attrs != null) {
                for (String pair : attrs) {
                    if (pair.isBlank()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        throw new Operation.Refusal("--attr must be name=value: '" + pair + "'");
                    }
                    String attrName = pair.substring(0, eq);
                    if (attrName.equals("activity-id")) {
                        throw new Operation.Refusal("--attr activity-id is refused; use flow.activity.rename");
                    }
                    checkKnownEnumAttr(attrName, pair.substring(eq + 1));
                }
            }

            String before = FormOps.prdFingerprint(prd);
            Document doc = prd.process.getOwnerDocument();
            Element el = act.element;
            boolean changed = false;

            if (nameArg != null) {
                for (Element dn : new ArrayList<>(act.displayNameElements())) {
                    el.removeChild(dn);
                }
                addDisplayName(doc, el, nameArg, defaultLabel(kindKeyOf(act), id));
                changed = true;
            }
            if (attrs != null) {
                for (String pair : attrs) {
                    if (pair.isBlank()) {
                        continue;
                    }
                    int eq = pair.indexOf('=');
                    el.setAttribute(pair.substring(0, eq), pair.substring(eq + 1));
                    changed = true;
                }
            }
            if (addressee != null) {
                for (Element ae : Xds.childrenByName(el, "addressee")) {
                    el.removeChild(ae);
                }
                for (String line : addressee.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    Element ae = doc.createElementNS(null, "addressee");
                    ae.appendChild(doc.createTextNode(line));
                    el.appendChild(ae);
                }
                changed = true;
            }
            if (timeout != null) {
                el.setAttribute("timeout", timeout);
                changed = true;
            }
            if (ontimeout != null) {
                el.setAttribute("ontimeout", ontimeout);
                changed = true;
            }
            if (approverType != null) {
                el.setAttribute("approver-type", approverType);
                changed = true;
            }
            if (expression != null) {
                setOrCreateTextChild(doc, el, "expression", expression);
                changed = true;
            }
            if (message != null) {
                setOrCreateTextChild(doc, el, "message", message);
                changed = true;
            }
            if (template != null) {
                List<Element> notifies = Xds.childrenByName(el, "notify");
                Element notify = notifies.isEmpty() ? null : notifies.get(0);
                if (notify == null) {
                    notify = doc.createElementNS(null, "notify");
                    el.appendChild(notify);
                }
                notify.setAttribute("template", template);
                changed = true;
            }
            if (entitlementDn != null) {
                setDataItemSource(el, "DirXML-Entitlement-DN", quoteLiteral(entitlementDn));
                changed = true;
            }
            if (entitlementParam != null) {
                setDataItemSource(el, "DirXML-Entitlement-Parameter", quoteLiteral(entitlementParam));
                changed = true;
            }

            if (changed) {
                FormOps.customizePrd(tx, found.driver, prd, before);
                syncDefinition(prd);
                tx.touched(FormOps.prdPath(found.driver, prd));
                tx.note("prd '" + prdRef + "': updated activity '" + id + "'");
            } else {
                tx.note("prd '" + prdRef + "': activity '" + id + "': no change");
            }
        }

        private void checkKnownEnumAttr(String attrName, String value) throws Operation.Refusal {
            switch (attrName) {
                case "ontimeout":
                    checkEnum(value, Flow.ONTIMEOUTS, "attr ontimeout");
                    break;
                case "approver-type":
                    checkEnum(value, Flow.APPROVER_TYPES, "attr approver-type");
                    break;
                case "category":
                    checkEnum(value, Flow.PROVISION_CATEGORIES, "attr category");
                    break;
                case "operation":
                    checkEnum(value, Flow.OPERATIONS, "attr operation");
                    break;
                case "digital-signature-type":
                    checkEnum(value, Flow.DIGITAL_SIGNATURE_TYPES, "attr digital-signature-type");
                    break;
                case "action":
                    checkEnum(value, Flow.BIND_ACTIONS, "attr action");
                    break;
                default:
                    break;
            }
        }

        private void setDataItemSource(Element activity, String dataItemName, String source) {
            String id = activity.getAttribute("activity-id");
            Element holder = findDataItems(activity.getOwnerDocument().getDocumentElement(), id);
            if (holder == null) {
                return;
            }
            for (Element di : Xds.childrenByName(holder, "data-item")) {
                if (dataItemName.equals(di.getAttribute("name"))) {
                    di.setAttribute("source", source);
                    return;
                }
            }
        }
    }

    private static void setOrCreateTextChild(Document doc, Element parent, String childName, String text) {
        List<Element> kids = Xds.childrenByName(parent, childName);
        Element target;
        if (!kids.isEmpty()) {
            target = kids.get(0);
            while (target.getFirstChild() != null) {
                target.removeChild(target.getFirstChild());
            }
        } else {
            target = doc.createElementNS(null, childName);
            parent.appendChild(target);
        }
        target.appendChild(doc.createTextNode(text));
    }

    private static String elementName(Flow.Activity a) {
        String ln = a.element.getLocalName();
        return ln != null ? ln : a.element.getNodeName();
    }

    private static String kindKeyOf(Flow.Activity a) {
        switch (a.kind) {
            case USER: return "approval";
            case CONDITION: return "condition";
            case LOG: return "log";
            case NOTIFICATION: return "notification";
            case MAPPING: return "mapping";
            case PROVISION: return "provision";
            case BRANCH: return "branch";
            case MERGE: return "merge";
            default: return a.id;
        }
    }

    // =============================================================================================
    // flow.activity.rename
    // =============================================================================================

    public static final class ActivityRename implements Operation {
        private final String driver;
        private final String prdRef;
        private final String id;
        private final String to;

        public ActivityRename(String driver, String prdRef, String id, String to) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.id = id;
            this.to = to;
        }

        @Override
        public String name() {
            return "flow.activity.rename";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            if (to == null || to.isBlank()) {
                throw new Operation.Refusal("--to is required");
            }
            if (!ID_PATTERN.matcher(to).matches()) {
                throw new Operation.Refusal("--to '" + to + "' is not a valid activity id (must match [A-Za-z_][A-Za-z0-9_-]*)");
            }
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            Flow.Activity act = requireActivity(flow, id, "id");
            if (to.equals(id)) {
                tx.note("prd '" + prdRef + "': activity '" + id + "': name unchanged");
                return;
            }
            if (flow.byId(to) != null) {
                throw new Operation.Refusal("activity id '" + to + "' already exists in this process");
            }

            String before = FormOps.prdFingerprint(prd);
            List<String> rewritten = new ArrayList<>();

            act.element.setAttribute("activity-id", to);
            rewritten.add("the activity element");

            int links = 0;
            for (Element l : Xds.childrenByName(prd.process, "link")) {
                boolean hit = false;
                if (id.equals(l.getAttribute("source"))) {
                    l.setAttribute("source", to);
                    hit = true;
                }
                if (id.equals(l.getAttribute("target"))) {
                    l.setAttribute("target", to);
                    hit = true;
                }
                if (hit) {
                    links++;
                }
            }
            if (links > 0) {
                rewritten.add(links + " link(s)");
            }

            int dataItems = 0;
            for (Element di : Xds.childrenByName(prd.process, "data-items")) {
                if (id.equals(di.getAttribute("activity-id"))) {
                    di.setAttribute("activity-id", to);
                    dataItems++;
                }
            }
            if (dataItems > 0) {
                rewritten.add(dataItems + " data-items block(s)");
            }

            int bindings = 0;
            for (Element fb : Xds.descendantsByName(prd.process, "form-binding")) {
                if (id.equals(fb.getAttribute("activity-id"))) {
                    fb.setAttribute("activity-id", to);
                    bindings++;
                }
            }
            if (bindings > 0) {
                rewritten.add(bindings + " form-binding(s)");
            }

            int[] exprHits = {0};
            rewriteIdentifierReferences(prd.process, id, to, exprHits);
            if (prd.request != null) {
                rewriteIdentifierReferences(prd.request, id, to, exprHits);
            }
            if (exprHits[0] > 0) {
                rewritten.add(exprHits[0] + " expression reference(s)");
            }

            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': renamed activity '" + id + "' -> '" + to + "' (" + String.join(", ", rewritten) + ")");
        }
    }

    /** Rewrites {@code <id>.} (an info-object reference) and {@code '<id>/} (a flowdata path prefix) in every attribute value and text node under {@code root}. */
    private static void rewriteIdentifierReferences(Element root, String oldId, String newId, int[] hits) {
        Pattern dotRef = Pattern.compile("\\b" + Pattern.quote(oldId) + "\\.");
        Pattern pathRef = Pattern.compile("'" + Pattern.quote(oldId) + "/");
        rewriteAttrsAndText(root, dotRef, newId + ".", hits);
        rewriteAttrsAndText(root, pathRef, "'" + newId + "/", hits);
    }

    private static void rewriteAttrsAndText(Element e, Pattern p, String replacement, int[] hits) {
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Attr a = (Attr) attrs.item(i);
            String v = a.getValue();
            Matcher m = p.matcher(v);
            if (m.find()) {
                a.setValue(m.replaceAll(Matcher.quoteReplacement(replacement)));
                hits[0]++;
            }
        }
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE) {
                String v = n.getNodeValue();
                Matcher m = p.matcher(v);
                if (m.find()) {
                    n.setNodeValue(m.replaceAll(Matcher.quoteReplacement(replacement)));
                    hits[0]++;
                }
            }
        }
        for (Element c : Xds.childElements(e)) {
            rewriteAttrsAndText(c, p, replacement, hits);
        }
    }

    // =============================================================================================
    // flow.activity.remove
    // =============================================================================================

    private static final Set<String> PRIMARY_LINK_TYPES = Set.of("forward", "approved", "true", "success");

    public static final class ActivityRemove implements Operation {
        private final String driver;
        private final String prdRef;
        private final String id;

        public ActivityRemove(String driver, String prdRef, String id) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.id = id;
        }

        @Override
        public String name() {
            return "flow.activity.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            Flow.Activity act = requireActivity(flow, id, "id");
            if (act.kind == Flow.Kind.START || act.kind == Flow.Kind.FINISH) {
                throw new Operation.Refusal("activity '" + id + "' is the " + (act.kind == Flow.Kind.START ? "start" : "finish")
                    + " activity and cannot be removed");
            }
            if (act.kind == Flow.Kind.BRANCH || act.kind == Flow.Kind.MERGE) {
                throw new Operation.Refusal("activity '" + id + "' is a " + elementName(act) + "; use flow.branch.remove");
            }
            Flow.Link primary = null;
            for (Flow.Link l : flow.outgoing(id)) {
                if (PRIMARY_LINK_TYPES.contains(l.type)) {
                    primary = l;
                    break;
                }
            }
            if (primary == null) {
                throw new Operation.Refusal("activity '" + id + "' has no forward/approved/true/success outgoing link to reconnect through");
            }
            String successor = primary.target;

            String before = FormOps.prdFingerprint(prd);
            int reconnected = 0;
            for (Element l : Xds.childrenByName(prd.process, "link")) {
                if (id.equals(l.getAttribute("target"))) {
                    l.setAttribute("target", successor);
                    reconnected++;
                }
            }
            int dropped = 0;
            for (Element l : new ArrayList<>(Xds.childrenByName(prd.process, "link"))) {
                if (id.equals(l.getAttribute("source"))) {
                    prd.process.removeChild(l);
                    dropped++;
                }
            }
            boolean droppedDataItems = false;
            Element di = findDataItems(prd.process, id);
            if (di != null) {
                prd.process.removeChild(di);
                droppedDataItems = true;
            }
            int droppedBindings = 0;
            for (Element fb : new ArrayList<>(Xds.descendantsByName(prd.process, "form-binding"))) {
                if (id.equals(fb.getAttribute("activity-id"))) {
                    fb.getParentNode().removeChild(fb);
                    droppedBindings++;
                }
            }
            prd.process.removeChild(act.element);

            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': removed activity '" + id + "'; reconnected " + reconnected
                + " incoming link(s) to '" + successor + "'; dropped " + dropped + " outgoing link(s)"
                + (droppedDataItems ? ", its data-items block" : "") + (droppedBindings > 0 ? ", " + droppedBindings + " form-binding(s)" : ""));
        }
    }

    // =============================================================================================
    // flow.branch.add / flow.branch.remove
    // =============================================================================================

    public static final class BranchAdd implements Operation {
        private final String driver;
        private final String prdRef;
        private final String id;
        private final String merge;
        private final String after;
        private final String via;

        public BranchAdd(String driver, String prdRef, String id, String merge, String after, String via) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.id = id;
            this.merge = merge;
            this.after = isBlankNull(after);
            this.via = isBlankNull(via);
        }

        @Override
        public String name() {
            return "flow.branch.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            checkNewId(flow, id);
            if (merge == null || merge.isBlank()) {
                throw new Operation.Refusal("--merge is required");
            }
            if (!ID_PATTERN.matcher(merge).matches()) {
                throw new Operation.Refusal("--merge '" + merge + "' is not a valid activity id");
            }
            if (flow.byId(merge) != null) {
                throw new Operation.Refusal("activity id '" + merge + "' already exists in this process");
            }
            if (after == null) {
                throw new Operation.Refusal("--after is required");
            }
            Flow.Activity afterAct = requireActivity(flow, after, "after");
            Flow.Link consumed = pickOutgoingLink(flow, afterAct, via);
            String z0 = consumed.target;

            String before = FormOps.prdFingerprint(prd);
            Document doc = prd.process.getOwnerDocument();

            Element branchEl = doc.createElementNS(null, "branch-activity");
            branchEl.setAttribute("activity-id", id);
            addDisplayName(doc, branchEl, null, defaultLabel("branch", id));
            insertActivity(prd.process, branchEl);

            Element mergeEl = doc.createElementNS(null, "merge-activity");
            mergeEl.setAttribute("activity-id", merge);
            mergeEl.setAttribute("branch-activity-id", id);
            addDisplayName(doc, mergeEl, null, defaultLabel("merge", merge));
            insertActivity(prd.process, mergeEl);

            Element linkEl = findLinkElement(prd.process, consumed);
            if (linkEl != null) {
                linkEl.setAttribute("target", id);
            }
            addLink(prd.process, createLink(doc, id, merge, "forward"));
            addLink(prd.process, createLink(doc, merge, z0, "forward"));

            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': added branch '" + id + "'/merge '" + merge + "' after '" + after
                + "' (add legs with flow.activity.add --after " + id + " --to " + merge + ")");
        }
    }

    public static final class BranchRemove implements Operation {
        private final String driver;
        private final String prdRef;
        private final String id;

        public BranchRemove(String driver, String prdRef, String id) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.id = id;
        }

        @Override
        public String name() {
            return "flow.branch.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            Flow.Activity branch = requireActivity(flow, id, "id");
            if (branch.kind != Flow.Kind.BRANCH) {
                throw new Operation.Refusal("activity '" + id + "' is not a branch activity");
            }
            Flow.Activity merge = null;
            for (Flow.Activity a : flow.activities) {
                if (a.kind == Flow.Kind.MERGE && id.equals(a.attr("branch-activity-id"))) {
                    merge = a;
                    break;
                }
            }
            if (merge == null) {
                throw new Operation.Refusal("no merge activity references branch '" + id + "'");
            }
            List<Flow.Link> branchOut = flow.outgoing(id);
            if (branchOut.size() != 1) {
                throw new Operation.Refusal("branch '" + id + "' has " + branchOut.size()
                    + " legs; flow.branch.remove only handles a single leg — remove the extra legs first");
            }
            List<String> chain = new ArrayList<>();
            String cur = branchOut.get(0).target;
            Set<String> seen = new LinkedHashSet<>();
            while (!cur.equals(merge.id)) {
                if (!seen.add(cur)) {
                    throw new Operation.Refusal("branch '" + id + "' leg forms a cycle before reaching merge '" + merge.id + "'");
                }
                List<Flow.Link> out = flow.outgoing(cur);
                if (out.size() != 1) {
                    throw new Operation.Refusal("branch '" + id + "' leg is not a simple chain to the merge (activity '" + cur
                        + "' has " + out.size() + " outgoing link(s)); simplify it first");
                }
                chain.add(cur);
                cur = out.get(0).target;
            }
            List<Flow.Link> mergeOut = flow.outgoing(merge.id);
            if (mergeOut.size() != 1) {
                throw new Operation.Refusal("merge '" + merge.id + "' has " + mergeOut.size()
                    + " outgoing link(s); flow.branch.remove only handles a single successor");
            }
            String zOut = mergeOut.get(0).target;
            String firstOfChain = chain.isEmpty() ? zOut : chain.get(0);

            String before = FormOps.prdFingerprint(prd);
            for (Element l : Xds.childrenByName(prd.process, "link")) {
                if (id.equals(l.getAttribute("target"))) {
                    l.setAttribute("target", firstOfChain);
                }
            }
            for (Element l : new ArrayList<>(Xds.childrenByName(prd.process, "link"))) {
                String src = l.getAttribute("source");
                String tgt = l.getAttribute("target");
                if (id.equals(src) || merge.id.equals(src) || merge.id.equals(tgt) && chain.isEmpty()) {
                    prd.process.removeChild(l);
                } else if (merge.id.equals(tgt) && !chain.isEmpty()) {
                    // the last chain activity's link into merge: retarget instead of dropping
                    l.setAttribute("target", zOut);
                }
            }
            prd.process.removeChild(branch.element);
            prd.process.removeChild(merge.element);

            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': removed branch '" + id + "'/merge '" + merge.id + "'"
                + (chain.isEmpty() ? " (no activities between them)" : " (reconnected around " + chain + ")"));
        }
    }

    // =============================================================================================
    // flow.link.add / remove / retype
    // =============================================================================================

    private static void checkLinkTypeAllowed(Flow flow, String fromId, String type) throws Operation.Refusal {
        if (!Flow.LINK_TYPES.contains(type)) {
            throw new Operation.Refusal("--type '" + type + "' is not one of " + Flow.LINK_TYPES);
        }
        Flow.Activity from = flow.byId(fromId);
        if (from != null && from.kind.restrictsOutgoingLinkTypes() && !from.kind.allowedOutgoingLinkTypes().contains(type)) {
            throw new Operation.Refusal("a " + from.kind.element + " may only emit " + from.kind.allowedOutgoingLinkTypes());
        }
    }

    public static final class LinkAdd implements Operation {
        private final String driver;
        private final String prdRef;
        private final String from;
        private final String to;
        private final String type;

        public LinkAdd(String driver, String prdRef, String from, String to, String type) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.from = from;
            this.to = to;
            this.type = type;
        }

        @Override
        public String name() {
            return "flow.link.add";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            requireActivity(flow, from, "from");
            requireActivity(flow, to, "to");
            if (type == null || type.isBlank()) {
                throw new Operation.Refusal("--type is required");
            }
            checkLinkTypeAllowed(flow, from, type);
            for (Flow.Link l : flow.outgoing(from)) {
                if (to.equals(l.target) && type.equals(l.type)) {
                    tx.note("prd '" + prdRef + "': link " + from + "--" + type + "-->" + to + " already exists; no change");
                    return;
                }
            }
            String before = FormOps.prdFingerprint(prd);
            addLink(prd.process, createLink(prd.process.getOwnerDocument(), from, to, type));
            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': added link " + from + "--" + type + "-->" + to);
        }
    }

    public static final class LinkRemove implements Operation {
        private final String driver;
        private final String prdRef;
        private final String from;
        private final String to;
        private final String type;

        public LinkRemove(String driver, String prdRef, String from, String to, String type) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.from = from;
            this.to = to;
            this.type = isBlankNull(type);
        }

        @Override
        public String name() {
            return "flow.link.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            List<Element> toRemove = new ArrayList<>();
            for (Element l : Xds.childrenByName(prd.process, "link")) {
                if (from.equals(l.getAttribute("source")) && to.equals(l.getAttribute("target"))
                    && (type == null || type.equals(l.getAttribute("type")))) {
                    toRemove.add(l);
                }
            }
            if (toRemove.isEmpty()) {
                throw new Operation.Refusal("no link " + from + "->" + to + (type == null ? "" : " of type '" + type + "'") + " found");
            }
            String before = FormOps.prdFingerprint(prd);
            for (Element l : toRemove) {
                prd.process.removeChild(l);
            }
            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': removed " + toRemove.size() + " link(s) " + from + "->" + to);
        }
    }

    public static final class LinkRetype implements Operation {
        private final String driver;
        private final String prdRef;
        private final String from;
        private final String to;
        private final String type;
        private final String toType;

        public LinkRetype(String driver, String prdRef, String from, String to, String type, String toType) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.from = from;
            this.to = to;
            this.type = type;
            this.toType = toType;
        }

        @Override
        public String name() {
            return "flow.link.retype";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            if (toType == null || toType.isBlank()) {
                throw new Operation.Refusal("--to-type is required");
            }
            checkLinkTypeAllowed(flow, from, toType);
            Element linkEl = null;
            for (Element l : Xds.childrenByName(prd.process, "link")) {
                if (from.equals(l.getAttribute("source")) && to.equals(l.getAttribute("target")) && type.equals(l.getAttribute("type"))) {
                    linkEl = l;
                    break;
                }
            }
            if (linkEl == null) {
                throw new Operation.Refusal("no link " + from + "--" + type + "-->" + to + " found");
            }
            if (toType.equals(type)) {
                tx.note("prd '" + prdRef + "': link " + from + "->" + to + ": type unchanged");
                return;
            }
            String before = FormOps.prdFingerprint(prd);
            linkEl.setAttribute("type", toType);
            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': retyped link " + from + "->" + to + ": " + type + " -> " + toType);
        }
    }

    // =============================================================================================
    // flow.data.set / flow.data.remove
    // =============================================================================================

    public static final class DataSet implements Operation {
        private final String driver;
        private final String prdRef;
        private final String activity;
        private final String dataName;
        private final String source;
        private final String target;
        private final String type;
        private final String targetType;

        public DataSet(String driver, String prdRef, String activity, String dataName, String source, String target,
                        String type, String targetType) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.activity = activity;
            this.dataName = dataName;
            this.source = isBlankNull(source);
            this.target = isBlankNull(target);
            this.type = isBlankNull(type);
            this.targetType = isBlankNull(targetType);
        }

        @Override
        public String name() {
            return "flow.data.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Flow flow = Flow.of(prd);
            requireActivity(flow, activity, "activity");
            if (dataName == null || dataName.isBlank()) {
                throw new Operation.Refusal("--name is required");
            }
            if ((source == null) == (target == null)) {
                throw new Operation.Refusal("give exactly one of --source or --target");
            }
            if (type != null) {
                checkEnum(type, Flow.DATA_TYPES, "type");
            }
            if (targetType != null) {
                checkEnum(targetType, Flow.TARGET_TYPES, "target-type");
            }

            String before = FormOps.prdFingerprint(prd);
            Document doc = prd.process.getOwnerDocument();
            Element holder = findDataItems(prd.process, activity);
            if (holder == null) {
                holder = doc.createElementNS(null, "data-items");
                holder.setAttribute("activity-id", activity);
                insertBeforeStart(prd.process, holder);
            }
            Element item = null;
            for (Element di : Xds.childrenByName(holder, "data-item")) {
                if (dataName.equals(di.getAttribute("name"))) {
                    item = di;
                    break;
                }
            }
            String dataType = type != null ? type : "string";
            if (item == null) {
                item = createDataItem(doc, dataName, dataType, source, target, targetType);
                holder.appendChild(item);
            } else {
                item.setAttribute("data-type", dataType);
                if (source != null) {
                    item.setAttribute("source", source);
                    item.removeAttribute("target");
                }
                if (target != null) {
                    item.setAttribute("target", target);
                    item.removeAttribute("source");
                }
                if (targetType != null) {
                    item.setAttribute("target-type", targetType);
                }
            }
            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': set data item '" + dataName + "' on activity '" + activity + "'");
        }
    }

    public static final class DataRemove implements Operation {
        private final String driver;
        private final String prdRef;
        private final String activity;
        private final String dataName;

        public DataRemove(String driver, String prdRef, String activity, String dataName) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.activity = activity;
            this.dataName = dataName;
        }

        @Override
        public String name() {
            return "flow.data.remove";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            Element holder = findDataItems(prd.process, activity);
            Element item = null;
            if (holder != null) {
                for (Element di : Xds.childrenByName(holder, "data-item")) {
                    if (dataName.equals(di.getAttribute("name"))) {
                        item = di;
                        break;
                    }
                }
            }
            if (item == null) {
                throw new Operation.Refusal("data item '" + dataName + "' not found on activity '" + activity + "' in prd '" + prdRef + "'");
            }
            String before = FormOps.prdFingerprint(prd);
            holder.removeChild(item);
            if (Xds.childrenByName(holder, "data-item").isEmpty()) {
                prd.process.removeChild(holder);
            }
            FormOps.customizePrd(tx, found.driver, prd, before);
            syncDefinition(prd);
            tx.touched(FormOps.prdPath(found.driver, prd));
            tx.note("prd '" + prdRef + "': removed data item '" + dataName + "' from activity '" + activity + "'");
        }
    }

    // =============================================================================================
    // flow.set
    // =============================================================================================

    public static final class SetProcess implements Operation {
        private final String driver;
        private final String prdRef;
        private final String version;
        private final String processType;
        private final String flowStrategy;
        private final String defaultCompletedApprovalStatus;
        private final String setnotify;
        private final String restrictView;
        private final String generateComments;

        public SetProcess(String driver, String prdRef, String version, String processType, String flowStrategy,
                           String defaultCompletedApprovalStatus, String setnotify, String restrictView,
                           String generateComments) {
            this.driver = driver;
            this.prdRef = prdRef;
            this.version = isBlankNull(version);
            this.processType = isBlankNull(processType);
            this.flowStrategy = isBlankNull(flowStrategy);
            this.defaultCompletedApprovalStatus = isBlankNull(defaultCompletedApprovalStatus);
            this.setnotify = isBlankNull(setnotify);
            this.restrictView = isBlankNull(restrictView);
            this.generateComments = isBlankNull(generateComments);
        }

        @Override
        public String name() {
            return "flow.set";
        }

        @Override
        public void apply(DriverSet ds, Transaction tx) throws Operation.Refusal, IOException {
            FormOps.FoundPrd found = resolveFlowPrd(ds, prdRef, driver);
            Prd prd = found.prd;
            boolean any = version != null || processType != null || flowStrategy != null
                || defaultCompletedApprovalStatus != null || setnotify != null || restrictView != null || generateComments != null;
            if (!any) {
                throw new Operation.Refusal("give at least one of --version, --process-type, --flow-strategy, "
                    + "--default-completed-approval-status, --setnotify, --restrict-view, --generate-comments");
            }
            if (version != null) {
                checkEnum(version, new LinkedHashSet<>(Flow.PROCESS_VERSIONS), "version");
            }
            if (processType != null) {
                checkEnum(processType, Flow.PROCESS_TYPES, "process-type");
            }
            if (flowStrategy != null) {
                checkEnum(flowStrategy, Flow.FLOW_STRATEGIES, "flow-strategy");
            }
            if (defaultCompletedApprovalStatus != null) {
                checkEnum(defaultCompletedApprovalStatus, Flow.DEFAULT_COMPLETED_APPROVAL_STATUSES, "default-completed-approval-status");
            }
            checkBool(setnotify, "setnotify");
            checkBool(restrictView, "restrict-view");
            checkBool(generateComments, "generate-comments");

            String before = FormOps.prdFingerprint(prd);
            Element p = prd.process;
            boolean changed = false;
            changed |= setIfChanged(p, "version", version);
            changed |= setIfChanged(p, "process-type", processType);
            changed |= setIfChanged(p, "flow-strategy", flowStrategy);
            changed |= setIfChanged(p, "default-completed-approval-status", defaultCompletedApprovalStatus);
            changed |= setIfChanged(p, "setnotify", setnotify);
            changed |= setIfChanged(p, "restrict-view", restrictView);
            changed |= setIfChanged(p, "generate-comments", generateComments);

            if (changed) {
                FormOps.customizePrd(tx, found.driver, prd, before);
                syncDefinition(prd);
                tx.touched(FormOps.prdPath(found.driver, prd));
                tx.note("prd '" + prdRef + "': updated process attributes");
            } else {
                tx.note("prd '" + prdRef + "': no change");
            }
        }

        private static void checkBool(String v, String label) throws Operation.Refusal {
            if (v != null && !v.equals("true") && !v.equals("false")) {
                throw new Operation.Refusal("--" + label + " must be true or false");
            }
        }

        private static boolean setIfChanged(Element process, String attr, String value) {
            if (value == null) {
                return false;
            }
            if (value.equals(process.getAttribute(attr))) {
                return false;
            }
            process.setAttribute(attr, value);
            return true;
        }
    }
}
