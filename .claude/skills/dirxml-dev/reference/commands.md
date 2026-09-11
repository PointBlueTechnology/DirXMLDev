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

`form.list tree/ [--driver D]` · `form.show tree/ <name>|<kind>/<name>|<driver>/<kind>/<name> [--driver D] [--json]` · `prd.list tree/ [--driver D]` · `prd.show tree/ <name> [--driver D] [--json]`

Lives under `drivers/<driver>/provisioning/` — picked up automatically by
`import`/`import-project`/`import-ldif`/`import-live` when the driver has a
`cn=AppConfig` subtree. Form kinds: `request` `approval` `template`.

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
- `form.localize <tree> <form> --lang L (--set "Label=Text"…|--sync) [--driver D]` — `--sync` tops up every declared language with an entry (English/source text) for anything missing.
- `form.rename <tree> <form> --to N [--driver D]` — rewrites every `form-id` reference and `flowdata.<act>/<old>/` prefix across the driver's PRDs.
- `form.delete <tree> <form> [--driver D]` — refuses while any PRD binds it (`--force` never overrides that); a packaged form needs `--force`.
- `prd.map <tree> <prd> --field K [--activity A] [--target <expr>|--source <expr>] [--unmap]` — the explicit mapping step binding sync never does; default target/source follow BindingSync's own conventions (`flowdata.<start>/<form id_>/K`, `flowdata.get('<start>/<request form id_>/K')`).
- `prd.add <tree> --name N --from-template <template PRD> --request-form F [--approval-form G] [--category K] [--display-name "lang~Text"] [--driver D]` — copies a template PRD (status `Template`) into a new Active one; the request field list is rebuilt by binding sync, mappings kept only where the field still exists.

Look at a form without the Identity Applications:
- `form.preview tree/ <form> [--driver D] [--out page.html] [--lang en]` — a self-contained HTML page (open-source Form.io renderer + placeholders for the NetIQ component types; live data sources are not fetched). Open it in a browser; it is a layout/conditional check, not the vendor renderer.

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
