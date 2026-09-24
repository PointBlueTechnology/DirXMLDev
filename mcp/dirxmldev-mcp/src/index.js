#!/usr/bin/env node
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { createServer } from "./server.js";
import { resolveRuntime } from "./runtime.js";

const rt = resolveRuntime(process.env, process.cwd());
serveStdio(() => createServer(rt));
