# Spike 4 — vault object classes, package checksum, secrets: **PASS** (2026-09-08)

Run with `com.pointblue.dirxml.dev.spike.VaultSpike` against the test vault
(`ldaps://172.17.2.81:636`, driver set `cn=driverset1,o=system`, driver
`cn=Querytest`), through the new `deploy.Vault` primitives. Scratch objects
under `cn=Library` were created and deleted in `finally`.

## 1. Every object class the deployer writes round-trips byte-exact

| class | content attribute | result |
|---|---|---|
| `DirXML-Rule` | `XmlData` | OK, create + modify + read back byte-exact |
| `DirXML-StyleSheet` | `XmlData` | OK |
| `DirXML-Resource` | `DirXML-Data` + `DirXML-ContentType` | OK (`objectClass: Top, DirXML-Resource`; the server adds nothing else) |
| `DirXML-GlobalConfigDef` | `DirXML-ConfigValues` | OK |

The Library container is `objectClass: Top, DirXML-Library`. Minimal attribute
sets (`objectClass`, `cn`, the content attribute) are accepted for all four.

## 2. The server does **not** update `DirXML-pkgChecksum` on a content change

A scratch object created with a real packaged policy's `XmlData` and all its
`DirXML-pkg*` attributes (`pkgAssociationId`, `pkgChecksum`, `pkgGUID`,
`pkgInitialState`, `pkgLinkages` — the server accepts them on a hand-created
object), then modified:

```
checksum before=1318535667 after=1318535667 -> SERVER DOES NOT UPDATE the checksum
```

So a vault-deployed customization of a packaged object leaves the vault's
checksum pair (`DirXML-pkgChecksum` vs the baseline inside
`DirXML-pkgInitialState`) **equal** — Designer, and package upgrade, would
still consider the object unmodified. Combined with spike 2 (Designer's
"modified" test is plain integer inequality of that pair, and the number is
not a function of the content):

**Deployer rule:** when it writes a packaged artifact the tree marks
`package.customized`, it also sets `DirXML-pkgChecksum` to a content-derived
integer (CRC32 of the canonical content) — different from the baseline with
overwhelming probability, stable across re-deploys of the same content — so
the vault's pair differs and Designer / upgrade see the override. The tree's
`.package-baseline/` remains the actual record of what was customized.

## 3. Secrets (4a)

- **Named passwords** work through the extended ops on both a driver and the
  driver set: `SetNamedPassword(dn, name, displayName, char[])` → the name
  appears in `ListNamedPasswords` (whose entries are `String[] {name,
  displayName}`, not strings) → `RemoveNamedPassword`. The test driver set
  already holds `NOVLLIBLDAP.password`.
- **`DirXML-ShimAuthPassword` is LDAP-writable by the deploy identity**
  (`replace` succeeded) — and, on this vault, **readable back** (an 8-byte
  value came back after the write). So the deployer can set the shim password
  with a plain modify, and *can verify it* by re-reading — but must treat what
  it reads as a secret (never print, snapshot, or log it).
- Driver state / start option through the ext ops: Querytest `stopped`, start
  option `1` (manual).

**Caution recorded:** the first spike run wrote `spike-pw` into Querytest's
`DirXML-ShimAuthPassword` before it was known the attribute was set and
readable; the previous value is gone. Querytest is the in-house test shim
(no real authentication), so nothing broke — but the deployer's rule follows:
**never write a secret without a snapshot of the attribute first**, and the
spike now skips that write unless `-Dspike.writeShimPassword=true`.

## Implications

- `deploy.Vault` (this spike's primitives) is the LDAP/ext-op layer for Phase 4
  as designed; no surprises in object shapes.
- Packaged objects: content + a content-derived `DirXML-pkgChecksum` on
  customized ones (decision 2 refined).
- Secrets: shim password = LDAP modify (+ re-read verification, value never
  shown); named passwords = ext ops; Remote Loader password still to be
  located (not present on Querytest — check a Remote Loader driver's
  attributes when one is available; it is expected to be another driver
  attribute or a named password).
