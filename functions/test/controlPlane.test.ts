import { describe, expect, test } from "vitest";
import {
  decideAuthorization,
  type AiControlConfig,
  type AiEntitlement,
} from "../src/ai/controlPlane.js";

const input = {
  uid: "user-1",
  requestId: "req-1",
  capability: "TEXT",
  nowEpochMillis: 1_000_000,
} as const;

const geminiConfig: AiControlConfig = {
  enabled: true,
  geminiEnabled: true,
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const disabledConfig: AiControlConfig = {
  enabled: false,
  geminiEnabled: true,
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const geminiOffConfig: AiControlConfig = {
  enabled: true,
  geminiEnabled: false,
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const validEntitlement: AiEntitlement = {
  enabled: true,
  expiresAtEpochMillis: 2_000_000,
};

const noUsage = { usedActions: 0 };

describe("decideAuthorization", () => {
  test("anonymous caller is denied with AI_AUTHENTICATION_REQUIRED even with valid config and entitlement", () => {
    const anonymousInput = { ...input, isAnonymous: true };
    expect(
      decideAuthorization(anonymousInput, geminiConfig, validEntitlement, noUsage, noUsage),
    ).toEqual({
      decision: "AI_AUTHENTICATION_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("absent configuration is disabled", () => {
    expect(decideAuthorization(input, null, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_DISABLED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("disabled configuration denies", () => {
    expect(decideAuthorization(input, disabledConfig, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_DISABLED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("gemini disabled denies as AI_DISABLED", () => {
    expect(decideAuthorization(input, geminiOffConfig, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_DISABLED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("malformed geminiEnabled value denies as AI_DISABLED", () => {
    const malformed = {
      enabled: true,
      geminiEnabled: "yes",
      monthlyActionLimit: 100,
      perMinuteLimit: 10,
    } as unknown as AiControlConfig;
    expect(decideAuthorization(input, malformed, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_DISABLED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("missing entitlement requires entitlement", () => {
    expect(decideAuthorization(input, geminiConfig, null, noUsage, noUsage)).toEqual({
      decision: "AI_ENTITLEMENT_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("expired entitlement requires entitlement", () => {
    const expired: AiEntitlement = { enabled: true, expiresAtEpochMillis: 999_999 };
    expect(decideAuthorization(input, geminiConfig, expired, noUsage, noUsage)).toEqual({
      decision: "AI_ENTITLEMENT_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("entitlement disabled requires entitlement", () => {
    const off: AiEntitlement = { enabled: false, expiresAtEpochMillis: 2_000_000 };
    expect(decideAuthorization(input, geminiConfig, off, noUsage, noUsage)).toEqual({
      decision: "AI_ENTITLEMENT_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("quota exactly at limit denies", () => {
    expect(
      decideAuthorization(input, geminiConfig, validEntitlement, { usedActions: 100 }, noUsage),
    ).toEqual({ decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false });
  });

  test("rate exactly at limit denies", () => {
    expect(
      decideAuthorization(input, geminiConfig, validEntitlement, noUsage, { usedActions: 10 }),
    ).toEqual({ decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false });
  });

  test("well-formed gemini config authorizes GEMINI", () => {
    expect(decideAuthorization(input, geminiConfig, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
    });
  });

  test("invalid monthly limits deny as disabled", () => {
    for (const monthlyActionLimit of [0, -1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1]) {
      const config: AiControlConfig = {
        enabled: true,
        geminiEnabled: true,
        monthlyActionLimit,
        perMinuteLimit: 10,
      };
      expect(decideAuthorization(input, config, validEntitlement, noUsage, noUsage)).toEqual({
        decision: "AI_DISABLED",
        provider: null,
        fallbackUsed: false,
      });
    }
  });

  test("invalid per-minute limits deny as disabled", () => {
    for (const perMinuteLimit of [0, -3, 2.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1]) {
      const config: AiControlConfig = {
        enabled: true,
        geminiEnabled: true,
        monthlyActionLimit: 100,
        perMinuteLimit,
      };
      expect(decideAuthorization(input, config, validEntitlement, noUsage, noUsage)).toEqual({
        decision: "AI_DISABLED",
        provider: null,
        fallbackUsed: false,
      });
    }
  });

  test("never throws on malformed inputs", () => {
    const shapes: unknown[] = [
      {},
      { enabled: true },
      { enabled: "yes", geminiEnabled: null, monthlyActionLimit: "x", perMinuteLimit: null },
      null,
      undefined,
    ];
    for (const config of shapes) {
      expect(() =>
        decideAuthorization(
          input,
          config as AiControlConfig,
          null,
          { usedActions: -5 },
          { usedActions: Number.POSITIVE_INFINITY },
        ),
      ).not.toThrow();
    }
  });
});
