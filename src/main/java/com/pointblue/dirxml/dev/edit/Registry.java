package com.pointblue.dirxml.dev.edit;

import com.pointblue.dirxml.dev.model.PolicySet;
import com.pointblue.dirxml.dev.model.Scope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one registry of edit operations: name, argument spec, description, and a
 * factory from parsed arguments. The CLI dispatches from it; any other surface
 * (an MCP adapter, if ever) would too. Arguments arrive as {@code --key value}
 * pairs; a repeatable argument (e.g. {@code --order}) is joined with {@code \n}.
 */
public final class Registry {

    /** One argument of an operation. */
    public static final class Arg {
        public final String name;
        public final boolean required;
        public final String help;

        Arg(String name, boolean required, String help) {
            this.name = name;
            this.required = required;
            this.help = help;
        }
    }

    /** The factory for one operation. */
    public interface Factory {
        Operation create(Map<String, String> args) throws IOException, IllegalArgumentException;
    }

    public static final class Spec {
        public final String name;
        public final String help;
        public final List<Arg> args;
        final Factory factory;

        Spec(String name, String help, List<Arg> args, Factory factory) {
            this.name = name;
            this.help = help;
            this.args = args;
            this.factory = factory;
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private Registry() {
    }

    public static Spec get(String name) {
        return SPECS.get(name);
    }

    public static List<Spec> all() {
        return new ArrayList<>(SPECS.values());
    }

    private static void register(String name, String help, Factory f, Arg... args) {
        SPECS.put(name, new Spec(name, help, Arrays.asList(args), f));
    }

    private static java.nio.file.Path pathOrNull(String dir) {
        return dir == null || dir.isBlank() ? null : Paths.get(dir);
    }

    private static com.pointblue.dirxml.dev.packages.Catalog catalogOf(String dir) {
        try {
            return dir == null || dir.isBlank() ? null : com.pointblue.dirxml.dev.packages.Catalog.open(Paths.get(dir));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot open catalog " + dir + ": " + e.getMessage());
        }
    }

    private static java.util.Map<String, String> readAnswers(String file) {
        try {
            return com.pointblue.dirxml.dev.packages.PackageInstall.readAnswers(file == null || file.isBlank() ? null : Paths.get(file));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("cannot read answers file " + file + ": " + e.getMessage());
        }
    }

    private static Arg req(String name, String help) {
        return new Arg(name, true, help);
    }

    private static Arg opt(String name, String help) {
        return new Arg(name, false, help);
    }

    static {
        Arg driver = opt("driver", "driver name (omit for the Library)");
        Arg scope = opt("scope", "driver|subscriber|publisher|library (default: driver, or library when no --driver)");
        Arg content = opt("content-file", "file holding the content (XML, or JS for ecmascript); default: a skeleton");
        Arg link = opt("link", "also link it: a policy-set key (input, output, subscriber-command, publisher-event, …)");
        Arg at = opt("at", "where in the set: first|last|after:<path>|before:<path>|<order> (default last)");
        Arg linkDriver = opt("link-driver", "for a Library artifact with --link: the driver whose set links it");

        register("policy.add", "create a policy (DirXML Script, XSLT or schema map) and optionally link it",
            a -> new ArtifactOps.Add(scopeOf(a), a.get("driver"), a.get("name"),
                a.getOrDefault("kind", "policy"), contentOf(a), setOf(a.get("link")),
                a.containsKey("at") ? ArtifactOps.Position.parse(a.get("at")) : null, a.get("link-driver")),
            req("name", "policy name"), driver, scope, opt("kind", "policy|xslt|schema-map (default policy)"),
            content, link, at, linkDriver);

        register("resource.add", "create a mapping table, ECMAScript or GCV-definition resource and optionally link it",
            a -> new ArtifactOps.Add(scopeOf(a), a.get("driver"), a.get("name"),
                a.getOrDefault("kind", "mapping-table"), contentOf(a), setOf(a.get("link")),
                a.containsKey("at") ? ArtifactOps.Position.parse(a.get("at")) : null, a.get("link-driver")),
            req("name", "resource name"), driver, scope, opt("kind", "mapping-table|ecmascript|gcv (default mapping-table)"),
            content, link, at, linkDriver);

        register("artifact.set-content", "replace an artifact's content",
            a -> new ArtifactOps.SetContent(a.get("path"), contentOf(a)),
            req("path", "artifact path (library/X, drivers/D/X, drivers/D/subscriber/X)"),
            req("content-file", "file holding the new content"));

        register("artifact.rename", "rename an artifact and rewrite every reference to it",
            a -> new ArtifactOps.Rename(a.get("path"), a.get("name")),
            req("path", "artifact path"), req("name", "the new name"));

        register("artifact.delete", "delete an artifact; refuses while anything references it",
            a -> new ArtifactOps.Delete(a.get("path"), a.containsKey("unlink")),
            req("path", "artifact path"), opt("unlink", "remove its policy-set links first (flag)"));

        register("form.set-content", "replace a JSON form's document (pretty-printed in the tree) and re-sync every PRD binding that references it",
            a -> new FormOps.SetContent(a.get("driver"), a.get("form"), contentOf(a)),
            req("form", "form name, kind/name (request|approval|template) or driver/kind/name"),
            req("content-file", "file holding the new form JSON (what the vendor builder saved)"),
            opt("driver", "the User Application driver (needed when several drivers have a form of that name)"));

        register("form.sync", "re-normalize a form already saved into the tree (e.g. by the vendor builder) and re-sync its PRD bindings",
            a -> new FormOps.SetContent(a.get("driver"), a.get("form"), null),
            req("form", "form name, kind/name or driver/kind/name"),
            opt("driver", "the User Application driver"));

        // provisioning: typed form/PRD operations (Track P step P2b)
        Arg formRef = req("form", "form name, kind/name (request|approval|template) or driver/kind/name");
        Arg formDriver = opt("driver", "the User Application driver (needed when several drivers have a form of that name)");
        register("form.add", "create a new form (blank, or --from another form's document)",
            a -> new FormOps.Add(a.get("driver"), a.get("kind"), a.get("name"), a.get("from"), a.get("title")),
            req("kind", "request|approval|template"), req("name", "the new form's name"),
            opt("from", "copy this form's document (new title)"), opt("title", "the new form's title (default: its name)"),
            driver);

        register("form.field.add", "add a new component to a form",
            a -> new FormOps.FieldAdd(a.get("driver"), a.get("form"), a.get("key"), a.get("type"), a.get("label"),
                a.containsKey("required"), a.containsKey("hidden"), a.containsKey("multiple"), a.containsKey("minimal"),
                a.get("after"), a.get("before"), a.containsKey("first"), a.get("in"), a.get("props")),
            formRef, req("key", "the new component's key"), req("type", "component type (a captured template name, or any Form.io type)"),
            opt("label", "label text"), opt("required", "flag: validate.required = true"), opt("hidden", "flag: hidden = true"),
            opt("multiple", "flag: multiple = true"), opt("minimal", "flag: force the minimal {label,key,type,input} shape"),
            opt("after", "place after this key"), opt("before", "place before this key"), opt("first", "flag: place first"),
            opt("in", "place inside this container/panel/columns component (its first column, if columns)"),
            opt("props", "extra properties as a JSON object, deep-merged in last (--json is the output flag)"), formDriver);

        register("form.field.set", "change an existing component's properties",
            a -> new FormOps.FieldSet(a.get("driver"), a.get("form"), a.get("key"), a.get("label"),
                boolOrNull(a.get("required")), boolOrNull(a.get("hidden")), boolOrNull(a.get("multiple")), a.get("type"),
                repeatable(a.get("prop"))),
            formRef, req("key", "the component's key"), opt("label", "new label"),
            opt("required", "true|false"), opt("hidden", "true|false"), opt("multiple", "true|false"), opt("type", "new component type"),
            opt("prop", "dotted-path=JSON-value (repeat --prop for each, e.g. validate.maxLength=50)"), formDriver);

        register("form.field.remove", "remove a component; refuses while a PRD data item maps it unless --force",
            a -> new FormOps.FieldRemove(a.get("driver"), a.get("form"), a.get("key")),
            formRef, req("key", "the component's key"), formDriver);

        register("form.field.move", "reorder or reparent a component",
            a -> new FormOps.FieldMove(a.get("driver"), a.get("form"), a.get("key"), a.get("after"), a.get("before"),
                a.containsKey("first"), a.containsKey("last"), a.get("in")),
            formRef, req("key", "the component's key"), opt("after", "place after this key"), opt("before", "place before this key"),
            opt("first", "flag: place first"), opt("last", "flag: place last"),
            opt("in", "reparent inside this container/panel/columns component"), formDriver);

        register("form.set", "change a form's title, display mode, inline script (from a file) or an external script",
            a -> new FormOps.SetForm(a.get("driver"), a.get("form"), a.get("title"), a.get("display"),
                scriptFileOf(a.get("inline-script")), a.get("external-script"), a.containsKey("remove")),
            formRef, opt("title", "new title"), opt("display", "form|workflowWizard"),
            opt("inline-script", "a file holding the new inlinescripts text"),
            opt("external-script", "a script URL to add (or remove, with --remove)"),
            opt("remove", "flag: with --external-script, remove it instead of adding it"), formDriver);

        register("form.localize", "set explicit localized strings, or top up every declared language with missing entries",
            a -> new FormOps.Localize(a.get("driver"), a.get("form"), a.get("lang"), repeatable(a.get("set")), a.containsKey("sync")),
            formRef, req("lang", "language code, e.g. fr"),
            opt("set", "Label=Localized (repeat --set for each)"),
            opt("sync", "flag: add, to every declared language, an entry for every label/placeholder/tooltip/option/button text missing one"),
            formDriver);

        register("form.rename", "rename a form and rewrite every PRD reference to it (form-id attributes and flowdata prefixes)",
            a -> new FormOps.Rename(a.get("driver"), a.get("form"), a.get("to")),
            formRef, req("to", "the new name"), formDriver);

        register("form.delete", "delete a form; refuses while any PRD binds it (never overridden by --force); a packaged form needs --force",
            a -> new FormOps.Delete(a.get("driver"), a.get("form")),
            formRef, formDriver);

        register("prd.map", "add, replace or (--unmap) remove a field's data-item mapping",
            a -> new FormOps.PrdMap(a.get("driver"), a.get("prd"), a.get("field"), a.get("activity"),
                a.get("target"), a.get("source"), a.containsKey("unmap")),
            req("prd", "PRD name"), req("field", "the form field's key"),
            opt("activity", "map an approval activity's data item instead of the request form's"),
            opt("target", "override the default flowdata target (request form only)"),
            opt("source", "override the default flowdata.get(...) source (activity only; required if the request form has no same-named field)"),
            opt("unmap", "flag: remove the mapping instead"), driver);

        register("prd.add", "copy a template PRD (status Template) into a new Active PRD bound to the given forms",
            a -> new FormOps.PrdAdd(a.get("driver"), a.get("name"), a.get("from-template"), a.get("request-form"),
                a.get("approval-form"), a.get("category"), a.get("display-name"), a.containsKey("map-all")),
            req("name", "the new PRD's name"), req("from-template", "the template PRD's name (status Template, e.g. NoApproval)"),
            req("request-form", "the request form to bind"), opt("approval-form", "the approval form to bind to the first user-activity, if any"),
            opt("category", "prov-category (default: the template's)"), opt("display-name", "lang~Text override for one language (default: the new PRD's name, every language)"),
            opt("map-all", "flag: map every bindable field of the bound form(s) to flowdata with the default targets/sources, as prd.map would"),
            driver);

        register("prd.delete", "delete a PRD; refuses while another PRD's start-correlated-flow-activity references it (never overridden by --force); a packaged PRD needs --force",
            a -> new FormOps.PrdDelete(a.get("driver"), a.get("prd")),
            req("prd", "PRD name"), driver);

        // workflow (flow.*): typed operations on a PRD's <process> (Track W step W2, integration kinds Track W step W3)
        Arg flowPrd = req("prd", "PRD name");
        Arg[] flowIntegrationArgs = {
            opt("protocol", "rest: http|https (required)"),
            opt("host", "rest: host (required)"),
            opt("port", "rest: port (required)"),
            opt("path", "rest: request path (required)"),
            opt("method", "rest: GET|POST|PUT|DELETE|PATCH, checked case-insensitively, stored as given (required)"),
            opt("content", "rest: the request body expression"),
            opt("header", "rest: Key=Value, repeatable (one <http-headers> element per header)"),
            opt("content-type", "rest: contentTypeHeader"),
            opt("accept", "rest: acceptHeader"),
            opt("authorization", "rest: authorizationHeader"),
            opt("status-to", "rest: data item name that receives the response status code"),
            opt("content-to", "rest: data item name that receives the response content"),
            opt("content-type-to", "rest: data item name that receives the response content type"),
            opt("role", "role-request: a role expression (typically a quoted DN), repeatable, at least one required"),
            opt("target", "role-request: a target expression, repeatable, at least one required; resource-request: a target-user expression, repeatable, at least one required"),
            opt("target-type", "role-request: USER|GROUP|CONTAINER|CONTAINER_WITH_SUBTREE|ROLE (default USER)"),
            opt("action", "role-request: GRANT|REVOKE|EXTEND (default GRANT); resource-request: GRANT|REVOKE (default GRANT)"),
            opt("description", "role-request/resource-request: the request-description expression (required)"),
            opt("effective-date", "role-request: the effective-date expression"),
            opt("expiration-date", "role-request: the expiration-date expression"),
            opt("correlation-id", "role-request/resource-request: the correlation-id expression; start-flow: the correlationId expression"),
            opt("resource", "resource-request: the target-resource expression (required)"),
            opt("param", "resource-request: source=target, repeatable (one <target-param> element per pair)"),
            opt("process", "start-flow: the processId expression naming the target PRD (required)"),
            opt("recipient", "start-flow: a recipient expression, repeatable, at least one required"),
        };
        register("flow.activity.add", "insert a workflow activity after another, wiring its default outgoing link(s)",
            a -> new FlowOps.ActivityAdd(a.get("driver"), a.get("prd"), a.get("kind"), a.get("id"), a.get("after"),
                a.get("via"), a.get("to"), a.get("on-denied"), a.get("on-false"), a.get("name"), a.get("addressee"),
                a.get("timeout"), a.get("ontimeout"), a.get("expression"), a.get("message"), a.get("template"),
                a.get("entitlement-dn"), a.get("entitlement-param"), a.get("form"), a.get("status"), flowExtra(a)),
            concatArgs(new Arg[] {flowPrd,
                req("kind", "approval|condition|branch|log|notification|mapping|provision|rest|role-request|resource-request|start-flow"),
                req("id", "the new activity's id ([A-Za-z_][A-Za-z0-9_-]*)"), req("after", "insert after this activity"),
                opt("via", "the outgoing link type of --after to consume (when it has more than one)"),
                opt("to", "required when --after is a branch: the leg's target (the branch's merge, or reachable from it)"),
                opt("on-denied", "an approval's 'denied' link target (default: the process's 'Workflow Status Denied' mapping, created as 'status_denied' → finish if absent)"),
                opt("status", "mapping only: approved|denied — the mapping sets flowdata.IDM_COMPLETED_APPROVAL_STATUS (what Request History shows)"),
                opt("on-false", "a condition's 'false' link target (default: the finish activity)"),
                opt("name", "display name: lang~Text or plain text (= en); default a sensible label"),
                opt("addressee", "an approval's addressee expression (default: the stock manager lookup)"),
                opt("form", "an approval's approval form to bind (default: the driver's stock 'Approval Form' if present; without one the dashboard cannot open the task)"),
                opt("timeout", "an approval's timeout in milliseconds (default: the stock 8-day timeout); rest's timeout in milliseconds"),
                opt("ontimeout", "an approval's ontimeout (default: denied)"),
                opt("expression", "a condition's boolean ECMAScript expression"),
                opt("message", "a log activity's message expression (default: a quoted 'Activity <id>' literal)"),
                opt("template", "a notification activity's notify template DN"),
                opt("entitlement-dn", "a provision activity's entitlement DN (quoted as a literal)"),
                opt("entitlement-param", "a provision activity's entitlement parameter (default '')")}, flowIntegrationArgs, new Arg[] {driver}));

        register("flow.activity.set", "change an existing workflow activity's attributes/addressee/expression/message/template/entitlement",
            a -> new FlowOps.ActivitySet(a.get("driver"), a.get("prd"), a.get("id"), a.get("name"), repeatable(a.get("attr")),
                a.get("addressee"), a.get("timeout"), a.get("ontimeout"), a.get("expression"), a.get("message"),
                a.get("template"), a.get("entitlement-dn"), a.get("entitlement-param"), a.get("approver-type"),
                a.get("form"), flowExtra(a)),
            concatArgs(new Arg[] {flowPrd, req("id", "the activity's id"), opt("name", "display name: lang~Text or plain text (= en)"),
                opt("form", "approval only: bind this approval form (declaration, form-binding, stock data items; replaces an existing binding)"),
                opt("attr", "name=value, repeatable (validated against known enums; activity-id is refused — use flow.activity.rename)"),
                opt("addressee", "replaces every addressee (repeat --addressee for more than one)"),
                opt("timeout", "milliseconds"), opt("ontimeout", "approved|denied|refused|timedout|error"),
                opt("expression", "a condition's expression"), opt("message", "a log activity's message expression"),
                opt("template", "a notify template DN"), opt("entitlement-dn", "provision only: entitlement DN (quoted as a literal)"),
                opt("entitlement-param", "provision only: entitlement parameter (quoted as a literal)"),
                opt("approver-type", "org-approver|group-approver|multiple-approver|quorum-approver")}, flowIntegrationArgs, new Arg[] {driver}));

        register("flow.activity.rename", "rename an activity id everywhere it is referenced (links, data-items, form-binding, expressions)",
            a -> new FlowOps.ActivityRename(a.get("driver"), a.get("prd"), a.get("id"), a.get("to")),
            flowPrd, req("id", "the activity's current id"), req("to", "the new id"), driver);

        register("flow.activity.remove", "remove an activity, reconnecting its incoming links to its primary successor",
            a -> new FlowOps.ActivityRemove(a.get("driver"), a.get("prd"), a.get("id")),
            flowPrd, req("id", "the activity's id (not start/finish/branch/merge)"), driver);

        register("flow.branch.add", "insert a branch/merge pair (add legs afterwards with flow.activity.add --after <branch> --to <merge>)",
            a -> new FlowOps.BranchAdd(a.get("driver"), a.get("prd"), a.get("id"), a.get("merge"), a.get("after"), a.get("via")),
            flowPrd, req("id", "the new branch activity's id"), req("merge", "the new merge activity's id"),
            req("after", "insert after this activity"), opt("via", "the outgoing link type of --after to consume"), driver);

        register("flow.branch.remove", "remove a branch/merge pair with a single leg (or none), reconnecting around them",
            a -> new FlowOps.BranchRemove(a.get("driver"), a.get("prd"), a.get("id")),
            flowPrd, req("id", "the branch activity's id"), driver);

        register("flow.link.add", "add a link between two activities",
            a -> new FlowOps.LinkAdd(a.get("driver"), a.get("prd"), a.get("from"), a.get("to"), a.get("type")),
            flowPrd, req("from", "source activity id"), req("to", "target activity id"),
            req("type", "forward|approved|denied|refused|timedout|success|fault|true|false|error"), driver);

        register("flow.link.remove", "remove a link between two activities",
            a -> new FlowOps.LinkRemove(a.get("driver"), a.get("prd"), a.get("from"), a.get("to"), a.get("type")),
            flowPrd, req("from", "source activity id"), req("to", "target activity id"),
            opt("type", "only remove a link of this type (default: any type between --from and --to)"), driver);

        register("flow.link.retype", "change a link's type",
            a -> new FlowOps.LinkRetype(a.get("driver"), a.get("prd"), a.get("from"), a.get("to"), a.get("type"), a.get("to-type")),
            flowPrd, req("from", "source activity id"), req("to", "target activity id"), req("type", "the link's current type"),
            req("to-type", "the new type"), driver);

        register("flow.data.set", "add or replace a raw data item on any activity's data-items block",
            a -> new FlowOps.DataSet(a.get("driver"), a.get("prd"), a.get("activity"), a.get("name"), a.get("source"),
                a.get("target"), a.get("type"), a.get("target-type")),
            flowPrd, req("activity", "the activity's id"), req("name", "the data item's name"),
            opt("source", "an ECMAScript source expression"), opt("target", "a flowdata target path"),
            opt("type", "string|boolean|integer|decimal|date|dn|binary|element (default: string)"),
            opt("target-type", "single-value|multi-value-list|multi-value-list-item"), driver);

        register("flow.data.remove", "remove a raw data item from an activity's data-items block",
            a -> new FlowOps.DataRemove(a.get("driver"), a.get("prd"), a.get("activity"), a.get("name")),
            flowPrd, req("activity", "the activity's id"), req("name", "the data item's name"), driver);

        register("flow.set", "change process-level attributes (version, process-type, flow-strategy, …)",
            a -> new FlowOps.SetProcess(a.get("driver"), a.get("prd"), a.get("version"), a.get("process-type"),
                a.get("flow-strategy"), a.get("default-completed-approval-status"), a.get("setnotify"),
                a.get("restrict-view"), a.get("generate-comments")),
            flowPrd, opt("version", "one of the engine's supported process versions"),
            opt("process-type", "Normal|RBAC|RBACSOD|Resource|Attestation|ResourceProvisioning|RoleProvisioning"),
            opt("flow-strategy", "SingleFlow|FlowPerMember|SingleFlowProvisionMembers"),
            opt("default-completed-approval-status", "approved|denied"),
            opt("setnotify", "true|false"), opt("restrict-view", "true|false"), opt("generate-comments", "true|false"), driver);

        register("policy.link", "link an artifact into a driver's policy set",
            a -> new ArtifactOps.Link(a.get("path"), a.get("driver"), setOf(a.get("set")),
                a.containsKey("at") ? ArtifactOps.Position.parse(a.get("at")) : null),
            req("path", "artifact path"), req("driver", "driver name"), req("set", "policy-set key"), at);

        register("policy.unlink", "remove an artifact from a driver's policy set",
            a -> new ArtifactOps.Unlink(a.get("path"), a.get("driver"), setOf(a.get("set"))),
            req("path", "artifact path"), req("driver", "driver name"), req("set", "policy-set key"));

        register("policy.reorder", "set the full order of a driver's policy set",
            a -> new ArtifactOps.Reorder(a.get("driver"), setOf(a.get("set")),
                Arrays.asList(a.getOrDefault("order", "").split("\n"))),
            req("driver", "driver name"), req("set", "policy-set key"),
            req("order", "the members in order (repeat --order <path> for each)"));

        // rules
        Arg rulePath = req("path", "path of a DirXML Script policy");
        Arg ruleId = req("rule", "the rule's <description>, or #n (1-based position)");
        Arg rulePos = opt("at", "first|last|after:<rule>|before:<rule> (default last)");
        register("rule.add", "insert a <rule> into a DirXML Script policy",
            a -> new RuleOps.Add(a.get("path"), contentOf(a), a.get("at")),
            rulePath, req("content-file", "file holding the <rule> XML"), rulePos);
        register("rule.delete", "remove a rule",
            a -> new RuleOps.Delete(a.get("path"), a.get("rule")), rulePath, ruleId);
        register("rule.move", "move a rule to another position",
            a -> new RuleOps.Move(a.get("path"), a.get("rule"), a.get("at")), rulePath, ruleId, rulePos);
        register("rule.disable", "set <rule disabled=\"true\"> — the engine skips it",
            a -> new RuleOps.SetDisabled(a.get("path"), a.get("rule"), true), rulePath, ruleId);
        register("rule.enable", "clear a rule's disabled flag",
            a -> new RuleOps.SetDisabled(a.get("path"), a.get("rule"), false), rulePath, ruleId);

        // entitlements (Track W step W4b; docs/entitlements.md)
        Arg entName = req("name", "the entitlement's name (cn)");
        Arg entDriver = opt("driver", "driver name (required for entitlement.add; needed for entitlement.set/remove when several drivers have one of that name)");
        Arg entDisplayName = opt("display-name", "display-name attribute");
        Arg entDescription = opt("description", "description attribute");
        Arg entMultiValued = opt("multi-valued", "flag: values/@multi-valued = true");
        Arg entConflict = opt("conflict", "union|priority (default: priority on entitlement.add)");
        Arg entValues = opt("values", "comma-separated static values -> <values><value>v</value>...</values>");
        Arg entDefFile = opt("definition-file", "a file holding a whole <entitlement> document, used verbatim instead of the other content flags");
        register("entitlement.add", "create a DirXML-Entitlement definition on a driver",
            a -> new EntitlementOps.Add(a.get("driver"), a.get("name"), a.get("display-name"), a.get("description"),
                a.containsKey("multi-valued"), a.get("conflict"), a.get("values"), a.get("definition-file")),
            entName, req("driver", "driver name"), entDisplayName, entDescription, entMultiValued, entConflict, entValues, entDefFile);
        register("entitlement.set", "change an existing entitlement's attributes/values, or replace its whole document with --definition-file",
            a -> new EntitlementOps.Set(a.get("driver"), a.get("name"), a.get("display-name"), a.get("description"),
                boolOrNull(a.get("multi-valued")), a.get("conflict"), a.get("values"), a.get("definition-file")),
            entName, entDriver, entDisplayName, entDescription, opt("multi-valued", "true|false"), entConflict, entValues, entDefFile);
        register("entitlement.remove", "delete an entitlement; refuses while any PRD's provision activity names it (--force does not override this)",
            a -> new EntitlementOps.Remove(a.get("driver"), a.get("name")),
            entName, entDriver);

        // AppConfig objects (docs/appconfig.md §9): generic, then roles, resources, entities
        Arg acDriver = opt("driver", "the User Application driver (needed when several drivers have an AppConfig)");
        Arg display = opt("display", "display name, lang=text or bare text for English (repeatable; merged per language)");
        Arg descr = opt("descr", "description, lang=text or bare text (repeatable; merged per language)");
        Arg owners = opt("owner", "owner DN (repeatable; '-' clears)");
        Arg approvers = opt("approver", "approver DN (repeatable; '-' clears)");
        register("appconfig.set", "set (replace all values of) or remove one attribute of any AppConfig object",
            a -> new AppConfigOps.Set(a.get("driver"), a.get("path"), a.get("attr"), a.get("value"), a.get("file"), a.containsKey("remove")),
            req("path", "object path under AppConfig (DirectoryModel/EntityDefs/user) or a unique name"),
            req("attr", "attribute name"), opt("value", "a value (repeatable for a multi-valued attribute; a bare localized text merges as English)"),
            opt("file", "read the value from this file (an XML attribute such as XmlData)"), opt("remove", "flag: remove the attribute"), acDriver);
        register("appconfig.add", "create any AppConfig object under an existing container",
            a -> new AppConfigOps.Add(a.get("driver"), a.get("path"), a.get("class"), a.get("aux"), a.get("attr")),
            req("path", "the new object's path under AppConfig"), req("class", "structural class (nrfNavItem, nrfRoleDefs, srvprvChoice …)"),
            opt("aux", "auxiliary classes, comma-separated"), opt("attr", "name=value (repeatable)"), acDriver);
        register("appconfig.remove", "delete an AppConfig object; refuses while it holds objects or anything names its DN",
            a -> new AppConfigOps.Remove(a.get("driver"), a.get("path")),
            req("path", "object path under AppConfig or a unique name"), acDriver);
        register("role.add", "create a role in the catalog (RoleConfig/RoleDefs/Level<n>/<category>), creating the category container",
            a -> new AppConfigOps.RoleAdd(a.get("driver"), a.get("name"), a.get("level"), a.get("category"), a.get("display"),
                a.get("descr"), a.get("owner"), a.get("approver"), a.get("quorum")),
            req("name", "role name (cn)"), req("level", "10 (permission), 20 (IT) or 30 (business)"),
            req("category", "category container under the level (System, Custom …); also the category key"),
            display, descr, owners, approvers, opt("quorum", "approval quorum"), acDriver);
        register("role.set", "change a role's names, descriptions, categories, owners, approvers, quorum or status",
            a -> new AppConfigOps.RoleSet(a.get("driver"), a.get("name"), a.get("display"), a.get("descr"), a.get("category"),
                a.get("owner"), a.get("approver"), a.get("quorum"), a.get("status")),
            req("name", "role name or path"), display, descr, opt("category", "category keys, comma-separated (replaces)"),
            owners, approvers, opt("quorum", "approval quorum"), opt("status", "nrfStatus (50 = active)"), acDriver);
        register("role.remove", "delete a role; refuses while anything names its DN",
            a -> new AppConfigOps.RoleRemove(a.get("driver"), a.get("name")), req("name", "role name or path"), acDriver);
        register("resource.add", "create a resource (RoleConfig/ResourceDefs/<category>), optionally bound to an entitlement",
            a -> new AppConfigOps.ResourceAdd(a.get("driver"), a.get("name"), a.get("category"), a.get("display"), a.get("descr"),
                a.get("entitlement"), a.get("param"), a.containsKey("multi"), a.get("owner"), a.get("approver")),
            req("name", "resource name (cn)"), req("category", "category container under ResourceDefs; also the category key"),
            display, descr, opt("entitlement", "DirXML-Entitlement DN the resource grants"), opt("param", "the entitlement parameter value"),
            opt("multi", "flag: allow multiple assignment"), owners, approvers, acDriver);
        register("resource.set", "change a resource's names, descriptions, categories, entitlement binding, flags, owners or approvers",
            a -> new AppConfigOps.ResourceSet(a.get("driver"), a.get("name"), a.get("display"), a.get("descr"), a.get("category"),
                a.get("entitlement"), a.get("param"), boolOrNull(a.get("multi")), boolOrNull(a.get("active")), a.get("owner"), a.get("approver")),
            req("name", "resource name or path"), display, descr, opt("category", "category keys, comma-separated (replaces)"),
            opt("entitlement", "entitlement DN ('-' unbinds)"), opt("param", "entitlement parameter"), opt("multi", "true|false"),
            opt("active", "true|false"), owners, approvers, acDriver);
        register("resource.remove", "delete a resource; refuses while anything names its DN",
            a -> new AppConfigOps.ResourceRemove(a.get("driver"), a.get("name")), req("name", "resource name or path"), acDriver);
        register("entity.add", "create a directory-abstraction entity (DirectoryModel/EntityDefs) with no attributes yet",
            a -> new AppConfigOps.EntityAdd(a.get("driver"), a.get("key"), a.get("object-class"), a.get("aux-class"), a.get("display"),
                a.get("search-root"), a.get("naming-attribute"), flagMap(a, AppConfigOps.ENTITY_FLAGS)),
            req("key", "entity key (cn)"), req("object-class", "LDAP object class"), opt("aux-class", "auxiliary classes, comma-separated"),
            display, opt("search-root", "search root DN or %user-root%"), opt("naming-attribute", "default cn"),
            opt("creatable", "true|false"), opt("editable", "true|false"), opt("removable", "true|false"), opt("viewable", "true|false"),
            opt("auto-query", "true|false"), acDriver);
        register("entity.set", "change an entity's display names, flags or search root",
            a -> new AppConfigOps.EntitySet(a.get("driver"), a.get("key"), a.get("display"), a.get("search-root"), flagMap(a, AppConfigOps.ENTITY_FLAGS)),
            req("key", "entity key"), display, opt("search-root", "search root"), opt("creatable", "true|false"), opt("editable", "true|false"),
            opt("removable", "true|false"), opt("viewable", "true|false"), opt("auto-query", "true|false"), acDriver);
        register("entity.remove", "delete an entity; refuses for a system entity or while anything names its DN",
            a -> new AppConfigOps.EntityRemove(a.get("driver"), a.get("key")), req("key", "entity key"), acDriver);
        register("entity.attr.add", "add an attribute to an entity definition",
            a -> new AppConfigOps.EntityAttrAdd(a.get("driver"), a.get("entity"), a.get("key"), a.get("ldap"), a.get("nds"), a.get("type"),
                a.get("display"), flagMap(a, AppConfigOps.ATTR_FLAGS)),
            req("entity", "entity key"), req("key", "attribute key"), req("ldap", "LDAP attribute name"), opt("nds", "NDS name (default: the LDAP name)"),
            opt("type", "String|Integer|Boolean|DN|Time|Binary|LocalizedString … (default String)"), display,
            opt("required", "true|false"), opt("multivalue", "true|false"), opt("editable", "true|false"), opt("readable", "true|false"),
            opt("searchable", "true|false"), opt("viewable", "true|false"), opt("hideable", "true|false"), opt("enabled", "true|false"), acDriver);
        register("entity.attr.set", "change an entity attribute's flags, type, LDAP name or display labels",
            a -> new AppConfigOps.EntityAttrSet(a.get("driver"), a.get("entity"), a.get("key"), a.get("ldap"), a.get("type"), a.get("display"),
                flagMap(a, AppConfigOps.ATTR_FLAGS)),
            req("entity", "entity key"), req("key", "attribute key"), opt("ldap", "LDAP attribute name"), opt("type", "type"), display,
            opt("required", "true|false"), opt("multivalue", "true|false"), opt("editable", "true|false"), opt("readable", "true|false"),
            opt("searchable", "true|false"), opt("viewable", "true|false"), opt("hideable", "true|false"), opt("enabled", "true|false"), acDriver);
        register("entity.attr.remove", "remove an attribute from an entity definition",
            a -> new AppConfigOps.EntityAttrRemove(a.get("driver"), a.get("entity"), a.get("key")),
            req("entity", "entity key"), req("key", "attribute key"), acDriver);

        // GCVs
        register("gcv.set", "set a GCV's value where the driver's scope defines it (or create it with --define)",
            a -> new GcvOps.Set(a.get("driver"), a.get("name"), a.getOrDefault("value", ""),
                a.get("define"), a.get("display-name")),
            req("name", "GCV name"), req("value", "the value"), driver,
            opt("define", "create the GCV when absent: its type (string|boolean|integer|dn|enum|…)"),
            opt("display-name", "display name for --define (default: the name)"));
        register("gcv.delete", "remove a GCV definition; refuses while any policy reads it",
            a -> new GcvOps.Delete(a.get("driver"), a.get("name")),
            req("name", "GCV name"), driver);

        // filter
        Arg drv = req("driver", "driver name");
        Arg cls = req("class", "class name (NDS name)");
        Arg pub = opt("publisher", "sync|ignore|notify|reset");
        Arg sub = opt("subscriber", "sync|ignore|notify|reset");
        register("filter.set-class", "add a class to the driver filter, or change its channel settings",
            a -> new ConfigOps.FilterSetClass(a.get("driver"), a.get("class"), a),
            drv, cls, pub, sub, opt("publisher-create-homedir", "true|false"), opt("publisher-track-template-member", "true|false"));
        register("filter.set-attr", "add an attribute to a filter class, or change its settings",
            a -> new ConfigOps.FilterSetAttr(a.get("driver"), a.get("class"), a.get("attr"), a),
            drv, cls, req("attr", "attribute name (NDS name)"), pub, sub, opt("merge-authority", "default|edir|app|none"),
            opt("publisher-optimize-modify", "true|false"), opt("subscriber-optimize-modify", "true|false"));
        register("filter.remove-class", "remove a class (and its attributes) from the filter",
            a -> new ConfigOps.FilterRemoveClass(a.get("driver"), a.get("class")), drv, cls);
        register("filter.remove-attr", "remove an attribute from a filter class",
            a -> new ConfigOps.FilterRemoveAttr(a.get("driver"), a.get("class"), a.get("attr")),
            drv, cls, req("attr", "attribute name"));

        // schema map
        register("schema-map.set", "map a class (--nds-class/--app-class), an attribute within it (add --nds-attr/--app-attr), or a top-level attribute (--nds-attr/--app-attr alone)",
            a -> new ConfigOps.SchemaMapSet(a.get("driver"), a.get("nds-class"), a.get("app-class"), a.get("nds-attr"), a.get("app-attr")),
            drv, opt("nds-class", "eDirectory class name"), opt("app-class", "application class name"),
            opt("nds-attr", "eDirectory attribute name"), opt("app-attr", "application attribute name"));
        register("schema-map.remove", "remove a class mapping, an attribute mapping within a class, or a top-level attribute mapping",
            a -> new ConfigOps.SchemaMapRemove(a.get("driver"), a.get("nds-class"), a.get("nds-attr")),
            drv, opt("nds-class", "eDirectory class name"), opt("nds-attr", "eDirectory attribute name"));

        // driver settings
        register("driver.set", "set a driver setting: shim-class, shim-auth-server, shim-auth-id, param:<shim parameter>, engine:<engine control value>",
            a -> new ConfigOps.DriverSet_(a.get("driver"), a.get("key"), a.get("value")),
            drv, req("key", "shim-class|shim-auth-server|shim-auth-id|param:<name>|engine:<name>"), req("value", "the value"));

        // drivers
        register("package.install", "install a package jar onto a driver (type 2) or the Library (type 3), Designer's way",
            a -> new com.pointblue.dirxml.dev.packages.PackageInstall(
                com.pointblue.dirxml.dev.packages.PackageInstall.jarsOf(a.get("jar"), a.get("catalog"), a.get("package")),
                a.get("driver"), readAnswers(a.get("answers")), !"true".equals(a.get("new-driver"))),
            opt("jar", "the package jar(s), comma-separated, in install order (or give --catalog and --package)"),
            opt("catalog", "a package catalog directory (jars/<SHORT>/<SHORT>_<ver>.jar)"),
            opt("package", "SHORT[_version][,SHORT[_version]…] in the catalog (newest version when omitted); base first"),
            opt("driver", "the driver to install onto (omit for a driver-set package into the Library)"),
            opt("answers", "name=value file answering the package prompts (see package.prompts)"),
            opt("new-driver", "true when the driver was just created (prompts run in driver-creation mode)"));
        register("package.adopt", "write the installed-package records from the objects' package stamps (trees imported from a vault or a project)",
            a -> new com.pointblue.dirxml.dev.packages.PackageAdopt(a.get("driver"), catalogOf(a.get("catalog"))),
            opt("driver", "one driver (default: every driver and the Library)"),
            opt("catalog", "a package catalog, to resolve project-style package ids to names and versions"));
        register("package.uninstall", "remove an installed package; refuses while another installed package depends on it, or while a base package's features remain, unless --all",
            a -> new com.pointblue.dirxml.dev.packages.PackageUninstall(a.get("driver"), a.get("package"),
                a.containsKey("yes"), a.containsKey("all"), pathOrNull(a.get("catalog"))),
            opt("driver", "the driver the package is installed on (omit for a driver-set package in the Library)"),
            req("package", "the installed package's SHORT name"),
            opt("yes", "remove customized objects too (flag; without it they're refused, named)"),
            opt("all", "also remove packages that depend on this one, or (for a base package) its remaining features"),
            opt("catalog", "a package catalog, to read other installed packages' declared dependencies"));
        register("package.upgrade", "replace an installed package with another version (--downgrade for an older one; same mechanics)",
            a -> new com.pointblue.dirxml.dev.packages.PackageUpgrade(a.get("driver"),
                com.pointblue.dirxml.dev.packages.PackageInstall.jarOf(a.get("jar"), a.get("catalog"), a.get("package")),
                readAnswers(a.get("answers")), a.containsKey("yes"), a.containsKey("downgrade"), pathOrNull(a.get("catalog"))),
            opt("driver", "the driver the package is installed on (omit for a driver-set package in the Library)"),
            opt("jar", "the new version's package jar (or give --catalog and --package)"),
            opt("catalog", "a package catalog directory (jars/<SHORT>/<SHORT>_<ver>.jar)"),
            opt("package", "SHORT_version in the catalog, the version to move to"),
            opt("answers", "name=value file answering the package's prompts (existing driver values pre-fill them)"),
            opt("yes", "remove customized objects the new version dropped (flag; without it they're refused, named)"),
            opt("downgrade", "moving to an older version (flag; informational — the mechanics are identical)"));
        register("driver.add", "add a driver: from a driver export, as a copy of another driver, or blank",
            a -> new DriverOps.Add(a.get("name"),
                a.get("from-export") == null || a.get("from-export").isBlank() ? null : Paths.get(a.get("from-export")),
                a.get("source-driver") != null ? a.get("source-driver") : a.get("copy-of"),
                a.get("copy-of") != null, a.get("shim-class"), a.get("auth-server"), a.get("auth-id"))
                .withPackages(a.get("packages") == null && a.get("package") == null ? null
                    : com.pointblue.dirxml.dev.packages.PackageInstall.jarsOf(a.get("packages"), a.get("catalog"), a.get("package")),
                    readAnswers(a.get("answers"))),
            req("name", "the new driver's name"),
            opt("from-export", "a driver export (Designer, with referenced policies) to merge in as this driver"),
            opt("source-driver", "with a driver-set export: which driver to take"),
            opt("copy-of", "an existing driver to clone"),
            opt("packages", "package jars, comma-separated, base first: the driver is built from the base package and the set installed"),
            opt("catalog", "with --package: the package catalog directory"),
            opt("package", "with --catalog: SHORT[_ver][,…] to install, base first"),
            opt("answers", "name=value file answering the packages' prompts"),
            opt("shim-class", "for a blank driver: the shim class"),
            opt("auth-server", "for a blank driver: the authentication server/URL"),
            opt("auth-id", "for a blank driver: the authentication id"));

        // mapping tables
        Arg table = req("path", "mapping-table resource path");
        Arg keyCol = opt("key-column", "column that identifies the row (default: the first)");
        register("mapping-table.set-row", "add or update a row, keyed by --key-column (default: the first column)",
            a -> new ConfigOps.TableSetRow(a.get("path"), a.get("key-column"), ConfigOps.columnValues(a.get("col"))),
            table, req("col", "name=value (repeat --col for each column)"), keyCol);
        register("mapping-table.delete-row", "delete the row whose key column has --key",
            a -> new ConfigOps.TableDeleteRow(a.get("path"), a.get("key-column"), a.get("key")),
            table, req("key", "the key column's value"), keyCol);
        register("mapping-table.add-column", "add a column (empty in every existing row)",
            a -> new ConfigOps.TableAddColumn(a.get("path"), a.get("column"), a.get("type")),
            table, req("column", "column name"), opt("type", "nocase|case|numeric (default nocase)"));
    }

    private static Scope scopeOf(Map<String, String> a) {
        String s = a.get("scope");
        if (s == null || s.isBlank()) {
            return a.get("driver") == null || a.get("driver").isBlank() ? Scope.LIBRARY : Scope.DRIVER;
        }
        return Scope.byKey(s);
    }

    private static PolicySet setOf(String key) {
        return key == null || key.isBlank() ? null : PolicySet.byKey(key);
    }

    private static String contentOf(Map<String, String> a) throws IOException {
        String f = a.get("content-file");
        return f == null || f.isBlank() ? null : Files.readString(Paths.get(f), StandardCharsets.UTF_8);
    }

    private static String scriptFileOf(String file) throws IOException {
        return file == null || file.isBlank() ? null : Files.readString(Paths.get(file), StandardCharsets.UTF_8);
    }

    /** {@code "true"}/{@code "false"} -> a boolean; anything else (including absent) -> null (unchanged). */
    /** The {@code true|false} flags among {@code names} that were given, as a map. */
    private static java.util.Map<String, String> flagMap(java.util.Map<String, String> a, List<String> names) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String n : names) {
            String v = a.get(n);
            if (v != null && !v.isBlank()) {
                out.put(n, v.trim().toLowerCase());
            }
        }
        return out;
    }

    private static Boolean boolOrNull(String v) {
        if (v == null) {
            return null;
        }
        if (v.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** A repeatable {@code --arg} (joined with {@code \n} by the CLI) split back into its values; empty list when absent. */
    private static List<String> repeatable(String joined) {
        if (joined == null || joined.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(joined.split("\n")));
    }

    private static Arg[] concatArgs(Arg[]... groups) {
        List<Arg> out = new ArrayList<>();
        for (Arg[] g : groups) {
            out.addAll(Arrays.asList(g));
        }
        return out.toArray(new Arg[0]);
    }

    /** Every {@code flow.activity.add}/{@code .set} flag for the rest/role-request/resource-request/start-flow
     *  kinds (Track W step W3), collected into the {@code Map<String, List<String>>} shape {@link
     *  com.pointblue.dirxml.dev.edit.FlowOps.ActivityAdd}/{@link com.pointblue.dirxml.dev.edit.FlowOps.ActivitySet}
     *  take as {@code extra} — a flag never given contributes no entry (repeated occurrences already arrive
     *  {@code \n}-joined, courtesy of {@link EditCli}). */
    private static Map<String, List<String>> flowExtra(Map<String, String> a) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String key : new String[] {
            "protocol", "host", "port", "path", "method", "content", "content-type", "accept", "authorization",
            "status-to", "content-to", "content-type-to", "header",
            "role", "target", "target-type", "action", "description", "effective-date", "expiration-date",
            "correlation-id", "resource", "param", "process", "recipient"}) {
            List<String> v = repeatable(a.get(key));
            if (!v.isEmpty()) {
                out.put(key, v);
            }
        }
        return out;
    }

    /** Check required args are present; returns the problem or null. */
    public static String missing(Spec spec, Map<String, String> args) {
        List<String> m = new ArrayList<>();
        for (Arg arg : spec.args) {
            if (arg.required && (args.get(arg.name) == null || args.get(arg.name).isBlank())) {
                m.add("--" + arg.name);
            }
        }
        return m.isEmpty() ? null : "missing " + String.join(", ", m);
    }

    public static String usage(Spec spec) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(spec.name).append(" <tree>");
        for (Arg a : spec.args) {
            sb.append(a.required ? " --" + a.name + " <v>" : " [--" + a.name + " <v>]");
        }
        sb.append(" [--dry-run] [--force] [--json]\n      ").append(spec.help).append('\n');
        for (Arg a : spec.args) {
            sb.append(String.format("      --%-14s %s%n", a.name, a.help));
        }
        return sb.toString();
    }
}
