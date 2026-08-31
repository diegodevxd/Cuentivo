import { describe, expect, test, vi } from "vitest";
import {
  createPlayRtdnHandler,
  parsePlayRtdnPayload,
  SUPPORTED_AI_PRODUCT_ID,
  type PlayRtdnPayload,
} from "../src/handlePlayRtdnNotification.js";
import type { PlaySubscriptionApi, PlaySubscriptionV2Response } from "../src/ai/playSubscriptionVerifier.js";
import type {
  SubscriptionEntitlementRepository,
  TokenBinding,
  UpdateEntitlementInput,
} from "../src/ai/subscriptionEntitlementRepository.js";

describe("handlePlayRtdnNotification", () => {
  const PACKAGE_NAME = "com.cuentivo.app";
  const TEST_UID = "user_diego_123";
  const TEST_TOKEN = "sample_play_purchase_token_rtdn_999";
  const NOW_MILLIS = 1725000000000;
  const FUTURE_ISO = "2026-09-30T12:00:00.000Z";
  const FUTURE_MILLIS = Date.parse(FUTURE_ISO);

  function createTestDeps(overrides?: {
    playApiResponse?: PlaySubscriptionV2Response | null;
    playApiError?: Error;
    tokenBinding?: TokenBinding | null;
    onIgnored?: (reason: string) => void;
    onProcessed?: (details: { uid: string; enabled: boolean; expiresAtEpochMillis: number }) => void;
  }) {
    const playApi: PlaySubscriptionApi = {
      getSubscription: vi.fn(async (_pkg, _token) => {
        if (overrides?.playApiError) {
          throw overrides.playApiError;
        }
        return overrides?.playApiResponse !== undefined
          ? overrides.playApiResponse
          : {
              subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
              lineItems: [{ productId: SUPPORTED_AI_PRODUCT_ID, expiryTime: FUTURE_ISO }],
            };
      }),
    };

    const recordedUpdates: UpdateEntitlementInput[] = [];
    const repository: SubscriptionEntitlementRepository = {
      recordSubscription: vi.fn(),
      findTokenBinding: vi.fn(async (token: string) => {
        if (overrides?.tokenBinding !== undefined) {
          return overrides.tokenBinding;
        }
        return { uid: TEST_UID, productId: SUPPORTED_AI_PRODUCT_ID };
      }),
      updateEntitlement: vi.fn(async (input: UpdateEntitlementInput) => {
        recordedUpdates.push(input);
      }),
    };

    return {
      playApi,
      repository,
      recordedUpdates,
      packageName: PACKAGE_NAME,
      now: () => NOW_MILLIS,
      onIgnored: overrides?.onIgnored ?? vi.fn(),
      onProcessed: overrides?.onProcessed ?? vi.fn(),
    };
  }

  test("testNotification terminates cleanly without error and without writing to Firestore", async () => {
    const deps = createTestDeps();
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      testNotification: { version: "1.0" },
    };

    const result = await handler(payload);
    expect(result).toEqual({ status: "TEST_NOTIFICATION" });
    expect(deps.recordedUpdates).toHaveLength(0);
    expect(deps.playApi.getSubscription).not.toHaveBeenCalled();
    expect(deps.onIgnored).toHaveBeenCalledWith("TEST_NOTIFICATION");
  });

  test("mismatched packageName is ignored cleanly without writing", async () => {
    const deps = createTestDeps();
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: "com.other.app",
      subscriptionNotification: {
        purchaseToken: TEST_TOKEN,
        subscriptionId: SUPPORTED_AI_PRODUCT_ID,
      },
    };

    const result = await handler(payload);
    expect(result).toEqual({ status: "IGNORED", reason: "PACKAGE_NAME_MISMATCH" });
    expect(deps.recordedUpdates).toHaveLength(0);
    expect(deps.playApi.getSubscription).not.toHaveBeenCalled();
  });

  test("token not bound to any UID terminates cleanly without writing or creating an entitlement", async () => {
    const deps = createTestDeps({ tokenBinding: null });
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      subscriptionNotification: {
        purchaseToken: "unbound_token_abc",
        subscriptionId: SUPPORTED_AI_PRODUCT_ID,
      },
    };

    const result = await handler(payload);
    expect(result).toEqual({ status: "TOKEN_NOT_BOUND" });
    expect(deps.recordedUpdates).toHaveLength(0);
    expect(deps.playApi.getSubscription).not.toHaveBeenCalled();
    expect(deps.onIgnored).toHaveBeenCalledWith("TOKEN_NOT_BOUND");
  });

  test("subscription canceled with future expiry still grants entitlement until expiry", async () => {
    const deps = createTestDeps({
      playApiResponse: {
        subscriptionState: "SUBSCRIPTION_STATE_CANCELED",
        lineItems: [{ productId: SUPPORTED_AI_PRODUCT_ID, expiryTime: FUTURE_ISO }],
      },
    });
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      subscriptionNotification: {
        purchaseToken: TEST_TOKEN,
        subscriptionId: SUPPORTED_AI_PRODUCT_ID,
      },
    };

    const result = await handler(payload);
    expect(result).toEqual({
      status: "PROCESSED",
      uid: TEST_UID,
      enabled: true,
      expiresAtEpochMillis: FUTURE_MILLIS,
    });
    expect(deps.recordedUpdates).toEqual([
      {
        uid: TEST_UID,
        productId: SUPPORTED_AI_PRODUCT_ID,
        enabled: true,
        expiresAtEpochMillis: FUTURE_MILLIS,
        nowEpochMillis: NOW_MILLIS,
      },
    ]);
  });

  test("subscription on-hold or expired disables entitlement without granting access", async () => {
    for (const state of ["SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_EXPIRED"] as const) {
      const deps = createTestDeps({
        playApiResponse: {
          subscriptionState: state,
          lineItems: [{ productId: SUPPORTED_AI_PRODUCT_ID, expiryTime: FUTURE_ISO }],
        },
      });
      const handler = createPlayRtdnHandler(deps);

      const payload: PlayRtdnPayload = {
        version: "1.0",
        packageName: PACKAGE_NAME,
        subscriptionNotification: {
          purchaseToken: TEST_TOKEN,
          subscriptionId: SUPPORTED_AI_PRODUCT_ID,
        },
      };

      const result = await handler(payload);
      expect(result).toEqual({
        status: "PROCESSED",
        uid: TEST_UID,
        enabled: false,
        expiresAtEpochMillis: FUTURE_MILLIS,
      });
      expect(deps.recordedUpdates).toEqual([
        {
          uid: TEST_UID,
          productId: SUPPORTED_AI_PRODUCT_ID,
          enabled: false,
          expiresAtEpochMillis: FUTURE_MILLIS,
          nowEpochMillis: NOW_MILLIS,
        },
      ]);
    }
  });

  test("voidedPurchaseNotification queries Play and writes disabled status on refund", async () => {
    const deps = createTestDeps({
      playApiResponse: null, // Play returns null / 404 or inactive on voided/refunded purchase
    });
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      voidedPurchaseNotification: {
        purchaseToken: TEST_TOKEN,
        orderId: "GPA.1234-5678-9012-34567",
        productType: 1,
        refundType: 1,
      },
    };

    const result = await handler(payload);
    expect(result).toEqual({
      status: "PROCESSED",
      uid: TEST_UID,
      enabled: false,
      expiresAtEpochMillis: 0,
    });
    expect(deps.recordedUpdates).toEqual([
      {
        uid: TEST_UID,
        productId: SUPPORTED_AI_PRODUCT_ID,
        enabled: false,
        expiresAtEpochMillis: 0,
        nowEpochMillis: NOW_MILLIS,
      },
    ]);
  });

  test("idempotency: processing the same notification twice yields the exact same state", async () => {
    const deps = createTestDeps({
      playApiResponse: {
        subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
        lineItems: [{ productId: SUPPORTED_AI_PRODUCT_ID, expiryTime: FUTURE_ISO }],
      },
    });
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      subscriptionNotification: {
        purchaseToken: TEST_TOKEN,
        subscriptionId: SUPPORTED_AI_PRODUCT_ID,
      },
    };

    const firstRun = await handler(payload);
    const secondRun = await handler(payload);

    expect(firstRun).toEqual(secondRun);
    expect(deps.recordedUpdates).toHaveLength(2);
    expect(deps.recordedUpdates[0]).toEqual(deps.recordedUpdates[1]);
  });

  test("Play API error throws to let Pub/Sub retry and DOES NOT modify Firestore", async () => {
    const playError = new Error("Google Play API timeout (504)");
    const deps = createTestDeps({ playApiError: playError });
    const handler = createPlayRtdnHandler(deps);

    const payload: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      subscriptionNotification: {
        purchaseToken: TEST_TOKEN,
        subscriptionId: SUPPORTED_AI_PRODUCT_ID,
      },
    };

    await expect(handler(payload)).rejects.toThrow("Google Play API timeout (504)");
    expect(deps.recordedUpdates).toHaveLength(0);
  });

  test("parsePlayRtdnPayload parses base64, JSON string, buffer and direct object", () => {
    const rawObj: PlayRtdnPayload = {
      version: "1.0",
      packageName: PACKAGE_NAME,
      testNotification: { version: "1.0" },
    };

    // 1. Direct object
    expect(parsePlayRtdnPayload(rawObj)).toEqual(rawObj);

    // 2. PubSub Message wrapper with base64 data
    const jsonStr = JSON.stringify(rawObj);
    const base64Str = Buffer.from(jsonStr, "utf8").toString("base64");
    expect(parsePlayRtdnPayload({ data: base64Str })).toEqual(rawObj);

    // 3. Raw JSON string
    expect(parsePlayRtdnPayload(jsonStr)).toEqual(rawObj);

    // 4. Raw Buffer
    expect(parsePlayRtdnPayload(Buffer.from(jsonStr, "utf8"))).toEqual(rawObj);

    // 5. Invalid string / null
    expect(parsePlayRtdnPayload(null)).toBeNull();
    expect(parsePlayRtdnPayload("not_valid_json_nor_base64_json!@#$")).toBeNull();
  });
});
