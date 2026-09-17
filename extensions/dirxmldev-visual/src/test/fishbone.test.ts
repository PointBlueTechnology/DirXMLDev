import * as assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import { describe, it } from "node:test";
import { findTreeRoot, loadTree } from "../ascode";
import { filesForNode, loadFishbone, PUBLISHER_RIBS, SUBSCRIBER_RIBS } from "../fishbone";
import { fishboneGet, fishboneRefresh, fishboneReveal } from "../hooks";
import { fishboneHtml } from "../webview";

const sample = path.resolve(__dirname, "..", "..", "sample-tree");

describe("sample as-code tree", () => {
  it("is a driverset.xml tree and walk-from-policy finds it", () => {
    assert.equal(findTreeRoot(sample), sample);
    const policy = path.join(sample, "drivers", "AD Driver", "subscriber", "sub-etp_Scoping.policy.xml");
    assert.equal(findTreeRoot(policy), sample);
  });

  it("loads the AD Driver with PolicySet keys from the Java enum", () => {
    const tree = loadTree(sample);
    assert.equal(tree.driverSet.name, "driverset1");
    assert.equal(tree.drivers.length, 1);
    const d = tree.drivers[0]!;
    assert.equal(d.name, "AD Driver");
    const keys = new Set(d.links.map((l) => l.setKey));
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
    ]) {
      assert.ok(keys.has(k), "missing set " + k);
    }
  });
});

describe("fishbone model", () => {
  it("puts Designer ribs on both channels and resolves files", () => {
    const model = fishboneGet(sample, "AD Driver");
    assert.equal(model.driver.name, "AD Driver");
    assert.equal(model.publisher.length, PUBLISHER_RIBS.length);
    assert.equal(model.subscriber.length, SUBSCRIBER_RIBS.length);
    assert.equal(model.spine.map((b) => b.key).join(","), "schema-mapping,input,output");

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

  it("renders the classic bone labels in the SVG", () => {
    const model = fishboneGet(sample, "AD Driver");
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
      "sub-etp_Scoping",
      "sch_Map",
    ]) {
      assert.ok(html.includes(label), "missing " + label);
    }
  });

  it("reveal hook maps a ref to a real file", () => {
    const file = fishboneReveal(sample, "AD Driver", "drivers/AD Driver/subscriber/sub-etp_Scoping");
    assert.ok(fs.existsSync(file));
    assert.ok(file.endsWith("sub-etp_Scoping.policy.xml"));
  });

  it("refresh hook writes the sentinel", () => {
    const sent = fishboneRefresh(sample);
    assert.ok(fs.existsSync(sent));
    assert.ok(sent.endsWith(path.join(".dirxmldev", "fishbone.refresh")));
    fs.rmSync(path.dirname(sent), { recursive: true, force: true });
  });
});
