import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PKG = path.resolve(HERE, "..");
const SAMPLE = path.resolve(PKG, "..", "..", "extensions", "dirxmldev-visual", "sample-tree");
const FIXTURE = path.join(HERE, "fixture-cli.js");

function startServer(extraEnv) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "idm-mcp-srv-"));
  const log = path.join(dir, "argv.log");
  fs.writeFileSync(path.join(dir, "environments.properties"), "stg.password=supersecretvalue\nstg.url=ldaps://lab:636\n");
  const env = { ...process.env, ...extraEnv };
  delete env.IDM_AGENT_ALLOW_WRITE;
  if (extraEnv && Object.prototype.hasOwnProperty.call(extraEnv, "IDM_AGENT_ALLOW_WRITE")) {
    if (extraEnv.IDM_AGENT_ALLOW_WRITE === undefined) delete env.IDM_AGENT_ALLOW_WRITE;
    else env.IDM_AGENT_ALLOW_WRITE = extraEnv.IDM_AGENT_ALLOW_WRITE;
  }
  env.IDM_BIN = FIXTURE;
  env.APPS_BIN = FIXTURE;
  env.FIXTURE_LOG = log;
  env.IDM_MCP_TIMEOUT_MS = "15000";
  const child = spawn(process.execPath, [path.join(PKG, "src", "index.js")], {
    cwd: dir,
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  let buf = "";
  const pending = new Map();
  let stderr = "";
  child.stderr.on("data", (c) => {
    stderr += c.toString();
  });
  child.stdout.on("data", (c) => {
    buf += c.toString();
    let nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        continue;
      }
      if (msg.id !== undefined && pending.has(msg.id)) {
        pending.get(msg.id)(msg);
        pending.delete(msg.id);
      }
    }
  });
  let nextId = 1;
  function request(method, params) {
    const id = nextId++;
    const payload = JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n";
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timeout waiting for ${method}; stderr=${stderr}`)), 10000);
      pending.set(id, (msg) => {
        clearTimeout(timer);
        resolve(msg);
      });
      child.stdin.write(payload);
    });
  }
  function notify(method, params) {
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method, params }) + "\n");
  }
  async function ready() {
    const init = await request("initialize", {
      protocolVersion: "2025-03-26",
      capabilities: {},
      clientInfo: { name: "dirxmldev-mcp-test", version: "0" },
    });
    if (init.error) throw new Error(JSON.stringify(init.error) + stderr);
    notify("notifications/initialized");
    return init;
  }
  function argvLog() {
    return fs.existsSync(log) ? fs.readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : [];
  }
  return { child, request, ready, argvLog, dir, stderr: () => stderr };
}

function textOf(msg) {
  const block = msg.result?.content?.find((c) => c.type === "text");
  assert.ok(block, "tool result has text: " + JSON.stringify(msg));
  return JSON.parse(block.text);
}

describe("mcp server", () => {
  it("starts, lists read and gated tools, and answers a 2025 initialize", async () => {
    const srv = startServer();
    try {
      const init = await srv.ready();
      assert.equal(init.result.serverInfo.name, "dirxmldev");
      assert.match(init.result.instructions, /IDM_AGENT_ALLOW_WRITE/);
      const listed = await srv.request("tools/list", {});
      const names = listed.result.tools.map((t) => t.name).sort();
      for (const required of [
        "idm.context", "idm.validate", "idm.query", "idm.show", "idm.tree.diff",
        "idm.simulate", "idm.vault.diff", "idm.vault.deploy.plan", "idm.vault.deploy",
        "idm.driver.start", "idm.driver.stop", "idm.driver.cache.clear",
        "fishbone.get", "fishbone.refresh", "fishbone.reveal", "apps.tasks",
      ]) {
        assert.ok(names.includes(required), required);
      }
      const deploy = listed.result.tools.find((t) => t.name === "idm.vault.deploy");
      assert.equal(deploy.annotations.destructiveHint, true);
      assert.equal(deploy.annotations.readOnlyHint, false);
      const plan = listed.result.tools.find((t) => t.name === "idm.vault.deploy.plan");
      assert.equal(plan.annotations.readOnlyHint, true);
    } finally {
      srv.child.kill("SIGKILL");
    }
  });

  it("runs validate and a dry-run against the sample tree without leaking a secret", async () => {
    const srv = startServer();
    try {
      await srv.ready();
      const validated = textOf(await srv.request("tools/call", {
        name: "idm.validate",
        arguments: { tree: SAMPLE },
      }));
      assert.equal(validated.schemaVersion, 1);
      assert.equal(validated.ok, true);
      assert.equal(validated.result.schemaVersion, undefined);
      assert.deepEqual(validated.result.counts, { error: 0, warning: 0, info: 0 });
      assert.deepEqual(validated.command, ["idm", "validate", SAMPLE, "--json"]);

      const plan = textOf(await srv.request("tools/call", {
        name: "idm.vault.deploy.plan",
        arguments: { tree: SAMPLE, env: "stg", driver: ["AD Driver"] },
      }));
      assert.equal(plan.schemaVersion, 1);
      assert.ok(plan.command.includes("--dry-run"));
      assert.equal(plan.command.includes("--yes"), false);
      assert.equal(JSON.stringify(plan).includes("supersecretvalue"), false);
      assert.match(JSON.stringify(plan), /\(redacted\)|\(dry run/);

      const ctx = textOf(await srv.request("tools/call", {
        name: "idm.context",
        arguments: { tree: SAMPLE },
      }));
      assert.equal(ctx.result.tree.driversetXml, true);
      assert.ok(ctx.result.tree.drivers.includes("AD Driver"));
      assert.equal(ctx.result.writesAllowed, false);
      assert.equal(JSON.stringify(ctx).includes("supersecretvalue"), false);
      assert.equal(ctx.result.env.IDM_AGENT_ALLOW_WRITE, "(not 1)");
    } finally {
      srv.child.kill("SIGKILL");
    }
  });

  it("does not start the CLI for a mutator unless the gate and confirm are both set", async () => {
    const closed = startServer();
    try {
      await closed.ready();
      const refused = textOf(await closed.request("tools/call", {
        name: "idm.vault.deploy",
        arguments: { confirm: true, tree: SAMPLE, env: "stg" },
      }));
      assert.equal(refused.blocked, true);
      assert.equal(refused.ok, false);
      assert.equal(closed.argvLog().some((a) => a[0] === "vault.deploy" && a.includes("--yes")), false);
      const stop = await closed.request("tools/call", {
        name: "idm.driver.stop",
        arguments: { confirm: true, env: "stg", driver: "AD Driver" },
      });
      assert.equal(textOf(stop).blocked, true);
      assert.equal(closed.argvLog().some((a) => a[0] === "driver.stop"), false);
    } finally {
      closed.child.kill("SIGKILL");
    }

    const open = startServer({ IDM_AGENT_ALLOW_WRITE: "1" });
    try {
      await open.ready();
      const missing = await open.request("tools/call", {
        name: "idm.driver.cache.clear",
        arguments: { confirm: false, env: "stg", driver: "AD Driver" },
      });
      assert.equal(missing.result?.isError, true);
      assert.equal(open.argvLog().some((a) => a.includes("clear")), false);

      const ran = textOf(await open.request("tools/call", {
        name: "idm.driver.start",
        arguments: { confirm: true, env: "stg", driver: "AD Driver", confirmEnv: "stg" },
      }));
      assert.equal(ran.blocked, false);
      assert.equal(ran.ok, true);
      assert.deepEqual(ran.command, [
        "idm", "driver.start", "--env", "stg", "--driver", "AD Driver", "--yes", "--confirm", "stg",
      ]);
    } finally {
      open.child.kill("SIGKILL");
    }
  });

  it("fishbone tools work against the sample tree via the fixture CLI", async () => {
    const srv = startServer();
    try {
      await srv.ready();
      const got = textOf(await srv.request("tools/call", {
        name: "fishbone.get",
        arguments: { tree: SAMPLE },
      }));
      assert.equal(got.ok, true);
      assert.equal(got.schemaVersion, 1);
      assert.equal(got.result.driver.name, "AD Driver");
      assert.equal(got.command[3], "fishbone");

      const revealed = textOf(await srv.request("tools/call", {
        name: "fishbone.reveal",
        arguments: { tree: SAMPLE, driver: "AD Driver", ref: "subscriber-event" },
      }));
      assert.equal(revealed.ok, true);
      assert.equal(
        revealed.result.file,
        path.join(SAMPLE, "drivers", "AD Driver", "subscriber", "sub-etp_Scoping.policy.xml"),
      );
      assert.equal(fs.existsSync(revealed.result.file), true);

      const scratch = fs.mkdtempSync(path.join(os.tmpdir(), "idm-mcp-tree-"));
      fs.writeFileSync(path.join(scratch, "driverset.xml"), "<driverset/>\n");
      const refreshed = textOf(await srv.request("tools/call", {
        name: "fishbone.refresh",
        arguments: { tree: scratch },
      }));
      assert.equal(refreshed.ok, true);
      assert.equal(fs.existsSync(path.join(scratch, ".dirxmldev", "fishbone.refresh")), true);
      assert.equal(srv.argvLog().some((a) => a.includes("fishbone.refresh")), false);
    } finally {
      srv.child.kill("SIGKILL");
    }
  });
});
