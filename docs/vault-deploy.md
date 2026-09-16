# Phase 4 design — vault deploy with safeguards

Status: **design confirmed, building** (2026-09-08). Follows [plan.md](plan.md) Phase 4; builds on
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
reports it and `vault.deploy --delete-driver D` is the explicit path — see
"`--delete-driver`" below.

## Packages

A packaged object in the vault carries `DirXML-pkgGUID`,
`DirXML-pkgAssociationId`, `DirXML-pkgInitialState`, `DirXML-pkgChecksum`
(spike 1). The deployer **modifies content and leaves the identity attributes
alone**; it never fabricates them on new objects (the tree's project-derived
package meta is not a vault attribute). **Measured
([spikes/vault-objects-and-secrets.md](spikes/vault-objects-and-secrets.md)):
the server does not update `DirXML-pkgChecksum` when content changes**, so a
deployed override would look unmodified to Designer and to package upgrade.
Therefore, the moment a packaged object is customized (`package.customized`),
the tree recomputes its checksum from the *new* content and carries that
forward into every deploy, so the vault's checksum pair differs from the
baseline the same way Designer's own copy would — Designer's "modified" test
is plain inequality (spike 2). **Artifacts** (policies, resources, GCV
objects — anything with a Designer catalog recipe) get Designer's own
installed-content checksum (`packages/InstalledChecksum`, the same recipe
`package.status` and `PackageInstall` use: content + name + the policy sets
it's linked into); **forms and PRDs**, which have no such recipe, keep a
content-derived CRC32 (`VaultMapping#customizedChecksum`). Either way the
recompute happens once, in the tree (`edit/Packages#refreshChecksums`), so the
tree, the vault and a round-tripped Designer project all carry the same
number. The tree keeps the truth (`.package-baseline/`, `package.customized`).

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

**Only the changed attribute is written.** An artifact and a form each hold
their content in one attribute, so a `modify` step is already minimal. A PRD
splits across three XML attributes (`XmlData`/definition, `srvprvRequestXML`,
`srvprvProcessXML`) plus a dozen plain properties (status, flow strategy,
grant/revoke, category, localized names…) — `ModelDiff` records exactly which
of those changed, and the plan writes only that subset (plus the package
stamps, only when they themselves changed) instead of re-sending every
attribute on every edit. An added PRD still writes everything, since there is
nothing yet to diff against.

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

### `--delete-driver`

`--delete-driver D` (repeatable) names a driver the diff reports as
**removed** (in the vault, absent from the tree) and asks deploy to delete it,
instead of leaving it alone:

- **Preconditions**, checked at plan time and refused (nothing written) if
  either fails: the driver must be **in the vault and absent from the tree**
  (in the tree → "remove it from the tree first"; in neither → "not found in
  the vault"), and it must be **stopped**, read the same way `driver.status`
  reads it — a running (or starting/stopping) driver is refused with "stop it
  first (`driver.stop`)"; deploy never stops it implicitly.
- **Plan.** A single `DELETE_SUBTREE` step for the driver DN, listed after
  every other step (before restarts): `delete driver subtree cn=D,… (N
  objects)`, where `N` and the exact DN list come from a subtree search
  (scope subtree, `(objectClass=*)`) done once, at plan time. `--dry-run`
  shows this step like any other.
- **Snapshot.** The driver DN and every DN under it are added to the plan's
  touched objects, so the snapshot LDIF captures the whole subtree — every
  attribute of every object — before anything is deleted. `vault.rollback`
  restores it: an object present in the snapshot but absent from the vault is
  re-added, parents before children (the driver object, then its Subscriber /
  Publisher containers, then what's inside them).
- **Execution.** The subtree is deleted deepest first — sorted by number of
  RDNs, descending, so the driver object (fewest) goes last — from the list
  fixed at plan time. A failure partway through is reported like any failed
  step: which objects were deleted, which remain (the snapshot is the way
  back).
- **Verify.** The driver DN must no longer exist (checked in addition to the
  usual re-read-and-diff, since a deleted driver is never in `affectedDrivers()`).
- **Audit.** The `deploy-log` line names the deleted driver(s) and the object
  count deleted for each.
- **Production.** `--delete-driver` on a `prd` tier needs the same `--confirm
  <name>` as any other production change, and the `DELETE_SUBTREE` step shows
  in the plan text printed before that confirmation, same as every other step.

### Deploy never empties a kind

**The incident (2026-09-16, lab vault ig4).** A tree had been imported by
`import-live` before the entitlement model existed (`Driver.entitlements` was
added the same week). Deploying it afterwards diffed the vault's 19
`DirXML-Entitlement` objects against a tree with none and produced 19
`ENTITLEMENT_REMOVED` changes → 19 delete steps, executed with `--yes`.
Recovery came from the deploy snapshot (`vault.rollback`). The same trap
exists for JSON forms and PRDs (a tree imported before Track P has none under
a User Application driver that has dozens) and for any future object kind: a
tree imported before a model extension existed always diffs an entire kind as
removed, never as "nothing to compare against yet".

**The guard.** For each driver and each of entitlements, JSON forms and PRDs,
if the *tree* has **zero** objects of that kind for the driver while the
*vault* has one or more, the plan holds back every delete step for that
driver + kind and reports one note instead:

```
driver 'AD': the tree has no entitlements but the vault has 19 — an older
tree? re-import (import-live) to adopt them, or pass --delete-all
entitlements to delete them
```

An individual removal — the tree still has at least one object of the kind —
is unaffected; it deletes exactly as before. `vault.diff` prints the same
note under the driver, so the trap is visible before anyone reaches the plan.

**`--delete-all entitlements|forms|prds`** (repeatable) is the explicit
override: it re-enables the deletes for that kind, for every driver the guard
would otherwise hold back. It goes through the same gate as any other
deploy (`--yes`/`--step`, `--confirm <env>` in production), shows in the plan
text, and is named in the `deploy-log` audit line (`--delete-all:
entitlements`) — so a real, intentional wipe is deployed the same way as
anything else, just never by accident.

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
| shim authentication password (`DirXML-ShimAuthPassword`) | the driver has a `shim-auth-id` / auth server | LDAP modify — **measured (spike 4a): writable by the deploy identity, and readable back, so it can be verified by re-read** (the value is never shown) |
| Remote Loader password | shim-config-info / engine-control values name a remote loader (`remote-loader` parameters) | to locate on a Remote Loader driver (not on the test driver); expected to be a driver attribute or a named password |
| named passwords | `validate` already lists every `token-named-password` a policy reads (`named-password` findings); password-ref GCVs (`type="password-ref"`) name them too | `SetNamedPassword` extended op on the driver (or driver set for shared names) — **measured: works on both**; `ListNamedPasswords` verifies the name exists; `RemoveNamedPassword` |
| application-side secrets inside shim parameters (a `password` typed parameter) | shim-config-info definitions with `type="password-ref"` | as named passwords |

**Where they come from** — a per-environment secrets file, gitignored, or the
environment:

```properties
# secrets-stg.properties (gitignored; path in stg.secrets=… or IDM_SECRETS)
AD Driver.shim-auth-password=…
AD Driver.remote-loader-password=…
AD Driver.named.exchange-service=…
driverset.named.smtp-relay=…
# or, per key: <key>Env=VAR_NAME   /   <key>Command=op read "op://vault/item/field"   /   <key>Keychain=service[/account] (macOS)
```

A value can be literal, an environment variable, the output of a command, or
a macOS Keychain item (so a password manager, the Keychain or a CI secret
store is the real source and nothing sensitive sits in a file). The same four
forms apply to the environments file's `password`, `appsPassword` and
`appsSecret`; both files trigger a one-line warning when readable by other
users (keep them at mode 600). See [getting-started.md](getting-started.md) §4. The deployer never prints a secret and never writes
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
stg.passwordKeychain=idm-stg/cn=idm-deploy   # or stg.passwordCommand=… | stg.passwordEnv=VAR | stg.password=… (literal)
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

1. ✅ **`ModelDiff`** (2026-09-08, Sonnet): 11 change kinds, linkage as
   ordered lists (order numbers normalized), `affectedDrivers()`, text/JSON,
   `idm tree.diff`; 21 tests incl. real RFI data.
2. ✅ **`Vault`** + spike 4 ([spikes/vault-objects-and-secrets.md](spikes/vault-objects-and-secrets.md)):
   JNDI reads/writes with XML attributes as bytes; extended ops for state /
   start / stop / restart / start option / named passwords; the server does
   not update `DirXML-pkgChecksum`; shim password writable and readable.
3. ✅ **`vault.diff`** = `LdifReader.readLive` + `ModelDiff`; on the test
   vault, the tree imported from it diffs empty.
4. ✅ **Snapshot / rollback** (Sonnet): LDIF + JSON manifest, absent markers,
   driver states, restore + differences behind a `Store` seam.
5. ✅ **Plan + deploy + verify + audit**: `Plan` (ordered, grouped by change,
   checksum for customized packaged objects, new drivers stopped + their
   secrets), `Deployer` (`--yes` / `--step` / `--dry-run` / `--driver` /
   `--no-restart` / `--secrets` / `--allow-missing-secrets`), `DeployLog`,
   `SecretInventory`, `VaultMapping` (tested as the inverse of the live reader).
6. ✅ **Environments + gating**: `Environments`, `Secrets` (literal / env /
   command), tiers, `--confirm`, `requires`, known-state check with
   `--capture-drift`.
7. ✅ **End to end on the test vault** (2026-09-08): `import-live` → git →
   `vault.diff` empty → `policy.add` on `Querytest` → `vault.deploy --dry-run`
   (plan: add object, replace linkage, restart) → `--yes` (snapshot with an
   *absent* marker, both writes, restart skipped: driver stopped, verify:
   vault matches the tree, audit line) → `vault.diff` empty →
   `vault.rollback --yes` (snapshots first; deletes the object, restores the
   linkage; verify) → `vault.diff` shows the tree's policy as not deployed, and
   against the pre-edit commit is empty.

8. ✅ **`--delete-driver`** (2026-09-16): the `DELETE_SUBTREE` plan step
   (subtree search at plan time, deepest-first deletion, refused on a driver
   that's in the tree, unknown, or not stopped), the snapshot capturing the
   whole subtree so `vault.rollback` re-adds it parents first, the extra
   driver-gone verify check, and the audit line naming the driver and object
   count — see "`--delete-driver`" above.
9. ✅ **The mass-deletion guard + `--delete-all`** (2026-09-16, the ig4
   incident): `ModelDiff#emptyKinds()` finds every driver + kind (entitlements,
   forms, PRDs) where the tree has zero objects but the vault has some; `Plan`
   holds back those delete steps (one note per driver + kind) unless
   `--delete-all <kind>` is given, in which case they deploy and the audit
   line records the override — see "Deploy never empties a kind" above.

Not done, deliberately deferred: Remote Loader password (no RL driver on the
test vault to learn from); `idm vault.secrets` as a standalone command (use
`vault.deploy --secrets all`); `simulate` as part of the production gate (run
it before deploying).

Delegation: 1 and 4 are well-specified subagent work; 2 (live vault, scratch
discipline), 5 and 7 are not.

## Decisions

1. ✅ **Deploy never deletes a driver** without `--delete-driver`; a new driver is
   created stopped with start option manual. *(confirmed 2026-09-08)*
1a. ✅ **Deploy never empties a kind** — entitlements, JSON forms, PRDs — without
   `--delete-all <kind>`; a driver+kind that goes from N&ge;1 in the vault to
   zero in the tree is held back and noted, individual removals are not.
   *(confirmed 2026-09-16, after the ig4 incident)*
2. **Packaged objects: content only**; `DirXML-pkg*` left to the server; the
   live checksum behaviour measured and recorded, not assumed. *(confirmed 2026-09-08)*
3. **Snapshots and the audit log live in the client repo** (`deploy-snapshots/`
   gitignored, `deploy-log/` committed). *(confirmed 2026-09-08)*
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

## Incident 2026-09-16 — a stale tree emptied a kind (recovered from the snapshot)

`tree-ig4` had been imported before the entitlement model existed. A deploy
run for `--delete-driver PkgTest7` also carried 19 `ENTITLEMENT_REMOVED`
changes (the vault had them, the tree had none) and, run with `--yes` after
only a glance at the dry-run, deleted every entitlement of five drivers.
The deploy snapshot held all 26 entries in full; `vault.rollback` recreated
the 19 entitlements with their `XmlData` and package stamps (the PkgTest7
subtree's re-add failed on a syntax violation and was left deleted, which
was the intent). A fresh `import-live` then matched the vault. Two lessons
became rules: read the whole dry-run before `--yes`, and the plan now
refuses to empty a kind for a driver unless `--delete-all <kind>` says so
(safeguard added the same day).
