import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { describe, it } from "node:test";
import { findTreeRoot } from "../ascode";
import { filesForNode, PUBLISHER_RIBS, RESOURCE_SETS, SPINE_SETS, SUBSCRIBER_RIBS } from "../fishbone";
import { fishboneGet, fishboneRefresh, fishboneReveal } from "../hooks";
import { findIdm } from "../idm";
import { fishboneHtml, fishboneLayout } from "../webview";

/**
 * Every model here comes from `bin/idm query … fishbone --json`, the one reader of
 * the manifests: these tests need the DirXMLDev checkout built (the launcher is
 * found upwards from sample-tree).
 */
const sample = path.resolve(__dirname, "..", "..", "sample-tree");

describe("sample as-code tree", () => {
  it("is a driverset.xml tree and walk-from-policy finds it", () => {
    assert.equal(findTreeRoot(sample), sample);
    const policy = path.join(sample, "drivers", "AD Driver", "subscriber", "sub-etp_Scoping.policy.xml");
    assert.equal(findTreeRoot(policy), sample);
  });

  it("finds bin/idm above the sample tree", () => {
    const idm = findIdm(sample);
    assert.ok(idm.command.endsWith(path.join("bin", "idm")), idm.command);
    assert.ok(fs.existsSync(idm.command));
  });

  it("loads the AD Driver with PolicySet keys from the Java enum", async () => {
    const model = await fishboneGet(sample, "AD Driver");
    assert.equal(model.driverSet.name, "driverset1");
    assert.equal(model.driver.name, "AD Driver");
    assert.equal(model.driver.dir, "drivers/AD Driver");
    const keys = new Set(
      [...model.publisher, ...model.subscriber, ...model.spine, ...model.resources]
        .filter((b) => b.policies.length > 0)
        .map((b) => b.key),
    );
    for (const k of [
      "subscriber-event",
      "subscriber-matching",
      "subscriber-create",
      "subscriber-placement",
      "subscriber-command",
      "publisher-event",
      "publisher-matching",
      "publisher-create",
      "publisher-placement",
      "publisher-command",
      "schema-mapping",
      "input",
      "output",
      "ecmascript",
      "gcv",
      "startup",
    ]) {
      assert.ok(keys.has(k), "missing set " + k);
    }
  });
});

describe("fishbone model", () => {
  it("puts Designer ribs on both channels and resolves files", async () => {
    const model = await fishboneGet(sample, "AD Driver");
    assert.equal(model.driver.name, "AD Driver");
    assert.equal(model.publisher.length, PUBLISHER_RIBS.length);
    assert.equal(model.subscriber.length, SUBSCRIBER_RIBS.length);
    assert.deepEqual(model.publisher.map((b) => b.key), PUBLISHER_RIBS.map((b) => b.key));
    assert.deepEqual(model.subscriber.map((b) => [b.key, b.label, b.setId]), SUBSCRIBER_RIBS.map((b) => [b.key, b.label, b.id]));
    assert.equal(model.spine.map((b) => b.key).join(","), SPINE_SETS.map((b) => b.key).join(","));

    const etp = model.subscriber.find((b) => b.key === "subscriber-event")!;
    assert.ok(etp.policies.length >= 2, "library + channel policy");
    const scoping = etp.policies.find((p) => p.name === "sub-etp_Scoping");
    assert.ok(scoping && !scoping.unresolved);
    assert.equal(scoping!.file, "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml");
    assert.ok(fs.existsSync(path.join(sample, scoping!.file!)));

    const lib = etp.policies.find((p) => p.ref.startsWith("library/"));
    assert.ok(lib && lib.file === "library/lib-common-event.policy.xml");

    const schema = model.spine.find((b) => b.key === "schema-mapping")!;
    assert.equal(schema.policies[0]!.file, "drivers/AD Driver/sch_Map.policy.xml");

    const files = filesForNode(model, scoping!.id);
    assert.equal(files[0]!.file, scoping!.file);
    const boneFiles = filesForNode(model, "bone:schema-mapping");
    assert.equal(boneFiles[0]!.ref, "drivers/AD Driver/sch_Map");
    assert.ok(model.filter?.file?.endsWith("driver-filter.xml"));
  });

  it("renders the classic bone labels in the SVG", async () => {
    const model = await fishboneGet(sample, "AD Driver");
    const html = fishboneHtml(model, "n", "default-src 'none'", "fishbone.css");
    for (const label of [
      "Publisher channel",
      "Subscriber channel",
      "Identity Vault",
      "Application",
      "Event Transformation",
      "Matching",
      "Creation",
      "Placement",
      "Command Transformation",
      "Schema Mapping",
      "Input Transformation",
      "Output Transformation",
      "Filter",
      "ECMAScript",
      "GCVs",
      "Startup",
      "Shutdown",
      "sub-etp_Scoping",
      "sch_Map",
      "NOVLADENTEX-Startup-InitEntitlementConfigurationResource".slice(0, 20) + "…",
    ]) {
      assert.ok(html.includes(label), "missing " + label);
    }
  });

  it("maps AD-style Startup linkage to a driver-level bone with policies", async () => {
    const model = await fishboneGet(sample, "AD Driver");
    assert.deepEqual(
      model.resources.map((b) => b.key),
      RESOURCE_SETS.map((s) => s.key),
    );
    const startup = model.resources.find((b) => b.key === "startup")!;
    assert.equal(startup.setId, 15);
    assert.equal(startup.label, "Startup");
    assert.equal(startup.channel, "driver");
    assert.equal(startup.policies.length, 1);
    assert.equal(startup.policies[0]!.name, "NOVLADENTEX-Startup-InitEntitlementConfigurationResource");
    assert.equal(
      startup.policies[0]!.file,
      "drivers/AD Driver/NOVLADENTEX-Startup-InitEntitlementConfigurationResource.policy.xml",
    );
  });

  it("stacks GCV chips vertically instead of a comma-separated run-on", async () => {
    const model = await fishboneGet(sample, "AD Driver");
    const gcv = model.resources.find((b) => b.key === "gcv")!;
    assert.ok(gcv.policies.length >= 2, "sample links several GCV objects");
    const html = fishboneHtml(model, "n", "default-src 'none'", "fishbone.css");
    for (const p of gcv.policies) {
      assert.ok(html.includes(`data-node="${p.id}"`), "chip for " + p.name);
    }
  });

  it("reveal hook maps a ref to a real file", async () => {
    const file = await fishboneReveal(sample, "AD Driver", "drivers/AD Driver/subscriber/sub-etp_Scoping");
    assert.equal(file, path.join(sample, "drivers", "AD Driver", "subscriber", "sub-etp_Scoping.policy.xml"));
    assert.ok(fs.existsSync(file));
  });

  it("refresh hook writes the sentinel", () => {
    const sent = fishboneRefresh(sample);
    assert.ok(fs.existsSync(sent));
    fs.rmSync(path.dirname(sent), { recursive: true, force: true });
  });
});

describe("linkage.unknown startup (pre-PolicySet-15 trees)", () => {
  it("ingests linkage.unknown #15 as Startup when there is no named set", async () => {
    const dir = treeWithUnknownStartup(false);
    try {
      const model = await fishboneGet(dir, "AD Driver");
      const bone = model.resources.find((b) => b.key === "startup")!;
      assert.equal(bone.policies.length, 1, "must not invent a second startup link");
      assert.equal(bone.policies[0]!.name, "NOVLADENTEX-Startup-InitEntitlementConfigurationResource");
      assert.equal(bone.policies[0]!.unresolved, false);
      const html = fishboneHtml(model, "n", "default-src 'none'", "fishbone.css");
      assert.ok(html.includes("NOVLADENTEX-Startup-InitEntitlementConfigurationResource".slice(0, 20) + "…"));
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it("does not duplicate a named startup link when linkage.unknown also names it", async () => {
    const dir = treeWithUnknownStartup(true);
    try {
      const model = await fishboneGet(dir, "AD Driver");
      assert.equal(model.resources.find((b) => b.key === "startup")!.policies.length, 1);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("resource-row layout", () => {
  it("places Filter/Startup/Shutdown below a tall subscriber Creation/Placement stack", async () => {
    const model = await fishboneGet(sample, "AD Driver");
    const create = model.subscriber.find((b) => b.key === "subscriber-create")!;
    const place = model.subscriber.find((b) => b.key === "subscriber-placement")!;
    for (let i = 0; i < 8; i++) {
      create.policies.push({
        id: `policy:subscriber-create:tall:${i}`,
        ref: `drivers/AD Driver/tall-create-${i}`,
        name: `tall-create-${i}`,
        order: 100 + i,
        kind: "policy",
        unresolved: true,
      });
      place.policies.push({
        id: `policy:subscriber-placement:tall:${i}`,
        ref: `drivers/AD Driver/tall-place-${i}`,
        name: `tall-place-${i}`,
        order: 100 + i,
        kind: "policy",
        unresolved: true,
      });
    }
    const layout = fishboneLayout(model);
    const html = fishboneHtml(model, "n", "default-src 'none'", "fishbone.css");
    const startup = firstRect(html, "bone:startup");
    const shutdown = firstRect(html, "bone:shutdown");
    const filter = firstRect(html, "config:driver-filter");
    assert.equal(startup.y, layout.resourceY);
    assert.equal(shutdown.y, layout.resourceY);
    assert.equal(filter.y, layout.resourceY);
    assert.ok(layout.resourceY > 548, "tall subscriber must push the resource row below the old fixed y=548");

    for (const p of [...create.policies, ...place.policies]) {
      const chip = firstRect(html, p.id);
      assert.ok(
        chip.y + chip.h <= startup.y - 4,
        `${p.name} chip bottom ${chip.y + chip.h} overlaps Startup tile y=${startup.y}`,
      );
    }
    const viewBox = html.match(/viewBox="0 0 \d+ ([0-9.]+)"/);
    assert.ok(viewBox);
    assert.equal(Number(viewBox![1]), layout.height);
    assert.ok(layout.height > layout.resourceY + 48);
  });
});

const STARTUP_UNKNOWN =
  "cn=NOVLADENTEX-Startup-InitEntitlementConfigurationResource,cn=AD Driver,cn=driverset1,o=system#0#15";

/** Copy of sample-tree whose Startup exists only as linkage.unknown (or both); bin/idm is found upwards from the copy's real parent, so the copy is made inside the checkout. */
function treeWithUnknownStartup(keepNamedSet: boolean): string {
  const scratch = path.resolve(__dirname, "..", "..", ".test-scratch");
  fs.mkdirSync(scratch, { recursive: true });
  const dir = fs.mkdtempSync(path.join(scratch, "idm-unknown-startup-"));
  fs.cpSync(sample, dir, { recursive: true });
  const p = path.join(dir, "drivers", "AD Driver", "driver.xml");
  let xml = fs.readFileSync(p, "utf8");
  if (!keepNamedSet) {
    xml = xml.replace(/\s*<set key="startup">[\s\S]*?<\/set>/, "");
  }
  xml = xml.replace(
    /(<driver\b[^>]*>)/,
    `$1\n  <meta key="linkage.unknown.0">${STARTUP_UNKNOWN}</meta>`,
  );
  fs.writeFileSync(p, xml);
  return dir;
}

function firstRect(html: string, dataNode: string): { x: number; y: number; w: number; h: number } {
  const i = html.indexOf(`data-node="${dataNode}"`);
  assert.ok(i >= 0, "missing node " + dataNode);
  const slice = html.slice(i, i + 1200);
  const m = slice.match(/<rect\s+x="([^"]+)"\s+y="([^"]+)"\s+width="([^"]+)"\s+height="([^"]+)"/);
  assert.ok(m, "no rect for " + dataNode);
  return { x: Number(m![1]), y: Number(m![2]), w: Number(m![3]), h: Number(m![4]) };
}
