# Spike 7c: Designer's verdict on our package work (2026-09-10)

The acceptance test for Phase 7 ([packages.md](../packages.md) §5.3): does
Designer accept what DirXMLDev builds and installs?

| check | result |
|---|---|
| 1. A package **we built** (`PBTEDIRCUST 1.0.0` — the JFW eDir driver's two hand-made policies and the five driver GCVs they read, `package.build` → `package.site`, served from `file:///…/DirXMLDev-e2e/site/`) added as a package site and imported into Designer's catalog | **passed** (Jerry, 2026-09-10): "imported PBTEDIRCUST from the site with no complaints" — the update-site layout, the jar (manifest, plugin.xml, package_import.xml) and every stored checksum satisfy Designer's import checks |
| 2. A driver **we installed and deployed** (`PkgTest7` on the test vault, from NOVLEDIRBASE 2.1.2 + NOVLEDIRDCFG 2.1.0) imported from the vault into a project: both packages shown installed, nothing marked modified | **passed** (Jerry, 2026-09-10): "PkgTest7 shows both packages installed, nothing modified" — the stamps the deployer writes (`DirXML-PkgItemAux`/`DirXML-PkgTargetAux`, GUID records, association ids, installed checksums, linkage records, initial state) are what Designer's importer expects |
| 3. PBTEDIRCUST installed by Designer onto an eDirectory driver: two policies and a GCV object appear and validate | pending |

Note on 2: a vault import never brings a package into Designer; the objects
carry only ids (`DirXML-pkgGUID`, association id) that Designer resolves
against its own catalog, stripping the association when the jar is absent.
That is why the catalog keeps every package we install as a jar, and why
`package.site` exists: Designer users get our packages the normal way.

The same import surfaced pre-existing conditions in the test vault that are
not ours: a disabled rule on the AcctExpNotif driver (April 2026) whose
`do-send-email-from-template` names a template DN with the wrong tree name
and no such object — Designer's importer throws a NullPointerException on
it; packaged GCV objects deployed earlier without `DirXML-pkgLinkages`
(Designer warns; ours carry it); two vendor packages absent from the
importing Designer's catalog (their objects are de-packaged on import — the
behaviour that makes `package.site` necessary).

**Check 3, first attempt (2026-09-10): the package was not offered on the driver.**
Cause found in Designer's code, not in our jar: the driver's Add Package dialog
lists project-catalog packages plus the package *bundles* registered in the
OSGi extension registry (`PackageMgrUtil.getUnImportedPackageBundles` ←
`PackageBundleCache.getPackageBundles` ← extension point
`com.novell.idm.packagemanager.packageregistration`). A jar downloaded by
Check for Package Updates is only copied into `packages/eclipse/plugins`; it
becomes a registered bundle when Designer restarts — the update action ends
with a restart prompt (`PkgUpdateAction`: `PlatformUI.getWorkbench().restart()`
on "yes"). Declining the restart leaves the package downloaded but invisible.
Two smaller facts from the same reading: an empty `<supported-drivers/>`
means "any driver" (`PackageBundleValueObject.isDriverTypeSupported` returns
true for an empty list), so the builder's original omission did not hide it;
and Eclipse's update core caches a site's `site.xml` for the session, so a
version published after the first check is offered only after a restart.
The builder now declares the driver type anyway (1.0.1), matching what
vendor packages do.
