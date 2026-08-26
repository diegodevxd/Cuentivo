import * as crypto from "node:crypto";

export type PlaySubscriptionStatus =
  | "SUBSCRIPTION_STATE_ACTIVE"
  | "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"
  | "SUBSCRIPTION_STATE_ON_HOLD"
  | "SUBSCRIPTION_STATE_PAUSED"
  | "SUBSCRIPTION_STATE_CANCELED"
  | "SUBSCRIPTION_STATE_EXPIRED"
  | "SUBSCRIPTION_STATE_PENDING"
  | "SUBSCRIPTION_STATE_UNSPECIFIED";

export type PlaySubscriptionLineItem = Readonly<{
  productId: string;
  expiryTime?: string;
}>;

export type PlaySubscriptionV2Response = Readonly<{
  subscriptionState?: PlaySubscriptionStatus;
  lineItems?: readonly PlaySubscriptionLineItem[];
  externalAccountIdentifiers?: Readonly<{
    obfuscatedExternalAccountId?: string;
  }>;
}>;

export interface PlaySubscriptionApi {
  getSubscription(
    packageName: string,
    token: string,
  ): Promise<PlaySubscriptionV2Response | null>;
}

export type PlayVerificationResult =
  | { status: "VERIFIED"; enabled: true; expiresAtEpochMillis: number }
  | { status: "INACTIVE"; enabled: false; expiresAtEpochMillis?: number }
  | { status: "INVALID" }
  | { status: "UNAVAILABLE" };

/**
 * Computes a SHA-256 hex hash of the purchase token for Firestore document keys.
 * Ensures the raw token is never exposed in collection paths or logs.
 */
export function hashPurchaseToken(purchaseToken: string): string {
  return crypto.createHash("sha256").update(purchaseToken.trim()).digest("hex");
}

/**
 * Pure translation of Google Play purchases.subscriptionsv2 response into an internal verification result.
 *
 * Expiry time is parsed into an absolute epoch milliseconds timestamp. Active and in-grace-period
 * subscriptions result in enabled entitlement. Expired, canceled, paused, or hold subscriptions
 * result in inactive status without granting entitlement.
 */
export function translatePlaySubscription(
  response: PlaySubscriptionV2Response | null,
  expectedProductId: string,
): PlayVerificationResult {
  if (!response || typeof response !== "object") {
    return { status: "INVALID" };
  }

  const state = response.subscriptionState;
  const lineItems = response.lineItems;
  if (!Array.isArray(lineItems) || lineItems.length === 0) {
    return { status: "INVALID" };
  }

  const lineItem = lineItems.find((item) => item.productId === expectedProductId);
  if (!lineItem) {
    return { status: "INVALID" };
  }

  const expiryTimeStr = lineItem.expiryTime;
  if (!expiryTimeStr || typeof expiryTimeStr !== "string") {
    return { status: "INVALID" };
  }

  const expiresAtEpochMillis = Date.parse(expiryTimeStr);
  if (isNaN(expiresAtEpochMillis) || expiresAtEpochMillis <= 0) {
    return { status: "INVALID" };
  }

  if (state === "SUBSCRIPTION_STATE_ACTIVE" || state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") {
    return {
      status: "VERIFIED",
      enabled: true,
      expiresAtEpochMillis,
    };
  }

  return {
    status: "INACTIVE",
    enabled: false,
    expiresAtEpochMillis,
  };
}

/**
 * Factory for Play Subscription API. When external service credentials are not configured,
 * it fails closed safely.
 */
export function createDefaultPlaySubscriptionApi(
  customFetcher?: (url: string, headers: Record<string, string>) => Promise<unknown>,
): PlaySubscriptionApi {
  return {
    async getSubscription(packageName: string, token: string): Promise<PlaySubscriptionV2Response | null> {
      if (customFetcher) {
        const response = await customFetcher(
          `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/subscriptionsv2/tokens/${token}`,
          {},
        );
        return response as PlaySubscriptionV2Response;
      }
      throw new Error("Play Developer API service account not configured in this environment");
    },
  };
}
