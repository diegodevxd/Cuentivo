import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createFirestoreControlPlaneRepository } from "../src/ai/firestoreControlPlane.js";

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST is required: this emulator-only gate must never target production Firestore.",
  );
}

const PROJECT_ID = "demo-cuentivoai-phase2a";
const UID = "concurrency-user";

initializeApp({ projectId: PROJECT_ID });
const firestore = getFirestore();

const NOW = Date.now();
const ENTITLEMENT_EXPIRES = NOW + 3_600_000;

const COLLECTIONS = ["aiControl", "aiEntitlements", "aiUsage", "aiRateWindows", "aiRequestLedger"];

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
  return { uid: UID, requestId, capability: "TEXT" as const, nowEpochMillis: NOW };
}

async function wipe() {
  for (const name of COLLECTIONS) {
    const docs = await firestore.collection(name).listDocuments();
    await Promise.all(docs.map((doc) => doc.delete()));
  }
}

async function seed() {
  await firestore.doc("aiControl/global").set({
    enabled: true,
    providers: { DEEPSEEK: true, GEMINI: false },
    monthlyActionLimit: 1,
    perMinuteLimit: 1,
  });
  await firestore.doc(`aiEntitlements/${UID}`).set({
    enabled: true,
    expiresAtEpochMillis: ENTITLEMENT_EXPIRES,
  });
}

async function ledgerCount(): Promise<number> {
  const snapshot = await firestore.collection("aiRequestLedger").count().get();
  return snapshot.data().count;
}

async function counters() {
  const usage = (await firestore.doc(`aiUsage/${UID}_${monthKey(NOW)}`).get()).data();
  const rate = (await firestore.doc(`aiRateWindows/${UID}_${minuteKey(NOW)}`).get()).data();
  return {
    usage: typeof usage?.["usedActions"] === "number" ? usage["usedActions"] : -1,
    rate: typeof rate?.["usedActions"] === "number" ? rate["usedActions"] : -1,
  };
}

describe("firestore emulator concurrency gate", () => {
  beforeEach(async () => {
    await wipe();
    await seed();
  });

  afterEach(async () => {
    await wipe();
  });

  test("ten concurrent authorizations with limit 1 authorize exactly once", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, index) => repository.authorize(input(`conc-${index}`))),
    );

    const authorized = results.filter((r) => r.decision === "AI_AUTHORIZED");
    expect(authorized.length).toBe(1);
    expect(authorized[0]?.provider).toBe("DEEPSEEK");
    expect(authorized[0]?.fallbackUsed).toBe(false);

    const denied = results.filter((r) => r.decision !== "AI_AUTHORIZED");
    expect(denied.length).toBe(9);

    expect(await counters()).toEqual({ usage: 1, rate: 1 });
    expect(await ledgerCount()).toBe(1);
  });

  test("repeats of the authorized requestId never increment", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    const firstBatch = await Promise.all(
      Array.from({ length: 10 }, (_, index) => repository.authorize(input(`rep-${index}`))),
    );
    const winnerIndex = firstBatch.findIndex((r) => r.decision === "AI_AUTHORIZED");
    expect(winnerIndex).toBeGreaterThanOrEqual(0);
    const winnerRequestId = `rep-${winnerIndex}`;

    const repeats = await Promise.all(
      Array.from({ length: 5 }, () => repository.authorize(input(winnerRequestId))),
    );
    for (const repeat of repeats) {
      expect(repeat).toEqual({
        decision: "AI_AUTHORIZED",
        provider: "DEEPSEEK",
        fallbackUsed: false,
      });
    }

    expect(await counters()).toEqual({ usage: 1, rate: 1 });
    expect(await ledgerCount()).toBe(1);
  });
});
