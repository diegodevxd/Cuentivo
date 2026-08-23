import { describe, expect, test } from "vitest";
import {
  MAX_INTERPRET_TEXT_LENGTH,
  createNotConfiguredResponse,
  parseSafeAiProposal,
  validateAuthorizeAiActionRequest,
  validateInterpretAiRequest,
} from "../src/contract.js";

const VALID_REQUEST = {
  contractVersion: 2,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "interpret_text_proposal",
  text: "vendeme dos perfumes",
} as const;

const VALID_AUTHORIZE_REQUEST = {
  contractVersion: 1,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "authorize_text",
} as const;

describe("validateInterpretAiRequest", () => {
  test("accepts the exact v2 interpret_text_proposal contract with short non-blank text", () => {
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
      "legacy v1 request",
      { contractVersion: 1, requestId: VALID_REQUEST.requestId, action: "interpret_text", text: "x" },
    ],
    [
      "disallowed action",
      { contractVersion: 2, requestId: VALID_REQUEST.requestId, action: "create_sale", text: "x" },
    ],
    [
      "invalid uuid",
      { contractVersion: 2, requestId: "not-a-uuid", action: "interpret_text_proposal", text: "x" },
    ],
    [
      "uuid over length",
      { contractVersion: 2, requestId: "123e4567-e89b-12d3-a456-4266141740000", action: "interpret_text_proposal", text: "x" },
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

  test("rejects object with inherited valid fields and arbitrary own keys", () => {
    const proto = VALID_REQUEST;
    const obj = Object.create(proto) as Record<string, unknown>;
    obj["first"] = true;
    obj["second"] = true;
    obj["third"] = true;
    obj["fourth"] = true;
    expect(validateInterpretAiRequest(obj)).toEqual({ ok: false, reason: "invalid-request" });
  });

  test("rejects request with own symbol property", () => {
    const obj = { ...VALID_REQUEST, [Symbol("extra")]: "extra" };
    expect(validateInterpretAiRequest(obj)).toEqual({ ok: false, reason: "invalid-request" });
  });
});

describe("validateAuthorizeAiActionRequest", () => {
  test("accepts the exact v1 authorize_text contract", () => {
    expect(validateAuthorizeAiActionRequest(VALID_AUTHORIZE_REQUEST)).toEqual({
      ok: true,
      value: VALID_AUTHORIZE_REQUEST,
    });
  });

  test("rejects object with inherited valid fields and arbitrary own keys", () => {
    const proto = VALID_AUTHORIZE_REQUEST;
    const obj = Object.create(proto) as Record<string, unknown>;
    obj["first"] = true;
    obj["second"] = true;
    obj["third"] = true;
    expect(validateAuthorizeAiActionRequest(obj)).toEqual({ ok: false, reason: "invalid-request" });
  });

  test("rejects request with own symbol property", () => {
    const obj = { ...VALID_AUTHORIZE_REQUEST, [Symbol("extra")]: "extra" };
    expect(validateAuthorizeAiActionRequest(obj)).toEqual({ ok: false, reason: "invalid-request" });
  });

  test.each([
    ["null", null],
    ["empty object", {}],
    ["array", []],
    [
      "future contract version",
      { contractVersion: 2, requestId: VALID_AUTHORIZE_REQUEST.requestId, action: "authorize_text" },
    ],
    [
      "disallowed action",
      { contractVersion: 1, requestId: VALID_AUTHORIZE_REQUEST.requestId, action: "interpret_text" },
    ],
    [
      "invalid uuid",
      { contractVersion: 1, requestId: "not-a-uuid", action: "authorize_text" },
    ],
    ["missing requestId", { contractVersion: 1, action: "authorize_text" }],
    ["extra field", { ...VALID_AUTHORIZE_REQUEST, extra: true }],
  ] as const)("rejects %s", (_name, input) => {
    expect(validateAuthorizeAiActionRequest(input)).toEqual({ ok: false, reason: "invalid-request" });
  });
});

describe("contract parseSafeAiProposal export", () => {
  test("re-exports parseSafeAiProposal function", () => {
    expect(typeof parseSafeAiProposal).toBe("function");
    expect(
      parseSafeAiProposal({ kind: "record_payment", customerName: "A", declaredAmountCents: "0" }),
    ).toEqual({ kind: "record_payment", customerName: "A", declaredAmountCents: "0" });
  });
});

describe("createNotConfiguredResponse", () => {
  test("returns the fixed v2 unavailable response with the same requestId", () => {
    expect(createNotConfiguredResponse(VALID_REQUEST)).toEqual({
      contractVersion: 2,
      requestId: VALID_REQUEST.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
  });
});
