import type { SafeInterpretation } from "../contract";

const ALLOWED_INTERPRETATION_PAIRS: ReadonlyArray<readonly [string, string]> = [
  ["requires_clarification", "NEEDS_CONTEXT"],
  ["requires_clarification", "AMBIGUOUS"],
  ["unsupported", "UNSUPPORTED"],
  ["unsupported", "UNSAFE"],
];

/**
 * Provider-neutral, exact-object parser for a safe interpretation. Accepts ONLY a plain non-null
 * object whose keys are exactly `kind` and `code` and whose pair is one of the four allow-listed
 * combinations. Anything else — extra keys, amounts, nested objects, unknown or mismatched
 * pairs — returns null. Never throws, never logs, never stores the value.
 */
export function parseSafeInterpretation(value: unknown): SafeInterpretation | null {
  if (
    typeof value !== "object" ||
    value === null ||
    Array.isArray(value) ||
    Object.getOwnPropertySymbols(value).length > 0
  ) {
    return null;
  }
  const record = value as Record<string, unknown>;
  const keys = Object.keys(record);
  if (keys.length !== 2 || !("kind" in record) || !("code" in record)) {
    return null;
  }
  const kind = record["kind"];
  const code = record["code"];
  if (typeof kind !== "string" || typeof code !== "string") {
    return null;
  }
  for (const [allowedKind, allowedCode] of ALLOWED_INTERPRETATION_PAIRS) {
    if (kind === allowedKind && code === allowedCode) {
      return { kind, code } as SafeInterpretation;
    }
  }
  return null;
}
