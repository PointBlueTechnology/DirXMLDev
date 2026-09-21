# AppConfig as code — design note (2026-09-21); A1 and A2 built the same day (§6, §7)

The User Application driver's `cn=AppConfig` subtree is the Identity Applications'
configuration: the directory abstraction layer, request definitions and forms,
the role and resource catalog, reports, navigation, authorization types and the
web-application settings. The tool models three kinds of it today (JSON forms,
PRDs, and entitlements, which hang off the driver rather than AppConfig). This
note is the plan for the rest, so a tree round-trips the **whole** subtree:
live vault → as-code → diff → deploy, and into a Designer project.

## 1. What is there (facts, 2026-09-21)

Census of `cn=AppConfig` on idm254 (262 entries) and ig4 (264): the same shape,
stock IDM 4.8/4.10 plus a handful of customer objects.

| Container | Objects (idm254) | Class(es) | Content attribute | In Designer's project as |
|---|---|---|---|---|
| `DirectoryModel/EntityDefs` | 54 entities | `srvprvEntity` | `XmlData` (`<entity-definition>`), `srvprvEntityType`, `description` | stock: inline in `.appconfig`; custom: `<name>.entity` = `XmlData` verbatim |
| `DirectoryModel/ChoiceDefs` | 6 | `srvprvChoice` | `XmlData` (`<list-items>`) | `.choice` = `XmlData` |
| `DirectoryModel/RelationshipDefs` | 3 | `srvprvRelationship` | `XmlData` (`<relationship>`) | `.relation` = `XmlData` |
| `DirectoryModel/configuration` | 1 | `srvprvDirectoryModelConfig` | `XmlData` (`<configuration-definition>`) | `.configuration` = `XmlData` |
| `DirectoryModel/QueryDefs` | container only | `srvprvQueryDefs` | | digest |
| `RequestDefs` | 39 PRDs | `srvprvRequest` | done (docs/forms.md, docs/workflows.md) | `.prd` (done) |
| `WorkflowForms/*` | 11 forms | `srvprvJSONForm` | done | `.formRequest` etc. (done) |
| `RoleConfig/RoleDefs/Level10|20|30/<cat>` | 10 roles | `nrfRole` | `nrfLocalizedNames/Descrs` (`lang~text|…`), `nrfRoleCategoryKey`, `nrfRoleLevel`, `nrfStatus` | `.role20` — Designer's **own XMI** (`role:Role`), not the vault's attributes |
| `RoleConfig/ResourceDefs/<cat>` | 6 resources | `nrfResource` | names/descrs, `nrfResourceParms` (XML), `nrfCategoryKey`, `nrfActive`, `nrfAllowMulti`, `nrfAllowAprOveride` | `.rsrc` — Designer's own XMI (`resource:ProvResource`) |
| `RoleConfig/Attestations` | 4 | `nrfAttestation` | `XmlData`, `nrfRequestDef` (DN), names/descrs, type flags | `.attestation` = a **ds-object** document (vault-faithful) |
| `RoleConfig/configuration` | 1 | `nrfConfiguration` | 14 DN-valued attributes (containers, standard PRDs), `nrfRoleLevels` (`dn#level#xml`), `nrfEntitlementConfigDefault` (XML) | `.roleconfig` — its own namespace, attribute for attribute |
| `RoleConfig/ReportDefs` | 23 reports | `nrfReport` | `XmlData`, names/descrs, ACL | inline in `.appconfig` |
| `RoleConfig/SoDDefs` | container | `nrfSODDefs` | | inline |
| `RoleConfig/Requests`, `ResourceRequests`, `ResourceAssociations`, `CprsRequests` | containers | `nrfRequests` … | **runtime**: the Identity Applications write role/resource requests and assignments here | inline containers, never the children |
| `UIConfig/NavItems` | 53 | `nrfNavItem` | `nrfNavItemId/Type`, names/descrs, `nrfDefault`, ACL (which roles see it) | inline in `.appconfig` |
| `AuthTypes` | 11 | `nrfAuthType` | `nrfAuthTypeId`, object/container classes, `nrfAccessAttribute` (multi) , names/descrs | inline in `.appconfig` |
| `AppDefs` | 3 | `srvprvWebAppConfig` | `XmlData` (`<properties>`, the i18n `<configuration>`), `srvprvDefaultTheme` | 2 inline; `locale-configuration` as `.locale` = `XmlData` |
| `TeamDefs`, `ServiceDefs`, `ResourceDefs`, `WorkFlowDefs`, `ProxyDefs`, `DelegateeDefs`, `DelegationDefs` | empty containers | `srvprv*Defs` | | inline / digests |
| `AppConfig` itself | 1 | `srvprvAppConfig` | `Version`, `srvprvPlugins` | `.appconfig` root |

Three facts decide the design:

1. **One document format already covers everything.** Designer's `.appconfig`,
   its `.attestation`, its `.digest` items, and the **User Application Base
   package** all carry AppConfig objects as `ds-object` documents: the LDAP
   class, the name, and every attribute as `ds-attribute`/`ds-value` (XML
   attributes carried as XML, not text). The package (NOVLUABASE 4.10.1,
   fetched today) holds its whole AppConfig — 54 entities, 30 PRDs, 11 forms,
   10 roles, 6 resources, 23 reports, 52 nav items, 11 auth types, the
   configurations — as **one** base64 `<provisioning>` document under
   `package-folder[Provisioning]/children`, and the object's package stamps as
   `@package-id`, `@pkg-assoc-id`, `@checksum`, `@guid` on the `ds-object`. Our
   `PackageJar` looks for a `provisioning-data` element that does not exist, so
   the catalog carries none of it today (bug, §5).
2. **Designer's per-item files are the vault attribute for six kinds** (`.entity`,
   `.choice`, `.relation`, `.configuration`, `.locale` = `XmlData` byte for byte
   after the `xsi:` header; `.attestation` = the ds-object) and **Designer's own
   dialect for three** (`.role20`, `.rsrc` XMI; `.roleconfig`). Designer reads
   objects inline in `.appconfig` as well (the stock entities, reports, nav
   items, web-app configs live there), which is where a writer can put anything
   it has no dialect for.
3. **Design vs runtime.** Roles, resources, SoDs, attestations, reports, nav
   items and the two configurations are design (they come from the package and
   Designer edits them). Requests, resource requests/associations and CPRS
   requests are the applications' runtime records (empty on both labs; on a
   customer vault thousands). Three attributes on design objects are
   operational, not design: `equivalentToMe` on a role (the inverse of the
   security equivalence the applications grant), `DirXML-Associations` (the
   Data Collection Service driver's), and the ACLs eDirectory adds itself.

## 2. Design

**Model.** One generic type, `AppObject`: path under AppConfig (container
RDNs + cn), object classes, attributes (multi-valued, ordered), package stamps
(`PackageStamps`, the vault vocabulary), and a *kind* derived from the
structural class (`entity`, `choice`, `relationship`, `dm-config`, `role`,
`resource`, `attestation`, `role-config`, `report`, `nav-item`, `auth-type`,
`web-app-config`, `container`). Forms, PRDs and entitlements keep their typed
models; nothing existing moves. A policy table (`AppConfigPolicy`) names the
runtime containers (never read, never written, never deleted) and the
operational attributes (read for the record, never diffed or deployed).

**As-code layout** (one file per object, readable diffs):

```
drivers/<UA driver>/provisioning/
  objects/<Container>/<Sub>/<cn>.xml        the ds-object: classes, attributes; XML-valued
                                            attributes (XmlData, nrfResourceParms,
                                            nrfEntitlementConfigDefault) pretty-printed inline
  objects/<Container>/<Container>.xml       a container object with its own attributes (Version, srvprvModified …)
  provisioning.xml                          manifest gains <object kind= path= …> entries with stamps/customized/baseline, as forms have
```

Localized names/descriptions are stored as the vault has them (`lang~text|…`)
but shown by `appconfig.show` per language; a later typed layer can split them.

**Readers/writers.** Live and LDIF readers add every AppConfig entry that is not
a form/PRD as an `AppObject` (replacing today's `provisioning.other-objects`
count). Designer project reader: inline `.appconfig` ds-objects + the six
`XmlData`-kind files + `.attestation` are read directly; `.role20`/`.rsrc`/
`.roleconfig` are translated (small, fixed mappings: `localizedName` →
`nrfLocalizedNames`, `categoryKey` → `nrfRoleCategoryKey`, `resourceParameter`
→ `nrfResourceParms`, and the roleconfig elements are the attribute names).
Designer project writer: the reverse; the kinds Designer keeps inline are
written inline into `.appconfig`, the file kinds as files with digests (the
existing digest minting), roles/resources in XMI after the spike in §4.

**Diff and deploy.** `ModelDiff` compares AppObjects attribute by attribute
(XML attributes canonicalized, as PRDs are), reports `~ changed entity user
(XmlData)` etc. Plan steps are the existing ADD/MODIFY/DELETE with the
AppConfig containers created parents-first (the clone's ordering), DN-valued
attributes (`nrfRequestDef`, the 14 in `nrfConfiguration`) rewritten through
the existing DN mapping, package stamps written as forms' are. Deletions of
roles/resources/entities fall under the mass-deletion guard: a kind that goes
from N to 0 needs `--delete-all <kind>`. Runtime containers are untouchable.
Restart: none (the applications re-read the DAL on their own cache refresh; a
note in the result says so — `POST /index/permissions` for PRDs is the only
refresh we know).

**Validation.** `AppConfigCheck`: entity `XmlData` well-formed and keys
unique; a role's `nrfRoleLevel` matches its Level container; `nrfRequestDef`
and the configuration's DNs resolve to objects in the tree; a resource's
`nrfResourceParms` entitlement reference resolves; localized-name strings
parse.

**CLI.** `appconfig.list <tree> [--kind K]`, `appconfig.show <tree> <path|name>
[--json]`, and `appconfig.set <tree> <path> --attr A --value V|--file F`
(generic attribute edit, the way `form.set` works) as the first edit
operation; typed `entity.*`, `role.*`, `resource.*` operations are a later
layer on the same storage (§4, B).

## 3. Decisions for Jerry — taken 2026-09-21

Jerry: roles in; proposals 2–5 as written. So: roles and resources are design
objects (assignments stay runtime); `equivalentToMe`, `DirXML-Associations` and
eDirectory's housekeeping attributes are operational (read, never compared or
deployed) while ACLs are design (on both labs every AppConfig ACL is an
application permission — a role or container trustee with an `nrfAccess*`
right; none is an eDirectory default); the layout is `provisioning/objects/`
beside `forms/` and `prds/`; Designer's role files are decided by the A3 spike;
the package jar fix is part of A1.


1. **Roles and resources in scope.** Earlier (docs/workflows.md) they were out
   because the applications manage them. "Fully supported" puts them in as
   design objects; the applications' *assignments* stay out (runtime). Yes/no.
2. **Operational attributes.** Proposed: `equivalentToMe`, `DirXML-Associations`
   and eDirectory's default ACLs are read for the record and never deployed;
   the ACLs on nav items and reports (which roles may see them) **are** design
   and deploy. Agree?
3. **Layout.** `provisioning/objects/<Container>/…` beside the existing
   `forms/` and `prds/`, or fold forms and PRDs into the same object layout
   later. Proposed: beside, nothing existing moves.
4. **Designer roles/resources.** Phase 1 writes them **inline in `.appconfig`**
   if Designer accepts roles there (spike: does Designer show an inline
   `nrfRole` in the Role Catalog view?); if not, phase 1 copies a project's
   existing `.role20`/`.rsrc` files through and refuses a *new* role with a
   note, and phase 2 adds the XMI writer.
5. **Package catalog fix** (§5) now, since baselines and "customized" marks
   for entities, roles and PRDs depend on it. Proposed: yes, in A1.

## 4. Build order and size

- **A1 Model + readers + as-code round trip** (live/LDIF → as-code → LDIF
  identical), `appconfig.list/show`, `AppConfigCheck`; package jar fix so the
  catalog holds the UA base's AppConfig objects and `package.status` sees them.
  ~2 days.
- **A2 Diff + deploy** on idm254: change an entity's `XmlData`, add a role and a
  resource, change the role configuration, verify with `vault.verify` and in the
  applications; delete guard; runtime containers proven untouched. ~2 days.
- **A3 Designer project** reader/writer for every kind, acceptance in Designer
  (a project written from the idm254 tree opens the DAL, Role Catalog and
  Provisioning views without errors; `.role20` spike). ~2–3 days.
- **B Typed operations** where editing by hand is clumsy: `entity.attr.add`,
  `role.add`, `resource.add --entitlement`, `nav.set`. Sized after A3.

## 5. Found on the way

- `PackageJar` reads `package-folder/provisioning-data`; the real element is
  `package-folder/children/provisioning` (base64 of a `<provisioning>` ds-object
  document, 6.4 MB for NOVLUABASE 4.10.1). Fix in A1.
- The Designer import of the EDIR_TEST2_TREE clone (Jerry, 2026-09-21) showed
  errors that are **not the clone's**: the policy `Send expiration email`
  (AcctExpNotif) is byte-identical on ig4 and uses `do-send-email-from-template`
  without the required `template-dn`; Designer's importer dereferences that
  attribute unguarded (`DeployImporter_Load.importXMLStream`) and drops the
  policy with a NullPointerException. The `NOVL*-GCVs` objects lack
  `DirXML-pkgLinkages` on ig4 too (stock, Designer warns), the packages
  Designer could not find are simply not in that Designer's catalog, and the
  `srvprvJSONForm` schema note is identical on both trees. Follow-up: a
  `validate` check for DirXML-Script required attributes (plan.md).

## 6. A1 — built 2026-09-21

**Model.** `model/AppObject` (path segments below AppConfig, classes, attributes
in schema spelling, meta with `dn` + package stamps in the vault vocabulary,
`kind()` from the structural class) and `model/AppConfigPolicy` (kinds,
container classes, runtime containers `RoleConfig/Requests|ResourceRequests|
ResourceAssociations|CprsRequests` and runtime classes, operational attributes,
XML-valued attributes `XmlData`/`nrfResourceParms`/`nrfEntitlementConfigDefault`,
canonical attribute names, the `lang~text|…` parser). `Provisioning.objects`
holds them beside `forms` and `prds`; the AppConfig container's own attributes
are `appconfig.<name>` metas on the provisioning manifest.

**Readers.** `LdifReader` (LDIF and live, the same path) turns every AppConfig
entry that is not a form or PRD into an `AppObject`: attribute names canonical,
multi-values sorted, XML values canonicalized, CRLF folded (the same reason as
PRDs), `cn`/`objectClass`/stamps to meta; the runtime records are skipped and
counted (`provisioning.runtime-objects`). The `WorkflowForms` and `RequestDefs`
containers stay with the forms/PRD machinery that creates them.

**As-code.** `provisioning/objects/<Container>/…/<cn>.xml`, one **ds-object**
document per object (`ascode/DsObjectXml`) — exactly the shape Designer's
`.appconfig`, its digest items and the package carry; XML-valued attributes are
written as XML inside `ds-value`, never re-indented (a multi-line text node such
as a certificate would otherwise gain the indent on re-read). The manifest gains
`<object kind= path= file=>` entries with the meta. Round trips: LDIF → tree →
tree byte-identical (`ProvisioningGuardedTest`), and the live idm254 tree read
and written again is byte-identical (356 XML files).

**CLI.** `appconfig.list <tree> [--driver D] [--kind K] [--containers] [--json]`,
`appconfig.show <tree> <path-or-name> [--attr A] [--json]` (localized strings
split per language; long values summarized, `--attr` prints one whole).

**Validation.** `AppConfigCheck`: `appconfig-xml-invalid`, `appconfig-entity-key-duplicate`,
`appconfig-role-level-mismatch`, `appconfig-ref-missing` (DN attributes of the
role configuration and attestations resolve to objects or PRDs in the tree),
`appconfig-ref-outside` (I), `appconfig-localized-unparsable` (W).

**Package catalog.** `PackageJar` reads `package-folder/children/provisioning`
(base64 of the `<provisioning>` ds-object document), flattens it into
`provisioningObjects` with Designer's stamps (`package-id`, `pkg-assoc-id`,
`checksum`, `designer.guid`) and — proved against NOVLUABASE 4.10.1's stored
package checksum: the **decoded** document matches, the base64 text and
"absent" do not — feeds the decoded document to the folder checksum. The
catalog unpacks them to `objects/5-Provisioning/<path>.xml`; `package.show`
counts them by kind.

**Numbers (2026-09-21).** idm254: 206 objects (175 non-container, 32 packaged,
0 runtime); ig4: 208; NOVLUABASE 4.10.1: 250 (54 entities, 30 PRDs, 11 forms,
10 roles, 6 resources, 23 reports, 52 nav items, 11 auth types, 35 containers …).
`validate` on both trees: no AppConfig findings. 663 tests.

## 7. A2 — diff and deploy, built 2026-09-21

**Diff.** `ModelDiff` gains `OBJECT_ADDED / OBJECT_REMOVED / OBJECT_CHANGED`
(no driver restart, like forms). Objects pair by path (case-insensitive) and
compare their classes and every design attribute — operational attributes
never count, XML values canonically (a hand-edited file need not be
canonical), multi-values as sorted lists. `Change.what` is the object's kind,
`Change.parts` the changed attribute names plus `classes` / `stamps`, so the
plan writes exactly what changed. A fresh `import-live` of idm254 diffs
"no differences" against the vault with the 206 objects in play.

**Guard.** The mass-deletion guard covers every object kind in the plural
(`roles`, `entities`, `nav-items`, `containers` …): when the tree has none of
a kind the vault has, the deletes are held back with the usual note, and
`--delete-all <kind>` overrides it. The applications' four runtime containers
are never deleted, whatever the flags.

**Plan.** An add writes the object's classes and design attributes (plus
package stamps and the `DirXML-PkgItemAux` class when stamped); a change
writes only the changed attributes (an emptied one is removed, a new auxiliary
class is added, a changed structural class is noted — delete and add instead);
a removal is a DELETE. Adds go parents-first and deletes deepest-first, so a
new category container precedes its role and a removed container follows what
it held. Snapshot, verify and audit are the existing ones.

**Customized packaged objects.** When a packaged object's content changes and
the tree still records the package's checksum, the plan writes a
content-derived `DirXML-pkgChecksum` (`XmlData` when the object has one, else
every design attribute in name order) so Designer shows it as modified — and
the diff treats "vault holds the derived checksum of the tree's content" as
the same state, not drift. Reverting the content writes the content and the
package checksum back, nothing else.

**Live (idm254, 2026-09-21).** A scratch role added, changed and removed:
one step each, `verify: vault matches the tree` every time, the vault back to
"no differences". A packaged role (`provManager`) customized and reverted:
content + derived checksum out, content + package checksum back, both
verified. 669 tests.

Next: **A3** the Designer project (reader/writer for every kind, the
`.role20` spike), then **B** typed operations (`appconfig.set`, `entity.*`,
`role.*`).
