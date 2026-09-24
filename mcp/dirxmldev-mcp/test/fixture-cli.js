#!/usr/bin/env node
/**
 * Stand-in for bin/idm and bin/apps in tests. Records argv and prints the
 * shapes the real CLI prints. Not used in production.
 */
import fs from "node:fs";

const argv = process.argv.slice(2);
const log = process.env.FIXTURE_LOG;
if (log) fs.appendFileSync(log, JSON.stringify(argv) + "\n");

const cmd = argv[0];

if (cmd === "query" && argv[2] === "drivers") {
  process.stdout.write(JSON.stringify({
    drivers: [{ name: "AD Driver", dir: "drivers/AD Driver" }],
  }) + "\n");
  process.exit(0);
}

if (cmd === "query" && argv[2] === "fishbone") {
  const tree = argv[1];
  process.stdout.write(JSON.stringify({
    treeRoot: tree,
    driver: { name: argv[3] },
    publisher: [],
    subscriber: [{
      id: "bone:subscriber-event",
      key: "subscriber-event",
      policies: [{
        id: "policy:subscriber-event:1:sub-etp",
        ref: "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml",
        file: "drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml",
        unresolved: false,
      }],
    }],
    spine: [],
    resources: [],
    filter: { id: "config:driver-filter", file: "drivers/AD Driver/driver-filter.xml" },
  }) + "\n");
  process.exit(0);
}

if (cmd === "validate") {
  process.stdout.write(JSON.stringify({
    ok: true,
    counts: { error: 0, warning: 0, info: 0 },
    findings: [],
  }) + "\n");
  process.exit(0);
}

if (cmd === "vault.deploy" && argv.includes("--dry-run")) {
  process.stdout.write("stg.password=supersecretvalue\n");
  process.stdout.write(JSON.stringify({
    ok: true,
    refusal: null,
    plan: "(dry run — nothing written)",
  }) + "\n");
  process.exit(0);
}

if (argv.includes("--yes")) {
  process.stdout.write(JSON.stringify({ ok: true, refusal: null }) + "\n");
  process.exit(0);
}

if (cmd === "--env" || argv[0] === "--env") {
  process.stdout.write("token ok: 12 chars, expires in 60 s\n");
  process.exit(0);
}

process.stdout.write(argv.join("\n") + "\n");
process.exit(0);
