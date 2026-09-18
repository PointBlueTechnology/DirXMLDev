# Phase 7 — Packages: our own package management (design note, 2026-09-09)

Jerry's decision (2026-09-09): **do not rely on Designer's headless application;
build our own package management.** Package definitions are downloaded from
the update site and kept in git; the tree installs, upgrades and uninstalls
them; customized configuration can be turned into packages of our own.

Evidence this note rests on: [spikes/package-format.md](spikes/package-format.md)
(update site, jar types, what the vault records) and
[spikes/designer-package-layer.md](spikes/designer-package-layer.md) (Designer
4.10.1's package layer: checksums verified by recomputation, install/upgrade
mechanics, dependency resolution, package building). Facts are cited by their
number in that report's "Facts DirXMLDev's design must respect" list (F1–F16).

## 1. Principles

- **The tree stays the source of truth.** A package install is an edit
  transaction like any other: load → apply → validate → write only if no new
  error. Deploy, diff, simulate, rollback and the production gate are
  unchanged; packages give them more to carry, not a second path.
- **Designer compatibility is the acceptance test, not the mechanism.**
  Whatever we install must look, to Designer and to the vault, exactly like a
  Designer install (F9): the same per-object stamps, the same installed
  checksum, the same linkage records, the same filter-extension cache. A
  Designer that later imports the vault must recognize every package and every
  customization. Same for a package we build: Designer must import the jar
  and install it (F15).
- **Packages live in git.** The catalog is a repository of package jars plus a
  deterministic unpacked form for review; the tree records which package
  versions it has. Unlike Designer's per-workstation catalog, the history of
  what was fetched, when, and from where is versioned, and the catalog can be
  served back as an update site (§3.6).
- **No Designer classes at runtime.** Checksums use the engine's own
  `nxsl.jar` (already in `lib/`: `DOMUtil.stripWhitespace`, `DOMWriter` —
  F1), the same jar Designer uses; prompts are XSLT run through the engine's
  processor we already ship for the simulator. Nothing from `idm.jar` or
  `packagemanager.jar` is needed.

## 2. The catalog repository

A separate git repository (one per consultancy, shared across clients; client
trees reference it by path/URL), created and maintained by `idm package.*`:

```
idm-packages/
  catalog.json                       generated index: SHORT → versions → {id, symbolic, type, base, deps, features, sha256, source, fetched}
  sites.properties                   update sites: name=url (defaults: nu.novell.com updatesite1_0_0 / updatesite2_0_0)
  jars/<SHORT>/<SHORT>_<ver>.jar     the package exactly as downloaded or built (Designer needs this file — F9)
  packages/<SHORT>/<ver>/            the unpacked, diffable form (generated; never hand-edited)
    package.xml                      metadata + installation directive (features, dependencies, supported drivers, driver ds-attributes)
    objects/<folder>/<name>.xml      one canonical file per ds-object, with its decoded directive and stored checksums in a sidecar meta
    prompts/<name>.xml               pkg-prompt resources (definitions + the two stylesheets)
    README.md                        the package's readme/license, decoded
  deprecations.properties            mirrored from the site
```

Jars are small (tens of KB; the whole 1,609-jar Designer catalog is ~120 MB),
so keeping every version fetched is fine. The unpacked form exists so that
`git diff packages/NOVLADDCFG/2.5.2…/ packages/NOVLADDCFG/2.5.3…/` answers
"what changed in this package version" in policy terms, and so the installer
reads canonical files, not base64 blobs.

## 3. Commands

### 3.1 Catalog

- `package.fetch --catalog DIR [--site NAME|URL] [--short SHORT[_ver]…] [--latest|--all-versions] [--dry-run]`
  reads `site.xml` → feature jar → `plugins/SHORT_ver.jar` (F16), verifies the
  jar parses and its stored checksums recompute (F1, F5) before adding it,
  unpacks it, updates `catalog.json`, and prints what was added. Nothing is
  removed by a fetch; deprecations are recorded, not acted on.
- `package.import --catalog DIR <jar|Designer packages dir>` — the same for a
  local jar or a whole `<Designer>/packages/eclipse/plugins`, to seed the
  catalog offline from a Designer install.
- `package.list --catalog DIR [--driver-type ID] [--type 2|3|4] [--base]`,
  `package.show --catalog DIR SHORT[_ver]` (id, type, base, supported drivers,
  features mandatory/optional, dependencies, prompts, objects with placement
  and weight), `package.diff --catalog DIR SHORT_v1 SHORT_v2` (object-level:
  added/removed/changed objects, directive changes, driver ds-attribute
  changes — `ModelDiff` vocabulary).
- `package.resolve --catalog DIR --base SHORT[_ver] [--feature SHORT…] [--driver-set-has …]` —
  the dependency closure per F14: exact-list / min–max / any version matching,
  newest first, features only for base packages, type-3/4 dependencies
  reported as "satisfied by driver set/vault" or missing. Prints the ordered
  install list; the installer refuses anything the resolver didn't approve.

### 3.2 Install into the tree

- `package.install tree/ --jar a.jar[,b.jar…] | --catalog DIR --package SHORT[_ver][,…] --driver D [--answers FILE] [--new-driver true] [--dry-run|--force|--json]`
  (type 2, onto an existing driver; several packages in **one transaction**,
  base first — Designer installs the resolved set and validates afterwards,
  and a common package's policies may reference GCVs its companion
  defines); without `--driver` a type-3 package installs into the Library
  (placement `context="driver-set" location="library"`); `--vault` (type 4:
  notification templates) is deferred, see §7. **Built 2026-09-10; parity
  with Designer proven ([spikes/package-install-parity.md](spikes/package-install-parity.md)).**
- `driver.add tree/ --name N --packages base.jar,feature.jar… | --catalog DIR --package SHORT[,…] [--answers FILE]`
  (built; `--base SHORT --feature …` resolves through the catalog once the
  resolver lands) — a new packaged driver: the base package's driver `ds-attributes` build the
  driver (`name`, shim from `configuration-manifest`/`shim-config-info`,
  `global-config-values`, filter, options), then the resolved packages install
  onto it. This replaces the Designer-headless idea in the PDT analysis.
- `package.prompts --catalog DIR SHORT[_ver] [--driver D] [--out FILE]` writes
  the answers template (every prompt definition with display-name, name, type,
  default) so an agent knows what to answer; `--answers` is keyed by
  definition **name** with display-name accepted as a fallback (Designer's
  headless keys by display-name — F13); password-ref answers reference the
  environment's secrets file by key, never a literal.

What install does, per F2/F8/F9/F11/F12/F13 (in this order, as Designer does):

1. Resolve; refuse if a base package is already installed on the target, a
   dependency is unsatisfied, or the supported-drivers list excludes the
   driver's shim.
2. Run prompts: the prompt stylesheet pre-populates definitions, answers are
   applied, the target stylesheet transforms each target item's directive
   (and non-GCV items' content) with `$defsDoc`/`$curDoc`/`$npDoc`.
3. Apply driver `ds-attributes` by name: filter, GCVs, shim config, manifest
   are **set**; engine control values are **merged** (F12).
4. Create objects at their placement (driver / subscriber / publisher / library),
   content from the package (post-transform), stamped in the artifact's meta
   in the vault's own names (`docs/model.md`, "Package stamps"):
   `dirxml-pkgguid` (the 5-field record `id;symbolic;version;name;SHORT`),
   `dirxml-pkgassociationid`, `dirxml-pkgchecksum` (the **installed** checksum — content plus
   linked set names, recomputed after linking, F2), `pkg-linkages` (the
   `<policy-linkage>` record with `Driver=` the driver's `designer.id`, minted
   if absent — the same 8-char id the project writer uses), and the initial
   state in `.package-baseline/<path>` (which is exactly `DirXML-pkgInitialState`,
   F6 — one mechanism, not two).
5. Link by weight: insert before the first strictly greater weight; existing
   non-package policies count as −1 so package policies land after hand-made
   ones; ties keep install order; `First`/`Last`/`Before`/`After` as in F8.
   GCV objects link into the driver's GCV set (`gcv`, weight) — they are
   separate objects, not merged into the driver's values.
6. Filter-ext resources are never objects: merge class/attribute entries by
   precedence (sync/app > notify/edir > ignore/default > reset/none) into the
   driver's filter and maintain the extensions cache (`DirXML-pkgExtensions`,
   F11) in the driver's meta so an uninstall removes exactly its entries.
7. Record the install in the driver's manifest (`package.installed.<SHORT>` =
   the 5-field record, `;base` for the base package; named passwords the
   packages expect in `package.named-passwords`) — the tree's
   own installed-package record, since the vault has none (F9); validate;
   write.

### 3.3 Upgrade, downgrade, uninstall

**Built 2026-09-10** (`package.upgrade --driver D --jar new.jar | --catalog DIR --package SHORT_ver [--answers] [--yes]`,
`package.uninstall --driver D --package SHORT [--yes] [--all]`; 6 lifecycle tests on
the eDirectory set). Rules as implemented, from Designer's code: uninstalling a
package removes only the link entries its own directives own (stamped with
its package id in `DirXML-pkgLinkages`), wherever they are — so removing the
driver-specific companion unlinks the common package's policies it had
linked, and leaves the common package's own GCV link alone; on upgrade a
customized object keeps its content and mark while its baseline and stamps
move to the new version; filter extensions are re-merged only for changed
classes/attributes when the old jar is in the catalog, fully otherwise; a
dependant package refuses the uninstall unless `--all`; the base refuses
while features remain unless `--all`. Not yet exercised: matching across
versions by container (association id or name only), re-applying other
packages' linkage after an upgrade (implemented, untested), driver-level GCV
value merge on upgrade.

- `package.upgrade tree/ --catalog DIR --driver D --package SHORT[_ver] [--answers FILE]`
  (and `--downgrade`, the same with an older version): Designer's mechanics
  (F7) made explicit and shown first as a `ModelDiff`: objects matched across
  versions by association id (rename-aware) or by name within the same
  container are **kept**; a matched object that is *not* customized gets the
  new content; a **customized** one keeps its customized content while its
  baseline and stored checksum move to the new version (F6 — no merge exists
  in Designer either; `package.diff` old→new plus `package.diff` baseline→
  customized is what the agent reads to decide whether to re-apply the
  customization); unmatched old objects are deleted; linkage is rebuilt from
  the new directives; driver ds-attributes re-applied per F12. `--dry-run`
  prints the plan; the transaction refuses on new validation errors.
- `package.uninstall tree/ --driver D --package SHORT` removes its objects,
  its filter entries (from the extensions cache), its GCV links and its
  manifest record; refuses if another installed package depends on it, or if
  it is the base package while feature packages remain (`--all` removes the
  closure). Customized objects are reported and removed only with `--yes`
  (their content stays in `.package-baseline/` history via git).
- `package.status tree/ [--driver D] [--catalog DIR]` — per target: installed
  packages (from the manifest, cross-checked against object stamps — a
  mismatch is reported, since vault-imported trees have stamps but no
  manifest record until `package.adopt` writes one), customized objects
  (`pkg-checksum != recomputed`, F6), and with a catalog: newer versions
  available and whether each installed version is present in the catalog
  (F9: Designer needs that jar).
- `package.adopt tree/ --catalog DIR` — for trees imported from a vault or a
  project: derive the installed-package records from object stamps and write
  the manifest entries, fetching any version the catalog lacks.

### 3.4 Vault side

**Built 2026-09-10 ([spikes/package-vault-stamps.md](spikes/package-vault-stamps.md)).**
The deployer already writes content and, for customized objects, a
CRC32-of-content checksum. It is now package-faithful: `DirXML-pkgGUID`,
`DirXML-pkgAssociationId`, `DirXML-pkgChecksum` (the installed number, F2),
`DirXML-pkgLinkages`, `DirXML-pkgInitialState` from the baseline, the driver's
`DirXML-pkgGUID` (base only) and `DirXML-pkgExtensions` — all from artifact and
driver meta, so an object we install is indistinguishable from one Designer
deployed. The readers (live/LDIF/project) already carry the stamps into meta;
`VaultMapping` gains the reverse direction. Today's "CRC32-of-content" for
customized objects is replaced by the real recipe (F1–F3) — Designer's
inequality test stays satisfied.

### 3.5 Building packages from customized configuration

**Built 2026-09-10.** `package.build --catalog DIR tree/ --driver D --short SHORT --name "…" --vendor V --version M.m.r [--include PATH…] [--new-version-of SHORT_ver|jar] [--depends SHORT…] [--gcvs referenced|all|none] [--customized keep] [--base]`

Turns tree content into a package jar (F15: ids `XXXXXXXX_yyyyMMddHHmmssSSSS`,
version `M.m.r.yyyyMMddHHmmss`, symbolic `com.<vendor>.<short>`, MANIFEST /
plugin.xml / package_import.xml, stored checksums per F1–F5 computed with the
same code the installer uses; no signature). Contents:

- **Non-packaged artifacts** the driver (or the Library, with `--library`)
  owns: policies, stylesheets, ECMAScript, mapping tables, GCV objects, each
  with a directive derived from where it sits — placement from its scope,
  `<policy-set … order="Weight" value=N>` with N chosen so a fresh install
  reproduces the tree's order relative to the packaged neighbours (the
  installer's own weight rule, run backwards), and the object's
  `idm-packageassocguid` minted once and **kept** across versions
  (`--new-version-of` reuses the previous version's ids so upgrades match by
  association id — F7).
- **Filter additions** as a `filter-ext+xml` resource; **schema-map and other
  driver-scope policies** as objects; **GCV definitions** as a GCV object
  (values are prompts if `--prompt` names them, else shipped as defaults —
  F3: values never enter a checksum).
- **Customized packaged objects are not included by default.** Designer's
  model gives an object one owning package (`DirXML-pkgGUID` is single-valued),
  so a vendor policy we modified cannot become "ours" while staying the
  vendor's. `package.build` lists them and refuses unless `--customized keep`
  leaves them as customizations (noted in the result). `--customized copy`
  (clone under `SHORT-<original>` after the original, unlink the original)
  is designed but **not built yet** — it is a tree edit, not a build option,
  and waits for a real case.
- **Driver-level GCVs the content reads** (`~name~`, `if-/token-global-variable`)
  that live in the driver's own config values ship as a `SHORT-GCVs` object
  with their current values as defaults (`--gcvs referenced`, the default;
  `all` ships every definition; `none`). Definitions that come from another
  package or the driver set are reported, not shipped — the package depends
  on them. Proven: the eDirectory driver's two hand-made policies + 5 GCVs
  build, pass Designer's import checks (every stored checksum recomputes),
  install onto a fresh package-built driver with identical content, and a
  new version reuses the package id and association ids (`PackageBuildTest`).
- Dependencies: the base package and every package whose objects the included
  content links or references (`Refs`), as `<dependency>` entries with the
  installed versions as `min-version`; supported drivers from the driver's
  shim.

The built jar goes into the catalog (`jars/SHORT/…`, unpacked form, index) and
is installable by `package.install` and by Designer (acceptance test §5).

### 3.6 Serving the catalog

**Built 2026-09-10.** `package.site --catalog DIR --out DIR [--description …]` renders the Eclipse update-site layout
(F16: `site.xml`, `features/SHORT.feature_ver.jar`, `plugins/SHORT_ver.jar`,
`deprecations/`) from the catalog. Published as a static directory (GitHub
Pages, an internal web server, or `file://`), it is a package site Designer
users add under Package Manager → Online Updates — so a client's Designer gets
our packages the normal way, and F9's requirement (the jar must be in every
importing Designer's catalog) is met by pointing Designer at the site.

## 4. Checksums (the load-bearing piece)

`packages.PackageChecksum` implements F1–F5 exactly: CRC32 over UTF-8 of
`name` + canonical XML (`nxsl` parse → `DOMUtil.stripWhitespace` → `DOMWriter`
with declaration, tab indent, no trailing newline) + per-type suffix
(resources: content type; policies/ECMAScript/GCV objects: the linked
policy-set names in `addPolicySetRefs` order when installed; GCV objects:
definitions sorted by name, never values; templates/jobs/entitlements/idPolicy
per §1.3 of the research, marked inferred until recomputed). Directive checksum
= CRC32 of the stored directive string; package checksum = CRC32 of folder
checksums 1..8, folder = CRC32 of children's stored checksums sorted by
association id. **Spike 7a done (2026-09-10,
[spikes/package-checksums.md](spikes/package-checksums.md)): exact for every
kind except jobs (1/30, recipe still inferred; stored value carried through)
and 29 stale package-level values Designer never validates.**

## 5. Verification

1. **7a — checksum recompute** over all 1,609 catalog jars: every stored
   `idm-contentchecksum`, `idm-directivechecksum`, `checksum` and
   `directive-checksum` must recompute exactly (GCV objects reported
   separately; F3 says Designer repairs those silently, so a miss there is a
   warning, elsewhere a bug). Then the installed recipe against test11 and the
   test vault: for every non-customized object, `DirXML-pkgChecksum` must
   equal our installed checksum.
2. **7b — install parity** ✅ (2026-09-10, [spikes/package-install-parity.md](spikes/package-install-parity.md)):
   the eDirectory driver's four packages installed with `driver.add
   --packages` match Designer's `test11` driver object for object (18/18
   installed checksums, every set in the vault's order). A feature package
   onto an existing driver is covered by the same path (`package.install`).
3. **7c — Designer accepts our work** (Jerry in Designer): (i) import the test
   vault after we deploy a package-installed driver — Designer shows the
   packages installed, nothing "modified"; (ii) add our rendered site as a
   package site, install a `package.build` jar onto a driver, compare with the
   tree; (iii) upgrade a driver in Designer and with us, diff the two results.
4. **Round trip through the vault**: deploy → `import-live` → `package.status`
   agrees with what was installed.

## 6. Safeguards

- Catalog integrity: every jar's sha256 and stored checksums verified on
  fetch/import; the unpacked form is regenerated, never edited; `catalog.json`
  is generated.
- Every tree change is a transaction (validate, refuse on new errors,
  `--dry-run`, `--json`); upgrade/uninstall print their `ModelDiff` first;
  customized content is never silently overwritten (F6) and never deleted
  without `--yes`.
- `package.build` refuses to include secrets (password-ref values become
  prompts) and refuses customized vendor objects unless told how.
- Deploy is unchanged: package changes reach a vault only through
  `vault.deploy` with its snapshot, verify, audit and production gate.

## 7. Not in scope now

Type-4 vault packages (notification templates; different object model, Track
P territory), jobs, entitlements and ID policies as package contents
(checksum recipes inferred, not verified — they are read and preserved, not
installed until 7a covers them), Designer-side `IdmPackage_` CObjects in the
project writer (the writer refuses packaged drivers today; extending it to
write `Idm:InstalledPackages` is Phase 6 follow-up once 7b passes), and
publishing to nu.novell.com (not ours).

## 8. Build order

1. ✅ **`PackageChecksum` + spike 7a** (2026-09-10; the whole local catalog recomputes).
2. ✅ **Catalog**: `PackageJar` reader/writer, unpacked form, `catalog.json`,
   `package.fetch|import|list|show|diff|resolve` (Sonnet, merged 2026-09-10;
   `package.diff` with `--catalog` = two package versions, without = an
   artifact vs its baseline).
3. ✅ **Installer** into the tree as a transaction (`package.install`,
   `driver.add --packages`, prompts as XSLT via the engine's processor,
   weights, package-level linkage, filter merge, stamps, manifest record)
   + spike 7b (2026-09-10).
4. ✅ **Vault stamping** in the deployer + reader reverse mapping; round trip
   on the test vault; `package.status|adopt` (2026-09-10).
5. ✅ **Upgrade/downgrade/uninstall** (Sonnet, merged 2026-09-10).
6. ✅ **`package.build` + `package.site`** (2026-09-10); then 7c with Jerry
   (inputs: `~/IdeaProjects/DirXMLDev-e2e/site` as a package site, the
   `PkgTest7` driver on the test vault); docs, skill, agent guide; plan closed.

## 9. Decisions to confirm

1. **Native, no Designer at runtime** — Jerry's call (2026-09-09); this note.
2. **The catalog is a separate git repository** holding jars *and* the
   unpacked form, shared across clients, and renderable as an update site.
   Alternative: catalog inside each client tree — rejected: packages are not
   client content, and the jar must be shared with Designer users anyway.
3. **The tree's `.package-baseline/` is the initial state** (one mechanism);
   the installed-package record lives in the driver manifest; object stamps in
   artifact meta; the deployer writes all `DirXML-pkg*` attributes.
4. **Customized vendor objects are not packaged by default**; `package.build
   --customized copy|keep` is explicit. Alternative — "our package re-links the
   vendor object" — rejected: Designer would not honour it, and an object has
   one owning package.
5. **Answers keyed by definition name**, display-name accepted; secrets by
   reference to the environment's secrets file.
6. **Weight placement follows Designer's rule exactly** (F8) even where it is
   odd (package policies always after hand-made ones) — parity beats taste.
