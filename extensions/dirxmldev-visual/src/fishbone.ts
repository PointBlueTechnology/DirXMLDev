/**
 * Policy-set keys from com.pointblue.dirxml.dev.model.PolicySet — do not invent
 * aliases. Labels are Designer's names (PackageInstall.DESIGNER_SET_NAMES /
 * Understanding Policies).
 */
export interface PolicySetDef {
  key: string;
  id: number;
  label: string;
  /** publisher rib, subscriber rib, shared spine, or driver-level resources. */
  channel: "publisher" | "subscriber" | "spine" | "driver";
}

/** Aligned ribs, Identity Vault → Application (Designer overview). */
export const PUBLISHER_RIBS: PolicySetDef[] = [
  { key: "publisher-event", id: 5, label: "Event Transformation", channel: "publisher" },
  { key: "publisher-matching", id: 7, label: "Matching", channel: "publisher" },
  { key: "publisher-create", id: 9, label: "Creation", channel: "publisher" },
  { key: "publisher-placement", id: 13, label: "Placement", channel: "publisher" },
  { key: "publisher-command", id: 11, label: "Command Transformation", channel: "publisher" },
];

export const SUBSCRIBER_RIBS: PolicySetDef[] = [
  { key: "subscriber-event", id: 4, label: "Event Transformation", channel: "subscriber" },
  { key: "subscriber-matching", id: 6, label: "Matching", channel: "subscriber" },
  { key: "subscriber-create", id: 8, label: "Creation", channel: "subscriber" },
  { key: "subscriber-placement", id: 12, label: "Placement", channel: "subscriber" },
  { key: "subscriber-command", id: 10, label: "Command Transformation", channel: "subscriber" },
];

export const SPINE_SETS: PolicySetDef[] = [
  { key: "schema-mapping", id: 0, label: "Schema Mapping", channel: "spine" },
  { key: "input", id: 1, label: "Input Transformation", channel: "spine" },
  { key: "output", id: 2, label: "Output Transformation", channel: "spine" },
];

export const RESOURCE_SETS: PolicySetDef[] = [
  { key: "ecmascript", id: 3, label: "ECMAScript", channel: "driver" },
  { key: "gcv", id: 14, label: "GCVs", channel: "driver" },
  { key: "startup", id: 15, label: "Startup", channel: "driver" },
  { key: "shutdown", id: 16, label: "Shutdown", channel: "driver" },
];

/** Same order as ReadCli.PUB_CHAIN / SUB_CHAIN (engine execution). */
export const PUB_CHAIN_KEYS = [
  "input",
  "schema-mapping",
  "publisher-event",
  "publisher-matching",
  "publisher-create",
  "publisher-placement",
  "publisher-command",
];

export const SUB_CHAIN_KEYS = [
  "subscriber-event",
  "subscriber-matching",
  "subscriber-create",
  "subscriber-placement",
  "subscriber-command",
  "schema-mapping",
  "output",
];

export interface FishbonePolicy {
  id: string;
  ref: string;
  name: string;
  order: number;
  kind: string;
  /** Path relative to the tree root, if the link resolved. */
  file?: string;
  unresolved: boolean;
}

export interface FishboneBone {
  id: string;
  key: string;
  label: string;
  channel: PolicySetDef["channel"];
  setId: number;
  policies: FishbonePolicy[];
}

export interface FishboneFilter {
  id: string;
  file: string;
}

export interface FishboneModel {
  treeRoot: string;
  driverSet: { name: string; dn: string };
  driver: { name: string; dn: string; shimClass: string; dir: string };
  publisher: FishboneBone[];
  subscriber: FishboneBone[];
  spine: FishboneBone[];
  resources: FishboneBone[];
  filter?: FishboneFilter;
}

/**
 * The model comes from `bin/idm query <tree> fishbone <driver> --json`
 * (com.pointblue.dirxml.dev.edit.Fishbone); this module only types it and
 * resolves nodes to files. The tables above document the bones it carries.
 */
export function findBone(model: FishboneModel, id: string): FishboneBone | undefined {
  return allBones(model).find((b) => b.id === id);
}

export function findPolicy(model: FishboneModel, id: string): FishbonePolicy | undefined {
  for (const b of allBones(model)) {
    const p = b.policies.find((x) => x.id === id);
    if (p) {
      return p;
    }
  }
  return undefined;
}

export function filesForNode(model: FishboneModel, nodeId: string): { ref: string; file: string }[] {
  if (nodeId === "config:driver-filter" && model.filter?.file) {
    return [{ ref: "driver-filter", file: model.filter.file }];
  }
  const policy = findPolicy(model, nodeId);
  if (policy) {
    return policy.file ? [{ ref: policy.ref, file: policy.file }] : [];
  }
  const bone = findBone(model, nodeId);
  if (!bone) {
    return [];
  }
  return bone.policies.filter((p) => p.file).map((p) => ({ ref: p.ref, file: p.file! }));
}

export function allBones(model: FishboneModel): FishboneBone[] {
  return [...model.publisher, ...model.subscriber, ...model.spine, ...model.resources];
}

function leaf(ref: string): string {
  const i = ref.lastIndexOf("/");
  return i < 0 ? ref : ref.slice(i + 1);
}
