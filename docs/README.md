# DirXMLDev documentation

Use the tool first. The design notes and the phase history are below them.

`bin/idm` with no arguments is the command reference. `bin/apps --help` is the
Identity Applications reference. When a page and the live help disagree, follow
the help.

## Using the tool

| Guide | Read it when you want to |
|---|---|
| [../README.md](../README.md) | See what DirXMLDev is and copy the first commands |
| [getting-started.md](getting-started.md) | Point an already-built `bin/idm` at a client tree and a vault |
| [tree-layout.md](tree-layout.md) | Understand `driverset.xml`, `drivers/`, `cases/`, and the secrets files |
| [day-to-day.md](day-to-day.md) | Change a policy, deploy, install a package, edit a form, operate a driver |
| [examples/](examples/) | Copy sanitized, fictional snippets |
| [walkthrough.md](walkthrough.md) | Follow the same loop in one long narrative |
| [agents.md](agents.md) | Point any agent at the tool (Cursor, Claude Code, Codex, others) |
| [mcp.md](mcp.md) | Optional MCP server: reads, dry-runs, and gated writes over `bin/idm` |
| [agent-guide.md](agent-guide.md) | The loop and the refusal rules that agent follows |
| [install.md](install.md) | Build `bin/idm` from source and configure a workstation |

## By job

| Job | Page |
|---|---|
| What `validate` checks | [validation.md](validation.md) |
| Deploy, diff, snapshot, rollback, the production gate | [vault-deploy.md](vault-deploy.md) (design note; commands are current) |
| Start, stop, cache, trace, submit | [operate.md](operate.md) (design note; the gate table matches the code) |
| Packages | [packages.md](packages.md) |
| JSON forms and PRDs | [forms.md](forms.md) |
| Workflow activities (`flow.*`) | [workflows.md](workflows.md) |
| Entitlements | [entitlements.md](entitlements.md) |
| Roles, resources, entities, the rest of AppConfig | [appconfig.md](appconfig.md) |
| Identity Applications REST (`bin/apps`) | [idapps-rest.md](idapps-rest.md) |
| Edit operations in detail | [edit-operations.md](edit-operations.md) |

## Designer, clones, and the fishbone

| Job | Page |
|---|---|
| A new Designer project from a vault, without Designer connecting | [howto-fresh-designer-project.md](howto-fresh-designer-project.md) |
| What `export-project --new` writes | [designer-new-project.md](designer-new-project.md) |
| Updating an existing Designer project | [designer-roundtrip.md](designer-roundtrip.md) |
| Carry a customer's vault home as a lab clone | [howto-clone-vault.md](howto-clone-vault.md), [vault-clone.md](vault-clone.md) |
| Read-only policy-flow viewer | [vscode-extension-v1.md](vscode-extension-v1.md), [../extensions/dirxmldev-visual/README.md](../extensions/dirxmldev-visual/README.md) |

## Architecture and history

These describe how the tool was built. They are not the place to learn a command.

| Page | What it is |
|---|---|
| [plan.md](plan.md) | Architecture, phases, decisions, safeguards |
| [model.md](model.md) | The typed model and the as-code file contract |
| [spikes/](spikes/) | Measurements that closed a question. The same facts are in `.claude/skills/dirxml-dev/reference/facts.md` (plain markdown; any agent can read it) |

How an agent is set up, for any product, is [agents.md](agents.md). Claude
Code's loader for the same rules is
[../.claude/skills/dirxml-dev/SKILL.md](../.claude/skills/dirxml-dev/SKILL.md).
Measured facts are
[../.claude/skills/dirxml-dev/reference/facts.md](../.claude/skills/dirxml-dev/reference/facts.md).
