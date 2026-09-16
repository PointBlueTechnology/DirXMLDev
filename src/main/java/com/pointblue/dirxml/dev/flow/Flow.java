package com.pointblue.dirxml.dev.flow;

import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.sim.Xds;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A typed read view over a PRD's {@code <process>} DOM (Track W step W1;
 * {@code docs/workflows.md} &sect;1.1/&sect;4). The DOM stays the store — this is a
 * convenience for reading it (activities, links, data items, form bindings, process
 * attributes) without hand-walking {@code org.w3c.dom}; nothing here mutates
 * {@link #process}.
 *
 * <p>Built once from a {@link Prd}; {@link #of} returns null for a classic PRD with
 * no {@code <process>} (or no PRD at all). Never throws on a malformed process —
 * missing attributes read as null/defaults, unrecognized elements are skipped by
 * the model (a {@code com.pointblue.dirxml.dev.validate.FlowCheck} in the same
 * source package reports those as findings, not exceptions).
 */
public final class Flow {

    /** {@code link/@type} values the engine's JAXB binding accepts (workflows.md &sect;1.1). */
    public static final List<String> LINK_TYPES = List.of(
        "forward", "approved", "denied", "refused", "timedout", "success", "fault", "true", "false", "error");

    /** {@code process/@version} values {@code ModelFactory.loadProcessFlow} accepts (workflows.md &sect;1.2 check 1). */
    public static final List<String> PROCESS_VERSIONS = List.of(
        "3.5.0", "3.5.1", "3.6.0", "3.6.1", "3.7.0", "4.0.0", "4.0.1", "4.0.2", "4.0.2A", "4.5.0");

    /** {@code data-item/@data-type} enum (workflows.md &sect;1.1). */
    public static final Set<String> DATA_TYPES = Set.of(
        "string", "boolean", "integer", "decimal", "date", "dn", "binary", "element");

    /** {@code data-item/@target-type} enum. */
    public static final Set<String> TARGET_TYPES = Set.of(
        "single-value", "multi-value-list", "multi-value-list-item");

    /** {@code provision-activity/@category} enum. */
    public static final Set<String> PROVISION_CATEGORIES = Set.of(
        "entity", "nonit_asset", "entitlement", "custom");

    /** {@code provision-activity/@operation} enum. */
    public static final Set<String> OPERATIONS = Set.of("grant", "revoke");

    /** {@code user-activity/@ontimeout} and {@code integration-activity/@ontimeout} enum. */
    public static final Set<String> ONTIMEOUTS = Set.of("approved", "denied", "refused", "timedout", "error");

    /** {@code user-activity/@approver-type} enum. */
    public static final Set<String> APPROVER_TYPES = Set.of(
        "org-approver", "group-approver", "multiple-approver", "quorum-approver");

    /** {@code process/@process-type} enum; absent defaults to {@code Normal}. */
    public static final Set<String> PROCESS_TYPES = Set.of(
        "Normal", "RBAC", "RBACSOD", "Resource", "Attestation", "ResourceProvisioning", "RoleProvisioning");

    /** {@code process/@flow-strategy} enum. */
    public static final Set<String> FLOW_STRATEGIES = Set.of(
        "SingleFlow", "FlowPerMember", "SingleFlowProvisionMembers");

    /** {@code digital-signature-type} attribute enum (process, and per-activity). */
    public static final Set<String> DIGITAL_SIGNATURE_TYPES = Set.of("data", "form", "not-required");

    /** {@code process/@default-completed-approval-status} enum. */
    public static final Set<String> DEFAULT_COMPLETED_APPROVAL_STATUSES = Set.of("approved", "denied");

    /** {@code bind-role-activity/@action} and {@code bind-resource-status-activity/@action} enum. */
    public static final Set<String> BIND_ACTIONS = Set.of("APPROVED", "DENIED");

    /** {@code rest-activity/@protocol} enum (Track W step W3, docs/workflows.md &sect;4 W3 grammar table). */
    public static final Set<String> REST_PROTOCOLS = Set.of("http", "https");

    /** {@code rest-activity/@method} enum, checked case-insensitively (the engine's binding stores it as given). */
    public static final Set<String> REST_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH");

    /** {@code role-request-activity/targetType} enum; absent defaults to {@code USER}. */
    public static final Set<String> ROLE_TARGET_TYPES = Set.of(
        "USER", "GROUP", "CONTAINER", "CONTAINER_WITH_SUBTREE", "ROLE");

    /** {@code role-request-activity/action} enum; absent defaults to {@code GRANT}. */
    public static final Set<String> ROLE_REQUEST_ACTIONS = Set.of("GRANT", "REVOKE", "EXTEND");

    /** {@code resource-request-activity/action} enum; absent defaults to {@code GRANT}. */
    public static final Set<String> RESOURCE_REQUEST_ACTIONS = Set.of("GRANT", "REVOKE");

    public final Element process;

    public final String id;
    public final String version;
    /** Never null: absent on the DOM defaults to {@code "Normal"}, matching the engine. */
    public final String processType;
    public final String flowStrategy;
    public final String setnotify;
    public final String formSrc;
    public final String restrictView;
    public final String generateComments;
    public final String defaultCompletedApprovalStatus;

    /** In document order (the order the grammar requires: start, activities…, finish). */
    public final List<Activity> activities;
    /** In document order. */
    public final List<Link> links;
    /** Keyed by {@code data-items/@activity-id}; a process with no data items for an activity has no entry. */
    public final Map<String, List<DataItem>> dataItemsByActivity;
    /** Every {@code <form-binding>} found under the process (approval bindings only — the request binding lives on {@code <provision-request>}, see {@link Prd#bindings()}). */
    public final List<FormBinding> formBindings;

    private Flow(Element process) {
        this.process = process;
        this.id = attr(process, "id");
        this.version = attr(process, "version");
        String pt = attr(process, "process-type");
        this.processType = pt == null ? "Normal" : pt;
        this.flowStrategy = attr(process, "flow-strategy");
        this.setnotify = attr(process, "setnotify");
        this.formSrc = attr(process, "formSrc");
        this.restrictView = attr(process, "restrict-view");
        this.generateComments = attr(process, "generate-comments");
        this.defaultCompletedApprovalStatus = attr(process, "default-completed-approval-status");

        List<Activity> acts = new ArrayList<>();
        for (Element e : Xds.childElements(process)) {
            String ln = localName(e);
            if (ln != null && ln.endsWith("-activity")) {
                acts.add(new Activity(attr(e, "activity-id"), Kind.byElement(ln), e));
            }
        }
        this.activities = Collections.unmodifiableList(acts);

        List<Link> lns = new ArrayList<>();
        for (Element e : Xds.childrenByName(process, "link")) {
            lns.add(new Link(attr(e, "source"), attr(e, "target"), attr(e, "type")));
        }
        this.links = Collections.unmodifiableList(lns);

        Map<String, List<DataItem>> dmap = new LinkedHashMap<>();
        for (Element block : Xds.childrenByName(process, "data-items")) {
            String actId = attr(block, "activity-id");
            List<DataItem> items = dmap.computeIfAbsent(actId, k -> new ArrayList<>());
            for (Element di : Xds.childrenByName(block, "data-item")) {
                items.add(new DataItem(attr(di, "name"), attr(di, "data-type"), attr(di, "source"),
                    attr(di, "target"), attr(di, "target-type")));
            }
        }
        this.dataItemsByActivity = Collections.unmodifiableMap(dmap);

        List<FormBinding> fbs = new ArrayList<>();
        for (Element fb : Xds.descendantsByName(process, "form-binding")) {
            fbs.add(new FormBinding(attr(fb, "activity-id"), attr(fb, "form-id")));
        }
        this.formBindings = Collections.unmodifiableList(fbs);
    }

    /** Null for a PRD with no {@code <process>} (classic-only, or no PRD). */
    public static Flow of(Prd prd) {
        if (prd == null || prd.process == null) {
            return null;
        }
        return new Flow(prd.process);
    }

    /** The activity with this id, or null. */
    public Activity byId(String activityId) {
        if (activityId == null) {
            return null;
        }
        for (Activity a : activities) {
            if (activityId.equals(a.id)) {
                return a;
            }
        }
        return null;
    }

    /** The first {@link Kind#START} activity, or null. */
    public Activity start() {
        for (Activity a : activities) {
            if (a.kind == Kind.START) {
                return a;
            }
        }
        return null;
    }

    /** The first {@link Kind#FINISH} activity, or null. */
    public Activity finish() {
        for (Activity a : activities) {
            if (a.kind == Kind.FINISH) {
                return a;
            }
        }
        return null;
    }

    /** Links whose target is this activity id, document order. */
    public List<Link> incoming(String activityId) {
        List<Link> out = new ArrayList<>();
        for (Link l : links) {
            if (l.target != null && l.target.equals(activityId)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Links whose source is this activity id, document order. */
    public List<Link> outgoing(String activityId) {
        List<Link> out = new ArrayList<>();
        for (Link l : links) {
            if (l.source != null && l.source.equals(activityId)) {
                out.add(l);
            }
        }
        return out;
    }

    /** Reads a raw DOM attribute as null-if-empty (shared by {@code Activity.attr} and by {@code FlowCheck}/{@code FlowView} for elements that aren't activities, e.g. {@code <link>}, {@code <data-item>}, {@code <notify>}/{@code <map>}). */
    public static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static String localName(Element e) {
        String ln = e.getLocalName();
        return ln != null ? ln : e.getNodeName();
    }

    // ------------------------------------------------------------------------------------

    /** One activity: its id, recognized {@link Kind}, and the raw element (for attributes W1 doesn't type). */
    public static final class Activity {
        public final String id;
        public final Kind kind;
        public final Element element;

        Activity(String id, Kind kind, Element element) {
            this.id = id;
            this.kind = kind;
            this.element = element;
        }

        /** This activity's attribute, or null if absent/empty (attribute names keep their XML dashes, e.g. {@code "approver-type"}). */
        public String attr(String name) {
            return Flow.attr(element, name);
        }

        /**
         * The {@code <display-name>} text for {@code lang}, falling back to {@code en}, then the
         * first declared language, then null if the activity has none at all. A display name may
         * itself be a flowdata expression ({@code <display-name expr="true">flowdata.get(…)</display-name>});
         * this returns the text as written either way.
         */
        public String displayName(String lang) {
            List<Element> names = Xds.childrenByName(element, "display-name");
            if (names.isEmpty()) {
                return null;
            }
            Element chosen = null;
            Element en = null;
            for (Element e : names) {
                String l = xmlLang(e);
                if (lang != null && lang.equals(l)) {
                    chosen = e;
                    break;
                }
                if (en == null && "en".equals(l)) {
                    en = e;
                }
            }
            if (chosen == null) {
                chosen = en != null ? en : names.get(0);
            }
            return Xds.text(chosen);
        }

        /** Every declared {@code display-name} language, document order (for W1's placeholder/localization checks). */
        public List<Element> displayNameElements() {
            return Xds.childrenByName(element, "display-name");
        }

        @Override
        public String toString() {
            return kind + " " + id;
        }
    }

    /** {@code xml:lang}, tried both as a namespaced attribute and as the literal qualified name (matches {@code ProjectReader}/{@code FormOps}: the vault's own DOM is not always namespace-aware on read). */
    static String xmlLang(Element e) {
        String v = e.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
        if (v != null && !v.isEmpty()) {
            return v;
        }
        v = e.getAttribute("xml:lang");
        return (v == null || v.isEmpty()) ? null : v;
    }

    /** One {@code <link source target type>}. */
    public static final class Link {
        public final String source;
        public final String target;
        public final String type;

        public Link(String source, String target, String type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }

        @Override
        public String toString() {
            return source + " --" + type + "--> " + target;
        }
    }

    /** One {@code <data-item>} of a {@code <data-items activity-id>} block. */
    public static final class DataItem {
        public final String name;
        public final String dataType;
        public final String source;
        public final String target;
        public final String targetType;

        public DataItem(String name, String dataType, String source, String target, String targetType) {
            this.name = name;
            this.dataType = dataType;
            this.source = source;
            this.target = target;
            this.targetType = targetType;
        }

        @Override
        public String toString() {
            return name + ":" + dataType + " " + (source != null ? "source=" + source : "target=" + target);
        }
    }

    /** One {@code <form-binding activity-id form-id>} under the process (an approval binding). */
    public static final class FormBinding {
        public final String activityId;
        public final String formId;

        public FormBinding(String activityId, String formId) {
            this.activityId = activityId;
            this.formId = formId;
        }

        @Override
        public String toString() {
            return "activity " + activityId + " -> " + formId;
        }
    }

    /**
     * The recognized workflow activity elements ({@code IProcessFlow}'s {@code @XmlElements},
     * workflows.md &sect;1.1) and, for the ten kinds the engine's link-source check
     * ({@code ProcessFlowModel.validate()} check 4, workflows.md &sect;1.2) actually restricts, the
     * outgoing {@code link/@type} values it allows.
     *
     * <p>Two documented departures from strict engine-faithfulness:
     * <ul>
     *   <li>{@link #REST}: the engine's check 4 does not mention {@code rest-activity} at all (no
     *   stock PRD uses one to have proven its behavior against). We assume it is meant to behave
     *   like {@link #MAPPING}/{@link #PROVISION} ({@code forward}/{@code error}) and enforce that —
     *   an assumption, not an observed fact.</li>
     *   <li>{@link #NOTIFICATION}, {@link #START_CORRELATED_FLOW}, {@link #ROLE_REQUEST},
     *   {@link #RESOURCE_REQUEST}, {@link #IG_CATALOG_REQUEST}, {@link #IG_SOD},
     *   {@link #BIND_ROLE} and {@link #BIND_RESOURCE_STATUS} are not in the engine's check-4 list
     *   either; unlike {@link #REST} we do <b>not</b> assume a restriction for these — {@link
     *   #restrictsOutgoingLinkTypes()} is false and {@code flow-link-type-not-allowed} never fires
     *   for them. {@link #allowedOutgoingLinkTypes()} still reports the conventional {@code
     *   forward}/{@code error} (or, for the bind activities, {@code forward}) purely as a hint for
     *   {@code prd.flow}'s rendering.</li>
     * </ul>
     *
     * <p>{@link #FINISH} carries no allowed types ({@code validate()} check 4: "no outgoing
     * links") but is not "restricted" here either — {@code FlowCheck} reports any outgoing link
     * from finish under the dedicated {@code flow-finish-outgoing} code, not the generic
     * not-allowed one.
     */
    public enum Kind {
        START("start-activity", true, "forward", "error"),
        USER("user-activity", true, "approved", "denied", "refused", "timedout", "error"),
        INTEGRATION("integration-activity", true, "success", "fault", "timedout", "error"),
        REST("rest-activity", true, "forward", "error"),
        CONDITION("condition-activity", true, "true", "false", "error"),
        BRANCH("branch-activity", true, "forward"),
        MERGE("merge-activity", true, "forward"),
        LOG("log-activity", true, "forward"),
        MAPPING("mapping-activity", true, "forward", "error"),
        PROVISION("provision-activity", true, "forward", "error"),
        NOTIFICATION("notification-activity", false, "forward", "error"),
        START_CORRELATED_FLOW("start-correlated-flow-activity", false, "forward", "error"),
        ROLE_REQUEST("role-request-activity", false, "forward", "error"),
        RESOURCE_REQUEST("resource-request-activity", false, "forward", "error"),
        IG_CATALOG_REQUEST("ig-catalog-request-activity", false, "forward", "error"),
        IG_SOD("ig-sod-activity", false, "forward", "error"),
        BIND_ROLE("bind-role-activity", false, "forward"),
        BIND_RESOURCE_STATUS("bind-resource-status-activity", false, "forward"),
        FINISH("finish-activity"),
        /** An element named {@code *-activity} that isn't one of the above (flagged {@code flow-activity-kind-unknown}). */
        UNKNOWN("");

        public final String element;
        private final boolean restricted;
        private final Set<String> allowed;

        Kind(String element, boolean restricted, String... allowed) {
            this.element = element;
            this.restricted = restricted;
            this.allowed = Set.of(allowed);
        }

        Kind(String element) {
            this(element, false);
        }

        /** True for the kinds the engine's own check 4 restricts ({@link #FINISH} excluded — see class doc). */
        public boolean restrictsOutgoingLinkTypes() {
            return restricted;
        }

        /** The link types this kind may emit — enforced only when {@link #restrictsOutgoingLinkTypes()} is true. */
        public Set<String> allowedOutgoingLinkTypes() {
            return allowed;
        }

        public static Kind byElement(String elementName) {
            for (Kind k : values()) {
                if (k.element.equals(elementName)) {
                    return k;
                }
            }
            return UNKNOWN;
        }
    }
}
