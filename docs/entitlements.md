# Track W step W4b — entitlements as-code and a Loopback driver that carries them — design note

Status: **facts gathered 2026-09-15; proceeding on the recommendations below
(Jerry: "do it" on the Track W follow-ups). Nothing built yet.**

Why: the workflow live proof (W4) could not grant anything — idm254 has no
entitlement and the tool has no entitlement model. Jerry's instruction:
install a driver with entitlements on idm254 — a **Loopback driver with
entitlements added to it** — so a workflow's provision activity has a real
target.

## 1. Facts

### 1.1 The vault object

`DirXML-Entitlement`, a child of the driver object (`cn=<name>,cn=<driver>,
<driver set>`), read on ig4 (2026-09-15, `ldapsearch`):

- `objectClass: Top, DirXML-Entitlement` (+ `DirXML-PkgItemAux` when
  packaged); `cn`; **`XmlData`** = the entitlement definition:
  `<entitlement conflict-resolution="union|priority" description="…"
  display-name="…"><values multi-valued="true|false">[<query-app>… or
  <value>…</value>…]</values></entitlement>` (an unpackaged one is the
  one-line `<entitlement …/>`; a packaged AD one carries a `query-app` with
  an XDS query for the values and the package stamps `DirXML-pkgGUID`,
  `-pkgAssociationId`, `-pkgChecksum`, `-pkgInitialState`).
- That is the whole object. Grants live on the *recipient*
  (`DirXML-EntitlementRef` / `DirXML-EntitlementResult` on the user), written
  by the Identity Applications' provision activity; the driver's filter must
  sync `DirXML-EntitlementRef` for the driver to act on them.

### 1.2 Designer

`<id>.Entitlement_` (`CObject name="<cn>" type="Entitlement"`) with a
`contents` heavy-data attribute → `<id>_contents.xml` = the same
`<entitlement>` document; listed in the driver's `Idm:Entitlements`
relation (the reader already counts them: `entitlements.count`). No digest
beyond the usual CObject; packaged ones carry `_initial_state.xml`.

### 1.3 Loopback driver

The Loopback shim ships with the engine
(`com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim`); no remote
loader, no secrets. The local package catalog has no Loopback packages (3
packages only), so the driver is created as a hand-built shim driver
(`driver.add --shim-class …`) with a filter that syncs `User` /
`DirXML-EntitlementRef` and a no-op policy set — enough for the vault to
accept entitlement grants on users and for the driver to receive the events.

### 1.4 Workflow side (already built)

`flow.activity.add --kind provision --entitlement-dn <DN>` writes the five
stock data items (`dn`, `DirXML-Entitlement-DN`, `-Action '1'`,
`-Parameter`, `-MultiValueAllowed`); `flow.activity.set --entitlement-dn`
fixes a template's placeholder.

## 2. Design

- **Model**: `Entitlement { name, Element definition, meta (stamps,
  customized) }` on `Driver.entitlements`; tree file
  `drivers/<d>/entitlements/<name>.xml` (canonical XML, like a PRD part) with
  the manifest carrying the stamps like forms/PRDs do.
- **Readers**: live/LDIF (`DirXML-Entitlement` children of the driver),
  project (`*.Entitlement_` + `_contents.xml`), export (`<entitlement>` in a
  driver export, if present). **Writer**: as-code and `export-project`
  (CObject + contents + `Idm:Entitlements` relation).
- **Diff/deploy**: kinds `ENTITLEMENT_ADDED/REMOVED/CHANGED`; driver-scoped
  (an entitlement change does not need a restart — the engine reads
  entitlements from the vault when granting; note it in the plan as no
  restart). `VaultMapping.entitlementAttributes` = `XmlData` + stamps.
- **Operations**: `entitlement.add <tree> --driver D --name N [--display-name
  T] [--description …] [--multi-valued] [--conflict union|priority]
  [--values v1,v2…]` (static values; `--query-file` for a query-app
  definition), `entitlement.set`, `entitlement.remove` (refuses while a PRD's
  provision activity names it); `entitlement.list/show`.
- **Checks**: `EntitlementCheck` — document well-formed, `conflict-resolution`
  enum, `multi-valued` boolean; and in `FlowCheck`, a provision activity's
  `DirXML-Entitlement-DN` literal that names an entitlement in the tree is
  resolved (info when it points outside the tree, warning when the driver
  exists but the entitlement does not).
- **Loopback driver**: `driver.add --name Loopback --shim-class
  com.novell.nds.dirxml.driver.loopback.LoopbackDriverShim` + `filter.set`
  for `User` (`DirXML-EntitlementRef` sync/notify) — existing operations.

## 3. Proof (W4b live, idm254)

1. `driver.add` the Loopback driver, `entitlement.add --name TestAccess
   --values a,b`, deploy (driver created stopped, manual), `driver.start`.
2. Author a PRD (as W4) with `flow.activity.add --kind provision
   --entitlement-dn "cn=TestAccess,cn=Loopback,cn=driverset1,o=system"` after
   the approvals; deploy; request in the dashboard; approve twice.
3. Expect: `Workflow_Ended` after the provision activity, and uaadmin's
   `DirXML-EntitlementRef` carries a value referencing the entitlement (the
   Loopback driver's trace shows the modify event). Remove everything after.

## 4. Order

Model + readers/writer (agent) → diff/deploy + checks (agent) → operations
(agent) → live proof (me) → Designer check (Jerry: the Loopback driver with
its entitlement imported from the vault).
