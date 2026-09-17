#!/usr/bin/env node
/**
 * Headless dump of the fishbone JSON — the same payload the webview uses.
 * Agents and the MCP stub call this; the VS Code commands do not need it.
 *
 *   node out/cli.js dump <tree> [driver]
 */
import * as fs from "fs";
import * as path from "path";
import { loadTree } from "./ascode";
import { loadFishbone } from "./fishbone";

function usage(): never {
  console.error("usage: dirxmldev-fishbone dump <tree> [driver]");
  process.exit(2);
}

function main(argv: string[]): void {
  if (argv[0] !== "dump" || !argv[1]) {
    usage();
  }
  const root = path.resolve(argv[1]);
  if (!fs.existsSync(path.join(root, "driverset.xml"))) {
    console.error("not an IDM-as-code tree (missing driverset.xml): " + root);
    process.exit(1);
  }
  const tree = loadTree(root);
  const name = argv[2];
  const driver = name ? tree.drivers.find((d) => d.name === name) : tree.drivers[0];
  if (!driver) {
    console.error(
      "no driver " + JSON.stringify(name) + "; have: " + tree.drivers.map((d) => d.name).join(", "),
    );
    process.exit(1);
  }
  process.stdout.write(JSON.stringify(loadFishbone(tree, driver), null, 2) + "\n");
}

main(process.argv.slice(2));
