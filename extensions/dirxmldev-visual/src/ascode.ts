import * as fs from "fs";
import * as path from "path";
import { attr, child, children, parseXml, XmlElem } from "./xml";

/** Workspace files that mean "this is DirXMLDev / IDM", matching docs/vscode-extension-v1.md. */
export const TREE_MANIFEST = "driverset.xml";
export const DRIVER_MANIFEST = "driver.xml";
export const LIBRARY_MANIFEST = "library.xml";
export const REFRESH_SENTINEL = path.join(".dirxmldev", "fishbone.refresh");

export interface DriverSetInfo {
  name: string;
  dn: string;
  root: string;
}

export interface ArtifactInfo {
  kind: string;
  scope: string;
  name: string;
  /** Path relative to the driver directory (or library directory). */
  file: string;
  contentType: string;
}

export interface LinkInfo {
  setKey: string;
  ref: string;
  order: number;
}

export interface ConfigInfo {
  kind: string;
  file: string;
}

export interface DriverInfo {
  name: string;
  dn: string;
  shimClass: string;
  /** Directory relative to the tree root, from driverset.xml @dir. */
  dir: string;
  artifacts: ArtifactInfo[];
  links: LinkInfo[];
  configs: ConfigInfo[];
}

export interface LibraryInfo {
  artifacts: ArtifactInfo[];
}

export interface AsCodeTree {
  root: string;
  driverSet: DriverSetInfo;
  library: LibraryInfo;
  drivers: DriverInfo[];
}

export function isTreeRoot(dir: string): boolean {
  return fs.existsSync(path.join(dir, TREE_MANIFEST));
}

/** Walk parents from a file or directory until driverset.xml is found. */
export function findTreeRoot(start: string): string | undefined {
  let dir = fs.existsSync(start) && fs.statSync(start).isDirectory() ? start : path.dirname(start);
  while (true) {
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

export function loadTree(root: string): AsCodeTree {
  const xml = fs.readFileSync(path.join(root, TREE_MANIFEST), "utf8");
  const dsm = parseXml(xml);
  const drivers: DriverInfo[] = [];
  for (const d of children(dsm, "driver")) {
    const dir = attr(d, "dir");
    if (!dir) {
      continue;
    }
    const driverDir = path.join(root, dir);
    const manifestPath = path.join(driverDir, DRIVER_MANIFEST);
    if (!fs.existsSync(manifestPath)) {
      continue;
    }
    drivers.push(readDriver(parseXml(fs.readFileSync(manifestPath, "utf8")), dir));
  }
  let library: LibraryInfo = { artifacts: [] };
  const libManifest = path.join(root, "library", LIBRARY_MANIFEST);
  if (fs.existsSync(libManifest)) {
    library = { artifacts: readArtifacts(parseXml(fs.readFileSync(libManifest, "utf8"))) };
  }
  return {
    root,
    driverSet: { name: attr(dsm, "name"), dn: attr(dsm, "dn"), root },
    library,
    drivers,
  };
}

function readDriver(m: XmlElem, dir: string): DriverInfo {
  const links: LinkInfo[] = [];
  const linkage = child(m, "linkage");
  if (linkage) {
    for (const set of children(linkage, "set")) {
      const setKey = attr(set, "key");
      for (const l of children(set, "link")) {
        links.push({
          setKey,
          ref: attr(l, "ref"),
          order: Number(attr(l, "order") || "0"),
        });
      }
    }
  }
  const configs: ConfigInfo[] = children(m, "config").map((c) => ({
    kind: attr(c, "kind"),
    file: attr(c, "file"),
  }));
  return {
    name: attr(m, "name"),
    dn: attr(m, "dn"),
    shimClass: attr(m, "shim-class"),
    dir,
    artifacts: readArtifacts(m),
    links,
    configs,
  };
}

function readArtifacts(m: XmlElem): ArtifactInfo[] {
  return children(m, "artifact").map((a) => ({
    kind: attr(a, "kind"),
    scope: attr(a, "scope"),
    name: attr(a, "name"),
    file: attr(a, "file"),
    contentType: attr(a, "content-type"),
  }));
}

export function artifactPath(driverName: string | undefined, a: ArtifactInfo): string {
  if (a.scope === "library" || !driverName) {
    return "library/" + a.name;
  }
  if (a.scope === "subscriber") {
    return "drivers/" + driverName + "/subscriber/" + a.name;
  }
  if (a.scope === "publisher") {
    return "drivers/" + driverName + "/publisher/" + a.name;
  }
  return "drivers/" + driverName + "/" + a.name;
}

/** Absolute path of an artifact content file, or undefined if the manifest has no file. */
export function artifactFile(tree: AsCodeTree, driver: DriverInfo | undefined, a: ArtifactInfo): string | undefined {
  if (!a.file) {
    return undefined;
  }
  if (!driver || a.scope === "library") {
    return path.join(tree.root, "library", a.file);
  }
  return path.join(tree.root, driver.dir, a.file);
}

export function indexArtifacts(tree: AsCodeTree, driver: DriverInfo): Map<string, { artifact: ArtifactInfo; driver?: DriverInfo }> {
  const idx = new Map<string, { artifact: ArtifactInfo; driver?: DriverInfo }>();
  for (const a of tree.library.artifacts) {
    idx.set(artifactPath(undefined, { ...a, scope: a.scope || "library" }), { artifact: a });
  }
  for (const a of driver.artifacts) {
    idx.set(artifactPath(driver.name, a), { artifact: a, driver });
  }
  return idx;
}

export function driverContaining(tree: AsCodeTree, absFile: string): DriverInfo | undefined {
  const rel = path.relative(tree.root, absFile).replace(/\\/g, "/");
  for (const d of tree.drivers) {
    const prefix = d.dir.replace(/\\/g, "/");
    if (rel === prefix || rel === prefix + "/" + DRIVER_MANIFEST || rel.startsWith(prefix + "/")) {
      return d;
    }
  }
  return undefined;
}
