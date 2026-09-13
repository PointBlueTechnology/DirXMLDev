# Track W — Workflow design (the PRD's `<process>`) — design note

Status: **P0 findings done (2026-09-13), design proposed, awaiting Jerry's
decisions (§7).** Nothing built yet.

Roles and resources are *not* in scope: they are managed in the Identity
Applications, not in Designer or the vault's AppConfig (Jerry, 2026-09-13).

## 1. What a workflow is (facts, P0)

A workflow is the `<process>` element of a provisioning request definition
(PRD). It is the third of the PRD's three XML parts (Track P): `definition`
(`<prov-req-defn>`, vault `XmlData`, which contains the process as a child),
`request` (`<provision-request>`, vault `srvprvRequestXML`) and `process`
(vault `srvprvProcessXML`). Designer's `.prd` is the union of the three. The
Identity Applications' workflow engine loads `srvprvProcessXML`; Designer
imports from the vault by reading `srvprvRequestXML` and `srvprvProcessXML`
and inserting them into `XmlData`'s skeleton (`PRDefImportDeployHandler`).

### 1.1 The grammar (engine-side, the one that matters)

Sources: `workflow.jar` from the idm254 identityapplications pod (JAXB
binding `com.novell.soa.af.impl.model.binding`, decompiled in the session
scratchpad only), `IDMfw.jar` `conf/schema/ApprovalProcess3_5_1.xsd` (the
newest schema shipped anywhere), the 39 stock PRDs on idm254 (17 at
`version="3.6.1"`, 22 at `4.5.0`), Designer's `com.novell.prov.edit` DTD
(`prdef.dtd`, the `<prov-req-defn>` shell).

`<process id version setnotify formSrc restrict-view generate-comments
default-completed-approval-status process-type flow-strategy>` holds, in this
order: `legal-disclaimer*`, `import-script*`, `form*`, `form-binding*`,
`data-items*`, **`start-activity`** (exactly one), the activities, the
provisioning/binding activities, **`finish-activity`** (exactly one),
**`link+`**.

Activity elements the engine binds (`IProcessFlow`'s `@XmlElements`), each
with `activity-id` (required, unique), `audit`, `digital-signature-type`,
`legal-disclaimer-id` and `display-name xml:lang` children:

| element | engine attributes / children | seen in the 39 stock PRDs |
|---|---|---|
| `start-activity` | — | 39 |
| `user-activity` (approval) | `timeout ontimeout priority setnotify approver-type approver-condition approver-condition-expr exclude-request-principals attempts interval`; children `addressee+` (ECMAScript), `notify`/`confirm`/`reminder` (`template` + `map source/target`), `retry attempts interval` (own `addressee`) | 138 |
| `condition-activity` | `startNewThread`; child `expression` (ECMAScript → boolean) | 24 |
| `branch-activity` / `merge-activity branch-activity-id` | parallel section; one pair per process (engine check) | 12 / 12 |
| `log-activity audit` | children `author`, `message`, `comment` | 115 |
| `mapping-activity` | data items only (its `data-items` block does the work) | 41 |
| `notification-activity setnotify` | child `notify template` + maps | 1 |
| `provision-activity` | `entity-type category(entity|nonit_asset|entitlement|custom) operation(grant|revoke) deleteobject set-completed-approval-status` | 62 (all `entitlement`/`sys-entitlement-request`/`grant`) |
| `bind-role-activity action(APPROVED|DENIED)` | RBAC/RBACSOD process types only | 4 |
| `bind-resource-status-activity action` | Resource process type only | 4 |
| `integration-activity` | `timeout retry ontimeout wsdl-resource service-name`; `input-maps/output-maps/fault-maps` | 0 |
| `rest-activity` | `protocol host port path method contentTypeHeader acceptHeader authorizationHeader timeout trustManagers`; `http-headers` | 0 |
| `role-request-activity` | `target-type effective-date expiration-date request-description correlation-id sod-override-*`; `roles`, `targets` | 0 |
| `resource-request-activity` | `target-resource target-user request-description correlation-id target-param target-guid` | 0 |
| `start-correlated-flow-activity` | `processId`, `recipient` | 0 |
| `ig-catalog-request-activity`, `ig-sod-activity` | Identity Governance calls | 0 |

`link source target type` with `type ∈ {forward, approved, denied, refused,
timedout, success, fault, true, false, error}`; which types an activity may
emit is fixed per kind (engine check, §1.3). `data-items activity-id` +
`data-item name data-type(string|boolean|integer|decimal|date|dn|binary|
element) source|target target-type(single-value|multi-value-list|
multi-value-list-item) readonly designer-id` are the flowdata mappings Track
P already handles (`prd.map`). `form-binding activity-id form-id` binds an
approval form to a `user-activity`; the request form is bound in
`<provision-request>`.

Expressions (`addressee`, `expression`, `data-item source/target`, `map
source`, `message`) are **ECMAScript** run by `scriptengine.jar` with these
scope objects: `flowdata` (`get('path')` / `getObject`), `process`
(`getName(locale)`, `getTimestamp()`…), `initiator`, `recipient`, `IDVault`
(`get(dn,'user','manager')`, …), `GCV`, `RoleVault`, `NrfRequest`,
`NrfResourceRequest`, `AttestationRequest`, and one info object per activity
named by its id (`approval_A.getAction()`, `.getAddressee()`).

### 1.2 What the engine validates when it picks a PRD up

`ModelFactory.loadProcessFlow`: (1) the `version` attribute must be one of
3.5.0, 3.5.1, 3.6.0, 3.6.1, 3.7.0, 4.0.0, 4.0.1, 4.0.2, 4.0.2A, 4.5.0 —
**there is no XSD validation at runtime** (the `noNamespaceSchemaLocation=
"ApprovalProcess3_6_1.xsd"` on every stock process names a file that ships
nowhere; the name only appears in error messages); (2) JAXB binding (unknown
elements/attributes are ignored, enum attributes must be valid values);
(3) `ProcessFlowModel.validate()`, ten checks:

1. every `link` source and target is an existing activity id;
2. no `form-binding` and no `data-items` on the start activity, `data-items`
   activity ids exist;
3. RBAC/RBACSOD processes have both a `bind-role-activity APPROVED` and
   `DENIED`, and every link into finish comes from one of them (Resource
   processes likewise with `bind-resource-status-activity`);
4. link types by source kind: start → `forward|error` and no incoming links;
   user-activity → `approved|denied|refused|timedout|error`; integration →
   `success|fault|timedout|error`; branch/merge/log → `forward`; mapping and
   provision → `forward|error`; condition → `true|false|error` **and both a
   `true` and a `false` link**; finish → no outgoing links;
5. no dangling activity: every activity has an incoming link (except start)
   and an outgoing link (except finish);
6. a branch needs a merge whose `branch-activity-id` is a branch;
7. every `flowdata.` reference in a data-item source, notify/confirm map
   source, addressee or log message is `flowdata.get(`/`getObject(`;
   every user-activity has at least one addressee; `approver-type` valid;
   multiple/quorum approvers may not have `target` data items;
8. `notify`/`confirm`/`reminder` present ⇒ `template` non-empty;
9. `approver-condition` and `approver-condition-expr` are mutually exclusive;
10. (UI validate only) a `user-activity`/`integration-activity` with
    `ontimeout` has an outgoing link of that type.

That is the whole runtime contract. Our checker can be engine-faithful.

### 1.3 Designer's side

- Designer's flow editor **auto-lays-out** the diagram (GEF
  `CompoundDirectedGraphLayout`); no coordinates are stored in the `.prd`
  or the digest. An as-code workflow needs no layout data.
- `xml-data/design-params` is a legacy design-time block (`MergeIManager`
  "extracts" addressees, log messages, timeouts-with-units, prov-resource and
  entitlement items into it and "merges" them back). The 22 newer template
  PRDs carry it almost empty (no `links`, no `user-activity` mirrors) and
  Designer opens them fine; the 17 older ones mirror the process. Rule for
  us: keep whatever the template/copy carries, never mirror ourselves;
  Designer regenerates it when it saves (`clearDesignParams`).
- Designer's own project validation of a PRD is thin
  (`PRDefTypeFlowValidator`: category, entity and choice references); the
  real gate is the engine's (1.2) at deploy/pickup, plus the request-side
  binding rules Track P already implements (`BindingSync`).
- Designer "deploys" a PRD as `srvprvRequestXML`, `srvprvProcessXML` and
  `XmlData` — exactly what `VaultMapping.prdAttributes` writes (Track P,
  proven live).

## 2. What exists today (Track P)

`prd.add --from-template` copies a stock template PRD (NoApproval,
SingleApproval, 2–5 step serial/parallel, quorum…) and rebinds forms;
`prd.map` maps form fields to flowdata; the PRD deploys and the Identity
Applications run it (proven on idm254 — it failed only on the template's
`{enter Entitlement DN here}` placeholder). So today an agent can pick a
stock approval shape but cannot change the flow, the approvers, timeouts,
notifications, conditions or the provisioning target.

## 3. Options

**A. Template composition only** — parameters on top of the stock templates:
set approver, timeout, notify template, entitlement DN, category. Cheap,
covers the common "N-step approval that grants an entitlement" request, but
every real client workflow I have seen adds a condition, a log, a second
provision step or a REST call; A alone stops there.

**B. Typed flow operations on the `<process>` DOM (recommended core)** —
the as-code file stays the vault's own XML (definition/request/process, as
now); the agent edits it through validated operations, one per concept:
`flow.activity.add --kind approval|condition|branch|log|notification|
mapping|provision|rest|role-request|resource-request|start-flow --id X
--after Y [--link-type …]` (inserts into the link graph, creates the
display-name, defaults from the engine's schema), `flow.activity.set`
(attributes, addressee, timeout, notify template, expression, entitlement
DN…), `flow.activity.remove` (re-links around it), `flow.link add|remove|
retype`, `flow.branch` (branch+merge around a set of activities),
`flow.set` (process attributes), plus `prd.map` for data items and
`form.*`/`prd.*` from Track P for the forms. A `FlowCheck` validator mirrors
§1.2 exactly (engine checks) plus the attribute enums from the 3.5.1 XSD
and the JAXB names, so `validate` fails before deploy for anything the
engine would refuse. `prd.flow <tree> <prd>` renders the graph (text +
Mermaid) so a human can review a change without Designer.

**C. Higher-level authoring** — a compact flow description (e.g. a YAML
"steps" list) compiled into `<process>`. Attractive for agents, but it is a
second source of truth and a second grammar to keep faithful; deferred
until B has shown which shapes recur.

Recommendation: **B, with A's parameters as the first operations**
(they are `flow.activity.set` on a template copy), C later if wanted.

## 4. Build order (proposed)

- **W1 Model + check + view**: `Flow` model over the process DOM
  (activities, links, data items, bindings as typed views; DOM stays the
  store), `FlowCheck` (§1.2 engine checks + enum/attribute checks + "all
  ids unique", "expression references a known activity id / bound field",
  "entitlement DN placeholder left"), wired into `validate`; `prd.flow`
  text/Mermaid rendering. Calibrated on the 39 stock PRDs (0 errors) and
  on deliberately broken copies.
- **W2 Typed operations** (option B) on the common set — approval,
  condition, branch/merge, log, notification, mapping, provision — plus
  `flow.set`; each is a transaction like Track P's (load → apply → validate
  → write); `Transaction.touched` + package/customization stamps as today.
- **W3 Integration activities**: rest, role-request, resource-request,
  start-correlated-flow (schema known, no stock examples — needs one real
  case to calibrate against).
- **W4 Live proof on idm254**: author a PRD from NoApproval → add a
  condition + a two-step serial approval + a log + provision of a *real*
  entitlement (needs a scratch entitlement on the lab's UA driver or an
  existing one), deploy, request it in the Identity Applications, approve
  as the addressee, see the entitlement granted, then remove everything.
- **W5 Designer acceptance** (human): open the authored PRD in
  `Designer-modernized`, diagram renders (auto-layout), validation clean,
  deploy offered. Designer round trip via `export-project` already writes
  PRDs (Track P step 6).

## 5. Facts that shape the design

- The vault XML is the only faithful store; Designer keeps nothing else
  (no layout). Editing the DOM is safe as long as element order follows
  §1.1 (JAXB is order-tolerant on read, the XSD is not — keep the schema
  order so Designer's XSD-based tooling, if any, stays happy).
- `version` must stay one the engine accepts; new PRDs should carry
  `4.5.0` (what 4.10.1's own templates carry) and
  `noNamespaceSchemaLocation="ApprovalProcess3_6_1.xsd"` as the templates do.
- Display names are per-language on every activity; the typed ops write
  `en` (plus the tree's declared languages if `form.localize --sync`-style
  behaviour is wanted later).
- Expressions are opaque ECMAScript to us; W1 checks only what the engine
  checks (flowdata form, addressee present) plus a syntax compile through
  the same Rhino path `FormCheck` uses for form scripts.

## 6. Test data

39 stock PRDs (`~/IdeaProjects/DirXMLDev-e2e/tree-idm254`, also on test11);
the engine and Designer sources in the session scratchpad (`wf/`,
`wf/dsn/`), never committed; the 3.5.1 XSD summary is in this note.

## 7. Decisions for Jerry

1. **Option B (typed flow operations on the vault XML) as the core, with
   A's template parameters as the first operations; C deferred?**
2. **Activity kinds for W2**: approval, condition, branch/merge, log,
   notification, mapping, provision — in? Anything to add or drop?
3. **W4 needs a real entitlement on idm254** to grant. Is there one on the
   lab's User Application driver I may use (or may I create a scratch
   entitlement on a driver there and delete it afterwards)?
4. **Approving as the addressee** in W4 means acting in the Identity
   Applications as another user (or as uaadmin with the manager mapping
   pointed at uaadmin). Fine to route the approval to uaadmin for the test?
5. Build order W1 → W2 → W4 → W5, with W3 after the live proof?
