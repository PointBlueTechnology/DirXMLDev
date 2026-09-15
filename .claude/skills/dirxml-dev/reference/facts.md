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
- **REST cannot submit a JSON-form PRD** (`/requests/permissions/item` fails
  with or without parameters); only the form renderer's submit works.
  Approve/deny/claim over `POST /rest/access/tasks` works → `workflow-live.md`.
- The vendor REST API is documented in the form builder's own
  `Contents/commons/swagger.json` (181 paths, base `/IDMProv/rest/access`).
- An authored workflow (condition, two approvals, log) deploys, is picked up
  without a flush, runs and completes; the trimmed PRD deploy writes only
  `XmlData` + `srvprvProcessXML` for a process change → `workflow-live.md`.

## Not proven / not to assume

- Roles and resources are managed in the Identity Applications, not in
  Designer or the AppConfig subtree — not a tool track.
- Entitlements are not a modeled object here yet (only referenced).
- Whether a normal browser opens the task's approval-form popup (the app's
  browser pane did not; the bulk Approve/Deny buttons work).
