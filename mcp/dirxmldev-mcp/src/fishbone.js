import fs from "node:fs";
import path from "node:path";

const SECTIONS = ["publisher", "subscriber", "spine", "resources"];

function bones(model) {
  return SECTIONS.flatMap((s) => (Array.isArray(model?.[s]) ? model[s] : []));
}

/**
 * Resolve a fishbone node to a content path relative to the tree.
 * Matches the visual stub: bone key, policy ref, node id, or artifact path.
 * Returns the first resolved file, the same choice as filesForNode()[0].
 */
export function revealRelative(model, ref) {
  if (model?.filter && (ref === model.filter.id || ref === model.filter.file || ref === "driver-filter")) {
    if (!model.filter.file) throw new Error("driver filter has no file");
    return model.filter.file;
  }
  for (const b of bones(model)) {
    if (b.id === ref || b.key === ref) {
      const files = (b.policies || []).filter((p) => p.file);
      if (!files.length) throw new Error(`bone ${ref} has no resolved policy file`);
      return files[0].file;
    }
  }
  for (const b of bones(model)) {
    for (const p of b.policies || []) {
      if (p.ref === ref || p.id === ref || p.file === ref) {
        if (!p.file) throw new Error(`unresolved ref ${ref}`);
        return p.file;
      }
    }
  }
  throw new Error(`no fishbone node ${ref}`);
}

/** Absolute path under the tree. Rejects a relative file that escapes the root. */
export function resolveInside(treeRoot, rel) {
  const root = path.resolve(treeRoot);
  const abs = path.resolve(root, rel);
  if (abs !== root && !abs.startsWith(root + path.sep)) {
    throw new Error("resolved path escapes the tree");
  }
  return abs;
}

/**
 * Touch `<tree>/.dirxmldev/fishbone.refresh` so an open fishbone webview reloads.
 * Refuses a directory that is not an as-code tree. Does not write policies.
 */
export function refreshSentinel(tree) {
  const root = path.resolve(tree);
  if (!fs.existsSync(path.join(root, "driverset.xml"))) {
    throw new Error(`not an IDM-as-code tree (missing driverset.xml): ${root}`);
  }
  const file = path.join(root, ".dirxmldev", "fishbone.refresh");
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, new Date().toISOString() + "\n", "utf8");
  return file;
}
