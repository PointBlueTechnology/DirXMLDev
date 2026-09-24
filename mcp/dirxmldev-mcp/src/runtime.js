import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

export function findRepo(start) {
  let dir = path.resolve(start);
  for (;;) {
    if (fs.existsSync(path.join(dir, "bin", "idm"))) return dir;
    const parent = path.dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

export function resolveRuntime(env, cwd) {
  const fromPackage = findRepo(HERE);
  const repoRoot = (env.IDM_HOME && fs.existsSync(path.join(env.IDM_HOME, "bin", "idm")) ? path.resolve(env.IDM_HOME) : null)
    || findRepo(cwd)
    || fromPackage;
  const idmBin = env.IDM_BIN || (repoRoot ? path.join(repoRoot, "bin", "idm") : "idm");
  const appsBin = env.APPS_BIN || (repoRoot ? path.join(repoRoot, "bin", "apps") : "apps");
  const timeoutMs = Number(env.IDM_MCP_TIMEOUT_MS || 180_000);
  return {
    cwd: path.resolve(cwd),
    repoRoot,
    idmBin,
    appsBin,
    env,
    timeoutMs: Number.isFinite(timeoutMs) && timeoutMs > 0 ? timeoutMs : 180_000,
  };
}

export const INSTRUCTIONS = [
  "DirXMLDev MCP wraps the bin/idm CLI (and read-only bin/apps). The as-code tree is the source of truth; this server does not reimplement the engine.",
  "Read and dry-run tools always run: idm.validate, idm.query, idm.show, idm.refs, idm.tree.diff, idm.simulate, idm.vault.diff, idm.vault.deploy.plan, status and trace reads, and fishbone.get / fishbone.reveal.",
  "Mutators (idm.vault.deploy, idm.vault.rollback, idm.driver.start/stop/restart, idm.driver.cache.clear) do nothing unless the server process has IDM_AGENT_ALLOW_WRITE=1 and the call sets confirm to true.",
  "Show idm.vault.deploy.plan to a human before idm.vault.deploy. deleteDriver or deleteAll also requires confirmDeletes true. The server passes the CLI's own --yes and, when you set confirmEnv, --confirm. It never passes --force, --step, or --follow.",
  "Production still needs the CLI's --confirm <env> (confirmEnv). A closed gate returns blocked:true and does not start the CLI.",
  "Tool results never include values from environments.properties or secrets*.properties. idm.context lists environment names and redacted env var names only.",
  "fishbone.refresh only writes <tree>/.dirxmldev/fishbone.refresh so an open diagram reloads. Tree edits stay file edits and bin/idm operations, which this server does not wrap.",
].join(" ");
