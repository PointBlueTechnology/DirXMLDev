/**
 * Agent-callable helpers a future MCP adapter would wrap.
 * Today: dump JSON, resolve a node to a file, and touch the refresh sentinel
 * the extension already watches (docs/vscode-extension-v1.md §6).
 */
import * as fs from "fs";
import * as path from "path";
import { REFRESH_SENTINEL, loadTree } from "./ascode";
import { FishboneModel, allBones, filesForNode, loadFishbone } from "./fishbone";

export function fishboneGet(treeRoot: string, driverName?: string): FishboneModel {
  const tree = loadTree(treeRoot);
  const driver = driverName
    ? tree.drivers.find((d) => d.name === driverName)
    : tree.drivers[0];
  if (!driver) {
    throw new Error("no driver " + (driverName ?? "(first)") + " in " + treeRoot);
  }
  return loadFishbone(tree, driver);
}

/** Touch <tree>/.dirxmldev/fishbone.refresh so an open webview reloads. */
export function fishboneRefresh(treeRoot: string): string {
  const file = path.join(treeRoot, REFRESH_SENTINEL);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, new Date().toISOString() + "\n", "utf8");
  return file;
}

export function fishboneReveal(treeRoot: string, driverName: string, nodeIdOrRef: string): string {
  const model = fishboneGet(treeRoot, driverName);
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
