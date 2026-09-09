# Designer 4.10.1 package layer — facts for DirXMLDev's own package management

Scope: read-only analysis of Designer 4.10.1 (`com.novell.idm_4.0.0.202507091432/idm.jar`,
`com.novell.idm.packagemanager_4.0.0.202412191437/packagemanager.jar`,
`com.novell.idm.deploy_4.0.0.202507091432/deploy.jar`, `com.novell.core_4.0.0.202412191437/novellcore.jar`,
`com.novell.core.jars_…/lib/nxsl.jar`), the catalog jar `NOVLEDIRDCFG_2.1.0.20120831225140.jar`, and the project
`/Users/jcombs/designer_workspace/test11`. Every statement is tagged **[verified]** (decompiled code read and, where
stated, recomputed against real data) or **[inferred]** (read from code but not exercised, or a reasonable reading of
partial code). Decompiled sources stay in the scratchpad (`…/scratchpad/pkg/{idm-src,model-src,pm-src,deploy-src,
core-src,nxsl-src}`); the verification program is `…/scratchpad/pkg/verify/PkgChecksum.java` (+ `InstalledChecksum.java`).

Naming used below: **catalog item** = the copy of an object inside an `IdmPackage` in the project catalog (or in the
jar); **installed item** = the copy created on a driver / driver set / vault; **target** = the `Driver`, `DriverSet`
or `IdentityVault` a package is installed on (`IPackageTarget`).

---

## 1. Checksums

### 1.1 The primitive **[verified]**

`com.novell.idm.packages.PackageCRC32 extends java.util.zip.CRC32`. `update(String s)` is
`CRC32.update(s.getBytes("UTF-8"))` (`XmlUtil.getEncodedBytes`, `XML_ENCODING = "UTF-8"`); a null/empty string
contributes nothing. Several `update()` calls are simply concatenation of their byte sequences into one CRC32. The
value is `CRC32.getValue()` (unsigned 32-bit in a `long`) and is always written as a decimal string. The constructor's
`item.getName().toLowerCase()` is only a debug watch-list key; the name is **not** lowercased for hashing.
`PackageChecksum` is a plain result record (`ChecksumType.CONTENT`/`DIRECTIVE`); it computes nothing.

### 1.2 Canonical XML string used for content checksums **[verified by recomputation]**

Wherever an item's XML takes part in a checksum it is the string produced by
`XmlUtil.serializeXMLDocument(XmlUtil.parseXMLDocument(xmlData).getXMLDocument(), true)`:

1. Parse (`XmlUtil.parseXMLDocument(String)` → `com.novell.xml.parser.SAXParserImpl` from nxsl.jar → Novell DOM),
   then **`DOMUtil.stripWhitespace(documentElement)`**: adjacent text nodes are merged and whitespace-only text nodes
   are removed, except inside any element (or ancestor) with `xml:space="preserve"`.
2. Serialize with `com.novell.xml.dom.DOMWriter`, `setWriteDeclaration(true)`, `setIndent(true)`, encoding UTF-8:
   * output starts with `<?xml version="1.0" encoding="UTF-8"?>` **immediately followed by the root start tag (no
     newline)**;
   * every element / PI / comment that is not the first child of the document is preceded by `"\n"` + one **tab per
     depth level**, unless its previous sibling is a text or CDATA node; a closing tag is preceded by `"\n"` + tabs
     unless the element's last child is text/CDATA; childless elements are written `<x/>`; no trailing newline;
     `xml:space="preserve"` subtrees are written verbatim;
   * attributes are written in the DOM's `NamedNodeMap` order (in every sample the jar already had them alphabetical
     and the recomputation matched; whether the Novell DOM re-sorts attributes was not tested **[inferred: keep the
     parsed order]**);
   * text escaping (`XMLUtil.encodeText`): `&`→`&amp;`, `<`→`&lt;`, `>` only when part of `]]>`, surrogate pairs and
     control characters as `&#x…;`; attribute values (`XMLUtil.encodeAttribute`): double-quoted with the same text
     escaping, single-quoted if the value contains `"` but not `'`, and fully entity-escaped (`&quot; &apos; &gt;` plus
     `&#x9; &#xa; &#xd;`) inside double quotes if it contains both quote kinds or any tab/CR/LF.

Reimplementing this exactly is possible but the verification program simply calls the real nxsl classes
(`XMLParserFactory.newParser()`, `DOMUtil.stripWhitespace`, `DOMWriter`) — nxsl.jar is a plain library with no
Eclipse dependency, so DirXMLDev can do the same (it already ships engine jars).

### 1.3 Content checksum by object type (`calculateChecksum(boolean set)`) **[verified unless noted]**

All start with `update(name)` where *name* is the object name exactly as stored (`ds-object-name`).

| Type (`ds-object-class`) | Bytes hashed, in order | Code |
|---|---|---|
| Policies: `DirXML-Rule` (ScriptPolicy / MappingPolicy) and `DirXML-StyleSheet` (StylesheetPolicy) | name; canonical XML of `XmlData`; then, because all three are `TransformPolicy`, **the name of every policy set the policy is currently linked into on its driver**, in `addPolicySetRefs` enumeration order (i = 0..23: `input`, `schema`, pub `event`, pub `matching`, pub `creation`, pub `placement`, pub `command`, `output`, sub `command`, sub `placement`, sub `creation`, sub `matching`, sub `event`, `Startup`, `Shutdown`). A catalog item has no driver → no set names. | `XMLContainerImpl.getChecksum`, `PolicyImpl.calculateChecksum` |
| `DirXML-Resource` (all content types) | name; canonical XML of `XmlData` if `MimeContentType.m_isXML`, else raw `XmlData` with `\r\n`→`\n`; then **the content-type string** (e.g. `application/vnd.novell.dirxml.filter-ext+xml`); ECMAScript resources add the names of sets they are linked into (`ecma-script`). | `IDMResourceImpl.calculateChecksum` |
| `DirXML-GlobalConfigDef` | name; linked set names (`gcv` when linked as a config extension); `definitions/@display-name`; then every definition **sorted by name**: name, `"" + type` (the `GCValue.getType()` int), `"true"/"false"` mandatory, plus per type: structured → template defs recursively; dn-ref → attr-name, aux-class-name; dn → delims; enum → choice values sorted; integer/real → range-hi, range-lo; list → value, separator; group/parent → children recursively; string → multiline. **Values are not hashed.** Source doc = the GCV's `ServerDirective` data for a catalog item, or the merged per-server `DirXML-ConfigValues` for an installed one. | `GlobalConfigImpl.calculateChecksum`, `updateGCVChecksum` — **[inferred; not recomputed** — needs a port of `GCDefinitions`; see 1.6 for why this matters less] |
| `DirXML-idPolicy` | name; prefix; area; acl; `""+min`; `""+max`; `""+fill`; `""+areaEI`; `""+accessControl` | `IDPolicyImpl` **[inferred]** |
| `DirXML-Entitlement` | name; canonical XML | `EntitlementImpl` **[inferred]** |
| `notfMergeTemplate` | name; canonical XML (raw with `\r\n`→`\n` if unparsable); email subject | `NotfTemplateImpl` **[inferred]** |
| `DirXML-Job` | name; canonical XML; `job-definition/@auto-delete,@disabled,@schedule,@scope-required`; each `result-processing` element (as string) that has an `audit` child; trace file, encoding, name, level, size limit; scopes sorted by path (type, `scopeDisplayNamePrameter`, path); template names sorted | `JobImpl.calculateChecksum(set, 2)` **[inferred]** |

Verification against `NOVLEDIRDCFG_2.1.0.20120831225140.jar` (`PkgChecksum.java`, using the real nxsl parser/writer):

| ds-object | stored `idm-contentchecksum` | recomputed | stored `idm-directivechecksum` | recomputed |
|---|---|---|---|---|
| `NOVLEDIRDCFG-pub-pp` (DirXML-Rule) | 3732451646 | 3732451646 ✔ | 141364481 | 141364481 ✔ |
| `NOVLEDIRDCFG-pub-mp-Scoping` (DirXML-Rule) | 859901783 | 859901783 ✔ | 485958664 | 485958664 ✔ |
| `NOVLEDIRDCFG-pub-mp` (DirXML-Rule) | 3546995173 | 3546995173 ✔ | 160919082 | 160919082 ✔ |
| `NOVLEDIRDCFG-Filter` (Resource, filter-ext) | 3575207045 | 3575207045 ✔ (name+xml+content-type) | 2768975614 | 2768975614 ✔ |
| `NOVLEDIRDCFG-SynchronizationPrompts` (Resource, pkg-prompt) | 3685207619 | 3685207619 ✔ | 2792031596 | 2792031596 ✔ |
| `NOVLEDIRDCFG-GCVs` (GlobalConfigDef) | 3419577846 | not attempted | 312817303 | 312817303 ✔ |
| `<package checksum>` | 4039670012 | 4039670012 ✔ | `directive-checksum` 4156503348 | 4156503348 ✔ |

Installed-item check from test11: policy `NETQSCIMDCFG-itp-AppendingAssociationRef` (assoc `8ZOESZHO_202106141310070928`)
has `Idm:ContentChecksum=3417194917` on the driver and `1543062048` on its catalog twin. Recomputed from its
`_initial_state.xml`: name+xml = 1543062048, name+xml+`"input"` = 3417194917 ✔ — the installed value folds in the
policy-set name(s) exactly as `addPolicySetRefs` predicts (`InstalledChecksum.java`).

### 1.4 Directive checksum **[verified]**

`CRC32(UTF-8(installationDirectiveString))` where the string is the installation directive **exactly as stored** —
i.e. the base64-decoded text of `idm-installationdirective` (a `<?xml …?><installation-directive>…` document with
Designer's tab indentation). No re-canonicalisation (`ItemImpl.setInstallationDirective`, `addPackageXML`,
`IdmPackageFolderImpl.validateChecksum`). For the *package* directive the stored form is what
`writeXMLDocument(<installation-directive> DOM)` produces, because `toXml` embeds the DOM and `fromXml` re-serializes
it; recomputing it as canonical(declaration + tabs) matched 4156503348.

### 1.5 Package-level `checksum` and `directive-checksum` **[verified]**

* `IdmPackageFolderImpl.calculateChecksum`: children sorted by **association id** (`String.compareTo`), for each
  `update("" + child.getContentChecksum())` — the **stored** decimal checksum, not a recomputation — then
  `update(provData)` if the folder has provisioning data (folder 5). An empty folder yields 0.
* `IdmPackageImpl.calculateChecksum`: `for (i = 1; i < 9; i++) update("" + folder(i).checksum)` — folder ids 1..8
  **only; folder 9 "Global Configurations" is excluded** (recomputation matched only with that exclusion). Empty
  folders contribute the string `"0"`.
* `directive-checksum` = `Idm:DirectiveChecksum` of the package = CRC32 of the package installation directive (1.4).

Folder ids/names (`createPackageFolders`): 1 Policies, 2 Resources, 3 Jobs, 4 Entitlements, 5 Provisioning, 6 Files,
7 Notification Templates, 8 ID Policies, 9 Global Configurations.

### 1.6 Are `idm-contentchecksum`, `Idm:ContentChecksum` and `DirXML-pkgChecksum` the same number?

* Jar `idm-contentchecksum` → catalog item `Idm:ContentChecksum`: copied verbatim (`ItemImpl.setPackageAttrs`). Same
  number **[verified]**; and `PackageUtil.doImport` validates it on catalog import (mismatch = "Failed checksum" log,
  a yes/no dialog in the UI, `validChecksum=false` transient headless; **GCV mismatches are silently recalculated**
  and accepted — `chksum.getItem() instanceof GlobalConfig → calculateChecksum(true)`).
* Installed item `Idm:ContentChecksum`: recomputed by `packageItemOperation` op 1/4/11 with `calculateChecksum(true)`
  **after** linkage, so for policies / ECMAScript / GCVs it differs from the catalog number by the linked set names
  (verified above; a policy linked to nothing, a filter-ext or prompt resource keeps the catalog number).
* Vault `DirXML-pkgChecksum` = `"" + getContentChecksum()` of the installed item (`ItemImpl.deployPackageAttributes`),
  i.e. the installed number **[verified in code, not checked against a live vault — the LDAP server was unreachable]**.

---

## 2. Install (`PackageUtil.installPackage(target, pkg)` → `IdmPackageImpl.install`)

### 2.1 Order of operations **[verified]**

1. `applyPrompts(target)` — every `PackagePrompt.applyTargetTransform` (see 2.5).
2. `installCheck(target, false)` — per folder `performOperation(target, 0)` (conflict check: an existing child with
   the same name that is not a package item of the same class, or that belongs to another package, is a conflict);
   for Driver/DriverSet targets `runPostInstallDirectives(target, 0)` verifies that packages referenced by the
   package-level `policy-linkage` are installed on the driver set / vault or declared as dependencies.
3. Package target attributes: `getTargetAttributes()` = the `<ds-attributes>` child of the package installation
   directive, localized with the package XLIFF (`localizeXMLWithXLIFF`), then `target.setAttributesFromXml(...)`.
   For a base package on a driver, `driver.setType(getTypeFromManifest())`.
4. `folderInstallationForDesigner`: for an IdentityVault target folder 7 (templates) first; then every folder
   `install(target)` = `performOperation(target, 1)`; then every folder `runPostInstallDirectives(target)` =
   `performOperation(4)` followed by `performOperation(11)`; then, for type-2-on-Driver / type-3-on-DriverSet,
   `runPostInstallDirectives(target, 1)` = package-level linkage directives (`postInstallLinkage`).
5. `installedPackages.add(pkg)` — the target's `Idm:InstalledPackages` reference relation.

`performOperation` iterates the folder's children (folder 5 delegates to `PackageEventManager` provisioning events).
Filter-extension resources (`application/vnd.novell.dirxml.filter-ext+xml`) go through `filterItemOperation` only
(ops 1 and 3) and are **not** created as objects; prompt resources (`…pkg-prompt+xml`) are skipped entirely unless
the vault has "package builder" enabled. Everything else goes through `packageItemOperation`.

### 2.2 Placement (`getInstallationContainer`) **[verified]**

`<placement location="…"/>` in the item directive:

| location | container |
|---|---|
| `subscriber` / `publisher` (Driver target) | driver's Subscriber / Publisher |
| `Driver` | the driver |
| `driver-set` (Job or GlobalConfig only) | the driver set |
| `notf-collection` (NotfTemplate) | vault's first notification-template collection |
| `id-policy-container` (or any IDPolicy) | driver's first `IDPolicyContainer`, created with `@name` (default type name) |
| `library` | library named `@name` (default "Library") in the driver set, or in the vault when `@context="identity-vault"` or the target is a vault; created if missing |
| anything else / `default` | the target itself |

`@order` on `<placement>` is only used by prompt resources (prompt page order).

### 2.3 What is stamped on the installed object (`packageItemOperation` op 1, 4, 11) **[verified]**

Op 1: find existing child by name, else by association id. If a same-named **non-package** object exists it is taken
over (`customized-pkg-item=true`, association id set). If the child exists and is customized, a snapshot
`toPackageXML()` is parked in transient `pkg-version-change` and its `Idm:InstalledLinkages` are preserved; GCV
objects park their current per-server values in `cur_gcvs`. Then `packageItem.pkgCopyCoreTo(child)` (or a clone of
the catalog item for a new object), `setPackageId(pkg id)`, and — if a prompt transform produced
`xform-prompt-target-data` — `XmlData` is replaced and parameter `TransformedPackageItem=true`; `calculateChecksum(true)`.

Op 4 (first post-install pass): unlink from all sets (`removeItemFromSets`), link with the item's linkage directives
whose order is **First/Last/Weight**; jobs: `processJobDirectives`; GCVs: `processGlobalConfigDirectives` (server
directive `mode="all"` → every server of the driver set/vault, `preferred-server`/empty → preferred server; existing
values merged into the package definitions via `IdmModel.mergeGCVDocuments` unless it is an upgrade/downgrade, in which
case the current values are kept as-is); named passwords; **`setInitialState()`** (heavy data `initial_state`, the
`_initial_state.xml` file = `toPackageXML()` with an extra `name` attribute — i.e. it already contains the prompt-
transformed content and the package attributes); `calculateChecksum(true)`; then, if `pkg-version-change` holds a
customized snapshot, `fromPackageXML(snapshot)` puts the customized content back (name included) and the cached
checksum is cleared. Op 11 (second pass): link with **Before/After** directives (so anchors exist), recompute checksum
unless customized.

Resulting project attributes on the installed CObject: `Idm:PackageGuid` (package id), `Idm:PackageAssocGuid`
(association id), `Idm:ContentChecksum` (long), `Idm:InstalledLinkages` (string XML), heavy data `initial_state`
(`…_initial_state.xml`), plus the object's own content. `Idm:InstallationDirective` / `Idm:DirectiveChecksum` live
only on catalog items (pkgCopyCoreTo does not copy them — the installed test11 policy has none) **[verified on test11]**.

Vault (`ItemImpl.deployPackageAttributes`, engine ≥ 4.0 only), aux class **`DirXML-PkgItemAux`**:
`DirXML-pkgAssociationId` = association id; `DirXML-pkgGUID` = `"<packageId>;<symbolicName>;<version>;<name>;<shortName>"`
(the last two only when the package has a name); `DirXML-pkgLinkages` = the `Idm:InstalledLinkages` string;
`DirXML-pkgChecksum` = decimal content checksum; `DirXML-pkgInitialState` = UTF-8 bytes of the initial-state XML
(stream syntax, `deployAuxClassStreamValue`). Each is removed when the corresponding value is absent.
Driver object (`DriverImpl.deployPackageAttributes`): `DirXML-pkgGUID` = the same 5-field string **for the base package
only**; `DirXML-PkgTargetAux` / `DirXML-pkgExtensions` = the `Idm:FilterExtensions` cache (written right after a
successful `DirXML-DriverFilter` deploy). **There is no vault attribute that lists installed packages.** On import
Designer rebuilds them: each item's `DirXML-pkgGUID` becomes a `<pkg-initial-state pkg-assoc-id package-id version
display-name name>` (with the initial state as base64 text) and a `<packages><package id symbolic-name version
display-name name/>` list; `DeployImporter_Import` then calls `PackageUtil.doImport(catalog, symbolicName, version)` for
packages missing from the project catalog (found by OSGi `Bundle-SymbolicName` + exact `Bundle-Version` among the jars
in `<Designer>/packages/eclipse/plugins`), and adds the package to the driver's / driver set's / vault's
`Idm:InstalledPackages` according to `type` (2/3/4). **An item whose package version cannot be found is de-packaged
(association id, package id and initial state cleared)** **[verified in `importPackageInitialStates`]**. The driver's
own `package-id`/`package-version` (from its `DirXML-pkgGUID`) adds the base package.

### 2.4 Linkage weight → position (`linkPackagePolicy`, `linkPackageGlobalConfig`, `linkPackageECMAScript`) **[verified]**

Directive form: `<policy-linkage><policy-set name="matching" channel="publisher" order="weight" value="100"
[order-id="<assocId>" order-name="…"]/>…</policy-linkage>` (`LinkageDirective`; `LinkageOrder` strings `First`,
`Last`, `Before`, `After`, `Weight`, compared case-insensitively). Set names: `input`, `output`, `schema` |
`schema-mapping`, `Startup`, `Shutdown` (engine ≥ 4.0.2.3 only), `event`, `command`, `matching`, `placement`,
`creation` (per `channel`), `gcv` (driver or driver-set config extensions), `ecma-script` (driver extension
functions); a leading `subscriber ` / `publisher ` in the name is stripped.

* **Weight**: walk the existing list; insert **before the first existing item whose weight is strictly greater** than
  the new weight; otherwise append. An existing item's weight comes from its `Idm:InstalledLinkages` entry for that
  set/channel (and, when several, the n-th occurrence of the same object in the list picks the n-th sorted weight),
  falling back to the legacy item parameter `Weight:<setGuid>:<setName>`, else **−1**. So: ties → the new item goes
  after the existing one; non-package / weightless policies count as −1 and are never displaced, i.e. **package
  policies always land after pre-existing hand-made policies** and among themselves in ascending weight, stable by
  install order. Then a `<policy-set name order="Weight" channel package-id="<owner pkg>" value="<weight>"
  Driver="<driver id>"/>` is appended to the item's `Idm:InstalledLinkages` (duplicates per package id removed).
* **First** → index 0, weight "0". **Last** / unknown → append, weight `9223372036854775807`.
* **Before/After** → relative to the object whose association id equals `order-id`; weight copied from the anchor;
  anchor missing → After appends (MAX), Before inserts at 0 ("0").
* Removal (op 3) removes the object from the set; a GCV/ECMA link owned by another installed package's directive is
  kept.

### 2.5 Prompts **[verified]**

A `pkg-prompt+xml` resource's directive holds `<placement order="n"/>`, `<package-item-targets><package-item
pkg-assoc-id="…"/>…</package-item-targets>` (or the package itself as target), `<prompt-stylesheet>` and
`<target-stylesheet>` (inline XSLT). Its `XmlData` is a `<configuration-values><definitions>…` document = the
prompts. Flow: the prompt stylesheet pre-populates the definitions (params `defsDoc`, `curDoc` = current target
`ds-attributes`, `npDoc` named passwords); answers come from the UI (`setGCVResponses`) or headless
`PackageUtil.patchAllGCVValues(pkg, Properties, prompt, target)` — key = the definition's `display-name` (XLF-resolved),
value `eNull` = empty, `list` definitions absent from the properties are cleared, `password-ref` values become named
passwords. Then `PromptTarget.apply` runs the target stylesheet with params `defsDoc` (answered definitions), `curDoc`,
`npDoc`, `directiveDoc`, `opDoc`, `propertyWizard` **on each target item's installation directive** (result →
transient `xform-prompt-directive-data`, which `getInstallationDirective()` returns while `installing-package` is set,
so it drives that item's `<ds-attributes>` / server directive / linkage) and, for `XMLContainer` targets other than
GCVs, **on its `XmlData`** (→ `xform-prompt-target-data`, applied in op 1). For a GCV object the transformed directive's
`<configuration-values>` is the `ServerDirective` data written as the per-server `DirXML-ConfigValues`. The stock
NOVLEDIRDCFG target stylesheet copies `$defsDoc` values into `definition/value` elements by name and appends named-
password `ds-attribute`s. GCV **values never enter a checksum**, so prompt answers do not make an object "customized";
a prompt-transformed policy (`TransformedPackageItem=true`) is hashed after transformation, so its installed checksum
differs from the catalog's but it is still not "customized" (saved vs. current agree).

### 2.6 Driver-level `ds-attributes` — set vs merge (`DriverImpl.setAttributesFromXml`) **[verified]**

Set (overwrite): `name`, `application-schema`, `configuration-manifest`/`config-manifest`, **`driver-filter-xml`**,
`reciprocal-links`, `driver-image`, `driver-password`, `trace-*`, `log-*` (`log-events` with no values = inherit),
`java-module`/`native-module`, and on the driver set's preferred server: `shim-config-info-xml`,
**`configuration-values` / `global-config-values` (replaces `DirXML-ConfigValues`)**, `driver-start-option`
(defaulted from the `DefaultDriverStartup` preference when absent), `driver-cache-limit`, `shim-auth-id/server/password`,
`driver-version`, `version`, `named-password`. Merge: `global-engine-values` → `setEngineControlsPkgInstall(server,
xml, merge=true)` = `IdmModel.mergeGCVDocumentsPkg` (existing engine-control values survive for same-name/same-type
definitions; optionally to all servers when `PreferECVMultiServerConfiguration`). `subscriber-options` /
`publisher-options` were not seen in the driver branch **[inferred: handled by the channel objects, not verified]**.
Cross-package GCV "merging" is not a merge of documents: each package's GCV object is a separate
`DirXML-GlobalConfigDef` linked into `DirXML-ConfigExtensions` (`gcv` linkage), ordered by weight.

### 2.7 Filter extensions (`filterItemOperation`) **[verified]**

For each `filter-class` in the package filter: absent from the driver filter → copied in; present → per attribute of
the class (`publisher`, `subscriber`, `publisher-create-homedir`, `publisher-track-template-member`, and for
`filter-attr`: `publisher`, `subscriber`, `merge-authority`, `publisher-optimize-modify`, `priority-sync`) the package
value replaces the driver value only if its precedence is higher: `sync`/`app` = 4 > `notify`/`edir` = 3 >
`ignore`/`default`/`true` = 2 > `reset`/`none`/`false` = 1 > absent = 0. The cache `Idm:FilterExtensions` (vault
`DirXML-pkgExtensions`) mirrors the filter tree with `<package package-id pkg-assoc-id/>` children per class/attr and
`existing="true"` plus the pre-package driver values, so uninstall can restore or delete precisely. On upgrade only
classes/attrs whose definition changed between the two filter resources are touched.

---

## 3. Dependencies and features

### 3.1 Data **[verified]**

Manifest `Dependencies` (base64 of the `Idm:Dependencies` XML) and the package directive's `<dependencies>` both hold
`<dependency name="…" package-id="…" type="2|3|4">` with optional children `<min-version value="M.m.r[.build]"/>`,
`<max-version value="…"/>`, `<version value="…"/>*` (`PackageDependency`). `type` is the **package type** of the
required package (2 driver, 3 driver set, 4 Identity Vault). NOVLEDIRDCFG declares only
`Common Settings 4YGKJX0U_201006021536040516 type=3` with no version constraint. Manifest `Features` (base64 of
`Idm:PackagesToInstall`) = `<features><mandatory>…</mandatory><optional>…</optional></features>` whose entries are
`<package id="<packageId>" display-name="…"/>`; only read when the package is a **base package**
(`addFeatureDependencies`). `Supported-Drivers` = `<supported-drivers><definition display-name driver-id id/></supported-drivers>`.

`PackageVersion` = `major.minor.revision[.buildId]`, compared lexicographically on the four numbers (missing build =
0). `PackageDependency.getAcceptableVersions`: explicit `<version>` list without min/max → exact matches only; `min`
removes lower (unless listed); `max` removes higher (unless listed); with neither and an engine version given, packages
whose `idm-minidmversion`/`idm-maxidmversion` exclude the engine are dropped; result sorted **newest first**. No
constraint = any version (`ensurePackageDependencyVersions`).

### 3.2 Resolution (`PackageUtil.getDependencyBundles` → `getDependencyTree`) **[verified]**

Candidates = versions already in the project catalog **plus** jars (`PackageBundleCache.getPackageBundles()`, i.e. every
OSGi bundle registered through the `com.novell.idm.packagemanager.packageregistration` extension in
`<Designer>/packages/eclipse/plugins`). Depth-first: for each dependency (plus mandatory/optional feature entries of a
base package, optional ones skipped when `populateOnlyMandatory`), take the acceptable versions newest-first and keep
the first whose own dependencies resolve; a package id met earlier is reused (`dependencyMap`) and the new constraint
merged; anything unresolvable throws and that root is skipped. Then `PackageUtil.doImport` pulls the chosen bundles into
the catalog.

### 3.3 Install-time checks and the pyramid **[verified]**

`submitPendingPackageOperation` refuses a package whose type does not match the target (`IdmModel.mapItemToType`).
`checkPendingInstall`: a driver-target dependency of type 3 must be satisfied by the **driver set's** installed (or
pending) packages, type 4 by the **vault's**; type-2 deps by the target's pending set; at most one base package per
target; a non-base driver package with provisioning data requires a base with provisioning data; `checkUpstream
Dependencies` rejects a version that breaks an already-installed dependant (on the target, or on drivers under a
driver set / driver sets+drivers under a vault). Uninstall (`checkPendingUnInstall`) is refused while any installed
or pending package on the target — or below it in the pyramid — depends on the package. Missing dependencies are
reported, never auto-installed at this layer (the wizard / `getDependencyBundles` adds them before submitting).

### 3.4 Contents of driver-set (type 3) and vault (type 4) packages **[inferred from placement code; not inspected on a jar]**

Same nine-folder layout. Type 3 installs on a `DriverSet`: jobs and GCVs with `location="driver-set"`, policies /
ECMAScript / mapping tables in a driver-set **library** (`location="library"`), GCV linkage into the driver set's
`DirXML-ConfigExtensions`; its package-level linkage directives can order items across drivers (`Driver` attribute).
Type 4 installs on an `IdentityVault`: notification templates (folder 7, `notf-collection`, installed first),
libraries (`location="library" context="identity-vault"`), provisioning data (folder 5, `PackageEventManager`),
GCVs (`iv.getServers()` for `mode="all"`). Driver packages reach them only through type-3/4 dependencies.

---

## 4. Upgrade, downgrade, uninstall, customizations

### 4.1 Mechanics **[verified]**

`PendingOperation` codes: 0 none, 1 install, 2 uninstall, 3 upgrade, 4 downgrade, 5 revert, 6 sync, 7 associate,
8 compare. `getOrderedPendingPackageOperations` turns 3 and 4 into **uninstall(old) + install(new)** with transient
`pkg-version-change` set on both packages (old → new, new → old); removals run first ordered dependants-first
(`RemoveOperationComparator`), then installs with base packages first, then revert / sync / compare. `PackageCommand`
executes them in that order (`InstallPackage.executeAction` = `pkg.install(target)`, `UninstallPackage` =
`pkg.remove(target)`). Upgrade and downgrade are therefore identical code paths; "downgrade" is only a UI word.

`remove(target)`: package-level unlink (op 3), each folder `performOperation(3)`:
* filter-ext: with a version change, the old and new filter resources are compared; identical → nothing; else only
  the differing classes/attrs are removed from the driver filter (cache-aware);
* every other item → `deleteItem`: **if the new version has a matching child** (`hasMatchingChild`: same association
  id — renaming supported — or same name, **and** the same installation container) the object is **kept** with
  transients `pkg-version-change=true` and `customized-pkg-item=isCustomized()`; otherwise it is deleted
  (`deleteReferences`, removed from its parent, cleaned up). So objects the new version dropped are deleted, moved ones
  are re-created at the new placement, renamed ones are renamed in place.
Then `install(target)` as in §2 with the parked snapshot: a customized object keeps its **customized content** (restored
from the `pkg-version-change` snapshot after the new content and new linkage were applied), while its `initial_state`
and saved `Idm:ContentChecksum` now describe the **new** package version; a non-customized object receives the new
content, linkage, initial state and checksum. Linkage order is rebuilt from the new directives (op 4/11 unlink +
relink); GCV values on upgrade are kept verbatim (`upgradeOrDowngrade=true` branch), named passwords are merged.
`postUpgradeLinkage` re-applies the package-level linkage directives of every installed package that depends on the
changed one.

### 4.2 "Customized" as Designer defines it **[verified]**

`ItemImpl.isCustomized()`: the object has `Idm:PackageGuid` and `Idm:PackageAssocGuid`, is not itself inside an
`IdmPackage`, its package is installed on an enclosing target (or the transient flag is set), **and**
`Idm:ContentChecksum != calculateChecksum(false)`. `IdmPackageFolderImpl.isCustomized(target)` also counts a
*missing* installable object as customized. Checksum inequality is the whole definition — there is no three-way merge
anywhere; `validateChecksum(target)` (used by revert and the compare dialog) additionally tolerates `calculateChecksum
(false, 0|1)` for jobs.

### 4.3 The three commands **[verified]**

* **Revert** (`RevertCustomizations` → `IdmPackage.revertCustomizedPackageItems`): per folder `validateChecksum
  (target)`; deleted items are re-installed; customized ones get `restoreInitialState()` (name + `fromPackageXML` of
  the `initial_state` heavy data), then policies/ECMA/GCVs are relinked (ops 4/11 with overwrite) and every installed
  package's package-level linkage directives that mention the item are re-applied; named passwords, GCV settings
  (current values merged) and job settings restored.
* **Sync** (`SyncCustomizations` → `syncCustomizedPackageItems` → `syncItemToPackage`): copies the installed content
  into the catalog item (`pkgCopyCoreTo`), merges directives (`mergeInstallationDirectives` keeps placement order,
  server mode, prompt data, and linkage assoc/order/weight from the previous directive), recomputes both checksums,
  re-snapshots `initial_state`, and pushes the item to the package's other targets. Only offered for packages that
  are neither released nor imported.
* **Compare** (`CompareCustomizations`): opens `ComparePackageDialog` — read-only.
* Factory mode (`enableFactoryMode`) = revert everything, optionally strip non-package items and rebuild the filter
  from the packages (`resetFilterAndAttributes`).

### 4.4 `_initial_state` / `DirXML-pkgInitialState` **[verified]**

Written by `setInitialState()` in op 4 of every install (fresh and upgrade): `<ds-object ds-object-class=…
ds-object-name=… name=…>` with the item's `toPackageXML` body (XmlData, base64 `idm-installationdirective`,
`idm-directivechecksum`, `idm-packageassocguid`, `idm-packageguid`, `idm-contentchecksum` — the last being
`calculateChecksum(false)` **at snapshot time, i.e. with linkage set names**; test11 shows 3417194917 in both the CObject
and the initial state). Serialized with declaration + tabs. Deployed as the stream attribute, re-imported into the heavy
data, and consumed by `restoreInitialState()`.

---

## 5. Update site

**[verified]** Designer uses the classic Eclipse Update Manager (`org.eclipse.update.core`: `UpdateSearchRequest`,
`SiteManager`, `BatchInstallOperation`), not p2. `PkgUpdateAction` reads preference `Content Updates URL JSON`
(`IDMPlugin.initializeDefaultPreferences`), default
`{"Novell Public":["https://nu.novell.com/designer/packages/idm/updatesite1_0_0/",true],
"Novell Public 2.0":["https://nu.novell.com/designer/packages/idm/updatesite2_0_0/",true]}`
(individual defaults `Content Updates URL` / `Content Updates URL 2` with the same values; older
`http://cdn.novell.com/cached/designer/packages/idm/updatesite{1,2}_0_0/` entries are rewritten to nu.novell.com by
`packageUpdateDefaultUrl`). Designer's program updates use `Updates URL 3.0.0` =
`https://nu.novell.com/designer/updatesite4_10_0` (+ `/site.xml`). HTTP redirects are followed once, HTTP 401 gets a
`NetAuthenticator`, the deprecation list is fetched from `<site>/deprecations/deprecated.properties`.

Formats (what `PublishPackageAction` writes and the Update Manager reads):
* `site.xml`: `<site><description url="http://test">…</description><feature id="<SHORT>.feature"
  url="features/<SHORT>.feature_<version>.jar" version="<version>"/>…</site>` (id/version are split at the file name's
  last `_`; template `template/site.xml` in the packagemanager plugin).
* `features/<SHORT>.feature_<version>.jar`: MANIFEST (`Bundle-SymbolicName: <pkg symbolic name>; singleton:=true`,
  `Bundle-Version`, `Bundle-Name`, `Bundle-Vendor`, `Require-Bundle: com.novell.idm.packagemanager`,
  `Internal-Version`) and `feature.xml`:
  `<feature id="<SHORT>.feature" label="<name>" version="<version>" provider-name="<vendor>"><description>…</description>
  <plugin id="<SHORT>" download-size="0" install-size="0" version="<version>" fragment="true" unpack="false"/></feature>`.
* `plugins/<SHORT>_<version>.jar`: the package jar itself (copied unchanged). So a package version maps to
  `plugins/<Short-Name>_<Bundle-Version>.jar`, where `<version>` is `M.m.r.yyyyMMddHHmmss`.
* `deprecations/deprecated.properties`: `<SHORT>=<ver>[/<tooltip>],<ver2>…` with ranges `*-ver`, `ver-*`, `*`.

Local side: `<Designer>/packages` is an Eclipse configured site (`packages/eclipse/{plugins,features,deprecations}`);
the shipped catalog has 1,609 plugin jars and an empty `features/` — features only appear for packages installed via
the updater. `PackageVersionFilter` hides features whose exact version is already installed; after install the
catalog's `lastSuccessfulUpdateTime` is set and Designer restarts. Jars become "available packages" through the
`packageregistration` extension in each jar's `plugin.xml` (`PackageUtil.getPackageBundles`), keyed by
`Bundle-SymbolicName` and exact `Bundle-Version` (`PackageUtil.getBundle`).

---

## 6. Package building (Build / New Version / Publish)

**[verified]**
* Ids: `Core.generateID()` = `RandomID` = 8 characters from `[A-Z0-9]` (`java.util.Random` seeded from
  `SecureRandom`). Package id = `<8 chars>_<yyyyMMddHHmmssSSSS>` (`AddPackageAction`, `CopyPackageAction`:
  `item.getId() + "_" + SimpleDateFormat("yyyyMMddHHmmssSSSS")` — note the 4-digit `SSSS`, e.g.
  `RRKB9O08_201008101523510733`). Association ids are minted the same way per item (`addItemToPackage`,
  `fixupPackageItem`, `NewPackageVersionAction.reissuePackageAssociationIDs`). The item's own CObject id supplies the
  8-char prefix, so ids are random, not derived from content.
* Symbolic name (`IdmPackageImpl.getSymbolicName`) = `"com." + vendorName.replaceAll("[^a-zA-Z0-9]","").toLowerCase()
  + "." + shortName.toLowerCase()` (`pkgvendor` if the vendor is empty) — e.g. `com.novellinc.novledirdcfg`.
* `BuildPackageAction.performAction(pkg, dir, release)`: `buildTime = yyyyMMddHHmmss`; if the package is not yet
  released, `version.buildId = buildTime` → version `M.m.r.yyyyMMddHHmmss`; `release=true` sets `Idm:Released`; build
  host/user from `HOSTNAME`/`COMPUTERNAME`/`USERNAME`; `validateChecksum(null)` failures trigger `resetXLFIds()`
  (re-injects `xlfid(...)` ids) — nothing blocks the build. Jar `<SHORT>_<version>.jar` =
  MANIFEST (`Manifest-Version 1.0`, `Bundle-ManifestVersion 2`, `Bundle-Name`, `Bundle-Version`, `Bundle-Vendor`,
  `Bundle-SymbolicName: <sym>; singleton:=true`, `Require-Bundle: com.novell.idm.packagemanager`, `Internal-Version`,
  `Short-Name`, `Type`, `Dependencies` (base64), `Supported-Drivers` (base64), `Base-Package`, `Features` (base64)),
  `plugin.xml` (`<packageregistration><package id version/><display name="<SHORT>_<version>"/><symbolic name/>`),
  `package_import.xml` = `IdmPackage.toXml(doc, true)` serialized with declaration + tabs: `<package id name version
  type category-folder category symbolic-name checksum directive-checksum base-package>` + `description`,
  `category-description`, `category-folder-description`, `idm-shortname`, `idm-buildtime`, `idm-buildhost`,
  `idm-builduser`, `idm-released`, `idm-protected`, `idm-newversion`, `idm-creationtime`, `idm-vendorname`,
  `idm-vendoraddress`, `idm-vendorurl`, `idm-vendoremail`, `idm-contactname`, `idm-contactemail`,
  `idm-internalversion`, `idm-minidmversion`, `idm-maxidmversion`, `idm-minappversion`, `idm-maxappversion`, base64
  `license` and `readme`, `idm-installationdirective` (inline DOM, not base64), nine `package-folder` elements, and
  `<properties lang="xx">base64 .properties</properties>` per language (+ `provext`). Per `ds-object`
  (`toPackageXML` + `addPackageXML`): `XmlData` inline, `idm-installationdirective` **base64**, `idm-directivechecksum`,
  `idm-packageassocguid`, `idm-packageguid`, `idm-contentchecksum` (= `calculateChecksum(false)`).
* `NewPackageVersionAction`: same package id, category, base flag; version = old `M.m.r` + fresh buildId;
  `Idm:Released=false`, `Idm:PackageImported=false`, `newVersion=true`, `internalVersion+1`, new creation time, build
  fields cleared; folders deep-copied; association ids reused when an item with the same name and type existed in the
  previous version, otherwise minted; provisioning localization refreshed.
* `PublishPackageAction`: needs the built jar in the build dir; creates `features/`, `plugins/`, `deprecations/`
  (+ default `deprecated.properties`), writes the feature jar, copies the plugin jar, regenerates `site.xml` from the
  template listing every `features/*.jar` that has a matching `plugins/*.jar`.
* Protection: there is **no signature, digest or encryption**. `idm-protected` and `idm-released` are plain booleans
  read into `Idm:Protected` / `Idm:Released`; the packagemanager UI uses them (and `Idm:PackageImported`, which
  `doImport` always sets to true) only to hide editing actions (`package_created_local`, `package_released`,
  `protected_package` enablement tests; `AddItemToPackageAction` skips released/imported packages; `GeneralWizardPage`
  locks protected ones). Nothing gates install/upgrade on them. The only acceptance test on import is
  `PackageUtil.doImport`: `package_import.xml` must exist, root `<package>`, id/version/category/category-folder
  present, the version must not already be in the catalog, and `validateChecksum(null)` must pass (GCV mismatches are
  auto-repaired; other mismatches → error log + question dialog in the UI, tolerated headless).

---

## Facts DirXMLDev's design must respect

1. Content checksums are CRC32 over the UTF-8 concatenation of `name` + canonical XML (+ type-specific suffixes, §1.3).
   Canonical XML = nxsl parse → `DOMUtil.stripWhitespace` → `DOMWriter` (declaration, tab indent, no trailing
   newline). Use nxsl.jar's classes to produce it rather than re-implementing the writer.
2. For policies, ECMAScript resources and GCV objects the **installed** checksum includes the names of the policy sets
   they are linked into (in `addPolicySetRefs` order); the catalog/jar number does not. `DirXML-pkgChecksum` must carry
   the installed number, `idm-contentchecksum` in a jar the catalog number. Recompute after linking, never copy.
3. GCV checksums hash definitions (sorted by name) but never values; prompt answers do not create "customized" state.
   Designer silently repairs GCV checksum mismatches on catalog import, so a small drift there is survivable; policy /
   resource mismatches are not silently repaired.
4. Directive checksum = CRC32 of the stored directive string byte-for-byte (§1.4); store the directive exactly as
   Designer would serialize it (declaration + tabs) and hash that.
5. Package `checksum` = CRC32 of folder checksums 1..8 (folder 9 excluded); folder checksum = CRC32 of the children's
   stored content checksums as decimal strings, sorted by association id (§1.5).
6. "Customized" ⇔ `Idm:ContentChecksum != calculateChecksum()`. There is no merge; on upgrade a customized object keeps
   its customized content while its baseline (`initial_state`) and stored checksum move to the new package version.
   DirXMLDev's `.package-baseline/` + `customized` mark is the same model and should be kept in sync with
   `initial_state`/`DirXML-pkgInitialState`.
7. Upgrade = uninstall old + install new; objects are matched across versions by association id (rename-aware) or by
   name, and only when the placement container is the same; unmatched old objects are deleted; linkage is rebuilt from
   the new directives.
8. Linkage: Weight inserts before the first strictly-greater weight, existing non-package policies count as −1 (package
   policies land after hand-made ones), ties keep install order; each link is recorded in `Idm:InstalledLinkages` /
   `DirXML-pkgLinkages` as `<policy-set name order="Weight" channel package-id value Driver/>` and that record is what
   later weight comparisons read.
9. Vault stamping: per object `DirXML-PkgItemAux` {`DirXML-pkgAssociationId`, `DirXML-pkgGUID` =
   `id;symbolicName;version;name;shortName`, `DirXML-pkgLinkages`, `DirXML-pkgChecksum`, `DirXML-pkgInitialState`};
   driver `DirXML-pkgGUID` (base package only) and `DirXML-PkgTargetAux`/`DirXML-pkgExtensions`. There is no vault list
   of installed packages: Designer reconstructs it from item `DirXML-pkgGUID` values and needs the exact
   `symbolicName` + `version` jar in `<Designer>/packages/eclipse/plugins`, else it strips the package association.
   Any package DirXMLDev installs must therefore exist as a jar in every Designer that will import the vault.
10. Project representation: `Idm:InstalledPackages` reference relations on `Driver_`/`DriverSet_`/`IdentityVault_`
    to `IdmPackage_` CObjects in the catalog (which carry `Idm:PackageGuid`, `Idm:PackageVersion`, `Idm:PackageType`,
    `Idm:BasePackage`, `Idm:shortName`, checksums, `Idm:PackageImported`, folders); installed items carry
    `Idm:PackageGuid`, `Idm:PackageAssocGuid`, `Idm:ContentChecksum`, `Idm:InstalledLinkages`, `initial_state`;
    the driver carries `Idm:FilterExtensions`.
11. Filter-ext resources are never objects: merge class/attr by precedence (§2.7) and maintain the extensions cache.
12. Driver `ds-attributes` are applied by name; filter, GCVs, shim config, manifest are **set**, engine controls are
    merged; other packages' GCVs are separate objects linked with `gcv` weight.
13. Prompts are XSLT: the directive of each prompt target (and the XmlData of non-GCV targets) is transformed with the
    answered `<configuration-values>` as `$defsDoc`; headless answers key on definition `display-name` (§2.5).
14. Dependencies: `type` = package type of the dependency (2/3/4); type 3/4 deps are satisfied by the driver set / vault;
    versions match by exact list, or min ≤ v ≤ max, or anything; candidates newest first; features (mandatory/optional
    `<package id/>`) apply only to base packages; one base package per target.
15. Building: ids `XXXXXXXX_yyyyMMddHHmmssSSSS` with an 8-char `[A-Z0-9]` random prefix; versions `M.m.r.yyyyMMddHHmmss`;
    symbolic name `com.<vendor>.<short>`; MANIFEST fields and `plugin.xml` as in §6; no signature. A DirXMLDev-built jar
    is accepted by Designer if `package_import.xml` is well-formed, the version is new to the catalog, and every
    stored checksum matches (§1) — the 5/5 policy+resource matches and the package-level match show this is attainable.
16. Update site = Eclipse Update Manager layout (`site.xml`, `features/<SHORT>.feature_<ver>.jar` with `feature.xml`,
    `plugins/<SHORT>_<ver>.jar`, `deprecations/deprecated.properties`); default URLs
    `https://nu.novell.com/designer/packages/idm/updatesite1_0_0/` and `…/updatesite2_0_0/`.

## Sources

Decompiled with CFR 0.152 into the scratchpad (`…/scratchpad/pkg/`):
* `idm.jar` → `com.novell.idm.packages.{PackageCRC32, PackageChecksum, PackageUtil, PackageBundle, PackageVersion,
  PackageDependency, PackagePrompt, PromptTarget, RemoveOperationComparator, directives.{LinkageDirective,
  PlacementDirective, ServerDirective, PromptDirective, ReferenceDirective}}`; `com.novell.idm.model.impl.{ItemImpl,
  XMLContainerImpl, PolicyImpl, TransformPolicyImpl, NonMappingPolicyImpl, ScriptPolicyImpl, StylesheetPolicyImpl,
  MappingPolicyImpl, IDMResourceImpl, GlobalConfigImpl, IDPolicyImpl, EntitlementImpl, NotfTemplateImpl, JobImpl,
  IdmPackageImpl, IdmPackageFolderImpl, DriverImpl, DriverSetImpl, IdentityVaultImpl}`; `com.novell.idm.{IdmModel,
  IdmConstants, IDMPlugin}`; `com.novell.idm.ui.contentupdates.{PkgUpdateAction, PkgInstallCommand, PkgUpdateSite,
  PackageVersionFilter}`.
* `packagemanager.jar` → `PackageConstants`, `actions.{BuildPackageAction, NewPackageVersionAction,
  PublishPackageAction, AddPackageAction, CopyPackageAction, AddItemToPackageAction}`, `commands.{PackageCommand,
  PackageOperation, InstallPackage, UninstallPackage, CompareCustomizations, RevertCustomizations, SyncCustomizations}`,
  `core.AutoUpdatePackagesPreferencePage`; plugin `plugin.xml`, `template/site.xml`.
* `deploy.jar` → `com.novell.idm.deploy.internal.importer.{DeployImporter_Load, DeployImporter_Import}`, `DeployUtil`,
  `ImportConfigAction`.
* `novellcore.jar` → `com.novell.core.util.{XmlUtil, RandomID}`, `com.novell.core.Core`; `nxsl.jar` →
  `com.novell.xml.dom.{DOMWriter, DOMUtil, DocumentFactory}`, `com.novell.xml.util.XMLUtil`,
  `com.novell.xml.parser.XMLParserFactory`; `designermodel.jar` → `CObjectImpl.getId`.
* Data: `/Applications/Designer/packages/eclipse/plugins/NOVLEDIRDCFG_2.1.0.20120831225140.jar` (MANIFEST, plugin.xml,
  package_import.xml), `/Applications/Designer/packages/eclipse/deprecations/deprecated.properties`,
  `/Users/jcombs/designer_workspace/test11/Model/…` (`8OFYTMD8.Driver_`, `8OFYTMD8/A85MBG6X.ScriptPolicy_`,
  `A85MBG6X_initial_state.xml`, catalog twin `VRBZFYPC.ScriptPolicy_`, `HLYRDAQT.IdmPackage_`, `ACVN54TW.IdmPackage_`,
  `ZEZTZUKV.DriverSet_`, `49832PBW.IdentityVault_`).
* Verification programs: `…/scratchpad/pkg/verify/PkgChecksum.java`, `InstalledChecksum.java` (run with
  `nxsl.jar` + `xp.jar` on the classpath).
* Prior notes: `docs/spikes/pdt-analysis.md` §1.2, 3.1, 3.3; `docs/plan.md` Phase 7.
