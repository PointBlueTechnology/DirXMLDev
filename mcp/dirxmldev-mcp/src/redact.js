import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/**
 * Values that must never appear in a tool result. Sources are the environments
 * file, the secrets files it points at, and password-like -D entries in
 * IDM_JAVA_OPTS. The files themselves are never returned.
 */

const SECRET_LEAF = /(password|passwd|secret|token|credential)/i;
const INDIRECTION = /(Keychain|Command|Env)$/i;
const MIN_SCRUB = 6;

function leafOf(key) {
  const i = key.lastIndexOf(".");
  return i < 0 ? key : key.slice(i + 1);
}

/** A property whose value is the secret itself (not a keychain item, command, env-var name, or secrets-file path). */
export function isLiteralSecretKey(key) {
  const leaf = leafOf(key);
  if (INDIRECTION.test(leaf)) return false;
  if (/^secrets$/i.test(leaf)) return false;
  return SECRET_LEAF.test(leaf);
}

export function isEnvPointerKey(key) {
  return /Env$/i.test(leafOf(key));
}

/**
 * Pattern pass for assignments the CLI or a JVM flag might echo.
 * Quoted JSON strings and bare `key=value` / `key: value` forms.
 */
export function redactPatterns(text) {
  if (!text) return "";
  const key = String.raw`[\w.-]*?(?:password|passwd|secret|token|credential|bindpw)[\w.-]*`;
  let s = text;
  s = s.replace(new RegExp(`-D(${key})=([^\\s]+)`, "gi"), "-D$1=(redacted)");
  s = s.replace(
    new RegExp(`(^|[\\s,{])(${key})(\\s*[:=]\\s*)(?:"[^"\\n]*"|'[^'\\n]*'|[^\\s,}\\n]+)`, "gi"),
    "$1$2$3(redacted)",
  );
  s = s.replace(/(Authorization\s*:\s*Bearer\s+)\S+/gi, "$1(redacted)");
  s = s.replace(/("access_token"\s*:\s*")[^"]*/gi, '$1(redacted)');
  return s;
}

export function scrub(text, secrets) {
  let out = redactPatterns(text || "");
  const seen = new Set();
  for (const secret of secrets || []) {
    if (typeof secret !== "string" || secret.length < MIN_SCRUB || seen.has(secret)) continue;
    seen.add(secret);
    out = out.split(secret).join("(redacted)");
  }
  return out;
}

function parseProps(text) {
  const rows = [];
  for (const line of text.split(/\n/)) {
    const t = line.trim();
    if (!t || t.startsWith("#") || !t.includes("=")) continue;
    const i = t.indexOf("=");
    rows.push({ key: t.slice(0, i).trim(), value: t.slice(i + 1).trim() });
  }
  return rows;
}

function scanFile(file, values, seen, allValues) {
  const abs = path.resolve(file);
  if (seen.has(abs)) return;
  seen.add(abs);
  let text;
  try {
    text = fs.readFileSync(abs, "utf8");
  } catch {
    return;
  }
  for (const { key, value } of parseProps(text)) {
    if (!value) continue;
    // A secrets file is nothing but secret values (named passwords included).
    // An environments file contributes only password/secret/token literals.
    if (allValues || isLiteralSecretKey(key)) values.push(value);
    if (!allValues && isEnvPointerKey(key)) {
      const fromEnv = process.env[value];
      if (typeof fromEnv === "string" && fromEnv.length >= MIN_SCRUB) values.push(fromEnv);
    }
    if (!allValues && /^secrets$/i.test(leafOf(key))) {
      scanFile(path.resolve(path.dirname(abs), value), values, seen, true);
    }
  }
}

function javaOptSecrets(values) {
  const opts = process.env.IDM_JAVA_OPTS || "";
  const re = /(?:^|\s)-D[\w.-]*(?:password|passwd|secret|token|credential)=(\S+)/gi;
  for (const m of opts.matchAll(re)) {
    if (m[1]) values.push(m[1]);
  }
}

/**
 * Secret values to strip from tool output. `roots` are directories that may
 * hold environments.properties (the server cwd and the repo).
 */
export function loadSensitiveValues(roots, env = process.env) {
  const values = [];
  const seen = new Set();
  const files = [];
  if (env.IDM_ENVIRONMENTS) files.push(env.IDM_ENVIRONMENTS);
  for (const root of roots || []) {
    if (root) files.push(path.join(root, "environments.properties"));
  }
  files.push(path.join(os.homedir(), ".idm", "environments.properties"));
  for (const file of files) scanFile(file, values, seen);
  javaOptSecrets(values);
  return values;
}

/** Environment names (the prefix before the first dot) and nothing else from a properties file. */
export function environmentNames(file) {
  let text;
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return [];
  }
  const names = new Set();
  for (const { key } of parseProps(text)) {
    const dot = key.indexOf(".");
    if (dot > 0) names.add(key.slice(0, dot));
  }
  return [...names].sort();
}

export function secretsBasenames(dir) {
  let entries;
  try {
    entries = fs.readdirSync(dir);
  } catch {
    return [];
  }
  return entries.filter((n) => /^secrets.*\.properties$/i.test(n)).sort();
}
