# Command reference

Every `bin/idm` command: what it does, each argument (required or not, the values it takes, the
default) and an example. `bin/idm` with no arguments prints the same commands in short. The edit
operations' entries are written from the operation registry, so they always match the code; the
others are kept with it by a test that fails when a command has no entry here.

`bin/apps` (Identity Applications over REST) is [idapps-rest.md](idapps-rest.md). The simulator's
own `bin/sim` is the DirXMLSimulator repository's.

## How to read an entry

- **`<tree>`** is an IDM-as-code tree directory: the folder with `driverset.xml`
  ([tree-layout.md](tree-layout.md)).
- **`--env <name>`** names an environment in `environments.properties`: its vault, its bind and
  where its password comes from ([secrets.md](secrets.md)). A command that reads or writes a vault
  takes one.
- **Flags** are written `--name value` or `--name=value`. A flag that takes no value is marked *a
  flag* or says so, and never takes the word after it, so the tree can come before or after the
  flags. An option the command doesn't take, a value given to a flag, an option without its value
  and a number that isn't one exit 2 and say which. `--json` is accepted by every command.
- **`--json`** prints the result as JSON instead of text, where the command offers it.
- **Exit codes**: 0 when the command did what was asked, 1 when it refused or found a difference
  (a diff, a validation error), 2 on a usage error, 3 on an unexpected error (`ERROR: …`). An
  entry says when a command differs.
- **The simulator jar**: `bin/idm` runs every command but `doctor` only when it finds the
  simulator jar; `doctor` says what is missing ([install.md](install.md)).
- **Writes to a vault** need `--yes`, and `IDM_AGENT_ALLOW_WRITE=1` or `--confirm <env>`; a
  production environment needs `--confirm <env>` as well. [Vault writes](#vault-writes) has the
  list. Nothing else here writes to a vault.
- **Examples** use generic names: a tree in `tree`, environments `test`, `qa` and `prod`, drivers
  such as "Active Directory" and "User Application". Replace them with yours.

## Contents

- [Set up and check](#set-up-and-check): `version`, `doctor`
- [Get a tree, and give it back](#get-a-tree-and-give-it-back): `import`, `import-project`,
  `import-ldif`, `import-live`, `export`, `export-project`
- [Look at a tree](#look-at-a-tree): `check`, `validate`, `show`, `query`, `refs`,
  `mapping-table.export-csv`, `package.diff`, `docs`
- [Test](#test): `simulate`
- [Two trees and two projects](#two-trees-and-two-projects): `tree.diff`, `tree.compare`, `promote.*`
- [Vault: compare, deploy, schema, clone, events](#vault-compare-deploy-schema-clone-events):
  `vault.*`, `schema.diff`, `events.*`, `query … events`
- [Forms, workflows, AppConfig and entitlements: reading them](#forms-workflows-appconfig-and-entitlements-reading-them)
- [The package catalog](#the-package-catalog): `package.*` on a catalog
- [Operate a running driver set](#operate-a-running-driver-set): `driverset.*`, `driver.*`,
  `job.*`, `engine.*`, `trace.view`, `rbe.*`
- Changing a tree, the edit operations: [policies, resources and rules](#edit-policies-resources-and-rules),
  [mapping tables](#edit-mapping-tables), [GCVs, filter, schema map and driver settings](#edit-gcvs-filter-schema-map-and-driver-settings),
  [environments](#edit-environments), [packages](#edit-packages), [vault schema](#edit-vault-schema),
  [forms](#edit-forms), [workflows](#edit-workflows-prds), [entitlements](#edit-entitlements),
  [AppConfig, roles and resources](#edit-appconfig-roles-and-resources)

## Set up and check

### `version`

The running version of DirXMLDev. With `--check`, it also asks GitHub for the latest release (two-second timeout). Exits 1 when GitHub can't be reached; otherwise 0.

```
bin/idm version [--check]
```

| Argument | Required | Values |
|---|---|---|
| `--check` | no | ask GitHub for the latest release; anywhere after `version` (any other argument exits 2). Runs even when `IDM_NO_UPDATE_CHECK` is set; sends `GITHUB_TOKEN` when set |

```bash
bin/idm version --check
```

More: [install.md](install.md#25-versions-and-release-notices).

### `doctor`

Checks the workstation: JDK 21, the simulator jar, the engine jars in `lib/`, JLDAP, the trace viewer, TLS settings, the latest release (information only), and the environments file. It lists each environment's name, tier, and whether a URL, bind DN, password and driver set are set, never the values. With `--env`, it also opens one LDAPS connection and binds to that environment. Exits 1 when a check fails, 2 on an unknown argument. When the JDK or the built classes are missing, the shell launcher prints the JDK, simulator and `lib/` lines itself and exits 1.

```
bin/idm doctor [--json] [--env <name>]
```

| Argument | Required | Values |
|---|---|---|
| `--json` | no | the report as JSON (`ok`, `checks[]`) |
| `--env` | no | an environment in `environments.properties` (found via `IDM_ENVIRONMENTS`, the working directory, then `~/.idm/`); probes its LDAPS bind. The password and bind DN are not printed |

```bash
bin/idm doctor --env test
```

More: [install.md](install.md#24-check-the-workstation-doctor-and-what-ci-runs).

## Get a tree, and give it back

### `import`

Reads a Designer driver export or driver-set export and writes an IDM-as-code tree. Existing files are overwritten; files the export doesn't have are not removed. Also writes `.gitattributes` so git keeps line endings. Exits 1 when a policy link resolves to nothing (the tree is still written).

```
bin/idm import <export.xml> <outDir>
```

| Argument | Required | Values |
|---|---|---|
| `<export.xml>` | yes | a Designer driver or driver-set export file |
| `<outDir>` | yes | the tree directory to write; created if needed |

```bash
bin/idm import DriverSet-export.xml tree
```

More: [getting-started.md](getting-started.md#4-import-the-driver-set).

### `import-project`

Reads a Designer project directory and writes an IDM-as-code tree. Same writing and exit behaviour as `import`.

```
bin/idm import-project <projectDir> <outDir>
```

| Argument | Required | Values |
|---|---|---|
| `<projectDir>` | yes | a Designer project directory |
| `<outDir>` | yes | the tree directory to write; created if needed |

```bash
bin/idm import-project ~/designer_workspace/Project tree
```

More: [getting-started.md](getting-started.md#4-import-the-driver-set).

### `import-ldif`

Reads an LDIF of the driver-set subtree and writes an IDM-as-code tree. The LDIF is a subtree export from the driver set's DN down, with `objectClass` and the DirXML data attributes. Same writing and exit behaviour as `import`.

```
bin/idm import-ldif <dump.ldif> <outDir>
```

| Argument | Required | Values |
|---|---|---|
| `<dump.ldif>` | yes | an LDIF of the driver-set subtree (an LDIF based on one driver yields only that driver) |
| `<outDir>` | yes | the tree directory to write; created if needed |

```bash
bin/idm import-ldif driverset.ldif tree
```

More: [getting-started.md](getting-started.md#4-import-the-driver-set).

### `import-live`

Reads the live vault's driver set and writes an IDM-as-code tree. It reads the vault and never writes to it. With `--env` and an existing tree in `<outDir>`, it keeps the tree's driver and server names, keeps the tree's drivers this environment doesn't get, and folds values that differ per stage back into `overrides/<env>.properties`. A shim auth id that the environment's secrets file supplies is kept out of the tree. Exits 1 when a policy link resolves to nothing, 2 on a usage error.

```
bin/idm import-live [<driverSetDN>] <outDir> --env <name> [--mark-new-drivers]
IDM_JAVA_OPTS="-Dldap.url=… -Dldap.bindDn=… -Dldap.password=…" bin/idm import-live <driverSetDN> <outDir>
```

| Argument | Required | Values |
|---|---|---|
| `<driverSetDN>` | yes without `--env`; no with it | the driver set's DN; with `--env`, default: the environment's `driverSet`. A DN other than that one is read without the other servers' driver settings |
| `<outDir>` | yes | the tree directory to write (always the last positional) |
| `--env` | no | an environment in `environments.properties` (`url`, `bindDn`, the password in any supported form, `driverSet`) |
| `--mark-new-drivers` | no | with `--env` and an existing tree: marks drivers new to the tree as this environment's only |
| `-Dldap.url`, `-Dldap.bindDn`, `-Dldap.password` | yes without `--env` | system properties in `IDM_JAVA_OPTS`; the connection when no `--env` is given |
| `-Dldap.trustAll=true` | no | without `--env`: trust any server certificate |

```bash
bin/idm import-live tree --env test
```

More: [getting-started.md](getting-started.md#4-import-the-driver-set), [vault-deploy.md](vault-deploy.md#environments-that-differ-in-more-than-values-2026-10-20).

### `export`

Writes the tree as a Designer driver-set export that Designer can import. Prints the tree's summary. Exits 0, even when links are broken.

```
bin/idm export <asCodeDir> <out.xml>
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `<out.xml>` | yes | the export file to write |

```bash
bin/idm export tree driverset-export.xml
```

More: [day-to-day.md](day-to-day.md#6-hand-the-result-to-designer).

### `export-project`

Updates an existing Designer project to match the tree, or with `--new` writes a whole new project for it. It edits only the project files the difference calls for. On an update it refuses a driver removed from the tree and a driver that carries package metadata. With `--new`, the directory's basename is the project name, the directory must not exist or must be empty, and no vault password is ever written. Exits 1 when refused, 2 on a usage error (including a `--new`-only flag without `--new`).

```
bin/idm export-project <tree> <projectDir> [--dry-run] [--json]
bin/idm export-project <tree> <newProjectDir> --new [--vault-name <name>] [--vault-host <host>] [--vault-user <DN>]
        [--server <name> --server-context <DN>] [--catalog <dir>] [--designer <dir>] [--dry-run] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<projectDir>` | yes | an existing Designer project; with `--new`, a new or empty directory |
| `--new` | no | write a new project instead of updating one |
| `--vault-name` | no; `--new` only | the `IdentityVault_` object's name (the tree name); default: a neutral name |
| `--vault-host` | no; `--new` only | `IdentityVaultHost`; default: none (Designer asks) |
| `--vault-user` | no; `--new` only | `IdentityVaultUsername`, a DN; default: none |
| `--server` | no; `--new` only | the engine server's name; without it no `Server_` object is written |
| `--server-context` | no; `--new` only | the server's container DN; used only with `--server` |
| `--catalog` | no; `--new` only | the git package catalog directory: fills the project's own package catalog, and refuses when a package the tree names is missing |
| `--designer` | no; `--new` only | a Designer install to copy driver icons from; default: `IDM_DESIGNER`, then `-Ddesigner`, then the default install roots |
| `--dry-run` | no | report the files it would create, change or delete; write nothing |
| `--json` | no | the result as JSON |

```bash
bin/idm export-project tree ~/designer_workspace/Project --dry-run
```

More: [agent-guide.md](agent-guide.md#hand-it-back-to-designer), [designer-new-project.md](designer-new-project.md).

## Look at a tree

### `check`

Loads the tree and prints a summary: the driver set, the library, and each driver's policy, resource and link counts, then any links that resolve to nothing. Exits 1 when a link resolves to nothing.

```
bin/idm check <asCodeDir>
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |

```bash
bin/idm check tree
```

More: [getting-started.md](getting-started.md#5-look-around-before-you-change-anything).

### `validate`

Runs every validation check over the tree, compiling each policy with the engine's own compilers. Exits 1 on any error. Without the engine jars in `lib/`, the checks that run the engine are skipped and each is reported as an info finding (`check-skipped-no-engine`).

```
bin/idm validate <asCodeDir> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `--json` | no | `{"ok", "counts", "findings"}`; findings by severity (error, warning, info) |

```bash
bin/idm validate tree --json
```

More: [validation.md](validation.md).

### `show`

Prints an artifact's content as the tree holds it. Exits 1 when there is no artifact at the path.

```
bin/idm show <asCodeDir> <artifactPath>
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `<artifactPath>` | yes | an artifact path, with no file extension (`drivers/Active Directory/subscriber/sub-ctp-Transform`) |

```bash
bin/idm show tree "drivers/Active Directory/subscriber/sub-ctp-Transform"
```

More: [getting-started.md](getting-started.md#5-look-around-before-you-change-anything).

### `query`

Reads the tree and answers one question. Writes nothing. `query … events` and `query … event` read the Event Logger's store and have their own entry.

```
bin/idm query <asCodeDir> artifacts [<driver>]
bin/idm query <asCodeDir> chain <driver> sub|pub
bin/idm query <asCodeDir> gcvs [<driver>]
bin/idm query <asCodeDir> tables [<driver>]
bin/idm query <asCodeDir> schema [<driver>]
bin/idm query <asCodeDir> fishbone <driver> [--json]
bin/idm query <asCodeDir> drivers [--json]
```

| Verb | Arguments | What it prints |
|---|---|---|
| `artifacts` | `<driver>` optional; default: every artifact | each artifact's kind and path, marked `[packaged]` or `[packaged, customized]` |
| `chain` | `<driver>` and the channel, both required: `sub` (or `subscriber`) or `pub` (or `publisher`); anything else exits 2 | the channel's policies in execution order, then the driver's ECMAScript, GCV, startup and shutdown resources |
| `gcvs` | `<driver>` optional; default: driver-set scope | every GCV in scope with its value and where it is defined, first definition winning; a server's own differing value beneath it. Password values show as `(password)` |
| `tables` | `<driver>` optional; default: the library's tables only | each mapping table in reach with its row count and columns |
| `schema` | `<driver>` optional | without a driver, the vault schema in `schema/vault.xml` (classes, custom ones marked `*`); with one, the driver's application schema. Exits 1 when the tree has none |
| `fishbone` | `<driver>` required; `--json` optional | the policy-flow fishbone the VS Code viewer draws |
| `drivers` | `--json` optional | each driver's name and directory |

`<driver>` is a driver's name; an unknown name lists the tree's drivers and exits 1. Arguments beyond a verb's own exit 2. An unknown verb exits 2. `--json` applies to `fishbone` and `drivers` only.

```bash
bin/idm query tree chain "Active Directory" sub
```

More: [agent-guide.md](agent-guide.md#orient-first), [schema.md](schema.md).

### `refs`

Everything that references an artifact: driver links, driver-set links, `include`s and `token-map` tables. For a mapping table it also lists the drivers that run a policy reading it. Prints one line per reference and a count. Exits 0, also when there is no artifact at the path.

```
bin/idm refs <asCodeDir> <artifactPath>
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `<artifactPath>` | yes | an artifact path, with no file extension (`library/lib-Shared`) |

```bash
bin/idm refs tree "library/lib-Shared"
```

More: [agent-guide.md](agent-guide.md#orient-first).

### `mapping-table.export-csv`

Prints a mapping table as CSV (RFC 4180, CRLF line ends) on stdout: a header of column names, then one line per row. `mapping-table.import-csv` reads it back. Exits 2 when the path is not a mapping table.

```
bin/idm mapping-table.export-csv <asCodeDir> <path>
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `<path>` | yes | a mapping table's artifact path (`library/LocationCodeMap`) |

```bash
bin/idm mapping-table.export-csv tree library/LocationCodeMap > codes.csv
```

More: [agent-guide.md](agent-guide.md#two-kinds-of-change).

### `package.diff` (a tree's artifact)

A customized packaged artifact compared with its package baseline in the tree, as a line diff (`-` baseline, `+` customized). For an artifact that is not package-managed, or packaged and not customized, it says so. Exits 1 when there is no artifact at the path. With `--catalog` anywhere on the line, the command is the catalog form instead, which has its own entry.

```
bin/idm package.diff <asCodeDir> <artifactPath>
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `<artifactPath>` | yes | an artifact path, with no file extension |

```bash
bin/idm package.diff tree "drivers/Active Directory/subscriber/NOVLADDCFG-sub-ctp-EntitlementsImpl"
```

More: [agent-guide.md](agent-guide.md#packaged-content).

### `docs`

Generates documentation from the model: `README.md`, one page per driver under `drivers/`, `library.md`, and with `--since` a `changes.md`. With `--format html` it also writes a single `index.html`. Prints `wrote <dir>`.

```
bin/idm docs <asCodeDir> --out <dir> [--driver <name>…] [--since <commit>] [--format md|html]
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `--out` | yes | the output directory; created if needed |
| `--driver` | no, repeatable | a driver's name; default: every driver gets a page |
| `--since` | no | a git revision of the tree's repository (`HEAD~5`); `changes.md` lists the changes since it |
| `--format` | no | `md` (default) or `html` (case-insensitive); anything else exits 2 |

```bash
bin/idm docs tree --out docs --since HEAD~5 --format html
```

More: [designer-roundtrip.md](designer-roundtrip.md#1-documentation-from-the-model).

## Test

### `simulate`

Runs the simulator cases through the tree's real policies. With `--against`, it runs each case on another tree too and reports any case whose output changed. With `--env`, the tree's `overrides/<env>.properties` values are applied first (no vault connection). Needs the simulator and the engine jars. Exits 0 when no case fails or errors and, with `--against`, none changed; 1 otherwise; 2 without `--cases`.

```
bin/idm simulate <asCodeDir> --cases <dir> [--against <asCodeDir>] [--env <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<asCodeDir>` | yes | an IDM-as-code tree directory |
| `--cases` | yes | a directory of simulator cases; each names its driver with `driver=` or through its `export=` file |
| `--against` | no | another tree directory (for example a worktree of the previous commit); not a git revision |
| `--env` | no | an overrides name: applies `overrides/<env>.properties` of the tree (and of the `--against` tree); default: base values |
| `--json` | no | the outcome as JSON |

```bash
bin/idm simulate tree --cases cases --against /tmp/tree-before/tree
```

More: [day-to-day.md](day-to-day.md#simulate).

## Two trees and two projects

### `tree.diff`

A structured diff of two IDM-as-code trees, driver by driver. Exits 1 when they differ.

```
bin/idm tree.diff <fromDir> <toDir> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<fromDir>` | yes | an IDM-as-code tree directory |
| `<toDir>` | yes | an IDM-as-code tree directory |
| `--json` | no | the diff as JSON |

```bash
bin/idm tree.diff tree /tmp/tree-before/tree
```

### `tree.compare`

Compares two projects (the target and the source tree) and sorts each difference by the pair's promotion record: pending, the target's own, known, unchanged since the record. Writes nothing. Exits 0 when nothing is open, 1 when something is, 2 on a usage error.

```
bin/idm tree.compare <target> --with <source> [--name <pair>] [--cases <dir>] [--source-cases <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<target>` | yes | the target tree directory |
| `--with` | yes | the source tree directory; `--from` is accepted too |
| `--name` | no | the pair's name: a letter or digit, then letters, digits, `.`, `_`, `-`. Default: the source directory's name, or its parent's when it is named `tree` |
| `--cases` | no | the target's simulator cases; default: `<target>/cases`, or `cases/` beside a directory named `tree`. Cases are compared only when both sides have them |
| `--source-cases` | no | the source's simulator cases; same default, from the source |
| `--json` | no | each item with `path`, `kind`, `driver`, `bucket`, `state`, `fingerprint`, `changedSinceKnown`, and `detail` or `source`/`target` |

```bash
bin/idm tree.compare prod/tree --with qa/tree
```

More: [promotion.md](promotion.md#treecompare).

### `promote.plan`, `promote.apply`

Move chosen differences from the source into the target tree. `promote.plan` does everything but write. `promote.apply` copies the chosen files, keeps the target's values except those named with `--value`, reads the result back and validates it, then writes only the target files whose bytes change and updates `promotions/<pair>.json`. It is refused, writing nothing, when a chosen file would not be part of the target's model (its manifest was not promoted with it) or when the result has new validation errors (unless `--force`). It writes in place on the checked-out branch; committing is yours, and the result gives the commit trailer. Exits 0 when done, 1 when refused, 2 on a usage error.

```
bin/idm promote.plan  <target> --from <source> [--name <pair>] [--object <path>…] [--value <key>…] [--pending] [--contents <file.json>] [--json]
bin/idm promote.apply <target> --from <source> [--name <pair>] [--object <path>…] [--value <key>…] [--pending] [--contents <file.json>] [--force] [--by <who>] [--dry-run] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<target>` | yes | the target tree directory |
| `--from` | yes | the source tree directory; `--with` is accepted too |
| `--name` | no | the pair's name; default: the source directory's name, or its parent's when it is named `tree` |
| `--object` | at least one of `--object`, `--value`, `--pending`, `--contents`; repeatable | a tree path of a file that differs (`drivers/Active Directory/driver.xml`), or a directory: every differing file under it. A driver's directory brings its cases too |
| `--value` | repeatable | an overrides key of a value both sides hold (`drivers/Active Directory.gcv.drv.users`) |
| `--pending` | no | every pending file (never values) |
| `--contents` | no | a JSON file `{"<path>": "<content>"}`: a file as you merged it, used in place of the source's; chooses that file too |
| `--force` | no | write despite new validation errors |
| `--by` | no | who promoted, for the record |
| `--dry-run` | no | `promote.apply` only: the same as `promote.plan` |
| `--cases`, `--source-cases` | no | the two sides' simulator cases; defaults as for `tree.compare` |
| `--json` | no | the result as JSON (`ok`, `refusal`, `promoted`, `values`, `changedFiles`, `deletedFiles`, `newErrors`, `trailer` …) |

```bash
bin/idm promote.apply prod/tree --from qa/tree --pending --by jdoe
```

More: [promotion.md](promotion.md#promoteplan-and-promoteapply).

### `promote.map`

Prints a proposed map for the pair, in the map file's form, and writes nothing. It starts from the pair's own map lines, adds a `drivers/<source name>.name = <target name>` line for each source driver that looks like one only the target has, and maps servers by nearest name when the map names none. Exits 0, 2 on a usage error.

```
bin/idm promote.map <target> --from <source> [--name <pair>]
```

| Argument | Required | Values |
|---|---|---|
| `<target>` | yes | the target tree directory |
| `--from` | yes | the source tree directory; `--with` is accepted too |
| `--name` | no | the pair's name; default as for `tree.compare` |
| `--cases`, `--source-cases` | no | the two sides' simulator cases; defaults as for `tree.compare` |

```bash
bin/idm promote.map prod/tree --from qa/tree
```

More: [promotion.md](promotion.md#promotemap).

### `promote.pair`

Takes the pair's first record: every file and value of both sides as they are now, so that from then on only new changes show. Writes `promotions/<pair>.json`, plus `promotions/<pair>.properties` with `--propose-map` and `promotions/<pair>.known.json` with `--accept`. Refused when the pair already has a record, unless `--force`. Exits 0 when done, 1 when refused, 2 on a usage error.

```
bin/idm promote.pair <target> --from <source> [--name <pair>] [--propose-map] [--pending <path or key>…] [--accept <path or key>…] [--dry-run] [--force] [--by <who>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<target>` | yes | the target tree directory |
| `--from` | yes | the source tree directory; `--with` is accepted too |
| `--name` | no | the pair's name; default as for `tree.compare` |
| `--propose-map` | no | write `promote.map`'s proposal as the map and pair with it |
| `--pending` | no, repeatable | a difference to leave pending (a file path or a value key) |
| `--accept` | no, repeatable | a difference to accept as known, with the decision `intended` |
| `--dry-run` | no | report; write nothing |
| `--force` | no | start an existing record over |
| `--by` | no | who, for the record |
| `--cases`, `--source-cases` | no | the two sides' simulator cases; defaults as for `tree.compare` |
| `--json` | no | the result as JSON |

```bash
bin/idm promote.pair prod/tree --from qa/tree --propose-map --by jdoe
```

More: [promotion.md](promotion.md#promotepair).

### `promote.accept`, `promote.forget`

`promote.accept` records one difference as known, so it is counted rather than listed while neither side changes it. `promote.forget` drops a known difference, so it shows again. Both write `promotions/<pair>.known.json`. Exits 0 when done, 1 when refused (no such difference, or a bad `--decision`), 2 on a usage error (including no `--object` or more than one).

```
bin/idm promote.accept <target> --from <source> [--name <pair>] --object <path or key> [--decision intended|later] [--note <text>] [--by <who>] [--dry-run] [--json]
bin/idm promote.forget <target> (--from <source> | --name <pair>) --object <path or key> [--dry-run] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<target>` | yes | the target tree directory |
| `--from` | yes for `promote.accept`; for `promote.forget`, this or `--name` | the source tree directory; `--with` is accepted too |
| `--name` | no; for `promote.forget`, this or `--from` | the pair's name; default as for `tree.compare` |
| `--cases`, `--source-cases` | no | the two sides' simulator cases; defaults as for `tree.compare` |
| `--object` | yes, exactly once | one file path or value key; for `promote.accept` it must differ now |
| `--decision` | no; `promote.accept` only | `intended` (different on purpose; the default) or `later` (to decide) |
| `--note` | no; `promote.accept` only | free text |
| `--by` | no; `promote.accept` only | who, for the record |
| `--dry-run` | no | report; write nothing |
| `--json` | no | the result as JSON |

```bash
bin/idm promote.accept prod/tree --from qa/tree --object "drivers/Active Directory/subscriber/sub-smp.policy.xml" --note "production maps Surname itself"
```

More: [promotion.md](promotion.md#promoteaccept-and-promoteforget).

## Vault: compare, deploy, schema, clone, events

### Vault writes

Most commands in this group only read. The ones that write to a vault are `vault.deploy` with `--yes` or `--step` (and without `--dry-run`), `vault.rollback --yes`, `vault.import-clone --yes` and `vault.deploy-schema --yes`. Without those flags each of them prints its plan and stops. The CLI checks all four before it resolves a secret or opens LDAP: the write goes ahead only when `IDM_AGENT_ALLOW_WRITE=1` is set or `--confirm <env>` names the target environment. Otherwise it prints `refusing vault write to '<env>'` and exits 1. Tier rules come on top. A `prd`-tier deploy always needs `--confirm <env>`, even with `IDM_AGENT_ALLOW_WRITE=1`. It also needs the tree committed in git with nothing uncommitted, the `--base-branch` branch checked out when one is given, a green deploy to `<env>.requires` when that is set, and a vault that matches the last deploy on record. `vault.deploy-schema` to a `prd` tier needs `--confirm <env>`. `vault.import-clone` never targets a `prd` tier. The operate commands that change a driver, and `driver.query`, are gated the same way ([Operate a running driver set](#operate-a-running-driver-set)); `driver.migrate --dry-run` is not a write. `vault.rollback` to a `prd` tier needs only `--yes` and the agent gate. `vault.schema`, `vault.app-schema`, `vault.export-clone` and `events.case` write files in the tree or on disk, never to the vault.

### `vault.diff`, `vault.verify`

Compares the tree with the live vault, driver by driver, after applying the environment's overrides. `vault.verify` is the same diff, under a name for reading the vault back after a deploy. Exits 0 when they match, 1 when they differ, 2 on a usage error or a refused driver scope.

```
bin/idm vault.diff <tree> --env <name> [--driver <name>…] [--json]
bin/idm vault.verify <tree> --env <name> [--driver <name>…] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | no, repeatable | a driver's name as the tree names it; default: every driver |
| `--json` | no | the diff as JSON |

```bash
bin/idm vault.diff tree --env test --driver "Active Directory"
```

More: [vault-deploy.md](vault-deploy.md#the-diff).

### `vault.compare-envs`

Reads two environments' vaults and compares them: drivers only one has, values that differ, other drift. Shim auth ids that either environment's secrets file supplies are left out, so no credential is shown. With a tree, drivers are given the tree's names. Exits 0 when the vaults match, 1 when they differ.

```
bin/idm vault.compare-envs [<tree>] --env <name> --with <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | no | an IDM-as-code tree directory; supplies driver names |
| `--env` | yes | the first environment |
| `--with` | yes | the second environment |
| `--json` | no | the comparison as JSON |

```bash
bin/idm vault.compare-envs tree --env qa --with prod
```

More: [promotion.md](promotion.md).

### `vault.deploy`

Deploys the tree to a vault in this order: validate, diff, plan, snapshot, write, restart, verify, audit line. The tree must validate (within the deploy's driver scope). Without `--yes` or `--step`, it prints the plan and refuses to write. Writes to the vault: see [Vault writes](#vault-writes). The snapshot goes to `deploy-snapshots/<env>/<time>.ldif` (with a `.json`) and the audit line to `deploy-log/<env>.jsonl`, both in the tree. A driver is never deleted without `--delete-driver`. A deploy never empties a kind of object on a driver without `--delete-all`. Exits 0 on success or when there is nothing to deploy, 1 on a refusal or a failure, 2 on a usage error.

```
bin/idm vault.deploy <tree> --env <name> [--driver <name>…] --dry-run|--yes|--step [--confirm <name>]
        [--no-restart] [--secrets none|missing|all] [--allow-missing-secrets]
        [--capture-drift] [--capture-only] [--base-branch <branch>]
        [--driver-server <driver>=<serverDn>…] [--delete-driver <name>…] [--delete-all <kind>…] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | no, repeatable | a driver's name as the tree names it; default: every driver |
| `--dry-run` | one of these to write | plan only; never writes. Against a `prd` tier it also reports what the production checks would refuse (`gate`, `gateKind`: `not-in-git`, `uncommitted`, `branch`, `requires`, `unknown-state`, `drift`) |
| `--yes` | one of these to write | deploy the whole plan after one snapshot |
| `--step` | one of these to write | interactive: per change, show it, ask `apply? [y/n/q]`, write it and verify it. Cannot be combined with `--json` |
| `--confirm` | for a `prd` tier, or for the agent gate | the environment's name, typed out |
| `--no-restart` | no | do not restart the drivers the plan changes; default: restart running drivers and wait up to 180 s for Running |
| `--secrets` | no | `none` (default: secrets set only on new drivers), `missing` (also named passwords the live driver lacks), `all` (every secret the secrets file has). Anything else exits 2 |
| `--allow-missing-secrets` | no | create a driver even when a required secret is missing from the secrets file; default: refuse |
| `--capture-drift` | no | when a `prd` vault differs from the last deploy on record, record its state as a commit on an `as-found/<env>/<time>` branch. The deploy is still refused: deploy again on top of it |
| `--capture-only` | no | read the vault and capture its state only (`captured`, `verified` or `known`); no plan, no write, no `--yes` or `--confirm` needed. The tree must be in git |
| `--base-branch` | no | a branch name; a `prd` deploy is refused unless the tree is on it |
| `--driver-server` | no, repeatable | `<driver>=<serverDn>`: the server a driver new to this environment runs on, when it is not the server the deploy connects through |
| `--delete-driver` | no, repeatable | a driver in the vault but not in the tree, to delete with its subtree. It must be stopped; refused when it is still in the tree or not in the vault |
| `--delete-all` | no, repeatable (one kind each) | allow deletes the mass-deletion guard would hold back for a kind: `entitlements`, `jobs`, `rbe-policies`, `templates`, `forms`, `prds`, `entities`, `choices`, `relationships`, `dm-configs`, `roles`, `resources`, `attestations`, `sods`, `role-configs`, `reports`, `nav-items`, `auth-types`, `web-app-configs`, `containers`, `others`. Another value is refused |
| `--json` | no | the result as JSON (plan, diff, snapshot, done, skipped, restarted, refusal, gate) |

```bash
bin/idm vault.deploy tree --env test --driver "Active Directory" --dry-run
IDM_AGENT_ALLOW_WRITE=1 bin/idm vault.deploy tree --env test --driver "Active Directory" --yes
bin/idm vault.deploy tree --env prod --yes --confirm prod --base-branch main
```

More: [vault-deploy.md](vault-deploy.md#the-plan-and-the-deploy).

### `vault.rollback`

Restores a deploy's snapshot. Objects that existed get their captured attributes back, objects recorded as absent are deleted, and objects the deploy deleted are added again. The drivers' states are restored too. First it takes a snapshot of the current state, so a rollback can itself be rolled back. Afterwards it checks that the vault matches the snapshot and appends a `rollback` line to `deploy-log/<env>.jsonl`. Without `--yes` it prints the snapshot's contents and stops. Writes to the vault: see [Vault writes](#vault-writes). Exits 0 when the vault matches the snapshot afterwards, 1 otherwise.

```
bin/idm vault.rollback [<tree>] --env <name> --snapshot <file.ldif> [--yes] [--confirm <name>] [--server <serverDn>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | no | the tree the new snapshot and the audit line are written to; default: the current directory |
| `--env` | yes | an environment in `environments.properties` |
| `--snapshot` | yes | a snapshot file, `deploy-snapshots/<env>/<time>.ldif` |
| `--yes` | to write | restore the snapshot |
| `--confirm` | for the agent gate | the environment's name (or set `IDM_AGENT_ALLOW_WRITE=1`) |
| `--server` | no | a server DN: restore a per-server snapshot through that server's own connection. Its URL comes from the tree or `<env>.servers=<serverDn>=<url>` |
| `--json` | no | the result as JSON |

```bash
bin/idm vault.rollback tree --env test --snapshot tree/deploy-snapshots/test/2026-10-28T09-12-00Z.ldif --yes --confirm test
```

More: [vault-deploy.md](vault-deploy.md#rollback).

### `vault.schema`

Reads the vault's `cn=schema` and writes it into the tree as `schema/vault.xml`. Custom attributes and classes that the tree defines and the vault does not yet have are kept. Reads the vault and writes the tree only. Exits 0 on success, 1 when `cn=schema` is not readable or the write is refused.

```
bin/idm vault.schema <tree> --env <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--env` | yes | an environment in `environments.properties` |
| `--json` | no | the result as JSON (source, attributes, classes, customKept, file) |

```bash
bin/idm vault.schema tree --env test
```

More: [schema.md](schema.md#the-vaults-schema-in-the-tree--schemavaultxml).

### `vault.app-schema`

Asks the engine for a driver's application schema, which the engine gets from the shim. Writes it into the tree as `drivers/<driver>/app-schema.xml`. The driver must be stopped: the engine asks the shim only then, and starts the shim for the question. If the engine refuses to ask, the last schema it stored is used. Writes the tree, not the vault. Exits 0 on success, 1 when the driver is running or no schema comes back.

```
bin/idm vault.app-schema <tree> --env <name> --driver <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name in the tree (the first one given is used) |
| `--json` | no | the result as JSON (driver, classes, attributes, file, how) |

```bash
bin/idm driver.stop --env test --driver "Active Directory" --yes --confirm test
bin/idm vault.app-schema tree --env test --driver "Active Directory"
```

More: [schema.md](schema.md#the-applications-schema--driversdriverapp-schemaxml).

### `vault.deploy-schema`

Writes the tree's custom attribute and class definitions that the vault lacks into `cn=schema`. Attributes go first, then classes in superclass order. Every custom definition needs an OID. Without `--yes` it prints the plan. Writes to the vault with `--yes`, which needs `IDM_AGENT_ALLOW_WRITE=1` or `--confirm <env>` on any tier ([Vault writes](#vault-writes)); a `prd` tier needs `--confirm <env>` in any case. Exits 0 on success, 1 on a refusal or a failed write.

```
bin/idm vault.deploy-schema <tree> --env <name> [--yes] [--confirm <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory with `schema/vault.xml` |
| `--env` | yes | an environment in `environments.properties` |
| `--yes` | to write | add the planned definitions; default: plan only |
| `--confirm` | with `--yes`, unless `IDM_AGENT_ALLOW_WRITE=1`; always on a `prd` tier | the environment's name |
| `--json` | no | the result as JSON (environment, toAdd, done, skipped, failures) |

```bash
bin/idm vault.deploy-schema tree --env test
bin/idm vault.deploy-schema tree --env test --yes --confirm test
```

More: [schema.md](schema.md#schema-you-define--and-push).

### `schema.diff`

Compares the tree's `schema/vault.xml` with an environment's live `cn=schema`, or compares two environments' live schemas. Definitions match by NDS name, else by LDAP name. Writes nothing. Exits 0 when they are in sync, 1 when they differ.

```
bin/idm schema.diff <tree> --env <name> [--json]
bin/idm schema.diff --env <name> --other <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | one of these | an IDM-as-code tree directory: compare its schema with `--env` |
| `--other` | one of these | a second environment: compare the two live schemas (a `<tree>` given as well is ignored) |
| `--env` | yes | an environment in `environments.properties` |
| `--json` | no | the result as JSON |

```bash
bin/idm schema.diff tree --env test
bin/idm schema.diff --env qa --other prod
```

More: [schema.md](schema.md#comparing--schemadiff).

### `events.describe`

Describes the environment's Event Logger store: URL, table, row count, newest time, the lowest and highest schema version, whether the table is migrated, and whether rows are pseudonymised. The environment needs `<env>.eventsUrl` (with `eventsUser` and an events password). The connection is read-only. Exits 0, or 2 on a usage error.

```
bin/idm events.describe <tree> --env <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--env` | yes | an environment with an event store |
| `--json` | no | the description as JSON |

```bash
bin/idm events.describe tree --env test
```

More: [event-store.md](event-store.md#2-environment-settings).

### `query <tree> events`, `query <tree> event`

Reads the event store. `events` lists rows matching the selectors, which combine with AND. The default order is newest first. With `--dn` the object's timeline is in time order. With `--event-id` the driver's row comes first, then each policy stage. `event` shows one row with its attribute changes, its XML or JSON, and the other rows of the same engine event. When the environment sets `eventsPseudonymise=true`, rows are masked. DNs are accepted in LDAP form (`cn=jdoe,ou=users,o=data`) or the store's slash form (`\TREE\data\users\jdoe`). LDAP DNs are converted using `eventsTree`, or the tree name taken from the store. Exits 0. `event` exits 1 when there is no such row.

```
bin/idm query <tree> events --env <name> [--dn <dn> | --under <dn> | --name <rdn>] [--driver <name>]
        [--policy <name> [--stage input|output]] [--own | --logged] [--type <type>,…] [--class <class>]
        [--since <time>] [--until <time>] [--event-id <id>] [--text <s>] [--attr <name>] [--limit <n>] [--json] [--xml]
bin/idm query <tree> event <id> --env <name> [--json] [--xml]
```

| Argument | Verbs | Required | Values |
|---|---|---|---|
| `<tree>` | both | yes | an IDM-as-code tree directory (read, not otherwise used) |
| `--env` | both | yes | an environment with an event store |
| `<id>` | `event` | yes | a row's numeric id |
| `--dn` | `events` | no | one object's timeline (LDAP or slash DN) |
| `--under` | `events` | no | everything below a container (LDAP or slash DN) |
| `--name` | `events` | no | the end of a source DN (an RDN or tail) |
| `--driver` | `events` | no | a driver's name, matched case-insensitively against the store's driver DNs; or a slash DN starting with `\` |
| `--policy` | `events` | no | rows a PolicyLogger wrote at that policy |
| `--stage` | `events` | no | `input` or `output`; anything else exits 2 |
| `--own` | `events` | no | only the driver's own rows (no policy) |
| `--logged` | `events` | no | only rows its policies logged; `--own` wins if both are given |
| `--type` | `events` | no | comma-separated: `add`, `modify`, `delete`, `sync`, `rename`, `move`; anything else exits 2 |
| `--class` | `events` | no | an object class, such as `User` |
| `--since`, `--until` | `events` | no | `30s`, `30m`, `24h` or `7d` ago; an ISO instant (`2026-10-28T09:00:00Z`); or a date (`2026-10-28`, local midnight). `--until` is exclusive; anything else exits 2 |
| `--event-id` | `events` | no | an engine event id: every row of that event |
| `--text` | `events` | no | free text over the row's JSON. On a store over 100,000 rows it is refused unless `--dn`, `--under`, `--name`, `--driver`, `--event-id`, `--since` or `--until` is given too |
| `--attr` | `events` | no | modify events that touched this attribute |
| `--limit` | `events` | no | rows to return; default 100, at most 1000 |
| `--json` | both | no | the rows as JSON |
| `--xml` | both | no | include the stored XML in JSON; for `event`, print the XML instead of the JSON |

```bash
bin/idm query tree events --env test --under "ou=users,o=data" --type add,modify --since 24h
bin/idm query tree event 4711 --env test --xml
```

More: [event-store.md](event-store.md#3-queries).

### `events.case`

Turns one row of the event store into a simulator case. It writes `case.properties` (driver, channel, source, policy and stage, time) and `input.xds` to `<tree>/cases/<name>/`. When the row has no stored XML, `input.xds` is rebuilt from the JSON. That needs the Event Logger jar on the class path and a schema version 2 row. Pseudonymised rows are masked and marked `pseudonymised=true`. Writes files, never the vault. Exits 0 when written (or planned), 1 on a refusal or a missing row, 2 on a usage error.

```
bin/idm events.case <tree> --env <name> --id <n> --name <case> [--driver <name>] [--channel subscriber|publisher]
        [--dir <dir>] [--replace] [--dry-run] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--env` | yes | an environment with an event store |
| `--id` | yes | the row's numeric id |
| `--name` | yes | the case's name: up to 80 characters, letters, digits, `.`, `_`, `-` and spaces, starting with a letter or digit |
| `--driver` | no | a driver in the tree; default: the row's driver, which must be in the tree |
| `--channel` | no | `subscriber` or `publisher`; default: the row's channel, else `subscriber` |
| `--dir` | no | the directory that holds cases; default: `<tree>/cases` |
| `--replace` | no | overwrite a case that exists; default: refuse |
| `--dry-run` | no | report what would be written; write nothing |
| `--json` | no | the plan as JSON, with `written` |

```bash
bin/idm events.case tree --env test --id 4711 --name "user-add-sample" --driver "Active Directory"
```

More: [event-store.md](event-store.md#4-eventscase).

### `vault.export-clone`

Reads a vault into a clone bundle: a directory of ordered LDIF files (schema, containers, objects, per-server values, references, ACLs), a manifest and a list of the secrets the target needs. Passwords and secrets never enter the bundle. Every driver's start option becomes manual. Other servers in the driver set are read through their own connections, at the URL the tree describes or at `<env>.servers=<serverDn>=<url>;…`. Reads the vault, writes the bundle. Exits 0.

```
bin/idm vault.export-clone --env <name> --out <dir> [--rbs] [--keep-driver-state] [--data <container>[,…]…] [--pseudonymise]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | the source environment |
| `--out` | yes | the bundle directory to write |
| `--rbs` | no | include the iManager RBS collection; default: skipped |
| `--keep-driver-state` | no | carry `DirXML-DriverStorage` and `DirXML-PersistentData`; default: excluded |
| `--data` | no, repeatable | container DNs, comma-separated: clone the people, groups, aliases and roles under them; default: configuration only |
| `--pseudonymise` | no | replace given name, surname, full name, display name and the local part of mail with consistent fakes in the bundle; `--pseudonymize` is accepted too |

```bash
bin/idm vault.export-clone --env qa --out clone/qa-2026-10-28 --data o=data --pseudonymise
```

More: [vault-clone.md](vault-clone.md#3-shape-two-commands-and-an-inspectable-bundle).

### `vault.import-clone`

Creates a clone bundle's contents in a lab tree. It computes the schema delta and the plan, snapshots the touched entries to `<from>/imports/`, writes everything in order, then reads the target back and compares every cloned entry. Without `--yes` it plans and stops. Writes to the vault with `--yes`: see [Vault writes](#vault-writes). It never targets a `prd` tier. An entry that exists is skipped unless `--replace` is given. Exits 0 on success, 1 on a refusal or a failure, 2 on a usage error (for example several target servers and no `--server`).

```
bin/idm vault.import-clone --env <name> --from <dir> [--server <dn>] [--map <srcDn>=<dstDn>…]
        [--driver-server <driver>=<srcServerDn>…] [--user-password <key>] [--replace] [--replace-driverset]
        [--yes] [--confirm <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | the target (lab) environment; not a `prd` tier |
| `--from` | yes | a bundle directory written by `vault.export-clone` |
| `--server` | no | the lab server DN every source server maps to (merged mode); default: the target's only server; required when it has several |
| `--map` | no, repeatable | `<sourceDn>=<targetDn>`: rewrite a DN. Map every source server to a lab server for one-to-one mode |
| `--driver-server` | no, repeatable | `<driver>=<sourceServerDn>`: the source server whose server-specific values a driver takes in merged mode; default: the server it ran on |
| `--user-password` | no | a key in the target environment's secrets file (`<env>.secrets`): one lab password for every cloned person |
| `--replace` | no | delete and recreate leaf entries that exist; default: skip them |
| `--replace-driverset` | no | allow an existing driver set with the same DN to be replaced |
| `--yes` | to write | write the plan; default: plan only |
| `--confirm` | for the agent gate | the environment's name (or set `IDM_AGENT_ALLOW_WRITE=1`) |
| `--json` | no | the result as JSON |

```bash
bin/idm vault.import-clone --env test --from clone/qa-2026-10-28 --server cn=server1,ou=servers,o=system
bin/idm vault.import-clone --env test --from clone/qa-2026-10-28 --server cn=server1,ou=servers,o=system --yes --confirm test
```

More: [vault-clone.md](vault-clone.md#9-several-servers-in-a-driver-set-added-2026-09-21-jerrys-requirement).

## Forms, workflows, AppConfig and entitlements: reading them

### `form.list`

Every JSON form in the tree: kind, driver/name, title, number of input fields, and a `[packaged]` mark. Prints the count at the end.

```
bin/idm form.list <tree> [--driver <name>]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--driver` | no | a driver's name; default: every driver. An unknown name exits 1 |

```bash
bin/idm form.list tree --driver "User Application"
```

More: [forms.md](forms.md#3-tree-layout-and-model).

### `form.show`

A form's outline (title, display, languages, scripts, each input field with type, label, required, hidden and conditional) and the PRD bindings that use it. Exits 1 when no form matches.

```
bin/idm form.show <tree> <form> [--driver <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<form>` | yes | a form's name, `<kind>/<name>`, or `<driver>/<kind>/<name>`; kind is `request`, `approval` or `template`. The first match wins |
| `--driver` | no | a driver's name; default: every driver |
| `--json` | no | the outline, every component and the bindings as JSON |

```bash
bin/idm form.show tree request/NewHire --json
```

More: [forms.md](forms.md#3-tree-layout-and-model).

### `form.edit`

Opens a form in the vendor form builder (from a Designer install). When you save and close the builder, the new document is stored in the tree as `form.set-content` does (packaged forms baselined and marked customized, PRD bindings re-synced) and the result printed. Writes to the tree, not the vault. Needs the form builder: found through `IDM_FORMBUILDER`, the `formbuilder` system property (`-Dformbuilder=<path>` in `IDM_JAVA_OPTS`), or Designer's plugin folders. Exits 1 when the builder is missing, the form is not found (or is on several drivers), or the store fails validation.

```
bin/idm form.edit <tree> <form> [--driver <name>] [--env <name>] [--locale <ll_CC>] [--no-wait] [--dry-run] [--force] [--json]
bin/idm form.edit <tree> [<form>] --check
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<form>` | yes, except with `--check` | a form's name, `<kind>/<name>` or `<driver>/<kind>/<name>`; kind is `request`, `approval` or `template` |
| `--driver` | no | a driver's name; needed when the form name is on several drivers |
| `--env` | no | an environment in `environments.properties` with `<env>.formsUrl` set; turns on the builder's online features. Exits 1 when `formsUrl` is missing |
| `--locale` | no | the builder's locale as `ll_CC` (for example `en_US`); default: the system locale |
| `--no-wait` | no | start the builder on the tree's own file and return at once; run `form.sync` after saving |
| `--check` | no | only report which builder would be used and any one-time fixes; exits 0 when ready, 1 when not |
| `--dry-run` | no | show what storing the saved form would change, write nothing |
| `--force` | no | store the form even when it adds validation errors |
| `--json` | no | the store result as JSON |

```bash
bin/idm form.edit tree request/NewHire --driver "User Application"
```

More: [forms.md](forms.md#option-a--the-vendor-form-builder-as-an-external-editor-human-path).

### `form.preview`

Writes a self-contained HTML page that renders the form with the open-source Form.io renderer (NetIQ components shown as placeholders) next to an outline of its fields. Open it in a browser. Exits 1 when the form is not found or is on several drivers.

```
bin/idm form.preview <tree> <form> [--driver <name>] [--out <file>] [--lang <code>]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<form>` | yes | a form's name, `<kind>/<name>` or `<driver>/<kind>/<name>`; must come before the flags |
| `--driver` | no | a driver's name; needed when the form name is on several drivers |
| `--out` | no | the HTML file to write; default `<form name>.preview.html` in the current directory |
| `--lang` | no | the language the form is rendered in; default `en` |

```bash
bin/idm form.preview tree request/NewHire --out new-hire.html --lang de
```

More: [forms.md](forms.md#option-c--preview-without-the-identity-applications).

### `prd.list`

Every PRD in the tree: status, category key, `json-forms` or `classic`, and the forms it binds. Prints the count at the end.

```
bin/idm prd.list <tree> [--driver <name>]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--driver` | no | a driver's name; default: every driver. An unknown name exits 1 |

```bash
bin/idm prd.list tree
```

More: [forms.md](forms.md#3-tree-layout-and-model).

### `prd.show`

A PRD's properties, its form bindings (request or activity, form, mapped fields) and its activity ids. Exits 1 when no PRD has that name.

```
bin/idm prd.show <tree> <prd> [--driver <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<prd>` | yes | the PRD's name; the first driver that has it wins |
| `--driver` | no | a driver's name; default: every driver |
| `--json` | no | properties, bindings and activities as JSON |

```bash
bin/idm prd.show tree "Request Access" --driver "User Application"
```

More: [forms.md](forms.md#3-tree-layout-and-model).

### `prd.flow`

Renders a PRD's workflow: a text walk of the activities from the start activity (unreachable ones listed after), or a Mermaid flowchart. Exits 1 when the PRD is missing or has no `<process>`.

```
bin/idm prd.flow <tree> <prd> [--driver <name>] [--format text|mermaid] [--lang <code>] [--out <file>]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<prd>` | yes | the PRD's name |
| `--driver` | no | a driver's name; default: every driver |
| `--format` | no | `text` (default) or `mermaid`; anything else exits 2 |
| `--lang` | no | the language for activity display names; default `en` |
| `--out` | no | a file to write instead of stdout |

```bash
bin/idm prd.flow tree "Request Access" --format mermaid --out request-access.mmd
```

More: [workflows.md](workflows.md).

### `appconfig.list`

The AppConfig objects in the tree (entities, roles, resources, reports, nav items …): kind, driver/path, display name, and a `[packaged]` mark. Containers are hidden unless `--containers` or `--kind` is given.

```
bin/idm appconfig.list <tree> [--driver <name>] [--kind <kind>] [--containers] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--driver` | no | a driver's name; default: every driver. An unknown name exits 1 |
| `--kind` | no | one of `entity`, `choice`, `relationship`, `dm-config`, `role`, `resource`, `attestation`, `sod`, `role-config`, `report`, `nav-item`, `auth-type`, `web-app-config`, `prd`, `form`, `container`, `other`; anything else exits 2 |
| `--containers` | no | include container objects |
| `--json` | no | one row per object (driver, kind, path, class, display, packaged) as JSON |

```bash
bin/idm appconfig.list tree --kind role
```

More: [appconfig.md](appconfig.md#6-a1--built-2026-09-21).

### `appconfig.show`

One AppConfig object: its classes, display name, DN, package, and every attribute (localized strings split per language; long values shortened). Exits 1 when no object matches, the name is ambiguous, or `--attr` names an attribute the object lacks.

```
bin/idm appconfig.show <tree> <object> [--driver <name>] [--attr <attribute>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `<object>` | yes | the object's path under AppConfig (for example `UIConfig/NavItems/AccessRptTool`), or its name when that is unique |
| `--driver` | no | a driver's name; default: every driver |
| `--attr` | no | print only this attribute's values, one per line, in full |
| `--json` | no | classes, attributes and meta as JSON |

```bash
bin/idm appconfig.show tree UIConfig/NavItems/AccessRptTool --attr nrfLocalizedNames
```

More: [appconfig.md](appconfig.md#6-a1--built-2026-09-21).

### `category.list`

The role or resource category keys (`DirectoryModel/ChoiceDefs/roles-category` or `resources-category`), each with its label and how many roles or resources use it. Exits 1 when the tree has no such category list.

```
bin/idm category.list <tree> --for role|resource [--driver <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--for` | yes | `role` or `resource` (also `roles`, `resources`; any case); missing or other values exit 2 |
| `--driver` | no | a driver's name; default: every driver |
| `--json` | no | rows of driver, key, label, labels per language, used |

```bash
bin/idm category.list tree --for role
```

More: [appconfig.md](appconfig.md#9-b--typed-operations-built-2026-09-22).

### `entitlement.list`

Every entitlement in the tree: driver/name, conflict resolution, multi-valued, display name, and a `[packaged]` or `[packaged, customized]` mark. Prints the count at the end.

```
bin/idm entitlement.list <tree> [--driver <name>]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--driver` | no | a driver's name; default: every driver. An unknown name exits 1 |

```bash
bin/idm entitlement.list tree --driver "Active Directory"
```

More: [entitlements.md](entitlements.md#2-design).

### `entitlement.show`

One entitlement: display name, description, conflict resolution, multi-valued, its document, and the PRD provisioning activities that reference it. Exits 1 when it is not found or the name is on several drivers and `--driver` is not given.

```
bin/idm entitlement.show <tree> --name <name> [--driver <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--name` | yes | the entitlement's name; missing exits 2 |
| `--driver` | no | a driver's name; needed when several drivers have an entitlement of that name |
| `--json` | no | the fields, the document, packaged/customized and `referencedByPrds` as JSON |

```bash
bin/idm entitlement.show tree --driver "Active Directory" --name Group
```

More: [entitlements.md](entitlements.md#2-design).

## The package catalog

### `package.fetch`

Downloads packages from an update site into the catalog: reads the site, verifies each jar parses and its checksums recompute, unpacks it and updates `catalog.json`. Nothing is removed. Needs network access. Writes only to the catalog directory (created when missing). Exits 1 when the site cannot be read or any package is refused or not found.

```
bin/idm package.fetch --catalog <dir> --short <SHORT[_ver]>… [--site <name|url>] [--all-versions] [--dry-run] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; missing exits 2 |
| `--short` | yes, repeatable | a package's short name, optionally `_<version>` to pin one; default: the newest. One package per flag. Missing exits 1 |
| `--site` | no | a site name from the catalog's `sites.properties`, or a URL starting with `http`; default: the vendor's public 2.0 update site. An unknown name exits 2 |
| `--all-versions` | no | fetch every version of each unpinned `--short` |
| `--dry-run` | no | list what would be fetched, download nothing |
| `--json` | no | `added`, `skipped`, `refused` and `error` as JSON |

```bash
bin/idm package.fetch --catalog catalog --short NOVLADENTB --short NOVLACOMSET_2.1.0 --dry-run
```

More: [packages.md](packages.md#31-catalog).

### `package.import`

Adds local package jars to the catalog: one jar, or every `.jar` directly inside a directory (for example a Designer install's `packages/eclipse/plugins`). Each is verified and unpacked as `package.fetch` does. Writes only to the catalog directory. Exits 1 when any jar is refused.

```
bin/idm package.import --catalog <dir> <jar|dir> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory (created when missing); missing exits 2 |
| `<jar|dir>` | yes | a package jar, or a directory of jars (not searched recursively) |
| `--json` | no | one result per jar (short, version, added, refusal) |

```bash
bin/idm package.import --catalog catalog ~/designer/packages/eclipse/plugins
```

More: [packages.md](packages.md#31-catalog).

### `package.list`

The packages in the catalog, sorted by short name: type, base mark, and the versions held.

```
bin/idm package.list --catalog <dir> [--driver-type <id>] [--type 2|3|4] [--base] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; missing exits 2 |
| `--driver-type` | no | only packages whose supported drivers include this id (the supported driver's `id` or `driverId`) |
| `--type` | no | `2` driver, `3` driver set, `4` identity vault; a non-number exits 2 |
| `--base` | no | only base packages |
| `--json` | no | short, id, type, base and versions per package as JSON |

```bash
bin/idm package.list --catalog catalog --type 2 --base
```

More: [packages.md](packages.md#31-catalog).

### `package.show`

One package version from the catalog: id, symbolic name, type, base, versions held, supported drivers, mandatory and optional features, dependencies, prompts, objects with placement and weight, and any AppConfig objects. Exits 1 when the package or version is not in the catalog.

```
bin/idm package.show --catalog <dir> <SHORT[_ver]> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; missing exits 2 |
| `<SHORT[_ver]>` | yes | a package's short name, optionally `_<version>`; default: the newest version in the catalog |
| `--json` | no | the same as JSON |

```bash
bin/idm package.show --catalog catalog NOVLADENTB_2.6.0
```

More: [packages.md](packages.md#31-catalog).

### `package.diff` (two catalog versions)

Two package versions in the catalog compared object by object: added, removed and changed objects, directive changes, and driver attribute changes. Exits 1 when they differ or either is not in the catalog. Without `--catalog`, `package.diff <tree> <artifactPath>` compares a customized packaged artifact with its baseline: [the tree form](#packagediff-a-trees-artifact).

```
bin/idm package.diff --catalog <dir> <SHORT_v1> <SHORT_v2> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; its presence selects this form |
| `<SHORT_v1>` | yes | the first package, `SHORT_<version>`; without a version, the newest |
| `<SHORT_v2>` | yes | the second package, the same form |
| `--json` | no | the difference as JSON |

```bash
bin/idm package.diff --catalog catalog NOVLADENTB_2.5.0 NOVLADENTB_2.6.0
```

More: [packages.md](packages.md#31-catalog).

### `package.resolve`

The dependency closure for installing a base package (and features) from the catalog: the ordered install list, each with the reason it was chosen, plus dependencies left to the driver set or vault that are missing. Exits 1 when the resolver refuses.

```
bin/idm package.resolve --catalog <dir> --base <SHORT[_ver]> [--feature <SHORT>…] [--driver-set-has <SHORT_ver>…] [--vault-has <SHORT_ver>…] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; missing exits 2 |
| `--base` | yes | the base package's short name, optionally `_<version>`; missing exits 2 |
| `--feature` | no, repeatable | a feature package's short name, one per flag |
| `--driver-set-has` | no, repeatable | a package already on the driver set, as `SHORT_<version>` |
| `--vault-has` | no, repeatable | a package already in the vault, as `SHORT_<version>` |
| `--json` | no | `refusal`, `install` and `missing` as JSON |

```bash
bin/idm package.resolve --catalog catalog --base NOVLADENTB --feature NOVLADPWDSYNC --driver-set-has NOVLACOMSET_2.1.0
```

More: [packages.md](packages.md#31-catalog).

### `package.build`

Builds a package jar from a driver's (or the Library's) non-packaged artifacts and adds it to the catalog. The driver's own GCV definitions that the content reads travel as a GCV object. Refuses packaged objects unless `--customized keep`. Writes only to the catalog. Exits 1 on a refusal.

```
bin/idm package.build --catalog <dir> <tree> --short <SHORT> --name <display name> [--driver <name>] [--vendor <v>] [--version <M.m.r>] [--description <text>] [--readme <file>] [--include <path>…] [--new-version-of <SHORT_ver|jar>] [--depends <SHORT[_ver]>…] [--supported-driver <id>…] [--gcvs referenced|all|none] [--customized keep] [--base] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; missing exits 2 |
| `<tree>` | yes | an IDM-as-code tree directory |
| `--short` | yes | 4–12 uppercase letters or digits (for example `PBTCUSTAD`); missing exits 2, other forms are refused |
| `--name` | yes | the package's display name; missing exits 2 |
| `--driver` | no | the driver whose artifacts to package; default: the Library (a driver-set package). An unknown name is refused |
| `--vendor` | no | the vendor; default `pointbluetech`; also forms the symbolic name |
| `--version` | no | `M.m.r`; default `1.0.0`; the build time (`yyyyMMddHHmmss`) is appended |
| `--description` | no | the package description; default empty |
| `--readme` | no | a file whose text becomes the package readme |
| `--include` | no, repeatable | an artifact path to include; default: every non-packaged artifact of the target |
| `--new-version-of` | no | a previous version, as `SHORT_<version>` from the catalog or a `.jar` path; reuses its package id and object ids |
| `--depends` | no, repeatable | an installed package to declare as a dependency, `SHORT` or `SHORT_<version>` |
| `--supported-driver` | no, repeatable | a driver type id (for example `EDIR-Driver`); default: the base package's list, else the driver's type |
| `--gcvs` | no | `referenced` (default), `all` or `none`: which driver-level GCV definitions to ship |
| `--customized` | no | `keep`: leave packaged (customized or included) objects out as customizations of their own packages; default: refuse |
| `--base` | no | build a base package; default: a feature package |
| `--json` | no | the build result as JSON |

```bash
bin/idm package.build --catalog catalog tree --driver "Active Directory" --short PBTCUSTAD --name "Custom AD policies" --version 1.0.0 --customized keep
```

More: [packages.md](packages.md#35-building-packages-from-customized-configuration).

### `package.site`

Renders the catalog as an Eclipse update site (feature and plugin jars plus `site.xml`) that Designer can read, for example over a web server or `file://`.

```
bin/idm package.site --catalog <dir> --out <dir> [--description <text>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--catalog` | yes | the catalog directory; missing exits 2 |
| `--out` | yes | the directory to write the site into; missing exits 2 |
| `--description` | no | the site's description; default `Packages built with DirXMLDev` |
| `--json` | no | `out` and the features written as JSON |

```bash
bin/idm package.site --catalog catalog --out site --description "Custom packages"
```

More: [packages.md](packages.md#36-serving-the-catalog).

### `package.status`

Per driver: the installed packages (from the manifest, cross-checked against object stamps), customized packaged objects, and with a catalog the newer versions available and whether each installed version is in the catalog. `--strict` exits 1 on a customized packaged object, or (with `--catalog`) an installed version the catalog lacks.

```
bin/idm package.status <tree> [--driver <name>] [--catalog <dir>] [--strict] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | an IDM-as-code tree directory |
| `--driver` | no | a driver's name; default: every driver |
| `--catalog` | no | the catalog directory (created when missing); adds upgrades and catalog presence |
| `--strict` | no | exit 1 on any violation; lines prefixed `STRICT:` (in JSON, a `strict` object) |
| `--json` | no | the status as JSON |

```bash
bin/idm package.status tree --catalog catalog --strict
```

More: [packages.md](packages.md#51-auditable-staging-no-customised-released-packages).

## Operate a running driver set

### `driverset.status`

Every driver of the set in one table: state, start option, cache size. Read-only.

```
bin/idm driverset.status --env <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--json` | no | the result as JSON |

```bash
bin/idm driverset.status --env test
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.status`

One driver: state, start option, cache size, trace level and file, named-password names, and its last five audit lines from the deploy log. Read-only. Exits 1 when the driver does not exist.

```
bin/idm driver.status --env <name> --driver <name> [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--tree` | no | the directory whose `deploy-log/<env>.jsonl` holds the audit lines; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.status --env test --driver "Active Directory"
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.start`, `driver.stop`, `driver.restart`

Starts, stops or restarts a driver, then waits for it to reach the running (or stopped) state and reports the states it saw. Writes to the vault: needs `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`. Start and restart also need `--yes` on a `stg` or `prd` tier; stop needs `--yes` on every tier. A `prd` tier needs `--confirm <env>` in any case. Audited in `deploy-log/<env>.jsonl`. Exits 1 when refused or failed.

```
bin/idm driver.start|driver.stop|driver.restart --env <name> --driver <name> [--wait <seconds>] [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--wait` | no | seconds to wait for the new state; default: 180 |
| `--yes` | by tier | see above |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | where the audit line goes (`deploy-log/`); default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.restart --env test --driver "Active Directory" --yes --confirm test
```

More: [operate.md](operate.md#safeguards).

### `driver.start-option`

Sets a driver's start option live (`SetDriverStartOption`) and reads it back; the tree's value wins at the next deploy. Writes to the vault: needs `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier. Audited. Not in the usage text yet.

```
bin/idm driver.start-option --env <name> --driver <name> --option auto|manual|disabled [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--option` | yes | `auto`, `manual` or `disabled` (any case) |
| `--yes`, `--confirm` | by tier | `--confirm` takes the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.start-option --env test --driver "Active Directory" --option manual --yes --confirm test
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.cache`

`view` lists the events queued in a driver's cache, and with `--out` writes them as a simulator case. `clear` discards every queued event: it prints the count and the first and last event, saves the events to `deploy-snapshots/<env>/<timestamp>-cache-<driver>.xds` under `--tree`, then deletes them. `view` is read-only (the driver may be running). `clear` writes to the vault: needs `--yes` on every tier, `--confirm <env>` on a `stg` or `prd` tier, and `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` on any tier. An empty cache is reported and nothing is cleared.

```
bin/idm driver.cache view  --env <name> --driver <name> [--count <n>] [--out <dir>] [--json]
bin/idm driver.cache clear --env <name> --driver <name> --yes [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Verbs | Required | Values |
|---|---|---|---|
| `view` \| `clear` | | yes | the verb, right after the command |
| `--env` | both | yes | an environment in `environments.properties` |
| `--driver` | both | yes | a driver's name |
| `--count` | view | no | the page size of the paged read; default: 100. The whole cache is always read |
| `--out` | view | no | a directory to write `cache.xds`, `input.xds` and `README` into (a simulator case) |
| `--yes` | clear | yes | |
| `--confirm` | clear | by tier | the environment's name |
| `--tree` | clear | no | where the snapshot and the audit line go; default: the current directory |
| `--json` | both | no | the result as JSON |

```bash
bin/idm driver.cache view --env test --driver "Active Directory" --out cases/ad-cache
bin/idm driver.cache clear --env test --driver "Active Directory" --yes --confirm test
```

More: [operate.md](operate.md#safeguards).

### `driver.migrate`

Migrates objects through a running driver. `--direction app` (the default when `--xds` is given) sends an XDS query document to the engine's Migrate from Application. `--direction vault` searches the vault and sends each object found down the driver's subscriber channel as a `<sync>`, so the engine sends its add or modify to the application; `--dry-run` lists the objects instead. Writes to the vault: needs `--yes` on every tier, `--confirm <env>` on a `prd` tier, and `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` on any tier ; `--dry-run` (only with `--direction vault`) needs neither `--yes` nor the agent gate, and with `--direction app` it is refused. The driver must be running. Audited.

```
bin/idm driver.migrate --env <name> --driver <name> [--direction app] --xds <file> --yes [--confirm <env>] [--tree <dir>] [--json]
bin/idm driver.migrate --env <name> --driver <name> --direction vault --base <dn> --filter <ldap filter> --class <class> [--max <n>] [--dry-run] --yes [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--direction` | no | `app` or `vault`; default: `app` when `--xds` is given, otherwise required |
| `--xds` | with `app` | an XDS file holding `<nds><input><query …>` |
| `--base` | with `vault` | the search base DN |
| `--filter` | with `vault` | an LDAP filter |
| `--class` | with `vault` | the class name each `<sync>` carries |
| `--max` | no (`vault`) | the most objects to migrate; default: 500 |
| `--dry-run` | no (`vault`) | list the objects found; send nothing |
| `--yes` | yes (not with `--dry-run`) | |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.migrate --env test --driver "Active Directory" --direction vault --base "ou=users,o=data" --filter "(objectClass=User)" --class User --dry-run --confirm test
```

More: [console-gaps.md](console-gaps.md#11-as-built-m2-first-part-migrate-into-the-application-the-log-level-the-extra-secrets).

### `driver.resync`

Asks the engine to resynchronize a running driver: every object, or those changed since an instant. Writes to the vault: needs `--yes` on every tier, `--confirm <env>` on a `prd` tier, and `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` on any tier. Audited. A full resync of a large tree is a real load.

```
bin/idm driver.resync --env <name> --driver <name> [--since <instant>] --yes [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--since` | no | an ISO-8601 instant, e.g. `2026-09-01T00:00:00Z`; default: a full resync |
| `--yes` | yes | |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.resync --env test --driver "Active Directory" --since 2026-09-01T00:00:00Z --yes --confirm test
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.secrets`

`list` shows a driver's named-password names (never the values). `set` and `remove` change one secret live: a named password, the shim's authentication password, the Remote Loader password, or the Remote Loader's mutual-authentication key or keystore password. `set` takes the value from the environment's secrets file, or one line on stdin with `--stdin`; never from an argument. `list` is read-only. `set` and `remove` write to the vault: need `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier. Audited.

```
bin/idm driver.secrets list   --env <name> --driver <name> [--json]
bin/idm driver.secrets set    --env <name> --driver <name> [--kind <kind>] [--name <name>] [--stdin] [--yes] [--confirm <env>] [--tree <dir>] [--json]
bin/idm driver.secrets remove --env <name> --driver <name> [--kind <kind>] [--name <name>] [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Verbs | Required | Values |
|---|---|---|---|
| `list` \| `set` \| `remove` | | yes | the verb, right after the command |
| `--env` | all | yes | an environment in `environments.properties` |
| `--driver` | all | yes | a driver's name |
| `--kind` | set, remove | no | `named`, `shim-auth`, `remote-loader`, `key`, `keystore`; default: `named` |
| `--name` | set, remove | with `named` | the named password's name |
| `--stdin` | set | no | read the value from one line on stdin. Without it, the secrets-file key is `<driver>.named.<name>`, `<driver>.shim-auth-password`, `<driver>.remote-loader-password`, `<driver>.mutual-auth-key-password` or `<driver>.mutual-auth-keystore-password` |
| `--yes` | set, remove | by tier | |
| `--confirm` | set, remove | by tier | the environment's name |
| `--tree` | set, remove | no | where the audit line goes; default: the current directory |
| `--json` | all | no | the result as JSON |

```bash
bin/idm driver.secrets set --env test --driver "Active Directory" --name adminPassword --yes --confirm test
```

More: [secrets.md](secrets.md).

### `driver.trace`

A driver's trace. `show` prints its trace level and file. `set` changes them live and records the old values in the audit line; `reset` puts back the values from the last `driver.trace set` of that driver in the deploy log. `tail` prints the driver's trace: over SSH from its `DirXML-TraceFile` on the engine host when the environment names an `sshHost`, otherwise (or with `--ldap`) from the engine's live trace events over the environment's LDAPS connection. `view` opens the desktop DirXML Trace Viewer, connected to the vault with the driver selected, or on a file; it needs the viewer installed (`viewer.install`), exits 1 when it is not, and returns while the viewer stays open. `show`, `tail` and `view` are read-only. `set` and `reset` write to the vault: need `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier.

```
bin/idm driver.trace show  --env <name> --driver <name> [--json]
bin/idm driver.trace set   --env <name> --driver <name> [--level <n>] [--file <path>] [--yes] [--confirm <env>] [--tree <dir>] [--json]
bin/idm driver.trace reset --env <name> --driver <name> [--yes] [--confirm <env>] [--tree <dir>] [--json]
bin/idm driver.trace tail  --env <name> --driver <name> [--lines <n>] [--since <minutes>] [--grep <regex>] [--follow] [--json]
bin/idm driver.trace tail  --env <name> --driver <name> --ldap [--seconds <n>] [--engine] [--grep <regex>] [--follow] [--json]
bin/idm driver.trace view  --env <name> [--driver <name>] [--json]
bin/idm driver.trace view  --file <trace file> [--json]
```

| Argument | Verbs | Required | Values |
|---|---|---|---|
| `show` \| `set` \| `reset` \| `tail` \| `view` | | yes | the verb, right after the command |
| `--env` | all | yes (`view`: unless `--file`) | an environment in `environments.properties` |
| `--driver` | all | yes (`view`: no) | a driver's name |
| `--level` | set | no | the trace level, an integer (e.g. 3) |
| `--file` | set | no | the trace file path on the engine host |
| `--file` | view | one of `--env`, `--file` | a trace file on this machine |
| `--yes`, `--confirm` | set, reset | by tier | `--confirm` takes the environment's name |
| `--tree` | set, reset | no | the directory whose `deploy-log/<env>.jsonl` gets (`set`) or is read for (`reset`) the audit line; default: the current directory |
| `--lines` | tail (SSH) | no | the last N lines; default: 50 |
| `--since` | tail (SSH) | no | the last N minutes, by the trace's own timestamps |
| `--grep` | tail | no | a regular expression the lines must match |
| `--follow` | tail | no | keep streaming until Ctrl-C |
| `--ldap` | tail | no | read over LDAP even when the environment names an `sshHost` |
| `--seconds` | tail (LDAP) | no | how long to collect; default: 30 |
| `--engine` | tail (LDAP) | no | add the engine-level channel |
| `--json` | all | no | the result as JSON (`tail --follow` streams text) |

```bash
bin/idm driver.trace set --env test --driver "Active Directory" --level 3 --yes --confirm test
bin/idm driver.trace tail --env test --driver "Active Directory" --ldap --seconds 60 --grep "status level"
```

More: [operate.md](operate.md#operations-and-what-backs-them), [install.md](install.md#52-the-dirxml-trace-viewer) for the viewer.

### `trace.view`

A driver trace as transactions: per driver and channel, each event with its policy sets, policies, rules, actions, vetoes and statuses. It reads only the engine's own wording, so an empty status or veto list means none was recognised. Reads a file, or a bounded read of a driver's trace from an environment (never follows). Read-only. Needs the trace parser `dirxml-trace-core` on the class path (exits 1 without it). Exits 2 on a bad option, 1 when the file or `--transaction N` does not exist.

```
bin/idm trace.view --file <trace file> [--driver <trace name>] [filters] [--json]
bin/idm trace.view --env <name> --driver <name> [--lines <n> | --since <minutes> | --ldap [--seconds <n>] [--engine]] [--trace-driver <trace name>] [filters] [--json]
filters: [--channel <channel>] [--status <level>] [--vetoed] [--text <regex>] [--last <n>] [--transaction <n>]
```

| Argument | Required | Values |
|---|---|---|
| `--file` | one of `--file`, `--env` | a trace file on this machine |
| `--env` | one of `--file`, `--env` | an environment in `environments.properties` |
| `--driver` | with `--env`: yes | with `--env`, the driver whose trace is read; with `--file`, keep that trace name's transactions |
| `--trace-driver` | no | keep one trace name's transactions (with `--env`; with `--file` it wins over `--driver`) |
| `--lines` | no | over SSH, the last N lines of the driver's `DirXML-TraceFile`; default: 2000 |
| `--since` | no | over SSH, the last N minutes |
| `--ldap` | no | live events over LDAP; the default when the environment names no `sshHost` |
| `--seconds` | no | over LDAP, how long to collect; default: 30 |
| `--engine` | no | over LDAP, add the engine-level channel |
| `--channel` | no | `subscriber` (`sub`, `st`), `publisher` (`pub`, `pt`), `service` (`sst`), `other` (`engine`) |
| `--status` | no | `success`, `warning`, `retry`, `error`, `fatal`: keep transactions with a status at that level |
| `--vetoed` | no | keep transactions with a recognised veto |
| `--text` | no | a regular expression the raw text must match |
| `--last` | no | show the most recent N that match; default: 20 |
| `--transaction` | no | that one transaction (1-based) with its raw text |
| `--json` | no | the result as JSON |

```bash
bin/idm trace.view --file ad.trace --status error --json
bin/idm trace.view --env test --driver "Active Directory" --ldap --seconds 60
```

More: [trace-view.md](trace-view.md#options).

### `viewer.check`, `viewer.install`

`viewer.check` says where the DirXML Trace Viewer is and how it was found (`IDM_TRACE_VIEWER`, the `traceViewer` property, `~/.idm/trace-viewer`, or the macOS app); exits 1 when it is not installed. `viewer.install` downloads the latest release (or a tag) from GitHub into `~/.idm/trace-viewer`, or with `--source` clones the repository and builds it (needs JDK 21 and Maven). Neither touches a vault.

```
bin/idm viewer.check
bin/idm viewer.install [--version <tag>] [--source] [--dir <dir>]
```

| Argument | Required | Values |
|---|---|---|
| `--version` | no | a release tag; default: the latest release |
| `--source` | no | clone and build instead of downloading a release |
| `--dir` | no | the install directory; default: `~/.idm/trace-viewer`. Elsewhere, set `IDM_TRACE_VIEWER` to the jar it prints |

```bash
bin/idm viewer.install
```

More: [install.md](install.md#52-the-dirxml-trace-viewer).

### `driver.submit`

Sends an XDS document into a driver. `command` (the default) is `SubmitCommand` into the running driver's subscriber channel and prints the shim's result; with `--tree` the same command runs through the simulator too, and when the environment names an `sshHost` and the driver traces at level 3 or more, the document the live shim received is compared with the simulator's (the canary). `event` is `SubmitEvent` (publisher channel); `queue` is `QueueEvent`, into the subscriber cache, even of a stopped driver. Writes to the vault: needs `--yes` on every tier, `--confirm <env>` on a `prd` tier, and `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` on any tier. Audited. Exits 1 on a canary mismatch or a failed event/queue; `command` on a stopped driver is refused (exit 1). The simulator run needs the engine jars.

```
bin/idm driver.submit --env <name> --driver <name> --xds <file> [--mode command|event|queue] --yes [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--xds` | yes | an XDS file (UTF-8) |
| `--mode` | no | `command`, `event` or `queue`; default: `command` |
| `--yes` | yes | |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | an IDM-as-code tree: runs the simulator canary (`command` only) and takes the audit line; default: no canary, audit in the current directory |
| `--json` | no | the result as JSON (`event` and `queue` only) |

```bash
bin/idm driver.submit --env test --driver "Loopback" --xds add-user.xml --tree tree --yes --confirm test
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.query`

Asks the connected system a question through the running driver: the engine's query verb as a command into the subscriber channel; prints the `<instance>`s the shim answers. An LDAP `--dn` is converted to the slash form the verb takes. It occupies the driver's channel, so it is gated as a light operation: `--yes` on a `stg` or `prd` tier, `--confirm <env>` on a `prd` tier, and, on every tier, `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` (the agent write gate). Audited. Refused when the driver is not running.

```
bin/idm driver.query --env <name> --driver <name> [--class <class>] [--scope subtree|subordinates|entry] [--dn <dn>] [--association <value>] [--search <attr>=<value>]… [--read-attr <attr>]…|[--read-attr none] [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--class` | no | the class to search (ignored for `entry` scope) |
| `--scope` | no | `subtree`, `subordinates` or `entry`; default: `subtree` |
| `--dn` | no | the base or entry, as an LDAP DN |
| `--association` | no | an association value to query by |
| `--search` | no, repeatable | `attr=value`, one per flag |
| `--read-attr` | no, repeatable | an attribute to read, one per flag; `none` reads nothing (a match test); default: every attribute |
| `--yes` | by tier | |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON, with the query document sent |

```bash
bin/idm driver.query --env test --driver "Loopback" --class User --dn "ou=users,o=data" --search CN=jdoe --read-attr Surname --yes
```

More: [console-gaps.md](console-gaps.md#10-as-built-m1-the-application-query-and-driver-health).

### `driver.associations`

The objects associated with a driver: a count per association state, then the objects with their association values. Read-only. Not in the usage text yet.

```
bin/idm driver.associations --env <name> --driver <name> [--state <state>] [--base <dn>] [--limit <n>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--state` | no | `processed`, `disabled`, `pending`, `manual` or `migrate`; default: every state |
| `--base` | no | the search base; default: the whole tree |
| `--limit` | no | the most objects listed (the counts cover all); default: 50 |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.associations --env test --driver "Active Directory" --state pending
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.password-sync`

The driver set's password-sync timeout and every password-related setting of the driver, read live. Read-only. Not in the usage text yet.

```
bin/idm driver.password-sync --env <name> --driver <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.password-sync --env test --driver "Active Directory"
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `object.inspect`

One object: its classes, its associations across drivers (driver, state, value), and its password-sync status per driver. Read-only. Exits 1 when the object does not exist. Not in the usage text yet.

```
bin/idm object.inspect --env <name> --dn <object dn> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--dn` | yes | the object's DN |
| `--json` | no | the result as JSON |

```bash
bin/idm object.inspect --env test --dn "cn=jdoe,ou=users,o=data"
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `driver.health`

The Driver Health job's last state per server for a driver, the health configuration on the driver, and the set's health jobs with whether this driver is in their scope. `clear` removes the recorded status. Show is read-only. `clear` writes to the vault: needs `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier. Audited.

```
bin/idm driver.health [clear] --env <name> --driver <name> [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `clear` | no | the verb, right after the command; without it the command shows |
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name |
| `--yes`, `--confirm` | by tier (`clear`) | `--confirm` takes the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.health --env test --driver "Active Directory"
```

More: [console-gaps.md](console-gaps.md#10-as-built-m1-the-application-query-and-driver-health).

### `driver.log-level`

The log level of a driver, or of the driver set without `--driver`: the level, the event ids the engine logs, the most entries kept, and the events type; a driver without its own values uses the set's. `set` writes them live; `--level` alone also sets the event ids Designer pairs with that level, and `--inherit` makes a driver use the set's values again. Show is read-only. `set` writes to the vault: needs `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier. Audited. Not in the usage text yet.

```
bin/idm driver.log-level --env <name> [--driver <name>] [--json]
bin/idm driver.log-level set --env <name> [--driver <name>] [--level <level>] [--events <id,…>] [--limit <n>] [--events-type <n>] [--inherit] [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `set` | no | the verb, right after the command; without it the command shows |
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | no (`--inherit`: yes) | a driver's name; default: the driver set |
| `--level` | no | `errors` (`error`, 0), `errors-and-warnings` (`warnings`, 1), `last-log-time` (2), `off` (`none`, 3), `specific-events` (`specific`, `events`, 5) |
| `--events` | with `specific-events` | event ids, comma- or space-separated |
| `--limit` | no | `DirXML-LogLimit`, an integer (0 turns the set's logging off) |
| `--events-type` | no | `DirXML-LogEventsType`, an integer |
| `--inherit` | no | clear the driver's own four values |
| `--yes`, `--confirm` | by tier (`set`) | `--confirm` takes the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

With `set`, give at least one of `--level`, `--events`, `--limit`, `--events-type`, `--inherit`.

```bash
bin/idm driver.log-level set --env test --driver "Active Directory" --level errors-and-warnings --yes --confirm test
```

More: [console-gaps.md](console-gaps.md#11-as-built-m2-first-part-migrate-into-the-application-the-log-level-the-extra-secrets).

### `job.list`

Every job of the driver set, or of one driver, with the engine's state: running, configuration state, scheduled, next run. Read-only.

```
bin/idm job.list --env <name> [--driver <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | no | list only that driver's jobs; default: every job under the set |
| `--json` | no | the result as JSON |

```bash
bin/idm job.list --env test
```

More: [console-gaps.md](console-gaps.md#8-as-built-j2).

### `job.status`

One job: class, servers, running state, configuration, scheduled, next run. Read-only.

```
bin/idm job.status --env <name> --job <name> [--driver <name>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--job` | yes | the job's name |
| `--driver` | no | the driver the job sits under; default: the job sits directly under the set |
| `--json` | no | the result as JSON |

```bash
bin/idm job.status --env test --job "Password Expiration Notification" --driver "User Application"
```

More: [console-gaps.md](console-gaps.md#8-as-built-j2).

### `job.start`, `job.abort`

Starts or aborts a job now (`StartJob` / `AbortJob`). Writes to the vault: needs `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier. Audited.

```
bin/idm job.start|job.abort --env <name> --job <name> [--driver <name>] [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--job` | yes | the job's name |
| `--driver` | no | the driver the job sits under; default: directly under the set |
| `--yes`, `--confirm` | by tier | `--confirm` takes the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm job.start --env test --job "Password Expiration Notification" --driver "User Application" --yes --confirm test
```

More: [console-gaps.md](console-gaps.md#8-as-built-j2).

### `driverset.activation`

The driver set's activation as the engine reports it (`ViewActivation`, when the engine answers) and the activation attributes on the set (credentials shown as present, never their content). `apply` sends a vendor activation credential (`ApplyActivation`). Show is read-only. `apply` writes to the vault: needs `--yes` on every tier, `--confirm <env>` on a `prd` tier, and `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` on any tier. Audited.

```
bin/idm driverset.activation --env <name> [--json]
bin/idm driverset.activation apply --env <name> --file <credential.xml> --yes [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `apply` | no | the verb, right after the command; without it the command shows |
| `--env` | yes | an environment in `environments.properties` |
| `--file` | with `apply` | the vendor credential file |
| `--yes` | with `apply` | |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driverset.activation --env test
```

More: [console-gaps.md](console-gaps.md#13-as-built-l1-the-low-set).

### `driverset.servers`

The servers associated with the driver set (`DirXML-ServerList`) and the set the connected server reports running. `add` and `remove` associate or dissociate a server: the call goes to that server (its URL from the environment's `servers=` mapping, or derived) as `SetDriverSet` / `ClearDriverSet`, and is refused when the server runs another set. Show is read-only. `add` and `remove` write to the vault: need `--yes` on every tier, `--confirm <env>` on a `prd` tier, and `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1` on any tier. Audited.

```
bin/idm driverset.servers --env <name> [--json]
bin/idm driverset.servers add|remove --env <name> --server <server dn> --yes [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `add` \| `remove` | no | the verb, right after the command; without it the command shows |
| `--env` | yes | an environment in `environments.properties` |
| `--server` | with `add`/`remove` | the server's DN |
| `--yes` | with `add`/`remove` | |
| `--confirm` | by tier | the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

```bash
bin/idm driverset.servers add --env test --server "cn=idm2,ou=servers,o=system" --yes --confirm test
```

More: [console-gaps.md](console-gaps.md#13-as-built-l1-the-low-set).

### `engine.metrics`

User and driver metrics: users, users with an association, the enabled ones among those (the licensing count), and each driver's associations by state. Counts only. Read-only.

```
bin/idm engine.metrics --env <name> [--base <dn>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--base` | no | narrow the user searches to this base DN; default: the whole tree |
| `--json` | no | the result as JSON |

```bash
bin/idm engine.metrics --env test --base "ou=users,o=data"
```

More: [console-gaps.md](console-gaps.md#13-as-built-l1-the-low-set).

### `driver.rights`

eDirectory's effective rights of the driver object on another object: entry rights, all-attributes rights, and the named attributes. A lookup only. Read-only. Exits 1 without `--dn`.

```
bin/idm driver.rights --env <name> --driver <name> --dn <object dn> [--attr <attr>]… [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | yes | a driver's name (the trustee) |
| `--dn` | yes | the object to check the rights on |
| `--attr` | no, repeatable | an attribute's LDAP name, one per flag |
| `--json` | no | the result as JSON |

```bash
bin/idm driver.rights --env test --driver "Active Directory" --dn "cn=jdoe,ou=users,o=data" --attr mail --attr telephoneNumber
```

More: [console-gaps.md](console-gaps.md#13-as-built-l1-the-low-set).

### `workorder.list`

The Work Order driver's `DirXML-WorkOrder` objects: status, due date, description, newest due date first. Read-only.

```
bin/idm workorder.list --env <name> [--base <dn>] [--limit <n>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--base` | no | the search base; default: the driver set |
| `--limit` | no | the most objects listed; default: 200 |
| `--json` | no | the result as JSON |

```bash
bin/idm workorder.list --env test
```

More: [console-gaps.md](console-gaps.md#13-as-built-l1-the-low-set).

### `vault.email-server`

The notification collection's SMTP settings; the password is reported as set or not, never shown. `set` writes the settings given (an empty value, `--host=` or `--host ""`, clears that setting) and the password from the environment's secrets file (`--password-key`) or one line on stdin (`--stdin`). Show is read-only. `set` writes to the vault: needs `--confirm <env>` or `IDM_AGENT_ALLOW_WRITE=1`, `--yes` on a `stg` or `prd` tier, and `--confirm <env>` on a `prd` tier. Audited.

```
bin/idm vault.email-server --env <name> [--json]
bin/idm vault.email-server set --env <name> [--host <host>] [--port <n>] [--from <address>] [--user <user>] [--tls true|false] [--timeout <n>] [--protocol <protocol>] [--auth <mechanisms>] [--password-key <key> | --stdin] [--yes] [--confirm <env>] [--tree <dir>] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `set` | no | the verb, right after the command; without it the command shows |
| `--env` | yes | an environment in `environments.properties` |
| `--host` | no | `notfSMTPEmailHost` |
| `--port` | no | `notfSMTPPort` |
| `--from` | no | `notfSMTPEmailFrom` |
| `--user` | no | `notfSMTPEmailUserName` |
| `--tls` | no | `notfSMTPUseTLS`: `true` or `false` |
| `--timeout` | no | `notfSMTPTimeout` |
| `--protocol` | no | `notfSMTPMailProtocol` |
| `--auth` | no | `notfSMTPAuthMechanisms` |
| `--password-key` | no | a key in the environment's secrets file, e.g. `email-server.password` |
| `--stdin` | no | read the password from one line on stdin (wins over `--password-key`) |
| `--yes`, `--confirm` | by tier (`set`) | `--confirm` takes the environment's name |
| `--tree` | no | where the audit line goes; default: the current directory |
| `--json` | no | the result as JSON |

With `set`, at least one setting or a password is needed.

```bash
bin/idm vault.email-server set --env test --host smtp.example.com --port 587 --tls true --password-key email-server.password --yes --confirm test
```

More: [console-gaps.md](console-gaps.md#12-as-built-m2-second-part-the-e-mail-server-and-the-notification-templates).

### `rbe.list`

The role-based entitlement policies of the driver set: priority, member count the directory computes, grants, membership query. Read-only.

```
bin/idm rbe.list --env <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--json` | no | the result as JSON |

```bash
bin/idm rbe.list --env test
```

More: [console-gaps.md](console-gaps.md#9-as-built-r1-role-based-entitlement-policies).

### `rbe.members`

The members the directory computes for one entitlement policy (static and dynamic). Read-only.

```
bin/idm rbe.members --env <name> --policy <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--policy` | yes | the policy's name |
| `--json` | no | the result as JSON |

```bash
bin/idm rbe.members --env test --policy "Sales Staff"
```

More: [console-gaps.md](console-gaps.md#9-as-built-r1-role-based-entitlement-policies).

### `engine.version`

The engine's version (`GetVersion`). Read-only.

```
bin/idm engine.version --env <name> [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--json` | no | the result as JSON |

```bash
bin/idm engine.version --env test
```

More: [operate.md](operate.md#operations-and-what-backs-them).

### `engine.stats`

The engine's JVM heap and thread counts, and each driver's cache and operation counters (`GetDriverStats`). Read-only.

```
bin/idm engine.stats --env <name> [--driver <name>]… [--json]
```

| Argument | Required | Values |
|---|---|---|
| `--env` | yes | an environment in `environments.properties` |
| `--driver` | no, repeatable | a driver's name, one per flag; default: every driver |
| `--json` | no | the result as JSON |

```bash
bin/idm engine.stats --env test --driver "Active Directory" --driver "Loopback"
```

More: [operate.md](operate.md#operations-and-what-backs-them).

## Edit operations: what every one shares

Each edit operation loads the tree, makes the change in memory, validates the result and writes
the files only when the change adds no validation error: every file, or none. They all take:

| Flag | Meaning |
|---|---|
| `--dry-run` | do everything except write; the result shows each file that would change |
| `--force` | write even though the change adds validation errors (some refusals can't be forced; the entry says so) |
| `--json` | the result as JSON: what changed, the validation findings, a refusal |

Exit 0 when the operation did (or on a dry run would do) what was asked, 1 when it was refused,
2 on a usage error. An operation that names a driver takes the tree's driver name, as
`driverset.xml` lists it.

<!-- BEGIN edit operations: written from Registry.java and examples.properties by OperationsDoc; do not edit by hand (mvn -o test -Dtest=CommandsReferenceTest -Dcommands.write=true) -->

## Edit: policies, resources and rules

### `policy.add`

Create a policy (DirXML Script, XSLT or schema map) and optionally link it.

```
bin/idm policy.add <tree> --name <name> [--driver <driver>] [--scope <scope>] [--kind <kind>] [--content-file <content-file>] [--link <link>] [--at <at>] [--link-driver <link-driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | policy name |
| `--driver` | no | driver name (omit for the Library) |
| `--scope` | no | driver\|subscriber\|publisher\|library (default: driver, or library when no --driver) |
| `--kind` | no | policy\|xslt\|schema-map (default policy) |
| `--content-file` | no | file holding the content (XML, or JS for ecmascript); default: a skeleton |
| `--link` | no | also link it: a policy-set key (input, output, subscriber-command, publisher-event, …) |
| `--at` | no | where in the set: first\|last\|after:&lt;path&gt;\|before:&lt;path&gt;\|&lt;order&gt; (default last) |
| `--link-driver` | no | for a Library artifact with --link: the driver whose set links it |

```bash
bin/idm policy.add tree --driver "Active Directory" --name sub-etp-Defaults --link subscriber-event --dry-run
```

### `artifact.add`

Create a mapping table, ECMAScript or GCV-definition resource and optionally link it.

```
bin/idm artifact.add <tree> --name <name> [--driver <driver>] [--scope <scope>] [--kind <kind>] [--content-file <content-file>] [--link <link>] [--at <at>] [--link-driver <link-driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | resource name |
| `--driver` | no | driver name (omit for the Library) |
| `--scope` | no | driver\|subscriber\|publisher\|library (default: driver, or library when no --driver) |
| `--kind` | no | mapping-table\|ecmascript\|gcv (default mapping-table) |
| `--content-file` | no | file holding the content (XML, or JS for ecmascript); default: a skeleton |
| `--link` | no | also link it: a policy-set key (input, output, subscriber-command, publisher-event, …) |
| `--at` | no | where in the set: first\|last\|after:&lt;path&gt;\|before:&lt;path&gt;\|&lt;order&gt; (default last) |
| `--link-driver` | no | for a Library artifact with --link: the driver whose set links it |

```bash
bin/idm artifact.add tree --driver "Active Directory" --name Departments --kind mapping-table --dry-run
```

### `artifact.set-content`

Replace an artifact's content.

```
bin/idm artifact.set-content <tree> --path <path> --content-file <content-file> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | artifact path (library/X, drivers/D/X, drivers/D/subscriber/X) |
| `--content-file` | yes | file holding the new content |

```bash
bin/idm artifact.set-content tree --path "drivers/Active Directory/sub-etp-Defaults" --content-file sub-etp-Defaults.xml --dry-run
```

### `artifact.rename`

Rename an artifact and rewrite every reference to it.

```
bin/idm artifact.rename <tree> --path <path> --name <name> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | artifact path |
| `--name` | yes | the new name |

```bash
bin/idm artifact.rename tree --path "drivers/Active Directory/sub-etp-Defaults" --name sub-etp-Set-Defaults --dry-run
```

### `artifact.delete`

Delete an artifact; refuses while anything references it.

```
bin/idm artifact.delete <tree> --path <path> [--unlink] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | artifact path |
| `--unlink` | no, a flag | remove its policy-set links first |

```bash
bin/idm artifact.delete tree --path "drivers/Active Directory/sub-etp-Old" --unlink --dry-run
```

### `policy.link`

Link an artifact into a driver's policy set.

```
bin/idm policy.link <tree> --path <path> --driver <driver> --set <set> [--at <at>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | artifact path |
| `--driver` | yes | driver name |
| `--set` | yes | policy-set key |
| `--at` | no | where in the set: first\|last\|after:&lt;path&gt;\|before:&lt;path&gt;\|&lt;order&gt; (default last) |

```bash
bin/idm policy.link tree --path library/Common-Placement --driver "Active Directory" --set subscriber-placement --at first --dry-run
```

### `policy.unlink`

Remove an artifact from a driver's policy set.

```
bin/idm policy.unlink <tree> --path <path> --driver <driver> --set <set> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | artifact path |
| `--driver` | yes | driver name |
| `--set` | yes | policy-set key |

```bash
bin/idm policy.unlink tree --path library/Common-Placement --driver "Active Directory" --set subscriber-placement --dry-run
```

### `policy.reorder`

Set the full order of a driver's policy set.

```
bin/idm policy.reorder <tree> --driver <driver> --set <set> --order <order> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--set` | yes | policy-set key |
| `--order` | yes | the members in order (repeat --order &lt;path&gt; for each) |

```bash
bin/idm policy.reorder tree --driver "Active Directory" --set subscriber-command --order "drivers/Active Directory/sub-cp-Passwords" --order "drivers/Active Directory/sub-cp-Groups" --dry-run
```

### `rule.add`

Insert a &lt;rule&gt; into a DirXML Script policy.

```
bin/idm rule.add <tree> --path <path> --content-file <content-file> [--at <at>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | path of a DirXML Script policy |
| `--content-file` | yes | file holding the &lt;rule&gt; XML |
| `--at` | no | first\|last\|after:&lt;rule&gt;\|before:&lt;rule&gt; (default last) |

```bash
bin/idm rule.add tree --path "drivers/Active Directory/sub-etp-Defaults" --content-file rule.xml --at first --dry-run
```

### `rule.delete`

Remove a rule.

```
bin/idm rule.delete <tree> --path <path> --rule <rule> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | path of a DirXML Script policy |
| `--rule` | yes | the rule's &lt;description&gt;, or #n (1-based position) |

```bash
bin/idm rule.delete tree --path "drivers/Active Directory/sub-etp-Defaults" --rule "Set default department" --dry-run
```

### `rule.move`

Move a rule to another position.

```
bin/idm rule.move <tree> --path <path> --rule <rule> [--at <at>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | path of a DirXML Script policy |
| `--rule` | yes | the rule's &lt;description&gt;, or #n (1-based position) |
| `--at` | no | first\|last\|after:&lt;rule&gt;\|before:&lt;rule&gt; (default last) |

```bash
bin/idm rule.move tree --path "drivers/Active Directory/sub-etp-Defaults" --rule "#3" --at first --dry-run
```

### `rule.disable`

Set &lt;rule disabled="true"&gt; — the engine skips it.

```
bin/idm rule.disable <tree> --path <path> --rule <rule> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | path of a DirXML Script policy |
| `--rule` | yes | the rule's &lt;description&gt;, or #n (1-based position) |

```bash
bin/idm rule.disable tree --path "drivers/Active Directory/sub-etp-Defaults" --rule "Set default department" --dry-run
```

### `rule.enable`

Clear a rule's disabled flag.

```
bin/idm rule.enable <tree> --path <path> --rule <rule> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | path of a DirXML Script policy |
| `--rule` | yes | the rule's &lt;description&gt;, or #n (1-based position) |

```bash
bin/idm rule.enable tree --path "drivers/Active Directory/sub-etp-Defaults" --rule "Set default department" --dry-run
```

## Edit: mapping tables

### `mapping-table.set-row`

Add or update a row, keyed by --key-column (default: the first column).

```
bin/idm mapping-table.set-row <tree> --path <path> --col <col> [--key-column <key-column>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | mapping-table resource path |
| `--col` | yes | name=value (repeat --col for each column) |
| `--key-column` | no | column that identifies the row (default: the first) |

```bash
bin/idm mapping-table.set-row tree --path "drivers/Active Directory/Departments" --col department=Sales --col ou=ou=sales,o=data --dry-run
```

### `mapping-table.delete-row`

Delete the row whose key column has --key.

```
bin/idm mapping-table.delete-row <tree> --path <path> --key <key> [--key-column <key-column>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | mapping-table resource path |
| `--key` | yes | the key column's value |
| `--key-column` | no | column that identifies the row (default: the first) |

```bash
bin/idm mapping-table.delete-row tree --path "drivers/Active Directory/Departments" --key Sales --dry-run
```

### `mapping-table.add-column`

Add a column (empty in every existing row).

```
bin/idm mapping-table.add-column <tree> --path <path> --column <column> [--type <type>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | mapping-table resource path |
| `--column` | yes | column name |
| `--type` | no | nocase\|case\|numeric (default nocase) |

```bash
bin/idm mapping-table.add-column tree --path "drivers/Active Directory/Departments" --column manager --dry-run
```

### `mapping-table.import-csv`

Replace the table from a CSV file (first line: column names, first column: the key); --merge true adds and updates rows by key instead.

```
bin/idm mapping-table.import-csv <tree> --path <path> --csv-file <csv-file> [--merge <merge>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | mapping-table resource path |
| `--csv-file` | yes | the CSV file (RFC 4180; UTF-8) |
| `--merge` | no | true: add and update rows by key, keep the others (default: the table becomes the CSV) |

```bash
bin/idm mapping-table.import-csv tree --path "drivers/Active Directory/Departments" --csv-file departments.csv --merge true --dry-run
```

## Edit: GCVs, filter, schema map and driver settings

### `gcv.set`

Set a GCV's value where the driver's scope defines it (or create it with --define).

```
bin/idm gcv.set <tree> --name <name> --value <value> [--driver <driver>] [--define <define>] [--display-name <display-name>] [--server <server>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | GCV name |
| `--value` | yes | the value |
| `--driver` | no | driver name (omit for the Library) |
| `--define` | no | create the GCV when absent: its type (string\|boolean\|integer\|dn\|enum\|…) |
| `--display-name` | no | display name for --define (default: the name) |
| `--server` | no | on a driver set served by several servers, the value one server holds for the driver (its DN, its name, or primary); without it, the primary's, which every server without its own value follows |

```bash
bin/idm gcv.set tree --driver "Active Directory" --name drv.user.base --value ou=users,o=data --dry-run
```

### `gcv.delete`

Remove a GCV definition; refuses while any policy reads it.

```
bin/idm gcv.delete <tree> --name <name> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | GCV name |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm gcv.delete tree --driver "Active Directory" --name drv.legacy.flag --dry-run
```

### `filter.set-class`

Add a class to the driver filter, or change its channel settings.

```
bin/idm filter.set-class <tree> --driver <driver> --class <class> [--publisher <publisher>] [--subscriber <subscriber>] [--publisher-create-homedir <publisher-create-homedir>] [--publisher-track-template-member <publisher-track-template-member>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--class` | yes | class name (NDS name) |
| `--publisher` | no | sync\|ignore\|notify\|reset |
| `--subscriber` | no | sync\|ignore\|notify\|reset |
| `--publisher-create-homedir` | no | true\|false |
| `--publisher-track-template-member` | no | true\|false |

```bash
bin/idm filter.set-class tree --driver "Active Directory" --class User --subscriber sync --publisher sync --dry-run
```

### `filter.set-attr`

Add an attribute to a filter class, or change its settings.

```
bin/idm filter.set-attr <tree> --driver <driver> --class <class> --attr <attr> [--publisher <publisher>] [--subscriber <subscriber>] [--merge-authority <merge-authority>] [--publisher-optimize-modify <publisher-optimize-modify>] [--subscriber-optimize-modify <subscriber-optimize-modify>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--class` | yes | class name (NDS name) |
| `--attr` | yes | attribute name (NDS name) |
| `--publisher` | no | sync\|ignore\|notify\|reset |
| `--subscriber` | no | sync\|ignore\|notify\|reset |
| `--merge-authority` | no | default\|edir\|app\|none |
| `--publisher-optimize-modify` | no | true\|false |
| `--subscriber-optimize-modify` | no | true\|false |

```bash
bin/idm filter.set-attr tree --driver "Active Directory" --class User --attr "Telephone Number" --subscriber sync --publisher notify --merge-authority edir --dry-run
```

### `filter.remove-class`

Remove a class (and its attributes) from the filter.

```
bin/idm filter.remove-class <tree> --driver <driver> --class <class> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--class` | yes | class name (NDS name) |

```bash
bin/idm filter.remove-class tree --driver "Active Directory" --class Device --dry-run
```

### `filter.remove-attr`

Remove an attribute from a filter class.

```
bin/idm filter.remove-attr <tree> --driver <driver> --class <class> --attr <attr> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--class` | yes | class name (NDS name) |
| `--attr` | yes | attribute name |

```bash
bin/idm filter.remove-attr tree --driver "Active Directory" --class User --attr Fax --dry-run
```

### `schema-map.set`

Map a class (--nds-class/--app-class), an attribute within it (add --nds-attr/--app-attr), or a top-level attribute (--nds-attr/--app-attr alone).

```
bin/idm schema-map.set <tree> --driver <driver> [--nds-class <nds-class>] [--app-class <app-class>] [--nds-attr <nds-attr>] [--app-attr <app-attr>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--nds-class` | no | eDirectory class name |
| `--app-class` | no | application class name |
| `--nds-attr` | no | eDirectory attribute name |
| `--app-attr` | no | application attribute name |

```bash
bin/idm schema-map.set tree --driver "Active Directory" --nds-class User --nds-attr "Telephone Number" --app-attr telephoneNumber --dry-run
```

### `schema-map.remove`

Remove a class mapping, an attribute mapping within a class, or a top-level attribute mapping.

```
bin/idm schema-map.remove <tree> --driver <driver> [--nds-class <nds-class>] [--nds-attr <nds-attr>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--nds-class` | no | eDirectory class name |
| `--nds-attr` | no | eDirectory attribute name |

```bash
bin/idm schema-map.remove tree --driver "Active Directory" --nds-class User --nds-attr Fax --dry-run
```

### `config.set-content`

Replace a whole configuration document (a driver's config-values, shim-config-info, driver-filter or engine-control-values; without --driver, the driver set's config-values).

```
bin/idm config.set-content <tree> --kind <kind> --content-file <content-file> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--kind` | yes | config-values\|shim-config-info\|driver-filter\|engine-control-values |
| `--content-file` | yes | file holding the new document |
| `--driver` | no | driver name (omit for the driver set's config-values) |

```bash
bin/idm config.set-content tree --driver "Active Directory" --kind driver-filter --content-file filter.xml --dry-run
```

### `driver.server.copy`

Copy a driver's server-specific settings (GCV values, shim parameters, engine control values, the shim's server and account) from one server of the set to another; without --driver, every driver's.

```
bin/idm driver.server.copy <tree> --from <from> --to <to> [--driver <driver>] [--only <only>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--from` | yes | the server to copy from: its DN, its name, or primary |
| `--to` | yes | the server to copy to: its DN, its name, or primary |
| `--driver` | no | one driver (default: every driver) |
| `--only` | no | some of gcv,shim,ecv,auth-server,auth-id (default: all) |

```bash
bin/idm driver.server.copy tree --from primary --to idm2 --driver "Active Directory" --only gcv --dry-run
```

### `driver.set`

Set a driver setting: shim-class, shim-auth-server, shim-auth-id, environments (the environments it is deployed to, comma-separated; --clear = every environment), planned (environments it is meant to reach but isn't promoted to yet), param:&lt;shim parameter&gt;, engine:&lt;engine control value&gt;.

```
bin/idm driver.set <tree> --driver <driver> --key <key> [--value <value>] [--clear] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--key` | yes | shim-class\|shim-auth-server\|shim-auth-id\|environments\|planned\|param:&lt;name&gt;\|engine:&lt;name&gt; |
| `--value` | no | the value |
| `--clear` | no, a flag | make the setting empty (an empty --value reads as missing on the command line) |

```bash
bin/idm driver.set tree --driver "Active Directory" --key shim-auth-server --value dc1.example.com --dry-run
```

### `driver.setting.add`

Add a shim parameter (param:&lt;name&gt;, into a section of the shim config) or an engine control value (engine:&lt;name&gt;): its definition with display name, type and value.

```
bin/idm driver.setting.add <tree> --driver <driver> --key <key> [--type <type>] [--display <display>] [--value <value>] [--section <section>] [--description <description>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--key` | yes | param:&lt;name&gt;\|engine:&lt;name&gt; |
| `--type` | no | string\|integer\|boolean\|dn\|enum\|password-ref… (default string) |
| `--display` | no | display name (default: the name) |
| `--value` | no | the value (default empty) |
| `--section` | no | for a shim parameter: driver-options (default), subscriber-options, publisher-options |
| `--description` | no | a description |

```bash
bin/idm driver.setting.add tree --driver "Active Directory" --key param:page-size --type integer --display "Page size" --value 500 --section subscriber-options --dry-run
```

### `driver.setting.remove`

Remove a shim parameter (param:&lt;name&gt;) or an engine control value (engine:&lt;name&gt;) from a driver.

```
bin/idm driver.setting.remove <tree> --driver <driver> --key <key> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | yes | driver name |
| `--key` | yes | param:&lt;name&gt;\|engine:&lt;name&gt; |

```bash
bin/idm driver.setting.remove tree --driver "Active Directory" --key param:page-size --dry-run
```

### `driver.add`

Add a driver: from a driver export, as a copy of another driver, or blank.

```
bin/idm driver.add <tree> --name <name> [--from-export <from-export>] [--source-driver <source-driver>] [--copy-of <copy-of>] [--packages <packages>] [--catalog <catalog>] [--package <package>] [--answers <answers>] [--shim-class <shim-class>] [--auth-server <auth-server>] [--auth-id <auth-id>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the new driver's name |
| `--from-export` | no | a driver export (Designer, with referenced policies) to merge in as this driver |
| `--source-driver` | no | with a driver-set export: which driver to take |
| `--copy-of` | no | an existing driver to clone |
| `--packages` | no | package jars, comma-separated, base first: the driver is built from the base package and the set installed |
| `--catalog` | no | with --package: the package catalog directory |
| `--package` | no | with --catalog: SHORT[_ver][,…] to install, base first |
| `--answers` | no | name=value file answering the packages' prompts |
| `--shim-class` | no | for a blank driver: the shim class |
| `--auth-server` | no | for a blank driver: the authentication server/URL |
| `--auth-id` | no | for a blank driver: the authentication id |

```bash
bin/idm driver.add tree --name Loopback --copy-of "Loopback Template" --dry-run
```

## Edit: environments

### `override.set`

Set a value for one environment (overrides/&lt;env&gt;.properties); the tree keeps its base value.

```
bin/idm override.set <tree> --env <env> --key <key> --value <value> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--env` | yes | environment name |
| `--key` | yes | drivers/&lt;driver&gt;.gcv.&lt;name&gt; \| .shim.&lt;name&gt; \| .ecv.&lt;name&gt; \| .shim-auth-server \| .shim-auth-id \| .name (the driver's name in that vault) \| driverset.gcv.&lt;name&gt; |
| `--value` | yes | the environment's value |

```bash
bin/idm override.set tree --env prod --key "drivers/Active Directory.gcv.drv.user.base" --value ou=users,o=prod --dry-run
```

### `environments.set`

Which environments get objects and which they are planned for (driver.xml / driverset.xml environments, planned); several objects at once, one tree path per line.

```
bin/idm environments.set <tree> --paths <paths> [--environments <environments>] [--every] [--planned <planned>] [--no-plan] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--paths` | yes | tree paths, one per line: drivers/&lt;d&gt;, drivers/&lt;d&gt;/jobs/&lt;n&gt;, jobs/&lt;n&gt;, rbe-policies/&lt;n&gt;, templates/&lt;n&gt; |
| `--environments` | no | the environments that get them, comma-separated |
| `--every` | no, a flag | every environment gets them |
| `--planned` | no | environments they are planned for, not promoted to yet |
| `--no-plan` | no, a flag | nothing planned |

```bash
bin/idm environments.set tree --paths "drivers/Active Directory" --environments test,prod --dry-run
```

### `known.accept`

Accept a difference between two environments as it is (overrides/&lt;env&gt;.known.json): comparisons leave it out while it is unchanged.

```
bin/idm known.accept <tree> --env <env> --with <with> --path <path> --fingerprint <fingerprint> [--decision <decision>] [--summary <summary>] [--note <note>] [--by <by>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--env` | yes | the environment whose file records it |
| `--with` | yes | the other environment of the pair |
| `--path` | yes | the changed object's tree path |
| `--fingerprint` | yes | the change's fingerprint, from vault.compare-envs |
| `--decision` | no | intended (default: different on purpose) or later (to decide) |
| `--summary` | no | what differs, in a few words |
| `--note` | no | why |
| `--by` | no | who accepted it |

```bash
bin/idm known.accept tree --env prod --with test --path "drivers/Active Directory" --fingerprint 3f2a9c1e --summary "different OU base" --note "production keeps its own" --dry-run
```

### `known.forget`

Stop accepting a known difference: comparisons show it again.

```
bin/idm known.forget <tree> --env <env> [--with <with>] --path <path> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--env` | yes | environment name |
| `--with` | no | the other environment |
| `--path` | yes | the object's tree path |

```bash
bin/idm known.forget tree --env prod --with test --path "drivers/Active Directory" --dry-run
```

### `override.remove`

Remove an environment's override, so it takes the tree's base value.

```
bin/idm override.remove <tree> --env <env> --key <key> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--env` | yes | environment name |
| `--key` | yes | the override key |

```bash
bin/idm override.remove tree --env prod --key "drivers/Active Directory.gcv.drv.user.base" --dry-run
```

## Edit: packages

### `package.install`

Install a package jar onto a driver (type 2) or the Library (type 3), Designer's way.

```
bin/idm package.install <tree> [--jar <jar>] [--catalog <catalog>] [--package <package>] [--driver <driver>] [--answers <answers>] [--new-driver <new-driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--jar` | no | the package jar(s), comma-separated, in install order (or give --catalog and --package) |
| `--catalog` | no | a package catalog directory (jars/&lt;SHORT&gt;/&lt;SHORT&gt;_&lt;ver&gt;.jar) |
| `--package` | no | SHORT[_version][,SHORT[_version]…] in the catalog (newest version when omitted); base first |
| `--driver` | no | the driver to install onto (omit for a driver-set package into the Library) |
| `--answers` | no | name=value file answering the package prompts (see package.prompts) |
| `--new-driver` | no | true when the driver was just created (prompts run in driver-creation mode) |

```bash
bin/idm package.install tree --catalog ../packages --package NOVLADBASE,NOVLADDCFG --driver "Active Directory" --answers ad-answers.properties --dry-run
```

### `package.revert`

Put a customized packaged object (artifact, form, PRD, entitlement, AppConfig object) back to its package baseline and drop the customized mark.

```
bin/idm package.revert <tree> --path <path> [--catalog <catalog>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | the tree path: library/&lt;n&gt;, drivers/&lt;d&gt;/&lt;n&gt;, drivers/&lt;d&gt;/provisioning/forms/&lt;kind&gt;/&lt;n&gt;, …/prds/&lt;n&gt;, …/objects/&lt;path&gt;, drivers/&lt;d&gt;/entitlements/&lt;n&gt; |
| `--catalog` | no | a package catalog, to look up the package's checksum when the tree did not record it |

```bash
bin/idm package.revert tree --path "drivers/Active Directory/NOVLADDCFG-sub-etp-Defaults" --catalog ../packages --dry-run
```

### `package.strip`

Make a driver fully custom: remove every package stamp (driver, artifacts, entitlements, forms, PRDs, AppConfig objects), the customized marks and the baselines; the next deploy removes the vault's stamps and package aux classes too.

```
bin/idm package.strip <tree> [--driver <driver>] [--library] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | no | the driver to strip |
| `--library` | no, a flag | also strip the Library's items and the driver set's own records (shared by every driver, so never implied) |

```bash
bin/idm package.strip tree --driver Loopback --dry-run
```

### `package.adopt`

Write the installed-package records from the objects' package stamps (trees imported from a vault or a project).

```
bin/idm package.adopt <tree> [--driver <driver>] [--catalog <catalog>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | no | one driver (default: every driver and the Library) |
| `--catalog` | no | a package catalog, to resolve project-style package ids to names and versions |

```bash
bin/idm package.adopt tree --catalog ../packages --dry-run
```

### `package.uninstall`

Remove an installed package; refuses while another installed package depends on it, or while a base package's features remain, unless --all.

```
bin/idm package.uninstall <tree> [--driver <driver>] --package <package> [--yes] [--all] [--catalog <catalog>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | no | the driver the package is installed on (omit for a driver-set package in the Library) |
| `--package` | yes | the installed package's SHORT name |
| `--yes` | no, a flag | remove customized objects too (without it they're refused, named) |
| `--all` | no, a flag | also remove packages that depend on this one, or (for a base package) its remaining features |
| `--catalog` | no | a package catalog, to read other installed packages' declared dependencies |

```bash
bin/idm package.uninstall tree --driver "Active Directory" --package NOVLADENTEX --catalog ../packages --dry-run
```

### `package.upgrade`

Replace an installed package with another version (--downgrade for an older one; same mechanics).

```
bin/idm package.upgrade <tree> [--driver <driver>] [--jar <jar>] [--catalog <catalog>] [--package <package>] [--answers <answers>] [--yes] [--downgrade] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--driver` | no | the driver the package is installed on (omit for a driver-set package in the Library) |
| `--jar` | no | the new version's package jar (or give --catalog and --package) |
| `--catalog` | no | a package catalog directory (jars/&lt;SHORT&gt;/&lt;SHORT&gt;_&lt;ver&gt;.jar) |
| `--package` | no | SHORT_version in the catalog, the version to move to |
| `--answers` | no | name=value file answering the package's prompts (existing driver values pre-fill them) |
| `--yes` | no, a flag | remove customized objects the new version dropped (without it they're refused, named) |
| `--downgrade` | no, a flag | moving to an older version (informational — the mechanics are identical) |

```bash
bin/idm package.upgrade tree --driver "Active Directory" --catalog ../packages --package NOVLADBASE_2.6.0.20240115 --dry-run
```

## Edit: vault schema

### `schema.add-attribute`

Define a custom attribute in the tree's vault schema (vault.deploy-schema pushes it).

```
bin/idm schema.add-attribute <tree> --name <name> [--ldap <ldap>] [--syntax <syntax>] [--oid <oid>] [--single] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | NDS name |
| `--ldap` | no | LDAP name (default: the name) |
| `--syntax` | no | LDAP syntax OID (default Directory String) |
| `--oid` | no | the attribute's OID (needed to push it) |
| `--single` | no, a flag | single-valued |

```bash
bin/idm schema.add-attribute tree --name exEmployeeType --oid 2.16.840.1.113719.1.9999.4.1 --single --dry-run
```

### `schema.add-class`

Define a custom class in the tree's vault schema.

```
bin/idm schema.add-class <tree> --name <name> [--ldap <ldap>] [--kind <kind>] [--oid <oid>] [--superclasses <superclasses>] [--mandatory <mandatory>] [--optional <optional>] [--containment <containment>] [--naming <naming>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | NDS name |
| `--ldap` | no | LDAP name |
| `--kind` | no | structural (default) \| auxiliary \| abstract |
| `--oid` | no | the class's OID (needed to push it) |
| `--superclasses` | no | comma-separated (default Top) |
| `--mandatory` | no | attributes, comma-separated |
| `--optional` | no | attributes, comma-separated |
| `--containment` | no | container classes, comma-separated |
| `--naming` | no | naming attributes, comma-separated |

```bash
bin/idm schema.add-class tree --name exPerson --kind auxiliary --oid 2.16.840.1.113719.1.9999.6.1 --optional exEmployeeType --dry-run
```

### `schema.set`

Change a custom attribute or class of the tree's vault schema.

```
bin/idm schema.set <tree> --name <name> [--ldap <ldap>] [--syntax <syntax>] [--oid <oid>] [--single <single>] [--kind <kind>] [--superclasses <superclasses>] [--mandatory <mandatory>] [--optional <optional>] [--containment <containment>] [--naming <naming>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the attribute's or class's name |
| `--ldap` | no | LDAP name |
| `--syntax` | no | attribute syntax OID |
| `--oid` | no | OID ('' clears) |
| `--single` | no | true\|false |
| `--kind` | no | class kind |
| `--superclasses` | no | replaces the list |
| `--mandatory` | no | replaces the list |
| `--optional` | no | replaces the list |
| `--containment` | no | replaces the list |
| `--naming` | no | replaces the list |

```bash
bin/idm schema.set tree --name exEmployeeType --single false --dry-run
```

### `schema.remove`

Remove a custom attribute or class from the tree's vault schema (never from a vault).

```
bin/idm schema.remove <tree> --name <name> [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the attribute's or class's name |

```bash
bin/idm schema.remove tree --name exEmployeeType --dry-run
```

## Edit: forms

### `form.set-content`

Replace a JSON form's document (pretty-printed in the tree) and re-sync every PRD binding that references it.

```
bin/idm form.set-content <tree> --form <form> --content-file <content-file> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--content-file` | yes | file holding the new form JSON (what the vendor builder saved) |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.set-content tree --form request/AccessRequest --content-file AccessRequest.json --dry-run
```

### `form.sync`

Re-normalize a form already saved into the tree (e.g. by the vendor builder) and re-sync its PRD bindings.

```
bin/idm form.sync <tree> --form <form> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name or driver/kind/name |
| `--driver` | no | the User Application driver |

```bash
bin/idm form.sync tree --form request/AccessRequest --dry-run
```

### `form.add`

Create a new form (blank, or --from another form's document).

```
bin/idm form.add <tree> --kind <kind> --name <name> [--from <from>] [--title <title>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--kind` | yes | request\|approval\|template |
| `--name` | yes | the new form's name |
| `--from` | no | copy this form's document (new title) |
| `--title` | no | the new form's title (default: its name) |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm form.add tree --kind request --name AccessRequest --title "Request access" --driver "User Application" --dry-run
```

### `form.field.add`

Add a new component to a form.

```
bin/idm form.field.add <tree> --form <form> --key <key> --type <type> [--label <label>] [--required] [--hidden] [--multiple] [--minimal] [--after <after>] [--before <before>] [--first] [--in <in>] [--props <props>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--key` | yes | the new component's key |
| `--type` | yes | component type (a captured template name, or any Form.io type) |
| `--label` | no | label text |
| `--required` | no, a flag | validate.required = true |
| `--hidden` | no, a flag | hidden = true |
| `--multiple` | no, a flag | multiple = true |
| `--minimal` | no, a flag | force the minimal {label,key,type,input} shape |
| `--after` | no | place after this key |
| `--before` | no | place before this key |
| `--first` | no, a flag | place first |
| `--in` | no | place inside this container/panel/columns component (its first column, if columns) |
| `--props` | no | extra properties as a JSON object, deep-merged in last (--json is the output flag) |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.field.add tree --form request/AccessRequest --key costCenter --type textfield --label "Cost centre" --required --after department --dry-run
```

### `form.field.set`

Change an existing component's properties.

```
bin/idm form.field.set <tree> --form <form> --key <key> [--label <label>] [--required <required>] [--hidden <hidden>] [--multiple <multiple>] [--type <type>] [--prop <prop>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--key` | yes | the component's key |
| `--label` | no | new label |
| `--required` | no | true\|false |
| `--hidden` | no | true\|false |
| `--multiple` | no | true\|false |
| `--type` | no | new component type |
| `--prop` | no | dotted-path=JSON-value (repeat --prop for each, e.g. validate.maxLength=50) |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.field.set tree --form request/AccessRequest --key costCenter --prop validate.maxLength=10 --dry-run
```

### `form.field.remove`

Remove a component; refuses while a PRD data item maps it unless --force.

```
bin/idm form.field.remove <tree> --form <form> --key <key> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--key` | yes | the component's key |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.field.remove tree --form request/AccessRequest --key costCenter --dry-run
```

### `form.field.move`

Reorder or reparent a component.

```
bin/idm form.field.move <tree> --form <form> --key <key> [--after <after>] [--before <before>] [--first] [--last] [--in <in>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--key` | yes | the component's key |
| `--after` | no | place after this key |
| `--before` | no | place before this key |
| `--first` | no, a flag | place first |
| `--last` | no, a flag | place last |
| `--in` | no | reparent inside this container/panel/columns component |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.field.move tree --form request/AccessRequest --key costCenter --before department --dry-run
```

### `form.set`

Change a form's title, display mode, inline script (from a file) or an external script.

```
bin/idm form.set <tree> --form <form> [--title <title>] [--display <display>] [--inline-script <inline-script>] [--external-script <external-script>] [--remove] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--title` | no | new title |
| `--display` | no | form\|workflowWizard |
| `--inline-script` | no | a file holding the new inlinescripts text |
| `--external-script` | no | a script URL to add (or remove, with --remove) |
| `--remove` | no, a flag | with --external-script, remove it instead of adding it |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.set tree --form request/AccessRequest --title "Request system access" --dry-run
```

### `form.localize`

Set explicit localized strings, or top up every declared language with missing entries.

```
bin/idm form.localize <tree> --form <form> --lang <lang> [--set <set>] [--sync] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--lang` | yes | language code, e.g. fr |
| `--set` | no | Label=Localized (repeat --set for each) |
| `--sync` | no, a flag | add, to every declared language, an entry for every label/placeholder/tooltip/option/button text missing one |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.localize tree --form request/AccessRequest --lang fr --set "Cost centre=Centre de coûts" --dry-run
```

### `form.rename`

Rename a form and rewrite every PRD reference to it (form-id attributes and flowdata prefixes).

```
bin/idm form.rename <tree> --form <form> --to <to> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--to` | yes | the new name |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.rename tree --form request/AccessRequest --to SystemAccessRequest --dry-run
```

### `form.delete`

Delete a form; refuses while any PRD binds it (never overridden by --force); a packaged form needs --force.

```
bin/idm form.delete <tree> --form <form> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--form` | yes | form name, kind/name (request\|approval\|template) or driver/kind/name |
| `--driver` | no | the User Application driver (needed when several drivers have a form of that name) |

```bash
bin/idm form.delete tree --form request/AccessRequest --dry-run
```

## Edit: workflows (PRDs)

### `prd.map`

Add, replace or (--unmap) remove a field's data-item mapping.

```
bin/idm prd.map <tree> --prd <prd> --field <field> [--activity <activity>] [--target <target>] [--source <source>] [--unmap] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--field` | yes | the form field's key |
| `--activity` | no | map an approval activity's data item instead of the request form's |
| `--target` | no | override the default flowdata target (request form only) |
| `--source` | no | override the default flowdata.get(...) source (activity only; required if the request form has no same-named field) |
| `--unmap` | no, a flag | remove the mapping instead |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm prd.map tree --prd "Request Access" --field costCenter --dry-run
```

### `prd.add`

Copy a template PRD (status Template) into a new Active PRD bound to the given forms.

```
bin/idm prd.add <tree> --name <name> --from-template <from-template> --request-form <request-form> [--approval-form <approval-form>] [--category <category>] [--display-name <display-name>] [--map-all] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the new PRD's name |
| `--from-template` | yes | the template PRD's name (status Template, e.g. NoApproval) |
| `--request-form` | yes | the request form to bind |
| `--approval-form` | no | the approval form to bind to the first user-activity, if any |
| `--category` | no | prov-category (default: the template's) |
| `--display-name` | no | lang~Text override for one language (default: the new PRD's name, every language) |
| `--map-all` | no, a flag | map every bindable field of the bound form(s) to flowdata with the default targets/sources, as prd.map would |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm prd.add tree --name "Request Access" --from-template NoApproval --request-form AccessRequest --map-all --dry-run
```

### `prd.delete`

Delete a PRD; refuses while another PRD's start-correlated-flow-activity references it (never overridden by --force); a packaged PRD needs --force.

```
bin/idm prd.delete <tree> --prd <prd> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm prd.delete tree --prd "Request Access" --dry-run
```

### `flow.activity.add`

Insert a workflow activity after another, wiring its default outgoing link(s).

```
bin/idm flow.activity.add <tree> --prd <prd> --kind <kind> --id <id> --after <after> [--via <via>] [--to <to>] [--on-denied <on-denied>] [--status <status>] [--on-false <on-false>] [--name <name>] [--addressee <addressee>] [--form <form>] [--timeout <timeout>] [--ontimeout <ontimeout>] [--expression <expression>] [--message <message>] [--template <template>] [--entitlement-dn <entitlement-dn>] [--entitlement-param <entitlement-param>] [--protocol <protocol>] [--host <host>] [--port <port>] [--path <path>] [--method <method>] [--content <content>] [--header <header>] [--content-type <content-type>] [--accept <accept>] [--authorization <authorization>] [--status-to <status-to>] [--content-to <content-to>] [--content-type-to <content-type-to>] [--role <role>] [--target <target>] [--target-type <target-type>] [--action <action>] [--description <description>] [--effective-date <effective-date>] [--expiration-date <expiration-date>] [--correlation-id <correlation-id>] [--resource <resource>] [--param <param>] [--process <process>] [--recipient <recipient>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--kind` | yes | approval\|condition\|branch\|log\|notification\|mapping\|provision\|rest\|role-request\|resource-request\|start-flow |
| `--id` | yes | the new activity's id ([A-Za-z_][A-Za-z0-9_-]*) |
| `--after` | yes | insert after this activity |
| `--via` | no | the outgoing link type of --after to consume (when it has more than one) |
| `--to` | no | required when --after is a branch: the leg's target (the branch's merge, or reachable from it) |
| `--on-denied` | no | an approval's 'denied' link target (default: the process's 'Workflow Status Denied' mapping, created as 'status_denied' → finish if absent) |
| `--status` | no | mapping only: approved\|denied — the mapping sets flowdata.IDM_COMPLETED_APPROVAL_STATUS (what Request History shows) |
| `--on-false` | no | a condition's 'false' link target (default: the finish activity) |
| `--name` | no | display name: lang~Text or plain text (= en); default a sensible label |
| `--addressee` | no | an approval's addressee expression (default: the stock manager lookup) |
| `--form` | no | an approval's approval form to bind (default: the driver's stock 'Approval Form' if present; without one the dashboard cannot open the task) |
| `--timeout` | no | an approval's timeout in milliseconds (default: the stock 8-day timeout); rest's timeout in milliseconds |
| `--ontimeout` | no | an approval's ontimeout (default: denied) |
| `--expression` | no | a condition's boolean ECMAScript expression |
| `--message` | no | a log activity's message expression (default: a quoted 'Activity &lt;id&gt;' literal) |
| `--template` | no | a notification activity's notify template DN |
| `--entitlement-dn` | no | a provision activity's entitlement DN (quoted as a literal) |
| `--entitlement-param` | no | a provision activity's entitlement parameter (default '') |
| `--protocol` | no | rest: http\|https (required; an expression; a plain value is written quoted, 'https') |
| `--host` | no | rest: host (required; an expression; a plain value is written quoted) |
| `--port` | no | rest: port (required; an expression; a plain value is written quoted) |
| `--path` | no | rest: request path (required; an expression; a plain value is written quoted) |
| `--method` | no | rest: GET\|POST\|PUT\|DELETE\|PATCH, checked case-insensitively (required; an expression; a plain value is written quoted) |
| `--content` | no | rest: the request body expression |
| `--header` | no | rest: Key=Value, repeatable (one &lt;http-headers&gt; element per header) |
| `--content-type` | no | rest: contentTypeHeader (an expression; a plain value is written quoted) |
| `--accept` | no | rest: acceptHeader (an expression; a plain value is written quoted) |
| `--authorization` | no | rest: authorizationHeader (an expression; a plain value is written quoted) |
| `--status-to` | no | rest: the flowdata path that receives the response status code (flowdata.restStatus; a bare name gets the flowdata. prefix) |
| `--content-to` | no | rest: the flowdata path that receives the response content (a bare name gets the flowdata. prefix) |
| `--content-type-to` | no | rest: the flowdata path that receives the response content type (a bare name gets the flowdata. prefix) |
| `--role` | no | role-request: a role expression (typically a quoted DN), repeatable, at least one required |
| `--target` | no | role-request: a target expression, repeatable, at least one required; resource-request: a target-user expression, repeatable, at least one required |
| `--target-type` | no | role-request: USER\|GROUP\|CONTAINER\|CONTAINER_WITH_SUBTREE\|ROLE (default USER) |
| `--action` | no | role-request: GRANT\|REVOKE\|EXTEND (default GRANT); resource-request: GRANT\|REVOKE (default GRANT) |
| `--description` | no | role-request/resource-request: the request-description expression (required) |
| `--effective-date` | no | role-request: the effective-date expression |
| `--expiration-date` | no | role-request: the expiration-date expression |
| `--correlation-id` | no | role-request/resource-request: the correlation-id expression; start-flow: the correlationId expression |
| `--resource` | no | resource-request: the target-resource expression (required) |
| `--param` | no | resource-request: source=target, repeatable (one &lt;target-param&gt; element per pair) |
| `--process` | no | start-flow: the processId expression naming the target PRD (required) |
| `--recipient` | no | start-flow: a recipient expression, repeatable, at least one required |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.activity.add tree --prd "Request Access" --kind approval --id manager_approval --after start --dry-run
```

### `flow.activity.set`

Change an existing workflow activity's attributes/addressee/expression/message/template/entitlement.

```
bin/idm flow.activity.set <tree> --prd <prd> --id <id> [--name <name>] [--form <form>] [--attr <attr>] [--addressee <addressee>] [--timeout <timeout>] [--ontimeout <ontimeout>] [--expression <expression>] [--message <message>] [--template <template>] [--entitlement-dn <entitlement-dn>] [--entitlement-param <entitlement-param>] [--approver-type <approver-type>] [--protocol <protocol>] [--host <host>] [--port <port>] [--path <path>] [--method <method>] [--content <content>] [--header <header>] [--content-type <content-type>] [--accept <accept>] [--authorization <authorization>] [--status-to <status-to>] [--content-to <content-to>] [--content-type-to <content-type-to>] [--role <role>] [--target <target>] [--target-type <target-type>] [--action <action>] [--description <description>] [--effective-date <effective-date>] [--expiration-date <expiration-date>] [--correlation-id <correlation-id>] [--resource <resource>] [--param <param>] [--process <process>] [--recipient <recipient>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--id` | yes | the activity's id |
| `--name` | no | display name: lang~Text or plain text (= en) |
| `--form` | no | approval only: bind this approval form (declaration, form-binding, stock data items; replaces an existing binding) |
| `--attr` | no | name=value, repeatable (validated against known enums; activity-id is refused — use flow.activity.rename) |
| `--addressee` | no | replaces every addressee (repeat --addressee for more than one) |
| `--timeout` | no | milliseconds |
| `--ontimeout` | no | approved\|denied\|refused\|timedout\|error |
| `--expression` | no | a condition's expression |
| `--message` | no | a log activity's message expression |
| `--template` | no | a notify template DN |
| `--entitlement-dn` | no | provision only: entitlement DN (quoted as a literal) |
| `--entitlement-param` | no | provision only: entitlement parameter (quoted as a literal) |
| `--approver-type` | no | org-approver\|group-approver\|multiple-approver\|quorum-approver |
| `--protocol` | no | rest: http\|https (required; an expression; a plain value is written quoted, 'https') |
| `--host` | no | rest: host (required; an expression; a plain value is written quoted) |
| `--port` | no | rest: port (required; an expression; a plain value is written quoted) |
| `--path` | no | rest: request path (required; an expression; a plain value is written quoted) |
| `--method` | no | rest: GET\|POST\|PUT\|DELETE\|PATCH, checked case-insensitively (required; an expression; a plain value is written quoted) |
| `--content` | no | rest: the request body expression |
| `--header` | no | rest: Key=Value, repeatable (one &lt;http-headers&gt; element per header) |
| `--content-type` | no | rest: contentTypeHeader (an expression; a plain value is written quoted) |
| `--accept` | no | rest: acceptHeader (an expression; a plain value is written quoted) |
| `--authorization` | no | rest: authorizationHeader (an expression; a plain value is written quoted) |
| `--status-to` | no | rest: the flowdata path that receives the response status code (flowdata.restStatus; a bare name gets the flowdata. prefix) |
| `--content-to` | no | rest: the flowdata path that receives the response content (a bare name gets the flowdata. prefix) |
| `--content-type-to` | no | rest: the flowdata path that receives the response content type (a bare name gets the flowdata. prefix) |
| `--role` | no | role-request: a role expression (typically a quoted DN), repeatable, at least one required |
| `--target` | no | role-request: a target expression, repeatable, at least one required; resource-request: a target-user expression, repeatable, at least one required |
| `--target-type` | no | role-request: USER\|GROUP\|CONTAINER\|CONTAINER_WITH_SUBTREE\|ROLE (default USER) |
| `--action` | no | role-request: GRANT\|REVOKE\|EXTEND (default GRANT); resource-request: GRANT\|REVOKE (default GRANT) |
| `--description` | no | role-request/resource-request: the request-description expression (required) |
| `--effective-date` | no | role-request: the effective-date expression |
| `--expiration-date` | no | role-request: the expiration-date expression |
| `--correlation-id` | no | role-request/resource-request: the correlation-id expression; start-flow: the correlationId expression |
| `--resource` | no | resource-request: the target-resource expression (required) |
| `--param` | no | resource-request: source=target, repeatable (one &lt;target-param&gt; element per pair) |
| `--process` | no | start-flow: the processId expression naming the target PRD (required) |
| `--recipient` | no | start-flow: a recipient expression, repeatable, at least one required |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.activity.set tree --prd "Request Access" --id manager_approval --timeout 172800000 --ontimeout denied --dry-run
```

### `flow.activity.rename`

Rename an activity id everywhere it is referenced (links, data-items, form-binding, expressions).

```
bin/idm flow.activity.rename <tree> --prd <prd> --id <id> --to <to> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--id` | yes | the activity's current id |
| `--to` | yes | the new id |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.activity.rename tree --prd "Request Access" --id manager_approval --to line_manager_approval --dry-run
```

### `flow.activity.remove`

Remove an activity, reconnecting its incoming links to its primary successor.

```
bin/idm flow.activity.remove <tree> --prd <prd> --id <id> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--id` | yes | the activity's id (not start/finish/branch/merge) |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.activity.remove tree --prd "Request Access" --id manager_approval --dry-run
```

### `flow.branch.add`

Insert a branch/merge pair (add legs afterwards with flow.activity.add --after &lt;branch&gt; --to &lt;merge&gt;).

```
bin/idm flow.branch.add <tree> --prd <prd> --id <id> --merge <merge> --after <after> [--via <via>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--id` | yes | the new branch activity's id |
| `--merge` | yes | the new merge activity's id |
| `--after` | yes | insert after this activity |
| `--via` | no | the outgoing link type of --after to consume |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.branch.add tree --prd "Request Access" --id split --merge join --after start --dry-run
```

### `flow.branch.remove`

Remove a branch/merge pair with a single leg (or none), reconnecting around them.

```
bin/idm flow.branch.remove <tree> --prd <prd> --id <id> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--id` | yes | the branch activity's id |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.branch.remove tree --prd "Request Access" --id split --dry-run
```

### `flow.link.add`

Add a link between two activities.

```
bin/idm flow.link.add <tree> --prd <prd> --from <from> --to <to> --type <type> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--from` | yes | source activity id |
| `--to` | yes | target activity id |
| `--type` | yes | forward\|approved\|denied\|refused\|timedout\|success\|fault\|true\|false\|error |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.link.add tree --prd "Request Access" --from manager_approval --to finish --type denied --dry-run
```

### `flow.link.remove`

Remove a link between two activities.

```
bin/idm flow.link.remove <tree> --prd <prd> --from <from> --to <to> [--type <type>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--from` | yes | source activity id |
| `--to` | yes | target activity id |
| `--type` | no | only remove a link of this type (default: any type between --from and --to) |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.link.remove tree --prd "Request Access" --from manager_approval --to finish --type denied --dry-run
```

### `flow.link.retype`

Change a link's type.

```
bin/idm flow.link.retype <tree> --prd <prd> --from <from> --to <to> --type <type> --to-type <to-type> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--from` | yes | source activity id |
| `--to` | yes | target activity id |
| `--type` | yes | the link's current type |
| `--to-type` | yes | the new type |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.link.retype tree --prd "Request Access" --from manager_approval --to finish --type denied --to-type timedout --dry-run
```

### `flow.data.set`

Add or replace a raw data item on any activity's data-items block.

```
bin/idm flow.data.set <tree> --prd <prd> --activity <activity> --name <name> [--source <source>] [--target <target>] [--type <type>] [--target-type <target-type>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--activity` | yes | the activity's id |
| `--name` | yes | the data item's name |
| `--source` | no | an ECMAScript source expression |
| `--target` | no | a flowdata target path |
| `--type` | no | string\|boolean\|integer\|decimal\|date\|dn\|binary\|element (default: string) |
| `--target-type` | no | single-value\|multi-value-list\|multi-value-list-item |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.data.set tree --prd "Request Access" --activity manager_approval --name reason --source "flowdata.get('reason')" --target reason --dry-run
```

### `flow.data.remove`

Remove a raw data item from an activity's data-items block.

```
bin/idm flow.data.remove <tree> --prd <prd> --activity <activity> --name <name> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--activity` | yes | the activity's id |
| `--name` | yes | the data item's name |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.data.remove tree --prd "Request Access" --activity manager_approval --name reason --dry-run
```

### `flow.set`

Change process-level attributes (version, process-type, flow-strategy, …).

```
bin/idm flow.set <tree> --prd <prd> [--version <version>] [--process-type <process-type>] [--flow-strategy <flow-strategy>] [--default-completed-approval-status <default-completed-approval-status>] [--setnotify <setnotify>] [--restrict-view <restrict-view>] [--generate-comments <generate-comments>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--prd` | yes | PRD name |
| `--version` | no | one of the engine's supported process versions |
| `--process-type` | no | Normal\|RBAC\|RBACSOD\|Resource\|Attestation\|ResourceProvisioning\|RoleProvisioning |
| `--flow-strategy` | no | SingleFlow\|FlowPerMember\|SingleFlowProvisionMembers |
| `--default-completed-approval-status` | no | approved\|denied |
| `--setnotify` | no | true\|false |
| `--restrict-view` | no | true\|false |
| `--generate-comments` | no | true\|false |
| `--driver` | no | driver name (omit for the Library) |

```bash
bin/idm flow.set tree --prd "Request Access" --process-type Normal --setnotify true --dry-run
```

## Edit: entitlements

### `entitlement.add`

Create a DirXML-Entitlement definition on a driver.

```
bin/idm entitlement.add <tree> --name <name> --driver <driver> [--display-name <display-name>] [--description <description>] [--multi-valued] [--conflict <conflict>] [--values <values>] [--definition-file <definition-file>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the entitlement's name (cn) |
| `--driver` | yes | driver name |
| `--display-name` | no | display-name attribute |
| `--description` | no | description attribute |
| `--multi-valued` | no, a flag | values/@multi-valued = true |
| `--conflict` | no | union\|priority (default: priority on entitlement.add) |
| `--values` | no | comma-separated static values -&gt; &lt;values&gt;&lt;value&gt;v&lt;/value&gt;...&lt;/values&gt; |
| `--definition-file` | no | a file holding a whole &lt;entitlement&gt; document, used verbatim instead of the other content flags |

```bash
bin/idm entitlement.add tree --driver "Active Directory" --name Group --display-name "Group membership" --multi-valued --conflict union --dry-run
```

### `entitlement.set`

Change an existing entitlement's attributes/values, or replace its whole document with --definition-file.

```
bin/idm entitlement.set <tree> --name <name> [--driver <driver>] [--display-name <display-name>] [--description <description>] [--multi-valued <multi-valued>] [--conflict <conflict>] [--values <values>] [--definition-file <definition-file>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the entitlement's name (cn) |
| `--driver` | no | driver name (required for entitlement.add; needed for entitlement.set/remove when several drivers have one of that name) |
| `--display-name` | no | display-name attribute |
| `--description` | no | description attribute |
| `--multi-valued` | no | true\|false |
| `--conflict` | no | union\|priority (default: priority on entitlement.add) |
| `--values` | no | comma-separated static values -&gt; &lt;values&gt;&lt;value&gt;v&lt;/value&gt;...&lt;/values&gt; |
| `--definition-file` | no | a file holding a whole &lt;entitlement&gt; document, used verbatim instead of the other content flags |

```bash
bin/idm entitlement.set tree --driver "Active Directory" --name Group --description "Membership of an Active Directory group" --dry-run
```

### `entitlement.remove`

Delete an entitlement; refuses while any PRD's provision activity names it (--force does not override this).

```
bin/idm entitlement.remove <tree> --name <name> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | the entitlement's name (cn) |
| `--driver` | no | driver name (required for entitlement.add; needed for entitlement.set/remove when several drivers have one of that name) |

```bash
bin/idm entitlement.remove tree --driver "Active Directory" --name Group --dry-run
```

## Edit: AppConfig, roles and resources

### `appconfig.set`

Set (replace all values of) or remove one attribute of any AppConfig object.

```
bin/idm appconfig.set <tree> --path <path> --attr <attr> [--value <value>] [--file <file>] [--remove] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | object path under AppConfig (DirectoryModel/EntityDefs/user) or a unique name |
| `--attr` | yes | attribute name |
| `--value` | no | a value (repeatable for a multi-valued attribute; a bare localized text merges as English) |
| `--file` | no | read the value from this file (an XML attribute such as XmlData) |
| `--remove` | no, a flag | remove the attribute |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm appconfig.set tree --path DirectoryModel/EntityDefs/user --attr nrfAllowCreate --value false --dry-run
```

### `appconfig.add`

Create any AppConfig object under an existing container.

```
bin/idm appconfig.add <tree> --path <path> --class <class> [--aux <aux>] [--attr <attr>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | the new object's path under AppConfig |
| `--class` | yes | structural class (nrfNavItem, nrfRoleDefs, srvprvChoice …) |
| `--aux` | no | auxiliary classes, comma-separated |
| `--attr` | no | name=value (repeatable) |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm appconfig.add tree --path NavItems/Reports --class nrfNavItem --attr "nrfName=Reports" --dry-run
```

### `appconfig.remove`

Delete an AppConfig object; refuses while it holds objects or anything names its DN.

```
bin/idm appconfig.remove <tree> --path <path> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--path` | yes | object path under AppConfig or a unique name |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm appconfig.remove tree --path NavItems/Reports --dry-run
```

### `role.add`

Create a role in the catalog (RoleConfig/RoleDefs/Level&lt;n&gt;/&lt;container&gt;), creating the container.

```
bin/idm role.add <tree> --name <name> --level <level> --category <category> [--container <container>] [--display <display>] [--descr <descr>] [--owner <owner>] [--approver <approver>] [--quorum <quorum>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | role name (cn) |
| `--level` | yes | 10 (permission), 20 (IT) or 30 (business) |
| `--category` | yes | category keys, comma-separated: keys of DirectoryModel/ChoiceDefs/roles-category (category.list --for role) |
| `--container` | no | the container it goes in (default: named like the first --category; created when absent) |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--descr` | no | description, lang=text or bare text (repeatable; merged per language) |
| `--owner` | no | owner DN (repeatable; '-' clears) |
| `--approver` | no | approver DN (repeatable; '-' clears) |
| `--quorum` | no | approval quorum |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm role.add tree --name HelpDesk --level 20 --category it --display "Help desk" --dry-run
```

### `role.set`

Change a role's names, descriptions, categories, owners, approvers, quorum or status.

```
bin/idm role.set <tree> --name <name> [--display <display>] [--descr <descr>] [--category <category>] [--owner <owner>] [--approver <approver>] [--quorum <quorum>] [--status <status>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | role name or path |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--descr` | no | description, lang=text or bare text (repeatable; merged per language) |
| `--category` | no | category keys, comma-separated (replaces; checked against the list) |
| `--owner` | no | owner DN (repeatable; '-' clears) |
| `--approver` | no | approver DN (repeatable; '-' clears) |
| `--quorum` | no | approval quorum |
| `--status` | no | nrfStatus (50 = active) |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm role.set tree --name HelpDesk --descr "First-line support" --approver cn=jsmith,ou=users,o=data --dry-run
```

### `role.remove`

Delete a role; refuses while anything names its DN.

```
bin/idm role.remove <tree> --name <name> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | role name or path |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm role.remove tree --name HelpDesk --dry-run
```

### `resource.add`

Create a resource (RoleConfig/ResourceDefs/&lt;container&gt;), optionally bound to an entitlement.

```
bin/idm resource.add <tree> --name <name> --category <category> [--container <container>] [--display <display>] [--descr <descr>] [--entitlement <entitlement>] [--param <param>] [--multi] [--owner <owner>] [--approver <approver>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | resource name (cn) |
| `--category` | yes | category keys, comma-separated: keys of DirectoryModel/ChoiceDefs/resources-category (category.list --for resource) |
| `--container` | no | the container it goes in (default: named like the first --category; created when absent) |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--descr` | no | description, lang=text or bare text (repeatable; merged per language) |
| `--entitlement` | no | DirXML-Entitlement DN the resource grants |
| `--param` | no | the entitlement parameter value |
| `--multi` | no, a flag | allow multiple assignment |
| `--owner` | no | owner DN (repeatable; '-' clears) |
| `--approver` | no | approver DN (repeatable; '-' clears) |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm resource.add tree --name VpnAccess --category network --entitlement "cn=Group,cn=Active Directory,cn=DriverSet,o=system" --param cn=VPN,ou=groups,o=data --dry-run
```

### `resource.set`

Change a resource's names, descriptions, categories, entitlement binding, flags, owners or approvers.

```
bin/idm resource.set <tree> --name <name> [--display <display>] [--descr <descr>] [--category <category>] [--entitlement <entitlement>] [--param <param>] [--multi <multi>] [--active <active>] [--owner <owner>] [--approver <approver>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | resource name or path |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--descr` | no | description, lang=text or bare text (repeatable; merged per language) |
| `--category` | no | category keys, comma-separated (replaces; checked against the list) |
| `--entitlement` | no | entitlement DN ('-' unbinds) |
| `--param` | no | entitlement parameter |
| `--multi` | no | true\|false |
| `--active` | no | true\|false |
| `--owner` | no | owner DN (repeatable; '-' clears) |
| `--approver` | no | approver DN (repeatable; '-' clears) |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm resource.set tree --name VpnAccess --active false --dry-run
```

### `resource.remove`

Delete a resource; refuses while anything names its DN.

```
bin/idm resource.remove <tree> --name <name> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--name` | yes | resource name or path |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm resource.remove tree --name VpnAccess --dry-run
```

### `category.add`

Add a key to the role or resource category list (DirectoryModel/ChoiceDefs/&lt;roles|resources&gt;-category).

```
bin/idm category.add <tree> --for <for> --key <key> [--display <display>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--for` | yes | role or resource: which category list |
| `--key` | yes | the category key (letters, digits, . _ -) |
| `--display` | no | label, lang=text or bare text for English (repeatable; default: the key) |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm category.add tree --for role --key it --display "IT roles" --dry-run
```

### `category.set`

Change a category's labels (merged per language).

```
bin/idm category.set <tree> --for <for> --key <key> --display <display> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--for` | yes | role or resource: which category list |
| `--key` | yes | the category key |
| `--display` | yes | label, lang=text or bare text for English (repeatable) |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm category.set tree --for role --key it --display "Information technology" --display "fr=Informatique" --dry-run
```

### `category.remove`

Remove a key from the category list; refuses while a role or resource uses it.

```
bin/idm category.remove <tree> --for <for> --key <key> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--for` | yes | role or resource: which category list |
| `--key` | yes | the category key |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm category.remove tree --for role --key it --dry-run
```

### `entity.add`

Create a directory-abstraction entity (DirectoryModel/EntityDefs) with no attributes yet.

```
bin/idm entity.add <tree> --key <key> --object-class <object-class> [--aux-class <aux-class>] [--display <display>] [--search-root <search-root>] [--naming-attribute <naming-attribute>] [--creatable <creatable>] [--editable <editable>] [--removable <removable>] [--viewable <viewable>] [--auto-query <auto-query>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--key` | yes | entity key (cn) |
| `--object-class` | yes | LDAP object class |
| `--aux-class` | no | auxiliary classes, comma-separated |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--search-root` | no | search root DN or %user-root% |
| `--naming-attribute` | no | default cn |
| `--creatable` | no | true\|false |
| `--editable` | no | true\|false |
| `--removable` | no | true\|false |
| `--viewable` | no | true\|false |
| `--auto-query` | no | true\|false |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm entity.add tree --key device --object-class device --display Device --search-root ou=devices,o=data --dry-run
```

### `entity.set`

Change an entity's display names, flags or search root.

```
bin/idm entity.set <tree> --key <key> [--display <display>] [--search-root <search-root>] [--creatable <creatable>] [--editable <editable>] [--removable <removable>] [--viewable <viewable>] [--auto-query <auto-query>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--key` | yes | entity key |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--search-root` | no | search root |
| `--creatable` | no | true\|false |
| `--editable` | no | true\|false |
| `--removable` | no | true\|false |
| `--viewable` | no | true\|false |
| `--auto-query` | no | true\|false |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm entity.set tree --key device --editable true --dry-run
```

### `entity.remove`

Delete an entity; refuses for a system entity or while anything names its DN.

```
bin/idm entity.remove <tree> --key <key> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--key` | yes | entity key |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm entity.remove tree --key device --dry-run
```

### `entity.attr.add`

Add an attribute to an entity definition.

```
bin/idm entity.attr.add <tree> --entity <entity> --key <key> --ldap <ldap> [--nds <nds>] [--type <type>] [--display <display>] [--required <required>] [--multivalue <multivalue>] [--editable <editable>] [--readable <readable>] [--searchable <searchable>] [--viewable <viewable>] [--hideable <hideable>] [--enabled <enabled>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--entity` | yes | entity key |
| `--key` | yes | attribute key |
| `--ldap` | yes | LDAP attribute name |
| `--nds` | no | NDS name (default: the LDAP name) |
| `--type` | no | String\|Integer\|Boolean\|DN\|Time\|Binary\|LocalizedString … (default String) |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--required` | no | true\|false |
| `--multivalue` | no | true\|false |
| `--editable` | no | true\|false |
| `--readable` | no | true\|false |
| `--searchable` | no | true\|false |
| `--viewable` | no | true\|false |
| `--hideable` | no | true\|false |
| `--enabled` | no | true\|false |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm entity.attr.add tree --entity device --key serial --ldap serialNumber --display "Serial number" --searchable true --dry-run
```

### `entity.attr.set`

Change an entity attribute's flags, type, LDAP name or display labels.

```
bin/idm entity.attr.set <tree> --entity <entity> --key <key> [--ldap <ldap>] [--type <type>] [--display <display>] [--required <required>] [--multivalue <multivalue>] [--editable <editable>] [--readable <readable>] [--searchable <searchable>] [--viewable <viewable>] [--hideable <hideable>] [--enabled <enabled>] [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--entity` | yes | entity key |
| `--key` | yes | attribute key |
| `--ldap` | no | LDAP attribute name |
| `--type` | no | type |
| `--display` | no | display name, lang=text or bare text for English (repeatable; merged per language) |
| `--required` | no | true\|false |
| `--multivalue` | no | true\|false |
| `--editable` | no | true\|false |
| `--readable` | no | true\|false |
| `--searchable` | no | true\|false |
| `--viewable` | no | true\|false |
| `--hideable` | no | true\|false |
| `--enabled` | no | true\|false |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm entity.attr.set tree --entity device --key serial --required true --dry-run
```

### `entity.attr.remove`

Remove an attribute from an entity definition.

```
bin/idm entity.attr.remove <tree> --entity <entity> --key <key> [--driver <driver>] [--dry-run] [--force] [--json]
```

| Argument | Required | Values |
|---|---|---|
| `<tree>` | yes | the IDM-as-code tree to change |
| `--entity` | yes | entity key |
| `--key` | yes | attribute key |
| `--driver` | no | the User Application driver (needed when several drivers have an AppConfig) |

```bash
bin/idm entity.attr.remove tree --entity device --key serial --dry-run
```

<!-- END edit operations -->
