import { isAnonymousUser, type CallableAuth } from "./contract";
import type { PlaySubscriptionApi } from "./ai/playSubscriptionVerifier";
import { hashObfuscatedAccountId, translatePlaySubscription } from "./ai/playSubscriptionVerifier";
import type { SubscriptionEntitlementRepository } from "./ai/subscriptionEntitlementRepository";

export type VerifyAiSubscriptionRequest = Readonly<{
  auth?: CallableAuth;
  data: unknown;
}>;

export type VerifyAiSubscriptionResponse = Readonly<{
  status: "VERIFIED";
  enabled: boolean;
  expiresAtEpochMillis: number;
}>;

export type VerifyAiSubscriptionFailure = Readonly<{
  code: "unauthenticated" | "permission-denied" | "invalid-argument" | "unavailable";
  reason?: string;
}>;

export const SUPPORTED_AI_PRODUCT_ID = "cuentivo_ai_mensual";

export function createVerifyAiSubscriptionHandler(deps: {
  playApi: PlaySubscriptionApi;
  repository: SubscriptionEntitlementRepository;
  packageName: string;
  now: () => number;
  onRejected?: (reason: string) => void;
}): (request: VerifyAiSubscriptionRequest) => Promise<VerifyAiSubscriptionResponse | VerifyAiSubscriptionFailure> {
  return async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
      deps.onRejected?.("AI_AUTHENTICATION_REQUIRED");
      return { code: "unauthenticated", reason: "AI_AUTHENTICATION_REQUIRED" };
    }

    if (isAnonymousUser(request.auth)) {
      deps.onRejected?.("AI_ANONYMOUS_REJECTED");
      return { code: "permission-denied", reason: "AI_ANONYMOUS_REJECTED" };
    }

    const data = request.data as Record<string, unknown> | null | undefined;
    const purchaseToken = typeof data?.purchaseToken === "string" ? data.purchaseToken.trim() : "";
    const productId = typeof data?.productId === "string" ? data.productId.trim() : "";

    if (!purchaseToken || !productId || productId !== SUPPORTED_AI_PRODUCT_ID) {
      deps.onRejected?.("INVALID_PURCHASE_DATA");
      return { code: "invalid-argument", reason: "INVALID_PURCHASE_DATA" };
    }

    let playResponse;
    try {
      playResponse = await deps.playApi.getSubscription(deps.packageName, purchaseToken);
    } catch {
      deps.onRejected?.("PLAY_API_UNAVAILABLE");
      return { code: "unavailable", reason: "PLAY_API_UNAVAILABLE" };
    }

    const verification = translatePlaySubscription(playResponse, productId);
    if (verification.status === "INVALID") {
      deps.onRejected?.("PLAY_SUBSCRIPTION_INVALID");
      return { code: "invalid-argument", reason: "PLAY_SUBSCRIPTION_INVALID" };
    }
    if (verification.status === "UNAVAILABLE") {
      deps.onRejected?.("PLAY_API_UNAVAILABLE");
      return { code: "unavailable", reason: "PLAY_API_UNAVAILABLE" };
    }

    // Fail-closed verification of obfuscatedExternalAccountId for active/grace subscriptions.
    // Must strictly match SHA-256 hex digest of the authenticated UID.
    if (verification.enabled) {
      const expectedObfuscatedId = hashObfuscatedAccountId(uid);
      if (
        !verification.obfuscatedExternalAccountId ||
        verification.obfuscatedExternalAccountId !== expectedObfuscatedId
      ) {
        deps.onRejected?.("ACCOUNT_IDENTIFIER_MISMATCH");
        return { code: "permission-denied", reason: "ACCOUNT_IDENTIFIER_MISMATCH" };
      }
    }

    const recordResult = await deps.repository.recordSubscription({
      uid,
      purchaseToken,
      productId,
      enabled: verification.enabled,
      expiresAtEpochMillis: verification.expiresAtEpochMillis ?? 0,
      nowEpochMillis: deps.now(),
    });

    if (recordResult.status === "TOKEN_BOUND_TO_DIFFERENT_UID") {
      deps.onRejected?.("TOKEN_BOUND_TO_DIFFERENT_UID");
      return { code: "permission-denied", reason: "TOKEN_BOUND_TO_DIFFERENT_UID" };
    }

    if (recordResult.status === "STORAGE_ERROR") {
      deps.onRejected?.("STORAGE_ERROR");
      return { code: "unavailable", reason: "STORAGE_ERROR" };
    }

    return {
      status: "VERIFIED",
      enabled: verification.enabled,
      expiresAtEpochMillis: verification.expiresAtEpochMillis ?? 0,
    };
  };
}
