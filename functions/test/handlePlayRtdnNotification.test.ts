import { describe, expect, test, vi } from "vitest";
import {
  createPlayRtdnHandler,
  parsePlayRtdnPayload,
  SUPPORTED_AI_PRODUCT_ID,
  type PlayRtdnPayload,
} from "../src/handlePlayRtdnNotification.js";
import {
  type PlaySubscriptionApi,
  type PlaySubscriptionV2Response,
  PlayApiConfigurationError,
} from "../src/ai/playSubscriptionVerifier.js";
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
    updateResult?: "APPLIED" | "IGNORED_SUPERSEDED" | "IGNORED_LEGACY_SOURCE";
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
        return overrides?.updateResult ?? ("APPLIED" as const);
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
        purchaseToken: TEST_TOKEN,
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
          purchaseToken: TEST_TOKEN,
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
        purchaseToken: TEST_TOKEN,
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

  test("invalid or malformed payload terminates cleanly and DOES NOT throw (discards without retry)", async () => {
    const deps = createTestDeps();
    const handler = createPlayRtdnHandler(deps);

    // 1. Completely empty or unparseable raw message
    const emptyResult = await handler(null);
    expect(emptyResult).toEqual({ status: "IGNORED", reason: "INVALID_PAYLOAD" });
    expect(deps.recordedUpdates).toHaveLength(0);
    expect(deps.playApi.getSubscription).not.toHaveBeenCalled();

    // 2. Corrupt string that cannot be parsed as JSON nor base64 JSON
    const corruptResult = await handler("malformed_json_content_{{[[");
    expect(corruptResult).toEqual({ status: "IGNORED", reason: "INVALID_PAYLOAD" });
    expect(deps.recordedUpdates).toHaveLength(0);

    // 3. Payload with missing purchaseToken
    const missingTokenResult = await handler({
      packageName: PACKAGE_NAME,
      subscriptionNotification: {},
    });
    expect(missingTokenResult).toEqual({ status: "IGNORED", reason: "NO_PURCHASE_TOKEN" });
    expect(deps.recordedUpdates).toHaveLength(0);
  });

  test("permanent credentials or configuration error terminates cleanly without throwing (discards without retry)", async () => {
    const configError = new PlayApiConfigurationError("Play Developer API service account not configured in this environment");
    const deps = createTestDeps({ playApiError: configError });
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
    expect(result).toEqual({ status: "IGNORED", reason: "PERMANENT_CONFIG_ERROR" });
    expect(deps.recordedUpdates).toHaveLength(0);
    expect(deps.onIgnored).toHaveBeenCalledWith("PERMANENT_CONFIG_ERROR");
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

  test("a notification rejected as superseded is reported ignored, not processed", async () => {
    // The repository refuses writes that describe a subscription the user already replaced.
    // The handler must treat that as a resolved outcome — no retry, no success signal — or the
    // caller would believe a revocation landed when it did not.
    const onIgnored = vi.fn();
    const onProcessed = vi.fn();
    const deps = createTestDeps({ updateResult: "IGNORED_SUPERSEDED", onIgnored, onProcessed });
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

    expect(result).toEqual({ status: "IGNORED", reason: "SUPERSEDED_BY_NEWER_STATE" });
    expect(onIgnored).toHaveBeenCalledWith("SUPERSEDED_BY_NEWER_STATE");
    expect(onProcessed).not.toHaveBeenCalled();
  });

  test("a notification against a legacy entitlement is reported unresolved, not processed", async () => {
    // An entitlement written before source-token tracking cannot be proven to belong to this
    // notification's token. Revoking on that basis could cut off a paying user, so the event is
    // surfaced for reconciliation instead of applied.
    const onIgnored = vi.fn();
    const onProcessed = vi.fn();
    const deps = createTestDeps({ updateResult: "IGNORED_LEGACY_SOURCE", onIgnored, onProcessed });
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

    expect(result).toEqual({ status: "IGNORED", reason: "LEGACY_SOURCE_UNRESOLVED" });
    expect(onIgnored).toHaveBeenCalledWith("LEGACY_SOURCE_UNRESOLVED");
    expect(onProcessed).not.toHaveBeenCalled();
  });
});
