import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { environmentNames, secretsBasenames } from "./redact.js";
import { safeToken } from "./safe.js";

const ENV_ALLOW = /^(IDM_|SIM_|DIRXML_)/;

function gitSha(cwd) {
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], {
      cwd,
      encoding: "utf8",
      timeout: 5000,
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return null;
  }
}

function driverNames(tree) {
  const dir = path.join(tree, "drivers");
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((d) => d.isDirectory() && fs.existsSync(path.join(dir, d.name, "driver.xml")))
    .map((d) => d.name)
    .sort();
}

function redactedEnv(env) {
  const out = {
    IDM_AGENT_ALLOW_WRITE: env.IDM_AGENT_ALLOW_WRITE === "1" ? "1" : "(not 1)",
  };
  for (const key of Object.keys(env).sort()) {
    if (key === "IDM_AGENT_ALLOW_WRITE") continue;
    if (!ENV_ALLOW.test(key) && key !== "JAVA_HOME") continue;
    const value = env[key];
    out[key] = value ? "(set)" : "(empty)";
  }
  return out;
}

function envFileReport(file) {
  const present = fs.existsSync(file);
  return {
    path: file,
    present,
    names: present ? environmentNames(file) : [],
  };
}

/**
 * Orient the caller: tree on disk, git SHA, whether the write gate is open,
 * and the names (never the values) of configured environments.
 */
export function buildContext({ cwd, repoRoot, env, tree }) {
  const treePath = tree ? path.resolve(safeToken("tree", tree)) : cwd;
  const hasDriverset = fs.existsSync(path.join(treePath, "driverset.xml"));
  const files = [];
  if (env.IDM_ENVIRONMENTS) files.push(envFileReport(env.IDM_ENVIRONMENTS));
  files.push(envFileReport(path.join(cwd, "environments.properties")));
  if (repoRoot && path.resolve(repoRoot) !== path.resolve(cwd)) {
    files.push(envFileReport(path.join(repoRoot, "environments.properties")));
  }
  const secretDirs = [cwd];
  if (repoRoot) secretDirs.push(repoRoot);
  const secretsFiles = [...new Set(secretDirs.flatMap((d) => secretsBasenames(d)))];
  return {
    cwd,
    repoRoot: repoRoot || null,
    gitSha: gitSha(repoRoot || cwd),
    writesAllowed: env.IDM_AGENT_ALLOW_WRITE === "1",
    tree: {
      path: treePath,
      driversetXml: hasDriverset,
      drivers: hasDriverset ? driverNames(treePath) : [],
    },
    environments: files,
    secretsFiles,
    env: redactedEnv(env),
  };
}
