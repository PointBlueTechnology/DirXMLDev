# Design note: cloning an Identity Vault into a lab tree (`vault.clone`)

*Status: decided and built 2026-09-21 (first cut, §10). Jerry's decisions: same container names; no identity data in the first cut; driver run-time state excluded; iManager RBS skipped; a second eDirectory instance (`EDIR_TEST2_TREE`, no engine) as the target.*

## 1. What Jerry asked for

Read an existing Identity Vault — its configuration, schema, tree structure,
notification objects, password policies "etc." — and recreate them in a lab tree,
with the imported driver set properly associated with the lab's existing server,
so that development and testing happen on a faithful clone rather than on the
customer's tree. Two questions came with it: how to order the objects so that
references resolve, or whether the server can be made to defer reference checks
until everything exists (LBURP?).

## 2. What the labs say (2026-09-21, read-only on ig4, scratch objects on idm254)

**The tree.** ig4 (`IDM_IG4_TREE`, eDirectory 9.2.8, IDM 4.10) holds 14,333
entries: 13,156 users under `o=data`, 574 under the driver set, ~350 notification
templates, the rest containers, groups, iManager RBS objects, Security. So a
*configuration* clone is on the order of 1,200 objects; a *data* clone is the
other 13,000.

**How a driver set is tied to a server.** One attribute, on the driver set:
`DirXML-ServerList` = the NCP server's DN (`cn=idm-ig4,ou=servers,o=system`). The
server object carries nothing pointing back (`ncpServer` has no DirXML attribute in
the schema, and ig4's server objects have none). `DirXML-Job` objects also MUST a
`DirXML-ServerList`. That is the whole of "associate with the server": rewrite those
values to the lab server's DN. (The engine on that server must hold a replica of the
partition the driver set sits in — trivially true in a single-server lab.)

**What eDirectory enforces at create time — the reference question, answered.**
Tested on idm254 with scratch objects, cleaned up:

| operation | result |
|---|---|
| add a group whose `member` names an object that does not exist | **rejected**, LDAP 19 constraint violation, NDS −613 |
| add a user whose `nspmPasswordPolicyDN` names a missing policy | **rejected**, −613 |
| add an entry under a parent that does not exist | rejected, LDAP 32, NDS −601 |

So DN-valued attributes are checked when the entry is written, and there is no
"defer references" switch. ICE's `-F` ("allow forward references") creates a
placeholder **only for a missing parent container**, never for an attribute value
([NetIQ ICE documentation](https://training.netiq.com/documentation/edirectory-92/edir_admin/data/a5hgmnu.html));
and LBURP (advertised by ig4: the three `2.16.840.1.113719.1.142.100.*`
extensions) is a *bulk transport* for ordered update operations — faster, not
laxer. **Ordering is our job**, and it is a two-phase write (§4).

**Schema.** ig4 defines 2,150 attributes and 279 classes; idm254 2,188 / 288. 145
attributes and 5 classes exist only on ig4 (product schema like `DirXML-nwo*`, and
the customer's own `jfe*`, `ll*`, `pb*`, `test555`). eDirectory accepts schema
extension over LDAP (`changetype: modify` on `cn=schema`, `add: attributeTypes` /
`objectClasses`, NetIQ's `X-NDS_*` flags included). 188 attributes have DN syntax.
Among the classes we care about, only these MUST a DN attribute: `DirXML-Job`
(`DirXML-ServerList`), the RBPM assignment classes (`srvprvDelegationAssignment`,
`srvprvDelegatorAssignment`, `srvprvProxyAssignment`) and `nrfAttestation`.
Everything else can be created without its DN attributes and get them afterwards.

**What lives where.**

| what | where | clone? |
|---|---|---|
| driver set, drivers, channels, policies, resources, entitlements, jobs, `AppConfig` (forms, PRDs, `RoleConfig`, `ResourceConfig`, roles, resources, nav items…) | `cn=driverset1,o=system` subtree, 574 objects | yes, whole subtree, generic copy |
| password policies and challenge sets (3), assigned through `nsimAssignments` on the policy and `nspmPasswordPolicyDN` on containers/users (5 on ig4) | `cn=Password Policies,cn=Security` | yes |
| notification templates, 351 `notfMergeTemplate`, package-stamped | `cn=Default Notification Collection,cn=Security` | yes (the collection exists on any tree) |
| containers, groups (27), the admin/service accounts under `ou=sa` | `o=system`, `o=data` | yes (structure); accounts by choice |
| users (13,156), `DirXML-Associations`, `nrfAssignedRoles`, `pwmUser` | `o=data` | **option** `--data`, see §6 |
| CA, KMOs, issued certs, CRLs, SDI key partition, login methods, Login Policy, SecretStore, RBPM trusted roots | `cn=Security` | **never** — tree-bound cryptographic state; the lab has its own |
| NCP server, LDAP Server/Group, SAS, volumes, partition/replica objects | `ou=servers,o=system` | never |
| iManager RBS collection (`rbs*`, ~110 objects under `cn=Default,o=data`) | | no by default (the lab iManager owns its own); flag to include |
| the Identity Applications database (workflow, PermIndex, request history) | not in the vault | out of scope — a lab IDApps pointed at the clone rebuilds it |

**What cannot cross trees, attribute by attribute** (the exclusion list the
exporter applies): server-computed and operational attributes (`GUID`,
`createTimestamp`, `modifyTimestamp`, `revision`, `localEntryID`, `entryFlags`,
`subordinateCount`, `Obituary`, `Back Link`, `Reference`); eDirectory-maintained
inverse links, of which only the forward side is written (`member` not
`groupMembership`, `securityEquals` not `equivalentToMe`, `manager` not
`directReports`); passwords (`userPassword`, `nspmDistributionPassword` — Universal
Password is not readable); `networkAddress`; `ACL` is copied but last (§4);
`DirXML-ShimAuthPassword` (10 on ig4: encrypted with the source server's keys,
useless on the lab — re-set from the lab's secrets file); `DirXML-DriverStorage` (4)
and `DirXML-PersistentData` (18) are driver run-time state — excluded by default,
`--keep-driver-state` to carry them; `DirXML-Associations` (2 under the driver set,
30 tree-wide) is data. `DirXML-pkgInitialState` and the other package stamps are
kept: Designer and our tooling need them.

**DN rewriting.** LDAP DNs carry no tree name, so if the lab keeps the same
container names (`o=system`, `o=data`, …) nothing but the server DN changes — and
that matters, because DNs also live *inside* values: GCVs (`idv.dit.data.users` =
`o=data\ou=users` in slash form), policies, `srvprvRequestXML`, ACL trustees. The
note therefore proposes **same container names** as the default and a DN map
(`--map "cn=idm-ig4,ou=servers,o=system=cn=lab1,ou=servers,o=system"`) for the
few values that must change; a general "rename the base container" is possible
(rewrite every DN-syntax value and every DN-looking string in XML) but is a later
option, and the note says so honestly rather than promising it.

## 3. Shape: two commands and an inspectable bundle

Not a model-level feature. The as-code model deliberately carries what a developer
edits; a clone needs *everything* on an object (trace settings, jobs, per-server
values, ACLs, `AppConfig` internals), verbatim. So the clone is a **generic,
attribute-faithful object copy** with a small amount of understanding (schema,
ordering, exclusions, DN rewriting), and the as-code tooling is used *afterwards*
on the clone (`import-live` → tree → develop → deploy), exactly as on any lab.

```
bin/idm vault.export-clone --env ig4prd --out clone/acme-2026-09-21 [--rbs] [--keep-driver-state]
bin/idm vault.import-clone --env lab1 --from clone/acme-2026-09-21 [--server <labServerDn>] \
        [--map <srcServerDn>=<labServerDn> …] [--driver-server <driver>=<srcServerDn> …] \
        [--replace] [--replace-driverset] [--yes] [--json]
```

(As built. `--include` and `--data` from the proposal are not in the first cut: it clones
configuration only. `--yes` writes; without it the command plans and stops.)

`export-clone` reads the source (our `Vault`, every attribute as bytes, binary
attributes marked) and writes a **bundle**: plain LDIF, ordered, reviewable,
diffable, shippable from a customer site, and ICE-loadable as a fallback:

```
clone/<name>/
  manifest.json          source tree, base DNs, server DN, counts, excluded attributes, date
  10-schema.ldif         attributeTypes then objectClasses the target lacks (computed at import time too)
  20-containers.ldif     every non-leaf entry, parents first (DN depth), no DN-syntax attributes
  30-objects.ldif        every other entry, parents first, no DN-syntax attributes
  40-references.ldif     changetype: modify — the held-back DN-syntax attributes, one modify per entry
  50-acls.ldif           changetype: modify — ACL values, last (every trustee now exists)
  secrets-needed.txt     driver / named-password secrets the target must be given (names only)
```

Secrets never enter the bundle. Passwords never enter the bundle.

`import-clone` reads the bundle and the target, computes the plan (schema delta,
which entries already exist, which DN values need the map), snapshots the touched
containers the way `vault.deploy` does, writes in order with the existing `Vault`
primitives, then **verifies**: re-reads the target and compares every cloned entry
attribute by attribute (exclusions applied) and reports counts per container and
every difference. Re-runnable: an entry that exists is skipped (`--replace` to
overwrite), a modify that is already in place is a no-op.

## 4. The order, and why two phases are enough

1. **Schema**: attributes the target lacks, then classes in `SUP` order. Never
   modifies a definition that exists (reported instead).
2. **Entries, parents first** (sort by DN depth, then name), each written with
   its **non-DN attributes only** — plus the few mandatory DN attributes
   (`DirXML-ServerList` on jobs and the driver set → the lab server, which exists;
   the RBPM assignment classes, written after their targets by holding those
   entries to the end of this phase).
3. **References**: for every entry, one `modify` adding its DN-syntax attributes
   (188 attributes on ig4 have DN syntax; the exporter knows them from the schema).
   Every target exists now, so eDirectory accepts each value; cycles (group ↔ group,
   user ↔ manager) are no problem because no *entry* creation depends on them.
4. **ACLs** last, for the same reason.
5. **Driver-set association**: the driver set's `DirXML-ServerList` is written in
   phase 2 already rewritten; the lab engine picks the driver set up when it next
   scans (or on restart). Drivers are created **stopped / manual start** — cloned
   `DirXML-DriverStartOption` values are forced to manual, and the note says so:
   nobody wants a cloned AD driver waking up in the lab with the customer's
   endpoints.

Why not LBURP: 1,200 config objects write in well under a minute with plain LDAP
adds over one connection; 13,000 users take a few minutes. LBURP is a transport
optimisation ICE uses; the bundle's LDIF is ICE-compatible (`ice -S LDIF -f
30-objects.ldif -D LDAP … -F -c`) if someone ever wants that path, and `-F` covers
exactly the one thing our ordering already guarantees. Not implementing LBURP.

## 5. What the tool must refuse or warn about

- A target that is not a lab: `import-clone` requires the target environment's
  `tier` to be `lab`/`stg` (the same tiering deploy uses) and refuses `prd`.
- Schema conflicts: an attribute or class that exists on the target with a
  different definition (syntax, SUP, MUST) is reported and left alone; the clone
  continues unless the class is one the bundle's entries use.
- Existing objects: skipped and listed; `--replace` deletes-and-recreates (never
  for containers with children).
- The driver set already existing on the target with the same DN: refuse unless
  `--replace-driverset` (snapshot first).
- Values the DN map cannot cover: a DN-syntax value naming an object neither in
  the bundle nor on the target (a user outside the cloned scope as a group member,
  a trustee under a container not cloned) is **dropped with a note**, never
  invented.
- Tree-name-bearing values in XML (`\\IDM_IG4_TREE\…` forms) are reported by count
  per attribute, with a one-line hint (rare in IDM config; expected in nothing we
  clone by default).

## 6. Data: users and groups (option)

`--data <container>[:limit=N][:filter=(…)]` clones a slice of identity data with
the same machinery: users and groups under the container, their DN attributes in
phase 3 (`member`, `manager`, `nrfAssignedRoles` targets inside the driver set),
`DirXML-Associations` **kept** (so the clone's drivers see associated users — the
values name the *driver DN*, which is the same in the clone), passwords absent.
`--user-password KEY` sets one lab password on every cloned user from the
environment's secrets (never a literal). No pseudonymisation in this note; if a
customer's names cannot enter the lab, that is a separate transform on the bundle
(the LDIF is plain text) and a decision for §8.

## 7. Verification, on our labs

- **Spike 1 (read-only, 1 day):** `export-clone` of ig4 → bundle; check the
  bundle by numbers (entries per container, held-back attribute counts, the
  exclusion list applied, the secrets list) and by reading three objects of each
  family against the vault.
- **Spike 2 (needs a target, §8):** `import-clone` into a fresh single-server tree
  with IDM installed; then `import-live` the clone and `tree.diff` it against
  `import-live` of ig4 — the two trees must differ only in the excluded attributes
  and the server DN. Start one harmless driver (Loopback / Null) on the clone to
  prove the association. Then a `vault.deploy` of a small policy change to the
  clone, the ordinary way.
- Unit tests on `FakeVault` for: schema delta ordering, DN-depth ordering,
  held-back attributes, the map, the refusals, re-run idempotence, verification.

Size: exporter + bundle ~2 days, importer + verification ~3 days, spikes 1 + 2
in between. Roughly the driver-icons and stamp-vocabulary work combined.

## 8. Decisions for Jerry

1. **A target tree.** The realistic target is a fresh eDirectory 9.2 + IDM 4.10
   engine, single server, empty but for `o=system`, `ou=servers`, `cn=Security`.
   idm254 is not it: its driver set is also `cn=driverset1,o=system`, so a clone
   of ig4 would collide, and idm254 is in daily use as a deploy target. Do you have,
   or can you stand up (k3s?), a throwaway tree the tool may build and rebuild?
2. **Same container names** in the lab as at the source (the default, and what
   makes the clone faithful with only the server DN remapped) — or must the tool
   support renaming the base containers from day one?
3. **Scope of the first cut:** schema + Security (password policies, notification
   collection) + containers + driver set, no data — then `--data` as the second
   cut? Or is a user slice needed from the start to make the clone useful?
4. **Driver state:** excluded by default (`DirXML-DriverStorage`,
   `DirXML-PersistentData`), drivers forced to manual start. Agree?
5. **iManager RBS objects:** skip by default?
6. **Pseudonymisation** of user data: out of scope for this note unless you say
   otherwise.

Sources: [NetIQ ICE utility (eDirectory 9.2)](https://training.netiq.com/documentation/edirectory-92/edir_admin/data/a5hgmnu.html),
[Improving bulkload performance](https://www.netiq.com/documentation/edir88/edir88/data/bqu6wcq.html).
