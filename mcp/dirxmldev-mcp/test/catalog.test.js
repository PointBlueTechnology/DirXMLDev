import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { argvFor, GATED, toolNames } from "../src/catalog.js";

const tree = "/trees/sample";

describe("argv matches bin/idm usage", () => {
  it("validate and query pass only the documented flags", () => {
    assert.deepEqual(argvFor("idm.validate", { tree }), ["validate", tree, "--json"]);
    assert.deepEqual(argvFor("idm.query", { tree, what: "chain", driver: "AD Driver", channel: "sub" }), [
      "query", tree, "chain", "AD Driver", "sub",
    ]);
    assert.deepEqual(argvFor("idm.query", { tree, what: "drivers" }), ["query", tree, "drivers", "--json"]);
    assert.deepEqual(argvFor("idm.query", { tree, what: "fishbone", driver: "AD Driver" }), [
      "query", tree, "fishbone", "AD Driver", "--json",
    ]);
    assert.deepEqual(argvFor("idm.show", { tree, path: "library/lib-common-event.policy.xml" }), [
      "show", tree, "library/lib-common-event.policy.xml",
    ]);
  });

  it("deploy plan is dry-run and cannot be talked into --yes", () => {
    const argv = argvFor("idm.vault.deploy.plan", {
      tree,
      env: "stg",
      driver: ["AD Driver"],
      secrets: "missing",
      deleteDriver: ["Old"],
      yes: true,
      step: true,
      captureDrift: true,
      force: true,
    });
    assert.deepEqual(argv, [
      "vault.deploy", tree, "--env", "stg", "--driver", "AD Driver",
      "--dry-run", "--secrets", "missing", "--delete-driver", "Old", "--json",
    ]);
    assert.equal(argv.includes("--yes"), false);
    assert.equal(argv.includes("--step"), false);
    assert.equal(argv.includes("--capture-drift"), false);
    assert.equal(argv.includes("--force"), false);
  });

  it("deploy writes only with --yes, and deletes need confirmDeletes", () => {
    const argv = argvFor("idm.vault.deploy", {
      confirm: true,
      tree,
      env: "prd",
      confirmEnv: "prd",
      noRestart: true,
    });
    assert.ok(argv.includes("--yes"));
    assert.equal(argv.includes("--dry-run"), false);
    assert.deepEqual(argv.slice(0, 6), ["vault.deploy", tree, "--env", "prd", "--yes", "--confirm"]);
    assert.equal(argv.at(-1), "--json");
    assert.throws(
      () => argvFor("idm.vault.deploy", { confirm: true, tree, env: "stg", deleteAll: ["forms"] }),
      /confirmDeletes/,
    );
  });

  it("rollback puts the tree before flags so --yes does not swallow it", () => {
    assert.deepEqual(argvFor("idm.vault.rollback", {
      confirm: true,
      env: "stg",
      snapshot: "/snaps/a.ldif",
      tree,
    }), ["vault.rollback", tree, "--env", "stg", "--snapshot", "/snaps/a.ldif", "--yes", "--json"]);
  });

  it("driver mutators pass --yes and cache view does not pass --out", () => {
    assert.deepEqual(argvFor("idm.driver.stop", { confirm: true, env: "stg", driver: "AD Driver", wait: 5 }), [
      "driver.stop", "--env", "stg", "--driver", "AD Driver", "--wait", "5", "--yes",
    ]);
    const view = argvFor("idm.driver.cache.view", { env: "stg", driver: "AD Driver", count: 3, out: "/tmp/x" });
    assert.deepEqual(view, ["driver.cache", "view", "--env", "stg", "--driver", "AD Driver", "--count", "3", "--json"]);
    assert.equal(view.includes("--out"), false);
    const tail = argvFor("idm.driver.trace.tail", { env: "stg", driver: "AD Driver", lines: 20, grep: "error", follow: true });
    assert.equal(tail.includes("--follow"), false);
    assert.deepEqual(argvFor("idm.driver.cache.clear", { confirm: true, env: "prd", driver: "AD Driver", confirmEnv: "prd" }), [
      "driver.cache", "clear", "--env", "prd", "--driver", "AD Driver", "--yes", "--confirm", "prd",
    ]);
  });

  it("apps reads pass --json except token, and never a write command", () => {
    assert.deepEqual(argvFor("apps.permission", { env: "stg", prd: "My PRD" }), [
      "--env", "stg", "--json", "permission", "My PRD",
    ]);
    assert.deepEqual(argvFor("apps.token", { env: "stg", insecure: true }), ["--env", "stg", "--insecure", "token"]);
    assert.equal(argvFor("apps.token", { env: "stg" }).includes("--json"), false);
  });

  it("rejects flag-like positional arguments", () => {
    assert.throws(() => argvFor("idm.validate", { tree: "--yes" }), /must not start with '-'/);
    assert.throws(() => argvFor("idm.query", { tree, what: "chain", driver: "AD", channel: "sideways" }));
  });

  it("gates the vault and driver mutators and nothing else", () => {
    assert.deepEqual(GATED.sort(), [
      "idm.driver.cache.clear",
      "idm.driver.restart",
      "idm.driver.start",
      "idm.driver.stop",
      "idm.vault.deploy",
      "idm.vault.rollback",
    ]);
    for (const name of ["idm.validate", "idm.vault.deploy.plan", "idm.vault.diff", "fishbone.get", "apps.tasks"]) {
      assert.equal(toolNames.includes(name), true);
      assert.equal(GATED.includes(name), false);
    }
  });
});
