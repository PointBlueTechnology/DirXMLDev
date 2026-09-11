# Spike P0 — JSON forms: where they live, what they are, how Designer edits them (2026-09-11)

Read-only reconnaissance for Track P (provisioning forms). Sources: the test
vault's User Application driver (IDM 4.8.7 schema and objects), Designer
4.10.1's provisioning plugins (`com.novell.prov.form`, `com.mf.formbuilder`,
`com.mf.mac.cocoa.formbuilder`; decompiled into the scratchpad only), the
`test11` and `XFDEMO1` Designer projects, and the test server `idm-ig4`.
Nothing was written to any vault or project.

## 1. Vault objects

```
cn=AppConfig,cn=User Application Driver,cn=driverset1,o=system   srvprvAppConfig
  cn=WorkflowForms                                               srvprvJSONForms
    cn=WorkflowRequestForms                                      srvprvJSONForms
      cn=Help-desk Request Form …                                srvprvJSONForm
    cn=WorkflowApprovalForms                                     srvprvJSONForms
    cn=WorkflowTemplateForms                                     srvprvJSONForms
  cn=RequestDefs                                                 srvprvRequestDefs
    cn=HelpdeskTicket …                                          srvprvRequest   (the PRD)
```

Schema (from `cn=schema`):

| Class / attribute | Definition |
|---|---|
| `srvprvJSONForm` | `MUST cn MAY srvprvJSONData`, contained by `srvprvJSONForms`, not a container |
| `srvprvJSONForms` | `MUST cn`, contained by `srvprvJSONForms` or `srvprvAppConfig` |
| `srvprvJSONData` | octet string (syntax …121.1.5), single-valued — **the whole form as one JSON document** |
| `srvprvRequest` | `MUST cn, srvprvStatus, srvprvFlowStrategy, srvprvGrant, srvprvRevoke, srvprvCategoryKey, srvprvLocalizedNames, srvprvLocalizedDescrs` `MAY description, srvprvEntitlementRef, XmlData, srvprvRequestXML, srvprvProcessXML, srvprvProcessType, srvprvWorkflowData` |

The 11 stock forms and 39 stock PRDs on the test vault all carry
`DirXML-pkgGUID` / `DirXML-pkgAssociationId` / `DirXML-pkgChecksum` from the
User Application base package (`novluabase 4.8.0`), exactly like packaged
policies. Designer marks the 11 stock forms protected/read-only by *name*
(a hard-coded list in `FormImportDeploy`), not by package stamp.

## 2. The form document

`srvprvJSONData` is a **Form.io** form definition (the form builder is an
Angular wrapper around the Form.io builder) with NetIQ extensions:

```json
{ "components": [ … ],           Form.io component tree (type/key/label/input/validate/conditional/logic/…)
  "page": 0, "display": "form",  display = "form" | "workflowWizard" (the Create Workflow Form)
  "title": "HelpDesk Ticket",
  "inlinescripts": "",           ECMAScript run by the renderer
  "externalScripts": [],
  "localization": { "en": { "Title": "Title", … }, "fr": …, … 15 languages } }
```

Component types seen across the 11 stock forms (246 components): standard
Form.io `textfield`, `textarea`, `select`, `checkbox`, `radio`, `datetime`,
`button`, `columns`/`column`, `panel`, `container`, `tabs`, `datagrid`,
`htmlelement`; NetIQ custom `title`, `labelelement`, `dn_display`,
`dynamic_entity`, `data_item_mapping`, `dataItemMappingTextField`, `tree`,
`permission_requestDN` (these are the only custom types registered in the
builder bundle). Builder-generated components are verbose (~40 keys each,
mostly defaults). Stock documents are single-line JSON.

## 3. The PRD side

A PRD object splits its definition over three attributes; Designer's `.prd`
file is their union:

| Attribute | Content | Designer `.prd` |
|---|---|---|
| `XmlData` | `<prov-req-defn …>` with display names/descriptions, `<xml-data>`, `<process>` | the whole file |
| `srvprvRequestXML` | `<provision-request formSrc="1" version="3.6.1">` — `<form-binding form-id="…">` listing the request form's fields (`name`, `data-type`, `control-type`) and `<data-item>`s mapping each field to `flowdata.…` | inline as `<provision-request>` |
| `srvprvProcessXML` | `<process formSrc="1">` — activities, links, per-activity `<data-items>`, approval `<form-binding activity-id="…" form-id="…">` | inline as `<process>` |

`formSrc="1"` marks a JSON-forms PRD; classic PRDs (the `Template*_TA/_TD`
templates) have no `formSrc` and carry XForms inline. **A form is referenced
by name** (`form-id` = the `srvprvJSONForm` cn); the binding's field list is
Designer's copy of the form's `key`/`type` pairs, and `request-data-items`
derives the flowdata targets from them. Renaming or adding a field in the
form therefore requires updating the PRD's binding and data items — Designer
does this when the form is saved from its own UI. Designer records the
dependency in the PRD's `.digest` (`digest-dependency object-type="srvprvJSONRequestForm"`).

**How Designer keeps a binding in step with the form** (decompiled
`com.novell.prov.prd.ui`: `JSONFormControlPage`, `util/json/ParseJSON`,
`JSONFormDataType` + its `FormDataConfig.json`, `workflow/model/Activity`
`synchronizeDataItemsWithJSONForm`, `UserActivity.doMapAll`; calibrated on the
stock PRDs of 4.8.7, 4.10.1 and test11 — 9 of 11 stock forms re-sync as exact
no-ops, the other two are stale stock bindings):
- `ParseJSON` collects every JSON object anywhere in the document that has a
  string `key` and `type` (first wins per key) plus its `multiple` flag —
  containers, columns and buttons included.
- The **request form's** `<form-binding><content>` fields are rebuilt on
  selection/save from those items whose type is in `FormDataConfig.json`
  (33 distinct types: textfield/textarea/text/select/radio/tree/email/url/
  phoneNumber/day/htmlelement/title/labelelement/tags/hidden/file/signature/
  modaledit → string; number/currency → decimal; checkbox → boolean; time →
  time; datetime → date; dynamic_entity/dnquery/dn_display/permission_requestDN
  → dn; selectboxes/survey/datagrid/editgrid/container/datamap → jsonobject),
  skipping the key `apwaComment`; buttons are not in the map (the stock PRDs
  still carry `button` fields from an older Designer).
- An **approval activity's** `<form-binding activity-id form-id>` under
  `<process>` carries no field list — it is a reference.
- **Data items** (`request-data-items`, `<data-items activity-id>`) are the
  persisted *mappings*: the in-memory list holds every non-button field, but
  only mapped items (a `target`/`source`) are written, so stock PRDs bind
  fields with no data item (e.g. `subHeading`). Map-All builds
  `flowdata.<activityId>/<form id with spaces → _>/<key>`, skipping
  `htmlelement`; `target-type` is `multi-value-list` when `multiple` is true.

Schemas exist for validation: `ProvisioningRequestDefn.xsd` (1,233 lines,
`com.novell.prov.prd.ui/model/`) and `prdef.dtd` (`com.novell.prov.edit`).

## 4. Designer's project layout and mapping

```
Model/Provisioning/AppConfig/
  WorkflowForms/WorkflowRequestForms/<name>.formRequest    (+ <name>.digest)
  WorkflowForms/WorkflowApprovalForms/<name>.formApproval
  WorkflowForms/WorkflowTemplateForms/<name>.formTemplate
  RequestDefs/<name>.prd                                    (+ <name>.digest)
```

- `<name>.formRequest` is **byte-identical** to the vault's `srvprvJSONData`
  (checked on `Help-desk Request Form`). Deploy reads the file with
  `Misc.readFully` and writes it as the attribute; import writes the attribute
  bytes to the file (`FormImportDeploy`). No transformation either way.
- Digest items carry `guid`, `dirguid`, `dirrev`, `package-id`,
  `pkg-assoc-id`, `pkg-checksum`, `protected`, `readonly`, `modstamp`, and the
  type `srvprvJSONRequestForm` / `srvprvJSONApprovalForm` /
  `srvprvJSONTemplateForm` (derived from the container name, not the class).

## 5. How Designer edits a form: an external Electron app

Designer does not embed a form editor. The `.formRequest` file is handed to a
separate program:

```
<plugins>/com.mf.mac.cocoa.formbuilder_4.0.0.<ts>/lib/FormBuilder.app/Contents/MacOS/FormBuilder
    --filepath=<absolute path of the .formRequest/.formApproval/.formTemplate file>
    --locale=en_US
    [--service=<ServiceRegistry.json>]            optional; Linux adds --no-sandbox
```

(`FormCreateWizard.performFinish`, `ProcessBuilder`.) `FormBuilder.app` is an
unsigned Electron 4.10.1 app (925 MB; Windows `FormBuilder.exe` and Linux
`formbuilder` variants exist as sibling plugins). Its `main.js` shows the whole
contract:

- `loadFile` reads the `--filepath` file (any extension; the open/save dialogs
  filter on `*.form`) and `saveFile` writes the JSON back to the same path.
  The comment `// to support standalone builder` and the `open-file` handler
  confirm it is meant to run without Designer.
- `--service=ServiceRegistry.json` supplies `{"FormsBackendUrl":
  "https://<forms server>/WFHandler"}` — the workflow engine — used only for
  online features (OAuth login, entity/DAL lookups, preview against a server).
  With the placeholder file it runs offline.
- `commons/swagger.json` and `commons/macros.json` feed the REST-call and
  macro pickers; both are static files inside the app.
- Gatekeeper: the bundle carries `com.apple.quarantine` and no signature, so a
  launch outside Designer shows "cannot be verified" (Jerry hit this; Designer
  only fixes execute bits, via `Files.walk(...).setExecutable`). Fix once per
  install: `xattr -dr com.apple.quarantine <FormBuilder.app>` plus
  `chmod -R a+x <FormBuilder.app>`.

## 6. Server side (idm-ig4)

Identity Applications are installed (`IDMProv`, `idmdash`, `workflow.war`,
`osp`, `sspr` under `/opt/netiq/idm/apps/tomcat/webapps`) but **Tomcat is not
running**. What runs: the **IGA Form Renderer** (`IgaFormRenderer.sh`, a Go
binary on port 3000 serving the Angular renderer from
`/opt/netiq/idm/apps/sites/forms`) behind nginx on 8600. The renderer shows
forms to end users by fetching them from the workflow engine (`/WFHandler`),
so a live preview through the vendor stack needs Tomcat up. Runtime pickup of
a deployed form/PRD change (cache flush or not) is untested for the same
reason.

### 6a. Second test system: idm254 (IDM 4.10.1, 2026-09-11)

`idm254-engine.pointbluetech.com` (eDirectory 9.3.3, `cn=driverset1,o=system`,
four running drivers: User Application, Role and Resource, MSGW, DCS; User
Application base package `NOVLUABASE 4.10.1.20250606222933`) with the
Identity Applications at `https://idm254.pointbluetech.com` (`/IDMProv/` →
login redirect, `/idmdash/` 200, `/forms/` = IGA Form Renderer 1.2.2.0200,
IDM 4.10.2 patch). The engine is `engine-container` (identityengine
4.10.2.0200) on a Docker host (`debian@idm254-engine`, passwordless sudo;
traces live inside the container). The applications run on k3s
(`debian@192.168.103.98` = `nam-k3s`, `sudo k3s kubectl -n idm`): pods
`identityapplications` (IDMProv, idmdash, **workflow** — the workflow engine
is co-deployed in that pod, there is no separate workflow pod), `osp`,
`formrenderer`, `sspr`, `identityreporting`, `identityconsole`, `activemq`.
Ingress routes `/workflow` → identityapplications and `/WFHandler`, `/forms`
→ formrenderer, so the builder's online `FormsBackendUrl` for this system is
`https://idm254.pointbluetech.com/WFHandler`. `/workflow/*` answering 500 to
anonymous calls is the OAuth REST filter logging "An error occurred while
attempting to authenticate" — not a fault; authenticated calls are needed.
Same 11 stock forms / 39 PRDs as 4.8.7, but
**the 4.10.1 stock forms are minimal documents**: components carry only the
keys that matter (a `textfield` has ~14 keys instead of the 4.8 builder's 47;
`validate` holds only `{"required":true}`), and they render. So the renderer
tolerates components without the builder's default keys — typed operations
may emit minimal components; the 22 captured templates
(`resources/forms/components/`) remain the reference for what each key means
and what the builder writes. Environment `idm254` is in the gitignored
`environments.properties`; `driverset.status --env idm254` works.

## 7. Real usage

Designer workspaces on this Mac: every project carries the 11 stock forms;
`XFDEMO1` has 6 custom JSON forms bound to 5 custom PRDs (`Emergency
Termination FormsIO`, `Identity Delete`, `customerGroupCreation`, …) alongside
classic PRDs; `idm-ig4`/`testc7` have two AppConfigs. So both form
generations coexist in real projects, and only `formSrc="1"` PRDs are in
scope.
