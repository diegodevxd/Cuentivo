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

const disabledConfig: AiControlConfig = {
  enabled: false,
  providers: { DEEPSEEK: false, GEMINI: false },
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const deepSeekOnlyConfig: AiControlConfig = {
  enabled: true,
  providers: { DEEPSEEK: true, GEMINI: false },
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const geminiOnlyConfig: AiControlConfig = {
  enabled: true,
  providers: { DEEPSEEK: false, GEMINI: true },
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const bothEnabledConfig: AiControlConfig = {
  enabled: true,
  providers: { DEEPSEEK: true, GEMINI: true },
  monthlyActionLimit: 100,
  perMinuteLimit: 10,
};

const validEntitlement: AiEntitlement = {
  enabled: true,
  expiresAtEpochMillis: 2_000_000,
};

const noUsage = { usedActions: 0 };

describe("decideAuthorization", () => {
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

  test("both providers disabled denies", () => {
    const noProviders: AiControlConfig = {
      enabled: true,
      providers: { DEEPSEEK: false, GEMINI: false },
      monthlyActionLimit: 100,
      perMinuteLimit: 10,
    };
    expect(decideAuthorization(input, noProviders, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_DISABLED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("missing entitlement requires entitlement", () => {
    expect(decideAuthorization(input, deepSeekOnlyConfig, null, noUsage, noUsage)).toEqual({
      decision: "AI_ENTITLEMENT_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("expired entitlement requires entitlement", () => {
    const expired: AiEntitlement = { enabled: true, expiresAtEpochMillis: 999_999 };
    expect(decideAuthorization(input, deepSeekOnlyConfig, expired, noUsage, noUsage)).toEqual({
      decision: "AI_ENTITLEMENT_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("entitlement disabled requires entitlement", () => {
    const off: AiEntitlement = { enabled: false, expiresAtEpochMillis: 2_000_000 };
    expect(decideAuthorization(input, deepSeekOnlyConfig, off, noUsage, noUsage)).toEqual({
      decision: "AI_ENTITLEMENT_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
  });

  test("quota exactly at limit denies", () => {
    expect(
      decideAuthorization(input, deepSeekOnlyConfig, validEntitlement, { usedActions: 100 }, noUsage),
    ).toEqual({ decision: "AI_QUOTA_EXHAUSTED", provider: null, fallbackUsed: false });
  });

  test("rate exactly at limit denies", () => {
    expect(
      decideAuthorization(input, deepSeekOnlyConfig, validEntitlement, noUsage, { usedActions: 10 }),
    ).toEqual({ decision: "AI_RATE_LIMITED", provider: null, fallbackUsed: false });
  });

  test("deepseek only authorizes deepseek", () => {
    expect(decideAuthorization(input, deepSeekOnlyConfig, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_AUTHORIZED",
      provider: "DEEPSEEK",
      fallbackUsed: false,
    });
  });

  test("gemini only authorizes gemini", () => {
    expect(decideAuthorization(input, geminiOnlyConfig, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
    });
  });

  test("both providers enabled chooses deepseek first", () => {
    expect(decideAuthorization(input, bothEnabledConfig, validEntitlement, noUsage, noUsage)).toEqual({
      decision: "AI_AUTHORIZED",
      provider: "DEEPSEEK",
      fallbackUsed: false,
    });
  });

  test("invalid monthly limits deny as disabled", () => {
    for (const monthlyActionLimit of [0, -1, 1.5, Number.NaN, Number.MAX_SAFE_INTEGER + 1]) {
      const config: AiControlConfig = {
        enabled: true,
        providers: { DEEPSEEK: true, GEMINI: false },
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
        providers: { DEEPSEEK: true, GEMINI: false },
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
      { enabled: "yes", providers: null, monthlyActionLimit: "x", perMinuteLimit: null },
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
