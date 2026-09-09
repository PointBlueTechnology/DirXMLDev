# Agent guide — editing a driver set with `bin/idm`

How an agent (or a person at a shell) changes an IDM driver set without
Designer: the tree is the source of truth, the CLI keeps it consistent, the
validator is the engine's own verdict, and git is the history.

`bin/idm` with no arguments prints every command with its arguments.

## The tree

An IDM-as-code tree ([model.md](model.md)) — one file per object:

```
tree/
  driverset.xml                     manifest: drivers, driver-set linkage, meta
  config-values.xml                 driver-set GCVs
  library/library.xml               Library manifest
  library/<name>.policy.xml         shared policies, mapping tables, GCV objects, ECMAScript
  drivers/<driver>/driver.xml       manifest: config files, artifacts, policy-set linkage
  drivers/<driver>/*.policy.xml     driver-scope policies (schema map, input/output transforms, …)
  drivers/<driver>/subscriber/…     channel policies
  drivers/<driver>/publisher/…
  .package-baseline/…               pre-edit content of customized packaged artifacts
```

Artifacts are addressed by **path**: `library/<name>`, `drivers/<driver>/<name>`,
`drivers/<driver>/subscriber/<name>`, `drivers/<driver>/publisher/<name>`.
Names are the object names (spaces and all); quote them.

Get a tree from any source:

```bash
bin/idm import <driver-or-driverset-export.xml> tree/
bin/idm import-project <designer-project-dir> tree/
bin/idm import-ldif <driverset-subtree.ldif> tree/
IDM_JAVA_OPTS="-Dldap.url=ldaps://host:636 -Dldap.bindDn=… -Dldap.password=…" \
  bin/idm import-live "cn=driverset1,o=system" tree/
```

Put it in git before editing. The import is byte-idempotent, so re-importing
the same source is a no-op diff — which is how you see what changed in a vault.
The import writes a `.gitattributes` (`* -text`) so git never normalizes line
endings: vault content is bytes, and a CRLF inside an ECMAScript resource must
survive a checkout or `vault.diff` will report it. Keep that file.

## Orient first

```bash
bin/idm validate tree/                       # the baseline: is this tree sound?
bin/idm query tree/ artifacts "AD Driver"    # what the driver has
bin/idm query tree/ chain "AD Driver" sub    # the subscriber chain in execution order
bin/idm query tree/ gcvs "AD Driver"         # every GCV in scope, value, and where it's defined
bin/idm query tree/ tables "AD Driver"       # mapping tables in reach, with columns
bin/idm show tree/ "drivers/AD Driver/subscriber/sub-ctp-Transform"
bin/idm refs tree/ "library/lib-Shared"      # who links / includes / maps it
```

`validate` on a tree from a running vault should report **0 errors**. If it
doesn't, the errors are real (a dangling reference, a GCV nothing defines) — or
a validator bug, which is the same as a finding: report it, don't work around it.

## Two kinds of change

**Content** — the rules inside a policy, a stylesheet, a script, a table's rows:
edit the file. DirXML Script is the language; the engine's compiler judges it:

```bash
$EDITOR "tree/drivers/AD Driver/subscriber/sub-ctp-Transform.policy.xml"
bin/idm validate tree/
```

**Structure** — anything that touches more than one file or the linkage: use an
operation. Each loads the tree, applies the change, validates, and writes only
if it introduced **no new error**; otherwise it refuses and writes nothing.

```bash
bin/idm policy.add tree/ --driver "AD Driver" --scope subscriber --name sub-ctp-NormalizeTitle \
    --link subscriber-command --at "after:drivers/AD Driver/subscriber/sub-ctp-Transform"
bin/idm rule.add tree/ --path "drivers/AD Driver/subscriber/sub-ctp-NormalizeTitle" \
    --content-file rule.xml --at last
bin/idm artifact.rename tree/ --path "library/LocCodeMap" --name LocationCodeMap
bin/idm gcv.set tree/ --driver "AD Driver" --name drv.user.container --value "data\users"
bin/idm filter.set-attr tree/ --driver "AD Driver" --class User --attr Title --publisher sync --subscriber sync
bin/idm mapping-table.set-row tree/ --path library/LocationCodeMap --col LocCode=0042 --col Domain-Placement="OU=x,DC=y"
```

Every operation takes `--dry-run` (do everything but write; the result shows
what would change), `--force` (write despite new errors — say why in the commit),
and `--json` (the same result as data: `ok`, `written`, `touched`, `renamed`,
`customized`, `changedFiles`, `deletedFiles`, `newErrors`, and the full
`validate` report).

Read a refusal as information, not an obstacle:

| refusal | it means |
|---|---|
| `'…' is still referenced — link from drivers/X (set …); include from …` | delete would leave a dangling reference; `--unlink` drops policy-set links, but an `<include>` or a Map token is content — fix that policy first |
| `would introduce N validation error(s)` | the engine would refuse to load the result; the listed errors are its diagnostics |
| `GCV 'x' is not defined … add --define <type>` | you're setting a GCV nothing defines; create it deliberately |
| `GCV 'x' is read by … policies` | deleting it would stop the driver from starting |
| `'x' names 2 rules; use #n` | duplicate descriptions — address the rule by position |

## Packaged content

Overriding a policy that came from a package is normal. The first operation
that touches one keeps its pre-edit content in `.package-baseline/` and marks it
`package.customized` in the manifest; the result says `customized (packaged;
baseline kept)`. Later:

```bash
bin/idm package.diff tree/ "drivers/AD Driver/subscriber/NOVLADDCFG-sub-ctp-EntitlementsImpl"
```

shows exactly what was customized. Commit the baseline with the change. (The
vault-side "modified" mark is set by the deployer when it writes the object.)

## Prove the change

```bash
bin/idm validate tree/                                   # engine will load it
bin/idm simulate tree/ --cases cases/ --against tree@HEAD # regression corpus: what changed
git add -A tree/ && git commit -m "AD: normalize Title on subscriber command (#123)"
```

`simulate` runs the client's regression cases (harvested with the simulator's
`harvest`, or authored) against the edited tree and diffs them against the
previous tree — an output that changed is shown, never hidden.

## Deploy it

Vault targets live in a gitignored `environments.properties` (path in
`IDM_ENVIRONMENTS`; see [vault-deploy.md](vault-deploy.md)); secrets the tree
can't carry in a gitignored secrets file per environment.

```bash
bin/idm vault.diff   tree/ --env stg                      # what differs, per object; exit 1 if anything
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --dry-run   # the plan, nothing written
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --yes       # snapshot → write → restart → verify → audit
bin/idm vault.deploy tree/ --env stg --step               # or: confirm and verify each change
bin/idm vault.verify tree/ --env stg                      # re-read: vault == tree
bin/idm vault.rollback tree/ --env stg --snapshot deploy-snapshots/stg/<ts>.ldif --yes
```

What the deployer will not do: write anything the tree doesn't `validate`
clean; delete a driver (it reports one the tree lacks); restart a stopped
driver (it loads the new configuration when started); touch a secret unless
the driver is new or you pass `--secrets all|missing`; deploy to a `prd` tier
without `--confirm <env>`, a committed tree, and a vault that matches the last
recorded deploy (or `--capture-drift`, which records the vault's current state
on an `as-found/<env>/<ts>` branch for you to merge first). Every deploy
appends to `deploy-log/<env>.jsonl` — commit it with the tree.

## Operate it

```bash
bin/idm driverset.status --env stg                          # every driver: state, start option, cache bytes, trace
bin/idm driver.status    --env stg --driver "AD Driver"     # + trace file, named passwords, recent audit
bin/idm engine.stats     --env stg --driver "AD Driver"     # JVM heap/threads + cache and operation counters
bin/idm driver.cache view --env stg --driver "AD Driver" --out cases/ad-cache   # queued events → a simulator case
bin/idm driver.trace tail --env stg --driver "AD Driver" --since 10 --grep "Applying rule"
bin/idm driver.start|stop|restart --env stg --driver "AD Driver" [--yes]
bin/idm driver.trace set --env stg --driver "AD Driver" --level 3 --file /var/opt/novell/eDirectory/log/ad.trace
bin/idm driver.submit --env stg --driver "AD Driver" --xds event.xds --yes --tree tree/   # the canary
bin/idm driver.cache clear --env stg --driver "AD Driver" --yes --confirm stg
```

Gating follows what an operation can break ([operate.md](operate.md)): reads
are free; start/restart/trace/secrets need `--yes` on stg and `--yes --confirm`
on prd; stop/resync/migrate/submit need `--yes` everywhere; `cache clear`
shows the count and first/last event, saves the events to
`deploy-snapshots/`, and needs `--yes` (plus `--confirm` on stg/prd). Every
state change is audited. `driver.submit --tree` is the ground truth for a
policy change: the live engine's Subscriber channel hands the shim a document,
the trace shows which, and the simulator's prediction from the tree must match
it.

## Conventions

- One operation or one content edit per commit, with the result's file list in
  the message; `--json` output is fine to paste.
- Never hand-edit `driver.xml` / `library.xml` / `driverset.xml` manifests —
  that's what the operations are for.
- Never delete `.package-baseline/` entries; they're the record of every
  override.
- Don't run two operations on the same tree concurrently.
- Client trees hold client policy: they live in the client's repo, never here.
