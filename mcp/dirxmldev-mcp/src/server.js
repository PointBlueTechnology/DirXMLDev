import { McpServer } from "@modelcontextprotocol/server";
import { buildContext } from "./context.js";
import { tools } from "./catalog.js";
import { runProcess } from "./exec.js";
import { refreshSentinel, resolveInside, revealRelative } from "./fishbone.js";
import { loadSensitiveValues, scrub } from "./redact.js";
import { INSTRUCTIONS } from "./runtime.js";
import { safeToken } from "./safe.js";

export const SCHEMA_VERSION = 1;

function envelope(tool, fields) {
  return {
    schemaVersion: SCHEMA_VERSION,
    tool,
    ok: false,
    exitCode: null,
    command: [],
    result: null,
    text: null,
    stderr: "",
    truncated: false,
    blocked: false,
    ...fields,
  };
}

function toolResult(body, isError = false) {
  return {
    content: [{ type: "text", text: JSON.stringify(body, null, 2) }],
    isError,
  };
}

function blocked(tool, message) {
  return toolResult(envelope(tool, { stderr: message, blocked: true }), true);
}

function failed(tool, message) {
  return toolResult(envelope(tool, { stderr: message }), true);
}

function writesAllowed(env) {
  return env.IDM_AGENT_ALLOW_WRITE === "1";
}

function parseJson(stdout) {
  const trimmed = stdout.trim();
  if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null;
  try {
    return JSON.parse(trimmed);
  } catch {
    return null;
  }
}

async function runCli(toolName, binKey, argv, rt) {
  const bin = binKey === "apps" ? rt.appsBin : rt.idmBin;
  const secrets = loadSensitiveValues([rt.cwd, rt.repoRoot].filter(Boolean), rt.env);
  const ran = await runProcess(bin, argv, { cwd: rt.cwd, env: rt.env, timeoutMs: rt.timeoutMs });
  const stdout = scrub(ran.stdout, secrets);
  let stderr = scrub(ran.stderr, secrets);
  if (ran.truncated) stderr = `${stderr}\n(output truncated)\n`;
  const result = parseJson(stdout);
  const body = envelope(toolName, {
    ok: !ran.spawnError && ran.exitCode === 0,
    exitCode: ran.exitCode,
    command: [binKey === "apps" ? "apps" : "idm", ...argv],
    result,
    text: result === null ? stdout : null,
    stderr,
    truncated: ran.truncated,
  });
  return toolResult(body, ran.spawnError || ran.exitCode === null);
}

async function queryJson(rt, argv) {
  const secrets = loadSensitiveValues([rt.cwd, rt.repoRoot].filter(Boolean), rt.env);
  const ran = await runProcess(rt.idmBin, argv, { cwd: rt.cwd, env: rt.env, timeoutMs: rt.timeoutMs });
  const stdout = scrub(ran.stdout, secrets);
  const stderr = scrub(ran.stderr, secrets);
  const result = parseJson(stdout);
  return { ran, stdout, stderr, result, command: ["idm", ...argv] };
}

async function fishboneModel(rt, tree, driver) {
  const root = safeToken("tree", tree);
  let name = driver ? safeToken("driver", driver) : null;
  const commands = [];
  if (!name) {
    const listed = await queryJson(rt, ["query", root, "drivers", "--json"]);
    commands.push(listed.command);
    if (listed.ran.spawnError || listed.result === null) {
      return {
        error: listed.stderr || listed.stdout || "query drivers failed",
        commands,
        exitCode: listed.ran.exitCode,
      };
    }
    const first = listed.result?.drivers?.[0]?.name;
    if (!first) return { error: "no driver in " + root, commands, exitCode: listed.ran.exitCode };
    name = first;
  }
  const got = await queryJson(rt, ["query", root, "fishbone", name, "--json"]);
  commands.push(got.command);
  if (got.ran.spawnError || got.result === null) {
    return { error: got.stderr || got.stdout || "query fishbone failed", commands, exitCode: got.ran.exitCode };
  }
  return { model: got.result, driver: name, commands, exitCode: got.ran.exitCode, stderr: got.stderr };
}

export function createServer(rt) {
  const server = new McpServer(
    { name: "dirxmldev", version: "0.1.0" },
    { instructions: INSTRUCTIONS },
  );

  for (const tool of tools) {
    server.registerTool(
      tool.name,
      {
        title: tool.title,
        description: tool.description,
        inputSchema: tool.inputSchema,
        annotations: tool.annotations,
      },
      async (args) => dispatch(tool, args, rt),
    );
  }
  return server;
}

async function dispatch(tool, args, rt) {
  try {
    if (tool.gated) {
      if (args?.confirm !== true) {
        return blocked(tool.name, "confirm must be true. This call was not run.");
      }
      if (!writesAllowed(rt.env)) {
        return blocked(
          tool.name,
          "IDM_AGENT_ALLOW_WRITE is not 1. Mutators are disabled. Set it on the MCP server process and call again with confirm true. The CLI was not started.",
        );
      }
    }
    if (tool.kind === "context") {
      const body = buildContext({ cwd: rt.cwd, repoRoot: rt.repoRoot, env: rt.env, tree: args.tree });
      const text = JSON.stringify(body);
      const secrets = loadSensitiveValues([rt.cwd, rt.repoRoot].filter(Boolean), rt.env);
      const scrubbed = scrub(text, secrets);
      const result = JSON.parse(scrubbed);
      return toolResult(envelope(tool.name, { ok: true, exitCode: 0, result }));
    }
    if (tool.kind === "fishbone.refresh") {
      const file = refreshSentinel(safeToken("tree", args.tree));
      return toolResult(envelope(tool.name, {
        ok: true,
        exitCode: 0,
        result: { file, wrote: "sentinel-only" },
      }));
    }
    if (tool.kind === "fishbone.get" || tool.kind === "fishbone.reveal") {
      const got = await fishboneModel(rt, args.tree, args.driver);
      if (got.error) {
        const command = got.commands?.length ? got.commands[got.commands.length - 1] : [];
        return toolResult(envelope(tool.name, {
          exitCode: got.exitCode ?? null,
          command,
          stderr: got.error,
          text: got.error,
        }), true);
      }
      if (tool.kind === "fishbone.get") {
        return toolResult(envelope(tool.name, {
          ok: got.exitCode === 0,
          exitCode: got.exitCode,
          command: got.commands[got.commands.length - 1],
          result: got.model,
          stderr: got.stderr || "",
        }));
      }
      const rel = revealRelative(got.model, safeToken("ref", args.ref));
      const file = resolveInside(got.model.treeRoot || args.tree, rel);
      return toolResult(envelope(tool.name, {
        ok: true,
        exitCode: 0,
        command: got.commands[got.commands.length - 1],
        result: { file, relative: rel, driver: got.driver },
        stderr: got.stderr || "",
      }));
    }
    const argv = tool.build(args);
    return await runCli(tool.name, tool.bin, argv, rt);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return failed(tool.name, message);
  }
}
