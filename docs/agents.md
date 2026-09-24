# Using DirXMLDev from an agent

Any agent that can read files and run a shell uses the same interface as a
person: `bin/idm` and `bin/apps`. That CLI is the contract. `bin/idm` with no
arguments is the command list.

An optional MCP server wraps a subset of those commands for clients that call
tools. It shells out to the CLI and does not add flags. See
[MCP](#mcp-optional) and [mcp.md](mcp.md).

The instructions are ordinary markdown. A vendor skill directory is only a
loader for products that auto-read one. If your agent does not, point it at
this page and at [agent-guide.md](agent-guide.md).

## What every agent follows

Work in the **client directory** (the tree, `cases/`, `environments.properties`),
not in the DirXMLDev checkout, unless the task is to change DirXMLDev itself.
Run `bin/idm` from that checkout or by full path.

Before the first edit, read [agent-guide.md](agent-guide.md). It is the loop
and the refusals. Human walkthroughs of the same commands are
[day-to-day.md](day-to-day.md) and [getting-started.md](getting-started.md).

Non-negotiable, whatever the product:

1. Show `vault.diff` and `vault.deploy --dry-run` before anything is written.
   Wait for a person to say yes. Then `--yes` or `--step`.
2. Production (`tier=prd`) needs `--confirm` followed by that environment's
   name, typed by a person, plus a committed tree and a vault that matches
   the last recorded deploy.
3. Never print a secret. Names only. Passwords stay in the secrets file, a
   keychain, a command, or an environment variable.
4. Never pass `--force` to skip a refusal. Fix the cause or report it.
5. A plan full of deletes is a stale tree. Re-import with `import-live`.
   Deleting a driver needs `--delete-driver`. Emptying a kind needs
   `--delete-all`. Do not add either silently.
6. `bin/idm` with no arguments is the command list. If a doc and that text
   disagree, follow `bin/idm`.

`bin/idm` with no arguments, then `bin/idm validate tree/`, is the right first
session. Do not invent flags.

Policy tests against sample events are the DirXML Policy Simulator (`bin/sim`
in that project). This repository is the loop around it: edit, validate,
simulate, diff, deploy, operate.

## Client repository

Commit an `AGENTS.md` at the root of the client repo so whichever agent opens
that repo sees the rules. Start from
[examples/client-AGENTS.md](examples/client-AGENTS.md). Set the path to
`bin/idm` and the path to this checkout's `docs/agent-guide.md`.

That file is the setup. It does not depend on Claude, Cursor, or any other
product. Agents that already read `AGENTS.md` (Cursor, Codex, Copilot coding
agent, and others) pick it up from the client repo. Agents that do not can be
given the same file, or [agent-guide.md](agent-guide.md), as context.

Also commit `tree/` and `cases/`. Do not commit `environments.properties`,
`secrets*.properties`, or `deploy-snapshots/`.

## Claude Code

Claude Code reads a skill when the conversation is in a checkout that contains
it. This repository ships that loader at `.claude/skills/dirxml-dev/SKILL.md`.
The body is the same loop as [agent-guide.md](agent-guide.md), plus recipes
and measured facts under `.claude/skills/dirxml-dev/reference/`.

In a client repo, copy or symlink that skill directory to the client's
`.claude/skills/dirxml-dev/` if you want Claude Code to load it without
opening the DirXMLDev checkout. Other agents ignore that directory. They use
the client `AGENTS.md` instead.

## MCP (optional)

[`mcp/dirxmldev-mcp`](../mcp/dirxmldev-mcp) is a stdio server. It runs `bin/idm`
(and a few read-only `bin/apps` commands). It does not reimplement the engine.
Install, the Cursor `mcp.json` snippet, and the full tool list are in
[mcp.md](mcp.md).

Reads and dry-runs are available as soon as the server is running.
`idm.vault.deploy.plan` is always `vault.deploy --dry-run`.

Writes are a small set: `idm.vault.deploy`, `idm.vault.rollback`,
`idm.driver.start`, `idm.driver.stop`, `idm.driver.restart`,
`idm.driver.cache.clear`. Both gates have to be open or the server does not
start `bin/idm`:

1. `IDM_AGENT_ALLOW_WRITE=1` in the server process. Unset or any other value
   leaves mutators off. Changing it means restarting the server.
2. The tool call sets `confirm` to `true`.

`deleteDriver` or `deleteAll` also needs `confirmDeletes: true`. Production
still needs the CLI confirm: the call sets `confirmEnv` to the environment
name, and the server passes `--confirm <env>`. The server never passes
`--force`, `--step`, or `driver.trace --follow`.

Tree edits (`policy.add`, `gcv.set`, and the rest), `driver.secrets`,
`driver.migrate`, `driver.resync`, `driver.submit`, and `bin/apps` writes
(`request`, `approve`, `deny`, …) are not tools. Run those with the CLI, with
a person reading the dry-run. Tool results strip values from
`environments.properties` and `secrets*.properties`.

## Cursor

Cursor reads `AGENTS.md` in the folder you open. Open the client repo and the
template above is the instruction. Open this checkout and the root
[AGENTS.md](../AGENTS.md) covers changing DirXMLDev; operating a client tree
is this page.

The VS Code / Cursor extension
([extensions/dirxmldev-visual](../extensions/dirxmldev-visual/README.md)) draws
the policy-flow fishbone. It is read-only: it does not deploy and it does not
replace `bin/idm`.

A project rule is optional. One line is enough: follow `docs/agent-guide.md`
and do not write to a vault without a dry-run in the conversation.

## Anything else

Codex, Copilot, Aider, a chat model with a terminal, a script: same commands.

- Give the agent [agent-guide.md](agent-guide.md) and the client `AGENTS.md`.
- Have it run `bin/idm` and `bin/apps --help` before it writes a command.
- Keep the dry-run in the conversation so a person can read it.
- If the agent cannot run a shell, it can still edit files in `tree/`. A
  person then runs `validate`, `simulate`, `vault.diff`, and `vault.deploy`.

Measured behavior of the engine, Designer, and packages is plain markdown at
`.claude/skills/dirxml-dev/reference/facts.md`. Any agent can read it. Read it
before re-deriving a fact with a spike.
