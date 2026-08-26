import { describe, expect, test } from "vitest";
import {
  recordSubscriptionInTransaction,
  tokenBindingPath,
  entitlementPath,
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
});
