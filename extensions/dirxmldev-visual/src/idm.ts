/**
 * The bridge to `bin/idm`, the one reader of an IDM-as-code tree's manifests.
 * The extension draws what `query <tree> fishbone <driver> --json` returns and
 * picks drivers from `query <tree> drivers --json`; it parses no XML itself
 * (docs/vscode-extension-v1.md §6.3).
 *
 * Where the launcher is, in order: the `dirxmldev.idmPath` setting (passed in by
 * the extension), `IDM_HOME/bin/idm`, `bin/idm` in the tree's ancestors, `bin/idm`
 * in a workspace folder or its ancestors, and finally `idm` on the PATH.
 */
import { execFile } from "child_process";
import * as fs from "fs";
import * as path from "path";
import { FishboneModel } from "./fishbone";

export interface DriverListing {
  treeRoot: string;
  driverSet: { name: string; dn: string };
  drivers: { name: string; dn: string; shimClass: string; dir: string }[];
}

export interface IdmLocation {
  /** The launcher to execute (`…/bin/idm`, `…\bin\idm.cmd`, or `idm`). */
  command: string;
  /** Where it was found, for messages. */
  source: string;
}

const LAUNCHERS = process.platform === "win32" ? ["bin/idm.cmd", "bin/idm"] : ["bin/idm"];

export function findIdm(treeRoot: string, workspaceFolders: string[] = [], configured?: string): IdmLocation {
  if (configured && configured.trim()) {
    return { command: configured.trim(), source: "setting dirxmldev.idmPath" };
  }
  const home = process.env["IDM_HOME"];
  if (home) {
    const hit = launcherIn(home);
    if (hit) {
      return { command: hit, source: "IDM_HOME" };
    }
  }
  for (const start of [treeRoot, ...workspaceFolders]) {
    const hit = launcherUpwards(start);
    if (hit) {
      return { command: hit, source: path.dirname(path.dirname(hit)) };
    }
  }
  return { command: "idm", source: "PATH" };
}

function launcherIn(dir: string): string | undefined {
  for (const rel of LAUNCHERS) {
    const p = path.join(dir, rel);
    if (fs.existsSync(p)) {
      return p;
    }
  }
  return undefined;
}

function launcherUpwards(start: string): string | undefined {
  let dir = start;
  for (;;) {
    const hit = launcherIn(dir);
    if (hit) {
      return hit;
    }
    const parent = path.dirname(dir);
    if (parent === dir) {
      return undefined;
    }
    dir = parent;
  }
}

export function runIdmJson<T>(idm: IdmLocation, args: string[]): Promise<T> {
  return new Promise((resolve, reject) => {
    execFile(
      idm.command,
      args,
      { maxBuffer: 64 * 1024 * 1024, timeout: 120_000, windowsHide: true },
      (err, stdout, stderr) => {
        if (err) {
          const detail = (stderr || err.message || "").toString().trim().split("\n").slice(-3).join(" ");
          reject(new Error(`${idm.command} ${args.join(" ")}: ${detail || "failed"} (launcher from ${idm.source})`));
          return;
        }
        try {
          resolve(JSON.parse(stdout.toString()) as T);
        } catch (e) {
          reject(new Error(`${idm.command}: not JSON: ${String(e)}`));
        }
      },
    );
  });
}

export function listDrivers(idm: IdmLocation, treeRoot: string): Promise<DriverListing> {
  return runIdmJson<DriverListing>(idm, ["query", treeRoot, "drivers", "--json"]);
}

export function loadFishbone(idm: IdmLocation, treeRoot: string, driver: string): Promise<FishboneModel> {
  return runIdmJson<FishboneModel>(idm, ["query", treeRoot, "fishbone", driver, "--json"]);
}
