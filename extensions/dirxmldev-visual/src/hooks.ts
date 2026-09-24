/**
 * Agent-callable helpers a future MCP adapter would wrap.
 * Today: the fishbone JSON (from `bin/idm`), a node resolved to a file, and the
 * refresh sentinel the extension already watches (docs/vscode-extension-v1.md §6).
 */
import * as fs from "fs";
import * as path from "path";
import { REFRESH_SENTINEL } from "./ascode";
import { FishboneModel, allBones, filesForNode } from "./fishbone";
import { findIdm, listDrivers, loadFishbone } from "./idm";

export async function fishboneGet(treeRoot: string, driverName?: string): Promise<FishboneModel> {
  const idm = findIdm(treeRoot);
  let name = driverName;
  if (!name) {
    const listing = await listDrivers(idm, treeRoot);
    name = listing.drivers[0]?.name;
    if (!name) {
      throw new Error("no driver in " + treeRoot);
    }
  }
  return loadFishbone(idm, treeRoot, name);
}

/** Touch <tree>/.dirxmldev/fishbone.refresh so an open webview reloads. */
export function fishboneRefresh(treeRoot: string): string {
  const file = path.join(treeRoot, REFRESH_SENTINEL);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, new Date().toISOString() + "\n", "utf8");
  return file;
}

export async function fishboneReveal(treeRoot: string, driverName: string, nodeIdOrRef: string): Promise<string> {
  const model = await fishboneGet(treeRoot, driverName);
  const byId = filesForNode(model, nodeIdOrRef);
  if (byId[0]) {
    return path.join(model.treeRoot, byId[0].file);
  }
  for (const b of allBones(model)) {
    if (b.key === nodeIdOrRef || b.id === nodeIdOrRef) {
      const f = filesForNode(model, b.id);
      if (f[0]) {
        return path.join(model.treeRoot, f[0].file);
      }
    }
    for (const p of b.policies) {
      if (p.ref === nodeIdOrRef || p.id === nodeIdOrRef) {
        if (!p.file) {
          throw new Error("unresolved ref " + nodeIdOrRef);
        }
        return path.join(model.treeRoot, p.file);
      }
    }
  }
  throw new Error("no fishbone node " + nodeIdOrRef);
}
