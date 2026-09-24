#!/usr/bin/env node
/**
 * Headless dump of the fishbone JSON — the same payload the webview uses,
 * obtained from `bin/idm query <tree> fishbone <driver> --json`.
 *
 *   node out/cli.js dump <tree> [driver]
 */
import * as fs from "fs";
import * as path from "path";
import { fishboneGet } from "./hooks";

function usage(): never {
  console.error("usage: dirxmldev-fishbone dump <tree> [driver]");
  process.exit(2);
}

async function main(argv: string[]): Promise<void> {
  if (argv[0] !== "dump" || !argv[1]) {
    usage();
  }
  const root = path.resolve(argv[1]);
  if (!fs.existsSync(path.join(root, "driverset.xml"))) {
    console.error("not an IDM-as-code tree (missing driverset.xml): " + root);
    process.exit(1);
  }
  const model = await fishboneGet(root, argv[2]);
  process.stdout.write(JSON.stringify(model, null, 2) + "\n");
}

main(process.argv.slice(2)).catch((e) => {
  console.error(e instanceof Error ? e.message : String(e));
  process.exit(1);
});
