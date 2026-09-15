# Track W step W4b — entitlements as-code and a Loopback driver that carries them — design note

Status: **facts gathered 2026-09-15; §2's model/readers/writers/diff/deploy/
operations/checks shipped the same day (agent, on the `w4b-entitlements`
branch) — 505 tests green (473 prior + 32 new), 11 skipped. Live proof (§3)
and the Designer acceptance check (§4) still pending.**

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

**Built** (2026-09-15, agent, `w4b-entitlements`):

- **Model**: `Entitlement { name, Element definition, meta (stamps,
  customized) }` on `Driver.entitlements`; tree file
  `drivers/<d>/entitlements/<name>.xml` (canonical XML, like a PRD part).
  Deviation: the stamps are listed in the driver's own manifest
  (`driver.xml`, an `<entitlement name=… file=…>` element per entitlement),
  not a separate `entitlements.xml` — an entitlement has no scope of its own
  and `driver.xml` already lists driver-scope artifacts/config/linkage the
  same way, so this keeps one manifest per driver instead of two.
- **Readers**: live/LDIF (`DirXML-Entitlement` children of the driver, same
  `dirxml-pkg*` meta convention as forms/PRDs), project (`*.Entitlement_` +
  `_contents.xml`, counted via `Idm:Entitlements` the reader already parsed
  for `entitlements.count`). **Export**: *not implemented* — neither the
  export-format doc (`ProjectReader`'s class doc, `ExportReader`'s class doc)
  nor any test fixture shows an `<entitlement>` element under
  `<driver-configuration>`; a Designer "Export to Configuration File" does
  not appear to carry entitlements at all (they are vault/project-only
  objects). If that turns out to be wrong, `ExportReader`/`ExportWriter` are
  the place to add it, mirroring `readPolicy`/`writeArtifact`. **Writer**:
  as-code (`AsCodeWriter`/`AsCodeReader`) and `export-project`
  (`*.Entitlement_` CObject + `_contents.xml`, `Idm:Entitlements` relation
  kept in step — mirrors a driver-scope policy's `Idm:Policies` handling).
- **Diff/deploy**: `ModelDiff.Kind.ENTITLEMENT_ADDED/REMOVED/CHANGED`,
  `Kind.isEntitlement()`/`noRestart()` (folds with `isProvisioning()` for the
  restart-exclusion and `affectedDrivers()` logic — an entitlement change
  never needs a restart, same reasoning as forms/PRDs: the Identity
  Applications read it from the vault at grant time). `VaultMapping.
  entitlementDn`/`entitlementAttributes` (`XmlData` = canonical XML bytes),
  stamps via the existing `provisioningPackageAttributes`. `Plan.
  entitlementSteps`: ADD (`Top, DirXML-Entitlement` [+ `DirXML-PkgItemAux`
  when stamped]) / MODIFY `XmlData` only when changed / DELETE — no
  Deployer change needed (existing ADD/MODIFY/DELETE/AUX_CLASS ops).
- **Operations** (`edit/EntitlementOps.java`): `entitlement.add <tree>
  --driver D --name N [--display-name T] [--description S] [--multi-valued]
  [--conflict union|priority] [--values v1,v2…] [--definition-file f.xml]`
  (`--conflict` defaults to `priority`; `--definition-file` takes a whole
  `<entitlement>` document instead of the content flags — this supersedes
  the design's `--query-file`, since a query-app-based entitlement is just
  another whole document), `entitlement.set` (same flags on an existing one;
  a packaged entitlement is baselined + marked customized on its first
  edit, same convention as `FormOps.customizeForm`), `entitlement.remove`
  (refuses while any PRD's provision activity's `DirXML-Entitlement-DN`
  literal names it by name inside the DN — `--force` never overrides this);
  `entitlement.list`/`entitlement.show` (the latter lists referencing PRDs).
- **Checks**: `EntitlementCheck` (registered after `FlowCheck`) —
  `entitlement-name-blank`, `entitlement-no-document`,
  `entitlement-wrong-root`, `entitlement-conflict-invalid`,
  `entitlement-multi-valued-invalid`. `FlowCheck` gained
  `flow-entitlement-unknown` (warning — a provision activity's
  `DirXML-Entitlement-DN` names a driver in the tree with no such
  entitlement) and `flow-entitlement-external` (info — it names a driver not
  in the tree).
- **Tests**: 32 new (505 total, 11 skipped) — model round trip, LDIF reader
  (packaged + unpackaged), project reader/writer (synthetic skeleton: add,
  change, remove), diff/plan (added/changed/removed + a customized packaged
  entitlement's content-derived checksum), operations (add with values, set,
  definition-file replace, remove refused/unrefused, list/show), the five
  `EntitlementCheck` codes, the two new `FlowCheck` codes.
- **Docs**: this file, `docs/agent-guide.md` (a "Entitlements" subsection
  under Provisioning), `.claude/skills/dirxml-dev/reference/commands.md` (an
  "Entitlements" section), `docs/plan.md` (Track W paragraph).

**Not built** (§3/§4 below): the Loopback driver
(`driver.add --shim-class com.novell.nds.dirxml.driver.loopback.
LoopbackDriverShim` + `filter.set`) and the live proof on idm254 are
existing operations needing no new code — Jerry's to run.

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
