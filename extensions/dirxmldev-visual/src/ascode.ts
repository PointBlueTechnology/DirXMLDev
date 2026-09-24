import * as fs from "fs";
import * as path from "path";

/**
 * Finding a tree on disk is all the extension does with the file system: the
 * manifests themselves are read by `bin/idm` (see idm.ts), never here.
 */
export const TREE_MANIFEST = "driverset.xml";
export const DRIVER_MANIFEST = "driver.xml";
export const REFRESH_SENTINEL = path.join(".dirxmldev", "fishbone.refresh");

export function isTreeRoot(dir: string): boolean {
  return fs.existsSync(path.join(dir, TREE_MANIFEST));
}

/** Walk parents from a file or directory until driverset.xml is found. */
export function findTreeRoot(start: string): string | undefined {
  let dir = fs.existsSync(start) && fs.statSync(start).isDirectory() ? start : path.dirname(start);
  for (;;) {
    if (isTreeRoot(dir)) {
      return dir;
    }
    const parent = path.dirname(dir);
    if (parent === dir) {
      return undefined;
    }
    dir = parent;
  }
}

/** The driver (by its `dir`, relative to the tree root) whose directory holds `absFile`. */
export function driverDirContaining(treeRoot: string, driverDirs: string[], absFile: string): string | undefined {
  const rel = path.relative(treeRoot, absFile).replace(/\\/g, "/");
  for (const d of driverDirs) {
    const prefix = d.replace(/\\/g, "/");
    if (rel === prefix || rel === prefix + "/" + DRIVER_MANIFEST || rel.startsWith(prefix + "/")) {
      return d;
    }
  }
  return undefined;
}
