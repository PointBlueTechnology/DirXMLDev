# A vendor-free vault path (design note)

**Status: built and verified live 2026-10-15 (V1–V4).**

## Why

The directory-console uses DirXMLDev and should ship with no vendor jars. Today everything that
talks to a vault needs two of NetIQ/OpenText's jars on the class path:

- **`ldap.jar`**: the LDAP client (`LDAPConnection`, the JSSE socket factory, `LDAPException`).
- **`dirxml_misc.jar`**: the IDM extended-operation classes (`com.novell.nds.dirxml.ldap.*`) for
  driver state, start/stop, cache, named passwords, jobs and so on, plus `DxConst` for the
  engine version.

The console's container mounts the customer's jars at `/engine` for this reason, and its tests
take `-Dengine.lib`.

## Decided (Jerry, 2026-10-15)

1. **The LDAP client is open-source JLDAP**: `com.novell.ldap:jldap:4.3` from Maven Central,
   under the OpenLDAP Public License. It has the same API as the vendor's `ldap.jar` (every class
   Vault uses is in it), and trace-core and the web already ship it.
2. **The scope is the vault/operate path**: everything that talks to a vault. That covers
   `vault.*` (import-live, diff, deploy, rollback, clone), `driver.*` / `driverset.*` / `engine.*`
   / `job.*` / `rbe.*`, the trace over LDAP, and `Environments` / `LegacyTls`. That is the
   console's whole surface.

**Out of scope:** the features that *run the engine*. Policy compilation (`validate`'s compile
check), `simulate`, and package GCV checksums link the real engine (`dirxml.jar`, which needs
`dirxml_misc.jar` itself). They keep needing the engine jars, loaded only when those features
run, as today.

## How

### The extended operations, encoded by our own code

The trace viewer already does this for four operations (`IdmExtendedOperations` in trace-core).
We extend the same approach to every operation Vault uses: an `IdmOps` class in
`com.pointblue.dirxml.dev.deploy` that builds `LDAPExtendedOperation`s with JLDAP's BER classes
and decodes the replies. Nothing is copied from the vendor's classes. The wire format below is
what we implement, and the tests check our bytes against the vendor's (see Verification).

**The format.** Every request's OID is `2.16.840.1.113719.1.14.100.<n>`, and its reply has the
next number. A request value is either absent, or a sequence of BER elements written back to
back:

| Java value | BER |
|---|---|
| `String` (a DN, key, event-id …) | OCTET STRING, UTF-8 |
| `int` | INTEGER |
| `byte[]` | OCTET STRING |
| `String[]` | SEQUENCE OF OCTET STRING |
| `int[]` | SET OF INTEGER |
| a password (`char[]`) | OCTET STRING of its UTF-8 bytes, zeroed after use. No vendor encryption: the LDAPS session is the protection. |

A reply value is a sequence of the same element types, read by position. Large results
(cache view, driver stats, schema, jobs) come back in chunks: a data handle and size, then
`GetChunkedResult` (33) until done, then `CloseChunkedResult` (35).

**The operations Vault and Operate use**, with their request numbers:

| # | Operation | Request value |
|---|---|---|
| 1 / 3 / 5 | get / set / clear the server's driver set | none / DN / none |
| 7 / 9 | get / set a driver's start option | DN / DN, option, suppress-resync |
| 11 | engine version | none |
| 13 / 15 / 17 / 101 | driver state / start / stop / restart | DN |
| 19 | driver stats (chunked) | DN, timeout, cache type |
| 21 | driver schema | DN |
| 23 | resync | DN, modified-since (epoch seconds) |
| 25 / 27 / 29 / 31 | migrate / queue event / submit command / submit event | DN plus the document |
| 33 / 35 | get / close a chunked result | handle, max size, timeout / handle |
| 41 / 43 | view / delete cache entries | DN, timeout, position, count, type×2 / DN, position, count, event-id, type×2+1 |
| 53 / 55 / 57 / 59 | notify / start / abort / state of a job | job DN |
| 63 / 65 | set / clear log events | DN, event ids / DN |
| 71 / 73 | set / clear the Remote Loader password | DN, password / DN |
| 75 / 77 / 81 | set / remove / list named passwords | DN, key, description, password / DN, key / DN |
| 93 | JVM stats | version, timeout |
| 95 / 97 | apply / view activation | driver-set DN, document / driver-set DN, timeout |
| 103 / 105 / 107 / 109 | set / clear the mutual-auth keystore and key passwords | DN, password / DN |

Each reply's shape is filled in per operation as it is built. Each is checked against the
vendor's decoder and then live (see Verification).

### The rest

- **`Vault`** swaps its `com.novell.nds.dirxml.ldap.*` imports for `IdmOps` calls. Its JLDAP
  imports stay, now resolved from Maven Central. Legacy TLS (`LegacyTls`) is unchanged.
- **`Operate`** replaces `DxConst.parseDirXMLVersion` with its own decoding of the packed
  version integer.
- **`pom.xml`** gains `com.novell.ldap:jldap:4.3` (compile scope) and drops the `system`-scope
  `ldap.jar` / `dirxml_misc.jar` entries from the vault path. The engine profile keeps them for
  the compile, simulate and checksum features. `bin/idm` puts jldap on the class path from `~/.m2`.
  A release attaches it, as it does the PostgreSQL and trace-core jars.
- **`doctor`** reports two readinesses. *Vault*: JDK and JLDAP, so it is always ready once
  installed. *Engine*: the engine jars and the simulator, needed only for
  validate-compile/simulate/checksum. `bin/require-engine.sh` and the CLI's error say which
  features need the engine jars, instead of refusing everything.
- **trace-core's `jldap` exclusion** (PR #72) goes, since core then has JLDAP itself.

### Downstream

- **directory-console**: drop the `/engine` mount and the `-Dengine.lib` tests once it moves to the
  release that has this, and pin its DirXMLDev version.
- **DirXMLDevWeb**: its image already carries JLDAP (through trace-core). Vault features stop
  needing engine jars; validate's compile check and simulate still do.

## Verification

1. **Byte-for-byte, offline.** A guarded test, which runs only when `lib/dirxml_misc.jar` is
   present, builds each request with both our `IdmOps` and the vendor's class, and asserts the
   OID and value bytes are identical. It also decodes recorded reply values with both. The vendor
   jar is used only by that test and only if present: never a dependency, never committed.
2. **A portable unit test** checks our encoder on known byte strings (in CI, with no vendor jars).
3. **Live, read-only, on idm254 and edir3**: version, driver set, driver state, start option,
   stats, schema, cache view, named-password list, job state, activation view, JVM stats, and
   the trace over LDAP.
4. **Live writes, on a scratch driver on idm254 only, with a snapshot first and your go-ahead
   per run**: start/stop/restart, start option, a named password set and removed, log events
   set and cleared, a cache entry deleted, a job started. Each is cleaned up and the vault diff
   is shown clean.
5. **Containment.** A test runs the vault path with a class loader that hides
   `com.novell.nds.dirxml.*`, so any vendor class still reachable fails the build.

## Steps

- **V1** `IdmOps`: the encoder, the decoders and the chunked-result loop, with the byte-for-byte
  and portable tests.
- **V2** `Vault` and `Operate` on `IdmOps`; jldap from Maven Central; the containment test.
- **V3** `pom.xml`, `bin/idm`, `doctor`, `require-engine.sh`, the release assets, and the docs
  (`install.md`: what needs the engine jars now).
- **V4** Live verification (read-only, then the scratch writes above).
- Then a core release, and the console PR (drop `/engine`).

## Open

- **JLDAP 4.3 dates from 2009.** The vendor's `ldap.jar` is newer. Anything Vault relies on that
  differs (TLS defaults, referral handling) shows up in V4. The fallback for a gap is a small fix
  in our code, not the vendor jar.
- **Licensing.** The OpenLDAP Public License needs its notice shipped with the jar: a `NOTICE` /
  third-party file in the release and in the console's image.

## Results (2026-10-15)

- **V1.** `IdmOps` builds every request Vault uses byte for byte as the vendor's classes do (40
  requests), and reads every reply as they do. `IdmOpsVendorTest` checks this with the vendor jar
  loaded in a class loader of its own, and passes on both the vendor's BER classes and JLDAP 4.3's.
  The engine version's packing matches `DxConst`.
- **V2.**
  - `Vault` and `Operate` are on `IdmOps`, and JLDAP 4.3 comes from Maven Central; `ldap.jar` is
    gone from the build.
  - The full suite passes on it (138 suites), including the engine-backed ones.
  - Two more edges were cut along the way: the trust-all socket factory JNDI loads by name (it was
    the simulator's `TrustAllSocketFactory`, now `deploy/TrustAllSockets`), and the simulator's DOM
    helper in the vault path (now `xml/Dom`). The package-baseline read moved to `AsCodeWriter`.
- **Containment.**
  - `VendorFreeTest` walks from exactly the methods the directory-console calls, method by method,
    through every call their bytecode makes. An interface call reaches every implementation.
  - It fails on any vendor class named by an instruction, a catch type, a signature, or a string
    that is loaded by name.
  - It reaches 272 methods and none name a vendor class. A planted reference fails it.
  - The rest of `Operate` (cache snapshots into a tree, submit) still reaches the tree and the
    validator. The console does not call those, and classes load lazily.
- **V3.** `doctor` has a `vault` line ("no engine jar needed"), `ldap.jar` is out of every jar
  list, `bin/idm` puts JLDAP on the class path, and THIRD-PARTY.md carries the notices.
- **V4, read-only, on idm254**, with a class path of only this project, JLDAP, trace-core and the
  PostgreSQL driver (no `lib/`, no simulator). These all answered as the released build does:
  - `engine.version` (4.10.2.0 build 3), `driverset.status` (state and start option of all four
    drivers), `driverset.servers` (the server's driver set);
  - `engine.stats` (driver and JVM statistics, both chunked), `driver.status`,
    `driver.secrets list`, `job.list` (job state), `driver.log-level`.

  `driver.cache view` on the running User Application driver and `ViewActivation` answer "Other" on
  this lab with the released vendor-class build too, so the engine refuses them and our encoding is
  not the cause. The trace over LDAP first needed the simulator jar (its `EdirTraceStream`). It now
  uses the trace viewer's own stream (`dirxml-trace-core`, 2026-10-15), so it needs neither.
- **V4 writes** (go-ahead given 2026-10-15) ran on a scratch Loopback driver and a scratch job on
  idm254. Both were deployed from the full imported tree, with a snapshot first. The vendor-free
  class path did all of these:
  - the start option set to auto and back to manual;
  - a named password set, listed and removed;
  - the Remote Loader, shim-auth, key and keystore passwords each set and cleared;
  - log events set, then cleared (`--inherit`, ClearLogEvents);
  - an event queued into the stopped driver's cache, viewed, and the cache cleared (DeleteCacheEntries,
    with the engine's snapshot);
  - the driver started, restarted and stopped;
  - the job started.

  `AbortJob` on the already finished job answers "Other", as it does with the released vendor-class
  build. The deploy itself used our SetDriverStartOption and NotifyJobUpdate. The scratch driver and
  job were then removed (`--delete-driver`), and `vault.diff` showed no differences.

**2026-10-15, later:** the directory-console (main at 06e2035) already runs on 0.37.0 with nothing at
`/engine`. It now calls more of DirXMLDev:
- `Operate`: `startOption`, `resync`, `passwordSync`, `migrate`, `migrateIntoApp`, `logLevelSet`,
  `lifecycle`, `jobAction`, `engineStats`, `driversetStatus`, `driversetActivation`, `driverHealth`,
  `driverAssociations`, `cacheClear`;
- `IdmOps` directly.

`VendorFreeTest` now starts from all of these and checks that each one exists. It walks 353 methods,
and none reaches a vendor class. Keep its list in step with the console.
