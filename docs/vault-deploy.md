# Phase 4 design — vault deploy with safeguards

Status: **design** (2026-09-08). Follows [plan.md](plan.md) Phase 4; builds on
the Phase 0 spikes ([spikes/ldap-write.md](spikes/ldap-write.md),
[spikes/engine-pickup.md](spikes/engine-pickup.md),
[spikes/extended-ops-api.md](spikes/extended-ops-api.md)), the model, the
validator, and the Phase 3 tree operations.

## What Phase 4 delivers

A validated, simulated IDM-as-code tree becomes the running configuration of a
vault — through a diff you can read, a plan you approve, a snapshot you can
roll back to, and a verification that proves the vault now matches the tree.

```
idm vault.diff     tree/ --env stg                 what differs, per object
idm vault.deploy   tree/ --env stg --dry-run       the plan: every LDAP write, every restart
idm vault.deploy   tree/ --env stg --yes           snapshot → write → restart → verify → audit
idm vault.rollback        --env stg --snapshot …   put the snapshot back
idm vault.verify   tree/ --env stg                 re-read the vault; diff must be empty
```

Nothing here is new protocol: it is LDAP writes of `DirXML-*` objects plus the
`RestartDriver` extended operation, exactly what Phase 0 proved the engine
honours. What is new is the discipline around them.

Not in Phase 4: driver lifecycle beyond what deploy needs (Phase 5),
provisioning forms (Track P). Secrets the tree can't carry (shim and Remote
Loader passwords, named passwords) **are** in scope — see "Secrets" below.

## The contract, from the spikes

- **Objects.** Policies are `DirXML-Rule` (DirXML Script, schema map) or
  `DirXML-StyleSheet` (XSLT) with content in `XmlData`; resources are
  `DirXML-Resource` with `DirXML-ContentType` + `DirXML-Data`; GCV objects are
  `DirXML-GlobalConfigDef` with `DirXML-ConfigValues`. A driver is a
  `DirXML-Driver` with `DirXML-JavaModule`, `DirXML-ShimAuthServer`,
  `DirXML-ShimAuthID`, `DirXML-ShimConfigInfo`, `DirXML-ConfigValues`,
  `DirXML-DriverFilter`, `DirXML-EngineControlValues`, and the linkage
  `DirXML-Policies` (typed-name values `<policyDN>#<order>#<setId>`); its
  `Subscriber` / `Publisher` children hold channel policies; the driver set
  holds `DirXML-ConfigValues` and its own `DirXML-Policies` (GCV objects, set
  14); `cn=Library` under the driver set holds shared artifacts.
- **The server does not touch content.** `XmlData` round-trips byte-exact
  (spike 1), so **diff = canonical compare** of what the tree would write against
  what the vault holds, and post-deploy verification is byte equality.
- **The engine loads policies at driver start; a live edit is not re-read;
  `RestartDriver` reloads** (spike 1b). So every change to a running driver
  ends in a restart, and a stopped driver just gets the writes.
- **A policy that fails to load aborts the driver** — which is why nothing is
  written that `validate` didn't pass.
- **Extended ops** are `DirXMLRequest` subclasses in `dirxml_misc.jar` over
  Novell JLDAP (`lib/ldap.jar`): `GetDriverState` (0 stopped / 1 starting / 2
  running / 3 stopping), `StartDriver`, `StopDriver`, `RestartDriver`. Stops can
  take a minute with some shims; wait on state, not on the response.

## Artifact ↔ vault mapping

| tree | vault DN | class | content attribute |
|---|---|---|---|
| `library/X` | `cn=X,cn=Library,<dsDn>` | by kind | |
| `drivers/D/X` | `cn=X,cn=D,<dsDn>` | by kind | |
| `drivers/D/subscriber/X` | `cn=X,cn=Subscriber,cn=D,<dsDn>` | by kind | |
| `drivers/D/publisher/X` | `cn=X,cn=Publisher,cn=D,<dsDn>` | by kind | |
| policy, DirXML Script / schema map | | `DirXML-Rule` | `XmlData` |
| policy, XSLT | | `DirXML-StyleSheet` | `XmlData` |
| mapping table / ECMAScript / other resource | | `DirXML-Resource` | `DirXML-Data` + `DirXML-ContentType` |
| GCV-definition resource | | `DirXML-GlobalConfigDef` | `DirXML-ConfigValues` |
| driver settings and config blobs | `cn=D,<dsDn>` | `DirXML-Driver` | the attributes above |
| driver linkage | `cn=D,<dsDn>` | | `DirXML-Policies` (replace the whole set, in tree order) |
| driver-set GCVs / GCV linkage | `<dsDn>` | `DirXML-DriverSet` | `DirXML-ConfigValues` / `DirXML-Policies` |

The live reader ([`LdifReader.readLive`](../src/main/java/com/pointblue/dirxml/dev/source/LdifReader.java))
already implements the read direction of this table; the deployer is its
inverse, and the two are tested against each other (write → re-read → equal).

A driver in the tree that does not exist in the vault is created
(`DirXML-Driver` + `Subscriber` + `Publisher` containers, attributes, artifacts,
linkage), **stopped**, with start option *manual* — starting it is a separate,
explicit act (Phase 5), because it is the first moment real events flow. A
driver in the vault that the tree lacks is **never deleted** by deploy; the diff
reports it and `vault.deploy --delete-driver D` is the explicit path.

## Packages

A packaged object in the vault carries `DirXML-pkgGUID`,
`DirXML-pkgAssociationId`, `DirXML-pkgInitialState`, `DirXML-pkgChecksum`
(spike 1). The deployer **modifies content and leaves those attributes alone**;
it never fabricates them on new objects (the tree's project-derived package
meta is not a vault attribute). What it does add: whether the server updates
`DirXML-pkgChecksum` when `XmlData` changes is unknown — the first build step
measures it on a scratch packaged object in the test vault, and the answer
goes into `spikes/package-checksum-live.md`. Either way the tree keeps the
truth (`.package-baseline/`, `package.customized`), and Designer's "modified"
comparison is against its own baseline.

## The diff

`vault.diff tree/ --env <name> [--driver D…] [--json]`:

1. read the vault's driver set live into a model (`LdifReader.readLive`);
2. compare it with the tree's model, object by object, in a `ModelDiff`:
   - artifacts: **added** (tree only), **removed** (vault only), **changed**
     (canonical content differs — with the text diff), **kind changed**
     (e.g. a policy became XSLT: delete + add);
   - per driver: shim class / auth server / auth id, each config blob
     (`shim-config-info`, `config-values`, `driver-filter`,
     `engine-control-values`), the linkage of each policy set (as an ordered
     list — a reorder is a change);
   - driver set: GCVs, GCV linkage;
   - drivers only in the vault (reported, never acted on without
     `--delete-driver`).
3. print it grouped by driver, then Library, then driver set; exit 1 when
   anything differs (so CI can assert "vault == tree").

`ModelDiff` is pure (model vs model) and is also `idm tree.diff a/ b/` — the
same engine, usable offline and in tests. Vault-only attributes the model
doesn't carry (trace level, start option, package attrs, timestamps) are not
part of the diff and are never written.

## The plan and the deploy

`vault.deploy` turns a diff into a **plan** — an ordered list of steps, each a
single LDAP operation or extended op — and prints it before doing anything:

```
plan for stg (cn=driverset1,o=system), 3 driver(s) affected:
  1. add     cn=sub-ctp-NormalizeTitle,cn=Subscriber,cn=AD,…   DirXML-Rule (XmlData 2.1 KB)
  2. modify  cn=AD,…                                           DirXML-Policies (11 values; +1 at set 10 order 3)
  3. modify  cn=LocationCodeMap,cn=Library,…                   DirXML-Data (+3 rows)
  4. restart AD                                                running → restart after writes
  5. restart Loopback                                          running → restart (links library/LocationCodeMap)
  snapshot: 3 object(s) + 2 driver state(s) → deploy-snapshots/stg/2026-09-08T14-02-11.ldif
```

Order within a plan: **Library objects first, then driver-scope objects, then
channel objects, then driver attributes and linkage, then driver-set attributes,
then restarts** — so a linkage never points at an object that doesn't exist yet
and a restarting driver sees the finished state. Deletes go last among the
writes, after the linkage that referenced them has been replaced.

`--dry-run` stops after the plan. Otherwise, in order:

1. **Preconditions.** `validate` on the tree = 0 errors (the deployer runs it;
   `--skip-validate` does not exist). The environment's gate (below).
2. **Snapshot.** Every object the plan touches is exported as LDIF — all
   attributes, binary ones base64, including objects about to be added
   (recorded as *absent*) — plus each affected driver's state and start option,
   into `deploy-snapshots/<env>/<timestamp>.ldif` (+ `.json` manifest: env, tree
   commit, plan, who). Gitignored by default (client vault content).
3. **Writes**, in plan order. The first failure stops the deploy; what was
   written stays written (the vault is not transactional) and the result says
   exactly which steps completed — the snapshot is the way back.
4. **Restarts.** For each affected driver that was running: `RestartDriver`,
   then poll `GetDriverState` until running (or stopped with an error after a
   bounded wait). A driver that was stopped stays stopped.
5. **Verify.** Re-read the vault; `ModelDiff` against the tree for the affected
   drivers must be empty; every restarted driver is running. Anything else is a
   failed deploy, reported with the diff.
6. **Audit.** Append one line to `deploy-log/<env>.jsonl`: timestamp, user,
   environment, tree commit (`git rev-parse HEAD` if the tree is in git), the
   plan, per-step outcome, snapshot path, verification result. Committed to the
   client repo — it's the deployment history.

`--driver D` (repeatable) limits the deploy to those drivers (plus the Library
objects they reference); `--no-restart` writes without restarting (the result
says the drivers are running stale configuration until restarted).

### Two ways to walk the plan

- **Automated** (`--yes`): snapshot everything, confirm the whole plan once,
  then write → restart → verify as above. The normal path for dev/stg and for
  small, well-simulated production changes.
- **Step by step** (`--step`): the plan is grouped into *changes* (one artifact,
  one driver's linkage, one driver's config blob …). For each change the
  deployer shows its diff, asks, writes it, immediately re-reads and verifies
  that object, and only then moves to the next; a `no` skips the change,
  `quit` stops with everything so far written and verified, and the audit line
  records exactly which changes went in. Restarts come at the end (or after each
  change with `--restart-each`, for changes you want to watch land one at a
  time). The snapshot is still taken up front, so rollback covers a partial
  step-by-step deploy too.

Both modes go through the same plan, snapshot, verify and audit; `--step` is
the same deploy with a confirmation and a verification per change instead of
per plan.

### Production changes start from a known state

A production vault may only be changed from a state the repo knows. Before
writing to a `prd` tier the deployer re-reads the vault and compares it with
what the audit log says was last deployed there (the tree at that commit):

- **no drift** — proceed;
- **drift** (someone changed production outside the tool, or nothing was ever
  deployed by it) — refuse, print the drift as a diff, and offer
  `--capture-drift`: import the live state into the tree as its own commit
  ("prd as found 2026-09-08"), so the repo now holds production's real state
  and the intended change is applied on top of it, visibly, in the next
  deploy. There is no `--ignore-drift`.

The pre-deploy snapshot is always taken, so the state immediately before any
production change is also on disk (and, with `--capture-drift`, in git).

## Rollback

`vault.rollback --env <name> --snapshot <file> [--yes]`: for each object in the
snapshot, restore it — an object that existed gets its captured attributes
replaced (and attributes the deploy added removed); an object recorded as
*absent* is deleted; an object the deploy deleted is re-added. Then each driver
that was running at snapshot time is restarted. Rollback runs through the same
plan/verify/audit machinery: it prints its plan, snapshots the current state
first (so a rollback is itself reversible), and verifies that the vault matches
the snapshot afterwards.

## Secrets — what the tree can never carry

Exports, projects and the tree hold no secrets, but a driver doesn't run
without them. The deployer treats them as a first-class part of a deploy, never
as an afterthought a human fixes in iManager.

**Inventory** — what a driver may need, and how the deployer knows:

| secret | how it's detected | how it's set |
|---|---|---|
| shim authentication password (`DirXML-ShimAuthPassword`) | the driver has a `shim-auth-id` / auth server | LDAP modify of the write-only attribute — *or* the driver-set password channel; **spike 4a decides which the engine honours** (proof = the driver starts and authenticates) |
| Remote Loader password | shim-config-info / engine-control values name a remote loader (`remote-loader` parameters) | same spike |
| named passwords | `validate` already lists every `token-named-password` a policy reads (`named-password` findings); password-ref GCVs (`type="password-ref"`) name them too | `SetNamedPassword` extended op on the driver (or driver set for shared names); `ListNamedPasswords` verifies the name exists; `RemoveNamedPassword` |
| application-side secrets inside shim parameters (a `password` typed parameter) | shim-config-info definitions with `type="password-ref"` | as named passwords |

**Where they come from** — a per-environment secrets file, gitignored, or the
environment:

```properties
# secrets-stg.properties (gitignored; path in stg.secrets=… or IDM_SECRETS)
AD Driver.shim-auth-password=…
AD Driver.remote-loader-password=…
AD Driver.named.exchange-service=…
driverset.named.smtp-relay=…
# or, per key: <key>Env=VAR_NAME   /   <key>Command=op read "op://vault/item/field"
```

A value can be literal, an environment variable, or the output of a command
(so a password manager or a CI secret store is the real source and nothing
sensitive sits in a file). The deployer never prints a secret and never writes
one into a snapshot, an audit line, or a tree.

**In the plan.** Secrets are not diffable — the vault won't return them — so
they are planned by *need*, not by difference:

- **Initial deploy of a driver** (the driver doesn't exist in the vault):
  every secret the inventory says it needs is a plan step; a missing one is
  reported (`secret 'AD Driver.shim-auth-password' not provided`) and the
  deploy refuses — unless `--allow-missing-secrets`, which creates the driver
  stopped and lists what must be set before it can start.
- **Updating a driver**: secrets are *not* touched unless asked:
  `--secrets all` re-sets every provided secret for the affected drivers
  (the way to force a rotated password in), `--secrets missing` sets only named
  passwords that `ListNamedPasswords` shows absent, and
  `idm vault.secrets --env stg --driver "AD Driver" --set named.exchange-service`
  sets one outside a deploy (through the same gate and audit).
- The plan shows secret steps by **name only** (`set secret AD Driver.named.exchange-service`).

**Verification.** Named passwords: the name is in `ListNamedPasswords` after the
write. Shim / Remote Loader passwords can't be read back; the proof is the
driver starting and authenticating, which the deploy's post-restart state check
covers for a running driver (and Phase 5's `driver.start` for a newly created
one). The audit line records which secrets were set — names, never values.

**Spike 4a** (build step 2, on the test vault's `Querytest` driver and a scratch
named password): which write path the engine honours for the shim password and
the Remote Loader password, whether `DirXML-ShimAuthPassword` is LDAP-writable
by the deploy identity, and the exact `SetNamedPassword` semantics on a driver
vs the driver set. Findings → `spikes/secrets.md`.

## Environments and gating

Vault targets live in a local, gitignored `environments.properties` (or the
path in `IDM_ENVIRONMENTS`):

```properties
stg.url=ldaps://idm-stg:636
stg.bindDn=cn=idm-deploy,ou=sa,o=system
stg.password=…                    # or stg.passwordEnv=IDM_STG_PASSWORD
stg.driverSet=cn=driverset1,o=system
stg.tier=stg
prd.url=…
prd.tier=prd
prd.requires=stg
```

The gate, by tier:

| tier | to deploy |
|---|---|
| `dev` | `--yes` or `--step` |
| `stg` | `--yes` or `--step`; validate clean |
| `prd` | `--confirm prd` (the environment's name typed out) with `--yes` or `--step`; validate clean; **current state known** (no drift vs the last deploy recorded in `deploy-log/prd.jsonl`, or `--capture-drift` first); `simulate` green if the tree has a corpus (`--cases`); and, when the environment sets `prd.requires=stg`, a green STG deploy of the same tree commit in `deploy-log/stg.jsonl` |

A deploy identity per environment, with rights only on its driver set, is the
client's job; the tool never writes outside `driverSet`.

## Build order

1. **`ModelDiff`** (pure): artifacts, driver settings/blobs, linkage, driver-set;
   text rendering and JSON; `idm tree.diff`. Tests on synthetic trees; on RFI
   tree vs itself (empty) and vs a copy with one of each change.
2. **`Vault`** (the LDAP side, from the spike code): connect (trust-all
   LDAPS, binary `XmlData`/`DirXML-Data`), read an entry with all attributes,
   add/modify/delete, `DirXML-Policies` replace; extended ops
   `state/start/stop/restart` with state polling, `Set/List/RemoveNamedPassword`.
   Integration tests against the test vault on scratch objects under
   `cn=Library` and the side-effect-free `Querytest` driver (as spike 1b),
   cleaned up in `finally`. This step also settles the package-checksum question
   on a scratch packaged object, and **spike 4a** (secrets write paths).
3. **`vault.diff`** = readLive + `ModelDiff`.
4. **Snapshot / rollback**: LDIF writer for entries (all attributes), manifest,
   restore.
5. **Plan + deploy + verify + audit**, `--yes` / `--step`, `--dry-run`,
   `--driver`, `--no-restart`, new-driver creation (stopped, manual), secrets
   (inventory, `--secrets`, `vault.secrets`).
6. **Environments + gating** (`environments.properties`, secrets sources, tiers,
   `--confirm`, known-state check + `--capture-drift`, `requires`).
7. End to end on the test vault: import-live → tree → an edit → `vault.diff`
   shows it → `vault.deploy --yes` → trace shows `Found DirXMLScript policy` for
   the new object after restart (spike 1b's evidence) → `vault.verify` empty →
   `vault.rollback` → `vault.verify` against the pre-edit tree empty.

Delegation: 1 and 4 are well-specified subagent work; 2 (live vault, scratch
discipline), 5 and 7 are not.

## Decisions

1. **Deploy never deletes a driver** without `--delete-driver`; a new driver is
   created stopped with start option manual. *(to confirm)*
2. **Packaged objects: content only**; `DirXML-pkg*` left to the server; the
   live checksum behaviour measured and recorded, not assumed. *(to confirm)*
3. **Snapshots and the audit log live in the client repo** (`deploy-snapshots/`
   gitignored, `deploy-log/` committed). *(to confirm)*
4. ✅ **Confirmed 2026-09-08 (Jerry):** a deploy is either **step by step** —
   diff, confirm, write and verify each change — or **automated** after a
   backup and one confirmation; and **a production change always starts from a
   known state**: the vault must match what the repo last deployed, or its
   current state is captured into the repo first (`--capture-drift`). A green
   STG deploy of the same commit is an environment option (`prd.requires`),
   not a universal rule.
5. ✅ **Confirmed 2026-09-08 (Jerry):** secrets the tree can't carry (shim
   password, Remote Loader password, named passwords) are handled cleanly:
   sourced from a gitignored per-environment secrets file / environment
   variables / a command, **required and set on a driver's initial deploy**,
   never touched on updates unless **forced** (`--secrets all|missing`,
   `vault.secrets --set`), verified by name where the vault allows, never
   printed, snapshotted or logged.
