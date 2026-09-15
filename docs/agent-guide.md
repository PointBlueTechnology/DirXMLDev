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

## Provisioning: forms and PRDs

A User Application driver's `cn=AppConfig` subtree (JSON/Form.io provisioning
forms and their request definitions — see [forms.md](forms.md)) reads and
writes with `import`/`import-project`/`import-ldif`/`import-live` like any
other driver content, under `drivers/<driver>/provisioning/`.

```bash
bin/idm form.list tree/ --driver "User Application Driver"           # kind, name, title, #fields, packaged mark
bin/idm form.show tree/ "Help-desk Request Form" --json               # fields, scripts, languages, PRDs that bind it
bin/idm prd.list  tree/ --driver "User Application Driver"            # status, category, json-forms/classic, bound forms
bin/idm prd.show  tree/ HelpdeskTicket                                 # properties, bindings, workflow activities
```

`form.show` accepts a bare name (searched across every driver's provisioning),
`<kind>/<name>` (`request`/`approval`/`template`), or `<driver>/<kind>/<name>`
when names collide. A form is referenced *by name* from a PRD's
`form-binding`; `form.show`/`prd.show` cross-reference the two so you can see
a field's shape and everywhere it's used in one place.

Changing a form is a transaction like any other, and every form transaction
re-syncs the PRD bindings that reference the form (Designer's own rules — the
request form's field list is rebuilt from the components; data items are the
persisted mappings and are kept or pruned, never invented, so a new field needs
an explicit mapping step):

```bash
bin/idm form.edit tree/ "Help-desk Request Form" --check                       # which vendor builder would run; one-time fixes if any
bin/idm form.edit tree/ "Help-desk Request Form"                               # a person: opens the vendor form builder; on save+close → stored + bindings synced
bin/idm form.set-content tree/ --form "Help-desk Request Form" --content-file new.json   # an agent: same result without a GUI
bin/idm form.sync tree/ --form "Help-desk Request Form"                         # after a builder saved into the tree with --no-wait
```

The form is stored pretty-printed (readable diffs); the deployer and the
Designer writer emit the compact form the vendor tools use. Editing one of the
11 stock forms or a stock PRD is allowed: it is marked customized and its
pre-edit document is baselined under `.package-baseline/`, exactly like a
packaged policy.

For a change an agent can make without the GUI, use the typed operations
(`form.add`, `form.field.add/set/remove/move`, `form.set`, `form.localize`,
`form.rename`, `form.delete`, `prd.map`, `prd.add`, `prd.delete`) — same
transaction machinery (`--dry-run`, `--force`, `--json`, `validate` after
every write). A
common recipe, add a field to a request form and map it to flowdata:

```bash
bin/idm form.field.add tree/ "Help-desk Request Form" --key priority --type select \
    --label Priority --required --json '{"data":{"values":[{"label":"High","value":"high"},{"label":"Low","value":"low"}]}}'
bin/idm prd.map tree/ HelpdeskTicket --field priority          # default target: flowdata.Start/Help-desk_Request_Form/priority
bin/idm validate tree/                                          # 0 errors expected (FormCheck runs by default)
```

`form.field.add` places the field at the end of `components` by default, or
next to another field (`--after`/`--before`/`--first`) or inside a named
container/columns component (`--in`); it starts from the captured builder
template for that type when one exists (`resources/forms/components/`) so a
form we author round-trips through the vendor builder unchanged, or the
minimal `{label,key,type,input}` shape with `--minimal` (what the IDM 4.10.1
stock forms actually use). `prd.map` is the one binding-sync never does on its
own — a new field starts unmapped until the agent explicitly maps it (or maps
an approval activity's data item with `--activity`); everything else about
keeping the PRD in step (rebuilding the request field list, pruning a mapping
whose field disappeared) happens automatically on every form-changing
operation. `FormCheck` (part of the standard validator) flags a document with
no components, a duplicate or missing key, an unknown component type, a
`conditional`/`logic` reference to a key that doesn't exist, a script that
doesn't compile, a request form with no button, incomplete localization, and a
PRD binding/mapping that's stale, drifted, or unbound.

To look at a form before deploying it, without the Identity Applications:

```bash
bin/idm form.preview tree/ "Help-desk Request Form" --out helpdesk.html   # self-contained page: open-source Form.io renderer + placeholders for NetIQ components
```

It is a layout and conditional-logic check (live data sources are shown as
"not fetched"), not the vendor renderer. Deploying forms and PRDs is the
normal `vault.diff` → `vault.deploy` → verify path; no driver restarts, and
the plan says when the Identity Applications may need a cache flush.

### Reading a workflow (Track W step W1)

A PRD's `<process>` — the workflow the Identity Applications actually runs —
reads as a typed view without hand-parsing XML:

```bash
bin/idm prd.flow tree/ HelpdeskTicket                                  # header + a walk of the graph from start, activity by activity
bin/idm prd.flow tree/ HelpdeskTicket --format mermaid --out flow.mmd  # a flowchart another tool (or a person) can render
```

The text form walks the activity graph breadth-first from `start-activity`
following links (an activity no link ever reaches — a broken process — is
still listed, tagged `(unreachable)`), printing each activity's kind, display
name, the attributes that matter for its kind (timeout/approver-type/addressee
for an approval, the expression for a condition, category/operation for a
provisioning step, …) and its outgoing links, then every activity's data
items. `--format mermaid` emits a `flowchart TD` instead. Neither needs the
Identity Applications, Designer, or a live vault.

`validate` (on by default, `FlowCheck`) mirrors the workflow engine's own ten
load-time checks (`ModelFactory.loadProcessFlow` / `ProcessFlowModel.validate()`
— see `docs/workflows.md` §1.2) against every PRD's `<process>`: known process
version, exactly one start/finish, every link's endpoints and type valid for
its source activity's kind, a condition has both a `true` and `false` link, no
dangling activity, a branch has its merge, RBAC/RBACSOD/Resource processes
bind both outcomes, every `flowdata.` reference is `.get(`/`.getObject(`,
every approval has an addressee, `notify`/`confirm`/`reminder` have a
template — plus checks the engine doesn't run at all but that catch a broken
workflow before deploy: attribute enums, an addressee/expression/data-item
source that doesn't compile as ECMAScript, a leftover `{enter … here}`
template placeholder (warning on an `Active` PRD, informational on a template),
and an activity with no display name. `docs/workflows.md` §1.4 has the full
rationale; `commands.md` lists every `flow-*` code.

### Author a workflow (Track W step W2)

Changing the flow itself — not just a form's fields — is the `flow.*` typed
operations (`commands.md` has the full list): one op per concept
(`flow.activity.add/set/rename/remove`, `flow.branch.add/remove`,
`flow.link.add/remove/retype`, `flow.data.set/remove`, `flow.set`), each a
transaction like every other edit here (`--dry-run`, `--force`, `--json`,
`validate` after every write — a change `FlowCheck` would newly flag is
refused before it is written, same as `prd.map`/`form.field.add`). A common
recipe — start from `NoApproval` (the simplest stock template: start → grant
an entitlement → finish), replace its provisioning step with a condition and
a two-step approval, then look at the result:

```bash
bin/idm prd.add tree/ --name "Widget Access" --from-template NoApproval --request-form "Widget Request Form"
bin/idm flow.activity.remove tree/ --prd "Widget Access" --id prov          # drop the stock grant step
bin/idm flow.activity.add tree/ --prd "Widget Access" --kind condition --id needs_reason \
    --after Start --expression "flowdata.get('reason') != null"
bin/idm flow.activity.add tree/ --prd "Widget Access" --kind approval --id approval_1 --after needs_reason --via true \
    --addressee "IDVault.get(recipient,'user','manager')"
bin/idm flow.activity.add tree/ --prd "Widget Access" --kind approval --id approval_2 --after approval_1 --via approved \
    --addressee "'cn=uaadmin,ou=sa,o=data'"
bin/idm prd.map tree/ "Widget Access" --field reason                        # bind the request form's field to flowdata
bin/idm validate tree/                                                       # 0 errors, 0 flow-placeholder expected
bin/idm prd.flow tree/ "Widget Access" --format mermaid --out flow.mmd       # review the shape before deploying
```

`flow.activity.add --after Y` inserts the new activity into `Y`'s single
outgoing link (or the one named by `--via`, when `Y` has more than one — the
op refuses and names the choices otherwise) and wires the new activity's own
default outgoing link(s) for its kind (an approval's `approved`/`denied`, a
condition's `true`/`false`, everything else's `forward`) — so the graph is
always dangling-free and validates clean right after the op, without a
separate `flow.link.add`. Parallel work is `flow.branch.add` (a branch/merge
pair) followed by `flow.activity.add --after <branch> --to <merge>` for each
leg. An approval's stock shape (timeout, default addressee, notify
template+maps, retry) and a provision activity's five entitlement data items
come from the same idm254 templates `prd.add --from-template` copies from
(`TemplateSingleApproval_TD`, `NoApproval`) — see `commands.md`. Once the
flow reads right, deploy it the normal way: `vault.diff` → `vault.deploy`.

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

## Add a driver

New drivers are authored in the tree, not in Designer. Three sources:

```bash
bin/idm driver.add tree/ --name "AD Driver 2" --from-export ad-export.xml   # a vendor/package export (driver-set form keeps Library scope)
bin/idm driver.add tree/ --name "AD Driver TEST" --copy-of "AD Driver"      # a deep copy: artifacts, config and links re-pointed
bin/idm driver.add tree/ --name Loop --shim-class com.example.Shim         # blank: channels, empty filter, no policies
```

The operation is a transaction like the others: the new driver must validate
in this tree, so an export whose GCVs are defined at the driver-set level of
its origin will be refused until those GCVs exist here (define them first with
`gcv.set --define`). Deploy it like any other change; the deployer creates the
objects and refuses until the driver's required secrets are in the environment's
secrets file (or `--allow-missing-secrets`).

## Packages

Packages are Designer's unit of vendor and custom content. DirXMLDev keeps
its own catalog (a git directory of package jars) and installs, inspects and
builds packages without Designer ([packages.md](packages.md)):

```bash
bin/idm package.fetch  --catalog ~/idm-packages --short NOVLADBASE --latest      # from the update site
bin/idm package.import --catalog ~/idm-packages /Applications/Designer/packages/eclipse/plugins   # or a Designer install
bin/idm package.show   --catalog ~/idm-packages NOVLADBASE
bin/idm package.resolve --catalog ~/idm-packages --base NOVLADBASE --feature NOVLADDCFG
bin/idm driver.add tree/ --name "AD Driver" --catalog ~/idm-packages --package NOVLADBASE,NOVLADDCFG --answers ad.properties
bin/idm package.install tree/ --catalog ~/idm-packages --package NOVLADENTEX --driver "AD Driver" --answers ad.properties
bin/idm package.status tree/ --catalog ~/idm-packages          # installed, customized, newer versions
bin/idm package.build  --catalog ~/idm-packages tree/ --driver "AD Driver" --short PBTADCUST --name "AD customizations" --vendor pointbluetech
bin/idm package.site   --catalog ~/idm-packages --out /var/www/packages   # an update site Designer users can add
```

An install is a transaction like any other edit, and reproduces Designer's
install exactly (prompts, placement, weights, stamps); the deployer writes
the package stamps to the vault, so Designer sees the packages when it
imports. A refusal naming mandatory prompts lists what the answers file
needs. Content that references GCVs another package defines installs in
the same transaction as that package (`--package a,b,c`). A tree imported
before package stamps were read needs `package.adopt` (or a fresh
`import-live`).

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

## Hand it back to Designer

Teams that keep a Designer project get it updated from the tree, not
re-imported:

```bash
bin/idm export-project tree/ ~/designer_workspace/Client --dry-run   # what it would touch
bin/idm export-project tree/ ~/designer_workspace/Client             # writes; result lists created/changed/deleted files
bin/idm docs tree/ --out docs/ --since HEAD~5                        # README, one page per driver, library, changes
```

The writer edits only the files the diff calls for: a changed policy rewrites
its `_contents.xml`; an added one gets a new 8-character id, its CObject and
contents files, and a Child relation on its owner; a removed one loses its
files and every relation that pointed at it; a rename keeps the id; linkage
rewrites the ordered relations on the driver or channel. Everything else in
the project is byte-identical afterwards. It refuses a driver that carries
package metadata (Designer's catalog owns those; deploy to the vault and
import there) and never deletes a driver. Commit the project after it runs
and open it in Designer once before trusting a new kind of change.

## Conventions

- One operation or one content edit per commit, with the result's file list in
  the message; `--json` output is fine to paste.
- Never hand-edit `driver.xml` / `library.xml` / `driverset.xml` manifests —
  that's what the operations are for.
- Never delete `.package-baseline/` entries; they're the record of every
  override.
- Don't run two operations on the same tree concurrently.
- Client trees hold client policy: they live in the client's repo, never here.
