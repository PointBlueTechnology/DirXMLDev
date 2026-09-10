# Spike 7c: Designer's verdict on our package work (2026-09-10)

The acceptance test for Phase 7 ([packages.md](../packages.md) §5.3): does
Designer accept what DirXMLDev builds and installs?

| check | result |
|---|---|
| 1. A package **we built** (`PBTEDIRCUST 1.0.0` — the JFW eDir driver's two hand-made policies and the five driver GCVs they read, `package.build` → `package.site`, served from `file:///…/DirXMLDev-e2e/site/`) added as a package site and imported into Designer's catalog | **passed** (Jerry, 2026-09-10): "imported PBTEDIRCUST from the site with no complaints" — the update-site layout, the jar (manifest, plugin.xml, package_import.xml) and every stored checksum satisfy Designer's import checks |
| 2. A driver **we installed and deployed** (`PkgTest7` on the test vault, from NOVLEDIRBASE 2.1.2 + NOVLEDIRDCFG 2.1.0) imported from the vault into a project: both packages shown installed, nothing marked modified | pending |
| 3. PBTEDIRCUST installed by Designer onto an eDirectory driver: two policies and a GCV object appear and validate | pending |

Note on 2: a vault import never brings a package into Designer; the objects
carry only ids (`DirXML-pkgGUID`, association id) that Designer resolves
against its own catalog, stripping the association when the jar is absent.
That is why the catalog keeps every package we install as a jar, and why
`package.site` exists: Designer users get our packages the normal way.
