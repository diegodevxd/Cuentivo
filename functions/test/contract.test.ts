import { describe, expect, test } from "vitest";
import {
  MAX_INTERPRET_TEXT_LENGTH,
  createNotConfiguredResponse,
  parseSafeInterpretation,
  validateInterpretAiRequest,
} from "../src/contract.js";

const VALID_REQUEST = {
  contractVersion: 1,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "interpret_text",
  text: "vendeme dos perfumes",
} as const;

describe("validateInterpretAiRequest", () => {
  test("accepts the exact v1 interpret_text contract with short non-blank text", () => {
    expect(validateInterpretAiRequest(VALID_REQUEST)).toEqual({
      ok: true,
      value: VALID_REQUEST,
    });
  });

  test("returns the original accepted text unchanged", () => {
    const padded = { ...VALID_REQUEST, text: "  hola  " };
    const result = validateInterpretAiRequest(padded);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.value.text).toBe("  hola  ");
    }
  });

  test.each([
    ["null", null],
    ["empty object", {}],
    ["array", []],
    [
      "future contract version",
      { contractVersion: 2, requestId: VALID_REQUEST.requestId, action: "interpret_text", text: "x" },
    ],
    [
      "disallowed action",
      { contractVersion: 1, requestId: VALID_REQUEST.requestId, action: "create_sale", text: "x" },
    ],
    [
      "invalid uuid",
      { contractVersion: 1, requestId: "not-a-uuid", action: "interpret_text", text: "x" },
    ],
    [
      "uuid over length",
      { contractVersion: 1, requestId: "123e4567-e89b-12d3-a456-4266141740000", action: "interpret_text", text: "x" },
    ],
    ["missing text", { ...VALID_REQUEST, text: undefined }],
    ["blank text", { ...VALID_REQUEST, text: "   " }],
    ["non-string text", { ...VALID_REQUEST, text: 42 }],
    ["extra field", { ...VALID_REQUEST, extra: true }],
    [
      "text over the 1000 code unit limit",
      { ...VALID_REQUEST, text: "a".repeat(MAX_INTERPRET_TEXT_LENGTH + 1) },
    ],
  ] as const)("rejects %s", (_name, input) => {
    expect(validateInterpretAiRequest(input)).toEqual({ ok: false, reason: "invalid-request" });
  });

  test("accepts text at exactly the 1000 code unit limit", () => {
    const atLimit = { ...VALID_REQUEST, text: "a".repeat(MAX_INTERPRET_TEXT_LENGTH) };
    expect(validateInterpretAiRequest(atLimit).ok).toBe(true);
  });
});

describe("contract parseSafeInterpretation export", () => {
  test("re-exports parseSafeInterpretation function", () => {
    expect(typeof parseSafeInterpretation).toBe("function");
    expect(
      parseSafeInterpretation({ kind: "requires_clarification", code: "NEEDS_CONTEXT" }),
    ).toEqual({ kind: "requires_clarification", code: "NEEDS_CONTEXT" });
  });
});

describe("createNotConfiguredResponse", () => {
  test("returns the fixed AI_NOT_CONFIGURED response with the same requestId", () => {
    expect(createNotConfiguredResponse(VALID_REQUEST)).toEqual({
      contractVersion: 1,
      requestId: VALID_REQUEST.requestId,
      status: "AI_NOT_CONFIGURED",
    });
  });
});
