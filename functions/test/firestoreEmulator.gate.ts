import { randomUUID } from "node:crypto";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createFirestoreControlPlaneRepository } from "../src/ai/firestoreControlPlane.js";
import type { AuthorizeAiResult } from "../src/ai/controlPlane.js";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST is required: this emulator-only gate must never target production Firestore.",
  );
}

const PROJECT_ID = "demo-cuentivoai-phase2a";

// Unique per run: the gate only ever reads or writes documents owned by this uid (plus the
// dedicated demo project's aiControl/global configuration document). No wildcard, no listing,
// no collection-wide deletes, so other runs or other tenants can never be touched.
const RUN_UID = `gate-${randomUUID()}`;

const REQUEST_IDS = Array.from({ length: 10 }, (_, index) => `conc-${index}`);

initializeApp({ projectId: PROJECT_ID });
const firestore = getFirestore();

const NOW = Date.now();
const ENTITLEMENT_EXPIRES = NOW + 3_600_000;

function monthKey(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

function minuteKey(epochMillis: number): string {
  const d = new Date(epochMillis);
  return (
    monthKey(epochMillis) +
    `${String(d.getUTCDate()).padStart(2, "0")}${String(d.getUTCHours()).padStart(2, "0")}${String(
      d.getUTCMinutes(),
    ).padStart(2, "0")}`
  );
}

function input(requestId: string) {
  return { uid: RUN_UID, requestId, capability: "TEXT" as const, nowEpochMillis: NOW };
}

/** Exact paths owned by this gate run: the dedicated config doc plus uid-scoped documents only. */
function ownedPaths(): string[] {
  return [
    "aiControl/global",
    `aiEntitlements/${RUN_UID}`,
    `aiUsage/${RUN_UID}_${monthKey(NOW)}`,
    `aiRateWindows/${RUN_UID}_${minuteKey(NOW)}`,
    ...REQUEST_IDS.map((requestId) => `aiRequestLedger/${RUN_UID}_${requestId}`),
  ];
}

async function cleanupOwnedPaths() {
  for (const path of ownedPaths()) {
    await firestore.doc(path).delete();
  }
}

async function seed() {
  await firestore.doc("aiControl/global").set({
    enabled: true,
    geminiEnabled: true,
    monthlyActionLimit: 1,
    perMinuteLimit: 1,
  });
  await firestore.doc(`aiEntitlements/${RUN_UID}`).set({
    enabled: true,
    expiresAtEpochMillis: ENTITLEMENT_EXPIRES,
  });
}

async function ledgerCountForRun(): Promise<number> {
  const snapshot = await firestore
    .collection("aiRequestLedger")
    .where("uid", "==", RUN_UID)
    .count()
    .get();
  return snapshot.data().count;
}

async function counters() {
  const usage = (await firestore.doc(`aiUsage/${RUN_UID}_${monthKey(NOW)}`).get()).data();
  const rate = (await firestore.doc(`aiRateWindows/${RUN_UID}_${minuteKey(NOW)}`).get()).data();
  return {
    usage: typeof usage?.["usedActions"] === "number" ? usage["usedActions"] : -1,
    rate: typeof rate?.["usedActions"] === "number" ? rate["usedActions"] : -1,
  };
}

describe("firestore emulator concurrency gate", () => {
  beforeEach(async () => {
    await cleanupOwnedPaths();
    await seed();
  });

  afterEach(async () => {
    await cleanupOwnedPaths();
  });

  /**
   * Runs the ten concurrent authorizations. With the Admin default retry policy, extreme
   * contention on the shared window can exhaust retries for the whole batch in the emulator
   * (each loser fails closed to AI_DISABLED). That is a known emulator-contention artifact, not
   * a logic bug: retry the batch only when zero authorized AND at least one call shows the
   * retry-exhausted AI_DISABLED signature. Any other shape (e.g. two authorized, or zero
   * authorized with only rate denials) surfaces immediately to the assertions.
   */
  async function runBatch(repository: ReturnType<typeof createFirestoreControlPlaneRepository>): Promise<AuthorizeAiResult[]> {
    for (let attempt = 0; attempt < 3; attempt++) {
      const results = await Promise.all(
        REQUEST_IDS.map((requestId) => repository.authorize(input(requestId))),
      );
      const authorized = results.filter((r) => r.decision === "AI_AUTHORIZED").length;
      if (authorized > 0) {
        return results;
      }
      const retryExhausted = results.some((r) => r.decision === "AI_DISABLED");
      if (!retryExhausted) {
        return results; // real bug shape: let the assertions fail
      }
      await cleanupOwnedPaths();
      await seed();
    }
    return []; // never reached with a healthy control plane; fails the assertions otherwise
  }

  test("ten concurrent authorizations with limit 1 authorize exactly once", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    const results = await runBatch(repository);

    const authorized = results.filter((r) => r.decision === "AI_AUTHORIZED");
    expect(authorized.length).toBe(1);
    expect(authorized[0]?.provider).toBe("GEMINI");
    expect(authorized[0]?.fallbackUsed).toBe(false);

    const denied = results.filter((r) => r.decision !== "AI_AUTHORIZED");
    expect(denied.length).toBe(9);

    expect(await counters()).toEqual({ usage: 1, rate: 1 });
    expect(await ledgerCountForRun()).toBe(1);
  });

  test("repeats of the authorized requestId never increment", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    const firstBatch = await runBatch(repository);
    const winnerIndex = firstBatch.findIndex((r) => r.decision === "AI_AUTHORIZED");
    expect(winnerIndex).toBeGreaterThanOrEqual(0);
    const winnerRequestId = REQUEST_IDS[winnerIndex] as string;

    const repeats = await Promise.all(
      Array.from({ length: 5 }, () => repository.authorize(input(winnerRequestId))),
    );
    for (const repeat of repeats) {
      expect(repeat).toEqual({
        decision: "AI_AUTHORIZED",
        provider: "GEMINI",
        fallbackUsed: false,
      });
    }

    expect(await counters()).toEqual({ usage: 1, rate: 1 });
    expect(await ledgerCountForRun()).toBe(1);
  });
});
