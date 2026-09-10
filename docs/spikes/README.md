# Phase 0 spikes

De-risking the two real unknowns before any foundation code (see
[../plan.md](../plan.md), Phase 0). Each spike is read-only or scratch-only; none
touches a real object in any vault.

| # | Spike | Question it settles | Status |
|---|---|---|---|
| 1 | **LDAP write path** — `LdapWriteSpike` | Can we create/modify/delete `DirXML-Rule` objects + `XmlData` over LDAP with the deploy identity? What does the server stamp on them? | ✅ **PASS** → [`ldap-write.md`](ldap-write.md) |
| 1b | **Engine pickup** — `EnginePickupSpike` | After linking a policy and `RestartDriver`, does the engine load and run it? Are live edits re-read? | ✅ **PASS** (proven from the driver trace; `SubmitEvent` semantics logged as a Phase-2/DxCMD open item) → [`engine-pickup.md`](engine-pickup.md) |
| 2 | **Package modified flag** | Which attribute marks a packaged object as modified/customized, and how is the baseline stored? | ✅ done — a checksum *pair*, not a boolean → [`modified-flag.md`](modified-flag.md) |
| 2b | **Checksum algorithm** | Can we compute `Idm:ContentChecksum`? | ✅ done — **negative**: not a content function (identical content, different checksums) → [`checksum-algorithm.md`](checksum-algorithm.md) |
| 3 | **Extended-op API** | Constructor/response conventions for Start/Stop/Restart, Set/GetDriverSet, InitDriverObject, Migrate/Resync, Submit*, named passwords, GCVs, cache, version | ✅ done → [`extended-ops-api.md`](extended-ops-api.md) |

## Running spike 1 (test vault only — it writes)

```bash
export JAVA_HOME=…/zulu-21
mvn -q compile
java -cp "target/classes:$(ls lib/*.jar | tr '\n' ':')$HOME/.m2/repository/com/pointblue/dirxml/dirxml-simulator/1.5.0/dirxml-simulator-1.5.0.jar" \
  -Dspike.url=ldaps://HOST:636 -Dspike.bindDn='cn=admin,ou=sa,o=system' -Dspike.password=… \
  -Dspike.driverSetDn='cn=driverset1,o=system' \
  com.pointblue.dirxml.dev.spike.LdapWriteSpike
```

It creates `cn=dirxmldev-spike-<ts>,cn=Library,<driverSetDn>`, reads/modifies it,
prints the server-side attributes, and deletes it in a `finally`. Record the
output in [`ldap-write.md`](ldap-write.md).

## Phase 4 spike (test vault only — it writes scratch objects)

`com.pointblue.dirxml.dev.spike.VaultSpike` — object classes, package checksum,
secrets; same `-Dspike.*` properties plus `-Dspike.driver=<side-effect-free
driver DN>`; findings in [`vault-objects-and-secrets.md`](vault-objects-and-secrets.md).
The guarded `VaultTest` runs the same primitives under JUnit with
`-Dvault.url/.bindDn/.password/.driverSetDn[/.driver]`.

## Phase 5 spike (test vault only — starts/stops the Querytest driver)

`com.pointblue.dirxml.dev.spike.OperateSpike` — engine/driver stats, cache
queue/view/clear parameters, live trace level, SubmitCommand/SubmitEvent
observed in the driver trace over SSH; `-Dspike.phase=a|b`,
`-Dspike.ssh=root@host`; findings in [`operate.md`](operate.md).
- [pdt-analysis.md](pdt-analysis.md) — NetIQ Package Deployment Tool 1.0 analyzed (2026-09-09): a REST/UI wrapper that shells out to Designer's `DesignerHeadless` application; the headless command surface, package jar format, Route A (drive Designer headless) vs Route B (native Phase 7), and ranked recommendations.
- [designer-headless.md](designer-headless.md) — Designer's headless application launches on macOS: `listContents -L P` listed 480 base packages in 20 s, `#OPERATION_SUCCESS`; `-l` wants a file; next spike is `deployDriver -f`.
- [package-format.md](package-format.md) — packages: the live update site (`site.xml` → feature jar → `plugins/SHORT_ver.jar`), jar types 2/3/4, what the vault records (`DirXML-pkg*`, driver base record, filter ownership in pkgExtensions, jar vs vault checksums differ).
- [designer-package-layer.md](designer-package-layer.md) — Designer 4.10.1's package layer decompiled and verified: checksum recipes (recomputed exactly), install order, placement, weight rule, prompts as XSLT, filter-ext merge, dependency resolution, upgrade = uninstall+install with customizations kept, update-site layout, package building (ids, versions, no signature).
- [designer-writer.md](designer-writer.md) — spike 6a: Designer opened a writer-updated project (new policy, minted id, rewritten relations) with no errors; `.proj`/`.cproj` naming lesson for copied projects.
- [package-checksums.md](package-checksums.md) — spike 7a: every Designer package checksum recomputed over the 1,609-jar catalog (all kinds exact except jobs 1/30 and 29 stale package-level values Designer never validates); empty-content and GCV-in-directive findings.
- [package-install-parity.md](package-install-parity.md) — spike 7b: `package.install` / `driver.add --packages` reproduce Designer's eDirectory driver install object for object (18/18 installed checksums, every set order = the vault's); prompts via the engine's XSLT, one transaction per package set, package-level linkage.
- [package-vault-stamps.md](package-vault-stamps.md) — spike 7b′: the deployer writes Designer's package stamps (aux classes, GUID/assoc/checksum/linkages/initial state, driver GUID + extensions); live import and `vault.diff` round-trip them; trees imported earlier need a re-import.
