import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { buildContext } from "../src/context.js";
import { loadSensitiveValues, redactPatterns, scrub } from "../src/redact.js";
import { revealRelative, resolveInside } from "../src/fishbone.js";

describe("secrets stay out of tool results", () => {
  it("redacts assignments, JVM flags, and bearer tokens", () => {
    const raw = [
      "stg.password=hunter2xx",
      "-Dldap.password=hunter2xx",
      '"access_token": "aaaa.bbbb.cccc"',
      "Authorization: Bearer abcdefghijklmnop",
      "subscriber-event is a policy set",
    ].join("\n");
    const out = redactPatterns(raw);
    assert.equal(out.includes("hunter2xx"), false);
    assert.equal(out.includes("aaaa.bbbb.cccc"), false);
    assert.equal(out.includes("abcdefghijklmnop"), false);
    assert.match(out, /subscriber-event is a policy set/);
    assert.match(out, /stg\.password=\(redacted\)/);
  });

  it("scrubs literal secret values loaded from environments and secrets files", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "idm-mcp-"));
    fs.writeFileSync(path.join(dir, "environments.properties"), [
      "stg.url=ldaps://idm-stg:636",
      "stg.bindDn=cn=idm-deploy,ou=sa,o=system",
      "stg.password=supersecretvalue",
      "stg.passwordEnv=IDM_TEST_PW",
      "stg.secrets=secrets-stg.properties",
      "stg.tier=stg",
    ].join("\n"));
    fs.writeFileSync(path.join(dir, "secrets-stg.properties"), "AD.named.x=namedpasswordvalue\n");
    const prev = process.env.IDM_TEST_PW;
    process.env.IDM_TEST_PW = "fromenvsecret";
    try {
      const values = loadSensitiveValues([dir], { ...process.env, IDM_ENVIRONMENTS: "" });
      assert.ok(values.includes("supersecretvalue"));
      assert.ok(values.includes("namedpasswordvalue"));
      assert.ok(values.includes("fromenvsecret"));
      const text = scrub("plan uses supersecretvalue and namedpasswordvalue and fromenvsecret", values);
      assert.equal(text.includes("supersecretvalue"), false);
      assert.equal(text.includes("namedpasswordvalue"), false);
      assert.equal(text.includes("fromenvsecret"), false);
      const ctx = buildContext({ cwd: dir, repoRoot: dir, env: process.env, tree: undefined });
      const dumped = JSON.stringify(ctx);
      assert.equal(dumped.includes("supersecretvalue"), false);
      assert.equal(dumped.includes("namedpasswordvalue"), false);
      assert.deepEqual(ctx.environments.find((f) => f.present)?.names, ["stg"]);
      assert.deepEqual(ctx.secretsFiles, ["secrets-stg.properties"]);
      assert.equal(ctx.env.IDM_JAVA_OPTS, undefined);
    } finally {
      if (prev === undefined) delete process.env.IDM_TEST_PW;
      else process.env.IDM_TEST_PW = prev;
    }
  });
});

describe("fishbone reveal", () => {
  it("resolves a bone key and a policy ref to a file inside the tree", () => {
    const model = {
      treeRoot: "/t",
      filter: { id: "config:driver-filter", file: "drivers/AD Driver/driver-filter.xml" },
      subscriber: [{
        id: "bone:subscriber-event",
        key: "subscriber-event",
        policies: [{ ref: "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml", file: "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml" }],
      }],
    };
    assert.equal(revealRelative(model, "subscriber-event"), "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml");
    assert.equal(
      revealRelative(model, "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml"),
      "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml",
    );
    assert.equal(resolveInside("/t", revealRelative(model, "config:driver-filter")), "/t/drivers/AD Driver/driver-filter.xml");
    assert.throws(() => resolveInside("/t", "../outside.xml"), /escapes/);
  });
});
