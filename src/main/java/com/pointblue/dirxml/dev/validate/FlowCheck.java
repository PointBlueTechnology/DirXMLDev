package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.ascode.AsCodeWriter;
import com.pointblue.dirxml.dev.flow.Flow;
import com.pointblue.dirxml.dev.model.Driver;
import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.Prd;
import com.pointblue.dirxml.sim.Xds;
import com.novell.soa.script.mozilla.javascript.Context;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A PRD's workflow ({@code <process>}; Track W step W1 — see {@code docs/workflows.md}
 * &sect;1.2 for the engine checks this mirrors, and &sect;1.4 for what it adds beyond
 * them). On by default in {@link Validator#standard}, registered after {@link FormCheck}.
 *
 * <p>Codes (E = error, W = warning, I = info): {@code flow-version-unsupported} (E),
 * {@code flow-start-missing} (E), {@code flow-finish-missing} (E), {@code flow-start-multiple}
 * (E), {@code flow-finish-multiple} (E), {@code flow-activity-id-missing} (E),
 * {@code flow-activity-id-duplicate} (E), {@code flow-activity-kind-unknown} (W),
 * {@code flow-link-source-unknown} (E), {@code flow-link-target-unknown} (E),
 * {@code flow-link-type-invalid} (E), {@code flow-link-type-not-allowed} (E),
 * {@code flow-condition-links} (E), {@code flow-start-incoming} (E),
 * {@code flow-finish-outgoing} (E), {@code flow-activity-dangling} (E),
 * {@code flow-branch-merge} (E), {@code flow-ontimeout-link} (W),
 * {@code flow-form-binding-start} (E), {@code flow-data-items-activity-unknown} (E),
 * {@code flow-data-items-on-start} (E), {@code flow-role-binding} (E),
 * {@code flow-resource-binding} (E), {@code flow-addressee-missing} (E),
 * {@code flow-flowdata-expression} (E), {@code flow-approver-type-invalid} (E),
 * {@code flow-approver-condition-both} (E), {@code flow-approver-target-items} (E),
 * {@code flow-email-template-missing} (E), {@code flow-attribute-enum} (E),
 * {@code flow-expression-syntax} (E), {@code flow-placeholder} (W on an {@code Active}
 * PRD, I otherwise), {@code flow-display-name-missing} (W), {@code flow-entitlement-unknown}
 * (W — a provision activity's {@code DirXML-Entitlement-DN} names a driver in this tree that
 * has no such entitlement), {@code flow-entitlement-external} (I — it names a driver not in
 * this tree; see {@code docs/entitlements.md}), {@code flow-activity-incomplete} (E — a
 * rest/role-request/resource-request/start-flow activity, Track W step W3, is missing a
 * required attribute or child), {@code flow-start-flow-unknown} (W — a
 * start-correlated-flow-activity's {@code processId} is a quoted literal naming a PRD not
 * found in this tree by name or DN).
 *
 * <p>Never throws for a malformed process: every check reads defensively and turns a
 * problem into a {@link Finding} rather than an exception (see {@link Check}).
 */
public final class FlowCheck implements Check {

    private static final Pattern FLOWDATA_BAD_REF = Pattern.compile("flowdata\\.(?!get\\(|getObject\\()");
    private static final Pattern QUOTED_LITERAL = Pattern.compile("^'[^']*'$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{enter [^}]*}");

    @Override
    public String name() {
        return "flows";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (Driver d : ds.drivers) {
            if (d.provisioning == null) {
                continue;
            }
            for (Prd prd : d.provisioning.prds) {
                Flow flow = Flow.of(prd);
                if (flow == null) {
                    continue;
                }
                String path = prdPath(d, prd);
                boolean active = "Active".equals(prd.property("status"));
                checkFlow(ds, flow, path, active, r);
            }
        }
    }

    private static void checkFlow(DriverSet ds, Flow flow, String path, boolean active, Report r) {
        checkVersion(flow, path, r);
        checkStartFinishCounts(flow, path, r);
        checkActivityIds(flow, path, r);
        checkLinkEndpoints(flow, path, r);
        checkLinkTypes(flow, path, r);
        checkStartFinishLinks(flow, path, r);
        checkDangling(flow, path, r);
        checkBranchMerge(flow, path, r);
        checkOntimeoutLinks(flow, path, r);
        checkFormBindingsAndDataItems(flow, path, r);
        checkRoleResourceBinding(flow, path, r);
        checkAddresseeAndFlowdata(flow, path, r);
        checkApprover(flow, path, r);
        checkEmailTemplates(flow, path, r);
        checkAttributeEnums(flow, path, r);
        checkExpressionSyntax(flow, path, r);
        checkPlaceholders(flow, path, active, r);
        checkDisplayNames(flow, path, r);
        checkEntitlementDns(ds, flow, path, r);
        checkActivityIncomplete(flow, path, r);
        checkStartFlowTargets(ds, flow, path, r);
    }

    // ---- 1: version -----------------------------------------------------------------------

    private static void checkVersion(Flow flow, String path, Report r) {
        if (flow.version == null || !Flow.PROCESS_VERSIONS.contains(flow.version)) {
            r.add(Finding.error("flow-version-unsupported", path,
                "process version " + quoteOrAbsent(flow.version) + " is not one the engine accepts: " + Flow.PROCESS_VERSIONS));
        }
    }

    // ---- start/finish presence + count -----------------------------------------------------

    private static void checkStartFinishCounts(Flow flow, String path, Report r) {
        int starts = count(flow, Flow.Kind.START);
        int finishes = count(flow, Flow.Kind.FINISH);
        if (starts == 0) {
            r.add(Finding.error("flow-start-missing", path, "process has no start-activity"));
        } else if (starts > 1) {
            r.add(Finding.error("flow-start-multiple", path, "process has " + starts + " start-activity elements; the engine binds exactly one"));
        }
        if (finishes == 0) {
            r.add(Finding.error("flow-finish-missing", path, "process has no finish-activity"));
        } else if (finishes > 1) {
            r.add(Finding.error("flow-finish-multiple", path, "process has " + finishes + " finish-activity elements; the engine binds exactly one"));
        }
    }

    private static int count(Flow flow, Flow.Kind kind) {
        int n = 0;
        for (Flow.Activity a : flow.activities) {
            if (a.kind == kind) {
                n++;
            }
        }
        return n;
    }

    // ---- activity ids ------------------------------------------------------------------------

    private static void checkActivityIds(Flow flow, String path, Report r) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Flow.Activity a : flow.activities) {
            if (a.id == null) {
                r.add(Finding.error("flow-activity-id-missing", path, "a '" + elementName(a) + "' activity has no activity-id"));
                continue;
            }
            seen.merge(a.id, 1, Integer::sum);
            if (a.kind == Flow.Kind.UNKNOWN) {
                r.add(Finding.warning("flow-activity-kind-unknown", path,
                    "activity '" + a.id + "' is a '" + elementName(a) + "' element, not one of the engine's recognized activity kinds"));
            }
        }
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() > 1) {
                r.add(Finding.error("flow-activity-id-duplicate", path,
                    "activity id '" + e.getKey() + "' is used by " + e.getValue() + " activities"));
            }
        }
    }

    private static String elementName(Flow.Activity a) {
        String ln = a.element.getLocalName();
        return ln != null ? ln : a.element.getNodeName();
    }

    // ---- engine check 1: link endpoints -------------------------------------------------------

    private static void checkLinkEndpoints(Flow flow, String path, Report r) {
        for (Flow.Link l : flow.links) {
            if (l.source == null || flow.byId(l.source) == null) {
                r.add(Finding.error("flow-link-source-unknown", path,
                    "link " + l + " has an unknown source activity"));
            }
            if (l.target == null || flow.byId(l.target) == null) {
                r.add(Finding.error("flow-link-target-unknown", path,
                    "link " + l + " has an unknown target activity"));
            }
        }
    }

    // ---- engine check 4: link types by source kind, plus condition true/false -----------------

    private static void checkLinkTypes(Flow flow, String path, Report r) {
        for (Flow.Link l : flow.links) {
            if (l.type == null || !Flow.LINK_TYPES.contains(l.type)) {
                r.add(Finding.error("flow-link-type-invalid", path, "link " + l + " has an unrecognized type"));
                continue;
            }
            Flow.Activity source = flow.byId(l.source);
            if (source == null || source.kind == Flow.Kind.FINISH) {
                continue;   // reported by link-source-unknown / flow-finish-outgoing
            }
            if (source.kind.restrictsOutgoingLinkTypes() && !source.kind.allowedOutgoingLinkTypes().contains(l.type)) {
                r.add(Finding.error("flow-link-type-not-allowed", path,
                    "link " + l + ": a " + source.kind.element + " may only emit " + source.kind.allowedOutgoingLinkTypes()));
            }
        }
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.CONDITION || a.id == null) {
                continue;
            }
            boolean hasTrue = false;
            boolean hasFalse = false;
            for (Flow.Link l : flow.outgoing(a.id)) {
                hasTrue |= "true".equals(l.type);
                hasFalse |= "false".equals(l.type);
            }
            if (!hasTrue || !hasFalse) {
                r.add(Finding.error("flow-condition-links", path,
                    "condition activity '" + a.id + "' must have both a 'true' and a 'false' outgoing link"
                        + (hasTrue ? "" : " (missing 'true')") + (hasFalse ? "" : " (missing 'false')")));
            }
        }
    }

    // ---- engine check 4: start has no incoming links, finish has no outgoing links -------------

    private static void checkStartFinishLinks(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind == Flow.Kind.START && a.id != null) {
                for (Flow.Link l : flow.incoming(a.id)) {
                    r.add(Finding.error("flow-start-incoming", path, "start activity '" + a.id + "' has an incoming link " + l));
                }
            }
            if (a.kind == Flow.Kind.FINISH && a.id != null) {
                for (Flow.Link l : flow.outgoing(a.id)) {
                    r.add(Finding.error("flow-finish-outgoing", path, "finish activity '" + a.id + "' has an outgoing link " + l));
                }
            }
        }
    }

    // ---- engine check 5: no dangling activity ---------------------------------------------------

    private static void checkDangling(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.id == null) {
                continue;
            }
            if (a.kind != Flow.Kind.START && flow.incoming(a.id).isEmpty()) {
                r.add(Finding.error("flow-activity-dangling", path, "activity '" + a.id + "' has no incoming link"));
            }
            if (a.kind != Flow.Kind.FINISH && flow.outgoing(a.id).isEmpty()) {
                r.add(Finding.error("flow-activity-dangling", path, "activity '" + a.id + "' has no outgoing link"));
            }
        }
    }

    // ---- engine check 6: branch needs a merge, merge's branch-activity-id is a branch -----------

    private static void checkBranchMerge(Flow flow, String path, Report r) {
        Set<String> branchIds = new LinkedHashSet<>();
        for (Flow.Activity a : flow.activities) {
            if (a.kind == Flow.Kind.BRANCH && a.id != null) {
                branchIds.add(a.id);
            }
        }
        Set<String> mergedBranches = new LinkedHashSet<>();
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.MERGE) {
                continue;
            }
            String branchRef = a.attr("branch-activity-id");
            Flow.Activity branch = branchRef == null ? null : flow.byId(branchRef);
            if (branch == null || branch.kind != Flow.Kind.BRANCH) {
                r.add(Finding.error("flow-branch-merge", path,
                    "merge activity '" + a.id + "' branch-activity-id " + quoteOrAbsent(branchRef) + " is not a branch activity"));
            } else {
                mergedBranches.add(branchRef);
            }
        }
        for (String b : branchIds) {
            if (!mergedBranches.contains(b)) {
                r.add(Finding.error("flow-branch-merge", path, "branch activity '" + b + "' has no merge activity referencing it"));
            }
        }
    }

    // ---- engine check 10 (UI-only, but harmless to enforce offline): ontimeout has an outgoing link of that type

    private static void checkOntimeoutLinks(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.USER && a.kind != Flow.Kind.INTEGRATION) {
                continue;
            }
            String ontimeout = a.attr("ontimeout");
            if (ontimeout == null || a.id == null) {
                continue;
            }
            boolean has = false;
            for (Flow.Link l : flow.outgoing(a.id)) {
                if (ontimeout.equals(l.type)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                r.add(Finding.warning("flow-ontimeout-link", path,
                    "activity '" + a.id + "' has ontimeout='" + ontimeout + "' but no outgoing link of that type"));
            }
        }
    }

    // ---- engine check 2: no form-binding / data-items on start; data-items reference a known activity

    private static void checkFormBindingsAndDataItems(Flow flow, String path, Report r) {
        Flow.Activity start = flow.start();
        String startId = start == null ? null : start.id;
        for (Flow.FormBinding fb : flow.formBindings) {
            if (startId != null && startId.equals(fb.activityId)) {
                r.add(Finding.error("flow-form-binding-start", path, "start activity '" + startId + "' has a form-binding to '" + fb.formId + "'"));
            }
        }
        for (Map.Entry<String, List<Flow.DataItem>> e : flow.dataItemsByActivity.entrySet()) {
            String activityId = e.getKey();
            if (activityId == null) {
                continue;
            }
            if (startId != null && startId.equals(activityId)) {
                r.add(Finding.error("flow-data-items-on-start", path, "start activity '" + startId + "' has a data-items block"));
            }
            if (flow.byId(activityId) == null) {
                r.add(Finding.error("flow-data-items-activity-unknown", path, "data-items activity-id '" + activityId + "' is not an activity in this process"));
            }
        }
    }

    // ---- engine check 3: RBAC/RBACSOD and Resource process types need both bind activities ------

    private static void checkRoleResourceBinding(Flow flow, String path, Report r) {
        boolean roleProcess = "RBAC".equals(flow.processType) || "RBACSOD".equals(flow.processType);
        boolean resourceProcess = "Resource".equals(flow.processType);
        if (!roleProcess && !resourceProcess) {
            return;
        }
        Flow.Kind bindKind = roleProcess ? Flow.Kind.BIND_ROLE : Flow.Kind.BIND_RESOURCE_STATUS;
        String code = roleProcess ? "flow-role-binding" : "flow-resource-binding";
        String elementName = bindKind.element;

        Flow.Activity approved = null;
        Flow.Activity denied = null;
        for (Flow.Activity a : flow.activities) {
            if (a.kind != bindKind) {
                continue;
            }
            String action = a.attr("action");
            if ("APPROVED".equals(action)) {
                approved = a;
            } else if ("DENIED".equals(action)) {
                denied = a;
            }
        }
        if (approved == null) {
            r.add(Finding.error(code, path, "process-type '" + flow.processType + "' has no " + elementName + " action=\"APPROVED\""));
        }
        if (denied == null) {
            r.add(Finding.error(code, path, "process-type '" + flow.processType + "' has no " + elementName + " action=\"DENIED\""));
        }
        Flow.Activity finish = flow.finish();
        if (finish == null || finish.id == null) {
            return;
        }
        Set<String> bindIds = new LinkedHashSet<>();
        if (approved != null) {
            bindIds.add(approved.id);
        }
        if (denied != null) {
            bindIds.add(denied.id);
        }
        for (Flow.Link l : flow.incoming(finish.id)) {
            if (!bindIds.contains(l.source)) {
                r.add(Finding.error(code, path,
                    "link " + l + " reaches finish without going through a " + elementName + " (process-type '" + flow.processType + "')"));
            }
        }
    }

    // ---- engine check 7: addressee present + flowdata.get()/getObject() form -------------------

    private static void checkAddresseeAndFlowdata(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind == Flow.Kind.USER) {
                List<Element> addressees = Xds.childrenByName(a.element, "addressee");
                boolean anyNonBlank = false;
                for (Element ae : addressees) {
                    if (nonBlank(addresseeExpr(ae))) {
                        anyNonBlank = true;
                        break;
                    }
                }
                if (!anyNonBlank) {
                    r.add(Finding.error("flow-addressee-missing", path, "user activity '" + a.id + "' has no non-blank addressee"));
                }
            }
        }
        for (Map.Entry<String, List<Flow.DataItem>> e : flow.dataItemsByActivity.entrySet()) {
            for (Flow.DataItem di : e.getValue()) {
                checkFlowdataForm(di.source, "data-item '" + di.name + "' (activity '" + e.getKey() + "') source", path, r);
            }
        }
        for (Flow.Activity a : flow.activities) {
            for (Element ae : Xds.descendantsByName(a.element, "addressee")) {
                checkFlowdataForm(addresseeExpr(ae), "activity '" + a.id + "' addressee", path, r);
            }
            for (String childName : new String[] {"notify", "confirm", "reminder"}) {
                for (Element notif : Xds.childrenByName(a.element, childName)) {
                    for (Element map : Xds.childrenByName(notif, "map")) {
                        checkFlowdataForm(Flow.attr(map, "source"), "activity '" + a.id + "' " + childName + " map source", path, r);
                    }
                }
            }
            if (a.kind == Flow.Kind.LOG) {
                for (Element msg : Xds.childrenByName(a.element, "message")) {
                    checkFlowdataForm(Xds.text(msg), "log activity '" + a.id + "' message", path, r);
                }
            }
        }
    }

    private static void checkFlowdataForm(String value, String where, String path, Report r) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (FLOWDATA_BAD_REF.matcher(value).find()) {
            r.add(Finding.error("flow-flowdata-expression", path,
                where + " references 'flowdata.' without '.get(' or '.getObject(': " + value));
        }
    }

    private static String addresseeExpr(Element addressee) {
        String v = Flow.attr(addressee, "value");
        return v != null ? v : Xds.text(addressee);
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---- engine check 7/9: approver-type, approver-condition exclusivity, target items -----------

    private static void checkApprover(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.USER) {
                continue;
            }
            String approverType = a.attr("approver-type");
            if (approverType != null && !Flow.APPROVER_TYPES.contains(approverType)) {
                r.add(Finding.error("flow-approver-type-invalid", path,
                    "user activity '" + a.id + "' has approver-type '" + approverType + "', not one of " + Flow.APPROVER_TYPES));
            }
            String cond = a.attr("approver-condition");
            String condExpr = a.attr("approver-condition-expr");
            if (nonBlank(cond) && nonBlank(condExpr)) {
                r.add(Finding.error("flow-approver-condition-both", path,
                    "user activity '" + a.id + "' has both approver-condition and approver-condition-expr"));
            }
            if ("multiple-approver".equals(approverType) || "quorum-approver".equals(approverType)) {
                for (Flow.DataItem di : flow.dataItemsByActivity.getOrDefault(a.id, List.of())) {
                    if (nonBlank(di.target)) {
                        r.add(Finding.error("flow-approver-target-items", path,
                            "user activity '" + a.id + "' (approver-type '" + approverType + "') has data item '"
                                + di.name + "' with a target — multiple/quorum approvers may not target flowdata"));
                    }
                }
            }
        }
    }

    // ---- engine check 8: notify/confirm/reminder present => template non-empty --------------------

    private static void checkEmailTemplates(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.USER && a.kind != Flow.Kind.FINISH) {
                continue;
            }
            for (String childName : new String[] {"notify", "confirm", "reminder"}) {
                for (Element notif : Xds.childrenByName(a.element, childName)) {
                    String template = Flow.attr(notif, "template");
                    if (!nonBlank(template)) {
                        r.add(Finding.error("flow-email-template-missing", path,
                            "activity '" + a.id + "' <" + childName + "> has an empty template"));
                    }
                }
            }
        }
    }

    // ---- attribute enums --------------------------------------------------------------------------

    private static void checkAttributeEnums(Flow flow, String path, Report r) {
        checkEnum(flow.processType, Flow.PROCESS_TYPES, "process process-type", path, r);
        if (flow.flowStrategy != null) {
            checkEnum(flow.flowStrategy, Flow.FLOW_STRATEGIES, "process flow-strategy", path, r);
        }
        if (flow.defaultCompletedApprovalStatus != null) {
            checkEnum(flow.defaultCompletedApprovalStatus, Flow.DEFAULT_COMPLETED_APPROVAL_STATUSES,
                "process default-completed-approval-status", path, r);
        }
        for (Flow.Activity a : flow.activities) {
            String dst = a.attr("digital-signature-type");
            if (dst != null) {
                checkEnum(dst, Flow.DIGITAL_SIGNATURE_TYPES, "activity '" + a.id + "' digital-signature-type", path, r);
            }
            if (a.kind == Flow.Kind.PROVISION) {
                String category = a.attr("category");
                if (category != null) {
                    checkEnum(category, Flow.PROVISION_CATEGORIES, "activity '" + a.id + "' category", path, r);
                }
                String operation = a.attr("operation");
                if (operation != null) {
                    checkEnum(operation, Flow.OPERATIONS, "activity '" + a.id + "' operation", path, r);
                }
            }
            if ((a.kind == Flow.Kind.USER || a.kind == Flow.Kind.INTEGRATION) && a.attr("ontimeout") != null) {
                checkEnum(a.attr("ontimeout"), Flow.ONTIMEOUTS, "activity '" + a.id + "' ontimeout", path, r);
            }
            if ((a.kind == Flow.Kind.BIND_ROLE || a.kind == Flow.Kind.BIND_RESOURCE_STATUS) && a.attr("action") != null) {
                checkEnum(a.attr("action"), Flow.BIND_ACTIONS, "activity '" + a.id + "' action", path, r);
            }
            if (a.kind == Flow.Kind.REST) {
                String protocol = a.attr("protocol");
                if (protocol != null) {
                    checkEnum(protocol, Flow.REST_PROTOCOLS, "activity '" + a.id + "' protocol", path, r);
                }
                String method = a.attr("method");
                if (method != null) {
                    checkEnumCaseInsensitive(method, Flow.REST_METHODS, "activity '" + a.id + "' method", path, r);
                }
            }
            if (a.kind == Flow.Kind.ROLE_REQUEST) {
                String targetType = textOfFirstChild(a.element, "targetType");
                if (targetType != null) {
                    checkEnum(targetType, Flow.ROLE_TARGET_TYPES, "activity '" + a.id + "' targetType", path, r);
                }
                String roleAction = textOfFirstChild(a.element, "action");
                if (roleAction != null) {
                    checkEnum(roleAction, Flow.ROLE_REQUEST_ACTIONS, "activity '" + a.id + "' action", path, r);
                }
            }
            if (a.kind == Flow.Kind.RESOURCE_REQUEST) {
                String resourceAction = textOfFirstChild(a.element, "action");
                if (resourceAction != null) {
                    checkEnum(resourceAction, Flow.RESOURCE_REQUEST_ACTIONS, "activity '" + a.id + "' action", path, r);
                }
            }
        }
        for (Map.Entry<String, List<Flow.DataItem>> e : flow.dataItemsByActivity.entrySet()) {
            for (Flow.DataItem di : e.getValue()) {
                if (di.dataType != null) {
                    checkEnum(di.dataType, Flow.DATA_TYPES, "data-item '" + di.name + "' (activity '" + e.getKey() + "') data-type", path, r);
                }
                if (di.targetType != null) {
                    checkEnum(di.targetType, Flow.TARGET_TYPES, "data-item '" + di.name + "' (activity '" + e.getKey() + "') target-type", path, r);
                }
            }
        }
    }

    private static void checkEnum(String value, Set<String> allowed, String where, String path, Report r) {
        if (!allowed.contains(value)) {
            r.add(Finding.error("flow-attribute-enum", path, where + " has value '" + value + "', not one of " + allowed));
        }
    }

    /** Like {@link #checkEnum} but case-insensitive against an all-uppercase {@code allowedUpper} (rest-activity's {@code method}, docs/workflows.md &sect;4 W3). */
    private static void checkEnumCaseInsensitive(String value, Set<String> allowedUpper, String where, String path, Report r) {
        if (!allowedUpper.contains(value.toUpperCase(java.util.Locale.ROOT))) {
            r.add(Finding.error("flow-attribute-enum", path, where + " has value '" + value + "', not one of " + allowedUpper + " (case-insensitive)"));
        }
    }

    // ---- expression syntax (Rhino, same path as FormCheck's form scripts) -------------------------

    private static void checkExpressionSyntax(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind == Flow.Kind.CONDITION) {
                for (Element expr : Xds.childrenByName(a.element, "expression")) {
                    checkScript(Xds.text(expr), "activity '" + a.id + "' expression", path, r);
                }
            }
            for (Element ae : Xds.descendantsByName(a.element, "addressee")) {
                checkScript(addresseeExpr(ae), "activity '" + a.id + "' addressee", path, r);
            }
            for (String childName : new String[] {"notify", "confirm", "reminder"}) {
                for (Element notif : Xds.childrenByName(a.element, childName)) {
                    for (Element map : Xds.childrenByName(notif, "map")) {
                        checkScript(Flow.attr(map, "source"), "activity '" + a.id + "' " + childName + " map source", path, r);
                    }
                }
            }
            if (a.kind == Flow.Kind.LOG) {
                for (Element msg : Xds.childrenByName(a.element, "message")) {
                    checkScript(Xds.text(msg), "log activity '" + a.id + "' message", path, r);
                }
            }
            if (a.kind == Flow.Kind.REST) {
                checkScript(textOfFirstChild(a.element, "content"), "activity '" + a.id + "' content", path, r);
            }
            if (a.kind == Flow.Kind.ROLE_REQUEST) {
                for (Element role : Xds.childrenByName(a.element, "roles")) {
                    checkScript(Xds.text(role), "activity '" + a.id + "' roles", path, r);
                }
                for (Element target : Xds.childrenByName(a.element, "targets")) {
                    checkScript(Xds.text(target), "activity '" + a.id + "' targets", path, r);
                }
                checkScript(textOfFirstChild(a.element, "request-description"), "activity '" + a.id + "' request-description", path, r);
            }
            if (a.kind == Flow.Kind.RESOURCE_REQUEST) {
                checkScript(textOfFirstChild(a.element, "target-resource"), "activity '" + a.id + "' target-resource", path, r);
                for (Element user : Xds.childrenByName(a.element, "target-user")) {
                    checkScript(Xds.text(user), "activity '" + a.id + "' target-user", path, r);
                }
                checkScript(textOfFirstChild(a.element, "request-description"), "activity '" + a.id + "' request-description", path, r);
            }
            if (a.kind == Flow.Kind.START_CORRELATED_FLOW) {
                checkScript(textOfFirstChild(a.element, "processId"), "activity '" + a.id + "' processId", path, r);
                for (Element recipient : Xds.childrenByName(a.element, "recipient")) {
                    checkScript(Xds.text(recipient), "activity '" + a.id + "' recipient", path, r);
                }
            }
        }
        for (Map.Entry<String, List<Flow.DataItem>> e : flow.dataItemsByActivity.entrySet()) {
            for (Flow.DataItem di : e.getValue()) {
                checkScript(di.source, "data-item '" + di.name + "' (activity '" + e.getKey() + "') source", path, r);
            }
        }
    }

    private static String textOfFirstChild(Element parent, String childName) {
        List<Element> kids = Xds.childrenByName(parent, childName);
        return kids.isEmpty() ? null : Xds.text(kids.get(0));
    }

    private static void checkScript(String src, String where, String path, Report r) {
        if (src == null || src.isBlank() || QUOTED_LITERAL.matcher(src.strip()).matches()) {
            return;
        }
        String err = EcmaScriptCheck.compileError(src, where, Context.VERSION_ES6);
        if (err != null) {
            r.add(Finding.error("flow-expression-syntax", path, where + ": " + err));
        }
    }

    // ---- placeholder text left over from a template -------------------------------------------

    private static void checkPlaceholders(Flow flow, String path, boolean active, Report r) {
        List<String> hits = new ArrayList<>();
        collectPlaceholders(flow.process, hits);
        for (String hit : hits) {
            if (active) {
                r.add(Finding.warning("flow-placeholder", path, "unresolved template placeholder (the request will fail at runtime until it is replaced): " + hit));
            } else {
                r.add(Finding.info("flow-placeholder", path, "template placeholder (not yet Active): " + hit));
            }
        }
    }

    private static void collectPlaceholders(Element el, List<String> hits) {
        org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
        if (attrs != null) {
            for (int i = 0; i < attrs.getLength(); i++) {
                findPlaceholders(attrs.item(i).getNodeValue(), hits);
            }
        }
        org.w3c.dom.NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            org.w3c.dom.Node k = kids.item(i);
            if (k.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                collectPlaceholders((Element) k, hits);
            } else if (k.getNodeType() == org.w3c.dom.Node.TEXT_NODE || k.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                findPlaceholders(k.getNodeValue(), hits);
            }
        }
    }

    private static void findPlaceholders(String text, List<String> hits) {
        if (text == null) {
            return;
        }
        java.util.regex.Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            hits.add(m.group());
        }
    }

    // ---- display names ---------------------------------------------------------------------------

    private static void checkDisplayNames(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.displayNameElements().isEmpty()) {
                r.add(Finding.warning("flow-display-name-missing", path, "activity '" + a.id + "' has no display-name"));
            }
        }
    }

    // ---- entitlements (docs/entitlements.md) -------------------------------------------------

    /**
     * A provision activity's {@code DirXML-Entitlement-DN} literal names {@code cn=<entitlement>,cn=<driver>,…}:
     * warn when the named driver is in this tree but has no such entitlement, or note it (info) when the DN's
     * driver isn't in this tree at all (a live/foreign entitlement this tree can't see).
     */
    private static void checkEntitlementDns(DriverSet ds, Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.PROVISION) {
                continue;
            }
            for (Flow.DataItem di : flow.dataItemsByActivity.getOrDefault(a.id, java.util.List.of())) {
                if (!"DirXML-Entitlement-DN".equals(di.name)) {
                    continue;
                }
                String dn = unquoteLiteral(di.source);
                if (dn == null || dn.isBlank()) {
                    continue;
                }
                String[] rdns = firstTwoRdnValues(dn);
                if (rdns == null) {
                    continue;
                }
                String entName = rdns[0];
                String driverName = rdns[1];
                Driver d = ds.driver(driverName);
                if (d == null) {
                    r.add(Finding.info("flow-entitlement-external", path, "activity '" + a.id
                        + "' DirXML-Entitlement-DN names a driver ('" + driverName + "') not in this tree: " + dn));
                    continue;
                }
                if (d.entitlement(entName) == null) {
                    r.add(Finding.warning("flow-entitlement-unknown", path, "activity '" + a.id
                        + "' DirXML-Entitlement-DN names entitlement '" + entName + "' on driver '" + driverName
                        + "', which has no such entitlement"));
                }
            }
        }
    }

    /** Reverses {@code FlowOps.quoteLiteral}: a single-quoted literal -&gt; its value; null otherwise. */
    private static String unquoteLiteral(String source) {
        if (source == null) {
            return null;
        }
        String s = source.strip();
        if (s.length() < 2 || s.charAt(0) != '\'' || s.charAt(s.length() - 1) != '\'') {
            return null;
        }
        String inner = s.substring(1, s.length() - 1);
        return inner.replace("\\'", "'").replace("\\\\", "\\");
    }

    /** The first two RDN values of a DN ({@code [leaf, parent]}), naively split on unescaped commas; null if fewer than two components. */
    private static String[] firstTwoRdnValues(String dn) {
        List<String> comps = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\' && i + 1 < dn.length()) {
                cur.append(c).append(dn.charAt(++i));
            } else if (c == ',') {
                comps.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            comps.add(cur.toString().trim());
        }
        if (comps.size() < 2) {
            return null;
        }
        return new String[] {rdnValue(comps.get(0)), rdnValue(comps.get(1))};
    }

    private static String rdnValue(String comp) {
        int eq = comp.indexOf('=');
        String v = eq >= 0 ? comp.substring(eq + 1) : comp;
        return v.replace("\\,", ",").replace("\\\\", "\\").trim();
    }

    // ---- integration activities: required attributes/children present (Track W step W3) --------

    /**
     * Per-kind required-field check for the four integration activity kinds
     * (docs/workflows.md &sect;4 W3 grammar table); the engine's JAXB binding does not
     * enforce these (a missing attribute/element just unmarshals to null and fails, if
     * at all, deep inside the runtime the first time a request reaches that activity),
     * so catching it offline is cheap insurance in the same spirit as &sect;1.4.
     */
    private static void checkActivityIncomplete(Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            switch (a.kind) {
                case REST:
                    requireAttr(a, "protocol", path, r);
                    requireAttr(a, "host", path, r);
                    requireAttr(a, "port", path, r);
                    requireAttr(a, "path", path, r);
                    requireAttr(a, "method", path, r);
                    break;
                case ROLE_REQUEST:
                    requireChildNonEmpty(a, "roles", path, r, "at least one role");
                    requireChildNonEmpty(a, "targets", path, r, "at least one target");
                    requireChildText(a, "request-description", path, r);
                    break;
                case RESOURCE_REQUEST:
                    requireChildText(a, "target-resource", path, r);
                    requireChildNonEmpty(a, "target-user", path, r, "at least one target-user");
                    requireChildText(a, "request-description", path, r);
                    break;
                case START_CORRELATED_FLOW:
                    requireChildText(a, "processId", path, r);
                    requireChildNonEmpty(a, "recipient", path, r, "at least one recipient");
                    break;
                default:
                    break;
            }
        }
    }

    private static void requireAttr(Flow.Activity a, String attrName, String path, Report r) {
        if (!nonBlank(a.attr(attrName))) {
            r.add(Finding.error("flow-activity-incomplete", path,
                elementName(a) + " '" + a.id + "' is missing required attribute '" + attrName + "'"));
        }
    }

    private static void requireChildText(Flow.Activity a, String childName, String path, Report r) {
        if (!nonBlank(textOfFirstChild(a.element, childName))) {
            r.add(Finding.error("flow-activity-incomplete", path,
                elementName(a) + " '" + a.id + "' is missing required '" + childName + "'"));
        }
    }

    private static void requireChildNonEmpty(Flow.Activity a, String childName, String path, Report r, String what) {
        boolean any = false;
        for (Element k : Xds.childrenByName(a.element, childName)) {
            if (nonBlank(Xds.text(k))) {
                any = true;
                break;
            }
        }
        if (!any) {
            r.add(Finding.error("flow-activity-incomplete", path, elementName(a) + " '" + a.id + "' needs " + what));
        }
    }

    // ---- start-correlated-flow-activity's processId names a PRD in this tree (Track W step W3) ---

    private static void checkStartFlowTargets(DriverSet ds, Flow flow, String path, Report r) {
        for (Flow.Activity a : flow.activities) {
            if (a.kind != Flow.Kind.START_CORRELATED_FLOW) {
                continue;
            }
            String literal = unquoteLiteral(textOfFirstChild(a.element, "processId"));
            if (literal == null || literal.isBlank()) {
                continue;
            }
            if (!prdExistsByNameOrDn(ds, literal)) {
                r.add(Finding.warning("flow-start-flow-unknown", path,
                    "activity '" + a.id + "' processId names a PRD ('" + literal + "') not found in this tree by name or DN"));
            }
        }
    }

    private static boolean prdExistsByNameOrDn(DriverSet ds, String value) {
        for (Driver d : ds.drivers) {
            if (d.provisioning == null) {
                continue;
            }
            for (Prd p : d.provisioning.prds) {
                if (value.equals(p.name)) {
                    return true;
                }
                if (p.process != null) {
                    String dn = p.process.getAttribute("id");
                    if (dn != null && !dn.isEmpty() && dn.equalsIgnoreCase(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static String quoteOrAbsent(String v) {
        return v == null ? "(absent)" : "'" + v + "'";
    }

    private static String prdPath(Driver d, Prd p) {
        return "drivers/" + AsCodeWriter.fileSafe(d.name) + "/provisioning/prds/" + AsCodeWriter.fileSafe(p.name);
    }
}
