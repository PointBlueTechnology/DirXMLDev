# A fresh Designer project from a tree — design note

Status: **N1+N2+N3 built and merged 2026-09-17**; `~/designer_workspace/ig4new` = `export-project --new --catalog`
of the live-imported `tree-ig4` (19 drivers with icons, 31 packages / 218 items, `Idm:InstalledPackages` on drivers,
driver set and vault) awaits the second Designer check (§7.5). Its round trip through `import-project` differs from
the live tree only in the package stamps' representation (vault form vs export form) — content, linkage, forms,
PRDs and entitlements are identical after three fixes found by that round trip: a new driver's linkage is written
after the Library's artifacts exist (a Library ECMAScript link resolved to nothing before), `ProjectReader` reads
channel-scope resources (a subscriber mapping table was dropped), and PRD `grant`/`revoke` keep the vault's spelling.
(N1+N2 in b781d67; N3 on `n3-catalog`:
`source.ProjectCatalogWriter`, `source.DesignerInstall`, `source.ApplicationType`,
`bin/idm export-project … --new --catalog DIR`; 20 tests in `NewProjectWriterTest`, 586 green).
`~/designer_workspace/test11new` was written from `tree-test11pf` for the first Designer check (§7.2a);
round trip through `import-project` is clean. The N3 spike is
[spikes/designer-project-catalog.md](spikes/designer-project-catalog.md); what N3 built, where it
deviates and what only Designer can answer are in §7.3 and §7.4.
Confirmed 2026-09-17 (Jerry: "go with your recommendation" on every §5 point —
forms/PRDs/entitlements first and opaque `AppConfig` later; N3 packages as
described; optional vault/server flags, default none; `export-project --new`).
N3 spike running. **§7 below records what was built, where it deviates from this
note, and what only Designer can answer.** Follows
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

## 7. Built — N1 + N2 (2026-09-17)

```
bin/idm export-project tree/ <newProjectDir> --new
    [--vault-name NAME] [--vault-host HOST] [--vault-user DN]
    [--server NAME --server-context DN] [--dry-run] [--json]
```

- `source.NewProject` — the optional vault/server details; deliberately has no
  password field.
- `source.ProjectSkeleton` (N1) — `.project`, `<name>.proj`, `<name>.cproj`, the
  three `CRoot_`s, `Domain_`, `IdentityVault_`, `DriverSet_` + `Library_`,
  `Server_` (only with `--server`), `ProjectData_` + `IdmCatalog_` + the six
  stock `IdmCategory_` objects. It also mints the `ModelerNodes_` id and writes
  the diagram and `Model/Provisioning/.provisioning` at the end, once the
  `Application_` objects exist.
- `ProjectWriter.create` (N2) — builds the skeleton in a staging directory, runs
  the *same* change loop the update path runs (so every fix benefits both), then
  copies the result into place. `--dry-run` therefore reports every file it would
  write and leaves the target directory untouched (it is not even created).
- Lifted on this path only: the packaged-driver refusal, the "no AppConfig"
  refusal, and the ambiguity note on driver-set GCV linkage (the tree's
  `driverset.linkage.*` meta settles it for a new project).

Verified: the round trip of §4, check 1, passes on a synthetic project (packaged
policy, packaged entitlement, protected packaged form, bound PRD, library
ECMAScript, dangling reference placeholder) and, guarded, on
`~/IdeaProjects/DirXMLDev-e2e/tree-test11pf` (19 drivers, 4 packaged, 12 forms,
40 PRDs) — `import-project` of the written project is byte-identical as-code to
the tree, modulo the minted ids. `src/test/.../source/NewProjectWriterTest.java`.

### 7.1 Deviations from this note

1. **The `.appconfig` template is the skeleton, not test11pf's file.**
   test11pf's `.appconfig` is 931 KB — the stock `DirectoryModel` entity
   definitions, `UIConfig` nav items, `RoleConfig` report definitions and
   `AuthTypes` of one client's vault. Bundling it would ship a client artifact
   and a megabyte of content §3.4 says stays out, so
   `src/main/resources/designer/appconfig-template.xml` carries the shape and
   nothing else: the `srvprvAppConfig` ds-object with `version` (substituted) and
   the stock `srvprvPlugins`, and every top- and second-level container from
   test11pf's file (`RequestDefs`, `WorkFlowDefs`, `ResourceDefs`, `ServiceDefs`,
   `DirectoryModel` + its four, `AppDefs`, `ProxyDefs`, `DelegateeDefs`,
   `DelegationDefs`, `TeamDefs`, `RoleConfig` + its four, `AuthTypes`,
   `UIConfig` + `NavItems`), each empty. **This is the first thing for Designer
   to judge** (§7.2).
2. **The vault's default name is `Identity Vault`.** The design said "the driver
   set's tree name"; the model records the driver set's DN but never the
   eDirectory *tree* name, so there is nothing to infer — pass `--vault-name`.
3. **`Idm:ConfigExtensions`.** test11pf's driver set references every
   library-scope GCV object there, whether the driver set or the Library owns it.
   The tree cannot record that (the reader never walks `ConfigExtensions`), so a
   new project writes one `Idm:ConfigExtensions` reference per library GCV
   object — the superset test11pf has.
4. **Reference placeholders are recreated.** A tree read from a project records a
   dangling `Idm:ExtensionFunctions` target as `library/<id>` (test11pf's
   `0.`/`1.ECMAScriptResource_`). `--new` writes those `type="Ref"` stubs back so
   the linkage list survives the round trip; their `name` is a synthesized DN,
   because the original one is not in the tree.
5. **No per-server attribute sets for ordinary driver settings.** `DirXML-ConfigValues`
   (driver, driver set and GCV objects) goes in an `associatedAttrSets` block the
   way test11pf does it, but `DirXML-DriverStartOption` / `DirXML-ShimAuthID` and
   the rest are written at the top level of the CObject — where the reader finds
   them either way. Without `--server` there is no `Server_` at all and the
   per-server files are filed under a minted token; pass `--server` for a project
   Designer will connect to.
6. **Smaller fixes that fell out of this work and apply to the update path too:**
   a resource whose content type Designer does not model specially (an
   `EntitlementConfiguration`) is now written as `IDMResource` instead of being
   skipped with a note; a new driver keeps the Designer type the tree recorded
   (`designer.driver-type`) instead of guessing from a sibling's shim; a new
   driver's own `DirXML-ConfigValues` file is written; a form/PRD digest keeps
   `protected`/`readonly`/`dirguid`/`dirrev` when the tree carries them; and the
   "PRD binds a form that does not resolve" note is said once, not once per PRD.

### 7.2a Designer check — results (Jerry, 2026-09-17, `Designer-modernized`, project `test11new`)

| # | Result |
|---|---|
| 1 opens, views populated | **good** — except most drivers have **no icon** (test11pf's drivers carry `<id>_icon.gif`, copied by Designer's importer from its `com.novell.core_*/icons/iManager/<ApplicationType>.gif`; `--new` writes none, and typed the AD driver `GenericApp` because its shim is the Remote Loader's `com.novell.nds.dirxml.remote.driver.DriverShimImpl`) → fix in N3 |
| 2 pruned `.appconfig` | **good** — forms and PRDs open with the empty containers |
| 3 diagram | **good** |
| 4 no `Server_` | **fine**; a server must be added before a deploy (as expected — `--server`, or in Designer) |
| 5 packaged items, no catalog | *Packages* on the driver-set properties **first showed an "invalid values" error** — the generated `DriverSet_` has no `Idm:InstalledPackages` (nor `Idm:Servers`/`Idm:Jobs`) and the items name packages the project's catalog does not hold → N3 (the catalog + the InstalledPackages relations) is **required**, not merely nice |
| 6 Validate / Compare | appears ok |
| 7 deploy from the project | **good** |

### 7.2b Icons and driver types — resolved (2026-09-17, after the ig4new check)

Jerry's second check: everything good except the icons. Cause: Designer's icon set is
selected by the **driver type** on the `Driver_` (`AD-Driver`, `SCIM-Driver`,
`LoopBack-Driver`, … `[ANY]` for an unknown shim), and a live-imported tree records
none, so `--new` wrote `[ANY]` everywhere and the modeler drew the generic box. Now:
the type comes from Designer's driver definitions by shim class when one shim settles
it, else — for the Remote Loader proxy shim and SCIM shims, as Designer's importer
does — from the driver's **base package** (`supported-drivers/@driver-id` in the jar's
directive, read from `--catalog`); the application type follows the type; and the
`<id>_icon.gif` is chosen by application type, then driver type, then the base
package's driver type, from the provisioning plugin's `icons/iManager` before the
core's (test11pf's User Application icon is the provisioning plugin's). Verified
against Designer's own import of the same vault (test11pf): every driver's type is
identical and every icon is byte-identical except (a) three drivers whose icons in
test11pf are older files of an earlier Designer's icon set (Loopback, Gateway, Null —
ours are the current install's), (b) custom shims with no base package in the vault
(Beeline, CyberArk, EventLogger, AcctExpNotif — their test11pf icons were set when
the drivers were built in Designer; the vault never holds an icon), which get the
generic icon, and (c) two drivers Designer drew without any icon.

### 7.2 What the Designer check must look at (the code cannot)

1. **The project opens at all** and the System Model, developer view and
   Provisioning view are populated (a silent empty import is the §1.1 symptom).
2. **The pruned `.appconfig`** (deviation 1): does the Provisioning view open
   with empty `DirectoryModel` / `RoleConfig` / `UIConfig` / `AuthTypes`
   containers, and do the forms and PRDs still open in the form builder and the
   workflow editor? If Designer needs the stock content, the template grows —
   from Designer's own defaults, not from a client's file.
3. **The modeler diagram**: one node per driver, on the canvas, connected to the
   vault after a *Re-layout*; no missing-node repair prompt. (`--new` writes no
   driver-set node and no edges — §3.1 said a simple grid; whether Designer
   re-draws the edges itself is the open question.)
4. **A project with no `Server_`** (no `--server`): does Designer accept a driver
   set without one and offer to add a server, or does it repair/complain about
   the per-server `DirXML-ConfigValues` files filed under a token with no
   matching object?
5. **Packaged items without the catalog**: confirm the 7c observation — the
   items show as plain (not as *modified*), *Check for Package Updates* offers
   nothing wrong, and importing the packages in Designer afterwards
   re-associates them (this is what decides whether N3 is required or merely
   nice).
6. ***Project → Validate*** is clean, and ***Live → Compare*** against the source
   vault shows only what §3.4 leaves out (jobs, notification templates, schema,
   the rest of `AppConfig`, packages).
7. **Deploy one policy** from the new project to a scratch driver and
   `vault.diff` against the tree — §4.3, the proof the project is not merely
   viewable.

### 7.3 Built — N3 (2026-09-17)

```
bin/idm export-project tree/ <newProjectDir> --new
    [--vault-name NAME] [--vault-host HOST] [--vault-user DN]
    [--server NAME --server-context DN]
    [--catalog DIR]        # the git package catalog: fills the project's own catalog
    [--designer DIR]       # the Designer install driver icons are copied from
    [--dry-run] [--json]
```

- **`source.ProjectCatalogWriter`** — the project's own package catalog, written from the
  jars `--catalog` holds, exactly as [spikes/designer-project-catalog.md](spikes/designer-project-catalog.md)
  read it off `test11pf`: the `IdmCategory_` chosen by `package/@category`, an
  `IdmCategoryFolder_` per `@category-folder` (`Idm:Packages` Child relations), the
  `IdmPackage_` with the spike's attribute table (only non-empty fields,
  `Idm:PackageImported=true`, `Idm:InstallationDirective` as `PackageJar.directive` holds it),
  the heavy-data files (`<K>_license.xml`, `<K>_readme.txt`, `<K>_change.log`,
  `<K>_<lang>.properties`), all nine `IdmPackageFolder_` with `Idm:FolderId` even when empty,
  and one CObject per item (`DirXML-Rule` → `ScriptPolicy_`/`StylesheetPolicy_`/`MappingPolicy_`
  by content, `DirXML-Resource` → `IDMResource_` with `DirXML-ContentType` and
  `Idm:PkgPromptType`, `DirXML-GlobalConfigDef` → `GlobalConfig_`) carrying
  `Idm:PackageGuid`/`PackageAssocGuid`/`ContentChecksum`/`DirectiveChecksum`/`InstallationDirective`
  verbatim, plus `<I>_contents.xml` when the jar item has content. Then the relations:
  `Idm:InstalledPackages` Reference on each `Driver_` (type 2), on `DriverSet_` (type 3) and on
  `IdentityVault_` (type 4), with the `Idm:InstalledPackageDriverRefs` /
  `Idm:InstalledPackageDSetRefs` / `Idm:InstalledPackageIVRefs` back-references.
- **Refusals.** With `--catalog`, a package the tree names that the catalog does not hold
  refuses the whole run and lists every missing one (short name + version + id, as far as the
  tree records them); an item class outside the table above refuses and names the class, the
  item and the package rather than guessing a CObject type. Nothing reaches the target
  directory in either case — everything is built in a staging directory first.
- **Without `--catalog`** nothing changed: no `IdmPackage_` objects, and the result note names
  the packages Designer will therefore not associate.
- **`source.DesignerInstall`** — driver icons. Designer's own vault importer copies
  `plugins/com.novell.core_<ver>/icons/iManager/<ApplicationType>.gif` to
  `<driverId>_icon.gif` beside the `Driver_`, with a
  `<attributes xsi:type="…CHeavyData" attrName="icon" extension="gif"/>` attribute; `--new`
  does the same, falling back to `GenericApp.gif`, and says once in the result when no install
  was found. The install is found the way `form.edit` finds the form builder: `IDM_DESIGNER`,
  then the `designer` system property, then the platform's default roots (`--designer`
  overrides all three). Nothing else is ever read out of the install, and an icon is never
  written into a tree.
- **`source.ApplicationType`** — the `Application_` type. The old switch typed the Active
  Directory driver `GenericApp` because its `DirXML-JavaModule` is the Remote Loader proxy
  `com.novell.nds.dirxml.remote.driver.DriverShimImpl`. Two tables, both read out of a Designer
  install's own `defs/model_items/Drivers/*.xml` (`<driver type=… primaryApp=…>` with
  `<supported-shims>`): shim class → application type for the Java shims that map to exactly one
  application, and Designer driver type → application type for all 79 driver types. A
  Remote Loader driver has no shim class to go on, so the tree's `designer.driver-type`
  (`AD-Driver`) settles it — which is how the AD driver now comes out `ActiveDirectory`, as
  `test11pf` has it.

### 7.4 Deviations from this note and from `test11pf` (N3)

1. **The tree may not record a package version.** A tree read from a *project* records only
   `package-id` on its items (Designer's project format keeps no version there); a tree read
   from a *vault* records the whole five-field `dirxml-pkgguid`
   (`id;symbolic-name;version;name;SHORT`). So the catalog lookup is by id + version when the
   tree has a version and by id alone — taking the catalog's newest version of that package —
   when it does not. The refusal list says `(version not recorded in the tree)` in that case.
2. **`<K>_change.log` is generated, not copied.** No jar carries a change log: it is the
   *project's* local history of that package, and Designer's importer writes exactly three
   lines ("Setting package imported flag to 'null'", "Changed installation directive for …",
   "Changed license for package …") stamped with the import time. `--new` writes the same three
   lines with the current time. Every package in `test11pf` has exactly those three.
3. **A package whose `@category` is not one of Designer's six gets a seventh `IdmCategory_`.**
   Locally built packages do (`package.build` writes `category="Custom"`), so refusing would
   make a Point Blue package unusable. The new category object has the same shape as the stock
   six and the result notes it.
4. **`provext` is not written.** Two of `test11pf`'s notification-template packages carry a
   `<K>_provext.xml` heavy-data file (`<prov-extensions packages="true"/>`); none of the jars
   in the e2e catalog has a package-level element it could come from, so the source is
   unidentified and nothing is invented.
5. **A text-only `XmlData` is written as a contents file.** The spike's rule is "a contents
   file iff the item's content element is non-null". An item whose `XmlData` is text rather
   than an element (an ECMAScript resource, say) does not occur in `test11pf`'s two packages;
   `--new` writes the text as `<I>_contents.xml` rather than dropping it, which is inference,
   not observation.
6. **Two cosmetic escaping differences**, both pre-existing and both parsing back identically:
   an attribute value that embeds XML escapes `>` as `&gt;` and newlines/tabs as `&#10;`/`&#9;`,
   where Designer writes `>` and `&#xA;`/`&#x9;`. With those normalized, a package written from
   `NOVLEDIRBASE 2.1.2.20190219130306` / `NOVLEDIRDCFG 2.1.0.20120831225140` is **identical to
   `test11pf`'s own copy** (`80FZS91C` / `8QA7Z65G`): same attribute set, order and values on
   the package and on every item, the same nine folders, the same item CObject types, and
   byte-identical `_license.xml`, `_readme.txt`, `_<lang>.properties` and `_contents.xml` files.
   Only the minted ids differ.
7. **SCIM drivers keep `GenericApp`.** Designer's driver definitions map `SCIM-Driver` to a
   `SCIM` application type, but `test11pf`'s `MITLL-Druva` — a `SCIM-Driver` — has a
   `GenericApp` application, so `SCIM-Driver` is deliberately absent from the driver-type table
   and a SCIM shim class returns `GenericApp`.
8. **The driver set carries only what the tree records** (deviation for §4 of the N3 brief).
   `test11pf`'s `DriverSet_` also holds `DSetCreatePartition`, `DirXML-LogEvents`,
   `DirXML-JavaDebugPort`, `DirXML-JavaTraceFile`, `DirXML-LogLimit`, `DirXML-TraceSizeLimit`,
   `DirXML-XSLTraceLevel`, `JavaEnvParameters` and `NamedPasswords`. A tree's `driverset.xml`
   records the name, the DN and the config values and nothing else — checked on
   `tree-test11pf`, `tree-7c`, `tree-ig4` and `tree-idm254`, none of which carries a single
   driver-set setting. They are left absent rather than invented, and the result says so in a
   note; set them in Designer (or teach the readers to record them, which is a separate change
   to the vault/project readers, the as-code format and `vault.diff`).
9. **The update path is unchanged.** A catalog and an icon are `--new` things: an existing
   project already has Designer's icons and its own `IdmPackage_` objects, and `update` still
   refuses to add a packaged driver at all. A driver `update` *does* add now gets a note saying
   Designer will draw its icon.
10. **`Idm:Jobs` and `Idm:Servers` on the driver set** are still absent (jobs are out of scope
   per §3.4; a `Server_` only exists with `--server`). Point 5 of the first Designer check
   named all three as the cause of the "invalid values" error on the Packages page; N3 fixes
   the `Idm:InstalledPackages` half, which is the one that page is actually about.

### 7.5 What the Designer check must look at (N3)

On a project written with `--new --catalog` (a driver set whose packages the catalog holds —
the e2e pair is `tree-7c`'s `PkgTest7` with `NOVLEDIRBASE` + `NOVLEDIRDCFG`):

1. **The driver-set properties → *Packages* page opens without the "invalid values" error**
   that point 5 of §7.2a hit, and lists the driver-set-level packages.
2. **Each driver's properties → *Packages* page** shows its packages **installed**, with
   **nothing marked modified** (the 7c check): the `Idm:InstalledPackages` reference, the
   `IdmPackage_` in the project catalog and the item stamps must agree.
3. ***Help → Check for Package Updates*** (or *Package Catalog → Check for updates*) offers
   **nothing wrong** — no spurious "newer version available" for a package written at the
   version the tree names, and no complaint about a package that is not in the workstation
   catalog.
4. **The Package Catalog view** (Project → *Package Catalog*) shows the packages under the
   right category and category folder, with their readme, licence and change log readable in
   the package properties, and the package's content tree (Policies / Resources / Global
   Configurations) populated.
5. **Every driver now has an icon** in the modeler and the outline, and the Active Directory
   driver's is the Active Directory one, not the generic application icon.
6. **A package upgrade still works**: *Package → Upgrade* on a driver in the new project
   offers the newer version from the workstation catalog and applies it.
