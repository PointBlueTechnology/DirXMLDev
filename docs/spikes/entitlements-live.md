# Spike W4b — an entitlement authored as-code, granted by an authored workflow, live on idm254 (2026-09-16)

Question: can the tool create a driver and an entitlement on it, author a
workflow that grants that entitlement, deploy all of it, and does the
Identity Applications' provision step actually grant it?

System: `idm254` (IDM 4.10.1 / Identity Applications CE 25.4.2), user
uaadmin as requester, recipient and approver. No Loopback package in the
local catalog, so the driver is hand-built (`docs/entitlements.md` §1.3).

## Result: PASS

**Authoring** (6 commands): `driver.add --name Loopback --shim-class
com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim`;
`filter.set-class --class User --subscriber sync --publisher ignore` +
`filter.set-attr --attr DirXML-EntitlementRef`; `entitlement.add --driver
Loopback --name TestAccess --display-name "Test Access" --multi-valued true
--conflict union --values alpha,beta`; `form.add` "DirXMLDev W4b Form";
`prd.add` "DirXMLDev W4b" from `NoApproval` (`--map-all`);
`flow.activity.set --id prov --entitlement-dn
"cn=TestAccess,cn=Loopback,cn=driverset1,o=system" --entitlement-param alpha`;
`flow.activity.add --kind approval --id approval_1 --after Activity
--addressee "'cn=uaadmin,ou=sa,o=data'"`. `validate`: nothing on the new
objects (`flow-entitlement-*` resolved the DN against the tree).

**Deploy**: the driver (created stopped, manual, Subscriber/Publisher
containers), then the entitlement (`DirXML-Entitlement`, 253 B `XmlData`),
`driver.start` → running; the form and PRD as in W4 (2 modify steps after
the entitlement fix). A fresh `import-live` of the driver set afterwards
diffs empty — the Loopback driver and its entitlement round-trip through
the live reader.

**Run**: request submitted in the dashboard (Access → Request → New Request
→ "DirXMLDev W4b entitlement grant" → JSON form → Submit), approved over
REST (`POST /rest/access/tasks`, action approve). Engine log:
`Workflow_Started` → Workflow Status → Grant approval (claimed, approved)
→ `[Provision_Submitted]` → **`[Entitlement_Grant] Entitlement
cn=TestAccess,cn=Loopback,cn=driverset1,o=system with parameter alpha
granted`** → `Workflow_Ended`. uaadmin now carries
`DirXML-EntitlementRef: cn=TestAccess,cn=Loopback,cn=driverset1,o=system#1#
<ref><src>AF</src><id>83782463235a43c085bb49ca5bc1d594:…</id><param>alpha</param></ref>`
and Request History shows `20260916-1` Approved. The Loopback driver ran
with an empty cache throughout (its filter syncs `DirXML-EntitlementRef` on
the Subscriber; the engine's event went through it — nothing to do for a
loopback).

## Found on the way (all fixed the same day, 5d008fc)

- `flow.activity.set --entitlement-dn/--entitlement-param` searched for
  the provision activity's data items under the PRD's definition root
  instead of its `<process>` — a silent no-op that reported "updated". It
  now edits the right block and refuses when the item is missing.
- A brand-new driver's entitlements were not part of its add plan (the diff
  folds a new driver's contents into `DRIVER_ADDED`); the plan now adds them.
- The engine writes `DirXML-EngineControlValues` when a driver first
  starts; a tree that never had them made the plan remove them. The plan
  now leaves them with a note; `import-live` adopts them.
- Removing an attribute the object never had (`DirXML-Policies` on a new
  driver with no policies) failed the deploy with an LDAP -603; now a no-op.

## Open

- The applications warned `Entitlement configuration object not found in
  driver: cn=Loopback…` and granted anyway. A packaged driver carries an
  `EntitlementConfiguration` resource (`DirXML-Resource`, content type
  `application/vnd.novell.dirxml.entitlement-configuration+xml`) that
  describes its entitlements to the Resource/Role catalog; a hand-built
  driver has none. `entitlement.add` should be able to create and maintain
  it (follow-up, needed before roles/resources map to such an entitlement).
- Designer acceptance (§4 of the design note): **PASSED 2026-09-16** — Jerry
  imported the Loopback driver with its entitlement from the vault into
  `Designer-modernized`; both look right.
- Objects left on idm254 for that check were removed 2026-09-16 after the
  Designer pass: `prd.delete`, `form.delete`, `entitlement.remove`,
  `driver.stop`, then `vault.deploy --delete-driver Loopback`; the grant
  value on uaadmin was removed over LDAP. `vault.diff` against a fresh
  import: no differences.
