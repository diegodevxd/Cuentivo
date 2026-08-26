import { describe, expect, test } from "vitest";
import {
  authorizeInTransaction,
  claimProviderInvocationInTransaction,
  type AiTransaction,
} from "../src/ai/firestoreControlPlane.js";
import type { AuthorizeAiInput } from "../src/ai/controlPlane.js";

const NOW = Date.UTC(2026, 7, 16, 12, 0, 0); // 2026-08-16T12:00:00Z
const MONTH = "202608";
const MINUTE = "202608161200";

function config(monthlyActionLimit: number, perMinuteLimit: number) {
  return {
    enabled: true,
    geminiEnabled: true,
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

type RecordingTransaction = AiTransaction & {
  reads: string[];
  writes: string[];
};

function fakeTransaction(store: Store): RecordingTransaction {
  const reads: string[] = [];
  const writes: string[] = [];
  return {
    reads,
    writes,
    async get(path) {
      reads.push(path);
      return store.get(path) ?? null;
    },
    async set(path, data) {
      writes.push(path);
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

function authorizedStore(): Store {
  const store: Store = new Map();
  store.set("aiControl/global", config(2, 100));
  store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
  return store;
}

describe("authorizeInTransaction", () => {
  test("anonymous caller returns AI_AUTHENTICATION_REQUIRED with zero reads and zero writes", async () => {
    const store = authorizedStore();
    const tx = fakeTransaction(store);
    const anonymousInput: AuthorizeAiInput = { ...input("r1"), isAnonymous: true };

    const result = await authorizeInTransaction(tx, anonymousInput);

    expect(result).toEqual({
      decision: "AI_AUTHENTICATION_REQUIRED",
      provider: null,
      fallbackUsed: false,
    });
    expect(tx.reads).toHaveLength(0);
    expect(tx.writes).toHaveLength(0);
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
    expect(store.has(ledgerPath("user-1", "r1"))).toBe(false);
  });

  test("authorizes once and increments usage and rate by exactly one", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result).toEqual({ decision: "AI_AUTHORIZED", provider: "GEMINI", fallbackUsed: false });
    expect(store.get(usagePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ratePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual({
      uid: "user-1",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
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
    expect(store.has(ledgerPath("user-1", "r3"))).toBe(false);
  });

  test("rate boundary: distinct requestIds never exceed the per-minute limit", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(100, 1));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));

    await authorizeInTransaction(fakeTransaction(store), input("r1"));
    const second = await authorizeInTransaction(fakeTransaction(store), input("r2"));

    expect(second.decision).toBe("AI_RATE_LIMITED");
    expect(store.get(ratePath("user-1"))).toEqual({ usedActions: 1 });
    expect(store.has(ledgerPath("user-1", "r2"))).toBe(false);
  });

  test("missing configuration denies with zero writes of any kind", async () => {
    const store: Store = new Map();
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    const initialSize = store.size;

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.size).toBe(initialSize);
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
    expect(store.has(ledgerPath("user-1", "r1"))).toBe(false);
  });

  test("missing entitlement denies with zero writes of any kind", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    const initialSize = store.size;

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_ENTITLEMENT_REQUIRED");
    expect(store.size).toBe(initialSize);
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
    expect(store.has(ledgerPath("user-1", "r1"))).toBe(false);
  });

  test("expired entitlement denies with zero writes of any kind", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW - 1));
    const initialSize = store.size;

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_ENTITLEMENT_REQUIRED");
    expect(store.size).toBe(initialSize);
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
    expect(store.has(ledgerPath("user-1", "r1"))).toBe(false);
  });

  test("ledger owned by another uid denies and leaves it untouched", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    const foreign = {
      uid: "user-2",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    };
    store.set(ledgerPath("user-1", "r1"), foreign);

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual(foreign);
    expect(store.has(usagePath("user-1"))).toBe(false);
  });

  test("malformed stored ledger denies and leaves it untouched", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    const malformed = { unexpected: "shape" };
    store.set(ledgerPath("user-1", "r1"), malformed);

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual(malformed);
    expect(store.has(usagePath("user-1"))).toBe(false);
  });

  test("persisted ledger with a non-gemini provider fails closed without reinterpretation", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    const legacy = {
      uid: "user-1",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "DEEPSEEK",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    };
    store.set(ledgerPath("user-1", "r1"), legacy);

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(result.provider).toBeNull();
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual(legacy);
    expect(store.has(usagePath("user-1"))).toBe(false);
    expect(store.has(ratePath("user-1"))).toBe(false);
  });

  test("malformed usage document denies with zero ledger writes", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    store.set(usagePath("user-1"), { usedActions: "corrupt" });

    const result = await authorizeInTransaction(fakeTransaction(store), input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(store.has(ledgerPath("user-1", "r1"))).toBe(false);
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

  test("missing config short-circuits to a single read and zero writes", async () => {
    const store: Store = new Map();
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(tx.reads).toEqual(["aiControl/global"]);
    expect(tx.writes).toEqual([]);
  });

  test("disabled config short-circuits after the config read", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", { ...config(2, 100), enabled: false });
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(tx.reads).toEqual(["aiControl/global"]);
    expect(tx.writes).toEqual([]);
  });

  test("no provider short-circuits after the config read", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", {
      ...config(2, 100),
      geminiEnabled: false,
    });
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(tx.reads).toEqual(["aiControl/global"]);
    expect(tx.writes).toEqual([]);
  });

  test("missing entitlement reads only config and entitlement", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_ENTITLEMENT_REQUIRED");
    expect(tx.reads).toEqual(["aiControl/global", "aiEntitlements/user-1"]);
    expect(tx.writes).toEqual([]);
  });

  test("expired entitlement reads only config and entitlement", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW - 1));
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_ENTITLEMENT_REQUIRED");
    expect(tx.reads).toEqual(["aiControl/global", "aiEntitlements/user-1"]);
    expect(tx.writes).toEqual([]);
  });

  test("authorized ledger retry reads config, entitlement and ledger only", async () => {
    const store = authorizedStore();
    store.set(ledgerPath("user-1", "r1"), {
      uid: "user-1",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    });
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_AUTHORIZED");
    expect(tx.reads).toEqual([
      "aiControl/global",
      "aiEntitlements/user-1",
      "aiRequestLedger/user-1_r1",
    ]);
    expect(tx.writes).toEqual([]);
  });

  test("foreign ledger stops at the ledger read and writes nothing", async () => {
    const store = authorizedStore();
    store.set(ledgerPath("user-1", "r1"), {
      uid: "user-2",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    });
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_DISABLED");
    expect(tx.reads).toEqual([
      "aiControl/global",
      "aiEntitlements/user-1",
      "aiRequestLedger/user-1_r1",
    ]);
    expect(tx.writes).toEqual([]);
  });

  test("new authorization reads all five paths and writes the three authorized documents", async () => {
    const store = authorizedStore();
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_AUTHORIZED");
    expect(tx.reads).toEqual([
      "aiControl/global",
      "aiEntitlements/user-1",
      "aiRequestLedger/user-1_r1",
      "aiUsage/user-1_202608",
      "aiRateWindows/user-1_202608161200",
    ]);
    expect(tx.writes).toEqual([
      "aiRequestLedger/user-1_r1",
      "aiUsage/user-1_202608",
      "aiRateWindows/user-1_202608161200",
    ]);
  });

  test("exhausted monthly quota returns AI_QUOTA_EXHAUSTED and writes neither rate, usage nor ledger", async () => {
    const store: Store = new Map();
    store.set("aiControl/global", config(2, 100));
    store.set("aiEntitlements/user-1", entitlement(NOW + 3_600_000));
    store.set(usagePath("user-1"), { usedActions: 2 });
    const tx = fakeTransaction(store);

    const result = await authorizeInTransaction(tx, input("r1"));

    expect(result.decision).toBe("AI_QUOTA_EXHAUSTED");
    expect(result.provider).toBeNull();
    expect(tx.reads).toEqual([
      "aiControl/global",
      "aiEntitlements/user-1",
      "aiRequestLedger/user-1_r1",
      "aiUsage/user-1_202608",
      "aiRateWindows/user-1_202608161200",
    ]);
    expect(tx.writes).toEqual([]);
  });

  test("every denial leaves zero new documents", async () => {
    const scenarios: Array<{ name: string; store: Store }> = [
      { name: "missing config", store: new Map() },
      {
        name: "disabled config",
        store: new Map([
          ["aiControl/global", { ...config(2, 100), enabled: false }],
          ["aiEntitlements/user-1", entitlement(NOW + 3_600_000)],
        ]),
      },
      { name: "missing entitlement", store: new Map([["aiControl/global", config(2, 100)]]) },
      {
        name: "expired entitlement",
        store: new Map([
          ["aiControl/global", config(2, 100)],
          ["aiEntitlements/user-1", entitlement(NOW - 1)],
        ]),
      },
      {
        name: "quota exhausted",
        store: new Map([
          ["aiControl/global", config(2, 100)],
          ["aiEntitlements/user-1", entitlement(NOW + 3_600_000)],
          [usagePath("user-1"), { usedActions: 2 }],
        ]),
      },
      {
        name: "rate exhausted",
        store: new Map([
          ["aiControl/global", config(100, 1)],
          ["aiEntitlements/user-1", entitlement(NOW + 3_600_000)],
          [ratePath("user-1"), { usedActions: 1 }],
        ]),
      },
    ];

    for (const scenario of scenarios) {
      const initialSize = scenario.store.size;
      const result = await authorizeInTransaction(fakeTransaction(scenario.store), input("r1"));
      expect(result.fallbackUsed).toBe(false);
      expect(result.decision).not.toBe("AI_AUTHORIZED");
      expect(
        scenario.store.size,
        `scenario "${scenario.name}" must not create or modify any document`,
      ).toBe(initialSize);
      expect(scenario.store.has(ledgerPath("user-1", "r1"))).toBe(false);
    }
  });
});

describe("claimProviderInvocationInTransaction", () => {
  function authorizedLedger() {
    return {
      uid: "user-1",
      requestId: "r1",
      decision: "AI_AUTHORIZED",
      provider: "GEMINI",
      fallbackUsed: false,
      createdAtEpochMillis: NOW,
    };
  }

  test("first claim over an authorized ledger writes only invocation state and returns CLAIMED", async () => {
    const store: Store = new Map();
    store.set(ledgerPath("user-1", "r1"), authorizedLedger());

    const outcome = await claimProviderInvocationInTransaction(fakeTransaction(store), input("r1"));

    expect(outcome).toBe("CLAIMED");
    const ledger = store.get(ledgerPath("user-1", "r1"));
    expect(ledger?.["providerInvocation"]).toBe("CLAIMED");
    expect(ledger?.["providerClaimedAtEpochMillis"]).toBe(NOW);
    // Only invocation state + timestamp are added to the existing ledger row; never raw
    // text, provider output, tokens or keys.
    expect(Object.keys(ledger ?? {}).sort()).toEqual([
      "createdAtEpochMillis",
      "decision",
      "fallbackUsed",
      "provider",
      "providerClaimedAtEpochMillis",
      "providerInvocation",
      "requestId",
      "uid",
    ]);
  });

  test("second claim returns ALREADY_CLAIMED without modification", async () => {
    const store: Store = new Map();
    store.set(ledgerPath("user-1", "r1"), authorizedLedger());
    await claimProviderInvocationInTransaction(fakeTransaction(store), input("r1"));
    const before = store.get(ledgerPath("user-1", "r1"));

    const outcome = await claimProviderInvocationInTransaction(fakeTransaction(store), input("r1"));

    expect(outcome).toBe("ALREADY_CLAIMED");
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual(before);
  });

  test("missing ledger fails closed without writing", async () => {
    const store: Store = new Map();

    const outcome = await claimProviderInvocationInTransaction(fakeTransaction(store), input("r1"));

    expect(outcome).toBe("ALREADY_CLAIMED");
    expect(store.has(ledgerPath("user-1", "r1"))).toBe(false);
  });

  test.each([
    ["foreign uid", { ...authorizedLedger(), uid: "user-2" }],
    ["malformed ledger", { unexpected: "shape" }],
    ["denied decision", { ...authorizedLedger(), decision: "AI_DISABLED" }],
    ["non-gemini provider", { ...authorizedLedger(), provider: "DEEPSEEK" }],
  ] as const)("invalid stored ledger (%s) fails closed without writes", async (_name, ledger) => {
    const store: Store = new Map();
    store.set(ledgerPath("user-1", "r1"), { ...ledger });

    const outcome = await claimProviderInvocationInTransaction(fakeTransaction(store), input("r1"));

    expect(outcome).toBe("ALREADY_CLAIMED");
    expect(store.get(ledgerPath("user-1", "r1"))).toEqual({ ...ledger });
  });
});
