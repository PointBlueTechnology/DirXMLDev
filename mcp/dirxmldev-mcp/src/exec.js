import { spawn } from "node:child_process";

const MAX_CHARS = 1_000_000;

/**
 * Run a command with an argument array. No shell. stdout and stderr are capped
 * so a trace or a large tree cannot pin the process.
 */
export function runProcess(bin, args, { cwd, env, timeoutMs }) {
  return new Promise((resolve) => {
    let stdout = "";
    let stderr = "";
    let truncated = false;
    let settled = false;
    let child;
    try {
      child = spawn(bin, args, { cwd, env, shell: false, windowsHide: true });
    } catch (err) {
      resolve({
        exitCode: null,
        stdout: "",
        stderr: err instanceof Error ? err.message : String(err),
        truncated: false,
        spawnError: true,
      });
      return;
    }

    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    };

    const timer = setTimeout(() => {
      truncated = true;
      stderr += `\n(timed out after ${timeoutMs}ms)\n`;
      child.kill("SIGKILL");
    }, timeoutMs);

    const take = (chunk, which) => {
      const piece = chunk.toString("utf8");
      if (which === "stdout") {
        if (stdout.length < MAX_CHARS) stdout += piece.slice(0, MAX_CHARS - stdout.length);
        else truncated = true;
      } else if (stderr.length < MAX_CHARS) {
        stderr += piece.slice(0, MAX_CHARS - stderr.length);
      } else {
        truncated = true;
      }
      if (stdout.length >= MAX_CHARS || stderr.length >= MAX_CHARS) {
        truncated = true;
        child.kill("SIGKILL");
      }
    };

    child.stdout?.on("data", (c) => take(c, "stdout"));
    child.stderr?.on("data", (c) => take(c, "stderr"));
    child.on("error", (err) => {
      finish({
        exitCode: null,
        stdout,
        stderr: stderr + (err instanceof Error ? err.message : String(err)),
        truncated,
        spawnError: true,
      });
    });
    child.on("close", (code) => {
      finish({ exitCode: code, stdout, stderr, truncated, spawnError: false });
    });
  });
}
