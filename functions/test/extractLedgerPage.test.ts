import { describe, expect, test, vi } from "vitest";
import { createExtractLedgerPageHandler } from "../src/extractLedgerPage.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { LedgerExtractionProvider } from "../src/ai/geminiLedgerProvider.js";
import type { AuthorizeAiResult } from "../src/ai/controlPlane.js";
import type { LedgerExtractionResult } from "../src/contract.js";

const NOW = 1_000_000;

const AUTH_USER = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
} as const;

const VALID_DATA = {
  contractVersion: 3,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "extract_ledger_page",
  imageBase64: "aGVsbG8=",
  imageMimeType: "image/jpeg",
} as const;

const AUTHORIZED: AuthorizeAiResult = {
  decision: "AI_AUTHORIZED",
  provider: "GEMINI",
  fallbackUsed: false,
};

const EXTRACTION: LedgerExtractionResult = {
  operations: [
    {
      lineIndex: 0,
      proposedKind: "SALE",
      customerName: "Rosa",
      amountCents: "25000",
      uncertainFields: [],
      unreadableFields: [],
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
};

const EXTRACTION_V4: LedgerExtractionResult = {
  operations: [
    {
      lineIndex: 0,
      proposedKind: "SALE",
      customerName: "Rosa",
      amountCents: "25000",
      description: "Protector tapete",
      uncertainFields: [],
      unreadableFields: [],
      confidence: { customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 },
      sources: { customer: "EXPLICIT", date: "PAGE_DATE" },
      rawText: "Rosa protector tapete 250",
      items: [{ description: "Protector tapete", quantity: 1, subtotalCents: "25000" }],
      warnings: [],
    },
  ],
  discards: [],
  unreadTextRemaining: false,
  pageIncomplete: false,
  pageDate: { date: { year: 2026, month: 7, day: 2 }, confidence: 0.95 },
};

function openGuard() {
  return createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1_000, maxTrackedUids: 1_000 });
}

function providerReturning(result: LedgerExtractionResult | null): LedgerExtractionProvider {
  return { extract: vi.fn(async () => result) };
}

function deps(overrides: Partial<Parameters<typeof createExtractLedgerPageHandler>[0]> = {}) {
  return {
    authorize: async () => AUTHORIZED,
    claimProviderInvocation: async () => "CLAIMED" as const,
    provider: providerReturning(EXTRACTION_V4),
    guard: openGuard(),
    now: () => NOW,
    ...overrides,
  };
}

const V3_ALLOWED_ROOT_KEYS = [
  "contractVersion",
  "requestId",
  "status",
  "operations",
  "discards",
  "unreadTextRemaining",
  "pageIncomplete",
] as const;

const V3_ALLOWED_OP_KEYS = [
  "lineIndex",
  "proposedKind",
  "customerName",
  "amountCents",
  "description",
  "date",
  "uncertainFields",
  "unreadableFields",
  "region",
] as const;

describe("createExtractLedgerPageHandler", () => {
  test("v3 request returns exact v3 projection without a single v4 key even when provider returns rich v4 data", async () => {
    const result = (await createExtractLedgerPageHandler(deps())({
      auth: AUTH_USER,
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(result.status).toBe("AI_PROPOSAL");
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(result).not.toHaveProperty("pageDate");

    expect(result.operations).toHaveLength(1);
    const op = result.operations[0];
    const opKeys = Object.keys(op);
    for (const key of opKeys) {
      expect((V3_ALLOWED_OP_KEYS as readonly string[]).includes(key)).toBe(true);
    }
    expect(op).not.toHaveProperty("confidence");
    expect(op).not.toHaveProperty("sources");
    expect(op).not.toHaveProperty("rawText");
    expect(op).not.toHaveProperty("items");
    expect(op).not.toHaveProperty("warnings");
  });

  test("v4 request preserves rich v4 data including pageDate, confidence, sources, items, rawText, warnings", async () => {
    const v4Data = { ...VALID_DATA, contractVersion: 4 };
    const result = (await createExtractLedgerPageHandler(deps())({
      auth: AUTH_USER,
      data: v4Data,
    })) as any;

    expect(result.contractVersion).toBe(4);
    expect(result.status).toBe("AI_PROPOSAL");
    expect(result.pageDate).toEqual({ date: { year: 2026, month: 7, day: 2 }, confidence: 0.95 });
    expect(result.operations[0].confidence).toEqual({ customer: 0.95, concept: 0.9, amount: 0.98, date: 0.9, kind: 0.95 });
    expect(result.operations[0].sources).toEqual({ customer: "EXPLICIT", date: "PAGE_DATE" });
    expect(result.operations[0].rawText).toBe("Rosa protector tapete 250");
    expect(result.operations[0].items).toEqual([{ description: "Protector tapete", quantity: 1, subtotalCents: "25000" }]);
  });

  test.each([
    ["AI_UNAVAILABLE", async () => ({} as any), deps({ provider: null })],
    ["AI_QUOTA_EXHAUSTED", async () => ({ decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false }) })],
    ["AI_RATE_LIMITED", async () => ({ decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false }) })],
    ["AI_DISABLED", async () => ({ decision: "AI_DISABLED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_DISABLED", provider: null, fallbackUsed: false }) })],
    ["AI_ENTITLEMENT_REQUIRED", async () => ({ decision: "AI_ENTITLEMENT_REQUIRED", provider: null, fallbackUsed: false }), deps({ authorize: async () => ({ decision: "AI_ENTITLEMENT_REQUIRED", provider: null, fallbackUsed: false }) })],
    ["PROVIDER_ERROR", async () => ({} as any), deps({ provider: { extract: async () => { throw new Error("boom"); } } })],
    ["REPLAY", async () => ({} as any), deps({ claimProviderInvocation: async () => "ALREADY_CLAIMED" as const })],
  ])("v3 projection holds for non-proposal branch: %s", async (_name, _authOverride, testDeps) => {
    const result = (await createExtractLedgerPageHandler(testDeps)({
      auth: AUTH_USER,
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(result).not.toHaveProperty("pageDate");
  });

  test("refuses an unauthenticated caller before touching anything", async () => {
    const provider = providerReturning(EXTRACTION_V4);
    const authorize = vi.fn(async () => AUTHORIZED);

    const result = await createExtractLedgerPageHandler(deps({ provider, authorize }))({
      data: VALID_DATA,
    });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(authorize).not.toHaveBeenCalled();
    expect(provider.extract).not.toHaveBeenCalled();
  });

  test("an anonymous session never reaches the provider and respects v3 key projection", async () => {
    const provider = providerReturning(EXTRACTION_V4);
    const onRejected = vi.fn();

    const result = (await createExtractLedgerPageHandler(deps({ provider, onRejected }))({
      auth: { uid: "anon", token: { firebase: { sign_in_provider: "anonymous" } } },
      data: VALID_DATA,
    })) as any;

    expect(result.contractVersion).toBe(3);
    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(result.operations).toBeNull();
    expect(Object.keys(result).sort()).toEqual([...V3_ALLOWED_ROOT_KEYS].sort());
    expect(provider.extract).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith("ANONYMOUS_SESSION");
  });

  test("a v2 or v5 contractVersion is rejected as invalid-argument", async () => {
    for (const badVersion of [2, 5]) {
      const result = await createExtractLedgerPageHandler(deps())({
        auth: AUTH_USER,
        data: {
          ...VALID_DATA,
          contractVersion: badVersion,
        },
      });
      expect(result).toEqual({ code: "invalid-argument" });
    }
  });

  test("an incomplete page keeps its warning instead of looking whole", async () => {
    const result = await createExtractLedgerPageHandler(
      deps({
        provider: providerReturning({ ...EXTRACTION_V4, pageIncomplete: true, unreadTextRemaining: true }),
      }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toMatchObject({ pageIncomplete: true, unreadTextRemaining: true });
  });

  test("the image reaches the provider exactly as validated", async () => {
    const provider = providerReturning(EXTRACTION_V4);

    await createExtractLedgerPageHandler(deps({ provider }))({
      auth: AUTH_USER,
      data: VALID_DATA,
    });

    expect(provider.extract).toHaveBeenCalledTimes(1);
    expect(provider.extract).toHaveBeenCalledWith(
      expect.objectContaining({
        imageBase64: VALID_DATA.imageBase64,
        imageMimeType: VALID_DATA.imageMimeType,
      }),
    );
  });
});
