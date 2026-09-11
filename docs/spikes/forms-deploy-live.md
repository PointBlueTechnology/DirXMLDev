# Spike P4a — provisioning objects through the deployer, live on idm254 (2026-09-11)

Question: do the vault mapping, diff, plan, deployer, snapshot and verify handle
JSON forms and PRDs (`srvprvJSONForm` / `srvprvRequest` under a User
Application driver's `cn=AppConfig`) the way they handle driver artifacts —
and is the mapping calibrated so that an untouched vault diffs empty?

System: `idm254` (IDM 4.10.1 lab; eDirectory `idm254-engine`, Identity
Applications on k3s), tier `dev`. Only a scratch object was written; it was
removed again; the vault ended clean.

## Result: PASS

1. **Calibration.** `import-live cn=driverset1,o=system` → tree with
   `drivers/User Application Driver/provisioning/` (11 forms, 39 PRDs, stamps
   in the manifest) → `vault.diff --env idm254` = **no differences**. Forms
   compare as parsed JSON (tree pretty, vault compact), PRDs as canonical XML
   of definition/request/process plus their plain properties and stamps.
2. **Add.** A scratch request form (`DirXMLDev Scratch`, a copy of the stock
   `Request Form` with a new title) added to the tree → diff `+ added form …`,
   0 drivers affected (provisioning changes never restart the engine) → plan:
   `ensure_container cn=WorkflowForms`, `ensure_container
   cn=WorkflowRequestForms` (both already present → skipped), `add
   cn=DirXMLDev Scratch … srvprvJSONForm (14.6 KB)`; snapshot written
   (`deploy-snapshots/idm254/…ldif`, absent marker); verify = vault matches
   the tree. LDAP shows `objectClass: Top, srvprvJSONForm` and
   `srvprvJSONData` = the document compact (0 newlines), as the vendor
   builder writes it.
3. **Modify.** Title changed in the tree → plan `modify … srvprvJSONData` →
   verify OK → LDAP shows the new title.
4. **Delete.** Form removed from the tree → plan `delete …` → verify OK →
   LDAP `no such entry`; final `vault.diff` = no differences.

## What this settles

- The deploy path for forms/PRDs is the driver path with three new object
  kinds and one new plan step (`ENSURE_CONTAINER`, an add only when the DN is
  absent); no special casing in the deployer, snapshot or verify.
- Packaged (stock) forms/PRDs keep their `DirXML-pkg*` stamps on write; a
  customized one gets a content-derived `DirXML-pkgChecksum` and its baseline
  as `DirXML-pkgInitialState` (unit-tested; not exercised live — no stock
  object was touched).

## Scratch PRD deployed (2026-09-11, after P2b)

`form.add --kind request --name "DirXMLDev Scratch" --from "Request Form"`
+ `prd.add --name "DirXMLDev Scratch PRD" --from-template NoApproval
--request-form "DirXMLDev Scratch" --display-name "en~DirXMLDev Scratch PRD"`
(category set to `accounts`, status Active, request binding rebuilt: title,
subHeading, recipient, reason) → `validate` 0 errors → `vault.deploy --env
idm254 --yes`: containers ensured (skipped), form added, `cn=RequestDefs`
ensured (skipped), PRD added (`srvprvRequest`, 6.6 KB: XmlData + request +
process XML + status/flow/grant/revoke/category/localized names/descr) →
verify OK. **Both scratch objects are on idm254 until the pickup check is
done; remove them with the tree (delete the two entries, `vault.deploy`).**

REST facts learned for the pickup check: an OAuth token comes from
`POST /osp/a/idm/auth/oauth2/token` (password grant, `client_id=rbpm`,
the lab's client secret); `GET /IDMProv/rest/access/permissions?q=…` (the
permission index the Requests page uses) returns 0 items for `uaadmin` even
for the stock `HelpdeskTicket`, so it cannot tell whether a PRD was picked
up; `GET /IDMProv/rest/access/prds` is 404 on 4.10.1; the `/workflow/rest/v1`
paths are not the ones the vendor swagger names (`No endpoint GET …`). The
pickup check is therefore a UI check (Administration → Workflows / the
request form under Requests), with and without Administration → Caching →
flush.

## Runtime pickup: PASS (2026-09-11, Jerry in the Identity Applications + REST)

- **No cache flush needed.** `DirXMLDev Scratch PRD` appeared under Requests
  right after `vault.deploy`; Jerry submitted it and the workflow engine ran
  it (pod log: `Workflow_Started … cn=dirxmldev scratch prd`), failing at the
  provision activity with `{enter Entitlement DN here}` — the NoApproval
  template's own placeholder, as expected for a template copy.
- **The form is served like a stock one.** The dashboard asks
  `POST /IDMProv/rest/access/permissions/item {"id":<PRD DN>,"entityType":"prd"}`
  and got, for ours exactly as for `HelpdeskTicket`, `isNewForm: true` and
  `requestFormId: cn=DirXMLDev Scratch,cn=WorkflowForms,cn=appconfig,…`
  (a flattened DN — the applications resolve the form by name under
  `WorkflowForms`); `GET /IDMProv/rest/access/forms?id=<that DN>&pid=<PRD DN>
  &formContainer=RequestForms&recipient=…&locale=en` returns our document
  (`formJSON`: title `DirXMLDev Scratch`, display `form`, the copied
  components) with the same shape as the stock one.
- **Where the form shows.** For `isNewForm` PRDs the dashboard (chunk
  `showPRDModal`) opens the JSON form renderer in a **new browser window**
  (`/forms/#/form/details?id=…&pid=…&sid=IDM&uri=/rest/access/forms
  &formContainer=RequestForms`) when the PRD is picked; the New Request
  page's own Request button is the classic no-form submit. Jerry's first
  submission went through that button (no form seen, workflow ran); after
  `prd.map` added `reason`/`recipient` data items, the same button answered
  "Internal exception occurred processing REST service" — the no-form path
  cannot supply mapped fields. A blocked popup looks exactly like "no form".
- Deploying a PRD change rewrote every PRD attribute (the plan modifies all
  attributes of a changed object); correct but noisy — a per-attribute diff
  would trim it (follow-up).
- `prd.add` copies the template's mappings only where the field names still
  exist, so a PRD built from `NoApproval` starts with no data items; map the
  fields explicitly (`prd.map`) — a `--map-all` convenience is a candidate.

## Still open

- **Runtime pickup**: whether the Identity Applications serve a changed form
  or a new PRD without a cache flush/restart. Test plan: deploy a scratch PRD
  (`prd.add --from-template NoApproval --request-form "DirXMLDev Scratch"`)
  and a form edit; check in the Identity Applications (Requests → the new
  PRD, and the request form's rendering) with and without
  Administration → Caching → flush. Until measured, `vault.deploy` prints a
  reminder after touching provisioning objects.
- `prd.add` output must also deploy cleanly (properties, localized names as
  `lang~text|…`, `XmlData` carrying the process, `srvprvRequestXML`,
  `srvprvProcessXML`).
