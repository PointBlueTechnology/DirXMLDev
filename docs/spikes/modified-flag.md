# Spike: how Designer marks a packaged IDM object as MODIFIED

Projects analyzed: `~/designer_workspace/test11` and the unzipped
`AMICA-PRD-2026-06-27.zip` (→ `scratchpad/amica-prd/AMICA-PRD-20260627`).

## 1. The attribute(s) that mark modification

There is **no boolean "modified"/"customized" flag**. Modification is
computed by comparing a checksum pair — the same pair used for the
object's "installation directive":

| Current value (lives in `<ID>.<Type>_` CObject) | Baseline value (lives in `<ID>_initial_state.xml`) |
|---|---|
| `attrName="Idm:ContentChecksum"` (xsi:type `CLong`) | `<ds-attribute ds-attr-name="idm-contentchecksum"><ds-value>` |
| `attrName="Idm:DirectiveChecksum"` (CLong, seen on GCV/placement objects) | `<ds-attribute ds-attr-name="idm-directivechecksum">` |

When `Idm:ContentChecksum` (current) ≠ `idm-contentchecksum` (package
baseline), the object is customized/modified relative to its package.
Identity attributes `Idm:PackageAssocGuid` and `Idm:PackageGuid` stay
**identical** between current and baseline in every sample checked (0
mismatches across 956 objects) — they identify *which* package item the
object came from, not whether it changed.

**UNCHANGED example** (`0O4NJHK9.ScriptPolicy_`, amica):
```
<attributes ... attrName="Idm:ContentChecksum" value="303876320"/>
```
baseline (`0O4NJHK9_initial_state.xml`):
```
<ds-attribute ds-attr-name="idm-contentchecksum"><ds-value>303876320</ds-value></ds-attribute>
```
→ equal → UNCHANGED.

**MODIFIED example** (`KKG927AR.ScriptPolicy_`, amica):
```
<attributes ... attrName="Idm:ContentChecksum" value="1801215884"/>
```
baseline (`KKG927AR_initial_state.xml`):
```
<ds-attribute ds-attr-name="idm-contentchecksum"><ds-value>1572411971</ds-value></ds-attribute>
```
→ differ → MODIFIED. `Idm:PackageAssocGuid`/`Idm:PackageGuid` were
identical in both files (`MBHAM66E_201006231751400713` /
`XTEF1YO3_201006231733410161`).

No attribute named Modified/Customized/Changed/Override/Dirty/Baseline/
Lock/Original exists anywhere in the `Idm:` namespace in either project
(full inventory below) — grepped exhaustively, zero hits besides the
Checksum family.

## 2. How the package baseline is stored

- A packaged object's metadata carries two `CHeavyData` pointer
  attributes with no inline value: `attrName="contents"` (→
  `<ID>_contents.xml`, the live payload) and `attrName="initial_state"`
  (→ `<ID>_initial_state.xml`, the frozen package baseline). Presence of
  the `initial_state` CHeavyData attribute correlates 1:1 with the
  physical `_initial_state.xml` file existing (150/150 in test11,
  951/951 in amica) — its mere presence means Designer has captured a
  baseline snapshot for that object at least once.
- `<ID>_initial_state.xml` wraps the baseline in a
  `<ds-object ds-object-class="..." ds-object-name="..." name="...">`
  element containing `<ds-attributes>` → `<ds-attribute
  ds-attr-name="...">` → `<ds-value>`. For policy/mapping/filter/
  resource content the payload attribute is `ds-attr-name="XmlData"`
  and its `<ds-value>` holds the original content **inline as nested
  XML** (not base64) — re-indented/pretty-printed differently than the
  live `_contents.xml`. The same `<ds-object>` also always carries
  `idm-packageassocguid`, `idm-packageguid`, `idm-contentchecksum`, and
  (for objects that have a placement/config payload, e.g. GCV defs)
  `idm-installationdirective` (base64-encoded nested XML) with its own
  `idm-directivechecksum`.
- **Important caveat**: because the baseline XML is re-indented inside
  the `ds-value` wrapper, a naive text diff between `_contents.xml` and
  the baseline's `XmlData` is *not* reliable evidence of a real edit —
  whitespace/pretty-print alone produces textual differences with no
  semantic change. The checksum pair is the authoritative signal, but
  its exact algorithm (byte-level? canonicalized-XML level?) could not
  be reverse-engineered from static files alone (see §4).

## 3. Counts

| Project | Packaged objects (`Idm:PackageAssocGuid` present) | Have a captured baseline (`_initial_state.xml`) | Of those, comparable via `XmlData`/`_contents.xml` | MODIFIED (checksum differs) | UNCHANGED (checksum equal) |
|---|---|---|---|---|---|
| test11 | 1084 | 150 | 134 | **21** | **113** |
| amica (PRD) | 1908 | 951 | 822 | **180** | **642** |

(The 16/129 objects with a baseline but no `XmlData`/`_contents.xml`
comparison are non-content objects such as GlobalConfig/GCV definitions,
which store their payload only as the base64
`idm-installationdirective`, not as `XmlData`.)

Object types covered by the MODIFIED/UNCHANGED comparison: ScriptPolicy,
MappingPolicy, MappingTableResource, StylesheetPolicy, Entitlement,
ECMAScriptResource.

## 4. Uncertainty / what could NOT be fully determined

- **Both projects DO contain real modified packaged objects** (21 in
  test11, 180 in amica), so this is not a hypothetical — the checksum
  mismatch is observed in the wild.
- The **exact checksum algorithm** is unknown. Spot-checking one
  "MODIFIED" object (`EG8D36SV`/test11) whose baseline vs. current
  content were byte-identical after whitespace normalization still
  showed differing checksums — meaning the checksum is sensitive to
  exact serialization (indentation/line endings), not just semantic
  content, OR Designer computed it before a later purely-cosmetic
  re-save. Conversely, one "UNCHANGED-by-checksum" object
  (`QSU09IJ5`/amica) had a real semantic diff (`disabled="true"` added
  to two rule elements) that the checksum did **not** pick up —
  suggesting the checksum may be computed over a subset of the DOM that
  excludes certain attributes, or that the checksum simply wasn't
  refreshed after that particular edit. Net effect: treat the checksum
  mismatch as a strong, IDM-native signal, but not proven to be
  100%-complete at the attribute level.
- We could not determine whether Designer **recomputes**
  `Idm:ContentChecksum` automatically whenever it loads/saves a project,
  or trusts whatever value is on disk. This matters directly for a
  writer tool (see §5).
- Full `Idm:` attribute inventory (both projects combined, for
  reference — nothing beyond the Checksum family relates to
  modification state): `PackageGuid`, `ContentChecksum`,
  `PackageAssocGuid`, `InstallationDirective`, `DirectiveChecksum`,
  `FolderId`, `InstalledLinkages`, `PkgPromptType`, `vendorName`,
  `shortName`, `newVersion`, `minIdmVersion`, `description`, `Released`,
  `Protected`, `PackageVersion`, `PackageType`, `PackageImported`,
  `InternalVersion`, `CreationTime`, `BuildUser`, `BuildTime`,
  `BuildHost`, `BasePackage`, `FilterExtensions`, `vendorURL`,
  `vendorEmail`, `provDataChecksum`, `ContactName`, `ContactEmail`,
  `minAppVersion` — all package-definition/version bookkeeping on
  `.IdmPackage_` objects, not per-object modification state.

## 5. What a writer tool must do when editing a packaged object

1. Write the new payload to `<ID>_contents.xml` as usual.
2. Update `attrName="Idm:ContentChecksum"` in the sibling
   `<ID>.<Type>_` CObject file to the checksum of the **new** content —
   do not leave the old value in place, and do not touch
   `Idm:PackageAssocGuid` / `Idm:PackageGuid` (these must stay pointing
   at the original package association regardless of edits).
3. **Never touch `<ID>_initial_state.xml`** — it is the immutable
   package baseline; modification status is derived by comparing
   against it, so overwriting it would erase the very evidence of
   customization that upgrade tooling relies on. If no
   `_initial_state.xml`/`attrName="initial_state"` exists yet for this
   object, the first edit is presumably the point at which Designer
   would create one (capturing pre-edit content as the new baseline) —
   a tool imitating Designer's behavior should do the same before
   changing `_contents.xml`.
4. Because the checksum algorithm itself is not confirmed (see §4),
   leaving `Idm:ContentChecksum` stale after editing `_contents.xml` is
   not a safe fallback — a stale-but-present value could cause the
   engine/Designer to misreport modification status either way.
   Recommend validating against a live Designer install (open the
   edited project and confirm it shows the "customized" overlay) before
   relying on a home-grown checksum implementation in production
   tooling.
