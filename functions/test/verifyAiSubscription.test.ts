import { describe, expect, test, vi } from "vitest";
import {
  createVerifyAiSubscriptionHandler,
  SUPPORTED_AI_PRODUCT_ID,
} from "../src/verifyAiSubscription.js";
import type { PlaySubscriptionApi, PlaySubscriptionV2Response } from "../src/ai/playSubscriptionVerifier.js";
import type {
  SubscriptionEntitlementRepository,
  RecordSubscriptionInput,
  RecordSubscriptionResult,
} from "../src/ai/subscriptionEntitlementRepository.js";

describe("verifyAiSubscription handler", () => {
  const PACKAGE_NAME = "com.cuentivo.app";
  const NOW = 1756200000000;
  const FUTURE_ISO = "2026-09-25T12:00:00.000Z";
  const EXPIRES = Date.parse(FUTURE_ISO);
  const TOKEN = "valid_play_token_abc_123";

  function createMockPlayApi(response: PlaySubscriptionV2Response | null, shouldThrow = false): PlaySubscriptionApi {
    return {
      async getSubscription() {
        if (shouldThrow) {
          throw new Error("Google Play API network timeout");
        }
        return response;
      },
    };
  }

  function createMockRepository(status: "RECORDED" | "TOKEN_BOUND_TO_DIFFERENT_UID" | "STORAGE_ERROR" = "RECORDED"): SubscriptionEntitlementRepository {
    return {
      async recordSubscription(input: RecordSubscriptionInput): Promise<RecordSubscriptionResult> {
        if (status === "RECORDED") {
          return {
            status: "RECORDED",
            enabled: input.enabled,
            expiresAtEpochMillis: input.expiresAtEpochMillis,
          };
        }
        return { status };
      },
    };
  }

  test("rejects unauthenticated requests with unauthenticated code", async () => {
    const onRejected = vi.fn();
    const handler = createVerifyAiSubscriptionHandler({
      playApi: createMockPlayApi(null),
      repository: createMockRepository(),
      packageName: PACKAGE_NAME,
      now: () => NOW,
      onRejected,
    });

    const result = await handler({
      auth: undefined,
      data: { purchaseToken: TOKEN, productId: SUPPORTED_AI_PRODUCT_ID },
    });

    expect(result).toEqual({
      code: "unauthenticated",
      reason: "AI_AUTHENTICATION_REQUIRED",
    });
    expect(onRejected).toHaveBeenCalledWith("AI_AUTHENTICATION_REQUIRED");
  });

  test("rejects anonymous auth session before calling Play API", async () => {
    const onRejected = vi.fn();
    const playApi = createMockPlayApi(null);
    const getSubscriptionSpy = vi.spyOn(playApi, "getSubscription");

    const handler = createVerifyAiSubscriptionHandler({
      playApi,
      repository: createMockRepository(),
      packageName: PACKAGE_NAME,
      now: () => NOW,
      onRejected,
    });

    const result = await handler({
      auth: {
        uid: "anon_uid_123",
        token: { firebase: { sign_in_provider: "anonymous" } },
      },
      data: { purchaseToken: TOKEN, productId: SUPPORTED_AI_PRODUCT_ID },
    });

    expect(result).toEqual({
      code: "permission-denied",
      reason: "AI_ANONYMOUS_REJECTED",
    });
    expect(getSubscriptionSpy).not.toHaveBeenCalled();
    expect(onRejected).toHaveBeenCalledWith("AI_ANONYMOUS_REJECTED");
  });

  test("rejects invalid request data (missing token or unknown product)", async () => {
    const handler = createVerifyAiSubscriptionHandler({
      playApi: createMockPlayApi(null),
      repository: createMockRepository(),
      packageName: PACKAGE_NAME,
      now: () => NOW,
    });

    const resultMissingToken = await handler({
      auth: { uid: "user_valid", token: { firebase: { sign_in_provider: "google.com" } } },
      data: { purchaseToken: "", productId: SUPPORTED_AI_PRODUCT_ID },
    });
    expect(resultMissingToken).toEqual({
      code: "invalid-argument",
      reason: "INVALID_PURCHASE_DATA",
    });

    const resultWrongProduct = await handler({
      auth: { uid: "user_valid", token: { firebase: { sign_in_provider: "google.com" } } },
      data: { purchaseToken: TOKEN, productId: "unknown_plan" },
    });
    expect(resultWrongProduct).toEqual({
      code: "invalid-argument",
      reason: "INVALID_PURCHASE_DATA",
    });
  });

  test("returns unavailable when Play Developer API throws network error", async () => {
    const handler = createVerifyAiSubscriptionHandler({
      playApi: createMockPlayApi(null, true),
      repository: createMockRepository(),
      packageName: PACKAGE_NAME,
      now: () => NOW,
    });

    const result = await handler({
      auth: { uid: "user_valid", token: { firebase: { sign_in_provider: "google.com" } } },
      data: { purchaseToken: TOKEN, productId: SUPPORTED_AI_PRODUCT_ID },
    });

    expect(result).toEqual({
      code: "unavailable",
      reason: "PLAY_API_UNAVAILABLE",
    });
  });

  test("returns invalid-argument when Play returns invalid or non-existent subscription", async () => {
    const handler = createVerifyAiSubscriptionHandler({
      playApi: createMockPlayApi(null),
      repository: createMockRepository(),
      packageName: PACKAGE_NAME,
      now: () => NOW,
    });

    const result = await handler({
      auth: { uid: "user_valid", token: { firebase: { sign_in_provider: "google.com" } } },
      data: { purchaseToken: TOKEN, productId: SUPPORTED_AI_PRODUCT_ID },
    });

    expect(result).toEqual({
      code: "invalid-argument",
      reason: "PLAY_SUBSCRIPTION_INVALID",
    });
  });

  test("successfully verifies active Play subscription and records entitlement", async () => {
    const playResponse: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [
        {
          productId: SUPPORTED_AI_PRODUCT_ID,
          expiryTime: FUTURE_ISO,
        },
      ],
    };

    const handler = createVerifyAiSubscriptionHandler({
      playApi: createMockPlayApi(playResponse),
      repository: createMockRepository("RECORDED"),
      packageName: PACKAGE_NAME,
      now: () => NOW,
    });

    const result = await handler({
      auth: { uid: "user_valid", token: { firebase: { sign_in_provider: "google.com" } } },
      data: { purchaseToken: TOKEN, productId: SUPPORTED_AI_PRODUCT_ID },
    });

    expect(result).toEqual({
      status: "VERIFIED",
      enabled: true,
      expiresAtEpochMillis: EXPIRES,
    });
  });

  test("rejects token bound to another user with permission-denied", async () => {
    const playResponse: PlaySubscriptionV2Response = {
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [
        {
          productId: SUPPORTED_AI_PRODUCT_ID,
          expiryTime: FUTURE_ISO,
        },
      ],
    };

    const onRejected = vi.fn();
    const handler = createVerifyAiSubscriptionHandler({
      playApi: createMockPlayApi(playResponse),
      repository: createMockRepository("TOKEN_BOUND_TO_DIFFERENT_UID"),
      packageName: PACKAGE_NAME,
      now: () => NOW,
      onRejected,
    });

    const result = await handler({
      auth: { uid: "user_intruder", token: { firebase: { sign_in_provider: "google.com" } } },
      data: { purchaseToken: TOKEN, productId: SUPPORTED_AI_PRODUCT_ID },
    });

    expect(result).toEqual({
      code: "permission-denied",
      reason: "TOKEN_BOUND_TO_DIFFERENT_UID",
    });
    expect(onRejected).toHaveBeenCalledWith("TOKEN_BOUND_TO_DIFFERENT_UID");
  });
});
