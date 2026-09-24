/** Argument checks for values that become a single argv element. No shell is involved. */

export function safeToken(label, value) {
  if (typeof value !== "string" || value.length === 0 || value.includes("\0") || /[\r\n]/.test(value)) {
    throw new Error(`${label} must be a non-empty single-line string`);
  }
  if (value.startsWith("-")) {
    throw new Error(`${label} must not start with '-'`);
  }
  return value;
}

/** A flag value that may itself start with a single dash (a grep), never with `--`. */
export function safeValue(label, value) {
  if (typeof value !== "string" || value.length === 0 || value.includes("\0") || /[\r\n]/.test(value)) {
    throw new Error(`${label} must be a non-empty single-line string`);
  }
  if (value.startsWith("--")) {
    throw new Error(`${label} must not start with '--'`);
  }
  return value;
}

export function safeInt(label, value, { min = 0, max = 1_000_000 } = {}) {
  if (typeof value !== "number" || !Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${label} must be an integer from ${min} to ${max}`);
  }
  return String(value);
}

export function oneOf(label, value, allowed) {
  const v = safeToken(label, value);
  if (!allowed.includes(v)) {
    throw new Error(`${label} must be one of: ${allowed.join(", ")}`);
  }
  return v;
}

export function pushDrivers(argv, drivers) {
  for (const d of drivers || []) {
    argv.push("--driver", safeToken("driver", d));
  }
}

export function pushRepeat(argv, flag, label, values) {
  for (const v of values || []) {
    argv.push(flag, safeToken(label, v));
  }
}
