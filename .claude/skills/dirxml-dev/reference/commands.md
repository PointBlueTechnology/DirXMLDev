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

- `export-project <tree> <projectDir> [--dry-run] [--json]` — update an existing Designer project in place from the tree (only the files the diff needs; refuses packaged new drivers and driver deletes)
- `docs <tree> --out <dir> [--driver D…] [--since <commit>] [--format md|html]` — README, `drivers/<name>.md`, `library.md`, `changes.md`
