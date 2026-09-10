# Spike 7a: package checksums recomputed over Designer's whole catalog (2026-09-10)

**Question.** Can DirXMLDev reproduce every checksum Designer stores in a
package jar — the numbers Designer validates on catalog import and compares to
decide "customized"?

**Instrument.** `packages.PackageChecksum` (the recipes from
[designer-package-layer.md](designer-package-layer.md) §1, using the engine's
`nxsl.jar` canonicalizer and `dirxml_misc.jar` GCV classes — the same classes
Designer links), `packages.PackageJar` (jar reader), `packages.ChecksumAudit`,
run by `spike.ChecksumSpike` over `/Applications/Designer/packages/eclipse/plugins`
(1,609 jars, 10 s).

**Result.**

| kind / object class | match | mismatch |
|---|---:|---:|
| content DirXML-Rule | 6575 | 0 |
| content DirXML-StyleSheet | 414 | 0 |
| content DirXML-Resource (filter-ext, mapping-table, pkg-prompt, ECMAScript, ds-object, text/xml) | 4763 | 0 |
| content DirXML-GlobalConfigDef | 1029 | 0 |
| content DirXML-Entitlement | 548 | 0 |
| content notfMergeTemplate | 2181 | 0 |
| content DirXML-idPolicy | 6 | 0 |
| content DirXML-Job | 1 | 29 |
| directive (every object class) | 15546 | 0 |
| package directive-checksum | 1609 | 0 |
| package checksum | 1580 | 29 |

**Findings beyond the research note.**

1. An XML resource with **no content** (e.g. `MFAZUREBASE-UpgradeSettings`, a
   `pkg-prompt+xml` of `idm-pkgprompttype` 5) hashes as name + the bare
   declaration `<?xml version="1.0" encoding="UTF-8"?>` + content type — what
   Designer's serializer yields for an empty document.
2. A package GCV object keeps its `<configuration-values>` in its
   **installation directive**, not in `XmlData`; the checksum is over the
   definitions parsed by the engine's `GCDefinitions` (type codes are the
   engine's: string 0, boolean 1, integer 2, real 3, dn 4, enum 5,
   password-ref 6, gcv-ref 7, header 8, group 9, subordinates 10, list 11,
   dn-ref 12, structured 13), definitions sorted by name, values excluded.
3. The 29 package-level `checksum` mismatches are **stale values in the jars**
   (e.g. `NOVLUABASE` 4.8.6 and 4.8.7 store the same number, 4.8.8 another,
   while their folder contents hash identically; `NOVLHANABASE_1.0.1` has four
   matching resources and still disagrees). Designer's
   `IdmPackageImpl.validateChecksum(null)` checks the package *directive*
   checksum and every folder's item checksums, never the package `checksum`
   attribute — so it is informational. We compute it by the recipe when we
   build; we do not reject a fetched jar over it.
4. Jobs: the recipe (`JobImpl.calculateChecksum(set, 2)` — name + XML +
   job-definition attributes + audited result-processing + trace settings)
   reproduces 1 of 30; the `getConfigvaluesChecksum()` seed it uses for
   package items is not yet understood. Jobs are rare (30 objects across the
   whole catalog). Until solved, the installer carries a job's **stored**
   checksum through (Designer's installed job checksum only adds project-side
   scopes/templates), and `package.build` refuses jobs.

**Consequence.** Every checksum that Designer validates or compares is
reproducible for policies, stylesheets, resources, GCV objects, entitlements,
templates and ID policies. `package.fetch` can verify a jar; the installer can
write the installed number (content + linked set names) that Designer expects
in `DirXML-pkgChecksum`; `package.build` can produce jars Designer will accept.
