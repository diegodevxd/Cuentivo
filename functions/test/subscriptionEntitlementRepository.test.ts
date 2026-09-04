import { describe, expect, test } from "vitest";
import {
  recordSubscriptionInTransaction,
  tokenBindingPath,
  entitlementPath,
  entitlementWriteDecision,
  type SubscriptionTransactionBridge,
} from "../src/ai/subscriptionEntitlementRepository.js";
import { hashPurchaseToken } from "../src/ai/playSubscriptionVerifier.js";

describe("subscriptionEntitlementRepository", () => {
  const PRODUCT_ID = "cuentivo_ai_mensual";
  const TOKEN = "valid_play_token_xyz_987";
  const TOKEN_HASH = hashPurchaseToken(TOKEN);
  const NOW = 1756200000000;
  const EXPIRES = NOW + 30 * 86400 * 1000;

  function createMemoryBridge(initialDocs: Record<string, Record<string, unknown>> = {}) {
    const store = new Map<string, Record<string, unknown>>(
      Object.entries(initialDocs).map(([k, v]) => [k, { ...v }]),
    );

    const bridge: SubscriptionTransactionBridge = {
      async get(path: string) {
        const data = store.get(path);
        return data ? { ...data } : null;
      },
      set(path: string, data: Record<string, unknown>, merge?: boolean) {
        if (merge) {
          const existing = store.get(path) ?? {};
          store.set(path, { ...existing, ...data });
        } else {
          store.set(path, { ...data });
        }
      },
    };

    return { bridge, store };
  }

  test("first writer wins: binds token and writes entitlement for user", async () => {
    const { bridge, store } = createMemoryBridge();

    const result = await recordSubscriptionInTransaction(bridge, {
      uid: "user_owner",
      purchaseToken: TOKEN,
      productId: PRODUCT_ID,
      enabled: true,
      expiresAtEpochMillis: EXPIRES,
      nowEpochMillis: NOW,
    });

    expect(result).toEqual({
      status: "RECORDED",
      enabled: true,
      expiresAtEpochMillis: EXPIRES,
    });

    const tokenDoc = store.get(tokenBindingPath(TOKEN_HASH));
    expect(tokenDoc).toEqual({
      uid: "user_owner",
      boundAtEpochMillis: NOW,
      productId: PRODUCT_ID,
      tokenHash: TOKEN_HASH,
    });

    const entDoc = store.get(entitlementPath("user_owner"));
    expect(entDoc).toEqual({
      enabled: true,
      expiresAtEpochMillis: EXPIRES,
      lastVerifiedAtEpochMillis: NOW,
      productId: PRODUCT_ID,
      sourceTokenHash: TOKEN_HASH,
    });
  });

  test("idempotent reprocessing: same user with same token updates absolute expiry time", async () => {
    const { bridge, store } = createMemoryBridge({
      [tokenBindingPath(TOKEN_HASH)]: {
        uid: "user_owner",
        boundAtEpochMillis: NOW - 10000,
        productId: PRODUCT_ID,
        tokenHash: TOKEN_HASH,
      },
      [entitlementPath("user_owner")]: {
        enabled: true,
        expiresAtEpochMillis: EXPIRES,
        lastVerifiedAtEpochMillis: NOW - 10000,
        productId: PRODUCT_ID,
      },
    });

    const EXTENDED_EXPIRES = EXPIRES + 10000;
    const result = await recordSubscriptionInTransaction(bridge, {
      uid: "user_owner",
      purchaseToken: TOKEN,
      productId: PRODUCT_ID,
      enabled: true,
      expiresAtEpochMillis: EXTENDED_EXPIRES,
      nowEpochMillis: NOW,
    });

    expect(result).toEqual({
      status: "RECORDED",
      enabled: true,
      expiresAtEpochMillis: EXTENDED_EXPIRES,
    });

    const entDoc = store.get(entitlementPath("user_owner"));
    expect(entDoc?.["expiresAtEpochMillis"]).toBe(EXTENDED_EXPIRES);
  });

  test("stolen token: token previously bound to user_owner presented by user_intruder is rejected", async () => {
    const { bridge, store } = createMemoryBridge({
      [tokenBindingPath(TOKEN_HASH)]: {
        uid: "user_owner",
        boundAtEpochMillis: NOW - 5000,
        productId: PRODUCT_ID,
        tokenHash: TOKEN_HASH,
      },
      [entitlementPath("user_owner")]: {
        enabled: true,
        expiresAtEpochMillis: EXPIRES,
        lastVerifiedAtEpochMillis: NOW - 5000,
        productId: PRODUCT_ID,
      },
    });

    const result = await recordSubscriptionInTransaction(bridge, {
      uid: "user_intruder",
      purchaseToken: TOKEN,
      productId: PRODUCT_ID,
      enabled: true,
      expiresAtEpochMillis: EXPIRES,
      nowEpochMillis: NOW,
    });

    expect(result).toEqual({
      status: "TOKEN_BOUND_TO_DIFFERENT_UID",
    });

    // Token binding remains owned by user_owner
    expect(store.get(tokenBindingPath(TOKEN_HASH))?.["uid"]).toBe("user_owner");

    // user_intruder gets NO entitlement created
    expect(store.get(entitlementPath("user_intruder"))).toBeUndefined();

    // user_owner entitlement is untouched
    expect(store.get(entitlementPath("user_owner"))?.["expiresAtEpochMillis"]).toBe(EXPIRES);
  });

  test("a verification that observed Play earlier does not overwrite a later cancellation", async () => {
    // The race that loses revocation: a slow verification reads Play as active, an RTDN then
    // records the cancellation, and the verification finally lands. Writing blindly would
    // re-enable a subscription the user already cancelled.
    const CANCELLED_AT = NOW;
    const { bridge, store } = createMemoryBridge({
      [tokenBindingPath(TOKEN_HASH)]: {
        uid: "user_owner",
        boundAtEpochMillis: NOW - 60000,
        productId: PRODUCT_ID,
        tokenHash: TOKEN_HASH,
      },
      [entitlementPath("user_owner")]: {
        enabled: false,
        expiresAtEpochMillis: 0,
        lastVerifiedAtEpochMillis: CANCELLED_AT,
        productId: PRODUCT_ID,
        sourceTokenHash: TOKEN_HASH,
      },
    });

    const result = await recordSubscriptionInTransaction(bridge, {
      uid: "user_owner",
      purchaseToken: TOKEN,
      productId: PRODUCT_ID,
      enabled: true,
      expiresAtEpochMillis: EXPIRES,
      nowEpochMillis: CANCELLED_AT - 30000, // observed Play before the cancellation
    });

    expect(result).toEqual({ status: "STALE_IGNORED" });
    expect(store.get(entitlementPath("user_owner"))?.["enabled"]).toBe(false);
  });

  describe("entitlementWriteDecision", () => {
    const OTHER_TOKEN_HASH = hashPurchaseToken("a_different_play_token_222");

    test("applies when nothing is stored yet", () => {
      expect(
        entitlementWriteDecision(null, {
          sourceTokenHash: TOKEN_HASH,
          enabled: true,
          nowEpochMillis: NOW,
          origin: "VERIFICATION",
        }),
      ).toBe("APPLY");
    });

    test("a verification adopts a document written before sourceTokenHash existed", () => {
      // Legacy entitlements carry no source token; a verification just asked Play about a token
      // it knows belongs to this user, so it can establish the source safely.
      expect(
        entitlementWriteDecision(
          { enabled: true, lastVerifiedAtEpochMillis: NOW },
          {
            sourceTokenHash: TOKEN_HASH,
            enabled: true,
            nowEpochMillis: NOW + 5000,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("APPLY");
    });

    test("a stale verification cannot adopt a legacy document either", () => {
      // Being a verification does not make an old observation current: a legacy entitlement
      // already revoked at T2 must not be re-enabled by a verification that saw Play at T1.
      expect(
        entitlementWriteDecision(
          { enabled: false, lastVerifiedAtEpochMillis: NOW },
          {
            sourceTokenHash: TOKEN_HASH,
            enabled: true,
            nowEpochMillis: NOW - 5000,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("IGNORE_STALE");
    });

    test("an RTDN cannot revoke a legacy entitlement it cannot prove it owns", () => {
      // Without a stored source token, a notification about some historical token would become
      // the first sourceTokenHash and cut off a subscription that is still being paid for.
      expect(
        entitlementWriteDecision(
          { enabled: true, lastVerifiedAtEpochMillis: NOW },
          {
            sourceTokenHash: TOKEN_HASH,
            enabled: false,
            nowEpochMillis: NOW + 5000,
            origin: "RTDN",
          },
        ),
      ).toBe("IGNORE_LEGACY_UNRESOLVED");
    });

    test("an RTDN for a replaced token cannot revoke a newly purchased subscription", () => {
      // The user resubscribed with a new token. A late notification about the old one says
      // inactive. Letting it through would revoke a subscription that was just paid for.
      expect(
        entitlementWriteDecision(
          { enabled: true, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: OTHER_TOKEN_HASH,
            enabled: false,
            nowEpochMillis: NOW + 60000,
            origin: "RTDN",
          },
        ),
      ).toBe("IGNORE_FOREIGN_TOKEN");
    });

    test("a verification adopts a new token when it observed Play more recently", () => {
      // Same situation, opposite direction: the resubscription's own verification must be able
      // to take ownership of the entitlement.
      expect(
        entitlementWriteDecision(
          { enabled: false, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: OTHER_TOKEN_HASH,
            enabled: true,
            nowEpochMillis: NOW + 60000,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("APPLY");
    });

    test("an inactive verification of a different token cannot revoke the active source", () => {
      // The app verifies every owned purchase on launch, so an expired historical token gets
      // verified alongside the live one. Its inactive result says nothing about the token that
      // is actually granting access.
      expect(
        entitlementWriteDecision(
          { enabled: true, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: OTHER_TOKEN_HASH,
            enabled: false,
            nowEpochMillis: NOW + 60000,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("IGNORE_FOREIGN_TOKEN");
    });

    test("an active verification of a new token adopts even on an exact tie", () => {
      // A resubscription must not be refused because a different subscription ended in the same
      // millisecond: they are separate purchases.
      expect(
        entitlementWriteDecision(
          { enabled: false, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: OTHER_TOKEN_HASH,
            enabled: true,
            nowEpochMillis: NOW,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("APPLY");
    });

    test("an older observation of the same token never overwrites a newer one", () => {
      expect(
        entitlementWriteDecision(
          { enabled: false, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: TOKEN_HASH,
            enabled: true,
            nowEpochMillis: NOW - 1,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("IGNORE_STALE");
    });

    test("an exact timestamp tie never resolves towards granting access", () => {
      expect(
        entitlementWriteDecision(
          { enabled: false, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: TOKEN_HASH,
            enabled: true,
            nowEpochMillis: NOW,
            origin: "VERIFICATION",
          },
        ),
      ).toBe("IGNORE_STALE");

      // ...but a tie that revokes is applied, so revocation is never the one dropped.
      expect(
        entitlementWriteDecision(
          { enabled: true, lastVerifiedAtEpochMillis: NOW, sourceTokenHash: TOKEN_HASH },
          {
            sourceTokenHash: TOKEN_HASH,
            enabled: false,
            nowEpochMillis: NOW,
            origin: "RTDN",
          },
        ),
      ).toBe("APPLY");
    });
  });
});
