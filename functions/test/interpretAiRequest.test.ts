import { describe, expect, test } from "vitest";
import { createInterpretAiHandler } from "../src/interpretAiRequest.js";
import type { TextInterpretationProvider } from "../src/ai/geminiTextProvider.js";
import type { AuthorizeAiInput, AuthorizeAiResult } from "../src/ai/controlPlane.js";
import type { SafeInterpretation } from "../src/contract.js";

const NOW = 1_000_000;

const VALID_DATA = {
  contractVersion: 1,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "interpret_text",
  text: "vendeme dos perfumes",
} as const;

const AUTHORIZED: AuthorizeAiResult = {
  decision: "AI_AUTHORIZED",
  provider: "GEMINI",
  fallbackUsed: false,
};

const INTERPRETATION: SafeInterpretation = {
  kind: "requires_clarification",
  code: "NEEDS_CONTEXT",
};

function recordingDeps(overrides?: {
  authorizeResult?: AuthorizeAiResult;
  authorizeThrows?: boolean;
  claim?: "CLAIMED" | "ALREADY_CLAIMED";
  claimThrows?: boolean;
  provider?: TextInterpretationProvider | null;
  providerThrows?: boolean;
  providerResult?: SafeInterpretation | null;
}) {
  const authorizeInputs: AuthorizeAiInput[] = [];
  const claimInputs: Array<{ uid: string; requestId: string; nowEpochMillis: number }> = [];
  const providerInputs: Array<{ text: string }> = [];
  const deps = {
    authorize: async (input: AuthorizeAiInput) => {
      authorizeInputs.push(input);
      if (overrides?.authorizeThrows) {
        throw new Error("control plane down");
      }
      return overrides?.authorizeResult ?? AUTHORIZED;
    },
    claimProviderInvocation: async (input: {
      uid: string;
      requestId: string;
      nowEpochMillis: number;
    }) => {
      claimInputs.push(input);
      if (overrides?.claimThrows) {
        throw new Error("storage down");
      }
      return overrides?.claim ?? "CLAIMED";
    },
    provider:
      overrides?.provider === undefined
        ? {
            async interpret(input: { text: string }) {
              providerInputs.push(input);
              if (overrides?.providerThrows) {
                throw new Error("provider down");
              }
              const result =
                overrides && "providerResult" in overrides
                  ? overrides.providerResult
                  : INTERPRETATION;
              return result;
            },
          }
        : overrides.provider,
    now: () => NOW,
  };
  return { deps, authorizeInputs, claimInputs, providerInputs };
}

function makeHandler(overrides?: Parameters<typeof recordingDeps>[0]) {
  const recorded = recordingDeps(overrides);
  return {
    handler: createInterpretAiHandler(recorded.deps),
    ...recorded,
  };
}

describe("createInterpretAiHandler", () => {
  test("without auth returns unauthenticated and touches nothing", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs } = makeHandler();

    const result = await handler({ data: VALID_DATA });

    expect(result).toEqual({ code: "unauthenticated" });
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("invalid payload returns invalid-argument and touches nothing", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs } = makeHandler();

    const result = await handler({
      auth: { uid: "test-user" },
      data: { ...VALID_DATA, extra: true },
    });

    expect(result).toEqual({ code: "invalid-argument" });
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test.each([
    ["AI_DISABLED", { decision: "AI_DISABLED", provider: null, fallbackUsed: false }],
    [
      "AI_ENTITLEMENT_REQUIRED",
      { decision: "AI_ENTITLEMENT_REQUIRED", provider: null, fallbackUsed: false },
    ],
    ["AI_QUOTA_EXHAUSTED", { decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false }],
    ["AI_RATE_LIMITED", { decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false }],
  ] as const)("control plane denial %s returns unavailable without claim or provider", async (_name, denial) => {
    const { handler, claimInputs, providerInputs } = makeHandler({ authorizeResult: denial });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      interpretation: null,
    });
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("control plane failure returns unavailable without claim or provider", async () => {
    const { handler, claimInputs, providerInputs } = makeHandler({ authorizeThrows: true });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("null provider returns unavailable without claiming the invocation", async () => {
    const { handler, claimInputs, providerInputs } = makeHandler({ provider: null });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      interpretation: null,
    });
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("authorized request claims durably then calls the provider exactly once", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs } = makeHandler();

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(authorizeInputs).toEqual([
      { uid: "test-user", requestId: VALID_DATA.requestId, capability: "TEXT", nowEpochMillis: NOW },
    ]);
    expect(claimInputs).toEqual([
      { uid: "test-user", requestId: VALID_DATA.requestId, nowEpochMillis: NOW },
    ]);
    expect(providerInputs).toEqual([{ text: VALID_DATA.text }]);
    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_INTERPRETED",
      interpretation: INTERPRETATION,
    });
  });

  test("already claimed request id never invokes the provider", async () => {
    const { handler, providerInputs } = makeHandler({ claim: "ALREADY_CLAIMED" });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      interpretation: null,
    });
    expect(providerInputs).toHaveLength(0);
  });

  test("claim failure fails closed without invoking the provider", async () => {
    const { handler, providerInputs } = makeHandler({ claimThrows: true });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(providerInputs).toHaveLength(0);
  });

  test("provider null result returns unavailable", async () => {
    const { handler } = makeHandler({ providerResult: null });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      interpretation: null,
    });
  });

  test("provider failure returns unavailable", async () => {
    const { handler } = makeHandler({ providerThrows: true });

    const result = await handler({ auth: { uid: "test-user" }, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      interpretation: null,
    });
  });
});
