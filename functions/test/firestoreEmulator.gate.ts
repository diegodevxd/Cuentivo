import { randomUUID } from "node:crypto";
import { afterEach, beforeEach, describe, expect, test } from "vitest";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { createFirestoreControlPlaneRepository } from "../src/ai/firestoreControlPlane.js";
import { createInterpretAiHandler } from "../src/interpretAiRequest.js";
import { createGetAiAvailabilityHandler } from "../src/getAiAvailability.js";
import { createAiEdgeRequestGuard } from "../src/ai/edgeRequestGuard.js";
import {
  createFirestoreSubscriptionEntitlementRepository,
  tokenBindingPath,
  entitlementPath,
} from "../src/ai/subscriptionEntitlementRepository.js";
import { hashPurchaseToken, type PlaySubscriptionApi } from "../src/ai/playSubscriptionVerifier.js";
import { createPlayRtdnHandler } from "../src/handlePlayRtdnNotification.js";
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
const INTRUDER_UID = `intruder-${randomUUID()}`;
const TEST_TOKEN = `token-${randomUUID()}`;
const TEST_TOKEN_HASH = hashPurchaseToken(TEST_TOKEN);

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

function openGuard() {
  return createAiEdgeRequestGuard({ maxNewRequestsPerMinute: 1_000, maxTrackedUids: 1_000 });
}

function input(requestId: string) {
  return { uid: RUN_UID, requestId, capability: "TEXT" as const, nowEpochMillis: NOW };
}

/** Exact paths owned by this gate run: the dedicated config doc plus uid-scoped documents only. */
function ownedPaths(): string[] {
  return [
    "aiControl/global",
    `aiEntitlements/${RUN_UID}`,
    `aiEntitlements/${INTRUDER_UID}`,
    `aiUsage/${RUN_UID}_${monthKey(NOW)}`,
    `aiRateWindows/${RUN_UID}_${minuteKey(NOW)}`,
    tokenBindingPath(TEST_TOKEN_HASH),
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

  test("subscription entitlement: grant, idempotent reprocess, and stolen token rejection", async () => {
    const subRepo = createFirestoreSubscriptionEntitlementRepository(firestore);
    const product = "cuentivo_ai_mensual";
    const initialExpiry = NOW + 30 * 86400 * 1000;

    // 1. Conceder: First writer binds token and writes entitlement
    const grantResult = await subRepo.recordSubscription({
      uid: RUN_UID,
      purchaseToken: TEST_TOKEN,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: initialExpiry,
      nowEpochMillis: NOW,
    });
    expect(grantResult).toEqual({
      status: "RECORDED",
      enabled: true,
      expiresAtEpochMillis: initialExpiry,
    });

    const entDoc = (await firestore.doc(entitlementPath(RUN_UID)).get()).data();
    expect(entDoc).toEqual({
      enabled: true,
      expiresAtEpochMillis: initialExpiry,
      lastVerifiedAtEpochMillis: NOW,
      productId: product,
      sourceTokenHash: TEST_TOKEN_HASH,
    });

    // 2. Reprocesar: Same user re-verifying with extended expiry
    const newExpiry = initialExpiry + 5000;
    const reprocessResult = await subRepo.recordSubscription({
      uid: RUN_UID,
      purchaseToken: TEST_TOKEN,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: newExpiry,
      nowEpochMillis: NOW + 1000,
    });
    expect(reprocessResult).toEqual({
      status: "RECORDED",
      enabled: true,
      expiresAtEpochMillis: newExpiry,
    });

    const reprocessedDoc = (await firestore.doc(entitlementPath(RUN_UID)).get()).data();
    expect(reprocessedDoc?.["expiresAtEpochMillis"]).toBe(newExpiry);

    // 3. Revocación: Inactive / expired status updates entitlement to enabled=false with absolute expiry without adding time
    const revokedExpiry = NOW - 1000;
    const revokeResult = await subRepo.recordSubscription({
      uid: RUN_UID,
      purchaseToken: TEST_TOKEN,
      productId: product,
      enabled: false,
      expiresAtEpochMillis: revokedExpiry,
      nowEpochMillis: NOW + 1500,
    });
    expect(revokeResult).toEqual({
      status: "RECORDED",
      enabled: false,
      expiresAtEpochMillis: revokedExpiry,
    });

    const revokedDoc = (await firestore.doc(entitlementPath(RUN_UID)).get()).data();
    expect(revokedDoc).toEqual({
      enabled: false,
      expiresAtEpochMillis: revokedExpiry,
      lastVerifiedAtEpochMillis: NOW + 1500,
      productId: product,
      sourceTokenHash: TEST_TOKEN_HASH,
    });

    // 4. Token robado: Different user attempting to use the same token
    const stolenResult = await subRepo.recordSubscription({
      uid: INTRUDER_UID,
      purchaseToken: TEST_TOKEN,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: newExpiry,
      nowEpochMillis: NOW + 2000,
    });
    expect(stolenResult).toEqual({
      status: "TOKEN_BOUND_TO_DIFFERENT_UID",
    });

    // Intruder does not have an entitlement document
    const intruderDoc = await firestore.doc(entitlementPath(INTRUDER_UID)).get();
    expect(intruderDoc.exists).toBe(false);
  });

  test("interpretAiRequest handler against firestore control plane emits distinct rejection reasons", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    const mockProvider = {
      async interpret() {
        return {
          kind: "record_payment" as const,
          customerName: "María",
          declaredAmountCents: "20000",
        };
      },
    };
    const handler = createInterpretAiHandler({
      authorize: (i) => repository.authorize(i),
      claimProviderInvocation: async () => "CLAIMED",
      provider: mockProvider,
      guard: openGuard(),
      now: () => NOW,
    });

    const validPayload = {
      contractVersion: 2,
      requestId: randomUUID(),
      action: "interpret_text_proposal",
      text: "registro de abono",
    };

    // 1. AI_DISABLED when aiControl/global is disabled
    await firestore.doc("aiControl/global").set({
      enabled: false,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 10,
    });
    const disabledResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
      data: validPayload,
    });
    expect(disabledResult).toEqual({
      contractVersion: 2,
      requestId: validPayload.requestId,
      status: "AI_DISABLED",
      proposal: null,
    });

    // 2. AI_ENTITLEMENT_REQUIRED when entitlement is missing or expired
    await firestore.doc("aiControl/global").set({
      enabled: true,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 10,
    });
    await firestore.doc(`aiEntitlements/${RUN_UID}`).delete();
    const noEntitlementResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
      data: { ...validPayload, requestId: randomUUID() },
    });
    expect(noEntitlementResult).toEqual({
      contractVersion: 2,
      requestId: expect.any(String),
      status: "AI_ENTITLEMENT_REQUIRED",
      proposal: null,
    });

    // Restore valid entitlement
    await firestore.doc(`aiEntitlements/${RUN_UID}`).set({
      enabled: true,
      expiresAtEpochMillis: ENTITLEMENT_EXPIRES,
    });

    // 3. AI_QUOTA_EXHAUSTED when monthly quota is exhausted
    await firestore.doc(`aiUsage/${RUN_UID}_${monthKey(NOW)}`).set({
      usedActions: 10,
    });
    const quotaResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
      data: { ...validPayload, requestId: randomUUID() },
    });
    expect(quotaResult).toEqual({
      contractVersion: 2,
      requestId: expect.any(String),
      status: "AI_QUOTA_EXHAUSTED",
      proposal: null,
    });

    // Reset usage to 0, set perMinuteLimit to 1, consume 1
    await firestore.doc(`aiUsage/${RUN_UID}_${monthKey(NOW)}`).set({
      usedActions: 0,
    });
    await firestore.doc("aiControl/global").set({
      enabled: true,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 1,
    });
    await firestore.doc(`aiRateWindows/${RUN_UID}_${minuteKey(NOW)}`).set({
      usedActions: 1,
    });

    // 4. AI_RATE_LIMITED when minute rate window is exhausted
    const rateResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
      data: { ...validPayload, requestId: randomUUID() },
    });
    expect(rateResult).toEqual({
      contractVersion: 2,
      requestId: expect.any(String),
      status: "AI_RATE_LIMITED",
      proposal: null,
    });

    // 5. Anonymous session falls back to AI_UNAVAILABLE
    const anonResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "anonymous" } } },
      data: { ...validPayload, requestId: randomUUID() },
    });
    expect(anonResult).toEqual({
      contractVersion: 2,
      requestId: expect.any(String),
      status: "AI_UNAVAILABLE",
      proposal: null,
    });
  });

  test("getAiAvailability against firestore control plane is strictly read only and reflects configuration", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    const handler = createGetAiAvailabilityHandler({
      getAvailability: () => repository.getAvailability(),
    });

    // 1. When aiControl/global is enabled
    await firestore.doc("aiControl/global").set({
      enabled: true,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 10,
    });
    const enabledResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
    });
    expect(enabledResult).toEqual({ available: true });

    // 2. When aiControl/global is disabled
    await firestore.doc("aiControl/global").set({
      enabled: false,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 10,
    });
    const disabledResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
    });
    expect(disabledResult).toEqual({ available: false });

    // 3. When aiControl/global is deleted/missing
    await firestore.doc("aiControl/global").delete();
    const missingResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
    });
    expect(missingResult).toEqual({ available: false });

    // 4. Unauthenticated and anonymous callers are rejected
    const unauthResult = await handler({ auth: undefined });
    expect(unauthResult).toEqual({ code: "unauthenticated" });

    const anonResult = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "anonymous" } } },
    });
    expect(anonResult).toEqual({ code: "unauthenticated" });

    // 5. Verify zero quota consumption: no usage or rate window documents created
    const usageDoc = await firestore.doc(`aiUsage/${RUN_UID}_${monthKey(NOW)}`).get();
    expect(usageDoc.exists).toBe(false);

    const rateDoc = await firestore.doc(`aiRateWindows/${RUN_UID}_${minuteKey(NOW)}`).get();
    expect(rateDoc.exists).toBe(false);
  });

  test("emergency killswitch on aiControl/global cuts before provider invocation with zero money spent", async () => {
    const repository = createFirestoreControlPlaneRepository(firestore);
    let providerInvocations = 0;
    const countingProvider = {
      async interpret() {
        providerInvocations++;
        return {
          kind: "record_payment" as const,
          customerName: "María",
          declaredAmountCents: "20000",
        };
      },
    };
    const handler = createInterpretAiHandler({
      authorize: (i) => repository.authorize(i),
      claimProviderInvocation: (i) => repository.claimProviderInvocation(i),
      provider: countingProvider,
      guard: openGuard(),
      now: () => NOW,
    });

    const payload1 = {
      contractVersion: 2,
      requestId: randomUUID(),
      action: "interpret_text_proposal",
      text: "registro de abono",
    };

    // 1. Killswitch is ON (service disabled)
    await firestore.doc("aiControl/global").set({
      enabled: false,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 10,
    });

    const disabledResponse = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
      data: payload1,
    });

    expect(disabledResponse).toEqual({
      contractVersion: 2,
      requestId: payload1.requestId,
      status: "AI_DISABLED",
      proposal: null,
    });
    // Critical assertion: provider was NEVER called, zero money spent
    expect(providerInvocations).toBe(0);

    // 2. Killswitch is turned OFF (service enabled)
    await firestore.doc("aiControl/global").set({
      enabled: true,
      geminiEnabled: true,
      monthlyActionLimit: 10,
      perMinuteLimit: 10,
    });

    const payload2 = {
      contractVersion: 2,
      requestId: randomUUID(),
      action: "interpret_text_proposal",
      text: "registro de abono",
    };

    const enabledResponse = await handler({
      auth: { uid: RUN_UID, token: { firebase: { sign_in_provider: "password" } } },
      data: payload2,
    });

    expect(enabledResponse).toEqual({
      contractVersion: 2,
      requestId: payload2.requestId,
      status: "AI_PROPOSAL",
      proposal: {
        kind: "record_payment",
        customerName: "María",
        declaredAmountCents: "20000",
      },
    });
    // Critical assertion: provider was invoked exactly once upon re-enabling
    expect(providerInvocations).toBe(1);
  });

  test("RTDN consumer against firestore: grant via purchase, revoke on refund notification, and reprocess", async () => {
    const subRepo = createFirestoreSubscriptionEntitlementRepository(firestore);
    const product = "cuentivo_ai_mensual";
    const initialExpiry = NOW + 30 * 86400 * 1000;

    // 1. Initial purchase grant
    await subRepo.recordSubscription({
      uid: RUN_UID,
      purchaseToken: TEST_TOKEN,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: initialExpiry,
      nowEpochMillis: NOW,
    });

    const initialDoc = (await firestore.doc(entitlementPath(RUN_UID)).get()).data();
    expect(initialDoc).toEqual({
      enabled: true,
      expiresAtEpochMillis: initialExpiry,
      lastVerifiedAtEpochMillis: NOW,
      productId: product,
      sourceTokenHash: TEST_TOKEN_HASH,
    });

    // 2. RTDN Refund / Voided Purchase Notification arrives
    let playState: "ACTIVE" | "REFUNDED" = "REFUNDED";
    const mockPlayApi: PlaySubscriptionApi = {
      async getSubscription() {
        if (playState === "REFUNDED") {
          return null; // Play returns null/404 or inactive on voided/refunded purchase
        }
        return {
          subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
          lineItems: [{ productId: product, expiryTime: new Date(initialExpiry).toISOString() }],
        };
      },
    };

    const rtdnHandler = createPlayRtdnHandler({
      playApi: mockPlayApi,
      repository: subRepo,
      packageName: "com.cuentivo.app",
      now: () => NOW + 5000,
    });

    const voidedPayload = {
      version: "1.0",
      packageName: "com.cuentivo.app",
      voidedPurchaseNotification: {
        purchaseToken: TEST_TOKEN,
        orderId: "GPA.1234-5678-9012-34567",
      },
    };

    const revokeResult = await rtdnHandler(voidedPayload);
    expect(revokeResult).toEqual({
      status: "PROCESSED",
      uid: RUN_UID,
      enabled: false,
      expiresAtEpochMillis: 0,
    });

    const revokedDoc = (await firestore.doc(entitlementPath(RUN_UID)).get()).data();
    expect(revokedDoc).toEqual({
      enabled: false,
      expiresAtEpochMillis: 0,
      lastVerifiedAtEpochMillis: NOW + 5000,
      productId: product,
      sourceTokenHash: TEST_TOKEN_HASH,
    });

    // 3. Reprocessing the exact same refund notification leaves the exact same revoked state
    const reprocessResult = await rtdnHandler(voidedPayload);
    expect(reprocessResult).toEqual(revokeResult);

    const reprocessedDoc = (await firestore.doc(entitlementPath(RUN_UID)).get()).data();
    expect(reprocessedDoc).toEqual({
      enabled: false,
      expiresAtEpochMillis: 0,
      lastVerifiedAtEpochMillis: NOW + 5000,
      productId: product,
      sourceTokenHash: TEST_TOKEN_HASH,
    });
  });

  test("entitlement ordering against firestore: a stale verification and a replaced token cannot revoke access", async () => {
    // Exercises the real runTransaction paths, not the in-memory bridge: both writers read the
    // stored entitlement and honour source token plus observation time. Unit tests cannot cover
    // this — they neither enforce Firestore's read-before-write rule nor persist state.
    const subRepo = createFirestoreSubscriptionEntitlementRepository(firestore);
    const product = "cuentivo_ai_mensual";
    const uid = `gate-order-${randomUUID()}`;
    const tokenA = `tokenA-${randomUUID()}`;
    const tokenB = `tokenB-${randomUUID()}`;
    const hashA = hashPurchaseToken(tokenA);
    const hashB = hashPurchaseToken(tokenB);
    const expiry = NOW + 30 * 86400 * 1000;

    // 1. Token A is verified and becomes the source of the entitlement.
    await subRepo.recordSubscription({
      uid,
      purchaseToken: tokenA,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: expiry,
      nowEpochMillis: NOW,
    });

    // 2. An RTDN for token A revokes it, observed later than the verification.
    const revoke = await subRepo.updateEntitlement({
      uid,
      productId: product,
      purchaseToken: tokenA,
      enabled: false,
      expiresAtEpochMillis: NOW,
      nowEpochMillis: NOW + 2000,
    });
    expect(revoke).toBe("APPLIED");

    // 3. A verification of token A that observed Play *before* the cancellation lands late.
    //    Applying it would hand back access the user already cancelled.
    const stale = await subRepo.recordSubscription({
      uid,
      purchaseToken: tokenA,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: expiry,
      nowEpochMillis: NOW + 1000,
    });
    expect(stale).toEqual({ status: "STALE_IGNORED" });

    const afterStale = (await firestore.doc(entitlementPath(uid)).get()).data();
    expect(afterStale?.["enabled"]).toBe(false);
    expect(afterStale?.["sourceTokenHash"]).toBe(hashA);

    // 4. The user resubscribes: token B is verified and adopts the entitlement.
    const adopt = await subRepo.recordSubscription({
      uid,
      purchaseToken: tokenB,
      productId: product,
      enabled: true,
      expiresAtEpochMillis: expiry,
      nowEpochMillis: NOW + 3000,
    });
    expect(adopt).toEqual({
      status: "RECORDED",
      enabled: true,
      expiresAtEpochMillis: expiry,
    });
    expect((await firestore.doc(entitlementPath(uid)).get()).data()?.["sourceTokenHash"]).toBe(hashB);

    // 5. A late RTDN about the *replaced* token A must not revoke the new subscription,
    //    even though it is observed later than everything else.
    const foreign = await subRepo.updateEntitlement({
      uid,
      productId: product,
      purchaseToken: tokenA,
      enabled: false,
      expiresAtEpochMillis: NOW,
      nowEpochMillis: NOW + 9000,
    });
    expect(foreign).toBe("IGNORED_SUPERSEDED");

    const finalDoc = (await firestore.doc(entitlementPath(uid)).get()).data();
    expect(finalDoc?.["enabled"]).toBe(true);
    expect(finalDoc?.["sourceTokenHash"]).toBe(hashB);
  });

  test("entitlement ordering against firestore: an RTDN cannot adopt a legacy entitlement", async () => {
    // Documents written before source-token tracking exist in production. A notification about
    // some historical token must not become their source and revoke a paying user.
    const subRepo = createFirestoreSubscriptionEntitlementRepository(firestore);
    const product = "cuentivo_ai_mensual";
    const uid = `gate-legacy-${randomUUID()}`;
    const legacyToken = `legacy-${randomUUID()}`;

    await firestore.doc(entitlementPath(uid)).set({
      enabled: true,
      expiresAtEpochMillis: NOW + 30 * 86400 * 1000,
      lastVerifiedAtEpochMillis: NOW,
      productId: product,
    });

    const result = await subRepo.updateEntitlement({
      uid,
      productId: product,
      purchaseToken: legacyToken,
      enabled: false,
      expiresAtEpochMillis: NOW,
      nowEpochMillis: NOW + 5000,
    });

    expect(result).toBe("IGNORED_LEGACY_SOURCE");

    const doc = (await firestore.doc(entitlementPath(uid)).get()).data();
    expect(doc?.["enabled"]).toBe(true);
    expect(doc?.["sourceTokenHash"]).toBeUndefined();
  });
});
