import { describe, expect, test } from "vitest";
import {
  hashPurchaseToken,
  translatePlaySubscription,
  createDefaultPlaySubscriptionApi,
  type PlaySubscriptionV2Response,
} from "../src/ai/playSubscriptionVerifier.js";

describe("playSubscriptionVerifier", () => {
  const PRODUCT_ID = "cuentivo_ai_mensual";
  const FUTURE_ISO = "2026-09-25T12:00:00.000Z";
  const FUTURE_MILLIS = Date.parse(FUTURE_ISO);

  test("hashPurchaseToken produces stable 64-char sha256 hex string", () => {
    const token = "sample_play_purchase_token_12345";
    const hash1 = hashPurchaseToken(token);
    const hash2 = hashPurchaseToken(token);
    expect(hash1).toBe(hash2);
    expect(hash1).toHaveLength(64);
    expect(hash1).toMatch(/^[0-9a-f]{64}$/);
    expect(hashPurchaseToken("different_token")).not.toBe(hash1);
  });

  test("active subscription translates to VERIFIED with absolute epoch millis", () => {
    const response: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [
        {
          productId: PRODUCT_ID,
          expiryTime: FUTURE_ISO,
        },
      ],
    };
    const result = translatePlaySubscription(response, PRODUCT_ID);
    expect(result).toEqual({
      status: "VERIFIED",
      enabled: true,
      expiresAtEpochMillis: FUTURE_MILLIS,
    });
  });

  test("in-grace-period subscription translates to VERIFIED with absolute epoch millis", () => {
    const response: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
      lineItems: [
        {
          productId: PRODUCT_ID,
          expiryTime: FUTURE_ISO,
        },
      ],
    };
    const result = translatePlaySubscription(response, PRODUCT_ID);
    expect(result).toEqual({
      status: "VERIFIED",
      enabled: true,
      expiresAtEpochMillis: FUTURE_MILLIS,
    });
  });

  test("canceled subscription with future expiry translates to VERIFIED with absolute epoch millis", () => {
    const canceledResponse: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_CANCELED",
      lineItems: [
        {
          productId: PRODUCT_ID,
          expiryTime: FUTURE_ISO,
        },
      ],
    };
    const resultCanceled = translatePlaySubscription(canceledResponse, PRODUCT_ID);
    expect(resultCanceled).toEqual({
      status: "VERIFIED",
      enabled: true,
      expiresAtEpochMillis: FUTURE_MILLIS,
    });
  });

  test("expired, on-hold, paused, or pending subscriptions translate to INACTIVE without granting entitlement", () => {
    const states: PlaySubscriptionV2Response["subscriptionState"][] = [
      "SUBSCRIPTION_STATE_EXPIRED",
      "SUBSCRIPTION_STATE_ON_HOLD",
      "SUBSCRIPTION_STATE_PAUSED",
      "SUBSCRIPTION_STATE_PENDING",
      "SUBSCRIPTION_STATE_UNSPECIFIED",
    ];

    for (const state of states) {
      const response: PlaySubscriptionV2Response = {
        subscriptionState: state,
        lineItems: [
          {
            productId: PRODUCT_ID,
            expiryTime: FUTURE_ISO,
          },
        ],
      };
      const result = translatePlaySubscription(response, PRODUCT_ID);
      expect(result).toEqual({
        status: "INACTIVE",
        enabled: false,
        expiresAtEpochMillis: FUTURE_MILLIS,
      });
    }
  });

  test("null response or missing line items translates to INVALID", () => {
    expect(translatePlaySubscription(null, PRODUCT_ID)).toEqual({ status: "INVALID" });
    expect(translatePlaySubscription({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE" }, PRODUCT_ID)).toEqual({
      status: "INVALID",
    });
    expect(
      translatePlaySubscription({ subscriptionState: "SUBSCRIPTION_STATE_ACTIVE", lineItems: [] }, PRODUCT_ID),
    ).toEqual({ status: "INVALID" });
  });

  test("mismatched product ID translates to INVALID", () => {
    const response: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [
        {
          productId: "cuentivo_pro_mensual",
          expiryTime: FUTURE_ISO,
        },
      ],
    };
    expect(translatePlaySubscription(response, PRODUCT_ID)).toEqual({ status: "INVALID" });
  });

  test("invalid or unparseable expiry time string translates to INVALID", () => {
    const response: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [
        {
          productId: PRODUCT_ID,
          expiryTime: "not_a_valid_timestamp",
        },
      ],
    };
    expect(translatePlaySubscription(response, PRODUCT_ID)).toEqual({ status: "INVALID" });
  });

  test("createDefaultPlaySubscriptionApi with customFetcher dispatches without credentials", async () => {
    const expectedResponse: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [{ productId: PRODUCT_ID, expiryTime: FUTURE_ISO }],
    };
    const api = createDefaultPlaySubscriptionApi(async (url) => {
      expect(url).toContain("tokens/test_token_123");
      return expectedResponse;
    });
    const result = await api.getSubscription("com.cuentivo.app", "test_token_123");
    expect(result).toEqual(expectedResponse);
  });

  test("createDefaultPlaySubscriptionApi fails closed when service account is missing or invalid JSON", async () => {
    const apiMissing = createDefaultPlaySubscriptionApi();
    await expect(apiMissing.getSubscription("com.cuentivo.app", "token_123")).rejects.toThrow(
      "service account not configured",
    );

    const apiInvalidJson = createDefaultPlaySubscriptionApi({ serviceAccountJson: "invalid_json_string" });
    await expect(apiInvalidJson.getSubscription("com.cuentivo.app", "token_123")).rejects.toThrow(
      "not valid JSON",
    );
  });
});
