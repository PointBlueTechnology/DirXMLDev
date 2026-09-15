# Commands, by what they're for

Run `bin/idm` for the full usage with every argument. All commands print
`--json` when asked; write operations take `--dry-run`.

## Get a tree

| | |
|---|---|
| `import <export.xml> tree/` | a driver or driver-set export |
| `import-project <dir> tree/` | a Designer project |
| `import-ldif <dump.ldif> tree/` | an LDIF of the driver-set subtree (must include the DirXML attributes) |
| `import-live <driverSetDN> tree/` | the live vault (`IDM_JAVA_OPTS=-Dldap.url/-Dldap.bindDn/-Dldap.password`) |
| `export tree/ out.xml` | a Designer driver-set export of the tree |

## Orient

`validate tree/` · `query tree/ artifacts|chain|gcvs|tables [driver]` · `show tree/ <path>` · `refs tree/ <path>` · `tree.diff a/ b/` · `package.diff tree/ <path>`

## Provisioning (forms + PRDs; docs/forms.md)

`form.list tree/ [--driver D]` · `form.show tree/ <name>|<kind>/<name>|<driver>/<kind>/<name> [--driver D] [--json]` · `prd.list tree/ [--driver D]` · `prd.show tree/ <name> [--driver D] [--json]` · `prd.flow tree/ <name> [--driver D] [--format text|mermaid] [--lang en] [--out FILE]`

Lives under `drivers/<driver>/provisioning/` — picked up automatically by
`import`/`import-project`/`import-ldif`/`import-live` when the driver has a
`cn=AppConfig` subtree. Form kinds: `request` `approval` `template`.

`prd.flow` renders a PRD's workflow (its `<process>`) without the Identity
Applications or Designer: `text` (default) walks the activity graph
breadth-first from `start-activity`, one line per activity (kind, display
name, key attributes, outgoing links), then a "Data items" section; an
activity no link reaches is listed last, tagged `(unreachable)`. `mermaid`
emits a `flowchart TD`.

Edit a form (transactions; each re-syncs the PRD bindings that reference it):
- `form.edit tree/ <form> [--driver D] [--env E] [--locale L] [--no-wait] [--check] [--dry-run] [--json]`
  — opens it in the vendor form builder (needs Designer 4.8+ installed or
  `IDM_FORMBUILDER`; `--check` says which and prints the one-time fix if it
  can't launch); when the builder closes, a saved document is stored
  pretty-printed and packaged forms are baselined + marked customized.
  `--env E` turns on the builder's online features against `E.formsUrl`.
  `--no-wait` launches on the tree file and returns: run `form.sync` after.
- `form.set-content tree/ --form <form> --content-file f.json [--driver D]` — the same without a GUI (what an agent uses).
- `form.sync tree/ --form <form> [--driver D]` — re-normalize a document saved into the tree and re-sync bindings.
Binding sync = Designer's rules (request-form field list from the components;
approval bindings are references; data items are kept/pruned, never invented —
map a new field explicitly, see below). Packaged forms/PRDs: never refused,
marked customized, baselined under `.package-baseline/`.

Typed operations (P2b — no GUI; every one re-syncs bindings the same way):
- `form.add <tree> --kind request|approval|template --name N [--from <form>] [--title T] [--driver D]` — blank, or a copy of another form's document.
- `form.field.add <tree> <form> --key K --type T [--label L] [--required] [--hidden] [--multiple] [--after K|--before K|--first] [--in <container>] [--json '<extra>'] [--minimal] [--driver D]` — a captured builder template for `T` (or `--minimal`'s `{label,key,type,input}`), placed and flagged as asked; refuses a duplicate key anywhere in the document.
- `form.field.set <tree> <form> --key K [--label L] [--required true|false] [--hidden true|false] [--multiple true|false] [--type T] [--prop path=value]… [--driver D]` — `--prop` is a dotted path, JSON-typed value.
- `form.field.remove <tree> <form> --key K [--driver D]` — refuses while a PRD data item maps it unless `--force` (the sync then prunes the mapping).
- `form.field.move <tree> <form> --key K (--after K|--before K|--first|--last) [--in <container>] [--driver D]` — reorder or reparent.
- `form.set <tree> <form> (--title T|--display form|workflowWizard|--inline-script <file>|--external-script <url> [--remove]) [--driver D]` — top-level properties; a script is reviewed as a file.
- `form.localize <tree> <form> --lang L (--set "Label=Text"…|--sync) [--driver D]` — `--sync` tops up every declared language with everything any language or the components declare (component labels/placeholders/tooltips/option texts, plus every key any language map already has), so a synced form never trips `form-localization-missing`; a missing entry's value comes from `--lang`'s own map, else `en`, else the key text itself.
- `form.rename <tree> <form> --to N [--driver D]` — rewrites every `form-id` reference and `flowdata.<act>/<old>/` prefix across the driver's PRDs.
- `form.delete <tree> <form> [--driver D]` — refuses while any PRD binds it (`--force` never overrides that); a packaged form needs `--force`.
- `prd.map <tree> <prd> --field K [--activity A] [--target <expr>|--source <expr>] [--unmap]` — the explicit mapping step binding sync never does; default target/source follow BindingSync's own conventions (`flowdata.<start>/<form id_>/K`, `flowdata.get('<start>/<request form id_>/K')`).
- `prd.add <tree> --name N --from-template <template PRD> --request-form F [--approval-form G] [--category K] [--display-name "lang~Text"] [--map-all] [--driver D]` — copies a template PRD (status `Template`) into a new Active one; the request field list is rebuilt by binding sync, mappings kept only where the field still exists. `--map-all` maps every bindable field of the bound form(s) to flowdata with the default targets/sources, as `prd.map` would (an approval field with no same-named request field is skipped and noted).
- `prd.delete <tree> --prd P [--driver D]` — the mirror of `form.delete`: refuses while another PRD's `start-correlated-flow-activity` references it by name or DN (`--force` never overrides that); a packaged PRD needs `--force`. The forms it bound are not deleted (noted), so a following `form.delete` on them can now succeed.

Look at a form without the Identity Applications:
- `form.preview tree/ <form> [--driver D] [--out page.html] [--lang en]` — a self-contained HTML page (open-source Form.io renderer + placeholders for the NetIQ component types; live data sources are not fetched). Open it in a browser; it is a layout/conditional check, not the vendor renderer.

Look at a PRD's workflow (Track W step W1; `docs/workflows.md`):
- `prd.flow tree/ <prd> [--driver D] [--format text|mermaid] [--lang en] [--out FILE]` — a text walk of the activity graph from `start-activity` (kind, display name, key attributes, outgoing links, then data items; an unreached activity is tagged `(unreachable)`), or a `flowchart TD` Mermaid diagram.

Flow checks (`FlowCheck`, on by default in `validate`, mirroring the engine's own `ProcessFlowModel.validate()` plus attribute/expression/placeholder checks it doesn't run — `docs/workflows.md` §1.2/§1.4): `flow-version-unsupported` `flow-start-missing` `flow-finish-missing` `flow-start-multiple` `flow-finish-multiple` `flow-activity-id-missing` `flow-activity-id-duplicate` `flow-activity-kind-unknown` `flow-link-source-unknown` `flow-link-target-unknown` `flow-link-type-invalid` `flow-link-type-not-allowed` `flow-condition-links` `flow-start-incoming` `flow-finish-outgoing` `flow-activity-dangling` `flow-branch-merge` `flow-ontimeout-link` `flow-form-binding-start` `flow-data-items-activity-unknown` `flow-data-items-on-start` `flow-role-binding` `flow-resource-binding` `flow-addressee-missing` `flow-flowdata-expression` `flow-approver-type-invalid` `flow-approver-condition-both` `flow-approver-target-items` `flow-email-template-missing` `flow-attribute-enum` `flow-expression-syntax` `flow-placeholder` `flow-display-name-missing`.

## Workflow (flow.*; Track W step W2, `docs/workflows.md`)

Typed operations on a PRD's `<process>` — no GUI; each resolves `--prd` (+ `--driver` when ambiguous), refuses on an unknown/duplicate/invalid activity id or a value outside its engine enum, and finishes through the same customize/baseline/`--force` conventions as `prd.map`/`prd.add`.

- `flow.activity.add <tree> --prd P --kind approval|condition|log|notification|mapping|provision --id X --after Y [--via T] [--to Z] [--on-denied ID] [--on-false ID] [--name TEXT] [--addressee EXPR] [--timeout MS] [--ontimeout T] [--expression E] [--message M] [--template DN] [--entitlement-dn DN] [--entitlement-param V] [--form F] [--status approved|denied] [--driver D]` — inserts activity `X` after `Y` (consuming `Y`'s single/`--via`-picked outgoing link, or — when `Y` is a branch — adding a new parallel leg to `--to`), wiring `X`'s default outgoing link(s) by kind (approval: `approved` to the successor, `denied` to the process's "Workflow Status Denied" mapping — created as `status_denied` → finish when absent — so Request History reads Denied; `--on-denied` overrides; condition: `true`/`false`; log/mapping/provision/notification: `forward`) and its stock defaults (see below). `--kind branch` is refused — use `flow.branch.add`. `--kind mapping --status approved|denied` creates a status mapping (sets `flowdata.IDM_COMPLETED_APPROVAL_STATUS`). An approval binds an approval form (`--form F`, default the driver's stock `Approval Form` if present) with the stock data items — without one the Identity Applications cannot open the task.
- `flow.activity.set <tree> --prd P --id X [--name TEXT] [--attr name=value]… [--addressee EXPR] [--timeout MS] [--ontimeout T] [--expression E] [--message M] [--template DN] [--entitlement-dn DN] [--entitlement-param V] [--approver-type T] [--form F] [--driver D]` — sets only what is given; `--form` (approval only) binds/replaces the approval form; `--attr activity-id` is refused (use `flow.activity.rename`); `--entitlement-dn`/`--entitlement-param` only apply to a provision activity.
- `flow.activity.rename <tree> --prd P --id X --to Y [--driver D]` — renames the id everywhere: the element, every link, `data-items`/`form-binding activity-id`, and any `X.`/`'X/` reference in an expression or flowdata path.
- `flow.activity.remove <tree> --prd P --id X [--driver D]` — refused for start/finish/branch/merge; reconnects every incoming link to `X`'s primary (`forward`/`approved`/`true`/`success`) successor, dropping `X`'s other outgoing links, data items and form binding.
- `flow.branch.add <tree> --prd P --id B --merge M --after Y [--via T] [--driver D]` — inserts a branch/merge pair in place of `Y`'s consumed link; add legs afterwards with `flow.activity.add --after B --to M`.
- `flow.branch.remove <tree> --prd P --id B [--driver D]` — removes a branch/merge pair with a single leg (or none between them), reconnecting around it; refuses a branch with more legs.
- `flow.link.add <tree> --prd P --from A --to B --type T [--driver D]` / `flow.link.remove <tree> --prd P --from A --to B [--type T] [--driver D]` / `flow.link.retype <tree> --prd P --from A --to B --type T --to-type U [--driver D]` — validated against the engine's per-kind allowed link types (refuses early, naming the allowed set).
- `flow.data.set <tree> --prd P --activity X --name N (--source EXPR|--target EXPR) [--type T] [--target-type T] [--driver D]` / `flow.data.remove <tree> --prd P --activity X --name N [--driver D]` — a raw data item on any activity (`prd.map` stays the form-field-driven one).
- `flow.set <tree> --prd P [--version V] [--process-type T] [--flow-strategy S] [--default-completed-approval-status approved|denied] [--setnotify true|false] [--restrict-view true|false] [--generate-comments true|false] [--driver D]` — process-level attributes, enum-validated.

Stock defaults (copied from the idm254 templates, never the templates themselves): an approval's `timeout`/`ontimeout`/default `addressee`/`notify` template+maps/`retry` from `TemplateSingleApproval_TD`; a log's `author` from the same; a provision activity's five data items (`dn`, `DirXML-Entitlement-DN`, `-Action`, `-Parameter`, `-MultiValueAllowed`) from `NoApproval`.

Deploy: forms and PRDs ride the normal `vault.diff`/`vault.deploy`/`vault.rollback` (no driver restart; the plan says when the Identity Applications may need a cache flush).

`export-project` (below) also carries a driver's forms/PRDs into an existing Designer project — same command, no new flags.

## Change (transactions; `--dry-run`, `--force` — don't)

`policy.add` `resource.add` `artifact.set-content` `artifact.rename` `artifact.delete` `policy.link` `policy.unlink` `policy.reorder`
`rule.add` `rule.delete` `rule.move` `rule.disable` `rule.enable`
`gcv.set` `gcv.delete` `filter.set-class` `filter.set-attr` `filter.remove-class` `filter.remove-attr`
`schema-map.set` `schema-map.remove` `driver.set` `mapping-table.set-row` `mapping-table.delete-row` `mapping-table.add-column`
`driver.add --from-export | --copy-of | --shim-class`

Artifact paths: `library/<name>`, `drivers/<driver>/<name>`, `drivers/<driver>/subscriber|publisher/<name>`. Policy-set keys: `input output ecmascript subscriber-event publisher-event subscriber-matching publisher-matching subscriber-create publisher-create subscriber-command publisher-command subscriber-placement publisher-placement gcv schema-mapping`.

## Prove

`validate tree/` · `simulate tree/ --cases cases/ [--against <tree>]`

## Deploy (`--env <name>` from `environments.properties`)

`vault.diff` · `vault.deploy --dry-run | --yes | --step [--driver D] [--confirm <env>] [--secrets none|missing|all] [--capture-drift]` · `vault.verify` · `vault.rollback --snapshot <file> --yes`

## Operate

`driverset.status` · `driver.status` · `driver.start|stop|restart` · `driver.cache view [--out dir] | clear --yes` · `driver.migrate --xds f --yes` · `driver.resync [--since t] --yes` · `driver.secrets list|set|remove` · `driver.trace show|set|reset|tail [--lines N] [--grep RE] [--since MIN] [--follow]` · `driver.submit --xds f --yes [--tree tree/]` · `engine.version` · `engine.stats [--driver D]`

Gating: reads are free; start/restart/trace/secrets need `--yes` on stg and `--yes --confirm <env>` on prd; stop/resync/migrate/submit need `--yes` everywhere; `cache clear` needs `--yes` (+ `--confirm` on stg/prd). Every state change is audited in `deploy-log/<env>.jsonl`.

## Document

`docs tree/ --out docs/ [--driver D] [--since <commit>] [--format md|html]`

## Designer and documentation

- `export-project <tree> <projectDir> [--dry-run] [--json]` — update an existing Designer project in place from the tree (only the files the diff needs; refuses packaged new drivers and driver deletes; also carries a driver's forms/PRDs, refusing only that driver's provisioning when the project has no AppConfig for it)
- `docs <tree> --out <dir> [--driver D…] [--since <commit>] [--format md|html]` — README, `drivers/<name>.md`, `library.md`, `changes.md`

## Packages (Phase 7, in progress)

- `package.install <tree> --jar a.jar[,b.jar…] | --catalog DIR --package SHORT[_ver][,…] --driver D [--answers FILE] [--new-driver true]` — install a package set onto a driver in one transaction, Designer's way (prompts, weights, filter merge, stamps, installed checksums); without `--driver`, a driver-set package into the Library
- `driver.add <tree> --name N --packages base.jar,… | --catalog DIR --package SHORT[,…] [--answers FILE]` — a new packaged driver built from the base package and its features
- `package.upgrade <tree> --driver D --jar new.jar | --catalog DIR --package SHORT_ver [--answers FILE] [--yes]` — Designer's uninstall-old + install-new: customized objects keep their content, baselines and stamps move; downgrade allowed with a note
- `package.uninstall <tree> --driver D --package SHORT [--yes] [--all]` — removes the package's objects, the links it owns, its filter entries and record; refuses while another package depends on it (`--all` removes dependants) or the base while features remain; customized objects need `--yes`
- `package.status <tree> [--driver D] [--catalog DIR] [--json]` — per driver: installed packages (manifest record cross-checked with object stamps), customized objects (installed checksum ≠ recomputed), catalog presence and newer versions
- `package.adopt <tree> [--driver D] [--catalog DIR]` — write the installed-package records from the object stamps (a tree imported from a vault or a Designer project has stamps but no record)
- `package.fetch|import|list|show|diff|resolve --catalog DIR …` — the package catalog (a git directory of jars + a diffable unpacked form; `package.fetch` pulls from the update site)
- `package.build --catalog DIR <tree> --driver D --short SHORT --name "…" [--vendor V] [--version M.m.r] [--include path]… [--new-version-of SHORT_ver] [--gcvs referenced|all|none] [--customized keep]` — a package from the driver's (or, without `--driver`, the Library's) non-packaged artifacts + the driver GCVs they read; verified like Designer verifies on import; added to the catalog. Designer lists it by `--name`, not by the short name; a downloaded package appears in Designer's install lists only after Designer restarts
- `package.site --catalog DIR --out DIR` — render the catalog as an update site Designer users can add (Package Manager → Online Updates)
- a refusal naming "mandatory prompts without a value" lists the prompt names to put in the answers file (`name=value`; `eNull` = empty; password prompts are named passwords for the environment's secrets file)
