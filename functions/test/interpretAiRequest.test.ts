import { describe, expect, test } from "vitest";
import { createInterpretAiHandler } from "../src/interpretAiRequest.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { ReferenceDate, TextInterpretationProvider } from "../src/ai/geminiTextProvider.js";
import type { AuthorizeAiInput, AuthorizeAiResult } from "../src/ai/controlPlane.js";
import type { SafeAiProposal } from "../src/contract.js";

const NOW = 1_000_000;

const AUTH_USER = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
} as const;

const VALID_DATA = {
  contractVersion: 2,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "interpret_text_proposal",
  text: "vendeme dos perfumes",
} as const;

const AUTHORIZED: AuthorizeAiResult = {
  decision: "AI_AUTHORIZED",
  provider: "GEMINI",
  fallbackUsed: false,
};

const PROPOSAL: SafeAiProposal = {
  kind: "record_payment",
  customerName: "María",
  declaredAmountCents: "20000",
};

const OTHER_REQUEST_ID = "223e4567-e89b-12d3-a456-426614174000";

function openGuard() {
  return createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1_000, maxTrackedUids: 1_000 });
}

function recordingDeps(overrides?: {
  authorizeResult?: AuthorizeAiResult;
  authorizeThrows?: boolean;
  claim?: "CLAIMED" | "ALREADY_CLAIMED";
  claimThrows?: boolean;
  provider?: TextInterpretationProvider | null;
  providerThrows?: boolean;
  providerResult?: SafeAiProposal | null;
  guard?: ReturnType<typeof createAiEdgeRequestGuard>;
  now?: () => number;
  onRejected?: (reason: "ANONYMOUS_SESSION") => void;
}) {
  const authorizeInputs: AuthorizeAiInput[] = [];
  const claimInputs: Array<{ uid: string; requestId: string; nowEpochMillis: number }> = [];
  const providerInputs: Array<{ text: string; referenceDate: ReferenceDate }> = [];
  const rejections: string[] = [];
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
            async interpret(input: { text: string; referenceDate: ReferenceDate }) {
              providerInputs.push(input);
              if (overrides?.providerThrows) {
                throw new Error("provider down");
              }
              const result =
                overrides && "providerResult" in overrides
                  ? overrides.providerResult
                  : PROPOSAL;
              return result;
            },
          }
        : overrides.provider,
    guard: overrides?.guard ?? openGuard(),
    now: overrides && "now" in overrides && typeof overrides.now === "function" ? overrides.now : () => NOW,
    onRejected: (reason: "ANONYMOUS_SESSION") => {
      rejections.push(reason);
      overrides?.onRejected?.(reason);
    },
    onProviderInvocation: () => {
      invocations.push(true);
      overrides?.onProviderInvocation?.();
    },
    onProviderLatency: (duration: number) => {
      latencies.push(duration);
      overrides?.onProviderLatency?.(duration);
    },
  };
  const invocations: boolean[] = [];
  const latencies: number[] = [];
  return { deps, authorizeInputs, claimInputs, providerInputs, rejections, invocations, latencies };
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
      auth: AUTH_USER,
      data: { ...VALID_DATA, extra: true },
    });

    expect(result).toEqual({ code: "invalid-argument" });
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test.each([
    ["AI_DISABLED", "AI_DISABLED"],
    ["AI_ENTITLEMENT_REQUIRED", "AI_ENTITLEMENT_REQUIRED"],
    ["AI_QUOTA_EXHAUSTED", "AI_QUOTA_EXHAUSTED"],
    ["AI_RATE_LIMITED", "AI_RATE_LIMITED"],
  ] as const)("control plane denial %s propagates specific rejection status %s without claim or provider", async (decision, expectedStatus) => {
    const { handler, claimInputs, providerInputs } = makeHandler({
      authorizeResult: { decision, provider: null, fallbackUsed: false },
    });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: expectedStatus,
      proposal: null,
    });
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("control plane denial AI_AUTHENTICATION_REQUIRED collapses to AI_UNAVAILABLE", async () => {
    const { handler, claimInputs, providerInputs } = makeHandler({
      authorizeResult: { decision: "AI_AUTHENTICATION_REQUIRED", provider: null, fallbackUsed: false },
    });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("edge guard rate limit returns AI_RATE_LIMITED without calling control plane, claim, or provider", async () => {
    const tightGuard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1, maxTrackedUids: 10 });
    // First request passes edge guard
    tightGuard.beforeRepository("test-user", VALID_DATA.requestId, NOW);

    const { handler, authorizeInputs, claimInputs, providerInputs } = makeHandler({
      guard: tightGuard,
    });

    // Second distinct request within same minute is rate-limited at edge
    const result = await handler({
      auth: AUTH_USER,
      data: { ...VALID_DATA, requestId: OTHER_REQUEST_ID },
    });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: OTHER_REQUEST_ID,
      status: "AI_RATE_LIMITED",
      proposal: null,
    });
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("anonymous auth token returns unavailable, triggers onRejected callback and touches nothing", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs, rejections } = makeHandler();

    const result = await handler({
      auth: {
        uid: "guest-user-123",
        token: { firebase: { sign_in_provider: "anonymous" } },
      },
      data: VALID_DATA,
    });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
    expect(rejections).toEqual(["ANONYMOUS_SESSION"]);
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("anonymous auth token via provider_id returns unavailable and triggers onRejected callback", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs, rejections } = makeHandler();

    const result = await handler({
      auth: {
        uid: "guest-user-123",
        token: { provider_id: "anonymous" },
      },
      data: VALID_DATA,
    });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
    expect(rejections).toEqual(["ANONYMOUS_SESSION"]);
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("missing token claims fails closed as anonymous, triggers onRejected, and touches nothing", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs, rejections } = makeHandler();

    const result = await handler({
      auth: { uid: "user-without-claims" },
      data: VALID_DATA,
    });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
    expect(rejections).toEqual(["ANONYMOUS_SESSION"]);
    expect(authorizeInputs).toHaveLength(0);
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("control plane failure returns unavailable without claim or provider", async () => {
    const { handler, claimInputs, providerInputs } = makeHandler({ authorizeThrows: true });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("null provider returns unavailable without claiming the invocation", async () => {
    const { handler, claimInputs, providerInputs } = makeHandler({ provider: null });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
    expect(claimInputs).toHaveLength(0);
    expect(providerInputs).toHaveLength(0);
  });

  test("authorized request claims durably then calls the provider exactly once with typed reference date", async () => {
    const { handler, authorizeInputs, claimInputs, providerInputs } = makeHandler();

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(authorizeInputs).toEqual([
      { uid: "test-user", requestId: VALID_DATA.requestId, capability: "TEXT", nowEpochMillis: NOW },
    ]);
    expect(claimInputs).toEqual([
      { uid: "test-user", requestId: VALID_DATA.requestId, nowEpochMillis: NOW },
    ]);
    expect(providerInputs).toHaveLength(1);
    expect(providerInputs[0].text).toBe(VALID_DATA.text);
    expect(providerInputs[0].referenceDate).toEqual({
      year: 1969,
      month: 12,
      day: 31,
      isoDate: "1969-12-31",
      weekday: "miércoles",
    });
    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_PROPOSAL",
      proposal: PROPOSAL,
    });
  });

  test("computes correct America/Mexico_City reference date when UTC date differs (edge case)", async () => {
    // 2026-08-25T02:00:00.000Z is 2026-08-24 20:00:00 (Monday) in America/Mexico_City (UTC-6)
    const edgeTimestamp = Date.parse("2026-08-25T02:00:00.000Z");
    const { handler, providerInputs } = makeHandler({
      now: () => edgeTimestamp,
    } as any);

    await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(providerInputs).toHaveLength(1);
    expect(providerInputs[0].referenceDate).toEqual({
      year: 2026,
      month: 8,
      day: 24,
      isoDate: "2026-08-24",
      weekday: "lunes",
    });
  });

  test("already claimed request id never invokes the provider", async () => {
    const { handler, providerInputs } = makeHandler({ claim: "ALREADY_CLAIMED" });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
    expect(providerInputs).toHaveLength(0);
  });

  test("claim failure fails closed without invoking the provider", async () => {
    const { handler, providerInputs } = makeHandler({ claimThrows: true });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(providerInputs).toHaveLength(0);
  });

  test("provider null result returns unavailable", async () => {
    const { handler } = makeHandler({ providerResult: null });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
  });

  test("provider failure returns unavailable", async () => {
    const { handler } = makeHandler({ providerThrows: true });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(result).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
  });

  test("edge rate denial touches no authorization, claim nor provider", async () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1, maxTrackedUids: 10 });
    const { handler, authorizeInputs, claimInputs, providerInputs } = makeHandler({ guard });

    const first = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(first.status).toBe("AI_PROPOSAL");

    const second = await handler({
      auth: AUTH_USER,
      data: { ...VALID_DATA, requestId: OTHER_REQUEST_ID },
    });
    expect(second).toEqual({
      contractVersion: 2,
      requestId: OTHER_REQUEST_ID,
      status: "AI_RATE_LIMITED",
      proposal: null,
    });

    // Only the first request reached the control plane, the claim and the provider.
    expect(authorizeInputs).toHaveLength(1);
    expect(claimInputs).toHaveLength(1);
    expect(providerInputs).toHaveLength(1);
  });

  test("claimed replay provider isolation after the durable gate closes", async () => {
    // Same uid + requestId, same shared guard, stateful durable claim: the first claim returns
    // CLAIMED, the second (from the guard replay) returns ALREADY_CLAIMED.
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 10, maxTrackedUids: 10 });
    const recorded = recordingDeps({});
    const claimCalls: Array<{ uid: string; requestId: string; nowEpochMillis: number }> = [];
    let claimCount = 0;
    const handler = createInterpretAiHandler({
      ...recorded.deps,
      guard,
      claimProviderInvocation: async (input) => {
        claimCalls.push(input);
        claimCount += 1;
        return claimCount === 1 ? "CLAIMED" : "ALREADY_CLAIMED";
      },
    });

    const first = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(first).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_PROPOSAL",
      proposal: PROPOSAL,
    });

    const second = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(second).toEqual({
      contractVersion: 2,
      requestId: VALID_DATA.requestId,
      status: "AI_UNAVAILABLE",
      proposal: null,
    });

    expect(recorded.authorizeInputs).toHaveLength(1); // guard replay, no second authorize
    expect(claimCalls).toHaveLength(2); // the replay did reach the durable gate
    expect(recorded.providerInputs).toHaveLength(1); // provider invoked exactly once
  });

  test("replay with already claimed durable state never invokes the provider", async () => {
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 10, maxTrackedUids: 10 });
    const { handler, providerInputs } = makeHandler({ guard, claim: "ALREADY_CLAIMED", authorizeResult: AUTHORIZED });

    // First call: authorize AI_AUTHORIZED (remembered), but the durable claim says it was
    // already claimed, so the provider is never invoked.
    const first = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(first.status).toBe("AI_UNAVAILABLE");

    // Second call: the guard replays the remembered authorization; ALREADY_CLAIMED still blocks
    // the provider.
    const replay = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(replay.status).toBe("AI_UNAVAILABLE");
    expect(providerInputs).toHaveLength(0);
  });

  test("invokes onProviderInvocation and onProviderLatency strictly upon claim and execution", async () => {
    let clock = 1000;
    const { handler, invocations, latencies } = makeHandler({
      now: () => {
        clock += 50;
        return clock;
      },
    });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(result.status).toBe("AI_PROPOSAL");
    expect(invocations).toHaveLength(1);
    expect(latencies).toHaveLength(1);
    expect(latencies[0]).toBeGreaterThan(0);
  });

  test("records provider telemetry even when provider fails", async () => {
    let clock = 1000;
    const { handler, invocations, latencies } = makeHandler({
      providerThrows: true,
      now: () => {
        clock += 30;
        return clock;
      },
    });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(result.status).toBe("AI_UNAVAILABLE");
    expect(invocations).toHaveLength(1);
    expect(latencies).toHaveLength(1);
    expect(latencies[0]).toBeGreaterThan(0);
  });

  test("never invokes provider telemetry when rejected or blocked before claim", async () => {
    const { handler, invocations, latencies } = makeHandler({
      authorizeResult: { decision: "AI_DISABLED", provider: "GEMINI", fallbackUsed: false },
    });

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(result.status).toBe("AI_DISABLED");
    expect(invocations).toHaveLength(0);
    expect(latencies).toHaveLength(0);
  });
});
