# Spike 7b′: package stamps through the vault (2026-09-10)

**Question.** After `driver.add --packages`, does `vault.deploy` write what
Designer writes, and does a live import bring it back unchanged?

**Run** (test vault, scratch driver `PkgTest7` from `NOVLEDIRBASE 2.1.2` +
`NOVLEDIRDCFG 2.1.0`, prompts answered, deployed with
`--allow-missing-secrets`, deleted afterwards):

| object | classes | attributes written |
|---|---|---|
| driver | `Top, DirXML-Driver, DirXML-PkgTargetAux, DirXML-PkgItemAux` | `DirXML-pkgGUID` (base record), `DirXML-pkgExtensions` (filter-extension cache) |
| policy / GCV object | `Top, DirXML-Rule` or `DirXML-GlobalConfigDef`, `DirXML-PkgItemAux` | `DirXML-pkgGUID`, `DirXML-pkgAssociationId`, `DirXML-pkgChecksum` (installed number), `DirXML-pkgLinkages`, `DirXML-pkgInitialState` (= the tree's `.package-baseline` bytes, md5-identical) |

`verify: vault matches the tree`; `vault.diff` empty; a second deploy plans
0 steps; `import-live` of the driver set reproduces every stamp byte for
byte (`tree.diff` empty).

**What changed to get there.**

1. The schema puts the driver's own `DirXML-pkgGUID` in **`DirXML-PkgItemAux`**
   and `DirXML-pkgExtensions` in `DirXML-PkgTargetAux`; a driver with both
   needs both aux classes (Designer's drivers carry both). Adding an attribute
   without its aux class fails with `-608 illegal attribute`. New objects get
   the classes on `add`; existing objects get an `AUX_CLASS` plan step
   (`objectClass += …`, idempotent) before a stamp `modify`.
2. `import-live` and `vault.diff` now read the subtree through our own
   connection with **every attribute** (the simulator's live reader asks for
   a fixed list without `DirXML-pkg*`); `DirXML-pkgExtensions` is binary;
   `DirXML-pkgInitialState` is kept out of meta (the baseline file is the
   tree's copy).
3. `ModelDiff` compares the four stamp keys on artifacts and the two on
   drivers (`package-stamps` changes), so a package install onto an existing
   driver — or an as-found vault whose tree was imported before stamps were
   read — shows up as a change the deployer applies. **Trees imported before
   this change need a fresh `import-live` (or `package.adopt`) before
   `vault.diff` is clean again**: the test vault showed 168 stamp-only
   changes across 14 drivers until re-imported.
4. A blank package attribute (`shim-auth-id` with no value) must leave the
   driver field unset, not empty — the vault has no empty string.
