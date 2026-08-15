import { describe, expect, test } from "vitest";
import {
  createNotConfiguredResponse,
  validateInterpretAiRequest,
} from "../src/contract.js";

const VALID_REQUEST = {
  contractVersion: 1,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "interpret_text",
} as const;

describe("validateInterpretAiRequest", () => {
  test("accepts the exact v1 interpret_text contract", () => {
    expect(validateInterpretAiRequest(VALID_REQUEST)).toEqual({
      ok: true,
      value: VALID_REQUEST,
    });
  });

  test.each([
    ["null", null],
    ["empty object", {}],
    ["array", []],
    [
      "future contract version",
      { contractVersion: 2, requestId: VALID_REQUEST.requestId, action: "interpret_text" },
    ],
    [
      "disallowed action",
      { contractVersion: 1, requestId: VALID_REQUEST.requestId, action: "create_sale" },
    ],
    ["invalid uuid", { contractVersion: 1, requestId: "not-a-uuid", action: "interpret_text" }],
    [
      "uuid over length",
      { contractVersion: 1, requestId: "123e4567-e89b-12d3-a456-4266141740000", action: "interpret_text" },
    ],
    ["extra field", { ...VALID_REQUEST, text: "hola" }],
  ])("rejects %s", (_name, input) => {
    expect(validateInterpretAiRequest(input)).toEqual({ ok: false, reason: "invalid-request" });
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
