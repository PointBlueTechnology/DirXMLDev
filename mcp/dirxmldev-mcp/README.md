# dirxmldev-mcp

Stdio MCP server that wraps [`bin/idm`](../../bin/idm) and read-only [`bin/apps`](../../bin/apps). Install, Cursor snippet, and the safety model: [docs/mcp.md](../../docs/mcp.md).

```bash
npm install
node src/index.js
```

Mutators stay off unless this process has `IDM_AGENT_ALLOW_WRITE=1` **and** the call sets `confirm: true`. `idm.vault.deploy.plan` is always `--dry-run`. Results never include values from `environments.properties` or `secrets*.properties`.

`npm test` starts the server, lists tools, and runs the read / dry-run / fishbone tools against `extensions/dirxmldev-visual/sample-tree`.
