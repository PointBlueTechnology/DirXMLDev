# Spike 1 — LDAP write path: **PASS** (2026-09-08)

**Question:** can the deploy identity create, modify, and delete DirXML policy
objects over LDAP, with `XmlData` round-tripping exactly? **Yes.**

Run against the test vault (`ldaps://172.17.2.81:636`, bind `cn=admin,ou=sa,o=system`,
driver set `cn=driverset1,o=system`) with `LdapWriteSpike`:

```
OK   create cn=dirxmldev-spike-1788881424634,cn=Library,cn=driverset1,o=system
OK   read-back equals what we wrote
OK   modify XmlData
OK   read-back after modify equals v2
     server-side attributes on the scratch object: objectClass=Top  cn=dirxmldev-spike-…
OK   delete cn=dirxmldev-spike-1788881424634,cn=Library,cn=driverset1,o=system
```

## What it establishes

- **Creating a `DirXML-Rule`** needs only `objectClass: Top, DirXML-Rule`, `cn`, and
  `XmlData` — no other mandatory attributes; no schema violation.
- **`XmlData` round-trips byte-exact** when written and read as binary
  (`java.naming.ldap.attributes.binary=XmlData`). The server does not normalize,
  re-serialize, or touch the XML — so our canonical serializer is the only thing
  that determines on-disk/in-vault formatting. Good for diffs.
- **Replace-modify works in place** (`REPLACE_ATTRIBUTE` on `XmlData`).
- **Delete works**; the object left no residue.
- **The server stamps nothing package-related** on a hand-created object (no
  `DirXML-pkg*`). Package attributes exist only on packaged objects — consistent
  with the vault-side observation on the mapping-table resource
  (`DirXML-pkgGUID`, `DirXML-pkgInitialState`, `DirXML-pkgChecksum`,
  `DirXML-pkgAssociationId`, `DirXML-ContentType`). When *we* edit a packaged
  object, *we* must maintain those (spike 2 determines exactly how).
- The **trust-all LDAPS** path and the deploy identity's rights are sufficient for
  Library-scope writes.

## Implications for the deployer (Phase 4)

- Deploy is plain LDAP: `add`/`modify`/`delete` of `DirXML-*` objects with the
  content attrs (`XmlData` for policies; `DirXML-Data` for resources), plus linkage
  and config attrs on the driver/channel objects. No proprietary API needed.
- Because the server doesn't rewrite `XmlData`, **diff = compare canonical forms**;
  a post-deploy re-read can verify byte equality.
- Snapshot/rollback can be a plain LDIF export/import of the affected objects.

## Still open → spike 1b (engine pickup)

Whether the **engine** honors a newly linked/modified policy after
`RestartDriver` (and which changes are re-read live without a restart) needs the
extended-op API (spike 3) and a driver whose restart has no side effects.
