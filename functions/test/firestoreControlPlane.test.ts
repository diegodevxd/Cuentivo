import { describe, expect, test } from "vitest";
import {
  authorizeInTransaction,
  type AiTransaction,
} from "../src/ai/firestoreControlPlane.js";
import type { AuthorizeAiInput } from "../src/ai/controlPlane.js";

const NOW = Date.UTC(2026, 7, 16, 12, 0, 0); // 2026-08-16T12:00:00Z
const MONTH = "202608";
const MINUTE = "202608161200";

function config(monthlyActionLimit: number, perMinuteLimit: number) {
  return {
    enabled: true,
    providers: { DEEPSEEK: true, GEMINI: false },
    monthlyActionLimit,
    perMinuteLimit,
  };
}

function entitlement(expiresAtEpochMillis: number) {
  return { enabled: true, expiresAtEpochMillis };
}

function input(requestId: string, nowEpochMillis: number = NOW): AuthorizeAiInput {
  return { uid: "user-1", requestId, capability: "TEXT", nowEpochMillis };
}

type Store = Map<string, Record<string, unknown>>;

function fakeTransaction(store: Store): AiTransaction {
  return {
    async get(path) {
      return store.get(path) ?? null;
    },
    async set(path, data) {
      store.set(path, { ...data });
    },
  };
}

function usagePath(uid: string, month: string = MONTH) {
  return `aiUsage/${uid}_${month}`;
}

function ratePath(uid: string, minute: string = MINUTE) {
  return `aiRateWindows/${uid}_${minute}`;
}

function ledgerPath(uid: string, requestId: string) {
  return `aiRequestLedger/${uid}_${requestId}`;
}

describe("authorizeInTransaction", () => {
  test("authorizes once and increments usage and rate by exactly one", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result).toEqual({ decision: "AI_AUTHORIZED", provider: "DEEPSEEK", fallbackUsed: false });
    expect(store.get(usagePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ratePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual({
      uid: "user-1",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "DEEPSEEK",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    });
  });

  test("repeat with the same requestId returns the original result without increments", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    const first = await authorizeInTransaction(fakeTransaction(store), input("r1"));
    const second = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(first).toEqual(second);
    expect(store.get(usagePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ratePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ledgerPath("user-1", "r1"))?.decision).toBe("AI_AUTHORIZED");
  });

  test("quota boundary: limit is never exceeded", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    await authorizeInTransaction(fakeTransaction(store), input("r1"));
    await authorizeInTransaction(fakeTransaction(store), input("r2"));
    const third = await authorizeInTransaction(fakeTransaction(store), input("r3"));

    expect(third.decision).toBe("AI_QUOTA_EXHAUSTED");
    expect(third.provider).toBeNull();
    expect(store.get(usagePath("user-1"))).toEqual({ usedActions: 2 });
    expect(store.get(ledgerPath("user-1", "r3"))?.decision).toBe("AI_QUOTA_EXHAUSTED");
  });

  test("rate boundary: distinct requestIds never exceed the per-minute limit", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(100, 1));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    await authorizeInTransaction(fakeTransaction(store), input("r1"));
    const second = await authorizeInTransaction(fakeTransaction(store), input("r2"));

    expect(second.decision).toBe("AI_RATE_LIMITED");
    expect(store.get(ratePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ledgerPath("user-1", "r2"))?.decision).toBe("AI_RATE_LIMITED");
  });

  test("missing configuration denies with zero usage and rate writes", async () => {
    const store: Store = new Map();
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
    expect(store.get(ledgerPath("user-1", "r1"))?.decision).toBe("AI_DISABLED");
  });

  test("missing entitlement denies with zero usage and rate writes", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_ENTITLEMENT_REQUIRED");
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
    expect(store.get(ledgerPath("user-1", "r1"))?.decision).toBe("AI_ENTITLEMENT_REQUIRED");
  });

  test("expired entitlement denies with zero usage and rate writes", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW - 1));

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_ENTITLEMENT_REQUIRED");
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
  });

  test("ledger owned by another uid denies", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    store.set(ledgerPath("user-1", "r1"), {
      uid: "user-2",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "DEEPSEEK",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    });

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.has(usagePath("user-1"))).toBe(false);
  });

  test("malformed stored ledger denies without writes", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    store.set(ledgerPath("user-1", "r1"), { unexpected: "shape" });

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.has(usagePath("user-1"))).toBe(false);
  });

  test("malformed usage document denies", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    store.set(usagePath("user-1"), { usedActions: "corrupt" });

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
  });

  test("transaction error fails closed to AI_DISABLED", async () => {
    const failing: AiTransaction = {
      async get() {
        throw new Error("storage unavailable");
      },
      async set() {
        throw new Error("storage unavailable");
      },
    };

    const result = await authorizeInTransaction(failing, input("r1"));

    expect(result).toEqual({ decision: "AI_DISABLED", provider: null, fallbackUsed: false });
  });
});
