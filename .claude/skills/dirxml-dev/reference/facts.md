# Proven facts — read before deriving anything

One line per fact we measured or read from the vendor's own code, with the
spike that proves it (`docs/spikes/…`). If a question below has an answer
here, do not re-spike it; if a spike closes, add its facts here (the rule in
`docs/spikes/README.md`). Dates are when it was proven; "4.10.1" is the IDM
version it was proven on unless stated.

## The vault and the engine

- Driver artifacts (`DirXML-Rule`, GCVs, resources, filters, schema map,
  mapping tables, driver settings) create/modify/delete over LDAP with the
  deploy identity; the server stamps nothing we must reproduce beyond the
  attributes we write → `ldap-write.md`, `vault-objects-and-secrets.md`.
- A linked policy is loaded by the engine after `RestartDriver`; live edits to
  a running driver's policies are not re-read until restart → `engine-pickup.md`.
- Extended operations (start/stop/restart, driver set get/set, init, migrate,
  resync, submit, named passwords, GCV, cache, version): constructor and
  response conventions → `extended-ops-api.md`; cache queue/view/clear, trace
  level, `SubmitCommand`/`SubmitEvent` semantics → `operate.md`.
- `~gcv~` substitution is engine-faithful in the simulator: an undefined GCV
  means the driver will not start; GCV definitions merge first-wins.
- Provisioning objects (forms, PRDs) never need a driver restart; the
  Identity Applications pick them up with no cache flush → `forms-deploy-live.md`.

## Packages

- "Modified" is a checksum *pair* (`Idm:ContentChecksum` vs the baseline's
  `idm-contentchecksum`), not a flag; the baseline lives in `_initial_state.xml`
  / `DirXML-pkgInitialState` → `modified-flag.md`.
- Designer's stored checksum is **not** a content function (identical
  content, different values); the *installed* checksum recipe is, and we
  recompute it exactly (all kinds; jobs 1/30 off) → `checksum-algorithm.md`,
  `designer-package-layer.md`, `package-checksums.md`.
- A customized packaged artifact must carry a recomputed checksum in the
  tree, the vault and the Designer project, or Designer shows it unmodified
  and an upgrade clobbers it (done: `Packages.refreshChecksums`).
- Package jar types, the update site layout, what the vault records
  (`DirXML-pkg*`, driver base record, filter ownership in `pkgExtensions`) →
  `package-format.md`, `package-vault-stamps.md`.
- `package.install` / `driver.add --packages` reproduce Designer's install
  object for object (18/18 checksums; every policy-set order = the vault's);
  upgrade = uninstall + install keeping customizations → `package-install-parity.md`,
  `designer-package-layer.md`. A package we build is accepted by Designer →
  `designer-package-acceptance.md`.
- The Package Deployment Tool is a wrapper over `DesignerHeadless`; the
  headless app runs on macOS (`listContents`) → `pdt-analysis.md`, `designer-headless.md`.

## Designer projects

- A writer-updated project opens in Designer without repair: minted ids,
  rewritten relation lists, 2-space re-indentation are all fine → `designer-writer.md`.
- A **copied** project needs `.proj`/`.cproj`/`.project` renamed **and** their
  contents rewritten (`cprojectURI`, `name`, `adapterProject href`, project
  name); names only → a silent empty import; contents only → "No valid .proj
  file" → `designer-writer.md`.
- Designer deploys a PRD as `srvprvRequestXML` + `srvprvProcessXML` + `XmlData`
  and imports one by putting the first two into `XmlData`'s skeleton; the
  `.prd` file is the union with `<provision-request>` right before `<process>`
  → `json-forms-format.md`, `docs/workflows.md` §1.3.
- Designer stores **no layout** for a workflow (GEF auto-layout); the
  `xml-data/design-params` block is legacy and may be left as the templates
  carry it → `docs/workflows.md` §1.3.
- Designer edits JSON forms by launching an external Electron app
  (`FormBuilder --filepath=<file> --locale=<ll_CC>`); it saves compact JSON in
  place; macOS needs the quarantine cleared once; the vendor bundle is
  x86_64-only (Rosetta) — DesignerModernPlatform rebuilds it on arm64 →
  `docs/forms.md` §2, `json-forms-format.md`.

## JSON forms (Track P)

- A form is one Form.io document in `srvprvJSONForm/srvprvJSONData`,
  byte-identical to Designer's `.formRequest`/`.formApproval`/`.formTemplate`
  file; bound **by name** from the PRD → `json-forms-format.md`.
- Designer's binding rules (decompiled, calibrated 9/11 stock forms as exact
  no-ops): request-binding fields = every keyed component whose type is in
  the data-type map (`apwaComment` skipped, bound buttons kept); activity
  bindings are bare references; data items are persisted mappings — kept or
  pruned, never invented → `docs/forms.md`, `BindingSync`.
- Stock 4.10.1 forms are minimal component documents; the renderer tolerates
  missing defaults → `json-forms-format.md`.
- Tree = pretty JSON, wire = compact; a customized stock form/PRD gets a
  content-derived checksum and a baseline → `forms-deploy-live.md`.
- The Identity Applications serve our form exactly like a stock one
  (`/rest/access/permissions/item` → `isNewForm`, `/rest/access/forms` →
  `formJSON`); the dashboard opens JSON forms in a **new window**; the New
  Request page's own Request button is the no-form path and fails once data
  items are mapped → `forms-deploy-live.md`.

## Workflows (Track W)

- The engine binds `<process>` with JAXB: 17 activity kinds, fixed link
  types per kind, versions 3.5.0…4.5.0 accepted, **no XSD validation at
  runtime**; `ProcessFlowModel.validate()` = the ten checks `FlowCheck`
  mirrors → `docs/workflows.md` §1.1–1.2 (source: `workflow.jar`, scratchpad only).
- Expressions are ECMAScript (`scriptengine.jar`) with scope `flowdata`,
  `process`, `initiator`, `recipient`, `IDVault`, `GCV`, `RoleVault`,
  `NrfRequest`, `NrfResourceRequest`, `AttestationRequest` and one info object
  per activity id → `docs/workflows.md` §1.1.
- **An approval activity must bind an approval form**, or the dashboard's
  task-details call fails and the task cannot be opened (the engine itself
  does not care) → `workflow-live.md`.
- **A denied path must set `flowdata.IDM_COMPLETED_APPROVAL_STATUS` to
  `'denied'`** through a mapping activity, or Request History reads
  "Approved" → `workflow-live.md`.
- A PRD shows under Access → Request only when **Active and the user has
  directory rights** to it → `docs/workflows.md` §6a.
- A template's `{enter Entitlement DN here}` placeholder makes the request
  fail at the provision step → `forms-deploy-live.md`.
- Submitting a JSON-form PRD over REST: `POST /IDMProv/rest/access/requests/permissions/item`
  answered "Internal exception" with and without `permReqParams` (2026-09-15);
  the form renderer's own submit works. **Jerry: it should be possible over
  REST — an open task to find the right call/payload** (candidates: the
  renderer's `/WFHandler` route with a session, or the workflow REST
  `/workflow/rest/v1` endpoints). Approve/deny/claim over
  `POST /rest/access/tasks` works → `workflow-live.md`.
- The vendor REST API is documented in the form builder's own
  `Contents/commons/swagger.json` (181 paths, base `/IDMProv/rest/access`).
- An authored workflow (condition, two approvals, log) deploys, is picked up
  without a flush, runs and completes; the trimmed PRD deploy writes only
  `XmlData` + `srvprvProcessXML` for a process change → `workflow-live.md`.

## Entitlements (Track W step W4b)

- A `DirXML-Entitlement` is `cn` + `XmlData` (`<entitlement conflict-resolution
  display-name description><values multi-valued>…`) + package stamps, a child
  of the driver; grants live on the recipient as `DirXML-EntitlementRef`
  (`<DN>#1#<ref><src>AF</src><id>…</id><param>…</param></ref>`) →
  `docs/entitlements.md`, `entitlements-live.md`.
- A workflow's provision activity grants an entitlement authored with
  `entitlement.add` on a hand-built Loopback driver (`Entitlement_Grant` in
  the applications' log) — proven live → `entitlements-live.md`.
- The applications warn "Entitlement configuration object not found" for a
  driver without an `EntitlementConfiguration` resource and grant anyway;
  the applications read that resource only when entitlement *binding* is
  configured (mapping entitlements to roles/resources in the catalog) — a
  workflow's direct grant never needs it. It is usually built by hand in
  Designer; some drivers carry startup policies that create or update it
  (Jerry) — so ours must not overwrite one a policy maintains. Not built
  yet; low priority until binding is in scope.
- The engine writes `DirXML-EngineControlValues` on a driver's first start;
  a tree that never had them must adopt them (`import-live`), the plan never
  removes them → `entitlements-live.md`.
- `vault.deploy --delete-driver D` is implemented (2026-09-16): a deploy still
  never deletes a driver on its own, only when named — and only when it's
  stopped and absent from the tree (in the tree, or unknown to the vault, is
  refused). Snapshots the whole subtree before deleting it → `vault-deploy.md`.
- A tree imported before a model extension (entitlements, JSON forms, PRDs —
  or any future object kind) lacks that kind entirely for a driver the vault
  already has some of; diffing it looks like every one of those objects was
  removed. The plan refuses to empty a kind this way (2026-09-16 incident: 19
  entitlements deleted from a tree imported before `Driver.entitlements`
  existed) — one note instead of the deletes, and `--delete-all <kind>` is the
  explicit override → `vault-deploy.md`, "Deploy never empties a kind".

## Identity Applications REST (spike W6, `docs/idapps-rest.md`)

- **Start a JSON-form PRD with `POST /IDMProv/rest/access/requests/permissions/v2`**
  — body `{reqPermissions:[{id:<PRD DN>, entityType:"PRD"}], data:[{key:<form
  field key>, value:[…]}], recipients:[{dn, type:"user"}]}` (the JSON form
  renderer's own call; `value` is always a list; `recipients` absent = the
  caller). `requestId` in the answer = the workflow process id →
  `prd-rest-live.md`. `bin/apps request` does it.
- **`/requests/permissions/item` is the legacy-form/role/resource call** and
  answers "Internal exception occurred processing REST service" for a
  JSON-form PRD — not a bug in our objects → `workflow-live.md`, `prd-rest-live.md`.
- **A freshly deployed PRD is not requestable over REST for up to 10 minutes:**
  the applications resolve permissions in an in-memory index whose PRD
  provider re-reads the vault every `com.netiq.idm.cis.rbpm.updateInterval[.prd]`
  minutes (default 10). Symptom: 200 `{"success": false}` with no `Fault` and
  nothing logged; `POST /permissions/item` says `PermissionIndexException …
  does not exist`. `POST /index/permissions` accepts `ADD_OR_MODIFY`/`REMOVE`/
  `REFRESH` (the vendor doc says REMOVE only) — whether it shortens the wait
  is unproven → `prd-rest-live.md`.
- **Token:** OSP password grant at `/osp/a/idm/auth/oauth2/token`, Basic
  `<client>:<secret>`; `rbpmrest` (documented) and `rbpm` (the dashboard's)
  both work on the lab. Application errors are HTTP 489 with an `NcacFault`
  body; the XSS filter answers 400 → `idapps-rest.md` §1.
- **Tasks:** `GET /tasks/list` then `POST /tasks {tasks:[{taskId}], action,
  comment}`; `action` ∈ approve, deny, comment, refuse, claim, release,
  reassign, return (case-insensitive) → `idapps-rest.md` §6.
- **History:** `GET /requests/historylist?nextIndex=1&size=N&q=*` (the short
  `/requests/history` answered 489 on 2026-09-16); completed-approved =
  requestState 2 / processState 3, denied = 1 / 3 → `idapps-rest.md` §7.
- **The index search `GET /permissions?q=*&type=PRD` returns 0 rows** for
  uaadmin on idm254 even for stock PRDs (three sessions) — not a probe for
  "is my PRD there"; use `/permissions/item` → `prd-rest-live.md`.
- **Base paths:** access calls under `/IDMProv/rest/access`, catalog
  (`/prds`) under `/IDMProv/rest/catalog`, admin (`/cache/*`) under
  `/IDMProv/rest/admin`; the wrong base is a plain 404 → `idapps-rest.md` §1.

## Not proven / not to assume

- Roles and resources are managed in the Identity Applications, not in
  Designer or the AppConfig subtree — not a tool track.
- Whether a normal browser opens the task's approval-form popup (the app's
  browser pane did not; the bulk Approve/Deny buttons work).
