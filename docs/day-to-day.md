# Day to day

Workflows for a person or an agent who already has a client tree
([getting-started.md](getting-started.md)). Commands are run from the client
directory. Driver names (`AD Driver`) and environment names (`stg`, `prd`) are
examples; use the names in your tree and in `environments.properties`.

Every edit operation is a transaction: load the tree, apply the change, run
`validate`, and write only if no new error appeared. `--dry-run` prints what
it would write. `--json` prints a machine-readable result. `bin/idm` with no
arguments lists every flag.

Two kinds of change:

- **Content** — a rule, a stylesheet, a script, a table cell. Edit the file
  (or pass `--content-file`), then `validate`.
- **Structure** — add, rename, delete, link, reorder, set a GCV, change the
  filter. Use an operation. Do not hand-edit `driverset.xml`, `library.xml`,
  or `driver.xml`.

A packaged object is kept as-is in `.package-baseline/` and marked customized
on the first edit, so a later package upgrade can see the override.

## 1. Change a policy and prove it

Orient, then add a subscriber command policy. The content file is a `<policy>`
document (DirXML Script) or XSLT — the same XML you would paste into
Designer's XML view. A fictional file is in
[examples/normalize-title.policy.xml](examples/normalize-title.policy.xml).

```bash
bin/idm query tree/ chain "AD Driver" sub
bin/idm policy.add tree/ \
  --driver "AD Driver" \
  --scope subscriber \
  --name "ACME-sub-ctp-NormalizeTitle" \
  --content-file normalize-title.policy.xml \
  --link subscriber-command \
  --at last
bin/idm validate tree/
```

Further edits use the artifact path (no `.policy.xml` suffix):

```bash
bin/idm rule.add tree/ \
  --path "drivers/AD Driver/subscriber/ACME-sub-ctp-NormalizeTitle" \
  --content-file extra-rule.xml \
  --at first
bin/idm rule.disable tree/ \
  --path "drivers/AD Driver/subscriber/NOVLADDCFG-sub-ctp-TransformTitle" \
  --rule "Legacy title mapping"
bin/idm gcv.set tree/ --driver "AD Driver" --name "drv.user.container" --value "ou=users,o=acme"
bin/idm filter.set-attr tree/ --driver "AD Driver" --class User --attr Title \
  --publisher sync --subscriber sync
bin/idm validate tree/
```

`policy.link` and `policy.reorder` take the same artifact paths. `--order` is
repeated once per member, in the order you want:

```bash
bin/idm policy.reorder tree/ --driver "AD Driver" --set subscriber-command \
  --order "drivers/AD Driver/subscriber/ACME-sub-ctp-NormalizeTitle" \
  --order "drivers/AD Driver/subscriber/NOVLADDCFG-sub-ctp-TransformTitle"
```

Policy-set keys: `schema-mapping`, `input`, `output`, `ecmascript`,
`subscriber-event`, `publisher-event`, `subscriber-matching`,
`publisher-matching`, `subscriber-create`, `publisher-create`,
`subscriber-command`, `publisher-command`, `subscriber-placement`,
`publisher-placement`, `gcv`, `startup`, `shutdown`.

### Simulate

`simulate` runs the cases under `--cases` through the real policies and, with
`--against`, diffs each case against another as-code tree (a worktree of the
commit before the edit). It does not accept a git revision as `--against`.

```bash
git worktree add /tmp/tree-before HEAD
bin/idm simulate tree/ --cases cases/ --against /tmp/tree-before/tree
git worktree remove /tmp/tree-before
```

Read every case the report marks as changed. If `cases/` is empty, the change
is unproven: say so. You can harvest later with the simulator's `bin/sim harvest`,
or turn a stopped driver's queue into cases:

```bash
bin/idm driver.cache view --env stg --driver "AD Driver" --out cases/ad-cache
```

Commit the tree (and any new cases) before you deploy. One operation, one commit.

## 2. Deploy to staging

```bash
bin/idm vault.diff tree/ --env stg
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --dry-run
```

Read the whole plan. It lists each LDAP write, which drivers restart, and
secret **names** only. Stop when you see a delete you did not intend, a
`MISSING SECRET` line, or a driver you did not mean to touch (narrow it with
`--driver`).

A pile of deletes of one kind usually means the tree was imported before that
kind existed. Re-import (`import-live`) rather than deleting. The plan holds
those deletes back unless you pass `--delete-all entitlements` (or `forms`,
`prds`, `roles`, `entities`, or another AppConfig kind in the plural — `bin/idm`
lists them). Deleting a driver that is in the vault and absent from the tree
needs `--delete-driver <name>`, and the driver must be stopped. The subtree is
snapshotted first.

When the plan matches the change:

```bash
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --yes
bin/idm vault.verify tree/ --env stg
```

What `--yes` does: validate; snapshot every object about to change into
`deploy-snapshots/stg/<timestamp>.ldif`; write; restart affected **running**
drivers (a stopped driver stays stopped and loads the change when it starts);
re-read the vault; append one line to `deploy-log/stg.jsonl`. Commit that log
with the tree.

`--step` asks you to confirm and verify each change instead of writing the
whole plan. It cannot be combined with `--json`.

`--dry-run` writes nothing. It still requires a tree that validates.

If the result is wrong:

```bash
bin/idm vault.rollback --env stg --snapshot deploy-snapshots/stg/<timestamp>.ldif --yes
bin/idm vault.verify tree/ --env stg
```

`vault.rollback` does not take the tree as an argument. The snapshot path is
the one the deploy printed.

For a policy change, the canary submits one event to the **running** driver
and, with `--tree`, compares what the live engine hands the shim with the
simulator's prediction:

```bash
bin/idm driver.submit --env stg --driver "AD Driver" --xds event.xds --yes --tree tree/
bin/idm driver.trace tail --env stg --driver "AD Driver" --since 5 --grep "Applying rule"
```

`driver.trace tail` needs `sshHost` on the environment. `--since` is a number
of minutes.

## 3. Deploy to production

Same commands. The environment's `tier` must be `prd`. `--dry-run` still does
not write and does not require `--confirm`. The write does:

```bash
bin/idm vault.diff tree/ --env prd
bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --dry-run
bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --yes --confirm prd
```

`--confirm` must be the environment's name, typed by a person. The deployer
also refuses the write when:

- the tree has no git commit, or has uncommitted changes;
- `<env>.requires` names another environment (often `stg`) and that
  environment has no green deploy of this same commit in `deploy-log/`;
- the vault does not match the last successful deploy recorded for this
  environment.

When the vault has drifted, look at the refusal, then either fix the drift by
hand or record it:

```bash
bin/idm vault.deploy tree/ --env prd --capture-drift
```

That does not deploy your change. It writes the vault's current state as a
commit on a branch named `as-found/<env>/<timestamp>`, based on the last
deployed commit, and records that commit as the known state. The command
prints the `git rebase` of that branch. Review it, put your change on top,
then deploy again.

`simulate` is not part of this gate. Run it yourself (section 1) before you
ask for `--confirm`.

## 4. Packages, forms, and workflows

These are ordinary `bin/idm` commands. They deploy with the same
`vault.diff` / `vault.deploy` path. Forms, PRDs, and entitlements do not
restart the driver.

### A package on a driver

```bash
bin/idm package.fetch --catalog catalog/ --short NOVLADBASE --short NOVLADDCFG
bin/idm package.list --catalog catalog/ --base
bin/idm driver.add tree/ --name "AD Driver" \
  --catalog catalog/ --package NOVLADBASE,NOVLADDCFG --answers ad.properties
bin/idm package.install tree/ --catalog catalog/ --package NOVLADENTEX \
  --driver "AD Driver" --answers ad.properties
bin/idm package.status tree/ --catalog catalog/
```

`--short` is required on `package.fetch`, once per package (a comma is not a
separator). Omitting a version fetches the newest; `--all-versions` fetches
every version the site lists for that short name. A refusal that
names "mandatory prompts" lists the prompt names to put in the answers file
(`name=value`). Password prompts belong in the environment's secrets file, not
in the answers file and not in chat.

`package.upgrade`, `package.uninstall`, `package.adopt`, and `package.build`
are in `bin/idm`. Details: [packages.md](packages.md).

### A form field

```bash
bin/idm form.list tree/ --driver "User Application Driver"
bin/idm form.show tree/ "Help-desk Request Form"
bin/idm form.field.add tree/ \
  --form "Help-desk Request Form" \
  --key priority \
  --type select \
  --label Priority \
  --required \
  --props '{"data":{"values":[{"label":"High","value":"high"},{"label":"Low","value":"low"}]}}'
bin/idm prd.map tree/ --prd HelpdeskTicket --field priority
bin/idm form.preview tree/ "Help-desk Request Form" --out preview.html
bin/idm validate tree/
```

`--props` is the extra JSON. `--json` on an edit operation means "print JSON
output", so it is the wrong flag for field properties. `form.preview` takes
the form name as a positional argument and writes a self-contained HTML page.
`form.edit tree/ "Help-desk Request Form" --check` reports whether the vendor
form builder (a Designer plugin) can launch; it does not modify the form.

### A small workflow

```bash
bin/idm prd.add tree/ \
  --name "Widget Access" \
  --from-template NoApproval \
  --request-form "Widget Request Form" \
  --category accounts \
  --display-name "en~Widget access" \
  --map-all
bin/idm flow.activity.add tree/ \
  --prd "Widget Access" \
  --kind approval \
  --id approval_1 \
  --after Activity \
  --addressee "IDVault.get(recipient,'user','manager')"
bin/idm prd.flow tree/ "Widget Access"
bin/idm validate tree/
```

An approval binds an approval form (the driver's stock `Approval Form` when
you do not pass `--form`) and a denied path that records status. Leave both
in place. Activity kinds and flags: [workflows.md](workflows.md).

### Prove it in the Identity Applications

`bin/apps` needs `<env>.formsUrl`, `<env>.appsUser`, and a password
(`appsPassword`, `appsPasswordEnv`, `appsPasswordCommand`, or
`appsPasswordKeychain`). It prints token length and expiry, never the token.

```bash
bin/apps --env stg token
bin/apps --env stg permission "Widget Access"
bin/apps --env stg request "Widget Access" --data reason="proof"
bin/apps --env stg tasks
bin/apps --env stg approve <taskId> --comment "ok"
bin/apps --env stg history
```

A PRD deployed less than about ten minutes ago is not in the permission index
yet. `request` says it was not started. `bin/apps --env stg index "Widget Access"`
asks the index to pick it up (`--op` defaults to `ADD_OR_MODIFY`). Bodies and
status codes: [idapps-rest.md](idapps-rest.md).

## 5. Operate

Reads are free on every tier.

```bash
bin/idm driverset.status --env stg
bin/idm driver.status --env stg --driver "AD Driver"
bin/idm engine.version --env stg
bin/idm engine.stats --env stg --driver "AD Driver"
bin/idm driver.cache view --env stg --driver "AD Driver" --count 20
bin/idm driver.trace show --env stg --driver "AD Driver"
bin/idm driver.trace tail --env stg --driver "AD Driver" --lines 80 --since 10
bin/idm driver.secrets list --env stg --driver "AD Driver"
```

`driver.secrets list` prints names only.

State changes follow the tier. The command refuses when the flag is missing,
and it appends a line to `deploy-log/<env>.jsonl`.

| Operation | dev | stg | prd |
|---|---|---|---|
| status, cache view, trace show, trace tail, secrets list, engine.version, engine.stats | free | free | free |
| start, restart, trace set, trace reset, secrets set, secrets remove | free | `--yes` | `--yes --confirm <env>` |
| stop, resync, migrate, submit | `--yes` | `--yes` | `--yes --confirm <env>` |
| cache clear | `--yes` | `--yes --confirm <env>` | `--yes --confirm <env>` |

```bash
bin/idm driver.restart --env stg --driver "AD Driver" --yes
bin/idm driver.stop --env stg --driver "AD Driver" --yes
bin/idm driver.trace set --env stg --driver "AD Driver" --level 3 \
  --file /var/opt/novell/eDirectory/log/ad.trace --yes
bin/idm driver.resync --env stg --driver "AD Driver" --since 2026-09-01T00:00:00Z --yes
bin/idm driver.cache clear --env stg --driver "AD Driver" --yes --confirm stg
```

`driver.stop` is safe for the vault and bad for the queue: events keep
arriving and the cache grows until you start the driver again. Say so before
you stop a real connector.

`driver.cache clear` prints the event count and the first and last event
before it deletes anything. It writes the events to
`deploy-snapshots/<env>/<timestamp>-cache-<driver>.xds` first. On `stg` and
`prd` it also requires `--confirm <env>`. Read the count out loud; do not
clear a cache you have not looked at.

`driver.migrate` needs `--xds` pointing at an XDS query file and `--yes`.
`driver.secrets set` reads the value from the secrets file
(`<driver>.named.<name>`) or from `--stdin`, never from an argument.

The design note, including which extended operation backs each command, is
[operate.md](operate.md).

## 6. Hand the result to Designer

```bash
bin/idm export tree/ driverset-export.xml
bin/idm export-project tree/ ~/designer_workspace/Client --dry-run
bin/idm export-project tree/ ~/designer_workspace/Client
```

`export` writes a driver-set configuration Designer can import. `export-project`
updates an existing project in place and refuses a packaged driver it cannot
represent. A project that does not exist yet is `export-project … --new`
([designer-new-project.md](designer-new-project.md)); the directory's basename
becomes the project name, and no vault password is written.

After a deploy, Designer's *Import from the Identity Vault* also picks up
what the tool wrote.
