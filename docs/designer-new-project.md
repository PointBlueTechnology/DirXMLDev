# A fresh Designer project from a tree — design note

Status: **proposed 2026-09-17, awaiting Jerry's decisions (§5).** Follows
[designer-roundtrip.md](designer-roundtrip.md) §3 (the writer that updates an
existing project) and closes the gap
[howto-fresh-designer-project.md](howto-fresh-designer-project.md) works
around: the configuration-file route cannot carry the User Application
driver's `AppConfig` (forms, PRDs, entitlements), while a Designer project
can — and the tree already holds them.

Why now (Jerry, 2026-09-17): "Why don't we have the ability to store the app
config elements from the user application driver? Designer captures them in
the project." We store them; what we lack is a way to hand a team a
*project* without Designer ever reading the vault. Today `export-project`
refuses a brand-new project, a packaged driver it would have to create, and
an `AppConfig` that does not exist yet (Phase 6 scope). This note is the plan
to lift those three refusals.

## 1. Facts — what a Designer project is on disk

Read from `~/designer_workspace/test11pf` (Designer 4.8.7, a vault import of
ig4) and from what `ProjectReader`/`ProjectWriter` already handle. Ids are 8
characters from `[0-9A-Z]`, unique per project; every object is a CObject
metadata file `<ID>.<Type>_` with `<attributes>` and ordered `<relations>`
(`Child`, `Reference`, `BackReference`, `ContainedReference`), payload in a
sibling `<ID>_contents.xml` or `<ID>_<attr>.<ext>` heavy-data file.

### 1.1 The descriptor files (spike 6b's lesson)

| File | Holds | Coupled to the folder name |
|---|---|---|
| `.project` | Eclipse descriptor, nature `com.novell.idm.DesignerProjectNature`, `<name>` | yes |
| `<name>.proj` | `com.novell.idm.model:Project`: `name`, `guid`, `cprojectURI="<name>/<name>.cproj"`, `domainURI="IdentityManager/<domainId>.domain"`, `productID`/`version` (4.7 in a 4.8.7 project), `packageLinkagesMigrated="true"`, `<modelerNodes href="Model/IdentityManager/<id>.ModelerNodes_#/"/>`, `<adapterProject href="<name>.cproj#/"/>` | yes |
| `<name>.cproj` | `IdmAdapterProject name="<name>"` with three `Roots` Child relations: `IdentityManager.CRoot_`, `Project.CRoot_`, `EdirOrphan.CRoot_` | yes |

A mismatch anywhere gives "No valid .proj file" or a silent empty import
([spikes/designer-writer.md](spikes/designer-writer.md)).

### 1.2 The three roots under `Model/`

```
Model/IdentityManager.CRoot_          Idm:DevRootDomain → <D>.Domain_ ; ModelerNodes → <M>.ModelerNodes_
Model/IdentityManager/<D>.Domain_     "Modeler Workspace": Idm:DomainItems Child → <V>.IdentityVault_, one <A>.Application_ per driver
Model/IdentityManager/<D>/<V>.IdentityVault_
                                      IdentityVaultHost/Username/(SavePassword, obfuscated Password), Modeler.DefaultServerAdded;
                                      Idm:DriverSets ContainedReference → DriverSet_; Idm:Servers ContainedReference → Server_;
                                      Idm:Schema Child → SchemaDef_; Idm:TemplateCollections → NotfTemplateCollection_;
                                      Idm:InstalledPackages Reference → IdmPackage_ (vault-level packages)
Model/IdentityManager/<D>/<A>.Application_
                                      name = driver name; type NProv for the User Application driver, else the app type;
                                      Idm:Drivers Reference → the Driver_ (the driver carries Idm:Application BackReference
                                      and IdmParameter:AppIDCreatedDuringImport = <A>)
Model/IdentityManager/<M>.ModelerNodes_
                                      the diagram: one <modelerNodes objectURI="#…"> per vault/application with x/y/width/height
                                      and the ten port names; the Domain node has no geometry
Model/EdirOrphan.CRoot_               (empty root) — the directory holds the vault-side objects:
Model/EdirOrphan/<S>.DriverSet_       DSetContext, JavaEnvParameters, NamedPasswords, DirXML-* settings, Idm:Drivers/Libraries/
                                      GlobalConfigs Child, Idm:ConfigExtensions Reference, Idm:Servers, Idm:InstalledPackages
Model/EdirOrphan/<S>/<id>.Driver_ …   drivers, channels, filters, policies, resources, GCV bundles — the part the reader and
                                      writer already model in full; a packaged item adds package-id/pkg-assoc-id/checksum
                                      attributes and an <id>_initial_state.xml baseline (150 in test11pf)
Model/EdirOrphan/<X>.Server_          the engine server: ServerContext, hosts/ports, engine/eDir versions, installed job defs,
                                      BackReferences from the vault and driver set
Model/EdirOrphan/0.ECMAScriptResource_, 1.ECMAScriptResource_   Designer's system ECMAScript resources (referenced by
                                      Idm:ExtensionFunctions; no metadata of their own)
Model/EdirOrphan/<N>.NotfTemplateCollection_   the Default Notification Collection (optional for us)
Model/Project.CRoot_ → Model/Project/<P>.ProjectData_ → <C>.IdmCatalog_ "Package Catalog"
                                      → IdmCategory_ → IdmCategoryFolder_ → <K>.IdmPackage_ (+ <K>_license.xml, _readme.txt,
                                      _change.log, _<lang>.properties) → <K>/ IdmPackageFolder_ ("Resources", "Policies", …)
                                      → IDMResource_/policy CObjects: the package's content items in CObject form.
                                      Idm:PackageGuid/Version/Type/BasePackage/shortName/ContentChecksum/DirectiveChecksum,
                                      Idm:InstalledPackageDriverRefs BackReference → the drivers that installed it
Model/Provisioning/.provisioning      <application folder="AppConfig" guid="<A>"/> — ties the folder to the UA Application_
Model/Provisioning/AppConfig/.appconfig   the srvprvAppConfig ds-object skeleton (version, srvprvPlugins, the child containers
                                      RequestDefs/WorkFlowDefs/ResourceDefs/ServiceDefs/DirectoryModel/…)
Model/Provisioning/AppConfig/**       one .digest per container and per item; forms as .formRequest/.formApproval/…,
                                      PRDs as .prd — the layout the writer already produces for an existing AppConfig
```

A packaged driver is therefore three things at once: per-object package
attributes on its items, `Idm:InstalledPackages` references from the driver
(and from the vault / driver set for those levels) to `IdmPackage_` objects,
and those `IdmPackage_` objects with their full content trees inside the
project's own catalog. Designer imports packages into a *project* from the
workstation catalog; nothing in the project points outside it.

### 1.3 What the tree holds today

Every vault object the reader models — driver set, Library, drivers with
channels, filters, schema maps, policies, resources, GCVs, engine control
values, shim settings, package stamps (`dirxml-pkg*`), the UA driver's forms
and PRDs, entitlements — but **not** the rest of `AppConfig` (DirectoryModel,
UIConfig, RoleConfig, TeamDefs, AppDefs, AuthTypes, ChoiceDefs), jobs,
notification templates, servers, or schema. The git package catalog holds
every package as a jar plus an unpacked review form (`package.xml`, prompts,
content) — not as Designer CObjects.

## 2. What already exists to build on

- `ProjectWriter.update`: mints ids, writes CObjects and contents, keeps
  relations in step, carries forms/PRDs/entitlements into an existing
  `AppConfig`, refuses what it cannot do with a note.
- The tests' synthetic skeleton (`ProvisioningProjectWriterTest`): a
  minimal project the *reader* accepts — driver set, driver, `Application_`,
  container digests, one form, one PRD. Not yet something Designer opens.
- `ProjectReader#attachProvisioning`: the `.provisioning` guid → `Application_`
  → driver resolution, which the writer must produce in reverse.
- `PackageJar` (catalog): reads a package's `package.xml`, content items and
  prompts; `PackageInstall` knows how a package's items map onto a driver.
- Spike 6b / 7c: what Designer checks on open and on import (descriptor
  coupling; packages absent from the catalog are de-packaged).

## 3. Design — `export-project --new`

```
bin/idm export-project tree/ <newProjectDir> --new
    [--vault-name NAME] [--vault-host HOST] [--vault-user DN]   # IdentityVault_ attributes; no password is ever written
    [--server NAME --server-context DN]                          # one Server_ (default: from the driver set's server refs, else none)
    [--catalog DIR]                                              # package jars for the packaged drivers (§3.3)
    [--dry-run] [--json]
```

`<newProjectDir>` must not exist (or be empty); its basename becomes the
project name and is written into every coupled place (§1.1). Without `--new`
the command keeps today's behaviour and refuses a directory that is not a
project.

### 3.1 Skeleton (N1)

Write, in this order: `.project`, `<name>.proj`, `<name>.cproj`; the three
`CRoot_` files; `Domain_` "Modeler Workspace"; `IdentityVault_` (name from
`--vault-name` or the driver set's tree name; host/user when given,
`IdentityVaultSavePassword=false`, never a password); `ModelerNodes_` with the
vault node and one node per `Application_` in a simple grid (Designer re-lays
out on request; a missing node is the thing to test first — §4); the system
`0.`/`1.ECMAScriptResource_` placeholders; a `Server_` when named (Designer
tolerates a driver set without one). `ProjectData_` + an empty `IdmCatalog_`
with the six stock categories, so packages have a place to go (§3.3).

### 3.2 Content (N2) — the existing writer, run against an empty project

`ModelDiff.of(emptyProject, tree)` yields "everything added"; the writer's
`DRIVER_ADDED`, artifact-added, linkage, GCV and driver-setting paths already
produce Driver_/Subscriber_/Publisher_/Filter_/policy/resource CObjects and
contents for a non-packaged driver. Three additions:

- **An `Application_` per driver** (type `NProv` for the User Application
  driver, the shim's app type otherwise — the reader keeps the observed
  types), the `Idm:Application` back-reference and
  `IdmParameter:AppIDCreatedDuringImport` on the driver, the `Domain_`
  relation, and the modeler node.
- **`AppConfig` creation** for the UA driver: `.provisioning` with the
  `Application_` guid, `.appconfig` from a bundled template with the version
  the tree records, the container digests the writer already knows how to
  mint, then forms/PRDs through the existing provisioning path. Entitlements
  already go on the driver (`Idm:Entitlements`).
- **Package attributes on items**: the same `dirxml-pkg*` → `package-id` /
  `pkg-assoc-id` / `checksum` / `modified` mapping `ExportWriter` gained on
  2026-09-16, plus `<id>_initial_state.xml` = the item's content for a
  non-customized item, the `.package-baseline/` content for a customized one.

After N1+N2 a project of a vault whose drivers are un-packaged, or whose
packages the team will import in Designer afterwards, opens and validates.
Packaged drivers open too, but without the catalog entries of §3.3 Designer
treats their items as plain (the 7c observation), so N2 alone is a
milestone, not the finish.

### 3.3 Packages (N3) — the project catalog from the git catalog

For every distinct package the tree's stamps name (`dirxml-pkgguid` = id;
symbolic name; version; name; short name), find its jar in `--catalog`
(refuse with the list of missing ones — `package.fetch` gets them), and write
its `IdmPackage_` under the right category/folder with the attributes of
§1.2 (guid, version, type, base flag, short name, description, released,
build time, the two checksums the jar carries, `Idm:PackageImported=true`),
its license/readme/changelog/properties heavy-data files, and its content
tree: one `IdmPackageFolder_` per folder in `package.xml` and one CObject per
item, with the item's `_initial_state.xml`-style contents. Then the
`Idm:InstalledPackages` references from the driver (driver-type packages),
the driver set (type 3) and the vault (type 4), with the matching
`Idm:InstalledPackageDriverRefs` back-references.

This is the part with a format to learn: which CObject type each package
item kind gets (`IDMResource_` for resources; policies, GCVs, filters,
prompts, entitlements, jobs, ECMAScript), which attributes Designer's
package importer writes on them, and how `Idm:ContentChecksum` /
`Idm:DirectiveChecksum` relate to the jar's stored checksums
([spikes/designer-package-layer.md](spikes/designer-package-layer.md) has the
Java side; the on-disk side is one spike on test11pf: diff a package's
CObject tree against its jar). Only packages the tree uses are written — a
project catalog is not the workstation catalog.

### 3.4 What stays out

Jobs, notification templates, schema (`SchemaDef_`), the rest of `AppConfig`
unless §5.1 says otherwise, Designer's `.metadata`, the vault password.
`--new` never touches an existing project; updating stays with the current
writer.

## 4. Verification

1. **Round trip**: `import-project` of the written project equals the tree
   (as-code byte-identical modulo minted ids), for a synthetic tree and for
   `tree-idm254` (4 packaged drivers, 11 forms, 39 PRDs, 1 entitlement when
   present).
2. **Designer, human in the loop** (Jerry, `Designer-modernized`), one check
   per milestone: N1+N2 — the project opens, every driver and policy is
   there, the UA driver's forms open in the form builder and the PRDs in the
   workflow editor, *Project → Validate* is clean, *Live → Compare* against
   idm254 shows only what §3.4 leaves out. N3 — a packaged driver shows its
   packages installed, nothing marked modified (the 7c check), and
   *Check for Package Updates* offers nothing wrong.
3. **Deploy from the project**: Designer deploys one policy from the new
   project to a scratch driver on idm254 and `vault.diff` against the tree
   shows exactly that change — proves the project is not just viewable.

## 5. Decisions for Jerry

1. **Scope of `AppConfig`.** Forms, PRDs and entitlements only (what the
   tree models; the rest of `AppConfig` — DirectoryModel, UIConfig,
   RoleConfig, TeamDefs, AppDefs, AuthTypes — stays out and comes from a
   later *Live → Import* of just those containers), **or** model the rest of
   `AppConfig` as opaque provisioning objects (each vault object as its
   `ds-object` document, the way Designer's `.digest` + content files hold
   them) so the tree round-trips the whole subtree and the project is
   complete. The second is more work in the reader/writer/deploy (opaque
   objects deploy as whole-attribute writes) but is what "store the app
   config elements" fully means. Recommendation: forms/PRDs/entitlements
   first (N2), opaque `AppConfig` as its own step after N3, because roles
   and resources are managed in the applications and most of that subtree
   is application state, not design.
2. **Packages (N3) as described**, from the git catalog, including the
   on-disk spike; or stop at N2 and let teams import packages in Designer
   (the project then re-associates items on *Package → Sync* — unverified).
3. **Server and vault details** on the command line (no vault password ever;
   `IdentityVaultSavePassword=false`), or none at all and let Designer ask
   on first connect. Recommendation: optional flags, default none.
4. **Command shape**: `export-project --new` (proposed) vs a separate
   `project.create`. Recommendation: the flag — it is the same writer with
   an empty "from" side.

## 6. Order and size

N1 skeleton + N2 content and `AppConfig` creation: the writer's existing
paths plus the descriptor/root/domain/application/node files — a few days
including the round-trip tests and the first Designer check. N3 packages:
one on-disk spike (a day) then the catalog-to-CObject writer — the larger
half, a week. Opaque `AppConfig` (§5.1, second option): after N3, sized when
chosen. Each milestone ends with Jerry opening the project in Designer.
