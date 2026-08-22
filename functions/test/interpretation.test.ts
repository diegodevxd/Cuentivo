import { describe, expect, test } from "vitest";
import { parseSafeInterpretation } from "../src/ai/interpretation.js";

describe("parseSafeInterpretation", () => {
  test.each([
    [{ kind: "requires_clarification", code: "NEEDS_CONTEXT" }],
    [{ kind: "requires_clarification", code: "AMBIGUOUS" }],
    [{ kind: "unsupported", code: "UNSUPPORTED" }],
    [{ kind: "unsupported", code: "UNSAFE" }],
  ] as const)("accepts the allowed pair %o", (value) => {
    expect(parseSafeInterpretation(value)).toEqual(value);
  });

  test.each([
    ["null", null],
    ["string", "unsupported"],
    ["array", [{ kind: "unsupported", code: "UNSAFE" }]],
    ["empty object", {}],
    ["undefined", undefined],
    ["boolean", true],
    ["number", 123],
    ["unknown kind", { kind: "other", code: "UNSAFE" }],
    ["unknown code", { kind: "unsupported", code: "OTHER" }],
    ["mismatched pair", { kind: "unsupported", code: "NEEDS_CONTEXT" }],
    ["extra key", { kind: "unsupported", code: "UNSAFE", extra: true }],
    ["amount key", { kind: "unsupported", code: "UNSAFE", amountCents: 500 }],
    ["saleId key", { kind: "unsupported", code: "UNSAFE", saleId: "sale-1" }],
    ["paymentId key", { kind: "unsupported", code: "UNSAFE", paymentId: "pay-1" }],
    ["nested object", { kind: "unsupported", code: "UNSAFE", payload: { text: "x" } }],
    ["nested kind", { kind: { value: "unsupported" }, code: "UNSAFE" }],
    ["numeric code", { kind: "unsupported", code: 42 }],
    ["object with symbol key", { [Symbol("test")]: "val", kind: "unsupported", code: "UNSAFE" }],
  ] as const)("rejects %s with null", (_name, value) => {
    expect(parseSafeInterpretation(value)).toBeNull();
  });
});
