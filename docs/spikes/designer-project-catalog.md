# Spike: a package inside a Designer project's catalog, from its jar (2026-09-17)

Question (milestone N3 of [../designer-new-project.md](../designer-new-project.md)):
how does Designer lay a package out inside a project's own catalog
(`Model/Project/…/IdmCatalog_`), and can that be written from the package
jar our git catalog holds? Read-only, on `~/designer_workspace/test11pf`
(Designer 4.8.7) against the same package versions in
`DirXMLDev-e2e/catalog` (`NOVLEDIRBASE 2.1.2.20190219130306`,
`NOVLEDIRDCFG 2.1.0.20120831225140`).

Answer: **yes — it is a one-to-one rendering of `package_import.xml`, which
`PackageJar` already parses**, plus the relation from each installing driver.
Nothing in the project catalog comes from anywhere else.

## The package object

`Model/Project/<P>/<C>/<Cat>/<Folder>/<K>.IdmPackage_` where `<Cat>` is one
of the six fixed `IdmCategory_` objects (*Common, Directory, Notification,
Provisioning, Service, Tool*) chosen by `package/@category`, and `<Folder>`
an `IdmCategoryFolder_` named by `package/@category-folder` (created when
absent; it lists its packages as `Idm:Packages` Child relations).

| `IdmPackage_` attribute | from `package_import.xml` |
|---|---|
| CObject `name` | `package/@name` |
| `Idm:PackageGuid` | `@id` |
| `Idm:PackageVersion` | `@version` |
| `Idm:PackageType` (CInteger) | `@type` (2 driver, 3 driver set, 4 vault) |
| `Idm:BasePackage` (CBoolean) | `@base-package` |
| `Idm:ContentChecksum` / `Idm:DirectiveChecksum` (CLong) | `@checksum` / `@directive-checksum` |
| `Idm:description`, `Idm:shortName`, `Idm:BuildTime`, `Idm:BuildHost`, `Idm:BuildUser`, `Idm:Released`, `Idm:Protected`, `Idm:newVersion`, `Idm:CreationTime`, `Idm:vendorName`, `Idm:minIdmVersion`, `Idm:InternalVersion` | the `idm-*` / `description` children, same names (`idm-vendoraddress`/`url`/`email`, `idm-contactname`/`email`, `idm-maxidmversion`, `idm-min/maxappversion` when non-empty — check which empties Designer still writes) |
| `Idm:InstallationDirective` (CString, the whole XML with declaration) | `idm-installationdirective/installation-directive`, serialized the way `PackageJar.directive` already holds it |
| `Idm:PackageImported` (CBoolean) | always `true` |
| heavy data `license` → `<K>_license.xml`, `readme` (`extension="txt"`) → `<K>_readme.txt`, `change` (`log`) → `<K>_change.log`, one `<lang>` (`properties`) per `properties` child → `<K>_<lang>.properties` | `license`/`readme` (base64), the change log, `properties[@lang]` |
| `Idm:PackageFolders` Child → nine `IdmPackageFolder_`, **always all nine**, even when empty | `package-folder/@id` and `@name`: 1 Policies, 2 Resources, 3 Jobs, 4 Entitlements, 5 Provisioning, 6 Files, 7 Notification Templates, 8 ID Policies, 9 Global Configurations |
| `Idm:InstalledPackageDriverRefs` BackReference → every `Driver_` whose `Idm:InstalledPackages` Reference names this package | the tree's driver stamps (`dirxml-pkgguid` first field = `Idm:PackageGuid`) |

The package's vault-level and driver-set-level counterparts hang off
`IdentityVault_` / `DriverSet_` `Idm:InstalledPackages` the same way (type 4
and 3).

## The folders and items

`<K>/<F>.IdmPackageFolder_`: CObject `name` = folder name, `Idm:FolderId`
(CInteger) = id, one Child relation per item whose relation name follows the
item type (`Idm:Policies`, `Idm:Resources`, `Idm:GlobalConfigs`, …).

Each `ds-object` child of a folder becomes `<K>/<F>/<I>.<Type>_`:

| `ds-object-class` | CObject type | payload |
|---|---|---|
| `DirXML-Rule` | `ScriptPolicy_` (DirXML Script), `StylesheetPolicy_` (XSLT), `MappingPolicy_` (schema map) — by content, as `ProjectReader` already derives | `<I>_contents.xml` = the `XmlData` element (`<policy>` …), canonical XML |
| `DirXML-Resource` | `IDMResource_` with `DirXML-ContentType` = `@DirXML-ContentType` (filter-ext, mapping-table, ECMAScript, pkg-prompt, …) | `<I>_contents.xml` = the `XmlData` element — **except prompts** (`pkg-prompt+xml`, `Idm:PkgPromptType` = `idm-pkgprompttype`), which carry no contents file |
| `DirXML-GlobalConfigDef` | `GlobalConfig_` | no contents file — the GCV definitions travel inside the item's `Idm:InstallationDirective` (`<configuration-values>` under the directive) |
| jobs, entitlements, notification templates, provisioning items, ID policies | `Job_`, `Entitlement_`, `NotfTemplate_`, … — **not observed** in these two packages; read one such package before writing them |

Every item CObject carries `Idm:PackageGuid`, `Idm:PackageAssocGuid`,
`Idm:ContentChecksum`, `Idm:DirectiveChecksum` and `Idm:InstallationDirective`
= the item's `idm-packageguid`, `idm-packageassocguid`,
`idm-contentchecksum`, `idm-directivechecksum` and decoded
`idm-installationdirective` from the jar, verbatim.

## How it ties to the vault and the tree

For `NOVLEDIRDCFG-pub-pp` on ig4: the vault's `DirXML-pkgAssociationId`
(`5C8LU4QR_201008101717250632`) **is** the project item's
`Idm:PackageAssocGuid`, and the first field of `DirXML-pkgGUID`
(`RRKB9O08_201008101523510733`) is `Idm:PackageGuid`. The vault's
`DirXML-pkgChecksum` (`1137138532`) is the CRC of the vault content and
differs from the item's `Idm:ContentChecksum` (`3732451646`), which is the
package's stored checksum — the pair spike 2b described. So the installed
item in `Model/EdirOrphan/…` and the catalog item in `Model/Project/…` share
guid + association id and nothing else; the writer keeps them apart.

## What N3 therefore needs

1. `PackageJar.read(jar)` — exists — for every distinct package the tree's
   stamps name; refuse with the missing list otherwise.
2. A `ProjectCatalogWriter`: category/folder lookup-or-create, the
   `IdmPackage_` + heavy-data files, nine folders, one CObject per item with
   the class→type table above (refuse an item class outside the table with a
   note naming it), `_contents.xml` for rules and non-prompt resources.
3. `Idm:InstalledPackages` on `Driver_` (type 2), `DriverSet_` (3),
   `IdentityVault_` (4) from the stamps, with the back-references.
4. Round trip: `import-project` of the result must read the drivers' package
   stamps back as it does from a Designer-made project; then Jerry's check
   (packages installed, nothing modified, *Check for Package Updates* quiet).

Closed the same day: Designer writes **only non-empty** package fields (no
`Idm:vendorAddress`/`vendorUrl`/`vendorEmail`/`contact*`/`maxIdmVersion`/
`min|maxAppVersion` when the jar's elements are empty). A prompt item has a
`contents` heavy-data attribute and `<I>_contents.xml` **iff** the jar's
`ds-object` carries `XmlData` (`NOVLEDIRDCFG-SynchronizationPrompts` does,
`NOVLEDIRBASE-UpgradeSettings` does not — its definitions live only in the
item's 14–17 KB `Idm:InstallationDirective`); so the writer's rule is "a
contents file when `PackageJar.Item.content` is non-null", for every class.

Still open: the CObject types of the unobserved item classes (jobs,
entitlements, notification templates, provisioning items, ID policies) —
read one package that carries them before writing them.
