# DirXMLDev MCP server

A thin stdio MCP server in [`mcp/dirxmldev-mcp`](../mcp/dirxmldev-mcp) that shells out to `bin/idm` (and, for a few reads, `bin/apps`). It does not reimplement the engine. Edit operations stay on the CLI and in the tree; this server is the read / dry-run surface plus a small set of gated vault and driver writes.

`bin/idm` with no arguments is the command list these tools were checked against. The server does not invent flags.

## Wire it into Cursor

From the DirXMLDev checkout:

```bash
cd mcp/dirxmldev-mcp && npm install
```

`~/.cursor/mcp.json` (or the project `.cursor/mcp.json`), with the checkout path filled in:

```json
{
  "mcpServers": {
    "dirxmldev": {
      "command": "node",
      "args": ["/ABS/PATH/DirXMLDev/mcp/dirxmldev-mcp/src/index.js"],
      "env": {
        "IDM_AGENT_ALLOW_WRITE": "0"
      }
    }
  }
}
```

The server's working directory should be the checkout (or the client repo) that holds `environments.properties`, the same place you run `bin/idm`. JDK 21 and the simulator jar are whatever `bin/idm` already requires. Leave the write flag at `0` until a human wants mutators on this process.

## Safety model

Two gates, both required, before any mutator starts the CLI:

1. **Process gate.** `IDM_AGENT_ALLOW_WRITE=1` in the MCP server's environment. Any other value, including unset, disables mutators. Changing it means restarting the server.
2. **Call gate.** The tool argument `confirm` must be `true`. That is the per-call confirmation. Show `idm.vault.deploy.plan` (or `idm.vault.diff`) to a human first.

`deleteDriver` or `deleteAll` on `idm.vault.deploy` also needs `confirmDeletes: true`.

The server then passes the CLI's own flags: `--yes`, and `--confirm <env>` when the call sets `confirmEnv`. Production still needs that CLI confirm. The server never passes `--force`, `--step`, or `driver.trace --follow`. A closed gate returns `blocked: true` and does not start `bin/idm`.

Mutators: `idm.vault.deploy`, `idm.vault.rollback`, `idm.driver.start`, `idm.driver.stop`, `idm.driver.restart`, `idm.driver.cache.clear`. Each is annotated `destructiveHint: true`.

`idm.vault.deploy.plan` is always `vault.deploy --dry-run`. It cannot be turned into a write.

`fishbone.refresh` only writes `<tree>/.dirxmldev/fishbone.refresh` so an open diagram reloads. It does not write policies and is not a vault mutator.

## Secrets

Tool results never include values from `environments.properties` or `secrets*.properties`. Those files are read only to know which strings to strip from stdout and stderr. `idm.context` lists environment names, secret-file basenames, and whether `IDM_*` / `SIM_*` / `JAVA_HOME` are set — not their values. `apps.token` is invoked without `--json`; the CLI prints the token length, and `access_token` is scrubbed if it appears anyway.

## Results

Every tool returns one JSON object. `schemaVersion` is `1` on that envelope. When the CLI's usage lists `--json`, the parsed document is `result` (the CLI's own JSON has no `schemaVersion` field; it is not added). Otherwise the CLI text is `text`. `exitCode` is the process status. A validate finding or a non-empty diff is `ok: false` with the CLI output, not a transport failure.

## Tools

Reads and dry-runs: `idm.context`, `idm.check`, `idm.validate`, `idm.query`, `idm.show`, `idm.refs`, `idm.package.diff`, `idm.tree.diff`, `idm.simulate`, `idm.form.list`, `idm.form.show`, `idm.prd.list`, `idm.prd.show`, `idm.prd.flow`, `idm.package.status`, `idm.vault.diff`, `idm.vault.verify`, `idm.vault.deploy.plan`, `idm.driverset.status`, `idm.driver.status`, `idm.driver.cache.view`, `idm.engine.version`, `idm.engine.stats`, `idm.driver.trace.show`, `idm.driver.trace.tail`.

Identity Applications, read-only: `apps.permission`, `apps.tasks`, `apps.task`, `apps.history`, `apps.token`.

Fishbone, the names in [`extensions/dirxmldev-visual/mcp/tools.json`](../extensions/dirxmldev-visual/mcp/tools.json): `fishbone.get` and `fishbone.reveal` run `bin/idm query <tree> fishbone <driver> --json` (`drivers --json` when the driver is omitted). `fishbone.reveal` returns the content file path. `fishbone.refresh` touches the sentinel.

Not wrapped, on purpose: tree edit operations (`policy.*`, `gcv.*`, …), `driver.secrets`, `driver.migrate` / `resync` / `submit`, and `bin/apps` writes (`request`, `approve`, `deny`, …). Use `bin/idm` / `bin/apps` for those, with a human.

## Tests

`cd mcp/dirxmldev-mcp && npm test`. The suite lists tools over stdio, runs validate, the deploy dry-run, and the fishbone tools against `extensions/dirxmldev-visual/sample-tree` through a fixture CLI (so it does not need the simulator jars), and checks that a closed write gate does not start that process.
