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
  /** Driver-level <meta key> values from driver.xml. */
  meta: Record<string, string>;
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

/**
 * Engine policy-set ids from {@code com.pointblue.dirxml.dev.model.PolicySet}.
 * Do not invent aliases — keys must match the Java enum.
 */
export const POLICY_SET_BY_ID: Readonly<Record<number, string>> = {
  0: "schema-mapping",
  1: "input",
  2: "output",
  3: "ecmascript",
  4: "subscriber-event",
  5: "publisher-event",
  6: "subscriber-matching",
  7: "publisher-matching",
  8: "subscriber-create",
  9: "publisher-create",
  10: "subscriber-command",
  11: "publisher-command",
  12: "subscriber-placement",
  13: "publisher-placement",
  14: "gcv",
  15: "startup",
  16: "shutdown",
};

const UNKNOWN_LINKAGE = /^linkage\.unknown\.\d+$/;

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
  const meta: Record<string, string> = {};
  for (const el of children(m, "meta")) {
    const key = attr(el, "key");
    if (key) {
      meta[key] = el.text;
    }
  }
  const name = attr(m, "name");
  mergeUnknownLinkage(links, meta, name);
  const configs: ConfigInfo[] = children(m, "config").map((c) => ({
    kind: attr(c, "kind"),
    file: attr(c, "file"),
  }));
  return {
    name,
    dn: attr(m, "dn"),
    shimClass: attr(m, "shim-class"),
    dir,
    artifacts: readArtifacts(m),
    links,
    configs,
    meta,
  };
}

/**
 * Trees exported before PolicySet knew Startup (15) / Shutdown (16) keep those
 * DirXML-Policies values as {@code <meta key="linkage.unknown.n">dn#order#setId</meta>}.
 * Map known set ids onto {@link LinkInfo} the same way named {@code <set key>} links
 * resolve, without duplicating a ref the named set already has.
 */
function mergeUnknownLinkage(links: LinkInfo[], meta: Record<string, string>, driverName: string): void {
  for (const [key, raw] of Object.entries(meta)) {
    if (!UNKNOWN_LINKAGE.test(key)) {
      continue;
    }
    const parsed = parseUnknownLinkage(raw);
    if (!parsed) {
      continue;
    }
    const setKey = POLICY_SET_BY_ID[parsed.setId];
    if (!setKey) {
      continue;
    }
    const ref = refFromLinkageDn(parsed.dn, driverName);
    if (links.some((l) => l.setKey === setKey && l.ref === ref)) {
      continue;
    }
    links.push({ setKey, ref, order: parsed.order });
  }
}

/** Vault / as-code unknown-linkage payload: {@code dn#order#setId}. */
export function parseUnknownLinkage(raw: string): { dn: string; order: number; setId: number } | undefined {
  const v = raw.trim();
  const h2 = v.lastIndexOf("#");
  const h1 = h2 < 0 ? -1 : v.lastIndexOf("#", h2 - 1);
  if (h1 < 0) {
    return undefined;
  }
  const order = Number(v.slice(h1 + 1, h2));
  const setId = Number(v.slice(h2 + 1));
  if (!Number.isFinite(order) || !Number.isFinite(setId)) {
    return undefined;
  }
  return { dn: v.slice(0, h1), order, setId };
}

/**
 * Same rule as {@code ExportReader.resolveRef}: first CN is the artifact name;
 * second CN of Library / Publisher / Subscriber picks scope; anything else is
 * driver-scope of this driver.
 */
export function refFromLinkageDn(dn: string, driverName: string): string {
  const comps = dn.split(",", 3);
  const name = stripCn(comps[0] ?? "");
  const second = stripCn(comps[1] ?? "");
  if (second.toLowerCase() === "library") {
    return "library/" + name;
  }
  if (second.toLowerCase() === "publisher") {
    return "drivers/" + driverName + "/publisher/" + name;
  }
  if (second.toLowerCase() === "subscriber") {
    return "drivers/" + driverName + "/subscriber/" + name;
  }
  return "drivers/" + driverName + "/" + name;
}

function stripCn(comp: string): string {
  const s = comp.trim();
  const eq = s.indexOf("=");
  return eq >= 0 ? s.slice(eq + 1).trim() : s;
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
