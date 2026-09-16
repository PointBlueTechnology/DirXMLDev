# Walkthrough — from a live driver set to a proven, deployed change

This is the process, end to end, for a person or an agent using DirXMLDev on a
real environment. It assumes the setup in [getting-started.md](getting-started.md)
is done: the tool builds, `environments.properties` names `stg` and `prd`, and
the deploy identity can reach the vault. Commands are shown as run from the
client directory (`tree/`, `cases/`, `environments.properties` beside each
other). Every command prints `--help`-style usage when called wrong, and
`--json` gives machine-readable output.

The loop is always the same, whatever the change:

```
import → validate → change (transactions) → validate/simulate → commit
       → vault.diff → vault.deploy --dry-run → read the whole plan → vault.deploy --yes
       → verify → prove at runtime → operate → hand back to Designer if the team keeps one
```

## Step 1 — Take the driver set as code

Pick the source you trust most. The live vault is the ground truth; a Designer
project or an export is fine when the vault is not reachable yet.

```bash
# the live vault (recommended)
IDM_JAVA_OPTS="-Dldap.url=ldaps://idm-stg.example.com:636 -Dldap.bindDn=cn=idm-deploy,ou=sa,o=system -Dldap.password=$IDM_STG_PASSWORD" \
  bin/idm import-live cn=driverset1,o=system tree/

# alternatives
bin/idm import-project ~/designer_workspace/Client tree/     # a Designer project
bin/idm import  DriverSet-export.xml tree/                    # a driver or driver-set export
bin/idm import-ldif driverset.ldif tree/                      # an LDIF dump of the subtree
```

What you get: one readable file per object under `tree/` — `driverset.xml`,
`library/`, and per driver `driver.xml` (the manifest), `driver-filter.xml`,
`shim-config-info.xml`, `engine-control-values.xml`, every policy, mapping
table, ECMAScript resource and GCV set, `subscriber/` and `publisher/`
channel objects, `provisioning/` (JSON forms and PRDs of a User Application
driver) and `entitlements/`. Packaged objects carry their package stamps and,
when customised, a baseline under `.package-baseline/`.

```bash
bin/idm validate tree/            # expect: OK: 0 error(s), n warning(s), n info
bin/idm vault.diff tree/ --env stg # expect: no differences
git add tree/ && git commit -m "stg driver set as imported $(date +%F)"
```

A production tree normally validates with zero errors. Warnings are worth a
look (unlinked policies, template placeholders); infos are informational.

## Step 2 — Orient

Read before changing. These are free and never write.

```bash
bin/idm query tree/ artifacts "AD Driver"        # every policy/resource of a driver, with linkage
bin/idm query tree/ chain "AD Driver" sub         # the subscriber channel's policy chain in engine order
bin/idm query tree/ gcvs "AD Driver"              # GCVs with their effective values
bin/idm query tree/ tables "AD Driver"            # mapping tables
bin/idm show tree/ "drivers/AD Driver/subscriber/NOVLADDCFG-sub-ctp-TransformTitle.policy.xml"
bin/idm refs tree/ "library/NOVLLIBAJC-ecma-Utilities.ecmascript.xml"   # who references it
bin/idm package.diff tree/ "drivers/AD Driver/NOVLADDCFG-sub-ctp-…"      # a customised packaged policy vs its baseline
bin/idm docs tree/ --out docs/ --format md        # a README, one page per driver, the library
```

For provisioning: `form.list`, `form.show`, `prd.list`, `prd.show`, and
`prd.flow tree/ "<PRD>"` (a text walk of the workflow) or `--format mermaid`.
For entitlements: `entitlement.list`, `entitlement.show`.

## Step 3 — Make the change

Every edit is a **transaction**: the tool loads the tree, applies the change,
runs `validate`, and writes only if no *new* error appeared. `--dry-run` shows
what it would write. A packaged object is baselined and marked customised on
its first edit, so a later package upgrade can show what you overrode. Never
hand-edit `driver.xml`, `library.xml` or `driverset.xml`.

### 3a — A policy change (the common case)

```bash
# a new policy from a file, linked into the subscriber command transform set
bin/idm policy.add tree/ --driver "AD Driver" --scope subscriber --name "ACME-sub-ctp-NormalizeTitle" \
  --content-file normalize-title.xml --link subscriber-command --at last

# or edit an existing one: rules in, out, moved, disabled
bin/idm rule.add     tree/ --path "drivers/AD Driver/subscriber/ACME-sub-ctp-NormalizeTitle.policy.xml" --content-file rule.xml --at first
bin/idm rule.disable tree/ --path "drivers/AD Driver/subscriber/NOVLADDCFG-sub-ctp-…policy.xml" --rule "Legacy title mapping"
bin/idm artifact.set-content tree/ --path "drivers/AD Driver/…policy.xml" --content-file new-content.xml

# linkage and order
bin/idm policy.link    tree/ --path "…" --driver "AD Driver" --set subscriber-command --at "after:…"
bin/idm policy.reorder tree/ --driver "AD Driver" --set subscriber-command --order "A,B,C"

# GCVs, filter, schema map, mapping tables, driver settings — each has its own operation; bin/idm lists them
```

The content file is what you would paste into Designer's XML view: a
`<policy>` with `<rule>`s (DirXML Script), or XSLT. `validate` compiles it
with the engine's own compiler in the driver's context, resolves every GCV,
mapping table, named password and ECMAScript function it references, and
checks the filter and schema map against the driver.

### 3b — A JSON form and its request definition

```bash
bin/idm form.field.add tree/ --form "Help-desk Request Form" --key priority --type select --label Priority --required
bin/idm prd.map        tree/ --prd HelpdeskTicket --field priority           # into flowdata
bin/idm form.localize  tree/ --form "Help-desk Request Form" --lang de --set "Priority=Priorität"
bin/idm form.preview   tree/ --form "Help-desk Request Form" --out preview.html   # look at it without Designer
bin/idm form.edit      tree/ "Help-desk Request Form"                              # or in the vendor builder
```

### 3c — A workflow

```bash
bin/idm prd.add tree/ --name "Widget Access" --from-template NoApproval --request-form "Widget Request Form" \
  --category accounts --display-name "en~Widget access" --map-all
bin/idm flow.activity.remove tree/ --prd "Widget Access" --id prov            # the template's placeholder grant
bin/idm flow.activity.add tree/ --prd "Widget Access" --kind approval --id approval_1 --after Activity \
  --addressee "IDVault.get(recipient,'user','manager')"
bin/idm flow.activity.add tree/ --prd "Widget Access" --kind provision --id grant --after approval_1 --via approved \
  --entitlement-dn "cn=WidgetAccess,cn=Loopback,cn=driverset1,o=system"
bin/idm prd.flow tree/ "Widget Access"
```

An approval gets the driver's stock approval form and a "denied" path that
records the status, because the Identity Applications need both. The activity
kinds, their flags and the ten checks the engine itself applies are in
[workflows.md](workflows.md); entitlements as code in
[entitlements.md](entitlements.md).

### 3d — A new driver

```bash
bin/idm driver.add tree/ --name "AD Driver" --catalog catalog/ --package NOVLADBASE,NOVLADDCFG --answers ad.properties
bin/idm package.install tree/ --catalog catalog/ --package NOVLADENTEX --driver "AD Driver" --answers ad.properties
```

The result matches what Designer's package installer writes, stamps included.
A hand-built driver (`--shim-class …`) works too for shims that need no
package.

## Step 4 — Prove it before it leaves the workstation

```bash
bin/idm validate tree/                                     # 0 errors, always
git worktree add /tmp/tree-before HEAD                     # the committed tree, to compare against
bin/idm simulate tree/ --cases cases/ --against /tmp/tree-before/tree   # the corpus; every changed output is shown
git worktree remove /tmp/tree-before
git add -A tree/ cases/ && git commit -m "AD: normalize Title on subscriber command (#123)"
```

`simulate` runs the client's regression cases (harvested from a driver's
cache or trace with the simulator's `harvest`, or authored) through the real
policies of the edited tree and diffs the results against the committed tree.
A change you did not intend shows up here, not in production. If the tree has
no corpus yet, `driver.cache view --out cases/…` (Step 7) turns queued live
events into one.

## Step 5 — Deploy

```bash
bin/idm vault.diff   tree/ --env stg                                   # per object: added / changed / removed
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --dry-run    # the plan; nothing written
```

**Read the whole dry-run before going on.** It lists every LDAP write, which
drivers restart, which secrets it will set (names only), and any note. Three
things should make you stop: a delete you did not intend (an out-of-date
tree, so re-import), `MISSING SECRET`, or a driver you did not mean to touch
(add `--driver`). Then:

```bash
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --yes    # snapshot → write → restart → verify → audit
# or one change at a time, each confirmed and verified:
bin/idm vault.deploy tree/ --env stg --step
```

What happens: an LDIF snapshot of every object about to change goes to
`deploy-snapshots/stg/<timestamp>.ldif`; the writes run; affected running
drivers restart (a stopped driver is left stopped and loads the change when
started); the vault is re-read and compared with the tree (`verify: vault
matches the tree`); one line is appended to `deploy-log/stg.jsonl`. Commit
that log with the tree.

What the deployer refuses on its own: writing a tree that does not validate;
deleting a driver (needs `--delete-driver <name>`, which snapshots the whole
subtree first); emptying every entitlement, form or PRD of a driver (needs
`--delete-all <kind>`); touching an existing driver's secrets (needs
`--secrets all|missing`); deploying to `prd` without the gate in Step 8.

If something is wrong afterwards:

```bash
bin/idm vault.rollback tree/ --env stg --snapshot deploy-snapshots/stg/<timestamp>.ldif --yes
bin/idm vault.verify   tree/ --env stg
```

## Step 6 — Prove it at runtime

For a policy change, the canary compares the live engine with the simulator's
prediction from the tree: submit an event on the Subscriber channel, read what
the engine hands the shim, and compare.

```bash
bin/idm driver.submit tree/ --env stg --driver "AD Driver" --xds event.xds --yes
bin/idm driver.trace tail --env stg --driver "AD Driver" --since 5 --grep "Applying rule"
```

For a form or workflow, run it in the Identity Applications without a
browser (see [idapps-rest.md](idapps-rest.md) for every body):

```bash
bin/apps --env stg permission "Widget Access"                       # indexed? which request form?
bin/apps --env stg request "Widget Access" --data reason="proof"    # start it (≈10 min after deploy at the earliest)
bin/apps --env stg tasks
bin/apps --env stg approve <taskId> --comment "looks right"
bin/apps --env stg history                                          # requestState 2 / processState 3 = approved and finished
```

The applications' log (`kubectl logs` on the applications pod, or Tomcat's
`catalina.out`) shows `[Workflow_Started] … [Workflow_Ended]` with every
activity between. A form is looked at in the dashboard (Access → Request → the
PRD) or with `form.preview`.

## Step 7 — Operate

The same environments and gate serve day-two work; no iManager, DxCMD or
shell on the engine host is needed.

```bash
bin/idm driverset.status --env stg                                   # state, start option, cache size, trace level
bin/idm driver.status    --env stg --driver "AD Driver"
bin/idm engine.stats     --env stg --driver "AD Driver"
bin/idm driver.cache view  --env stg --driver "AD Driver" --out cases/ad-cache   # queued events → a simulator case
bin/idm driver.cache clear --env stg --driver "AD Driver" --yes --confirm stg
bin/idm driver.start|stop|restart --env stg --driver "AD Driver" --yes
bin/idm driver.trace set --env stg --driver "AD Driver" --level 3 --file /var/opt/novell/eDirectory/log/ad.trace
bin/idm driver.trace tail --env stg --driver "AD Driver" --since 10
bin/idm driver.resync  --env stg --driver "AD Driver" --since 2026-09-01T00:00:00Z --yes
bin/idm driver.secrets list --env stg --driver "AD Driver"          # names only; set/remove need --yes
```

Reads are free. Start, restart, trace and secrets need `--yes` on `stg` and
`--yes --confirm prd` in production; stop, resync, migrate and submit need
`--yes` everywhere; clearing a cache shows the count and saves the events
before it asks. Every state change is audited in `deploy-log/`.

## Step 8 — Promote to production

Production is the same commands with a gate the tool enforces:

```bash
bin/idm vault.diff   tree/ --env prd
bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --dry-run
bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --yes --confirm prd
```

`--confirm prd` is the environment's name typed out by a person. The tool
also requires: a committed tree (no uncommitted changes under `tree/`), a
production vault that matches the last deploy recorded in
`deploy-log/prd.jsonl` (otherwise someone changed it by hand — run
`vault.deploy --capture-drift` first, which records the vault's current state
on an `as-found/prd/<timestamp>` branch for you to merge), a green
`simulate` when `cases/` exists, and, when `prd.requires=stg` is set, a green
`stg` deploy of the same tree commit in `deploy-log/stg.jsonl`.

## Step 9 — Hand it back to Designer

Teams that keep a Designer project get it updated from the tree rather than
re-imported, so their layout and history survive:

```bash
bin/idm export-project tree/ ~/designer_workspace/Client --dry-run   # what it would touch
bin/idm export-project tree/ ~/designer_workspace/Client             # writes only the files the diff calls for
```

Or, simpler, let Designer *Import from the Identity Vault* after the deploy:
everything the tool writes — policies, forms, PRDs with their workflows,
entitlements, packaged drivers with their stamps — imports and opens
correctly, and workflows lay themselves out. `bin/idm export tree/ out.xml`
produces a driver-set export Designer can import as well.

## Conventions that keep this safe

- One operation or one content edit per commit; the operation's file list
  goes in the message.
- Re-import a tree from its vault after upgrading the tool and before the
  next deploy; a stale tree proposes deletions.
- Read the whole dry-run before `--yes`. If the plan surprises you, the
  answer is to look, not to add a flag.
- Scratch objects on a shared lab carry a recognisable prefix and are removed
  the same day; a lab's diff should read "no differences" when you leave.
- Never put a password, a snapshot or a client's policy content into a chat
  or into this repository; client trees live in the client's repository.
