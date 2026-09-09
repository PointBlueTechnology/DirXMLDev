# Spike: packages — update site, jar format, vault records (2026-09-09)

Facts for Phase 7 (native package management, no Designer at runtime).
Everything here was read from the live update site, Designer 4.10.1's catalog
on this Mac, and the test vault.

## Update site

- Two catalogs, both live over HTTPS (the `http://cdn.novell.com/cached/…`
  mirrors the index names are dead and return an OpenText HTML page):
  `https://nu.novell.com/designer/packages/idm/updatesite1_0_0/` (370 feature
  entries, IDM 4.0-era) and `…/updatesite2_0_0/` (1,135 entries). Designer
  4.10.1's `idm.jar` carries both URLs plus `https://nu.novell.com/designer/updatesite4_10_0`
  (Designer's own update, not packages).
- `site.xml` is a flat Eclipse update-site index: one `<description url=…>` and
  `<feature id="SHORT.feature" url="features/SHORT.feature_<ver>.jar" version="<ver>"/>`
  per package version; no categories.
- A feature jar holds only `META-INF/MANIFEST.MF` and `feature.xml`:
  `<feature id="SHORT.feature" label="…" version=… provider-name=…>` with one
  `<plugin id="SHORT" version="<ver>" fragment="true" unpack="false"/>` — the
  package jar is `plugins/SHORT_<ver>.jar` under the same base URL
  (`…/updatesite2_0_0/plugins/NOVLADBASE_2.2.7.20220330111630.jar` → 200,
  `application/x-java-archive`).
- Versions are `major.minor.micro[.yyyyMMddHHmmss]`; the site carries every
  version ever published (e.g. `NOVLADBASE.feature_2.2.9.20260225221706`,
  newer than the local Designer catalog). The label/description in
  `feature.xml` duplicates the package's `name`/`description`.

## Package jar

`plugins/SHORT_<ver>.jar` = `META-INF/MANIFEST.MF` + `plugin.xml` +
`package_import.xml` (see plan.md Phase 7 for the element inventory). Package
`type`: 2 = driver (480 base + 1,084 feature jars in the local catalog), 3 =
driver set (24 — the Library packages such as `NOVLLIBLDAP`, objects placed
with `<placement context="driver-set" location="library" name="Library"/>`,
GCV objects linked with `<policy-set name="gcv" order="last"/>`), 4 = Identity
Vault (21 — notification templates, `ds-object-class="notfMergeTemplate"`,
`<placement location="notf-collection"/>`). Prompt resources
(`DirXML-ContentType="application/vnd.novell.dirxml.pkg-prompt+xml"`) carry
`<placement location="" order="N"/>` — they are not vault objects.

## What the vault records (test vault, IDM 4.8.7)

Schema attributes (all on the DirXML objects): `DirXML-pkgGUID`,
`DirXML-pkgAssociationId`, `DirXML-pkgChecksum` (string, decimal unsigned
32-bit), `DirXML-pkgLinkages` (string XML), `DirXML-pkgExtensions` (octet
XML), `DirXML-pkgInitialState` (octet XML); all single-valued.

- **Driver**: `DirXML-pkgGUID` = the *base* package only, as
  `id;symbolic-name;version;name;SHORT`
  (`57T9GSLL_201003011155340962;com.netiqcorporation.novladbase;2.2.7.20220330111630;Active Directory Base;NOVLADBASE`).
  Feature packages are not listed on the driver; they are known only through
  their objects (the AD driver's objects name 9 distinct packages).
  `DirXML-pkgExtensions` on the driver = the filter with package ownership:
  `<filter><filter-class class-name="User"><package package-id=… pkg-assoc-id=…/>
  <filter-attr attr-name="CN"><package …/></filter-attr>…` — which package
  contributed each class and attribute, so an uninstall can remove exactly its
  filter entries and a merge across packages is tracked.
- **Driver set**: no `DirXML-pkg*` attributes on `driverset1` (no driver-set
  package installed there); Library objects from a type-3 package carry the
  per-object attributes below.
- **Policy / resource / GCV object**: `DirXML-pkgGUID` (same 5-field record,
  naming the package that owns the object), `DirXML-pkgAssociationId`
  (`idm-packageassocguid` from the jar — stable across versions of the
  package, the object's identity), `DirXML-pkgChecksum`, `DirXML-pkgLinkages`
  (`<policy-linkage><policy-set Driver="QP5MS93M" name="input" order="Weight"
  package-id="…" value="1200"/>` — note `Driver=` is the Designer CObject id
  of the driver, a Designer-side id persisted in the vault), and on 158 of
  the test vault's objects `DirXML-pkgInitialState` (the package's original
  content, present where Designer decided to keep it — customized objects, to
  confirm).
- **Checksums differ between jar and vault** for the same object: for the AD
  driver's `NOVLADDCFG-sub-pp` (assoc `A8YTL2AN_201009040020200789`) the jar's
  `idm-contentchecksum` is 2618871909 and the vault's `DirXML-pkgChecksum` is
  1722270568; every sampled object differs. Either the vault value hashes a
  different serialization (the deployed `XmlData` bytes vs the jar's) or the
  algorithm's input differs — the package-layer research settles this; our
  deployer already writes CRC32-of-content into `DirXML-pkgChecksum` for
  customized objects and Designer reports them as modified, which is
  consistent with "inequality means modified", not with a shared canonical
  value.
