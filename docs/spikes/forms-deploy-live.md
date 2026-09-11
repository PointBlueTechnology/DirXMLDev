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

## Still open (needs a PRD, i.e. step P2b's `prd.add`)

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
