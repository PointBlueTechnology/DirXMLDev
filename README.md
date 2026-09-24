# DirXMLDev

DirXMLDev is a Designer-optional toolchain for OpenText / NetIQ Identity
Manager. The **driver set lives as files** (IDM-as-code). You change those
files with `bin/idm`, prove them with the engine's own compilers and the
[DirXML Policy Simulator](https://github.com/PointBlueTechnology/DirXMLSimulator),
then deploy and operate the Identity Vault over LDAPS and DirXML extended
operations. Designer stays an import/export target for teams that still use it.

A human operator and an agent run the same commands. The tree in git is the
source of truth. The vault is a deploy target.

## Who it is for

- An IDM engineer who wants policies, filters, GCVs, forms, and workflows in
  git, with a diff and a snapshot before anything is written to a vault.
- An agent (the Claude skill in `.claude/skills/dirxml-dev/`) doing that loop
  under the same safety rules.
- A team that still opens Designer: `export` and `export-project` hand the
  tree back as a configuration file or an updated project.

## What you can do

```
import  →  edit  →  validate  →  simulate  →  vault.diff / vault.deploy  →  operate
```

| Step | Command | What you get |
|---|---|---|
| Bring a driver set in | `import`, `import-project`, `import-ldif`, `import-live` | One readable file per object under `tree/` |
| See what is there | `query`, `show`, `refs`, `docs`, `query fishbone` | Chains, GCVs, who references what, a generated write-up, the policy-flow fishbone |
| Change it | edit the policy file, or an operation (`policy.add`, `gcv.set`, `form.field.add`, `flow.activity.add`, `package.install`, …) | A transaction: load, apply, validate, write only if no new error |
| Check it offline | `validate` | The engine's compilers, in the driver's context, plus linkage, GCV, filter, form, and flow checks |
| Prove a policy change | `simulate --cases … --against …` | The regression corpus run on the new tree and diffed against the previous tree |
| See the vault delta | `vault.diff` | Per-object added / changed / removed. Nothing is written |
| Deploy | `vault.deploy --dry-run`, then `--yes` or `--step` | Plan, LDIF snapshot, LDAP writes, driver restart, re-read, an audit line |
| Operate | `driverset.status`, `driver.start` / `stop` / `restart`, `cache`, `trace`, `migrate`, `resync`, `secrets`, `submit` | The same environments and audit log as deploy |
| Prove a workflow | `bin/apps` | Identity Applications REST: request, tasks, approve, history |
| Hand it back | `export`, `export-project` | A Designer driver-set export, or an update of an existing project |

A read-only VS Code / Cursor extension
([extensions/dirxmldev-visual](extensions/dirxmldev-visual/README.md)) draws
the classic policy-flow fishbone from `bin/idm query … fishbone`. It does not
write the tree or talk to a vault.

`bin/idm` with no arguments lists every command and flag. That text is the
contract. Where an older design note disagrees with it, follow `bin/idm`.

## Quick start

These assume `bin/idm` is already built and you are in a **client** directory
(not this repository). Full setup of that directory, including a redacted
`environments.properties`, is [docs/getting-started.md](docs/getting-started.md).
Names such as `stg` and `AD Driver` are placeholders for your environment name
and your driver's name.

```bash
bin/idm import-live tree/ --env stg
bin/idm validate tree/
bin/idm vault.diff tree/ --env stg
bin/idm query tree/ chain "AD Driver" sub
bin/idm query tree/ drivers
```

A policy change, then a staging deploy. Read the dry-run before `--yes`.

```bash
bin/idm policy.add tree/ --driver "AD Driver" --scope subscriber \
  --name "ACME-sub-ctp-NormalizeTitle" --link subscriber-command \
  --content-file normalize-title.policy.xml
bin/idm validate tree/
bin/idm simulate tree/ --cases cases/ --against /path/to/tree-before
bin/idm vault.diff tree/ --env stg
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --dry-run
bin/idm vault.deploy tree/ --env stg --driver "AD Driver" --yes
```

Production is the same commands plus the gate. A person types the environment
name after `--confirm`:

```bash
bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --dry-run
bin/idm vault.deploy tree/ --env prd --driver "AD Driver" --yes --confirm prd
```

More workflows, including forms, packages, and driver operations:
[docs/day-to-day.md](docs/day-to-day.md). Sanitized samples:
[docs/examples/](docs/examples/).

## Safety

- Show the plan before you write. `vault.diff`, then `vault.deploy --dry-run`,
  then a human's yes, then `--yes` or `--step`.
- A production environment (`tier=prd`) requires `--confirm <env>`, a committed
  tree, and a vault that matches the last recorded deploy. If it does not,
  `--capture-drift` records the vault's current state first.
- The deployer refuses to delete a driver unless you pass `--delete-driver`,
  and refuses to empty every object of a kind (entitlements, forms, PRDs, …)
  unless you pass `--delete-all <kind>`. A plan full of deletes usually means
  the tree is stale: re-import with `import-live`.
- Secrets stay in `secrets-<env>.properties` or come from a keychain, a
  command, or an environment variable. The tool prints names, never values.
  Do not paste passwords, snapshots, or client policy content into chat.
- `driver.stop` leaves the cache in place, and events keep queueing.
  `driver.cache clear` prints the count and the first and last event, writes
  them to a snapshot, and on staging or production also requires
  `--confirm <env>`.
- Edit operations refuse rather than leave the tree invalid. Do not pass
  `--force` to skip a refusal; fix the cause.

## Guides

Start at [docs/README.md](docs/README.md). The short path:

1. [Getting started](docs/getting-started.md) — client tree, environments file, first import.
2. [Tree layout](docs/tree-layout.md) — what `driverset.xml`, `drivers/`, `cases/`, and the secrets files are.
3. [Day to day](docs/day-to-day.md) — policy change, deploy, packages, forms, operate.
4. [Examples](docs/examples/) — fictional, sanitized snippets.
5. [Agent guide](docs/agent-guide.md) — the same loop, written for an agent at the shell.

Building from source is [docs/install.md](docs/install.md).

## Status

Phases 0–7, JSON provisioning forms, and workflow authoring (`flow.*`) are
built. Vault deploy, driver operations, and Identity Applications proofs have
been run on lab vaults. The phase-by-phase record, the architecture, and the
decisions already made are in [docs/plan.md](docs/plan.md). Design notes under
`docs/` that still say "building" are historical; the commands in `bin/idm`
are what shipped.

## Install and build

JDK 21, Maven, and the Identity Manager engine jars in `lib/` (proprietary,
never committed; `lib/` may be a symlink to the simulator's `lib/`). Build the
DirXML Policy Simulator first so `dirxml-simulator` resolves from `~/.m2`.
`bin/idm` finds JDK 21 via `IDM_JAVA_HOME` (or `java_home -v 21` on macOS),
compiles on first use if `target/classes` is missing, and puts the simulator
jar on the classpath (`IDM_SIM_VERSION` selects another installed version).

```bash
export IDM_JAVA_HOME=/path/to/jdk-21
mvn test
bin/idm
```

Windows: `bin\idm.cmd`. Step-by-step, including the engine jars and a client
`environments.properties`: [docs/install.md](docs/install.md).

## This repository

| Path | What it is |
|---|---|
| `bin/idm`, `bin/apps` | The CLI and the Identity Applications helper |
| `src/` | Model, as-code, validate, edit, simulate, deploy, operate |
| `docs/` | User guides, then design notes and spikes |
| `.claude/skills/dirxml-dev/` | Agent skill: the loop, the rules, the recipes |
| `extensions/dirxmldev-visual/` | Read-only policy-flow fishbone |
| `lib/` | Engine jars (gitignored) |

Client trees, LDIFs, traces, `environments.properties`, and `secrets*.properties`
stay in the client's repository. They are gitignored here.

## For agents

The skill in `.claude/skills/dirxml-dev/` is picked up when Claude Code runs
in this repo. In a client repo, copy or symlink that directory to
`.claude/skills/`. It points at [docs/agent-guide.md](docs/agent-guide.md).
The simulator's `dirxml-policy-testing` skill covers running policies against
sample events; this one is the loop around it.
