import { z } from "zod";
import { pushDrivers, pushRepeat, safeInt, safeToken, safeValue } from "./safe.js";

/**
 * Tool catalog. Every argv list is a real `bin/idm` / `bin/apps` invocation
 * taken from `bin/idm` usage (Cli.java). `--json` is passed only where that
 * usage lists it. `--force`, `--step`, and `driver.trace --follow` are not exposed.
 */

const READ = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false };
const READ_VAULT = { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: true };
const WRITE = { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true };

const tree = z.string().min(1).describe("IDM-as-code tree directory (contains driverset.xml).");
const envName = z.string().min(1).describe("Environment name from environments.properties (--env). The name only; never a password.");
const driver = z.string().min(1).describe("Driver name (--driver).");
const drivers = z.array(z.string().min(1)).optional().describe("Repeatable --driver. Omit for every driver.");
const confirm = z.literal(true).describe(
  "Must be true. The MCP process must also have IDM_AGENT_ALLOW_WRITE=1. Show the dry-run plan to a human first. This does not replace the CLI's own --confirm on production.",
);
const confirmEnv = z.string().min(1).optional().describe("Passed as --confirm <env>. Required by the CLI on a production tier.");

function flag(argv, name, value) {
  if (value !== undefined && value !== null) argv.push(name, value);
}

export const tools = [
  {
    name: "idm.context",
    title: "IDM context",
    description:
      "Local orient: whether cwd (or tree) has driverset.xml, driver directory names, git HEAD, and a redacted env list. Environment names only — never values from environments.properties or secrets files. Does not run bin/idm.",
    annotations: READ,
    inputSchema: z.object({ tree: tree.optional().describe("Tree to inspect. Defaults to the server's working directory.") }),
    kind: "context",
  },
  {
    name: "idm.check",
    title: "Check tree",
    description: "bin/idm check <tree>. Load an as-code tree and report it. Exit 1 on broken links. No --json in the CLI usage; the text is returned as text.",
    annotations: READ,
    inputSchema: z.object({ tree }),
    bin: "idm",
    build: (a) => ["check", safeToken("tree", a.tree)],
  },
  {
    name: "idm.validate",
    title: "Validate tree",
    description:
      "bin/idm validate <tree> --json. The engine's own compilers, linkage, GCVs, mapping tables, filter and schema map. Exit 1 means findings, not a tool failure. Fix errors before any deploy.",
    annotations: READ,
    inputSchema: z.object({ tree }),
    bin: "idm",
    build: (a) => ["validate", safeToken("tree", a.tree), "--json"],
  },
  {
    name: "idm.query",
    title: "Query tree",
    description:
      "bin/idm query <tree> artifacts [driver] | chain <driver> sub|pub | gcvs [driver] | tables [driver] | fishbone <driver> --json | drivers --json. Orient: chains, GCVs in scope, mapping tables, the fishbone, or the driver list. fishbone and drivers are the only query forms whose usage lists --json.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      what: z.enum(["artifacts", "chain", "gcvs", "tables", "fishbone", "drivers"]),
      driver: driver.optional().describe("Required for chain and fishbone. Optional for artifacts, gcvs, and tables."),
      channel: z.enum(["sub", "pub"]).optional().describe("Required when what is chain."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["query", safeToken("tree", a.tree), a.what];
      if (a.what === "drivers") {
        argv.push("--json");
        return argv;
      }
      if (a.what === "fishbone") {
        argv.push(safeToken("driver", a.driver), "--json");
        return argv;
      }
      if (a.what === "chain") {
        if (a.channel !== "sub" && a.channel !== "pub") {
          throw new Error("channel must be sub or pub");
        }
        argv.push(safeToken("driver", a.driver), a.channel);
        return argv;
      }
      if (a.driver) argv.push(safeToken("driver", a.driver));
      return argv;
    },
  },
  {
    name: "idm.show",
    title: "Show artifact",
    description: "bin/idm show <tree> <artifactPath>. The artifact's content. No --json in the CLI usage.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      path: z.string().min(1).describe("Artifact path, e.g. drivers/AD Driver/subscriber/sub-etp_Scoping.policy.xml."),
    }),
    bin: "idm",
    build: (a) => ["show", safeToken("tree", a.tree), safeToken("path", a.path)],
  },
  {
    name: "idm.refs",
    title: "References",
    description: "bin/idm refs <tree> <artifactPath>. Everything that references an artifact.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      path: z.string().min(1).describe("Artifact path."),
    }),
    bin: "idm",
    build: (a) => ["refs", safeToken("tree", a.tree), safeToken("path", a.path)],
  },
  {
    name: "idm.package.diff",
    title: "Package baseline diff",
    description:
      "bin/idm package.diff <tree> <artifactPath>. A customized packaged artifact against its package baseline in the tree. This is the tree form (no --catalog).",
    annotations: READ,
    inputSchema: z.object({
      tree,
      path: z.string().min(1).describe("Artifact path."),
    }),
    bin: "idm",
    build: (a) => ["package.diff", safeToken("tree", a.tree), safeToken("path", a.path)],
  },
  {
    name: "idm.tree.diff",
    title: "Diff two trees",
    description: "bin/idm tree.diff <from> <to> --json. Structured diff of two as-code trees. Exit 1 when they differ.",
    annotations: READ,
    inputSchema: z.object({
      from: z.string().min(1).describe("From tree directory."),
      to: z.string().min(1).describe("To tree directory."),
    }),
    bin: "idm",
    build: (a) => ["tree.diff", safeToken("from", a.from), safeToken("to", a.to), "--json"],
  },
  {
    name: "idm.simulate",
    title: "Simulate cases",
    description:
      "bin/idm simulate <tree> --cases <dir> [--against <tree>] --json. Run the regression corpus. With --against, diff the results against another tree.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      cases: z.string().min(1).describe("Cases directory (--cases)."),
      against: z.string().min(1).optional().describe("Other as-code tree (--against)."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["simulate", safeToken("tree", a.tree), "--cases", safeToken("cases", a.cases)];
      if (a.against) argv.push("--against", safeToken("against", a.against));
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.form.list",
    title: "List forms",
    description: "bin/idm form.list <tree> [--driver D]. Kind, name, title, field count, packaged mark.",
    annotations: READ,
    inputSchema: z.object({ tree, driver: driver.optional() }),
    bin: "idm",
    build(a) {
      const argv = ["form.list", safeToken("tree", a.tree)];
      if (a.driver) argv.push("--driver", safeToken("driver", a.driver));
      return argv;
    },
  },
  {
    name: "idm.form.show",
    title: "Show form",
    description: "bin/idm form.show <tree> <name-or-path> [--driver D] --json. Outline and which PRDs bind the form.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      form: z.string().min(1).describe("Form name or path."),
      driver: driver.optional(),
    }),
    bin: "idm",
    build(a) {
      const argv = ["form.show", safeToken("tree", a.tree), safeToken("form", a.form)];
      if (a.driver) argv.push("--driver", safeToken("driver", a.driver));
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.prd.list",
    title: "List PRDs",
    description: "bin/idm prd.list <tree> [--driver D]. Status, category, json-forms or classic, bound forms.",
    annotations: READ,
    inputSchema: z.object({ tree, driver: driver.optional() }),
    bin: "idm",
    build(a) {
      const argv = ["prd.list", safeToken("tree", a.tree)];
      if (a.driver) argv.push("--driver", safeToken("driver", a.driver));
      return argv;
    },
  },
  {
    name: "idm.prd.show",
    title: "Show PRD",
    description: "bin/idm prd.show <tree> <name> [--driver D] --json. Properties, bindings, activities.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      prd: z.string().min(1).describe("PRD name."),
      driver: driver.optional(),
    }),
    bin: "idm",
    build(a) {
      const argv = ["prd.show", safeToken("tree", a.tree), safeToken("prd", a.prd)];
      if (a.driver) argv.push("--driver", safeToken("driver", a.driver));
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.prd.flow",
    title: "Show workflow",
    description:
      "bin/idm prd.flow <tree> <name> [--driver D] [--format text|mermaid] [--lang L]. Renders the workflow. Does not pass --out, so it does not write a file.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      prd: z.string().min(1),
      driver: driver.optional(),
      format: z.enum(["text", "mermaid"]).optional(),
      lang: z.string().min(1).optional().describe("Language code (--lang). Default is the CLI's en."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["prd.flow", safeToken("tree", a.tree), safeToken("prd", a.prd)];
      if (a.driver) argv.push("--driver", safeToken("driver", a.driver));
      if (a.format) argv.push("--format", a.format);
      if (a.lang) argv.push("--lang", safeToken("lang", a.lang));
      return argv;
    },
  },
  {
    name: "idm.package.status",
    title: "Package status",
    description: "bin/idm package.status <tree> [--driver D] [--catalog DIR] --json. Installed packages versus an optional catalog. Read-only.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      driver: driver.optional(),
      catalog: z.string().min(1).optional().describe("Package catalog directory (--catalog)."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["package.status", safeToken("tree", a.tree)];
      if (a.driver) argv.push("--driver", safeToken("driver", a.driver));
      if (a.catalog) argv.push("--catalog", safeToken("catalog", a.catalog));
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.vault.diff",
    title: "Diff tree against vault",
    description: "bin/idm vault.diff <tree> --env E [--driver D…] --json. What would change in the vault. Reads the vault; writes nothing. Exit 1 when they differ.",
    annotations: READ_VAULT,
    inputSchema: z.object({ tree, env: envName, driver: drivers }),
    bin: "idm",
    build(a) {
      const argv = ["vault.diff", safeToken("tree", a.tree), "--env", safeToken("env", a.env)];
      pushDrivers(argv, a.driver);
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.vault.verify",
    title: "Verify vault matches tree",
    description: "bin/idm vault.verify <tree> --env E [--driver D…] --json. The same diff, read after a deploy. Writes nothing.",
    annotations: READ_VAULT,
    inputSchema: z.object({ tree, env: envName, driver: drivers }),
    bin: "idm",
    build(a) {
      const argv = ["vault.verify", safeToken("tree", a.tree), "--env", safeToken("env", a.env)];
      pushDrivers(argv, a.driver);
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.vault.deploy.plan",
    title: "Deploy dry-run plan",
    description:
      "bin/idm vault.deploy <tree> --env E [--driver D…] --dry-run [--no-restart] [--secrets none|missing|all] [--allow-missing-secrets] [--delete-driver D…] [--delete-all KIND…] --json. Always --dry-run. Never --yes, --step, or --capture-drift. Show this plan before idm.vault.deploy. Exit is the CLI's; a refusal is in the JSON.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      tree,
      env: envName,
      driver: drivers,
      noRestart: z.boolean().optional().describe("Pass --no-restart."),
      secrets: z.enum(["none", "missing", "all"]).optional().describe("Passed as --secrets. Default is the CLI's none. Names only; values are scrubbed if echoed."),
      allowMissingSecrets: z.boolean().optional().describe("Pass --allow-missing-secrets."),
      deleteDriver: z.array(z.string().min(1)).optional().describe("Repeatable --delete-driver, included so the dry-run shows those deletes."),
      deleteAll: z.array(z.string().min(1)).optional().describe("Repeatable --delete-all (entitlements, forms, prds, …)."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["vault.deploy", safeToken("tree", a.tree), "--env", safeToken("env", a.env)];
      pushDrivers(argv, a.driver);
      argv.push("--dry-run");
      if (a.noRestart) argv.push("--no-restart");
      if (a.secrets) argv.push("--secrets", a.secrets);
      if (a.allowMissingSecrets) argv.push("--allow-missing-secrets");
      pushRepeat(argv, "--delete-driver", "deleteDriver", a.deleteDriver);
      pushRepeat(argv, "--delete-all", "deleteAll", a.deleteAll);
      argv.push("--json");
      if (argv.includes("--yes") || argv.includes("--step") || argv.includes("--capture-drift") || argv.includes("--force")) {
        throw new Error("dry-run plan must not include a write flag");
      }
      return argv;
    },
  },
  {
    name: "idm.driverset.status",
    title: "Driver set status",
    description: "bin/idm driverset.status --env E --json. Read-only engine state.",
    annotations: READ_VAULT,
    inputSchema: z.object({ env: envName }),
    bin: "idm",
    build: (a) => ["driverset.status", "--env", safeToken("env", a.env), "--json"],
  },
  {
    name: "idm.driver.status",
    title: "Driver status",
    description: "bin/idm driver.status --env E --driver D [--tree DIR] --json. Read-only.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      driver,
      tree: tree.optional().describe("As-code tree (--tree), when the status check should see the tree."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["driver.status", "--env", safeToken("env", a.env), "--driver", safeToken("driver", a.driver)];
      if (a.tree) argv.push("--tree", safeToken("tree", a.tree));
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.driver.cache.view",
    title: "View driver cache",
    description:
      "bin/idm driver.cache view --env E --driver D [--count N] --json. Read-only. Does not pass --out, so it does not write cache files to disk.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      driver,
      count: z.number().int().min(1).max(10000).optional().describe("How many events (--count)."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["driver.cache", "view", "--env", safeToken("env", a.env), "--driver", safeToken("driver", a.driver)];
      if (a.count !== undefined) argv.push("--count", safeInt("count", a.count, { min: 1, max: 10000 }));
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.engine.version",
    title: "Engine version",
    description: "bin/idm engine.version --env E. The usage line has no --json; text is returned as text.",
    annotations: READ_VAULT,
    inputSchema: z.object({ env: envName }),
    bin: "idm",
    build: (a) => ["engine.version", "--env", safeToken("env", a.env)],
  },
  {
    name: "idm.engine.stats",
    title: "Engine stats",
    description: "bin/idm engine.stats --env E [--driver D…] --json.",
    annotations: READ_VAULT,
    inputSchema: z.object({ env: envName, driver: drivers }),
    bin: "idm",
    build(a) {
      const argv = ["engine.stats", "--env", safeToken("env", a.env)];
      pushDrivers(argv, a.driver);
      argv.push("--json");
      return argv;
    },
  },
  {
    name: "idm.driver.trace.show",
    title: "Show trace settings",
    description: "bin/idm driver.trace show --env E --driver D. Current trace level and file. Does not change them. The usage line has no --json.",
    annotations: READ_VAULT,
    inputSchema: z.object({ env: envName, driver }),
    bin: "idm",
    build: (a) => ["driver.trace", "show", "--env", safeToken("env", a.env), "--driver", safeToken("driver", a.driver)],
  },
  {
    name: "idm.driver.trace.tail",
    title: "Tail driver trace",
    description:
      "bin/idm driver.trace tail --env E --driver D [--lines N] [--grep RE] [--since MIN]. A bounded read over SSH. Does not pass --follow, so the call returns. The usage line has no --json.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      driver,
      lines: z.number().int().min(1).max(5000).optional().describe("--lines. CLI default is 50."),
      grep: z.string().min(1).optional().describe("--grep regular expression."),
      since: z.number().int().min(0).max(100000).optional().describe("--since, in minutes."),
    }),
    bin: "idm",
    build(a) {
      const argv = ["driver.trace", "tail", "--env", safeToken("env", a.env), "--driver", safeToken("driver", a.driver)];
      if (a.lines !== undefined) argv.push("--lines", safeInt("lines", a.lines, { min: 1, max: 5000 }));
      if (a.grep !== undefined) argv.push("--grep", safeValue("grep", a.grep));
      if (a.since !== undefined) argv.push("--since", safeInt("since", a.since, { min: 0, max: 100000 }));
      if (argv.includes("--follow")) throw new Error("trace tail must not follow");
      return argv;
    },
  },
  {
    name: "apps.permission",
    title: "PRD permission",
    description: "bin/apps --env E --json permission <PRD>. Whether the PRD is in the Identity Applications permission index, and its request form. Read-only.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      prd: z.string().min(1).describe("PRD name or DN."),
      props: z.string().min(1).optional().describe("environments.properties path (--props). Default is the CLI's."),
      insecure: z.boolean().optional().describe("Pass --insecure (skip TLS verification)."),
    }),
    bin: "apps",
    build: (a) => appsArgs(a, ["permission", safeToken("prd", a.prd)]),
  },
  {
    name: "apps.tasks",
    title: "List tasks",
    description: "bin/apps --env E --json tasks [--q TEXT] [--size N]. Open tasks for the apps user. Read-only.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      q: z.string().min(1).optional().describe("--q search. CLI default is *."),
      size: z.number().int().min(1).max(500).optional(),
      props: z.string().min(1).optional(),
      insecure: z.boolean().optional(),
    }),
    bin: "apps",
    build(a) {
      const rest = ["tasks"];
      if (a.q !== undefined) rest.push("--q", safeValue("q", a.q));
      if (a.size !== undefined) rest.push("--size", safeInt("size", a.size, { min: 1, max: 500 }));
      return appsArgs(a, rest);
    },
  },
  {
    name: "apps.task",
    title: "Show task",
    description: "bin/apps --env E --json task <taskId>. One task and its data items. Read-only.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      taskId: z.string().min(1),
      props: z.string().min(1).optional(),
      insecure: z.boolean().optional(),
    }),
    bin: "apps",
    build: (a) => appsArgs(a, ["task", safeToken("taskId", a.taskId)]),
  },
  {
    name: "apps.history",
    title: "Request history",
    description: "bin/apps --env E --json history [--size N] [--q TEXT]. The apps user's requests. Read-only.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      q: z.string().min(1).optional(),
      size: z.number().int().min(1).max(500).optional(),
      props: z.string().min(1).optional(),
      insecure: z.boolean().optional(),
    }),
    bin: "apps",
    build(a) {
      const rest = ["history"];
      if (a.size !== undefined) rest.push("--size", safeInt("size", a.size, { min: 1, max: 500 }));
      if (a.q !== undefined) rest.push("--q", safeValue("q", a.q));
      return appsArgs(a, rest);
    },
  },
  {
    name: "apps.token",
    title: "Apps token check",
    description:
      "bin/apps --env E token. Prints token length and expiry, never the token. Does not pass --json, which is unused by this command. Read-only.",
    annotations: READ_VAULT,
    inputSchema: z.object({
      env: envName,
      props: z.string().min(1).optional(),
      insecure: z.boolean().optional(),
    }),
    bin: "apps",
    build: (a) => appsArgs(a, ["token"], { json: false }),
  },
  {
    name: "idm.vault.deploy",
    title: "Deploy to vault",
    description:
      "bin/idm vault.deploy <tree> --env E [--driver D…] --yes [--confirm E] [--no-restart] [--secrets none|missing|all] [--allow-missing-secrets] [--capture-drift] [--delete-driver D…] [--delete-all KIND…] --json. Gated: IDM_AGENT_ALLOW_WRITE=1 and confirm true. deleteDriver or deleteAll also needs confirmDeletes true. The server always passes --yes and never --step, --dry-run, or --force. The CLI's own tier gate still applies (production needs confirmEnv).",
    annotations: WRITE,
    gated: true,
    inputSchema: z.object({
      confirm,
      tree,
      env: envName,
      driver: drivers,
      confirmEnv,
      noRestart: z.boolean().optional(),
      secrets: z.enum(["none", "missing", "all"]).optional(),
      allowMissingSecrets: z.boolean().optional(),
      captureDrift: z.boolean().optional().describe("Pass --capture-drift. Records vault state; still a write, still gated."),
      deleteDriver: z.array(z.string().min(1)).optional(),
      deleteAll: z.array(z.string().min(1)).optional(),
      confirmDeletes: z.literal(true).optional().describe("Required when deleteDriver or deleteAll is set."),
    }),
    bin: "idm",
    build(a) {
      assertNoSurpriseDeletes(a);
      const argv = ["vault.deploy", safeToken("tree", a.tree), "--env", safeToken("env", a.env)];
      pushDrivers(argv, a.driver);
      argv.push("--yes");
      flag(argv, "--confirm", a.confirmEnv ? safeToken("confirmEnv", a.confirmEnv) : undefined);
      if (a.noRestart) argv.push("--no-restart");
      if (a.secrets) argv.push("--secrets", a.secrets);
      if (a.allowMissingSecrets) argv.push("--allow-missing-secrets");
      if (a.captureDrift) argv.push("--capture-drift");
      pushRepeat(argv, "--delete-driver", "deleteDriver", a.deleteDriver);
      pushRepeat(argv, "--delete-all", "deleteAll", a.deleteAll);
      argv.push("--json");
      rejectWriteSmuggling(argv, { allowYes: true, allowCapture: true });
      return argv;
    },
  },
  {
    name: "idm.vault.rollback",
    title: "Roll back a deploy",
    description:
      "bin/idm vault.rollback --env E --snapshot <file.ldif> --yes [--json]. Gated: IDM_AGENT_ALLOW_WRITE=1 and confirm true. Restores a deploy snapshot. The server always passes --yes.",
    annotations: WRITE,
    gated: true,
    inputSchema: z.object({
      confirm,
      env: envName,
      snapshot: z.string().min(1).describe("Snapshot LDIF from the deploy (--snapshot)."),
      tree: tree.optional().describe("Working tree. The CLI defaults to the current directory when omitted."),
    }),
    bin: "idm",
    build(a) {
      // Positional tree goes before flags: the CLI treats the next non-flag as a flag's value,
      // so a path after --yes would be swallowed.
      const argv = ["vault.rollback"];
      if (a.tree) argv.push(safeToken("tree", a.tree));
      argv.push("--env", safeToken("env", a.env), "--snapshot", safeToken("snapshot", a.snapshot), "--yes", "--json");
      rejectWriteSmuggling(argv, { allowYes: true });
      return argv;
    },
  },
  {
    name: "idm.driver.start",
    title: "Start driver",
    description:
      "bin/idm driver.start --env E --driver D [--wait N] --yes [--confirm E]. Gated. Starting a driver is a vault write. The CLI still requires --yes, and --confirm on production.",
    annotations: WRITE,
    gated: true,
    inputSchema: driverChangeSchema(),
    bin: "idm",
    build: (a) => driverChange("driver.start", a),
  },
  {
    name: "idm.driver.stop",
    title: "Stop driver",
    description:
      "bin/idm driver.stop --env E --driver D [--wait N] --yes [--confirm E]. Gated. Stopping a real connector queues its events; the cache grows until it is started again.",
    annotations: WRITE,
    gated: true,
    inputSchema: driverChangeSchema(),
    bin: "idm",
    build: (a) => driverChange("driver.stop", a),
  },
  {
    name: "idm.driver.restart",
    title: "Restart driver",
    description: "bin/idm driver.restart --env E --driver D [--wait N] --yes [--confirm E]. Gated.",
    annotations: WRITE,
    gated: true,
    inputSchema: driverChangeSchema(),
    bin: "idm",
    build: (a) => driverChange("driver.restart", a),
  },
  {
    name: "idm.driver.cache.clear",
    title: "Clear driver cache",
    description:
      "bin/idm driver.cache clear --env E --driver D --yes [--confirm E]. Gated. Drops queued events. Read idm.driver.cache.view first and show the count. The CLI requires --confirm on staging and production.",
    annotations: WRITE,
    gated: true,
    inputSchema: z.object({
      confirm,
      env: envName,
      driver,
      confirmEnv,
    }),
    bin: "idm",
    build(a) {
      const argv = ["driver.cache", "clear", "--env", safeToken("env", a.env), "--driver", safeToken("driver", a.driver), "--yes"];
      if (a.confirmEnv) argv.push("--confirm", safeToken("confirmEnv", a.confirmEnv));
      rejectWriteSmuggling(argv, { allowYes: true });
      return argv;
    },
  },
  {
    name: "fishbone.get",
    title: "Fishbone JSON",
    description:
      "The Designer-style policy-flow fishbone for one driver. Runs bin/idm query <tree> fishbone <driver> --json. When driver is omitted, runs query <tree> drivers --json and uses the first driver. Same contract as extensions/dirxmldev-visual/mcp/tools.json.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      driver: driver.optional().describe("Driver name. Omitted = first driver from query drivers --json."),
    }),
    kind: "fishbone.get",
  },
  {
    name: "fishbone.refresh",
    title: "Refresh fishbone",
    description:
      "Write <tree>/.dirxmldev/fishbone.refresh so an open fishbone webview reloads from disk. Does not write policies and does not touch the vault. Not behind IDM_AGENT_ALLOW_WRITE. Refuses a directory without driverset.xml.",
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    inputSchema: z.object({
      tree,
      driver: driver.optional().describe("Ignored. The sentinel is per tree, matching the visual stub."),
    }),
    kind: "fishbone.refresh",
  },
  {
    name: "fishbone.reveal",
    title: "Reveal fishbone node",
    description:
      "Resolve a fishbone node to a content file. Runs query fishbone --json, then matches bone key, policy ref, node id, or artifact path (the visual stub). Returns the absolute file path. Does not open an editor and does not write.",
    annotations: READ,
    inputSchema: z.object({
      tree,
      driver,
      ref: z.string().min(1).describe("Artifact path, bone key (subscriber-event), or node id."),
    }),
    kind: "fishbone.reveal",
  },
];

function appsArgs(a, rest, { json = true } = {}) {
  const argv = ["--env", safeToken("env", a.env)];
  if (a.props) argv.push("--props", safeToken("props", a.props));
  if (a.insecure) argv.push("--insecure");
  if (json) argv.push("--json");
  argv.push(...rest);
  return argv;
}

function driverChangeSchema() {
  return z.object({
    confirm,
    env: envName,
    driver,
    wait: z.number().int().min(0).max(3600).optional().describe("--wait seconds."),
    confirmEnv,
  });
}

function driverChange(cmd, a) {
  const argv = [cmd, "--env", safeToken("env", a.env), "--driver", safeToken("driver", a.driver)];
  if (a.wait !== undefined) argv.push("--wait", safeInt("wait", a.wait, { min: 0, max: 3600 }));
  argv.push("--yes");
  if (a.confirmEnv) argv.push("--confirm", safeToken("confirmEnv", a.confirmEnv));
  rejectWriteSmuggling(argv, { allowYes: true });
  return argv;
}

function assertNoSurpriseDeletes(a) {
  const n = (a.deleteDriver?.length || 0) + (a.deleteAll?.length || 0);
  if (n > 0 && a.confirmDeletes !== true) {
    throw new Error("deleteDriver or deleteAll requires confirmDeletes: true, after a human has read the dry-run plan");
  }
}

function rejectWriteSmuggling(argv, { allowYes = false, allowCapture = false } = {}) {
  const banned = ["--force", "--step", "--follow", "--stdin", "--dry-run"];
  if (!allowYes) banned.push("--yes");
  if (!allowCapture) banned.push("--capture-drift");
  for (const flagName of banned) {
    if (argv.includes(flagName)) throw new Error(`refusing to pass ${flagName}`);
  }
}

export function toolByName(name) {
  return tools.find((t) => t.name === name);
}

/** Build argv for a CLI tool. Throws on a bad argument. Used by tests and the server. */
export function argvFor(name, args) {
  const tool = toolByName(name);
  if (!tool?.build) throw new Error(`no CLI argv for ${name}`);
  return tool.build(args);
}

export const toolNames = tools.map((t) => t.name);

export const GATED = tools.filter((t) => t.gated).map((t) => t.name);
