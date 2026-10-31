import fs from "fs";
import path from "path";
import { describe, expect, test } from "vitest";
import {
  MAX_INTERPRET_TEXT_LENGTH,
  MAX_LEDGER_IMAGE_BASE64_LENGTH,
  createNotConfiguredResponse,
  parseLedgerExtraction,
  parseSafeAiProposal,
  isRegisterableLedgerOperation,
  validateAuthorizeAiActionRequest,
  validateExtractLedgerPageRequest,
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

const VALID_V3_REQUEST = {
  contractVersion: 3,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "extract_ledger_page",
  imageBase64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
  imageMimeType: "image/png",
} as const;

describe("v2 and v3 mutual compatibility and isolation", () => {
  test("validateInterpretAiRequest (v2) continues to accept valid v2 requests", () => {
    expect(validateInterpretAiRequest(VALID_REQUEST)).toEqual({
      ok: true,
      value: VALID_REQUEST,
    });
  });

  test("validateInterpretAiRequest (v2) strictly rejects v3 extract_ledger_page requests", () => {
    expect(validateInterpretAiRequest(VALID_V3_REQUEST)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });

  test("validateExtractLedgerPageRequest (v3) strictly rejects v2 interpret_text_proposal requests", () => {
    expect(validateExtractLedgerPageRequest(VALID_REQUEST)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });

  test("validateExtractLedgerPageRequest (v3) strictly rejects v1 authorize_text requests", () => {
    expect(validateExtractLedgerPageRequest(VALID_AUTHORIZE_REQUEST)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });
});

describe("validateExtractLedgerPageRequest", () => {
  test("accepts valid v3 request with png image", () => {
    expect(validateExtractLedgerPageRequest(VALID_V3_REQUEST)).toEqual({
      ok: true,
      value: VALID_V3_REQUEST,
    });
  });

  test.each(["image/jpeg", "image/png", "image/webp"] as const)(
    "accepts allowed mime type %s",
    (mime) => {
      const req = { ...VALID_V3_REQUEST, imageMimeType: mime };
      expect(validateExtractLedgerPageRequest(req)).toEqual({
        ok: true,
        value: req,
      });
    },
  );

  test.each([
    ["null", null],
    ["empty object", {}],
    ["array", []],
    [
      "bad contractVersion",
      { ...VALID_V3_REQUEST, contractVersion: 2 },
    ],
    [
      "bad action",
      { ...VALID_V3_REQUEST, action: "interpret_text_proposal" },
    ],
    [
      "invalid uuid",
      { ...VALID_V3_REQUEST, requestId: "not-a-valid-uuid" },
    ],
    [
      "disallowed mime type",
      { ...VALID_V3_REQUEST, imageMimeType: "image/gif" },
    ],
    [
      "empty imageBase64",
      { ...VALID_V3_REQUEST, imageBase64: "" },
    ],
    [
      "non-string imageBase64",
      { ...VALID_V3_REQUEST, imageBase64: 12345 },
    ],
    [
      "unpadded/malformed base64",
      { ...VALID_V3_REQUEST, imageBase64: "abcde" },
    ],
    [
      "corrupted characters in base64",
      { ...VALID_V3_REQUEST, imageBase64: "====" },
    ],
    [
      "missing field",
      {
        contractVersion: 3,
        requestId: VALID_V3_REQUEST.requestId,
        action: VALID_V3_REQUEST.action,
        imageBase64: VALID_V3_REQUEST.imageBase64,
      },
    ],
    [
      "extra field",
      { ...VALID_V3_REQUEST, extra: true },
    ],
    [
      "image over MAX_LEDGER_IMAGE_BASE64_LENGTH limit",
      {
        ...VALID_V3_REQUEST,
        imageBase64: "AAAA".repeat(2_000_001), // 8_000_004 chars > 8_000_000
      },
    ],
  ] as const)("rejects %s", (_name, input) => {
    expect(validateExtractLedgerPageRequest(input)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });

  test("accepts imageBase64 at exactly MAX_LEDGER_IMAGE_BASE64_LENGTH (8_000_000)", () => {
    const atLimit = {
      ...VALID_V3_REQUEST,
      imageBase64: "AAAA".repeat(2_000_000), // exactly 8_000_000 chars
    };
    expect(validateExtractLedgerPageRequest(atLimit).ok).toBe(true);
  });

  test("rejects prototype pollution with inherited valid keys", () => {
    const proto = VALID_V3_REQUEST;
    const obj = Object.create(proto) as Record<string, unknown>;
    obj["extra1"] = 1;
    obj["extra2"] = 2;
    obj["extra3"] = 3;
    obj["extra4"] = 4;
    obj["extra5"] = 5;
    expect(validateExtractLedgerPageRequest(obj)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });

  test("rejects non-plain prototypes such as class instances", () => {
    class CustomRequest {
      contractVersion = 3 as const;
      requestId = VALID_V3_REQUEST.requestId;
      action = VALID_V3_REQUEST.action;
      imageBase64 = VALID_V3_REQUEST.imageBase64;
      imageMimeType = VALID_V3_REQUEST.imageMimeType;
    }
    expect(validateExtractLedgerPageRequest(new CustomRequest())).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });

  test("accepts Object.create(null) with exact keys", () => {
    const obj = Object.create(null) as Record<string, unknown>;
    obj["contractVersion"] = 3;
    obj["requestId"] = VALID_V3_REQUEST.requestId;
    obj["action"] = VALID_V3_REQUEST.action;
    obj["imageBase64"] = VALID_V3_REQUEST.imageBase64;
    obj["imageMimeType"] = VALID_V3_REQUEST.imageMimeType;
    expect(validateExtractLedgerPageRequest(obj)).toEqual({
      ok: true,
      value: VALID_V3_REQUEST,
    });
  });

  test("never throws when input has a throwing getter", () => {
    const dangerous = {
      contractVersion: 3,
      get requestId(): string {
        throw new Error("Explosive getter");
      },
      action: "extract_ledger_page",
      imageBase64: VALID_V3_REQUEST.imageBase64,
      imageMimeType: "image/png",
    };
    expect(validateExtractLedgerPageRequest(dangerous)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });

  test("rejects request containing symbol property", () => {
    const obj = { ...VALID_V3_REQUEST, [Symbol("hack")]: true };
    expect(validateExtractLedgerPageRequest(obj)).toEqual({
      ok: false,
      reason: "invalid-request",
    });
  });
});

describe("contract parseLedgerExtraction export", () => {
  test("re-exports parseLedgerExtraction function", () => {
    expect(typeof parseLedgerExtraction).toBe("function");
    expect(parseLedgerExtraction(null)).toBeNull();
  });
});

describe("contract isRegisterableLedgerOperation invariant", () => {
  const VALID_REGISTERABLE_OP = {
    lineIndex: 0,
    proposedKind: "SALE" as const,
    customerName: "Juan Pérez",
    amountCents: "10000",
    uncertainFields: [] as const,
    unreadableFields: [] as const,
  };

  test("returns true only when both unreadableFields and uncertainFields are empty and values are canonical", () => {
    expect(isRegisterableLedgerOperation(VALID_REGISTERABLE_OP)).toBe(true);
  });

  test("rejects operation when uncertainFields has any items (e.g. AMOUNT in doubt)", () => {
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        uncertainFields: ["AMOUNT" as const],
      })
    ).toBe(false);
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        uncertainFields: ["DATE" as const],
      })
    ).toBe(false);
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        uncertainFields: ["CUSTOMER" as const],
      })
    ).toBe(false);
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        uncertainFields: ["KIND" as const],
      })
    ).toBe(false);
  });

  test("rejects operation when unreadableFields has any items", () => {
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        unreadableFields: ["AMOUNT" as const],
      })
    ).toBe(false);
  });

  test.each([
    ["empty string", ""],
    ["whitespace string", "   "],
    ["number as money", 10000 as unknown as string],
    ["negative string", "-100"],
    ["decimal string", "100.50"],
    ["leading zeros", "00500"],
    ["too long (19 digits)", "1" + "0".repeat(18)],
  ])("rejects non-canonical amountCents (%s)", (_desc, badAmount) => {
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        amountCents: badAmount,
      })
    ).toBe(false);
  });

  test.each([
    ["empty string", ""],
    ["whitespace string", "   "],
    ["over max length (81 chars)", "A".repeat(81)],
  ])("rejects invalid customerName (%s)", (_desc, badCustomer) => {
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        customerName: badCustomer,
      })
    ).toBe(false);
  });

  test("rejects missing or invalid proposedKind", () => {
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        proposedKind: undefined,
      })
    ).toBe(false);
    expect(
      isRegisterableLedgerOperation({
        ...VALID_REGISTERABLE_OP,
        proposedKind: "INVALID_KIND" as unknown as "SALE",
      })
    ).toBe(false);
  });

  test("executes all shared fixtures from shared-fixtures/ledgerOperationFixtures.json", () => {
    const fixturesPath = path.resolve(__dirname, "../../shared-fixtures/ledgerOperationFixtures.json");
    expect(fs.existsSync(fixturesPath)).toBe(true);
    const fixtures = JSON.parse(fs.readFileSync(fixturesPath, "utf8"));
    expect(fixtures.length).toBeGreaterThanOrEqual(25);
    for (const f of fixtures) {
      const result = isRegisterableLedgerOperation(f.op);
      expect(result).toBe(f.expected);
    }
  });
});

describe("validateExtractLedgerPageRequest", () => {
  const VALID_LEDGER_REQ_V3 = {
    contractVersion: 3,
    requestId: "123e4567-e89b-12d3-a456-426614174000",
    action: "extract_ledger_page",
    imageBase64: "aGVsbG8=",
    imageMimeType: "image/jpeg",
  } as const;

  const VALID_LEDGER_REQ_V4 = {
    contractVersion: 4,
    requestId: "123e4567-e89b-12d3-a456-426614174000",
    action: "extract_ledger_page",
    imageBase64: "aGVsbG8=",
    imageMimeType: "image/png",
  } as const;

  test("accepts contractVersion 3", () => {
    const res = validateExtractLedgerPageRequest(VALID_LEDGER_REQ_V3);
    expect(res).toEqual({ ok: true, value: VALID_LEDGER_REQ_V3 });
  });

  test("accepts contractVersion 4", () => {
    const res = validateExtractLedgerPageRequest(VALID_LEDGER_REQ_V4);
    expect(res).toEqual({ ok: true, value: VALID_LEDGER_REQ_V4 });
  });

  test("accepts contractVersion 5", () => {
    const request = { ...VALID_LEDGER_REQ_V4, contractVersion: 5 as const };
    expect(validateExtractLedgerPageRequest(request)).toEqual({ ok: true, value: request });
  });

  test.each([
    ["version 2", 2],
    ["version 0", 0],
    ["negative version", -3],
    ["float version 3.5", 3.5],
    ["string version '3'", "3"],
    ["null version", null],
    ["undefined version", undefined],
  ])("rejects invalid contractVersion (%s)", (_desc, badVersion) => {
    const res = validateExtractLedgerPageRequest({ ...VALID_LEDGER_REQ_V3, contractVersion: badVersion });
    expect(res).toEqual({ ok: false, reason: "invalid-request" });
  });

  test.each([
    ["extra property", { ...VALID_LEDGER_REQ_V4, extraKey: true }],
    ["missing imageBase64", { contractVersion: 4, requestId: VALID_LEDGER_REQ_V4.requestId, action: "extract_ledger_page", imageMimeType: "image/png" }],
    ["invalid action", { ...VALID_LEDGER_REQ_V4, action: "interpret_text_proposal" }],
    ["invalid mimeType", { ...VALID_LEDGER_REQ_V4, imageMimeType: "image/gif" }],
    ["invalid requestId UUID", { ...VALID_LEDGER_REQ_V4, requestId: "not-a-uuid" }],
    ["empty imageBase64", { ...VALID_LEDGER_REQ_V4, imageBase64: "" }],
    ["malformed base64", { ...VALID_LEDGER_REQ_V4, imageBase64: "!!!notbase64!!!" }],
  ])("rejects invalid request structure (%s)", (_desc, badReq) => {
    const res = validateExtractLedgerPageRequest(badReq);
    expect(res).toEqual({ ok: false, reason: "invalid-request" });
  });
});

/**
 * `acceptsMexalmCrosscheck`: una capacidad OPCIONAL en la petición de la libreta.
 *
 * Existe porque la app publicada (≤ v48) rechaza la página entera ante una llave raíz que no
 * conoce, así que el servidor no puede mandarle el bloque a ciegas. La valida el mismo validador
 * estricto que todo lo demás, y con la misma política: si no es un booleano, se rechaza. No se
 * "ignora", y no por gusto de pureza: en JavaScript el texto "false" es verdadero, así que un
 * validador que se tragara cualquier cosa y después preguntara por truthiness le mandaría el bloque
 * a un cliente que dijo "false". Rechazar es lo único que no tiene que adivinar nada.
 */
describe("validateExtractLedgerPageRequest: la capacidad de la verificación cruzada", () => {
  const BASE = {
    contractVersion: 5,
    requestId: "123e4567-e89b-12d3-a456-426614174000",
    action: "extract_ledger_page",
    imageBase64: "aGVsbG8=",
    imageMimeType: "image/jpeg",
  } as const;

  test.each([true, false])("accepts the capability as the boolean %s and carries it in the value", (capability) => {
    const request = { ...BASE, acceptsMexalmCrosscheck: capability };
    expect(validateExtractLedgerPageRequest(request)).toEqual({
      ok: true,
      value: { ...BASE, acceptsMexalmCrosscheck: capability },
    });
  });

  test("an old request without the capability still validates to exactly the same value as before", () => {
    const res = validateExtractLedgerPageRequest(BASE);
    expect(res).toEqual({ ok: true, value: BASE });
    // Y sin la llave: el valor validado de una petición vieja no gana un campo que no traía.
    expect(res.ok && "acceptsMexalmCrosscheck" in res.value).toBe(false);
  });

  test.each([
    ["string 'true'", "true"],
    ["string 'false'", "false"],
    ["number 1", 1],
    ["number 0", 0],
    ["null", null],
    ["undefined", undefined],
    ["object", {}],
    ["array", []],
  ])("rejects a non-boolean capability (%s) instead of guessing what it meant", (_desc, bad) => {
    const res = validateExtractLedgerPageRequest({ ...BASE, acceptsMexalmCrosscheck: bad });
    expect(res).toEqual({ ok: false, reason: "invalid-request" });
  });

  test("the capability does not open the door to any OTHER key", () => {
    for (const extra of [
      { acceptsMexalmCrosscheckPlus: true },
      { knownCustomers: [] },
      { acceptsMexalmCrosscheck: true, extraKey: true },
      { acceptsMexalmCrosscheck: true, prompt: "hola" },
    ]) {
      expect(validateExtractLedgerPageRequest({ ...BASE, ...extra })).toEqual({
        ok: false,
        reason: "invalid-request",
      });
    }
  });

  test("the capability is not a substitute for a required key", () => {
    const { imageMimeType: _omitted, ...withoutMimeType } = BASE;
    expect(
      validateExtractLedgerPageRequest({ ...withoutMimeType, acceptsMexalmCrosscheck: true }),
    ).toEqual({ ok: false, reason: "invalid-request" });
  });

  test("the capability is accepted on every version: v3, v4 and v5", () => {
    for (const contractVersion of [3, 4, 5] as const) {
      expect(
        validateExtractLedgerPageRequest({ ...BASE, contractVersion, acceptsMexalmCrosscheck: true }).ok,
      ).toBe(true);
    }
  });
});
