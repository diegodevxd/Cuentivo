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
    provider: providerReturning(EXTRACTION),
    guard: openGuard(),
    now: () => NOW,
    ...overrides,
  };
}

describe("createExtractLedgerPageHandler", () => {
  test("returns the extracted page for an authorized caller", async () => {
    const result = await createExtractLedgerPageHandler(deps())({
      auth: AUTH_USER,
      data: VALID_DATA,
    });

    expect(result).toEqual({
      contractVersion: 3,
      requestId: VALID_DATA.requestId,
      status: "AI_PROPOSAL",
      operations: EXTRACTION.operations,
      discards: [],
      unreadTextRemaining: false,
      pageIncomplete: false,
    });
  });

  test("refuses an unauthenticated caller before touching anything", async () => {
    const provider = providerReturning(EXTRACTION);
    const authorize = vi.fn(async () => AUTHORIZED);

    const result = await createExtractLedgerPageHandler(deps({ provider, authorize }))({
      data: VALID_DATA,
    });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(authorize).not.toHaveBeenCalled();
    expect(provider.extract).not.toHaveBeenCalled();
  });

  test("an anonymous session never reaches the provider", async () => {
    const provider = providerReturning(EXTRACTION);
    const onRejected = vi.fn();

    const result = await createExtractLedgerPageHandler(deps({ provider, onRejected }))({
      auth: { uid: "anon", token: { firebase: { sign_in_provider: "anonymous" } } },
      data: VALID_DATA,
    });

    expect(result).toMatchObject({ status: "AI_UNAVAILABLE", operations: null });
    expect(provider.extract).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith("ANONYMOUS_SESSION");
  });

  test("a v2 payload is rejected as invalid, so an old client cannot reach this path", async () => {
    const result = await createExtractLedgerPageHandler(deps())({
      auth: AUTH_USER,
      data: {
        contractVersion: 2,
        requestId: VALID_DATA.requestId,
        action: "interpret_text_proposal",
        text: "hola",
      },
    });

    expect(result).toEqual({ code: "invalid-argument" });
  });

  test.each([
    ["AI_QUOTA_EXHAUSTED"],
    ["AI_RATE_LIMITED"],
    ["AI_DISABLED"],
    ["AI_ENTITLEMENT_REQUIRED"],
  ] as const)("a %s decision is reported and stops before the provider", async (decision) => {
    const provider = providerReturning(EXTRACTION);

    const result = await createExtractLedgerPageHandler(
      deps({ provider, authorize: async () => ({ decision, provider: null, fallbackUsed: false }) }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toMatchObject({ status: decision, operations: null });
    expect(provider.extract).not.toHaveBeenCalled();
  });

  test("an already claimed request never invokes the provider a second time", async () => {
    const provider = providerReturning(EXTRACTION);

    const result = await createExtractLedgerPageHandler(
      deps({ provider, claimProviderInvocation: async () => "ALREADY_CLAIMED" as const }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toMatchObject({ status: "AI_UNAVAILABLE", operations: null });
    expect(provider.extract).not.toHaveBeenCalled();
  });

  test("a missing server configuration recovers without consuming the claim", async () => {
    const claim = vi.fn(async () => "CLAIMED" as const);

    const result = await createExtractLedgerPageHandler(
      deps({ provider: null, claimProviderInvocation: claim }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toMatchObject({ status: "AI_UNAVAILABLE" });
    expect(claim).not.toHaveBeenCalled();
  });

  test("a provider that fails collapses to the fixed unavailable response", async () => {
    const result = await createExtractLedgerPageHandler(
      deps({ provider: { extract: async () => { throw new Error("boom"); } } }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 3,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      operations: null,
      discards: [],
      unreadTextRemaining: false,
      pageIncomplete: false,
    });
  });

  test("an incomplete page keeps its warning instead of looking whole", async () => {
    const result = await createExtractLedgerPageHandler(
      deps({
        provider: providerReturning({ ...EXTRACTION, pageIncomplete: true, unreadTextRemaining: true }),
      }),
    )({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toMatchObject({ pageIncomplete: true, unreadTextRemaining: true });
  });

  test("the image reaches the provider exactly as validated", async () => {
    const provider = providerReturning(EXTRACTION);

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
