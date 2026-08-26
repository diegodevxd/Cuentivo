import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";
import { createAuthorizeAiHandler } from "../src/authorizeAiAction.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import type { AiControlPlaneRepository } from "../src/ai/firestoreControlPlane.js";
import type { AuthorizeAiInput, AuthorizeAiResult } from "../src/ai/controlPlane.js";

const FAKE_NOW = 1_000_000;

const AUTH_USER = {
  uid: "test-user",
  token: { firebase: { sign_in_provider: "password" } },
} as const;

const VALID_DATA = {
  contractVersion: 1,
  requestId: "123e4567-e89b-12d3-a456-426614174000",
  action: "authorize_text",
} as const;

/** Guard wide open for tests that do not exercise the edge semantics. */
function openGuard() {
  return createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1_000, maxTrackedUids: 1_000 });
}

function repositoryWith(
  onAuthorize: (input: AuthorizeAiInput) => Promise<AuthorizeAiResult> | AuthorizeAiResult,
): AiControlPlaneRepository {
  return {
    authorize(input) {
      return Promise.resolve(onAuthorize(input));
    },
  };
}

describe("createAuthorizeAiHandler", () => {
  test("without auth returns unauthenticated", async () => {
    const handler = createAuthorizeAiHandler(repositoryWith(async () => {
      throw new Error("must not be called");
    }), () => FAKE_NOW, openGuard());

    expect(await handler({ data: VALID_DATA })).toEqual({ code: "unauthenticated" });
  });

  test("extra contract field returns invalid-argument", async () => {
    const handler = createAuthorizeAiHandler(repositoryWith(async () => {
      throw new Error("must not be called");
    }), () => FAKE_NOW, openGuard());

    expect(
      await handler({ auth: AUTH_USER, data: { ...VALID_DATA, text: "hola" } }),
    ).toEqual({ code: "invalid-argument" });
  });

  test("wrong action returns invalid-argument", async () => {
    const handler = createAuthorizeAiHandler(repositoryWith(async () => {
      throw new Error("must not be called");
    }), () => FAKE_NOW, openGuard());

    expect(
      await handler({
        auth: AUTH_USER,
        data: { contractVersion: 1, requestId: VALID_DATA.requestId, action: "interpret_text" },
      }),
    ).toEqual({ code: "invalid-argument" });
  });

  test("missing requestId returns invalid-argument", async () => {
    const handler = createAuthorizeAiHandler(repositoryWith(async () => {
      throw new Error("must not be called");
    }), () => FAKE_NOW, openGuard());

    expect(
      await handler({
        auth: AUTH_USER,
        data: { contractVersion: 1, action: "authorize_text" },
      }),
    ).toEqual({ code: "invalid-argument" });
  });

  test("valid request delegates with authenticated uid and server clock", async () => {
    let received: AuthorizeAiInput | null = null;
    const handler = createAuthorizeAiHandler(repositoryWith(async (input) => {
      received = input;
      return { decision: "AI_AUTHORIZED", provider: "GEMINI", fallbackUsed: false };
    }), () => FAKE_NOW, openGuard());

    const result = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(received).toEqual({
      uid: "test-user",
      requestId: VALID_DATA.requestId,
      capability: "TEXT",
      nowEpochMillis: FAKE_NOW,
    });
    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
    });
  });

  test("anonymous auth token returns AI_AUTHENTICATION_REQUIRED without calling repository", async () => {
    let called = false;
    const handler = createAuthorizeAiHandler(
      repositoryWith(async () => {
        called = true;
        throw new Error("must not be called");
      }),
      () => FAKE_NOW,
      openGuard(),
    );

    const result = await handler({
      auth: {
        uid: "guest-user-123",
        token: { firebase: { sign_in_provider: "anonymous" } },
      },
      data: VALID_DATA,
    });

    expect(called).toBe(false);
    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_AUTHENTICATION_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("anonymous auth token via provider_id returns AI_AUTHENTICATION_REQUIRED", async () => {
    let called = false;
    const handler = createAuthorizeAiHandler(
      repositoryWith(async () => {
        called = true;
        throw new Error("must not be called");
      }),
      () => FAKE_NOW,
      openGuard(),
    );

    const result = await handler({
      auth: {
        uid: "guest-user-123",
        token: { provider_id: "anonymous" },
      },
      data: VALID_DATA,
    });

    expect(called).toBe(false);
    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_AUTHENTICATION_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("missing token claims fails closed as anonymous and triggers onRejected callback", async () => {
    let called = false;
    let rejectedReason: string | null = null;
    const handler = createAuthorizeAiHandler(
      repositoryWith(async () => {
        called = true;
        throw new Error("must not be called");
      }),
      () => FAKE_NOW,
      openGuard(),
      (reason) => {
        rejectedReason = reason;
      },
    );

    const result = await handler({
      auth: { uid: "test-user-without-token" },
      data: VALID_DATA,
    });

    expect(called).toBe(false);
    expect(rejectedReason).toBe("ANONYMOUS_SESSION");
    expect(result).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_AUTHENTICATION_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("denied repository result mirrors exactly", async () => {
    const handler = createAuthorizeAiHandler(repositoryWith(async () => {
      return { decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false };
    }), () => FAKE_NOW, openGuard());

    expect(await handler({ auth: AUTH_USER, data: VALID_DATA })).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_RATE_LIMITED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("repository failure fails closed to AI_DISABLED", async () => {
    const handler = createAuthorizeAiHandler(repositoryWith(async () => {
      throw new Error("storage down");
    }), () => FAKE_NOW, openGuard());

    expect(await handler({ auth: AUTH_USER, data: VALID_DATA })).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_DISABLED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("exhausted warm guard never calls the repository", async () => {
    let calls = 0;
    const repository = repositoryWith(async () => {
      calls += 1;
      return { decision: "AI_AUTHORIZED", provider: "GEMINI", fallbackUsed: false };
    });
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1, maxTrackedUids: 10 });
    const handler = createAuthorizeAiHandler(repository, () => FAKE_NOW, guard);

    const first = await handler({ auth: AUTH_USER, data: VALID_DATA });
    expect(first.decision).toBe("AI_AUTHORIZED");
    expect(calls).toBe(1);

    const secondData = {
      contractVersion: 1,
      requestId: "223e4567-e89b-12d3-a456-426614174000",
      action: "authorize_text",
    } as const;
    const second = await handler({ auth: AUTH_USER, data: secondData });
    expect(second).toEqual({
      contractVersion: 1,
      requestId: secondData.requestId,
      decision: "AI_RATE_LIMITED",
      provider: null,
      fallbackUsed: false,
    });
    expect(calls).toBe(1);
  });

  test("remembered authorized result replays without repository access", async () => {
    let calls = 0;
    const repository = repositoryWith(async () => {
      calls += 1;
      return { decision: "AI_AUTHORIZED", provider: "GEMINI", fallbackUsed: false };
    });
    const guard = createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 10, maxTrackedUids: 10 });
    const handler = createAuthorizeAiHandler(repository, () => FAKE_NOW, guard);

    const first = await handler({ auth: AUTH_USER, data: VALID_DATA });
    const replay = await handler({ auth: AUTH_USER, data: VALID_DATA });

    expect(replay).toEqual(first);
    expect(replay).toEqual({
      contractVersion: 1,
      requestId: VALID_DATA.requestId,
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
    });
    expect(calls).toBe(1);
  });

  test("boundary source has no logger and no provider SDK dependency", () => {
    const source = readFileSync(resolve(process.cwd(), "src/authorizeAiAction.ts"), "utf8");
    for (const forbidden of ["logger", "openai", "deepseek", "gemini", "axios", "https://"]) {
      expect(source.toLowerCase()).not.toContain(forbidden);
    }
  });
});
